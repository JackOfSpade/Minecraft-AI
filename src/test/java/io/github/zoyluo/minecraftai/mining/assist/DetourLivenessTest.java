package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link DetourLiveness} (P1 contract section F.2, G.3): the live-derivation order (owner changed,
 * not running, phase idle, stale), the publish-age boundary, and the tick-granular net's TPS-before-hurt
 * ordering across every {@link DetourPhase#netAbortable()} phase.
 */
class DetourLivenessTest {

    // ---- check(): order and boundaries ----

    @Test
    void ownerChangedWinsEvenWhenEverythingElseIsAlsoWrong() {
        DetourLiveness.Verdict v = DetourLiveness.check(false, false, null, MiningAssistState.NEVER, 1000);
        assertEquals(DetourLiveness.Verdict.OWNER_CHANGED, v);
    }

    @Test
    void notRunningWinsOverPhaseAndStaleness() {
        DetourLiveness.Verdict v = DetourLiveness.check(true, false, null, MiningAssistState.NEVER, 1000);
        assertEquals(DetourLiveness.Verdict.NOT_RUNNING, v);
    }

    @Test
    void phaseIdleWinsOverStaleness() {
        DetourLiveness.Verdict v = DetourLiveness.check(true, true, DetourPhase.IDLE, MiningAssistState.NEVER, 1000);
        assertEquals(DetourLiveness.Verdict.PHASE_IDLE, v);

        DetourLiveness.Verdict nullPhase = DetourLiveness.check(true, true, null, MiningAssistState.NEVER, 1000);
        assertEquals(DetourLiveness.Verdict.PHASE_IDLE, nullPhase);
    }

    @Test
    void ageSixIsLiveAgeSevenIsStale() {
        DetourLiveness.Verdict age6 = DetourLiveness.check(true, true, DetourPhase.APPROACH, 994, 1000);
        assertEquals(DetourLiveness.Verdict.LIVE, age6);

        DetourLiveness.Verdict age7 = DetourLiveness.check(true, true, DetourPhase.APPROACH, 993, 1000);
        assertEquals(DetourLiveness.Verdict.STALE, age7);
    }

    @Test
    void neverPublishedIsStale() {
        DetourLiveness.Verdict v = DetourLiveness.check(true, true, DetourPhase.MINE, MiningAssistState.NEVER, 1000);
        assertEquals(DetourLiveness.Verdict.STALE, v);
    }

    @Test
    void publishedInTheFutureIsStale() {
        DetourLiveness.Verdict v = DetourLiveness.check(true, true, DetourPhase.MINE, 1001, 1000);
        assertEquals(DetourLiveness.Verdict.STALE, v);
    }

    @Test
    void liveWhenFreshlyPublished() {
        DetourLiveness.Verdict v = DetourLiveness.check(true, true, DetourPhase.RETURN, 1000, 1000);
        assertEquals(DetourLiveness.Verdict.LIVE, v);
    }

    // ---- netReason(): phase gating, TPS/headroom before hurt, edge-only hurt trigger ----

    @Test
    void netReasonIsNullForIdleReturnAndFinishWhateverTheInputs() {
        for (DetourPhase phase : new DetourPhase[] {DetourPhase.IDLE, DetourPhase.RETURN, DetourPhase.FINISH}) {
            assertNull(DetourLiveness.netReason(phase, true, true, 10, 0), phase.name());
        }
    }

    @Test
    void netReasonFiresDegradedTpsOnEveryAbortablePhase() {
        for (DetourPhase phase : abortablePhases()) {
            assertEquals(DetourLiveness.NET_DEGRADED_TPS,
                    DetourLiveness.netReason(phase, true, false, 0, 0), phase.name());
            assertEquals(DetourLiveness.NET_DEGRADED_TPS,
                    DetourLiveness.netReason(phase, false, true, 0, 0), phase.name());
        }
    }

    @Test
    void netReasonFiresHurtOnARiseOnEveryAbortablePhase() {
        for (DetourPhase phase : abortablePhases()) {
            assertEquals(DetourLiveness.NET_HURT,
                    DetourLiveness.netReason(phase, false, false, 10, 0), phase.name());
        }
    }

    @Test
    void netReasonIsNullOnASteadyOrFallingHurtTime() {
        for (DetourPhase phase : abortablePhases()) {
            assertNull(DetourLiveness.netReason(phase, false, false, 10, 10), phase.name());
            assertNull(DetourLiveness.netReason(phase, false, false, 5, 10), phase.name());
        }
    }

    @Test
    void tpsWinsOverHurtWhenBothFire() {
        String reason = DetourLiveness.netReason(DetourPhase.APPROACH, true, false, 10, 0);
        assertEquals(DetourLiveness.NET_DEGRADED_TPS, reason);
    }

    @Test
    void netAbortablePhasesMatchTheDesign() {
        assertTrue(DetourPhase.APPROACH.netAbortable());
        assertTrue(DetourPhase.MINE.netAbortable());
        assertTrue(DetourPhase.POSTBREAK.netAbortable());
        assertTrue(DetourPhase.SETTLE_DROP.netAbortable());
        assertTrue(DetourPhase.NEXT.netAbortable());
        assertFalse(DetourPhase.RETURN.netAbortable());
        assertFalse(DetourPhase.IDLE.netAbortable());
        assertFalse(DetourPhase.FINISH.netAbortable());
    }

    private static DetourPhase[] abortablePhases() {
        return new DetourPhase[] {
                DetourPhase.APPROACH, DetourPhase.MINE, DetourPhase.POSTBREAK,
                DetourPhase.SETTLE_DROP, DetourPhase.NEXT
        };
    }
}
