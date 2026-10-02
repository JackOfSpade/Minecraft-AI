package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure input-geometry coverage for escort aim preserving a legacy navigator's world-space movement. */
class ActionPackLegacyInputProjectionTest {
    @Test
    void forwardInputBecomesStrafeAfterAQuarterTurnWithoutChangingItsWorldDirection() {
        ActionPack.LegacyInputs projected = ActionPack.reprojectLegacyInputs(1.0F, 0.0F, -90.0F, 0.0F);

        assertEquals(0.0D, projected.forward(), 1.0E-6D);
        assertEquals(1.0D, projected.strafing(), 1.0E-6D);
        assertSameWorldDirection(1.0F, 0.0F, -90.0F, projected, 0.0F);
    }

    @Test
    void partialPaceScaledInputKeepsItsExactWorldVectorAcrossAnArbitraryTurn() {
        float forward = 0.4F;
        float strafing = -0.2F;
        ActionPack.LegacyInputs projected = ActionPack.reprojectLegacyInputs(forward, strafing, 37.0F, -68.0F);

        double[] expected = worldVector(forward, strafing, 37.0F);
        double[] actual = worldVector(projected.forward(), projected.strafing(), -68.0F);
        assertEquals(expected[0], actual[0], 1.0E-6D);
        assertEquals(expected[1], actual[1], 1.0E-6D);
    }

    @Test
    void diagonalInputKeepsItsDirectionWhenRotationNeedsItToBeBoundedBackToVanillaKeyRange() {
        ActionPack.LegacyInputs projected = ActionPack.reprojectLegacyInputs(1.0F, 1.0F, 0.0F, 45.0F);

        assertEquals(0.0D, projected.forward(), 1.0E-6D);
        assertEquals(1.0D, projected.strafing(), 1.0E-6D);
        assertSameWorldDirection(1.0F, 1.0F, 0.0F, projected, 45.0F);
    }

    private static void assertSameWorldDirection(
            float forward, float strafing, float yawBefore,
            ActionPack.LegacyInputs projected, float yawAfter) {
        double[] before = worldVector(forward, strafing, yawBefore);
        double[] after = worldVector(projected.forward(), projected.strafing(), yawAfter);
        double beforeLength = Math.hypot(before[0], before[1]);
        double afterLength = Math.hypot(after[0], after[1]);
        assertEquals(before[0] / beforeLength, after[0] / afterLength, 1.0E-6D);
        assertEquals(before[1] / beforeLength, after[1] / afterLength, 1.0E-6D);
    }

    private static double[] worldVector(float forward, float strafing, float yaw) {
        double radians = Math.toRadians(yaw);
        return new double[] {
                strafing * Math.cos(radians) - forward * Math.sin(radians),
                forward * Math.cos(radians) + strafing * Math.sin(radians)
        };
    }
}
