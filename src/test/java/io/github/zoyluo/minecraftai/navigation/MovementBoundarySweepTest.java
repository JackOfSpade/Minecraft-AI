package io.github.zoyluo.minecraftai.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Physical boundary regressions independent of Baritone's static movement declarations. */
final class MovementBoundarySweepTest {
    @Test
    void clipsAnAxisOrderedDiagonalThatHasLegalEndpointsButAnOutsideCorner() {
        NavRoute.MovementBoundary boundary = new NavRoute.MovementBoundary(new BlockPos(0, 64, 0), 8, 64);
        Vec3 origin = new Vec3(8.5D, 64.0D, 0.5D);
        Vec3 requested = new Vec3(-0.8D, 0.0D, 0.8D);

        // The ideal diagonal's endpoints are both legal. Vanilla collision may resolve Z before
        // X, however, which visits (8, 64, 1), outside the radius-eight tangent.
        assertTrue(boundary.allowsFeet(BlockPos.containing(origin)));
        assertTrue(boundary.allowsFeet(BlockPos.containing(origin.add(requested))));
        MovementBoundarySweep.Result result = MovementBoundarySweep.clip(boundary, origin, requested);

        assertTrue(result.refused());
        assertEquals(new BlockPos(8, 64, 1), result.deniedCell());
        assertTrue(boundary.allowsFeet(BlockPos.containing(origin.add(result.permittedDelta()))));
        assertTrue(result.permittedDelta().lengthSqr() < requested.lengthSqr());
    }

    @Test
    void clipsAReleasedAirborneBodyBeforeItCrossesTheFeetFloor() {
        NavRoute.MovementBoundary boundary = new NavRoute.MovementBoundary(new BlockPos(0, 64, 0), 8, 64);
        Vec3 origin = new Vec3(0.5D, 64.1D, 0.5D);
        Vec3 requested = new Vec3(0.0D, -0.4D, 0.0D);

        MovementBoundarySweep.Result result = MovementBoundarySweep.clip(boundary, origin, requested);

        assertTrue(result.refused());
        assertEquals(new BlockPos(0, 63, 0), result.deniedCell());
        assertTrue(boundary.allowsFeet(BlockPos.containing(origin.add(result.permittedDelta()))));
    }

    @Test
    void usesVanillaCollisionResultInsteadOfStaleGroundStateForDownwardMotion() {
        NavRoute.MovementBoundary boundary = new NavRoute.MovementBoundary(new BlockPos(0, 64, 0), 8, 64);
        Vec3 groundedGravity = new Vec3(0.35D, -0.08D, 0.0D);
        Vec3 supportResolved = new Vec3(0.35D, 0.0D, 0.0D);
        // A walk-off has the same prior-tick onGround state as a supported walk, but collision
        // leaves its downward component unchanged. The guard must retain it for floor checking.
        Vec3 walkOffResolved = groundedGravity;

        Vec3 supported = MovementBoundarySweep.collisionAwareDelta(groundedGravity, supportResolved);
        Vec3 walkOff = MovementBoundarySweep.collisionAwareDelta(groundedGravity, walkOffResolved);

        assertEquals(supportResolved, supported,
                "a collision-proven supported gravity probe may use its resolved vertical motion");
        assertEquals(groundedGravity, walkOff,
                "a walk-off must retain its downward component despite a prior onGround state");
        assertFalse(MovementBoundarySweep.clip(boundary, new Vec3(0.5D, 64.0D, 0.5D), supported).refused());
        assertTrue(MovementBoundarySweep.clip(boundary, new Vec3(0.5D, 64.0D, 0.5D), walkOff).refused());
    }

    @Test
    void leavesAWhollyInsideMoveUntouched() {
        NavRoute.MovementBoundary boundary = new NavRoute.MovementBoundary(new BlockPos(0, 64, 0), 8, 64);
        Vec3 requested = new Vec3(-0.25D, 0.0D, 0.25D);

        MovementBoundarySweep.Result result = MovementBoundarySweep.clip(boundary,
                new Vec3(7.5D, 64.0D, 0.5D), requested);

        assertFalse(result.refused());
        assertEquals(requested, result.permittedDelta());
    }
}
