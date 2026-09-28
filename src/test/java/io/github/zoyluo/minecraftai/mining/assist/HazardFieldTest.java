package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.mining.assist.HazardField.Cell;
import io.github.zoyluo.minecraftai.mining.assist.HazardField.Kind;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HazardFieldTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    // ---- lava permanence (I15, A5) -------------------------------------------------------------

    @Test
    void lavaNeverAgesByTimeEvenOverAnAbsurdGap() {
        HazardField field = new HazardField();
        BlockPos lava = at(10, 12, -30);
        field.observe(lava, Kind.LAVA, 5);

        assertEquals(0, field.expire(5));
        assertEquals(0, field.expire(5 + HazardField.WATER_MAX_AGE_TICKS + 1));
        assertEquals(0, field.expire(5 + HazardField.TRAP_MAX_AGE_TICKS + 1));
        assertEquals(0, field.expire(1_000_000));
        assertEquals(0, field.expire(Integer.MAX_VALUE));

        assertTrue(field.isLava(lava));
        assertEquals(Kind.LAVA, field.kindAt(lava));
        assertTrue(field.anyLavaWithin(lava, 0));
        assertEquals(1, field.count());
    }

    @Test
    void expireDropsWaterAndTrapButNeverLavaInTheSameSweep() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.LAVA, 0);
        field.observe(at(1, 0, 0), Kind.WATER, 0);
        field.observe(at(2, 0, 0), Kind.TRAP, 0);

        assertEquals(1, field.expire(1201));
        assertEquals(Kind.LAVA, field.kindAt(at(0, 0, 0)));
        assertNull(field.kindAt(at(1, 0, 0)));
        assertEquals(Kind.TRAP, field.kindAt(at(2, 0, 0)));

        assertEquals(1, field.expire(3601));
        assertEquals(Kind.LAVA, field.kindAt(at(0, 0, 0)));
        assertNull(field.kindAt(at(2, 0, 0)));
        assertEquals(1, field.count());
    }

    /**
     * The A5 defect: a 64-entry ring with a 100-tick age-out lost lava as soon as the sweep got slower
     * than 100 ticks (throttled by many sweeping bots or a headroom halving) or other sightings
     * churned the ring. The lava cell is seen once per sweep, or never again; the field must hold it
     * at every tick regardless of the effective ray rate.
     */
    @Test
    void lavaSurvivesEveryThrottledRayRateBetweenSweeps() {
        int lattice = 2048;
        int[] raysPerTick = {40, 20, 10, 5, 2, 1};
        for (int rate : raysPerTick) {
            int sweepPeriod = (lattice + rate - 1) / rate;
            HazardField field = new HazardField();
            BlockPos lava = at(-3, 11, 8);
            field.observe(lava, Kind.LAVA, 100);
            int horizon = 100 + sweepPeriod * 3;
            for (int tick = 101; tick <= horizon; tick++) {
                if ((tick - 100) % sweepPeriod == 0) {
                    field.observe(lava, Kind.LAVA, tick);
                }
                // Other sightings churn the field the whole time.
                field.observe(at(500 + tick % 40, 10, tick % 7), Kind.WATER, tick);
                if (tick % 20 == 0) {
                    field.expire(tick);
                }
                assertTrue(field.isLava(lava), "rate " + rate + " lost lava at tick " + tick);
                assertTrue(field.anyLavaWithin(at(0, 11, 8), 4), "rate " + rate + " tick " + tick);
            }
            if (rate <= 20) {
                assertTrue(sweepPeriod > 100, "rate " + rate + " must exceed the old 100-tick horizon");
            }
        }
    }

    @Test
    void lavaNeverSeenAgainStaysForeverWhileUnrelatedWaterChurns() {
        HazardField field = new HazardField();
        BlockPos lava = at(7, -40, 7);
        field.observe(lava, Kind.LAVA, 0);
        for (int tick = 1; tick <= 20_000; tick++) {
            field.observe(at(100, -40, tick % 500), Kind.WATER, tick);
            if (tick % 20 == 0) {
                field.expire(tick);
            }
        }
        assertTrue(field.isLava(lava));
        // Only water seen within the last 1200 ticks can remain: at most ~500 distinct cells.
        assertTrue(field.count(Kind.WATER) <= 500);
    }

    @Test
    void waterRefreshedEachSweepPersistsButNotWhenTheSweepIsSlowerThanItsAgeOut() {
        HazardField fast = new HazardField();
        HazardField slow = new HazardField();
        BlockPos water = at(4, 4, 4);
        fast.observe(water, Kind.WATER, 0);
        slow.observe(water, Kind.WATER, 0);
        boolean slowLostIt = false;
        for (int tick = 1; tick <= 6000; tick++) {
            if (tick % 1024 == 0) {           // 2 rays per tick: one sweep per 1024 ticks
                fast.observe(water, Kind.WATER, tick);
            }
            if (tick % 2048 == 0) {           // 1 ray per tick: one sweep per 2048 ticks
                slow.observe(water, Kind.WATER, tick);
            }
            fast.expire(tick);
            slow.expire(tick);
            assertEquals(Kind.WATER, fast.kindAt(water), "fast lost water at " + tick);
            if (slow.kindAt(water) == null) {
                slowLostIt = true;
            }
        }
        assertTrue(slowLostIt, "water older than 1200 ticks must age out between slow sweeps");
    }

    // ---- re-observation and kind rules ---------------------------------------------------------

    @Test
    void nonFluidReobservationRemovesLavaAndAllowsItToReturn() {
        HazardField field = new HazardField();
        BlockPos lava = at(1, 2, 3);
        assertTrue(field.observe(lava, Kind.LAVA, 10));

        assertTrue(field.observeClear(lava, 500));
        assertNull(field.kindAt(lava));
        assertFalse(field.anyLavaWithin(lava, 5));
        assertEquals(0, field.count());
        assertFalse(field.observeClear(lava, 501), "clearing a missing cell is a no-op");

        assertTrue(field.observe(lava, Kind.LAVA, 600));
        assertTrue(field.isLava(lava));
    }

    @Test
    void reobservationRemovesWaterAndTrapCellsToo() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.WATER, 0);
        field.observe(at(1, 0, 0), Kind.TRAP, 0);
        assertTrue(field.observeClear(at(0, 0, 0), 1));
        assertTrue(field.observeClear(at(1, 0, 0), 1));
        assertTrue(field.isEmpty());
    }

    @Test
    void staleClearOlderThanTheLastSightingIsIgnored() {
        HazardField field = new HazardField();
        BlockPos lava = at(0, 0, 0);
        field.observe(lava, Kind.LAVA, 100);
        assertFalse(field.observeClear(lava, 99));
        assertTrue(field.isLava(lava));
        assertTrue(field.observeClear(lava, 100), "same-tick clear applies");
        assertNull(field.kindAt(lava));
    }

    @Test
    void clearingOneCellLeavesItsBucketNeighboursAlone() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.LAVA, 0);
        field.observe(at(1, 0, 0), Kind.LAVA, 0);
        assertTrue(field.observeClear(at(0, 0, 0), 1));
        assertTrue(field.isLava(at(1, 0, 0)));
        assertEquals(1, field.count(Kind.LAVA));
    }

    @Test
    void lavaDominatesOtherKindsAndUpgradesThem() {
        HazardField field = new HazardField();
        BlockPos p = at(2, 2, 2);

        assertTrue(field.observe(p, Kind.LAVA, 10));
        assertFalse(field.observe(p, Kind.WATER, 20), "water cannot downgrade lava");
        assertFalse(field.observe(p, Kind.TRAP, 30), "trap cannot downgrade lava");
        assertEquals(Kind.LAVA, field.kindAt(p));

        BlockPos q = at(3, 2, 2);
        field.observe(q, Kind.WATER, 100);
        assertTrue(field.observe(q, Kind.LAVA, 90), "lava upgrades water even from an older observation");
        Cell cell = field.cellAt(q);
        assertEquals(Kind.LAVA, cell.kind());
        assertEquals(100, cell.lastTick());
        assertEquals(2, field.count(Kind.LAVA));
        assertEquals(0, field.count(Kind.WATER));
    }

    @Test
    void waterAndTrapConflictNewestObservationWins() {
        HazardField field = new HazardField();
        BlockPos p = at(0, 0, 0);
        field.observe(p, Kind.WATER, 100);

        assertFalse(field.observe(p, Kind.TRAP, 50), "older observation is ignored");
        assertEquals(Kind.WATER, field.kindAt(p));

        assertTrue(field.observe(p, Kind.TRAP, 150));
        assertEquals(Kind.TRAP, field.kindAt(p));
        assertEquals(150, field.cellAt(p).lastTick());
        assertEquals(0, field.count(Kind.WATER));
        assertEquals(1, field.count(Kind.TRAP));
    }

    @Test
    void refreshKeepsTheNewestTickAndReportsNoChange() {
        HazardField field = new HazardField();
        BlockPos p = at(9, 9, 9);
        assertTrue(field.observe(p, Kind.WATER, 100));
        assertFalse(field.observe(p, Kind.WATER, 200));
        assertEquals(200, field.cellAt(p).lastTick());
        assertFalse(field.observe(p, Kind.WATER, 150));
        assertEquals(200, field.cellAt(p).lastTick(), "an older tick never rewinds the age");
        assertEquals(1, field.count());
    }

    // ---- ageing boundaries ---------------------------------------------------------------------

    @Test
    void waterAgesOutStrictlyAfterTwelveHundredTicks() {
        HazardField field = new HazardField();
        BlockPos p = at(0, 0, 0);
        field.observe(p, Kind.WATER, 100);
        assertEquals(0, field.expire(100));
        assertEquals(0, field.expire(1299));
        assertEquals(0, field.expire(1300), "age exactly 1200 is kept");
        assertEquals(Kind.WATER, field.kindAt(p));
        assertEquals(1, field.expire(1301));
        assertNull(field.kindAt(p));
    }

    @Test
    void trapAgesOutStrictlyAfterThirtySixHundredTicks() {
        HazardField field = new HazardField();
        BlockPos p = at(0, 0, 0);
        field.observe(p, Kind.TRAP, 100);
        assertEquals(0, field.expire(1301), "a trap outlives the water horizon");
        assertEquals(0, field.expire(3700), "age exactly 3600 is kept");
        assertEquals(1, field.expire(3701));
        assertTrue(field.isEmpty());
    }

    @Test
    void refreshingAWaterCellResetsItsAge() {
        HazardField field = new HazardField();
        BlockPos p = at(0, 0, 0);
        field.observe(p, Kind.WATER, 0);
        field.observe(p, Kind.WATER, 1000);
        assertEquals(0, field.expire(2200));
        assertEquals(1, field.expire(2201));
    }

    @Test
    void expireHandlesFutureStampsAndMixedBuckets() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.WATER, 5000);  // stamped after "now": age is negative, kept
        field.observe(at(1, 0, 0), Kind.WATER, 0);
        field.observe(at(2, 0, 0), Kind.LAVA, 0);      // same bucket as both
        assertEquals(1, field.expire(1500));
        assertEquals(2, field.count());
        assertEquals(Kind.WATER, field.kindAt(at(0, 0, 0)));
        assertTrue(field.isLava(at(2, 0, 0)));
    }

    // ---- capacity ------------------------------------------------------------------------------

    @Test
    void atExactlyTheCapNothingIsEvicted() {
        HazardField field = new HazardField();
        for (int i = 0; i < HazardField.CAP; i++) {
            field.observe(at(i, 64, 0), Kind.WATER, 0);
        }
        assertEquals(HazardField.CAP, field.count());
        assertEquals(0, field.evictIfOver(ORIGIN));
        assertEquals(HazardField.CAP, field.count());
    }

    @Test
    void overTheCapEvictsTheTwoHundredFiftySixFarthestCellsFarthestFirst() {
        HazardField field = new HazardField();
        for (int i = 0; i <= HazardField.CAP; i++) {          // 4097 cells at x = 0..4096
            field.observe(at(i, 64, 0), i % 2 == 0 ? Kind.WATER : Kind.TRAP, 0);
        }
        assertEquals(HazardField.CAP + 1, field.count());

        assertEquals(HazardField.EVICT_BATCH, field.evictIfOver(ORIGIN));

        int kept = HazardField.CAP + 1 - HazardField.EVICT_BATCH;   // 3841
        assertEquals(kept, field.count());
        for (int i = 0; i < kept; i++) {
            assertTrue(field.kindAt(at(i, 64, 0)) != null, "near cell " + i + " must survive");
        }
        for (int i = kept; i <= HazardField.CAP; i++) {
            assertNull(field.kindAt(at(i, 64, 0)), "far cell " + i + " must be evicted");
        }
        assertEquals(0, field.evictIfOver(ORIGIN), "back under the cap: nothing more to do");
    }

    @Test
    void evictionIsRelativeToTheBotNotTheFieldCentroid() {
        HazardField field = new HazardField();
        for (int i = 0; i <= HazardField.CAP; i++) {
            field.observe(at(i, 64, 0), Kind.WATER, 0);
        }
        BlockPos farBot = at(HazardField.CAP, 64, 0);
        field.evictIfOver(farBot);
        // The cells nearest x = 0 are now the farthest from the bot.
        for (int i = 0; i < HazardField.EVICT_BATCH; i++) {
            assertNull(field.kindAt(at(i, 64, 0)));
        }
        assertTrue(field.kindAt(at(HazardField.EVICT_BATCH, 64, 0)) != null);
        assertTrue(field.kindAt(farBot) != null);
    }

    @Test
    void lavaIsNotExemptFromEvictionButLosesTiesToOtherKinds() {
        // Lava at +k (k = 1..2049), water at -k (k = 1..2048): 4097 cells, symmetric around the bot.
        // The 256 farthest are LAVA(2049), pairs k = 2048..1922, and half of the k = 1921 pair, which
        // must be the WATER cell because equal distance evicts non-lava first.
        HazardField field = new HazardField();
        for (int k = 1; k <= 2049; k++) {
            field.observe(at(k, 64, 0), Kind.LAVA, 0);
        }
        for (int k = 1; k <= 2048; k++) {
            field.observe(at(-k, 64, 0), Kind.WATER, 0);
        }
        assertEquals(HazardField.CAP + 1, field.count());

        assertEquals(HazardField.EVICT_BATCH, field.evictIfOver(ORIGIN));

        assertNull(field.kindAt(at(2049, 64, 0)), "far lava is evicted like any other cell");
        assertNull(field.kindAt(at(2048, 64, 0)));
        assertNull(field.kindAt(at(-2048, 64, 0)));
        assertNull(field.kindAt(at(-1921, 64, 0)), "water loses the tie at distance 1921");
        assertEquals(Kind.LAVA, field.kindAt(at(1921, 64, 0)), "lava wins the tie at distance 1921");
        assertEquals(Kind.WATER, field.kindAt(at(-1920, 64, 0)));
        assertEquals(Kind.LAVA, field.kindAt(at(1920, 64, 0)));
    }

    @Test
    void evictionResultDoesNotDependOnInsertionOrder() {
        List<BlockPos> cells = new ArrayList<>();
        for (int k = 1; k <= 2049; k++) {
            cells.add(at(k, 64, 0));
            if (k <= 2048) {
                cells.add(at(-k, 64, 0));
            }
        }
        List<BlockPos> shuffled = new ArrayList<>(cells);
        SplittableRandom random = new SplittableRandom(99);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            Collections.swap(shuffled, i, random.nextInt(i + 1));
        }

        HazardField a = new HazardField();
        HazardField b = new HazardField();
        for (BlockPos p : cells) {
            a.observe(p, p.getX() > 0 ? Kind.LAVA : Kind.WATER, 0);
        }
        for (BlockPos p : shuffled) {
            b.observe(p, p.getX() > 0 ? Kind.LAVA : Kind.WATER, 0);
        }
        a.evictIfOver(ORIGIN);
        b.evictIfOver(ORIGIN);
        assertEquals(a.snapshot(), b.snapshot());
    }

    @Test
    void hardCeilingBoundsTheFieldEvenIfTheCallerNeverEvicts() {
        HazardField field = new HazardField();
        int limit = HazardField.CAP + HazardField.EVICT_BATCH;
        for (int i = 0; i < 6000; i++) {
            field.observe(at(i, 64, 0), Kind.WATER, i);
            assertTrue(field.count() <= limit, "count " + field.count() + " at " + i);
            assertEquals(Kind.WATER, field.kindAt(at(i, 64, 0)), "the newest cell is never its own victim");
        }
        // Eviction was relative to each new observation: the oldest (smallest x) went first.
        assertNull(field.kindAt(at(0, 64, 0)));
        assertTrue(field.count() > HazardField.CAP - HazardField.EVICT_BATCH);
    }

    // ---- radius queries ------------------------------------------------------------------------

    @Test
    void radiusQueriesAtBucketBoundariesOnBothSidesOfZero() {
        HazardField field = new HazardField();
        // x = 3 | 4 straddles a bucket boundary; x = -1 | 0 straddles the origin; -4 | -5 negative side.
        field.observe(at(3, 0, 0), Kind.LAVA, 0);
        field.observe(at(4, 0, 0), Kind.LAVA, 0);
        field.observe(at(-1, 0, 0), Kind.WATER, 0);
        field.observe(at(-5, 0, 0), Kind.TRAP, 0);

        assertTrue(field.anyLavaWithin(at(3, 0, 0), 0));
        assertTrue(field.anyLavaWithin(at(4, 0, 0), 0));
        assertFalse(field.anyLavaWithin(at(5, 0, 0), 0));
        assertTrue(field.anyLavaWithin(at(5, 0, 0), 1));
        assertFalse(field.anyLavaWithin(at(6, 0, 0), 1), "cell at 4 is two blocks from 6");
        assertTrue(field.anyLavaWithin(at(6, 0, 0), 2));
        assertTrue(field.anyLavaWithin(at(2, 0, 0), 1));
        assertFalse(field.anyLavaWithin(at(1, 0, 0), 1), "cell at 3 is two blocks from 1");
        assertEquals(List.of(at(3, 0, 0), at(4, 0, 0)), field.lavaCellsWithin(at(3, 0, 0), 1));
        assertEquals(List.of(at(3, 0, 0)), field.lavaCellsWithin(at(2, 0, 0), 1));

        assertTrue(field.anyWithin(Kind.WATER, at(0, 0, 0), 1));
        assertFalse(field.anyWithin(Kind.WATER, at(1, 0, 0), 1));
        assertTrue(field.anyWithin(Kind.TRAP, at(-4, 0, 0), 1));
        assertTrue(field.anyTrapWithin(at(-8, 0, 0), 3));
        assertFalse(field.anyTrapWithin(at(-9, 0, 0), 3));
        assertFalse(field.anyLavaWithin(at(-5, 0, 0), 2), "lava query ignores water and trap cells");
    }

    @Test
    void radiusIsAChebyshevCubeNotASphere() {
        HazardField field = new HazardField();
        field.observe(at(4, 68, 4), Kind.LAVA, 0);
        BlockPos centre = at(0, 64, 0);
        assertTrue(field.anyLavaWithin(centre, 4), "the cube corner is inside");
        assertFalse(field.anyLavaWithin(centre, 3));
        assertEquals(48L, field.nearestLavaDistanceSq(centre, 4), "corner is sqrt(48) away, still inside the cube");
        assertEquals(HazardField.NONE, field.nearestLavaDistanceSq(centre, 3));
    }

    @Test
    void negativeRadiusAndMissingKindsFindNothing() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.LAVA, 0);
        assertFalse(field.anyLavaWithin(at(0, 0, 0), -1));
        assertEquals(HazardField.NONE, field.nearestLavaDistanceSq(at(0, 0, 0), -1));
        assertTrue(field.lavaCellsWithin(at(0, 0, 0), -3).isEmpty());
        assertFalse(field.anyTrapWithin(at(0, 0, 0), 100));
        assertTrue(field.cellsWithin(Kind.WATER, at(0, 0, 0), 100).isEmpty());
        assertFalse(new HazardField().anyLavaWithin(at(0, 0, 0), 1000));
    }

    @Test
    void nearestDistanceAndOrderedCellList() {
        HazardField field = new HazardField();
        field.observe(at(10, 0, 0), Kind.LAVA, 0);
        field.observe(at(0, 0, 3), Kind.LAVA, 0);
        field.observe(at(0, 3, 0), Kind.LAVA, 0);     // ties with (0,0,3) on distance
        field.observe(at(-3, 0, 0), Kind.LAVA, 0);    // also distance 3
        BlockPos c = at(0, 0, 0);

        assertEquals(9L, field.nearestLavaDistanceSq(c, 20));
        // Tie-break is BlockPos order: y, then z, then x.
        assertEquals(List.of(at(-3, 0, 0), at(0, 0, 3), at(0, 3, 0), at(10, 0, 0)), field.lavaCellsWithin(c, 20));
        assertEquals(List.of(at(-3, 0, 0), at(0, 0, 3), at(0, 3, 0)), field.lavaCellsWithin(c, 9));
    }

    @Test
    void queriesWorkAtLargeAndNegativeCoordinates() {
        HazardField field = new HazardField();
        BlockPos far = at(29_999_999, -60, -29_999_999);
        field.observe(far, Kind.LAVA, 0);
        assertTrue(field.anyLavaWithin(far, 0));
        assertTrue(field.anyLavaWithin(far.add(-3, 0, 3), 3));
        assertFalse(field.anyLavaWithin(far.add(-4, 0, 4), 3));
        assertFalse(field.anyLavaWithin(ORIGIN, 4));
        assertTrue(field.anyLavaWithin(ORIGIN, Integer.MAX_VALUE), "an enormous radius must not overflow");
        assertEquals(List.of(far), field.lavaCellsWithin(ORIGIN, Integer.MAX_VALUE));
    }

    @Test
    void radiusQueriesMatchABruteForceReferenceOnRandomFields() {
        SplittableRandom random = new SplittableRandom(20250926L);
        HazardField field = new HazardField();
        Map<BlockPos, Kind> reference = new HashMap<>();
        Kind[] kinds = Kind.values();
        for (int i = 0; i < 700; i++) {
            BlockPos p = at(random.nextInt(-25, 26), random.nextInt(-25, 26), random.nextInt(-25, 26));
            Kind kind = kinds[random.nextInt(kinds.length)];
            field.observe(p, kind, i);
            if (reference.get(p) != Kind.LAVA) {
                reference.put(p, kind);   // lava is sticky; WATER <-> TRAP newest wins (ticks increase here)
            }
        }
        for (int i = 0; i < 150; i++) {
            BlockPos p = at(random.nextInt(-25, 26), random.nextInt(-25, 26), random.nextInt(-25, 26));
            if (random.nextBoolean() && reference.containsKey(p)) {
                assertTrue(field.observeClear(p, 10_000));
                reference.remove(p);
            }
        }
        assertEquals(reference.size(), field.count());

        for (int i = 0; i < 400; i++) {
            BlockPos centre = at(random.nextInt(-30, 31), random.nextInt(-30, 31), random.nextInt(-30, 31));
            int radius = random.nextInt(0, 21);
            for (Kind kind : kinds) {
                List<BlockPos> expected = new ArrayList<>();
                long nearest = HazardField.NONE;
                for (Map.Entry<BlockPos, Kind> e : reference.entrySet()) {
                    BlockPos p = e.getKey();
                    if (e.getValue() != kind
                            || Math.abs(p.getX() - centre.getX()) > radius
                            || Math.abs(p.getY() - centre.getY()) > radius
                            || Math.abs(p.getZ() - centre.getZ()) > radius) {
                        continue;
                    }
                    expected.add(p);
                    long d = (long) p.getSquaredDistance(centre);
                    if (nearest == HazardField.NONE || d < nearest) {
                        nearest = d;
                    }
                }
                expected.sort(Comparator.comparingLong((BlockPos p) -> (long) p.getSquaredDistance(centre))
                        .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)
                        .thenComparingInt(BlockPos::getX));
                String label = kind + " " + centre + " r" + radius;
                assertEquals(!expected.isEmpty(), field.anyWithin(kind, centre, radius), label);
                assertEquals(nearest, field.nearestDistanceSq(kind, centre, radius), label);
                assertEquals(expected, field.cellsWithin(kind, centre, radius), label);
            }
        }
    }

    @Test
    void radiusQueriesTouchOnlyTheBucketsThatOverlapTheCube() {
        HazardField field = new HazardField();
        // One lava cell in every 4x4x4 bucket of a 16x16x16 block grid of buckets: 4096 buckets, 4096 cells.
        for (int i = -8; i < 8; i++) {
            for (int j = -8; j < 8; j++) {
                for (int k = -8; k < 8; k++) {
                    field.observe(at(4 * i, 4 * j, 4 * k), Kind.LAVA, 0);
                }
            }
        }
        assertEquals(HazardField.CAP, field.count());

        long before = field.bucketProbes();
        assertFalse(field.anyLavaWithin(at(2, 2, 2), 1), "cube [1,3]^3 lies inside one bucket and misses its cell");
        assertEquals(1L, field.bucketProbes() - before);

        before = field.bucketProbes();
        assertFalse(field.anyLavaWithin(at(-2, -2, -2), 1), "cube [-3,-1]^3 is entirely bucket (-1,-1,-1)");
        assertEquals(1L, field.bucketProbes() - before);

        before = field.bucketProbes();
        assertTrue(field.anyLavaWithin(at(3, 3, 3), 1), "cube [2,4]^3 straddles 8 buckets and reaches (4,4,4)");
        assertTrue(field.bucketProbes() - before <= 8L);

        before = field.bucketProbes();
        field.lavaCellsWithin(at(0, 0, 0), 4);
        assertEquals(27L, field.bucketProbes() - before, "cube [-4,4]^3 spans 3 buckets per axis");
    }

    @Test
    void hugeRadiusOverAFewBucketsProbesNoMoreThanTheBucketsThatExist() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.LAVA, 0);
        field.observe(at(1000, 0, 0), Kind.LAVA, 0);
        field.observe(at(0, 0, -1000), Kind.WATER, 0);

        long before = field.bucketProbes();
        assertEquals(2, field.lavaCellsWithin(at(0, 0, 0), 5000).size());
        assertTrue(field.bucketProbes() - before <= 3L);
    }

    // ---- bookkeeping ---------------------------------------------------------------------------

    @Test
    void countsSnapshotAndClear() {
        HazardField field = new HazardField();
        assertTrue(field.isEmpty());
        field.observe(at(5, 5, 5), Kind.TRAP, 7);
        field.observe(at(-5, 5, 5), Kind.LAVA, 8);
        field.observe(at(0, 0, 0), Kind.WATER, 9);

        assertEquals(3, field.count());
        assertEquals(1, field.count(Kind.LAVA));
        assertEquals(1, field.count(Kind.WATER));
        assertEquals(1, field.count(Kind.TRAP));
        assertEquals(List.of(
                        new Cell(at(0, 0, 0), Kind.WATER, 9),
                        new Cell(at(-5, 5, 5), Kind.LAVA, 8),
                        new Cell(at(5, 5, 5), Kind.TRAP, 7)),
                field.snapshot());

        field.clear();
        assertTrue(field.isEmpty());
        assertEquals(0, field.count(Kind.LAVA));
        assertFalse(field.anyLavaWithin(at(-5, 5, 5), 10));
        assertNull(field.kindAt(at(5, 5, 5)));
    }

    @Test
    void mutableBlockPosIsCopiedNotRetained() {
        HazardField field = new HazardField();
        BlockPos.Mutable cursor = new BlockPos.Mutable(1, 2, 3);
        field.observe(cursor, Kind.LAVA, 0);
        cursor.set(9, 9, 9);
        assertTrue(field.isLava(at(1, 2, 3)));
        assertFalse(field.isLava(at(9, 9, 9)));
        assertEquals(at(1, 2, 3), field.snapshot().get(0).pos());
    }

    @Test
    void identicalOperationSequencesGiveIdenticalState() {
        HazardField a = drive(7L);
        HazardField b = drive(7L);
        assertEquals(a.snapshot(), b.snapshot());
        assertEquals(a.lavaCellsWithin(ORIGIN, 30), b.lavaCellsWithin(ORIGIN, 30));
        assertFalse(a.snapshot().equals(drive(8L).snapshot()), "a different seed must give a different field");
    }

    private static HazardField drive(long seed) {
        HazardField field = new HazardField();
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < 2000; i++) {
            BlockPos p = at(random.nextInt(-40, 40), random.nextInt(-20, 20), random.nextInt(-40, 40));
            Kind kind = Kind.values()[random.nextInt(3)];
            if (random.nextInt(10) == 0) {
                field.observeClear(p, i);
            } else {
                field.observe(p, kind, i);
            }
            if (i % 50 == 0) {
                field.expire(i);
            }
        }
        return field;
    }

    @Test
    void fastPathsOnAnEmptyField() {
        HazardField field = new HazardField();
        assertNull(field.kindAt(ORIGIN));
        assertNull(field.cellAt(ORIGIN));
        assertFalse(field.isLava(ORIGIN));
        assertEquals(0, field.expire(1_000_000));
        assertEquals(0, field.evictIfOver(ORIGIN));
        long before = field.bucketProbes();
        assertFalse(field.anyLavaWithin(ORIGIN, 64));
        assertEquals(before, field.bucketProbes(), "no lava stored: the query never touches a bucket");
    }

    // ---- adversarial review additions ----------------------------------------------------------

    @Test
    void passThroughRayClearsFluidButNeverForgetsATrap() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.LAVA, 10);
        field.observe(at(1, 0, 0), Kind.WATER, 10);
        field.observe(at(2, 0, 0), Kind.TRAP, 10);

        assertFalse(field.observeNotFluid(at(2, 0, 0), 20), "a collider ray passes through a pressure plate");
        assertEquals(Kind.TRAP, field.kindAt(at(2, 0, 0)));
        assertFalse(field.observeNotFluid(at(0, 0, 0), 9), "a stale pass-through cannot contradict the lava");
        assertTrue(field.isLava(at(0, 0, 0)));
        assertFalse(field.observeNotFluid(at(5, 5, 5), 20), "an empty cell is a no-op");

        assertTrue(field.observeNotFluid(at(0, 0, 0), 10), "same-tick pass-through applies");
        assertTrue(field.observeNotFluid(at(1, 0, 0), 11));
        assertFalse(field.observeNotFluid(at(1, 0, 0), 12), "already gone");
        assertEquals(1, field.count());
        assertEquals(0, field.count(Kind.LAVA) + field.count(Kind.WATER));

        // A trap-only field short-circuits, and observeClear (block state actually read) still removes it.
        assertFalse(field.observeNotFluid(at(2, 0, 0), 30));
        assertTrue(field.observeClear(at(2, 0, 0), 30));
        assertTrue(field.isEmpty());
        assertFalse(field.observeNotFluid(at(2, 0, 0), 31));
    }

    @Test
    void nullArgumentsAreRejected() {
        HazardField field = new HazardField();
        assertThrows(NullPointerException.class, () -> field.observe(null, Kind.LAVA, 0));
        assertThrows(NullPointerException.class, () -> field.observe(ORIGIN, null, 0));
        assertThrows(NullPointerException.class, () -> field.observeClear(null, 0));
        assertThrows(NullPointerException.class, () -> field.observeNotFluid(null, 0));
        assertThrows(NullPointerException.class, () -> field.kindAt(null));
        assertThrows(NullPointerException.class, () -> field.cellAt(null));
        assertThrows(NullPointerException.class, () -> field.evictIfOver(null));
        assertThrows(NullPointerException.class, () -> field.anyWithin(null, ORIGIN, 1));
        assertThrows(NullPointerException.class, () -> field.anyWithin(Kind.LAVA, null, 1));
        assertThrows(NullPointerException.class, () -> field.nearestDistanceSq(Kind.LAVA, null, 1));
        assertThrows(NullPointerException.class, () -> field.cellsWithin(Kind.LAVA, null, 1));
        assertTrue(field.isEmpty());
    }

    @Test
    void ageArithmeticDoesNotOverflowAtIntegerExtremes() {
        HazardField field = new HazardField();
        field.observe(at(0, 0, 0), Kind.WATER, Integer.MIN_VALUE);
        field.observe(at(1, 0, 0), Kind.TRAP, Integer.MIN_VALUE);
        field.observe(at(2, 0, 0), Kind.LAVA, Integer.MIN_VALUE);
        field.observe(at(3, 0, 0), Kind.WATER, Integer.MAX_VALUE);
        assertEquals(2, field.expire(Integer.MAX_VALUE), "an int subtraction would wrap and keep both");
        assertTrue(field.isLava(at(2, 0, 0)));
        assertEquals(Kind.WATER, field.kindAt(at(3, 0, 0)));
    }

    @Test
    void radiusBoundariesHoldOnEveryAxisAndSignAroundBucketEdges() {
        int[] edges = {-9, -8, -5, -4, -3, -1, 0, 3, 4, 7, 8};
        for (int axis = 0; axis < 3; axis++) {
            for (int edge : edges) {
                HazardField field = new HazardField();
                field.observe(onAxis(axis, edge), Kind.LAVA, 0);
                for (int c = -14; c <= 14; c++) {
                    for (int r = 0; r <= 5; r++) {
                        boolean expected = Math.abs(c - edge) <= r;
                        BlockPos centre = onAxis(axis, c);
                        String label = "axis " + axis + " cell " + edge + " centre " + c + " r " + r;
                        assertEquals(expected, field.anyLavaWithin(centre, r), label);
                        assertEquals(expected ? 1 : 0, field.lavaCellsWithin(centre, r).size(), label);
                        assertEquals(expected ? (long) (c - edge) * (c - edge) : HazardField.NONE,
                                field.nearestLavaDistanceSq(centre, r), label);
                    }
                }
            }
        }
    }

    private static BlockPos onAxis(int axis, int value) {
        return axis == 0 ? at(value, 0, 0) : axis == 1 ? at(0, value, 0) : at(0, 0, value);
    }

    @Test
    void emptiedBucketsAreReleasedByExpireClearAndEviction() {
        // A query whose cube spans more buckets than exist walks the stored buckets, so its probe count
        // is exactly the number of buckets still held. A leaked empty bucket would inflate it.
        HazardField cleared = new HazardField();
        HazardField expired = new HazardField();
        for (int i = 0; i < 300; i++) {
            cleared.observe(at(i * 8, 64, 0), Kind.WATER, 0);
            expired.observe(at(i * 8, 64, 0), Kind.TRAP, 0);
        }
        for (int i = 0; i < 300; i++) {
            assertTrue(cleared.observeClear(at(i * 8, 64, 0), 1));
        }
        assertEquals(300, expired.expire(1_000_000));
        for (HazardField field : new HazardField[] {cleared, expired}) {
            field.observe(at(0, 64, 0), Kind.LAVA, 5);
            long before = field.bucketProbes();
            assertEquals(1, field.lavaCellsWithin(ORIGIN, Integer.MAX_VALUE).size());
            assertEquals(1L, field.bucketProbes() - before, "only the lava bucket may remain");
        }

        HazardField evicted = new HazardField();
        for (int i = 0; i <= HazardField.CAP; i++) {
            evicted.observe(at(i * 4, 64, 0), Kind.WATER, 0);      // one cell per bucket
        }
        assertEquals(HazardField.EVICT_BATCH, evicted.evictIfOver(ORIGIN));
        long before = evicted.bucketProbes();
        assertEquals(HazardField.CAP + 1 - HazardField.EVICT_BATCH,
                evicted.cellsWithin(Kind.WATER, ORIGIN, Integer.MAX_VALUE).size());
        assertEquals((long) HazardField.CAP + 1 - HazardField.EVICT_BATCH, evicted.bucketProbes() - before);
    }

    @Test
    void evictionTiesAmongTheSameKindRemoveTheLowestBlockPosOrderFirst() {
        // Four cells per distance shell d = 1..1024 (+-d on x and on z) plus one lone cell at d = 1025.
        // The lone cell and 63 whole shells go first; the cut then lands inside shell 961, which loses
        // three of its four cells in (y, z, x) order and keeps the last one, (0, 64, 961).
        HazardField field = new HazardField();
        for (int d = 1; d <= 1024; d++) {
            field.observe(at(d, 64, 0), Kind.WATER, 0);
            field.observe(at(-d, 64, 0), Kind.WATER, 0);
            field.observe(at(0, 64, d), Kind.WATER, 0);
            field.observe(at(0, 64, -d), Kind.WATER, 0);
        }
        field.observe(at(1025, 64, 0), Kind.WATER, 0);
        assertEquals(HazardField.CAP + 1, field.count());

        assertEquals(HazardField.EVICT_BATCH, field.evictIfOver(ORIGIN));

        assertNull(field.kindAt(at(1025, 64, 0)));
        for (int d : new int[] {1024, 963, 962}) {
            assertNull(field.kindAt(at(d, 64, 0)), "shell " + d);
            assertNull(field.kindAt(at(-d, 64, 0)), "shell " + d);
            assertNull(field.kindAt(at(0, 64, d)), "shell " + d);
            assertNull(field.kindAt(at(0, 64, -d)), "shell " + d);
        }
        assertNull(field.kindAt(at(0, 64, -961)));
        assertNull(field.kindAt(at(-961, 64, 0)));
        assertNull(field.kindAt(at(961, 64, 0)));
        assertEquals(Kind.WATER, field.kindAt(at(0, 64, 961)));
        for (int d : new int[] {960, 500, 1}) {
            assertEquals(Kind.WATER, field.kindAt(at(d, 64, 0)), "shell " + d);
            assertEquals(Kind.WATER, field.kindAt(at(-d, 64, 0)), "shell " + d);
            assertEquals(Kind.WATER, field.kindAt(at(0, 64, d)), "shell " + d);
            assertEquals(Kind.WATER, field.kindAt(at(0, 64, -d)), "shell " + d);
        }
    }

    @Test
    void randomChurnMatchesAReferenceModelThroughEvictionAndExpiry() {
        for (long seed : new long[] {11L, 22L, 33L}) {
            runModelChurn(seed);
        }
    }

    private static void runModelChurn(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        HazardField field = new HazardField();
        Model model = new Model();
        Kind[] kinds = Kind.values();
        int evictions = 0;
        int expiries = 0;
        int maxCount = 0;
        for (int i = 0; i < 26_000; i++) {
            int tick = i / 8 - random.nextInt(0, 30);        // mostly rising, sometimes stale
            BlockPos p = at(random.nextInt(0, 24), random.nextInt(0, 16), random.nextInt(0, 24));
            String where = "seed " + seed + " op " + i;
            int roll = random.nextInt(100);
            if (roll < 72) {
                Kind kind = kinds[random.nextInt(100) < 15 ? 0 : 1 + random.nextInt(2)];   // 15% lava
                assertEquals(model.observe(p, kind, tick), field.observe(p, kind, tick), where);
            } else if (roll < 78) {
                assertEquals(model.observeClear(p, tick), field.observeClear(p, tick), where);
            } else if (roll < 84) {
                assertEquals(model.observeNotFluid(p, tick), field.observeNotFluid(p, tick), where);
            } else if (roll < 85) {
                BlockPos bot = at(random.nextInt(0, 24), random.nextInt(0, 16), random.nextInt(0, 24));
                int expected = model.evictIfOver(bot);
                evictions += expected;
                assertEquals(expected, field.evictIfOver(bot), where);
            } else if (roll < 88) {
                int expected = model.expire(i / 8);
                expiries += expected;
                assertEquals(expected, field.expire(i / 8), where);
            } else {
                assertEquals(model.kindAt(p), field.kindAt(p), where);
            }
            maxCount = Math.max(maxCount, field.count());
            assertTrue(field.count() <= HazardField.CAP + HazardField.EVICT_BATCH, where);
            if (i % 1009 == 0) {
                assertEquals(model.snapshot(), field.snapshot(), where);
                for (Kind kind : kinds) {
                    assertEquals(model.count(kind), field.count(kind), where);
                }
            }
            if (i % 401 == 0) {
                BlockPos centre = at(random.nextInt(-5, 30), random.nextInt(-5, 21), random.nextInt(-5, 30));
                int radius = random.nextInt(0, 45);
                for (Kind kind : kinds) {
                    List<BlockPos> expected = model.within(kind, centre, radius);
                    assertEquals(expected, field.cellsWithin(kind, centre, radius), where);
                    assertEquals(!expected.isEmpty(), field.anyWithin(kind, centre, radius), where);
                    assertEquals(expected.isEmpty() ? HazardField.NONE : Model.distSq(expected.get(0), centre),
                            field.nearestDistanceSq(kind, centre, radius), where);
                }
            }
        }
        assertEquals(model.snapshot(), field.snapshot(), "seed " + seed + " final");
        assertTrue(evictions > 0, "seed " + seed + " never exercised capacity eviction");
        assertTrue(expiries > 0, "seed " + seed + " never exercised expiry");
        assertTrue(maxCount > HazardField.CAP, "seed " + seed + " never exceeded the cap");
    }

    /** Straightforward map-based restatement of the documented rules; shares no code with the bucket index. */
    private static final class Model {
        private record Entry(Kind kind, int tick) {}

        private final Map<BlockPos, Entry> cells = new HashMap<>();

        static long distSq(BlockPos a, BlockPos b) {
            long dx = a.getX() - b.getX();
            long dy = a.getY() - b.getY();
            long dz = a.getZ() - b.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        boolean observe(BlockPos p, Kind kind, int tick) {
            Entry e = cells.get(p);
            if (e == null) {
                if (cells.size() >= 4096 + 256) {
                    evict(p, Math.max(256, cells.size() - 4096));
                }
                cells.put(p, new Entry(kind, tick));
                return true;
            }
            if (e.kind() == kind) {
                cells.put(p, new Entry(kind, Math.max(e.tick(), tick)));
                return false;
            }
            if (e.kind() == Kind.LAVA) {
                return false;
            }
            if (kind == Kind.LAVA) {
                cells.put(p, new Entry(Kind.LAVA, Math.max(e.tick(), tick)));
                return true;
            }
            if (tick < e.tick()) {
                return false;
            }
            cells.put(p, new Entry(kind, tick));
            return true;
        }

        boolean observeClear(BlockPos p, int tick) {
            Entry e = cells.get(p);
            if (e == null || tick < e.tick()) {
                return false;
            }
            cells.remove(p);
            return true;
        }

        boolean observeNotFluid(BlockPos p, int tick) {
            Entry e = cells.get(p);
            if (e == null || e.kind() == Kind.TRAP || tick < e.tick()) {
                return false;
            }
            cells.remove(p);
            return true;
        }

        int expire(int now) {
            int before = cells.size();
            cells.values().removeIf(e -> e.kind() != Kind.LAVA
                    && (long) now - e.tick() > (e.kind() == Kind.WATER ? 1200 : 3600));
            return before - cells.size();
        }

        int evictIfOver(BlockPos bot) {
            return cells.size() <= 4096 ? 0 : evict(bot, Math.max(256, cells.size() - 4096));
        }

        private int evict(BlockPos from, int wanted) {
            List<Victim> order = new ArrayList<>(cells.size());
            cells.forEach((p, e) -> order.add(new Victim(p, e.kind() == Kind.LAVA, distSq(p, from))));
            order.sort(Comparator.comparingLong(Victim::dist).reversed()
                    .thenComparing(Victim::lava)
                    .thenComparingInt((Victim v) -> v.pos().getY())
                    .thenComparingInt(v -> v.pos().getZ())
                    .thenComparingInt(v -> v.pos().getX()));
            int n = Math.min(wanted, order.size());
            for (int i = 0; i < n; i++) {
                cells.remove(order.get(i).pos());
            }
            return n;
        }

        private record Victim(BlockPos pos, boolean lava, long dist) {}

        Kind kindAt(BlockPos p) {
            Entry e = cells.get(p);
            return e == null ? null : e.kind();
        }

        int count(Kind kind) {
            int n = 0;
            for (Entry e : cells.values()) {
                if (e.kind() == kind) {
                    n++;
                }
            }
            return n;
        }

        List<Cell> snapshot() {
            List<Cell> all = new ArrayList<>();
            cells.forEach((p, e) -> all.add(new Cell(p, e.kind(), e.tick())));
            all.sort(Comparator.comparingInt((Cell c) -> c.pos().getY())
                    .thenComparingInt(c -> c.pos().getZ()).thenComparingInt(c -> c.pos().getX()));
            return all;
        }

        List<BlockPos> within(Kind kind, BlockPos centre, int radius) {
            List<BlockPos> found = new ArrayList<>();
            cells.forEach((p, e) -> {
                if (e.kind() == kind
                        && Math.abs(p.getX() - centre.getX()) <= radius
                        && Math.abs(p.getY() - centre.getY()) <= radius
                        && Math.abs(p.getZ() - centre.getZ()) <= radius) {
                    found.add(p);
                }
            });
            found.sort(Comparator.<BlockPos>comparingLong(p -> distSq(p, centre))
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)
                    .thenComparingInt(BlockPos::getX));
            return found;
        }
    }
}
