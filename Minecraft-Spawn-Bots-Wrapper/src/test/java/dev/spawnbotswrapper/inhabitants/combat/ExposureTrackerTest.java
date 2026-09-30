package dev.spawnbotswrapper.inhabitants.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exposure bookkeeping behind the reaction time: continuous runs, one missed tick tolerated, two reset. */
class ExposureTrackerTest {

    private static final String K = ExposureTracker.key("Warden7", "Steve");

    @Test
    void exposureCountsTicksSinceTheRunStarted() {
        ExposureTracker t = new ExposureTracker();
        assertEquals(0, t.sighted(K, 100));
        assertEquals(1, t.sighted(K, 101));
        t.sighted(K, 102);
        t.sighted(K, 103);
        t.sighted(K, 104);
        assertEquals(5, t.sighted(K, 105));
    }

    @Test
    void aSingleMissedTickIsTolerated() {
        ExposureTracker t = new ExposureTracker();
        t.sighted(K, 100);
        t.sighted(K, 101);
        // 102 missed
        assertEquals(3, t.sighted(K, 103), "the run continues over one missed tick");
    }

    @Test
    void twoMissedTicksInARowStartOver() {
        ExposureTracker t = new ExposureTracker();
        t.sighted(K, 100);
        t.sighted(K, 101);
        // 102 and 103 missed
        assertEquals(0, t.sighted(K, 104), "not sighted for 2 ticks: the exposure starts again");
    }

    @Test
    void aBrokenRunIsDroppedByMissed() {
        ExposureTracker t = new ExposureTracker();
        t.sighted(K, 100);
        assertTrue(t.inProgress(K, 101));
        assertTrue(t.inProgress(K, 102));
        assertFalse(t.inProgress(K, 103), "gone for too long: not in progress any more");
        t.missed(K, 103);
        assertEquals(0, t.size());
    }

    @Test
    void aRunStillAliveIsWatchedEveryTick() {
        ExposureTracker t = new ExposureTracker();
        assertFalse(t.inProgress(K, 100));
        t.sighted(K, 100);
        assertTrue(t.inProgress(K, 100));
        t.missed(K, 101);
        assertEquals(1, t.size(), "one missed tick keeps the run");
    }

    @Test
    void pairsAreIndependentAndCanBeForgotten() {
        ExposureTracker t = new ExposureTracker();
        String other = ExposureTracker.key("Warden7", "Alex");
        String bot2 = ExposureTracker.key("Ranger", "Steve");
        t.sighted(K, 100);
        t.sighted(other, 100);
        t.sighted(bot2, 100);
        assertEquals(3, t.size());
        t.forgetObserver("Warden7");
        assertEquals(1, t.size());
        t.forget(bot2);
        assertEquals(0, t.size());
    }

    @Test
    void runsOfSubjectsThatAreNoLongerPresentAreDroppedAtOnce() {
        ExposureTracker t = new ExposureTracker();
        t.sighted(ExposureTracker.key("Warden7", "Steve"), 100);
        t.sighted(ExposureTracker.key("Warden7", "Alex"), 100);
        t.sighted(ExposureTracker.key("Ranger", "Gone"), 100);
        t.retainSubjects("Warden7", java.util.Set.of("Steve"));
        assertEquals(2, t.size(), "Alex left, the other observer is untouched");
        assertTrue(t.inProgress(ExposureTracker.key("Warden7", "Steve"), 101));
        t.retainSubjects("Warden7", java.util.Set.of());
        assertEquals(1, t.size());
    }

    @Test
    void aClockGoingBackwardsStartsOver() {
        ExposureTracker t = new ExposureTracker();
        t.sighted(K, 5000);
        assertEquals(0, t.sighted(K, 10), "a new server: ticks restarted");
    }
}
