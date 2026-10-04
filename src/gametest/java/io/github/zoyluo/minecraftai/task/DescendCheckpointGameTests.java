package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.goal.GoalStep;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.persist.MissionRecord;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
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

/** Strict-survival retirement coverage for persisted DESCEND_TO_Y routes. */
public final class DescendCheckpointGameTests {
    @GameTest(maxTicks = 40)
    public void directDescendRefusesWithoutPhysicalMutation(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start);
        AIPlayerEntity bot = spawn(context, "DescendRetirementGT", start);
        Map<BlockPos, BlockState> before = snapshot(context, start);
        DescendToYTask task = new DescendToYTask(start.getY() - 2);

        task.start(bot);
        require(context, task.state() == TaskState.FAILED,
                "direct Descend did not fail closed: " + task.state());
        require(context, RetiredNavigationTask.OBSERVED_TARGET_REQUIRED.equals(task.failureReason()),
                "direct Descend lost its typed refusal: " + task.failureReason());
        require(context, bot.blockPosition().equals(start),
                "direct Descend moved the bot before refusing");
        require(context, snapshot(context, start).equals(before),
                "direct Descend mutated the world before refusing");
        finish(context, bot, "DescendRetirementGT");
    }

    @GameTest(maxTicks = 40)
    public void retainedDescendCheckpointIsRejectedEvenWhenGoalIsSatisfied(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start);
        AIPlayerEntity bot = spawn(context, "DescendMissingCheckpointGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("origin", encode(start));
        checkpoint.put("started_tick", "0");
        checkpoint.put("revision", "0");
        checkpoint.put("task_kind", GoalStep.Kind.DESCEND_TO_Y.name());

        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(), MissionSpec.fromGoal(goal), checkpoint),
                List.of(), false));
        GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        require(context, result != null
                        && "mission_restore_invalid_descend_checkpoint".equals(result.reason()),
                "retained DESCEND checkpoint bypassed typed rejection: " + result);
        require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                "invalid DESCEND checkpoint restored an active mission");
        finish(context, bot, "DescendMissingCheckpointGT");
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper context, BlockPos center) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    result.put(pos, context.getLevel().getBlockState(pos));
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void preparePlatform(GameTestHelper context, BlockPos start) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getLevel().setBlock(start.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(start.offset(dx, 0, dz),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(start.offset(dx, 1, dz),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
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
