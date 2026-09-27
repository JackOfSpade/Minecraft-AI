package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiBandLogGateTest {
    private static final int NO_LINE = PoiBandLogGate.NO_LINE;

    @Test
    void aQuietBotNeverLogsAndTheFirstChangeIsLoggedAtOnce() {
        PoiBandLogGate gate = new PoiBandLogGate();
        for (int tick = 0; tick < 1000; tick += 20) {
            assertEquals(NO_LINE, gate.consider(PoiScorer.Band.NONE, false, tick));
        }
        assertEquals(0, gate.consider(PoiScorer.Band.POSSIBLE, true, 1000), "no gap to wait out before the first line");
        assertEquals(PoiScorer.Band.POSSIBLE, gate.lastLoggedBand());
    }

    @Test
    void aBandThatFlipsEveryEvaluationCostsOneLinePerGapNotOnePerFlip() {
        PoiBandLogGate gate = new PoiBandLogGate();
        assertEquals(0, gate.consider(PoiScorer.Band.POSSIBLE, true, 0));
        int lines = 1;
        PoiScorer.Band band = PoiScorer.Band.POSSIBLE;
        // One evaluation per second for three minutes, right at a threshold: 180 evaluations of flapping.
        for (int tick = 20; tick <= 3600; tick += 20) {
            band = band == PoiScorer.Band.POSSIBLE ? PoiScorer.Band.NONE : PoiScorer.Band.POSSIBLE;
            if (gate.consider(band, true, tick) != NO_LINE) {
                lines++;
            }
        }
        assertTrue(lines <= 3600 / PoiBandLogGate.MIN_GAP_TICKS + 1, "lines: " + lines);
        assertTrue(lines < 30, "far fewer than the 180 flips: " + lines);
    }

    @Test
    void aChangeInsideTheGapIsDeferredNotLostAndTheLineCountsWhatWasHeldBack() {
        PoiBandLogGate gate = new PoiBandLogGate();
        assertEquals(0, gate.consider(PoiScorer.Band.POSSIBLE, true, 0));
        // Two changes inside the gap: POSSIBLE -> CAVERN_ONLY -> STRUCTURE_CERTAIN.
        assertEquals(NO_LINE, gate.consider(PoiScorer.Band.CAVERN_ONLY, true, 40));
        assertEquals(NO_LINE, gate.consider(PoiScorer.Band.STRUCTURE_CERTAIN, true, 80));
        // Still in effect when the gap has passed: logged then, with the count of changes it withheld.
        assertEquals(NO_LINE, gate.consider(PoiScorer.Band.STRUCTURE_CERTAIN, false, PoiBandLogGate.MIN_GAP_TICKS - 20));
        assertEquals(2, gate.consider(PoiScorer.Band.STRUCTURE_CERTAIN, false, PoiBandLogGate.MIN_GAP_TICKS));
        assertEquals(PoiScorer.Band.STRUCTURE_CERTAIN, gate.lastLoggedBand());
        assertEquals(NO_LINE, gate.consider(PoiScorer.Band.STRUCTURE_CERTAIN, false, PoiBandLogGate.MIN_GAP_TICKS + 20),
                "the band is unchanged since the last line");
    }

    @Test
    void aChangeThatFlipsBackInsideTheGapCostsNoLineAtAll() {
        PoiBandLogGate gate = new PoiBandLogGate();
        assertEquals(0, gate.consider(PoiScorer.Band.POSSIBLE, true, 0));
        assertEquals(NO_LINE, gate.consider(PoiScorer.Band.NONE, true, 40));
        assertEquals(NO_LINE, gate.consider(PoiScorer.Band.POSSIBLE, true, 80));
        assertEquals(NO_LINE, gate.consider(PoiScorer.Band.POSSIBLE, false, PoiBandLogGate.MIN_GAP_TICKS + 40));
        assertEquals(PoiScorer.Band.POSSIBLE, gate.lastLoggedBand());
    }

    @Test
    void theGapFollowsTheTickBackwardsAndResetForgetsTheLastLine() {
        PoiBandLogGate gate = new PoiBandLogGate();
        assertEquals(0, gate.consider(PoiScorer.Band.POSSIBLE, true, 50_000));
        assertEquals(0, gate.consider(PoiScorer.Band.NONE, true, 10), "the world reloaded: the old tick means nothing");
        gate.reset();
        assertEquals(PoiScorer.Band.NONE, gate.lastLoggedBand());
        assertEquals(0, gate.consider(PoiScorer.Band.MANDATORY, true, 11));
    }

    @Test
    void aNullBandCountsAsNone() {
        PoiBandLogGate gate = new PoiBandLogGate();
        assertEquals(NO_LINE, gate.consider(null, false, 5));
    }
}
