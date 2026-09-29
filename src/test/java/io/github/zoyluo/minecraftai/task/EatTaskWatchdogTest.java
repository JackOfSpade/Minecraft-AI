package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The eating watchdog only fires for a bite that is still in the using-item state past the deadline. */
final class EatTaskWatchdogTest {
    @Test
    void expiresOnlyWhenStillUsingTheItemPastTheDeadline() {
        assertFalse(EatTask.isEatWatchdogExpired(EatTask.EAT_WATCHDOG_TICKS, true), "at the deadline is still fine");
        assertTrue(EatTask.isEatWatchdogExpired(EatTask.EAT_WATCHDOG_TICKS + 1, true));
        assertFalse(EatTask.isEatWatchdogExpired(EatTask.EAT_WATCHDOG_TICKS + 50, false),
                "a finished bite is never a watchdog case");
        assertFalse(EatTask.isEatWatchdogExpired(32, true), "a normal bite length is far below the deadline");
    }

    @Test
    void retriesOncePerBudgetThenGivesUp() {
        int stuck = EatTask.EAT_WATCHDOG_TICKS + 1;
        assertEquals(EatTask.WatchdogAction.NONE, EatTask.watchdogDecision(32, true, 0));
        assertEquals(EatTask.WatchdogAction.NONE, EatTask.watchdogDecision(stuck, false, 0),
                "a finished bite never triggers the watchdog, whatever the counter");
        assertEquals(EatTask.WatchdogAction.RETRY, EatTask.watchdogDecision(stuck, true, 0));
        assertEquals(EatTask.WatchdogAction.GIVE_UP,
                EatTask.watchdogDecision(stuck, true, EatTask.MAX_WATCHDOG_RETRIES),
                "once the retry budget is spent the next stuck bite ends the pass");
        assertEquals(EatTask.WatchdogAction.GIVE_UP, EatTask.watchdogDecision(stuck, true, EatTask.MAX_WATCHDOG_RETRIES + 3));
    }

    @Test
    void theRetryCounterIsWhatBoundsTheLoop() {
        // Drive the decision the way waitForFinish does: every RETRY bumps the counter, so a permanently stuck
        // bite yields exactly MAX_WATCHDOG_RETRIES retries and then GIVE_UP (never an endless retry loop).
        int retries = 0;
        int retryCount = 0;
        for (int round = 0; round < 10; round++) {
            EatTask.WatchdogAction action = EatTask.watchdogDecision(EatTask.EAT_WATCHDOG_TICKS + 1, true, retries);
            if (action == EatTask.WatchdogAction.RETRY) {
                retries++;
                retryCount++;
            } else {
                assertEquals(EatTask.WatchdogAction.GIVE_UP, action);
                break;
            }
        }
        assertEquals(EatTask.MAX_WATCHDOG_RETRIES, retryCount);
    }
}
