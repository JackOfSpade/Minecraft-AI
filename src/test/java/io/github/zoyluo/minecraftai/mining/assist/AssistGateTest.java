package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistGateTest {
    private static final Set<String> FIVE_REAL = Set.of("MISSION", "PLAYER_COMMAND", "PLAYER_PANEL", "LLM_TOOL", "JOB");

    @Test
    void openGateNeedsEveryConditionAtOnce() {
        assertTrue(AssistGate.enabled(AssistMode.SENSE, false, false, true, false, false));
        assertTrue(AssistGate.enabled(AssistMode.ALL, false, false, true, false, false));
    }

    @Test
    void fullTruthTableMatchesTheDesignRule() {
        for (AssistMode mode : AssistMode.values()) {
            for (int bits = 0; bits < 32; bits++) {
                boolean harnessOff = (bits & 1) != 0;
                boolean forced = (bits & 2) != 0;
                boolean realOrigin = (bits & 4) != 0;
                boolean audit = (bits & 8) != 0;
                boolean tpsDegraded = (bits & 16) != 0;

                boolean expected = mode != AssistMode.OFF
                        && (!harnessOff || forced)
                        && realOrigin
                        && !audit
                        && !tpsDegraded;

                assertEquals(expected,
                        AssistGate.enabled(mode, harnessOff, forced, realOrigin, audit, tpsDegraded),
                        mode + " harnessOff=" + harnessOff + " forced=" + forced + " origin=" + realOrigin
                                + " audit=" + audit + " tps=" + tpsDegraded);
            }
        }
    }

    @Test
    void modeOffAndNullModeNeverEnable() {
        assertFalse(AssistGate.enabled(AssistMode.OFF, false, true, true, false, false));
        assertFalse(AssistGate.enabled(null, false, true, true, false, false));
    }

    @Test
    void forcingBypassesOnlyTheHarnessDefault() {
        assertFalse(AssistGate.enabled(AssistMode.ALL, true, false, true, false, false));
        assertTrue(AssistGate.enabled(AssistMode.ALL, true, true, true, false, false));

        assertFalse(AssistGate.enabled(AssistMode.OFF, true, true, true, false, false), "mode still applies");
        assertFalse(AssistGate.enabled(AssistMode.ALL, true, true, false, false, false), "origin still applies");
        assertFalse(AssistGate.enabled(AssistMode.ALL, true, true, true, true, false), "audit still applies");
        assertFalse(AssistGate.enabled(AssistMode.ALL, true, true, true, false, true), "tps still applies");
    }

    @Test
    void auditSessionAndDegradedTpsAlwaysRefuse() {
        for (AssistMode mode : AssistMode.values()) {
            assertFalse(AssistGate.enabled(mode, false, true, true, true, false));
            assertFalse(AssistGate.enabled(mode, false, true, true, false, true));
        }
    }

    @Test
    void denyReasonNamesTheFirstFailingConditionInOrder() {
        assertNull(AssistGate.denyReason(AssistMode.ALL, false, false, true, false, false));

        // Everything fails at once: mode is reported first, then peeled one condition at a time.
        assertEquals("mode_off", AssistGate.denyReason(AssistMode.OFF, true, false, false, true, true));
        assertEquals("harness_off", AssistGate.denyReason(AssistMode.ALL, true, false, false, true, true));
        assertEquals("origin", AssistGate.denyReason(AssistMode.ALL, true, true, false, true, true));
        assertEquals("audit_session", AssistGate.denyReason(AssistMode.ALL, true, true, true, true, true));
        assertEquals("tps_degraded", AssistGate.denyReason(AssistMode.ALL, true, true, true, false, true));
        assertNull(AssistGate.denyReason(AssistMode.ALL, true, true, true, false, false));

        assertEquals("mode_off", AssistGate.DENY_MODE_OFF);
        assertEquals("harness_off", AssistGate.DENY_HARNESS_OFF);
        assertEquals("origin", AssistGate.DENY_ORIGIN);
        assertEquals("audit_session", AssistGate.DENY_AUDIT);
        assertEquals("tps_degraded", AssistGate.DENY_TPS);
    }

    @Test
    void enabledIsExactlyDenyReasonBeingNull() {
        for (AssistMode mode : AssistMode.values()) {
            for (int bits = 0; bits < 32; bits++) {
                boolean a = (bits & 1) != 0;
                boolean b = (bits & 2) != 0;
                boolean c = (bits & 4) != 0;
                boolean d = (bits & 8) != 0;
                boolean e = (bits & 16) != 0;

                assertEquals(AssistGate.denyReason(mode, a, b, c, d, e) == null,
                        AssistGate.enabled(mode, a, b, c, d, e));
            }
        }
    }

    // ---- origin kinds --------------------------------------------------------------------------

    @Test
    void theFiveRealOriginNamesAreExposedAsConstants() {
        assertEquals("MISSION", AssistGate.MISSION);
        assertEquals("PLAYER_COMMAND", AssistGate.PLAYER_COMMAND);
        assertEquals("PLAYER_PANEL", AssistGate.PLAYER_PANEL);
        assertEquals("LLM_TOOL", AssistGate.LLM_TOOL);
        assertEquals("JOB", AssistGate.JOB);
        assertEquals(List.of("MISSION", "PLAYER_COMMAND", "PLAYER_PANEL", "LLM_TOOL", "JOB"),
                AssistGate.realOriginNames());
        assertThrows(UnsupportedOperationException.class, () -> AssistGate.realOriginNames().add("VERIFY"));
    }

    @Test
    void onlyTheFiveRealKindsAreRealOrigins() {
        for (String name : FIVE_REAL) {
            assertTrue(AssistGate.isRealOrigin(name), name);
        }
        assertFalse(AssistGate.isRealOrigin("SAFETY"));
        assertFalse(AssistGate.isRealOrigin("SYSTEM_BACKGROUND"));
        assertFalse(AssistGate.isRealOrigin("VERIFY"));
        assertFalse(AssistGate.isRealOrigin(null));
        assertFalse(AssistGate.isRealOrigin(""));
        assertFalse(AssistGate.isRealOrigin("mission"), "names are the exact enum constants");
        assertFalse(AssistGate.isRealOrigin(" MISSION "));
        assertFalse(AssistGate.isRealOrigin("MISSION_2"));
    }

    @Test
    void namesTrackTheRealTaskOriginKinds() {
        for (String name : FIVE_REAL) {
            TaskOrigin.Kind kind = TaskOrigin.Kind.valueOf(name); // fails if a constant is renamed
            assertTrue(AssistGate.isRealOrigin(kind.name()));
        }
        for (TaskOrigin.Kind kind : TaskOrigin.Kind.values()) {
            assertEquals(FIVE_REAL.contains(kind.name()), AssistGate.isRealOrigin(kind.name()),
                    "kind " + kind + " needs an explicit decision");
        }
        assertFalse(AssistGate.isRealOrigin(TaskOrigin.Kind.SAFETY.name()));
        assertFalse(AssistGate.isRealOrigin(TaskOrigin.Kind.VERIFY.name()));
        assertFalse(AssistGate.isRealOrigin(TaskOrigin.Kind.SYSTEM_BACKGROUND.name()));
    }
}
