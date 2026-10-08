package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pins the fake-player respawn ordering that prevents a stale chunk ticket from crashing the server. */
final class AIPlayerDeathRespawnSourceContractTest {
    @Test
    void deathRespawnRebuildsChunkTrackingOnlyAfterTheTeleport() throws IOException {
        String manager = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/manager/AIPlayerManager.java"));
        assertTrue(manager.contains("bot.prepareMinecraftAiDeathRespawn();"));
        assertTrue(manager.contains("bot.completeMinecraftAiDeathRespawn();"));
        assertTrue(manager.indexOf("bot.prepareMinecraftAiDeathRespawn();")
                        < manager.indexOf("bot.teleportTo(respawnWorld"));
        assertTrue(manager.indexOf("bot.completeMinecraftAiDeathRespawn();")
                        > manager.indexOf("bot.teleportTo(respawnWorld"));

        String entity = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/entity/AIPlayerEntity.java"));
        assertTrue(entity.contains("chunkTrackingSuspendedForRespawn"));
        assertTrue(entity.contains("minecraftai$updatePlayerStatus(this, false)"));
        assertTrue(entity.contains("minecraftai$updatePlayerStatus(this, true)"));
    }
}
