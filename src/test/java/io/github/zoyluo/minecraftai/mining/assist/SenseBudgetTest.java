package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The counter-based ray throttle of design 3.4. */
class SenseBudgetTest {
    @Test
    void aSingleBotGetsItsConfiguredRate() {
        assertEquals(40, SenseBudget.raysEff(40, 640, 1, false));
    }

    @Test
    void theGlobalBudgetIsSharedAcrossSweepingBots() {
        assertEquals(40, SenseBudget.raysEff(40, 640, 16, false));
        assertEquals(35, SenseBudget.raysEff(40, 640, 18, false));
        assertEquals(20, SenseBudget.raysEff(40, 640, 32, false));
        assertEquals(10, SenseBudget.raysEff(40, 640, 64, false));
    }

    @Test
    void theShareUsesFloorDivision() {
        assertEquals(213, SenseBudget.raysEff(500, 640, 3, false));
    }

    @Test
    void headroomLatchHalvesTheRate() {
        assertEquals(20, SenseBudget.raysEff(40, 640, 1, true));
        assertEquals(10, SenseBudget.raysEff(40, 640, 32, true));
        assertEquals(17, SenseBudget.raysEff(40, 640, 18, true));
    }

    @Test
    void neverBelowOneRayAndNeverAPermanentDisable() {
        assertEquals(1, SenseBudget.raysEff(40, 640, 10_000, false));
        assertEquals(1, SenseBudget.raysEff(1, 640, 1, true));
        assertEquals(1, SenseBudget.raysEff(0, 0, 0, true));
        assertEquals(1, SenseBudget.raysEff(-5, -5, -5, false));
    }

    @Test
    void zeroOrNegativeBotCountCountsAsOne() {
        assertEquals(40, SenseBudget.raysEff(40, 640, 0, false));
        assertEquals(40, SenseBudget.raysEff(40, 640, -3, false));
    }

    @Test
    void breakthroughRaisesTheTargetToNinetySixButNeverLowersAHigherConfig() {
        assertEquals(40, SenseBudget.target(false, 40));
        assertEquals(96, SenseBudget.target(true, 40));
        assertEquals(96, SenseBudget.BREAKTHROUGH_RAYS_PER_TICK);
        assertEquals(200, SenseBudget.target(true, 200));
        assertEquals(96, SenseBudget.target(true, 0));
    }

    @Test
    void breakthroughStillObeysTheGlobalShareAndTheHalving() {
        int target = SenseBudget.target(true, 40);
        assertEquals(96, SenseBudget.raysEff(target, 640, 1, false));
        assertEquals(48, SenseBudget.raysEff(target, 640, 1, true));
        assertEquals(80, SenseBudget.raysEff(target, 640, 8, false));
        assertEquals(20, SenseBudget.raysEff(target, 640, 32, false));
    }

    @Test
    void fullSweepDurationFromTheRate() {
        assertEquals(52, SenseBudget.sweepTicks(40));
        assertEquals(22, SenseBudget.sweepTicks(96));
        assertEquals(2048, SenseBudget.sweepTicks(1));
        assertEquals(1, SenseBudget.sweepTicks(4096));
    }

    @Test
    void everySecondVisitGetsADecorRay() {
        int decor = 0;
        for (int k = 0; k < 2048; k++) {
            if (SenseBudget.decorRay(k)) {
                decor++;
            }
        }
        assertEquals(1024, decor);
        assertTrue(SenseBudget.decorRay(0));
        assertFalse(SenseBudget.decorRay(1));
        assertEquals(2, SenseBudget.DECOR_STRIDE);
    }

    @Test
    void theSweepRadiusIsThePerceptionRadiusClampedToOneAndTheCeiling() {
        assertEquals(16.0D, SenseBudget.sweepRadius(16), 0.0D, "the shipped default is untouched");
        assertEquals(12.0D, SenseBudget.sweepRadius(12), 0.0D);
        assertEquals(1.0D, SenseBudget.sweepRadius(0), 0.0D);
        assertEquals(1.0D, SenseBudget.sweepRadius(-5), 0.0D);
        assertEquals(SenseBudget.MAX_SWEEP_RADIUS, SenseBudget.sweepRadius(SenseBudget.MAX_SWEEP_RADIUS), 0.0D);
        assertEquals(SenseBudget.MAX_SWEEP_RADIUS, SenseBudget.sweepRadius(64), 0.0D,
                "a configured radius of 64 must not make every ray four times as long");
        assertEquals(SenseBudget.MAX_SWEEP_RADIUS, SenseBudget.sweepRadius(Integer.MAX_VALUE), 0.0D);
    }
}
