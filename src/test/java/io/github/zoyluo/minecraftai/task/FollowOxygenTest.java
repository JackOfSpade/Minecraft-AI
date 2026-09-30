package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure oxygen arithmetic behind follow's "resurface before drowning" decision. */
final class FollowOxygenTest {
    private static final int MAX_AIR = 300;

    @Test
    void zeroLossRateNeverForcesTheBotUpNoMatterHowLowOrDeep() {
        assertFalse(FollowOxygen.shouldSurface(MAX_AIR, 0.0D, 10.0D));
        assertFalse(FollowOxygen.shouldSurface(5, 0.0D, 200.0D),
                "Water Breathing measures as no loss at all: never a reason to surface");
        assertFalse(FollowOxygen.shouldSurface(0, 0.0D, Double.POSITIVE_INFINITY));
    }

    @Test
    void fullLungsAtAModestDepthAreNotAReasonToSurface() {
        assertFalse(FollowOxygen.shouldSurface(MAX_AIR, 1.0D, 10.0D));
        assertFalse(FollowOxygen.shouldSurface(FollowOxygen.SURFACE_FLOOR_AIR + 10, 1.0D, 10.0D));
    }

    @Test
    void airFloorSitsAboveTheRescueThresholdSoFollowAlwaysTurnsUpFirst() {
        assertTrue(FollowOxygen.SURFACE_FLOOR_AIR > FollowOxygen.RESCUE_AIR);
        assertTrue(FollowOxygen.shouldSurface(FollowOxygen.SURFACE_FLOOR_AIR, 1.0D, 1.0D));
        assertFalse(FollowOxygen.shouldSurface(FollowOxygen.SURFACE_FLOOR_AIR + 20, 1.0D, 1.0D));
    }

    @Test
    void deeperMeansEarlierBecauseTheWayUpTakesLonger() {
        // 12 blocks at 0.1 blocks/tick (a real swimmer's climb) is 120 ticks, times 1.5 plus a 40 tick margin: 220 ticks.
        assertTrue(FollowOxygen.shouldSurface(220, 1.0D, 12.0D));
        assertFalse(FollowOxygen.shouldSurface(221, 1.0D, 12.0D));
        assertTrue(FollowOxygen.shouldSurface(250, 1.0D, 20.0D));
        assertFalse(FollowOxygen.shouldSurface(250, 1.0D, 12.0D));
    }

    @Test
    void slowerLossBuysProportionallyMoreTime() {
        // Same air and depth: at a quarter of the loss rate (Respiration III) the bot may stay.
        assertTrue(FollowOxygen.shouldSurface(200, 1.0D, 40.0D));
        assertFalse(FollowOxygen.shouldSurface(200, 0.25D, 40.0D, FollowOxygen.ASCENT_BLOCKS_PER_TICK,
                FollowOxygen.MARGIN_TICKS, 0));
    }

    @Test
    void noKnownWayUpForcesSurfacingWheneverAirIsFalling() {
        assertTrue(FollowOxygen.shouldSurface(MAX_AIR, 1.0D, Double.POSITIVE_INFINITY),
                "never dive on without a known way to breathe");
    }

    @Test
    void slowerAscentSpeedTurnsUpEarlier() {
        assertFalse(FollowOxygen.shouldSurface(200, 1.0D, 10.0D, 0.25D, 40, 0));
        assertTrue(FollowOxygen.shouldSurface(200, 1.0D, 10.0D, 0.05D, 40, 0));
    }

    @Test
    void resumeNeedsNearlyFullLungsUnlessAirIsNotFalling() {
        assertFalse(FollowOxygen.mayResumeDive(150, MAX_AIR, 1.0D));
        assertFalse(FollowOxygen.mayResumeDive(269, MAX_AIR, 1.0D));
        assertTrue(FollowOxygen.mayResumeDive(270, MAX_AIR, 1.0D));
        assertTrue(FollowOxygen.mayResumeDive(10, MAX_AIR, 0.0D));
    }

    @Test
    void resumeLevelClearsTheWorstCaseSurfaceTriggerForADiveOfUsualDepth() {
        // Otherwise the bot would resume and immediately want to surface again.
        // Real swimming climbs about a tenth of a block per tick, so a usual dive is about ten blocks deep.
        assertFalse(FollowOxygen.shouldSurface((int) (MAX_AIR * FollowOxygen.RESUME_FRACTION), 1.0D, 10.0D));
    }

    @Test
    void longBreathingEffectCoversTheDiveButAShortOneDoesNot() {
        assertTrue(FollowOxygen.effectCoversDive(true, 5));
        assertTrue(FollowOxygen.effectCoversDive(false, FollowOxygen.LONG_EFFECT_TICKS));
        assertFalse(FollowOxygen.effectCoversDive(false, FollowOxygen.LONG_EFFECT_TICKS - 1));
    }

    @Test
    void estimatorStartsPessimisticAndConvergesToTheRealLossRate() {
        FollowOxygen.LossEstimator estimator = new FollowOxygen.LossEstimator();
        assertEquals(1.0D, estimator.lossPerTick(), 1.0e-9);
        int air = 300;
        for (int tick = 0; tick < 200; tick++) {
            estimator.observe(air, true, tick);
            if (tick % 4 == 0) {
                air--; // Respiration III style: one unit every fourth tick
            }
        }
        assertEquals(0.25D, estimator.lossPerTick(), 0.1D);
    }

    @Test
    void estimatorMeasuresNoLossAsExactlyZeroOnceConfirmed() {
        FollowOxygen.LossEstimator estimator = new FollowOxygen.LossEstimator();
        for (int tick = 0; tick < 150; tick++) {
            estimator.observe(300, true, tick);
        }
        assertEquals(0.0D, estimator.lossPerTick(), 0.0D);
        assertFalse(FollowOxygen.shouldSurface(300, estimator.lossPerTick(), 30.0D));
    }

    @Test
    void estimatorForgetsEverythingOnceTheHeadIsOutOfTheWater() {
        FollowOxygen.LossEstimator estimator = new FollowOxygen.LossEstimator();
        for (int tick = 0; tick < 150; tick++) {
            estimator.observe(300, true, tick);
        }
        estimator.observe(300, false, 150);
        assertEquals(1.0D, estimator.lossPerTick(), 1.0e-9,
                "a fresh dive starts from the safe assumption again");
        assertEquals(0, estimator.samples());
    }

    @Test
    void estimatorIgnoresRefillsAndLongGapsInsteadOfInventingLoss() {
        FollowOxygen.LossEstimator estimator = new FollowOxygen.LossEstimator();
        estimator.observe(100, true, 0);
        estimator.observe(300, true, 1); // a refill (bubble column) is never negative loss
        assertTrue(estimator.lossPerTick() <= 1.0D);
        estimator.observe(200, true, 500); // task did not tick for 500 ticks: no per-tick rate to infer
        assertEquals(0, estimator.samples());
    }

    @Test
    void estimatorCatchesUpAcrossSkippedTicksWithoutOvershooting() {
        FollowOxygen.LossEstimator estimator = new FollowOxygen.LossEstimator();
        estimator.observe(300, true, 0);
        estimator.observe(290, true, 10); // ten ticks, ten units: exactly the vanilla rate
        assertEquals(1.0D, estimator.lossPerTick(), 1.0e-9);
    }
}
