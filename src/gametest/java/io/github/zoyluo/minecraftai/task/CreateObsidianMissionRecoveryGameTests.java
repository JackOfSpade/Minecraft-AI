package io.github.zoyluo.minecraftai.task;

import com.google.gson.Gson;
import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalPlanner;
import io.github.zoyluo.minecraftai.goal.GoalStep;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.persist.MissionRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Mission-level restart boundaries for the 32-block obsidian expedition. */
public final class CreateObsidianMissionRecoveryGameTests {
    private static final int TARGET = 32;
    private static final int TARGET_BUDGET = 76800;

    @GameTest(maxTicks = 20)
    public void fifteenOfThirtyTwoRestoresMakeObsidianWithoutDroppingCheckpoint(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMission15GT", 15, true);
        Map<String, String> taskCheckpoint = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SCAN, 15, null);

        restore(context, fixture, taskCheckpoint);

        assertRunningMakeObsidian(context, fixture, taskCheckpoint);
        assertTaskCheckpoint(context, fixture, "inventory_baseline", "0");
        assertTaskCheckpoint(context, fixture, "collected", "15");
        assertTaskCheckpoint(context, fixture, "target_count", "32");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void placedWaterDebtRestoresRecoveryBeforeAcquireWater(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMissionWaterGT", 15, false);
        BlockPos waterSource = fixture.start().east(2);
        fixture.bot().level().setBlock(
                waterSource, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        Map<String, String> taskCheckpoint = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.RECOVER_WATER, 15, waterSource);

        restore(context, fixture, taskCheckpoint);

        assertRunningMakeObsidian(context, fixture, taskCheckpoint);
        require(context, fixture.bot().level().getFluidState(waterSource).is(Fluids.WATER)
                        && fixture.bot().level().getFluidState(waterSource).isSource(),
                "fixture water_source is not live at restore");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1
                        && InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 0,
                "fixture must represent one placed bucket: empty=1 water=0");
        assertTaskCheckpoint(context, fixture, "phase", "RECOVER_WATER");
        assertTaskCheckpoint(context, fixture, "water_source", encode(waterSource));
        assertTaskCheckpoint(context, fixture, "water_bucket_baseline", "1");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void thirtyOneOfThirtyTwoDoesNotCompleteOrReplaceCheckpoint(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMission31GT", 31, true);
        Map<String, String> taskCheckpoint = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SCAN, 31, null);

        restore(context, fixture, taskCheckpoint);

        assertRunningMakeObsidian(context, fixture, taskCheckpoint);
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot()).isEmpty(),
                "31/32 restore published a false terminal result");
        require(context, GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "31/32 restore discarded the active mission");
        assertTaskCheckpoint(context, fixture, "inventory_baseline", "0");
        assertTaskCheckpoint(context, fixture, "collected", "31");
        assertTaskCheckpoint(context, fixture, "target_count", "32");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 160)
    public void eightBlockBoundaryRunsObsidianPolicyThenResumesOriginalThirtyTwo(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianBoundary8GT", 8, true);
        Map<String, String> pending = pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8);

        restore(context, fixture, pending);
        AtomicBoolean serviceSeen = new AtomicBoolean();

        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
            if (active instanceof MiningServiceTask) {
                serviceSeen.set(true);
                MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
                Map<String, String> checkpoint = captured.active() == null
                        ? Map.of() : captured.active().checkpoint();
                require(context, GoalStep.Kind.MINING_SERVICE.name().equals(
                                checkpoint.get("task_kind"))
                                && "OBSIDIAN_8".equals(
                                checkpoint.get("task.service_profile")),
                        "boundary service lost OBSIDIAN_8 policy: " + checkpoint);
                require(context, "8".equals(
                                checkpoint.get("obsidian.pending_service_boundary"))
                                && "32".equals(checkpoint.get("obsidian.target_count"))
                                && "0".equals(checkpoint.get("obsidian.inventory_baseline")),
                        "dual namespace lost the original obsidian transaction: " + checkpoint);
                return;
            }
            if (!serviceSeen.get() || !(active instanceof CreateObsidianTask)) {
                return;
            }
            MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
            Map<String, String> checkpoint = captured.active() == null
                    ? Map.of() : captured.active().checkpoint();
            require(context, GoalStep.Kind.MAKE_OBSIDIAN.name().equals(
                            checkpoint.get("task_kind"))
                            && "32".equals(checkpoint.get("task.target_count"))
                            && "0".equals(checkpoint.get("task.inventory_baseline")),
                    "service resumed a remaining-count task instead of original target 32: "
                            + checkpoint);
            require(context, "8".equals(checkpoint.get("obsidian.serviced_collected"))
                            && "0".equals(
                            checkpoint.get("obsidian.pending_service_boundary")),
                    "service acknowledgement was not committed exactly once: " + checkpoint);
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void doneServiceCheckpointAcknowledgesBoundaryWithoutReplay(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceDoneGT", 8, true);
        Map<String, String> pending = pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8);

        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "fixture service did not reach DONE: " + committed.state()
                        + ":" + committed.failureReason());

        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        committed.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        pending.forEach((key, value) ->
                missionCheckpoint.put("obsidian." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "DONE service was replayed instead of acknowledged: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "8".equals(checkpoint.get("obsidian.serviced_collected"))
                        && "0".equals(checkpoint.get("obsidian.pending_service_boundary"))
                        && "32".equals(checkpoint.get("obsidian.target_count")),
                "DONE restore lost or repeated the service acknowledgement: " + checkpoint);
        finish(context, fixture);
    }

    @GameTest(maxTicks = 80)
    public void doneServiceCheckpointAlreadyAcknowledgedResumesWithoutReplay(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceAlreadyAckGT", 8, true);
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "already-ACKed service fixture did not commit");

        restoreService(fixture, committed.checkpoint(), acknowledged);

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "already-ACKed DONE service was replayed: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, List.of(GoalStep.makeObsidian(32).describe())
                        .equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "already-ACKed restore retained service or preflight steps: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, GoalStep.Kind.MAKE_OBSIDIAN.name().equals(checkpoint.get("task_kind"))
                        && "8".equals(checkpoint.get("obsidian.serviced_collected"))
                        && "0".equals(checkpoint.get("obsidian.pending_service_boundary"))
                        && "32".equals(checkpoint.get("obsidian.target_count"))
                        && !checkpoint.containsKey("task.service_profile"),
                "already-ACKed restore lost its idempotent transaction identity: " + checkpoint);
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void doneBoundaryEightCannotAcknowledgePendingBoundarySixteen(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianWrongBoundaryGT", 16, true);
        MiningServiceTask staleBoundary = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        staleBoundary.start(fixture.bot());
        for (int tick = 0; tick < 20 && staleBoundary.state() == TaskState.RUNNING; tick++) {
            staleBoundary.tick(fixture.bot());
        }
        require(context, staleBoundary.state() == TaskState.COMPLETED,
                "stale boundary fixture did not commit");

        Map<String, String> pending16 = pendingBoundaryCheckpoint(
                fixture.start(), 16, 8, 16);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        staleBoundary.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        pending16.forEach((key, value) ->
                missionCheckpoint.put("obsidian." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "wrong-boundary DONE service remained active");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "wrong-boundary DONE service did not fail closed");
        require(context, "16".equals(pending16.get("pending_service_boundary")),
                "rejected service mutated the factual pending boundary");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void staleTargetThirtyTwoBoundaryCannotImpersonateFreshRemainingEight(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianBoundaryWrongTargetGT", 8, true);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 16);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, fresh.success()
                        && fresh.steps().stream().anyMatch(step -> step.isObsidianPreflight()
                        && step.obsidianTransactionTarget() == 8)
                        && fresh.steps().stream().anyMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN
                                && step.count() == 8),
                "stale-boundary fixture did not prove fresh remaining target8: " + fresh.steps());
        Map<String, String> pending = pendingBoundaryCheckpoint(
                fixture.start(), 8, 0, 8);
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        stale.start(fixture.bot());
        require(context, stale.state() == TaskState.RUNNING,
                "stale target32 boundary fixture did not remain resumable");

        restoreService(fixture, goal, stale.checkpoint(), pending);

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "target32 boundary replaced the factual fresh remaining target8");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale boundary target did not fail closed");
        require(context, "8".equals(pending.get("pending_service_boundary")),
                "rejected stale target mutated the factual pending boundary");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void staleActiveMakeTargetCannotImpersonateFreshRemainingEight(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMakeWrongTargetGT", 8, true);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 16);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, fresh.success()
                        && fresh.steps().stream().anyMatch(step -> step.isObsidianPreflight()
                        && step.obsidianTransactionTarget() == 8),
                "stale-MAKE fixture did not prove fresh remaining target8: " + fresh.steps());
        Map<String, String> staleTransaction = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SCAN, 8, null);

        restoreMake(fixture, goal, staleTransaction);

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "active MAKE target32 replaced the factual fresh remaining target8");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale active MAKE target did not fail closed");
        require(context, "8".equals(staleTransaction.get("collected"))
                        && "32".equals(staleTransaction.get("target_count")),
                "rejected active MAKE mutated its factual transaction checkpoint");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void committedMakeDoneAdvancesToStockpileWithoutReplay(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMakeDoneStockpileGT", TARGET, true);
        Goal goal = new Goal.Stockpile(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, fresh.success()
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN)
                        && fresh.steps().stream().anyMatch(
                        step -> step.kind() == GoalStep.Kind.STOCKPILE),
                "terminal-MAKE fixture did not prove a stockpile-only fresh tail: "
                        + fresh.steps());
        Map<String, String> terminal = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.DONE, TARGET, null));
        // The final batch is intentionally not followed by an inter-batch service boundary.
        terminal.put("serviced_collected", String.valueOf(TARGET - 8));
        require(context, CreateObsidianTask.inspectCheckpoint(terminal).isPresent(),
                "terminal MAKE fixture is not a valid production checkpoint");

        restoreMake(fixture, goal, Map.copyOf(terminal));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "committed MAKE lost the remaining stockpile tail");
        require(context, active instanceof StockpileTask,
                "committed MAKE was replayed instead of advancing to STOCKPILE: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        require(context, captured.active() != null
                        && captured.active().checkpoint().keySet().stream()
                        .noneMatch(key -> key.startsWith("obsidian.")),
                "committed terminal MAKE remained as a live transaction namespace");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void interruptedBoundaryServiceRestoresOnlyServiceThenOriginalMake(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceRunningGT", 8, true);
        Map<String, String> pending = pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8);
        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32,
                MiningCursor.initial(fixture.start(), 48));
        interrupted.start(fixture.bot());
        require(context, interrupted.state() == TaskState.RUNNING,
                "boundary service fixture did not remain resumable");
        Map<String, String> serviceCheckpoint = interrupted.checkpoint();
        BlockPos displaced = fixture.start().east();
        fixture.bot().teleportTo(
                fixture.bot().level(),
                displaced.getX() + 0.5D, displaced.getY(), displaced.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        restoreService(fixture, serviceCheckpoint, pending);

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask
                        && active.state() == TaskState.RUNNING,
                "running boundary service was not restored first from its persisted cursor");
        List<String> expected = List.of(
                GoalStep.obsidianService(8, 32).describe(),
                GoalStep.makeObsidian(32).describe());
        require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "running boundary restore retained stale preparation or preflight steps: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "8".equals(checkpoint.get("task.service_boundary"))
                        && "32".equals(checkpoint.get("task.service_target_count"))
                        && "8".equals(checkpoint.get("obsidian.pending_service_boundary")),
                "running boundary restore lost its exact dual identity: profile="
                        + checkpoint.get("task.service_profile")
                        + " boundary=" + checkpoint.get("task.service_boundary")
                        + " target=" + checkpoint.get("task.service_target_count")
                        + " pending=" + checkpoint.get("obsidian.pending_service_boundary"));
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void runningBoundaryRestorePreservesUnrelatedBuildMaterialPrefix(GameTestHelper context) {
        String blueprint = "obsidian_restore_mixed_"
                + UUID.randomUUID().toString().replace("-", "");
        Path blueprintPath = writeMixedObsidianBlueprint(blueprint);
        Fixture fixture = spawnPreparedBot(context, "ObsidianBuildRestoreGT", 8, true);
        try {
            Goal goal = new Goal.Build(blueprint);
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
            require(context, fresh.success()
                            && fresh.steps().stream().anyMatch(
                            step -> step.kind() == GoalStep.Kind.GATHER)
                            && fresh.steps().stream().anyMatch(
                            step -> step.kind() == GoalStep.Kind.CRAFT)
                            && fresh.steps().stream().anyMatch(
                            step -> step.kind() == GoalStep.Kind.BUILD)
                            && fresh.steps().stream().anyMatch(
                            step -> step.isObsidianPreflight()
                                    && step.obsidianTransactionTarget() == 24),
                    "mixed build fixture did not expose unrelated preparation around obsidian: "
                            + fresh.steps());
            List<String> preserved = fresh.steps().stream()
                    .filter(step -> step.kind() != GoalStep.Kind.MAKE_OBSIDIAN
                            && !step.isObsidianPreflight())
                    .map(GoalStep::describe)
                    .toList();
            Map<String, String> pending = pendingBoundaryCheckpoint(
                    fixture.start(), 8, 0, 8);
            MiningServiceTask interrupted = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    ServicePolicy.obsidian8(32, 8), 8,
                    fixture.missionId().toString(), 32);
            interrupted.start(fixture.bot());

            restoreService(fixture, goal, interrupted.checkpoint(), pending);

            List<String> expected = new ArrayList<>();
            expected.add(GoalStep.obsidianService(8, 32).describe());
            expected.add(GoalStep.makeObsidian(32).describe());
            expected.addAll(preserved);
            require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                    "boundary rebuild discarded unrelated Build preparation: expected="
                            + expected + " actual="
                            + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        } finally {
            deleteBlueprint(blueprintPath);
        }
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void runningBoundaryServiceCannotRestoreAfterBoundaryWasAcknowledged(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceOrphanGT", 8, true);
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        interrupted.start(fixture.bot());

        restoreService(fixture, interrupted.checkpoint(), acknowledged);

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "running service without a pending boundary remained active");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "orphaned running boundary service did not fail closed");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void interruptedPreflightReplacesTheFreshPlannerCopyWithoutBoundaryAck(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightRestoreGT", 0, true);

        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        interrupted.start(fixture.bot());
        require(context, interrupted.state() == TaskState.RUNNING,
                "preflight fixture did not enter a resumable state");

        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        interrupted.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask
                        && active.state() == TaskState.RUNNING,
                "interrupted preflight was not replayed first: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        long preflights = GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()).stream()
                .filter(GoalStep.obsidianPreflight().describe()::equals)
                .count();
        require(context, preflights == 1,
                "restore retained a duplicate obsidian preflight: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "OBSIDIAN_PREFLIGHT".equals(
                        checkpoint.get("task.service_profile")),
                "restored service lost its preflight identity: " + checkpoint);
        require(context, checkpoint.keySet().stream().noneMatch(key -> key.startsWith("obsidian.")),
                "preflight fabricated an obsidian boundary transaction: " + checkpoint);
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void runningPreflightUsesCheckpointIdentityWhenFreshTargetIsUnknown(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightUnknownGT", 0, true);
        BlockPos depotPos = movePreflightReadinessToDepot(context, fixture);

        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        interrupted.start(fixture.bot());
        Map<String, String> checkpoint = interrupted.checkpoint();
        require(context, encode(depotPos).equals(checkpoint.get("depot")),
                "unknown-target fixture did not persist its factual depot: " + checkpoint);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, !fresh.success()
                        && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                "fixture unexpectedly proved a fresh preflight target: " + fresh.steps());

        restoreService(fixture, checkpoint, Map.of());

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask
                        && active.state() == TaskState.RUNNING,
                "schema-4 preflight was rejected only because fresh target was unknown");
        List<String> expected = List.of(
                GoalStep.obsidianPreflight(32).describe(),
                GoalStep.makeObsidian(32).describe());
        require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "unknown-target restore did not retain checkpoint target32: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> restored = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "32".equals(restored.get("task.service_target_count"))
                        && fixture.missionId().toString().equals(
                        restored.get("task.service_mission_id")),
                "unknown-target restore weakened the checkpoint identity: " + restored);
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void committedPreflightUsesCheckpointIdentityWhenFreshTargetIsUnknown(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightDoneUnknownGT", 0, true);
        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "unknown-target DONE fixture did not commit");
        Map<String, String> checkpoint = committed.checkpoint();
        movePreflightReadinessToDepot(context, fixture);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, !fresh.success()
                        && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                "DONE fixture unexpectedly proved a fresh preflight target: " + fresh.steps());

        restoreService(fixture, checkpoint, Map.of());

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "committed schema-4 preflight was not advanced to original MAKE when fresh target was unknown: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, List.of(GoalStep.makeObsidian(32).describe())
                        .equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "unknown-target DONE restore replayed preflight or changed target32: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> restored = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, GoalStep.Kind.MAKE_OBSIDIAN.name().equals(restored.get("task_kind"))
                        && !restored.containsKey("task.service_profile"),
                "committed unknown-target preflight identity survived after hand-off: " + restored);
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void stalePreflightCannotUseUnknownFreshTargetAuthority(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightUnknownStaleGT", 0, true);
        movePreflightReadinessToDepot(context, fixture);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, !fresh.success()
                        && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                "stale-target fixture unexpectedly proved a fresh identity: " + fresh.steps());
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(16), 0,
                fixture.missionId().toString(), 16);
        stale.start(fixture.bot());

        restoreService(fixture, stale.checkpoint(), Map.of());

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "unknown fresh plan let target16 impersonate remaining target32");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale unknown-target preflight did not fail closed");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void compoundBuildCannotUseUnknownPreflightAuthority(GameTestHelper context) {
        String blueprint = "obsidian_unknown_build_"
                + UUID.randomUUID().toString().replace("-", "");
        Path blueprintPath = writeMixedObsidianBlueprint(blueprint);
        Fixture fixture = spawnPreparedBot(context, "ObsidianBuildUnknownGT", 8, true);
        try {
            movePreflightReadinessToDepot(context, fixture);
            Goal goal = new Goal.Build(blueprint);
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
            require(context, !fresh.success()
                            && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                            && fresh.steps().stream().noneMatch(
                            step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                    "compound fixture unexpectedly proved a fresh obsidian identity: "
                            + fresh.steps());
            Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                    pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
            MiningServiceTask interrupted = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    ServicePolicy.obsidianPreflight(24), 0,
                    fixture.missionId().toString(), 24);
            interrupted.start(fixture.bot());

            restoreService(fixture, goal, interrupted.checkpoint(), acknowledged);

            require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                    "compound Build used an unknown fresh target as preflight authority");
            require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                            .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                    .equals(result.reason()))
                            .orElse(false),
                    "compound Build unknown preflight did not fail closed");
        } finally {
            deleteBlueprint(blueprintPath);
        }
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void compoundBuildWithFailedFreshPlanCannotReplayOnlyBoundaryAndMake(GameTestHelper context) {
        String blueprint = "obsidian_unknown_boundary_build_"
                + UUID.randomUUID().toString().replace("-", "");
        Path blueprintPath = writeMixedObsidianBlueprint(blueprint);
        Fixture fixture = spawnPreparedBot(context, "ObsidianBuildBoundaryUnknownGT", 8, true);
        try {
            movePreflightReadinessToDepot(context, fixture);
            Goal goal = new Goal.Build(blueprint);
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
            require(context, !fresh.success()
                            && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                            && fresh.steps().stream().noneMatch(
                            step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                    "compound boundary fixture unexpectedly retained a rebuildable tail: "
                            + fresh.steps());
            Map<String, String> pending = pendingBoundaryCheckpoint(
                    fixture.start(), 8, 0, 8);
            MiningServiceTask interrupted = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    ServicePolicy.obsidian8(32, 8), 8,
                    fixture.missionId().toString(), 32);
            interrupted.start(fixture.bot());
            require(context, interrupted.state() == TaskState.RUNNING,
                    "compound boundary fixture did not remain resumable");

            restoreService(fixture, goal, interrupted.checkpoint(), pending);

            require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                    "compound restore scheduled service -> MAKE after losing the Build tail");
            require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                            .map(result -> "mission_restore_compound_obsidian_tail_unavailable"
                                    .equals(result.reason()))
                            .orElse(false),
                    "compound boundary restore did not fail closed on its unavailable tail");
            require(context, "8".equals(pending.get("pending_service_boundary")),
                    "rejected compound restore mutated the factual pending boundary");
        } finally {
            deleteBlueprint(blueprintPath);
        }
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void runningPreflightWithStaleTargetCannotReplaceFreshTarget(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightWrongTargetGT", 0, true);
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(16), 0,
                fixture.missionId().toString(), 16);
        stale.start(fixture.bot());

        restoreService(fixture, stale.checkpoint(), Map.of());

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "running target16 preflight replaced fresh target32");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale running preflight target did not fail closed");
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void committedStalePreflightCannotSkipFreshTargetPreflight(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightStaleDoneGT", 0, true);
        // Keep the fresh target32 plan at its preflight boundary. Without carried torches or the
        // required expedition weapon, the stricter readiness contract legitimately prepends an
        // acquisition branch, which is unrelated to the stale committed service identity under test.
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.TORCH, 8));
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.STONE_SWORD));
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                fixture.bot(), new Goal.HaveItem(Items.OBSIDIAN, TARGET));
        require(context, fresh.success()
                        && fresh.steps().stream().anyMatch(step -> step.isObsidianPreflight()
                        && step.obsidianTransactionTarget() == TARGET),
                "stale DONE fixture did not establish a fresh target32 preflight: "
                        + fresh.steps() + " unresolved=" + fresh.unresolved());
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(16), 0,
                fixture.missionId().toString(), 16);
        stale.start(fixture.bot());
        for (int tick = 0; tick < 20 && stale.state() == TaskState.RUNNING; tick++) {
            stale.tick(fixture.bot());
        }
        require(context, stale.state() == TaskState.COMPLETED,
                "stale preflight fixture did not commit");

        restoreService(fixture, stale.checkpoint(), Map.of());

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask,
                "stale DONE preflight skipped the fresh target32 preflight");
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "OBSIDIAN_PREFLIGHT".equals(
                        checkpoint.get("task.service_profile"))
                        && "32".equals(checkpoint.get("task.service_target_count")),
                "fresh preflight did not retain target32 after stale DONE restore: "
                        + checkpoint);
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void remainingTargetPreflightCanRepairAnAcknowledgedTransaction(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightRemainingGT", 8, true);
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(24), 0,
                fixture.missionId().toString(), 24);
        interrupted.start(fixture.bot());

        restoreService(fixture, interrupted.checkpoint(), acknowledged);

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask,
                "remaining target24 repair preflight was rejected");
        List<String> expected = List.of(
                GoalStep.obsidianPreflight(24).describe(),
                GoalStep.makeObsidian(32).describe());
        require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "repair preflight did not preserve the original target32 transaction: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void committedPreflightIsNotReplayedAfterCrashWindowRestore(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightDoneGT", 0, true);
        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "preflight crash-window fixture did not commit");

        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        committed.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "committed preflight was replayed instead of advancing to MAKE_OBSIDIAN: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()).stream()
                        .noneMatch(GoalStep.obsidianPreflight().describe()::equals),
                "committed preflight remained in the restored plan: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        finish(context, fixture);
    }

    @GameTest(maxTicks = 20)
    public void searchPreservesHigherTierOreAndClosesTheStoneOnlyLeg(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchGoldGT", 0, true);
        BlockPos gold = fixture.start().north().above();
        fixture.bot().level().setBlock(
                gold, Blocks.GOLD_ORE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, ObservableWorldQuery.canObserveCell(fixture.bot(), gold),
                "fixture head-level gold must be directly observable");
        require(context, !ObservableWorldQuery.canObserveCell(
                        fixture.bot(), fixture.start().north()),
                "fixture must lock the head-wall occlusion of the feet cell");
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).beginNextLeg().encode()
                .forEach(checkpoint::put);
        int stoneDamageBefore = fixture.bot().getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamageValue)
                .sum();

        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());
        AtomicInteger ticks = new AtomicInteger();
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            require(context, task.state() == TaskState.RUNNING,
                    "higher-tier search obstruction ended the obsidian task: "
                            + task.failureReason());
            require(context,
                    fixture.bot().level().getBlockState(gold).is(Blocks.GOLD_ORE),
                    "stone-only obsidian search destroyed the finite gold obstruction");
            if (!"0".equals(task.checkpoint().get("steps_left"))) {
                if (ticks.incrementAndGet() > 10) {
                    context.fail(Component.nullToEmpty(
                            "gold-facing search leg was not durably closed: "
                                    + task.checkpoint()));
                }
                return;
            }
            require(context, "0".equals(task.checkpoint().get("direction")),
                    "gold obstruction changed the wrong search leg: " + task.checkpoint());
            int stoneDamageAfter = fixture.bot().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> stack.is(Items.STONE_PICKAXE))
                    .mapToInt(ItemStack::getDamageValue)
                    .sum();
            require(context, stoneDamageAfter == stoneDamageBefore,
                    "gold reroute consumed stone-pick durability");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 220)
    public void restoredScanReturnsToDedicatedRimBeforeBindingDisplacedObsidian(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianScanResumeGT", 0, true);
        var world = fixture.bot().level();
        BlockPos cursorFace = fixture.start();
        BlockPos scanFace = fixture.start().east(2);
        BlockPos displaced = fixture.start().east(5);
        for (int dx = 0; dx <= 5; dx++) {
            BlockPos feet = fixture.start().east(dx);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos displacedObsidian = displaced.north();
        world.setBlock(displacedObsidian.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(displacedObsidian,
                Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(displacedObsidian.above(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        fixture.bot().teleportTo(world,
                displaced.getX() + 0.5D, displaced.getY(), displaced.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                cursorFace, CreateObsidianTask.Phase.SCAN, 0, null));
        checkpoint.put("scan_resume_face", encode(scanFace));
        ObsidianSearchCursor.initial(cursorFace, 12).encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());

        require(context, CreateObsidianTask.Phase.RETURN_TO_SCAN_FACE.name()
                        .equals(task.checkpoint().get("phase")),
                "displaced SCAN restore did not enter its dedicated return phase: "
                        + task.checkpoint());
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("scan resume failed before its durable face: "
                        + task.failureReason()));
            }
            Map<String, String> live = task.checkpoint();
            require(context, !live.containsKey("obsidian")
                            && world.getBlockState(displacedObsidian).is(Blocks.OBSIDIAN),
                    "displaced restore bound or mutated a target before returning: " + live);
            if (!fixture.bot().blockPosition().equals(scanFace)
                    || !CreateObsidianTask.Phase.SCAN.name().equals(live.get("phase"))) {
                return;
            }
            require(context, !fixture.bot().blockPosition().equals(cursorFace),
                    "SCAN resume reused the corridor cursor instead of the dedicated rim");
            require(context, encode(scanFace).equals(live.get("scan_resume_face")),
                    "SCAN resume identity changed during physical return");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 40)
    public void auditScanDefersPreExistingObsidianAndBindsOnlyWaterBackedCell(
            GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianAuditGateGT", 0, true);
        var world = fixture.bot().level();
        BlockPos preExisting = fixture.start().north();
        BlockPos waterBacked = fixture.start().east(2);
        world.setBlock(preExisting, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(waterBacked, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);

        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        MiningEvidenceAudit.recordWaterPlacement(fixture.bot(), Set.of(waterBacked));
        require(context, MiningEvidenceAudit.recordLavaToObsidian(fixture.bot(), waterBacked),
                "fixture could not commit its exact water-backed conversion");

        CreateObsidianTask task = new CreateObsidianTask(
                TARGET, taskCheckpoint(fixture.start(), CreateObsidianTask.Phase.SCAN, 0, null));
        task.start(fixture.bot());
        task.tick(fixture.bot());
        Map<String, String> live = task.checkpoint();

        require(context, task.state() == TaskState.RUNNING,
                "audit-gated scan ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, encode(waterBacked).equals(live.get("obsidian")),
                "audit-gated scan bound a cell without exact conversion provenance: " + live);
        require(context, world.getBlockState(preExisting).is(Blocks.OBSIDIAN),
                "audit-gated scan mutated the pre-existing obsidian cell");
        require(context, MiningEvidenceAudit.snapshot(fixture.bot())
                        .map(snapshot -> snapshot.lavaConversions() == 1
                                && snapshot.obsidianBreaks() == 0
                                && snapshot.obsidianPhysicalPickups() == 0)
                        .orElse(false),
                "audit-gated scan advanced beyond its one exact conversion");

        task.cancel(fixture.bot(), "gametest_complete");
        MiningEvidenceAudit.clear(fixture.bot());
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void auditedServiceRestartDoesNotPromoteUnrelatedInventoryObsidian(
            GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianAuditServiceRestartGT", 0, true);
        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        for (int index = 0; index < 8; index++) {
            BlockPos exact = fixture.start().offset(index + 2, -1, 0);
            MiningEvidenceAudit.recordWaterPlacement(fixture.bot(), Set.of(exact));
            require(context, MiningEvidenceAudit.recordLavaToObsidian(fixture.bot(), exact)
                            && MiningEvidenceAudit.recordObsidianBreak(fixture.bot(), exact)
                            && MiningEvidenceAudit.recordObsidianPickup(fixture.bot(), exact, 2),
                    "fixture could not commit exact audited pickup " + index);
        }
        // Nine physical inventory items but only eight exact-cell transactions. The ninth item is
        // deliberately unrelated and must not become mission progress when service replaces the
        // task and restores its acknowledged boundary checkpoint.
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.OBSIDIAN, 9));
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        CreateObsidianTask restored = new CreateObsidianTask(TARGET, acknowledged);
        restored.start(fixture.bot());

        require(context, restored.state() == TaskState.RUNNING,
                "audited service restore failed at start: " + restored.failureReason());
        require(context, "8".equals(restored.checkpoint().get("collected"))
                        && "8".equals(restored.checkpoint().get("serviced_collected")),
                "service restore promoted unrelated inventory obsidian: "
                        + restored.checkpoint());
        restored.tick(fixture.bot());
        require(context, restored.state() == TaskState.RUNNING
                        && "8".equals(restored.checkpoint().get("collected")),
                "first restored tick rejected or promoted the exact audit ledger: "
                        + restored.state() + ":" + restored.failureReason()
                        + " checkpoint=" + restored.checkpoint());

        restored.cancel(fixture.bot(), "gametest_complete");
        MiningEvidenceAudit.clear(fixture.bot());
        finish(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void strictAuditSessionLossAndReplacementMismatchFailBeforeAnotherAction(
            GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianAuditSessionGT", 0, true);
        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        CreateObsidianTask live = new CreateObsidianTask(TARGET);
        live.start(fixture.bot());
        Map<String, String> boundCheckpoint = live.checkpoint();
        require(context, live.state() == TaskState.RUNNING
                        && boundCheckpoint.containsKey("audit_session"),
                "fresh strict task did not persist its audit session identity: "
                        + boundCheckpoint);

        MiningEvidenceAudit.clear(fixture.bot());
        live.tick(fixture.bot());
        require(context, live.state() == TaskState.FAILED
                        && "create_obsidian_audit_session_missing_or_mismatched"
                        .equals(live.failureReason()),
                "live task silently downgraded after its strict session disappeared: "
                        + live.state() + ":" + live.failureReason());

        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        CreateObsidianTask replacement = new CreateObsidianTask(TARGET, boundCheckpoint);
        replacement.start(fixture.bot());
        require(context, replacement.state() == TaskState.FAILED
                        && "create_obsidian_audit_session_missing_or_mismatched"
                        .equals(replacement.failureReason()),
                "replacement accepted a checkpoint from a different strict session: "
                        + replacement.state() + ":" + replacement.failureReason());

        MiningEvidenceAudit.clear(fixture.bot());
        finish(context, fixture);
    }

    @GameTest(maxTicks = 80)
    public void searchPhysicallyLightsTheDarkTrailBehindItsReachedFace(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchTorchGT", 0, true);
        var world = fixture.bot().level();
        BlockPos next = fixture.start().north();
        for (BlockPos cell : List.of(fixture.start(), next)) {
            world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            for (net.minecraft.core.Direction side : List.of(
                    net.minecraft.core.Direction.EAST,
                    net.minecraft.core.Direction.WEST)) {
                world.setBlock(cell.relative(side),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.relative(side).above(),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (BlockPos end : List.of(fixture.start().south(), next.north())) {
            world.setBlock(end, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(end.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.TORCH));
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).beginNextLeg().encode()
                .forEach(checkpoint::put);
        AtomicReference<CreateObsidianTask> taskRef = new AtomicReference<>();
        AtomicBoolean litBeforeMove = new AtomicBoolean();
        // Sky and block light under the fresh corridor roofs only settle once the light engine
        // catches up with the fixture placement; under parallel batch load that can lag past
        // a fixed tick. Gate on the observed darkness instead, so the assertions cannot race
        // the engine.
        AtomicBoolean started = new AtomicBoolean();
        context.failIfEver(() -> {
            if (!started.get()) {
                if (world.canSeeSky(fixture.start()) || world.canSeeSky(next)
                        || world.getBrightness(LightLayer.BLOCK, fixture.start()) >= 8
                        || world.getBrightness(LightLayer.SKY, fixture.start()) >= 8) {
                    return;
                }
                started.set(true);
                require(context, !world.canSeeSky(fixture.start()) && !world.canSeeSky(next),
                        "dark search fixture still exposed its corridor to the sky");
                require(context, world.getBrightness(LightLayer.BLOCK, fixture.start()) < 8
                                && world.getBrightness(LightLayer.SKY, fixture.start()) < 8,
                        "dark search fixture was not actually dark");
                CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
                task.start(fixture.bot());
                taskRef.set(task);
                return;
            }
            CreateObsidianTask task = taskRef.get();
            if (task == null) {
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("dark search trail failed before lighting: "
                        + task.failureReason()));
            }
            if (fixture.bot().level().getBlockState(fixture.start()).is(Blocks.TORCH)
                    && fixture.bot().blockPosition().equals(fixture.start())) {
                litBeforeMove.set(true);
            }
            if (!fixture.bot().level().getBlockState(fixture.start()).is(Blocks.TORCH)) {
                return;
            }
            if (!fixture.bot().blockPosition().equals(fixture.start().north())) {
                return;
            }
            require(context, litBeforeMove.get(),
                    "dark SEARCH moved or mined before physically lighting its initial face");
            require(context, fixture.bot().blockPosition().equals(fixture.start().north()),
                    "search torch appeared without a physical one-cell advance");
            require(context, InventoryAction.countItem(fixture.bot(), Items.TORCH) == 0,
                    "search lighting did not consume exactly one physical torch");
            require(context, fixture.start().north().equals(
                            ObsidianSearchCursor.decode(task.checkpoint()).orElseThrow().face()),
                    "torch placement fabricated or lost the durable search face");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 100)
    public void threeBlockedDirectionsClearOnlyAfterTheOpenFaceIsReached(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchOneExitGT", 0, true);
        for (net.minecraft.core.Direction direction : List.of(
                net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.SOUTH)) {
            fixture.bot().level().setBlock(
                    fixture.start().relative(direction).above(),
                    Blocks.GOLD_ORE.defaultBlockState(), Block.UPDATE_ALL);
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("one-exit search failed before moving: "
                        + task.failureReason()));
            }
            if (!fixture.bot().blockPosition().equals(fixture.start().west())) {
                return;
            }
            require(context, "0".equals(task.checkpoint().get("blocked_directions")),
                    "physical movement did not clear the prior blocked sweep: " + task.checkpoint());
            require(context, "1".equals(task.checkpoint().get("topology_epoch")),
                    "one factual face advance did not increment topology exactly once");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void skyLitSearchDoesNotSpendTheUndergroundTorchReserve(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchSkyGT", 0, true);
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.TORCH));
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).beginNextLeg().encode()
                .forEach(checkpoint::put);
        AtomicReference<CreateObsidianTask> taskRef = new AtomicReference<>();
        // canSeeSky() depends on a lazily refreshed heightmap and can briefly report the
        // pre-fixture value when the default GameTest batch prepares many neighbouring
        // structures in the same server tick (see
        // searchPhysicallyLightsTheDarkTrailBehindItsReachedFace and
        // DangerWatcherLowHealthGameTests#hostileLowHealthCannotInterruptAtomicHealingEat).
        // Gate on the observed sky visibility settling to the expected value instead of
        // asserting it synchronously right after spawn, so the assertion and the task start
        // cannot race the engine.
        AtomicBoolean started = new AtomicBoolean();
        context.failIfEver(() -> {
            if (!started.get()) {
                if (!fixture.bot().level().canSeeSky(fixture.start())) {
                    return;
                }
                started.set(true);
                CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
                task.start(fixture.bot());
                taskRef.set(task);
                return;
            }
            CreateObsidianTask task = taskRef.get();
            if (task == null) {
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("sky-lit search failed: " + task.failureReason()));
            }
            if (!fixture.bot().blockPosition().equals(fixture.start().north())) {
                return;
            }
            require(context, !fixture.bot().level().getBlockState(
                            fixture.start()).is(Blocks.TORCH),
                    "sky-lit search placed an unnecessary underground torch");
            require(context, InventoryAction.countItem(fixture.bot(), Items.TORCH) == 1,
                    "sky-lit search consumed its torch reserve");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 80)
    public void fourClosedSearchDirectionsFailTypedWithoutRotatingAsProgress(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchClosedGT", 0, true);
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.IRON_PICKAXE));
        List<net.minecraft.core.Direction> blockedDirections = new ArrayList<>();
        for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            blockedDirections.add(direction);
        }
        for (int index = 0; index < blockedDirections.size(); index++) {
            fixture.bot().level().setBlock(
                    fixture.start().relative(blockedDirections.get(index)),
                    Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            fixture.bot().level().setBlock(
                    fixture.start().relative(blockedDirections.get(index)).above(),
                    Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());
        int toolDamageBefore = fixture.bot().getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.STONE_PICKAXE)
                        || stack.is(Items.IRON_PICKAXE)
                        || stack.is(Items.DIAMOND_PICKAXE))
                .mapToInt(ItemStack::getDamageValue)
                .sum();

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                // GameTests run in parallel and several mining fixtures deliberately tunnel
                // beyond their tiny empty templates. Reassert this test-owned one-cell shell
                // before every task tick so a neighbouring fixture cannot turn a factual closed
                // direction into ordinary mineable stone midway through the four-face sweep.
                for (net.minecraft.core.Direction direction : blockedDirections) {
                    fixture.bot().level().setBlock(
                            fixture.start().relative(direction),
                            Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    fixture.bot().level().setBlock(
                            fixture.start().relative(direction).above(),
                            Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                }
                task.tick(fixture.bot());
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "four-direction closure ended as " + task.state());
            require(context, task.failureReason().startsWith("create_obsidian_search_enclosed"),
                    "four-direction closure did not expose a typed terminal reason: "
                            + task.failureReason());
            require(context, "15".equals(task.checkpoint().get("blocked_directions")),
                    "closed-direction mask was not durably persisted: " + task.checkpoint());
            require(context, fixture.bot().blockPosition().equals(fixture.start()),
                    "closed-direction sweep moved away from its factual work face");
            require(context, "0".equals(task.checkpoint().get("topology_epoch"))
                            && "390".equals(task.checkpoint().get("last_progress")),
                    "pure blocked rotation was recorded as topology/progress: " + task.checkpoint());
            int toolDamageAfter = fixture.bot().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> stack.is(Items.STONE_PICKAXE)
                            || stack.is(Items.IRON_PICKAXE)
                            || stack.is(Items.DIAMOND_PICKAXE))
                    .mapToInt(ItemStack::getDamageValue)
                    .sum();
            require(context, toolDamageAfter == toolDamageBefore,
                    "closed-direction sweep spent a reserved mining tool");
            for (int index = 0; index < blockedDirections.size(); index++) {
                require(context, fixture.bot().level().getBlockState(
                                fixture.start().relative(blockedDirections.get(index)))
                                .is(Blocks.BEDROCK),
                        "closed search destroyed its feet-level factual blocker");
                require(context, fixture.bot().level().getBlockState(
                                fixture.start().relative(blockedDirections.get(index)).above())
                                .is(Blocks.BEDROCK),
                        "closed search destroyed its head-level factual blocker");
            }
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 40)
    public void darkRestoredClosedMaskOutranksMissingTorch(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianDarkClosedGT", 0, true);
        var world = fixture.bot().level();
        BlockPos start = fixture.start();
        // A single centre roof still admits propagated skylight from the four upper corners and
        // made the strict no-torch precondition depend on parallel fixture placement. Own a small
        // complete roof instead; the production branch under test remains mask=15 ordering.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(start.offset(dx, 2, dz),
                        Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            world.setBlock(start.relative(direction),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(start.relative(direction).above(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        ObsidianSearchCursor cursor = ObsidianSearchCursor.initial(start, 12);
        for (int direction = 0; direction < 4; direction++) {
            cursor = cursor.beginNextLeg().skipBlockedLeg();
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                start, CreateObsidianTask.Phase.SEARCH, 0, null));
        cursor.encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());

        context.runAtTickTime(1, () -> {
            // Long-running mining GameTests share the world and can cross the small empty-template
            // spacing. Reassert this fixture's owned shell in the same tick as the light check so
            // an unrelated tunnel cannot transiently turn a factual dark restore into skylight.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlock(start.offset(dx, 2, dz),
                            Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            for (net.minecraft.core.Direction direction
                    : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                world.setBlock(start.relative(direction),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.relative(direction).above(),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            require(context, world.getBrightness(LightLayer.BLOCK, start) < 8
                            && world.getBrightness(LightLayer.SKY, start) < 8
                            && InventoryAction.countItem(fixture.bot(), Items.TORCH) == 0,
                    "closed-mask fixture is not a dark no-torch restore");
            task.tick(fixture.bot());
            require(context, task.state() == TaskState.FAILED,
                    "persisted mask=15 did not fail terminally");
            require(context, task.failureReason().startsWith("create_obsidian_search_enclosed"),
                    "missing torch obscured the factual enclosure: " + task.failureReason());
            require(context, fixture.bot().blockPosition().equals(start)
                            && "15".equals(task.checkpoint().get("blocked_directions")),
                    "terminal enclosed restore moved or lost its factual mask");
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 120)
    public void pickupMicrostepsCloseTwoCellGapWithoutAcceptingAdjacentPathSnap(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPickupMicroGT", 0, true);
        BlockPos start = fixture.start();
        BlockPos target = start.offset(2, 0, 1);
        CreateObsidianTask pickup = new CreateObsidianTask(1);

        require(context, pickup.stepTowardPickupCell(fixture.bot(), target),
                "first bounded pickup microstep was not issued");
        // The microsteps are walked (movement keys, a few ticks each), never a teleport: the bot has not arrived in the same tick,
        // it passes through the exact adjacent transit cell, and the next step starts only after it has arrived.
        require(context, !fixture.bot().blockPosition().equals(target),
                "the first pickup microstep jumped straight to the drop cell");
        boolean[] transit = {false};
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            var bot = fixture.bot();
            if (bot.blockPosition().equals(start.east())) {
                transit[0] = true;
            }
            if (bot.blockPosition().equals(target)) {
                require(context, transit[0],
                        "the pickup microsteps skipped the exact adjacent transit cell "
                                + start.east().toShortString());
                finish(context, fixture);
                return;
            }
            if (bot.getActionPack().stepIdle()) {
                require(context, pickup.stepTowardPickupCell(bot, target),
                        "bounded pickup collision step was not issued at " + bot.blockPosition().toShortString());
            }
            require(context, tick[0] < 100,
                    "pickup microsteps stopped beside the factual drop cell: " + bot.blockPosition().toShortString());
        });
    }

    @GameTest(maxTicks = 120)
    public void pickupCheckpointPhysicallyCollectsSurvivingItemEntity(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPickupEntityGT", 0, true);
        BlockPos target = fixture.start().offset(2, 0, 1);
        ItemEntity drop = new ItemEntity(fixture.bot().level(),
                target.getX() + 0.5D, target.getY() + 0.1D, target.getZ() + 0.5D,
                new ItemStack(Items.OBSIDIAN));
        fixture.bot().level().addFreshEntity(drop);

        CreateObsidianTask task = new CreateObsidianTask(1,
                pickupCheckpoint(fixture.start(), target));
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_pickup_entity"));
        AtomicBoolean collisionPickupObserved = new AtomicBoolean();

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("obsidian pickup task ended as " + task.state()
                        + ":" + task.failureReason() + " checkpoint=" + task.checkpoint()));
            }
            int obsidian = InventoryAction.countItem(fixture.bot(), Items.OBSIDIAN);
            if (obsidian > 0 && !collisionPickupObserved.get()) {
                require(context, fixture.bot().position().distanceToSqr(target.getCenter()) <= 4.0D,
                        "obsidian entered inventory away from the surviving ItemEntity");
                collisionPickupObserved.set(true);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, collisionPickupObserved.get(),
                        "pickup task completed without an observed collision pickup");
                require(context, obsidian == 1 && !drop.isAlive(),
                        "physical obsidian drop was not consumed exactly once");
                require(context, !task.checkpoint().containsKey("pending_pickup_pos")
                                && !task.checkpoint().containsKey("pending_pickup_last_seen_pos"),
                        "completed pickup retained a stale pending ledger: " + task.checkpoint());
                finish(context, fixture);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:create_obsidian_mission_recovery_game_tests_air_at_restored_active_break_rebuilds_protected_pickup_transaction", maxTicks = 40)
    public void airAtRestoredActiveBreakRebuildsProtectedPickupTransaction(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianActiveBreakAirGT", 1, true);
        BlockPos target = fixture.start().east();
        require(context, fixture.bot().level().getBlockState(target).isAir(),
                "active-break restore fixture must begin after the physical block break");

        CreateObsidianTask task = new CreateObsidianTask(1,
                activeBreakCheckpoint(fixture.start(), target, fixture.start()));
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_active_break_air"));

        context.runAtTickTime(8, () -> {
            Map<String, String> live = task.checkpoint();
            require(context, task.state() == TaskState.RUNNING,
                    "AIR restore did not retain a live pickup transaction: "
                            + task.state() + ":" + task.failureReason());
            require(context, InventoryAction.countItem(fixture.bot(), Items.OBSIDIAN) == 1,
                    "fixture must model a break whose drop crossed the crash boundary");
            require(context, CreateObsidianTask.Phase.PROTECT_PICKUP.name()
                            .equals(live.get("phase"))
                            && encode(target).equals(live.get("pending_pickup_pos"))
                            && !live.containsKey("active_break_pos"),
                    "AIR restore did not atomically promote active break to pickup: " + live);
            String source = live.get("water_source");
            require(context, source != null
                            && InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1
                            && InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 0,
                    "AIR restore did not place the post-break protection source: " + live);
            ObsidianCheckpoint decoded = ObsidianCheckpoint.decode(live, 1, 24000)
                    .orElseThrow(() -> new IllegalStateException(
                            "live AIR restore checkpoint failed its codec: " + live));
            require(context, target.equals(decoded.pickupLastSeenPos()),
                    "promoted pickup lost its factual last-seen fallback: " + live);
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:create_obsidian_mission_recovery_game_tests_air_at_restored_active_break_retains_live_source_for_fresh_protection_window", maxTicks = 40)
    public void airAtRestoredActiveBreakRetainsLiveSourceForFreshProtectionWindow(
            GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianActiveBreakWaterGT", 0, false);
        BlockPos target = fixture.start().east();
        fixture.bot().level().setBlock(
                target, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                activeBreakCheckpoint(fixture.start(), target, fixture.start()));
        checkpoint.put("water_source", encode(target));
        checkpoint.put("water_bucket_baseline", "1");
        Map<String, String> sealed = Map.copyOf(checkpoint);
        require(context, ObsidianCheckpoint.decode(sealed, 1, 24000).isPresent(),
                "live-source active-break fixture failed the production codec");

        CreateObsidianTask task = new CreateObsidianTask(1, sealed);
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_obsidian_active_break_live_source"));

        context.runAtTickTime(8, () -> {
            Map<String, String> live = task.checkpoint();
            require(context, task.state() == TaskState.RUNNING
                            && CreateObsidianTask.Phase.PROTECT_PICKUP.name()
                            .equals(live.get("phase")),
                    "AIR promotion reclaimed its live source before protection: " + live);
            require(context, encode(target).equals(live.get("water_source"))
                            && fixture.bot().level().getFluidState(target).isSource()
                            && InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1
                            && InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 0,
                    "fresh post-break protection did not retain the committed source: " + live);
            require(context, Integer.parseInt(live.get("budget_used"))
                            - Integer.parseInt(live.get("phase_started")) < 20,
                    "fixture observed the source after its fresh protection window expired");
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:create_obsidian_mission_recovery_game_tests_raw_two_pick_settles_final_break_and_physical_pickup_at_raw_one", maxTicks = 800)
    public void rawTwoPickSettlesFinalBreakAndPhysicalPickupAtRawOne(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianLastDurabilityGT", 0, true);
        BlockPos target = fixture.start().east();
        fixture.bot().level().setBlock(
                target, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);

        int diamondSlot = InventoryAction.findItem(fixture.bot(), Items.DIAMOND_PICKAXE)
                .orElseThrow(() -> new IllegalStateException("fixture missing diamond pickaxe"));
        ItemStack diamond = fixture.bot().getInventory().getNonEquipmentItems().get(diamondSlot);
        diamond.setDamageValue(diamond.getMaxDamage() - 2);

        Map<String, String> checkpoint = activeBreakCheckpoint(
                fixture.start(), target, fixture.start());
        CreateObsidianTask task = new CreateObsidianTask(1, checkpoint);
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_last_durability"));
        AtomicBoolean collisionPickupObserved = new AtomicBoolean();
        AtomicBoolean protectedSourceObserved = new AtomicBoolean();
        AtomicBoolean drainWindowObserved = new AtomicBoolean();
        AtomicBoolean drainWindowCompleted = new AtomicBoolean();
        AtomicBoolean edgeImpulseInjected = new AtomicBoolean();
        AtomicBoolean shiftedLastSeenObserved = new AtomicBoolean();
        AtomicBoolean rimReturnObserved = new AtomicBoolean();
        AtomicInteger protectionStartedBudget = new AtomicInteger(-1);
        AtomicInteger drainStartedBudget = new AtomicInteger(-1);
        AtomicReference<Vec3> lastObservedDropPosition = new AtomicReference<>();

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("last-durability obsidian task ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint()));
            }
            int obsidian = InventoryAction.countItem(fixture.bot(), Items.OBSIDIAN);
            Map<String, String> live = task.checkpoint();
            if (CreateObsidianTask.Phase.PROTECT_PICKUP.name().equals(live.get("phase"))
                    && live.containsKey("water_source")) {
                protectedSourceObserved.set(true);
                protectionStartedBudget.compareAndSet(
                        -1, Integer.parseInt(live.get("phase_started")));
            }
            if (CreateObsidianTask.Phase.WAIT_DRAIN.name().equals(live.get("phase"))) {
                require(context, protectedSourceObserved.get(),
                        "pickup drain window began before post-break water protection");
                int budget = Integer.parseInt(live.get("budget_used"));
                require(context, budget - protectionStartedBudget.get() >= 20,
                        "post-break source was recovered before its 20-tick spread window");
                drainWindowObserved.set(true);
                drainStartedBudget.compareAndSet(
                        -1, Integer.parseInt(live.get("phase_started")));
            }
            if (CreateObsidianTask.Phase.PICKUP.name().equals(live.get("phase"))
                    && drainStartedBudget.get() >= 0) {
                require(context, Integer.parseInt(live.get("budget_used"))
                                - drainStartedBudget.get() >= 60,
                        "physical pickup resumed before the 60-tick drain window");
                drainWindowCompleted.set(true);
            }
            String lastSeen = live.get("pending_pickup_last_seen_pos");
            if (lastSeen != null && !encode(target).equals(lastSeen)) {
                shiftedLastSeenObserved.set(true);
            }
            if (CreateObsidianTask.Phase.RETURN_TO_RIM.name().equals(live.get("phase"))) {
                rimReturnObserved.set(true);
            }
            fixture.bot().level().getEntitiesOfClass(
                            ItemEntity.class, new AABB(target).inflate(8.0D, 4.0D, 8.0D),
                            entity -> entity.isAlive()
                                    && entity.getItem().is(Items.OBSIDIAN)
                                    && ObservableWorldQuery.canObserveEntity(fixture.bot(), entity))
                    .stream()
                    .min(java.util.Comparator.comparingDouble(
                            entity -> entity.position().distanceToSqr(target.getCenter())))
                    .ifPresent(entity -> {
                        lastObservedDropPosition.set(entity.position());
                        if (CreateObsidianTask.Phase.PROTECT_PICKUP.name()
                                .equals(live.get("phase"))
                                && live.containsKey("water_source")
                                && edgeImpulseInjected.compareAndSet(false, true)) {
                            // Make the old intermittent water-wash failure deterministic: the drop
                            // crosses the east lip and lands three blocks lower, where the platform
                            // occludes it until the durable last-seen route reaches that edge.
                            entity.setDeltaMovement(0.12D, entity.getDeltaMovement().y, 0.0D);
                        }
                    });
            if (obsidian > 0 && !collisionPickupObserved.get()) {
                Vec3 dropPosition = lastObservedDropPosition.get();
                require(context, dropPosition != null
                                && fixture.bot().position().distanceToSqr(dropPosition) <= 4.0D,
                        "final obsidian entered inventory away from the last observed ItemEntity");
                collisionPickupObserved.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, collisionPickupObserved.get() && obsidian == 1,
                    "raw-one settlement did not physically collect the final obsidian");
            require(context, fixture.bot().level().getBlockState(target).isAir(),
                    "final obsidian block was not physically mined");
            require(context, diamond.getMaxDamage() - diamond.getDamageValue() == 1,
                    "final break did not consume raw-2 pick to raw-1");
            require(context, InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 1
                            && InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 0,
                    "pickup protection did not recover the reusable water source");
            require(context, protectedSourceObserved.get() && drainWindowObserved.get()
                            && drainWindowCompleted.get(),
                    "final pickup skipped its protection or drain safety phase");
            require(context, edgeImpulseInjected.get() && shiftedLastSeenObserved.get(),
                    "adversarial water wash never advanced the durable last-seen pickup cell");
            require(context, rimReturnObserved.get()
                            && fixture.bot().blockPosition().equals(fixture.start()),
                    "final completion skipped its durable dry-rim return debt");
            require(context, !task.checkpoint().containsKey("active_break_pos")
                            && !task.checkpoint().containsKey("pending_pickup_pos")
                            && !task.checkpoint().containsKey("pending_pickup_last_seen_pos"),
                    "completed final break retained a transaction debt: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(maxTicks = 40)
    public void unsafePickupEndpointIsRejectedWithoutDiscardingLedger(GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPickupGuardGT", 0, true);
        BlockPos target = fixture.start().offset(2, 0, 1);
        BlockPos[] unsupported = {
                target, target.north(), target.east(), target.south(), target.west()
        };
        for (BlockPos cell : unsupported) {
            fixture.bot().level().setBlock(
                    cell.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        CreateObsidianTask task = new CreateObsidianTask(1,
                pickupCheckpoint(fixture.start(), target));
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_pickup_guard"));

        context.runAtTickTime(12, () -> {
            require(context, task.state() == TaskState.RUNNING,
                    "unsafe pickup endpoint prematurely terminated the transaction: "
                            + task.state() + ":" + task.failureReason());
            require(context, !fixture.bot().blockPosition().equals(target),
                    "pickup guard moved the bot into an unsupported drop cell");
            require(context, fixture.bot().getActionPack().activePathGoal() == null
                            || !fixture.bot().getActionPack().activePathGoal()
                            .equals(fixture.bot().blockPosition()),
                    "pickup guard accepted a path snapped to the current cell");
            require(context, encode(target).equals(task.checkpoint().get("pending_pickup_pos")),
                    "unsafe pickup endpoint discarded the durable ledger: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    /**
     * F5: For an open transaction (active break) plus a missing-tool failure
     * (need_better_tool), replan must physically resupply before resuming. Before the fix,
     * the resume step was inserted ahead of the fresh plan's resupply prefix, so the restored
     * task failed again from the same cause on its very first tick; after three replans with
     * zero progress, the task was terminated outright. After the fix, CRAFT (resupply
     * pickaxe) executes first, then MAKE resumes under the same transaction identity.
     */
    @GameTest(maxTicks = 220)
    public void missingToolFailureWithOpenTransactionResuppliesBeforeResuming(
            GameTestHelper context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianResupplyFirstGT", 0, true);
        // Fill out expedition readiness besides the diamond pickaxe (torch / weapon / sealed
        // log), and pre-stock 3 diamonds so the resupply prefix collapses to a single CRAFT
        // diamond_pickaxe step (avoiding triggering a real dive-and-mine-diamond chain inside
        // the test arena).
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.DIAMOND, 3));
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.TORCH, 64));
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.OAK_LOG, 16));
        var world = fixture.bot().level();
        BlockPos obsidian = fixture.start().east(2);
        BlockPos stand = fixture.start().east();
        world.setBlock(obsidian.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(obsidian, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
        Map<String, String> openTransaction = openBreakCheckpoint(
                fixture.start(), obsidian, stand);

        restore(context, fixture, openTransaction);
        Task restored = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, restored instanceof CreateObsidianTask
                        && restored.state() == TaskState.RUNNING,
                "open-transaction fixture did not restore MAKE_OBSIDIAN first: "
                        + (restored == null ? "none" : restored.getClass().getSimpleName()));
        require(context, InventoryAction.removeItems(fixture.bot(), Items.DIAMOND_PICKAXE, 1),
                "fixture could not remove the diamond pickaxe to inject need_better_tool");
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                fixture.bot(), new Goal.HaveItem(Items.OBSIDIAN, TARGET));
        int firstMake = -1;
        for (int index = 0; index < fresh.steps().size(); index++) {
            if (fresh.steps().get(index).kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                firstMake = index;
                break;
            }
        }
        int makeIndex = firstMake;
        require(context, fresh.success() && makeIndex > 0
                        && fresh.steps().stream().limit(makeIndex).anyMatch(
                        step -> step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.DIAMOND_PICKAXE),
                "missing-tool fixture did not prove a resupply prefix ahead of MAKE_OBSIDIAN: "
                        + fresh.steps() + " unresolved=" + fresh.unresolved());

        AtomicBoolean resupplyObserved = new AtomicBoolean();
        context.failIfEver(() -> {
            require(context, GoalExecutor.INSTANCE.hasActivePlan(fixture.bot())
                            && GoalExecutor.INSTANCE.lastResult(fixture.bot()).isEmpty(),
                    "open-transaction missing-tool failure killed the mission: "
                            + GoalExecutor.INSTANCE.lastResult(fixture.bot())
                            .map(result -> result.reason()).orElse("plan_lost"));
            Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
            if (!resupplyObserved.get()) {
                if (active instanceof CreateObsidianTask || active == null) {
                    return; // wait for the injected need_better_tool failure to trigger a replan.
                }
                // replan has occurred: the resupply step (not resume) is assigned first; the
                // open transaction identity must remain intact in the obsidian.* namespace
                // awaiting resume.
                MissionRuntimeRecord captured =
                        GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
                Map<String, String> checkpoint = captured.active() == null
                        ? Map.of() : captured.active().checkpoint();
                require(context, encode(obsidian).equals(
                                checkpoint.get("obsidian.active_break_pos"))
                                && "32".equals(checkpoint.get("obsidian.target_count")),
                        "resupply-first replan dropped the open obsidian transaction: "
                                + checkpoint);
                resupplyObserved.set(true);
                return;
            }
            if (!(active instanceof CreateObsidianTask)
                    || InventoryAction.countItem(fixture.bot(), Items.DIAMOND_PICKAXE) < 1) {
                // wait for CRAFT to finish resupplying the pickaxe, then MAKE resumes under
                // the original transaction.
                return;
            }
            MissionRuntimeRecord captured =
                    GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
            Map<String, String> checkpoint = captured.active() == null
                    ? Map.of() : captured.active().checkpoint();
            require(context, active.state() == TaskState.RUNNING
                            && "32".equals(checkpoint.get("task.target_count"))
                            && encode(obsidian).equals(
                            checkpoint.get("task.active_break_pos")),
                    "resumed MAKE lost its open-transaction identity after resupply: "
                            + checkpoint);
            finish(context, fixture);
        });
    }

    private static void restore(GameTestHelper context,
                                Fixture fixture,
                                Map<String, String> taskCheckpoint) {
        require(context, ObsidianCheckpoint.decode(taskCheckpoint, TARGET, TARGET_BUDGET).isPresent(),
                "fixture checkpoint must satisfy the production codec");
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        restoreMake(fixture, goal, taskCheckpoint);
        require(context, GoalExecutor.INSTANCE.isActiveGoal(fixture.bot(), goal),
                "restored obsidian goal is not active");
    }

    private static void restoreMake(Fixture fixture,
                                    Goal goal,
                                    Map<String, String> taskCheckpoint) {
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MAKE_OBSIDIAN.name());
        taskCheckpoint.forEach((key, value) -> missionCheckpoint.put("task." + key, value));
        MissionRuntimeRecord runtime = new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false);

        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), runtime);
    }

    private static void restoreService(Fixture fixture,
                                       Map<String, String> serviceCheckpoint,
                                       Map<String, String> obsidianCheckpoint) {
        restoreService(fixture, new Goal.HaveItem(Items.OBSIDIAN, TARGET),
                serviceCheckpoint, obsidianCheckpoint);
    }

    private static void restoreService(Fixture fixture,
                                       Goal goal,
                                       Map<String, String> serviceCheckpoint,
                                       Map<String, String> obsidianCheckpoint) {
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        serviceCheckpoint.forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        obsidianCheckpoint.forEach((key, value) ->
                missionCheckpoint.put("obsidian." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));
    }

    private static Path writeMixedObsidianBlueprint(String name) {
        Path path = FabricLoader.getInstance().getGameDir()
                .resolve("blueprints").resolve(name + ".json");
        List<BlueprintSchema.BlockPlacement> placements = new ArrayList<>();
        placements.add(new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:oak_planks"));
        for (int x = 1; x <= TARGET; x++) {
            placements.add(new BlueprintSchema.BlockPlacement(
                    x, 0, 0, "minecraft:obsidian"));
        }
        BlueprintSchema schema = new BlueprintSchema(
                name, TARGET + 1, 1, 1, List.copyOf(placements), List.of());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new Gson().toJson(schema));
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write mixed obsidian blueprint", exception);
        }
    }

    private static void deleteBlueprint(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete mixed obsidian blueprint", exception);
        }
    }

    private static BlockPos movePreflightReadinessToDepot(GameTestHelper context, Fixture fixture) {
        require(context, InventoryAction.removeItems(fixture.bot(), Items.COOKED_BEEF, 24)
                        && InventoryAction.removeItems(fixture.bot(), Items.STONE_PICKAXE, 4),
                "unknown-target fixture did not remove carried readiness supplies");
        BlockPos depotPos = fixture.start().east(2);
        fixture.bot().level().setBlock(
                depotPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        Container depot = ContainerAction.resolve(fixture.bot(), depotPos).orElseThrow();
        depot.setItem(0, new ItemStack(Items.COOKED_BEEF, 8));
        for (int slot = 1; slot <= 4; slot++) {
            depot.setItem(slot, new ItemStack(Items.STONE_PICKAXE));
        }
        depot.setChanged();
        BotMemoryStore.INSTANCE.of(fixture.bot().getUUID()).markPlace(
                "depot", fixture.bot().level(), depotPos);
        return depotPos.immutable();
    }

    private static Map<String, String> baseMissionCheckpoint(Fixture fixture) {
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(fixture.start()));
        missionCheckpoint.put("started_tick",
                String.valueOf(fixture.bot().level().getServer().getTickCount()));
        missionCheckpoint.put("revision", "0");
        return missionCheckpoint;
    }

    private static void assertRunningMakeObsidian(GameTestHelper context,
                                                   Fixture fixture,
                                                   Map<String, String> expectedCheckpoint) {
        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "first restored task must remain MAKE_OBSIDIAN, got "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, active.state() == TaskState.RUNNING,
                "restored MAKE_OBSIDIAN is not running: " + active.state() + ":" + active.failureReason());
        require(context, !"create_obsidian_invalid_checkpoint".equals(active.failureReason()),
                "valid Mission checkpoint was rejected");

        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        require(context, captured.active() != null, "restored Mission disappeared during capture");
        require(context, fixture.missionId().toString().equals(captured.active().missionId()),
                "restore changed Mission identity");
        require(context, GoalStep.Kind.MAKE_OBSIDIAN.name()
                        .equals(captured.active().checkpoint().get("task_kind")),
                "restore replaced MAKE_OBSIDIAN checkpoint kind: " + captured.active().checkpoint());
        for (Map.Entry<String, String> entry : expectedCheckpoint.entrySet()) {
            String actual = captured.active().checkpoint().get("task." + entry.getKey());
            require(context, entry.getValue().equals(actual),
                    "restore changed task checkpoint key " + entry.getKey()
                            + ": expected=" + entry.getValue() + " actual=" + actual);
        }
    }

    private static void assertTaskCheckpoint(GameTestHelper context,
                                             Fixture fixture,
                                             String key,
                                             String expected) {
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        String actual = captured.active() == null
                ? null : captured.active().checkpoint().get("task." + key);
        require(context, expected.equals(actual),
                "unexpected restored checkpoint " + key + ": expected=" + expected + " actual=" + actual);
    }

    private static Map<String, String> taskCheckpoint(BlockPos origin,
                                                       CreateObsidianTask.Phase phase,
                                                       int collected,
                                                       BlockPos waterSource) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).withProduced(collected).encode());
        values.put("task_schema", "3");
        values.put("target_count", String.valueOf(TARGET));
        values.put("phase", phase.name());
        values.put("scan_resume_face", encode(origin));
        values.put("inventory_baseline", "0");
        values.put("collected", String.valueOf(collected));
        values.put("serviced_collected", String.valueOf(collected / 8 * 8));
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "400");
        values.put("phase_started", "350");
        values.put("last_progress", "390");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", waterSource == null ? "-1" : "1");
        values.put("pending_pickup_inventory", "-1");
        values.put("pickup_gain_budget", "-1");
        values.put("active_break_inventory", "-1");
        values.put("protection_prepared", "false");
        if (waterSource != null) {
            values.put("water_source", encode(waterSource));
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> pickupCheckpoint(BlockPos origin, BlockPos pickupPos) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).encode());
        values.put("task_schema", "3");
        values.put("target_count", "1");
        values.put("phase", CreateObsidianTask.Phase.PICKUP.name());
        values.put("scan_resume_face", encode(origin));
        values.put("inventory_baseline", "0");
        values.put("collected", "0");
        values.put("serviced_collected", "0");
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "20");
        values.put("phase_started", "20");
        values.put("last_progress", "20");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", "-1");
        values.put("pending_pickup_pos", encode(pickupPos));
        values.put("pending_pickup_inventory", "0");
        values.put("pickup_gain_budget", "-1");
        values.put("return_rim", encode(origin));
        values.put("active_break_inventory", "-1");
        values.put("protection_prepared", "false");
        Map<String, String> checkpoint = Map.copyOf(values);
        if (ObsidianCheckpoint.decode(checkpoint, 1, 24000).isEmpty()) {
            throw new IllegalStateException("invalid pickup checkpoint fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Map<String, String> activeBreakCheckpoint(BlockPos origin,
                                                              BlockPos obsidian,
                                                              BlockPos stand) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).encode());
        values.put("task_schema", "3");
        values.put("target_count", "1");
        values.put("phase", CreateObsidianTask.Phase.MINE.name());
        values.put("scan_resume_face", encode(origin));
        values.put("inventory_baseline", "0");
        values.put("collected", "0");
        values.put("serviced_collected", "0");
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "20");
        values.put("phase_started", "20");
        values.put("last_progress", "20");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", "-1");
        values.put("pending_pickup_inventory", "-1");
        values.put("pickup_gain_budget", "-1");
        values.put("active_break_inventory", "0");
        values.put("protection_prepared", "false");
        values.put("obsidian", encode(obsidian));
        values.put("stand", encode(stand));
        values.put("active_break_pos", encode(obsidian));
        Map<String, String> checkpoint = Map.copyOf(values);
        if (ObsidianCheckpoint.decode(checkpoint, 1, 24000).isEmpty()) {
            throw new IllegalStateException("invalid active-break fixture: " + checkpoint);
        }
        return checkpoint;
    }

    /**
     * Open active-break transaction for the original target32 (same shape as
     * activeBreakCheckpoint, but goes through Mission restore).
     */
    private static Map<String, String> openBreakCheckpoint(BlockPos origin,
                                                            BlockPos obsidian,
                                                            BlockPos stand) {
        Map<String, String> values = new LinkedHashMap<>(taskCheckpoint(
                origin, CreateObsidianTask.Phase.MINE, 0, null));
        values.put("obsidian", encode(obsidian));
        values.put("stand", encode(stand));
        values.put("active_break_pos", encode(obsidian));
        values.put("active_break_inventory", "0");
        Map<String, String> checkpoint = Map.copyOf(values);
        if (ObsidianCheckpoint.decode(checkpoint, TARGET, TARGET_BUDGET).isEmpty()) {
            throw new IllegalStateException("invalid open-break fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Map<String, String> pendingBoundaryCheckpoint(BlockPos origin,
                                                                  int collected,
                                                                  int serviced,
                                                                  int pending) {
        Map<String, String> values = new LinkedHashMap<>(taskCheckpoint(
                origin, CreateObsidianTask.Phase.SERVICE_BOUNDARY, collected, null));
        values.put("serviced_collected", String.valueOf(serviced));
        values.put("pending_service_boundary", String.valueOf(pending));
        Map<String, String> checkpoint = Map.copyOf(values);
        if (ObsidianCheckpoint.decode(checkpoint, TARGET, TARGET_BUDGET).isEmpty()) {
            throw new IllegalStateException("invalid service-boundary fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Fixture spawnPreparedBot(GameTestHelper context,
                                            String name,
                                            int obsidian,
                                            boolean hasWaterBucket) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(2, 2, 2));
        // EMPTY_STRUCTURE is 8x8. Keep every owned block inside relative x/z 0..7 so concurrent
        // GameTest batches cannot erase this platform while a long pickup transaction is running.
        for (int dx = -2; dx <= 5; dx++) {
            for (int dz = -2; dz <= 5; dz++) {
                world.setBlock(start.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);

        InventoryAction.giveItem(bot, new ItemStack(Items.OBSIDIAN, obsidian));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 24));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 12));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 40));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(hasWaterBucket ? Items.WATER_BUCKET : Items.BUCKET));
        return new Fixture(name, bot, start.immutable(), UUID.randomUUID());
    }

    private static void finish(GameTestHelper context, Fixture fixture) {
        TaskManager.INSTANCE.cancelIntentTasks(fixture.bot(), "gametest_complete");
        GoalExecutor.INSTANCE.unload(fixture.bot());
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos start, UUID missionId) {
    }
}
