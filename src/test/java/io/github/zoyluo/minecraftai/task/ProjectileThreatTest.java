package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
