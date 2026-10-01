package io.github.zoyluo.minecraftai.perception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CreatureSensesProjectileTest {
    @Test
    void hearingOnlyBallisticsAreLimitedToVerifiedVanillaEntityTypes() {
        assertTrue(CreatureSenses.hasVanillaHearingBallisticCourse("minecraft:arrow"));
        assertTrue(CreatureSenses.hasVanillaHearingBallisticCourse("minecraft:spectral_arrow"));
        assertTrue(CreatureSenses.hasVanillaHearingBallisticCourse("minecraft:trident"));
        assertTrue(CreatureSenses.hasVanillaHearingBallisticCourse("minecraft:llama_spit"));
        assertFalse(CreatureSenses.hasVanillaHearingBallisticCourse("minecraft:snowball"));
        assertFalse(CreatureSenses.hasVanillaHearingBallisticCourse("minecraft:firework_rocket"));
        assertFalse(CreatureSenses.hasVanillaHearingBallisticCourse("modded:arrow"));
    }

    @Test
    void arrowShotMatchesItsBackProjectedFirstFlightEventRatherThanAnArbitraryFlightPosition() {
        Vec3 launch = new Vec3(12.0D, 70.0D, -3.0D);
        Vec3 position = launch;
        Vec3 velocity = new Vec3(3.2D, 0.0D, 0.0D);
        Vec3 eventPosition = null;
        for (int tick = 1; tick <= 60; tick++) {
            position = position.add(velocity);
            velocity = new Vec3(velocity.x * 0.99D, velocity.y * 0.99D - 0.05D, velocity.z * 0.99D);
            if (tick == 1) {
                eventPosition = position;
            }
        }
        Vec3 estimatedEvent = CreatureSenses.backProjectedBallisticLaunch(position, velocity, 59, 0.05D);

        assertTrue(CreatureSenses.matchesHeardShot(BlockPos.containing(eventPosition), estimatedEvent));
        assertFalse(CreatureSenses.matchesHeardShot(BlockPos.containing(launch), estimatedEvent),
                "PersistentProjectileEntity emits after its first flight step, not from the original spawn position");
    }

    @Test
    void freshOrStationaryProjectilesOnlyMatchTheirCurrentLaunchPosition() {
        Vec3 position = new Vec3(-4.0D, 64.0D, 9.0D);

        Vec3 estimatedLaunch = CreatureSenses.backProjectedBallisticLaunch(position, Vec3.ZERO, 0, 0.0D);
        assertTrue(CreatureSenses.matchesHeardShot(BlockPos.containing(position), estimatedLaunch));
        assertFalse(CreatureSenses.matchesHeardShot(BlockPos.containing(position.add(5.0D, 0.0D, 0.0D)), estimatedLaunch));
    }

    @Test
    void llamaSpitReversesToItsPreMoveEventAcrossALongFlight() {
        Vec3 launch = new Vec3(12.0D, 72.0D, -3.0D);
        Vec3 position = launch;
        Vec3 velocity = new Vec3(3.2D, 0.8D, 0.0D);
        for (int tick = 0; tick < 60; tick++) {
            position = position.add(velocity);
            velocity = new Vec3(velocity.x * 0.99D, velocity.y * 0.99D - 0.06D, velocity.z * 0.99D);
        }

        Vec3 estimatedEvent = CreatureSenses.backProjectedBallisticLaunch(position, velocity, 60, 0.06D);
        assertTrue(CreatureSenses.matchesHeardShot(BlockPos.containing(launch), estimatedEvent));
        assertFalse(CreatureSenses.matchesHeardShot(BlockPos.containing(launch.add(8.0D, 0.0D, 0.0D)), estimatedEvent));
    }

    @Test
    void heardShotRequiresTheVibrationToFitTheProjectileFlightAge() {
        // At tick 100, a sound received at tick 95 after ten vibration ticks describes a projectile age of 1 + 10 + 5.
        assertTrue(CreatureSenses.couldHaveHeardShotDuringFlight(100L, 95L, 15, 10));
        assertTrue(CreatureSenses.couldHaveHeardShotDuringFlight(100L, 95L, 16, 10));
        assertTrue(CreatureSenses.couldHaveHeardShotDuringFlight(100L, 95L, 17, 10));
        assertFalse(CreatureSenses.couldHaveHeardShotDuringFlight(100L, 95L, 14, 10));
        assertFalse(CreatureSenses.couldHaveHeardShotDuringFlight(100L, 95L, 18, 10));
        assertFalse(CreatureSenses.couldHaveHeardShotDuringFlight(100L, 95L, 6, -1),
                "an unavailable original vibration delay must not become a false zero-distance match");
    }

    @Test
    void heardShotOnlyAcceptsTheDeliveredSourceBlockPlusRounding() {
        BlockPos sourceBlock = new BlockPos(12, 70, -3);

        assertTrue(CreatureSenses.matchesHeardShot(sourceBlock, new Vec3(12.99D, 70.99D, -2.01D)));
        assertTrue(CreatureSenses.matchesHeardShot(sourceBlock, new Vec3(11.8D, 69.8D, -3.2D)));
        assertFalse(CreatureSenses.matchesHeardShot(sourceBlock, new Vec3(11.7D, 70.0D, -2.5D)));
        assertFalse(CreatureSenses.matchesHeardShot(sourceBlock, new Vec3(12.5D, 71.3D, -2.5D)));
    }
}
