package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks the player-requested surface route onto the existing guarded stair, not a vertical shaft. */
final class DigToSurfaceSourceContractTest {
    @Test
    void surfaceRequestUsesTheFlowSafeObservedStair() throws IOException {
        String digOut = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/DigOutTask.java"));
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));

        assertTrue(digOut.contains("public static DigOutTask toSurface()"));
        assertTrue(digOut.contains("requireSurface ? SurfaceCheck.isOnSurface(world, feet)"));
        assertTrue(digOut.contains("StairDig.refusalAt(bot, world, feet, move)"));
        assertTrue(digOut.contains("beginGravityClearance"));
        assertTrue(digOut.contains("findGravityClearanceStance"));
        assertTrue(digOut.contains("safeGravityStance"));
        assertTrue(digOut.contains("FallingBlock"));
        assertTrue(digOut.contains("AutomaticLighting.tryPlaceTorchAtFeet"));
        assertTrue(digOut.contains("TORCH_RISE_INTERVAL = 6"));
        assertTrue(registry.contains("register(\"dig_to_surface\""));
        assertTrue(registry.contains("DigOutTask.toSurface()"));
    }
}
