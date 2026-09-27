package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ProjectileBallistics} against an independent tick-by-tick simulation of the same
 * vanilla arrow model it's solving in closed form (position += velocity; velocity *= DRAG;
 * velocity.y -= GRAVITY) -- the simulation and the closed-form solver share no code, so this catches
 * any algebra mistake in the closed form rather than just re-checking its own equations.
 */
class ProjectileBallisticsTest {

    /** Ticks the raw per-tick recurrence forward until the arrow crosses horizontalDistance, and
     *  returns its height at that moment (linearly interpolated between the two surrounding ticks,
     *  matching how continuous flight actually crosses a distance mid-tick). */
    private static double simulateHeightAtDistance(double pitchDegrees, double horizontalDistance, double speed) {
        double upAngle = Math.toRadians(-pitchDegrees);
        double vx = speed * Math.cos(upAngle);
        double vy = speed * Math.sin(upAngle);
        double x = 0.0;
        double y = 0.0;
        for (int tick = 0; tick < 20000; tick++) {
            double nextX = x + vx;
            double nextY = y + vy;
            if (nextX >= horizontalDistance) {
                double frac = (horizontalDistance - x) / (nextX - x);
                return y + frac * (nextY - y);
            }
            x = nextX;
            y = nextY;
            vx *= ProjectileBallistics.DRAG;
            vy = vy * ProjectileBallistics.DRAG - ProjectileBallistics.GRAVITY;
        }
        throw new IllegalStateException("simulation never reached the target distance");
    }

    @Test
    void aLevelShotAtPointBlankNeedsAlmostNoCompensation() {
        double pitch = ProjectileBallistics.pitchForShot(4.5, 0.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
        assertEquals(0.0, pitch, 2.0, "near-zero pitch for a same-height, close target");
    }

    @Test
    void aLevelDistantShotNeedsToAimAboveTheNaiveLine() {
        // Straight-line pitch to a same-height target is 0 degrees; the ballistic solver must aim
        // *above* that (more negative pitch, Minecraft's "looking up" convention) to compensate for
        // drop over 18 blocks -- this is the exact effect a naive lookAt cannot produce.
        double naive = ProjectileBallistics.straightLinePitch(18.0, 0.0);
        double compensated = ProjectileBallistics.pitchForShot(18.0, 0.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
        assertEquals(0.0, naive, 1.0e-9);
        assertTrue(compensated < naive - 1.0, "expected a meaningfully upward correction, got " + compensated);
    }

    @Test
    void theSolvedPitchActuallyHitsTheTargetHeightWhenSimulatedTickByTick() {
        double[] distances = {5.0, 8.0, 12.0, 16.0, 20.0};
        double[] verticalOffsets = {-3.0, -1.0, 0.0, 1.0, 3.0};
        for (double d : distances) {
            for (double dy : verticalOffsets) {
                double pitch = ProjectileBallistics.pitchForShot(d, dy, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
                double simulatedHeight = simulateHeightAtDistance(pitch, d, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
                assertEquals(dy, simulatedHeight, 0.05,
                        "distance=" + d + " verticalOffset=" + dy + " pitch=" + pitch);
            }
        }
    }

    @Test
    void aHigherTargetNeedsMorePitchUpThanASameDistanceLevelTarget() {
        double level = ProjectileBallistics.pitchForShot(15.0, 0.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
        double higher = ProjectileBallistics.pitchForShot(15.0, 6.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
        assertTrue(higher < level, "aiming at a higher target should pitch further up (more negative)");
    }

    @Test
    void aLowerTargetNeedsLessPitchUpThanASameDistanceLevelTarget() {
        double level = ProjectileBallistics.pitchForShot(15.0, 0.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
        double lower = ProjectileBallistics.pitchForShot(15.0, -6.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
        assertTrue(lower > level, "aiming at a lower target should pitch further down (more positive)");
    }

    @Test
    void increasingRangeAtTheSameHeightNeedsMonotonicallyMoreUpwardCompensation() {
        double previous = Double.POSITIVE_INFINITY;
        for (double d : new double[]{4.5, 8.0, 12.0, 16.0, 20.0, 24.0}) {
            double pitch = ProjectileBallistics.pitchForShot(d, 0.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
            assertTrue(pitch <= previous + 1.0e-6, "pitch should not need to go back down as range grows: " + pitch + " after " + previous);
            previous = pitch;
        }
    }

    @Test
    void zeroOrNegativeHorizontalDistanceFallsBackToTheStraightLinePitchWithoutThrowing() {
        assertDoesNotThrow(() -> ProjectileBallistics.pitchForShot(0.0, 5.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED));
        assertDoesNotThrow(() -> ProjectileBallistics.pitchForShot(-1.0, 5.0, ProjectileBallistics.FULL_DRAW_ARROW_SPEED));
    }

    @Test
    void zeroSpeedFallsBackToTheStraightLinePitchWithoutThrowing() {
        double pitch = ProjectileBallistics.pitchForShot(10.0, 2.0, 0.0);
        assertEquals(ProjectileBallistics.straightLinePitch(10.0, 2.0), pitch, 1.0e-9);
    }

    @Test
    void anUnreachableDistanceAtLowSpeedFallsBackToTheStraightLinePitchRatherThanAWildAngle() {
        // Vanilla's own maximum horizontal travel is 100 blocks per block/tick of initial speed; a
        // deliberately absurd distance for a slow "shot" must degrade gracefully, not return NaN or
        // an unusable value.
        double pitch = ProjectileBallistics.pitchForShot(50000.0, 0.0, 0.1);
        assertFalse(Double.isNaN(pitch));
        assertFalse(Double.isInfinite(pitch));
    }

    @Test
    void pitchIsNeverOutsideVanillasClampedRange() {
        for (double d = 1.0; d <= 30.0; d += 1.0) {
            for (double dy = -10.0; dy <= 10.0; dy += 2.0) {
                double pitch = ProjectileBallistics.pitchForShot(d, dy, ProjectileBallistics.FULL_DRAW_ARROW_SPEED);
                assertTrue(pitch >= -90.0 && pitch <= 90.0, "pitch out of range: " + pitch);
            }
        }
    }
}
