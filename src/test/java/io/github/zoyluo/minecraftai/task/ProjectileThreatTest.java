package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;

/** Pure trajectory math backing the "turn to face and block an incoming projectile" reaction. */
class ProjectileThreatTest {

    @Test
    void headOnArrowIsAnImminentThreat() {
        // Ten blocks out, closing at two blocks/tick on a direct line -- five ticks to impact.
        Vec3 relativePosition = new Vec3(10.0D, 0.0D, 0.0D);
        Vec3 velocity = new Vec3(-2.0D, 0.0D, 0.0D);
        Double ticks = ProjectileThreat.ticksToClosestApproach(relativePosition, velocity);
        assertEquals(5.0D, ticks, 1.0E-9D);
    }

    @Test
    void arrowFlyingAwayIsNotAThreat() {
        Vec3 relativePosition = new Vec3(10.0D, 0.0D, 0.0D);
        Vec3 velocity = new Vec3(2.0D, 0.0D, 0.0D); // moving further away, not toward the bot
        assertNull(ProjectileThreat.ticksToClosestApproach(relativePosition, velocity));
    }

    @Test
    void arrowThatWillPassWideOfTheBotIsNotAThreat() {
        // Travels parallel to the bot at a large lateral offset -- closest approach is far outside
        // the interception radius even though it is technically closing in the X axis.
        Vec3 relativePosition = new Vec3(10.0D, 0.0D, 20.0D);
        Vec3 velocity = new Vec3(-2.0D, 0.0D, 0.0D);
        assertNull(ProjectileThreat.ticksToClosestApproach(relativePosition, velocity));
    }

    @Test
    void stationaryOrSpentProjectileIsNotAThreat() {
        Vec3 relativePosition = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 velocity = Vec3.ZERO;
        assertNull(ProjectileThreat.ticksToClosestApproach(relativePosition, velocity));
    }

    @Test
    void forecastUsesAnArrowNextDeltaRatherThanRepeatingItsConsumedLastStep() {
        // An arrow at p1 has just moved by -2; vanilla has already stored the smaller, falling p2-p1 vector for its NEXT move.
        Vec3 next = ProjectileThreat.forecastVelocity(new Vec3(-2.0D, 0.0D, 0.0D), new Vec3(-1.98D, -0.05D, 0.0D), 12);
        assertEquals(new Vec3(-1.98D, -0.05D, 0.0D), next);
        // A stopped arrow cannot revive from an old nonzero delta; a freshly created shot still gets one launch-vector forecast.
        assertEquals(Vec3.ZERO, ProjectileThreat.forecastVelocity(Vec3.ZERO, new Vec3(-2.0D, 0.0D, 0.0D), 12));
        assertEquals(new Vec3(-2.0D, 0.0D, 0.0D),
                ProjectileThreat.forecastVelocity(Vec3.ZERO, new Vec3(-2.0D, 0.0D, 0.0D), 1));
    }

    @Test
    void curvedForecastsAreLimitedToVerifiedVanillaBallistics() {
        assertTrue(ProjectileThreat.hasVanillaBallisticCourse("minecraft:arrow"));
        assertTrue(ProjectileThreat.hasVanillaBallisticCourse("minecraft:spectral_arrow"));
        assertTrue(ProjectileThreat.hasVanillaBallisticCourse("minecraft:trident"));
        assertTrue(ProjectileThreat.hasVanillaBallisticCourse("minecraft:llama_spit"));
        assertFalse(ProjectileThreat.hasVanillaBallisticCourse("minecraft:snowball"));
        assertFalse(ProjectileThreat.hasVanillaBallisticCourse("minecraft:firework_rocket"));
        assertFalse(ProjectileThreat.hasVanillaBallisticCourse("modded:arrow"));
    }

    @Test
    void tooFarBeyondTheLeadWindowIsNotYetActionable() {
        // Extremely slow closing speed means dozens of seconds to impact -- not something worth
        // reacting to this tick.
        Vec3 relativePosition = new Vec3(100.0D, 0.0D, 0.0D);
        Vec3 velocity = new Vec3(-0.1D, 0.0D, 0.0D);
        assertNull(ProjectileThreat.ticksToClosestApproach(relativePosition, velocity));
    }

    @Test
    void grazingShotWithinTheInterceptRadiusStillCounts() {
        // Passes one block to the side of the bot's eyes -- close enough that a shield should still
        // come up.
        Vec3 relativePosition = new Vec3(10.0D, 0.0D, 1.0D);
        Vec3 velocity = new Vec3(-2.0D, 0.0D, 0.0D);
        Double ticks = ProjectileThreat.ticksToClosestApproach(relativePosition, velocity);
        assertEquals(5.0D, ticks, 1.0E-9D);
    }

    @Test
    void anArrowOnItsArcIsJudgedByWhereItWillBeNotByTheStraightLineOfItsVelocity() {
        // A skeleton-style shot at fifteen blocks: aimed on the ballistic pitch, so the arrow rises first and comes down on the eyes.
        double speed = 1.6D;
        double pitch = Math.toRadians(ProjectileBallistics.pitchForShot(15.0D, 0.0D, speed));
        Vec3 velocity = new Vec3(-Math.cos(pitch) * speed, -Math.sin(pitch) * speed, 0.0D);
        Vec3 relativePosition = new Vec3(15.0D, 0.0D, 0.0D);
        // The straight line of the launch velocity passes far above the eyes ...
        assertNull(ProjectileThreat.ticksToClosestApproach(relativePosition, velocity));
        // ... but the arrow, stepped with vanilla gravity and drag, comes down on them.
        Double ticks = ProjectileThreat.ticksToClosestApproach(relativePosition, velocity, 0.05D, 0.99D);
        org.junit.jupiter.api.Assertions.assertNotNull(ticks);
        assertEquals(10.0D, ticks, 1.5D);
    }

    @Test
    void withoutGravityTheSteppedPredictionAgreesWithTheStraightLine() {
        Vec3 relativePosition = new Vec3(10.0D, 0.0D, 1.0D);
        Vec3 velocity = new Vec3(-2.0D, 0.0D, 0.0D);
        assertEquals(5.0D, ProjectileThreat.ticksToClosestApproach(relativePosition, velocity, 0.0D, 1.0D), 1.0E-9D);
        assertNull(ProjectileThreat.ticksToClosestApproach(new Vec3(10.0D, 0.0D, 20.0D), velocity, 0.0D, 1.0D));
        assertNull(ProjectileThreat.ticksToClosestApproach(new Vec3(10.0D, 0.0D, 0.0D), new Vec3(2.0D, 0.0D, 0.0D), 0.0D, 1.0D));
    }

    @Test
    void acceleratingFireballCourseIsNotMistakenForASlowStraightLine() {
        // AbstractHurtingProjectile starts at 0.1 blocks/tick but applies (v + normalize(v) * 0.1) * 0.95 before every move.
        // A straight-line prediction would put this ten-block shot beyond the 30-tick reaction window; vanilla reaches the eyes in
        // about fifteen ticks, so it must be treated as an imminent threat.
        Double ticks = ProjectileThreat.ticksToClosestApproachAccelerating(
                new Vec3(10.0D, 0.0D, 0.0D), new Vec3(-0.1D, 0.0D, 0.0D), 0.1D, 0.95D);
        assertNotNull(ticks);
        assertTrue(ticks > 10.0D && ticks < 18.0D, "accelerating fireball should reach the bot inside the lead window");
    }

    @Test
    void hurtingProjectileAppliesAccelerationAndInertiaBeforeItsNextMove() {
        // AbstractHurtingProjectile.tick calls applyInertia before its move. The first fireball segment from -1 is therefore -1.045,
        // not the stale -1 displacement and not a generic gravity-zero straight line.
        assertEquals(-1.045D, ProjectileThreat.acceleratingNextVelocity(new Vec3(-1.0D, 0.0D, 0.0D), 0.1D, 0.95F).x, 1.0E-6D);
        // A dangerous wither skull uses its public 0.73 inertia (water's 0.8 override is selected by the world adapter).
        assertEquals(-0.803D, ProjectileThreat.acceleratingNextVelocity(new Vec3(-1.0D, 0.0D, 0.0D), 0.1D, 0.73F).x, 1.0E-6D);
        assertEquals(-0.88D, ProjectileThreat.acceleratingNextVelocity(new Vec3(-1.0D, 0.0D, 0.0D), 0.1D, 0.8F).x, 1.0E-6D);
        // Wind charges carry zero acceleration and unit inertia, so their visibly straight trajectory stays straight.
        assertEquals(-1.0D, ProjectileThreat.acceleratingNextVelocity(new Vec3(-1.0D, 0.0D, 0.0D), 0.0D, 1.0D).x, 1.0E-9D);
    }
}
