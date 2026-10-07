package io.github.zoyluo.minecraftai.persist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tower a bot stood on when it was saved: how it is written, how it reads back, and what a restart makes of a save from before
 * the field existed or of a damaged one. Pure (no Minecraft bootstrap); the real-server round trips are the GameTests in
 * {@code TowerRestoreGameTests}.
 */
class TowerBaseCodecTest {
    private static RuntimeSnapshot snapshot(BotRecord bot) {
        return new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, "t", "b", "s",
                List.of(new PersistedBot(bot, MissionRuntimeRecord.empty())), List.of());
    }

    private static BotRecord record(String towerBase) {
        return new BotRecord("B", "minecraft:overworld", 1, 70, 3, 0, 0, "survival", 20, 20,
                "{Inventory:[]}", "{}", "", 1, "{XpLevel:7}", null, towerBase);
    }

    private static BotRecord throughRuntimeJson(JsonObject root) {
        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(new StringReader(root.toString()));
        assertEquals(RuntimeSnapshotCodec.Status.OK, decoded.status());
        return decoded.snapshot().bots().getFirst().bot();
    }

    private static JsonObject botJson(JsonObject root) {
        return root.getAsJsonArray("bots").get(0).getAsJsonObject().getAsJsonObject("bot");
    }

    @Test
    void aTowerBaseReadsBackAsItself() {
        for (BlockPos base : List.of(new BlockPos(5, 64, 9), new BlockPos(-5, -60, -20), new BlockPos(0, 0, 0),
                new BlockPos(-30000000, 319, 29999999))) {
            TowerBaseCodec.Decoded decoded = TowerBaseCodec.decode(TowerBaseCodec.encode(base));
            assertEquals(TowerBaseCodec.Status.VALID, decoded.status(), base.toString());
            assertEquals(base, decoded.base());
        }
    }

    @Test
    void theTowerSurvivesTheRuntimeJsonAndKeepsTheSchema() {
        BlockPos base = new BlockPos(17, 123, -50);
        JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot(record(TowerBaseCodec.encode(base)))))
                .getAsJsonObject();
        assertEquals("17,123,-50", botJson(root).get("towerBase").getAsString());

        BotRecord restored = throughRuntimeJson(root);
        assertEquals(base, TowerBaseCodec.decode(restored.towerBase()).base());
        assertEquals("{XpLevel:7}", restored.playerStateNbt(), "the rest of the record is untouched");
        assertEquals(1, RuntimeSnapshot.CURRENT_SCHEMA, "no schema bump: old files must keep loading");
    }

    @Test
    void aSaveFromBeforeTheFieldExistedHasNoTowerAndRestoresAsItAlwaysDid() {
        JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot(record("5,64,9")))).getAsJsonObject();
        assertTrue(botJson(root).has("towerBase"));
        botJson(root).remove("towerBase");

        BotRecord old = throughRuntimeJson(root);
        assertNull(old.towerBase());
        assertEquals(TowerBaseCodec.Status.ABSENT, TowerBaseCodec.decode(old.towerBase()).status());
        assertNull(TowerBaseCodec.decode(old.towerBase()).base());
        assertEquals("{Inventory:[]}", old.inventoryNbt());
        assertEquals("{XpLevel:7}", old.playerStateNbt());
        assertEquals(70.0D, old.y());
    }

    @Test
    void aBotThatStoodOnNoTowerWritesNothingForOne() {
        JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot(record(null)))).getAsJsonObject();

        assertFalse(botJson(root).has("towerBase"), "an older build must read the very file it always did");
    }

    @Test
    void theOlderConstructorShapesMeanNoTower() {
        assertNull(new BotRecord("B", "minecraft:overworld", 1, 2, 3, 0, 0, "survival", 20, 20,
                "{}", "{}", "", null).towerBase());
        assertNull(new BotRecord("B", "minecraft:overworld", 1, 2, 3, 0, 0, "survival", 20, 20,
                "{}", "{}", "", 1, "{}").towerBase());
        assertNull(new BotRecord("B", "minecraft:overworld", 1, 2, 3, 0, 0, "survival", 20, 20,
                "{}", "{}", "", 1, "{}", "{\"version\":1}").towerBase());
    }

    @Test
    void anythingButThreeCanonicalIntegersIsRefusedNotGuessedAt() {
        for (String damaged : List.of("", " ", ",,", "1,2", "1,2,3,4", "1,2,3,", ",1,2,3", "a,b,c", "1,2,x", "1,,3",
                "1.5,2,3", "1, 2,3", " 1,2,3", "1,2,3 ", "+1,2,3", "01,2,3", "1,02,3", "-0,2,3", "1;2;3", "[1,2,3]",
                "99999999999,1,1", "1,2,NaN", "none", "null")) {
            TowerBaseCodec.Decoded decoded = TowerBaseCodec.decode(damaged);
            assertEquals(TowerBaseCodec.Status.MALFORMED, decoded.status(), "'" + damaged + "'");
            assertNull(decoded.base(), "'" + damaged + "' must not yield a tower");
        }
    }

    @Test
    void aDamagedValueIsCarriedByTheCodecAndRefusedWhereItIsRead() {
        JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot(record("5,64"))))
                .getAsJsonObject();

        BotRecord restored = throughRuntimeJson(root);
        assertEquals("5,64", restored.towerBase());
        assertEquals(TowerBaseCodec.Status.MALFORMED, TowerBaseCodec.decode(restored.towerBase()).status());
    }

    @Test
    void aTowerBaseOfTheWrongJsonTypeFailsTheWholeLoadClosed() {
        JsonObject root = JsonParser.parseString(RuntimeSnapshotCodec.encode(snapshot(record("5,64,9")))).getAsJsonObject();
        JsonObject tower = new JsonObject();
        tower.addProperty("x", 5);
        botJson(root).add("towerBase", tower);

        RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(new StringReader(root.toString()));
        assertEquals(RuntimeSnapshotCodec.Status.MALFORMED, decoded.status(),
                "an object where the text belongs is a damaged file, which the load refuses whole instead of restoring half of it");
        assertNull(decoded.snapshot());
    }
}
