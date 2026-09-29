package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.OreProspector;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The strict-survival prospect scan used to be one 180-420 ms server tick (real session, roam steps at range
 * 96). It is now a resumable {@link OreProspector.Scan} advanced a couple of milliseconds per tick. These tests
 * pin that the spread scan finds exactly what the synchronous one does and that no single tick carries the
 * scan, and print the before/after timings.
 */
public final class ProspectScanBudgetGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("prospect-budget");
    /** Generous ceiling for one budgeted step (budget 2 ms + one candidate batch + a GC/JIT hiccup); the old scan was 180-420 ms. */
    private static final long MAX_STEP_NANOS = 25_000_000L;

    @GameTest(environment = "minecraftai-gametest:prospect_scan_budget_game_tests_strict_scan_is_spread_and_matches_synchronous", maxTicks = 600)
    public void strictScanIsSpreadAndMatchesSynchronous(TestContext context) {
        Fixture fixture = fixture(context, "ProspectScanGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos log = fixture.start().east(4);
        bot.getEntityWorld().setBlockState(log, Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);

        long syncStart = System.nanoTime();
        BlockPos syncFound = OreProspector.nearest(bot, 96, state -> state.isOf(Blocks.OAK_LOG));
        long syncNanos = System.nanoTime() - syncStart;
        // A second, empty-result synchronous scan is the worst case (nothing to shortcut): the treeless roam step.
        long emptyStart = System.nanoTime();
        BlockPos emptyFound = OreProspector.nearest(bot, 96, state -> state.isOf(Blocks.BEDROCK));
        long emptyNanos = System.nanoTime() - emptyStart;
        require(context, emptyFound == null, "no bedrock is visible in the fixture, got " + emptyFound);

        OreProspector.Scan scan = OreProspector.begin(bot, 96, state -> state.isOf(Blocks.OAK_LOG), null);
        OreProspector.Scan emptyScan = OreProspector.begin(bot, 96, state -> state.isOf(Blocks.BEDROCK), null);
        context.runAtEveryTick(() -> {
            if (!scan.isDone()) {
                scan.step(2_000_000L);
                return;
            }
            if (!emptyScan.isDone()) {
                emptyScan.step(2_000_000L);
                return;
            }
            LOG.info("[prospect-budget] sync_hit_ms={} sync_empty_ms={} | budgeted hit: steps={} max_step_ms={} total_ms={} | budgeted empty: steps={} max_step_ms={} total_ms={}",
                    syncNanos / 1_000_000L, emptyNanos / 1_000_000L,
                    scan.steps(), scan.maxStepNanos() / 1_000_000L, scan.totalNanos() / 1_000_000L,
                    emptyScan.steps(), emptyScan.maxStepNanos() / 1_000_000L, emptyScan.totalNanos() / 1_000_000L);
            require(context, log.equals(syncFound), "synchronous scan missed the visible log: " + syncFound);
            require(context, log.equals(scan.result()),
                    "budgeted scan disagrees with the synchronous one: " + scan.result() + " vs " + syncFound);
            require(context, emptyScan.result() == null, "budgeted empty scan found " + emptyScan.result());
            require(context, scan.maxStepNanos() <= MAX_STEP_NANOS,
                    "one budgeted step took " + scan.maxStepNanos() / 1_000_000L + " ms");
            require(context, emptyScan.maxStepNanos() <= MAX_STEP_NANOS,
                    "one budgeted empty step took " + emptyScan.maxStepNanos() / 1_000_000L + " ms");
            if (emptyNanos > 8_000_000L) {
                require(context, emptyScan.steps() > 1,
                        "a " + emptyNanos / 1_000_000L + " ms scan was not spread across ticks");
            }
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:prospect_scan_budget_game_tests_gather_prospect_never_lands_in_one_tick", maxTicks = 700)
    public void gatherProspectNeverLandsInOneTick(TestContext context) {
        Fixture fixture = fixture(context, "ProspectGatherGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        // No log anywhere in sight: SURVEY widens to 48, then the treeless-area prospect runs.
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicInteger scanTicks = new AtomicInteger();
        AtomicLong maxScanTickNanos = new AtomicLong();
        AtomicLong maxAnyTickNanos = new AtomicLong();
        AtomicLong beginTickNanos = new AtomicLong();
        AtomicLong finishTickNanos = new AtomicLong();
        AtomicInteger scanFinishedAt = new AtomicInteger(-1);
        AtomicInteger tick = new AtomicInteger();

        context.runAtEveryTick(() -> {
            int t = tick.incrementAndGet();
            boolean activeBefore = task.prospectScanActive();
            long start = System.nanoTime();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            long spent = System.nanoTime() - start;
            maxAnyTickNanos.accumulateAndGet(spent, Math::max);
            boolean activeAfter = task.prospectScanActive();
            if (activeBefore && activeAfter) {
                // A pure scan step. The tick that begins the scan also runs survey's own radius scan (HarvestCore, out of
                // scope here) and the tick that finishes it goes on to plan the roam path, so they are reported, not gated.
                scanTicks.incrementAndGet();
                maxScanTickNanos.accumulateAndGet(spent, Math::max);
            } else if (activeAfter) {
                beginTickNanos.set(spent);
            } else if (activeBefore) {
                finishTickNanos.set(spent);
            }
            if ((activeBefore || activeAfter) && scanFinishedAt.get() < 0 && !activeAfter) {
                scanFinishedAt.set(t);
            }
            if (scanFinishedAt.get() < 0) {
                return;
            }
            LOG.info("[prospect-budget] gather: scan ticks={} max_scan_tick_ms={} (begin tick {} ms, finish tick {} ms, slowest task tick of the run {} ms)",
                    scanTicks.get(), maxScanTickNanos.get() / 1_000_000L,
                    beginTickNanos.get() / 1_000_000L, finishTickNanos.get() / 1_000_000L, maxAnyTickNanos.get() / 1_000_000L);
            require(context, scanTicks.get() >= 1, "the prospect scan never ran across ticks");
            require(context, maxScanTickNanos.get() <= MAX_STEP_NANOS,
                    "a tick carrying the prospect scan took " + maxScanTickNanos.get() / 1_000_000L + " ms");
            finish(context, fixture);
        });
    }

    private static Fixture fixture(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        // Keep every mutation inside the 8x8 empty structure (see GatherPickupGameTests.fixture).
        for (int dx = -2; dx <= 5; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return new Fixture(bot, start, name);
    }

    private static void finish(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getEntityWorld().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }
}
