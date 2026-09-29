package io.github.zoyluo.minecraftai.mode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapabilityAuditThrottleTest {
    private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final PrivilegedCapability SCAN = PrivilegedCapability.HIDDEN_BLOCK_SCAN;
    private static final CapabilityDecision.Reason DENIED = CapabilityDecision.Reason.DENIED_STRICT_SURVIVAL;

    private static CapabilityAuditThrottle.Outcome deny(CapabilityAuditThrottle t, String context, int tick) {
        return t.observe(BOT, SCAN, false, DENIED, context, tick, false);
    }

    @Test
    void firstOccurrenceOfADenialIsAlwaysLoggedInFull() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        assertTrue(deny(t, "observable_block_query", 0).logDecision());
        assertTrue(deny(t, "diagnostic_nearby", 0).logDecision(), "a new context is a new first occurrence");
        assertTrue(t.observe(BOT, PrivilegedCapability.EMERGENCY_TELEPORT, false, DENIED, "x", 0, false).logDecision());
    }

    @Test
    void anAllowedDecisionIsNeverHiddenEvenIfTheSameContextWasDeniedBefore() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        assertTrue(deny(t, "ctx", 0).logDecision());
        assertFalse(deny(t, "ctx", 1).logDecision());
        assertTrue(t.observe(BOT, SCAN, true, CapabilityDecision.Reason.ALLOWED_OPERATOR_FLAG, "ctx", 2, false).logDecision(),
                "the strict-survival canary reads allowed='true' lines: the first one must always appear");
    }

    @Test
    void aChangedReasonIsAFreshFirstOccurrence() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        assertTrue(deny(t, "ctx", 0).logDecision());
        assertTrue(t.observe(BOT, SCAN, false, CapabilityDecision.Reason.DENIED_OPERATOR_FLAG, "ctx", 1, false).logDecision());
    }

    @Test
    void repeatsAreQuietAndNoLongerLoggedEveryHundredTicks() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        assertTrue(deny(t, "ctx", 0).logDecision());
        int logged = 0;
        for (int tick = 1; tick < CapabilityAuditThrottle.SUMMARY_INTERVAL_TICKS; tick++) {
            CapabilityAuditThrottle.Outcome outcome = deny(t, "ctx", tick);
            if (outcome.logDecision() || outcome.summary() != null) {
                logged++;
            }
        }
        assertEquals(0, logged);
    }

    @Test
    void aPeriodicSummaryCarriesTheCountAndContexts() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        deny(t, "a", 0);
        deny(t, "b", 0);
        CapabilityAuditThrottle.Summary summary = null;
        int repeats = 0;
        for (int tick = 1; summary == null && tick < 5000; tick++) {
            repeats++;
            summary = deny(t, tick % 2 == 0 ? "a" : "b", tick).summary();
        }
        assertNotNull(summary);
        assertEquals(repeats, summary.count());
        assertEquals(List.of("b", "a"), summary.contexts());
        assertTrue(summary.windowTicks() >= CapabilityAuditThrottle.SUMMARY_INTERVAL_TICKS);
        assertEquals(new CapabilityAuditThrottle.Key(BOT, SCAN, false, DENIED), summary.key());
        // A new window starts from zero.
        assertNull(deny(t, "a", 5001).summary());
    }

    @Test
    void drainFlushesPendingRepeatsOnce() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        deny(t, "a", 0);
        deny(t, "a", 1);
        deny(t, "a", 2);
        List<CapabilityAuditThrottle.Summary> drained = t.drain(BOT, 10);
        assertEquals(1, drained.size());
        assertEquals(2, drained.get(0).count());
        assertTrue(t.drain(BOT, 11).isEmpty());
        assertTrue(t.drain(UUID.randomUUID(), 11).isEmpty());
    }

    @Test
    void alwaysAuditDecisionsAreLoggedEveryTime() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        for (int tick = 0; tick < 10; tick++) {
            assertTrue(t.observe(BOT, PrivilegedCapability.MANUAL_TELEPORT, true,
                    CapabilityDecision.Reason.ALLOWED_OPERATOR_FLAG, "tp", tick, true).logDecision());
        }
    }

    @Test
    void botsAreIndependentAndClearForgetsOne() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        deny(t, "ctx", 0);
        assertTrue(t.observe(other, SCAN, false, DENIED, "ctx", 0, false).logDecision());
        t.clear(BOT);
        assertTrue(deny(t, "ctx", 1).logDecision(), "cleared bot logs its first occurrence again");
        assertFalse(t.observe(other, SCAN, false, DENIED, "ctx", 1, false).logDecision());
    }

    @Test
    void manyDistinctContextsStayBoundedAndSummaryContextListIsCapped() {
        CapabilityAuditThrottle t = new CapabilityAuditThrottle();
        for (int i = 0; i < 1000; i++) {
            assertTrue(deny(t, "ctx" + i, 0).logDecision(), "first occurrences are all logged");
        }
        for (int i = 0; i < 20; i++) {
            deny(t, "ctx" + (1000 - 1 - i), 1);
        }
        CapabilityAuditThrottle.Summary summary = t.drain(BOT, 2).get(0);
        assertTrue(summary.contexts().size() <= CapabilityAuditThrottle.MAX_SUMMARY_CONTEXTS);
        assertEquals(20, summary.count());
    }
}
