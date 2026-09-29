package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure timing/decision coverage of {@link RecoveryClock}; no world, bot or player involved. */
final class RecoveryClockTest {
    private static final int STALL = 100;
    private static final int GIVE_UP = 600;
    private static final int WINDOW = 40;
    private static final int RETRY = 100;

    private static RecoveryClock clock() {
        RecoveryClock clock = new RecoveryClock(STALL, GIVE_UP, WINDOW, RETRY);
        clock.reset(0);
        return clock;
    }

    @Test
    void doesNotIntercept_untilTheStallThresholdIsReached() {
        RecoveryClock clock = clock();
        for (int tick = 1; tick < STALL; tick++) {
            assertEquals(RecoveryClock.Action.NOT_HANDLED, clock.tick(tick, false), "tick " + tick);
            assertFalse(clock.isRecovering());
        }
        assertEquals(RecoveryClock.Action.STEP, clock.tick(STALL, false));
        assertTrue(clock.isRecovering());
    }

    @Test
    void alternatesStepWindowsWithASingleForcedReplanEveryWindow() {
        RecoveryClock clock = clock();
        int start = STALL;
        // First window: adjacent steps.
        for (int tick = start; tick < start + WINDOW; tick++) {
            assertEquals(RecoveryClock.Action.STEP, clock.tick(tick, false), "tick " + tick);
        }
        // Second window: one forced replan on its first tick, then the caller's ordinary logic.
        assertEquals(RecoveryClock.Action.FORCE_REPATH, clock.tick(start + WINDOW, false));
        for (int tick = start + WINDOW + 1; tick < start + 2 * WINDOW; tick++) {
            assertEquals(RecoveryClock.Action.NOT_HANDLED, clock.tick(tick, false), "tick " + tick);
        }
        // Third window: steps again.
        assertEquals(RecoveryClock.Action.STEP, clock.tick(start + 2 * WINDOW, false));
    }

    @Test
    void progressResolvesRecoveryOnceAndRestartsTheStallCount() {
        RecoveryClock clock = clock();
        clock.tick(STALL, false);
        assertTrue(clock.isRecovering());
        assertEquals(RecoveryClock.Action.RESOLVED, clock.tick(STALL + 5, true));
        assertFalse(clock.isRecovering());
        assertEquals(STALL, clock.lastRecoveryStartTick(), "kept for the resolution log line");
        assertEquals(RecoveryClock.Action.NOT_HANDLED, clock.tick(STALL + 6, true),
                "progress outside recovery is never reported as a resolution");
        // The stall count restarts from the last progress, not from the original reset.
        assertEquals(RecoveryClock.Action.NOT_HANDLED, clock.tick(STALL + 6 + STALL - 1, false));
        assertEquals(RecoveryClock.Action.STEP, clock.tick(STALL + 6 + STALL, false));
    }

    @Test
    void backoffForcesAReplanEveryRetryIntervalAndAnnouncesStuckExactlyOnce() {
        RecoveryClock clock = clock();
        int start = STALL;
        clock.tick(start, false);
        for (int tick = start + 1; tick < start + GIVE_UP; tick++) {
            clock.tick(tick, false);
            assertFalse(clock.hasAnnouncedStuck(), "not before giving up, tick " + tick);
        }
        int giveUpTick = start + GIVE_UP;
        assertEquals(RecoveryClock.Action.BACKOFF_FORCE_REPATH, clock.tick(giveUpTick, false));
        assertTrue(clock.hasAnnouncedStuck());
        for (int tick = giveUpTick + 1; tick < giveUpTick + RETRY; tick++) {
            assertEquals(RecoveryClock.Action.BACKOFF_HOLD, clock.tick(tick, false), "tick " + tick);
        }
        assertEquals(RecoveryClock.Action.BACKOFF_FORCE_REPATH, clock.tick(giveUpTick + RETRY, false));
        assertEquals(RecoveryClock.Action.BACKOFF_HOLD, clock.tick(giveUpTick + RETRY + 1, false));
        assertEquals(RecoveryClock.Action.BACKOFF_FORCE_REPATH, clock.tick(giveUpTick + 2 * RETRY, false));
        assertTrue(clock.hasAnnouncedStuck());
    }

    @Test
    void progressDuringBackoffClearsTheAnnouncementSoALaterEpisodeCanAnnounceAgain() {
        RecoveryClock clock = clock();
        for (int tick = STALL; tick <= STALL + GIVE_UP; tick++) {
            clock.tick(tick, false);
        }
        assertTrue(clock.hasAnnouncedStuck());
        assertEquals(RecoveryClock.Action.RESOLVED, clock.tick(STALL + GIVE_UP + 1, true));
        assertFalse(clock.hasAnnouncedStuck());
        assertFalse(clock.isRecovering());
    }

    @Test
    void resetDropsRecoveryState() {
        RecoveryClock clock = clock();
        for (int tick = STALL; tick <= STALL + GIVE_UP; tick++) {
            clock.tick(tick, false);
        }
        clock.reset(2_000);
        assertFalse(clock.isRecovering());
        assertFalse(clock.hasAnnouncedStuck());
        assertEquals(RecoveryClock.Action.NOT_HANDLED, clock.tick(2_000 + STALL - 1, false));
        assertEquals(RecoveryClock.Action.STEP, clock.tick(2_000 + STALL, false));
    }
}
