package io.github.zoyluo.minecraftai.task;

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
}
