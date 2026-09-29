package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.List;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins mining-assist design 2.4/4.3/4.5/4.13: the detour's private anti-thrash table and skip-log dedupe. */
class DetourExclusionsTest {
    @Test
    void excludedCellIsExcludedUntilItsTtlElapses() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p = new BlockPos(3, 4, 5);
        ex.exclude(p, 0, 10);
        assertTrue(ex.isExcluded(p, 9));
        assertFalse(ex.isExcluded(p, 10));
    }

    @Test
    void aNonPositiveTtlExcludesNothing() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p = new BlockPos(1, 1, 1);
        ex.exclude(p, 0, 0);
        ex.exclude(p, 0, -5);
        assertFalse(ex.isExcluded(p, 0));
        assertEquals(0, ex.size());
    }

    @Test
    void reExcludingWithAnEarlierExpiryKeepsTheLaterOne() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p = new BlockPos(5, 5, 5);
        ex.exclude(p, 0, 100); // expires 100
        ex.exclude(p, 50, 10); // would expire 60: earlier, ignored
        assertTrue(ex.isExcluded(p, 99));
        assertFalse(ex.isExcluded(p, 100));
    }

    @Test
    void reExcludingWithALaterExpiryExtendsIt() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p = new BlockPos(5, 5, 5);
        ex.exclude(p, 0, 10); // expires 10
        ex.exclude(p, 5, 100); // expires 105: later, replaces
        assertTrue(ex.isExcluded(p, 104));
        assertFalse(ex.isExcluded(p, 105));
    }

    @Test
    void excludeAllExcludesEveryCellWithTheSameTtl() {
        DetourExclusions ex = new DetourExclusions();
        List<BlockPos> cluster = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(0, 1, 0));
        ex.excludeAll(cluster, 10, 600);
        assertEquals(3, ex.size());
        for (BlockPos p : cluster) {
            assertTrue(ex.isExcluded(p, 10));
            assertTrue(ex.isExcluded(p, 609));
        }
        for (BlockPos p : cluster) {
            assertFalse(ex.isExcluded(p, 610)); // expires here, and is lazily removed by the check itself
        }
        assertEquals(0, ex.size());
    }

    @Test
    void whenFullEveryExpiredEntryIsDroppedBeforeAnyEviction() {
        DetourExclusions ex = new DetourExclusions();
        for (int i = 0; i < DetourExclusions.CAP; i++) {
            ex.exclude(new BlockPos(i, 0, 0), 0, 1); // all expire at tick 1
        }
        assertEquals(DetourExclusions.CAP, ex.size());
        BlockPos fresh = new BlockPos(-1, 0, 0);
        ex.exclude(fresh, 1, 500); // at tick 1 every earlier entry has expired
        assertEquals(1, ex.size());
        assertTrue(ex.isExcluded(fresh, 1));
        assertFalse(ex.isExcluded(new BlockPos(0, 0, 0), 1));
    }

    @Test
    void whenFullAndNothingHasExpiredTheSoonestExpiringEntryIsEvictedTiedBySmallerPackedPosition() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos posA = new BlockPos(0, 0, 0);
        BlockPos posB = new BlockPos(1, 0, 0);
        BlockPos smaller = posA.asLong() < posB.asLong() ? posA : posB;
        BlockPos larger = smaller == posA ? posB : posA;
        ex.exclude(smaller, 0, 50);
        ex.exclude(larger, 0, 50); // tied soonest expiry with `smaller`
        for (int i = 2; i < DetourExclusions.CAP; i++) {
            ex.exclude(new BlockPos(i, 10, 0), 0, 100); // expire later, never the eviction candidate
        }
        assertEquals(DetourExclusions.CAP, ex.size());

        BlockPos fresh = new BlockPos(999, 999, 999);
        ex.exclude(fresh, 0, 500); // nothing expired yet at tick 0: forces a soonest-expiry eviction
        assertEquals(DetourExclusions.CAP, ex.size());
        assertFalse(ex.isExcluded(smaller, 0));
        assertTrue(ex.isExcluded(larger, 0));
        assertTrue(ex.isExcluded(fresh, 0));
    }

    @Test
    void shouldLogSkipDedupesPerCellForSixHundredTicks() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p = new BlockPos(2, 2, 2);
        assertTrue(ex.shouldLogSkip(p, 0));
        assertFalse(ex.shouldLogSkip(p, DetourExclusions.SKIP_LOG_INTERVAL_TICKS - 1));
        assertTrue(ex.shouldLogSkip(p, DetourExclusions.SKIP_LOG_INTERVAL_TICKS));
    }

    @Test
    void shouldLogSkipIsIndependentPerCell() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p1 = new BlockPos(1, 1, 1);
        BlockPos p2 = new BlockPos(2, 2, 2);
        assertTrue(ex.shouldLogSkip(p1, 0));
        assertTrue(ex.shouldLogSkip(p2, 0));
        assertFalse(ex.shouldLogSkip(p1, 1));
    }

    @Test
    void expireDropsDueEntriesFromBothTablesAndReturnsHowManyWereDropped() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p1 = new BlockPos(1, 1, 1);
        BlockPos p2 = new BlockPos(2, 2, 2);
        ex.exclude(p1, 0, 10); // exclusions table: due at 10
        ex.shouldLogSkip(p2, 0); // skip-log table: due at 600
        assertEquals(1, ex.expire(10));
        assertEquals(1, ex.expire(600));
        assertEquals(0, ex.expire(600));
    }

    @Test
    void clearEmptiesBothTables() {
        DetourExclusions ex = new DetourExclusions();
        BlockPos p1 = new BlockPos(1, 1, 1);
        BlockPos p2 = new BlockPos(2, 2, 2);
        ex.exclude(p1, 0, 100);
        ex.shouldLogSkip(p2, 0);
        ex.clear();
        assertEquals(0, ex.size());
        assertFalse(ex.isExcluded(p1, 0));
        assertTrue(ex.shouldLogSkip(p2, 0)); // dedupe window reset too
    }
}
