package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A look-around sweep reaches one block through many rays; a lead its caller cannot use has to stay
 * quiet while the bot stands where it was judged, and only then. This is what keeps a leaf straight
 * overhead from answering every vertical and lattice ray of every step.
 */
final class DeclinedSightingsTest {
    private static final BlockPos FEET = new BlockPos(17, 123, -50);
    private static final BlockPos LEAF_OVERHEAD = new BlockPos(17, 129, -50);
    private static final BlockPos TRUNK_ASIDE = new BlockPos(18, 128, -50);

    @Test
    void nothingIsDeclinedUntilTheCallerSaysSo() {
        DeclinedSightings declined = new DeclinedSightings();

        assertFalse(declined.isDeclined(FEET, LEAF_OVERHEAD));
    }

    @Test
    void aDeclinedSightingStaysQuietForEveryLaterStepFromTheSameCell() {
        DeclinedSightings declined = new DeclinedSightings();
        declined.decline(FEET, LEAF_OVERHEAD);

        for (int step = 0; step < 50; step++) {
            assertTrue(declined.isDeclined(FEET, LEAF_OVERHEAD), "step " + step + " offered the declined leaf again");
        }
        assertFalse(declined.isDeclined(FEET, TRUNK_ASIDE), "an unrelated block must still be reported");
    }

    @Test
    void theSameBlockIsNewEvidenceOnceTheBotStandsInAnotherCell() {
        DeclinedSightings declined = new DeclinedSightings();
        declined.decline(FEET, LEAF_OVERHEAD);

        BlockPos aside = FEET.east();
        assertFalse(declined.isDeclined(aside, LEAF_OVERHEAD), "from the next cell the leaf has a heading");
        // Having moved, the old judgement is gone for good rather than coming back on return.
        assertFalse(declined.isDeclined(FEET, LEAF_OVERHEAD));
    }

    @Test
    void decliningFromANewCellForgetsWhatWasDeclinedFromTheOldOne() {
        DeclinedSightings declined = new DeclinedSightings();
        declined.decline(FEET, LEAF_OVERHEAD);
        BlockPos aside = FEET.east();

        declined.decline(aside, TRUNK_ASIDE);

        assertTrue(declined.isDeclined(aside, TRUNK_ASIDE));
        assertFalse(declined.isDeclined(aside, LEAF_OVERHEAD));
    }

    @Test
    void clearForgetsEverything() {
        DeclinedSightings declined = new DeclinedSightings();
        declined.decline(FEET, LEAF_OVERHEAD);

        declined.clear();

        assertFalse(declined.isDeclined(FEET, LEAF_OVERHEAD));
    }

    @Test
    void aMutableCursorPositionMatchesTheDeclinedBlock() {
        DeclinedSightings declined = new DeclinedSightings();
        declined.decline(FEET, LEAF_OVERHEAD);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                LEAF_OVERHEAD.getX(), LEAF_OVERHEAD.getY(), LEAF_OVERHEAD.getZ());

        assertTrue(declined.isDeclined(FEET, cursor));
        cursor.setY(cursor.getY() - 1);
        assertFalse(declined.isDeclined(FEET, cursor));
    }
}
