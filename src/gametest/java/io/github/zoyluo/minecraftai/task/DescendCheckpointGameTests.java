package io.github.zoyluo.minecraftai.task;

import com.mojang.logging.LogUtils;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalPlanner;
import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.goal.GoalStep;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.persist.MissionRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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
import org.slf4j.Logger;

/** Restart contracts for the exact staircase hand-off owned by {@link DescendToYTask}. */
public final class DescendCheckpointGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    @GameTest(environment = "minecraftai-gametest:descend_checkpoint_game_tests_full_depth_deepslate_descent_with_five_stone_pickaxes_fits_its_persisted_window", maxTicks = 9_000)
    public void fullDepthDeepslateDescentWithFiveStonePickaxesFitsItsPersistedWindow(
            GameTestHelper context) {
        BlockPos relativeOrigin = context.absolutePos(new BlockPos(4, 0, 80));
        BlockPos start = new BlockPos(relativeOrigin.getX(), 16, relativeOrigin.getZ());
        int targetY = -59;
        require(context, start.getY() - targetY == 75,
                "full-depth fixture did not span 75 levels: " + start + " -> " + targetY);

        context.getLevel().setBlock(start,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(start.above(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(start.below(),
                Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        List<BlockPos> physicalBreaks = new java.util.ArrayList<>();
        BlockPos cursor = start;
        for (int level = 0; level < 75; level++) {
            BlockPos ahead = cursor.north();
            BlockPos landing = ahead.below();
            for (BlockPos breakPos : List.of(ahead, ahead.above(), landing)) {
                context.getLevel().setBlock(breakPos,
                        Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
                physicalBreaks.add(breakPos.immutable());
            }
            context.getLevel().setBlock(landing.below(),
                    Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
            cursor = landing;
        }
        BlockPos expectedLanding = cursor;
        require(context, expectedLanding.getY() == targetY,
                "full-depth fixture ended at the wrong Y: " + expectedLanding);

        String name = "DescendFullDepthGT";
        AIPlayerEntity bot = spawn(context, name, start);
        bot.setOnGround(true);
        for (int pick = 0; pick < 5; pick++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        DescendToYTask task = new DescendToYTask(targetY);
        task.start(bot);
        require(context, "8400".equals(task.checkpoint().get("budget_limit")),
                "Y=16 to Y=-59 did not receive the derived 8,400-tick window: "
                        + task.checkpoint());

        context.failIfEver(() -> {
            // One task tick per real server tick is part of the contract. Replaying ActionPack in
            // the same server tick can make MiningController reach progress=1 before the server's
            // interaction manager has settled the break, which only burns the persisted budget.
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "full-depth Deepslate descent failed: " + task.failureReason()
                            + " checkpoint=" + task.checkpoint());
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            Map<String, String> terminal = task.checkpoint();
            int budgetUsed = Integer.parseInt(terminal.get("budget_used"));
            require(context, bot.blockPosition().equals(expectedLanding),
                    "full-depth descent completed at the wrong landing: expected="
                            + expectedLanding.toShortString() + " actual="
                            + bot.blockPosition().toShortString());
            require(context, budgetUsed > 4_800 && budgetUsed <= 8_400
                            && "8400".equals(terminal.get("budget_limit"))
                            && "false".equals(terminal.get("task_open")),
                    "full-depth descent violated its persisted dynamic budget: " + terminal);
            LOGGER.info("DESCEND_FULL_DEPTH_DEEPSLATE budget_used={} budget_limit={} headroom={} levels={}",
                    budgetUsed, 8_400, 8_400 - budgetUsed, start.getY() - targetY);
            require(context, physicalBreaks.stream().allMatch(pos ->
                            !context.getLevel().getBlockState(pos).is(Blocks.DEEPSLATE)),
                    "full-depth descent completed without physically clearing every body block");
            finish(context, bot, name);
        });
    }

    @GameTest(maxTicks = 40)
    public void exhaustedCheckpointFailsOnItsPersistedClock(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(2, 4, 2));
        prepareLanding(context, start);
        AIPlayerEntity bot = spawn(context, "DescendBudgetRestoreGT", start);

        Map<String, String> checkpoint = freshCheckpoint(bot, start.getY() - 1);
        checkpoint = new LinkedHashMap<>(checkpoint);
        checkpoint.put("budget_used", "4800");
        checkpoint.put("last_progress_budget", "4800");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(start.getY() - 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.FAILED,
                "exhausted checkpoint received a fresh clock: " + restored.state());
        require(context, restored.failureReason().startsWith("descend_timeout"),
                "unexpected exhausted-checkpoint failure: " + restored.failureReason());
        require(context, bot.blockPosition().equals(start),
                "timeout restore moved before enforcing its persisted budget");
        finish(context, bot, "DescendBudgetRestoreGT");
    }

    /**
     * The terminal budget check runs before Descend's normal step hold. A real task-owned stair
     * step is therefore live when the restored clock crosses its bound; terminal failure must
     * cancel that step and every controller input rather than relying on task removal to do it.
     */
    @GameTest(maxTicks = 30)
    public void timeoutCancelsTaskOwnedWalkedStepAndAllInputs(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(2, 8, 2));
        BlockPos landing = start.north().below();
        prepareLanding(context, start);
        prepareLanding(context, landing);
        context.getLevel().setBlock(start.north().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawn(context, "DescendTimeoutStepGT", start);

        Map<String, String> checkpoint = nearTerminalCheckpoint(bot, start.getY() - 2);
        DescendToYTask task = new DescendToYTask(start.getY() - 2, checkpoint);
        task.start(bot);
        task.tick(bot); // budget == limit: starts the real diagonal stair but does not yet fail.
        require(context, task.state() == TaskState.RUNNING && !bot.getActionPack().stepIdle()
                        && bot.getActionPack().hasActiveActions(),
                "fixture did not create a task-owned active stair at the timeout boundary");
        bot.getActionPack().onUpdate(); // prove a live walked step has written controller input.

        task.tick(bot); // budget > limit; this is deliberately before holdForStep.
        requireTerminalStepReleased(context, task, bot, "descend_timeout");
        finish(context, bot, "DescendTimeoutStepGT");
    }

    /** The same release invariant applies when an in-flight stair has physically overshot its target. */
    @GameTest(maxTicks = 30)
    public void overshootCancelsTaskOwnedWalkedStepAndAllInputs(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(6, 8, 2));
        BlockPos landing = start.north().below();
        BlockPos overshot = start.below(3);
        prepareLanding(context, start);
        prepareLanding(context, landing);
        prepareLanding(context, overshot);
        context.getLevel().setBlock(start.north().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawn(context, "DescendOvershootStepGT", start);

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        task.start(bot);
        task.tick(bot); // starts the task-owned stair.
        require(context, task.state() == TaskState.RUNNING && !bot.getActionPack().stepIdle()
                        && bot.getActionPack().hasActiveActions(),
                "fixture did not create a task-owned active stair before overshoot");
        bot.getActionPack().onUpdate();
        BotFixtureMoves.place(bot, overshot); // models a factual fall/knockback below the target while keys are live.

        task.tick(bot);
        requireTerminalStepReleased(context, task, bot, "descend_overshoot_unrecoverable");
        finish(context, bot, "DescendOvershootStepGT");
    }

    @GameTest(maxTicks = 30)
    public void schemaFourRestartKeepsTheOriginalDepthWindowAtANewHeight(GameTestHelper context) {
        BlockPos lane = context.absolutePos(new BlockPos(3, 0, 12));
        BlockPos start = new BlockPos(lane.getX(), 16, lane.getZ());
        BlockPos restart = new BlockPos(lane.getX(), 0, lane.getZ());
        prepareLanding(context, start);
        prepareLanding(context, restart);
        prepareLanding(context, restart.north().below());
        String name = "DescendWindowRestoreGT";
        AIPlayerEntity bot = spawn(context, name, start);

        DescendToYTask first = new DescendToYTask(-59);
        first.start(bot);
        Map<String, String> checkpoint = new LinkedHashMap<>(first.checkpoint());
        checkpoint.put("budget_used", "5000");
        checkpoint.put("last_progress_budget", "5000");
        require(context, "8400".equals(checkpoint.get("budget_limit"))
                        && DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "dynamic restart fixture was invalid: " + checkpoint);
        first.cancel(bot, "gametest_restart");
        bot.teleportTo(context.getLevel(), restart.getX() + 0.5D, restart.getY(),
                restart.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);

        DescendToYTask restored = new DescendToYTask(-59, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> afterRestart = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING,
                "schema-4 restart treated its live persisted clock as exhausted: "
                        + restored.failureReason());
        require(context, "8400".equals(afterRestart.get("budget_limit"))
                        && Integer.parseInt(afterRestart.get("budget_used")) > 5000,
                "schema-4 restart recomputed or refreshed the original budget: "
                        + afterRestart);

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, name);
    }

    @GameTest(maxTicks = 30)
    public void detourCheckpointSchemaRejectsInventedOrIncompleteEdgeHistory(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(4, 7, 2));
        prepareLanding(context, start);
        AIPlayerEntity bot = spawn(context, "DescendEdgeSchemaGT", start);
        Map<String, String> current = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 5));

        Map<String, String> legacyIdle = new LinkedHashMap<>(current);
        legacyIdle.put("task_schema", "2");
        legacyIdle.remove("traversed_detour_edges");
        legacyIdle.remove("budget_limit");
        legacyIdle.remove("landing_drift_recoveries");
        require(context, DescendToYTask.inspectCheckpoint(legacyIdle).isPresent(),
                "idle schema-2 checkpoint was not safely promoted");
        DescendToYTask promoted = new DescendToYTask(start.getY() - 5, legacyIdle);
        promoted.start(bot);
        require(context, "5".equals(promoted.checkpoint().get("task_schema"))
                        && "".equals(promoted.checkpoint().get("traversed_detour_edges"))
                        && "4800".equals(promoted.checkpoint().get("budget_limit"))
                        && "0".equals(promoted.checkpoint().get("landing_drift_recoveries")),
                "schema-2 promotion did not emit schema-5 edge, budget and landing-drift history: "
                        + promoted.checkpoint());
        promoted.cancel(bot, "gametest_schema_check");

        Map<String, String> schemaThree = new LinkedHashMap<>(current);
        schemaThree.put("task_schema", "3");
        schemaThree.remove("budget_limit");
        schemaThree.remove("landing_drift_recoveries");
        schemaThree.put("budget_used", "1000");
        schemaThree.put("last_progress_budget", "1000");
        require(context, DescendToYTask.inspectCheckpoint(schemaThree).isPresent(),
                "schema-3 edge checkpoint was not accepted for one-time budget promotion");
        DescendToYTask promotedBudget = new DescendToYTask(start.getY() - 5, schemaThree);
        promotedBudget.start(bot);
        require(context, "5".equals(promotedBudget.checkpoint().get("task_schema"))
                        && "5800".equals(promotedBudget.checkpoint().get("budget_limit")),
                "schema-3 promotion did not persist its dynamic budget: "
                        + promotedBudget.checkpoint());
        promotedBudget.cancel(bot, "gametest_schema_check");

        Map<String, String> schemaFour = new LinkedHashMap<>(current);
        schemaFour.put("task_schema", "4");
        schemaFour.remove("landing_drift_recoveries");
        require(context, DescendToYTask.inspectCheckpoint(schemaFour).isPresent(),
                "schema-4 checkpoint (pre-landing-drift field) was not accepted");
        DescendToYTask promotedLandingDrift = new DescendToYTask(start.getY() - 5, schemaFour);
        promotedLandingDrift.start(bot);
        require(context, "5".equals(promotedLandingDrift.checkpoint().get("task_schema"))
                        && "0".equals(promotedLandingDrift.checkpoint().get("landing_drift_recoveries")),
                "schema-4 promotion did not default landing_drift_recoveries to zero: "
                        + promotedLandingDrift.checkpoint());
        promotedLandingDrift.cancel(bot, "gametest_schema_check");

        Map<String, String> tooSmallLimit = new LinkedHashMap<>(current);
        tooSmallLimit.put("budget_limit", "4799");
        require(context, DescendToYTask.inspectCheckpoint(tooSmallLimit).isEmpty(),
                "schema-4 checkpoint accepted a sub-minimum budget limit");
        Map<String, String> tooLargeLimit = new LinkedHashMap<>(current);
        tooLargeLimit.put("budget_limit", "40001");
        require(context, DescendToYTask.inspectCheckpoint(tooLargeLimit).isEmpty(),
                "schema-4 checkpoint accepted an over-cap budget limit");
        Map<String, String> usedPastTerminal = new LinkedHashMap<>(current);
        usedPastTerminal.put("budget_used", "4802");
        usedPastTerminal.put("last_progress_budget", "4802");
        require(context, DescendToYTask.inspectCheckpoint(usedPastTerminal).isEmpty(),
                "schema-4 checkpoint accepted budget usage past limit+1");

        Map<String, String> legacyActive = new LinkedHashMap<>(legacyIdle);
        legacyActive.put("lateral_detours", "1");
        legacyActive.put("detour_heading", "0");
        require(context, DescendToYTask.inspectCheckpoint(legacyActive).isEmpty(),
                "active schema-2 detour invented missing traversal history");

        Map<String, String> duplicate = new LinkedHashMap<>(current);
        String north = encodeEdge(start, start.north());
        duplicate.put("lateral_detours", "2");
        duplicate.put("detour_heading", "0");
        duplicate.put("traversed_detour_edges", north + ";" + north);
        require(context, DescendToYTask.inspectCheckpoint(duplicate).isEmpty(),
                "duplicate directed detour edge was accepted");

        Map<String, String> nonAdjacent = new LinkedHashMap<>(current);
        nonAdjacent.put("lateral_detours", "1");
        nonAdjacent.put("detour_heading", "0");
        nonAdjacent.put("traversed_detour_edges", encodeEdge(start, start.north(2)));
        require(context, DescendToYTask.inspectCheckpoint(nonAdjacent).isEmpty(),
                "non-adjacent directed detour edge was accepted");

        Map<String, String> wrongHeading = new LinkedHashMap<>(current);
        wrongHeading.put("lateral_detours", "1");
        wrongHeading.put("detour_heading", "1");
        wrongHeading.put("traversed_detour_edges", north);
        require(context, DescendToYTask.inspectCheckpoint(wrongHeading).isEmpty(),
                "checkpoint heading disagreed with its last factual detour edge");
        finish(context, bot, "DescendEdgeSchemaGT");
    }

    // Four flat steps (6 game ticks each plus the tick that settles them) and the stair down (11 ticks plus its settle) are walked:
    // about 45 ticks on top of the tick of the restore, where the old teleporting steps took one tick each.
    @GameTest(maxTicks = 120)
    public void edgeSeventeenCheckpointContinuesToANearbySupportedCaveRim(
            GameTestHelper context) {
        BlockPos restart = context.absolutePos(new BlockPos(8, 7, 8));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -7; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    context.getLevel().setBlock(restart.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (int step = 0; step <= 4; step++) {
            prepareLanding(context, restart.north(step));
        }
        BlockPos lowerExit = restart.north(5).below();
        prepareLanding(context, lowerExit);

        String name = "DescendEdge17RestoreGT";
        AIPlayerEntity bot = spawn(context, name, restart);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, lowerExit.getY()));
        List<String> history = new java.util.ArrayList<>();
        BlockPos cursor = restart.south(17);
        for (int edge = 0; edge < 17; edge++) {
            BlockPos next = cursor.north();
            history.add(encodeEdge(cursor, next));
            cursor = next;
        }
        require(context, cursor.equals(restart),
                "edge-17 fixture history did not end at its factual restart pose");
        checkpoint.put("budget_used", "17");
        checkpoint.put("last_progress_budget", "17");
        checkpoint.put("stair_direction", "0");
        checkpoint.put("lateral_detours", "17");
        checkpoint.put("detour_heading", "0");
        String encodedHistory = String.join(";", history);
        checkpoint.put("traversed_detour_edges", encodedHistory);
        String budgetLimit = checkpoint.get("budget_limit");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "edge-17 checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(lowerExit.getY(), checkpoint);
        restored.start(bot);
        require(context, "17".equals(restored.checkpoint().get("lateral_detours"))
                        && encodedHistory.equals(
                        restored.checkpoint().get("traversed_detour_edges"))
                        && budgetLimit.equals(restored.checkpoint().get("budget_limit")),
                "restart refreshed the persisted edge debt: " + restored.checkpoint());
        int[] maxDebt = {17};
        // A plain synchronous loop over task.tick() never yields back to the real server between
        // calls, so any code path that depended on genuine per-tick progress (MiningController's
        // ActionPack#onUpdate-driven advancement) could never complete inside one. Drive one task
        // tick per real server tick instead, matching every other fixture in this suite whose
        // outcome must reflect real ticked state rather than a synchronous burst of calls.
        context.failIfEver(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
                maxDebt[0] = Math.max(maxDebt[0],
                        Integer.parseInt(restored.checkpoint().get("lateral_detours")));
                return;
            }
            require(context, restored.state() == TaskState.COMPLETED,
                    "edge-17 restart did not reach the supported rim: "
                            + restored.state() + ":" + restored.failureReason());
            require(context, bot.blockPosition().equals(lowerExit),
                    "edge-17 restart completed at the wrong landing: expected="
                            + lowerExit.toShortString() + " actual="
                            + bot.blockPosition().toShortString());
            // DescendToYTask now recognizes ahead as an already-open, already-supported flat
            // landing before ever treating the riser below it as a stair to mine through (a real
            // player standing here would just walk onto visibly-open, visibly-supported ground
            // rather than dig through it). The whole rim is therefore crossed with ordinary flat
            // steps instead of lateral detours -- but each flat step is itself still a same-level
            // hop, so it is recorded and budgeted exactly like a lateral detour (four more steps
            // across the rim on top of the persisted 17), never refreshed and never replayed.
            require(context, maxDebt[0] == 21,
                    "edge-17 restart refreshed or skipped its factual debt: max_debt=" + maxDebt[0]);
            require(context, Integer.parseInt(restored.checkpoint().get("budget_used")) > 17
                            && budgetLimit.equals(restored.checkpoint().get("budget_limit")),
                    "edge-17 restart refreshed or rewound its persisted clock: "
                            + restored.checkpoint());
            require(context, "0".equals(restored.checkpoint().get("lateral_detours"))
                            && "".equals(restored.checkpoint().get("traversed_detour_edges")),
                    "confirmed lower landing retained the restored detour history: "
                            + restored.checkpoint());
            finish(context, bot, name);
        });
    }

    @GameTest(maxTicks = 20)
    public void fullThirtyTwoEdgeCheckpointFailsClosedWithoutRefreshingOrReplaying(
            GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(8, 7, 8));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    context.getLevel().setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        prepareLanding(context, start);
        String name = "DescendEdge32CapGT";
        AIPlayerEntity bot = spawn(context, name, start);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 1));
        List<String> history = new java.util.ArrayList<>();
        BlockPos cursor = start.south(32);
        for (int edge = 0; edge < 32; edge++) {
            BlockPos next = cursor.north();
            history.add(encodeEdge(cursor, next));
            cursor = next;
        }
        require(context, cursor.equals(start),
                "edge-cap fixture history did not end at its factual restart pose");
        String encodedHistory = String.join(";", history);
        checkpoint.put("budget_used", "32");
        checkpoint.put("last_progress_budget", "32");
        checkpoint.put("stair_direction", "0");
        checkpoint.put("lateral_detours", "32");
        checkpoint.put("detour_heading", "0");
        checkpoint.put("traversed_detour_edges", encodedHistory);
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "full edge-cap checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(start.getY() - 1, checkpoint);
        restored.start(bot);
        for (int tick = 0; tick < 8 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }

        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().startsWith("descend_no_safe_landing"),
                "full edge-cap checkpoint escaped its bounded terminal: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.blockPosition().equals(start),
                "full edge-cap checkpoint moved after exhaustion: "
                        + bot.blockPosition().toShortString());
        require(context, "32".equals(restored.checkpoint().get("lateral_detours"))
                        && encodedHistory.equals(
                        restored.checkpoint().get("traversed_detour_edges")),
                "full edge-cap checkpoint refreshed or replayed history: "
                        + restored.checkpoint());
        finish(context, bot, name);
    }

    /**
     * A knockback between task ticks can leave the bot on a third cell that is neither the
     * pending landing origin nor its target. That is external displacement, not a broken safety
     * invariant: the descent must re-anchor on the factual pose and keep running instead of
     * terminating the whole mission as descend_landing_pose_drift.
     */
    @GameTest(maxTicks = 30)
    public void knockbackLandingDriftReanchorsInsteadOfFailingTheMission(
            GameTestHelper context) {
        BlockPos floor = context.absolutePos(new BlockPos(11, 7, 6));
        BlockPos origin = floor.east().above();
        BlockPos drifted = floor.west();
        prepareLanding(context, floor);
        prepareLanding(context, origin);
        prepareLanding(context, drifted);
        // Keep the next stair below the drifted pose solid so the recovered tick plans calmly.
        context.getLevel().setBlock(drifted.north(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        String name = "DescendDriftGT";
        AIPlayerEntity bot = spawn(context, name, drifted);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, floor.getY() - 1));
        checkpoint.put("pending_landing_origin", encode(origin));
        checkpoint.put("pending_landing_target", encode(floor));
        checkpoint.put("pending_landing_direction", "3");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "drift fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(floor.getY() - 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.RUNNING,
                "single knockback drift terminated the descent: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, "none".equals(
                        restored.checkpoint().get("pending_landing_target")),
                "drift recovery retained the stale pending landing: "
                        + restored.checkpoint());
        restored.tick(bot);
        require(context, restored.state() == TaskState.RUNNING,
                "re-anchored descent could not continue from the drifted pose: "
                        + restored.state() + ":" + restored.failureReason());
        restored.cancel(bot, "gametest_drift_recovered");
        finish(context, bot, name);
    }

    @GameTest(maxTicks = 30)
    public void upperRetreatLandingBackOnTheObstacleFloorKeepsItsDetourDebt(
            GameTestHelper context) {
        BlockPos floor = context.absolutePos(new BlockPos(5, 7, 6));
        BlockPos south = floor.south();
        BlockPos upper = floor.east().above();
        BlockPos upperSouth = upper.south();
        prepareLanding(context, floor);
        BlockPos freshLowerLanding = floor.north().below();
        prepareLanding(context, freshLowerLanding);
        context.getLevel().setBlock(floor.north(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        String name = "DescendFloorDebtGT";
        AIPlayerEntity bot = spawn(context, name, floor);
        // The descend tool gate typed-fails a pickless stair dig; this fixture tests detour
        // debt accounting, so provision the ordinary descent pick like a real mission would.
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, floor.getY() - 1));
        String detourHistory = String.join(";",
                encodeEdge(floor, south),
                encodeEdge(south, floor),
                encodeEdge(floor, upper),
                encodeEdge(upper, upperSouth),
                encodeEdge(upperSouth, upper));
        checkpoint.put("budget_used", "5");
        checkpoint.put("last_progress_budget", "5");
        checkpoint.put("lateral_detours", "5");
        checkpoint.put("detour_heading", "0");
        checkpoint.put("pending_landing_origin", encode(upper));
        checkpoint.put("pending_landing_target", encode(floor));
        checkpoint.put("pending_landing_direction", "3");
        checkpoint.put("traversed_detour_edges", detourHistory);
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "same-floor detour fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(floor.getY() - 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);

        Map<String, String> afterLanding = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING,
                "same-floor landing ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.blockPosition().equals(floor),
                "same-floor landing fixture moved away before debt inspection: "
                        + bot.blockPosition().toShortString());
        require(context, "5".equals(afterLanding.get("lateral_detours"))
                        && detourHistory.equals(afterLanding.get("traversed_detour_edges")),
                "upper retreat landing reset same-floor detour debt: " + afterLanding);

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, name);
    }

    // The stair step down is walked (11 game ticks plus the tick that settles it) before the landing is pending.
    @GameTest(maxTicks = 90)
    public void interruptedLandingAtOriginRejectsTheSameEdgeAfterRestore(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(6, 5, 2));
        prepareLanding(context, start);
        prepareLanding(context, start.north().below());
        prepareLanding(context, start.east().below());
        AIPlayerEntity bot = spawn(context, "DescendLandingRestoreGT", start);

        DescendToYTask first = new DescendToYTask(start.getY() - 1);
        first.start(bot);
        BlockPos northLanding = start.north().below();
        Map<String, String>[] snapshot = new Map[1];
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, first, bot, 60, "fixture did not issue the initial north landing",
                        () -> encode(northLanding).equals(first.checkpoint().get("pending_landing_target"))),
                () -> {
                    require(context, bot.blockPosition().equals(northLanding),
                            "fixture did not issue the initial north landing");
                    Map<String, String> checkpoint = first.checkpoint();
                    require(context, encode(start).equals(checkpoint.get("pending_landing_origin"))
                                    && encode(northLanding).equals(checkpoint.get("pending_landing_target")),
                            "fixture did not persist its unresolved landing: " + checkpoint);
                    snapshot[0] = checkpoint;
                    first.cancel(bot, "gametest_restart");
                    BotFixtureMoves.place(bot, start);
                    return true;
                },
                DescendTickStages.idle(3),
                () -> {
                    DescendToYTask restored = new DescendToYTask(start.getY() - 1, snapshot[0]);
                    restored.start(bot);
                    Map<String, String> promoted = restored.checkpoint();
                    require(context, "none".equals(promoted.get("pending_landing_origin"))
                                    && "none".equals(promoted.get("pending_landing_target")),
                            "restart retained an unobservable landing controller: " + promoted);
                    require(context, encode(start).equals(promoted.get("rejected_landing_origin"))
                                    && (Integer.parseInt(promoted.get("rejected_landing_directions")) & 1) != 0,
                            "restart did not reject the interrupted north edge: " + promoted);

                    restored.tick(bot);
                    require(context, !bot.blockPosition().equals(northLanding),
                            "restored Descend retried the interrupted landing");
                    restored.cancel(bot, "gametest_complete");
                    finish(context, bot, "DescendLandingRestoreGT");
                    return true;
                });
    }

    // The hop up to the upper escape is walked (a few game ticks plus the tick that settles it).
    @GameTest(maxTicks = 80)
    public void traversedDetourEdgesSurviveRestartAndUnlockTheUpperSealRoute(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(8, 7, 2));
        BlockPos south = start.south();
        BlockPos northSeal = start.north();
        BlockPos upperEscape = northSeal.above();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    context.getLevel().setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        prepareLanding(context, start);
        prepareLanding(context, south);
        context.getLevel().setBlock(northSeal,
                Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(upperEscape,
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(upperEscape.above(),
                Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawn(context, "DescendEdgeRestoreGT", start);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 5));
        checkpoint.put("budget_used", "2");
        checkpoint.put("last_progress_budget", "2");
        checkpoint.put("lateral_detours", "2");
        checkpoint.put("detour_heading", "0");
        checkpoint.put("owned_water_seals",
                encode(northSeal) + ",minecraft:dirt");
        checkpoint.put("traversed_detour_edges",
                encodeEdge(start, south) + ";" + encodeEdge(south, start));
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(start.getY() - 5, checkpoint);
        restored.start(bot);
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, restored, bot, 60,
                        "restored detour replayed the visited south edge instead of using the upper water-seal route",
                        () -> bot.blockPosition().equals(upperEscape) && bot.getActionPack().stepIdle()
                                && !"2".equals(restored.checkpoint().get("lateral_detours"))),
                () -> {
                    require(context, restored.state() == TaskState.RUNNING,
                            "restored detour ended unexpectedly: "
                                    + restored.state() + ":" + restored.failureReason());
                    require(context, bot.blockPosition().equals(upperEscape),
                            "restored detour replayed the visited south edge instead of using the upper "
                                    + "water-seal route: " + bot.blockPosition().toShortString());
                    require(context, context.getLevel().getBlockState(northSeal).is(Blocks.DIRT),
                            "upper-route escape mined its owned water seal");
                    require(context, DescendToYTask.inspectCheckpoint(restored.checkpoint()).isPresent(),
                            "upper-route move produced an invalid checkpoint: " + restored.checkpoint());
                    restored.cancel(bot, "gametest_complete");
                    finish(context, bot, "DescendEdgeRestoreGT");
                    return true;
                });
    }

    // The climb-over hop and the stair step down to the fresh column are walked (a hop and an 11 tick step, each plus its settle tick).
    @GameTest(maxTicks = 140)
    public void unsupportedSolidDetourPreservesUpperRetreatAcrossRestart(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(14, 7, 2));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    context.getLevel().setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        context.getLevel().setBlock(start.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos unsupportedBody = start.east();
        BlockPos upperRetreat = unsupportedBody.above();
        // The same-level EAST candidate is solid but has no floor. It is also the sole support for
        // the dry upper retreat. The old detour mined it before the walked-step landing rules rejected the lower
        // landing, thereby destroying both escape options.
        context.getLevel().setBlock(unsupportedBody,
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos freshLanding = upperRetreat.north().below();
        context.getLevel().setBlock(freshLanding.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        String name = "DescendUpperRetreatRestoreGT";
        AIPlayerEntity bot = spawn(context, name, start);
        Map<String, String> initial = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 5));
        // Start WEST: the only other candidate in this fixture (EAST, the unsupported solid body)
        // must also be discovered and skipped honestly by Descend itself.
        initial.put("stair_direction", "3");
        require(context, DescendToYTask.inspectCheckpoint(initial).isPresent(),
                "upper-retreat fixture checkpoint was invalid: " + initial);
        DescendToYTask first = new DescendToYTask(start.getY() - 5, initial);
        first.start(bot);
        // WEST is rejected outright (its own landing is observably unsupported). EAST's ahead
        // (unsupportedBody) is solid and its deeper landing is unconfirmed (hidden behind that
        // riser), so rotateStair now correctly allows Descend to consider it -- one tick to rotate
        // onto EAST, a second to recognize the safe climb-over onto its own top face instead of
        // mining through it (see DescendToYTask's climb-over branch). The climb-over is a walked hop
        // whose landing (and its bookkeeping) is verified a few game ticks later.
        Map<String, String>[] upperCheckpoint = new Map[1];
        DescendToYTask[] restoredTask = new DescendToYTask[1];
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, first, bot, 60,
                        "unsupported solid candidate was not skipped for the safe upper retreat: "
                                + start.toShortString() + " -> the upper retreat",
                        () -> bot.blockPosition().equals(upperRetreat) && bot.getActionPack().stepIdle()
                                && encode(upperRetreat).equals(first.checkpoint().get("rejected_landing_origin"))),
                () -> {
                    require(context, first.state() == TaskState.RUNNING,
                            "unsupported detour ended before its bounded upper retreat: "
                                    + first.state() + ":" + first.failureReason());
                    require(context, bot.blockPosition().equals(upperRetreat),
                            "unsupported solid candidate was not skipped for the safe upper retreat: "
                                    + start.toShortString() + " -> " + bot.blockPosition().toShortString());
                    require(context, context.getLevel().getBlockState(unsupportedBody).is(Blocks.STONE),
                            "Descend destroyed the only support for its upper retreat");

                    Map<String, String> checkpoint = first.checkpoint();
                    require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                            "upper-retreat checkpoint was invalid: " + checkpoint);
                    // The climb-over durably rejects the exact reverse of whichever direction it climbed from
                    // (here EAST, so its reverse WEST) at the new origin, exactly like the lateral detour's own
                    // upper-retreat bookkeeping -- a restart must not immediately step back down into the
                    // escaped, unsupported lower cell.
                    require(context, encode(upperRetreat).equals(checkpoint.get("rejected_landing_origin"))
                                    && (Integer.parseInt(checkpoint.get("rejected_landing_directions"))
                                    & 1 << 3) != 0,
                            "upper retreat did not durably reject the WEST reverse stair: " + checkpoint);

                    first.cancel(bot, "gametest_restart");
                    DescendToYTask restored = new DescendToYTask(start.getY() - 5, checkpoint);
                    restored.start(bot);
                    require(context, (Integer.parseInt(restored.checkpoint()
                                    .get("rejected_landing_directions")) & 1 << 3) != 0,
                            "restart discarded the WEST reverse rejection before the first tick: "
                                    + restored.checkpoint());
                    // The persisted heading (EAST) is not itself the rejected bit, so the first restored tick
                    // must discover EAST is unsupported from this new vantage and rotate onto NORTH in place
                    // before the second tick can physically use the fresh column.
                    restored.tick(bot);
                    require(context, restored.state() == TaskState.RUNNING,
                            "restored upper retreat ended unexpectedly: "
                                    + restored.state() + ":" + restored.failureReason());
                    require(context, bot.blockPosition().equals(upperRetreat),
                            "restored Descend moved instead of rotating in place on its first tick: "
                                    + upperRetreat.toShortString() + " -> " + bot.blockPosition().toShortString());
                    require(context, context.getLevel().getBlockState(unsupportedBody).is(Blocks.STONE),
                            "restored Descend destroyed the only support for its upper retreat");
                    restoredTask[0] = restored;
                    return true;
                },
                DescendTickStages.tickUntil(context, () -> restoredTask[0], bot, 60,
                        "restored Descend replayed the rejected stair instead of using the fresh column: "
                                + upperRetreat.toShortString() + " -> the fresh column",
                        () -> bot.blockPosition().equals(freshLanding)),
                () -> {
                    DescendToYTask restored = restoredTask[0];
                    require(context, restored.state() == TaskState.RUNNING,
                            "restored upper retreat failed before reaching the fresh column: "
                                    + restored.state() + ":" + restored.failureReason());
                    require(context, bot.blockPosition().equals(freshLanding),
                            "restored Descend replayed the rejected stair instead of using the fresh column: "
                                    + upperRetreat.toShortString() + " -> " + bot.blockPosition().toShortString());
                    require(context, !bot.blockPosition().equals(start),
                            "restored Descend immediately fell back into the escaped lower cell");

                    restored.cancel(bot, "gametest_complete");
                    finish(context, bot, name);
                    return true;
                });
    }

    // The sneak-bridge is walked: the lean over the edge, the walk back to the middle of the cell (both at sneaking pace) and the
    // step onto the new floor, each a few game ticks; the old fixture did all of it inside two task ticks.
    @GameTest(maxTicks = 240)
    public void isolatedPillarBuildsOnePhysicalFloorBeforeDetourMovement(GameTestHelper context) {
        String name = "DescendBridgeReceiptGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE + 1);
        DescendToYTask task = fixture.task();
        AIPlayerEntity bot = fixture.bot();

        DescendTickStages.run(context,
                // The block is placed while the bot leans out; the transaction ends with the bot back on the middle of its origin.
                DescendTickStages.tickUntil(context, task, bot, 160,
                        "Descend did not leave a factual bridge receipt and walk back to its origin",
                        () -> context.getLevel().getBlockState(fixture.support()).is(Blocks.COBBLESTONE)
                                && bot.blockPosition().equals(fixture.origin()) && bot.getActionPack().stepIdle()),
                () -> {
                    require(context, task.state() == TaskState.RUNNING,
                            "isolated-pillar bridge ended on placement: "
                                    + task.state() + ":" + task.failureReason());
                    require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                                    == MiningBudget.EMERGENCY_STONE_LIKE,
                            "bridge did not consume exactly one block or spent the emergency reserve");
                    require(context, "0".equals(task.checkpoint().get("lateral_detours"))
                                    && task.checkpoint().get("traversed_detour_edges").isEmpty(),
                            "placement incorrectly committed movement debt: " + task.checkpoint());
                    return true;
                },
                DescendTickStages.tickUntil(context, task, bot, 80,
                        "Descend did not physically step onto its verified bridge",
                        () -> bot.blockPosition().equals(fixture.landing())
                                && "1".equals(task.checkpoint().get("lateral_detours"))),
                () -> {
                    require(context, task.state() == TaskState.RUNNING,
                            "bridge receipt was not accepted on the following tick: "
                                    + task.state() + ":" + task.failureReason());
                    require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                                    == MiningBudget.EMERGENCY_STONE_LIKE,
                            "receipt acknowledgement consumed a second support block");
                    task.cancel(bot, "gametest_complete");
                    finish(context, bot, name);
                    return true;
                });
    }

    @GameTest(maxTicks = 240)
    public void bridgeWorldReceiptSurvivesRestartWithoutDuplicateMaterial(GameTestHelper context) {
        String name = "DescendBridgeRestartGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE + 1);
        DescendToYTask first = fixture.task();
        AIPlayerEntity bot = fixture.bot();
        int[] blocksAfterPlacement = {0};
        DescendToYTask[] restoredTask = new DescendToYTask[1];

        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, first, bot, 160,
                        "restart fixture did not place its bridge receipt",
                        () -> context.getLevel().getBlockState(fixture.support()).is(Blocks.COBBLESTONE)
                                && bot.blockPosition().equals(fixture.origin()) && bot.getActionPack().stepIdle()),
                () -> {
                    blocksAfterPlacement[0] = InventoryAction.countItem(bot, Items.COBBLESTONE);
                    Map<String, String> checkpoint = first.checkpoint();
                    require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                            "bridge placement produced an invalid checkpoint: " + checkpoint);
                    first.cancel(bot, "gametest_restart");

                    DescendToYTask restored = new DescendToYTask(
                            fixture.origin().getY() - 5, checkpoint);
                    restored.start(bot);
                    restoredTask[0] = restored;
                    return true;
                },
                DescendTickStages.tickUntil(context, () -> restoredTask[0], bot, 80,
                        "restored Descend did not consume the factual support receipt",
                        () -> bot.blockPosition().equals(fixture.landing())),
                () -> {
                    DescendToYTask restored = restoredTask[0];
                    require(context, restored.state() == TaskState.RUNNING,
                            "restored bridge receipt ended unexpectedly: "
                                    + restored.state() + ":" + restored.failureReason());
                    require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                                    == blocksAfterPlacement[0],
                            "restart duplicated the support placement material");
                    require(context, context.getLevel().getBlockState(fixture.support())
                                    .is(Blocks.COBBLESTONE),
                            "restart removed or replaced its factual support receipt");

                    restored.cancel(bot, "gametest_complete");
                    finish(context, bot, name);
                    return true;
                });
    }

    @GameTest(maxTicks = 30)
    public void isolatedPillarKeepsEmergencyReserveAndFailsClosed(GameTestHelper context) {
        String name = "DescendBridgeReserveGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE);
        DescendToYTask task = fixture.task();
        AIPlayerEntity bot = fixture.bot();

        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "reserve-only isolated pillar did not fail closed: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.blockPosition().equals(fixture.origin()),
                "reserve-only failure moved off the supported origin");
        require(context, context.getLevel().getBlockState(fixture.support()).isAir(),
                "reserve-only failure placed an unauthorized bridge");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE,
                "Descend spent the protected emergency stone reserve");

        finish(context, bot, name);
    }

    @GameTest(maxTicks = 30)
    public void visibleAdjacentLavaRejectsBridgePlacement(GameTestHelper context) {
        String name = "DescendBridgeLavaGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE + 1);
        BlockPos visibleLava = fixture.support().north();
        context.getLevel().setBlock(visibleLava,
                Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        // Keep NORTH as the only replaceable missing floor. The other candidates are dangerous
        // collision supports, so rejecting the visible adjacent lava cannot silently bridge in a
        // different direction and make the test pass for the wrong reason.
        context.getLevel().setBlock(fixture.origin().east().below(),
                Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(fixture.origin().west().below(),
                Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(fixture.origin().south().below(),
                Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        DescendToYTask task = fixture.task();
        AIPlayerEntity bot = fixture.bot();
        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "lava-adjacent isolated pillar did not fail closed: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.blockPosition().equals(fixture.origin()),
                "lava-adjacent rejection moved the bot");
        require(context, context.getLevel().getBlockState(fixture.support()).isAir(),
                "Descend bridged beside visible lava");
        require(context, context.getLevel().getBlockState(visibleLava).is(Blocks.LAVA),
                "lava-adjacent rejection mutated the hazard");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE + 1,
                "lava-adjacent rejection consumed bridge material");

        finish(context, bot, name);
    }

    // Two stair steps are walked (11 game ticks each plus the tick that settles them).
    @GameTest(environment = "minecraftai-gametest:descend_checkpoint_game_tests_settled_landing_survives_safety_task_displacement", maxTicks = 120)
    public void settledLandingSurvivesSafetyTaskDisplacement(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 5, 3));
        BlockPos firstLanding = start.north().below();
        BlockPos displaced = firstLanding.east();
        BlockPos finalLanding = displaced.north().below();
        prepareLanding(context, start);
        prepareLanding(context, firstLanding);
        prepareLanding(context, displaced);
        prepareLanding(context, finalLanding);
        AIPlayerEntity bot = spawn(context, "DescendSafetyPauseGT", start);

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        task.start(bot);
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 60, "fixture did not issue the first physical landing",
                        () -> encode(firstLanding).equals(task.checkpoint().get("pending_landing_target"))),
                () -> {
                    require(context, bot.blockPosition().equals(firstLanding),
                            "fixture did not issue the first physical landing");
                    require(context, encode(firstLanding).equals(task.checkpoint().get("pending_landing_target")),
                            "fixture did not retain the unresolved first landing");

                    task.pause(bot);
                    require(context, "none".equals(task.checkpoint().get("pending_landing_origin"))
                                    && "none".equals(task.checkpoint().get("pending_landing_target")),
                            "pause retained a dry factual landing debt: " + task.checkpoint());
                    // An ordinary safety-task displacement: another controller moved the bot sideways.
                    BotFixtureMoves.place(bot, displaced);

                    task.resume(bot);
                    return true;
                },
                DescendTickStages.tickUntil(context, task, bot, 60,
                        "resumed Descend did not continue from the new factual pose",
                        () -> bot.blockPosition().equals(finalLanding)),
                () -> {
                    require(context, task.state() == TaskState.RUNNING,
                            "resumed Descend rejected the safety displacement: "
                                    + task.state() + ":" + task.failureReason());
                    return true;
                },
                DescendTickStages.tickUntil(context, task, bot, 20,
                        "resumed Descend did not hand off at the target layer",
                        () -> task.state() == TaskState.COMPLETED),
                () -> {
                    require(context, bot.blockPosition().equals(finalLanding),
                            "resumed Descend did not hand off at the final landing");
                    finish(context, bot, "DescendSafetyPauseGT");
                    return true;
                });
    }

    @GameTest(environment = "minecraftai-gametest:descend_checkpoint_game_tests_threat_pause_preserves_rejection_at_the_settled_landing", maxTicks = 90)
    public void threatPausePreservesRejectionAtTheSettledLanding(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 5, 3));
        BlockPos landing = start.north().below();
        prepareLanding(context, start);
        prepareLanding(context, landing);
        AIPlayerEntity bot = spawn(context, "DescendThreatPauseGT", start);

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        task.start(bot);
        DescendTickStages.run(context,
                DescendTickStages.tickUntil(context, task, bot, 60, "fixture did not issue the pending physical landing",
                        () -> encode(landing).equals(task.checkpoint().get("pending_landing_target"))),
                () -> {
                    require(context, bot.blockPosition().equals(landing),
                            "fixture did not issue the pending physical landing");

                    task.avoidCurrentDescentDirection(bot, landing.east());
                    Map<String, String> avoided = task.checkpoint();
                    require(context, "none".equals(avoided.get("pending_landing_origin"))
                                    && "none".equals(avoided.get("pending_landing_target")),
                            "threat routing retained a settled landing debt: " + avoided);
                    require(context, encode(landing).equals(avoided.get("rejected_landing_origin"))
                                    && (Integer.parseInt(avoided.get("rejected_landing_directions")) & 1) != 0,
                            "threat routing did not reject the current north stair: " + avoided);

                    task.pause(bot);
                    Map<String, String> paused = task.checkpoint();
                    require(context, encode(landing).equals(paused.get("rejected_landing_origin"))
                                    && (Integer.parseInt(paused.get("rejected_landing_directions")) & 1) != 0,
                            "pause erased the threat rejection at the settled landing: " + paused);
                    task.cancel(bot, "gametest_complete");
                    finish(context, bot, "DescendThreatPauseGT");
                    return true;
                });
    }

    @GameTest(maxTicks = 60)
    public void plannerOmittedDescentStillReplaysTheActiveCheckpoint(GameTestHelper context) {
        BlockPos anchor = context.absolutePos(new BlockPos(10, 2, 2));
        BlockPos mineFace = new BlockPos(anchor.getX(), -58, anchor.getZ());
        prepareLanding(context, mineFace);
        AIPlayerEntity bot = spawnPreparedMiner(context, "DescendMissionReplayGT", mineFace);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalPlanner.plan(bot, goal).steps().stream()
                        .noneMatch(step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y),
                "fixture planner still requested a fresh descent");

        Map<String, String> taskCheckpoint = new LinkedHashMap<>(freshCheckpoint(bot, -58));
        taskCheckpoint.put("budget_used", "17");
        taskCheckpoint.put("last_progress_budget", "12");
        UUID missionId = UUID.randomUUID();
        GoalExecutor.INSTANCE.restoreRuntime(bot, missionRuntime(
                missionId, goal, mineFace, GoalStep.Kind.DESCEND_TO_Y, taskCheckpoint));

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof DescendToYTask,
                "restore skipped Descend and started "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, restored.active() != null
                        && missionId.toString().equals(restored.active().missionId()),
                "descent restore changed Mission identity");
        Map<String, String> persisted = restored.active().checkpoint();
        require(context, "DESCEND_TO_Y".equals(persisted.get("task_kind"))
                        && "-58".equals(persisted.get("task.target_y"))
                        && "17".equals(persisted.get("task.budget_used"))
                        && "12".equals(persisted.get("task.last_progress_budget")),
                "descent restore changed its durable cursor: " + persisted);
        finish(context, bot, "DescendMissionReplayGT");
    }

    @GameTest(maxTicks = 60)
    public void committedDescentAtBudgetBoundaryIsAcknowledgedWithoutReplay(GameTestHelper context) {
        BlockPos anchor = context.absolutePos(new BlockPos(12, 2, 2));
        BlockPos mineFace = new BlockPos(anchor.getX(), -58, anchor.getZ());
        prepareLanding(context, mineFace);
        AIPlayerEntity bot = spawnPreparedMiner(context, "DescendCommittedRestoreGT", mineFace);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);

        DescendToYTask completed = new DescendToYTask(-58);
        completed.start(bot);
        completed.tick(bot);
        require(context, completed.state() == TaskState.COMPLETED,
                "fixture descent did not commit at the hand-off layer");
        Map<String, String> taskCheckpoint = new LinkedHashMap<>(completed.checkpoint());
        taskCheckpoint.put("budget_used", "4800");
        taskCheckpoint.put("last_progress_budget", "4799");
        require(context, DescendToYTask.inspectCheckpoint(taskCheckpoint)
                        .map(metadata -> !metadata.transactionOpen()).orElse(false),
                "fixture did not encode a committed checkpoint: " + taskCheckpoint);

        GoalExecutor.INSTANCE.restoreRuntime(bot, missionRuntime(
                UUID.randomUUID(), goal, mineFace, GoalStep.Kind.DESCEND_TO_Y, taskCheckpoint));
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active != null && !(active instanceof DescendToYTask),
                "committed Descend replayed and exposed the timeout crash window: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, GoalExecutor.INSTANCE.isActiveGoal(bot, goal),
                "committed Descend prevented the ore mission from continuing");
        MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, restored.active() != null
                        && !"DESCEND_TO_Y".equals(restored.active().checkpoint().get("task_kind")),
                "committed Descend checkpoint was not acknowledged: "
                        + (restored.active() == null ? "none" : restored.active().checkpoint()));
        finish(context, bot, "DescendCommittedRestoreGT");
    }

    @GameTest(maxTicks = 40)
    public void satisfiedGoalRejectsMissingDescendCheckpoint(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(14, 4, 2));
        prepareLanding(context, start);
        AIPlayerEntity bot = spawn(context, "DescendMissingCheckpointGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);

        GoalExecutor.INSTANCE.restoreRuntime(bot, missionRuntime(
                UUID.randomUUID(), goal, start, GoalStep.Kind.DESCEND_TO_Y, Map.of()));
        GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        require(context, result != null
                        && "mission_restore_invalid_descend_checkpoint".equals(result.reason()),
                "missing Descend checkpoint bypassed fail-closed restore: "
                        + (result == null ? "no_result" : result.reason()));
        require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                "missing Descend checkpoint restored an active Mission");
        finish(context, bot, "DescendMissingCheckpointGT");
    }

    @GameTest(maxTicks = 20)
    public void descentLightingDoesNotConsumeToolServiceSticks(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(16, 4, 2));
        prepareLanding(context, start);
        prepareLanding(context, start.north().below());
        AIPlayerEntity bot = spawn(context, "DescendTorchReserveGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "descent-lighting fixture ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                "descent lighting consumed incidental coal");
        require(context, InventoryAction.countItem(bot, Items.STICK) == 1,
                "descent lighting stole a tool-service stick");
        require(context, InventoryAction.countItem(bot, Items.TORCH) == 0,
                "descent lighting synthesized torches without a planned craft");

        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendTorchReserveGT");
    }

    private static Map<String, String> freshCheckpoint(AIPlayerEntity bot, int targetY) {
        DescendToYTask task = new DescendToYTask(targetY);
        task.start(bot);
        Map<String, String> checkpoint = task.checkpoint();
        task.cancel(bot, "gametest_checkpoint_fixture");
        return checkpoint;
    }

    private static Map<String, String> nearTerminalCheckpoint(AIPlayerEntity bot, int targetY) {
        Map<String, String> checkpoint = new LinkedHashMap<>(freshCheckpoint(bot, targetY));
        checkpoint.put("budget_used", "4799");
        checkpoint.put("last_progress_budget", "4799");
        checkpoint.put("budget_limit", "4800");
        if (DescendToYTask.inspectCheckpoint(checkpoint).isEmpty()) {
            throw new IllegalStateException("near-terminal checkpoint was invalid: " + checkpoint);
        }
        return checkpoint;
    }

    private static void requireTerminalStepReleased(GameTestHelper context, DescendToYTask task,
                                                     AIPlayerEntity bot, String failurePrefix) {
        require(context, task.state() == TaskState.FAILED && task.failureReason().startsWith(failurePrefix),
                "unexpected terminal Descend state: " + task.state() + ":" + task.failureReason());
        require(context, bot.getActionPack().stepIdle(),
                "terminal Descend failure left its walked step active");
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle()
                        && !bot.getActionPack().hasActiveActions(),
                "terminal Descend failure left a controller or movement input active");
    }

    private static MissionRuntimeRecord missionRuntime(UUID missionId,
                                                       Goal goal,
                                                       BlockPos origin,
                                                       GoalStep.Kind kind,
                                                       Map<String, String> taskCheckpoint) {
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("origin", encode(origin));
        checkpoint.put("started_tick", "0");
        checkpoint.put("revision", "0");
        checkpoint.put("task_kind", kind.name());
        taskCheckpoint.forEach((key, value) -> checkpoint.put("task." + key, value));
        return new MissionRuntimeRecord(
                new MissionRecord(missionId.toString(), MissionSpec.fromGoal(goal), checkpoint),
                List.of(), false);
    }

    private static AIPlayerEntity spawnPreparedMiner(GameTestHelper context, String name, BlockPos pos) {
        AIPlayerEntity bot = spawn(context, name, pos);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 2));
        for (int i = 0; i < 5; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        return bot;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos pos) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(pos), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    private static void prepareLanding(GameTestHelper context, BlockPos feet) {
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String encodeEdge(BlockPos origin, BlockPos target) {
        return encode(origin) + "," + encode(target);
    }

    private static IsolatedDetourFixture isolatedDetourFixture(GameTestHelper context,
                                                               String name,
                                                               int cobblestoneCount) {
        BlockPos origin = context.absolutePos(new BlockPos(18, 8, 18));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    context.getLevel().setBlock(origin.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        context.getLevel().setBlock(origin.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        AIPlayerEntity bot = spawn(context, name, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        if (cobblestoneCount > 0) {
            InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, cobblestoneCount));
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, origin.getY() - 5));
        checkpoint.put("budget_used", "1");
        checkpoint.put("last_progress_budget", "1");
        checkpoint.put("stair_direction", "0");
        checkpoint.put("rejected_landing_origin", encode(origin));
        checkpoint.put("rejected_landing_directions", "15");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "isolated-pillar fixture checkpoint was invalid: " + checkpoint);
        DescendToYTask task = new DescendToYTask(origin.getY() - 5, checkpoint);
        task.start(bot);
        BlockPos landing = origin.north();
        return new IsolatedDetourFixture(
                bot, task, origin.immutable(), landing.immutable(), landing.below().immutable());
    }

    private record IsolatedDetourFixture(AIPlayerEntity bot,
                                         DescendToYTask task,
                                         BlockPos origin,
                                         BlockPos landing,
                                         BlockPos support) {
    }

    private static void finish(GameTestHelper context, AIPlayerEntity bot, String name) {
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
