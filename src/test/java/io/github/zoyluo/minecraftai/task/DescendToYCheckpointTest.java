package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * shelterdig-bug-1: landingDriftRecoveries must round-trip through
 * {@link DescendToYTask#inspectCheckpoint}, and an old schema-4 checkpoint (persisted before this
 * field existed) must still restore exactly as before -- landingDriftRecoveries defaulting to 0
 * rather than being rejected.
 */
class DescendToYCheckpointTest {
    // Mirrors DescendToYTask's private MAX_LANDING_DRIFT_RECOVERIES; kept in sync by
    // landingDriftRecoveriesBeyondTheCapIsRejected below (it would start failing the moment the
    // production cap changes without this constant following it).
    private static final int MAX_LANDING_DRIFT_RECOVERIES = 8;

    @Test
    void schemaFiveRoundTripsLandingDriftRecoveries() {
        Map<String, String> checkpoint = new LinkedHashMap<>(baseCheckpoint());
        checkpoint.put("landing_drift_recoveries", "3");

        DescendToYTask.RestoreMetadata restored =
                DescendToYTask.inspectCheckpoint(checkpoint).orElseThrow();
        assertEquals(3, restored.landingDriftRecoveries());
    }

    @Test
    void schemaFourCheckpointWithoutTheFieldRestoresAsLandingDriftRecoveriesZero() {
        Map<String, String> legacy = new LinkedHashMap<>(baseCheckpoint());
        legacy.put("task_schema", "4");
        legacy.remove("landing_drift_recoveries");

        DescendToYTask.RestoreMetadata restored =
                DescendToYTask.inspectCheckpoint(legacy).orElseThrow();
        assertEquals(0, restored.landingDriftRecoveries());
    }

    @Test
    void landingDriftRecoveriesBeyondTheCapIsRejected() {
        Map<String, String> atCap = new LinkedHashMap<>(baseCheckpoint());
        atCap.put("landing_drift_recoveries", String.valueOf(MAX_LANDING_DRIFT_RECOVERIES));
        assertTrue(DescendToYTask.inspectCheckpoint(atCap).isPresent(),
                "the cap value itself must still be accepted");

        Map<String, String> overCap = new LinkedHashMap<>(baseCheckpoint());
        overCap.put("landing_drift_recoveries", String.valueOf(MAX_LANDING_DRIFT_RECOVERIES + 1));
        assertFalse(DescendToYTask.inspectCheckpoint(overCap).isPresent(),
                "checkpoint accepted a landing-drift recovery count beyond its safety cap");
    }

    @Test
    void negativeLandingDriftRecoveriesIsRejected() {
        Map<String, String> negative = new LinkedHashMap<>(baseCheckpoint());
        negative.put("landing_drift_recoveries", "-1");
        assertFalse(DescendToYTask.inspectCheckpoint(negative).isPresent());
    }

    @Test
    void schemaFourCheckpointCarryingTheNewKeyIsRejected() {
        // A schema-4 checkpoint's key set is pinned; it must not also carry the new key (that
        // shape belongs exclusively to schema 5).
        Map<String, String> mixed = new LinkedHashMap<>(baseCheckpoint());
        mixed.put("task_schema", "4");
        assertFalse(DescendToYTask.inspectCheckpoint(mixed).isPresent(),
                "schema-4 checkpoint accepted an unexpected landing_drift_recoveries key");
    }

    /**
     * A minimal, otherwise-valid schema-5 idle checkpoint: no pending/rejected landing, no
     * detours, at the lowest legal budget window. Every test above overlays just the field(s)
     * under test on top of this.
     */
    private static Map<String, String> baseCheckpoint() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_schema", "5");
        values.put("task_open", "true");
        values.put("target_y", "0");
        values.put("budget_used", "0");
        values.put("last_progress_budget", "0");
        values.put("budget_limit", "4800");
        values.put("stair_direction", "0");
        values.put("lateral_detours", "0");
        values.put("landing_drift_recoveries", "0");
        values.put("detour_heading", "-1");
        values.put("pending_landing_origin", "none");
        values.put("pending_landing_target", "none");
        values.put("pending_landing_direction", "-1");
        values.put("rejected_landing_origin", "none");
        values.put("rejected_landing_directions", "0");
        values.put("last_torch_y", String.valueOf(Integer.MAX_VALUE));
        values.put("owned_water_seals", "");
        values.put("traversed_detour_edges", "");
        return values;
    }
}
