package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression coverage for the MineTask pickup-completion action-pack cleanup.
 *
 * <p>Bots have no forced pickup (no profile can vacuum a drop into the inventory), so {@code MineTask.pickup()} detects a successful
 * collection only by polling an inventory-count delta while {@code HarvestCore.chaseDropAnyOf}
 * (via {@code approachDropPhysically} / {@code FakePlayerMotion.nudgeWithinBlockToward}) chases the
 * drop. That chase can leave {@code ActionPack.sneaking} held true on the very tick vanilla's own
 * proximity pickup lands. If {@code pickup()} then transitions away (here: completes) without first
 * calling {@code stopAll()}, the dangling sneak makes {@code ActionPack.hasActiveActions()} report
 * true forever, which permanently blocks {@code DangerWatcher.scanBot}'s paused-task resume (it
 * requires actions to be idle before resuming) and deadlocks whatever task was paused beneath
 * MineTask. See {@code CraftTask#finishReclaim} for the canonical write-up of this bug class, first
 * found and fixed there.
 *
 * <p><b>Why the sneak is injected rather than awaited:</b> {@code FakePlayerMotion
 * .nudgeWithinBlockToward} only ever fires when the drop is grounded (it never fires on a still
 * -falling item) AND the bot is already standing in the exact resolved pickup cell. Empirically (see
 * this test's own git history), an ordinary single-block exposed-ore mine -- adjacent, diagonal, or
 * directly overhead -- always resolves via vanilla's own eager proximity pickup (checked every tick
 * against the bot's own hitbox, which is 1.8 blocks tall) before that same-cell nudge is ever
 * reached, whatever the geometry. Reliably forcing the exact sub-block settle-position needed to
 * defeat that eagerness would need a fragile, physics-dependent fixture (an off-centre item resting
 * against an obstacle) for no real gain in coverage. This test instead drives a completely real
 * mine-and-pickup cycle -- real {@code BlockMiner}, real vanilla drop, real vanilla proximity pickup,
 * real {@code MineTask.pickup()} inventory-count polling -- and directly holds {@code
 * ActionPack.sneaking} true (the exact flag {@code nudgeWithinBlockToward} sets) for the whole
 * pre-pickup window, reproducing the documented precondition deterministically instead of chasing
 * incidental block-physics timing.</p>
 */
public final class MinePickupGameTests {
    @GameTest(environment = "minecraftai-gametest:mine_pickup_game_tests_paused_task_resumes_after_natural_pickup_nudge", maxTicks = 400)
    public void pausedTaskResumesAfterNaturalPickupNudge(GameTestHelper context) {
        Fixture fixture = spawnMiner(context, "MinePickupDeadlockGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos ore = fixture.start().east(2);
        bot.level().setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);

        assertStrictCapabilities(context, bot);

        // Manufactures "some other task paused beneath the active MineTask" exactly the way this
        // codebase's own GameTests already do it: pause a currently-active placeholder task, then
        // hand a new task the active slot. See DangerWatcherLowHealthGameTests
        // #pausedMiningOwnerResuppliesInPlaceWithoutBaseTravel for the established pattern this
        // mirrors (there DigDownTask is the paused owner; HoldTask is used the same way as a bare
        // paused-task fixture elsewhere in this codebase, e.g. MinecraftAiVerifySubcommand).
        HoldTask pausedOwner = new HoldTask();
        TaskManager.INSTANCE.assign(bot, pausedOwner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_mine_pickup_paused_owner"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_mine_pickup_setup");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == pausedOwner
                        && pausedOwner.state() == TaskState.PAUSED,
                "fixture did not preserve the paused owner beneath MineTask");

        MineTask mineTask = new MineTask(Blocks.IRON_ORE, 1);
        TaskManager.INSTANCE.assign(bot, mineTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_mine_pickup_deadlock"));

        int[] ticksSinceMineCompleted = {-1};
        AtomicBoolean observedMidNudgeSneak = new AtomicBoolean();
        context.failIfEver(() -> {
            if (mineTask.state() == TaskState.FAILED || mineTask.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty(
                        "MineTask ended as " + mineTask.state() + ":" + mineTask.failureReason()));
            }
            if (mineTask.state() != TaskState.COMPLETED) {
                require(context, TaskManager.INSTANCE.peekPaused(bot).orElse(null) == pausedOwner
                                && pausedOwner.state() == TaskState.PAUSED,
                        "paused owner was disturbed while MineTask was still mining/picking up");
                // Reproduces the exact dangling flag FakePlayerMotion.nudgeWithinBlockToward
                // leaves behind (see the class javadoc for why this is injected rather than
                // awaited): once the real block break has happened but before the real vanilla
                // pickup lands, hold it true, so whichever tick MineTask.pickup() next polls its
                // inventory delta true it finds sneaking already dangling, exactly as a real
                // mid-chase nudge would leave it. Scoped to strictly after the break so it can
                // never influence the real search/move/mine phases beforehand.
                if (bot.level().getBlockState(ore).isAir()
                        && InventoryAction.countItem(bot, Items.RAW_IRON) == 0) {
                    bot.getActionPack().setSneaking(true);
                    observedMidNudgeSneak.set(true);
                }
                return;
            }
            require(context, observedMidNudgeSneak.get(),
                    "fixture never reached a tick with the mid pickup-nudge sneak held before "
                            + "MineTask completed");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) >= 1,
                    "MineTask completed without actually collecting the ore drop");
            // The most direct assertion of the fix itself: the moment pickup() detects success and
            // completes, the action pack must already be clean -- not merely "clean soon".
            require(context, !bot.getActionPack().hasActiveActions(),
                    "MineTask completed while a dangling action-pack flag (e.g. sneaking) was "
                            + "still held: sneaking=" + bot.isShiftKeyDown());
            ticksSinceMineCompleted[0]++;
            // TaskManager.tickAll runs before BotTickCoordinator (and its DangerWatcher scan) in
            // the same server tick, and TpsGuard's normal (non-degraded) danger-scan interval is
            // every tick, so a correctly cleaned-up action pack resumes the paused owner within the
            // very same tick MineTask completes. Bound the wait generously (30 ticks) so the
            // assertion tolerates incidental scheduling without masking a genuine permanent
            // deadlock -- the whole point of this regression.
            require(context, ticksSinceMineCompleted[0] <= 30,
                    "paused owner never resumed after MineTask completed -- hasActiveActions()="
                            + bot.getActionPack().hasActiveActions()
                            + " hasPaused=" + TaskManager.INSTANCE.hasPaused(bot));
            if (TaskManager.INSTANCE.getActive(bot).orElse(null) != pausedOwner) {
                return;
            }
            require(context, !TaskManager.INSTANCE.hasPaused(bot)
                            && pausedOwner.state() == TaskState.RUNNING,
                    "paused owner became active without a clean resume");
            finish(context, fixture);
        });
    }

    private static Fixture spawnMiner(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(2, 2, 2));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(feet.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
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
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        return new Fixture(name, bot, start.immutable());
    }

    private static void assertStrictCapabilities(GameTestHelper context, AIPlayerEntity bot) {
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "mine_pickup_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static void finish(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos start) {
    }
}
