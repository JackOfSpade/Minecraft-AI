package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateCacheTest {
    private static final UUID BOT = new UUID(1L, 2L);

    @Test
    void aVerdictIsServedForTwentyTicksAndThenExpires() {
        GateCache cache = new GateCache();
        assertEquals(20, GateCache.TTL_TICKS);
        cache.put(BOT, 1000, true, null);
        assertNotNull(cache.fresh(BOT, 1000));
        assertNotNull(cache.fresh(BOT, 1019));
        assertNull(cache.fresh(BOT, 1020));
        assertNull(cache.fresh(BOT, 5000));
    }

    @Test
    void aTickThatMovedBackwardsNeverServesAStaleVerdict() {
        GateCache cache = new GateCache();
        cache.put(BOT, 1000, true, null);
        assertNull(cache.fresh(BOT, 999));
        assertNull(cache.fresh(BOT, 0));
    }

    @Test
    void unknownBotHasNoVerdict() {
        GateCache cache = new GateCache();
        assertNull(cache.fresh(BOT, 0));
        assertNull(cache.last(BOT));
    }

    @Test
    void lastSurvivesExpiryForChangeDetection() {
        GateCache cache = new GateCache();
        cache.put(BOT, 10, false, AssistGate.DENY_ORIGIN);
        assertNull(cache.fresh(BOT, 500));
        GateCache.Verdict last = cache.last(BOT);
        assertNotNull(last);
        assertFalse(last.enabled());
        assertEquals(AssistGate.DENY_ORIGIN, last.denyReason());
        assertEquals(10L, last.sinceTick());
    }

    @Test
    void putReplacesAndReturnsTheVerdict() {
        GateCache cache = new GateCache();
        cache.put(BOT, 10, false, AssistGate.DENY_TPS);
        GateCache.Verdict now = cache.put(BOT, 12, true, null);
        assertTrue(now.enabled());
        assertNull(now.denyReason());
        assertTrue(cache.fresh(BOT, 13).enabled());
        assertEquals(1, cache.size());
    }

    @Test
    void removeAndClear() {
        GateCache cache = new GateCache();
        cache.put(BOT, 1, true, null);
        cache.put(new UUID(9L, 9L), 1, true, null);
        cache.remove(BOT);
        assertNull(cache.last(BOT));
        assertEquals(1, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }
}
