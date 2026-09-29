package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

/**
 * xPerf-NAVSAFE-01 / detour-refactor-navsafetynet-water-search-cost: locks the invalidation rules
 * for the memoized water-escape/breathable-cell search so the full-volume scan is skipped only
 * while it is actually still safe to do so (same feet cell, within the bounded staleness window).
 */
final class NavSafetyNetWaterSearchCacheTest {
    private static final BlockPos FEET = new BlockPos(10, 64, -5);

    @Test
    void sameFeetWithinWindowIsReused() {
        assertTrue(NavSafetyNet.waterSearchCacheValid(FEET, 100, FEET, 100));
        assertTrue(NavSafetyNet.waterSearchCacheValid(FEET, 100, FEET, 104),
                "reused up to (but not including) the cache window boundary");
    }

    @Test
    void windowBoundaryAndBeyondForcesRecompute() {
        assertFalse(NavSafetyNet.waterSearchCacheValid(FEET, 100, FEET, 105),
                "5 ticks old is already stale: the cache window is exclusive");
        assertFalse(NavSafetyNet.waterSearchCacheValid(FEET, 100, FEET, 250));
    }

    @Test
    void movedFeetCellInvalidatesImmediatelyEvenOnTheSameTick() {
        assertFalse(NavSafetyNet.waterSearchCacheValid(FEET, 100, FEET.offset(1, 0, 0), 100),
                "a changed feet cell must recompute on the very next tick regardless of age");
        assertFalse(NavSafetyNet.waterSearchCacheValid(FEET, 100, FEET.above(), 100));
    }

    @Test
    void missingOrClockSkewedCacheIsNeverTrusted() {
        assertFalse(NavSafetyNet.waterSearchCacheValid(null, 100, FEET, 100),
                "no prior cache entry -- must recompute");
        assertFalse(NavSafetyNet.waterSearchCacheValid(FEET, 100, FEET, 99),
                "a tick count that moved backwards must not be treated as fresh");
    }
}
