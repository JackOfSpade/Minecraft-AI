package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistModeTest {
    @Test
    void parsesEveryModeName() {
        assertEquals(Optional.of(AssistMode.OFF), AssistMode.parse("off"));
        assertEquals(Optional.of(AssistMode.SENSE), AssistMode.parse("sense"));
        assertEquals(Optional.of(AssistMode.DETOUR), AssistMode.parse("detour"));
        assertEquals(Optional.of(AssistMode.POI), AssistMode.parse("poi"));
        assertEquals(Optional.of(AssistMode.ALL), AssistMode.parse("all"));
    }

    @Test
    void parseIsCaseInsensitiveAndTrims() {
        assertEquals(Optional.of(AssistMode.ALL), AssistMode.parse("ALL"));
        assertEquals(Optional.of(AssistMode.SENSE), AssistMode.parse("  Sense\t"));
        assertEquals(Optional.of(AssistMode.POI), AssistMode.parse("\npOi "));
        for (AssistMode mode : AssistMode.values()) {
            assertEquals(Optional.of(mode), AssistMode.parse(mode.name()));
            assertEquals(Optional.of(mode), AssistMode.parse(mode.name().toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    void parseIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(Optional.of(AssistMode.POI), AssistMode.parse("poi"), "dotless-i locale must not break parsing");
            assertEquals(Optional.of(AssistMode.ALL), AssistMode.parse("all"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void unknownBlankAndNullYieldEmpty() {
        assertTrue(AssistMode.parse(null).isEmpty());
        assertTrue(AssistMode.parse("").isEmpty());
        assertTrue(AssistMode.parse("   ").isEmpty());
        assertTrue(AssistMode.parse("on").isEmpty());
        assertTrue(AssistMode.parse("true").isEmpty());
        assertTrue(AssistMode.parse("1").isEmpty());
        assertTrue(AssistMode.parse("detours").isEmpty());
        assertTrue(AssistMode.parse("all,poi").isEmpty());
        assertTrue(AssistMode.parse("off ok").isEmpty());
    }

    @Test
    void atLeastFollowsTheRolloutOrder() {
        for (AssistMode mode : AssistMode.values()) {
            assertTrue(mode.atLeast(mode), mode + " is at least itself");
            assertTrue(mode.atLeast(AssistMode.OFF), mode + " >= OFF");
        }
        assertFalse(AssistMode.OFF.atLeast(AssistMode.SENSE));
        assertTrue(AssistMode.SENSE.atLeast(AssistMode.SENSE));
        assertFalse(AssistMode.SENSE.atLeast(AssistMode.DETOUR));
        assertTrue(AssistMode.DETOUR.atLeast(AssistMode.SENSE));
        assertTrue(AssistMode.POI.atLeast(AssistMode.SENSE));
        assertTrue(AssistMode.ALL.atLeast(AssistMode.POI));
        assertFalse(AssistMode.POI.atLeast(AssistMode.ALL));
        assertFalse(AssistMode.ALL.atLeast(null), "null is never satisfied");
    }

    @Test
    void capabilityMatrix() {
        assertCaps(AssistMode.OFF, false, false, false);
        assertCaps(AssistMode.SENSE, true, false, false);
        assertCaps(AssistMode.DETOUR, true, true, false);
        assertCaps(AssistMode.POI, true, false, true);
        assertCaps(AssistMode.ALL, true, true, true);
    }

    private static void assertCaps(AssistMode mode, boolean sense, boolean detour, boolean poi) {
        assertEquals(sense, mode.allowsSense(), mode + " sense");
        assertEquals(detour, mode.allowsDetour(), mode + " detour");
        assertEquals(poi, mode.allowsPoi(), mode + " poi");
        assertEquals(sense, mode.atLeast(AssistMode.SENSE), mode + " sense == atLeast(SENSE)");
    }
}
