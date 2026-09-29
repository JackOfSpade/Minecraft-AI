package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.mining.assist.SightingLedger.Outcome;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger.Sighting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SightingLedgerTest {
    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Sighting s(BlockPos pos, String id, int value, int first, int last) {
        return new Sighting(pos, id, value, first, last);
    }

    /** Fills the ledger with 64 distinct sightings of the given value at x = 0..63, tick = x. */
    private static SightingLedger filled(int value) {
        SightingLedger ledger = new SightingLedger();
        for (int i = 0; i < SightingLedger.CAP; i++) {
            assertEquals(Outcome.ADDED, ledger.observe(at(i, 10, 0), "iron_ore", value, i));
        }
        assertEquals(SightingLedger.CAP, ledger.size());
        return ledger;
    }

    // ---- observe / dedupe ----------------------------------------------------------------------

    @Test
    void observeStoresANewSighting() {
        SightingLedger ledger = new SightingLedger();
        assertTrue(ledger.isEmpty());

        assertEquals(Outcome.ADDED, ledger.observe(at(1, 2, 3), "diamond_ore", 100, 50));

        assertEquals(1, ledger.size());
        assertTrue(ledger.contains(at(1, 2, 3)));
        assertEquals(s(at(1, 2, 3), "diamond_ore", 100, 50, 50), ledger.get(at(1, 2, 3)));
        assertNull(ledger.get(at(9, 9, 9)));
    }

    @Test
    void samePositionDedupesKeepingFirstTickLatestTickAndMaxValue() {
        SightingLedger ledger = new SightingLedger();
        BlockPos p = at(4, 5, 6);
        ledger.observe(p, "iron_ore", 30, 100);

        assertEquals(Outcome.UPDATED, ledger.observe(p, "iron_ore", 30, 160));
        assertEquals(s(p, "iron_ore", 30, 100, 160), ledger.get(p));

        assertEquals(Outcome.UPDATED, ledger.observe(p, "iron_ore", 45, 170));
        assertEquals(s(p, "iron_ore", 45, 100, 170), ledger.get(p));

        assertEquals(Outcome.UPDATED, ledger.observe(p, "iron_ore", 12, 180));
        assertEquals(s(p, "iron_ore", 45, 100, 180), ledger.get(p), "a lower value never lowers the stored value");

        assertEquals(Outcome.UPDATED, ledger.observe(p, "iron_ore", 45, 90));
        assertEquals(s(p, "iron_ore", 45, 90, 180), ledger.get(p), "an older tick lowers only firstTick");
        assertEquals(1, ledger.size());
    }

    @Test
    void blockIdFollowsTheObservationThatSuppliedTheWinningValue() {
        SightingLedger ledger = new SightingLedger();
        BlockPos p = at(0, 0, 0);
        ledger.observe(p, "iron_ore", 30, 100);

        ledger.observe(p, "coal_ore", 12, 110);
        assertEquals("iron_ore", ledger.get(p).blockId(), "lower value keeps the old id");

        ledger.observe(p, "deepslate_iron_ore", 30, 120);
        assertEquals("deepslate_iron_ore", ledger.get(p).blockId(), "equal value, newer observation wins the id");

        ledger.observe(p, "iron_ore", 30, 50);
        assertEquals("deepslate_iron_ore", ledger.get(p).blockId(), "equal value, older observation does not");

        ledger.observe(p, "diamond_ore", 100, 130);
        assertEquals("diamond_ore", ledger.get(p).blockId());
        assertEquals(100, ledger.get(p).rawValue());
    }

    @Test
    void sightingRecordCopiesPositionAndClampsNegativeValue() {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(1, 2, 3);
        SightingLedger ledger = new SightingLedger();
        ledger.observe(cursor, "gold_ore", -7, 5);
        cursor.set(8, 8, 8);

        Sighting stored = ledger.get(at(1, 2, 3));
        assertEquals(at(1, 2, 3), stored.pos());
        assertEquals(0, stored.rawValue());
        assertNull(ledger.get(at(8, 8, 8)));
    }

    // ---- capacity and eviction -----------------------------------------------------------------

    @Test
    void capIsSixtyFourAndNeverExceeded() {
        SightingLedger ledger = new SightingLedger();
        assertEquals(64, SightingLedger.CAP);
        for (int i = 0; i < 500; i++) {
            ledger.observe(at(i, 10, 0), "iron_ore", 30 + i % 7, i);
            assertTrue(ledger.size() <= SightingLedger.CAP);
        }
        assertEquals(SightingLedger.CAP, ledger.size());
    }

    @Test
    void fullLedgerEvictsTheLeastValuableForAMoreValuableSighting() {
        SightingLedger ledger = new SightingLedger();
        for (int i = 0; i < SightingLedger.CAP; i++) {
            ledger.observe(at(i, 10, 0), "ore", 10 + i, 100);   // values 10..73; the lowest is at x = 0
        }
        assertEquals(Outcome.ADDED, ledger.observe(at(500, 10, 0), "diamond_ore", 100, 200));

        assertEquals(SightingLedger.CAP, ledger.size());
        assertNull(ledger.get(at(0, 10, 0)), "the least valuable entry is evicted");
        assertTrue(ledger.contains(at(1, 10, 0)));
        assertTrue(ledger.contains(at(500, 10, 0)));
    }

    @Test
    void amongEqualValuesTheOldestSightingIsEvicted() {
        SightingLedger ledger = filled(30);          // ticks 0..63, x = tick
        assertEquals(Outcome.ADDED, ledger.observe(at(900, 10, 0), "iron_ore", 30, 1000));

        assertNull(ledger.get(at(0, 10, 0)), "tick 0 is the oldest");
        assertTrue(ledger.contains(at(1, 10, 0)));
        assertTrue(ledger.contains(at(900, 10, 0)));

        assertEquals(Outcome.ADDED, ledger.observe(at(901, 10, 0), "iron_ore", 30, 1001));
        assertNull(ledger.get(at(1, 10, 0)));
    }

    @Test
    void aSightingWorseThanEveryStoredEntryIsRejectedAndNothingIsEvicted() {
        SightingLedger ledger = filled(30);
        List<Sighting> before = ledger.snapshotSortedByValueDesc();

        assertEquals(Outcome.REJECTED, ledger.observe(at(900, 10, 0), "coal_ore", 12, 5000));

        assertEquals(before, ledger.snapshotSortedByValueDesc());
        assertNull(ledger.get(at(900, 10, 0)));
    }

    @Test
    void fullTieOnValueAndTickEvictsTheGreaterBlockPosOrderFirst() {
        SightingLedger ledger = new SightingLedger();
        for (int i = 0; i < SightingLedger.CAP; i++) {
            ledger.observe(at(i, 10, 0), "iron_ore", 30, 100);   // identical value and tick
        }
        // Same value and tick, and a lower BlockPos order (y = 9): outranks x = 63 (y = 10).
        assertEquals(Outcome.ADDED, ledger.observe(at(0, 9, 0), "iron_ore", 30, 100));
        assertNull(ledger.get(at(63, 10, 0)), "the last in (y, z, x) order is the victim: y equal, z equal, largest x");
        assertTrue(ledger.contains(at(0, 10, 0)));

        // Same value and tick and a greater BlockPos order than everything stored: it ranks last, rejected.
        assertEquals(Outcome.REJECTED, ledger.observe(at(0, 11, 0), "iron_ore", 30, 100));
    }

    @Test
    void updatingAnExistingPositionWhenFullNeverEvictsAnything() {
        SightingLedger ledger = filled(30);
        assertEquals(Outcome.UPDATED, ledger.observe(at(10, 10, 0), "iron_ore", 30, 9000));
        assertEquals(SightingLedger.CAP, ledger.size());
        assertEquals(9000, ledger.get(at(10, 10, 0)).lastTick());
        assertTrue(ledger.contains(at(0, 10, 0)));
    }

    @Test
    void keepingTheTopSixtyFourDoesNotDependOnArrivalOrder() {
        SplittableRandom random = new SplittableRandom(4242);
        List<Object[]> stream = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            stream.add(new Object[] {at(i, i % 5, i % 3), random.nextInt(0, 12) * 10, random.nextInt(0, 400)});
        }
        List<Object[]> shuffled = new ArrayList<>(stream);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            Collections.swap(shuffled, i, random.nextInt(i + 1));
        }

        SightingLedger a = feed(stream);
        SightingLedger b = feed(shuffled);

        assertEquals(SightingLedger.CAP, a.size());
        assertEquals(a.snapshotSortedByValueDesc(), b.snapshotSortedByValueDesc());
    }

    private static SightingLedger feed(List<Object[]> stream) {
        SightingLedger ledger = new SightingLedger();
        for (Object[] row : stream) {
            int tick = (Integer) row[2];
            ledger.observe((BlockPos) row[0], "ore", (Integer) row[1], tick);
        }
        return ledger;
    }

    // ---- markGone / get ------------------------------------------------------------------------

    @Test
    void markGoneRemovesOnlyThatSightingAndAllowsReobservation() {
        SightingLedger ledger = new SightingLedger();
        ledger.observe(at(1, 1, 1), "diamond_ore", 100, 10);
        ledger.observe(at(2, 2, 2), "gold_ore", 45, 10);

        assertTrue(ledger.markGone(at(1, 1, 1)));
        assertFalse(ledger.markGone(at(1, 1, 1)), "already gone");
        assertFalse(ledger.markGone(at(7, 7, 7)));
        assertNull(ledger.get(at(1, 1, 1)));
        assertEquals(1, ledger.size());

        assertEquals(Outcome.ADDED, ledger.observe(at(1, 1, 1), "diamond_ore", 100, 500));
        assertEquals(s(at(1, 1, 1), "diamond_ore", 100, 500, 500), ledger.get(at(1, 1, 1)));
    }

    @Test
    void markGoneFreesASlotSoARankedLowSightingIsAdmittedAgain() {
        SightingLedger ledger = filled(30);
        assertEquals(Outcome.REJECTED, ledger.observe(at(900, 10, 0), "coal_ore", 12, 5000));
        assertTrue(ledger.markGone(at(5, 10, 0)));
        assertEquals(Outcome.ADDED, ledger.observe(at(900, 10, 0), "coal_ore", 12, 5001));
        assertEquals(SightingLedger.CAP, ledger.size());
    }

    // ---- ordering ------------------------------------------------------------------------------

    @Test
    void snapshotIsValueDescThenNewestThenBlockPosOrder() {
        SightingLedger ledger = new SightingLedger();
        ledger.observe(at(0, 0, 0), "iron_ore", 30, 10);
        ledger.observe(at(1, 0, 0), "diamond_ore", 100, 5);
        ledger.observe(at(2, 0, 0), "iron_ore", 30, 50);       // same value, newer than (0,0,0)
        ledger.observe(at(3, 0, 0), "iron_ore", 30, 50);       // ties with (2,0,0): lower x first
        ledger.observe(at(0, 1, 0), "iron_ore", 30, 50);       // ties too, but y = 1 sorts after y = 0
        ledger.observe(at(4, 0, 0), "coal_ore", 12, 999);

        List<BlockPos> order = new ArrayList<>();
        for (Sighting sighting : ledger.snapshotSortedByValueDesc()) {
            order.add(sighting.pos());
        }
        assertEquals(List.of(at(1, 0, 0), at(2, 0, 0), at(3, 0, 0), at(0, 1, 0), at(0, 0, 0), at(4, 0, 0)), order);
    }

    @Test
    void snapshotIsACopy() {
        SightingLedger ledger = new SightingLedger();
        ledger.observe(at(0, 0, 0), "iron_ore", 30, 1);
        List<Sighting> snapshot = ledger.snapshotSortedByValueDesc();
        snapshot.clear();
        assertEquals(1, ledger.size());
        assertEquals(1, ledger.snapshotSortedByValueDesc().size());
    }

    @Test
    void nearestToOrdersByDistanceThenValueThenBlockPosOrder() {
        SightingLedger ledger = new SightingLedger();
        BlockPos from = at(0, 0, 0);
        ledger.observe(at(10, 0, 0), "diamond_ore", 100, 1);   // d 100
        ledger.observe(at(3, 0, 0), "iron_ore", 30, 1);        // d 9
        ledger.observe(at(-3, 0, 0), "gold_ore", 45, 1);       // d 9, more valuable than iron
        ledger.observe(at(0, 0, 3), "gold_ore", 45, 1);        // d 9, same value as gold at -3: z = 3 vs y = 0 -> ...
        ledger.observe(at(1, 1, 1), "coal_ore", 12, 1);        // d 3

        List<BlockPos> order = new ArrayList<>();
        for (Sighting sighting : ledger.nearestTo(from, 10)) {
            order.add(sighting.pos());
        }
        // d3, then the three at d9: value 45 before 30; between the two 45s BlockPos order (y, z, x):
        // (-3,0,0) has z=0 and (0,0,3) has z=3, so (-3,0,0) first.
        assertEquals(List.of(at(1, 1, 1), at(-3, 0, 0), at(0, 0, 3), at(3, 0, 0), at(10, 0, 0)), order);
    }

    @Test
    void nearestToHonoursLimitAndDegenerateLimits() {
        SightingLedger ledger = new SightingLedger();
        for (int i = 1; i <= 5; i++) {
            ledger.observe(at(i, 0, 0), "iron_ore", 30, i);
        }
        assertEquals(2, ledger.nearestTo(at(0, 0, 0), 2).size());
        assertEquals(at(1, 0, 0), ledger.nearestTo(at(0, 0, 0), 2).get(0).pos());
        assertEquals(at(2, 0, 0), ledger.nearestTo(at(0, 0, 0), 2).get(1).pos());
        assertEquals(5, ledger.nearestTo(at(0, 0, 0), 99).size());
        assertTrue(ledger.nearestTo(at(0, 0, 0), 0).isEmpty());
        assertTrue(ledger.nearestTo(at(0, 0, 0), -4).isEmpty());
        assertTrue(new SightingLedger().nearestTo(at(0, 0, 0), 3).isEmpty());
    }

    @Test
    void nearestToUsesTheReferencePointNotTheOrigin() {
        SightingLedger ledger = new SightingLedger();
        ledger.observe(at(100, 40, 100), "iron_ore", 30, 1);
        ledger.observe(at(-100, 40, -100), "iron_ore", 30, 1);
        assertEquals(at(100, 40, 100), ledger.nearestTo(at(90, 40, 90), 1).get(0).pos());
        assertEquals(at(-100, 40, -100), ledger.nearestTo(at(-90, 40, -95), 1).get(0).pos());
    }

    // ---- expire --------------------------------------------------------------------------------

    @Test
    void expireDropsOnlySightingsStrictlyOlderThanMaxAge() {
        SightingLedger ledger = new SightingLedger();
        ledger.observe(at(0, 0, 0), "iron_ore", 30, 100);
        ledger.observe(at(1, 0, 0), "iron_ore", 30, 200);
        ledger.observe(at(2, 0, 0), "iron_ore", 30, 300);

        assertEquals(0, ledger.expire(300, 200), "age exactly maxAge is kept");
        assertEquals(1, ledger.expire(301, 200));
        assertNull(ledger.get(at(0, 0, 0)));
        assertEquals(2, ledger.size());

        assertEquals(2, ledger.expire(10_000, 100));
        assertTrue(ledger.isEmpty());
    }

    @Test
    void expireUsesTheLatestSightingTickAndToleratesFutureStamps() {
        SightingLedger ledger = new SightingLedger();
        ledger.observe(at(0, 0, 0), "iron_ore", 30, 100);
        ledger.observe(at(0, 0, 0), "iron_ore", 30, 900);      // refreshed: lastTick 900
        ledger.observe(at(1, 0, 0), "iron_ore", 30, 5000);     // stamped after "now"

        assertEquals(0, ledger.expire(1000, 200));
        assertEquals(2, ledger.size());
        assertEquals(1, ledger.expire(1200, 200));              // (0,0,0) is now 300 old
        assertTrue(ledger.contains(at(1, 0, 0)));
    }

    @Test
    void expireOnAnEmptyLedgerAndAtIntegerExtremes() {
        SightingLedger ledger = new SightingLedger();
        assertEquals(0, ledger.expire(0, 0));
        ledger.observe(at(0, 0, 0), "iron_ore", 30, Integer.MIN_VALUE);
        assertEquals(1, ledger.expire(Integer.MAX_VALUE, 1000), "age arithmetic must not overflow");
    }

    // ---- determinism and housekeeping ----------------------------------------------------------

    @Test
    void identicalObservationSequencesGiveIdenticalLedgers() {
        SightingLedger a = drive(31L);
        SightingLedger b = drive(31L);
        assertEquals(a.snapshotSortedByValueDesc(), b.snapshotSortedByValueDesc());
        assertEquals(a.nearestTo(at(0, 0, 0), 10), b.nearestTo(at(0, 0, 0), 10));
        assertFalse(a.snapshotSortedByValueDesc().equals(drive(32L).snapshotSortedByValueDesc()));
    }

    private static SightingLedger drive(long seed) {
        SightingLedger ledger = new SightingLedger();
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < 1500; i++) {
            BlockPos p = at(random.nextInt(-12, 12), random.nextInt(-5, 5), random.nextInt(-12, 12));
            int roll = random.nextInt(10);
            if (roll == 0) {
                ledger.markGone(p);
            } else {
                ledger.observe(p, "ore_" + random.nextInt(4), random.nextInt(0, 10) * 10, i);
            }
            if (i % 200 == 0) {
                ledger.expire(i, 500);
            }
        }
        return ledger;
    }

    @Test
    void clearEmptiesTheLedger() {
        SightingLedger ledger = filled(30);
        ledger.clear();
        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.size());
        assertTrue(ledger.snapshotSortedByValueDesc().isEmpty());
        assertEquals(Outcome.ADDED, ledger.observe(at(0, 0, 0), "iron_ore", 30, 1));
    }

    // ---- adversarial review additions ----------------------------------------------------------

    @Test
    void nullArgumentsAreRejectedBeforeAnyStateChanges() {
        SightingLedger ledger = new SightingLedger();
        assertThrows(NullPointerException.class, () -> ledger.observe(null, "iron_ore", 30, 1));
        assertThrows(NullPointerException.class, () -> ledger.observe(at(0, 0, 0), null, 30, 1));
        assertThrows(NullPointerException.class, () -> ledger.get(null));
        assertThrows(NullPointerException.class, () -> ledger.markGone(null));
        assertThrows(NullPointerException.class, () -> ledger.nearestTo(null, 3));
        assertTrue(ledger.isEmpty());
    }

    @Test
    void negativeValueClampsToZeroAndStillMergesAsAnEqualValue() {
        SightingLedger ledger = new SightingLedger();
        BlockPos p = at(0, 0, 0);
        ledger.observe(p, "mystery", -50, 10);
        assertEquals(0, ledger.get(p).rawValue());
        ledger.observe(p, "coal_ore", 0, 20);
        assertEquals("coal_ore", ledger.get(p).blockId(), "equal (clamped) value, newer observation");
        assertEquals(s(p, "coal_ore", 0, 10, 20), ledger.get(p));
    }

    @Test
    void aFullLedgerRanksAnUpdatedEntryByItsNewValueWhenLaterEvicting() {
        SightingLedger ledger = filled(30);                       // ticks 0..63
        ledger.observe(at(0, 10, 0), "iron_ore", 100, 5000);      // the oldest entry becomes the best one
        assertEquals(Outcome.ADDED, ledger.observe(at(900, 10, 0), "iron_ore", 30, 6000));
        assertTrue(ledger.contains(at(0, 10, 0)), "value 100 outranks the value-30 entries");
        assertNull(ledger.get(at(1, 10, 0)), "the next oldest value-30 entry is the victim");
    }

    @Test
    void anEvictedPositionCanBeAdmittedAgainWithAHigherValue() {
        SightingLedger ledger = filled(30);
        assertEquals(Outcome.REJECTED, ledger.observe(at(900, 10, 0), "coal_ore", 12, 100));
        assertEquals(Outcome.ADDED, ledger.observe(at(900, 10, 0), "diamond_ore", 100, 101));
        assertEquals(SightingLedger.CAP, ledger.size());
        assertNull(ledger.get(at(0, 10, 0)));
        assertEquals("diamond_ore", ledger.get(at(900, 10, 0)).blockId());
    }

    @Test
    void nearestToBreaksFullTiesByBlockPosOrderAndNeverMutatesTheLedger() {
        SightingLedger ledger = new SightingLedger();
        // Four sightings on the shell d = 2 around the origin, identical value.
        ledger.observe(at(0, 0, 2), "iron_ore", 30, 1);
        ledger.observe(at(2, 0, 0), "iron_ore", 30, 1);
        ledger.observe(at(0, 2, 0), "iron_ore", 30, 1);
        ledger.observe(at(0, -2, 0), "iron_ore", 30, 1);
        List<Sighting> before = ledger.snapshotSortedByValueDesc();

        List<BlockPos> order = new ArrayList<>();
        for (Sighting sighting : ledger.nearestTo(at(0, 0, 0), 4)) {
            order.add(sighting.pos());
        }
        assertEquals(List.of(at(0, -2, 0), at(2, 0, 0), at(0, 0, 2), at(0, 2, 0)), order);
        assertEquals(before, ledger.snapshotSortedByValueDesc());
    }

    @Test
    void nearestToDistanceDoesNotOverflowForFarPositions() {
        SightingLedger ledger = new SightingLedger();
        ledger.observe(at(29_999_999, 0, 29_999_999), "iron_ore", 30, 1);
        ledger.observe(at(-29_999_999, 0, -29_999_999), "iron_ore", 30, 1);
        assertEquals(at(29_999_999, 0, 29_999_999), ledger.nearestTo(at(29_000_000, 0, 29_000_000), 1).get(0).pos());
        assertEquals(at(-29_999_999, 0, -29_999_999), ledger.nearestTo(at(-1, 0, -1), 2).get(0).pos());
    }

    @Test
    void randomChurnMatchesAReferenceModel() {
        for (long seed : new long[] {5L, 6L, 7L, 8L}) {
            runModelChurn(seed);
        }
    }

    private static void runModelChurn(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        SightingLedger ledger = new SightingLedger();
        Model model = new Model();
        String[] ids = {"coal_ore", "iron_ore", "gold_ore", "diamond_ore", "emerald_ore"};
        int rejected = 0;
        int evicted = 0;
        for (int i = 0; i < 20_000; i++) {
            String where = "seed " + seed + " op " + i;
            BlockPos p = at(random.nextInt(0, 12), random.nextInt(0, 12), random.nextInt(0, 12));   // 1728 cells
            int roll = random.nextInt(100);
            if (roll < 80) {
                int id = random.nextInt(ids.length);
                int value = random.nextInt(-2, 6) * 20;
                int tick = i / 4 - random.nextInt(0, 60);
                int sizeBefore = model.size();
                Outcome expected = model.observe(p, ids[id], value, tick);
                if (expected == Outcome.REJECTED) {
                    rejected++;
                } else if (expected == Outcome.ADDED && sizeBefore == SightingLedger.CAP) {
                    evicted++;
                }
                assertEquals(expected, ledger.observe(p, ids[id], value, tick), where);
            } else if (roll < 88) {
                assertEquals(model.markGone(p), ledger.markGone(p), where);
            } else if (roll < 92) {
                int now = i / 4;
                int maxAge = random.nextInt(0, 400);
                assertEquals(model.expire(now, maxAge), ledger.expire(now, maxAge), where);
            } else {
                assertEquals(model.get(p), ledger.get(p), where);
            }
            assertEquals(model.size(), ledger.size(), where);
            assertTrue(ledger.size() <= SightingLedger.CAP, where);
            if (i % 53 == 0) {
                assertEquals(model.ranked(), ledger.snapshotSortedByValueDesc(), where);
                BlockPos from = at(random.nextInt(0, 12), random.nextInt(0, 12), random.nextInt(0, 12));
                int limit = random.nextInt(-1, 70);
                assertEquals(model.nearest(from, limit), ledger.nearestTo(from, limit), where);
            }
        }
        assertEquals(model.ranked(), ledger.snapshotSortedByValueDesc(), "seed " + seed + " final");
        assertTrue(rejected > 0, "seed " + seed + " never rejected");
        assertTrue(evicted > 0, "seed " + seed + " never evicted");
    }

    /** Restates the documented merge, rank and eviction rules with a plain map and a sort. */
    private static final class Model {
        private final Map<BlockPos, Sighting> cells = new HashMap<>();

        int size() {
            return cells.size();
        }

        Sighting get(BlockPos p) {
            return cells.get(p);
        }

        boolean markGone(BlockPos p) {
            return cells.remove(p) != null;
        }

        int expire(int now, int maxAge) {
            int before = cells.size();
            cells.values().removeIf(s -> (long) now - s.lastTick() > maxAge);
            return before - cells.size();
        }

        Outcome observe(BlockPos p, String id, int value, int tick) {
            int v = Math.max(0, value);
            Sighting existing = cells.get(p);
            if (existing != null) {
                boolean wins = v > existing.rawValue() || (v == existing.rawValue() && tick >= existing.lastTick());
                cells.put(p, new Sighting(p, wins ? id : existing.blockId(), Math.max(v, existing.rawValue()),
                        Math.min(existing.firstTick(), tick), Math.max(existing.lastTick(), tick)));
                return Outcome.UPDATED;
            }
            Sighting incoming = new Sighting(p, id, v, tick, tick);
            if (cells.size() >= 64) {
                List<Sighting> pool = new ArrayList<>(cells.values());
                pool.add(incoming);
                pool.sort(RANK);
                Sighting worst = pool.get(pool.size() - 1);
                if (worst.pos().equals(p)) {
                    return Outcome.REJECTED;
                }
                cells.remove(worst.pos());
            }
            cells.put(p, incoming);
            return Outcome.ADDED;
        }

        List<Sighting> ranked() {
            List<Sighting> all = new ArrayList<>(cells.values());
            all.sort(RANK);
            return all;
        }

        List<Sighting> nearest(BlockPos from, int limit) {
            List<Sighting> all = new ArrayList<>(cells.values());
            all.sort(Comparator.comparingLong((Sighting s) -> {
                        long dx = s.pos().getX() - from.getX();
                        long dy = s.pos().getY() - from.getY();
                        long dz = s.pos().getZ() - from.getZ();
                        return dx * dx + dy * dy + dz * dz;
                    })
                    .thenComparing(Comparator.comparingInt(Sighting::rawValue).reversed())
                    .thenComparingInt((Sighting s) -> s.pos().getY())
                    .thenComparingInt(s -> s.pos().getZ())
                    .thenComparingInt(s -> s.pos().getX()));
            return limit <= 0 ? List.of() : new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
        }

        private static final Comparator<Sighting> RANK = (a, b) -> {
            if (a.rawValue() != b.rawValue()) {
                return Integer.compare(b.rawValue(), a.rawValue());
            }
            if (a.lastTick() != b.lastTick()) {
                return Integer.compare(b.lastTick(), a.lastTick());
            }
            if (a.pos().getY() != b.pos().getY()) {
                return Integer.compare(a.pos().getY(), b.pos().getY());
            }
            if (a.pos().getZ() != b.pos().getZ()) {
                return Integer.compare(a.pos().getZ(), b.pos().getZ());
            }
            return Integer.compare(a.pos().getX(), b.pos().getX());
        };
    }
}
