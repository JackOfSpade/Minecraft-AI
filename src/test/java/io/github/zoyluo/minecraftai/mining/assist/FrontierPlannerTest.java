package io.github.zoyluo.aibot.mining.assist;

import io.github.zoyluo.aibot.mining.assist.FrontierPlanner.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontierPlannerTest {
    // ---- utility formula (design 5.4) ------------------------------------------------------------

    @Test
    void utilityFormulaMatchesTheDesignWeights() {
        // U = (6*1 + 3*min(sightings,3) + 1.5*1) / (0 + 4)
        Candidate candidate = new Candidate(1.0D, 5, 1.0D, 0.0D, 10.0D);
        double expected = (6.0D * 1.0D + 3.0D * 3 + 1.5D * 1.0D) / 4.0D;
        assertEquals(expected, candidate.utility(), 1.0e-9D);
    }

    @Test
    void sightingsAreCappedAtThreeBeforeWeighting() {
        Candidate fewer = new Candidate(0.0D, 3, 0.0D, 0.0D, 10.0D);
        Candidate more = new Candidate(0.0D, 30, 0.0D, 0.0D, 10.0D);
        assertEquals(fewer.utility(), more.utility(), 1.0e-9D);
    }

    @Test
    void higherObservedPathCostLowersUtility() {
        Candidate cheap = new Candidate(1.0D, 3, 1.0D, 0.0D, 10.0D);
        Candidate expensive = new Candidate(1.0D, 3, 1.0D, 20.0D, 10.0D);
        assertTrue(expensive.utility() < cheap.utility());
    }

    // ---- validation / clamping ---------------------------------------------------------------------

    @Test
    void candidateClampsOutOfRangeAndNonFiniteInputs() {
        Candidate candidate = new Candidate(5.0D, -5, -5.0D, Double.NEGATIVE_INFINITY, Double.NaN);
        assertEquals(1.0D, candidate.unknownFrac());
        assertEquals(0, candidate.sightingsWithin6());
        assertEquals(0.0D, candidate.yBand());
        assertEquals(0.0D, candidate.observedPathCost());
        assertEquals(0.0D, candidate.distanceFromBot());
    }

    // ---- distance band acceptance (design 5.4: "6-24 blocks away") ---------------------------------

    @Test
    void inRangeRejectsTooCloseAndTooFar() {
        Candidate tooClose = new Candidate(1.0D, 3, 1.0D, 0.0D, FrontierPlanner.MIN_DISTANCE - 0.01D);
        Candidate atNear = new Candidate(1.0D, 3, 1.0D, 0.0D, FrontierPlanner.MIN_DISTANCE);
        Candidate atFar = new Candidate(1.0D, 3, 1.0D, 0.0D, FrontierPlanner.MAX_DISTANCE);
        Candidate tooFar = new Candidate(1.0D, 3, 1.0D, 0.0D, FrontierPlanner.MAX_DISTANCE + 0.01D);

        assertTrue(!tooClose.inRange());
        assertTrue(atNear.inRange());
        assertTrue(atFar.inRange());
        assertTrue(!tooFar.inRange());
    }

    @Test
    void bestAcceptedNeverReturnsAnOutOfRangeCandidateEvenWithTheHighestUtility() {
        Candidate outOfRange = new Candidate(1.0D, 3, 1.0D, 0.0D, 1.0D); // huge utility, but too close
        Candidate inRange = new Candidate(0.1D, 0, 0.0D, 0.0D, 12.0D);  // modest utility, in range

        Candidate best = FrontierPlanner.bestAccepted(List.of(outOfRange, inRange), 0.0D);

        assertSame(inRange, best);
    }

    @Test
    void bestAcceptedReturnsNullWhenNothingMeetsTheMinimumUtility() {
        Candidate weak = new Candidate(0.01D, 0, 0.0D, 10.0D, 10.0D);
        Candidate best = FrontierPlanner.bestAccepted(List.of(weak), 100.0D);
        assertNull(best);
    }

    @Test
    void bestAcceptedPicksTheHighestUtilityAmongQualifyingCandidates() {
        Candidate weaker = new Candidate(0.5D, 0, 0.0D, 0.0D, 10.0D);
        Candidate stronger = new Candidate(1.0D, 3, 1.0D, 0.0D, 10.0D);
        Candidate best = FrontierPlanner.bestAccepted(List.of(weaker, stronger), 0.0D);
        assertSame(stronger, best);
    }

    // ---- hysteresis (design 5.4) --------------------------------------------------------------------

    @Test
    void keepPreviousWhenWithinTheHysteresisGap() {
        Candidate previous = new Candidate(0.5D, 1, 0.5D, 0.0D, 10.0D);
        Candidate best = new Candidate(0.5D, 1, 0.5D, 0.0D, 10.0D); // same utility as previous
        assertTrue(FrontierPlanner.keepPrevious(previous, best));
    }

    @Test
    void switchesAwayFromPreviousBeyondTheHysteresisGap() {
        Candidate previous = new Candidate(0.1D, 0, 0.0D, 10.0D, 10.0D);
        Candidate best = new Candidate(1.0D, 3, 1.0D, 0.0D, 10.0D); // much higher utility
        assertTrue(best.utility() - previous.utility() > FrontierPlanner.HYSTERESIS_GAP);
        assertTrue(!FrontierPlanner.keepPrevious(previous, best));
    }

    @Test
    void keepPreviousIsFalseForNullOrOutOfRangePrevious() {
        Candidate best = new Candidate(1.0D, 3, 1.0D, 0.0D, 10.0D);
        Candidate outOfRangePrevious = new Candidate(1.0D, 3, 1.0D, 0.0D, 1.0D);

        assertTrue(!FrontierPlanner.keepPrevious(null, best));
        assertTrue(!FrontierPlanner.keepPrevious(outOfRangePrevious, best));
        assertTrue(!FrontierPlanner.keepPrevious(best, null));
    }
}
