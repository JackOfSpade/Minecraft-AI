package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenseStatusTest {
    private static final int GRACE = SenseStatus.DISABLE_GRACE_TICKS;
    private static final int IDLE = SenseStatus.IDLE_RELEASE_TICKS;

    @Test
    void aFreshStatusIsNotSensingHasNeverSensedAndIsNeverIdle() {
        SenseStatus status = new SenseStatus();
        assertFalse(status.sensing());
        assertEquals(SenseStatus.NEVER, status.lastSensedTick());
        assertEquals(SenseStatus.Change.NONE, status.notSensed(100));
        assertFalse(status.idleFor(1_000_000, IDLE), "a state that never sensed holds nothing to release");
    }

    @Test
    void theFirstSensingTickEnablesAndLaterOnesDoNot() {
        SenseStatus status = new SenseStatus();
        assertEquals(SenseStatus.Change.ENABLED, status.sensed(10));
        assertTrue(status.sensing());
        assertEquals(SenseStatus.Change.NONE, status.sensed(11));
        assertEquals(SenseStatus.Change.NONE, status.sensed(12));
        assertEquals(12, status.lastSensedTick());
    }

    @Test
    void aShortInterruptionNeverLogsBecauseSensingResumesInsideTheGrace() {
        SenseStatus status = new SenseStatus();
        status.sensed(100);
        for (int tick = 101; tick < 100 + GRACE; tick++) {
            assertEquals(SenseStatus.Change.NONE, status.notSensed(tick), "tick " + tick);
        }
        assertEquals(SenseStatus.Change.NONE, status.sensed(100 + GRACE - 1),
                "resuming inside the grace is the same session: no second enabled line");
        assertTrue(status.sensing());
    }

    @Test
    void theSessionEndsExactlyOnceWhenTheGraceRunsOut() {
        SenseStatus status = new SenseStatus();
        status.sensed(100);
        assertEquals(SenseStatus.Change.NONE, status.notSensed(100 + GRACE - 1));
        assertEquals(SenseStatus.Change.DISABLED, status.notSensed(100 + GRACE));
        assertFalse(status.sensing());
        assertEquals(SenseStatus.Change.NONE, status.notSensed(100 + GRACE + 1), "disabled is reported once");
        assertEquals(SenseStatus.Change.NONE, status.notSensed(100 + GRACE + 500));
    }

    @Test
    void aNewSessionAfterADisableIsEnabledAgain() {
        SenseStatus status = new SenseStatus();
        status.sensed(0);
        assertEquals(SenseStatus.Change.DISABLED, status.notSensed(GRACE));
        assertEquals(SenseStatus.Change.ENABLED, status.sensed(GRACE + 5));
    }

    @Test
    void idleForCountsFromTheLastSensingTickAndNeedsTheSessionToHaveEnded() {
        SenseStatus status = new SenseStatus();
        status.sensed(500);
        assertFalse(status.idleFor(500 + IDLE, IDLE), "still counted as sensing until notSensed ended the session");
        status.notSensed(500 + GRACE);
        assertFalse(status.idleFor(500 + IDLE - 1, IDLE));
        assertTrue(status.idleFor(500 + IDLE, IDLE));
        assertTrue(status.idleFor(500 + IDLE + 1000, IDLE));
    }

    @Test
    void aTickCounterThatGoesBackwardsResynchronisesInsteadOfEndingTheSession() {
        SenseStatus status = new SenseStatus();
        status.sensed(10_000);
        assertEquals(SenseStatus.Change.NONE, status.notSensed(5), "time went backwards: no disable");
        assertEquals(5, status.lastSensedTick());
        assertEquals(SenseStatus.Change.DISABLED, status.notSensed(5 + GRACE));
        assertFalse(status.idleFor(1, IDLE), "a backwards clock is never idle");
    }
}
