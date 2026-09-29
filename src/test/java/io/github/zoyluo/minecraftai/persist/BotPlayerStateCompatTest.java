package io.github.zoyluo.minecraftai.persist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (no Minecraft bootstrap) checks of the runtime.json compatibility of the equipment/player-state
 * field, plus source contracts for the wiring that the real-server GameTests
 * ({@code BotPersistenceRestoreGameTests}) prove end to end.
 */
class BotPlayerStateCompatTest {
    private static final String STATE = "{Equipment:{head:{id:\"minecraft:diamond_helmet\",count:1}},XpLevel:7}";

    private static RuntimeSnapshot snapshot(BotRecord bot) {
        return new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, "t", "b", "s",
                List.of(new PersistedBot(bot, MissionRuntimeRecord.empty())), List.of());
    }

    @Test
    void newFieldRoundTripsAndKeepsTheSchema() {
        BotRecord bot = new BotRecord("B", "minecraft:overworld", 1, 2, 3, 0, 0, "survival", 20, 20,
                "{}", "{}", "", 1, STATE);
        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(
                new StringReader(RuntimeSnapshotCodec.encode(snapshot(bot))));
        assertEquals(RuntimeSnapshotCodec.Status.OK, decoded.status());
        assertEquals(STATE, decoded.snapshot().bots().getFirst().bot().playerStateNbt());
        assertEquals(1, RuntimeSnapshot.CURRENT_SCHEMA, "no schema bump: old files must keep loading");
    }

    @Test
    void recordWrittenBeforeTheFieldExistedDecodesToNothingToRestore() {
        BotRecord bot = new BotRecord("B", "minecraft:overworld", 1, 2, 3, 0, 0, "survival", 20, 20,
                "{Inventory:[]}", "{}", "", 1, STATE);
        JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot(bot))).getAsJsonObject();
        JsonObject botJson = root.getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonObject("bot");
        assertTrue(botJson.has("playerStateNbt"));
        botJson.remove("playerStateNbt");

        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(new StringReader(root.toString()));
        assertEquals(RuntimeSnapshotCodec.Status.OK, decoded.status());
        BotRecord old = decoded.snapshot().bots().getFirst().bot();
        assertNull(old.playerStateNbt());
        assertEquals("{Inventory:[]}", old.inventoryNbt());
    }

    @Test
    void legacyFourteenArgumentConstructorMeansNoPlayerState() {
        BotRecord bot = new BotRecord("B", "minecraft:overworld", 1, 2, 3, 0, 0, "survival", 20, 20,
                "{}", "{}", "", null);
        assertNull(bot.playerStateNbt());
    }

    @Test
    void captureAndRestoreAreWired() throws IOException {
        String persistence = Files.readString(
                Path.of("src/main/java/io/github/zoyluo/minecraftai/persist/BotPersistence.java"));
        assertTrue(persistence.contains("BotPlayerState.encode(bot)"), "capture must save the player state");
        String manager = Files.readString(
                Path.of("src/main/java/io/github/zoyluo/minecraftai/manager/AIPlayerManager.java"));
        int restore = manager.indexOf("BotPersistence.applyPlayerState(bot, record.playerStateNbt())");
        int inventory = manager.indexOf("BotPersistence.applyInventory(bot, record.inventoryNbt())");
        int health = manager.indexOf("bot.setHealth(Math.max(1.0F");
        assertTrue(inventory >= 0 && restore > inventory, "state restores after the main inventory");
        assertTrue(health > restore, "health is clamped after effects/absorption changed the max");
        assertEquals(restore, manager.lastIndexOf("BotPersistence.applyPlayerState("),
                "the player state must be restored exactly once per respawn");
    }
}
