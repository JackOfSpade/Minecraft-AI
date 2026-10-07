package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmRetryPolicyTest {
    private static final LlmRetryPolicy FLOOR = new LlmRetryPolicy(() -> 0.0D);
    private static final LlmRetryPolicy CEILING = new LlmRetryPolicy(() -> 0.999999D);

    @Test
    void waitsDoubleFromTheInitialBackoffUpToTheCap() {
        long[] ceilings = {2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000};
        for (int failures = 1; failures <= ceilings.length; failures++) {
            long delay = CEILING.delayBeforeRetry(failures, null, 0).orElseThrow();
            assertTrue(delay <= ceilings[failures - 1] && delay > ceilings[failures - 1] - 5,
                    "failure " + failures + " waited " + delay);
        }
    }

    @Test
    void jitterNeverShortensTheWaitBelowHalfTheCeiling() {
        // The 2026-10-05 outage retried 0.3-1 s apart because nothing waited; the floor rules that out.
        assertEquals(1_000, FLOOR.delayBeforeRetry(1, null, 0).orElseThrow());
        assertEquals(2_000, FLOOR.delayBeforeRetry(2, null, 0).orElseThrow());
        assertEquals(15_000, FLOOR.delayBeforeRetry(9, null, 0).orElseThrow());
    }

    @Test
    void jitterSpreadsTheWaitAcrossTheUpperHalf() {
        long low = new LlmRetryPolicy(() -> 0.0D).delayBeforeRetry(3, null, 0).orElseThrow();
        long mid = new LlmRetryPolicy(() -> 0.5D).delayBeforeRetry(3, null, 0).orElseThrow();
        long high = new LlmRetryPolicy(() -> 0.999D).delayBeforeRetry(3, null, 0).orElseThrow();
        assertEquals(4_000, low);
        assertEquals(6_000, mid);
        assertTrue(high > mid && high < 8_000);
    }

    @Test
    void aHugeFailureCountDoesNotOverflowTheShift() {
        assertEquals(15_000, FLOOR.delayBeforeRetry(Integer.MAX_VALUE, null, 0).orElseThrow());
    }

    @Test
    void retryAfterIsAFloorLongerThanTheBackoff() {
        assertEquals(45_000, FLOOR.delayBeforeRetry(1, Duration.ofSeconds(45), 0).orElseThrow());
    }

    @Test
    void retryAfterShorterThanTheBackoffDoesNotShortenIt() {
        assertEquals(15_000, FLOOR.delayBeforeRetry(9, Duration.ofSeconds(1), 0).orElseThrow());
    }

    @Test
    void stopsOnceTheNextWaitWouldOutlastThePatience() {
        long almostOut = LlmRetryPolicy.TOTAL_PATIENCE_MS - 15_000;
        assertEquals(OptionalLong.of(15_000), FLOOR.delayBeforeRetry(9, null, almostOut));
        assertEquals(OptionalLong.empty(), FLOOR.delayBeforeRetry(9, null, almostOut + 1));
    }

    @Test
    void aRetryAfterBeyondTheRemainingPatienceGivesUpInsteadOfWaiting() {
        assertEquals(OptionalLong.empty(),
                FLOOR.delayBeforeRetry(1, Duration.ofMinutes(10), 0));
    }

    @Test
    void theNoticeComesWithTheWaitThatCarriesTheRequestPastTenSeconds() {
        assertFalse(LlmRetryPolicy.worthTellingThePlayer(0, 1_000));
        assertFalse(LlmRetryPolicy.worthTellingThePlayer(7_000, 2_999));
        assertTrue(LlmRetryPolicy.worthTellingThePlayer(7_000, 3_000));
        assertTrue(LlmRetryPolicy.worthTellingThePlayer(0, 34_000), "a service that asks for a long wait is reported at once");
        assertTrue(LlmRetryPolicy.worthTellingThePlayer(60_000, 1), "an attempt that itself took a minute to time out");
    }

    @Test
    void thePatienceSurvivesAMultiMinuteOutage() {

        assertTrue(LlmRetryPolicy.TOTAL_PATIENCE_MS >= 4 * 60_000L);
    }
}
