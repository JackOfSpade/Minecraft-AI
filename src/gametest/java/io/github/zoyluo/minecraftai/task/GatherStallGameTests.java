package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.KnownCellPickupSweep;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
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

    /**
     * A guarded step (a swim, a rescue) keeps a fence in front of every other controller until its owner has reconciled it,
     * so a break started meanwhile is not refused but merely not begun. The gather took that for a refusal of the block:
     * it dropped a good log for thirty seconds and went looking for another. It must wait, and chop once the fence lifts.
     */
    @GameTest(environment = "minecraftai-gametest:gather_stall_game_tests_log_waits_for_a_guarded_step_to_release_its_fence", maxTicks = 400)
    public void logWaitsForAGuardedStepToReleaseItsFence(GameTestHelper context) {
        Fixture fixture = fixture(context, "GatherFenceGT", 5);
        AIPlayerEntity bot = fixture.bot();
        ActionPack pack = bot.getActionPack();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        BlockPos log = fixture.start().east(2);
        bot.level().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        // A guarded step that has run to its end but whose owner has not released it yet: the pack stays fenced.
        ActionPack.StepLease lease = pack.runStep(
                WalkedStep.begin(bot, fixture.start().west(), WalkedStep.Kind.FLAT, "gametest_fence"),
                (stepBot, step) -> true);
        require(context, lease != null, "fixture: the guarded step was not admitted");
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicInteger ticksToReachBreak = new AtomicInteger();
        AtomicInteger fencedTicks = new AtomicInteger();
        AtomicInteger ticksSinceRelease = new AtomicInteger();

        context.failIfEver(() -> {
            if (!started.get()) {
                if (!pack.stepIdle()) {
                    return; // the guarded step is still walking
                }
                require(context, pack.baritoneControlBlocked(),
                        "fixture: the finished guarded step must keep its fence until its owner releases it");
                task.start(bot);
                started.set(true);
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            boolean harvesting = task.describe().contains("phase=HARVEST");
            if (!released.get()) {
                if (fencedTicks.get() == 0 && !harvesting) {
                    require(context, ticksToReachBreak.incrementAndGet() <= 10,
                            "the gather never reached the break: " + task.describe());
                    return;
                }
                require(context, harvesting, "the gather gave up the log it was waiting to break after "
                        + fencedTicks.get() + " fenced ticks: " + task.describe());
                require(context, bot.level().getBlockState(log).is(Blocks.OAK_LOG),
                        "the log was broken although the guarded step still held the fence");
                if (fencedTicks.incrementAndGet() >= 20) {
                    require(context, pack.releaseStepLease(lease), "fixture: the owner could not release its lease");
                    released.set(true);
                }
                return;
            }
            if (bot.level().getBlockState(log).isAir()) {
                List<String> lines = SensingArena.botLog(fixture.name());
                require(context, lines != null && lines.stream().noneMatch(line -> line.contains("event=gather_harvest_refused")),
                        "the held-back break was logged as a refusal of the block");
                finish(context, fixture);
                return;
            }
            require(context, ticksSinceRelease.incrementAndGet() <= 100,
                    "the log still stood " + ticksSinceRelease.get() + " ticks after the fence was lifted: " + task.describe());
        });
    }

    /**
     * Gather with a log in view that no pillar can reach: the pillar searches (for the sighted log, and over the whole
     * volume around the bot) come back empty, and the survey asks again on every tick while its look-around is pending.
     * Each search casts rays for dozens of columns or tens of thousands of cells, so a bot that has not moved must not
     * repeat either of them.
     */
    @GameTest(environment = "minecraftai-gametest:gather_stall_game_tests_empty_pillar_searches_are_not_repeated_while_the_bot_stands_still", maxTicks = 300)
    public void emptyPillarSearchesAreNotRepeatedWhileTheBotStandsStill(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 12, 12);
        // The bot stands in a one-cell pit of a stone slab whose rim it cannot see from the bottom (no column around
        // the log has a floor it has seen), and a log floats ten blocks straight above it, in plain view but out of
        // reach. Its own column would do for a tower, but an invisible light block in it is not air, so no pillar can
        // be planned there either: nothing is climbable from anywhere, and that finding must be kept.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx != 0 || dz != 0) {
                    fixture.arena.fill(dx, dz, Blocks.STONE, 0, 4);
                }
            }
        }
        fixture.arena.set(0, 10, 0, Blocks.OAK_LOG);
        fixture.arena.set(0, 6, 0, Blocks.LIGHT);
        String name = "PillarMemoGT";
        AIPlayerEntity bot = fixture.bot(name, 0, 0, false);
        fixture.give(bot, new ItemStack(Items.WOODEN_AXE));
        // An exact break keeps the survey in place while its look-around is pending (about a thousand ticks).
        GatherQuotaTask task = GatherQuotaTask.breakBlocks(Blocks.OAK_LOG, 1);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_gather_pillar_memo"));
        BlockPos stance = bot.blockPosition().immutable();

        context.failIfEver(() -> {
            fixture.require(bot.blockPosition().equals(stance), "the bot left its pit: " + bot.blockPosition());
            fixture.require(task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED,
                    "the gather ended as " + task.state() + ":" + task.failureReason());
            if (context.getTick() < 100) {
                return;
            }
            fixture.require(task.describe().contains("phase=SURVEY"),
                    "fixture: the survey must still be asking for pillars every tick: " + task.describe());
            List<String> lines = SensingArena.botLog(name);
            fixture.require(lines != null, "the per-bot log is unavailable, so the searches cannot be counted");
            long hint = lines.stream().filter(line -> line.contains("event=gather_pillar_scan_empty")
                    && line.contains("search='hint'")).count();
            long volume = lines.stream().filter(line -> line.contains("event=gather_pillar_scan_empty")
                    && line.contains("search='volume'")).count();
            fixture.require(hint == 1, "the search for the sighted log ran " + hint + " times in 100 ticks at one stance");
            fixture.require(volume == 1, "the search of the whole volume ran " + volume + " times in 100 ticks at one stance");
            fixture.finish();
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
