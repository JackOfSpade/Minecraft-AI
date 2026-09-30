package io.github.zoyluo.minecraftai.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A test that needs a degraded server forces it for its own bot only: the bot's scan rates drop to the degraded ones while every
 * other bot keeps the normal rates, and releasing it restores the normal rates.
 */
final class TpsGuardPerBotTest {
    @Test
    void forcingOneBotDegradedLeavesEveryOtherBotAlone() {
        UUID forced = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        TpsGuard.INSTANCE.reset();
        try {
            TpsGuard.forceDegradedForTests(forced, true);
            assertTrue(TpsGuard.isForcedDegraded(forced));
            assertTrue(TpsGuard.INSTANCE.degraded(forced));
            assertEquals(20, TpsGuard.INSTANCE.scanInterval(forced));
            assertEquals(5, TpsGuard.INSTANCE.dangerScanInterval(forced));

            assertFalse(TpsGuard.INSTANCE.degraded(other));
            assertEquals(1, TpsGuard.INSTANCE.scanInterval(other));
            assertEquals(1, TpsGuard.INSTANCE.dangerScanInterval(other));
            assertEquals(1, TpsGuard.INSTANCE.scanInterval(), "the server-wide verdict stays normal");
        } finally {
            TpsGuard.forceDegradedForTests(forced, false);
        }
        assertFalse(TpsGuard.INSTANCE.degraded(forced));
        assertEquals(1, TpsGuard.INSTANCE.scanInterval(forced));
    }
}
