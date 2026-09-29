package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
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
    public void lowHungerPausesFollowEatsToFullThenResumes(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "HungerFollowerGT", 2);
        AIPlayerEntity target = spawnOnPlatform(context, "HungerFollowTargetGT", 2);
        // Well inside FollowTask's 3.0-block STOP_DISTANCE from the start, so the bot never has to
        // path anywhere and settles into "waiting" (ActionPack idle) on the very first tick, rather
        // than sitting exactly on the stop-distance boundary -- which can stall the repath decision
        // and trip the unrelated StuckWatcher safety abort after 200 ticks.
        target.teleportTo(context.getLevel(),
                bot.getX() + 1.0D, bot.getY(), bot.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(10);
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));

        FollowTask follow = new FollowTask(target.getGameProfile().name());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunger_follow_pause"));

        AtomicBoolean sawFollowPausedForEat = new AtomicBoolean();
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof EatTask) {
                if (!sawFollowPausedForEat.get()) {
                    require(context, TaskManager.INSTANCE.hasPaused(bot)
                                    && follow.state() == TaskState.PAUSED,
                            "low hunger did not pause the active FollowTask via pauseFor");
                    sawFollowPausedForEat.set(true);
                }
                if (active.state() == TaskState.FAILED) {
                    context.fail(Component.nullToEmpty(
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
                require(context, bot.getFoodData().getFoodLevel() == 20,
                        "follow resumed before the food bar reached full: "
                                + bot.getFoodData().getFoodLevel());
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
    public void aboveThresholdHungerDoesNotEat(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "HungerAboveThresholdGT", 2);
        AIPlayerEntity target = spawnOnPlatform(context, "HungerAboveThresholdTargetGT", 2);
        target.teleportTo(context.getLevel(),
                bot.getX() + 1.0D, bot.getY(), bot.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(16); // above the default hungerEatThreshold of 14
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));

        FollowTask follow = new FollowTask(target.getGameProfile().name());
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunger_above_threshold"));

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == follow,
                "hunger above threshold started eating and replaced FollowTask with "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "hunger above threshold paused FollowTask even though eating should not admit");
        require(context, follow.state() == TaskState.RUNNING,
                "FollowTask was disturbed by a no-op hunger scan: " + follow.state());
        require(context, bot.getFoodData().getFoodLevel() == 16,
                "food level changed even though eating should not have started");
        despawnAndComplete(context, bot, target);
    }

    @GameTest(environment = "minecraftai-gametest:hunger_eat_to_full_game_tests_protected_mining_transaction_defers_eating_until_it_ends", maxTicks = 40)
    public void protectedMiningTransactionDefersEatingUntilItEnds(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "HungerProtectedTxnGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(10); // <= eat threshold, but above critical (6): not urgent
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));

        BlockPos activeBreak = bot.blockPosition().east();
        OreDigTask oreDig = new OreDigTask(Set.of(Blocks.IRON_ORE), 1,
                oreDigCheckpoint(bot.blockPosition(), null, activeBreak));
        TaskManager.INSTANCE.assign(bot, oreDig,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunger_protected_txn"));
        require(context, !bot.getActionPack().hasActiveActions(),
                "fixture unexpectedly started an in-flight ActionPack action");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task duringTxn = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, duringTxn == oreDig,
                "low hunger interrupted a protected mining transaction mid-break, replaced with "
                        + (duringTxn == null ? "idle" : duringTxn.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "low hunger pushed a pause frame over the protected mining transaction");
        require(context, oreDig.state() == TaskState.RUNNING,
                "protected mining transaction was disturbed by a deferred hunger scan: "
                        + oreDig.state());
        require(context, bot.getFoodData().getFoodLevel() == 10,
                "bot ate during a protected atomic transaction");

        // The transaction ends -- eating must be admitted at the next safe gap.
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_protected_txn_ended");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                "fixture did not actually end the protected transaction");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

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

    private static AIPlayerEntity spawnOnPlatform(GameTestHelper context, String name, int relativeY) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, relativeY, 3));
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static void despawnAndComplete(GameTestHelper context, AIPlayerEntity... bots) {
        var server = bots[0].level().getServer();
        for (AIPlayerEntity bot : bots) {
            String name = bot.getGameProfile().name();
            DangerWatcher.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(server, name);
        }
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
