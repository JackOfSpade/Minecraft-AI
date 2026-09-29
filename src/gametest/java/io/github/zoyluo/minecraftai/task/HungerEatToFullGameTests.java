package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live scheduling proofs for the "eat to full hunger" behaviour: low (non-critical) hunger pauses
 * ordinary interruptible work to eat to a full food bar and then lets the paused work resume, but
 * defers around a protected atomic transaction until it ends. See {@link EatTask} for the
 * successive-bite loop and {@link DangerWatcher#decideEatInterrupt} (unit-tested in
 * {@code EatInterruptPolicyTest}) for the pure task-kind admission decision exercised here live.
 */
public final class HungerEatToFullGameTests {
    @GameTest(environment = "minecraftai-gametest:hunger_eat_to_full_game_tests_low_hunger_pauses_follow_eats_to_full_then_resumes", maxTicks = 400)
    public void lowHungerPausesFollowEatsToFullThenResumes(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "HungerFollowerGT", 2);
        AIPlayerEntity target = spawnOnPlatform(context, "HungerFollowTargetGT", 2);
        // Well inside FollowTask's 2.0-block STOP_DISTANCE from the start, so the bot never has to
        // path anywhere and settles into "waiting" (ActionPack idle) on the very first tick, rather
        // than sitting exactly on the stop-distance boundary -- which can stall the repath decision
        // and trip the unrelated StuckWatcher safety abort after 200 ticks.
        target.teleport(context.getWorld(),
                bot.getX() + 1.0D, bot.getY(), bot.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(10);
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));

        FollowTask follow = new FollowTask(target.getGameProfile().name());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunger_follow_pause"));

        AtomicBoolean sawFollowPausedForEat = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof EatTask) {
                if (!sawFollowPausedForEat.get()) {
                    require(context, TaskManager.INSTANCE.hasPaused(bot)
                                    && follow.state() == TaskState.PAUSED,
                            "low hunger did not pause the active FollowTask via pauseFor");
                    sawFollowPausedForEat.set(true);
                }
                if (active.state() == TaskState.FAILED) {
                    context.throwGameTestException(Text.of(
                            "eat-to-full EatTask failed: " + active.failureReason()));
                }
                return;
            }
            if (!sawFollowPausedForEat.get()) {
                return; // Still waiting for the low-hunger scan to admit the pause.
            }
            if (active == follow) {
                require(context, follow.state() == TaskState.RUNNING,
                        "resumed FollowTask was not RUNNING: " + follow.state());
                require(context, bot.getHungerManager().getFoodLevel() == 20,
                        "follow resumed before the food bar reached full: "
                                + bot.getHungerManager().getFoodLevel());
                require(context, InventoryAction.countItem(bot, Items.BREAD) < 3,
                        "food reached 20 without actually consuming any bread: "
                                + InventoryAction.countItem(bot, Items.BREAD) + " remaining");
                require(context, !TaskManager.INSTANCE.hasPaused(bot),
                        "FollowTask resumed but a stale pause frame remained");
                despawnAndComplete(context, bot, target);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:hunger_eat_to_full_game_tests_above_threshold_hunger_does_not_eat", maxTicks = 40)
    public void aboveThresholdHungerDoesNotEat(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "HungerAboveThresholdGT", 2);
        AIPlayerEntity target = spawnOnPlatform(context, "HungerAboveThresholdTargetGT", 2);
        target.teleport(context.getWorld(),
                bot.getX() + 1.0D, bot.getY(), bot.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(16); // above the default hungerEatThreshold of 14
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));

        FollowTask follow = new FollowTask(target.getGameProfile().name());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunger_above_threshold"));

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == follow,
                "hunger above threshold started eating and replaced FollowTask with "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "hunger above threshold paused FollowTask even though eating should not admit");
        require(context, follow.state() == TaskState.RUNNING,
                "FollowTask was disturbed by a no-op hunger scan: " + follow.state());
        require(context, bot.getHungerManager().getFoodLevel() == 16,
                "food level changed even though eating should not have started");
        despawnAndComplete(context, bot, target);
    }

    @GameTest(environment = "minecraftai-gametest:hunger_eat_to_full_game_tests_protected_mining_transaction_defers_eating_until_it_ends", maxTicks = 40)
    public void protectedMiningTransactionDefersEatingUntilItEnds(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "HungerProtectedTxnGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(10); // <= eat threshold, but above critical (6): not urgent
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));

        BlockPos activeBreak = bot.getBlockPos().east();
        OreDigTask oreDig = new OreDigTask(Set.of(Blocks.IRON_ORE), 1,
                oreDigCheckpoint(bot.getBlockPos(), null, activeBreak));
        TaskManager.INSTANCE.assign(bot, oreDig,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunger_protected_txn"));
        require(context, !bot.getActionPack().hasActiveActions(),
                "fixture unexpectedly started an in-flight ActionPack action");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task duringTxn = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, duringTxn == oreDig,
                "low hunger interrupted a protected mining transaction mid-break, replaced with "
                        + (duringTxn == null ? "idle" : duringTxn.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "low hunger pushed a pause frame over the protected mining transaction");
        require(context, oreDig.state() == TaskState.RUNNING,
                "protected mining transaction was disturbed by a deferred hunger scan: "
                        + oreDig.state());
        require(context, bot.getHungerManager().getFoodLevel() == 10,
                "bot ate during a protected atomic transaction");

        // The transaction ends -- eating must be admitted at the next safe gap.
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_protected_txn_ended");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                "fixture did not actually end the protected transaction");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task afterTxn = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, afterTxn instanceof EatTask,
                "eating was not admitted once the protected transaction ended: "
                        + (afterTxn == null ? "idle" : afterTxn.name()));
        despawnAndComplete(context, bot);
    }

    private static Map<String, String> oreDigCheckpoint(BlockPos face,
                                                         BlockPos pendingPickup,
                                                         BlockPos activeBreak) {
        Set<Block> ores = Set.of(Blocks.IRON_ORE);
        Map<String, String> checkpoint = new OreDigCheckpoint(
                4,
                1,
                true,
                0,
                0,
                false,
                40,
                0,
                0,
                MiningCursor.initial(face, 48),
                OreDigTask.oreFingerprint(ores),
                0,
                0,
                null,
                null,
                pendingPickup,
                pendingPickup,
                pendingPickup == null ? -1 : 0,
                pendingPickup == null ? -1 : 0,
                -1,
                activeBreak,
                activeBreak == null ? -1 : 0).encode();
        if (OreDigCheckpoint.decode(checkpoint, ores).isEmpty()) {
            throw new IllegalStateException("invalid OreDig active-break fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static AIPlayerEntity spawnOnPlatform(TestContext context, String name, int relativeY) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, relativeY, 3));
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static void despawnAndComplete(TestContext context, AIPlayerEntity... bots) {
        var server = bots[0].getEntityWorld().getServer();
        for (AIPlayerEntity bot : bots) {
            String name = bot.getGameProfile().name();
            DangerWatcher.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(server, name);
        }
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}
