package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks in post-restore owner relocation without bypassing normal spawn compatibility hooks. */
final class AIPlayerRestoreOwnerSourceContractTest {
    @Test
    void onlineOwnerRelocationRunsAfterTheNormalPersistedPositionRestore() throws IOException {
        String manager = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/manager/AIPlayerManager.java"));
        int spawn = manager.indexOf("spawnInternal(\n                server,\n                record.name()");
        int stateRestore = manager.indexOf("BotPersistence.applyStoredPlayerState(bot, record);", spawn);
        int towerRestore = manager.indexOf("BotPersistence.restoreTower(bot, record);", stateRestore);
        int ownerLookup = manager.indexOf("Optional<ServerPlayer> owner = onlineOwner(server, record);", towerRestore);
        int relocation = manager.indexOf("bot.teleportTo(anchor.level()", ownerLookup);

        assertTrue(spawn >= 0);
        assertTrue(stateRestore > spawn);
        assertTrue(towerRestore > stateRestore);
        assertTrue(ownerLookup > towerRestore);
        assertTrue(relocation > ownerLookup);
        assertTrue(manager.contains("new Vec3(record.x(), record.y(), record.z())"));
        assertTrue(manager.contains("\"_then_owner_nearby\""));
        assertTrue(manager.contains("\"restore_strategy\", restoreStrategy"));
    }
}
