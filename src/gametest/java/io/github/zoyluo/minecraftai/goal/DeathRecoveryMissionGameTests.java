package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.RecoverDropsTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.UUID;

/** Deterministic death suspension coverage for active and queued mining missions. */
public final class DeathRecoveryMissionGameTests {
    private static final int SUSPENDED_ASSERT_TICK = 10;
    private static final int RESUMED_ASSERT_TICK = 115;
    // A mining mission does not complete the tick its drop arrives: OreDigTask keeps a quiet pickup window
    // (PICKUP_GRACE_TICKS, 30) after its last drop before it reports the quota done. So each completion is waited for, with room
    // for that window, instead of being asserted at a fixed tick.
    private static final int RESULT_WAIT_TICKS = 80;

    @GameTest(maxTicks = 300)
    public void mineOreSurvivesDeathRecoveryAndPreservesQueuedHaveItem(GameTestHelper context) {
        runScenario(
                context,
                "DeathMineGT",
                new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1),
                Items.RAW_IRON,
                new Goal.HaveItem(Items.SWEET_BERRIES, 1),
                Items.SWEET_BERRIES);
    }

    @GameTest(maxTicks = 300)
    public void haveItemSurvivesDeathRecoveryAndPreservesQueuedMineOre(GameTestHelper context) {
        runScenario(
                context,
                "DeathItemGT",
                new Goal.HaveItem(Items.SWEET_BERRIES, 1),
                Items.SWEET_BERRIES,
                new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1),
                Items.RAW_IRON);
    }

    private static void runScenario(GameTestHelper context,
                                    String botName,
                                    Goal activeGoal,
                                    Item activeReward,
                                    Goal queuedGoal,
                                    Item queuedReward) {
        Probe probe = startSuspendedMission(context, botName, activeGoal, queuedGoal);

        context.runAtTickTime(SUSPENDED_ASSERT_TICK, () -> assertSuspended(probe));
        context.runAtTickTime(RESUMED_ASSERT_TICK, () -> {
            assertResumed(probe);
            InventoryAction.giveItem(probe.bot(), new ItemStack(activeReward, 1));
            whenResultAfter(probe, probe.resultBaseline(), () -> {
                assertActiveMissionCompletedAndQueuePromoted(probe);
                long activeSequence = latestSequence(probe);
                InventoryAction.giveItem(probe.bot(), new ItemStack(queuedReward, 1));
                whenResultAfter(probe, activeSequence, () -> {
                    assertAllCompleted(probe);
                    cleanup(probe);
                    context.succeed();
                });
            });
        });
    }

    private static long latestSequence(Probe probe) {
        return GoalExecutor.INSTANCE.lastResult(probe.bot()).map(GoalResult::sequence).orElse(0L);
    }

    /**
     * Runs {@code next} on the tick a result newer than {@code sequence} exists. If none comes within RESULT_WAIT_TICKS it runs
     * anyway, so that its own assertions name what is missing.
     */
    private static void whenResultAfter(Probe probe, long sequence, Runnable next) {
        waitForResult(probe, sequence, RESULT_WAIT_TICKS, next);
    }

    private static void waitForResult(Probe probe, long sequence, int ticksLeft, Runnable next) {
        if (latestSequence(probe) > sequence || ticksLeft <= 0) {
            next.run();
            return;
        }
        probe.context().runAfterDelay(1, () -> waitForResult(probe, sequence, ticksLeft - 1, next));
    }

    private static Probe startSuspendedMission(GameTestHelper context,
                                               String botName,
                                               Goal activeGoal,
                                               Goal queuedGoal) {
        var world = context.getLevel();
        BlockPos cell = context.absolutePos(new BlockPos(1, 2, 1));
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3.atBottomCenterOf(cell),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        bot.teleportTo(world, cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));

        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot).map(GoalResult::sequence).orElse(0L);
        require(context, GoalExecutor.INSTANCE.submit(bot, activeGoal), "active goal setup failed: " + activeGoal);
        require(context, GoalExecutor.INSTANCE.submit(bot, queuedGoal), "queued goal setup failed: " + queuedGoal);
        MissionRuntimeRecord initial = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, initial.active() != null, "active mission was not captured");
        UUID missionId = UUID.fromString(initial.active().missionId());
        require(context, initial.queue().size() == 1, "queue was not established before death");

        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        MissionRuntimeRecord suspended = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, suspended.active() != null
                        && missionId.toString().equals(suspended.active().missionId()),
                "death suspension changed mission identity");
        require(context, suspended.queue().size() == 1, "death suspension dropped queued mission");
        require(context, GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).isEmpty(),
                "death suspension published a terminal result");

        TaskManager.INSTANCE.assign(bot,
                new RecoverDropsTask(bot.blockPosition(), bot.level().getServer().getTickCount()),
                TaskOrigin.safety("gametest_death_recovery"));
        return new Probe(context, botName, bot, activeGoal, queuedGoal, missionId, resultBaseline);
    }

    private static void assertSuspended(Probe probe) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(probe.bot());
        require(probe, GoalExecutor.INSTANCE.hasActivePlan(probe.bot()), "suspended mission is no longer visible");
        require(probe, runtime.active() != null
                        && probe.missionId().toString().equals(runtime.active().missionId()),
                "suspended missionId changed");
        require(probe, runtime.queue().size() == 1, "suspended queue was lost");
        require(probe, TaskManager.INSTANCE.getActive(probe.bot())
                        .filter(RecoverDropsTask.class::isInstance).isPresent(),
                "RecoverDropsTask no longer owns the safety slot");
        require(probe, TaskManager.INSTANCE.activeOrigin(probe.bot()).map(TaskOrigin::safety).orElse(false),
                "recovery task does not have SAFETY origin");
        require(probe, GoalExecutor.INSTANCE.resultAfter(probe.bot(), probe.resultBaseline()).isEmpty(),
                "suspended mission published FAILED/CANCELLED");
    }

    private static void assertResumed(Probe probe) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(probe.bot());
        require(probe, runtime.active() != null
                        && probe.missionId().toString().equals(runtime.active().missionId()),
                "resumed mission did not keep its missionId");
        require(probe, GoalExecutor.INSTANCE.isActiveGoal(probe.bot(), probe.activeGoal()),
                "original goal was not restored after recovery");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 1,
                "queued mission was not restored after recovery");
        require(probe, TaskManager.INSTANCE.activeOrigin(probe.bot())
                        .map(origin -> origin.kind() == TaskOrigin.Kind.MISSION
                                && probe.missionId().equals(origin.missionId()))
                        .orElse(false),
                "restored task is not owned by the original mission");
        require(probe, GoalExecutor.INSTANCE.resultAfter(probe.bot(), probe.resultBaseline()).isEmpty(),
                "mission published a terminal result before resumed work completed");
    }

    private static void assertActiveMissionCompletedAndQueuePromoted(Probe probe) {
        GoalResult result = GoalExecutor.INSTANCE.lastResult(probe.bot())
                .orElseThrow(() -> failure(probe, "missing result for resumed mission"));
        require(probe, result.sequence() > probe.resultBaseline(), "result sequence did not advance");
        require(probe, result.missionId().equals(probe.missionId()), "completed result changed missionId");
        require(probe, result.goal().equals(probe.activeGoal()), "wrong goal completed after recovery");
        require(probe, result.status() == GoalResult.Status.COMPLETED,
                "resumed mission ended as " + result.status() + ": " + result.reason());
        require(probe, GoalExecutor.INSTANCE.isActiveGoal(probe.bot(), probe.queuedGoal()),
                "preserved queued mission was not promoted");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 0,
                "promoted queue still contains a duplicate mission");
    }

    private static void assertAllCompleted(Probe probe) {
        GoalResult result = GoalExecutor.INSTANCE.lastResult(probe.bot())
                .orElseThrow(() -> failure(probe, "missing result for preserved queued mission"));
        require(probe, result.goal().equals(probe.queuedGoal()), "wrong queued goal completed");
        require(probe, result.status() == GoalResult.Status.COMPLETED,
                "queued mission ended as " + result.status() + ": " + result.reason());
        require(probe, !GoalExecutor.INSTANCE.hasActivePlan(probe.bot()), "mission remained active after completion");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 0, "queue was not drained");
    }

    private static void prepareCell(net.minecraft.server.level.ServerLevel world, BlockPos center) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlock(center.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        world.setBlock(center, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(center.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void cleanup(Probe probe) {
        AIPlayerManager.INSTANCE.despawn(probe.bot().level().getServer(), probe.botName());
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private static void require(Probe probe, boolean condition, String message) {
        if (!condition) {
            cleanup(probe);
            probe.context().fail(Component.nullToEmpty(message));
        }
    }

    private static RuntimeException failure(Probe probe, String message) {
        cleanup(probe);
        return new IllegalStateException(message);
    }

    private record Probe(GameTestHelper context,
                         String botName,
                         AIPlayerEntity bot,
                         Goal activeGoal,
                         Goal queuedGoal,
                         UUID missionId,
                         long resultBaseline) {
    }
}
