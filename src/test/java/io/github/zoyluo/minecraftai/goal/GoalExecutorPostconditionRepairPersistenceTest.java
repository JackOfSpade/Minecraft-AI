package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorPostconditionRepairPersistenceTest {
    @Test
    void missingNamespaceIsLegacyAndCanonicalStatesRoundTrip() {
        GoalExecutor.PostconditionRepairCheckpoint legacy =
                GoalCheckpointCodec.decodePostconditionRepairCheckpoint(Map.of()).orElseThrow();
        assertFalse(legacy.persisted());
        assertEquals(0, legacy.replans());
        assertEquals(0, legacy.lastMatched());
        assertEquals("", legacy.fingerprint());

        Map<String, String> untouched =
                GoalCheckpointCodec.encodePostconditionRepairCheckpoint(0, 7, "");
        GoalExecutor.PostconditionRepairCheckpoint decodedUntouched =
                GoalCheckpointCodec.decodePostconditionRepairCheckpoint(untouched).orElseThrow();
        assertTrue(decodedUntouched.persisted());
        assertEquals(0, decodedUntouched.replans());
        assertEquals(7, decodedUntouched.lastMatched());
        assertEquals("", decodedUntouched.fingerprint());

        String fingerprint = "[HUNT x4, COOK_FOOD x4]";
        Map<String, String> exhausted =
                GoalCheckpointCodec.encodePostconditionRepairCheckpoint(3, 11, fingerprint);
        GoalExecutor.PostconditionRepairCheckpoint decodedExhausted =
                GoalCheckpointCodec.decodePostconditionRepairCheckpoint(exhausted).orElseThrow();
        assertTrue(decodedExhausted.persisted());
        assertEquals(3, decodedExhausted.replans());
        assertEquals(11, decodedExhausted.lastMatched());
        assertEquals(fingerprint, decodedExhausted.fingerprint());
    }

    @Test
    void partialUnknownOrNonCanonicalNamespaceFailsClosed() {
        assertInvalid(Map.of("postcondition_replans", "1"));
        assertInvalid(Map.of("postcondition_unknown", "value"));

        Map<String, String> canonical =
                GoalCheckpointCodec.encodePostconditionRepairCheckpoint(
                        1, 2, "[HUNT x4]");
        for (Map.Entry<String, String> mutation : Map.of(
                "postcondition_replans", "01",
                "postcondition_last_matched", "+2",
                "postcondition_fingerprint", "%%%").entrySet()) {
            Map<String, String> malformed = new LinkedHashMap<>(canonical);
            malformed.put(mutation.getKey(), mutation.getValue());
            assertInvalid(malformed);
        }
    }

    @Test
    void countAndFingerprintCombinationMustDescribeOneFactualHistory() {
        Map<String, String> zeroWithFingerprint =
                new LinkedHashMap<>(GoalCheckpointCodec.encodePostconditionRepairCheckpoint(
                        1, 2, "[HUNT x4]"));
        zeroWithFingerprint.put("postcondition_replans", "0");
        assertInvalid(zeroWithFingerprint);

        Map<String, String> repairWithoutFingerprint =
                new LinkedHashMap<>(GoalCheckpointCodec.encodePostconditionRepairCheckpoint(
                        0, 2, ""));
        repairWithoutFingerprint.put("postcondition_replans", "1");
        assertInvalid(repairWithoutFingerprint);

        Map<String, String> tooMany =
                new LinkedHashMap<>(GoalCheckpointCodec.encodePostconditionRepairCheckpoint(
                        3, 2, "[HUNT x4]"));
        tooMany.put("postcondition_replans", "4");
        assertInvalid(tooMany);

        Map<String, String> negativeMatched =
                new LinkedHashMap<>(GoalCheckpointCodec.encodePostconditionRepairCheckpoint(
                        1, 2, "[HUNT x4]"));
        negativeMatched.put("postcondition_last_matched", "-1");
        assertInvalid(negativeMatched);

        assertThrows(IllegalArgumentException.class,
                () -> GoalCheckpointCodec.encodePostconditionRepairCheckpoint(0, 2, "stale"));
        assertThrows(IllegalArgumentException.class,
                () -> GoalCheckpointCodec.encodePostconditionRepairCheckpoint(1, 2, ""));
    }

    @Test
    void restoredLimitAndFingerprintCannotBeRefreshedOrRepeated() {
        assertTrue(GoalExecutor.withinPostconditionRepairBudget(0));
        assertTrue(GoalExecutor.withinPostconditionRepairBudget(2));
        assertFalse(GoalExecutor.withinPostconditionRepairBudget(3));
        assertFalse(GoalExecutor.withinPostconditionRepairBudget(-1));

        String fingerprint = "[HUNT x4, COOK_FOOD x4]";
        assertFalse(GoalExecutor.shouldAcceptPostconditionRepair(
                2, 2, fingerprint, fingerprint));
        assertTrue(GoalExecutor.shouldAcceptPostconditionRepair(
                3, 2, fingerprint, fingerprint));
        assertTrue(GoalExecutor.shouldAcceptPostconditionRepair(
                2, 2, "[GATHER x1]", fingerprint));
    }

    private static void assertInvalid(Map<String, String> checkpoint) {
        Optional<GoalExecutor.PostconditionRepairCheckpoint> decoded =
                GoalCheckpointCodec.decodePostconditionRepairCheckpoint(checkpoint);
        assertTrue(decoded.isEmpty(), () -> "accepted malformed checkpoint " + checkpoint);
        assertEquals("mission_restore_invalid_postcondition_repair_checkpoint",
                GoalExecutor.restoreCheckpointValidationFailure(checkpoint).orElseThrow());
    }
}
