package io.github.zoyluo.minecraftai.task;

import com.mojang.logging.LogUtils;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.pathfinding.MoveType;
import io.github.zoyluo.minecraftai.pathfinding.Node;
import io.github.zoyluo.minecraftai.pathfinding.PathExecutor;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.goal.GoalStep;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.persist.MissionRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/** Strict-survival regression for the stone bootstrap's factual staircase return. */
public final class DigDownReturnGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_full_depth_24_step_stair_returns_within_ordinary_budget", maxTicks = 5_000)
    public void fullDepth24StepStairReturnsWithinOrdinaryBudget(GameTestHelper context) {
        BlockPos relativeOrigin = context.absolutePos(new BlockPos(4, 0, 80));
        BlockPos start = new BlockPos(relativeOrigin.getX(), 24, relativeOrigin.getZ());
        int depth = 24;
        BlockPos cursor = start;
        context.getLevel().setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (int level = 0; level < depth; level++) {
            BlockPos ahead = cursor.north();
            BlockPos landing = ahead.below();
            for (BlockPos breakPos : List.of(ahead, ahead.above(), landing)) {
                context.getLevel().setBlock(breakPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            context.getLevel().setBlock(landing.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            cursor = landing;
        }
        BlockPos expectedDeepestLanding = cursor;
        require(context, start.getY() - expectedDeepestLanding.getY() == depth,
                "full-depth DigDown fixture did not span 24 physical steps");

        String name = "DigDownFullDepthReturnGT";
        // The live 48-item run reached y=1 (23 completed landings) with 71 gross stone:
        // 48 requested delivery plus the 23-layer return reserve. Requiring one more net item
        // raises that factual y=1 threshold to 72, so RETURN cannot begin until the next stair
        // landing has completed and its newly exposed tier has had a vanilla pickup opportunity.
        int requiredNetStone = 49;
        AIPlayerEntity bot = spawn(context, name, start);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        DigDownTask task = new DigDownTask(Blocks.STONE, requiredNetStone);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_full_depth_return"));
        AtomicInteger deepest = new AtomicInteger();
        AtomicInteger maxWorkBudget = new AtomicInteger();
        AtomicInteger maxReturnBudget = new AtomicInteger();
        AtomicBoolean sawReturn = new AtomicBoolean();

        context.failIfEver(() -> {
            deepest.accumulateAndGet(start.getY() - bot.blockPosition().getY(), Math::max);
            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            if (live != null) {
                maxWorkBudget.accumulateAndGet(live.workBudgetUsed(), Math::max);
                maxReturnBudget.accumulateAndGet(live.returnBudgetUsed(), Math::max);
                sawReturn.compareAndSet(false, live.phase() == DigDownTask.Phase.RETURN);
                require(context, live.phase() != DigDownTask.Phase.RETURN || deepest.get() >= depth,
                        "DigDown entered RETURN before completing the 24th physical landing: "
                                + deepest.get() + " checkpoint=" + task.checkpoint());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("24-step DigDown return ended as " + task.state()
                        + ":" + task.failureReason() + " checkpoint=" + task.checkpoint()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, deepest.get() >= depth,
                    "DigDown returned before physically descending 24 steps: " + deepest.get());
            require(context, sawReturn.get() && maxReturnBudget.get() > 0
                            && maxReturnBudget.get() <= 600,
                    "ordinary 24-step return did not fit its 600-tick budget: "
                            + maxReturnBudget.get());
            require(context, maxWorkBudget.get()
                            <= DigDownTask.maxWorkBudgetForTarget("minecraft:stone", requiredNetStone),
                    "full-depth work escaped its derived budget: " + maxWorkBudget.get());
            require(context, bot.blockPosition().equals(start),
                    "24-step DigDown did not return to its exact origin: "
                            + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= requiredNetStone,
                    "full-depth DigDown did not preserve the requested net stone delivery");
            LOGGER.info("DIG_DOWN_FULL_DEPTH_RETURN depth={} work_budget={} return_budget={}",
                    deepest.get(), maxWorkBudget.get(), maxReturnBudget.get());
            finish(context, bot, name);
        });
    }

    @GameTest(maxTicks = 900)
    public void unsupportedNaturalSlopeRotatesToSupportedStoneStair(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(6, 6, -28));
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // Default NORTH is a natural air slope ending above a void: next and next.down have no
        // support. EAST is a conventional mineable stone staircase. The task must rotate instead
        // of retrying the rejected NORTH landing until its no-progress deadline.
        BlockPos northNext = start.north().below();
        world.setBlock(start.north(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(northNext, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(northNext.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (int dx = 1; dx <= 5; dx++) {
            for (int dy = -6; dy <= 0; dy++) {
                world.setBlock(start.offset(dx, dy, 0),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            world.setBlock(start.offset(dx, 1, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(start.offset(dx, 2, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // isViableStairDirection now takes the bot (strict-survival observation is gated on its
        // eye position/profile), so it must be spawned before these fixture assertions run.
        AIPlayerEntity bot = spawn(context, "DigDownRotateGT", start);
        require(context, !DigDownTask.isViableStairDirection(
                        bot, start, net.minecraft.core.Direction.NORTH),
                "fixture's unsupported north stair was accepted");
        require(context, DigDownTask.isViableStairDirection(
                        bot, start, net.minecraft.core.Direction.EAST),
                "fixture's supported east stair was rejected");

        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_rotate_stair"));
        AtomicBoolean usedEastStair = new AtomicBoolean();

        context.failIfEver(() -> {
            if (bot.blockPosition().getX() > start.getX()) {
                usedEastStair.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("rotating DigDown ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, usedEastStair.get(),
                    "DigDown never used the supported alternative staircase");
            require(context, bot.blockPosition().equals(start),
                    "rotating DigDown did not return to its exact origin");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3,
                    "rotating DigDown did not collect the stone quota");
            finish(context, bot, "DigDownRotateGT");
        });
    }

    @GameTest(maxTicks = 900)
    public void movedDescendCheckpointStartsFreshAtCurrentPose(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos oldStart = context.absolutePos(new BlockPos(3, 6, -48));
        List<BlockPos> oldTrail = List.of(
                oldStart,
                oldStart.offset(0, -1, -1),
                oldStart.offset(0, -2, -2));
        BlockPos current = oldStart.offset(12, 0, 0);
        for (int dx = -2; dx <= 5; dx++) {
            for (int dz = -5; dz <= 2; dz++) {
                for (int dy = -7; dy <= -1; dy++) {
                    world.setBlock(current.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(current.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        Map<String, String> stale = new DigDownTask.DigDownCheckpoint(
                1, "minecraft:stone", 3, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                oldStart, oldTrail.getLast().getY(),
                0, 0, 203, 0, 0, 0, 0, false,
                null, 0, oldTrail,
                -1, 0, -20, false).encode();
        AIPlayerEntity bot = spawn(context, "DigDownMovedCheckpointGT", current);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3, stale);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_moved_checkpoint"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("moved-checkpoint DigDown ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.blockPosition().equals(current),
                    "fresh DigDown returned to the stale checkpoint origin: "
                            + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3,
                    "fresh DigDown did not satisfy the stone quota");
            finish(context, bot, "DigDownMovedCheckpointGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_safety_pause_rejoins_trusted_tail_and_fails_only_after_exact_return", maxTicks = 320)
    public void safetyPauseRejoinsTrustedTailAndFailsOnlyAfterExactReturn(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos middle = start.east();
        BlockPos pauseAnchor = middle.east();
        BlockPos displaced = pauseAnchor.east(5);
        BlockPos forbiddenRemoteMine = displaced.north();
        preparePlatform(context, start, 12);
        context.getLevel().setBlock(forbiddenRemoteMine,
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownSafetyPauseGT", middle);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 2));
        DigDownTask task = new DigDownTask(Blocks.STONE, 8,
                descentCheckpoint(start, List.of(start, middle), 8, 2));
        task.start(bot);
        require(context, task.state() == TaskState.RUNNING,
                "fixture DESCEND task did not start: " + task.failureReason());
        BotFixtureMoves.place(bot, pauseAnchor);

        task.pause(bot);
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, task.state() == TaskState.PAUSED && paused != null,
                "pause did not publish a valid checkpoint: " + task.checkpoint());
        require(context, paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "pause did not convert DESCEND into durable safety RETURN: " + task.checkpoint());
        require(context, paused.trail().equals(List.of(start, middle, pauseAnchor)),
                "pause did not settle the adjacent factual cell: " + paused.trail());
        require(context, paused.returnTrailIndex() == paused.trail().size() - 1,
                "paused return would skip the last trusted cell: " + paused.returnTrailIndex());

        for (int step = 1; step <= 5; step++) {
            BlockPos safetyStep = pauseAnchor.east(step);
            BotFixtureMoves.place(bot, safetyStep);
        }
        require(context, bot.blockPosition().equals(displaced),
                "fixture did not end at the non-adjacent safety pose");
        task.resume(bot);

        AtomicBoolean rejoinedPauseAnchor = new AtomicBoolean();
        AtomicBoolean followedOlderTrail = new AtomicBoolean();
        context.failIfEver(() -> {
            BlockPos before = bot.blockPosition();
            if (before.equals(pauseAnchor)) {
                rejoinedPauseAnchor.set(true);
            }
            if (rejoinedPauseAnchor.get() && before.equals(middle)) {
                followedOlderTrail.set(true);
            }

            task.tick(bot);

            BlockPos after = bot.blockPosition();
            if (after.equals(pauseAnchor)) {
                rejoinedPauseAnchor.set(true);
            }
            if (rejoinedPauseAnchor.get() && after.equals(middle)) {
                followedOlderTrail.set(true);
            }
            require(context, context.getLevel().getBlockState(forbiddenRemoteMine).is(Blocks.STONE),
                    "resumed DigDown mined at the displaced safety pose");
            if (!after.equals(start)) {
                require(context, task.state() == TaskState.RUNNING,
                        "interruption failure escaped before exact return: "
                                + task.state() + ":" + task.failureReason());
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }

            require(context, task.state() == TaskState.FAILED,
                    "interrupted shortfall ended as " + task.state());
            require(context, task.failureReason().equals("dig_down_safety_interrupted collected=2"),
                    "interrupted return lost its typed outcome: " + task.failureReason());
            require(context, after.equals(start),
                    "interrupted DigDown failed away from its exact origin: "
                            + after.toShortString());
            require(context, rejoinedPauseAnchor.get(),
                    "return skipped the last trusted pause anchor");
            require(context, followedOlderTrail.get(),
                    "return did not unwind the older factual trail after rejoining");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 2,
                    "return changed the partial physical delivery");
            finish(context, bot, "DigDownSafetyPauseGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_safety_interrupted_goal_replan_quarantines_old_entry_and_relocates_physically", maxTicks = 500)
    public void safetyInterruptedGoalReplanQuarantinesOldEntryAndRelocatesPhysically(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(3, 7, 3));
        BlockPos middle = start.north().below();
        BlockPos tail = middle.north().below();
        BlockPos oldFrontier = tail.north();
        BlockPos nextEntry = start.east(4);

        // Model the already-open factual stair from the evidence run. The only replacement surface
        // column is four blocks east, so the successor must visibly walk there; without quarantine
        // its default NORTH descent immediately steps back into middle and replays this same tunnel.
        for (BlockPos landing : List.of(start, middle, tail)) {
            world.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(landing, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(landing.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            // A stair a bot dug clears ahead and ahead.up over every tread: the head room a hop up the trail needs. The walked
            // return checks it (WalkedStep no_headroom); the old teleport never did.
            world.setBlock(landing.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(oldFrontier, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (int step = 0; step <= 4; step++) {
            BlockPos surface = start.east(step);
            world.setBlock(surface.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(surface, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(surface.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        String name = "DigDownSafetyReplanGT";
        AIPlayerEntity bot = spawn(context, name, tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival");

        Goal goal = new Goal.HaveItem(Items.COBBLESTONE, 8);
        Map<String, String> taskCheckpoint = strictDescentCheckpoint(
                start, List.of(start, middle, tail), 8, 2);
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(start));
        missionCheckpoint.put("started_tick", String.valueOf(bot.level().getServer().getTickCount()));
        missionCheckpoint.put("revision", "0");
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINE.name());
        taskCheckpoint.forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(),
                        MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false));

        Task restored = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, restored instanceof DigDownTask,
                "fixture did not restore the interrupted MINE cursor: "
                        + (restored == null ? "none" : restored.getClass().getSimpleName()));
        DigDownTask first = (DigDownTask) restored;
        require(context, first.state() == TaskState.RUNNING && bot.blockPosition().equals(tail),
                "restored DigDown did not own the exact old trail tail");

        TaskManager.INSTANCE.pauseFor(bot, "gametest_hostile_interrupt");
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(first.checkpoint()).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "pause did not publish the typed safety return debt: " + first.checkpoint());
        require(context, !EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), start, bot.level().getServer().getTickCount()),
                "safety entry TTL started before its exact return debt was paid");
        TaskManager.INSTANCE.resumeFromPause(bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == first
                        && first.state() == TaskState.RUNNING,
                "fixture could not resume the interrupted return owner");

        AtomicReference<DigDownTask> successor = new AtomicReference<>();
        AtomicReference<Integer> replacementArrivalTick = new AtomicReference<>();
        AtomicBoolean observedExactFailedReturn = new AtomicBoolean();
        AtomicBoolean observedPhysicalSurfaceStep = new AtomicBoolean();
        context.failIfEver(() -> {
            if (first.state() == TaskState.FAILED) {
                observedExactFailedReturn.set(true);
                require(context, bot.blockPosition().equals(start)
                                || successor.get() != null,
                        "safety interruption failed away from the exact old entry: "
                                + bot.blockPosition().toShortString());
                require(context, first.failureReason().equals(
                                "dig_down_safety_interrupted collected=2"),
                        "safety return lost its typed reason: " + first.failureReason());
                require(context, EpisodeMemory.INSTANCE.isExcluded(
                                bot.getUUID(), start, bot.level().getServer().getTickCount()),
                        "exact safety settlement did not quarantine the old entry");
            } else {
                require(context, first.state() == TaskState.RUNNING,
                        "original return owner ended unexpectedly as " + first.state());
            }

            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (successor.get() == null && active instanceof DigDownTask next && next != first) {
                require(context, observedExactFailedReturn.get(),
                        "fresh DigDown appeared before the typed return failure settled");
                successor.set(next);
                require(context, next.checkpoint().isEmpty(),
                        "successor opened a mining transaction before physical relocation");
                MissionRuntimeRecord replanned = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, replanned.active() != null
                                && "1".equals(replanned.active().checkpoint().get(
                                "lifetime_replans")),
                        "typed interruption did not spend exactly one real replan");
            }

            DigDownTask next = successor.get();
            if (next == null) {
                return;
            }
            BlockPos current = bot.blockPosition();
            require(context, !current.equals(middle) && !current.equals(tail),
                    "successor re-entered the quarantined factual trail at "
                            + current.toShortString());
            if (current.getY() == start.getY()
                    && current.getZ() == start.getZ()
                    && current.getX() > start.getX()
                    && current.getX() < nextEntry.getX()) {
                observedPhysicalSurfaceStep.set(true);
            }

            Map<String, String> checkpoint = next.checkpoint();
            if (checkpoint.isEmpty()) {
                if (current.equals(nextEntry)) {
                    int now = bot.level().getServer().getTickCount();
                    replacementArrivalTick.compareAndSet(null, now);
                    // Path execution can land after this task's tick, so exactly this arrival
                    // observation may still see the empty relocation cursor. The next observation
                    // must see initializeFreshDescent's exact start_pos anchor.
                    require(context, now == replacementArrivalTick.get(),
                            "successor did not anchor on the tick after replacement arrival");
                    require(context, world.getBlockState(oldFrontier).is(Blocks.STONE),
                            "successor mutated the old tunnel while awaiting replacement anchoring");
                } else {
                    require(context, replacementArrivalTick.get() == null,
                            "successor left the replacement entry before anchoring");
                }
                return;
            }
            require(context, encode(nextEntry).equals(checkpoint.get("start_pos")),
                    "successor anchored anywhere other than the physical replacement entry: "
                            + checkpoint.get("start_pos"));
            require(context, current.equals(nextEntry),
                    "replacement checkpoint appeared before exact arrival: "
                            + current.toShortString());
            require(context, observedPhysicalSurfaceStep.get(),
                    "successor reached the replacement entry without observable surface travel");
            require(context, world.getBlockState(oldFrontier).is(Blocks.STONE),
                    "successor mutated the old tunnel frontier before relocating");
            require(context, GoalExecutor.INSTANCE.isActiveGoal(bot, goal),
                    "remaining cobblestone goal terminated during safe relocation");
            finish(context, bot, name);
        });
    }

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_paused_checkpoint_restart_keeps_old_entry_return_debt", maxTicks = 260)
    public void pausedCheckpointRestartKeepsOldEntryReturnDebt(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(4);
        preparePlatform(context, start, 10);

        AIPlayerEntity bot = spawn(context, "DigDownPausedRestartGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE));
        DigDownTask pausedTask = new DigDownTask(Blocks.STONE, 8,
                descentCheckpoint(start, List.of(start, tail), 8, 1));
        pausedTask.start(bot);
        pausedTask.pause(bot);
        Map<String, String> pausedCheckpoint = pausedTask.checkpoint();
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(pausedCheckpoint).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "paused snapshot did not own durable RETURN debt: " + pausedCheckpoint);
        pausedTask.cancel(bot, "simulate_process_restart");

        for (int step = 1; step <= 4; step++) {
            BotFixtureMoves.place(bot, tail.east(step));
        }
        DigDownTask restoredTask = new DigDownTask(Blocks.STONE, 8, pausedCheckpoint);
        restoredTask.start(bot);
        DigDownTask.DigDownCheckpoint restored = DigDownTask.DigDownCheckpoint
                .decode(restoredTask.checkpoint()).orElse(null);
        require(context, restoredTask.state() == TaskState.RUNNING && restored != null,
                "restarted DigDown rejected the paused checkpoint: "
                        + restoredTask.failureReason());
        require(context, restored.phase() == DigDownTask.Phase.RETURN
                        && restored.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "restart downgraded interrupted RETURN into fresh DESCEND: "
                        + restoredTask.checkpoint());
        require(context, restored.startPos().equals(start)
                        && restored.trail().equals(List.of(start, tail))
                        && restored.returnTrailIndex() == 1,
                "restart lost the old entry/tail return debt: " + restoredTask.checkpoint());

        AtomicBoolean rejoinedTail = new AtomicBoolean();
        context.failIfEver(() -> {
            if (bot.blockPosition().equals(tail)) {
                rejoinedTail.set(true);
            }
            restoredTask.tick(bot);
            if (bot.blockPosition().equals(tail)) {
                rejoinedTail.set(true);
            }
            if (!bot.blockPosition().equals(start)) {
                require(context, restoredTask.state() == TaskState.RUNNING,
                        "restored interruption failed before exact return: "
                                + restoredTask.state() + ":" + restoredTask.failureReason());
            }
            if (restoredTask.state() == TaskState.RUNNING) {
                return;
            }
            require(context, restoredTask.state() == TaskState.FAILED
                            && restoredTask.failureReason().equals(
                            "dig_down_safety_interrupted collected=1"),
                    "restored return lost its typed terminal outcome: "
                            + restoredTask.state() + ":" + restoredTask.failureReason());
            require(context, rejoinedTail.get() && bot.blockPosition().equals(start),
                    "restored task did not repay the old factual trail and exact entry");
            finish(context, bot, "DigDownPausedRestartGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_return_pause_reanchors_the_current_factual_cell", maxTicks = 220)
    public void returnPauseReanchorsTheCurrentFactualCell(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos middle = start.east();
        BlockPos tail = middle.east();
        BlockPos displaced = middle.east(4);
        preparePlatform(context, start, 10);

        AIPlayerEntity bot = spawn(context, "DigDownReturnRepauseGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE));
        Map<String, String> returnDebt = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 8, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                start, start.getY(), 0, 1, 200, 190, 0, 0, 0, false,
                null, 0, List.of(start, middle, tail),
                1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 8, returnDebt);
        task.start(bot);

        AtomicBoolean rejoinedMiddle = new AtomicBoolean();
        // The step onto the next factual cell is walked: phase 0 ticks the task until that landing is verified and the cursor has
        // advanced, then pauses it there, displaces the bot (fixture move) and resumes; phase 1 is the return itself.
        int[] phase = {0};
        int[] waited = {0};
        context.failIfEver(() -> {
            if (phase[0] == 0) {
                require(context, ++waited[0] < 100, "the return never advanced onto the next factual cell: "
                        + bot.blockPosition().toShortString() + " " + task.checkpoint());
                task.tick(bot);
                DigDownTask.DigDownCheckpoint beforePause = DigDownTask.DigDownCheckpoint
                        .decode(task.checkpoint()).orElse(null);
                if (beforePause == null || beforePause.returnTrailIndex() != 0) {
                    return;
                }
                require(context, bot.blockPosition().equals(middle),
                        "fixture did not settle the next factual return cell");
                task.pause(bot);
                DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                        .decode(task.checkpoint()).orElse(null);
                require(context, paused != null
                                && paused.phase() == DigDownTask.Phase.RETURN
                                && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED
                                && paused.returnTrailIndex() == 1,
                        "RETURN pause did not restore the current factual cell as its first waypoint: "
                                + task.checkpoint());

                for (int step = 1; step <= 4; step++) {
                    BotFixtureMoves.place(bot, middle.east(step));
                }
                require(context, bot.blockPosition().equals(displaced),
                        "fixture did not end away from the repaused return trail");
                task.resume(bot);
                phase[0] = 1;
                return;
            }
            if (bot.blockPosition().equals(middle)) {
                rejoinedMiddle.set(true);
            }
            task.tick(bot);
            if (bot.blockPosition().equals(middle)) {
                rejoinedMiddle.set(true);
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && task.failureReason().equals(
                            "dig_down_safety_interrupted collected=1"),
                    "repaused return lost its terminal safety outcome: "
                            + task.state() + ":" + task.failureReason());
            require(context, rejoinedMiddle.get() && bot.blockPosition().equals(start),
                    "repaused return skipped the current factual cell or exact origin");
            finish(context, bot, "DigDownReturnRepauseGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_disconnected_descent_immediately_becomes_safety_return", maxTicks = 40)
    public void disconnectedDescentImmediatelyBecomesSafetyReturn(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(4);
        BlockPos forbiddenRemoteMine = displaced.north();
        preparePlatform(context, start, 10);
        context.getLevel().setBlock(forbiddenRemoteMine,
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownDisconnectedGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE));
        DigDownTask task = new DigDownTask(Blocks.STONE, 8,
                descentCheckpoint(start, List.of(start, tail), 8, 1));
        task.start(bot);
        for (int step = 1; step <= 4; step++) {
            BotFixtureMoves.place(bot, tail.east(step));
        }

        task.tick(bot);
        DigDownTask.DigDownCheckpoint interrupted = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, task.state() == TaskState.RUNNING && interrupted != null,
                "disconnected DESCEND failed before opening return debt: "
                        + task.state() + ":" + task.failureReason());
        require(context, interrupted.phase() == DigDownTask.Phase.RETURN
                        && interrupted.returnOutcome()
                        == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "disconnected DESCEND continued as mining work: " + task.checkpoint());
        require(context, interrupted.trail().equals(List.of(start, tail))
                        && interrupted.returnTrailIndex() == 1,
                "disconnected pose was forged into the factual trail: " + task.checkpoint());
        require(context, context.getLevel().getBlockState(forbiddenRemoteMine).is(Blocks.STONE),
                "disconnected DESCEND mined at the untrusted remote pose");
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DigDownDisconnectedGT");
    }

    @GameTest(maxTicks = 800)
    public void minedStoneReturnsAlongRecordedStaircaseBeforeCompleting(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(6, 5, 8));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -8; dz <= 4; dz++) {
                for (int dy = -8; dy <= -1; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        String name = "DigDownReturnGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        180.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));

        DigDownTask task = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_return"));
        AtomicBoolean descended = new AtomicBoolean();

        context.failIfEver(() -> {
            if (bot.blockPosition().getY() < start.getY()) {
                descended.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("DigDown return task ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, descended.get(), "fixture never exercised a lower staircase cell");
            require(context, bot.blockPosition().equals(start),
                    "DigDown completed away from its exact surface origin: start=" + start.toShortString()
                            + " end=" + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3,
                    "DigDown returned without the requested physical stone drops");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_remembered_walled_entry_physically_relocates_before_mining", maxTicks = 900)
    public void rememberedWalledEntryPhysicallyRelocatesBeforeMining(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos failedEntry = context.absolutePos(new BlockPos(1, 6, 3));
        BlockPos nextEntry = context.absolutePos(new BlockPos(5, 6, 3));
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                for (int y = 1; y <= 5; y++) {
                    world.setBlock(context.absolutePos(new BlockPos(x, y, z)),
                            Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                for (int y = 6; y <= 7; y++) {
                    world.setBlock(context.absolutePos(new BlockPos(x, y, z)),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // Buried water is intentionally unobservable here. Relocation is triggered solely by the
        // prior factual WALLED memory below, never by scanning this hidden block.
        world.setBlock(failedEntry.below(2), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownEntryRelocationGT", failedEntry);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival");
        require(context, !CapabilityRuntime.decide(bot,
                        PrivilegedCapability.EMERGENCY_TELEPORT,
                        "dig_down_entry_relocation_gametest").allowed(),
                "strict GameTest unexpectedly allowed emergency teleport");
        EpisodeMemory.INSTANCE.exclude(bot.getUUID(), failedEntry,
                bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        require(context, EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), failedEntry, bot.level().getServer().getTickCount()),
                "fixture did not remember the observed WALLED entry");

        DigDownTask task = new DigDownTask(Blocks.STONE, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_entry_relocation"));
        require(context, bot.blockPosition().equals(failedEntry),
                "DigDown teleported while starting entry relocation");
        require(context, task.checkpoint().isEmpty(),
                "entry relocation started a mining transaction before arrival");

        AtomicBoolean observedPhysicalStep = new AtomicBoolean();
        AtomicBoolean reachedNextEntry = new AtomicBoolean();
        context.failIfEver(() -> {
            BlockPos current = bot.blockPosition();
            if (current.getY() == failedEntry.getY()
                    && current.getX() > failedEntry.getX()
                    && current.getX() < nextEntry.getX()
                    && current.getZ() == failedEntry.getZ()) {
                observedPhysicalStep.set(true);
            }
            if (current.equals(nextEntry)) {
                reachedNextEntry.set(true);
            }
            if (!reachedNextEntry.get()) {
                require(context, task.checkpoint().isEmpty(),
                        "mining checkpoint appeared before reaching the replacement entry");
            }
            if (!task.checkpoint().isEmpty()) {
                require(context, encode(nextEntry).equals(task.checkpoint().get("start_pos")),
                        "mining transaction anchored somewhere other than the replacement entry");
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("entry relocation DigDown ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, observedPhysicalStep.get(),
                    "DigDown reached the replacement entry without observable surface movement");
            require(context, reachedNextEntry.get(),
                    "DigDown never reached the replacement entry");
            require(context, bot.blockPosition().equals(nextEntry),
                    "DigDown did not return exactly to its replacement entry: "
                            + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 1,
                    "DigDown returned without a physical stone drop");
            require(context, world.getBlockState(failedEntry.below(2)).is(Blocks.WATER),
                    "DigDown inspected by mutating the hidden fixture block");
            finish(context, bot, "DigDownEntryRelocationGT");
        });
    }

    @GameTest(maxTicks = 300)
    public void satisfiedMissionStillRestoresReturnDebtBeforeCompleting(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 8);
        BlockPos far = start.offset(4, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownMissionReturnGT", far);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));

        List<BlockPos> trail = List.of(start, start.offset(1, 0, 0), start.offset(2, 0, 0),
                start.offset(3, 0, 0), far);
        Map<String, String> taskCheckpoint = returnCheckpoint(start, trail, 3, 0, false);
        Goal goal = new Goal.HaveItem(Items.COBBLESTONE, 3);
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(start));
        missionCheckpoint.put("started_tick", String.valueOf(bot.level().getServer().getTickCount()));
        missionCheckpoint.put("revision", "0");
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINE.name());
        taskCheckpoint.forEach((key, value) -> missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(), MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof DigDownTask,
                "satisfied restore skipped return debt: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, active.state() == TaskState.RUNNING,
                "restored DigDown did not start: " + active.state() + ":" + active.failureReason());
        require(context, !bot.blockPosition().equals(start), "fixture did not start away from the origin");

        context.failIfEver(() -> {
            if (active.state() == TaskState.FAILED || active.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("restored return failed: " + active.failureReason()));
            }
            if (GoalExecutor.INSTANCE.isActiveGoal(bot, goal)) {
                require(context, active.state() != TaskState.COMPLETED || bot.blockPosition().equals(start),
                        "same-Y return published completion away from start");
                return;
            }
            require(context, bot.blockPosition().equals(start),
                    "Mission settled before exact return: " + bot.blockPosition().toShortString());
            finish(context, bot, "DigDownMissionReturnGT");
        });
    }

    @GameTest(maxTicks = 80)
    public void exhaustedReturnBudgetFailsOnItsOwnClock(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 5);
        BlockPos far = start.offset(2, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownBudgetGT", far);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        List<BlockPos> trail = List.of(start, start.offset(1, 0, 0), far);
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, trail, 1, 600, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_return_budget"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "exhausted return budget ended as " + task.state());
            require(context, task.failureReason().startsWith("dig_down_return_failed"),
                    "unexpected return budget reason: " + task.failureReason());
            require(context, bot.blockPosition().equals(far),
                    "budget exhaustion moved the bot before failing");
            finish(context, bot, "DigDownBudgetGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_safety_displacement_can_dig_back_after_legacy_return_limit", maxTicks = 2600)
    public void safetyDisplacementCanDigBackAfterLegacyReturnLimit(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 24, 3));
        List<BlockPos> trail = new java.util.ArrayList<>();
        for (int step = 0; step <= 18; step++) {
            BlockPos feet = start.offset(step, -step, 0);
            trail.add(feet);
            context.getLevel().setBlock(feet.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(feet,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(feet.above(),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos tail = trail.getLast();
        int displacement = 16;
        BlockPos displaced = tail.south(displacement);
        for (int step = 1; step <= displacement; step++) {
            BlockPos corridor = tail.south(step);
            context.getLevel().setBlock(corridor.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(corridor,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(corridor.above(),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(corridor.above(2),
                    Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DigDownSafetyRecoveryGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask interrupted = new DigDownTask(Blocks.STONE, 3,
                descentCheckpoint(start, trail, 3, 3));
        interrupted.start(bot);
        require(context, interrupted.state() == TaskState.RUNNING,
                "fixture DESCEND task did not start: " + interrupted.failureReason());
        interrupted.pause(bot);
        Map<String, String> rawPausedCheckpoint = interrupted.checkpoint();
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(rawPausedCheckpoint).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED
                        && paused.returnTrailIndex() == trail.size() - 1
                        && paused.returnBudgetUsed() == 0
                        && paused.lastReturnProgressBudget() == 0
                        && paused.returnBestDistanceSquared() == 0L
                        && paused.returnSafetyRecovery(),
                "pause did not publish the raw pre-displacement safety checkpoint: "
                        + rawPausedCheckpoint);

        for (int step = 1; step <= displacement; step++) {
            BotFixtureMoves.place(bot, tail.south(step));
        }
        require(context, bot.blockPosition().equals(displaced),
                "fixture did not end at the displaced restart pose");
        for (int step = 1; step < displacement; step++) {
            BlockPos wall = tail.south(step);
            context.getLevel().setBlock(wall,
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(wall.above(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        interrupted.cancel(bot, "simulate_paused_process_restart");

        DigDownTask task = new DigDownTask(Blocks.STONE, 3, rawPausedCheckpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_safety_recovery"));
        DigDownTask.DigDownCheckpoint restored = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, restored != null
                        && restored.returnBudgetUsed() == 0
                        && restored.lastReturnProgressBudget() == 0
                        && restored.returnProgressWaypointIndex() == trail.size() - 1
                        && restored.returnBestDistanceSquared() == squaredDistance(displaced, tail),
                "restart did not rebase only the physical return distance: " + task.checkpoint());
        AtomicBoolean crossedLegacyLimit = new AtomicBoolean();
        AtomicBoolean crossedLegacyLimitBeforeRejoin = new AtomicBoolean();
        AtomicBoolean physicallyDugRejoin = new AtomicBoolean();

        context.failIfEver(() -> {
            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            if (live != null && live.returnBudgetUsed() > 600) {
                crossedLegacyLimit.set(true);
                if (!bot.blockPosition().equals(tail)) {
                    crossedLegacyLimitBeforeRejoin.set(true);
                }
            }
            if (context.getLevel().getBlockState(tail.south(1)).isAir()
                    || context.getLevel().getBlockState(tail.south(1).above()).isAir()) {
                physicallyDugRejoin.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("safety-expanded return ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, crossedLegacyLimit.get(),
                    "safety recovery never crossed the old 600-tick return boundary");
            require(context, crossedLegacyLimitBeforeRejoin.get(),
                    "safety recovery crossed 600 ticks only after rejoining the factual tail");
            require(context, physicallyDugRejoin.get(),
                    "safety recovery did not physically dig through the displaced rejoin wall");
            require(context, bot.blockPosition().equals(start),
                    "safety recovery completed away from the exact mine entry: "
                            + bot.blockPosition().toShortString());
            finish(context, bot, "DigDownSafetyRecoveryGT");
        });
    }

    @GameTest(maxTicks = 40)
    public void safetyReturnHardCapSurvivesPauseAndResume(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(6);
        preparePlatform(context, start, 12);
        AIPlayerEntity bot = spawn(context, "DigDownSafetyHardCapGT", displaced);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                safetyReturnCheckpoint(start, List.of(start, tail), 1,
                        2399, 2399, squaredDistance(displaced, tail)));
        task.start(bot);
        task.pause(bot);
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, paused != null
                        && paused.returnBudgetUsed() == 2399
                        && paused.lastReturnProgressBudget() == 2399
                        && paused.returnSafetyRecovery(),
                "pause did not persist a fresh stall lease without resetting the hard clock");
        task.resume(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "safety return rejected its inclusive 2400-tick boundary");
        DigDownTask.DigDownCheckpoint atBoundary = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, atBoundary != null && atBoundary.returnBudgetUsed() == 2400,
                "pause/resume reset or corrupted the persisted return hard clock");

        task.pause(bot);
        task.resume(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith(
                        "dig_down_return_failed:hard_limit"),
                "safety return did not fail closed beyond 2400 ticks: "
                        + task.state() + ":" + task.failureReason());
        require(context, !bot.blockPosition().equals(start),
                "hard-cap fixture unexpectedly settled the exact return debt");
        finish(context, bot, "DigDownSafetyHardCapGT");
    }

    @GameTest(maxTicks = 40)
    public void safetyReturnStallLeaseFailsBeforeHardCap(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(6);
        preparePlatform(context, start, 12);
        AIPlayerEntity bot = spawn(context, "DigDownSafetyStallGT", displaced);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                safetyReturnCheckpoint(start, List.of(start, tail), 1,
                        600, 0, 0L));
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("dig_down_return_failed:stalled"),
                "stalled safety return escaped its 600-tick no-progress lease: "
                        + task.state() + ":" + task.failureReason());
        require(context, !bot.blockPosition().equals(start),
                "stall fixture unexpectedly settled the exact return debt");
        finish(context, bot, "DigDownSafetyStallGT");
    }

    @GameTest(maxTicks = 240)
    public void timeoutWithRequestedNetDeliveryCompletesOnlyAfterExactReturn(GameTestHelper context) {
        BlockPos bottom = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos middle = bottom.east().above();
        BlockPos start = middle.east().above();
        for (BlockPos feet : List.of(bottom, middle, start)) {
            context.getLevel().setBlock(feet.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(feet,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(feet.above(),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerEntity bot = spawn(context, "DigDownTimeoutNetGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 6));
        List<BlockPos> trail = List.of(start, middle, bottom);
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 6, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.TIMEOUT,
                start, bottom.getY(), 0, 6, 2400, 2399, 0, 0, 0, false,
                null, 0, trail, 1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 6, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_timeout_net_return"));
        java.util.concurrent.atomic.AtomicBoolean moved =
                new java.util.concurrent.atomic.AtomicBoolean();

        context.failIfEver(() -> {
            if (!bot.blockPosition().equals(bottom)) {
                moved.set(true);
            }
            if (!bot.blockPosition().equals(start)) {
                require(context, task.state() == TaskState.RUNNING,
                        "net delivery forgave the outstanding exact-return debt: "
                                + task.state() + ":" + task.failureReason());
                return;
            }
            // GameTest callbacks can observe the physical micro-step before TaskManager's next
            // tick settles the exact-return terminal state.
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "exact return did not promote satisfied timeout to completion: "
                            + task.state() + ":" + task.failureReason());
            require(context, moved.get(), "fixture never exercised a physical return step");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 6,
                    "completion lost the requested net stone delivery");
            finish(context, bot, "DigDownTimeoutNetGT");
        });
    }

    @GameTest(maxTicks = 40)
    public void restoredLargeQuotaContinuesPastLegacyBudgetAndKeepsNetDeliveryStrict(
            GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 4, 3));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= -1; dy++) {
                    context.getLevel().setBlock(start.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    context.getLevel().setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawn(context, "DigDownScaledRestoreGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 61));
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 64, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 61, 2400, 2400, 0, 0, 0, false,
                null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 64, checkpoint);
        task.start(bot);

        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "restored 64-block batch timed out at the legacy 2400-tick boundary: "
                        + task.failureReason());
        Map<String, String> resumed = task.checkpoint();
        require(context, "2401".equals(resumed.get("work_budget_used"))
                        && DigDownTask.Phase.DESCEND.name().equals(resumed.get("phase")),
                "continued large-batch checkpoint was not durable past tick 2400: " + resumed);

        // The restored batch still owns an incremental 64-block delivery. Only the remaining
        // physical inventory gain may complete it; scaling the clock must not forgive 61/64.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "net delivery completed before the exact-return phase was settled");
        task.tick(bot);
        require(context, task.state() == TaskState.COMPLETED,
                "restored large batch did not complete after exact 64/64 delivery and return: "
                        + task.failureReason());
        require(context, bot.blockPosition().equals(start),
                "restored large batch completed away from its exact entry");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 64,
                "restored large batch changed the strict net-delivery target");
        finish(context, bot, "DigDownScaledRestoreGT");
    }

    // The serialized live run completed the 12-cell out-and-back corridor plus its closed-frontier settlement in 191 ticks.
    // 240 leaves seven additional 7-tick owned step/settle turns (49 ticks). The remaining supported-frontier proof has one stone break,
    // pickup settlement and exactly two flat walked legs (into the frontier and back), so its separate 60-tick allowance
    // leaves 46 ticks beyond the two ordinary 7-tick walk/settle legs. The outer limit is their sum, not a second loose timer.
    private static final int HORIZONTAL_CORRIDOR_TICK_CAP = 240;
    private static final int HORIZONTAL_FRONTIER_TICK_ALLOWANCE = 60;

    @GameTest(maxTicks = HORIZONTAL_CORRIDOR_TICK_CAP + HORIZONTAL_FRONTIER_TICK_ALLOWANCE)
    public void horizontalOpenCorridorAdvancesFactuallyAndNeverBacktracks(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(6, 5, 18));
        int openCells = 12;
        for (int distance = 0; distance <= openCells; distance++) {
            BlockPos feet = start.north(distance);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerEntity bot = spawn(context, "DigDownHorizontalCorridorGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 1, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 0, 0, 0, 0, 0, 0, true,
                null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 1, checkpoint);
        task.start(bot);

        // Every advance is a walked step now: one cell takes several game ticks, and the trail grows only on the tick the landing
        // is verified. The corridor is driven tick by tick: phase 0 advances cell by cell, phase 1 waits out the closed frontier's
        // pickup settlement without backtracking, phase 2 walks the recorded trail back.
        int[] phase = {0};
        int[] cells = {0};
        int[] ticks = {0};
        // Pickup settlement: the first tick after the last corridor cell must still be DESCEND with the full trail (the old
        // per-call test required exactly that), and DESCEND has to be observed before RETURN ever appears.
        boolean[] settleStarted = {false};
        boolean[] sawDescend = {false};
        Runnable[] frontierPart = new Runnable[1];
        context.failIfEver(() -> {
            if (frontierPart[0] != null) {
                frontierPart[0].run();
                return;
            }
            ticks[0]++;
            require(context, ticks[0] <= HORIZONTAL_CORRIDOR_TICK_CAP, "horizontal corridor timed out in phase " + phase[0]
                    + " at " + bot.blockPosition().toShortString());
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            switch (phase[0]) {
                case 0 -> {
                    require(context, task.state() == TaskState.RUNNING,
                            "horizontal corridor task ended before the first solid boundary: "
                                    + task.state() + ":" + task.failureReason());
                    require(context, live != null && live.trail().size() <= cells[0] + 2,
                            "horizontal corridor appended more than one cell at a time: " + task.checkpoint());
                    if (live != null && live.trail().size() == cells[0] + 2) {
                        cells[0]++;
                        BlockPos expected = start.north(cells[0]);
                        require(context, bot.blockPosition().equals(expected),
                                "horizontal corridor recorded a cell the bot was not standing in on step "
                                        + cells[0] + ": expected=" + expected.toShortString()
                                        + " actual=" + bot.blockPosition().toShortString());
                        require(context, live.trail().getLast().equals(expected),
                                "horizontal corridor step was not durably appended to the return trail: "
                                        + task.checkpoint());
                        if (cells[0] == openCells) {
                            phase[0] = 1;
                        }
                    }
                }
                case 1 -> {
                    // NORTH/EAST/WEST beyond the endpoint are unsupported; SOUTH is the already recorded
                    // corridor. A mining frontier must not reinterpret that return trail as fresh work.
                    require(context, bot.blockPosition().equals(start.north(openCells)),
                            "horizontal endpoint backtracked into its already recorded return trail");
                    require(context, task.state() == TaskState.RUNNING,
                            "horizontal endpoint exposed failure during its physical pickup settle tick");
                    require(context, live != null && live.trail().size() == openCells + 1,
                            "horizontal endpoint changed its trail while settling: " + task.checkpoint());
                    if (!settleStarted[0]) {
                        settleStarted[0] = true;
                        require(context, live.phase() == DigDownTask.Phase.DESCEND,
                                "horizontal endpoint did not preserve DESCEND during pickup settlement: " + task.checkpoint());
                    }
                    if (live.phase() == DigDownTask.Phase.DESCEND) {
                        sawDescend[0] = true;
                    }
                    if (live.phase() == DigDownTask.Phase.RETURN) {
                        require(context, sawDescend[0],
                                "closed horizontal frontier went to RETURN without DESCEND being observed with the full trail: "
                                        + task.checkpoint());
                        require(context, live.returnOutcome() == DigDownTask.ReturnOutcome.WALLED,
                                "closed horizontal frontier did not preserve a typed exact-return debt: "
                                        + task.checkpoint());
                        phase[0] = 2;
                    }
                }
                default -> {
                    if (task.state() == TaskState.RUNNING) {
                        return;
                    }
                    require(context, task.state() == TaskState.FAILED
                                    && task.failureReason().startsWith("dig_down_walled"),
                            "closed horizontal frontier did not settle as WALLED after exact return: "
                                    + task.state() + ":" + task.failureReason());
                    require(context, bot.blockPosition().equals(start),
                            "closed horizontal frontier failed away from its exact origin: "
                                    + bot.blockPosition().toShortString());
                    LOGGER.info("DIG_DOWN_HORIZONTAL_OPEN_CORRIDOR elapsed_ticks={} internal_tick_cap={} outer_tick_cap={} work_budget_used={}",
                            ticks[0], HORIZONTAL_CORRIDOR_TICK_CAP,
                            HORIZONTAL_CORRIDOR_TICK_CAP + HORIZONTAL_FRONTIER_TICK_ALLOWANCE,
                            task.checkpoint().getOrDefault("work_budget_used", "not_recorded"));
                    AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "DigDownHorizontalCorridorGT");
                    frontierPart[0] = horizontalFrontierPart(context);
                }
            }
        });
    }

    /** The second half of the corridor test: a supported horizontal frontier is mined, its drop collected and the exact entry regained. */
    private static Runnable horizontalFrontierPart(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos frontierStart = context.absolutePos(new BlockPos(8, 5, 8));
        BlockPos frontier = frontierStart.north();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    world.setBlock(frontierStart.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(frontierStart.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(frontier.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(frontier, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity frontierBot = spawn(
                context, "DigDownHorizontalFrontierGT", frontierStart);
        InventoryAction.giveItem(frontierBot, new ItemStack(Items.WOODEN_PICKAXE));
        Map<String, String> frontierCheckpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 1, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                frontierStart, frontierStart.getY(), 0, 0, 0, 0, 0, 0, 0, true,
                null, 0, List.of(frontierStart), -1, 0, -20, false).encode();
        DigDownTask frontierTask = new DigDownTask(
                Blocks.STONE, 1, frontierCheckpoint);
        frontierTask.start(frontierBot);
        AtomicBoolean frontierBroken = new AtomicBoolean();

        return () -> {
            if (frontierTask.state() == TaskState.RUNNING) {
                frontierTask.tick(frontierBot);
            }
            if (world.getBlockState(frontier).isAir()) {
                frontierBroken.set(true);
            }
            if (frontierTask.state() == TaskState.FAILED
                    || frontierTask.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("supported horizontal frontier ended as "
                        + frontierTask.state() + ":" + frontierTask.failureReason()
                        + " checkpoint=" + frontierTask.checkpoint()));
            }
            if (frontierTask.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, frontierBroken.get(),
                    "horizontal task completed without physically mining the stone frontier");
            require(context, InventoryAction.countItem(frontierBot, Items.COBBLESTONE) == 1,
                    "horizontal task did not physically collect its exact stone delivery");
            require(context, frontierBot.blockPosition().equals(frontierStart),
                    "horizontal stone delivery completed away from its exact origin");
            AIPlayerManager.INSTANCE.despawn(
                    frontierBot.level().getServer(), "DigDownHorizontalFrontierGT");
            context.succeed();
        };
    }

    @GameTest(maxTicks = 180)
    public void nearBudgetHorizontalPickupDebtSurvivesRestartAndSettlesBeforeTimeout(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(6, 5, 6));
        BlockPos frontier = start.north();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -3; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(frontier.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(frontier, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownNearBudgetSettleGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERITE_PICKAXE));
        int maxBudget = DigDownTask.maxWorkBudgetForTarget("minecraft:stone", 1);
        // The debt is armed within about a dozen ticks of the fixture start (a break, then the walked step into the mined cell);
        // twenty ticks of headroom keep the hard boundary after the arming and inside the pickup window, as the old
        // eight covered a break followed by an instant teleport.
        Map<String, String> nearBudget = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 1, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 0, maxBudget - 20, maxBudget - 20,
                0, 0, 0, true, null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask initial = new DigDownTask(Blocks.STONE, 1, nearBudget);
        initial.start(bot);

        AtomicReference<DigDownTask> active = new AtomicReference<>(initial);
        AtomicReference<ItemEntity> delayedDrop = new AtomicReference<>();
        AtomicBoolean pickupReleased = new AtomicBoolean();
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicBoolean crossedHardBoundary = new AtomicBoolean();
        AtomicBoolean frontierBroken = new AtomicBoolean();

        context.failIfEver(() -> {
            DigDownTask task = active.get();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (world.getBlockState(frontier).isAir()) {
                frontierBroken.set(true);
            }
            if (!pickupReleased.get() && delayedDrop.get() == null) {
                world.getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(4.0D),
                                drop -> drop.isAlive() && drop.getItem().is(Items.COBBLESTONE))
                        .stream().findFirst().ifPresent(drop -> {
                            drop.setNeverPickUp();
                            // The drop stays in the frontier cell it was mined from. Vanilla pops it with a random sideways velocity
                            // (up to about a block of travel) and this frontier's floor is one cell wide with open pits beside it: a drop
                            // that rolled over the edge fell five blocks, out of every reach (seen: drops_within_16 one block east, five
                            // down), and the task rightly settled as WALLED. The fixture is about the pickup debt, not about a lost drop,
                            // so it takes the roll out of the pop and leaves the geometry the task decides on untouched.
                            drop.setDeltaMovement(0.0D, drop.getDeltaMovement().y, 0.0D);
                            delayedDrop.set(drop);
                        });
            }

            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            if (!restarted.get() && live != null
                    && live.phase() == DigDownTask.Phase.DESCEND
                    && live.pickupGrace() > 0) {
                require(context, live.horizontalMode(),
                        "pickup settlement debt was not tied to horizontal mode");
                require(context, live.workBudgetUsed() <= maxBudget
                                && live.workBudgetUsed() >= maxBudget - 30,
                        "settlement debt was not durably bounded near the hard budget: "
                                + task.checkpoint());
                Map<String, String> saved = task.checkpoint();
                task.abort(bot);
                DigDownTask restored = new DigDownTask(Blocks.STONE, 1, saved);
                restored.start(bot);
                active.set(restored);
                restarted.set(true);
                return;
            }

            if (restarted.get() && task == active.get() && live != null
                    && live.phase() == DigDownTask.Phase.DESCEND
                    && live.pickupGrace() > 0
                    && live.workBudgetUsed() == maxBudget) {
                crossedHardBoundary.set(true);
                ItemEntity held = delayedDrop.getAndSet(null);
                if (held != null && held.isAlive()) {
                    held.setNoPickUpDelay();
                }
                pickupReleased.set(true);
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                List<ItemEntity> drops = world.getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(4.0D),
                        drop -> drop.isAlive() && drop.getItem().is(Items.COBBLESTONE));
                context.fail(Component.nullToEmpty("near-budget settlement ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint() + " restarted=" + restarted.get()
                        + " released=" + pickupReleased.get() + " drops_near=" + drops.stream()
                                .map(drop -> String.format(java.util.Locale.ROOT, "%.2f@%s", drop.distanceTo(bot),
                                        drop.blockPosition().toShortString()))
                                .toList()
                        + " drops_within_16=" + world.getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(16.0D),
                                        drop -> drop.isAlive() && drop.getItem().is(Items.COBBLESTONE)).stream()
                                .map(drop -> drop.position().toString()).toList()
                        + " bot=" + bot.position()));
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get(),
                    "fixture never restarted an active horizontal settlement debt");
            require(context, crossedHardBoundary.get(),
                    "restored settlement did not remain live at the hard work boundary");
            require(context, frontierBroken.get(),
                    "near-budget task completed without physically breaking the frontier");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 1,
                    "settlement did not deliver the exact physical cobblestone drop");
            require(context, bot.blockPosition().equals(start),
                    "settled batch completed away from its exact entry: "
                            + bot.blockPosition().toShortString());
            finish(context, bot, "DigDownNearBudgetSettleGT");
        });
    }

    @GameTest(maxTicks = 240)
    public void unsafeRecordedLandingIsSkippedAndReturnStillCompletes(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 5);
        BlockPos unsafe = start.offset(1, 0, 0);
        BlockPos end = start.offset(2, 0, 0);
        context.getLevel().setBlock(unsafe.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawn(context, "DigDownUnsafeReturnGT", end);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, List.of(start, unsafe, end), 1, 0, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_unsafe_return"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("unsafe return failed: "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, !bot.blockPosition().equals(unsafe),
                    "return completed on an unsupported landing");
            require(context, bot.blockPosition().equals(start),
                    "return skipped the stale waypoint but did not settle exact origin: "
                            + bot.blockPosition().toShortString());
            finish(context, bot, "DigDownUnsafeReturnGT");
        });
    }

    @GameTest(maxTicks = 240)
    public void unsupportedAscendingWaypointGetsPhysicalSupportBeforeExactReturn(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(6, 6, 6));
        BlockPos unsupported = start.west();
        BlockPos bottom = unsupported.west().below();
        for (BlockPos body : List.of(start, start.above(), unsupported, unsupported.above(),
                bottom, bottom.above())) {
            context.getLevel().setBlock(body, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        context.getLevel().setBlock(bottom.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // The ascending intermediate loses its Y-1 support. A deeper solid remains available as
        // the vanilla placement face for the single adjacent repair optimization.
        context.getLevel().setBlock(unsupported.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(unsupported.below(2),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownSupportRepairGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, List.of(start, unsupported, bottom), 1, 0, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_support_repair"));

        AtomicBoolean repaired = new AtomicBoolean();
        context.failIfEver(() -> {
            if (!context.getLevel().getBlockState(unsupported.below()).isAir()) {
                repaired.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("support-repair return failed: "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, repaired.get(),
                    "DigDown skipped the unsupported ascending waypoint instead of repairing it");
            require(context, bot.blockPosition().equals(start),
                    "support-repair return did not settle the exact origin: "
                            + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 3,
                    "support repair did not pay exactly one physical cobblestone");
            finish(context, bot, "DigDownSupportRepairGT");
        });
    }

    @GameTest(maxTicks = 320)
    public void unsupportedExactEntryUsesTwoPhysicalPillarsInsteadOfSnapping(GameTestHelper context) {
        BlockPos bottom = context.absolutePos(new BlockPos(6, 4, 6));
        BlockPos middle = bottom.above();
        BlockPos start = bottom.above(2);
        for (int dy = 0; dy <= 3; dy++) {
            context.getLevel().setBlock(bottom.above(dy),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        context.getLevel().setBlock(bottom.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownExactPillarsGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, List.of(start, middle, bottom), 1, 0, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_exact_pillars"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("two-pillar exact return failed: "
                        + task.state() + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.blockPosition().equals(start),
                    "two-pillar return snapped below the exact entry: "
                            + bot.blockPosition().toShortString());
            require(context, context.getLevel().getBlockState(bottom).is(Blocks.DIRT)
                            && context.getLevel().getBlockState(middle).is(Blocks.DIRT),
                    "exact return did not place both physical dirt pillars");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                            "exact return did not spend exactly two dirt supports");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 3,
                    "pillar repair consumed the requested net cobblestone delivery");
            finish(context, bot, "DigDownExactPillarsGT");
        });
    }

    @GameTest(maxTicks = 160)
    public void restoredDescentFailureReturnsBeforePublishingFailure(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 5);
        BlockPos middle = start.offset(1, 0, 0);
        BlockPos end = start.offset(2, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownFailureReturnGT", end);
        List<BlockPos> trail = List.of(start, middle, end);
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 8, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.TIMEOUT,
                start, start.getY(), 0, 2, 2400, 2399, 0, 0, 0, false,
                null, 0, trail, 1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 8, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_failure_return"));

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                require(context, !task.failureReason().startsWith("dig_down_timeout"),
                        "failure leaked before exact return completed");
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "failure recovery ended as " + task.state());
            require(context, task.failureReason().equals("dig_down_timeout collected=2"),
                    "failure recovery lost its typed terminal outcome: " + task.failureReason());
            require(context, bot.blockPosition().equals(start),
                    "failure was published below/away from the original entry: "
                            + bot.blockPosition().toShortString());
            finish(context, bot, "DigDownFailureReturnGT");
        });
    }

    @GameTest(maxTicks = 40)
    public void promotedSchema2ReturnClearsLocalWaterSealOwnership(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 3);
        BlockPos end = start.offset(1, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownPromotedReturnGT", end);
        List<BlockPos> trail = List.of(start, end);
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                2, "minecraft:stone", 3, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 3, 200, 190, 0, 0, 0, false,
                end, 1, trail, -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 3, checkpoint);
        task.start(bot);

        Map<String, String> promoted = task.checkpoint();
        DigDownTask.DigDownCheckpoint decoded =
                DigDownTask.DigDownCheckpoint.decode(promoted).orElse(null);
        require(context, decoded != null && decoded.phase() == DigDownTask.Phase.RETURN,
                "satisfied DESCEND checkpoint was not promoted to a durable return: " + promoted);
        require(context, decoded.rejectedLandingOrigin() == null
                        && decoded.rejectedLandingDirections() == 0,
                "promoted return retained stale water-seal direction ownership: " + promoted);
        task.cancel(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "DigDownPromotedReturnGT");
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void missingMineCheckpointIsRejectedEvenWhenGoalIsSatisfied(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownMissingCheckpointGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        Goal goal = new Goal.HaveItem(Items.COBBLESTONE, 3);
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(start));
        missionCheckpoint.put("started_tick", String.valueOf(bot.level().getServer().getTickCount()));
        missionCheckpoint.put("revision", "0");
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINE.name());

        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(),
                        MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false));
        GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        require(context, result != null
                        && "mission_restore_invalid_dig_down_checkpoint".equals(result.reason()),
                "missing MINE cursor bypassed fail-closed restore: "
                        + (result == null ? "no_result" : result.reason()));
        require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                "missing MINE cursor restored an active mission");
        finish(context, bot, "DigDownMissingCheckpointGT");
    }

    @GameTest(maxTicks = 40)
    public void exactReturnRejectsGrossCollectionWithNetDeliveryShortfall(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownNetDeliveryGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 2));
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 7, 200, 190, 0, 0, 0, false,
                null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 3, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED,
                "gross collection incorrectly completed with only two delivered blocks");
        require(context, task.failureReason().equals(
                        "dig_down_net_delivery_shortfall:have=2:required=3"),
                "wrong net-delivery failure: " + task.failureReason());
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "DigDownNetDeliveryGT");
        context.succeed();
    }

    @GameTest(maxTicks = 240)
    public void grossReservePaysTwoPillarsAndStillDeliversRequestedStone(GameTestHelper context) {
        BlockPos bottom = context.absolutePos(new BlockPos(3, 3, 3));
        BlockPos start = bottom.above(2);
        context.getLevel().setBlock(bottom.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (int dy = 0; dy <= 4; dy++) {
            context.getLevel().setBlock(bottom.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerEntity bot = spawn(context, "DigDownNetPillarsGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 5));
        Node origin = new Node(bottom, 0.0D, 2.0D, MoveType.WALK, null);
        Node middle = new Node(bottom.above(), 1.0D, 1.0D, MoveType.PILLAR_UP, origin);
        Node upper = new Node(start, 2.0D, 0.0D, MoveType.PILLAR_UP, middle);
        PathExecutor executor = new PathExecutor(List.of(origin, middle, upper), start);

        context.failIfEver(() -> {
            // A real jump-arc's raw Y can transiently reach (or even overshoot) the target
            // block's Y band mid-flight, well before PathExecutor itself considers that node
            // landed and commits to the next one (tickPillar only advances once the bot is
            // genuinely grounded there). Driving this off a raw bot.getBlockPos() match -- as an
            // instant pillar teleport always coincided exactly with "arrived" -- can therefore
            // stop ticking the executor (and asserting "arrival") one tick before the second
            // pillar has actually placed its support block. Ask the executor itself whether the
            // whole path is done instead.
            var result = executor.tick(bot.getActionPack());
            if (result.isFailed()) {
                context.fail(Component.nullToEmpty("two-pillar return failed: " + result));
            }
            if (!result.isSuccess()) {
                return;
            }
            require(context, bot.blockPosition().equals(start),
                    "two-pillar path reported success away from its goal: "
                            + bot.blockPosition().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 3,
                    "two physical pillar repairs did not preserve the requested net delivery");
            require(context, context.getLevel().getBlockState(bottom).is(Blocks.COBBLESTONE)
                            && context.getLevel().getBlockState(bottom.above()).is(Blocks.COBBLESTONE),
                    "fixture did not pay two factual cobblestone pillar repairs");

            // The same gross reserve now arrives at the exact DigDown origin. Completion must use
            // the three delivered blocks, not the historical gross count of five.
            Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                    3, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                    DigDownTask.ReturnOutcome.COMPLETE,
                    start, start.getY(), 0, 5, 200, 190, 0, 0, 0, false,
                    null, 0, List.of(start), -1, 0, -20, false).encode();
            DigDownTask task = new DigDownTask(Blocks.STONE, 3, checkpoint);
            task.start(bot);
            task.tick(bot);
            require(context, task.state() == TaskState.COMPLETED,
                    "exact return rejected the requested net delivery: " + task.failureReason());
            finish(context, bot, "DigDownNetPillarsGT");
        });
    }

    @GameTest(maxTicks = 40)
    public void pillarRepairSpendsDirtBeforeMissionCobblestone(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownPillarMaterialGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        Node origin = new Node(start, 0.0D, 1.0D, MoveType.WALK, null);
        Node upper = new Node(start.above(), 1.0D, 0.0D, MoveType.PILLAR_UP, origin);
        PathExecutor executor = new PathExecutor(List.of(origin, upper), upper.pos());

        context.failIfEver(() -> {
            var result = executor.tick(bot.getActionPack());
            if (result.isFailed()) {
                context.fail(Component.nullToEmpty("fixture pillar failed: " + result));
            }
            if (!bot.blockPosition().equals(start.above())) {
                return;
            }
            require(context, context.getLevel().getBlockState(start).is(Blocks.DIRT),
                    "pillar repair did not place the preferred disposable dirt");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 1,
                    "pillar repair consumed the wrong dirt count");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 6,
                    "pillar repair spent mission cobblestone while dirt was available");

            executor.abort(bot.getActionPack());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "DigDownPillarMaterialGT");
            context.succeed();
        });
    }

    /**
     * The north stair is valid when DigDown starts, then loses its support after the real walked step owns it. The failed landing
     * must mark that exact direction rejected and choose the next factual east stair; it must not publish the missing north landing
     * or correct the bot there.
     */
    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_post_start_stair_support_loss_rejects_direction_and_rotates", maxTicks = 180)
    public void postStartStairSupportLossRejectsDirectionAndRotates(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(24, 20, 24));
        BlockPos northLanding = origin.north().below();
        BlockPos eastLanding = origin.east().below();
        for (BlockPos feet : List.of(origin, northLanding, eastLanding)) {
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();
        AIPlayerEntity bot = spawn(context, "DigDownPostStartStairGT", origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        TeleportAudit.reset(bot);
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                strictDescentCheckpoint(origin, List.of(origin), 3, 0));
        task.start(bot);
        boolean[] supportRemoved = {false};
        boolean[] failureSettled = {false};
        int[] ticks = {0};

        context.failIfEver(() -> {
            require(context, ++ticks[0] < 160, "post-start stair failure did not rotate to the east landing");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "post-start stair failure used a teleport: " + TeleportAudit.lastCaller(bot));
            require(context, task.state() == TaskState.RUNNING,
                    "post-start stair task ended: " + task.state() + ":" + task.failureReason());
            if (!supportRemoved[0]
                    && bot.getActionPack().stepInFlightFor("dig_down_stair", northLanding, WalkedStep.Kind.STEP_DOWN)) {
                // Deliberately after beginDescend admitted the landing: this is the dynamic re-proof, not a preflight refusal.
                world.setBlock(northLanding.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                Standability.clearCache();
                supportRemoved[0] = true;
                return;
            }
            if (supportRemoved[0] && !failureSettled[0] && bot.getActionPack().stepIdle()) {
                WalkedStep.Result result = bot.getActionPack().stepResult();
                require(context, result != null && result.failed() && "no_landing".equals(result.reason()),
                        "the post-start stair did not fail from lost support: " + result);
                task.tick(bot); // settle the owned failed step before reading the durable rejection marker
                DigDownTask.DigDownCheckpoint checkpoint = DigDownTask.DigDownCheckpoint.decode(task.checkpoint()).orElse(null);
                require(context, checkpoint != null && origin.equals(checkpoint.rejectedLandingOrigin())
                                && (checkpoint.rejectedLandingDirections() & 1) != 0
                                && bot.blockPosition().equals(origin),
                        "failed stair did not reject only its factual north landing: " + task.checkpoint());
                failureSettled[0] = true;
                return;
            }
            task.tick(bot);
            if (failureSettled[0]
                    && bot.getActionPack().stepInFlightFor("dig_down_stair", eastLanding, WalkedStep.Kind.STEP_DOWN)) {
                DigDownTask.DigDownCheckpoint checkpoint = DigDownTask.DigDownCheckpoint.decode(task.checkpoint()).orElse(null);
                require(context, checkpoint != null && checkpoint.stairDirection() == 1,
                        "post-start stair failure did not rotate NORTH to EAST: " + task.checkpoint());
                require(context, TeleportAudit.corrections(bot) == 0,
                        "rotated stair used a teleport: " + TeleportAudit.lastCaller(bot));
                task.cancel(bot, "gametest_complete");
                finish(context, bot, "DigDownPostStartStairGT");
            }
        });
    }

    /**
     * A neutral boat occupies the exact return entry only after the real return micro-step has started. Once that step fails, the
     * entry is walled before the fallback is admitted: a plain two-phase route would snap its WALK endpoint to the standing tail,
     * whereas the DIG approach must preserve the solid exact endpoint, physically clear it, and walk back into it.
     */
    @GameTest(environment = "minecraftai-gametest:dig_down_return_game_tests_post_start_return_occupancy_promotes_dig_path_fallback", maxTicks = 180)
    public void postStartReturnOccupancyPromotesDigPathFallback(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(24, 20, 40));
        BlockPos tail = start.east();
        preparePlatform(context, start, 5);
        AIPlayerEntity bot = spawn(context, "DigDownPostStartReturnGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        TeleportAudit.reset(bot);
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, List.of(start, tail), 0, 0, false));
        task.start(bot);
        Boat[] blocker = {null};
        boolean[] failedStepSettled = {false};
        boolean[] exactEntryWalled = {false};
        boolean[] exactDigPathStarted = {false};
        int[] ticks = {0};

        context.failIfEver(() -> {
            require(context, ++ticks[0] < 160, "post-start return occupancy did not begin a DIG-path fallback");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "post-start return failure used a teleport: " + TeleportAudit.lastCaller(bot));
            require(context, task.state() == TaskState.RUNNING,
                    "post-start return task ended: " + task.state() + ":" + task.failureReason());
            if (blocker[0] == null
                    && bot.getActionPack().stepInFlightFor("dig_down_return_trail", start, WalkedStep.Kind.FLAT)) {
                blocker[0] = spawnBoatOccupant(context, start);
                return;
            }
            if (blocker[0] != null && !failedStepSettled[0] && bot.getActionPack().stepIdle()) {
                WalkedStep.Result result = bot.getActionPack().stepResult();
                require(context, result != null && result.failed() && "entity_occupied".equals(result.reason()),
                        "the post-start return did not fail from its injected occupant: " + result);
                blocker[0].discard();
                // The failed step still owns the factual exact origin. Seal both body cells only after it has failed: this makes
                // the endpoint deliberately non-standable, so the fallback must retain it as a DIG endpoint instead of accepting
                // an ordinary WALK route snapped back to tail.
                context.getLevel().setBlock(start, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(start.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                exactEntryWalled[0] = true;
                task.tick(bot); // settle the failed owned micro-step while the cursor still names the exact start
                DigDownTask.DigDownCheckpoint checkpoint = DigDownTask.DigDownCheckpoint.decode(task.checkpoint()).orElse(null);
                require(context, checkpoint != null && checkpoint.returnPathFallback()
                                && checkpoint.returnTrailIndex() == 0,
                        "failed return step did not retain the factual exact entry for fallback: " + task.checkpoint());
                failedStepSettled[0] = true;
                return;
            }
            if (exactDigPathStarted[0] && bot.blockPosition().equals(start)) {
                require(context, context.getLevel().getBlockState(start).isAir()
                                && context.getLevel().getBlockState(start.above()).isAir(),
                        "DIG fallback reached the exact entry without physically clearing its walled body");
                require(context, TeleportAudit.corrections(bot) == 0,
                        "DIG-path return fallback used a teleport: " + TeleportAudit.lastCaller(bot));
                task.cancel(bot, "gametest_complete");
                finish(context, bot, "DigDownPostStartReturnGT");
                return;
            }
            task.tick(bot);
            if (failedStepSettled[0] && !bot.getActionPack().isPathExecutorIdle()) {
                require(context, start.equals(bot.getActionPack().activePathGoal()),
                        "return fallback did not preserve its solid exact-entry target: "
                                + bot.getActionPack().activePathGoal());
                require(context, exactEntryWalled[0],
                        "fallback began before the post-failure exact-entry wall was installed");
                exactDigPathStarted[0] = true;
                require(context, TeleportAudit.corrections(bot) == 0,
                        "DIG-path return fallback used a teleport: " + TeleportAudit.lastCaller(bot));
            }
        });
    }

    private static Map<String, String> returnCheckpoint(BlockPos start,
                                                         List<BlockPos> trail,
                                                         int returnIndex,
                                                         int returnBudget,
                                                         boolean fallback) {
        return new DigDownTask.DigDownCheckpoint(
                1, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, trail.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                0, 3, 200, 190, 0, 0, 0, false,
                null, 0, trail,
                returnIndex, returnBudget, -20, fallback).encode();
    }

    private static Map<String, String> safetyReturnCheckpoint(BlockPos start,
                                                               List<BlockPos> trail,
                                                               int returnIndex,
                                                               int returnBudget,
                                                               int lastProgressBudget,
                                                               long bestDistanceSquared) {
        int targetY = trail.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        return new DigDownTask.DigDownCheckpoint(
                4, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, targetY, 0, 3, 200, 190, 0,
                0, 0, false, null, 0, trail,
                returnIndex, returnBudget, -20, false,
                lastProgressBudget, returnIndex, bestDistanceSquared, true).encode();
    }

    private static long squaredDistance(BlockPos from, BlockPos to) {
        long dx = (long) from.getX() - to.getX();
        long dy = (long) from.getY() - to.getY();
        long dz = (long) from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Map<String, String> descentCheckpoint(BlockPos start,
                                                          List<BlockPos> trail,
                                                          int targetCount,
                                                          int collected) {
        return new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", targetCount, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, trail.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                0, collected, 200, 190, 0, 0, 0, false,
                null, 0, trail, -1, 0, -20, false).encode();
    }

    private static Map<String, String> strictDescentCheckpoint(BlockPos start,
                                                                List<BlockPos> trail,
                                                                int targetCount,
                                                                int collected) {
        return new DigDownTask.DigDownCheckpoint(
                4, "minecraft:stone", targetCount, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, trail.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                0, collected, 200, 190, 0, 0, 0, false,
                null, 0, trail, -1, 0, -20, false,
                0, -1, -1L, false).encode();
    }

    private static void preparePlatform(GameTestHelper context, BlockPos start, int radius) {
        for (int dx = -1; dx <= radius; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                context.getLevel().setBlock(start.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    context.getLevel().setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos pos) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), Vec3.atBottomCenterOf(pos),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    /** A neutral, collidable return-landing occupant; it cannot change combat ownership. */
    private static Boat spawnBoatOccupant(GameTestHelper context, BlockPos feet) {
        Boat boat = EntityType.OAK_BOAT.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (boat == null) {
            throw new IllegalStateException("failed to create occupied return boat");
        }
        boat.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        if (!context.getLevel().addFreshEntity(boat)) {
            throw new IllegalStateException("failed to spawn occupied return boat");
        }
        return boat;
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void finish(GameTestHelper context, AIPlayerEntity bot, String name) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        GoalExecutor.INSTANCE.unload(bot);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
