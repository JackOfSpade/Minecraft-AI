package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure bounds behind deep-water surfacing and the proportional operator-rescue deadline. */
final class NavSafetyNetWaterRescueBudgetTest {
    @Test
    void directDeepWaterRaisesTheSurfaceAirFloorButShallowWaterKeepsTheHistoricMargin() {
        assertEquals(NavSafetyNet.AIR_SURFACE_THRESHOLD, NavSafetyNet.surfaceAirThresholdForDepth(0));
        assertEquals(NavSafetyNet.AIR_SURFACE_THRESHOLD, NavSafetyNet.surfaceAirThresholdForDepth(8));
        assertEquals(156, NavSafetyNet.surfaceAirThresholdForDepth(12));
        assertTrue(NavSafetyNet.surfaceAirThresholdForDepth(12) > NavSafetyNet.AIR_SURFACE_THRESHOLD);
    }

    @Test
    void waterRescueDeadlineScalesWithTheProvedSwimRouteInsteadOfAFlatTenSeconds() {
        assertEquals((int) Math.ceil(WalkedStepRules.timeoutBudget(WalkedStep.Kind.SWIM, 1)),
                NavSafetyNet.waterRescueTimeoutTicks(1));
        assertEquals((int) Math.ceil(WalkedStepRules.timeoutBudget(WalkedStep.Kind.SWIM, 25)),
                NavSafetyNet.waterRescueTimeoutTicks(25));
        assertTrue(NavSafetyNet.waterRescueTimeoutTicks(25) > NavSafetyNet.waterRescueTimeoutTicks(12));
        assertTrue(NavSafetyNet.waterRescueTimeoutTicks(25) > 200,
                "a long, still-physical swim must not inherit the old fixed 200-tick teleport");
    }
}
