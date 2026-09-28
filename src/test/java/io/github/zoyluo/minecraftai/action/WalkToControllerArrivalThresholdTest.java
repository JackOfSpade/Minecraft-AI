package io.github.zoyluo.minecraftai.action;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalkToControllerArrivalThresholdTest {
    @Test
    void honorsCallerRequestedTolerancesLooserThanTheDefault() {
        Vec3d target = new Vec3d(0.0D, 0.0D, 0.0D);

        // Shoreline/boat-launch call sites (BoardBoatTask, BoatLaunchTask, FollowTask) request 1.0D,
        // which is looser than the class's own 0.6D default and must be honored, not silently capped.
        assertEquals(1.0D, new WalkToController(target, 1.0D).arrivalThreshold());
    }

    @Test
    void clampsBelowTheMinimumUpToTheFloor() {
        Vec3d target = new Vec3d(0.0D, 0.0D, 0.0D);

        assertEquals(0.1D, new WalkToController(target, 0.0D).arrivalThreshold());
        assertEquals(0.1D, new WalkToController(target, -5.0D).arrivalThreshold());
    }

    @Test
    void clampsPathologicallyLargeRequestsToTheUpperGuard() {
        Vec3d target = new Vec3d(0.0D, 0.0D, 0.0D);

        assertEquals(1.5D, new WalkToController(target, 1000.0D).arrivalThreshold());
    }

    @Test
    void singleArgConstructorUsesTheDefaultThreshold() {
        Vec3d target = new Vec3d(0.0D, 0.0D, 0.0D);

        assertEquals(0.6D, new WalkToController(target).arrivalThreshold());
    }
}
