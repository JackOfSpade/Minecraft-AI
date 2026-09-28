package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenseFailureGateTest {
    private static final UUID A = new UUID(1L, 2L);
    private static final UUID B = new UUID(3L, 4L);

    @Test
    void theRecoveryOfAFailedPassIsNotANewSessionExactlyOnce() {
        SenseFailureGate gate = new SenseFailureGate();
        assertFalse(gate.takeReenableSuppression(A), "no failure, nothing to withhold");
        gate.recordFailure(A, 100);
        assertTrue(gate.takeReenableSuppression(A), "the enabled line after a failure is withheld");
        assertFalse(gate.takeReenableSuppression(A), "but only that one");
        // A persistent fault: fail, rebuild, fail again 100 ticks later. Still no enabled line per cooldown.
        gate.recordFailure(A, 200);
        assertTrue(gate.takeReenableSuppression(A));
        gate.recordFailure(A, 300);
        gate.recordFailure(A, 400);
        assertTrue(gate.takeReenableSuppression(A));
        assertFalse(gate.takeReenableSuppression(B), "per bot");
    }

    @Test
    void clearingABotForgetsItsPendingSuppression() {
        SenseFailureGate gate = new SenseFailureGate();
        gate.recordFailure(A, 100);
        gate.clear(A);
        assertFalse(gate.takeReenableSuppression(A));
    }

    @Test
    void aBotWithoutFailuresIsNeverCoolingDown() {
        SenseFailureGate gate = new SenseFailureGate();
        assertFalse(gate.coolingDown(A, 0));
        assertFalse(gate.coolingDown(A, 999_999));
        assertEquals(0, gate.size());
    }

    @Test
    void theFirstFailureIsLoggedAndRepeatsInsideTheIntervalAreNot() {
        SenseFailureGate gate = new SenseFailureGate();
        assertTrue(gate.recordFailure(A, 1000));
        for (int tick = 1001; tick < 1000 + SenseFailureGate.LOG_INTERVAL_TICKS; tick += 37) {
            assertFalse(gate.recordFailure(A, tick), "tick " + tick);
        }
        assertTrue(gate.recordFailure(A, 1000 + SenseFailureGate.LOG_INTERVAL_TICKS),
                "one line per bot per minute");
    }

    @Test
    void theLogIntervalRunsFromTheLastLoggedFailureNotFromTheLastFailure() {
        SenseFailureGate gate = new SenseFailureGate();
        assertTrue(gate.recordFailure(A, 0));
        // A failure every 100 ticks must not push the next log line out forever.
        for (int tick = 100; tick < SenseFailureGate.LOG_INTERVAL_TICKS; tick += 100) {
            assertFalse(gate.recordFailure(A, tick));
        }
        assertTrue(gate.recordFailure(A, SenseFailureGate.LOG_INTERVAL_TICKS));
    }

    @Test
    void sensingPausesForTheCooldownAfterAFailureAndThenResumesByItself() {
        SenseFailureGate gate = new SenseFailureGate();
        gate.recordFailure(A, 500);
        assertTrue(gate.coolingDown(A, 500));
        assertTrue(gate.coolingDown(A, 500 + SenseFailureGate.COOLDOWN_TICKS - 1));
        assertFalse(gate.coolingDown(A, 500 + SenseFailureGate.COOLDOWN_TICKS),
                "a pause, never a permanent disable (design 3.4)");
    }

    @Test
    void aFreshFailureRestartsTheCooldown() {
        SenseFailureGate gate = new SenseFailureGate();
        gate.recordFailure(A, 0);
        gate.recordFailure(A, SenseFailureGate.COOLDOWN_TICKS + 20);
        assertTrue(gate.coolingDown(A, SenseFailureGate.COOLDOWN_TICKS + 21));
    }

    @Test
    void botsAreIndependent() {
        SenseFailureGate gate = new SenseFailureGate();
        assertTrue(gate.recordFailure(A, 10));
        assertTrue(gate.recordFailure(B, 11), "another bot's first failure is logged too");
        assertTrue(gate.coolingDown(A, 12));
        gate.clear(A);
        assertFalse(gate.coolingDown(A, 12));
        assertTrue(gate.coolingDown(B, 12));
        assertEquals(1, gate.size());
        gate.clearAll();
        assertEquals(0, gate.size());
        assertTrue(gate.recordFailure(A, 13), "a cleared bot starts over");
    }

    @Test
    void aTickCounterThatGoesBackwardsNeitherCoolsDownNorSwallowsTheLog() {
        SenseFailureGate gate = new SenseFailureGate();
        gate.recordFailure(A, 50_000);
        assertFalse(gate.coolingDown(A, 10), "time went backwards: not cooling down");
        assertTrue(gate.recordFailure(A, 10), "and the failure is logged again");
    }
}
