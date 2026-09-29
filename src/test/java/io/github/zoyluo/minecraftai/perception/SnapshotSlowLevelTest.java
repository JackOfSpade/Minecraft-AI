package io.github.zoyluo.minecraftai.perception;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SnapshotSlowLevelTest {
    @Test
    void ordinarySnapshotCostIsNotLoggedAtAll() {
        assertNull(PerceptionCollector.slowSnapshotLevel(0L));
        assertNull(PerceptionCollector.slowSnapshotLevel(10L));
    }

    @Test
    void elevenToFiftyMillisecondsIsOnlyDebugNoiseNeverWarn() {
        // Real session: 76 snapshot_slow WARN lines at 11-32 ms.
        for (long ms : new long[] {11L, 19L, 32L, 50L}) {
            assertEquals(Level.DEBUG, PerceptionCollector.slowSnapshotLevel(ms), ms + " ms");
        }
    }

    @Test
    void aFullTickOrMoreIsAWarning() {
        assertEquals(Level.WARN, PerceptionCollector.slowSnapshotLevel(51L));
        assertEquals(Level.WARN, PerceptionCollector.slowSnapshotLevel(400L));
    }
}
