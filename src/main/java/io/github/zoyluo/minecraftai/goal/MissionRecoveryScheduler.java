package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.SettledServiceAuthority;
import io.github.zoyluo.minecraftai.goal.GoalExecutor.ActivePlan;
import io.github.zoyluo.minecraftai.goal.GoalExecutor.CapacityParentNamespace;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.SettledServiceTombstone;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningMissionBudget;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.task.MiningServiceTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.ResupplyTask;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

/**
 * Decides how a failed or completed mining/service step in {@link GoalExecutor} is resumed,
 * retried or committed. Extracted out of {@code GoalExecutor.handleStepFailure} (and its
 * {@code tickBot} completion-settlement call sites) because this cluster forms one cohesive
 * concern: mid-mission service/replan failure recovery for the durable OreDig and MiningService
 * checkpoints, operating on a narrow set of {@link ActivePlan} fields plus {@code bot}/{@code
 * reason}.
 *
 * <p>This class only ever mutates the {@link ActivePlan} it is handed. {@link GoalExecutor} keeps
 * ownership of {@code activePlans}, {@code finishActive} and the top-level dispatch; every
 * decision here is delegated to from {@code handleStepFailure}/{@code tickBot} in the exact same
 * order those methods evaluated it before this extraction, so their combined decision order is
 * unchanged.</p>
 */
final class MissionRecoveryScheduler {
    private final GoalExecutor executor;

    MissionRecoveryScheduler(GoalExecutor executor) {
        this.executor = executor;
    }

    static Optional<MiningServiceTask.RestoreMetadata> settledTerminalServiceFailure(
            ActivePlan plan, String reason) {
        if (plan == null || plan.getCurrent() == null
                || plan.getCurrent().kind() != GoalStep.Kind.MINING_SERVICE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINING_SERVICE
                || GoalExecutor.hasActiveServicePocket(plan.getTaskCheckpoint())) {
            return Optional.empty();
        }
        return MiningServiceTask.inspectCheckpoint(plan.getTaskCheckpoint())
                .filter(metadata -> !metadata.done()
                        && !metadata.terminalFailure().isBlank()
                        && metadata.terminalFailure().equals(reason));
    }

    /**
     * A generic background resupply can finish after OreDig has already published its typed tool
     * failure. Replanning the whole parent goal at that point is both unnecessary and harmful: an
     * underground obsidian dependency would be asked to re-establish every surface bootstrap
     * reserve even though the exact ore branch now has a usable replacement pickaxe. Resume only
     * the attested open batch, preserving its physical ledgers, cursor and already-spent hard
     * budget. Any identity mismatch falls through to the ordinary fail-closed planner path.
     */
    static boolean resumeOreDigAfterToolRecovery(AIPlayerEntity bot,
                                                  ActivePlan plan,
                                                  String reason) {
        if (plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        int rareMissionTarget = GoalExecutor.rareMissionTargetForMiningStep(
                plan.getGoal(), plan.getCurrent().ores());
        String expectedTargetReason = "need_better_tool:"
                + ToolTier.requiredPickaxeItemId(plan.getCurrent().ores());
        boolean targetToolRecovered = expectedTargetReason.equals(reason);
        boolean channelToolRecovered = rareMissionTarget == 0
                && hasRecoveredMiningChannelTool(bot, reason);
        if ((!targetToolRecovered && !channelToolRecovered)
                || plan.getCurrent().ores().stream().noneMatch(block ->
                ToolTier.canHarvestWithInventory(bot, block.defaultBlockState()))) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> restored = OreDigTask.inspectCheckpoint(
                plan.getTaskCheckpoint(), rareMissionTarget);
        if (restored.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata metadata = restored.orElseThrow();
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.getCurrent().ores());
        if (!metadata.batchOpen()
                || !metadata.acceptsStepTarget(plan.getCurrent().count())
                || metadata.rareMissionTarget() != rareMissionTarget
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(metadata.ores()))) {
            return false;
        }

        Optional<Task> resumed = GoalExecutor.stepToTask(bot, plan.getCurrent(), plan);
        if (resumed.isEmpty() || !(resumed.orElseThrow() instanceof OreDigTask)) {
            return false;
        }
        plan.setCurrentTask(resumed.orElseThrow());
        BotLog.task(bot, "goal_step_retry_after_tool_recovery",
                "step", plan.getCurrent().describe(),
                "mission_id", plan.getMissionId(),
                "budget_used", metadata.budgetUsed(),
                "face", metadata.cursor().face().toShortString());
        GoalExecutor.captureBeforeAndAfterDispatch(
                () -> GoalExecutor.markDirty(bot),
                () -> TaskManager.INSTANCE.assign(bot, plan.getCurrentTask(),
                        TaskOrigin.mission(plan.getMissionId(), plan.getCurrent().describe())));
        return true;
    }

    private static boolean hasRecoveredMiningChannelTool(AIPlayerEntity bot, String reason) {
        String prefix = "need_mining_channel_tool:";
        if (reason == null || !reason.startsWith(prefix)) {
            return false;
        }
        String requiredId = reason.substring(prefix.length());
        if (requiredId.isBlank()) {
            return false;
        }
        return java.util.stream.Stream.concat(
                        bot.getInventory().getNonEquipmentItems().stream(),
                        java.util.stream.Stream.of(bot.getItemBySlot(EquipmentSlot.OFFHAND)))
                .anyMatch(stack -> !stack.isEmpty()
                        && requiredId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        && MiningServiceTask.usableDurability(stack) > 0);
    }

    static boolean isLongRareChannelToolFailure(ActivePlan plan, String reason) {
        return plan.getCurrent() != null
                && plan.getCurrent().kind() == GoalStep.Kind.MINE_ORE
                && GoalExecutor.originalLongRareOreTargetCount(plan.getGoal())
                >= MiningBudget.EXPEDITION_THRESHOLD
                && "need_mining_channel_tool:minecraft:stone_pickaxe".equals(reason)
                && GoalExecutor.miningStepFeedsGoal(plan.getGoal(), plan.getCurrent().ores());
    }

    static boolean isLongRareResourceEpochTimeout(ActivePlan plan, String reason) {
        if (plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        int missionTarget = GoalExecutor.originalLongRareOreTargetCount(plan.getGoal());
        return OreDigTask.inspectCheckpoint(plan.getTaskCheckpoint(), missionTarget)
                .filter(ore -> ore.rareMissionTarget() == missionTarget
                        && GoalExecutor.miningStepFeedsGoal(plan.getGoal(), ore.ores()))
                .filter(ore -> isLongRareResourceEpochTimeout(ore, reason))
                .isPresent();
    }

    static boolean isLongRareResourceEpochTimeout(OreDigTask.RestoreMetadata ore,
                                                  String reason) {
        if (ore == null || reason == null
                || !reason.startsWith("ore_dig_timeout collected=")
                || !ore.batchOpen()
                || ore.rareMissionTarget() < MiningBudget.EXPEDITION_THRESHOLD) {
            return false;
        }
        int epochCapacity = MiningBudget.rareMissionResourceEpochCapacity(
                MiningBudget.rareMissionBatchCount(ore.rareMissionTarget()));
        if (ore.resourceEpoch() < 0 || ore.resourceEpoch() >= epochCapacity) {
            return false;
        }
        return ore.budgetUsed()
                == MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(
                ore.resourceEpoch(), epochCapacity);
    }

    /**
     * An ordinary OreDig can discover channel-tool exhaustion after it has switched its hand to a
     * torch or another utility item. In that state the generic low-durability watcher has no
     * factual held pickaxe to observe. Restore and pause the exact open batch, run one physical
     * ResupplyTask, then let the normal pause stack resume that same task instance. The checkpoint
     * debit makes this bounded across retries and process restarts.
     */
    static boolean scheduleOrdinaryChannelToolResupply(AIPlayerEntity bot,
                                                        ActivePlan plan,
                                                        String reason) {
        String prefix = "need_mining_channel_tool:";
        if (plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE
                || GoalExecutor.rareMissionTargetForMiningStep(
                        plan.getGoal(), plan.getCurrent().ores()) != 0
                || reason == null || !reason.startsWith(prefix)) {
            return false;
        }
        String requiredId = reason.substring(prefix.length());
        Item requested;
        try {
            requested = BuiltInRegistries.ITEM.getOptional(Identifier.parse(requiredId)).orElse(null);
        } catch (RuntimeException invalidIdentifier) {
            return false;
        }
        if (requested == null) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.getTaskCheckpoint(), 0);
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.getCurrent().ores());
        if (metadata.isEmpty()
                || !metadata.orElseThrow().batchOpen()
                || !metadata.orElseThrow().acceptsStepTarget(plan.getCurrent().count())
                || metadata.orElseThrow().rareMissionTarget() != 0
                || metadata.orElseThrow().inventoryServiceUsed()
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(
                metadata.orElseThrow().ores()))
                || plan.getCurrent().ores().stream().noneMatch(block ->
                ToolTier.canHarvestWithInventory(bot, block.defaultBlockState()))) {
            return false;
        }
        Optional<Map<String, String>> debited =
                OreDigTask.debitChannelToolResupply(plan.getTaskCheckpoint());
        if (debited.isEmpty()) {
            return false;
        }
        plan.getTaskCheckpoint().clear();
        plan.getTaskCheckpoint().putAll(debited.orElseThrow());
        if (expectedFingerprint.equals(plan.getMiningCheckpoint().get("ore_fingerprint"))) {
            plan.getMiningCheckpoint().clear();
            plan.getMiningCheckpoint().putAll(plan.getTaskCheckpoint());
        }
        Optional<Task> resumed = GoalExecutor.stepToTask(bot, plan.getCurrent(), plan);
        if (resumed.isEmpty() || !(resumed.orElseThrow() instanceof OreDigTask)) {
            return false;
        }
        plan.setCurrentTask(resumed.orElseThrow());
        GoalExecutor.captureBeforeAndAfterDispatch(
                () -> GoalExecutor.markDirty(bot),
                () -> {
                    TaskManager.INSTANCE.assign(bot, plan.getCurrentTask(),
                            TaskOrigin.mission(plan.getMissionId(), plan.getCurrent().describe()));
                    TaskManager.INSTANCE.pauseFor(bot, "ore_channel_tool_resupply");
                    TaskManager.INSTANCE.assign(bot, ResupplyTask.tool(requested),
                            TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND,
                                    "ore_channel_tool_resupply"));
                });
        BotLog.task(bot, "goal_ore_channel_resupply_scheduled",
                "required", requiredId,
                "mission_id", plan.getMissionId(),
                "budget_used", metadata.orElseThrow().budgetUsed(),
                "face", metadata.orElseThrow().cursor().face().toShortString());
        return true;
    }

    /**
     * Atomically trades this exact open batch's single resource retry — or, once that is spent,
     * one bounded mission-level margin epoch (F2) — for one fresh rare-service step while
     * preserving the OreDig hard budget, cursor and physical pickup/break ledgers.
     */
    boolean scheduleRareResourceRetry(AIPlayerEntity bot,
                                      ActivePlan plan,
                                      String reason) {
        if (plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.getTaskCheckpoint(),
                        GoalExecutor.originalLongRareOreTargetCount(plan.getGoal()));
        if (metadata.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata ore = metadata.orElseThrow();
        boolean torchFailure = reason.equals(OreDigTask.resourceEpochFailureReason(
                ore.torchPlacements(), ore.resourceEpoch()));
        boolean channelToolFailure = isLongRareChannelToolFailure(plan, reason);
        boolean epochTimeout = isLongRareResourceEpochTimeout(ore, reason);
        // Epochs beyond the per-batch retry are paid from the finite mission margin pool; the
        // durable ledger below makes every draw exactly-once across replans and restarts.
        int marginPool = GoalExecutor.rareMissionEpochMarginPool(plan.getGoal());
        boolean marginDraw = ore.resourceEpoch()
                >= MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH;
        if (!ore.batchOpen()
                || ore.rareMissionTarget() != GoalExecutor.originalLongRareOreTargetCount(plan.getGoal())
                || plan.getRareResourceRetriesUsed() != ore.resourceEpoch()
                || marginDraw && plan.getRareEpochMarginUsed() >= marginPool
                || (!torchFailure && !channelToolFailure && !epochTimeout)
                || !GoalExecutor.miningStepFeedsGoal(plan.getGoal(), ore.ores())) {
            return false;
        }
        Optional<Map<String, String>> advanced =
                OreDigTask.advanceResourceEpoch(plan.getTaskCheckpoint());
        if (advanced.isEmpty()) {
            return false;
        }

        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.getGoal(), GoalExecutor.snapshotContext(plan), plan.getMissionId().toString());
        if (!fresh.success()) {
            return false;
        }
        List<GoalStep> continuation = new ArrayList<>(GoalExecutor.applySkippedTargetReceipts(
                fresh.steps(), plan.getSkippedTargetReceipts()));
        String fingerprint = OreDigTask.oreFingerprint(ore.ores());
        int serviceBoundary = GoalExecutor.goalTargetCount(bot, plan.getGoal());
        int serviceIndex = -1;
        int miningIndex = -1;
        for (int index = 0; index < continuation.size(); index++) {
            GoalStep step = continuation.get(index);
            if (serviceIndex < 0 && step.isRareOreService()
                    && step.count() == serviceBoundary
                    && step.rareOreMissionTarget()
                    == GoalExecutor.originalLongRareOreTargetCount(plan.getGoal())
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                serviceIndex = index;
            } else if (miningIndex < 0 && step.kind() == GoalStep.Kind.MINE_ORE
                    && ore.acceptsStepTarget(step.count())
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                miningIndex = index;
            }
        }
        if (miningIndex < 0) {
            return false;
        }
        GoalStep service = serviceIndex >= 0
                ? continuation.get(serviceIndex)
                : GoalStep.rareOreService(
                ore.ores(), serviceBoundary,
                GoalExecutor.originalLongRareOreTargetCount(plan.getGoal()));
        GoalStep mining = continuation.get(miningIndex);
        reorderServiceThenMining(continuation, serviceIndex, miningIndex, service, mining);

        // Commit only after the complete successor schedule has been proven. A crash can therefore
        // observe either the failed epoch plus its task, or the advanced epoch plus the
        // service-first schedule (margin ledger included), never a refreshed cursor with no
        // corresponding epoch or margin debit.
        plan.getTaskCheckpoint().clear();
        plan.getTaskCheckpoint().putAll(advanced.orElseThrow());
        plan.setTaskCheckpointKind(GoalStep.Kind.MINE_ORE);
        plan.getMiningCheckpoint().clear();
        plan.getMiningCheckpoint().putAll(advanced.orElseThrow());
        plan.setRareResourceRetriesUsed(ore.resourceEpoch() + 1);
        if (marginDraw) {
            plan.setRareEpochMarginUsed(plan.getRareEpochMarginUsed() + 1);
            BotLog.task(bot, "rare_epoch_margin_drawn",
                    "used", plan.getRareEpochMarginUsed(),
                    "pool", marginPool,
                    "epoch", plan.getRareResourceRetriesUsed());
        }
        plan.getSteps().clear();
        plan.getSteps().addAll(continuation);
        plan.setTotalSteps(continuation.size());
        plan.setCurrent(null);
        plan.setCurrentTask(null);
        BotLog.task(bot, "goal_rare_resource_epoch_advanced",
                "epoch", plan.getRareResourceRetriesUsed(),
                "trigger", channelToolFailure ? "channel_tool"
                        : epochTimeout ? "epoch_timeout" : "torch",
                "budget", ore.budgetUsed(),
                "face", ore.cursor().face().toShortString(),
                "service_boundary", service.count());
        executor.captureTransitionAndAssignNext(bot, plan);
        return true;
    }

    /** Schedules the one sealed inventory service owned by this exact OreDig batch. */
    boolean scheduleRareInventoryService(AIPlayerEntity bot, ActivePlan plan) {
        int missionTarget = GoalExecutor.originalLongRareOreTargetCount(plan.getGoal());
        if (plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || missionTarget < MiningBudget.EXPEDITION_THRESHOLD) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.getTaskCheckpoint(), missionTarget);
        if (metadata.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata ore = metadata.orElseThrow();
        if (!ore.batchOpen() || ore.rareMissionTarget() != missionTarget
                || !GoalExecutor.miningStepFeedsGoal(plan.getGoal(), ore.ores())) {
            return false;
        }
        Optional<Map<String, String>> debited =
                OreDigTask.debitInventoryService(plan.getTaskCheckpoint());
        if (debited.isEmpty()) {
            return false;
        }

        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.getGoal(), GoalExecutor.snapshotContext(plan), plan.getMissionId().toString());
        if (!fresh.success()) {
            return false;
        }
        List<GoalStep> continuation = new ArrayList<>(GoalExecutor.applySkippedTargetReceipts(
                fresh.steps(), plan.getSkippedTargetReceipts()));
        String fingerprint = OreDigTask.oreFingerprint(ore.ores());
        int serviceIndex = -1;
        int miningIndex = -1;
        for (int index = 0; index < continuation.size(); index++) {
            GoalStep step = continuation.get(index);
            if (serviceIndex < 0 && step.isRareOreService()
                    && step.rareOreMissionTarget() == missionTarget
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                serviceIndex = index;
            } else if (miningIndex < 0 && step.kind() == GoalStep.Kind.MINE_ORE
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                miningIndex = index;
            }
        }
        if (serviceIndex < 0 || miningIndex < 0) {
            return false;
        }
        GoalStep service = continuation.get(serviceIndex);
        GoalStep mining = continuation.get(miningIndex);
        reorderServiceThenMining(continuation, serviceIndex, miningIndex, service, mining);

        plan.getTaskCheckpoint().clear();
        plan.getTaskCheckpoint().putAll(debited.orElseThrow());
        plan.setTaskCheckpointKind(GoalStep.Kind.MINE_ORE);
        plan.getMiningCheckpoint().clear();
        plan.getMiningCheckpoint().putAll(debited.orElseThrow());
        plan.getSteps().clear();
        plan.getSteps().addAll(continuation);
        plan.setTotalSteps(continuation.size());
        plan.setCurrent(null);
        plan.setCurrentTask(null);
        BotLog.task(bot, "goal_rare_inventory_service_scheduled",
                "budget", ore.budgetUsed(),
                "face", ore.cursor().face().toShortString(),
                "service_boundary", service.count());
        executor.captureTransitionAndAssignNext(bot, plan);
        return true;
    }

    /**
     * Pulls the service+mining step pair to the front of {@code steps}, service before mining.
     * {@code serviceIndex} may be -1 (no existing service step to remove; only mining is dropped
     * before both are reinserted at the head). Removing the larger index first keeps the smaller
     * index valid for the second removal.
     */
    private static void reorderServiceThenMining(List<GoalStep> steps, int serviceIndex, int miningIndex,
                                                  GoalStep service, GoalStep mining) {
        if (serviceIndex >= 0) {
            if (serviceIndex > miningIndex) {
                steps.remove(serviceIndex);
                steps.remove(miningIndex);
            } else {
                steps.remove(miningIndex);
                steps.remove(serviceIndex);
            }
        } else {
            steps.remove(miningIndex);
        }
        steps.add(0, mining);
        steps.add(0, service);
    }

    /**
     * Inserts a capacity-only ORE_BATCH service before retrying the exact failed ordinary or
     * small-rare OreDig step. A first service debits the shared inventory-service bit. A later
     * service must retain that bit and strictly advance either the delivered watermark or the
     * factual branch work face. The persisted service count is capped by the original target
     * count, matching MiningMissionBudget's exact dynamic-service upper bound across restarts.
     */
    boolean scheduleBoundedCapacityHandoff(AIPlayerEntity bot, ActivePlan plan) {
        if (plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        boolean primaryParent = plan.getTaskCheckpoint().equals(plan.getMiningCheckpoint())
                && plan.getAuxiliaryMiningCheckpoint().isEmpty();
        boolean existingAuxiliaryParent = plan.getTaskCheckpoint().equals(
                plan.getAuxiliaryMiningCheckpoint())
                && !plan.getAuxiliaryMiningCheckpoint().isEmpty();
        boolean protectedRareParent = GoalExecutor.isProtectedRareMiningCheckpoint(
                plan.getGoal(), plan.getMiningCheckpoint())
                && !GoalExecutor.hasOreDigPhysicalLedger(plan.getMiningCheckpoint());
        boolean createAuxiliaryParent = plan.getCapacityParentNamespace() == null
                && !primaryParent && !existingAuxiliaryParent
                && plan.getAuxiliaryMiningCheckpoint().isEmpty() && protectedRareParent;
        if (!primaryParent && !existingAuxiliaryParent && !createAuxiliaryParent) {
            return false;
        }
        Map<String, String> parentCheckpoint = primaryParent
                ? plan.getMiningCheckpoint()
                : existingAuxiliaryParent
                ? plan.getAuxiliaryMiningCheckpoint() : plan.getTaskCheckpoint();
        Optional<OreDigTask.RestoreMetadata> task =
                OreDigTask.inspectCheckpoint(plan.getTaskCheckpoint(), 0);
        Optional<OreDigTask.RestoreMetadata> parent =
                OreDigTask.inspectCheckpoint(parentCheckpoint, 0);
        if (task.isEmpty() || parent.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata ore = task.orElseThrow();
        OreDigTask.RestoreMetadata durable = parent.orElseThrow();
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.getCurrent().ores());
        CapacityParentNamespace expectedParent = primaryParent
                ? CapacityParentNamespace.MINING : CapacityParentNamespace.AUXILIARY;
        boolean repeatedHandoff = plan.getCapacityParentNamespace() != null;
        boolean branchAdvanced = plan.getCapacityParentFace() != null
                && !ore.cursor().face().equals(plan.getCapacityParentFace());
        boolean validHandoffProgress = repeatedHandoff
                ? plan.getCapacityParentNamespace() == expectedParent
                && plan.getCapacityParentDelivered() >= 0
                && plan.getCapacityParentFace() != null
                && plan.getCapacityParentServicesUsed() >= 1
                && plan.getCapacityParentServicesUsed() < ore.targetCount()
                && ore.inventoryServiceUsed() && durable.inventoryServiceUsed()
                && (ore.delivered() > plan.getCapacityParentDelivered() || branchAdvanced)
                : plan.getCapacityParentDelivered() == -1
                && plan.getCapacityParentFace() == null
                && plan.getCapacityParentServicesUsed() == 0
                && !ore.inventoryServiceUsed() && !durable.inventoryServiceUsed();
        if (!ore.batchOpen() || !durable.batchOpen()
                || ore.rareMissionTarget() != 0 || durable.rareMissionTarget() != 0
                || !validHandoffProgress
                || !ore.acceptsStepTarget(plan.getCurrent().count())
                || !durable.acceptsStepTarget(plan.getCurrent().count())
                || ore.delivered() != durable.delivered()
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(ore.ores()))
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(durable.ores()))
                || !ore.cursor().equals(durable.cursor())
                || GoalExecutor.hasOreDigPhysicalLedger(plan.getTaskCheckpoint())
                || GoalExecutor.hasOreDigPhysicalLedger(parentCheckpoint)) {
            return false;
        }
        Optional<Map<String, String>> debit =
                OreDigTask.debitCapacityHandoff(
                        plan.getTaskCheckpoint(), plan.getCapacityParentDelivered(),
                        plan.getCapacityParentFace());
        if (debit.isEmpty()) {
            return false;
        }

        Map<String, String> debited = debit.orElseThrow();
        GoalStep retry = plan.getCurrent();
        GoalStep service = GoalStep.miningHandoffService(
                retry.ores(), Math.max(1, ore.delivered()),
                GoalExecutor.miningParentStoneLikeReserve(
                        plan.getGoal(), 0, plan.getMiningCheckpoint()));
        plan.getTaskCheckpoint().clear();
        plan.getTaskCheckpoint().putAll(debited);
        plan.setTaskCheckpointKind(GoalStep.Kind.MINE_ORE);
        plan.setCapacityParentDelivered(ore.delivered());
        plan.setCapacityParentFace(ore.cursor().face());
        plan.setCapacityParentServicesUsed(plan.getCapacityParentServicesUsed() + 1);
        if (primaryParent) {
            plan.getMiningCheckpoint().clear();
            plan.getMiningCheckpoint().putAll(debited);
            plan.setCapacityParentNamespace(CapacityParentNamespace.MINING);
        } else {
            plan.getAuxiliaryMiningCheckpoint().clear();
            plan.getAuxiliaryMiningCheckpoint().putAll(debited);
            plan.setCapacityParentNamespace(CapacityParentNamespace.AUXILIARY);
        }
        plan.getSteps().addFirst(retry);
        plan.getSteps().addFirst(service);
        plan.setTotalSteps(plan.getTotalSteps() + 1);
        plan.getStepLabels().add(service.describe());
        plan.setCurrent(null);
        plan.setCurrentTask(null);
        BotLog.task(bot, "goal_mining_capacity_handoff_scheduled",
                "budget", ore.budgetUsed(),
                "face", ore.cursor().face().toShortString(),
                "stone_reserve", service.miningHandoffStoneLikeReserve(),
                "delivered_watermark", plan.getCapacityParentDelivered(),
                "face_watermark", plan.getCapacityParentFace().toShortString(),
                "services_used", plan.getCapacityParentServicesUsed(),
                "service_limit", ore.targetCount(),
                "repeat", repeatedHandoff,
                "ore_fingerprint", expectedFingerprint);
        executor.captureTransitionAndAssignNext(bot, plan);
        return true;
    }

    /** Releases the retry debit only after both durable namespaces attest the closed rare batch. */
    static boolean settleCompletedRareBatch(ActivePlan plan) {
        int missionTarget = GoalExecutor.originalLongRareOreTargetCount(plan.getGoal());
        if (missionTarget < MiningBudget.EXPEDITION_THRESHOLD
                || plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE
                || !plan.getTaskCheckpoint().equals(plan.getMiningCheckpoint())) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> task = OreDigTask.inspectCheckpoint(
                plan.getTaskCheckpoint(), missionTarget);
        Optional<OreDigTask.RestoreMetadata> mining = OreDigTask.inspectCheckpoint(
                plan.getMiningCheckpoint(), missionTarget);
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.getCurrent().ores());
        if (task.isEmpty() || mining.isEmpty()
                || task.orElseThrow().batchOpen()
                || mining.orElseThrow().batchOpen()
                || task.orElseThrow().resourceEpoch() != 0
                || mining.orElseThrow().resourceEpoch() != 0
                || task.orElseThrow().rareMissionTarget() != missionTarget
                || !expectedFingerprint.equals(
                OreDigTask.oreFingerprint(task.orElseThrow().ores()))) {
            return false;
        }
        // The epoch counter is batch-scoped and resets with the closed commit. The margin ledger
        // (plan.rareEpochMarginUsed) is deliberately NOT reset here: it is mission-scoped and a
        // committed batch must not refund margin epochs the mission has already spent.
        plan.setRareResourceRetriesUsed(0);
        return true;
    }

    /** Distinguishes the debited parent retry from a different-family repair OreDig task. */
    static boolean currentOreTaskOwnsCapacityParent(ActivePlan plan) {
        if (plan == null || plan.getCurrent() == null
                || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE
                || plan.getCapacityParentNamespace() == null
                || !plan.isCurrentCapacityParentRetry()) {
            return false;
        }
        Map<String, String> parent = plan.getCapacityParentNamespace()
                == CapacityParentNamespace.AUXILIARY
                ? plan.getAuxiliaryMiningCheckpoint() : plan.getMiningCheckpoint();
        return !parent.isEmpty() && parent.equals(plan.getTaskCheckpoint())
                && java.util.Objects.equals(parent.get("ore_fingerprint"),
                OreDigTask.oreFingerprint(plan.getCurrent().ores()));
    }

    /** Closes the dynamic service debit only after its exact OreDig retry commits the batch. */
    static boolean settleCompletedCapacityParent(ActivePlan plan) {
        if (plan.getCurrent() == null || plan.getCurrent().kind() != GoalStep.Kind.MINE_ORE
                || plan.getTaskCheckpointKind() != GoalStep.Kind.MINE_ORE
                || plan.getCapacityParentNamespace() == null) {
            return false;
        }
        Map<String, String> parentCheckpoint = plan.getCapacityParentNamespace()
                == CapacityParentNamespace.AUXILIARY
                ? plan.getAuxiliaryMiningCheckpoint() : plan.getMiningCheckpoint();
        Optional<OreDigTask.RestoreMetadata> parent =
                OreDigTask.inspectCheckpoint(parentCheckpoint, 0);
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.getCurrent().ores());
        if (parent.isEmpty() || parent.orElseThrow().batchOpen()
                || parent.orElseThrow().rareMissionTarget() != 0
                || plan.getCapacityParentDelivered() < 0
                || plan.getCapacityParentDelivered() > parent.orElseThrow().targetCount()
                || plan.getCapacityParentFace() == null
                || plan.getCapacityParentServicesUsed() < 1
                || plan.getCapacityParentServicesUsed() > parent.orElseThrow().targetCount()
                || !parentCheckpoint.equals(plan.getTaskCheckpoint())
                || !expectedFingerprint.equals(
                OreDigTask.oreFingerprint(parent.orElseThrow().ores()))
                || GoalExecutor.hasOreDigPhysicalLedger(parentCheckpoint)) {
            return false;
        }
        CapacityParentNamespace completedParent = plan.getCapacityParentNamespace();
        plan.setCapacityParentNamespace(null);
        plan.setCapacityParentDelivered(-1);
        plan.setCapacityParentFace(null);
        plan.setCapacityParentServicesUsed(0);
        if (completedParent == CapacityParentNamespace.AUXILIARY) {
            GoalStep successor = plan.getSteps().peekFirst();
            boolean sameFamilyService = successor != null
                    && successor.kind() == GoalStep.Kind.MINING_SERVICE
                    && expectedFingerprint.equals(
                    OreDigTask.oreFingerprint(successor.ores()));
            if (!sameFamilyService) {
                plan.getAuxiliaryMiningCheckpoint().clear();
                plan.setAuxiliaryMiningContinuationFingerprint("");
            }
        }
        return true;
    }

    private static Optional<SettledServiceAuthority> liveServiceAuthority(
            AIPlayerEntity bot, MiningServiceTask.RestoreMetadata metadata) {
        if (bot == null || metadata == null
                || !bot.level().dimension().identifier().toString()
                .equals(metadata.serviceDimension())) {
            return Optional.empty();
        }
        return GoalExecutor.persistedServiceAuthority(metadata);
    }

    static Optional<SettledServiceTombstone> matchingSettledServiceGuard(
            AIPlayerEntity bot, ActivePlan plan, String reason) {
        if (bot == null || plan == null || plan.getCurrent() == null
                || plan.getCurrent().kind() != GoalStep.Kind.MINING_SERVICE
                || !MiningServiceTask.validTerminalFailureReason(reason)) {
            return Optional.empty();
        }
        Optional<MiningServiceTask.RestoreMetadata> metadata =
                MiningServiceTask.inspectCheckpoint(plan.getTaskCheckpoint());
        Optional<SettledServiceAuthority> authority = metadata
                .flatMap(value -> liveServiceAuthority(bot, value));
        return authority.flatMap(current -> plan.getSettledServiceTombstones().values()
                .stream()
                .filter(settled -> settled.failureReason().equals(reason)
                        && settled.sameGeometry(current))
                .findFirst());
    }
}
