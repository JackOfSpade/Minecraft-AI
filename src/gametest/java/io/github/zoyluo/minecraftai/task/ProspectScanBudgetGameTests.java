package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.HarvestCore;
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
    private static final long BUDGET_NANOS = 2_000_000L;

    /**
     * Hardware- and load-independent check that a scan was really spread. No absolute per-step millisecond ceiling
     * (a 2 ms budget can legitimately be overshot several-fold by a GC pause or a busy machine: the user plays on
     * the same box); instead the accounting the scan itself keeps:
     * <ul>
     *   <li>the average step stays within 10 budgets, so the work was cut into budget-sized pieces;</li>
     *   <li>when there was real work (at least 20 ms in at least 4 steps) no single step carried half of it; a
     *       lone hiccup cannot trip that because the many other steps sum to more than it does;</li>
     *   <li>when the synchronous run was long, the scan needed more than one step.</li>
     * </ul>
     */
    private static void requireSpread(TestContext context, String label, int steps, long maxStepNanos,
                                      long totalNanos, long syncNanos) {
        require(context, steps >= 1, label + " never stepped");
        require(context, totalNanos / steps <= 10L * BUDGET_NANOS,
                label + ": average step " + totalNanos / steps / 1_000L + " us is not budget-sized (" + steps + " steps)");
        if (steps >= 4 && totalNanos >= 20_000_000L) {
            require(context, 2L * maxStepNanos <= totalNanos,
                    label + ": one step carried " + maxStepNanos / 1_000_000L + " of " + totalNanos / 1_000_000L + " ms");
        }
        if (syncNanos > 8_000_000L) {
            require(context, steps > 1, label + ": a " + syncNanos / 1_000_000L + " ms scan was not spread across ticks");
        }
    }

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
                scan.step(BUDGET_NANOS);
                return;
            }
            if (!emptyScan.isDone()) {
                emptyScan.step(BUDGET_NANOS);
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
            requireSpread(context, "budgeted hit scan", scan.steps(), scan.maxStepNanos(), scan.totalNanos(), 0L);
            requireSpread(context, "budgeted empty scan", emptyScan.steps(), emptyScan.maxStepNanos(),
                    emptyScan.totalNanos(), emptyNanos);
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:prospect_scan_budget_game_tests_harvest_survey_scan_is_spread_and_matches_synchronous", maxTicks = 700)
    public void harvestSurveyScanIsSpreadAndMatchesSynchronous(TestContext context) {
        Fixture fixture = fixture(context, "SurveyScanGT");
        AIPlayerEntity bot = fixture.bot();
        BlockPos log = fixture.start().east(3);
        bot.getEntityWorld().setBlockState(log, Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        Set<Block> logs = Set.of(Blocks.OAK_LOG);
        Set<Block> bedrock = Set.of(Blocks.BEDROCK);

        long hitStart = System.nanoTime();
        HarvestCore.TargetChoice syncHit = HarvestCore.nearestReachableBlock(bot, logs, 48, 6, 12, null, false);
        long hitNanos = System.nanoTime() - hitStart;
        long emptyStart = System.nanoTime();
        HarvestCore.TargetChoice syncEmpty = HarvestCore.nearestReachableBlock(bot, bedrock, 48, 6, 12, null, false);
        long emptyNanos = System.nanoTime() - emptyStart;
        require(context, syncHit != null && log.equals(syncHit.pos()), "synchronous survey missed the visible log: " + syncHit);
        require(context, syncEmpty == null, "no bedrock is visible in the fixture, got " + syncEmpty);

        HarvestCore.NearestScan hit = HarvestCore.beginNearestScan(bot, logs, 48, 6, 12, null, false);
        HarvestCore.NearestScan empty = HarvestCore.beginNearestScan(bot, bedrock, 48, 6, 12, null, false);
        context.runAtEveryTick(() -> {
            if (!hit.isDone()) {
                hit.step(BUDGET_NANOS);
                return;
            }
            if (!empty.isDone()) {
                empty.step(BUDGET_NANOS);
                return;
            }
            LOG.info("[prospect-budget] survey r48: sync_hit_ms={} sync_empty_ms={} | budgeted hit: steps={} max_step_ms={} total_ms={} | budgeted empty: steps={} max_step_ms={} total_ms={}",
                    hitNanos / 1_000_000L, emptyNanos / 1_000_000L,
                    hit.steps(), hit.maxStepNanos() / 1_000_000L, hit.totalNanos() / 1_000_000L,
                    empty.steps(), empty.maxStepNanos() / 1_000_000L, empty.totalNanos() / 1_000_000L);
            require(context, hit.result() != null && syncHit.equals(hit.result()),
                    "budgeted survey disagrees with the synchronous one: " + hit.result() + " vs " + syncHit);
            require(context, empty.result() == null, "budgeted empty survey found " + empty.result());
            requireSpread(context, "budgeted survey hit", hit.steps(), hit.maxStepNanos(), hit.totalNanos(), 0L);
            requireSpread(context, "budgeted survey empty", empty.steps(), empty.maxStepNanos(), empty.totalNanos(), emptyNanos);
            finish(context, fixture);
        });
    }

    @GameTest(environment = "minecraftai-gametest:prospect_scan_budget_game_tests_gather_prospect_never_lands_in_one_tick", maxTicks = 900)
    public void gatherProspectNeverLandsInOneTick(TestContext context) {
        Fixture fixture = fixture(context, "ProspectGatherGT");
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_AXE));
        // Synchronous references for what one tick used to carry (measured, never asserted as fixed numbers).
        long surveyRef = timeNanos(() -> HarvestCore.nearestReachableBlock(bot, Set.of(Blocks.OAK_LOG), 48, 6, 12, null, false));
        long prospectRef = timeNanos(() -> OreProspector.nearest(bot, 96, state -> state.isOf(Blocks.OAK_LOG)));
        // A pure scan tick must stay far below what the synchronous scan cost. The floor keeps a fast machine (where
        // the reference itself is small) from being held to a few milliseconds that a load spike would break.
        long ceiling = Math.max(60_000_000L, Math.max(surveyRef, prospectRef) / 2L);
        // No log anywhere in sight: SURVEY widens to 32 then 48, then the treeless-area prospect runs.
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicInteger surveyScanTicks = new AtomicInteger();
        AtomicLong maxSurveyScanTickNanos = new AtomicLong();
        AtomicInteger scanTicks = new AtomicInteger();
        AtomicLong maxScanTickNanos = new AtomicLong();
        AtomicLong maxAnyTickNanos = new AtomicLong();
        AtomicLong beginTickNanos = new AtomicLong();
        AtomicLong finishTickNanos = new AtomicLong();
        AtomicInteger scanFinishedAt = new AtomicInteger(-1);
        AtomicInteger tick = new AtomicInteger();

        context.runAtEveryTick(() -> {
            int t = tick.incrementAndGet();
            boolean prospectBefore = task.prospectScanActive();
            boolean surveyBefore = task.surveyScanActive();
            long start = System.nanoTime();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            long spent = System.nanoTime() - start;
            maxAnyTickNanos.accumulateAndGet(spent, Math::max);
            boolean prospectAfter = task.prospectScanActive();
            boolean surveyAfter = task.surveyScanActive();
            if (surveyBefore && surveyAfter) {
                // A pure survey scan step: the wide HarvestCore survey is spread across ticks as well.
                surveyScanTicks.incrementAndGet();
                maxSurveyScanTickNanos.accumulateAndGet(spent, Math::max);
            }
            if (prospectBefore && prospectAfter) {
                // A pure scan step. The tick that finishes the scan goes on to plan the roam path (A*, deliberately still
                // one tick: see docs/LOGGING.md), so that tick is reported, not gated.
                scanTicks.incrementAndGet();
                maxScanTickNanos.accumulateAndGet(spent, Math::max);
            } else if (prospectAfter) {
                beginTickNanos.set(spent);
            } else if (prospectBefore) {
                finishTickNanos.set(spent);
            }
            if ((prospectBefore || prospectAfter) && scanFinishedAt.get() < 0 && !prospectAfter) {
                scanFinishedAt.set(t);
            }
            if (scanFinishedAt.get() < 0) {
                return;
            }
            LOG.info("[prospect-budget] gather: refs survey48_ms={} prospect96_ms={} ceiling_ms={} | survey scan ticks={} max_ms={} | prospect scan ticks={} max_scan_tick_ms={} (begin tick {} ms, finish tick {} ms, slowest task tick of the run {} ms)",
                    surveyRef / 1_000_000L, prospectRef / 1_000_000L, ceiling / 1_000_000L,
                    surveyScanTicks.get(), maxSurveyScanTickNanos.get() / 1_000_000L,
                    scanTicks.get(), maxScanTickNanos.get() / 1_000_000L,
                    beginTickNanos.get() / 1_000_000L, finishTickNanos.get() / 1_000_000L, maxAnyTickNanos.get() / 1_000_000L);
            require(context, scanTicks.get() >= 1, "the prospect scan never ran across ticks");
            if (surveyRef > 10_000_000L) {
                require(context, surveyScanTicks.get() >= 1,
                        "the wide survey scan (" + surveyRef / 1_000_000L + " ms synchronous) never ran across ticks");
            }
            require(context, maxSurveyScanTickNanos.get() <= ceiling,
                    "a tick carrying the survey scan took " + maxSurveyScanTickNanos.get() / 1_000_000L + " ms (ceiling " + ceiling / 1_000_000L + ")");
            require(context, maxScanTickNanos.get() <= ceiling,
                    "a tick carrying the prospect scan took " + maxScanTickNanos.get() / 1_000_000L + " ms (ceiling " + ceiling / 1_000_000L + ")");
            finish(context, fixture);
        });
    }

    private static long timeNanos(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
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
