package io.github.zoyluo.minecraftai.goal;

import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.activeTaskCheckpoint;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.canonicalNonNegativeInt;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodeBatchCheckpoint;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodeCompletedDeliveries;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodeHuntSearchCursorNamespace;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodePersistedMissionCounter;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodePos;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodePostconditionRepairCheckpoint;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodeReplanSnapshot;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodeSettledServiceTombstones;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodeSkippedTargetReceipts;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.decodeStepKind;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.encodeBatchCheckpoint;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.encodeCompletedDeliveries;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.encodeHuntSearchCursorNamespace;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.encodePos;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.encodePostconditionRepairCheckpoint;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.encodeSkippedTargetReceipts;
import static io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.nonNegativeInt;

import io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.SettledServiceAuthority;
import io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.SettledServiceDescriptor;
import io.github.zoyluo.minecraftai.goal.GoalCheckpointCodec.SettledServiceTombstone;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.brain.BotReporter;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.task.BlueprintLoader;
import io.github.zoyluo.minecraftai.task.BlueprintSchema;
import io.github.zoyluo.minecraftai.task.BuildTask;
import io.github.zoyluo.minecraftai.task.CheckpointableTask;
import io.github.zoyluo.minecraftai.task.CraftTask;
import io.github.zoyluo.minecraftai.task.CreateObsidianTask;
import io.github.zoyluo.minecraftai.task.DescendToYTask;
import io.github.zoyluo.minecraftai.task.DigDownTask;
import io.github.zoyluo.minecraftai.task.FarmTask;
import io.github.zoyluo.minecraftai.task.GatherQuotaTask;
import io.github.zoyluo.minecraftai.task.GiveItemTask;
import io.github.zoyluo.minecraftai.task.HuntSearchCursor;
import io.github.zoyluo.minecraftai.task.HuntPickupCheckpoint;
import io.github.zoyluo.minecraftai.task.HuntTask;
import io.github.zoyluo.minecraftai.task.MilkCowTask;
import io.github.zoyluo.minecraftai.task.MiningServiceTask;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.MoveTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.PlaceStationsTask;
import io.github.zoyluo.minecraftai.task.RetiredNavigationTask;
import io.github.zoyluo.minecraftai.task.ServicePolicy;
import io.github.zoyluo.minecraftai.task.ServiceProfile;
import io.github.zoyluo.minecraftai.task.SmeltTask;
import io.github.zoyluo.minecraftai.task.StockpileTask;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskState;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.persist.MissionRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import io.github.zoyluo.minecraftai.persist.MissionSpec.ExecutionMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GoalExecutor {
    public static final GoalExecutor INSTANCE = new GoalExecutor();
    /**
     * A goal step is a meaningful, bounded operation (for example gather a quota, craft a
     * recipe, or mine a declared batch), not one game tick. Ten such steps gives a player a
     * useful review point without interrupting normal bootstrap goals such as making a stone
     * pickaxe from scratch.
     */
    public static final int DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT = 10;
    /** One whole, safe goal stage per adaptive strategy decision. */
    static final int ADAPTIVE_STRATEGY_STEP_LIMIT = 1;
    private static final int BASE_LIFETIME_REPLAN_LIMIT = 12;
    private static final int MAX_CONSECUTIVE_REPLANS = 3;
    // Package-private: also read by GoalCheckpointCodec's postcondition-repair codec.
    static final int MAX_POSTCONDITION_REPLANS = 3;
    // Package-private: also read by GoalCheckpointCodec's skipped-target-receipt codec.
    static final int MAX_SKIPPED_TARGET_RECEIPTS = 32;
    // Package-private: also read by GoalCheckpointCodec's canonical-text codec.
    static final int MAX_SKIPPED_TARGET_TEXT_BYTES = 8_192;
    // Checkpoint parsing needs a finite anti-corruption bound, but fulfillment planning itself
    // has no arbitrary small bundle-size or execution-call ceiling.
    static final int MAX_DELIVERY_RECEIPTS = 65_536;
    private static final String AUXILIARY_MINING_CONTINUATION_KEY =
            "aux_mining_continuation";
    private static final String CAPACITY_PARENT_DELIVERED_KEY =
            "capacity_parent_delivered";
    private static final String CAPACITY_PARENT_FACE_KEY =
            "capacity_parent_face";
    private static final String CAPACITY_PARENT_SERVICES_USED_KEY =
            "capacity_parent_services_used";
    private static final String SETTLED_SERVICE_PREFIX = "settled_service.";
    // Package-private: also read by GoalCheckpointCodec's settled-service-tombstone codec.
    static final int MAX_SETTLED_SERVICE_TOMBSTONES = 16;
    private static final Set<String> ACTIVE_SERVICE_POCKET_PHASES = Set.of(
            "OPEN_DISPOSAL_POCKET",
            "CAPTURE_DISPOSAL_BASELINE",
            "DROP_DISPOSABLE",
            "SETTLE_DISPOSABLE",
            "RETURN_TO_DISPOSAL_FACE",
            "SEAL_DISPOSAL_POCKET");
    private static final Set<String> SERVICE_POCKET_IDENTITY_KEYS = Set.of(
            "pocket_entry",
            "pocket_sink",
            "pocket_direction",
            "pocket_entities",
            "pocket_lineage",
            "pocket_baseline",
            "pocket_ledger",
            "pocket_drop_committed",
            "pocket_ledger_verified",
            "pocket_phase_started",
            "pocket_failure",
            "pocket_clear_index");

    private final Map<UUID, ActivePlan> activePlans = new ConcurrentHashMap<>();
    // P0 goal queue (foundation of the conversational assistant): a compound instruction like "get food first, then mine iron" needs sequential goals. Under the original single-plan model,
    // the second goal would be rejected/overwritten (the prompt even required "one at a time, wait for STOP before the next"). Now: when an active goal exists, a new goal is enqueued,
    // and once the current goal completes/fails it automatically dequeues the next one (like a real person: finish what's in hand, then move to the next thing; if it can't be done, say so and skip).
    private final Map<UUID, java.util.Deque<QueuedGoal>> goalQueue = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastGoalFailTick = new ConcurrentHashMap<>(); // Optimization 2: the tick when the goal failed overall, used to block the brain's subsequent manual block-by-block mining
    private final Map<UUID, Goal> userGoal = new ConcurrentHashMap<>(); // B: the user's original high-level goal, to prevent the brain from downgrading it to one of its prerequisite sub-goals (mine diamond -> make iron pickaxe)
    private final Map<UUID, GoalResult> lastResults = new ConcurrentHashMap<>();
    // Death is a recoverable interruption for long-running Missions. The exact Mission record is
    // detached while the safety-origin corpse run owns TaskManager, then restored with the same id.
    private final Map<UUID, MissionRuntimeRecord> deathSuspended = new ConcurrentHashMap<>();
    // Reuses the same durable detached-runtime container for a different gate: an open physical
    // service pocket may resume only after the bot returns to the checkpoint's exact dimension.
    private final Map<UUID, String> dimensionSuspended = new ConcurrentHashMap<>();
    // A malformed checkpoint that still claims physical pocket ownership cannot be executed or
    // discarded. Keep its complete MissionRuntimeRecord durable until explicit cancellation; a
    // later process restore will re-run the same fail-closed probe and rebuild this marker.
    private final Map<UUID, String> restoreQuarantined = new ConcurrentHashMap<>();
    // Semantic validation of a raw active pocket is transactional: restore failures must be
    // quarantined without first publishing an irreversible terminal result/chat/episode.
    private final Set<UUID> pocketRestorePreflights = ConcurrentHashMap.newKeySet();
    private final AtomicLong resultSequence = new AtomicLong();
    // Decides how a failed/completed mining or service step is resumed, retried or committed;
    // only ever mutates the ActivePlan it is handed. This executor keeps ownership of activePlans,
    // finishActive and the top-level dispatch.
    private final MissionRecoveryScheduler recoveryScheduler = new MissionRecoveryScheduler(this);

    private GoalExecutor() {
    }

    /**
     * A player-visible safe stopping point between deterministic goal steps.  The remaining
     * steps stay in the live plan; after a server restart the same goal is factually replanned
     * from the durable inventory/world state before this checkpoint is released.
     */
    public record BatchCheckpointStatus(boolean awaitingPlayer,
                                        int completedInBatch,
                                        int stepLimit,
                                        int remainingSteps,
                                        String nextStep) {
    }

    /**
     * An immutable, server-generated decision token for an adaptive mission.  The model can only
     * authorize the exact safe successor identified here; a stale callback cannot advance a newer
     * stage or a replacement mission.
     */
    public record StrategyCheckpointStatus(String missionId,
                                           long revision,
                                           String goal,
                                           int remainingSteps,
                                           String nextStep) {
    }

    public boolean submit(AIPlayerEntity bot, Goal goal) {
        return submit(bot, goal, null, ExecutionMode.STANDARD);
    }

    /**
     * Starts a mission whose semantic strategy is revisited after every safe completed stage.
     * Recipes, inventory math, navigation, and physical handoffs remain owned by this executor.
     */
    public boolean submitAdaptive(AIPlayerEntity bot, Goal goal) {
        return submit(bot, goal, null, ExecutionMode.ADAPTIVE);
    }

    /**
     * A detached runtime still owns the bot's user-intent authority. New submissions may extend
     * its durable queue, but must never allocate a second ActivePlan or Task while the suspended
     * checkpoint (especially an open disposal pocket) remains unresolved.
     */
    private Optional<Boolean> submitIntoSuspendedRuntime(AIPlayerEntity bot,
                                                           Goal goal,
                                                           ExecutionMode executionMode) {
        UUID uuid = bot.getUUID();
        while (true) {
            MissionRuntimeRecord suspended = deathSuspended.get(uuid);
            if (suspended == null) {
                return Optional.empty();
            }
            Optional<Goal> suspendedGoal = suspended.active() == null
                    || suspended.active().spec() == null
                    ? Optional.empty() : suspended.active().spec().toGoal();
            if (suspendedGoal.filter(candidate -> sameGoalIntent(candidate, goal)).isPresent()
                    || suspended.queue().stream().map(MissionSpec::toGoal)
                    .flatMap(Optional::stream).anyMatch(candidate -> sameGoalIntent(candidate, goal))) {
                BotLog.task(bot, "goal_submit_ignored", "goal", goal,
                        "reason", "duplicate_suspended_runtime");
                return Optional.of(true);
            }
            if (restoreQuarantined.containsKey(uuid)) {
                BotLog.task(bot, "goal_submit_rejected", "goal", goal,
                        "reason", "restore_quarantined_physical_pocket");
                report(bot, "The current mining-pocket checkpoint is isolated. Cancel this task before starting a new goal.");
                return Optional.of(false);
            }
            if (suspendedGoal.filter(parent -> isPrerequisiteOf(bot, goal, parent))
                    .isPresent()) {
                BotLog.task(bot, "goal_downgrade_blocked", "sub", goal,
                        "user", suspendedGoal.orElseThrow());
                report(bot, "This is a prerequisite for the current goal and will finish automatically after recovery.");
                return Optional.of(false);
            }
            List<MissionSpec> appended = new ArrayList<>(suspended.queue());
            appended.add(MissionSpec.fromGoal(goal, executionMode));
            MissionRuntimeRecord updated = new MissionRuntimeRecord(
                    suspended.active(), appended, suspended.userPaused());
            if (!deathSuspended.replace(uuid, suspended, updated)) {
                continue;
            }
            BotLog.task(bot, "goal_queued", "goal", goal,
                    "behind", suspendedGoal.map(String::valueOf).orElse("suspended_queue"),
                    "queue_size", appended.size(), "state", "suspended");
            report(bot, "Noted. After the current mining recovery is wrapped up, I will: " + goalLabel(goal));
            markDirty(bot);
            return Optional.of(true);
        }
    }

    private boolean submit(AIPlayerEntity bot,
                           Goal goal,
                           RestoreSeed restore,
                           ExecutionMode executionMode) {
        ExecutionMode mode = executionMode == null ? ExecutionMode.STANDARD : executionMode;
        Optional<Boolean> suspendedSubmission = submitIntoSuspendedRuntime(bot, goal, mode);
        if (suspendedSubmission.isPresent()) {
            return suspendedSubmission.orElseThrow();
        }
        // GOALFIX-GF3: idempotency -- when the same bot already has an active plan for the same goal, ignore the duplicate submit
        // (prevents the brain from repeatedly calling mine_ore/achieve_goal, overwriting the plan and interrupting a step in progress).
        ActivePlan existing = activePlans.get(bot.getUUID());
        if (existing != null && sameGoalIntent(existing.goal, goal)) {
            BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_active_plan");
            return true;
        }
        // P0 queue: an in-progress goal already exists -> the new goal is enqueued (de-duplicated), and once the current work finishes it automatically continues to the next one. This is the foundation for compound instructions/sequential requests.
        // Note: is it only safe to check this after the "prerequisite downgrade block"? No -- the downgrade block is below; let it run its check first: a sub-goal must still be blocked.
        java.util.Deque<QueuedGoal> queued = goalQueue.computeIfAbsent(bot.getUUID(),
                k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (existing != null) {
            Goal ugQ = userGoal.get(bot.getUUID());
            if (ugQ != null && !ugQ.equals(goal) && isPrerequisiteOf(bot, goal, ugQ)) {
                BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ugQ);
                report(bot, "This is a prerequisite for the current goal and will finish automatically.");
                return false;
            }
            if (queued.stream().map(QueuedGoal::goal).anyMatch(candidate -> sameGoalIntent(candidate, goal))) {
                BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_queued");
                return true;
            }
            queued.addLast(new QueuedGoal(goal, mode));
            BotLog.task(bot, "goal_queued", "goal", goal, "behind", String.valueOf(existing.goal), "queue_size", queued.size());
            report(bot, "Noted. After the current work finishes, I will: " + goalLabel(goal));
            markDirty(bot);
            return true;
        }
        // B: protect the user's original goal -- the brain must not downgrade it to one of its prerequisite sub-goals. Observed in testing: after mining diamond failed, the brain's achieve_goal to make an iron pickaxe,
        // and mine_ore to mine iron (both prerequisites of mining diamond), overwrote the goal -- and after making the iron pickaxe it falsely reported "task complete", when the original request was to mine iron and make a pickaxe.
        Goal ug = userGoal.get(bot.getUUID());
        if (ug != null && !ug.equals(goal) && isPrerequisiteOf(bot, goal, ug)) {
            BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ug);
            report(bot, "This is a prerequisite for the current goal and will finish automatically. Send a new request to replace the goal.");
            return false;
        }
        UUID missionId = restore == null ? UUID.randomUUID() : restore.missionId();
        int startedTick = restore == null ? bot.level().getServer().getTickCount() : restore.startedTick();
        Optional<CapacityParentNamespace> decodedCapacityParent = restore == null
                ? Optional.empty()
                : CapacityParentNamespace.decode(restore.capacityParentNamespace());
        if (restore != null && !restore.capacityParentNamespace().isBlank()
                && decodedCapacityParent.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            GoalEvaluation invalidEvaluation = GoalPredicates.evaluate(goal,
                    GoalSnapshotCollector.collect(bot, goal, restore.context()),
                    restore.context().completedDeliveries());
            recordImmediateResult(bot, missionId, goal, startedTick, invalidEvaluation,
                    GoalResult.classify(invalidEvaluation, false),
                    "mission_restore_invalid_capacity_parent_namespace");
            return false;
        }
        CapacityParentNamespace restoredCapacityParent =
                decodedCapacityParent.orElse(null);
        GoalPredicate predicate = GoalPredicates.forGoal(goal);
        GoalSnapshotCollector.Context context = restore == null ? initialContext(bot, goal) : restore.context();
        GoalEvaluation initialEvaluation = GoalPredicates.evaluate(goal,
                GoalSnapshotCollector.collect(bot, goal, context),
                context.completedDeliveries());
        if (restore != null && restore.batchCheckpoint().persisted()
                && ((mode == ExecutionMode.ADAPTIVE
                && restore.batchCheckpoint().stepLimit() != ADAPTIVE_STRATEGY_STEP_LIMIT)
                || (mode != ExecutionMode.ADAPTIVE
                && restore.batchCheckpoint().stepLimit() != DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT))) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_execution_mode_checkpoint_mismatch");
            return false;
        }
        if (restore != null && (!restore.validationFailure().isBlank()
                || restore.huntSearchCursor() == null
                || restore.skippedTargetReceipts() == null
                || !completedDeliveriesAuthorized(
                goal, restore.context().completedDeliveries())
                || !skippedTargetReceiptsAuthorized(
                goal, restore.skippedTargetReceipts()))) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    restore.validationFailure().isBlank()
                            ? restore.huntSearchCursor() == null
                            ? "mission_restore_invalid_hunt_search_cursor"
                            : !completedDeliveriesAuthorized(
                            goal, restore.context().completedDeliveries())
                            ? "mission_restore_invalid_completed_deliveries"
                            : "mission_restore_invalid_skipped_target_receipts"
                            : restore.validationFailure());
            return false;
        }
        List<SkippedTargetReceipt> restoredSkippedTargets = restore == null
                ? List.of() : restore.skippedTargetReceipts();
        List<GoalResult.SkippedStep> restoredSkippedResults =
                restoredSkippedTargets.stream()
                        .map(SkippedTargetReceipt::asSkippedStep).toList();
        int persistedCapacityParentDelivered = restore == null
                ? -1 : restore.capacityParentDelivered();
        int persistedCapacityParentServicesUsed = restore == null
                ? 0 : restore.capacityParentServicesUsed();
        String persistedCapacityParentFace = restore == null
                ? "" : restore.capacityParentFace();
        BlockPos decodedCapacityParentFace = decodePos(
                persistedCapacityParentFace).orElse(null);
        if (persistedCapacityParentDelivered < -1
                || persistedCapacityParentServicesUsed < 0
                || !persistedCapacityParentFace.isBlank()
                && decodedCapacityParentFace == null) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_capacity_parent_checkpoint");
            return false;
        }
        int persistedRareResourceEpoch = restore == null
                ? 0 : restore.rareResourceRetriesUsed();
        int rareEpochMarginPool = rareMissionEpochMarginPool(goal);
        if (persistedRareResourceEpoch < 0
                || persistedRareResourceEpoch
                > MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH + rareEpochMarginPool) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_rare_resource_retry_count");
            return false;
        }
        int persistedRareEpochMarginUsed = restore == null
                ? 0 : restore.rareEpochMarginUsed();
        if (!validRestoredRareEpochMargin(persistedRareResourceEpoch,
                persistedRareEpochMarginUsed, rareEpochMarginPool)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_rare_epoch_margin");
            return false;
        }
        Map<String, String> restoredSettledServiceValues = restore == null
                ? Map.of() : restore.settledServiceTombstone();
        Optional<List<SettledServiceTombstone>> restoredSettledServices =
                decodeSettledServiceTombstones(restoredSettledServiceValues);
        if (restoredSettledServices.isEmpty()
                || restoredSettledServices.orElseThrow().stream()
                .anyMatch(settled -> !missionId.toString().equals(
                        settled.authority().descriptor().missionId()))) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_settled_service_tombstone");
            return false;
        }
        Map<String, String> restoredHuntCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.HUNT
                ? restore.taskCheckpoint() : Map.of();
        Optional<HuntPickupCheckpoint.Metadata> restoredHunt =
                HuntPickupCheckpoint.inspect(restoredHuntCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.HUNT
                && restoredHunt.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_hunt_pickup_checkpoint");
            return false;
        }
        boolean unsettledHuntPickup =
                restoredHunt.filter(HuntPickupCheckpoint.Metadata::open).isPresent();
        boolean committedHuntPickup = restoredHunt.isPresent() && !unsettledHuntPickup;
        if (restoredHunt.filter(metadata ->
                hasSameDimensionOpenHuntTimeRollback(
                        metadata,
                        bot.level().dimension().identifier().toString(),
                        bot.level().getGameTime())
                || !metadata.open()
                && !trustedClosedHuntPickupReceipt(bot, metadata)).isPresent()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_hunt_pickup_checkpoint");
            return false;
        }
        Map<String, String> restoredServiceCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINING_SERVICE
                ? restore.taskCheckpoint() : Map.of();
        Optional<MiningServiceTask.RestoreMetadata> restoredService =
                MiningServiceTask.inspectCheckpoint(restoredServiceCheckpoint);
        boolean mergedSettledTerminalReceipt = false;
        String mergedSettledTerminalFailure = "";
        String liveServiceDimension = bot.level().dimension()
                .identifier().toString();
        if (!restoredServiceCheckpoint.isEmpty() && (restoredService.isEmpty()
                || !liveServiceDimension.equals(
                restoredService.orElseThrow().serviceDimension())
                && restoredService.orElseThrow().terminalFailure().isBlank())) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_mining_service_checkpoint");
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINING_SERVICE",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        if (restoredService.isPresent() && !restoredSettledServices.orElseThrow().isEmpty()) {
            MiningServiceTask.RestoreMetadata currentMetadata = restoredService.orElseThrow();
            Optional<SettledServiceAuthority> currentServiceAuthority =
                    persistedServiceAuthority(currentMetadata);
            boolean guardedGeometry = currentServiceAuthority
                    .map(current -> restoredSettledServices.orElseThrow().stream()
                            .anyMatch(settled -> settled.sameGeometry(current)))
                    .orElse(false);
            Optional<SettledServiceTombstone> sameKey = currentServiceAuthority
                    .flatMap(current -> restoredSettledServices.orElseThrow().stream()
                            .filter(settled -> settled.key().equals(current.key()))
                            .findFirst());
            boolean terminalReceipt = !currentMetadata.terminalFailure().isBlank();
            boolean exactIdempotentReceipt = terminalReceipt && sameKey
                    .filter(settled -> settled.failureReason().equals(
                            currentMetadata.terminalFailure())).isPresent();
            boolean conflictingTerminalAuthority = terminalReceipt && !exactIdempotentReceipt
                    && (sameKey.isPresent() || guardedGeometry);
            if (currentServiceAuthority.isEmpty()
                    || hasActiveServicePocket(restoredServiceCheckpoint) && guardedGeometry
                    || conflictingTerminalAuthority) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_invalid_settled_service_tombstone");
                return false;
            }
            if (exactIdempotentReceipt) {
                // Crash window after guard publication but before the failed task reference was
                // detached. The two authorities are byte-for-byte equivalent; consume the task
                // copy only after the decoded service has passed every goal/profile/parent
                // compatibility check below.
                mergedSettledTerminalReceipt = true;
                mergedSettledTerminalFailure = currentMetadata.terminalFailure();
            }
        }
        Map<String, String> restoredOreDigCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                ? restore.taskCheckpoint() : Map.of();
        Optional<OreDigTask.RestoreMetadata> restoredOreDig =
                inspectOreDigCheckpointForGoal(goal, restoredOreDigCheckpoint);
        boolean restoredOreDigPhysicalDebt = hasOreDigPhysicalLedger(
                restoredOreDigCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                && restoredOreDig.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_ore_dig_checkpoint");
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINE_ORE",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        Map<String, String> restoredMiningCheckpoint = restore == null
                ? Map.of() : restore.miningCheckpoint();
        Optional<OreDigTask.RestoreMetadata> decodedMining =
                OreDigTask.inspectCheckpoint(restoredMiningCheckpoint);
        Optional<OreDigTask.RestoreMetadata> restoredMining =
                inspectOreDigCheckpointForGoal(goal, restoredMiningCheckpoint);
        boolean ignoredBootstrapMiningNamespace = restoredService
                .filter(metadata -> metadata.policy().profile()
                        == ServiceProfile.RARE_ORE_BATCH
                        || metadata.policy().profile()
                        == ServiceProfile.RARE_DESCENT_KIT)
                .filter(metadata -> metadata.serviceBoundary() == 0)
                .flatMap(metadata -> decodedMining.filter(mining ->
                        mining.rareMissionTarget() == 0
                                && !mining.batchOpen()
                                && !OreDigTask.oreFingerprint(metadata.ores()).equals(
                                OreDigTask.oreFingerprint(mining.ores()))))
                .isPresent()
                && !hasOreDigPhysicalLedger(restoredMiningCheckpoint);
        if (ignoredBootstrapMiningNamespace) {
            // A completed ordinary prerequisite (most commonly coal for torches) is the latest
            // branch namespace until the first rare batch starts.  Boundary zero owns a synthetic
            // rare work face and may discard only that closed, different-family predecessor.
            restoredMiningCheckpoint = Map.of();
            restoredMining = Optional.empty();
        }
        if (!restoredMiningCheckpoint.isEmpty() && restoredMining.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_mining_checkpoint");
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINING_CURSOR",
                    "reason", "invalid_mining_checkpoint");
            return false;
        }
        boolean restoredProtectedRareMining = originalLongRareOreTargetCount(goal) > 0
                && restoredMining.isPresent()
                && restoredMining.orElseThrow().rareMissionTarget()
                == originalLongRareOreTargetCount(goal)
                && miningStepFeedsGoal(goal, restoredMining.orElseThrow().ores());
        Map<String, String> restoredAuxiliaryMiningCheckpoint = restore == null
                ? Map.of() : restore.auxiliaryMiningCheckpoint();
        Optional<OreDigTask.RestoreMetadata> restoredAuxiliaryMining =
                OreDigTask.inspectCheckpoint(restoredAuxiliaryMiningCheckpoint, 0)
                        .filter(metadata -> metadata.rareMissionTarget() == 0);
        String restoredAuxiliaryMiningContinuationFingerprint = restore == null
                ? "" : restore.auxiliaryMiningContinuationFingerprint();
        boolean restoredAuxiliaryMiningContinuation =
                !restoredAuxiliaryMiningContinuationFingerprint.isBlank();
        boolean unmarkedClosedAuxiliaryService = restoredCapacityParent == null
                && restoredAuxiliaryMining.filter(metadata -> !metadata.batchOpen()).isPresent()
                && restoredService.map(MiningServiceTask.RestoreMetadata::policy)
                .map(ServicePolicy::profile)
                .filter(profile -> profile == ServiceProfile.ORE_BATCH)
                .isPresent();
        String restoredAuxiliaryFingerprint = restoredAuxiliaryMining
                .map(metadata -> OreDigTask.oreFingerprint(metadata.ores()))
                .orElse("");
        boolean validAuxiliaryContinuation = restoredAuxiliaryMiningContinuation
                && restoredCapacityParent == null
                && restoredService.isEmpty()
                && restoredAuxiliaryMining.filter(metadata -> !metadata.batchOpen()).isPresent()
                && restoredAuxiliaryFingerprint.equals(
                        restoredAuxiliaryMiningContinuationFingerprint)
                && !hasOreDigPhysicalLedger(restoredAuxiliaryMiningCheckpoint);
        if (restoredAuxiliaryMiningContinuation && !validAuxiliaryContinuation
                || !restoredAuxiliaryMiningCheckpoint.isEmpty()
                && (restoredAuxiliaryMining.isEmpty() || !restoredProtectedRareMining
                || restoredCapacityParent == null && !unmarkedClosedAuxiliaryService
                && !validAuxiliaryContinuation)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_auxiliary_mining_checkpoint");
            return false;
        }
        Map<String, String> restoredCapacityParentCheckpoint = switch (
                restoredCapacityParent == null
                        ? CapacityParentNamespace.MINING : restoredCapacityParent) {
            case MINING -> restoredMiningCheckpoint;
            case AUXILIARY -> restoredAuxiliaryMiningCheckpoint;
        };
        Optional<OreDigTask.RestoreMetadata> restoredCapacityParentMetadata =
                restoredCapacityParent == null ? Optional.empty()
                        : OreDigTask.inspectCheckpoint(restoredCapacityParentCheckpoint, 0);
        int restoredCapacityParentDelivered = persistedCapacityParentDelivered;
        int restoredCapacityParentServicesUsed = persistedCapacityParentServicesUsed;
        BlockPos restoredCapacityParentFace = decodedCapacityParentFace;
        if (restoredCapacityParent != null && restoredCapacityParentDelivered < 0
                && restoredCapacityParentMetadata.isPresent()) {
            // Legacy capacity-parent snapshots predate the explicit progress watermark. Binding
            // them to their current delivered count is conservative: restart cannot manufacture a
            // repeat service until the exact parent physically delivers another target item.
            restoredCapacityParentDelivered = restoredCapacityParentMetadata.orElseThrow()
                    .delivered();
        }
        if (restoredCapacityParent != null && restoredCapacityParentMetadata.isPresent()) {
            OreDigTask.RestoreMetadata parent = restoredCapacityParentMetadata.orElseThrow();
            if (restoredCapacityParentFace == null) {
                // Legacy capacity-parent snapshots had no work-face witness. Bind them to the
                // current cursor so restart cannot manufacture branch movement.
                restoredCapacityParentFace = parent.cursor().face();
            }
            if (restoredCapacityParentServicesUsed == 0) {
                // Under the former delivered-only rule, a persisted delivery watermark N could
                // have consumed at least one and at most N+1 services. Use the conservative upper
                // estimate so migration never grants extra service budget.
                restoredCapacityParentServicesUsed = Math.min(parent.targetCount(),
                        Math.max(1, restoredCapacityParentDelivered + 1));
            }
        }
        boolean validCapacityParentMarkers;
        if (restoredCapacityParent == null) {
            validCapacityParentMarkers = restoredCapacityParentDelivered == -1
                    && restoredCapacityParentServicesUsed == 0
                    && restoredCapacityParentFace == null;
        } else if (restoredCapacityParentMetadata.isEmpty()
                || restoredCapacityParentDelivered < 0
                || restoredCapacityParentServicesUsed < 1
                || restoredCapacityParentFace == null) {
            validCapacityParentMarkers = false;
        } else {
            OreDigTask.RestoreMetadata parent = restoredCapacityParentMetadata.orElseThrow();
            int upperBound = parent.batchOpen() ? parent.delivered() : parent.targetCount();
            validCapacityParentMarkers = restoredCapacityParentDelivered <= upperBound
                    && restoredCapacityParentServicesUsed <= parent.targetCount()
                    && (restore.taskCheckpointKind() != GoalStep.Kind.MINING_SERVICE
                    || restoredCapacityParentDelivered == parent.delivered()
                    && restoredCapacityParentFace.equals(parent.cursor().face()));
        }
        boolean restoredCommittedCapacityParent = restoredCapacityParent != null
                && validCommittedCapacityParent(
                restoredCapacityParentMetadata,
                restore.taskCheckpointKind(),
                restore.taskCheckpoint(),
                restoredCapacityParentCheckpoint);
        boolean restoredTaskIsCapacityParent = restoredCapacityParent != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                && restore.taskCheckpoint().equals(restoredCapacityParentCheckpoint);
        boolean restoredCapacityRepairOre = restoredCapacityParent != null
                && restoredOreDig.isPresent()
                && !restoredTaskIsCapacityParent;
        boolean supportedCapacityRepairTask = restore == null
                || restore.taskCheckpointKind() == null
                || restore.taskCheckpointKind() == GoalStep.Kind.MINING_SERVICE
                || restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                || restore.taskCheckpointKind() == GoalStep.Kind.MINE
                || restore.taskCheckpointKind() == GoalStep.Kind.DESCEND_TO_Y;
        if (!validCapacityParentMarkers || restoredCapacityParent != null
                && (!validCapacityParentRetry(restoredCapacityParentMetadata)
                && !restoredCommittedCapacityParent
                || restoredCapacityParent == CapacityParentNamespace.MINING
                && !restoredAuxiliaryMiningCheckpoint.isEmpty()
                || restoredCapacityParent == CapacityParentNamespace.AUXILIARY
                && !restoredProtectedRareMining
                || !supportedCapacityRepairTask)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_capacity_parent_checkpoint");
            return false;
        }
        boolean protectedRareNamespace = restoredOreDig.isPresent()
                && restoredOreDig.orElseThrow().rareMissionTarget() == 0
                && restoredProtectedRareMining;
        if (restoredOreDig.isPresent() && restoredMining.isPresent()
                && !protectedRareNamespace && !restoredCapacityRepairOre
                && !restoredOreDigCheckpoint.equals(restoredMiningCheckpoint)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_mining_checkpoint");
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> restoredRareEpochOwner =
                restoredProtectedRareMining
                        ? restoredMining
                        : restoredOreDig.filter(metadata ->
                        metadata.rareMissionTarget()
                                == originalLongRareOreTargetCount(goal)
                                && miningStepFeedsGoal(goal, metadata.ores()));
        OptionalInt normalizedRareResourceEpoch = normalizeRestoredRareResourceEpoch(
                persistedRareResourceEpoch, restoredRareEpochOwner);
        if (normalizedRareResourceEpoch.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_rare_resource_epoch");
            return false;
        }
        int restoredRareResourceEpoch = normalizedRareResourceEpoch.getAsInt();
        Map<String, String> restoredDigDownCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINE
                ? restore.taskCheckpoint() : Map.of();
        Optional<DigDownTask.RestoreMetadata> restoredDigDown =
                DigDownTask.inspectCheckpoint(restoredDigDownCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.MINE
                && restoredDigDown.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_dig_down_checkpoint");
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINE",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        Map<String, String> restoredDescendCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.DESCEND_TO_Y
                ? restore.taskCheckpoint() : Map.of();
        Optional<DescendToYTask.RestoreMetadata> restoredDescend =
                DescendToYTask.inspectCheckpoint(restoredDescendCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.DESCEND_TO_Y
                && restoredDescend.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_descend_checkpoint");
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "DESCEND_TO_Y",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        Map<String, String> restoredObsidianCheckpoint = restore == null
                ? Map.of() : restore.obsidianCheckpoint();
        if (restoredObsidianCheckpoint.isEmpty() && restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MAKE_OBSIDIAN) {
            // Backward compatibility for snapshots written before the dedicated transaction
            // namespace existed.
            restoredObsidianCheckpoint = restore.taskCheckpoint();
        }
        Optional<CreateObsidianTask.RestoreMetadata> restoredObsidian =
                CreateObsidianTask.inspectCheckpoint(restoredObsidianCheckpoint);
        if (!restoredObsidianCheckpoint.isEmpty() && restoredObsidian.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_obsidian_checkpoint");
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MAKE_OBSIDIAN",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        boolean unsettledObsidian = restoredObsidian
                .map(CreateObsidianTask.RestoreMetadata::transactionOpen)
                .orElse(false);
        boolean committedService = restoredService
                .map(MiningServiceTask.RestoreMetadata::done).orElse(false);
        int restoredPendingBoundary = restoredObsidian
                .map(CreateObsidianTask.RestoreMetadata::pendingServiceBoundary)
                .orElse(0);
        if (restoredService.isPresent()) {
            MiningServiceTask.RestoreMetadata serviceMetadata = restoredService.orElseThrow();
            ServiceProfile profile = serviceMetadata.policy().profile();
            boolean obsidianOres = serviceMetadata.ores().equals(
                    OreScan.expandOreFamilies(Set.of(net.minecraft.world.level.block.Blocks.OBSIDIAN)));
            boolean missionMatches = missionId.toString().equals(
                    serviceMetadata.serviceMissionId());
            boolean incompatibleServiceIdentity;
            if (restoredCapacityParent != null
                    && profile != ServiceProfile.ORE_BATCH) {
                incompatibleServiceIdentity = true;
            } else if (profile == ServiceProfile.OBSIDIAN_8) {
                CreateObsidianTask.RestoreMetadata obsidianMetadata = restoredObsidian.orElse(null);
                boolean policyMatches = obsidianMetadata != null
                        && serviceMetadata.serviceTargetCount() == obsidianMetadata.targetCount()
                        && serviceMetadata.policy().equals(
                        ServicePolicy.obsidian8(
                                obsidianMetadata.targetCount(),
                                serviceMetadata.serviceBoundary()));
                boolean pendingIdentity = obsidianMetadata != null
                        && restoredPendingBoundary > 0
                        && serviceMetadata.serviceBoundary() == restoredPendingBoundary;
                boolean alreadyAcknowledgedIdentity = obsidianMetadata != null
                        && serviceMetadata.done()
                        && restoredPendingBoundary == 0
                        && serviceMetadata.serviceBoundary()
                        == obsidianMetadata.servicedCollected();
                incompatibleServiceIdentity = !obsidianOres || !missionMatches
                        || !policyMatches
                        || (!pendingIdentity && !alreadyAcknowledgedIdentity);
            } else if (profile == ServiceProfile.OBSIDIAN_PREFLIGHT) {
                incompatibleServiceIdentity = !obsidianOres || !missionMatches
                        || serviceMetadata.serviceBoundary() != 0
                        || restoredPendingBoundary > 0
                        || unsettledObsidian
                        || !serviceMetadata.policy().equals(
                        ServicePolicy.obsidianPreflight(
                                serviceMetadata.serviceTargetCount()));
            } else if (profile == ServiceProfile.RARE_DESCENT_KIT) {
                BlockPos serviceFace = decodePos(restoredServiceCheckpoint.get("work_face"))
                        .orElse(null);
                BlockPos embeddedCursorFace = decodePos(
                        restoredServiceCheckpoint.get("cursor_face")).orElse(null);
                incompatibleServiceIdentity = !missionMatches
                        || restoredPendingBoundary > 0
                        || restoredRareResourceEpoch != 0
                        || serviceMetadata.serviceTargetCount() != 64
                        || serviceMetadata.serviceBoundary() != 0
                        || originalLongRareOreTargetCount(goal) != 64
                        || serviceFace == null
                        || !serviceFace.equals(embeddedCursorFace)
                        // Boundary-zero KIT precedes the first rare branch. The only legal prior
                        // namespace is a closed different-family bootstrap cursor, which was
                        // discarded above. Any namespace that remains is incompatible history.
                        || restoredMining.isPresent()
                        || !miningStepFeedsGoal(goal, serviceMetadata.ores())
                        || !serviceMetadata.policy().equals(
                        ServicePolicy.rareDescentKit(64));
            } else if (profile == ServiceProfile.RARE_ORE_BATCH) {
                BlockPos serviceFace = decodePos(restoredServiceCheckpoint.get("work_face"))
                        .orElse(null);
                boolean retryBoundaryZero = serviceMetadata.serviceBoundary() == 0
                        && restoredRareResourceEpoch > 0;
                boolean miningNamespaceRequired = serviceMetadata.serviceBoundary() > 0
                        || retryBoundaryZero;
                boolean miningNamespacePresent = restoredMining.isPresent();
                boolean miningFaceMatches = !miningNamespacePresent
                        ? !miningNamespaceRequired
                        : serviceFace != null && serviceFace.equals(
                        restoredMining.orElseThrow().cursor().face());
                boolean miningOreFamilyMatches = !miningNamespacePresent
                        ? !miningNamespaceRequired
                        : OreDigTask.oreFingerprint(serviceMetadata.ores()).equals(
                        OreDigTask.oreFingerprint(restoredMining.orElseThrow().ores()));
                boolean retryEpochMatches = !miningNamespaceRequired
                        || restoredMining.map(OreDigTask.RestoreMetadata::resourceEpoch)
                        .filter(epoch -> epoch == restoredRareResourceEpoch).isPresent();
                incompatibleServiceIdentity = !missionMatches
                        || restoredPendingBoundary > 0
                        || serviceMetadata.serviceTargetCount()
                        != originalLongRareOreTargetCount(goal)
                        || serviceMetadata.serviceBoundary() > goalTargetCount(bot, goal)
                        || !miningFaceMatches
                        || !miningOreFamilyMatches
                        || !retryEpochMatches
                        || !miningStepFeedsGoal(goal, serviceMetadata.ores())
                        || !serviceMetadata.policy().equals(
                        ServicePolicy.rareOreBatch(
                                serviceMetadata.serviceTargetCount(),
                                serviceMetadata.serviceBoundary(),
                                restoredRareResourceEpoch));
            } else {
                BlockPos serviceFace = decodePos(restoredServiceCheckpoint.get("work_face"))
                        .orElse(null);
                boolean miningNamespaceMatches;
                if (restoredCapacityParent != null) {
                    miningNamespaceMatches = capacityParentMatchesService(
                            restoredServiceCheckpoint, restoredCapacityParentCheckpoint);
                } else if (restoredAuxiliaryMining.isPresent()) {
                    miningNamespaceMatches = ordinaryServiceParentMatches(
                            restoredServiceCheckpoint,
                            restoredAuxiliaryMiningCheckpoint, false);
                } else {
                    miningNamespaceMatches = ordinaryServiceMiningNamespaceMatches(
                            goal, serviceMetadata.ores(), serviceFace, restoredMining);
                }
                ServicePolicy expectedOrdinaryPolicy =
                        !serviceMetadata.policy().maintainsTunnelingTools()
                                && serviceMetadata.policy().emergencyBlocksReserved()
                                > ServicePolicy.defaultOre(false)
                                .emergencyBlocksReserved()
                                ? ServicePolicy.capacityHandoff(
                                serviceMetadata.policy().emergencyBlocksReserved())
                                : ServicePolicy.defaultOre(
                                serviceMetadata.policy().maintainsTunnelingTools());
                incompatibleServiceIdentity = !missionMatches
                        || restoredPendingBoundary > 0
                        || serviceMetadata.serviceTargetCount() != 0
                        || serviceMetadata.serviceBoundary() != 0
                        || restoredCapacityParent != null
                        && serviceMetadata.policy().maintainsTunnelingTools()
                        || !miningNamespaceMatches
                        || !serviceMetadata.policy().equals(expectedOrdinaryPolicy);
            }
            if (incompatibleServiceIdentity) {
                String reason = switch (profile) {
                    case ORE_BATCH ->
                            "mission_restore_incompatible_mining_service_checkpoint";
                    case RARE_DESCENT_KIT ->
                            "mission_restore_incompatible_rare_descent_kit_checkpoint";
                    case RARE_ORE_BATCH ->
                            "mission_restore_incompatible_rare_ore_service_checkpoint";
                    case OBSIDIAN_PREFLIGHT, OBSIDIAN_8 ->
                            "mission_restore_incompatible_obsidian_service_checkpoint";
                };
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false), reason);
                return false;
            }
        }
        if (mergedSettledTerminalReceipt) {
            if (!liveServiceDimension.equals(
                    restoredService.orElseThrow().serviceDimension())) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick,
                        initialEvaluation, GoalResult.Status.FAILED,
                        mergedSettledTerminalFailure);
                return false;
            }
            // All identity and parent checks above were evaluated against the immutable receipt.
            // It is now safe to discard only the effective task copy; the guard remains the sole
            // durable replay authority.
            restoredServiceCheckpoint = Map.of();
            restoredService = Optional.empty();
        }
        // Crash window: service reached DONE after the obsidian boundary was persisted, but before
        // GoalExecutor could acknowledge it. Commit that acknowledgement idempotently on restore;
        // otherwise the same eight-block boundary would be serviced forever.
        if (committedService && restoredPendingBoundary > 0) {
            Optional<Map<String, String>> acknowledged =
                    CreateObsidianTask.acknowledgeServiceBoundary(restoredObsidianCheckpoint);
            if (acknowledged.isEmpty()) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_invalid_obsidian_service_ack");
                return false;
            }
            restoredObsidianCheckpoint = acknowledged.orElseThrow();
            restoredObsidian = CreateObsidianTask.inspectCheckpoint(restoredObsidianCheckpoint);
        }
        boolean unsettledService = restoredService
                .map(metadata -> !metadata.done()).orElse(false);
        boolean restoredTerminalServiceFailure = restoredService
                .map(MiningServiceTask.RestoreMetadata::terminalFailure)
                .filter(failure -> !failure.isBlank())
                .isPresent();
        boolean activeServicePocket = unsettledService
                && hasActiveServicePocket(restoredServiceCheckpoint);
        boolean interruptedDescend = restoredDescend
                .map(DescendToYTask.RestoreMetadata::transactionOpen).orElse(false);
        boolean committedDescend = restoredDescend.isPresent() && !interruptedDescend;
        GoalPlanner.GoalPlan plan = GoalPlanner.plan(
                bot, goal, context, missionId.toString());
        boolean openCapacityRepairOre = restoredCapacityRepairOre
                && restoredOreDig.map(OreDigTask.RestoreMetadata::batchOpen)
                .orElse(false);
        boolean committedCapacityRepairOre = restoredCapacityRepairOre
                && !openCapacityRepairOre && !restoredOreDigPhysicalDebt;
        if (openCapacityRepairOre && !restoredOreDigPhysicalDebt
                && (!plan.success() || !hasFreshMiningSuccessor(
                plan.steps(), restoredOreDig.orElseThrow()))) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_capacity_repair_checkpoint");
            return false;
        }
        if (restoredAuxiliaryMiningContinuation && plan.success()
                && !hasFreshMiningSuccessor(
                plan.steps(), restoredAuxiliaryMining.orElseThrow().ores())) {
            // The marker was created only after an exact failed service yielded to a fresh plan.
            // A later successful plan that no longer contains this ore family is positive proof
            // that its closed cursor is stale; retire both together instead of poisoning restore.
            restoredAuxiliaryMiningCheckpoint = Map.of();
            restoredAuxiliaryMining = Optional.empty();
            restoredAuxiliaryMiningContinuation = false;
            restoredAuxiliaryMiningContinuationFingerprint = "";
        }
        boolean committedRareDescentKit = committedService && restoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(ServicePolicy::profile)
                .filter(profile -> profile
                        == ServiceProfile.RARE_DESCENT_KIT)
                .isPresent();
        boolean committedRareDescentKitReady = committedRareDescentKit
                && MiningServiceTask.rareDescentKitReady(bot)
                && MiningServiceTask.ownedMissionDepot(bot, missionId.toString());
        if (committedRareDescentKit && !committedRareDescentKitReady
                && plan.success()
                && plan.steps().stream().noneMatch(GoalStep::isRareDescentKitService)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_rare_descent_kit_not_ready");
            return false;
        }
        boolean discardRestoredTaskCheckpoint = mergedSettledTerminalReceipt
                || committedCapacityRepairOre || committedHuntPickup;
        if (restoredCommittedCapacityParent) {
            // TaskManager can publish COMPLETED before NavSafety/DangerWatcher owns the rest of
            // the tick. A periodic or stopping snapshot taken during that safety transaction sees
            // the exact closed OreDig checkpoint while the capacity marker is still present. That
            // is a committed parent, not a retry and not corrupt history: settle it idempotently
            // without replaying the already-delivered batch.
            CapacityParentNamespace committedParent = restoredCapacityParent;
            String committedFingerprint = OreDigTask.oreFingerprint(
                    restoredCapacityParentMetadata.orElseThrow().ores());
            boolean immediateSameFamilyService = plan.success()
                    && !plan.steps().isEmpty()
                    && plan.steps().get(0).kind() == GoalStep.Kind.MINING_SERVICE
                    && committedFingerprint.equals(OreDigTask.oreFingerprint(
                    plan.steps().get(0).ores()));
            restoredCapacityParent = null;
            restoredCapacityParentDelivered = -1;
            restoredCapacityParentFace = null;
            restoredCapacityParentServicesUsed = 0;
            discardRestoredTaskCheckpoint = true;
            restoredOreDigCheckpoint = Map.of();
            restoredOreDig = Optional.empty();
            if (committedParent == CapacityParentNamespace.AUXILIARY
                    && !immediateSameFamilyService) {
                restoredAuxiliaryMiningCheckpoint = Map.of();
                restoredAuxiliaryMining = Optional.empty();
            }
        }
        Optional<MiningServiceTask.RestoreMetadata> stableRestoredService = restoredService;
        boolean ordinaryService = stableRestoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(ServicePolicy::profile)
                .filter(profile -> profile == ServiceProfile.ORE_BATCH)
                .isPresent();
        boolean freshOrdinaryServiceSuccessor = ordinaryService && plan.success()
                && hasFreshMiningSuccessor(plan.steps(),
                restoredService.orElseThrow().ores());
        Optional<OreDigTask.RestoreMetadata> ordinaryServiceParent = !ordinaryService
                ? Optional.empty()
                : restoredCapacityParent != null
                ? restoredCapacityParentMetadata
                : restoredAuxiliaryMining.isPresent()
                ? restoredAuxiliaryMining
                : restoredMining.filter(metadata -> metadata.rareMissionTarget() == 0
                && OreDigTask.oreFingerprint(metadata.ores()).equals(
                OreDigTask.oreFingerprint(stableRestoredService.orElseThrow().ores())));
        Map<String, String> ordinaryServiceParentCheckpoint = restoredCapacityParent != null
                ? restoredCapacityParentCheckpoint
                : restoredAuxiliaryMining.isPresent()
                ? restoredAuxiliaryMiningCheckpoint : restoredMiningCheckpoint;
        // A final ordinary hand-off is deliberately scheduled after its last OreDig batch. On a
        // restart the live inventory already satisfies that prerequisite, so the fresh plan has no
        // same-family mining successor by design. The exact closed branch namespace, mission-bound
        // service checkpoint and matching work face established above are the durable identity;
        // keep the service (including an open disposal ledger) instead of treating it as stale.
        boolean terminalOrdinaryServiceRestore = unsettledService && ordinaryService
                && !freshOrdinaryServiceSuccessor
                && !restoredService.orElseThrow().policy().maintainsTunnelingTools()
                && ordinaryServiceParent.filter(metadata -> metadata.rareMissionTarget() == 0
                        && !metadata.batchOpen()
                        && OreDigTask.oreFingerprint(metadata.ores()).equals(
                        OreDigTask.oreFingerprint(stableRestoredService.orElseThrow().ores())))
                .isPresent()
                && !hasOreDigPhysicalLedger(ordinaryServiceParentCheckpoint);
        boolean capacityOrdinaryServiceRestore = unsettledService && ordinaryService
                && restoredCapacityParent != null;
        boolean capacityRetryRestore = restoredCapacityParent != null
                && restoredCapacityParentMetadata
                .filter(OreDigTask.RestoreMetadata::batchOpen).isPresent();
        if (restoredOreDig.filter(OreDigTask.RestoreMetadata::batchOpen)
                .filter(metadata -> metadata.rareMissionTarget() != 0).isPresent()
                && (!restoredOreDigPhysicalDebt || plan.success())
                && !hasFreshMiningSuccessor(
                plan.steps(), restoredOreDig.orElseThrow())) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_ore_dig_successor");
            return false;
        }
        if (unsettledService && ordinaryService) {
            boolean miningLedgerOpen = !restoredProtectedRareMining
                    && hasOreDigPhysicalLedger(restoredMiningCheckpoint);
            if (miningLedgerOpen) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_orphaned_ordinary_service_ledger");
                return false;
            }
            // A live pocket is its own exact physical successor and must always replay first. A
            // marked dynamic capacity service likewise owns an exact debited parent cursor even if
            // the fresh symbolic planner temporarily cannot see the supplies held by that service.
            if (plan.success() && !freshOrdinaryServiceSuccessor
                    && !terminalOrdinaryServiceRestore
                    && !capacityOrdinaryServiceRestore && !activeServicePocket
                    && !restoredTerminalServiceFailure) {
                // The prerequisite may have become true between checkpoint and restart. With no
                // physical disposal debt, the fresh plan is authoritative and the stale service
                // can be dropped instead of mutating an unrelated ore family.
                boolean retireClosedAuxiliary = !restoredAuxiliaryMiningCheckpoint.isEmpty()
                        && failedClosedAuxiliaryServiceMatches(
                        restoredServiceCheckpoint, restoredAuxiliaryMiningCheckpoint);
                discardRestoredTaskCheckpoint = true;
                unsettledService = false;
                if (retireClosedAuxiliary) {
                    // The exact closed auxiliary cursor existed only to bind this service. Keeping
                    // it after dropping the service would poison the next restart: an unrelated
                    // fresh task checkpoint cannot authorize the orphaned aux_mining namespace.
                    restoredAuxiliaryMiningCheckpoint = Map.of();
                    restoredAuxiliaryMining = Optional.empty();
                }
            }
        }
        if (restoredOreDig.isPresent()
                && restoredOreDig.orElseThrow().rareMissionTarget() == 0
                && !hasFreshMiningSuccessor(plan.steps(), restoredOreDig.orElseThrow())
                && restoredCapacityParent == null) {
            boolean matchingMiningNamespace = restoredMining.isPresent()
                    && restoredOreDigCheckpoint.equals(restoredMiningCheckpoint);
            boolean ordinaryPhysicalDebt = restoredOreDigPhysicalDebt
                    || matchingMiningNamespace
                    && hasOreDigPhysicalLedger(restoredMiningCheckpoint);
            if (ordinaryPhysicalDebt && plan.success()) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_orphaned_ordinary_mining_ledger");
                return false;
            }
            if (!ordinaryPhysicalDebt && hasFreshMiningSuccessor(
                    plan.steps(), restoredOreDig.orElseThrow().ores())) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_incompatible_ore_dig_successor");
                return false;
            }
            if (!ordinaryPhysicalDebt) {
                discardRestoredTaskCheckpoint = true;
                if (matchingMiningNamespace) {
                    restoredMiningCheckpoint = Map.of();
                    restoredMining = Optional.empty();
                }
                restoredOreDig = Optional.empty();
            }
        }
        if (plan.success()
                && restoredMining.isPresent()
                && restoredMining.orElseThrow().rareMissionTarget() == 0
                && !hasFreshMiningSuccessor(plan.steps(), restoredMining.orElseThrow().ores())
                && !terminalOrdinaryServiceRestore
                && restoredCapacityParent != CapacityParentNamespace.MINING) {
            if (hasOreDigPhysicalLedger(restoredMiningCheckpoint)) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_orphaned_ordinary_mining_ledger");
                return false;
            }
            restoredMiningCheckpoint = Map.of();
            restoredMining = Optional.empty();
        }
        if (initialEvaluation.state() == GoalEvaluation.State.SATISFIED
                && !unsettledObsidian && !unsettledService && !unsettledHuntPickup
                && restoredDigDown.isEmpty() && !interruptedDescend
                && restoredOreDig.filter(
                OreDigTask.RestoreMetadata::batchOpen).isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.Status.COMPLETED, "already_satisfied",
                    restoredSkippedResults);
            return true;
        }
        boolean restoredPreflight = restoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(ServicePolicy::profile)
                .filter(profile -> profile
                        == ServiceProfile.OBSIDIAN_PREFLIGHT)
                .isPresent();
        int plannedPreflightTarget = plannedObsidianPreflightTarget(plan.steps());
        boolean freshObsidianIdentityPresent = plan.steps().stream().anyMatch(
                step -> step.isObsidianPreflight()
                        || step.kind() == GoalStep.Kind.MAKE_OBSIDIAN);
        int directObsidianRemainingTarget = directObsidianRemainingTarget(
                bot, goal, initialEvaluation);
        boolean plannedPreflightUnknown = plannedPreflightTarget < 0
                && !plan.success() && !freshObsidianIdentityPresent
                && restoredPreflight && directObsidianRemainingTarget > 0
                && restoredService.orElseThrow().serviceTargetCount()
                == directObsidianRemainingTarget;
        boolean preflightTargetMatches = restoredPreflight
                && restoredService.orElseThrow().serviceTargetCount() == plannedPreflightTarget;
        // A failed fresh plan may not reach the preflight node at all (for example the exact
        // supplies are in the service checkpoint's depot). Schema-4 remains authoritative only
        // for a direct obsidian goal whose live remaining count proves the same target. Compound
        // goals, a successful plan with no identity, and a different positive target fail closed.
        if (unsettledService && restoredPreflight
                && !preflightTargetMatches && !plannedPreflightUnknown) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_obsidian_service_checkpoint");
            return false;
        }
        // DONE is the commit record for the same preflight transaction. If a failed fresh plan
        // cannot materialize its preflight node, the exact direct-goal remaining count is the same
        // authority used above for a RUNNING restore; otherwise a crash between task completion and
        // GoalExecutor's hand-off would turn a valid committed preflight into plan_failed.
        boolean committedPreflight = committedService
                && (preflightTargetMatches || plannedPreflightUnknown);
        Set<Block> interruptedServiceOres = unsettledService
                ? restoredService.map(MiningServiceTask.RestoreMetadata::ores).orElse(Set.of())
                : Set.of();
        boolean restoredObsidianBoundary = restoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(ServicePolicy::profile)
                .filter(profile -> profile == ServiceProfile.OBSIDIAN_8)
                .isPresent();
        int restoredObsidianCollected = restoredObsidian.isPresent()
                ? nonNegativeInt(restoredObsidianCheckpoint.get("collected")) : -1;
        int restoredObsidianRemainingTarget = restoredObsidian.isPresent()
                ? Math.max(0, restoredObsidian.orElseThrow().targetCount()
                - restoredObsidianCollected) : -1;
        // DONE is the commit record for the complete MAKE transaction. Its inventory result is
        // already part of the fresh snapshot, so the checkpoint must not reinsert the original
        // MAKE ahead of a later STOCKPILE/BUILD tail after a crash-window restore. If inventory
        // subsequently regressed, the fresh plan owns that new deficit instead of stale history.
        boolean committedObsidianMake = restoredObsidian.isPresent()
                && restoredObsidianRemainingTarget == 0
                && "DONE".equals(restoredObsidianCheckpoint.get("phase"));
        boolean interruptedObsidian = restoredObsidian.isPresent()
                && !committedObsidianMake
                && (unsettledObsidian || (restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MAKE_OBSIDIAN)
                // RUNNING and DONE inter-batch service both belong to the same factual MAKE
                // transaction. Waiting until the service is committed lets a stale target32
                // boundary replay over a fresh remaining target8 before identity validation.
                || restoredObsidianBoundary);
        boolean transactionTargetMatchesFresh = interruptedObsidian
                && restoredObsidianRemainingTarget > 0
                && plannedPreflightTarget == restoredObsidianRemainingTarget;
        boolean transactionTargetMatchesUnknownDirect = interruptedObsidian
                && restoredObsidianRemainingTarget > 0
                && plannedPreflightTarget < 0 && !plan.success()
                && !freshObsidianIdentityPresent
                && directObsidianRemainingTarget == restoredObsidianRemainingTarget;
        boolean interruptedDigDown = restoredDigDown.isPresent();
        // A direct obsidian goal can be reconstructed from its exact transaction alone. A compound
        // goal cannot: its fresh plan owns unrelated material and terminal steps (notably BUILD).
        // When that plan failed, replaying only service -> MAKE would mutate the world and then lose
        // the unrecoverable tail; isolate the restore before scheduling either task.
        boolean compoundObsidianTailUnavailable = !plan.success()
                && !isDirectObsidianGoal(goal)
                && !unsettledHuntPickup
                && (restoredPreflight || restoredObsidianBoundary || interruptedObsidian);
        if (compoundObsidianTailUnavailable) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_compound_obsidian_tail_unavailable",
                    restoredSkippedResults);
            return false;
        }
        // The service policy binds the original transaction target, while a fresh plan sees only
        // what remains after the checkpoint's factual collected count. Bind both views explicitly:
        // target32/collected8 must match fresh target24; a stale target32 cannot impersonate a live
        // target8 merely because its service and transaction namespaces are internally consistent.
        if (interruptedObsidian
                && !transactionTargetMatchesFresh
                && !transactionTargetMatchesUnknownDirect) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_obsidian_service_checkpoint",
                    restoredSkippedResults);
            return false;
        }
        if (!plan.success() && !unsettledHuntPickup && interruptedServiceOres.isEmpty()
                && !committedPreflight && !interruptedObsidian
                && !interruptedDigDown && !interruptedDescend
                && !restoredOreDigPhysicalDebt) {
            String reason = !mergedSettledTerminalFailure.isBlank()
                    ? mergedSettledTerminalFailure
                    : !restoredSettledServices.orElseThrow().isEmpty()
                    ? settledServiceFailureWithoutContinuation(
                    restoredSettledServices.orElseThrow())
                    : committedRareDescentKit && !committedRareDescentKitReady
                    ? "mission_restore_rare_descent_kit_not_ready"
                    : "plan_failed:" + String.join(",", plan.unresolved());
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false), reason,
                    restoredSkippedResults);
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.TASK, bot, "goal_plan_failed",
                    "goal", goal,
                    "unresolved", plan.unresolved());
            return false;
        }
        List<GoalStep> restoredSteps;
        if (unsettledHuntPickup) {
            HuntPickupCheckpoint.Metadata metadata = restoredHunt.orElseThrow();
            GoalStep settlement = GoalStep.hunt(metadata.targetCount());
            if (!metadata.requireFullQuota()) {
                settlement = settlement.asBestEffort();
            }
            restoredSteps = new ArrayList<>(List.of(settlement));
        } else {
            restoredSteps = new ArrayList<>(applySkippedTargetReceipts(
                    plan.success() ? plan.steps() : List.of(), restoredSkippedTargets));
        }
        if (restoredOreDig.map(OreDigTask.RestoreMetadata::batchOpen).orElse(false)
                && (!capacityRetryRestore || restoredCapacityRepairOre
                || restoredOreDigPhysicalDebt)) {
            replayInterruptedOreBatchFirst(restoredSteps,
                    restoredOreDig.orElseThrow());
        } else if (capacityRetryRestore && !unsettledService && plan.success()) {
            reconcileCapacityRetryAfterPrerequisites(restoredSteps,
                    restoredCapacityParentMetadata.orElseThrow());
        }
        boolean rebuiltObsidianAcquisition = false;
        if (!interruptedServiceOres.isEmpty()) {
            // Replanning from the current inventory normally omits the already-entered inter-batch
            // service step. Replay it first so a restart at the depot cannot begin the next ore
            // batch from the wrong position; its checkpoint returns to the exact saved work face.
            // If planning currently fails because the tool/food is at that depot, run service alone;
            // assignNext's postcondition repair replans after the inventory has been replenished.
            ServicePolicy restoredPolicy = restoredService.orElseThrow().policy();
            int pendingBoundary = restoredObsidian
                    .map(CreateObsidianTask.RestoreMetadata::pendingServiceBoundary)
                    .orElse(0);
            GoalStep serviceStep;
            if (restoredPolicy.profile() == ServiceProfile.OBSIDIAN_8) {
                if (pendingBoundary <= 0) {
                    recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                            GoalResult.Status.FAILED,
                            "mission_restore_orphaned_obsidian_service");
                    return false;
                }
                serviceStep = GoalStep.obsidianService(
                        restoredService.orElseThrow().serviceBoundary(),
                        restoredService.orElseThrow().serviceTargetCount());
                rebuildObsidianAcquisition(goal, restoredSteps, List.of(serviceStep),
                        restoredObsidian.orElseThrow().targetCount());
                rebuiltObsidianAcquisition = true;
            } else if (restoredPolicy.profile()
                    == ServiceProfile.OBSIDIAN_PREFLIGHT) {
                serviceStep = GoalStep.obsidianPreflight(
                        restoredService.orElseThrow().serviceTargetCount());
                int continuationTarget = restoredObsidian
                        .map(CreateObsidianTask.RestoreMetadata::targetCount)
                        .orElse(restoredService.orElseThrow().serviceTargetCount());
                rebuildObsidianAcquisition(goal, restoredSteps, List.of(serviceStep),
                        continuationTarget);
                rebuiltObsidianAcquisition = true;
            } else if (restoredPolicy.profile()
                    == ServiceProfile.RARE_DESCENT_KIT) {
                serviceStep = GoalStep.rareDescentKitService(
                        interruptedServiceOres,
                        restoredService.orElseThrow().serviceTargetCount());
                // Entering KIT proves every bootstrap dependency was already completed. A fresh
                // inventory plan may rediscover resources currently stored in the mission chest;
                // retaining that acquisition prefix after replay would break the sealed
                // KIT->DESCEND hand-off and spend the freshly restored reserve twice. Keep only a
                // proven final descent/rare tail; otherwise run service alone and let the normal
                // postcondition repair plan from the completed live attestation.
                List<GoalStep> descentTail = rareDescentTail(
                        restoredSteps, interruptedServiceOres);
                restoredSteps.clear();
                restoredSteps.add(0, serviceStep);
                restoredSteps.addAll(descentTail);
            } else if (restoredPolicy.profile()
                    == ServiceProfile.RARE_ORE_BATCH) {
                serviceStep = GoalStep.rareOreService(
                        interruptedServiceOres,
                        restoredService.orElseThrow().serviceBoundary(),
                        restoredService.orElseThrow().serviceTargetCount());
                restoredSteps.add(0, serviceStep);
            } else {
                boolean explicitCapacityPolicy = !restoredPolicy.maintainsTunnelingTools()
                        && restoredPolicy.emergencyBlocksReserved()
                        > ServicePolicy.defaultOre(false)
                        .emergencyBlocksReserved();
                if ((explicitCapacityPolicy || capacityOrdinaryServiceRestore)
                        && ordinaryServiceParent.isEmpty()) {
                    queued.removeFirstOccurrence(goal);
                    recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                            GoalResult.classify(initialEvaluation, false),
                            "mission_restore_orphaned_capacity_handoff_cursor");
                    return false;
                }
                serviceStep = terminalOrdinaryServiceRestore || explicitCapacityPolicy
                        || capacityOrdinaryServiceRestore
                        ? GoalStep.miningHandoffService(interruptedServiceOres,
                        ordinaryServiceParent.orElseThrow().targetCount(),
                        restoredPolicy.emergencyBlocksReserved())
                        : GoalStep.miningService(interruptedServiceOres,
                        restore.completedSteps(), restoredPolicy.maintainsTunnelingTools());
                restoredSteps.add(0, serviceStep);
            }
        }
        if (committedPreflight) {
            int continuationTarget = restoredObsidian
                    .map(CreateObsidianTask.RestoreMetadata::targetCount)
                    .orElse(restoredService.orElseThrow().serviceTargetCount());
            rebuildObsidianAcquisition(goal, restoredSteps, List.of(), continuationTarget);
            rebuiltObsidianAcquisition = true;
        }
        if (!rebuiltObsidianAcquisition && interruptedObsidian) {
            rebuildObsidianAcquisition(goal, restoredSteps, List.of(),
                    restoredObsidian.orElseThrow().targetCount());
            rebuiltObsidianAcquisition = true;
        }
        if (!rebuiltObsidianAcquisition && !committedObsidianMake) {
            restoredObsidian.ifPresent(metadata -> reconcileObsidianSteps(
                    restoredSteps, metadata.targetCount(), false, false));
        }
        restoredDigDown.ifPresent(metadata -> reconcileDigDownSteps(restoredSteps, metadata));
        if (interruptedDescend) {
            reconcileDescendSteps(restoredSteps, restoredDescend.orElseThrow().targetY());
        }
        // replace boundary: A is active, B is already queued, then in the same batch: stop + B. B is removed from the old queue only after it has been successfully planned;
        // if planning the replacement fails, keep B so the next tick's normal queue drain can handle it again -- the goal must never be silently dropped.
        queued.removeFirstOccurrence(goal);
        if (restoredSteps.isEmpty()) {
            activePlans.remove(bot.getUUID());
            GoalResult.Status status = GoalResult.classify(initialEvaluation, false);
            String reason = !mergedSettledTerminalFailure.isBlank()
                    ? mergedSettledTerminalFailure
                    : restoredSettledServices.orElseThrow().isEmpty()
                    ? "empty_plan_unsatisfied"
                    : settledServiceFailureWithoutContinuation(
                    restoredSettledServices.orElseThrow());
            recordImmediateResult(bot, missionId, goal, startedTick,
                    initialEvaluation, status, reason,
                    restoredSkippedResults);
            return false;
        }
        HuntSearchCursor huntSearchCursor = restore == null
                ? HuntSearchCursor.initial() : restore.huntSearchCursor();
        ActivePlan active = new ActivePlan(missionId, startedTick, goal, predicate, context, mode,
                new ArrayDeque<>(restoredSteps), restoredSteps.size(),
                restoredSteps.stream().map(GoalStep::describe).toList(),
                huntSearchCursor, restoredSkippedTargets);
        active.huntPickupSettlement = unsettledHuntPickup;
        if (restore != null) {
            active.completedSteps = (int) Math.min(Integer.MAX_VALUE,
                    (long) restore.completedSteps()
                            + (restoredCommittedCapacityParent ? 1L : 0L));
            if (restore.batchCheckpoint().persisted()) {
                active.completedAtLastBatchCheckpoint =
                        restore.batchCheckpoint().completedAtCheckpoint();
                active.batchStepLimit = restore.batchCheckpoint().stepLimit();
                active.awaitingPlayerContinuation = true;
                active.strategyManuallyHeld = restore.batchCheckpoint().strategyManuallyHeld();
                active.strategyDecisionExhausted = restore.batchCheckpoint().strategyDecisionExhausted();
            }
            active.lifetimeReplans = restore.lifetimeReplans();
            active.rareResourceRetriesUsed = restoredRareResourceEpoch;
            active.rareEpochMarginUsed = restore.rareEpochMarginUsed();
            active.replanCount = restore.replanCount();
            if (restore.postconditionRepair().persisted()) {
                active.postconditionReplans =
                        restore.postconditionRepair().replans();
                active.lastEvaluationMatched =
                        restore.postconditionRepair().lastMatched();
                active.lastRepairFingerprint =
                        restore.postconditionRepair().fingerprint();
            }
            restore.replanSnapshot().ifPresent(snapshot -> {
                active.snapSteps = snapshot.steps();
                active.snapTargetCount = snapshot.targetCount();
                active.snapX = snapshot.x();
                active.snapY = snapshot.y();
                active.snapZ = snapshot.z();
                active.snapDimension = snapshot.dimension();
                active.snapHuntRawMeat = snapshot.huntRawMeat() >= 0
                        ? snapshot.huntRawMeat() : rawMeatCount(bot);
                active.snapHuntVisitedSectors = snapshot.huntVisitedSectors() >= 0
                        ? snapshot.huntVisitedSectors()
                        : active.huntSearchCursor.visitedCount();
            });
            if (!discardRestoredTaskCheckpoint) {
                active.taskCheckpointKind = restore.taskCheckpointKind();
                active.taskCheckpoint.putAll(restore.taskCheckpoint());
            }
            active.miningCheckpoint.putAll(restoredMiningCheckpoint);
            if (!(committedService && restoredAuxiliaryMining
                    .filter(metadata -> !metadata.batchOpen()).isPresent())) {
                active.auxiliaryMiningCheckpoint.putAll(
                        restoredAuxiliaryMiningCheckpoint);
            }
            active.capacityParentNamespace = restoredCapacityParent;
            active.capacityParentDelivered = restoredCapacityParentDelivered;
            active.capacityParentFace = restoredCapacityParentFace;
            active.capacityParentServicesUsed = restoredCapacityParentServicesUsed;
            active.auxiliaryMiningContinuationFingerprint =
                    restoredAuxiliaryMiningContinuationFingerprint;
            restoredSettledServices.orElseThrow().forEach(settled ->
                    active.settledServiceTombstones.put(settled.key(), settled));
            if (!committedObsidianMake) {
                active.obsidianCheckpoint.putAll(restoredObsidianCheckpoint);
            }
            // Backward-compatible recovery for snapshots written before the dedicated
            // cross-batch cursor namespace existed.
            if (!discardRestoredTaskCheckpoint
                    && active.miningCheckpoint.isEmpty()
                    && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE) {
                active.miningCheckpoint.putAll(restore.taskCheckpoint());
            }
            if (!committedObsidianMake && active.obsidianCheckpoint.isEmpty()
                    && restore.taskCheckpointKind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                active.obsidianCheckpoint.putAll(restore.taskCheckpoint());
            }
            if (committedObsidianMake
                    && active.taskCheckpointKind == GoalStep.Kind.MAKE_OBSIDIAN) {
                active.taskCheckpoint.clear();
                active.taskCheckpointKind = null;
            }
            if (committedDescend
                    && active.taskCheckpointKind == GoalStep.Kind.DESCEND_TO_Y) {
                active.taskCheckpoint.clear();
                active.taskCheckpointKind = null;
            }
            if (committedService
                    && active.taskCheckpointKind == GoalStep.Kind.MINING_SERVICE) {
                active.taskCheckpoint.clear();
                active.taskCheckpointKind = null;
            }
        }
        if (restore == null || !restore.postconditionRepair().persisted()) {
            active.lastEvaluationMatched = initialEvaluation.matched();
        }
        // Legacy snapshots did not persist the replan baseline. Start them from current factual
        // state so old completed steps or a negative mining Y cannot manufacture progress on the
        // first post-restart failure. New snapshots retain the exact prior comparison boundary.
        if (restore == null || restore.replanSnapshot().isEmpty()) {
            net.minecraft.core.BlockPos sp0 = bot.blockPosition();
            active.snapSteps = active.completedSteps;
            active.snapX = sp0.getX();
            active.snapY = sp0.getY();
            active.snapZ = sp0.getZ();
            active.snapTargetCount = goalTargetCount(bot, goal);
            active.snapDimension = bot.level().dimension()
                    .identifier().toString();
            active.snapHuntRawMeat = rawMeatCount(bot);
            active.snapHuntVisitedSectors = active.huntSearchCursor.visitedCount();
        }
        activePlans.put(bot.getUUID(), active);
        // Working-memory episode boundary: a new goal = a new episode, so the previous task's exclusions/trajectory are invalidated.
        // (A replan does not go through here -- handleStepFailure modifies plan.steps in place, and working memory surviving across a replan is intentional by design.)
        io.github.zoyluo.minecraftai.task.EpisodeMemory.INSTANCE.reset(bot.getUUID());
        userGoal.putIfAbsent(bot.getUUID(), goal); // B: record the first goal as the "user's original goal"; subsequent prerequisite sub-goals are blocked above, and switching goals is cleared by a user message
        BotLog.task(bot, "goal_plan", "goal", goal,
                "steps", restoredSteps.stream().map(GoalStep::describe).toList());
        if (!pocketRestorePreflights.contains(bot.getUUID())
                && !active.awaitingPlayerContinuation) {
            report(bot, "I will complete this goal in " + restoredSteps.size() + " steps.");
        }
        if (active.awaitingPlayerContinuation) {
            // Do not construct the next task before continuation authority is restored. Adaptive
            // missions are re-woken by BrainCoordinator with a fresh observation; legacy
            // missions still wait for explicit player approval.
            boolean adaptive = active.executionMode == ExecutionMode.ADAPTIVE;
            TaskManager.INSTANCE.pauseUserIntent(bot,
                    adaptive ? "restore_goal_strategy_checkpoint" : "restore_goal_batch_checkpoint");
            BotLog.task(bot, adaptive ? "goal_strategy_checkpoint_restored" : "goal_batch_checkpoint_restored",
                    "completed", active.completedSteps,
                    "remaining", active.steps.size(),
                    "step_limit", active.batchStepLimit);
            if (!adaptive) {
                report(bot, "This goal is still paused." + batchCheckpointReplyInstruction(active.steps.size()));
            }
            markDirty(bot);
            return true;
        }
        captureTransitionAndAssignNext(bot, active);
        return true;
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        MissionRuntimeRecord suspended = deathSuspended.get(bot.getUUID());
        if (suspended != null) {
            if (restoreQuarantined.containsKey(bot.getUUID())) {
                return true;
            }
            if (!bot.isAlive()) {
                return true;
            }
            String requiredDimension = dimensionSuspended.get(bot.getUUID());
            if (requiredDimension != null && !requiredDimension.equals(
                    bot.level().dimension().identifier().toString())) {
                return true;
            }
            Optional<Task> recovery = TaskManager.INSTANCE.getActive(bot);
            if (recovery.isPresent() || TaskManager.INSTANCE.hasPaused(bot)) {
                return true;
            }
            if (deathSuspended.remove(bot.getUUID(), suspended)) {
                dimensionSuspended.remove(bot.getUUID());
                restoreQuarantined.remove(bot.getUUID());
                BotLog.lifecycle(bot, "mission_death_resume",
                        "mission_id", suspended.active() == null ? "queued_only" : suspended.active().missionId());
                restoreRuntime(bot, suspended);
                markDirty(bot);
            }
            return hasActivePlan(bot) || queuedGoalCount(bot) > 0;
        }
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return hasActivePlan(bot) || queuedGoalCount(bot) > 0 || TaskManager.INSTANCE.hasPaused(bot);
        }
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null) {
            if (TaskManager.INSTANCE.getActive(bot).isEmpty()
                    && !TaskManager.INSTANCE.hasPaused(bot)
                    && !bot.getActionPack().hasActiveActions()) {
                return startNextQueuedIfIdle(bot);
            }
            return false;
        }
        if (plan.awaitingPlayerContinuation) {
            // A generic "resume" must not accidentally bypass the continuation authority.
            // Safety-origin work remains eligible because TaskManager never pauses safety tasks.
            if (!TaskManager.INSTANCE.isUserPaused(bot)) {
                TaskManager.INSTANCE.pauseUserIntent(bot, plan.executionMode == ExecutionMode.ADAPTIVE
                        ? "goal_strategy_checkpoint_guard" : "goal_batch_checkpoint_guard");
                markDirty(bot);
            }
            return true;
        }
        if (plan.huntSearchCursor.consumeDirty()) {
            markDirty(bot);
        }
        if (plan.currentTask instanceof HuntTask hunt
                && hunt.consumeCheckpointDirty()) {
            captureTaskEvidence(bot, plan);
            markDirty(bot);
        }
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (active.isPresent()) {
            if (plan.currentTask != null && active.get() != plan.currentTask) {
                TaskState missionState = plan.currentTask.state();
                if (missionState == TaskState.COMPLETED
                        || missionState == TaskState.FAILED) {
                    // A safety task may be assigned after TaskManager removed this terminal
                    // mission task but before GoalExecutor commits its checkpoint. Do not mistake
                    // that safety ownership for a user replacement, and do not assign the next
                    // mission step over it. Once safety releases the slot, the ordinary terminal
                    // branch below settles the original task exactly once.
                    return true;
                }
                // FREEZE fix: when a foreign task is active, first check whether our step was parked into the paused pool.
                // A survival task (combat/flee/eat) preempting the bot moves the current step's pauseFor into the paused pool -- this is a temporary preemption;
                // it resumes once that task is done, so the whole goal must never be abandoned (observed in testing: mob spawn -> combat -> goal_abandoned x12 -> replanning from scratch in a loop).
                if (TaskManager.INSTANCE.hasPaused(bot)) {
                    return true;
                }
                // The step is neither active nor in the paused pool = it was genuinely replaced by an explicit player instruction -> abandon the goal and yield.
                BotLog.task(bot, "goal_abandoned", "goal", plan.goal, "reason", "foreign_task_assigned");
                finishActive(bot, plan, evaluate(bot, plan), "foreign_task_assigned", false, true);
                return false;
            }
            return true;
        }
        // GOALFIX-GF1 P0-B: the current step was paused by the safety net (survival task preemption) -> wait for resume; do not mistake this for the step ending and skip ahead.
        if (TaskManager.INSTANCE.hasPaused(bot)) {
            return true;
        }
        if (plan.current == null) {
            assignNext(bot, plan);
            return true;
        }
        // SAFETY work has its own lifecycle and may have completed/failed after the mission step
        // stopped. The global lastStatus is therefore not an identity-safe source for the current
        // plan step (an Evade failure previously replaced OreDig's real terminal reason). Read the
        // task object that was actually assigned for this step.
        TaskStatus status = plan.currentTask == null
                ? TaskManager.INSTANCE.status(bot)
                : TaskStatus.from(plan.currentTask);
        if (status.state() == TaskState.COMPLETED) {
            BotLog.task(bot, "goal_step_completed", "step", plan.current.describe());
            captureTaskEvidence(bot, plan);
            if (plan.huntPickupSettlement) {
                settleRestoredHuntPickup(bot, plan);
                return true;
            }
            if (isTimedCollectionStep(plan.current)) {
                GoalEvaluation measuredOutcome = evaluate(bot, plan);
                GoalResult.Status outcome = measuredOutcome.matched() > 0
                        ? GoalResult.Status.COMPLETED : GoalResult.Status.FAILED;
                GoalEvaluation timedOutcome = outcome == GoalResult.Status.COMPLETED
                        ? completedTimedCollectionEvaluation(measuredOutcome) : measuredOutcome;
                finishActive(bot, plan, timedOutcome,
                        outcome == GoalResult.Status.COMPLETED
                                ? "collection_window_elapsed" : GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE,
                        false, true, outcome);
                return true;
            }
            if (plan.current.kind() == GoalStep.Kind.GIVE_ITEM
                    && !hasCommittedDelivery(plan, plan.current)) {
                finishActive(bot, plan, evaluate(bot, plan),
                        "fulfillment_delivery_receipt_invalid", false, true,
                        GoalResult.Status.FAILED);
                return true;
            }
            if (plan.current.kind() == GoalStep.Kind.MINE_ORE
                    && rareMissionTargetForMiningStep(plan.goal, plan.current.ores()) > 0) {
                int completedEpoch = plan.rareResourceRetriesUsed;
                if (!MissionRecoveryScheduler.settleCompletedRareBatch(plan)) {
                    finishActive(bot, plan, evaluate(bot, plan),
                            "rare_batch_commit_checkpoint_invalid", false, true);
                    return true;
                }
                BotLog.task(bot, "goal_rare_batch_resource_epoch_settled",
                        "completed_epoch", completedEpoch,
                        "next_epoch", 0);
            }
            if (plan.current.kind() == GoalStep.Kind.MINE_ORE
                    && plan.capacityParentNamespace != null
                    && plan.currentCapacityParentRetry
                    && MissionRecoveryScheduler.currentOreTaskOwnsCapacityParent(plan)
                    && !MissionRecoveryScheduler.settleCompletedCapacityParent(plan)) {
                finishActive(bot, plan, evaluate(bot, plan),
                        "capacity_parent_commit_checkpoint_invalid", false, true,
                        GoalResult.Status.FAILED);
                return true;
            }
            if (plan.current.kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                Optional<CreateObsidianTask.RestoreMetadata> metadata =
                        CreateObsidianTask.inspectCheckpoint(plan.obsidianCheckpoint);
                if (metadata.isEmpty()) {
                    finishActive(bot, plan, evaluate(bot, plan),
                            "create_obsidian_completed_without_checkpoint", false, true);
                    return true;
                }
                if (metadata.isPresent() && metadata.orElseThrow().pendingServiceBoundary() > 0) {
                    GoalEvaluation boundaryEvaluation = evaluate(bot, plan);
                    if (boundaryEvaluation.state() == GoalEvaluation.State.SATISFIED) {
                        finishActive(bot, plan, boundaryEvaluation,
                                "postcondition_satisfied_at_obsidian_boundary", false, true);
                        return true;
                    }
                    int boundary = metadata.orElseThrow().pendingServiceBoundary();
                    clearCompletedTaskCheckpoint(plan);
                    plan.completedSteps++;
                    // addFirst in reverse execution order: service commits before the original
                    // target CreateObsidianTask is reconstructed from its dedicated checkpoint.
                    GoalStep continuation = GoalStep.makeObsidian(
                            metadata.orElseThrow().targetCount());
                    GoalStep service = GoalStep.obsidianService(
                            boundary, metadata.orElseThrow().targetCount());
                    plan.steps.addFirst(continuation);
                    plan.steps.addFirst(service);
                    plan.totalSteps += 2;
                    plan.stepLabels.add(service.describe());
                    plan.stepLabels.add(continuation.describe());
                    BotLog.task(bot, "goal_obsidian_service_scheduled",
                            "boundary", boundary,
                            "target", metadata.orElseThrow().targetCount());
                    plan.current = null;
                    plan.currentTask = null;
                    if (requestBatchCheckpointIfDue(bot, plan)) {
                        return true;
                    }
                    captureTransitionAndAssignNext(bot, plan);
                    return true;
                }
            }
            if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                    && plan.current.isObsidianService()) {
                Optional<CreateObsidianTask.RestoreMetadata> metadata =
                        CreateObsidianTask.inspectCheckpoint(plan.obsidianCheckpoint);
                int expectedBoundary = metadata
                        .map(CreateObsidianTask.RestoreMetadata::pendingServiceBoundary)
                        .orElse(0);
                Optional<MiningServiceTask.RestoreMetadata> serviceMetadata =
                        MiningServiceTask.inspectCheckpoint(plan.taskCheckpoint);
                boolean identityMatches = metadata.isPresent() && serviceMetadata.isPresent()
                        && serviceMetadata.orElseThrow().done()
                        && serviceMetadata.orElseThrow().policy().profile()
                        == ServiceProfile.OBSIDIAN_8
                        && plan.missionId.toString().equals(
                        serviceMetadata.orElseThrow().serviceMissionId())
                        && serviceMetadata.orElseThrow().serviceTargetCount()
                        == metadata.orElseThrow().targetCount()
                        && serviceMetadata.orElseThrow().serviceBoundary() == expectedBoundary
                        && serviceMetadata.orElseThrow().policy().equals(
                        ServicePolicy.obsidian8(
                                metadata.orElseThrow().targetCount(), expectedBoundary));
                Optional<Map<String, String>> acknowledged = identityMatches
                        && expectedBoundary == plan.current.count()
                        ? CreateObsidianTask.acknowledgeServiceBoundary(plan.obsidianCheckpoint)
                        : Optional.empty();
                if (acknowledged.isEmpty()) {
                    finishActive(bot, plan, evaluate(bot, plan),
                            "obsidian_service_ack_failed:expected=" + expectedBoundary
                                    + ":completed=" + plan.current.count(), false, true);
                    return true;
                }
                plan.obsidianCheckpoint.clear();
                plan.obsidianCheckpoint.putAll(acknowledged.orElseThrow());
                BotLog.task(bot, "goal_obsidian_service_acknowledged",
                        "boundary", expectedBoundary);
            }
            if (plan.current.kind() == GoalStep.Kind.ACQUIRE_WATER) {
                retireRelocatedOrdinaryMiningCheckpoint(bot, plan);
            }
            if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE) {
                retireClosedAuxiliaryMiningCheckpoint(plan);
            }
            clearCompletedTaskCheckpoint(plan);
            plan.completedSteps++; // Phase A: completing a step = a progress signal
            GoalEvaluation completedEvaluation = evaluate(bot, plan);
            if (completedEvaluation.state() == GoalEvaluation.State.SATISFIED) {
                finishActive(bot, plan, completedEvaluation,
                        "postcondition_satisfied_after_step", false, true);
                return true;
            }
            plan.current = null;
            plan.currentTask = null;
            if (requestBatchCheckpointIfDue(bot, plan)) {
                return true;
            }
            captureTransitionAndAssignNext(bot, plan);
            return true;
        }
        if (status.state() == TaskState.FAILED) {
            if ((status.failureReason().startsWith("mining_service_dimension_mismatch:")
                    || status.failureReason().startsWith("hunt_pickup_dimension_mismatch:"))
                    && suspendDimensionBoundPocket(bot, plan)) {
                return true;
            }
            handleStepFailure(server, bot, plan, status.failureReason());
            return true;
        }
        // GOALFIX-GF1 P0-B: other statuses (such as a leftover lastStatus from the previous task) -> defensive no-op;
        // step advancement is driven only by the COMPLETED branch, and failure only by the FAILED branch.
        return true;
    }

    private void settleRestoredHuntPickup(AIPlayerEntity bot, ActivePlan plan) {
        Optional<HuntPickupCheckpoint.Metadata> receipt =
                HuntPickupCheckpoint.inspect(plan.taskCheckpoint);
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.HUNT
                || !(plan.currentTask instanceof HuntTask hunt)
                || !hunt.isSettlementOnly()
                || receipt.isEmpty() || receipt.orElseThrow().open()
                || !trustedClosedHuntPickupReceipt(
                bot, receipt.orElseThrow())) {
            finishActive(bot, plan, evaluate(bot, plan),
                    "hunt_pickup_settlement_receipt_invalid",
                    false, true, GoalResult.Status.FAILED);
            return;
        }
        plan.taskCheckpoint.clear();
        plan.taskCheckpointKind = null;
        plan.huntPickupSettlement = false;
        plan.current = null;
        plan.currentTask = null;
        plan.steps.clear();

        GoalEvaluation evaluation = evaluate(bot, plan);
        if (evaluation.state() == GoalEvaluation.State.SATISFIED) {
            finishActive(bot, plan, evaluation,
                    "postcondition_satisfied_after_hunt_pickup_settlement",
                    false, true);
            return;
        }
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
        List<GoalStep> continuation = applySkippedTargetReceipts(
                fresh.success() ? fresh.steps() : List.of(),
                plan.skippedTargetReceipts);
        if (!fresh.success() || continuation.isEmpty()) {
            finishActive(bot, plan, evaluation,
                    fresh.success() ? "hunt_pickup_settlement_replan_empty"
                            : "hunt_pickup_settlement_replan_failed:"
                            + String.join(",", fresh.unresolved()),
                    false, true);
            return;
        }
        plan.steps.addAll(continuation);
        plan.totalSteps = continuation.size();
        plan.stepLabels.clear();
        plan.stepLabels.addAll(
                continuation.stream().map(GoalStep::describe).toList());
        BotLog.task(bot, "goal_hunt_pickup_settlement_replanned",
                "steps", continuation.stream().map(GoalStep::describe).toList(),
                "postcondition_replans", plan.postconditionReplans,
                "failure_replans", plan.replanCount);
        captureTransitionAndAssignNext(bot, plan);
    }

    static boolean hasSameDimensionOpenHuntTimeRollback(
            HuntPickupCheckpoint.Metadata metadata,
            String liveDimension,
            long currentWorldTime) {
        return metadata != null && metadata.open()
                && metadata.dimension().equals(liveDimension)
                && currentWorldTime < metadata.pickupStartedWorldTime();
    }

    static boolean trustedClosedHuntPickupReceipt(
            HuntPickupCheckpoint.Metadata metadata,
            String liveDimension,
            int currentInventory,
            int currentPickupStat,
            long currentWorldTime) {
        if (metadata == null || metadata.open()
                || !metadata.dimension().equals(liveDimension)) {
            return false;
        }
        return switch (metadata.transactionState()) {
            case CLOSED_COLLECTED -> HuntPickupCheckpoint.collectionCoversBoundUnits(
                    metadata.inventoryBaseline(), currentInventory,
                    metadata.pickupStatBaseline(), currentPickupStat,
                    Math.max(1, metadata.boundUnits()));
            case CLOSED_NO_RAW -> metadata.boundUnits() == 0
                    && HuntPickupCheckpoint.ageAt(
                    metadata.pickupStartedWorldTime(), currentWorldTime)
                    >= HuntPickupCheckpoint.RECOVERY_LIMIT_TICKS;
            case OPEN -> false;
        };
    }

    private static boolean trustedClosedHuntPickupReceipt(
            AIPlayerEntity bot, HuntPickupCheckpoint.Metadata metadata) {
        Identifier expectedId = metadata == null
                ? null : Identifier.tryParse(metadata.expectedRawItemId());
        Item expected = expectedId == null
                ? null : BuiltInRegistries.ITEM.getOptional(expectedId).orElse(null);
        if (expected == null
                || !BuiltInRegistries.ITEM.getKey(expected).toString().equals(
                metadata.expectedRawItemId())) {
            return false;
        }
        int inventory = io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(
                bot, Set.of(expected));
        int pickupStat = bot.getStats().getValue(Stats.ITEM_PICKED_UP, expected);
        return trustedClosedHuntPickupReceipt(
                metadata,
                bot.level().dimension().identifier().toString(),
                inventory,
                pickupStat,
                bot.level().getGameTime());
    }

    public boolean hasActivePlan(AIPlayerEntity bot) {
        return activePlans.containsKey(bot.getUUID()) || deathSuspended.containsKey(bot.getUUID());
    }

    /** Exact active-goal probe used by deterministic runtime verification and diagnostics. */
    public boolean isActiveGoal(AIPlayerEntity bot, Goal goal) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan != null) {
            return sameGoalIntent(plan.goal, goal);
        }
        MissionRuntimeRecord suspended = deathSuspended.get(bot.getUUID());
        return suspended != null && suspended.active() != null
                && suspended.active().spec() != null
                && suspended.active().spec().toGoal()
                .filter(candidate -> sameGoalIntent(candidate, goal)).isPresent();
    }

    /**
     * A repeated public fulfillment call snapshots a later inventory state, but it is still the
     * same in-flight player intent.  Dedupe that retry by its canonical allocation manifest
     * while never conflating a fresh transaction with an old absolute-inventory fulfillment.
     */
    private static boolean sameGoalIntent(Goal left, Goal right) {
        if (left instanceof Goal.Fulfill leftFulfill && right instanceof Goal.Fulfill rightFulfill) {
            return leftFulfill.isFreshInventoryRequest() == rightFulfill.isFreshInventoryRequest()
                    && leftFulfill.allocations().equals(rightFulfill.allocations());
        }
        return java.util.Objects.equals(left, right);
    }

    public void clear(AIPlayerEntity bot) {
        cancelAll(bot);
    }

    /** Detaches only the active mission. Queue promotion is deliberately a separate step. */
    public boolean cancelCurrent(AIPlayerEntity bot) {
        return cancelCurrent(bot, "intent_cancelled");
    }

    public boolean cancelCurrent(AIPlayerEntity bot, String reason) {
        UUID uuid = bot.getUUID();
        ActivePlan active = activePlans.get(uuid);
        MissionRuntimeRecord suspended = deathSuspended.remove(uuid);
        dimensionSuspended.remove(uuid);
        restoreQuarantined.remove(uuid);
        boolean changed = active != null || suspended != null;
        if (active != null) {
            finishActive(bot, active, evaluate(bot, active), reason, true, false);
        }
        changed |= userGoal.remove(uuid) != null;
        changed |= lastGoalFailTick.remove(uuid) != null;
        if (changed) {
            io.github.zoyluo.minecraftai.task.EpisodeMemory.INSTANCE.reset(uuid);
            // Suspended/quarantined Missions have no ActivePlan for finishActive to persist.
            // Explicit cancellation must durably remove their raw physical checkpoint as well.
            markDirty(bot);
        }
        return changed;
    }

    public int clearQueue(AIPlayerEntity bot) {
        java.util.Deque<QueuedGoal> queued = goalQueue.remove(bot.getUUID());
        int removed = queued == null ? 0 : queued.size();
        if (removed > 0) {
            markDirty(bot);
        }
        return removed;
    }

    public boolean cancelAll(AIPlayerEntity bot) {
        boolean changed = cancelCurrent(bot);
        return clearQueue(bot) > 0 || changed;
    }

    /** Detach a Mission without publishing a terminal result while corpse recovery runs. */
    public boolean suspendForDeath(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        if (deathSuspended.containsKey(uuid)) {
            return true;
        }
        MissionRuntimeRecord runtime = captureRuntime(bot);
        if (runtime.active() == null && runtime.queue().isEmpty()) {
            return false;
        }
        dimensionSuspended.remove(uuid);
        restoreQuarantined.remove(uuid);
        deathSuspended.put(uuid, runtime);
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        userGoal.remove(uuid);
        BotLog.lifecycle(bot, "mission_death_suspended",
                "mission_id", runtime.active() == null ? "queued_only" : runtime.active().missionId(),
                "queued", runtime.queue().size());
        markDirty(bot);
        return true;
    }

    /**
     * Detaches an open service pocket without publishing failure when external movement changes
     * dimensions. The exact ledger remains persistable in deathSuspended; tickBot restores it only
     * after the bot returns to the checkpoint's dimension.
     */
    private boolean suspendDimensionBoundPocket(AIPlayerEntity bot, ActivePlan plan) {
        if (bot == null || plan == null || plan.current == null) {
            return false;
        }
        captureTaskEvidence(bot, plan);
        Optional<String> required = Optional.empty();
        if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE) {
            required =
                MiningServiceTask.inspectCheckpoint(plan.taskCheckpoint)
                        .filter(ignored -> hasActiveServicePocket(plan.taskCheckpoint))
                        .map(MiningServiceTask.RestoreMetadata::serviceDimension);
        } else if (plan.current.kind() == GoalStep.Kind.HUNT) {
            required = HuntPickupCheckpoint.inspect(plan.taskCheckpoint)
                    .filter(HuntPickupCheckpoint.Metadata::open)
                    .map(HuntPickupCheckpoint.Metadata::dimension);
        }
        if (required.isEmpty()) {
            return false;
        }
        MissionRuntimeRecord runtime = captureRuntime(bot);
        if (runtime.active() == null) {
            return false;
        }
        UUID uuid = bot.getUUID();
        String requiredDimension = required.orElseThrow();
        deathSuspended.put(uuid, runtime);
        dimensionSuspended.put(uuid, requiredDimension);
        restoreQuarantined.remove(uuid);
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        userGoal.remove(uuid);
        TaskManager.INSTANCE.abort(bot);
        BotLog.lifecycle(bot, "mission_dimension_suspended",
                "mission_id", runtime.active().missionId(),
                "required_dimension", requiredDimension,
                "live_dimension", bot.level().dimension()
                        .identifier().toString());
        markDirty(bot);
        return true;
    }

    /** Drop every in-memory projection for a Bot without publishing a terminal result (server unload path). */
    public void unload(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        lastGoalFailTick.remove(uuid);
        userGoal.remove(uuid);
        lastResults.remove(uuid);
        deathSuspended.remove(uuid);
        dimensionSuspended.remove(uuid);
        restoreQuarantined.remove(uuid);
        pocketRestorePreflights.remove(uuid);
        io.github.zoyluo.minecraftai.task.EpisodeMemory.INSTANCE.reset(uuid);
    }

    public void clearAllRuntime() {
        activePlans.clear();
        goalQueue.clear();
        lastGoalFailTick.clear();
        userGoal.clear();
        lastResults.clear();
        deathSuspended.clear();
        dimensionSuspended.clear();
        restoreQuarantined.clear();
        pocketRestorePreflights.clear();
    }

    public int queuedGoalCount(AIPlayerEntity bot) {
        java.util.Deque<QueuedGoal> queued = goalQueue.get(bot.getUUID());
        return queued == null ? 0 : queued.size();
    }

    /** True only when this goal deliberately stopped at a safe player-confirmation boundary. */
    public boolean isAwaitingBatchContinuation(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan != null && plan.awaitingPlayerContinuation;
    }

    /**
     * Read-only checkpoint facts for normal-chat routing and the companion UI.  The next step is
     * deliberately descriptive rather than a mutable task object, so callers cannot accidentally
     * advance a paused mission just by inspecting it.
     */
    public Optional<BatchCheckpointStatus> batchCheckpointStatus(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null) {
            return Optional.empty();
        }
        GoalStep next = plan.steps.peekFirst();
        return Optional.of(new BatchCheckpointStatus(
                plan.awaitingPlayerContinuation,
                Math.max(0, plan.completedSteps - plan.completedAtLastBatchCheckpoint),
                plan.batchStepLimit,
                plan.steps.size(),
                next == null ? "" : next.describe()));
    }

    /**
     * Returns a token only while an adaptive mission is parked at a safe whole-stage boundary.
     * A caller cannot use this to inspect or advance a mining/service transaction in flight.
     */
    public Optional<StrategyCheckpointStatus> strategyCheckpointStatus(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.executionMode != ExecutionMode.ADAPTIVE
                || !plan.awaitingPlayerContinuation || plan.strategyManuallyHeld
                || plan.strategyDecisionExhausted
                || !hasNoOpenStepTransaction(plan)) {
            return Optional.empty();
        }
        GoalStep next = plan.steps.peekFirst();
        if (next == null) {
            return Optional.empty();
        }
        return Optional.of(new StrategyCheckpointStatus(
                plan.missionId.toString(),
                plan.completedAtLastBatchCheckpoint,
                strategyGoalDescription(plan.goal),
                plan.steps.size(),
                next.describe()));
    }

    /** True for an adaptive safe boundary even when the human has explicitly put it on hold. */
    public boolean hasAdaptiveStrategyCheckpoint(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan != null && plan.executionMode == ExecutionMode.ADAPTIVE
                && plan.awaitingPlayerContinuation && hasNoOpenStepTransaction(plan);
    }

    /**
     * Converts the executor's automatic strategy pause into an explicit human hold.  This is
     * persisted with the checkpoint so the idle watcher cannot restart a cancelled/in-flight
     * model decision after the player says pause.
     */
    public boolean holdStrategyCheckpoint(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.executionMode != ExecutionMode.ADAPTIVE
                || !plan.awaitingPlayerContinuation || plan.strategyManuallyHeld
                || !hasNoOpenStepTransaction(plan)) {
            return false;
        }
        plan.strategyManuallyHeld = true;
        markDirty(bot);
        BotLog.task(bot, "goal_strategy_checkpoint_held_by_player",
                "mission_id", plan.missionId,
                "revision", plan.completedAtLastBatchCheckpoint);
        return true;
    }

    /** Persists that the bounded strategy allowance for this exact revision was spent. */
    public boolean exhaustStrategyCheckpoint(AIPlayerEntity bot, String missionId, long revision) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.executionMode != ExecutionMode.ADAPTIVE
                || !plan.awaitingPlayerContinuation
                || missionId == null || !plan.missionId.toString().equals(missionId)
                || revision != plan.completedAtLastBatchCheckpoint
                || !hasNoOpenStepTransaction(plan)) {
            return false;
        }
        plan.strategyDecisionExhausted = true;
        markDirty(bot);
        BotLog.task(bot, "goal_strategy_checkpoint_budget_exhausted",
                "mission_id", plan.missionId, "revision", revision);
        return true;
    }

    /**
     * Authorizes exactly the server-generated next stage for an adaptive mission.  The mission id
     * and revision are mandatory so a delayed model result cannot resume a superseded goal.
     */
    public boolean continueStrategyCheckpoint(AIPlayerEntity bot, String missionId, long revision) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.executionMode != ExecutionMode.ADAPTIVE
                || !plan.awaitingPlayerContinuation || plan.strategyManuallyHeld
                || missionId == null || !plan.missionId.toString().equals(missionId)
                || revision != plan.completedAtLastBatchCheckpoint
                || !hasNoOpenStepTransaction(plan)) {
            return false;
        }
        if (finishStrategyCheckpointIfSatisfied(bot, plan,
                "postcondition_satisfied_at_strategy_checkpoint")) {
            return true;
        }
        resumeCheckpoint(bot, plan, "goal_strategy_checkpoint_continue", false);
        return true;
    }

    /**
     * Rebuilds only the remaining declarative plan from the current authoritative inventory and
     * observations.  It is legal solely at the same safe strategy boundary as continuation, so
     * it can never discard an open mining/service transaction or an ambiguous handoff.
     */
    public boolean replanStrategyCheckpoint(AIPlayerEntity bot, String missionId, long revision) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.executionMode != ExecutionMode.ADAPTIVE
                || !plan.awaitingPlayerContinuation || plan.strategyManuallyHeld
                || missionId == null || !plan.missionId.toString().equals(missionId)
                || revision != plan.completedAtLastBatchCheckpoint
                || !hasNoOpenStepTransaction(plan)) {
            return false;
        }
        if (finishStrategyCheckpointIfSatisfied(bot, plan,
                "postcondition_satisfied_before_strategy_replan")) {
            return true;
        }
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, plan.goal, snapshotContext(plan),
                plan.missionId.toString());
        List<GoalStep> replanned = applySkippedTargetReceipts(
                fresh.success() ? fresh.steps() : List.of(), plan.skippedTargetReceipts);
        if (!fresh.success()) {
            BotLog.task(bot, "goal_strategy_replan_rejected",
                    "mission_id", plan.missionId,
                    "revision", revision,
                    "unresolved", fresh.unresolved());
            return false;
        }
        if (replanned.isEmpty()) {
            // The declarative planner sees no remaining work. Only the independent final
            // predicate may convert that into completion; otherwise keep the checkpoint parked
            // rather than claiming success from a planner/evaluator disagreement.
            if (finishStrategyCheckpointIfSatisfied(bot, plan,
                    "postcondition_satisfied_after_empty_strategy_replan")) {
                return true;
            }
            BotLog.task(bot, "goal_strategy_replan_rejected",
                    "mission_id", plan.missionId,
                    "revision", revision,
                    "unresolved", "planner_returned_no_steps_but_postcondition_is_unsatisfied");
            return false;
        }
        plan.steps.clear();
        plan.steps.addAll(replanned);
        plan.stepLabels.clear();
        plan.stepLabels.addAll(replanned.stream().map(GoalStep::describe).toList());
        plan.totalSteps = replanned.size();
        BotLog.task(bot, "goal_strategy_replanned",
                "mission_id", plan.missionId,
                "revision", revision,
                "steps", replanned.stream().map(GoalStep::describe).toList());
        resumeCheckpoint(bot, plan, "goal_strategy_checkpoint_replan", false);
        return true;
    }

    /**
     * Cancels exactly the revision-bound adaptive root mission. Unlike the generic chat stop
     * command, this cannot be applied by a delayed model response to whichever mission happens
     * to be active later.
     */
    public boolean stopStrategyCheckpoint(AIPlayerEntity bot, String missionId, long revision) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.executionMode != ExecutionMode.ADAPTIVE
                || !plan.awaitingPlayerContinuation || plan.strategyManuallyHeld
                || missionId == null || !plan.missionId.toString().equals(missionId)
                || revision != plan.completedAtLastBatchCheckpoint
                || !hasNoOpenStepTransaction(plan)) {
            return false;
        }
        resumeCheckpoint(bot, plan, "goal_strategy_checkpoint_stop", false);
        finishActive(bot, plan, evaluate(bot, plan), "strategy_checkpoint_stopped", true, true);
        return true;
    }

    /**
     * A player may have supplied items or completed a delivery while the model was deciding.
     * Re-check the authoritative predicate before either resuming or rebuilding a paused plan so
     * an adaptive boundary never performs a duplicate stage from stale inventory facts.
     */
    private boolean finishStrategyCheckpointIfSatisfied(AIPlayerEntity bot,
                                                         ActivePlan plan,
                                                         String reason) {
        GoalEvaluation evaluation = evaluate(bot, plan);
        if (evaluation.state() != GoalEvaluation.State.SATISFIED) {
            return false;
        }
        // The pause was installed by the just-completed safe stage. Release it before publishing
        // the terminal result so a queued, separately requested goal is not left inert.
        resumeCheckpoint(bot, plan, "goal_strategy_checkpoint_already_satisfied", false);
        finishActive(bot, plan, evaluation, reason, false, true);
        return true;
    }

    /**
     * Releases only an automatic batch checkpoint.  This intentionally does not resume an
     * ordinary user pause, and it never creates a new plan: the existing goal queue and exact
     * current continuation remain authoritative.
     */
    public boolean resumeBatchCheckpoint(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || !plan.awaitingPlayerContinuation) {
            return false;
        }
        resumeCheckpoint(bot, plan, "goal_batch_checkpoint_continue", true);
        return true;
    }

    private static void resumeCheckpoint(AIPlayerEntity bot,
                                         ActivePlan plan,
                                         String resumeReason,
                                         boolean playerFacing) {
        plan.awaitingPlayerContinuation = false;
        plan.strategyManuallyHeld = false;
        plan.strategyDecisionExhausted = false;
        plan.completedAtLastBatchCheckpoint = plan.completedSteps;
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            TaskManager.INSTANCE.resumeUserIntent(bot, resumeReason);
        }
        BotLog.task(bot, "goal_batch_checkpoint_resumed",
                "completed", plan.completedSteps,
                "remaining", plan.steps.size(),
                "step_limit", plan.batchStepLimit);
        if (playerFacing) {
            report(bot, "Continuing the next batch of the goal.");
        }
        markDirty(bot);
    }

    public Optional<GoalResult> lastResult(AIPlayerEntity bot) {
        return Optional.ofNullable(lastResults.get(bot.getUUID()));
    }

    public Optional<GoalResult> resultAfter(AIPlayerEntity bot, long sequence) {
        GoalResult result = lastResults.get(bot.getUUID());
        return result != null && result.sequence() > sequence ? Optional.of(result) : Optional.empty();
    }

    public String resultSummary(GoalResult result) {
        return resultMessage(result);
    }

    public MissionRuntimeRecord captureRuntime(AIPlayerEntity bot) {
        MissionRuntimeRecord suspended = deathSuspended.get(bot.getUUID());
        if (suspended != null) {
            return suspended;
        }
        ActivePlan active = activePlans.get(bot.getUUID());
        if (active != null) {
            captureTaskEvidence(bot, active);
        }
        MissionRecord activeRecord = active == null ? null : new MissionRecord(
                active.missionId.toString(), MissionSpec.fromGoal(active.goal, active.executionMode), checkpoint(active));
        java.util.Deque<QueuedGoal> queued = goalQueue.get(bot.getUUID());
        List<MissionSpec> queue = queued == null ? List.of() : queued.stream().map(QueuedGoal::spec).toList();
        boolean hasPersistableMission = activeRecord != null || !queue.isEmpty();
        return new MissionRuntimeRecord(activeRecord, queue,
                hasPersistableMission && TaskManager.INSTANCE.isUserPaused(bot));
    }

    public void restoreRuntime(AIPlayerEntity bot, MissionRuntimeRecord runtime) {
        if (runtime == null) {
            return;
        }
        Map<String, String> rawTaskCheckpoint = activeTaskCheckpoint(runtime);
        boolean rawActivePocket = hasActiveServicePocket(rawTaskCheckpoint);
        Optional<MiningServiceTask.RestoreMetadata> rawService =
                MiningServiceTask.inspectCheckpoint(rawTaskCheckpoint);
        if (rawActivePocket
                && rawService.filter(metadata -> !metadata.serviceDimension().isBlank())
                .isEmpty()) {
            quarantinePhysicalPocketRestore(bot, runtime,
                    "invalid_mining_service_pocket_checkpoint");
            return;
        }
        Optional<String> pocketDimension = activePocketServiceDimension(runtime);
        String liveDimension = bot.level().dimension()
                .identifier().toString();
        if (pocketDimension.filter(required -> !required.equals(liveDimension)).isPresent()) {
            UUID uuid = bot.getUUID();
            deathSuspended.put(uuid, runtime);
            dimensionSuspended.put(uuid, pocketDimension.orElseThrow());
            restoreQuarantined.remove(uuid);
            activePlans.remove(uuid);
            goalQueue.remove(uuid);
            userGoal.remove(uuid);
            TaskManager.INSTANCE.abort(bot);
            BotLog.lifecycle(bot, "mission_dimension_suspended",
                    "required_dimension", pocketDimension.orElseThrow(),
                    "live_dimension", liveDimension);
            markDirty(bot);
            return;
        }
        MissionRecord activeRecord = runtime.active();
        if (rawActivePocket
                && (activeRecord == null || activeRecord.spec() == null)) {
            quarantinePhysicalPocketRestore(bot, runtime,
                    "missing_goal_for_mining_service_pocket");
            return;
        }
        if (activeRecord != null && activeRecord.spec() != null) {
            Optional<Goal> restored = activeRecord.spec().toGoal();
            if (restored.isPresent()) {
                boolean submitted;
                if (rawActivePocket) {
                    pocketRestorePreflights.add(bot.getUUID());
                }
                try {
                    submitted = submit(bot, restored.get(),
                            restoreSeed(bot, restored.get(), activeRecord), activeRecord.spec().executionMode());
                    if (rawActivePocket && (!submitted
                            || !restoredActivePocketAuthority(
                            bot, activeRecord.missionId(), rawTaskCheckpoint))) {
                        quarantinePhysicalPocketRestore(bot, runtime,
                                "mining_service_pocket_restore_not_established");
                        return;
                    }
                } catch (RuntimeException restoreFailure) {
                    if (!rawActivePocket) {
                        throw restoreFailure;
                    }
                    BotLog.error(bot, "mission_pocket_restore_failed",
                            restoreFailure, "mission_id", activeRecord.missionId());
                    quarantinePhysicalPocketRestore(bot, runtime,
                            "mining_service_pocket_restore_start_failed");
                    return;
                } finally {
                    if (rawActivePocket) {
                        pocketRestorePreflights.remove(bot.getUUID());
                    }
                }
                // This is a replay of an existing mission, not a new plan announcement. Commit
                // exactly the task-level assignment that was withheld during semantic preflight.
                if (rawActivePocket
                        && !TaskManager.INSTANCE.publishCurrentAssignment(bot)) {
                    quarantinePhysicalPocketRestore(bot, runtime,
                            "mining_service_pocket_restore_assignment_lost");
                    return;
                }
            } else if (restored.isEmpty()) {
                if (rawActivePocket) {
                    quarantinePhysicalPocketRestore(bot, runtime,
                            "invalid_goal_for_mining_service_pocket");
                    return;
                }
                BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                        "mission_restore_isolated", "type", activeRecord.spec().type(), "reason", "invalid_spec");
            }
        }
        for (MissionSpec spec : runtime.queue()) {
            Optional<Goal> queued = spec.toGoal();
            if (queued.isPresent()) {
                submit(bot, queued.get(), null, spec.executionMode());
            } else {
                BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                        "mission_queue_restore_isolated", "type", spec.type(), "reason", "invalid_spec");
            }
        }
        if (runtime.userPaused() && (hasActivePlan(bot) || queuedGoalCount(bot) > 0)) {
            TaskManager.INSTANCE.pauseUserIntent(bot, "restore_persisted_pause");
        }
    }

    private boolean restoredActivePocketAuthority(AIPlayerEntity bot,
                                                  String missionId,
                                                  Map<String, String> rawCheckpoint) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || missionId == null
                || !plan.missionId.toString().equals(missionId)
                || plan.current == null
                || plan.current.kind() != GoalStep.Kind.MINING_SERVICE
                || !(plan.currentTask instanceof MiningServiceTask service)
                || (service.state() != TaskState.RUNNING
                && service.state() != TaskState.PAUSED)
                || TaskManager.INSTANCE.getActive(bot)
                .filter(task -> task == service).isEmpty()) {
            return false;
        }
        Map<String, String> liveCheckpoint = service.checkpoint();
        if (!hasActiveServicePocket(liveCheckpoint)) {
            return false;
        }
        Optional<MiningServiceTask.RestoreMetadata> raw =
                MiningServiceTask.inspectCheckpoint(rawCheckpoint);
        Optional<MiningServiceTask.RestoreMetadata> live =
                MiningServiceTask.inspectCheckpoint(liveCheckpoint);
        if (raw.isEmpty() || live.isEmpty()
                || !persistedServiceAuthority(raw.orElseThrow()).equals(
                persistedServiceAuthority(live.orElseThrow()))) {
            return false;
        }
        if (!java.util.Objects.equals(rawCheckpoint.get("phase"),
                liveCheckpoint.get("phase"))) {
            return false;
        }
        return SERVICE_POCKET_IDENTITY_KEYS.stream().allMatch(key ->
                java.util.Objects.equals(
                        rawCheckpoint.get(key), liveCheckpoint.get(key)));
    }

    private void quarantinePhysicalPocketRestore(AIPlayerEntity bot,
                                                 MissionRuntimeRecord runtime,
                                                 String reason) {
        UUID uuid = bot.getUUID();
        deathSuspended.put(uuid, runtime);
        dimensionSuspended.remove(uuid);
        restoreQuarantined.put(uuid, reason);
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        userGoal.remove(uuid);
        lastResults.remove(uuid);
        lastGoalFailTick.remove(uuid);
        TaskManager.INSTANCE.resetToIdle(bot);
        BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                "mission_restore_quarantined", "reason", reason,
                "mission_id", runtime.active() == null
                        ? "queued_only" : runtime.active().missionId());
        markDirty(bot);
    }

    private static Optional<String> activePocketServiceDimension(
            MissionRuntimeRecord runtime) {
        Map<String, String> task = activeTaskCheckpoint(runtime);
        MissionRecord active = runtime == null ? null : runtime.active();
        String taskKind = active == null || active.checkpoint() == null
                ? "" : active.checkpoint().getOrDefault("task_kind", "");
        Optional<String> huntDimension = "HUNT".equals(taskKind)
                ? HuntPickupCheckpoint.inspect(task)
                .filter(HuntPickupCheckpoint.Metadata::open)
                .map(HuntPickupCheckpoint.Metadata::dimension)
                : Optional.empty();
        if (huntDimension.isPresent()) {
            return huntDimension;
        }
        if (!hasActiveServicePocket(task)) {
            return Optional.empty();
        }
        return MiningServiceTask.inspectCheckpoint(task)
                .map(MiningServiceTask.RestoreMetadata::serviceDimension)
                .filter(dimension -> !dimension.isBlank());
    }

    private static Map<String, String> checkpoint(ActivePlan active) {
        Map<String, String> checkpoint = new java.util.LinkedHashMap<>();
        checkpoint.put("origin", encodePos(active.origin));
        checkpoint.put("started_tick", String.valueOf(active.startedTick));
        if (active.buildAnchor != null) {
            checkpoint.put("build_anchor", encodePos(active.buildAnchor));
            checkpoint.put("build_placed", String.valueOf(active.buildPlaced));
            checkpoint.put("build_skipped", String.valueOf(active.buildSkipped));
        }
        if (!active.boundContainers.isEmpty()) {
            checkpoint.put("containers", active.boundContainers.stream().map(GoalCheckpointCodec::encodePos).sorted()
                    .collect(java.util.stream.Collectors.joining(";")));
        }
        checkpoint.put("revision", String.valueOf(active.completedSteps));
        checkpoint.put("lifetime_replans", String.valueOf(active.lifetimeReplans));
        checkpoint.put("rare_resource_retries_used", String.valueOf(
                active.rareResourceRetriesUsed));
        checkpoint.put("rare_epoch_margin_used", String.valueOf(
                active.rareEpochMarginUsed));
        checkpoint.put("replan_count", String.valueOf(active.replanCount));
        checkpoint.putAll(encodePostconditionRepairCheckpoint(
                active.postconditionReplans,
                active.lastEvaluationMatched,
                active.lastRepairFingerprint));
        checkpoint.put("snap_steps", String.valueOf(active.snapSteps));
        checkpoint.put("snap_target", String.valueOf(active.snapTargetCount));
        checkpoint.put("snap_x", String.valueOf(active.snapX));
        checkpoint.put("snap_y", String.valueOf(active.snapY));
        checkpoint.put("snap_z", String.valueOf(active.snapZ));
        checkpoint.put("snap_dimension", active.snapDimension);
        checkpoint.put("snap_hunt_raw_meat", String.valueOf(active.snapHuntRawMeat));
        checkpoint.put("snap_hunt_visited_sectors",
                String.valueOf(active.snapHuntVisitedSectors));
        checkpoint.putAll(encodeHuntSearchCursorNamespace(active.huntSearchCursor));
        checkpoint.putAll(encodeSkippedTargetReceipts(active.skippedTargetReceipts));
        if (active.goal instanceof Goal.Fulfill) {
            checkpoint.putAll(encodeCompletedDeliveries(active.completedDeliveries));
        }
        if (active.taskCheckpointKind != null && !active.taskCheckpoint.isEmpty()) {
            checkpoint.put("task_kind", active.taskCheckpointKind.name());
            active.taskCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put("task." + entry.getKey(), entry.getValue()));
        }
        if (!active.miningCheckpoint.isEmpty()) {
            active.miningCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put("mining." + entry.getKey(), entry.getValue()));
        }
        if (!active.auxiliaryMiningCheckpoint.isEmpty()) {
            active.auxiliaryMiningCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put(
                            "aux_mining." + entry.getKey(), entry.getValue()));
        }
        if (active.capacityParentNamespace != null) {
            checkpoint.put("capacity_parent", active.capacityParentNamespace.persistedName);
            checkpoint.put(CAPACITY_PARENT_DELIVERED_KEY,
                    String.valueOf(active.capacityParentDelivered));
            checkpoint.put(CAPACITY_PARENT_FACE_KEY,
                    encodePos(active.capacityParentFace));
            checkpoint.put(CAPACITY_PARENT_SERVICES_USED_KEY,
                    String.valueOf(active.capacityParentServicesUsed));
        }
        if (!active.auxiliaryMiningContinuationFingerprint.isBlank()) {
            checkpoint.put(AUXILIARY_MINING_CONTINUATION_KEY,
                    active.auxiliaryMiningContinuationFingerprint);
        }
        if (!active.obsidianCheckpoint.isEmpty()) {
            active.obsidianCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put("obsidian." + entry.getKey(), entry.getValue()));
        }
        if (!active.settledServiceTombstones.isEmpty()) {
            checkpoint.put(SETTLED_SERVICE_PREFIX + "count",
                    String.valueOf(active.settledServiceTombstones.size()));
            int index = 0;
            for (SettledServiceTombstone settled
                    : active.settledServiceTombstones.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue).toList()) {
                String entryPrefix = SETTLED_SERVICE_PREFIX + index++ + ".";
                settled.encode().forEach((key, value) ->
                        checkpoint.put(entryPrefix + key, value));
            }
        }
        if (active.awaitingPlayerContinuation) {
            checkpoint.putAll(encodeBatchCheckpoint(new GoalBatchCheckpoint(
                    true,
                    active.completedAtLastBatchCheckpoint,
                    active.batchStepLimit,
                    active.strategyManuallyHeld,
                    active.strategyDecisionExhausted)));
        }
        return Map.copyOf(checkpoint);
    }

    /**
     * Applies durable skip receipts only to ordinary fresh Planner output. Each receipt removes the
     * earliest still-present same target at most once; count is intentionally ignored by
     * GoalStep.sameTarget, while every other identity field remains exact.
     */
    static List<GoalStep> applySkippedTargetReceipts(
            List<GoalStep> freshSteps, List<SkippedTargetReceipt> receipts) {
        List<GoalStep> remaining =
                new ArrayList<>(freshSteps == null ? List.of() : freshSteps);
        for (SkippedTargetReceipt receipt
                : receipts == null ? List.<SkippedTargetReceipt>of() : receipts) {
            for (int index = 0; index < remaining.size(); index++) {
                if (receipt.step().sameTarget(remaining.get(index))) {
                    remaining.remove(index);
                    break;
                }
            }
        }
        return List.copyOf(remaining);
    }

    static boolean skippedTargetReceiptsAuthorized(
            Goal goal, List<SkippedTargetReceipt> receipts) {
        return goal != null && receipts != null
                && receipts.stream().allMatch(receipt ->
                receipt != null && shouldSkipFailedStep(
                        goal, receipt.step(), receipt.reason()));
    }

    /** A persisted handoff receipt may only satisfy an allocation explicitly declared by Fulfill. */
    static boolean completedDeliveriesAuthorized(
            Goal goal, Set<Goal.Allocation> receipts) {
        if (receipts == null) {
            return false;
        }
        if (!(goal instanceof Goal.Fulfill fulfill)) {
            return receipts.isEmpty();
        }
        return fulfill.deliveries().containsAll(receipts);
    }

    private static RestoreSeed restoreSeed(AIPlayerEntity bot, Goal goal, MissionRecord record) {
        Map<String, String> checkpoint = record.checkpoint() == null ? Map.of() : record.checkpoint();
        Optional<String> validationFailure =
                restoreCheckpointValidationFailure(checkpoint);
        OptionalInt completedSteps = decodePersistedMissionCounter(checkpoint, "revision");
        OptionalInt lifetimeReplans =
                decodePersistedMissionCounter(checkpoint, "lifetime_replans");
        OptionalInt replanCount =
                decodePersistedMissionCounter(checkpoint, "replan_count");
        Optional<ReplanSnapshot> replanSnapshot = decodeReplanSnapshot(checkpoint);
        Optional<HuntSearchCursor> huntSearchCursor =
                decodeHuntSearchCursorNamespace(checkpoint);
        Optional<List<SkippedTargetReceipt>> skippedTargetReceipts =
                decodeSkippedTargetReceipts(checkpoint);
        Optional<Set<Goal.Allocation>> completedDeliveries =
                decodeCompletedDeliveries(checkpoint);
        Optional<PostconditionRepairCheckpoint> postconditionRepair =
                decodePostconditionRepairCheckpoint(checkpoint);
        Optional<GoalBatchCheckpoint> batchCheckpoint = decodeBatchCheckpoint(checkpoint);
        GoalSnapshotCollector.Context fallback = initialContext(bot, goal);
        BlockPos origin = decodePos(checkpoint.get("origin")).orElse(fallback.origin());
        Set<BlockPos> containers = new HashSet<>();
        String encodedContainers = checkpoint.get("containers");
        if (encodedContainers != null && !encodedContainers.isBlank()) {
            for (String encoded : encodedContainers.split(";")) {
                decodePos(encoded).ifPresent(containers::add);
            }
        }
        BlockPos buildAnchor = decodePos(checkpoint.get("build_anchor")).orElse(null);
        BlueprintSchema blueprint = null;
        if (goal instanceof Goal.Build build && buildAnchor != null) {
            try {
                blueprint = BlueprintLoader.load(build.blueprint());
            } catch (IOException exception) {
                BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.LIFECYCLE, bot,
                        "mission_checkpoint_blueprint_missing", "blueprint", build.blueprint());
            }
        }
        GoalSnapshotCollector.Context context = new GoalSnapshotCollector.Context(
                origin, containers, blueprint, buildAnchor,
                nonNegativeInt(checkpoint.get("build_placed")),
                nonNegativeInt(checkpoint.get("build_skipped")),
                completedDeliveries.orElse(Set.of()));
        UUID missionId;
        try {
            missionId = UUID.fromString(record.missionId());
        } catch (RuntimeException exception) {
            missionId = UUID.randomUUID();
        }
        int restoredStartedTick = checkpoint.containsKey("started_tick")
                ? nonNegativeInt(checkpoint.get("started_tick"))
                : bot.level().getServer().getTickCount();
        GoalStep.Kind taskCheckpointKind = decodeStepKind(checkpoint.get("task_kind")).orElse(null);
        Map<String, String> taskCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> miningCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> auxiliaryMiningCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> obsidianCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> settledServiceTombstone = new java.util.LinkedHashMap<>();
        checkpoint.forEach((key, value) -> {
            if (key.startsWith("task.") && key.length() > "task.".length()) {
                taskCheckpoint.put(key.substring("task.".length()), value);
            } else if (key.startsWith("mining.") && key.length() > "mining.".length()) {
                miningCheckpoint.put(key.substring("mining.".length()), value);
            } else if (key.startsWith("aux_mining.")
                    && key.length() > "aux_mining.".length()) {
                auxiliaryMiningCheckpoint.put(
                        key.substring("aux_mining.".length()), value);
            } else if (key.startsWith("obsidian.") && key.length() > "obsidian.".length()) {
                obsidianCheckpoint.put(key.substring("obsidian.".length()), value);
            } else if ("settled_service".equals(key)) {
                // Preserve the exact illegal root so the strict collection decoder rejects it;
                // silently ignoring this key would turn a corrupt guard namespace into legacy
                // no-guard state.
                settledServiceTombstone.put("__root__", value);
            } else if (key.startsWith(SETTLED_SERVICE_PREFIX)) {
                settledServiceTombstone.put(
                        key.substring(SETTLED_SERVICE_PREFIX.length()), value);
            }
        });
        // task_kind is routing metadata, not physical authority. A strict MiningService codec can
        // safely recover its effective kind even when an older/corrupt writer omitted or mislabeled
        // the top-level discriminator; restoreRuntime quarantines identity-bearing invalid pockets
        // before this point, so this inference can never turn malformed geometry into fresh work.
        Optional<MiningServiceTask.RestoreMetadata> inferredService =
                MiningServiceTask.inspectCheckpoint(taskCheckpoint);
        if (inferredService.isPresent()
                && (hasActiveServicePocket(taskCheckpoint)
                || !inferredService.orElseThrow().terminalFailure().isBlank())) {
            taskCheckpointKind = GoalStep.Kind.MINING_SERVICE;
        }
        GoalBatchCheckpoint restoredBatch = batchCheckpoint.orElse(
                GoalBatchCheckpoint.legacy());
        String restoredValidation = validationFailure.orElse("");
        if (restoredValidation.isBlank()
                && (completedDeliveries.isEmpty()
                || !completedDeliveriesAuthorized(goal,
                completedDeliveries.orElse(Set.of())))) {
            restoredValidation = "mission_restore_invalid_completed_deliveries";
        }
        if (restoredValidation.isBlank() && batchCheckpoint.isEmpty()) {
            restoredValidation = "mission_restore_invalid_goal_batch_checkpoint";
        }
        if (restoredValidation.isBlank() && restoredBatch.persisted()
                && (completedSteps.orElse(0) != restoredBatch.completedAtCheckpoint()
                || taskCheckpointKind != null || !taskCheckpoint.isEmpty()
                || !miningCheckpoint.isEmpty() || !auxiliaryMiningCheckpoint.isEmpty()
                || !obsidianCheckpoint.isEmpty()
                || !checkpoint.getOrDefault("capacity_parent", "").isBlank()
                || !checkpoint.getOrDefault(AUXILIARY_MINING_CONTINUATION_KEY, "").isBlank())) {
            // Batch confirmation is only written after a whole, non-transactional step.  A
            // checkpoint claiming both pending player consent and an active physical cursor is
            // ambiguous, so never auto-advance it on restore.
            restoredValidation = "mission_restore_goal_batch_checkpoint_not_safe";
        }
        return new RestoreSeed(
                missionId,
                context,
                completedSteps.orElse(0),
                lifetimeReplans.orElse(0),
                decodePersistedRareResourceEpoch(checkpoint).orElse(-1),
                decodePersistedRareEpochMarginUsed(checkpoint).orElse(-1),
                replanCount.orElse(0),
                replanSnapshot,
                huntSearchCursor.orElse(null),
                skippedTargetReceipts.orElse(null),
                restoredValidation,
                postconditionRepair.orElse(PostconditionRepairCheckpoint.legacy()),
                restoredBatch,
                restoredStartedTick,
                taskCheckpointKind,
                Map.copyOf(taskCheckpoint),
                Map.copyOf(miningCheckpoint),
                Map.copyOf(auxiliaryMiningCheckpoint),
                checkpoint.getOrDefault("capacity_parent", ""),
                decodePersistedCapacityParentDelivered(checkpoint).orElse(-2),
                checkpoint.getOrDefault(CAPACITY_PARENT_FACE_KEY, ""),
                decodePersistedCapacityParentServicesUsed(checkpoint).orElse(-1),
                checkpoint.getOrDefault(AUXILIARY_MINING_CONTINUATION_KEY, ""),
                Map.copyOf(obsidianCheckpoint),
                Map.copyOf(settledServiceTombstone));
    }

    /**
     * Validates the mission-level restart envelope before any decoded fallback can erase the
     * distinction between an absent legacy field and a malformed persisted value.
     */
    static Optional<String> restoreCheckpointValidationFailure(
            Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Map<String, String> task = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && key.startsWith("task.")
                    && key.length() > "task.".length()) {
                task.put(key.substring("task.".length()), value);
            }
        });
        boolean huntKind = "HUNT".equals(values.get("task_kind"));
        boolean huntIdentity = "hunt_pickup".equals(task.get("cursor_kind"));
        if ((huntKind || huntIdentity)
                && (!huntKind || !huntIdentity
                || HuntPickupCheckpoint.inspect(task).isEmpty())) {
            return Optional.of("mission_restore_invalid_hunt_pickup_checkpoint");
        }
        if (decodeHuntSearchCursorNamespace(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_hunt_search_cursor");
        }
        if (decodeSkippedTargetReceipts(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_skipped_target_receipts");
        }
        if (decodeCompletedDeliveries(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_completed_deliveries");
        }
        if (hasAnyReplanSnapshotField(values)
                && decodeReplanSnapshot(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_replan_snapshot");
        }
        if (decodePersistedMissionCounter(values, "revision").isEmpty()) {
            return Optional.of("mission_restore_invalid_completed_step_count");
        }
        if (decodePersistedMissionCounter(values, "lifetime_replans").isEmpty()) {
            return Optional.of("mission_restore_invalid_lifetime_replan_count");
        }
        if (decodePersistedMissionCounter(values, "replan_count").isEmpty()) {
            return Optional.of("mission_restore_invalid_replan_count");
        }
        if (decodePostconditionRepairCheckpoint(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_postcondition_repair_checkpoint");
        }
        return Optional.empty();
    }

    /** Missing is the only legacy value; every persisted value must be a non-negative watermark. */
    static OptionalInt decodePersistedCapacityParentDelivered(
            Map<String, String> checkpoint) {
        if (checkpoint == null || !checkpoint.containsKey(CAPACITY_PARENT_DELIVERED_KEY)) {
            return OptionalInt.of(-1);
        }
        try {
            int parsed = Integer.parseInt(checkpoint.get(CAPACITY_PARENT_DELIVERED_KEY));
            return parsed >= 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    /** Missing is the legacy no-counter value; every persisted value must be positive. */
    static OptionalInt decodePersistedCapacityParentServicesUsed(
            Map<String, String> checkpoint) {
        if (checkpoint == null
                || !checkpoint.containsKey(CAPACITY_PARENT_SERVICES_USED_KEY)) {
            return OptionalInt.of(0);
        }
        try {
            int parsed = Integer.parseInt(
                    checkpoint.get(CAPACITY_PARENT_SERVICES_USED_KEY));
            return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    /**
     * Missing means a legacy epoch-zero snapshot; malformed or negative values fail closed. The
     * mission-derived upper bound (per-batch retry plus the bounded margin pool) is enforced at
     * the restore site because only the goal knows the durable mission target.
     */
    static OptionalInt decodePersistedRareResourceEpoch(Map<String, String> checkpoint) {
        if (checkpoint == null || !checkpoint.containsKey("rare_resource_retries_used")) {
            return OptionalInt.of(0);
        }
        try {
            int parsed = Integer.parseInt(checkpoint.get("rare_resource_retries_used"));
            return parsed >= 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    /** Missing is a legacy zero-margin snapshot; malformed or negative values fail closed. */
    static OptionalInt decodePersistedRareEpochMarginUsed(Map<String, String> checkpoint) {
        if (checkpoint == null || !checkpoint.containsKey("rare_epoch_margin_used")) {
            return OptionalInt.of(0);
        }
        return canonicalNonNegativeInt(checkpoint.get("rare_epoch_margin_used"));
    }

    /** Bounded mission-level epoch margin pool derived from the durable original rare target. */
    static int rareMissionEpochMarginPool(Goal goal) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        return missionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                ? MiningBudget.rareMissionEpochMargin(
                MiningBudget.rareMissionBatchCount(missionTarget))
                : 0;
    }

    /**
     * A batch epoch beyond the per-batch retry must have been paid from the mission margin pool;
     * accepting a smaller persisted ledger would let a restart manufacture extra bounded epochs.
     */
    static boolean validRestoredRareEpochMargin(int persistedEpoch,
                                               int marginUsed,
                                               int marginPool) {
        return marginUsed >= 0 && marginUsed <= marginPool
                && persistedEpoch - MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH
                <= marginUsed;
    }

    static boolean hasAnyReplanSnapshotField(Map<String, String> checkpoint) {
        return checkpoint != null && checkpoint.keySet().stream()
                .anyMatch(key -> key != null && key.startsWith("snap_"));
    }

    public boolean startNextQueuedIfIdle(AIPlayerEntity bot) {
        if (hasActivePlan(bot)) {
            return false;
        }
        return advanceQueue(bot);
    }

    /** Diagnostic tracepoint: the currently active top-level goal (or "none" if there isn't one). For logging; kept in English to make troubleshooting easier. */
    public String describeActiveGoal(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? "none" : String.valueOf(plan.goal);
    }

    /** Panel task chain: goal title (Chinese). Item ids stay as minecraft:xxx; the client localizes them into a Chinese display name. */
    public String activeGoalTitle(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? "No active goal" : goalLabel(plan.goal);
    }

    private static String goalLabel(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> "Get " + itemLabel(g.item()) + " x" + g.count();
            case Goal.MineOre g -> g.isTimedCollection()
                    ? "Collect ore for ten minutes" : "Mine ore x" + g.count();
            case Goal.HarvestCrop g -> g.isTimedCollection()
                    ? "Farm " + itemLabel(g.produce()) + " for ten minutes"
                    : "Farm " + itemLabel(g.produce()) + " x" + g.count();
            case Goal.Food g -> "Prepare cooked food x" + g.cookedCount();
            case Goal.Armor g -> "Equip a full armor set and sword";
            case Goal.Workstation g -> "Set up a workstation";
            case Goal.Stockpile g -> "Stockpile " + itemLabel(g.item()) + " x" + g.count();
            case Goal.HavePickaxeTier g -> "Upgrade pickaxe (tier " + g.tier() + ")";
            case Goal.Build g -> "Build " + g.blueprint().replace('_', ' ');
            case Goal.Fulfill g -> "Fulfill " + g.allocations().size() + " item allocation"
                    + (g.allocations().size() == 1 ? "" : "s");
        };
    }

    /**
     * Read-only root objective carried into every adaptive decision, including after a restart.
     * In particular, a fulfillment count alone is not enough for the model to see which items
     * belong to the player versus the bot, so render the canonical server-owned manifest here.
     */
    private static String strategyGoalDescription(Goal goal) {
        if (!(goal instanceof Goal.Fulfill fulfill)) {
            return goalLabel(goal);
        }
        List<String> allocations = fulfill.allocations().stream()
                .map(allocation -> allocation.itemId() + " x" + allocation.count()
                        + (allocation.delivery() ? " -> " + allocation.recipient() : " -> bot"))
                .toList();
        return "Fulfill manifest: " + String.join(", ", allocations);
    }

    private static String itemLabel(Item item) {
        Identifier id = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "unknown item" : id.getPath().replace('_', ' ');
    }

    /** Diagnostic tracepoint: the step currently being executed + progress [step number/total steps] (or "" if there is no active step). */
    public String describeActiveStep(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.current == null) {
            return "";
        }
        int idx = plan.totalSteps - plan.steps.size(); // current has already been taken out of steps; we are now on step idx
        return plan.current.describe() + " [" + idx + "/" + plan.totalSteps + "]";
    }

    /** Panel task chain: the full list of step descriptions (empty if there is no active plan). */
    public java.util.List<String> activeGoalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? java.util.List.of() : plan.stepLabels;
    }

    /** Panel task chain: the 0-based index of the current step. */
    public int activeGoalCurrentIndex(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.current == null) {
            return 0;
        }
        return Math.max(0, plan.totalSteps - plan.steps.size() - 1);
    }

    /** Panel task chain: the total number of steps. */
    public int activeGoalTotalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? 0 : plan.totalSteps;
    }

    // P0 queue continuation: once the current goal is settled (completed/failed), automatically start the next one in the queue; ones that fail to plan are skipped one by one, with an explanation each time.
    private boolean advanceQueue(AIPlayerEntity bot) {
        java.util.Deque<QueuedGoal> queued = goalQueue.get(bot.getUUID());
        if (queued == null) {
            return false;
        }
        QueuedGoal next;
        while ((next = queued.pollFirst()) != null) {
            report(bot, "Next, I will: " + goalLabel(next.goal));
            if (submit(bot, next.goal, null, next.executionMode)) {
                if (hasActivePlan(bot)) {
                    return true;
                }
                // The goal is already satisfied and no active plan was created: keep checking the next item in the queue.
            }
            // If submit failed (planning failed / blocked), the reason was already reported internally; keep trying the next one in the queue
        }
        goalQueue.remove(bot.getUUID(), queued);
        return false;
    }

    /**
     * Captures a committed mission transition before successor dispatch can synchronously start
     * work or throw. A second capture records the assigned task's initial checkpoint when dispatch
     * succeeds. BotPersistence keeps both captures asynchronous with respect to disk I/O.
     */
    // Package-private: also called by MissionRecoveryScheduler after scheduling a service/retry.
    void captureTransitionAndAssignNext(AIPlayerEntity bot, ActivePlan plan) {
        captureBeforeAndAfterDispatch(
                () -> markDirty(bot),
                () -> assignNext(bot, plan));
    }

    /** Package-private ordering seam for transition unit tests. */
    static void captureBeforeAndAfterDispatch(Runnable capture, Runnable dispatch) {
        java.util.Objects.requireNonNull(capture, "capture");
        java.util.Objects.requireNonNull(dispatch, "dispatch");
        capture.run();
        dispatch.run();
        capture.run();
    }

    /** Package-private policy seam for focused checkpoint tests. */
    static boolean shouldCheckpointAfterCompletedStep(int completedAtLastCheckpoint,
                                                      int completedSteps,
                                                      int remainingSteps,
                                                      boolean safeBoundary) {
        return shouldCheckpointAfterCompletedStep(completedAtLastCheckpoint, completedSteps,
                remainingSteps, safeBoundary, DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT);
    }

    static boolean shouldCheckpointAfterCompletedStep(int completedAtLastCheckpoint,
                                                      int completedSteps,
                                                      int remainingSteps,
                                                      boolean safeBoundary,
                                                      int stepLimit) {
        return safeBoundary && remainingSteps > 0
                && (long) completedSteps - completedAtLastCheckpoint
                >= stepLimit;
    }

    /**
     * Never stop mid-transaction.  The dedicated mining/obsidian namespaces represent physical
     * work faces, pickups, and service pockets; their own resumable checkpoints must run to a
     * normal ordinary-step boundary before a player-consent checkpoint can be created.
     */
    private static boolean isSafeBatchCheckpointBoundary(ActivePlan plan) {
        return plan != null && !plan.awaitingPlayerContinuation
                && hasNoOpenStepTransaction(plan);
    }

    private static boolean hasNoOpenStepTransaction(ActivePlan plan) {
        return plan != null && plan.current == null && plan.currentTask == null
                && !plan.huntPickupSettlement
                && plan.taskCheckpointKind == null && plan.taskCheckpoint.isEmpty()
                && plan.miningCheckpoint.isEmpty()
                && plan.auxiliaryMiningCheckpoint.isEmpty()
                && plan.obsidianCheckpoint.isEmpty()
                && plan.capacityParentNamespace == null
                && plan.auxiliaryMiningContinuationFingerprint.isBlank();
    }

    /** Returns true when a committed step turned into a player or strategy checkpoint. */
    private boolean requestBatchCheckpointIfDue(AIPlayerEntity bot, ActivePlan plan) {
        if (isBatchCheckpointExempt(plan.goal, plan.executionMode)) {
            return false;
        }
        if (!shouldCheckpointAfterCompletedStep(plan.completedAtLastBatchCheckpoint,
                plan.completedSteps, plan.steps.size(), isSafeBatchCheckpointBoundary(plan),
                plan.batchStepLimit)) {
            return false;
        }
        int completedInBatch = Math.max(1, plan.completedSteps - plan.completedAtLastBatchCheckpoint);
        plan.awaitingPlayerContinuation = true;
        plan.completedAtLastBatchCheckpoint = plan.completedSteps;
        plan.strategyManuallyHeld = false;
        plan.strategyDecisionExhausted = false;
        boolean adaptive = plan.executionMode == ExecutionMode.ADAPTIVE;
        TaskManager.INSTANCE.pauseUserIntent(bot,
                adaptive ? "goal_strategy_checkpoint" : "goal_batch_checkpoint");
        BotLog.task(bot, adaptive ? "goal_strategy_checkpoint_waiting" : "goal_batch_checkpoint_waiting",
                "completed", plan.completedSteps,
                "remaining", plan.steps.size(),
                "step_limit", plan.batchStepLimit,
                "next", plan.steps.peekFirst() == null ? "" : plan.steps.peekFirst().describe());
        if (!adaptive) {
            report(bot, "I completed " + completedInBatch + " stages."
                    + batchCheckpointReplyInstruction(plan.steps.size()));
        }
        markDirty(bot);
        return true;
    }

    /** Fulfill remains one explicit transaction in legacy mode; adaptive goals deliberately pause. */
    static boolean isBatchCheckpointExempt(Goal goal, ExecutionMode executionMode) {
        return executionMode != ExecutionMode.ADAPTIVE && goal instanceof Goal.Fulfill;
    }

    /** Legacy policy seam retained for ordinary direct goal submissions and focused tests. */
    static boolean isBatchCheckpointExempt(Goal goal) {
        return isBatchCheckpointExempt(goal, ExecutionMode.STANDARD);
    }

    /** Exact player-facing consent contract for bounded work; keep this concise enough for chat. */
    static String batchCheckpointReplyInstruction(int remainingSteps) {
        return " " + Math.max(0, remainingSteps) + " more are planned. Reply exactly [continue] "
                + "to run the next batch, or [stop] to end this goal.";
    }

    private void assignNext(AIPlayerEntity bot, ActivePlan plan) {
        GoalStep step = plan.steps.pollFirst();
        if (step == null) {
            GoalEvaluation evaluation = evaluate(bot, plan);
            if (evaluation.state() == GoalEvaluation.State.SATISFIED) {
                finishActive(bot, plan, evaluation, "postcondition_satisfied", false, true);
                return;
            }
            if (!(plan.goal instanceof Goal.Build) && bot.isAlive()
                    && withinPostconditionRepairBudget(plan.postconditionReplans)) {
                GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                        bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
                List<GoalStep> repairSteps = applySkippedTargetReceipts(
                        fresh.success() ? fresh.steps() : List.of(),
                        plan.skippedTargetReceipts);
                String fingerprint =
                        repairSteps.stream().map(GoalStep::describe).toList().toString();
                boolean progress = evaluation.matched() > plan.lastEvaluationMatched;
                if (fresh.success() && !repairSteps.isEmpty()
                        && shouldAcceptPostconditionRepair(
                        evaluation.matched(), plan.lastEvaluationMatched,
                        fingerprint, plan.lastRepairFingerprint)) {
                    plan.postconditionReplans++;
                    plan.lastEvaluationMatched = Math.max(plan.lastEvaluationMatched, evaluation.matched());
                    plan.lastRepairFingerprint = fingerprint;
                    BotLog.task(bot, "goal_postcondition_repair", "goal", plan.goal,
                            "matched", evaluation.matched(), "required", evaluation.required(),
                            "steps", fingerprint, "repair", plan.postconditionReplans);
                    plan.steps.clear();
                    plan.steps.addAll(repairSteps);
                    plan.totalSteps = repairSteps.size();
                    plan.current = null;
                    plan.currentTask = null;
                    captureTransitionAndAssignNext(bot, plan);
                    return;
                }
                BotLog.task(bot, "goal_postcondition_repair_rejected",
                        "goal", plan.goal,
                        "success", fresh.success(),
                        "steps", fingerprint,
                        "unresolved", fresh.unresolved(),
                        "progress", progress,
                        "same", fingerprint.equals(plan.lastRepairFingerprint));
            }
            finishActive(bot, plan, evaluation, "postcondition_unsatisfied", false, true);
            return;
        }
        plan.currentCapacityParentRetry = false;
        Optional<Task> task = stepToTask(bot, step, plan);
        if (task.isEmpty()) {
            finishActive(bot, plan, evaluate(bot, plan), "unmapped_step:" + step.describe(), false, true);
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.TASK, bot, "goal_step_unmapped", "step", step.describe());
            return;
        }
        plan.current = step;
        plan.currentTask = task.get();
        int done = plan.totalSteps - plan.steps.size();
        BotLog.task(bot, "goal_step", "index", done, "total", plan.totalSteps, "step", step.describe());
        TaskOrigin origin = TaskOrigin.mission(plan.missionId, step.describe());
        if (pocketRestorePreflights.contains(bot.getUUID())) {
            TaskManager.INSTANCE.assignSilently(bot, task.get(), origin);
        } else {
            TaskManager.INSTANCE.assign(bot, task.get(), origin);
        }
        if (!pocketRestorePreflights.contains(bot.getUUID())) {
            report(bot, "Stage " + done + "/" + plan.totalSteps + ": "
                    + step.describe() + ".");
        }
    }

    static boolean withinPostconditionRepairBudget(int replans) {
        return replans >= 0 && replans < MAX_POSTCONDITION_REPLANS;
    }

    static boolean shouldAcceptPostconditionRepair(
            int currentMatched, int lastMatched,
            String candidateFingerprint, String lastFingerprint) {
        return currentMatched > lastMatched
                || !java.util.Objects.equals(candidateFingerprint, lastFingerprint);
    }

    // Phase A progress signal: current target-product inventory for absolute goals. Incremental
    // MineOre and HarvestCrop goals instead report mission-owned delivery above their immutable
    // submission baseline, so a preexisting stack cannot advance a replan watermark (or a rare
    // ore service boundary).
    // Package-private: also called by MissionRecoveryScheduler's rare-resource scheduling helpers.
    static int goalTargetCount(AIPlayerEntity bot, Goal goal) {
        if (goal instanceof Goal.HaveItem hi) {
            return io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(hi.item()));
        }
        if (goal instanceof Goal.Stockpile sp) {
            return io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(sp.item()));
        }
        if (goal instanceof Goal.MineOre mo) {
            int heldDrops = io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(bot,
                    io.github.zoyluo.minecraftai.action.HarvestCore.expectedDropsFor(mo.ores()));
            return mo.deliveredFromInitial(heldDrops);
        }
        if (goal instanceof Goal.HarvestCrop crop) {
            int heldProduce = io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(
                    bot, Set.of(crop.produce()));
            return crop.deliveredFromInitial(heldProduce);
        }
        if (goal instanceof Goal.Fulfill fulfill) {
            int total = 0;
            for (Item item : fulfill.inventoryRequired(Set.of()).keySet()) {
                int held = fulfill.isFreshInventoryRequest()
                        ? GoalSnapshotCollector.inventoryCount(bot, item)
                        : io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(
                                bot, Set.of(item));
                total = Math.addExact(total,
                        fulfill.isFreshInventoryRequest()
                                ? fulfill.deliveredFromInitial(item, held)
                                : held);
            }
            return total;
        }
        return 0;
    }

    private static int rawMeatCount(AIPlayerEntity bot) {
        return HuntTask.rawMeatCount(bot);
    }

    static boolean isUnsettledHuntPhysicalDebt(GoalStep.Kind kind, String reason) {
        if (kind != GoalStep.Kind.HUNT || reason == null) {
            return false;
        }
        return reason.startsWith("hunt_surface_return_timeout")
                || reason.startsWith("hunt_surface_return_unreachable")
                || reason.startsWith("hunt_surface_anchor_missing")
                || reason.startsWith("hunt_dimension_changed")
                || reason.startsWith("hunt_drop_unrecovered")
                || reason.startsWith("hunt_pickup_observation_timeout")
                || reason.startsWith("hunt_water_rescue_timeout")
                || reason.startsWith("hunt_pickup_checkpoint_capacity")
                || reason.startsWith("hunt_pickup_time_rollback")
                || reason.startsWith("hunt_pickup_dimension_mismatch")
                || reason.startsWith("hunt_pickup_invalid_checkpoint")
                || reason.startsWith("hunt_search_capacity_exhausted")
                || reason.startsWith("hunt_search_ordinal_exhausted");
    }

    /** Pure policy boundary for the generic best-effort failure branch. */
    static boolean shouldSkipFailedStep(Goal goal, GoalStep step, String reason) {
        if (step == null || isUnsettledHuntPhysicalDebt(step.kind(), reason)) {
            return false;
        }
        boolean cookFinalOfFood = goal instanceof Goal.Food
                && step.kind() == GoalStep.Kind.COOK_FOOD;
        boolean foodGoalBestEffort = goal instanceof Goal.Food;
        return !cookFinalOfFood && (step.bestEffort()
                || foodGoalBestEffort
                || step.kind() == GoalStep.Kind.STOCKPILE);
    }

    /**
     * Pure replan-watermark policy. Goal output and completed steps are universal progress.
     * HUNT then accepts only factual food/search gains; all other tasks retain the existing
     * controlled movement signal used by branch mining.
     */
    static boolean madeReplanProgress(
            GoalStep.Kind kind,
            int completedSteps, int snapshotSteps,
            int targetCount, int snapshotTargetCount,
            int huntRawMeat, int snapshotHuntRawMeat,
            int huntVisitedSectors, int snapshotHuntVisitedSectors,
            String dimension, String snapshotDimension,
            int x, int y, int z,
            int snapshotX, int snapshotY, int snapshotZ) {
        if (completedSteps > snapshotSteps || targetCount > snapshotTargetCount) {
            return true;
        }
        if (kind == GoalStep.Kind.HUNT) {
            return huntRawMeat > snapshotHuntRawMeat
                    || huntVisitedSectors > snapshotHuntVisitedSectors;
        }
        if (dimension == null || dimension.isBlank()
                || snapshotDimension == null || snapshotDimension.isBlank()
                || !dimension.equals(snapshotDimension)) {
            return false;
        }
        long dx = (long) x - snapshotX;
        long dz = (long) z - snapshotZ;
        return y < snapshotY || dx * dx + dz * dz >= 64L;
    }

    /**
     * A long rare-ore mission has one bounded retry epoch per eight-item batch. Three retries per
     * batch preserve the existing no-progress allowance while the base budget covers bootstrap and
     * short missions. The immutable top-level goal is used so partial inventory or a restart cannot
     * shrink or refresh the lifetime bound.
     */
    static int lifetimeReplanLimit(Goal goal) {
        return longRareOreLifetimeReplanLimit(originalLongRareOreTargetCount(goal));
    }

    static int longRareOreLifetimeReplanLimit(int targetCount) {
        if (targetCount < MiningBudget.EXPEDITION_THRESHOLD) {
            return BASE_LIFETIME_REPLAN_LIMIT;
        }
        int batchCount = MiningBudget.forQuota(targetCount, true, ToolTier.IRON).batchCount();
        return Math.max(BASE_LIFETIME_REPLAN_LIMIT, MAX_CONSECUTIVE_REPLANS * batchCount);
    }

    static boolean withinReplanBudget(Goal goal, int consecutiveReplans, int lifetimeReplans) {
        return withinReplanBudget(
                lifetimeReplanLimit(goal), consecutiveReplans, lifetimeReplans);
    }

    static boolean withinReplanBudget(
            int lifetimeLimit, int consecutiveReplans, int lifetimeReplans) {
        return consecutiveReplans < MAX_CONSECUTIVE_REPLANS
                && lifetimeReplans < lifetimeLimit;
    }

    // Package-private: also called by MissionRecoveryScheduler's rare-resource scheduling helpers.
    // MineOre.count is its immutable mission quota; MineOre.targetDropCount is only the physical
    // postcondition threshold after the public boundary captured preexisting drops.
    static int originalLongRareOreTargetCount(Goal goal) {
        int target = 0;
        if (goal instanceof Goal.MineOre mineOre && isRareOre(mineOre.ores())) {
            target = mineOre.count();
        } else if (goal instanceof Goal.HaveItem haveItem && isRareOreDrop(haveItem.item())) {
            target = haveItem.count();
        } else if (goal instanceof Goal.Stockpile stockpile && isRareOreDrop(stockpile.item())) {
            target = stockpile.count();
        }
        return target >= MiningBudget.EXPEDITION_THRESHOLD ? target : 0;
    }

    /**
     * Only the OreDig batches that directly deliver the long rare-ore goal own its durable mission
     * identity. Coal and iron bootstrap batches remain ordinary channels even when their parent is
     * a 64-diamond expedition; assigning 64 to those families makes their codec fail closed and
     * disables the one bounded local channel-tool resupply.
     */
    static int rareMissionTargetForMiningStep(Goal goal, Set<Block> ores) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        return missionTarget > 0 && miningStepFeedsGoal(goal, ores) ? missionTarget : 0;
    }

    // Package-private: also called by MissionRecoveryScheduler's capacity-handoff scheduling.
    static boolean isProtectedRareMiningCheckpoint(
            Goal goal, Map<String, String> checkpoint) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        return missionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                && OreDigTask.inspectCheckpoint(checkpoint, missionTarget)
                .filter(metadata -> metadata.rareMissionTarget() == missionTarget
                        && miningStepFeedsGoal(goal, metadata.ores()))
                .isPresent();
    }

    private static Optional<OreDigTask.RestoreMetadata> inspectOreDigCheckpointForGoal(
            Goal goal,
            Map<String, String> checkpoint) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        if (missionTarget == 0) {
            return OreDigTask.inspectCheckpoint(checkpoint, 0);
        }
        Optional<OreDigTask.RestoreMetadata> rare =
                OreDigTask.inspectCheckpoint(checkpoint, missionTarget)
                        .filter(metadata -> miningStepFeedsGoal(goal, metadata.ores()));
        if (rare.isPresent()) {
            return rare;
        }
        return OreDigTask.inspectCheckpoint(checkpoint, 0)
                .filter(metadata -> !miningStepFeedsGoal(goal, metadata.ores()));
    }

    /**
     * Reconciles the compatibility persistence key with the durable owner of the current rare batch.
     * The key historically accumulated one retry for the whole mission; epoch zero therefore accepts
     * and normalizes a persisted one. A later epoch — the per-batch retry or a mission-margin
     * epoch — cannot do the inverse because that would lose a physically committed epoch debit and
     * manufacture another resource epoch after restart.
     */
    static OptionalInt normalizeRestoredRareResourceEpoch(
            int persistedEpoch,
            Optional<OreDigTask.RestoreMetadata> owner) {
        if (persistedEpoch < 0) {
            return OptionalInt.empty();
        }
        if (owner == null || owner.isEmpty() || !owner.orElseThrow().batchOpen()) {
            // Without an open durable batch only the legacy mission-global one may normalize down;
            // a margin-funded epoch always has its open batch, so anything larger fails closed.
            return persistedEpoch <= MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH
                    ? OptionalInt.of(0) : OptionalInt.empty();
        }
        int durableEpoch = owner.orElseThrow().resourceEpoch();
        if (durableEpoch == 0) {
            return persistedEpoch <= MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH
                    ? OptionalInt.of(0) : OptionalInt.empty();
        }
        int epochCapacity = MiningBudget.rareMissionResourceEpochCapacity(
                MiningBudget.rareMissionBatchCount(owner.orElseThrow().rareMissionTarget()));
        if (durableEpoch >= 1 && durableEpoch < epochCapacity
                && persistedEpoch == durableEpoch) {
            return OptionalInt.of(durableEpoch);
        }
        return OptionalInt.empty();
    }

    private static boolean hasFreshMiningSuccessor(List<GoalStep> steps, Set<Block> ores) {
        String fingerprint = OreDigTask.oreFingerprint(ores);
        return steps != null && steps.stream().anyMatch(step ->
                step.kind() == GoalStep.Kind.MINE_ORE
                        && fingerprint.equals(OreDigTask.oreFingerprint(step.ores())));
    }

    private static boolean hasFreshMiningSuccessor(
            List<GoalStep> steps,
            OreDigTask.RestoreMetadata metadata) {
        if (metadata.remainingCount() == 0) {
            // The task still needs one commit tick (and possibly pickup-debt settlement), but the
            // fresh inventory plan correctly has no remaining ore step to match.
            return true;
        }
        String fingerprint = OreDigTask.oreFingerprint(metadata.ores());
        return steps != null && steps.stream().anyMatch(step ->
                step.kind() == GoalStep.Kind.MINE_ORE
                        && metadata.acceptsStepTarget(step.count())
                        && fingerprint.equals(OreDigTask.oreFingerprint(step.ores())));
    }

    /**
     * A disposal pocket remains a physical transaction until MiningService resets its complete
     * identity after both mouth cells and promised capacity have been re-attested.  In particular,
     * committed+verified is not settlement while RETURN or SEAL still owns these fields.
     */
    static boolean hasActiveServicePocket(Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return false;
        }
        String phase = checkpoint.get("phase");
        if (phase != null && ACTIVE_SERVICE_POCKET_PHASES.contains(phase)) {
            return true;
        }
        return SERVICE_POCKET_IDENTITY_KEYS.stream().anyMatch(checkpoint::containsKey);
    }

    /** A lone guard has one causal typed fact; multiple guards need an exact geometry key. */
    private static String settledServiceFailureWithoutContinuation(
            List<SettledServiceTombstone> settled) {
        return settled != null && settled.size() == 1
                ? settled.getFirst().failureReason()
                : "mission_restore_settled_service_guard_without_continuation";
    }

    static boolean validCapacityParentRetry(
            Optional<OreDigTask.RestoreMetadata> parent) {
        return parent != null && parent.filter(metadata -> metadata.batchOpen()
                && metadata.rareMissionTarget() == 0
                && metadata.inventoryServiceUsed()).isPresent();
    }

    static boolean validCommittedCapacityParent(
            Optional<OreDigTask.RestoreMetadata> parent,
            GoalStep.Kind taskKind,
            Map<String, String> taskCheckpoint,
            Map<String, String> parentCheckpoint) {
        return taskKind == GoalStep.Kind.MINE_ORE
                && taskCheckpoint != null
                && parentCheckpoint != null
                && !parentCheckpoint.isEmpty()
                && taskCheckpoint.equals(parentCheckpoint)
                && parent != null
                && parent.filter(metadata -> !metadata.batchOpen()
                        && metadata.rareMissionTarget() == 0
                        && !metadata.inventoryServiceUsed()).isPresent()
                && !hasOreDigPhysicalLedger(parentCheckpoint);
    }

    static boolean capacityParentMatchesService(
            Map<String, String> serviceCheckpoint,
            Map<String, String> parentCheckpoint) {
        return ordinaryServiceParentMatches(serviceCheckpoint, parentCheckpoint, true);
    }

    static boolean failedClosedAuxiliaryServiceMatches(
            Map<String, String> serviceCheckpoint,
            Map<String, String> auxiliaryCheckpoint) {
        return !hasActiveServicePocket(serviceCheckpoint)
                && ordinaryServiceParentMatches(
                serviceCheckpoint, auxiliaryCheckpoint, false);
    }

    private static boolean ordinaryServiceParentMatches(
            Map<String, String> serviceCheckpoint,
            Map<String, String> parentCheckpoint,
            boolean capacityRetry) {
        Optional<MiningServiceTask.RestoreMetadata> service =
                MiningServiceTask.inspectCheckpoint(serviceCheckpoint);
        Optional<OreDigTask.RestoreMetadata> parent =
                OreDigTask.inspectCheckpoint(parentCheckpoint, 0);
        if (service.isEmpty() || parent.isEmpty()
                || service.orElseThrow().policy().profile()
                != ServiceProfile.ORE_BATCH
                || service.orElseThrow().serviceTargetCount() != 0
                || service.orElseThrow().serviceBoundary() != 0
                || parent.orElseThrow().rareMissionTarget() != 0
                || capacityRetry != parent.orElseThrow().batchOpen()
                || capacityRetry && !parent.orElseThrow().inventoryServiceUsed()
                || hasOreDigPhysicalLedger(parentCheckpoint)
                || !OreDigTask.oreFingerprint(service.orElseThrow().ores()).equals(
                OreDigTask.oreFingerprint(parent.orElseThrow().ores()))
                || !parent.orElseThrow().cursor().face().equals(
                decodePos(serviceCheckpoint.get("work_face")).orElse(null))) {
            return false;
        }
        String[][] cursorFields = {
                {"origin", "cursor_origin"},
                {"face", "cursor_face"},
                {"direction", "cursor_direction"},
                {"leg", "cursor_leg"},
                {"steps_left", "cursor_steps_left"},
                {"leg_length", "cursor_leg_length"},
                {"batches", "cursor_batches"}
        };
        for (String[] fields : cursorFields) {
            if (!java.util.Objects.equals(
                    parentCheckpoint.get(fields[0]), serviceCheckpoint.get(fields[1]))) {
                return false;
            }
        }
        return true;
    }

    static boolean ordinaryServiceMiningNamespaceMatches(
            Goal goal,
            Set<Block> serviceOres,
            BlockPos serviceFace,
            Optional<OreDigTask.RestoreMetadata> mining) {
        if (mining == null || mining.isEmpty()) {
            return true;
        }
        OreDigTask.RestoreMetadata metadata = mining.orElseThrow();
        int rareTarget = originalLongRareOreTargetCount(goal);
        if (rareTarget > 0 && metadata.rareMissionTarget() == rareTarget
                && miningStepFeedsGoal(goal, metadata.ores())) {
            return true;
        }
        return metadata.rareMissionTarget() == 0
                && OreDigTask.oreFingerprint(serviceOres).equals(
                OreDigTask.oreFingerprint(metadata.ores()))
                && serviceFace != null && serviceFace.equals(metadata.cursor().face());
    }

    // Package-private: also called by MissionRecoveryScheduler's capacity/service scheduling.
    static boolean hasOreDigPhysicalLedger(Map<String, String> checkpoint) {
        return checkpoint != null && (checkpoint.containsKey("pending_pickup_pos")
                || checkpoint.containsKey("active_break_pos"));
    }

    private static boolean isRareOre(Set<Block> ores) {
        Set<Block> expanded = OreScan.expandOreFamilies(ores == null ? Set.of() : ores);
        return expanded.contains(Blocks.DIAMOND_ORE)
                || expanded.contains(Blocks.DEEPSLATE_DIAMOND_ORE)
                || expanded.contains(Blocks.EMERALD_ORE)
                || expanded.contains(Blocks.DEEPSLATE_EMERALD_ORE);
    }

    private static boolean isRareOreDrop(Item item) {
        return item == Items.DIAMOND || item == Items.EMERALD;
    }

    private void handleStepFailure(MinecraftServer server, AIPlayerEntity bot, ActivePlan plan, String reason) {
        captureTaskEvidence(bot, plan);
        // A timed collector only emits this reason after its entire search window elapsed with no
        // mission-owned gain. Replanning would start another full window and turn the promised
        // ten-minute result into an unbounded loop, so publish the factual terminal outcome.
        if (isTimedCollectionStep(plan.current)
                && GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE.equals(reason)) {
            finishActive(bot, plan, evaluate(bot, plan), reason,
                    false, true, GoalResult.Status.FAILED);
            return;
        }
        Optional<SettledServiceTombstone> replayGuard =
                MissionRecoveryScheduler.matchingSettledServiceGuard(bot, plan, reason);
        if (replayGuard.isPresent()) {
            // MiningService reached its exact PREPARE capacity predicate and rejected the
            // forbidden geometry before any pocket mutation. This typed fact is terminal; generic
            // replanning would merely route back to the same guard.
            finishActive(bot, plan, evaluate(bot, plan),
                    replayGuard.orElseThrow().failureReason(), false, true,
                    GoalResult.Status.FAILED);
            return;
        }
        // A failed service with any live disposal identity still owns a physical world mutation.
        // Generic replanning would restore MINE_ORE from its separate cursor namespace and replace
        // this checkpoint on the next capture, orphaning the unsealed ItemEntity ledger.  The task
        // has already exhausted its own bounded recovery, so stop the mission with the exact typed
        // reason instead of manufacturing a fresh transaction.
        if (plan.current != null && plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                && hasActiveServicePocket(plan.taskCheckpoint)) {
            finishActive(bot, plan, evaluate(bot, plan), reason,
                    false, true, GoalResult.Status.FAILED);
            return;
        }
        boolean failedClosedPrimaryService = plan.current != null
                && plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                && plan.taskCheckpointKind == GoalStep.Kind.MINING_SERVICE
                && plan.auxiliaryMiningCheckpoint.isEmpty()
                && !plan.miningCheckpoint.isEmpty()
                && failedClosedAuxiliaryServiceMatches(
                plan.taskCheckpoint, plan.miningCheckpoint);
        boolean failedClosedAuxiliaryService = plan.current != null
                && plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                && plan.taskCheckpointKind == GoalStep.Kind.MINING_SERVICE
                && !plan.auxiliaryMiningCheckpoint.isEmpty()
                && failedClosedAuxiliaryServiceMatches(
                plan.taskCheckpoint, plan.auxiliaryMiningCheckpoint);
        Optional<MiningServiceTask.RestoreMetadata> settledTerminalService =
                MissionRecoveryScheduler.settledTerminalServiceFailure(plan, reason);
        if (settledTerminalService.isPresent()) {
            // The double-sealed pocket has no remaining physical authority. This receipt exists
            // solely to bridge TaskManager -> GoalExecutor settlement. Persist its semantic
            // identity and exact work face before consuming it so repair/craft/supply prefixes and
            // process restarts cannot reopen the same pocket as a fresh service transaction.
            MiningServiceTask.RestoreMetadata settledMetadata =
                    settledTerminalService.orElseThrow();
            SettledServiceAuthority authority = persistedServiceAuthority(
                    settledMetadata).orElseThrow();
            SettledServiceTombstone tombstone = new SettledServiceTombstone(
                    authority, reason);
            Optional<SettledServiceTombstone> sameGeometry =
                    plan.settledServiceTombstones.values().stream()
                            .filter(settled -> settled.sameGeometry(tombstone))
                            .findFirst();
            SettledServiceTombstone existing = sameGeometry.orElse(null);
            if (existing != null && !existing.equals(tombstone)
                    || existing == null && plan.settledServiceTombstones.size()
                    >= MAX_SETTLED_SERVICE_TOMBSTONES) {
                BotLog.task(bot, "goal_terminal_service_tombstone_capacity_rejected",
                        "reason", reason,
                        "entries", plan.settledServiceTombstones.size());
                finishActive(bot, plan, evaluate(bot, plan), reason,
                        false, true, GoalResult.Status.FAILED);
                return;
            }
            plan.settledServiceTombstones.putIfAbsent(tombstone.key(), tombstone);
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
            // Disconnect the failed receipt source before any immediate capture/restart window;
            // the bounded tombstone is now the sole durable authority for this result.
            plan.currentTask = null;
            BotLog.task(bot, "goal_terminal_service_receipt_consumed",
                    "reason", reason,
                    "face", authority.geometry().workFace().toShortString(),
                    "entries", plan.settledServiceTombstones.size());
            if (!bot.level().dimension().identifier().toString()
                    .equals(settledMetadata.serviceDimension())) {
                finishActive(bot, plan, evaluate(bot, plan), reason,
                        false, true, GoalResult.Status.FAILED);
                return;
            }
        }
        if (MissionRecoveryScheduler.resumeOreDigAfterToolRecovery(bot, plan, reason)) {
            return;
        }
        if (MissionRecoveryScheduler.scheduleOrdinaryChannelToolResupply(bot, plan, reason)) {
            return;
        }
        if ("ore_dig_inventory_service_required".equals(reason)) {
            if (!recoveryScheduler.scheduleRareInventoryService(bot, plan)
                    && !recoveryScheduler.scheduleBoundedCapacityHandoff(bot, plan)) {
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        if (MissionRecoveryScheduler.isLongRareChannelToolFailure(plan, reason)) {
            if (!recoveryScheduler.scheduleRareResourceRetry(bot, plan, reason)) {
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        if (reason != null && reason.startsWith("ore_dig_torch_epoch_exhausted:")) {
            if (!recoveryScheduler.scheduleRareResourceRetry(bot, plan, reason)) {
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        if (MissionRecoveryScheduler.isLongRareResourceEpochTimeout(plan, reason)) {
            if (!recoveryScheduler.scheduleRareResourceRetry(bot, plan, reason)) {
                // Every funded epoch owns one independent 24,000-tick window: the batch's own
                // retry first, then bounded mission-margin epochs while the shared pool lasts.
                // Once both are spent the cumulative checkpoint is terminal and must not fall
                // through to a refreshable replan.
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        GoalStep failedStep = plan.current;
        // GiveItemTask can fail only after InventoryAction has made a physical debit in these
        // two cases.  There is no trustworthy receipt to carry into a new plan, so replanning
        // could craft and drop the allocation a second time.  Keep the mission terminal and
        // surface the factual uncertainty instead.
        if (isUnreceiptedPhysicalDeliveryFailure(failedStep, reason)) {
            finishActive(bot, plan, evaluate(bot, plan), reason,
                    false, true, GoalResult.Status.FAILED);
            return;
        }
        if (isUnsettledHuntPhysicalDebt(
                failedStep == null ? null : failedStep.kind(), reason)) {
            // Meat in inventory cannot erase a return-to-surface debt. In particular, Goal.Food
            // normally treats HUNT as best-effort; letting these failures reach that branch would
            // skip directly to cooking while the bot is still underground.
            finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            return;
        }
        // A descent that is physically below its requested hand-off layer is a broken safety
        // invariant, not useful mining progress. Replanning from that Y would skip DESCEND_TO_Y and
        // continue the mission on an unverified floor, so terminate the mission fail-closed.
        if (reason != null && (reason.startsWith("descend_overshoot_unrecoverable")
                || reason.startsWith("descend_landing_pose_drift")
                || reason.startsWith("descend_invalid_checkpoint")
                || reason.startsWith("descend_timeout")
                || reason.startsWith("descend_water_seal_checkpoint_capacity")
                || reason.startsWith("descend_no_safe_landing")
                || reason.startsWith("dig_down_return_failed")
                // AcquireWater owns a durable, bounded surface-search cursor. Once that cursor or
                // its hard budget is exhausted, replanning the same acquisition cannot create new
                // evidence; doing so used to replay satisfied craft steps and eventually replace
                // the exhausted checkpoint with a fresh 12,000-tick search.
                || reason.startsWith("acquire_water_timeout")
                || reason.startsWith("acquire_water_search_exhausted")
                || reason.startsWith("acquire_water_no_reachable_surface_route")
                || reason.startsWith("acquire_water_surface_return_unreachable")
                || reason.startsWith("acquire_water_invalid_checkpoint")
                // The persisted search cursor has factually observed all four directions at the
                // same work face as closed. Replaying that cursor cannot reveal a new exit and used
                // to refresh progress by rotating in place until the global timeout.
                || reason.startsWith("create_obsidian_search_enclosed")
                // Both perpendicular branch candidates are factually old or unsafe, while forward
                // is an observed boundary and reverse is the controlled corridor. Replanning the
                // same durable cursor cannot create a new work face and only repeats the failure.
                || reason.startsWith("ore_dig_branch_boundary_trapped:")
                // OreDig has already spent its durable physical-pickup deadline at this point.
                // A fresh plan cannot recover the vanished/occluded finite drop, and must not
                // replace the factual coordinate with an unrelated underground supply error.
                || reason.startsWith("ore_dig_drop_unrecovered"))) {
            finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            return;
        }
        // A failed DESCEND attempt is terminal evidence for that task instance, not a resumable
        // cursor. Its saved target_count is the original batch size while a fresh plan asks only
        // for the remaining delivery; carrying the old checkpoint into that smaller step makes
        // DigDown reject it as corrupt and causes a second pointless replan. RETURN failure is
        // handled fail-closed above because unresolved physical return debt must never be erased.
        if (plan.current != null && plan.current.kind() == GoalStep.Kind.MINE) {
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
        }
        // Layer 4: a failure in an explicitly best-effort step does not block the overall goal -- skip it and continue directly to the next step.
        // Surface food readiness for long-quota mining does not carry this flag; a HUNT/COOK failure must block, so a food shortage can't be carried all the way down to the bottom of the mine.
        // Hunting (Goal.Food) is best-effort as a whole: a failure in any prerequisite (chopping wood/making a sword) is downgraded and continues (using existing tools/hunting bare-handed), and must never get stuck/stall.
        // Exception: within a Food goal, COOK_FOOD is the final output step -- its failure (no_raw_food) means the whole goal must fail; skipping it would be a silent abandonment.
        // Fall through to the replan below: re-sense and pick a new source (after a hunt comes up empty the animals are mostly gone by now; the replan will fall back to a source like berries/bread;
        // observed in testing: hunting whiffs for 1 tick -> cooking with no meat -> silently ends, never giving the fallback source a chance).
        if (shouldSkipFailedStep(plan.goal, plan.current, reason)) {
            if (settledTerminalService.isEmpty()) {
                captureTaskEvidence(bot, plan);
            }
            if (plan.skippedTargetReceipts.size()
                    >= MAX_SKIPPED_TARGET_RECEIPTS) {
                finishActive(bot, plan, evaluate(bot, plan),
                        "skipped_target_receipt_capacity_exhausted",
                        false, true, GoalResult.Status.FAILED);
                return;
            }
            SkippedTargetReceipt receipt =
                    new SkippedTargetReceipt(plan.current, reason);
            plan.skippedTargetReceipts.add(receipt);
            plan.skippedSteps.add(receipt.asSkippedStep());
            BotLog.task(bot, "goal_step_skipped_besteffort", "step", plan.current.describe(), "reason", reason);
            clearCompletedTaskCheckpoint(plan);
            plan.current = null;
            plan.currentTask = null;
            captureTransitionAndAssignNext(bot, plan);
            return;
        }
        // Phase A progress-aware budget (the core of checkpoint/resume): progress made -> reset the "consecutive no-progress" counter. For HUNT,
        // only a net increase in raw meat or a search sector visited for the first time counts as additional progress; lateral movement/descent caused by chasing prey must never
        // refresh the budget. Other steps keep the semantics of mine-shaft lateral movement and descent.
        net.minecraft.core.BlockPos bp = bot.blockPosition();
        int curTarget = goalTargetCount(bot, plan.goal);
        int currentHuntRawMeat = rawMeatCount(bot);
        int currentHuntVisitedSectors = plan.huntSearchCursor.visitedCount();
        String currentDimension = bot.level().dimension()
                .identifier().toString();
        boolean madeProgress = madeReplanProgress(
                plan.current == null ? null : plan.current.kind(),
                plan.completedSteps, plan.snapSteps,
                curTarget, plan.snapTargetCount,
                currentHuntRawMeat, plan.snapHuntRawMeat,
                currentHuntVisitedSectors, plan.snapHuntVisitedSectors,
                currentDimension, plan.snapDimension,
                bp.getX(), bp.getY(), bp.getZ(),
                plan.snapX, plan.snapY, plan.snapZ);
        if (madeProgress) {
            plan.replanCount = 0; // progress grants amnesty
        }
        plan.snapSteps = plan.completedSteps;
        plan.snapTargetCount = curTarget;
        plan.snapX = bp.getX();
        plan.snapY = bp.getY();
        plan.snapZ = bp.getZ();
        plan.snapDimension = currentDimension;
        plan.snapHuntRawMeat = currentHuntRawMeat;
        plan.snapHuntVisitedSectors = currentHuntVisitedSectors;
        // Death gate: 3 consecutive no-progress replans, or the lifetime budget tied to the original long-quota batch count is exhausted,
        // or replanning is disabled -> declare death. The counter increments after the gate check, so the cap represents the actual number of replans allowed.
        if (!withinReplanBudget(plan.goal, plan.replanCount, plan.lifetimeReplans)
                || !MinecraftAiConfig.get().goal().replanOnFailureEnabled()) {
            finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            return;
        }
        plan.replanCount++;
        plan.lifetimeReplans++;
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
        Optional<CreateObsidianTask.RestoreMetadata> obsidianRestore =
                CreateObsidianTask.inspectCheckpoint(plan.obsidianCheckpoint);
        List<GoalStep> replanned = new ArrayList<>(applySkippedTargetReceipts(
                fresh.success() ? fresh.steps() : List.of(),
                plan.skippedTargetReceipts));
        if (obsidianRestore.isPresent()) {
            CreateObsidianTask.RestoreMetadata metadata = obsidianRestore.get();
            if (!fresh.success() && !metadata.transactionOpen()) {
                finishActive(bot, plan, evaluate(bot, plan),
                        settledTerminalService.isPresent() ? reason
                                : "replan_failed:" + String.join(",", fresh.unresolved()),
                        false, true);
                return;
            }
            // An open water/pickup/break transaction is a physical obligation and must be settled
            // before any newly planned acquisition. A safe search checkpoint may wait behind tool
            // or water repair, but its original target identity is still retained.
            int resumeIndex = reconcileObsidianSteps(
                    replanned, metadata.targetCount(), metadata.transactionOpen(),
                    isObsidianMissingResourceFailure(reason));
            if (metadata.transactionOpen() && resumeIndex > 0) {
                // F5: under a missing-resource failure, the resupply prefix physically executes ahead of the open transaction's resume step; otherwise the resumed task
                // would fail again for the same reason on its very first tick, and three zero-progress replans would declare it dead.
                BotLog.task(bot, "goal_obsidian_resume_resupply_first",
                        "reason", reason,
                        "supply_steps", resumeIndex,
                        "target", metadata.targetCount());
            }
        }
        if (failedClosedPrimaryService && fresh.success()) {
            OreDigTask.RestoreMetadata failedCursor = OreDigTask.inspectCheckpoint(
                    plan.miningCheckpoint, 0).orElseThrow();
            Set<Block> failedFamily = failedCursor.ores();
            boolean sameFamilyContinuation = hasFreshMiningSuccessor(
                    replanned, failedFamily);
            // The service has already double-sealed its empty pocket and published PREPARE without
            // pocket identity.  Retire only that exact task checkpoint.  Its closed primary cursor
            // remains authoritative while the fresh plan still contains the same ore family; once
            // the dependency is satisfied, keeping it would bind a later unrelated service to a
            // stale work face.
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
            if (sameFamilyContinuation) {
                BotLog.task(bot, "goal_failed_primary_service_cursor_retained",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            } else {
                plan.miningCheckpoint.clear();
                BotLog.task(bot, "goal_failed_primary_service_retired",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            }
        }
        if (failedClosedAuxiliaryService && fresh.success()) {
            OreDigTask.RestoreMetadata failedCursor = OreDigTask.inspectCheckpoint(
                    plan.auxiliaryMiningCheckpoint, 0).orElseThrow();
            Set<Block> failedFamily = failedCursor.ores();
            boolean sameFamilyContinuation = hasFreshMiningSuccessor(
                    replanned, failedFamily);
            // The failed service has no live pocket, so its task checkpoint can be retired once a
            // fresh plan exists. Its closed ordinary cursor is different: preserve it while that
            // same family is still planned, including across intervening repair steps and restarts.
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
            if (sameFamilyContinuation) {
                plan.auxiliaryMiningContinuationFingerprint =
                        OreDigTask.oreFingerprint(failedFamily);
                BotLog.task(bot, "goal_failed_auxiliary_service_cursor_retained",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            } else {
                plan.auxiliaryMiningCheckpoint.clear();
                plan.auxiliaryMiningContinuationFingerprint = "";
                BotLog.task(bot, "goal_failed_auxiliary_service_retired",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            }
        }
        if (plan.capacityParentNamespace != null && fresh.success()) {
            // F8: a general replan is about to clear the entire step queue, which would also destroy the capacity service's precise retry step. If the fresh
            // plan no longer contains a MINE_ORE step of the same family that could rebind this debit (checkpointForMineOre + isCapacityParentRetry
            // re-select by fingerprint/count when assigning), the capacity-parent marker would permanently lose its settlement path: evidence capture would from then on reject
            // every MINE_ORE miningCheckpoint update, and the next successfully committed rare batch would die at the moment of success with rare_batch_commit_checkpoint_invalid. Here, within
            // the same transaction that installs the new queue, roll back that marker. A parent carrying an unsettled physical ledger (pickup/block-break) or one that can't be decoded
            // keeps the existing fail-closed semantics and is not rolled back.
            Map<String, String> capacityParentCheckpoint = plan.capacityParentNamespace
                    == CapacityParentNamespace.AUXILIARY
                    ? plan.auxiliaryMiningCheckpoint : plan.miningCheckpoint;
            // Uses the same discriminant as settleCompletedCapacityParent: a capacity parent can only be a plain
            // (rare_mission_target=0) batch; a marker pointing to a rare cursor is an unexplainable state, so it stays fail-closed.
            Optional<OreDigTask.RestoreMetadata> capacityParent =
                    OreDigTask.inspectCheckpoint(capacityParentCheckpoint, 0)
                            .filter(value -> value.rareMissionTarget() == 0);
            boolean retryBindable = capacityParent
                    .filter(OreDigTask.RestoreMetadata::batchOpen)
                    .filter(parent -> replanned.stream().anyMatch(step ->
                            step.kind() == GoalStep.Kind.MINE_ORE
                                    && parent.acceptsStepTarget(step.count())
                                    && OreDigTask.oreFingerprint(parent.ores()).equals(
                                    OreDigTask.oreFingerprint(step.ores()))))
                    .isPresent();
            if (!retryBindable && capacityParent.isPresent()
                    && !hasOreDigPhysicalLedger(capacityParentCheckpoint)) {
                CapacityParentNamespace rolledBack = plan.capacityParentNamespace;
                BotLog.task(bot, "goal_capacity_parent_rolled_back",
                        "reason", reason,
                        "namespace", rolledBack.persistedName,
                        "family", OreDigTask.oreFingerprint(
                                capacityParent.orElseThrow().ores()),
                        "delivered_watermark", plan.capacityParentDelivered,
                        "services_used", plan.capacityParentServicesUsed);
                plan.capacityParentNamespace = null;
                plan.capacityParentDelivered = -1;
                plan.capacityParentFace = null;
                plan.capacityParentServicesUsed = 0;
                if (rolledBack == CapacityParentNamespace.AUXILIARY) {
                    // A dangling open plain-batch cursor carries no physical debt; discard it -- the delivered product is already in the inventory, and the planner
                    // will recompute honestly from that inventory. Keeping it would instead cause a restart to fail-closed on the aux namespace
                    // for missing a capacity marker (mission_restore_invalid_auxiliary_mining_checkpoint).
                    plan.auxiliaryMiningCheckpoint.clear();
                    plan.auxiliaryMiningContinuationFingerprint = "";
                } else {
                    // Same pattern as goal_failed_primary_service_retired above: when the fresh plan no longer includes this
                    // family, retire its cursor, to avoid a stale open batch hijacking the restart validation of a later, unrelated task.
                    plan.miningCheckpoint.clear();
                }
            }
        }
        BotLog.task(bot, "goal_replan", "goal", plan.goal, "reason", reason,
                "steps", replanned.stream().map(GoalStep::describe).toList(),
                "unresolved", fresh.unresolved());
        if ((!fresh.success() && obsidianRestore.isEmpty()) || replanned.isEmpty()) {
            finishActive(bot, plan, evaluate(bot, plan),
                    settledTerminalService.isPresent() ? reason
                            : fresh.success() ? "replan_empty"
                            : "replan_failed:" + String.join(",", fresh.unresolved()),
                    false, true);
            return;
        }
        // Foolproofing: if the replanned first step is exactly the same as the step that just failed, and the failure is a "hard stuck" type (can't dig/stuck/timeout),
        // retrying would just fail again the same way (this was the root cause of the replan storm observed in test #9). Declare failure immediately and hand it back to the brain/player to try a different approach.
        if (plan.current != null && plan.current.equals(replanned.get(0))
                && isHardFailure(reason) && !madeProgress) {
            finishActive(bot, plan, evaluate(bot, plan), "replan_same_step:" + reason, false, true);
            return;
        }
        plan.steps.clear();
        plan.steps.addAll(replanned);
        plan.totalSteps = replanned.size();
        plan.current = null;
        plan.currentTask = null;
        report(bot, "I ran into a problem and replanned the goal.");
        captureTransitionAndAssignNext(bot, plan);
    }

    // Optimization 2: whether the goal has failed overall recently (within withinTicks) -- used by ActionDispatcher to intercept the brain's manual block-by-block mining after a failure.
    public boolean recentlyFailed(AIPlayerEntity bot, int withinTicks) {
        Integer t = lastGoalFailTick.get(bot.getUUID());
        return t != null && bot.level().getServer().getTickCount() - t < withinTicks;
    }

    // B: clear the memory of the original goal when the user sends a new message (allowing the user to switch goals normally); called by BrainCoordinator when it receives a user message.
    public void clearUserGoal(AIPlayerEntity bot) {
        userGoal.remove(bot.getUUID());
    }

    // B: whether sub is a prerequisite of parent (the user's original goal) -- sub's product falls within the output of some step in parent's plan.
    // Covers the main case: making an iron pickaxe (HaveItem) / mining iron (MineOre) are both prerequisite steps within a mine-diamond plan, and will be blocked.
    private boolean isPrerequisiteOf(AIPlayerEntity bot, Goal sub, Goal parent) {
        GoalPlanner.GoalPlan parentPlan = GoalPlanner.plan(bot, parent);
        Set<Item> items = new HashSet<>();
        Set<Block> ores = new HashSet<>();
        for (GoalStep s : parentPlan.steps()) {
            if (s.item() != null) {
                items.add(s.item());
            }
            if (s.kind() == GoalStep.Kind.MINE_ORE) {
                ores.addAll(s.ores());
            }
        }
        if (sub instanceof Goal.HaveItem hi) {
            return items.contains(hi.item());
        }
        if (sub instanceof Goal.MineOre mo) {
            for (Block b : mo.ores()) {
                if (ores.contains(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Package-private: also called by MissionRecoveryScheduler to redispatch a resumed step.
    static Optional<Task> stepToTask(AIPlayerEntity bot, GoalStep step, ActivePlan plan) {
        return switch (step.kind()) {
            // Planner GATHER counts are incremental deliveries; GatherQuotaTask owns an absolute
            // family quota (all log species, all forage foods, or the exact item). A fresh
            // Fulfill final allocation is different: its immutable baseline is exact-item based,
            // so an oak-log delivery must not be satisfied by newly collecting birch logs.
            case GATHER -> Optional.of(plan != null && isFreshFulfillOutputGather(plan.goal, step.item())
                    ? GatherQuotaTask.collectAdditionalExact(step.item(), step.count())
                    : new GatherQuotaTask(step.item(), gatherTargetCount(
                            GatherQuotaTask.acceptedInventoryCount(bot, step.item()), step.count())));
            // Generic mining selects visible, reachable blocks and may widen the view through
            // bounded observation-fenced walk-only hops.  Its movement is Baritone-owned and it
            // never opens a shaft or paths toward unseen terrain to discover a target.
            case MINE -> Optional.of(OreScan.isOreBlock(step.block())
                    ? new OreDigTask(OreScan.oreFamily(step.block()), step.count())
                    : new MineTask(step.block(), step.count()));
            // OreDig mines only observed/revalidated finite ore targets.  Count mode may widen
            // its view through bounded observed walk-only hops, but has no layer-seeking or
            // blind-tunnel fallback behind this task boundary.
            case MINE_ORE -> {
                Map<String, String> oreCheckpoint =
                        plan.checkpointForMineOre(step.ores());
                plan.currentCapacityParentRetry =
                        plan.isCapacityParentRetry(step, oreCheckpoint);
                int rareMissionTarget = rareMissionTargetForMiningStep(
                        plan.goal, step.ores());
                yield Optional.of(new OreDigTask(
                        step.ores(), step.count(), rareMissionTarget,
                        miningParentStoneLikeReserve(
                                plan.goal, rareMissionTarget,
                                rareMissionTarget > 0
                                        ? oreCheckpoint : plan.miningCheckpoint),
                        oreCheckpoint));
            }
            // A duration-bearing ore step restores only its small clock/baseline receipt.  It
            // deliberately never consumes a fixed-quota OreDig cursor.
            case MINE_ORE_FOR_DURATION -> Optional.of(OreDigTask.collectForDuration(
                    step.ores(), step.count(),
                    plan.takeTaskCheckpoint(GoalStep.Kind.MINE_ORE_FOR_DURATION)));
            case MINING_SERVICE -> {
                MiningServiceInvocation invocation =
                        miningServiceInvocation(step, plan);
                Map<String, String> serviceCheckpoint =
                        plan.takeTaskCheckpoint(GoalStep.Kind.MINING_SERVICE);
                MiningCursor serviceCursor = serviceCursor(
                        bot, plan, step, serviceCheckpoint);
                yield Optional.of(new MiningServiceTask(
                        step.ores(),
                        serviceCheckpoint,
                        invocation.policy(),
                        invocation.boundary(),
                        plan.missionId.toString(),
                        invocation.target(),
                        serviceCursor,
                        plan.settledServiceTombstones.values().stream()
                                .map(SettledServiceTombstone::replayGuard)
                                .toList()));
            }
            // GoalStep.CRAFT carries an incremental delivery count, while CraftTask accepts an
            // absolute inventory target. Preserve already-crafted copies when the planner emits
            // multiple batches of the same tool (for example 1 bootstrap + 4 expedition picks).
            case CRAFT -> Optional.of(new CraftTask(step.item(), craftTargetCount(
                    io.github.zoyluo.minecraftai.action.InventoryAction.countItem(bot, step.item()),
                    step.count())));
            case SMELT -> Optional.of(new SmeltTask(step.input(), step.output(), step.count()));
            case MOVE -> Optional.of(new MoveTask(bot, step.pos()));
            // P3: the FARM step -> a count-limited FarmTask (till/plant/wait for growth/harvest in place; completes once count units of produce are collected).
            case FARM -> Optional.of(new FarmTask(bot.blockPosition(), 4, step.input(), step.block(),
                    true, false, step.item(), step.count()));
            case FARM_FOR_DURATION -> Optional.of(FarmTask.collectForDuration(
                    bot.blockPosition(), 4, step.input(), step.block(), step.item(), step.count(),
                    plan.takeTaskCheckpoint(GoalStep.Kind.FARM_FOR_DURATION)));
            // Layer 4: the HUNT step -> HuntTask kills animals for raw meat (to stock food).
            case HUNT -> Optional.of(new HuntTask(
                    step.count(), !step.bestEffort(), plan.huntSearchCursor,
                    plan.peekTaskCheckpoint(GoalStep.Kind.HUNT)));
            // P0 food loop: the COOK_FOOD step -> SmeltTask in cookAll mode, cooking each type of raw meat in the inventory into cooked meat.
            case COOK_FOOD -> Optional.of(new SmeltTask(step.count(), !step.bestEffort()));
            // Cake chain: the MILK_COW step -> MilkCowTask uses an empty bucket to milk count buckets of milk.
            case MILK_COW -> Optional.of(new MilkCowTask(step.count()));
            // Phase 2: the PLACE_STATIONS step -> set up a crafting table/furnace/chest.
            case PLACE_STATIONS -> Optional.of(new PlaceStationsTask());
            // Phase 3: the STOCKPILE step -> store inventory resources into a nearby chest (store everything that isn't a tool).
            case STOCKPILE -> Optional.of(new StockpileTask(true));
            case GIVE_ITEM -> {
                // A restored/replanned receipt is authoritative. Never construct a second
                // physical drop task for an allocation already committed by this mission.
                if (hasCommittedDelivery(plan, step)) {
                    yield Optional.empty();
                }
                boolean freshFulfillDelivery = isFreshFulfillDelivery(plan, step);
                yield Optional.of(new GiveItemTask(
                        step.item(), step.count(), step.giveRecipient(),
                        () -> commitDeliveryReceipt(bot, plan, step),
                        freshFulfillDelivery,
                        () -> !freshFulfillDelivery
                                || hasFreshFulfillDeliveryQuota(bot, plan, step)));
            }
            // Kept only to fail safely when resuming a pre-migration mission checkpoint. New
            // plans no longer emit layer-seeking excavation steps.
            case DESCEND_TO_Y -> Optional.of(new RetiredNavigationTask("descend_to_y"));
            case ACQUIRE_WATER -> Optional.of(new io.github.zoyluo.minecraftai.task.AcquireWaterTask(
                    plan.origin, plan.peekTaskCheckpoint(GoalStep.Kind.ACQUIRE_WATER)));
            case MAKE_OBSIDIAN -> Optional.of(new CreateObsidianTask(
                    step.count(), plan.checkpointForObsidian()));
            // Building: the BUILD step -> BuildTask (auto site selection + ground flattening, so it can still complete on real, uneven terrain); materials are already prepared during the planning phase;
            // if loading the blueprint fails (deleted/corrupted save) -> empty, and assignNext wraps up treating it as "step cannot be executed".
            // flatten=true: ready-made flat ground is rare on real terrain, so lenient site selection picks the flattest spot + FLATTEN cuts down high ground and fills in low ground to level it (fixes real_build no_flat_site).
            case BUILD -> {
                try {
                    BlockPos anchor = plan.buildAnchor;
                    yield Optional.of(new BuildTask(
                            BlueprintLoader.load(step.tag()), anchor, anchor == null, anchor == null));
                } catch (IOException e) {
                    yield Optional.empty();
                }
            }
        };
    }

    /**
     * Mirrors MINING_SERVICE construction without consuming any task checkpoint.
     *
     * <p>An independent branch cursor remains the strongest cross-namespace witness. When that
     * namespace is legitimately absent, an interrupted service must retain its own already-decoded
     * cursor (including an explicit cursor-less legacy/obsidian checkpoint) instead of inventing
     * authority from the bot's crash position. Only a genuinely fresh service may anchor itself to
     * the current position.</p>
     */
    private static MiningCursor serviceCursor(
            AIPlayerEntity bot, ActivePlan plan, GoalStep step,
            Map<String, String> serviceCheckpoint) {
        MiningCursor branchCursor = plan.miningCursorForService(step.ores());
        if (branchCursor != null) {
            return branchCursor;
        }
        if (serviceCheckpoint != null && !serviceCheckpoint.isEmpty()) {
            return MiningServiceTask.inspectCheckpoint(serviceCheckpoint)
                    .map(MiningServiceTask.RestoreMetadata::miningCursor)
                    .orElse(null);
        }
        return MiningCursor.initial(bot.blockPosition(), 48);
    }

    private static MiningServiceInvocation miningServiceInvocation(
            GoalStep step, ActivePlan plan) {
        if (step.isObsidianService()) {
            int target = step.obsidianTransactionTarget();
            return new MiningServiceInvocation(
                    ServicePolicy.obsidian8(target, step.count()),
                    target, step.count());
        }
        if (step.isObsidianPreflight()) {
            int target = step.obsidianTransactionTarget();
            return new MiningServiceInvocation(
                    ServicePolicy.obsidianPreflight(target), target, 0);
        }
        if (step.isRareOreService()) {
            int target = step.rareOreMissionTarget();
            return new MiningServiceInvocation(
                    ServicePolicy.rareOreBatch(
                            target, step.count(), plan.rareResourceRetriesUsed),
                    target, step.count());
        }
        if (step.isRareDescentKitService()) {
            int target = step.rareDescentKitMissionTarget();
            return new MiningServiceInvocation(
                    ServicePolicy.rareDescentKit(target), target, 0);
        }
        ServicePolicy policy = step.isMiningHandoffService()
                ? ServicePolicy.capacityHandoff(
                step.miningHandoffStoneLikeReserve())
                : ServicePolicy.defaultOre(
                step.maintainsTunnelingTools());
        return new MiningServiceInvocation(policy, 0, 0);
    }

    static int craftTargetCount(int existing, int increment) {
        return Math.max(0, existing) + Math.max(1, increment);
    }

    /**
     * Only final fresh-Fulfill output gets exact gather semantics.  Ingredient gathers retain
     * their normal family-aware behavior so a plan may still bootstrap from any local log type.
     */
    private static boolean isFreshFulfillOutputGather(Goal goal, Item item) {
        return goal instanceof Goal.Fulfill fulfill
                && fulfill.isFreshInventoryRequest()
                && fulfill.initialItemCounts().containsKey(item);
    }

    /** Fresh final delivery owns a conservation boundary until its receipt is committed. */
    private static boolean isFreshFulfillDelivery(ActivePlan plan, GoalStep step) {
        return plan != null && step != null && step.kind() == GoalStep.Kind.GIVE_ITEM
                && plan.goal instanceof Goal.Fulfill fulfill
                && fulfill.isFreshInventoryRequest();
    }

    /**
     * The exact item total must still cover the immutable baseline plus every retained and
     * unreceipted allocation.  GiveItemTask separately proves its own droppable count; this
     * total-domain check prevents an old stack from being handed over after fresh output was
     * consumed by another task.
     */
    private static boolean hasFreshFulfillDeliveryQuota(AIPlayerEntity bot,
                                                         ActivePlan plan,
                                                         GoalStep step) {
        if (!isFreshFulfillDelivery(plan, step)) {
            return true;
        }
        if (plan.current != step || hasCommittedDelivery(plan, step)) {
            return false;
        }
        Goal.Fulfill fulfill = (Goal.Fulfill) plan.goal;
        try {
            declaredDelivery(plan, step);
        } catch (RuntimeException invalidDelivery) {
            return false;
        }
        int required = fulfill.inventoryRequired(plan.completedDeliveries)
                .getOrDefault(step.item(), 0);
        return GoalSnapshotCollector.inventoryCount(bot, step.item()) >= required;
    }

    /**
     * A fresh receipt is valid only if the post-drop inventory still preserves its immutable
     * baseline plus every allocation that remains after this exact handoff.  The pre-drop guard
     * should imply this, but this last check fails closed if inventory changes during the
     * physical debit/callback boundary.
     */
    private static boolean hasFreshFulfillPostDeliveryQuota(AIPlayerEntity bot,
                                                            ActivePlan plan,
                                                            Goal.Allocation allocation) {
        if (!(plan.goal instanceof Goal.Fulfill fulfill) || !fulfill.isFreshInventoryRequest()) {
            return true;
        }
        Set<Goal.Allocation> completedAfterThisDelivery = new HashSet<>(plan.completedDeliveries);
        completedAfterThisDelivery.add(allocation);
        int required = fulfill.inventoryRequired(completedAfterThisDelivery)
                .getOrDefault(allocation.item(), 0);
        return GoalSnapshotCollector.inventoryCount(bot, allocation.item()) >= required;
    }

    static int gatherTargetCount(int existingFamilyCount, int increment) {
        return Math.max(0, existingFamilyCount) + Math.max(1, increment);
    }

    /** Replays an interrupted bootstrap in place; unresolved physical return debt always runs first. */
    private static void reconcileDigDownSteps(List<GoalStep> steps,
                                              DigDownTask.RestoreMetadata metadata) {
        GoalStep restored = GoalStep.mine(metadata.targetBlock(), metadata.targetCount());
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step.kind() == GoalStep.Kind.MINE && step.block() == metadata.targetBlock()) {
                steps.remove(index);
                if (!metadata.returnDebt()) {
                    steps.add(index, restored);
                    return;
                }
                break;
            }
        }
        if (metadata.returnDebt()) {
            steps.add(0, restored);
        } else {
            steps.add(restored);
        }
    }

    /** An entered descent owns an unresolved physical hand-off and must survive replanning. */
    private static void reconcileDescendSteps(List<GoalStep> steps, int targetY) {
        steps.removeIf(step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);
        steps.add(0, GoalStep.descendToY(targetY));
    }

    /**
     * An active ore batch may hold an exact face plus a physical break/pickup ledger. Replanning
     * at the mine layer can prepend a routine service checkpoint, but that must not overwrite the
     * active task cursor. Move the planner's live remaining-count batch to the front; a real tool
     * deficit will fail typed and replan into service without losing the branch debt.
     */
    private static void replayInterruptedOreBatchFirst(
            List<GoalStep> steps,
            OreDigTask.RestoreMetadata metadata) {
        String fingerprint = OreDigTask.oreFingerprint(metadata.ores());
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step.kind() == GoalStep.Kind.MINE_ORE
                    && metadata.acceptsStepTarget(step.count())
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                if (index > 0) {
                    steps.remove(index);
                    steps.add(0, step);
                }
                return;
            }
        }
        // A failed fresh planner can legitimately omit the interrupted task while its exact
        // break/pickup ledger remains physical authority. Recreate the remaining logical batch;
        // a fully delivered open checkpoint uses its original target only to run the final commit
        // tick and clear that ledger.
        int replayTarget = metadata.remainingCount() > 0
                ? metadata.remainingCount() : metadata.targetCount();
        steps.add(0, GoalStep.mineOre(metadata.ores(), replayTarget));
    }

    /**
     * A settled capacity handoff leaves no open service/pickup/break transaction. Its OreDig
     * checkpoint is continuation identity, not authority to jump ahead of newly-live repair
     * prerequisites. Keep the matching fresh step in planner order; if planning omitted it, append
     * one after the dependency prefix instead of moving it to the front.
     */
    private static void reconcileCapacityRetryAfterPrerequisites(
            List<GoalStep> steps,
            OreDigTask.RestoreMetadata metadata) {
        String fingerprint = OreDigTask.oreFingerprint(metadata.ores());
        for (GoalStep step : steps) {
            if (step.kind() == GoalStep.Kind.MINE_ORE
                    && metadata.acceptsStepTarget(step.count())
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                return;
            }
        }
        steps.add(GoalStep.mineOre(metadata.ores(), metadata.targetCount()));
    }

    /**
     * Extracts only the sealed successor of a target64 descent kit. Returning an empty list is
     * intentional: the restored service can finish alone and the ordinary postcondition repair
     * will then plan from its live ready/depot attestation. It is safer than replaying any
     * bootstrap acquisition between KIT and DESCEND.
     */
    static List<GoalStep> rareDescentTail(List<GoalStep> steps, Set<Block> ores) {
        if (steps == null || steps.isEmpty() || ores == null || ores.isEmpty()) {
            return List.of();
        }
        int targetY = io.github.zoyluo.minecraftai.mining.MiningChain.bestY(ores);
        String fingerprint = OreDigTask.oreFingerprint(ores);
        for (int index = 0; index + 2 < steps.size(); index++) {
            GoalStep descend = steps.get(index);
            GoalStep boundaryZero = steps.get(index + 1);
            GoalStep firstBatch = steps.get(index + 2);
            if (descend.kind() == GoalStep.Kind.DESCEND_TO_Y
                    && descend.pos() != null
                    && descend.pos().getY() == targetY
                    && boundaryZero.isRareOreService()
                    && boundaryZero.count() == 0
                    && boundaryZero.rareOreMissionTarget() == 64
                    && fingerprint.equals(
                    OreDigTask.oreFingerprint(boundaryZero.ores()))
                    && firstBatch.kind() == GoalStep.Kind.MINE_ORE
                    && firstBatch.count() == MiningBudget.EXPEDITION_THRESHOLD
                    && fingerprint.equals(OreDigTask.oreFingerprint(firstBatch.ores()))) {
                return List.copyOf(steps.subList(index, steps.size()));
            }
        }
        return List.of();
    }

    /** Returns the one fresh preflight target only when its MAKE step proves the same identity. */
    private static int plannedObsidianPreflightTarget(List<GoalStep> steps) {
        int preflightTarget = -1;
        int makeTarget = -1;
        for (GoalStep step : steps) {
            if (step.isObsidianPreflight()) {
                int candidate = step.obsidianTransactionTarget();
                if (preflightTarget > 0 && preflightTarget != candidate) {
                    return -1;
                }
                preflightTarget = candidate;
            } else if (step.kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                if (makeTarget > 0 && makeTarget != step.count()) {
                    return -1;
                }
                makeTarget = step.count();
            }
        }
        return preflightTarget > 0 && preflightTarget == makeTarget
                ? preflightTarget : -1;
    }

    /**
     * Replays an entered obsidian transaction without a direct obsidian goal's stale acquisition
     * prefix. Compound goals retain every unrelated step because mergeGathers may hoist other
     * material acquisition ahead of MAKE_OBSIDIAN.
     */
    private static void rebuildObsidianAcquisition(Goal goal,
                                                   List<GoalStep> steps,
                                                   List<GoalStep> replayPrefix,
                                                   int transactionTarget) {
        List<GoalStep> remainder = new ArrayList<>();
        if (isDirectObsidianGoal(goal)) {
            boolean afterMake = false;
            for (GoalStep step : steps) {
                if (!afterMake) {
                    afterMake = step.kind() == GoalStep.Kind.MAKE_OBSIDIAN;
                    continue;
                }
                if (step.kind() != GoalStep.Kind.MAKE_OBSIDIAN
                        && !step.isObsidianPreflight()) {
                    remainder.add(step);
                }
            }
        } else {
            // Build and other compound goals can have unrelated material acquisition hoisted ahead
            // of MAKE_OBSIDIAN by mergeGathers. Preserve those steps; only the explicit preflight
            // and MAKE nodes are superseded by the restored transaction identity.
            for (GoalStep step : steps) {
                if (step.kind() != GoalStep.Kind.MAKE_OBSIDIAN
                        && !step.isObsidianPreflight()) {
                    remainder.add(step);
                }
            }
        }
        steps.clear();
        steps.addAll(replayPrefix);
        steps.add(GoalStep.makeObsidian(transactionTarget));
        steps.addAll(remainder);
    }

    private static boolean isDirectObsidianGoal(Goal goal) {
        return goal instanceof Goal.HaveItem haveItem && haveItem.item() == Items.OBSIDIAN
                || goal instanceof Goal.Stockpile stockpile && stockpile.item() == Items.OBSIDIAN;
    }

    static int capacityHandoffStoneLikeReserveForTargets(int obsidianTarget,
                                                          int longRareTarget) {
        return miningParentStoneLikeReserveForTargets(
                obsidianTarget, longRareTarget, false, 0);
    }

    /**
     * A side-fluid placement and a capacity handoff are owned by the same durable mining parent.
     * Before a long rare batch opens, ordinary bootstrap work protects only the emergency reserve.
     * Once the rare parent is open, epoch zero additionally protects its sealed retry heads; epoch
     * one has already spent that debit. Direct obsidian acquisition retains its larger immutable
     * bootstrap/channel-retry horizon throughout all prerequisite phases.
     *
     * <p>Package-private: also called by MissionRecoveryScheduler's capacity-handoff scheduling.</p>
     */
    static int miningParentStoneLikeReserve(
            Goal goal,
            int activeRareMissionTarget,
            Map<String, String> rareParentCheckpoint) {
        int longRareTarget = originalLongRareOreTargetCount(goal);
        Optional<OreDigTask.RestoreMetadata> openRareParent = longRareTarget > 0
                ? OreDigTask.inspectCheckpoint(rareParentCheckpoint, longRareTarget)
                .filter(metadata -> metadata.batchOpen()
                        && metadata.rareMissionTarget() == longRareTarget
                        && miningStepFeedsGoal(goal, metadata.ores()))
                : Optional.empty();
        boolean openingRareParent = activeRareMissionTarget == longRareTarget
                && longRareTarget > 0;
        return miningParentStoneLikeReserveForTargets(
                directObsidianTarget(goal),
                longRareTarget,
                openingRareParent || openRareParent.isPresent(),
                openRareParent.map(OreDigTask.RestoreMetadata::resourceEpoch).orElse(0));
    }

    static int miningParentStoneLikeReserveForTargets(int obsidianTarget,
                                                       int longRareTarget,
                                                       boolean openRareParent,
                                                       int rareParentResourceEpoch) {
        if (obsidianTarget > 0) {
            return capacityHandoffStoneLikeReserveForObsidianTarget(obsidianTarget);
        }
        if (longRareTarget >= MiningBudget.EXPEDITION_THRESHOLD
                && openRareParent && rareParentResourceEpoch == 0) {
            return MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE;
        }
        return MiningBudget.EMERGENCY_STONE_LIKE;
    }

    private static int directObsidianTarget(Goal goal) {
        return goal instanceof Goal.HaveItem haveItem
                && haveItem.item() == Items.OBSIDIAN ? haveItem.count()
                : goal instanceof Goal.Stockpile stockpile
                && stockpile.item() == Items.OBSIDIAN ? stockpile.count() : 0;
    }

    static int capacityHandoffStoneLikeReserveForObsidianTarget(int obsidianTarget) {
        if (obsidianTarget > 0) {
            return ServicePolicy.bootstrapStoneLikeTarget(obsidianTarget)
                    + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;
        }
        return MiningBudget.EMERGENCY_STONE_LIKE;
    }

    private static int directObsidianRemainingTarget(AIPlayerEntity bot,
                                                     Goal goal,
                                                     GoalEvaluation evaluation) {
        int carried = io.github.zoyluo.minecraftai.action.HarvestCore.countInventoryItems(
                bot, Set.of(Items.OBSIDIAN));
        if (goal instanceof Goal.HaveItem haveItem && haveItem.item() == Items.OBSIDIAN) {
            return Math.max(0, haveItem.count() - carried);
        }
        if (goal instanceof Goal.Stockpile stockpile && stockpile.item() == Items.OBSIDIAN) {
            return Math.max(0, stockpile.count() - evaluation.matched() - carried);
        }
        return -1;
    }

    /**
     * Keeps the original transaction target while allowing prerequisite repair ahead of it.
     * Returns the index the resume step was inserted at (0 unless a missing-resource failure
     * kept the fresh plan's supply prefix physically ahead of the resumed transaction).
     */
    static int reconcileObsidianSteps(List<GoalStep> steps,
                                      int restoredTarget,
                                      boolean resumeFirst,
                                      boolean resupplyBeforeResume) {
        GoalStep restored = GoalStep.makeObsidian(restoredTarget);
        if (resumeFirst) {
            // resume-first contract: an open transaction (water source/pickup/block-break) is a physical obligation, and by default is placed ahead of any new procurement.
            // Exception (F5): when the failure reason is itself a "missing resource" type (need_better_tool / bucket-lost /
            // missing-water), putting resume at the top unchanged would just make the resumed task fail again for the same reason on its very first tick, and three zero-progress
            // replans would kill the whole mission. In that case, keep the resupply prefix that precedes MAKE_OBSIDIAN in the fresh plan and let
            // it physically execute first; for every other failure reason (physical continuation) keep today's resume-first ordering.
            int firstMake = -1;
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                    firstMake = index;
                    break;
                }
            }
            // firstMake is the FIRST MAKE_OBSIDIAN, so every removed step sits at or after it and
            // the prefix indices stay valid across removeIf.
            int insertAt = resupplyBeforeResume && firstMake > 0 ? firstMake : 0;
            steps.removeIf(step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN);
            steps.add(insertAt, restored);
            return insertAt;
        }
        int first = -1;
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).kind() != GoalStep.Kind.MAKE_OBSIDIAN) {
                continue;
            }
            if (first < 0) {
                first = index;
                steps.set(index, restored);
            } else {
                steps.remove(index--);
            }
        }
        if (first < 0) {
            steps.add(restored);
        }
        return Math.max(0, first);
    }

    /**
     * F5: resuming an open obsidian transaction under these "missing resource" failure prefixes is bound to fail again on the very first tick (a tool/bucket/water source won't
     * appear out of nowhere), so the fresh plan's resupply prefix must be allowed to physically execute first. The scope is pinned precisely to these three prefixes; every other reason
     * keeps resume-first (the correct order for physical continuation).
     */
    static boolean isObsidianMissingResourceFailure(String reason) {
        return reason != null && (reason.startsWith("need_better_tool:")
                || reason.startsWith("create_obsidian_bucket_lost_after_pour")
                || reason.endsWith("_missing_water"));
    }

    private GoalEvaluation evaluate(AIPlayerEntity bot, ActivePlan plan) {
        GoalSnapshot snapshot = GoalSnapshotCollector.collect(bot, plan.goal, snapshotContext(plan));
        plan.lastStructure = snapshot.structure().orElse(null);
        return GoalPredicates.evaluate(plan.goal, snapshot, plan.completedDeliveries);
    }

    // Package-private: also called by MissionRecoveryScheduler's rare-resource replanning.
    static GoalSnapshotCollector.Context snapshotContext(ActivePlan plan) {
        return new GoalSnapshotCollector.Context(
                plan.origin,
                plan.boundContainers,
                plan.blueprint,
                plan.buildAnchor,
                plan.buildPlaced,
                plan.buildSkipped,
                plan.completedDeliveries);
    }

    /**
     * Commits the logical receipt only after GiveItemTask reached its exact completed state.  The
     * receipt is intentionally scoped to an allocation present in this declared goal; a damaged
     * or mismatched plan must not turn an arbitrary inventory loss into a satisfied delivery.
     */
    private static boolean commitDeliveryReceipt(AIPlayerEntity bot,
                                                 ActivePlan plan,
                                                 GoalStep step) {
        if (plan == null || plan.current != step) {
            return false;
        }
        try {
            Goal.Allocation allocation = declaredDelivery(plan, step);
            if (!hasFreshFulfillPostDeliveryQuota(bot, plan, allocation)) {
                return false;
            }
            if (!plan.completedDeliveries.add(allocation)) {
                return false;
            }
            // Capture the receipt while GiveItemTask is still running. A persistence snapshot
            // taken after this exact verified inventory debit will therefore replan without a
            // duplicate handoff even if the server stops before the executor's next tick.
            try {
                markDirty(bot);
                return true;
            } catch (RuntimeException persistenceFailure) {
                plan.completedDeliveries.remove(allocation);
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasCommittedDelivery(ActivePlan plan, GoalStep step) {
        try {
            return plan != null && plan.completedDeliveries.contains(
                    declaredDelivery(plan, step));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Goal.Allocation declaredDelivery(ActivePlan plan, GoalStep step) {
        if (plan == null || step == null || step.kind() != GoalStep.Kind.GIVE_ITEM
                || !(plan.goal instanceof Goal.Fulfill fulfill)) {
            throw new IllegalArgumentException("invalid_fulfillment_delivery_step");
        }
        Goal.Allocation allocation = new Goal.Allocation(
                step.item(), step.count(), step.giveRecipient());
        if (!fulfill.deliveries().contains(allocation)) {
            throw new IllegalArgumentException("undeclared_fulfillment_delivery");
        }
        return allocation;
    }

    private static void captureTaskEvidence(AIPlayerEntity bot, ActivePlan plan) {
        if (plan.currentTask instanceof CheckpointableTask checkpointable && plan.current != null) {
            Map<String, String> candidate = checkpointable.checkpoint();
            boolean consumedTerminalReceipt = plan.current.kind()
                    == GoalStep.Kind.MINING_SERVICE
                    && MiningServiceTask.inspectCheckpoint(candidate)
                    .filter(metadata -> !metadata.terminalFailure().isBlank())
                    .map(metadata -> persistedServiceAuthority(metadata)
                            .map(authority -> java.util.Optional.ofNullable(
                                    plan.settledServiceTombstones.get(authority.key()))
                                    .filter(settled -> settled.failureReason().equals(
                                            metadata.terminalFailure()))
                                    .isPresent())
                            .orElse(false))
                    .orElse(false);
            // Empty means the task cannot attest a trustworthy successor state (for example a
            // constructor rejected a corrupt restore). Never erase the last durable checkpoint.
            if (!candidate.isEmpty() && !consumedTerminalReceipt) {
                plan.taskCheckpoint.clear();
                plan.taskCheckpoint.putAll(candidate);
                plan.taskCheckpointKind = plan.current.kind();
                if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                        && !plan.auxiliaryMiningContinuationFingerprint.isBlank()
                        && ordinaryServiceParentMatches(candidate,
                        plan.auxiliaryMiningCheckpoint, false)) {
                    // The current service checkpoint now durably binds the closed auxiliary cursor;
                    // the ordinary service identity replaces the transient continuation marker.
                    plan.auxiliaryMiningContinuationFingerprint = "";
                }
                if (plan.current.kind() == GoalStep.Kind.MINE_ORE) {
                    String previousFingerprint = plan.miningCheckpoint.get("ore_fingerprint");
                    String currentFingerprint = plan.taskCheckpoint.get("ore_fingerprint");
                    if (plan.capacityParentNamespace != null
                            && plan.currentCapacityParentRetry) {
                        Map<String, String> parent = plan.capacityParentNamespace
                                == CapacityParentNamespace.AUXILIARY
                                ? plan.auxiliaryMiningCheckpoint : plan.miningCheckpoint;
                        parent.clear();
                        parent.putAll(plan.taskCheckpoint);
                    } else if (plan.capacityParentNamespace != null) {
                        // While a capacity debit is open, every non-owner OreDig is a repair
                        // prefix. Its task.* checkpoint is durable independently, but it may
                        // neither replace the parent branch nor an unrelated protected branch,
                        // even when it happens to use the same ore family.
                    } else if (shouldReplaceMiningCheckpoint(
                            previousFingerprint,
                            currentFingerprint,
                            miningStepFeedsGoal(plan.goal, plan.current.ores()))) {
                        plan.miningCheckpoint.clear();
                        plan.miningCheckpoint.putAll(plan.taskCheckpoint);
                    }
                } else if (plan.current.kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                    plan.obsidianCheckpoint.clear();
                    plan.obsidianCheckpoint.putAll(plan.taskCheckpoint);
                }
            }
        }
        if (plan.currentTask instanceof BuildTask build) {
            plan.blueprint = build.blueprint();
            plan.buildAnchor = build.anchor();
            plan.buildPlaced = build.placedBlocks();
            plan.buildSkipped = build.skippedBlocks();
        } else if (plan.currentTask instanceof StockpileTask stockpile) {
            plan.boundContainers.addAll(stockpile.depositedContainers());
        } else if (plan.currentTask instanceof PlaceStationsTask stations && !stations.placedPositions().isEmpty()) {
            plan.origin = stations.placedPositions().iterator().next();
        }
    }

    /** Settles a completed service without discarding a still-planned ordinary branch cursor. */
    private static void retireClosedAuxiliaryMiningCheckpoint(ActivePlan plan) {
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINING_SERVICE
                || plan.auxiliaryMiningCheckpoint.isEmpty()) {
            return;
        }
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.current.ores());
        OreDigTask.inspectCheckpoint(plan.auxiliaryMiningCheckpoint, 0)
                .filter(metadata -> !metadata.batchOpen()
                        && expectedFingerprint.equals(
                        OreDigTask.oreFingerprint(metadata.ores())))
                .ifPresent(metadata -> {
                    if (hasFreshMiningSuccessor(
                            new ArrayList<>(plan.steps), metadata.ores())) {
                        plan.auxiliaryMiningContinuationFingerprint = expectedFingerprint;
                    } else {
                        plan.auxiliaryMiningCheckpoint.clear();
                        plan.auxiliaryMiningContinuationFingerprint = "";
                    }
                });
    }

    /**
     * A task-level cursor attests only the currently executing step.  Once that step commits, a
     * stale RETURN/DONE cursor must not survive into a later craft/smelt step and be replayed after
     * restart. Dedicated branch/transaction namespaces are intentionally retained separately.
     */
    private static void clearCompletedTaskCheckpoint(ActivePlan plan) {
        if (plan.current != null && plan.taskCheckpointKind == plan.current.kind()) {
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
        }
    }

    /**
     * A successful physical water expedition establishes a new work region. A closed ordinary
     * cursor has no pickup/break debt and may be retired; an open transaction or a long rare-ore
     * mission keeps its exact face and remains fail-closed.
     */
    private static void retireRelocatedOrdinaryMiningCheckpoint(
            AIPlayerEntity bot, ActivePlan plan) {
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.miningCheckpoint);
        if (!shouldRetireRelocatedOrdinaryMiningCheckpoint(
                metadata, hasOreDigPhysicalLedger(plan.miningCheckpoint))) {
            return;
        }
        BlockPos retiredFace = metadata.orElseThrow().cursor().face();
        plan.miningCheckpoint.clear();
        BotLog.task(bot, "goal_mining_cursor_retired_after_water_relocation",
                "face", retiredFace.toShortString(),
                "ore_fingerprint", OreDigTask.oreFingerprint(
                        metadata.orElseThrow().ores()));
    }

    static boolean shouldRetireRelocatedOrdinaryMiningCheckpoint(
            Optional<OreDigTask.RestoreMetadata> metadata,
            boolean physicalLedgerOpen) {
        return metadata != null
                && metadata.isPresent()
                && !metadata.orElseThrow().batchOpen()
                && metadata.orElseThrow().rareMissionTarget() == 0
                && !physicalLedgerOpen;
    }

    // Package-private: also called by MissionRecoveryScheduler's rare-resource scheduling helpers.
    static boolean miningStepFeedsGoal(Goal goal, Set<Block> ores) {
        Set<Item> drops = io.github.zoyluo.minecraftai.action.HarvestCore.expectedDropsFor(ores);
        if (goal instanceof Goal.HaveItem haveItem) {
            return drops.contains(haveItem.item());
        }
        if (goal instanceof Goal.Stockpile stockpile) {
            return drops.contains(stockpile.item());
        }
        if (goal instanceof Goal.MineOre mineOre) {
            Set<Block> requested = OreScan.expandOreFamilies(mineOre.ores());
            return ores.stream().anyMatch(requested::contains);
        }
        if (goal instanceof Goal.Armor) {
            return drops.contains(Items.RAW_IRON);
        }
        if (goal instanceof Goal.HavePickaxeTier pickaxe) {
            return pickaxe.tier() >= io.github.zoyluo.minecraftai.mining.ToolTier.DIAMOND
                    ? drops.contains(Items.DIAMOND)
                    : pickaxe.tier() >= io.github.zoyluo.minecraftai.mining.ToolTier.IRON
                    && drops.contains(Items.RAW_IRON);
        }
        return false;
    }

    /** Pure policy boundary so prerequisite coal/iron cannot overwrite a valuable rare-ore branch. */
    static boolean shouldReplaceMiningCheckpoint(String previousFingerprint,
                                                 String currentFingerprint,
                                                 boolean feedsGoal) {
        if (currentFingerprint == null || currentFingerprint.isBlank()) {
            return false;
        }
        if (previousFingerprint == null || previousFingerprint.isBlank()
                || previousFingerprint.equals(currentFingerprint)
                || feedsGoal) {
            return true;
        }
        // Keep an established rare branch across ordinary prerequisite work, but otherwise the
        // latest physical OreDig face is authoritative (coal -> iron included).
        return !isRareOreFingerprint(previousFingerprint)
                || isRareOreFingerprint(currentFingerprint);
    }

    private static boolean isRareOreFingerprint(String fingerprint) {
        return fingerprint != null && (fingerprint.contains("diamond_ore")
                || fingerprint.contains("emerald_ore")
                || fingerprint.contains("ancient_debris"));
    }

    private static GoalSnapshotCollector.Context initialContext(AIPlayerEntity bot, Goal goal) {
        if (goal instanceof Goal.Stockpile) {
            net.minecraft.core.BlockPos base = io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE
                    .of(bot.getUUID())
                    .placeIn(bot.level(), "base")
                    .orElse(bot.blockPosition());
            return GoalSnapshotCollector.Context.at(base);
        }
        return GoalSnapshotCollector.Context.at(bot.blockPosition());
    }

    private void finishActive(AIPlayerEntity bot,
                              ActivePlan plan,
                              GoalEvaluation evaluation,
                              String reason,
                              boolean cancelled,
                              boolean advanceQueue) {
        finishActive(bot, plan, evaluation, reason, cancelled, advanceQueue, null);
    }

    private void finishActive(AIPlayerEntity bot,
                              ActivePlan plan,
                              GoalEvaluation evaluation,
                              String reason,
                              boolean cancelled,
                              boolean advanceQueue,
                              GoalResult.Status forcedStatus) {
        if (!activePlans.remove(bot.getUUID(), plan)) {
            return;
        }
        GoalResult.Status status = forcedStatus == null
                ? GoalResult.classify(evaluation, cancelled) : forcedStatus;
        GoalResult result = new GoalResult(
                resultSequence.incrementAndGet(),
                plan.missionId,
                plan.goal,
                status,
                evaluation,
                reason,
                plan.startedTick,
                bot.level().getServer().getTickCount(),
                plan.skippedSteps,
                plan.lastStructure);
        publishResult(bot, result);
        userGoal.remove(bot.getUUID());
        if (status == GoalResult.Status.FAILED || status == GoalResult.Status.PARTIAL) {
            lastGoalFailTick.put(bot.getUUID(), bot.level().getServer().getTickCount());
        }
        if (advanceQueue) {
            advanceQueue(bot);
        }
    }

    private void recordImmediateResult(AIPlayerEntity bot,
                                       UUID missionId,
                                       Goal goal,
                                       int startedTick,
                                       GoalEvaluation evaluation,
                                       GoalResult.Status status,
                                       String reason) {
        recordImmediateResult(
                bot, missionId, goal, startedTick, evaluation, status, reason, List.of());
    }

    private void recordImmediateResult(AIPlayerEntity bot,
                                       UUID missionId,
                                       Goal goal,
                                       int startedTick,
                                       GoalEvaluation evaluation,
                                       GoalResult.Status status,
                                       String reason,
                                       List<GoalResult.SkippedStep> skippedSteps) {
        if (pocketRestorePreflights.contains(bot.getUUID())) {
            return;
        }
        publishResult(bot, new GoalResult(
                resultSequence.incrementAndGet(), missionId, goal, status, evaluation, reason,
                startedTick, bot.level().getServer().getTickCount(),
                skippedSteps == null ? List.of() : List.copyOf(skippedSteps), null));
    }

    private void publishResult(AIPlayerEntity bot, GoalResult result) {
        lastResults.put(bot.getUUID(), result);
        BotLog.task(bot, "goal_result",
                "sequence", result.sequence(),
                "mission_id", result.missionId(),
                "goal", result.goal(),
                "status", result.status(),
                "matched", result.evaluation().matched(),
                "required", result.evaluation().required(),
                "reason", result.reason(),
                "skipped", result.skippedSteps().size(),
                "evidence", result.evaluation().evidence());
        if (result.status() == GoalResult.Status.COMPLETED) {
            io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.GOAL_DONE, bot.blockPosition(), goalLabel(result.goal()));
        } else if (result.status() != GoalResult.Status.CANCELLED) {
            io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.GOAL_FAILED, bot.blockPosition(), goalLabel(result.goal()));
        }
        String message = resultMessage(result);
        reportTerminal(bot, message, result.status());
        markDirty(bot);
    }

    // Package-private: also called by MissionRecoveryScheduler's write-ahead-capture dispatches.
    static void markDirty(AIPlayerEntity bot) {
        io.github.zoyluo.minecraftai.persist.BotPersistence.INSTANCE.markDirty(bot.level().getServer());
    }

    private static String resultMessage(GoalResult result) {
        if (result.goal() instanceof Goal.MineOre mine && mine.isTimedCollection()
                || result.goal() instanceof Goal.HarvestCrop crop && crop.isTimedCollection()) {
            String resource = timedCollectionResourceLabel(result.goal());
            if (result.status() == GoalResult.Status.COMPLETED) {
                return "I collected " + result.evaluation().matched() + " new " + resource
                        + " during the ten-minute collection window.";
            }
            if (GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE.equals(result.reason())) {
                return "I explored for ten minutes but found no " + resource + ".";
            }
        }
        return switch (result.status()) {
            case COMPLETED -> "Goal completed.";
            case PARTIAL -> "Goal partially completed (" + result.evaluation().matched() + "/"
                    + result.evaluation().required() + "): "
                    + String.join(",", result.evaluation().unmet());
            case FAILED -> "Goal failed final verification: "
                    + (result.evaluation().unmet().isEmpty()
                    ? result.reason() : String.join(",", result.evaluation().unmet()));
            case CANCELLED -> "Goal cancelled.";
        };
    }

    private static String timedCollectionResourceLabel(Goal goal) {
        if (goal instanceof Goal.HarvestCrop crop) {
            return itemLabel(crop.produce());
        }
        if (goal instanceof Goal.MineOre mine) {
            return io.github.zoyluo.minecraftai.action.HarvestCore.expectedDropsFor(mine.ores()).stream()
                    .map(GoalExecutor::itemLabel)
                    .sorted()
                    .findFirst()
                    .orElse("requested ore");
        }
        return "requested resource";
    }

    private static void reportTerminal(AIPlayerEntity bot, String text, GoalResult.Status status) {
        BotReporter.INSTANCE.onGoalResult(bot, status, text);
    }

    private static void report(AIPlayerEntity bot, String text) {
        BotReporter.INSTANCE.onGoalMessage(bot, text);
    }

    // Hard-stuck class of failure: retrying unchanged will just fail again (can't dig/stuck/timeout/can't reach).
    // An exhausted set of zero-motion observed probes is also hard: it produced no new position,
    // inventory, or terrain evidence, so another identical plan cannot discover anything new.
    // A completed exploration remains soft because it did change the bot's physical viewpoint.
    static boolean isHardFailure(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("no_progress")
                || reason.contains("dig_down_blocked")
                || reason.contains("stuck:")
                || reason.contains("timeout")
                || GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE.equals(reason)
                || reason.contains("no_reachable")
                || reason.startsWith("no_observed_resource_in_local_view")
                || reason.startsWith("no_observed_ore_in_local_view");
    }

    private static boolean isTimedCollectionStep(GoalStep step) {
        return step != null && (step.kind() == GoalStep.Kind.MINE_ORE_FOR_DURATION
                || step.kind() == GoalStep.Kind.FARM_FOR_DURATION);
    }

    /** The timed predicate stays open during execution; crossing its real deadline settles it. */
    private static GoalEvaluation completedTimedCollectionEvaluation(GoalEvaluation measured) {
        return new GoalEvaluation(GoalEvaluation.State.SATISFIED,
                measured.matched(), measured.required(), measured.evidence(), List.of());
    }

    /**
     * These GiveItemTask outcomes occur after a vanilla drop attempt has altered inventory, but
     * before a compound-mission receipt could prove the exact allocation. Replanning must not
     * turn that ambiguity into a duplicate handoff.
     */
    static boolean isUnreceiptedPhysicalDeliveryFailure(GoalStep step, String reason) {
        return step != null && step.kind() == GoalStep.Kind.GIVE_ITEM
                && ("give_item_count_mismatch".equals(reason)
                || "give_item_receipt_commit_failed".equals(reason));
    }

    // Package-private (not public): MissionRecoveryScheduler needs the type to accept a plan
    // parameter, but every field stays private -- only the narrow accessors below are exposed.
    static final class ActivePlan {
        private UUID missionId;
        private final int startedTick;
        private final Goal goal;
        private final ExecutionMode executionMode;
        private final GoalPredicate predicate;
        private net.minecraft.core.BlockPos origin;
        private final Set<net.minecraft.core.BlockPos> boundContainers = new HashSet<>();
        private final ArrayDeque<GoalStep> steps;
        private final java.util.List<String> stepLabels; // full step descriptions (steps gets polled empty as execution proceeds; this keeps the full list for the panel's task-chain display)
        private final List<GoalResult.SkippedStep> skippedSteps = new ArrayList<>();
        private final List<SkippedTargetReceipt> skippedTargetReceipts = new ArrayList<>();
        /** Committed GiveItemTask handoffs; persisted so recovery never re-drops an allocation. */
        private final Set<Goal.Allocation> completedDeliveries = new HashSet<>();
        private GoalStep current;
        private Task currentTask;
        private BlueprintSchema blueprint;
        private net.minecraft.core.BlockPos buildAnchor;
        private int buildPlaced;
        private int buildSkipped;
        private StructureReport lastStructure;
        private int totalSteps;
        /** The last completed safe boundary accepted by the player. */
        private int completedAtLastBatchCheckpoint;
        /** Stable per-mission policy, persisted with MissionSpec and checkpointed while paused. */
        private int batchStepLimit = DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT;
        /** True only after the executor has committed a whole step and asked the player to continue. */
        private boolean awaitingPlayerContinuation;
        /** Explicit human pause layered on an otherwise automatic adaptive strategy boundary. */
        private boolean strategyManuallyHeld;
        /** Durable dedupe for the bounded model allowance at this exact stage revision. */
        private boolean strategyDecisionExhausted;
        private int replanCount;       // Phase A: semantics = the count of consecutive no-progress replans (reset to zero once progress is made)
        private int postconditionReplans;
        private int lastEvaluationMatched;
        private String lastRepairFingerprint = "";
        // Phase A resilience: progress-aware budget (checkpoint/resume):
        private int completedSteps;    // cumulative completed step count (monotonically increasing)
        private int lifetimeReplans;   // lifetime replan count (never reset; for long rare-ore missions, extended by the original batch count)
        // The resource epoch of the current rare-ore batch (0 = first, 1 = intra-batch retry, >=2 = margin epoch): preserved across a replan,
        // and reset to zero only after that batch is closed and committed.
        private int rareResourceRetriesUsed;
        // Mission-level margin epoch draw ledger (F2): monotonically increases across batches, not reset when a batch commits,
        // and only starts back at 0 for a brand-new mission; the cap = MiningBudget.rareMissionEpochMargin(batchCount).
        private int rareEpochMarginUsed;
        private int snapSteps;         // snapshot of completedSteps at the last replan
        private int snapTargetCount;   // inventory count of the target product at the last replan
        private int snapX, snapY, snapZ; // bot coordinates at the last replan (lateral movement/descent = progress criterion)
        private String snapDimension = "";
        private int snapHuntRawMeat;
        private int snapHuntVisitedSectors;
        /** Mission-scoped surface search facts shared by every HUNT task and replan. */
        private final HuntSearchCursor huntSearchCursor;
        /** Synthetic restore step that settles one durable PICKUP before any live replan. */
        private boolean huntPickupSettlement;
        private GoalStep.Kind taskCheckpointKind;
        private final Map<String, String> taskCheckpoint = new java.util.LinkedHashMap<>();
        /** Latest branch-mining cursor; survives intervening MINING_SERVICE checkpoints. */
        private final Map<String, String> miningCheckpoint = new java.util.LinkedHashMap<>();
        /** Ordinary prerequisite cursor temporarily suspended above a protected rare branch. */
        private final Map<String, String> auxiliaryMiningCheckpoint =
                new java.util.LinkedHashMap<>();
        /** Explicit owner of a dynamically inserted capacity service and its exact retry debit. */
        private CapacityParentNamespace capacityParentNamespace;
        /** Target-item delivery count at the most recently scheduled capacity service. */
        private int capacityParentDelivered = -1;
        /** Exact OreDig work face at the most recently scheduled capacity service. */
        private BlockPos capacityParentFace;
        /** Bounded dynamic services already consumed by this open ordinary OreDig batch. */
        private int capacityParentServicesUsed;
        /** Runtime owner bit: only the exact checkpoint selected from the capacity parent may
         * advance or settle that parent; same-family repair OreDig tasks remain independent. */
        private boolean currentCapacityParentRetry;
        /** Closed ordinary cursor retained across a successful replan until that family resumes. */
        private String auxiliaryMiningContinuationFingerprint = "";
        /** Original-target obsidian transaction; survives prerequisite water/tool repair tasks. */
        private final Map<String, String> obsidianCheckpoint = new java.util.LinkedHashMap<>();
        /** Bounded, non-evicting settled pocket facts retained for the life of this Mission. */
        private final java.util.LinkedHashMap<String, SettledServiceTombstone>
                settledServiceTombstones = new java.util.LinkedHashMap<>();

        private ActivePlan(UUID missionId,
                           int startedTick,
                           Goal goal,
                           GoalPredicate predicate,
                           GoalSnapshotCollector.Context context,
                           ExecutionMode executionMode,
                           ArrayDeque<GoalStep> steps,
                           int totalSteps,
                           java.util.List<String> stepLabels,
                           HuntSearchCursor huntSearchCursor,
                           List<SkippedTargetReceipt> skippedTargetReceipts) {
            this.missionId = missionId;
            this.startedTick = startedTick;
            this.goal = goal;
            this.executionMode = executionMode == null ? ExecutionMode.STANDARD : executionMode;
            this.batchStepLimit = this.executionMode == ExecutionMode.ADAPTIVE
                    ? ADAPTIVE_STRATEGY_STEP_LIMIT : DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT;
            this.predicate = predicate;
            this.origin = context.origin();
            this.boundContainers.addAll(context.boundContainers());
            this.blueprint = context.blueprint();
            this.buildAnchor = context.buildAnchor();
            this.buildPlaced = context.buildPlaced();
            this.buildSkipped = context.buildSkipped();
            this.completedDeliveries.addAll(context.completedDeliveries());
            this.steps = steps;
            this.totalSteps = totalSteps;
            this.stepLabels = new ArrayList<>(stepLabels);
            this.huntSearchCursor = java.util.Objects.requireNonNull(
                    huntSearchCursor, "huntSearchCursor");
            this.skippedTargetReceipts.addAll(
                    java.util.Objects.requireNonNull(
                            skippedTargetReceipts, "skippedTargetReceipts"));
            this.skippedTargetReceipts.stream()
                    .map(SkippedTargetReceipt::asSkippedStep)
                    .forEach(this.skippedSteps::add);
        }

        // ---------------------------------------------------------------------------------
        // Package-private accessors: fields stay private; MissionRecoveryScheduler (the only
        // consumer outside GoalExecutor) reaches this state only through these narrow seams.
        // Every mutable-collection accessor returns the live field -- it is mutated in place
        // (clear/putAll/add) exactly as GoalExecutor's own code already does -- never a copy.
        // ---------------------------------------------------------------------------------

        Goal getGoal() {
            return goal;
        }

        UUID getMissionId() {
            return missionId;
        }

        GoalStep getCurrent() {
            return current;
        }

        void setCurrent(GoalStep current) {
            this.current = current;
        }

        Task getCurrentTask() {
            return currentTask;
        }

        void setCurrentTask(Task currentTask) {
            this.currentTask = currentTask;
        }

        GoalStep.Kind getTaskCheckpointKind() {
            return taskCheckpointKind;
        }

        void setTaskCheckpointKind(GoalStep.Kind taskCheckpointKind) {
            this.taskCheckpointKind = taskCheckpointKind;
        }

        Map<String, String> getTaskCheckpoint() {
            return taskCheckpoint;
        }

        Map<String, String> getMiningCheckpoint() {
            return miningCheckpoint;
        }

        Map<String, String> getAuxiliaryMiningCheckpoint() {
            return auxiliaryMiningCheckpoint;
        }

        void setAuxiliaryMiningContinuationFingerprint(String auxiliaryMiningContinuationFingerprint) {
            this.auxiliaryMiningContinuationFingerprint = auxiliaryMiningContinuationFingerprint;
        }

        int getRareResourceRetriesUsed() {
            return rareResourceRetriesUsed;
        }

        void setRareResourceRetriesUsed(int rareResourceRetriesUsed) {
            this.rareResourceRetriesUsed = rareResourceRetriesUsed;
        }

        int getRareEpochMarginUsed() {
            return rareEpochMarginUsed;
        }

        void setRareEpochMarginUsed(int rareEpochMarginUsed) {
            this.rareEpochMarginUsed = rareEpochMarginUsed;
        }

        ArrayDeque<GoalStep> getSteps() {
            return steps;
        }

        java.util.List<String> getStepLabels() {
            return stepLabels;
        }

        List<SkippedTargetReceipt> getSkippedTargetReceipts() {
            return skippedTargetReceipts;
        }

        int getTotalSteps() {
            return totalSteps;
        }

        void setTotalSteps(int totalSteps) {
            this.totalSteps = totalSteps;
        }

        CapacityParentNamespace getCapacityParentNamespace() {
            return capacityParentNamespace;
        }

        void setCapacityParentNamespace(CapacityParentNamespace capacityParentNamespace) {
            this.capacityParentNamespace = capacityParentNamespace;
        }

        int getCapacityParentDelivered() {
            return capacityParentDelivered;
        }

        void setCapacityParentDelivered(int capacityParentDelivered) {
            this.capacityParentDelivered = capacityParentDelivered;
        }

        BlockPos getCapacityParentFace() {
            return capacityParentFace;
        }

        void setCapacityParentFace(BlockPos capacityParentFace) {
            this.capacityParentFace = capacityParentFace;
        }

        int getCapacityParentServicesUsed() {
            return capacityParentServicesUsed;
        }

        void setCapacityParentServicesUsed(int capacityParentServicesUsed) {
            this.capacityParentServicesUsed = capacityParentServicesUsed;
        }

        boolean isCurrentCapacityParentRetry() {
            return currentCapacityParentRetry;
        }

        java.util.LinkedHashMap<String, SettledServiceTombstone> getSettledServiceTombstones() {
            return settledServiceTombstones;
        }

        private Map<String, String> takeTaskCheckpoint(GoalStep.Kind kind) {
            if (taskCheckpointKind != kind || taskCheckpoint.isEmpty()) {
                return Map.of();
            }
            Map<String, String> restored = Map.copyOf(taskCheckpoint);
            taskCheckpoint.clear();
            taskCheckpointKind = null;
            return restored;
        }

        /**
         * Water search restore is transactional: keep the prior durable cursor until the running
         * task publishes a trusted non-empty successor or completes. An invalid restore returns an
         * empty checkpoint, so consuming here would silently turn corruption/exhaustion into a
         * fresh expedition on the next plan.
         */
        private Map<String, String> peekTaskCheckpoint(GoalStep.Kind kind) {
            if (taskCheckpointKind != kind || taskCheckpoint.isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(taskCheckpoint);
        }

        private Map<String, String> checkpointForMineOre(Set<Block> ores) {
            String expected = OreDigTask.oreFingerprint(ores);
            Map<String, String> current = takeTaskCheckpoint(GoalStep.Kind.MINE_ORE);
            if (expected.equals(current.get("ore_fingerprint"))) {
                return current;
            }
            if (expected.equals(auxiliaryMiningCheckpoint.get("ore_fingerprint"))) {
                Map<String, String> restored = Map.copyOf(auxiliaryMiningCheckpoint);
                if (expected.equals(auxiliaryMiningContinuationFingerprint)
                        && capacityParentNamespace == null) {
                    // Promote the suspended closed cursor into the newly assigned task atomically.
                    // Keeping both namespaces would make a restart ambiguous once MINE_ORE starts.
                    auxiliaryMiningCheckpoint.clear();
                    auxiliaryMiningContinuationFingerprint = "";
                }
                return restored;
            }
            if (expected.equals(miningCheckpoint.get("ore_fingerprint"))) {
                return Map.copyOf(miningCheckpoint);
            }
            return Map.of();
        }

        private boolean isCapacityParentRetry(GoalStep step,
                                              Map<String, String> selectedCheckpoint) {
            if (step == null || step.kind() != GoalStep.Kind.MINE_ORE
                    || capacityParentNamespace == null
                    || selectedCheckpoint == null || selectedCheckpoint.isEmpty()) {
                return false;
            }
            Map<String, String> parent = capacityParentNamespace
                    == CapacityParentNamespace.AUXILIARY
                    ? auxiliaryMiningCheckpoint : miningCheckpoint;
            Optional<OreDigTask.RestoreMetadata> metadata =
                    OreDigTask.inspectCheckpoint(parent, 0);
            return selectedCheckpoint.equals(parent)
                    && metadata.filter(value -> value.batchOpen()
                            && value.rareMissionTarget() == 0
                            && value.acceptsStepTarget(step.count())
                            && OreDigTask.oreFingerprint(value.ores()).equals(
                            OreDigTask.oreFingerprint(step.ores())))
                    .isPresent();
        }

        /** Read-only cursor handoff: service geometry may bind to the branch face, but cannot
         * consume, clear or replace the parent OreDig checkpoint needed by the next batch. */
        private MiningCursor miningCursorForService(Set<Block> ores) {
            String expected = OreDigTask.oreFingerprint(ores);
            int rareMissionTarget = rareMissionTargetForMiningStep(goal, ores);
            Optional<OreDigTask.RestoreMetadata> auxiliary = OreDigTask.inspectCheckpoint(
                            Map.copyOf(auxiliaryMiningCheckpoint), 0)
                    .filter(metadata -> expected.equals(
                            OreDigTask.oreFingerprint(metadata.ores())));
            if (auxiliary.isPresent()) {
                return auxiliary.orElseThrow().cursor();
            }
            return OreDigTask.inspectCheckpoint(
                            Map.copyOf(miningCheckpoint), rareMissionTarget)
                    .filter(metadata -> expected.equals(
                            OreDigTask.oreFingerprint(metadata.ores())))
                    .map(OreDigTask.RestoreMetadata::cursor)
                    .orElse(null);
        }

        private Map<String, String> checkpointForObsidian() {
            if (taskCheckpointKind == GoalStep.Kind.MAKE_OBSIDIAN
                    && !taskCheckpoint.isEmpty()) {
                return Map.copyOf(taskCheckpoint);
            }
            return Map.copyOf(obsidianCheckpoint);
        }
    }

    private record MiningServiceInvocation(ServicePolicy policy,
                                           int target,
                                           int boundary) {
    }

    // Package-private: also called by MissionRecoveryScheduler's settled-service replay guard.
    static Optional<SettledServiceAuthority> persistedServiceAuthority(
            MiningServiceTask.RestoreMetadata metadata) {
        Identifier dimension = metadata == null ? null
                : Identifier.tryParse(metadata.serviceDimension());
        if (metadata == null || metadata.miningCursor() == null
                || metadata.workFace() == null
                || !metadata.workFace().equals(metadata.miningCursor().face())
                || dimension == null
                || !dimension.toString().equals(metadata.serviceDimension())) {
            return Optional.empty();
        }
        return MiningServiceTask.DisposalGeometry.fromCursor(metadata.miningCursor())
                .map(geometry -> new SettledServiceAuthority(
                        SettledServiceDescriptor.fromMetadata(metadata),
                        metadata.serviceDimension(),
                        geometry));
    }

    // Package-private: also constructed by GoalCheckpointCodec's decodeReplanSnapshot.
    record ReplanSnapshot(int steps,
                                  int targetCount,
                                  int x,
                                  int y,
                                  int z,
                                  String dimension,
                                  int huntRawMeat,
                                  int huntVisitedSectors) {
    }

    record SkippedTargetReceipt(GoalStep step, String reason) {
        SkippedTargetReceipt {
            java.util.Objects.requireNonNull(step, "step");
            reason = reason == null ? "" : reason;
            if (reason.getBytes(StandardCharsets.UTF_8).length
                    > MAX_SKIPPED_TARGET_TEXT_BYTES
                    || step.tag() != null && step.tag().getBytes(StandardCharsets.UTF_8).length
                    > MAX_SKIPPED_TARGET_TEXT_BYTES) {
                throw new IllegalArgumentException("skipped target receipt text too large");
            }
        }

        GoalResult.SkippedStep asSkippedStep() {
            return new GoalResult.SkippedStep(step.describe(), reason);
        }
    }

    record PostconditionRepairCheckpoint(boolean persisted,
                                         int replans,
                                         int lastMatched,
                                         String fingerprint) {
        // Package-private: also read by GoalCheckpointCodec's decodePostconditionRepairCheckpoint.
        static PostconditionRepairCheckpoint legacy() {
            return new PostconditionRepairCheckpoint(false, 0, 0, "");
        }
    }

    /** Persisted only while an automatic, safe continuation confirmation is pending. */
    record GoalBatchCheckpoint(boolean persisted,
                               int completedAtCheckpoint,
                               int stepLimit,
                               boolean strategyManuallyHeld,
                               boolean strategyDecisionExhausted) {
        GoalBatchCheckpoint(boolean persisted, int completedAtCheckpoint, int stepLimit) {
            this(persisted, completedAtCheckpoint, stepLimit, false, false);
        }

        GoalBatchCheckpoint(boolean persisted, int completedAtCheckpoint, int stepLimit,
                            boolean strategyManuallyHeld) {
            this(persisted, completedAtCheckpoint, stepLimit, strategyManuallyHeld, false);
        }
        // Package-private: also read by GoalCheckpointCodec's decodeBatchCheckpoint.
        static GoalBatchCheckpoint legacy() {
            return new GoalBatchCheckpoint(false, 0,
                    DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT, false, false);
        }
    }

    /** Keeps a queued goal's execution policy durable without changing its semantic goal value. */
    private static final class QueuedGoal {
        private final Goal goal;
        private final ExecutionMode executionMode;

        private QueuedGoal(Goal goal, ExecutionMode executionMode) {
            this.goal = java.util.Objects.requireNonNull(goal, "goal");
            this.executionMode = executionMode == null ? ExecutionMode.STANDARD : executionMode;
        }

        private Goal goal() {
            return goal;
        }

        private MissionSpec spec() {
            return MissionSpec.fromGoal(goal, executionMode);
        }

        @Override
        public boolean equals(Object other) {
            // Several long-lived validation paths remove a just-submitted Goal from this queue.
            // Preserve their semantic "same goal" behavior while retaining the policy payload.
            return other instanceof QueuedGoal queued
                    ? goal.equals(queued.goal) && executionMode == queued.executionMode
                    : other instanceof Goal otherGoal && goal.equals(otherGoal);
        }

        @Override
        public int hashCode() {
            return goal.hashCode();
        }
    }

    private record RestoreSeed(UUID missionId,
                                GoalSnapshotCollector.Context context,
                               int completedSteps,
                               int lifetimeReplans,
                               int rareResourceRetriesUsed,
                               int rareEpochMarginUsed,
                               int replanCount,
                               Optional<ReplanSnapshot> replanSnapshot,
                               HuntSearchCursor huntSearchCursor,
                                List<SkippedTargetReceipt> skippedTargetReceipts,
                                String validationFailure,
                                PostconditionRepairCheckpoint postconditionRepair,
                                GoalBatchCheckpoint batchCheckpoint,
                                int startedTick,
                               GoalStep.Kind taskCheckpointKind,
                               Map<String, String> taskCheckpoint,
                               Map<String, String> miningCheckpoint,
                               Map<String, String> auxiliaryMiningCheckpoint,
                               String capacityParentNamespace,
                               int capacityParentDelivered,
                               String capacityParentFace,
                               int capacityParentServicesUsed,
                               String auxiliaryMiningContinuationFingerprint,
                               Map<String, String> obsidianCheckpoint,
                               Map<String, String> settledServiceTombstone) {
    }

    // Package-private (not public): MissionRecoveryScheduler's capacity-handoff scheduling
    // reads and assigns this enum through ActivePlan's package-private accessors.
    enum CapacityParentNamespace {
        MINING("mining"),
        AUXILIARY("auxiliary");

        private final String persistedName;

        CapacityParentNamespace(String persistedName) {
            this.persistedName = persistedName;
        }

        private static Optional<CapacityParentNamespace> decode(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return java.util.Arrays.stream(values())
                    .filter(candidate -> candidate.persistedName.equals(value))
                    .findFirst();
        }
    }
}
