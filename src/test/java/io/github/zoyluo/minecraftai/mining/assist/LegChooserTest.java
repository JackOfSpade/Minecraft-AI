package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.mining.assist.LegChooser.DirectionSignal;
import io.github.zoyluo.minecraftai.mining.assist.LegChooser.TurnChoice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegChooserTest {
    private static final DirectionSignal EMPTY_NORTH = new DirectionSignal(0, 0, 0, 0, 0);
    private static final DirectionSignal EMPTY_EAST = new DirectionSignal(1, 0, 0, 0, 0);
    private static final DirectionSignal EMPTY_SOUTH = new DirectionSignal(2, 0, 0, 0, 0);
    private static final DirectionSignal EMPTY_WEST = new DirectionSignal(3, 0, 0, 0, 0);

    // ---- empty-model backward compatibility (design 5.3) --------------------------------------

    @Test
    void emptyModelKeepsTheClockwiseDefault() {
        // dirIndex 1 (EAST) is today's clockwise successor of dirIndex 0 (NORTH); STRIP_DIRS = {N, E, S, W}.
        DirectionSignal clockwise = new DirectionSignal(1, 0, 0, 0, 0);
        DirectionSignal counterClockwise = new DirectionSignal(3, 0, 0, 0, 0);

        TurnChoice choice = LegChooser.chooseTurn(clockwise, counterClockwise);

        assertEquals(1, choice.dirIndex());
        assertFalse(choice.overridden());
    }

    @Test
    void emptyModelInitialDirectionIsNorth() {
        int chosen = LegChooser.chooseInitialDirection(EMPTY_NORTH, EMPTY_EAST, EMPTY_SOUTH, EMPTY_WEST);
        assertEquals(0, chosen);
    }

    // ---- override threshold ---------------------------------------------------------------------

    @Test
    void counterClockwiseWinsOnlyAtOrAboveTheOverrideGap() {
        DirectionSignal clockwise = new DirectionSignal(1, 0.0D, 0.0D, 0.0D, 0.0D);
        DirectionSignal justBelow = new DirectionSignal(3, LegChooser.OVERRIDE_UTILITY_GAP - 0.01D, 0, 0, 0);
        DirectionSignal exactlyAt = new DirectionSignal(3, LegChooser.OVERRIDE_UTILITY_GAP, 0, 0, 0);

        assertEquals(1, LegChooser.chooseTurn(clockwise, justBelow).dirIndex());
        assertFalse(LegChooser.chooseTurn(clockwise, justBelow).overridden());

        TurnChoice atThreshold = LegChooser.chooseTurn(clockwise, exactlyAt);
        assertEquals(3, atThreshold.dirIndex());
        assertTrue(atThreshold.overridden());
    }

    @Test
    void utilityFormulaMatchesTheDesignWeights() {
        DirectionSignal signal = new DirectionSignal(0, 1.0D, 1.0D, 1.0D, 1.0D);
        // 1.0*1 + 0.6*1 + 0.5*1 - 1.5*1 = 0.6
        assertEquals(0.6D, signal.utility(), 1.0e-9D);
    }

    @Test
    void hazardProximityOnlyEverPullsUtilityDown() {
        DirectionSignal clean = new DirectionSignal(0, 0.5D, 0.5D, 0.5D, 0.0D);
        DirectionSignal hazardous = new DirectionSignal(0, 0.5D, 0.5D, 0.5D, 1.0D);
        assertTrue(hazardous.utility() < clean.utility());
    }

    // ---- validation -------------------------------------------------------------------------------

    @Test
    void dirIndexOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DirectionSignal(-1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DirectionSignal(4, 0, 0, 0, 0));
    }

    @Test
    void fractionsAreClampedRatherThanTrusted() {
        DirectionSignal signal = new DirectionSignal(0, 5.0D, -5.0D, Double.NaN, 5.0D);
        assertEquals(1.0D, signal.freshFraction());
        assertEquals(0.0D, signal.openBias());
        assertEquals(0.0D, signal.zoneAttraction());
        assertEquals(1.0D, signal.hazardProximity());
    }

    // ---- leg length (design 5.3: "Length is 96 when freshFraction >= 0.85, else 48") -------------

    @Test
    void lengthForFreshFractionPicksTheLongLegAtTheThreshold() {
        assertEquals(LegChooser.SHORT_LEG_LENGTH,
                LegChooser.lengthForFreshFraction(LegChooser.LONG_LEG_FRESH_THRESHOLD - 0.01D));
        assertEquals(LegChooser.LONG_LEG_LENGTH,
                LegChooser.lengthForFreshFraction(LegChooser.LONG_LEG_FRESH_THRESHOLD));
        assertEquals(LegChooser.LONG_LEG_LENGTH, LegChooser.lengthForFreshFraction(1.0D));
    }

    // ---- initial direction ties and picks ----------------------------------------------------------

    @Test
    void initialDirectionPicksTheHighestUtilityCandidate() {
        DirectionSignal strongEast = new DirectionSignal(1, 1.0D, 1.0D, 1.0D, 0.0D);
        int chosen = LegChooser.chooseInitialDirection(EMPTY_NORTH, strongEast, EMPTY_SOUTH, EMPTY_WEST);
        assertEquals(1, chosen);
    }

    @Test
    void initialDirectionTiesGoToTheEarliestCandidateInStripDirsOrder() {
        DirectionSignal tiedEast = new DirectionSignal(1, 0.3D, 0.0D, 0.0D, 0.0D);
        DirectionSignal tiedNorth = new DirectionSignal(0, 0.3D, 0.0D, 0.0D, 0.0D);
        int chosen = LegChooser.chooseInitialDirection(tiedNorth, tiedEast, EMPTY_SOUTH, EMPTY_WEST);
        assertEquals(0, chosen);
    }

    // ---- never reverses (design 5.3) ---------------------------------------------------------------

    @Test
    void chosenTurnIsNeverTheGeometricReverseOfTheCurrentDirection() {
        for (int current = 0; current < 4; current++) {
            int clockwiseIdx = (current + 1) % 4;
            int counterClockwiseIdx = (current + 3) % 4;
            int reverseIdx = (current + 2) % 4;

            DirectionSignal clockwise = new DirectionSignal(clockwiseIdx, 0, 0, 0, 0);
            DirectionSignal counterClockwise = new DirectionSignal(counterClockwiseIdx, 1.0D, 1.0D, 1.0D, 0.0D);

            TurnChoice choice = LegChooser.chooseTurn(clockwise, counterClockwise);
            assertTrue(choice.dirIndex() != reverseIdx,
                    "turn from " + current + " must never reverse to " + reverseIdx);
        }
    }
}
