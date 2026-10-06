package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.KnownCellPickupSweep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * The stalls of a real gather session that cost time without moving the quota: a break the bot lost sight
 * of waited out the whole harvest deadline, and the pickup sweep tried every cell of a canopy it could not see.
 */
public final class GatherStallGameTests {
    /** One block a bot loses sight of mid-break must end the harvest on the spot, not after 240 ticks. */
    @GameTest(environment = "minecraftai-gametest:gather_stall_game_tests_log_that_leaves_sight_mid_break_is_dropped_at_once", maxTicks = 300)
    public void logThatLeavesSightMidBreakIsDroppedAtOnce(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherSightLostGT", 5);
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos log = fixture.start().east(3);
        bot.level().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicBoolean enclosed = new AtomicBoolean();
        AtomicInteger ticksSinceEnclosed = new AtomicInteger();

        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (!enclosed.get()) {
                if (!task.describe().contains("phase=HARVEST")) {
                    return;
                }
                // The log is in reach and its break has begun; now every neighbour of it becomes solid, as when
                // leaves or another block close in on a target the bot was chopping.
                require(context, !bot.blockPosition().equals(log.west()),
                        "fixture: the bot must not stand where the enclosure goes");
                for (BlockPos around : List.of(log.west(), log.east(), log.north(), log.south(), log.above())) {
                    bot.level().setBlock(around, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                enclosed.set(true);
                return;
            }
            int ticks = ticksSinceEnclosed.incrementAndGet();
            if (task.describe().contains("phase=HARVEST")) {
                require(context, ticks <= 10, "an unseen log was still being 'harvested' " + ticks
                        + " ticks after it was enclosed (the harvest deadline is 240): " + task.describe());
                return;
            }
            require(context, task.state() == TaskState.RUNNING, "the gather ended instead of re-planning: "
                    + task.state() + ":" + task.failureReason());
            require(context, EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), log,
                            bot.level().getServer().getTickCount()),
                    "the lost target was not excluded, so the survey would pick it again at once");
            List<String> lines = SensingArena.botLog(fixture.name());
            require(context, lines != null && lines.stream().anyMatch(line -> line.contains("event=gather_harvest_refused")
                            && line.contains("target_not_observed")),
                    "the refusal reason was not logged");
            finish(context, fixture);
        });
    }

    /** A break cell 4 blocks overhead, with a floor of its own above a platform: nothing there is in the bot's view. */
    @GameTest(environment = "minecraftai-gametest:gather_stall_game_tests_pickup_sweep_ignores_canopy_cells_it_cannot_see", maxTicks = 200)
    public void pickupSweepIgnoresCanopyCellsItCannotSee(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherSweepUnseenGT", 2);
        AIPlayerEntity bot = fixture.bot();
        BlockPos start = fixture.start();
        // A solid platform three blocks above the bot: the cells on top of it are standable, but the bot, standing
        // beneath it, cannot see them. The sweep around a break cell up there used to try every one of them.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                bot.level().setBlock(start.offset(dx, 3, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos origin = start.above(4);
        KnownCellPickupSweep sweep = new KnownCellPickupSweep(origin);
        BlockPos standingAt = bot.blockPosition();
        AtomicInteger ticks = new AtomicInteger();

        context.failIfEver(() -> {
            // A fresh bot sees nothing until its chunk tracking view is set up; judge the sweep only once it
            // sees an ordinary neighbouring cell, so that "cannot see" below means "hidden by the platform".
            if (!ObservableWorldQuery.canObserveCell(bot, start.east())) {
                return;
            }
            require(context, Standability.isStandable(bot.level(), origin)
                            && !ObservableWorldQuery.canObserveCell(bot, origin),
                    "fixture: the cell on the platform must be standable yet hidden from the bot");
            require(context, bot.blockPosition().equals(standingAt) && bot.getActionPack().isPathExecutorIdle(),
                    "the sweep sent the bot after a cell it cannot see: " + bot.blockPosition());
            KnownCellPickupSweep.Step step = sweep.step(bot);
            if (step != KnownCellPickupSweep.Step.EXHAUSTED) {
                require(context, ticks.incrementAndGet() < 5,
                        "the sweep is still working through cells nobody can see: " + step);
                return;
            }
            require(context, sweep.cellsVisited() == 0, "the sweep visited " + sweep.cellsVisited() + " unseen cells");
            List<String> lines = SensingArena.botLog(fixture.name());
            require(context, lines != null, "the per-bot log is unavailable, so the sweep cannot be judged");
            require(context, lines.stream().noneMatch(line -> line.contains("event=pickup_path_unobserved_endpoint")),
                    "the sweep asked for a route to a cell it could not see");
            finish(context, fixture);
        });
    }

    private static Fixture fixture(GameTestHelper context, String name, int east) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(2, 2, 2));
        // Keep every mutation inside the 8x8 empty structure; see GatherPickupGameTests.
        for (int dx = -2; dx <= east; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return new Fixture(bot, start, name);
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

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }
}
