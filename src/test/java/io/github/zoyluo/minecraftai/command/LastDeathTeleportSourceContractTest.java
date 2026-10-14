package io.github.zoyluo.minecraftai.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks in the command-only, hostile-safe route back to the caller's own last-death marker. */
final class LastDeathTeleportSourceContractTest {
    private static final Path COMMAND = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiCommand.java");
    private static final Path SAFETY = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/pathfinding/TeleportSafety.java");

    @Test
    void lastDeathCommandUsesAVanillaMarkerAndAMobSafeLanding() throws IOException {
        String command = Files.readString(COMMAND);
        String safety = Files.readString(SAFETY);

        assertTrue(command.contains("literal(\"last-death\")"),
                "the existing command-only last-death entry point must remain available");
        assertTrue(command.contains("literal(\"teleport\")") && command.contains("literal(\"death\")"),
                "the readable /minecraftai teleport death alias must be registered too");
        assertTrue(command.contains("player.getLastDeathLocation()"),
                "the command must use the caller's own vanilla last-death record");
        assertTrue(command.contains("source.getServer().getLevel(death.dimension())"),
                "a last death in another dimension must use its recorded world");
        assertTrue(command.contains("TeleportSafety.findNearestHostileSafeStandable(deathWorld, death.pos(), 48, 8, 8)"),
                "the destination must be near the death marker but outside hostile aggro range");
        assertTrue(command.contains("No safe landing away from hostile mobs was found"),
                "the command must refuse a dangerous landing instead of teleporting into it");
        assertTrue(safety.contains("hostile.getAttributeValue(Attributes.FOLLOW_RANGE) + HOSTILE_RANGE_BUFFER"),
                "safe means beyond each monster's actual acquisition range, not only melee range");
    }
}
