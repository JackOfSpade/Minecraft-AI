package io.github.zoyluo.aibot.mining.assist;

import io.github.zoyluo.aibot.mining.MiningCursor;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design 5.3's own checkpoint-compatibility requirement for P4 R2a: "{@code stripLegLength} stays
 * a multiple of 48 in [48, 384], {@code stepsLeft <= legLength}, and direction in [-1, 3], because
 * the checkpoint decode requires it." {@link LegChooser} never touches a cursor itself -- it is a
 * pure kernel that only returns ints -- so the thing that actually has to accept those values is
 * the shared {@link MiningCursor} codec {@code OreDigTask.OreDigCheckpoint} carries them through
 * (see {@code OreDigCheckpoint.cursor}). This proves that codec round-trips, with no silent
 * corruption or clamping, every value {@link LegChooser#chooseInitialDirection}, {@link
 * LegChooser#chooseTurn} and {@link LegChooser#lengthForFreshFraction} can legally produce, plus
 * the untouched default growth schedule a caller applies when L1 does not override a turn.
 */
class LegChooserCursorCompatibilityTest {
    private static final BlockPos ORIGIN = new BlockPos(0, -59, 0);

    @Test
    void everyStripDirsIndexRoundTripsThroughTheCheckpointCodec() {
        for (int dirIndex = 0; dirIndex < 4; dirIndex++) {
            MiningCursor cursor = new MiningCursor(
                    MiningCursor.CURRENT_SCHEMA, ORIGIN, ORIGIN, dirIndex, 0, 24, 48, 0);
            MiningCursor restored = MiningCursor.decode(cursor.encode()).orElseThrow();
            assertEquals(dirIndex, restored.directionIndex(),
                    "LegChooser's own dirIndex " + dirIndex + " must survive the checkpoint round-trip");
        }
    }

    @Test
    void theUnstartedSentinelSurvivesAlongsideAnOverriddenLength() {
        // chooseInitialStripDirection only ever runs when stripDirIndex < 0 (design 5.3's own gate);
        // the pre-first-tick sentinel must still decode as -1 even when paired with an L1 leg length.
        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA, ORIGIN, ORIGIN, -1, 0, 0, LegChooser.LONG_LEG_LENGTH, 0);
        MiningCursor restored = MiningCursor.decode(cursor.encode()).orElseThrow();
        assertEquals(-1, restored.directionIndex());
        assertEquals(LegChooser.LONG_LEG_LENGTH, restored.legLength());
    }

    @Test
    void bothOverrideLegLengthsRoundTripExactly() {
        for (int legLength : new int[] {LegChooser.SHORT_LEG_LENGTH, LegChooser.LONG_LEG_LENGTH}) {
            MiningCursor cursor = new MiningCursor(
                    MiningCursor.CURRENT_SCHEMA, ORIGIN, ORIGIN, 1, 3, legLength, legLength, 2);
            MiningCursor restored = MiningCursor.decode(cursor.encode()).orElseThrow();
            assertEquals(legLength, restored.legLength());
            assertEquals(legLength, restored.stepsLeft());
        }
    }

    @Test
    void everyDefaultGrowthScheduleLengthUpToTheThreeEightyFourCapRoundTripsExactly() {
        // publishStripSuccessor's own unoverridden schedule: min(STRIP_SEGMENT*8, legLength+STRIP_SEGMENT)
        // every other leg, i.e. 48, 48, 96, 96, 144, 144, ..., capped at 384 -- the "multiple of 48 in
        // [48, 384]" design 5.3 promises regardless of whether L1 ever overrides a single turn.
        for (int legLength = 48; legLength <= 384; legLength += 48) {
            MiningCursor cursor = new MiningCursor(
                    MiningCursor.CURRENT_SCHEMA, ORIGIN, ORIGIN, 2, 5, legLength / 2, legLength, 1);
            MiningCursor restored = MiningCursor.decode(cursor.encode()).orElseThrow();
            assertEquals(legLength, restored.legLength());
            assertTrue(restored.stepsLeft() <= restored.legLength(),
                    "stepsLeft must never exceed legLength after a round trip (design 5.3)");
        }
    }

    @Test
    void anEmptyModelsClockwiseTurnStaysWithinTheStepsLeftInvariantAfterARestart() {
        // "With an empty model it reproduces today's clockwise spiral bit for bit" (design 5.3): the
        // unoverridden TurnChoice changes nothing about stepsLeft, so a freshly opened leg's cursor
        // (stepsLeft == legLength, matching OreDigTask.publishStripSuccessor's own
        // "stripStepsLeft = stripLegLength" statement) must still satisfy stepsLeft <= legLength
        // after an encode/decode round trip, exactly as it did before L1 existed.
        LegChooser.DirectionSignal clockwise = new LegChooser.DirectionSignal(1, 0, 0, 0, 0);
        LegChooser.DirectionSignal counterClockwise = new LegChooser.DirectionSignal(3, 0, 0, 0, 0);
        LegChooser.TurnChoice choice = LegChooser.chooseTurn(clockwise, counterClockwise);
        assertFalse(choice.overridden(), "an empty model must never override the clockwise default");

        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA, ORIGIN, ORIGIN, choice.dirIndex(), 1, 96, 96, 0);
        MiningCursor restored = MiningCursor.decode(cursor.encode()).orElseThrow();
        assertEquals(choice.dirIndex(), restored.directionIndex());
        assertTrue(restored.stepsLeft() <= restored.legLength());
    }
}
