package io.github.zoyluo.minecraftai.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code gather_summary} {@code consistent} field's arithmetic (see docs/LOGGING.md
 * "Auditing a gather" and GatherQuotaTask's item 3): gained items must be explained by breaks
 * (bounded by the family's max drops per block) plus whatever arrived unattributed, and a forced
 * pickup always makes the result inconsistent regardless of the counts.
 */
final class GatherConsistencyTest {
    @Test
    void oneLogPerBrokenLogIsConsistent() {
        // 32 logs gathered, 32 logs broken, 1 drop per block, no stray gains, no forced pickups.
        assertTrue(GatherConsistency.isConsistent(32, 32, 1, 0, 0));
    }

    @Test
    void gainingMoreThanWhatWasBrokenIsInconsistent() {
        // Reported having gained 33 logs while only 32 blocks of that family were broken.
        assertFalse(GatherConsistency.isConsistent(33, 32, 1, 0, 0));
    }

    @Test
    void unattributedGainsWidenTheAllowedBound() {
        // A player handed the bot 5 extra logs mid-task (unattributed); 32 broken + 5 handed = 37 explainable.
        assertTrue(GatherConsistency.isConsistent(37, 32, 1, 5, 0));
        assertFalse(GatherConsistency.isConsistent(38, 32, 1, 5, 0));
    }

    @Test
    void anyForcedPickupMakesItInconsistentEvenIfTheCountsLineUp() {
        // Counts otherwise line up exactly, but strict_survival requires zero forced pickups.
        assertFalse(GatherConsistency.isConsistent(32, 32, 1, 0, 1));
    }

    @Test
    void multiDropFamiliesAllowMoreGainPerBreak() {
        // e.g. a melon block breaking into several slices: maxDropsPerBlock=9 for one broken block.
        assertTrue(GatherConsistency.isConsistent(9, 1, 9, 0, 0));
        assertFalse(GatherConsistency.isConsistent(10, 1, 9, 0, 0));
    }

    @Test
    void zeroBreaksWithOnlyUnattributedGainsIsStillConsistent() {
        // Nothing broken yet (still surveying), but a player already handed over items.
        assertTrue(GatherConsistency.isConsistent(3, 0, 1, 3, 0));
        assertFalse(GatherConsistency.isConsistent(4, 0, 1, 3, 0));
    }
}
