package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.goal.GoalStep;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.pathfinding.MoveType;
import io.github.zoyluo.minecraftai.pathfinding.Node;
import io.github.zoyluo.minecraftai.pathfinding.PathExecutor;
import io.github.zoyluo.minecraftai.persist.MissionRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Strict-survival coverage for retired descent checkpoints and factual pillar repair. */
public final class DigDownReturnGameTests {
    @GameTest(maxTicks = 40)
    public void directDigDownRefusesWithoutPhysicalMutation(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownDirectRetirementGT", start);
        Map<BlockPos, BlockState> before = snapshot(context, start, 2);

        requireStrictSurvival(context);
        DigDownTask task = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_retired_direct"));

        assertRetiredWithoutMutation(context, bot, task, start, before, "direct DigDown");
        finish(context, bot, "DigDownDirectRetirementGT");
    }

    @GameTest(maxTicks = 40)
    public void restoredDigDownCheckpointRefusesWithoutPhysicalMutation(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownRestoredRetirementGT", start);
        Map<BlockPos, BlockState> before = snapshot(context, start, 2);

        requireStrictSurvival(context);
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                strictDescentCheckpoint(start, 3));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_retired_restore"));

        assertRetiredWithoutMutation(context, bot, task, start, before, "restored DigDown");
        finish(context, bot, "DigDownRestoredRetirementGT");
    }

    @GameTest(maxTicks = 40)
    public void retainedGoalExecutorDescendRefusesWithoutPhysicalMutation(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "GoalDescendRetirementGT", start);
        Map<BlockPos, BlockState> before = snapshot(context, start, 2);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 1);
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("origin", encode(start));
        checkpoint.put("started_tick", String.valueOf(bot.level().getServer().getTickCount()));
        checkpoint.put("revision", "0");
        checkpoint.put("task_kind", GoalStep.Kind.DESCEND_TO_Y.name());

        requireStrictSurvival(context);
        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(),
                        MissionSpec.fromGoal(goal), checkpoint), List.of(), false));

        GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        require(context, result != null
                        && "mission_restore_invalid_descend_checkpoint".equals(result.reason()),
                "retained DESCEND did not preserve its typed restore refusal: "
                        + (result == null ? "no_result" : result.reason()));
        require(context, bot.blockPosition().equals(start),
                "retained DESCEND moved the bot: " + bot.blockPosition().toShortString());
        require(context, snapshot(context, start, 2).equals(before),
                "retained DESCEND changed the world before refusing");
        finish(context, bot, "GoalDescendRetirementGT");
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
                        MissionSpec.fromGoal(goal), missionCheckpoint), List.of(), false));
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
            if (!result.isSuccess()) {
                return;
            }
            require(context, context.getLevel().getBlockState(start).is(Blocks.DIRT),
                    "pillar repair placed " + context.getLevel().getBlockState(start));
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 1,
                    "pillar repair consumed the wrong dirt count");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 6,
                    "pillar repair spent mission cobblestone while dirt was available");
            executor.abort(bot.getActionPack());
            finish(context, bot, "DigDownPillarMaterialGT");
        });
    }

    private static void assertRetiredWithoutMutation(GameTestHelper context,
                                                      AIPlayerEntity bot,
                                                      Task task,
                                                      BlockPos expectedPosition,
                                                      Map<BlockPos, BlockState> before,
                                                      String operation) {
        require(context, task.state() == TaskState.FAILED,
                operation + " did not fail closed: " + task.state());
        require(context, RetiredNavigationTask.OBSERVED_TARGET_REQUIRED.equals(task.failureReason()),
                operation + " lost its typed refusal: " + task.failureReason());
        require(context, bot.blockPosition().equals(expectedPosition),
                operation + " moved the bot to " + bot.blockPosition().toShortString());
        require(context, snapshot(context, expectedPosition, 2).equals(before),
                operation + " changed the world before refusing");
    }

    private static Map<String, String> strictDescentCheckpoint(BlockPos start, int targetCount) {
        return new DigDownTask.DigDownCheckpoint(
                4, "minecraft:stone", targetCount, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 0, 200, 190, 0, 0, 0, false,
                null, 0, List.of(start), -1, 0, -20, false,
                0, -1, -1L, false).encode();
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper context,
                                                        BlockPos center,
                                                        int radius) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    blocks.put(pos, context.getLevel().getBlockState(pos));
                }
            }
        }
        return Map.copyOf(blocks);
    }

    private static void requireStrictSurvival(GameTestHelper context) {
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival");
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
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(pos), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
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
