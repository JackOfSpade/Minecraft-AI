package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Population by allocation (dataVersion 4): the SEEN flag and everything a sleeping seen bot needs persist; a real death is a
 * DEAD record; and a store from before it (dormant bots nobody is known to have seen) has those sleepers released on load.
 */
class PopulationFileMigrationTest {
    private static final String K1 = "minecraft:overworld|minecraft:village_plains|1,2";

    private static PopulationFile.Parsed ok(String json) {
        PopulationFile.ReadResult r = PopulationFile.parseText(json);
        assertEquals(PopulationFile.Outcome.OK, r.outcome(), r.detail());
        return r.parsed();
    }

    private static BotSnapshot snapshot() {
        BotSnapshot s = new BotSnapshot();
        s.health = 8.5f;
        s.foodLevel = 14;
        s.xpLevel = 12;
        s.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:iron_sword\",\"count\":1}"));
        s.stacks.add(new BotSnapshot.Entry(37, "{\"id\":\"minecraft:arrow\",\"count\":23}"));
        return s;
    }

    // ------------------------------------------------------------------ persistence of the seen flag and the sleeper

    @Test
    void theSeenFlagAndASleepersWholeStateSurviveWriteAndRead(@TempDir Path dir) throws IOException {
        StructureRecord rec = populated("Seen_1", "Unseen_2");
        BotRecord seen = rec.bots.get(0);
        seen.seen = true;
        seen.firstSeenMillis = 1_700_000_100_000L;
        seen.lastSeenMillis = 1_700_000_900_000L;
        seen.state = BotState.DORMANT;
        seen.removing = true;
        seen.dimension = NETHER;
        seen.x = -33.5;
        seen.y = 71.0;
        seen.z = 902.25;
        seen.yaw = 181.5f;
        seen.snapshot = snapshot();
        rec.nextBotIndex = 7;
        Map<StructureKey, StructureRecord> records = new LinkedHashMap<>();
        records.put(key(VILLAGE, 1, 2), rec);
        Path file = dir.resolve("populations.json");
        PopulationFile.write(file, StructureRecord.CURRENT_DATA_VERSION, records, Map.of());

        PopulationFile.Parsed read = PopulationFile.read(file).parsed();
        StructureRecord back = read.structures().get(key(VILLAGE, 1, 2));
        assertBotEquals(seen, back.bots.get(0));
        assertTrue(back.bots.get(0).seen);
        assertEquals(1_700_000_100_000L, back.bots.get(0).firstSeenMillis);
        assertEquals(NETHER, back.bots.get(0).dimension);
        assertFalse(back.bots.get(1).seen);
        assertEquals(7, back.nextBotIndex, "the counter that keeps indices from being reused is kept");
    }

    @Test
    void theSeenFlagSurvivesARealStoreSaveAndLoad(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        StructureRecord rec = populated("Seen_1");
        rec.bots.get(0).seen = true;
        rec.bots.get(0).firstSeenMillis = 42L;
        store.put(key(VILLAGE, 1, 2), rec);
        assertTrue(store.saveIfDirty());

        PopulationStore again = loaded(dir);
        BotRecord bot = again.find(key(VILLAGE, 1, 2)).orElseThrow().bots.get(0);
        assertTrue(bot.seen);
        assertEquals(42L, bot.firstSeenMillis);
    }

    @Test
    void aDeadBotIsARecordThatKeepsItsNameAndIndexAndNothingElse(@TempDir Path dir) throws IOException {
        StructureRecord rec = populated("Dead_1", "Alive_2");
        BotRecord dead = rec.bots.get(0);
        dead.state = BotState.DEAD;
        dead.profile = null;
        dead.snapshot = null;
        Map<StructureKey, StructureRecord> records = new LinkedHashMap<>();
        records.put(key(VILLAGE, 1, 2), rec);
        Path file = dir.resolve("populations.json");
        PopulationFile.write(file, StructureRecord.CURRENT_DATA_VERSION, records, Map.of());
        StructureRecord back = PopulationFile.read(file).parsed().structures().get(key(VILLAGE, 1, 2));
        assertEquals(BotState.DEAD, back.bots.get(0).state);
        assertEquals(1, back.deadCount());
        assertEquals(1, back.spawnedCount());
        assertEquals(2, back.plannedBots);
        assertEquals(1, back.fillTarget(), "N - dead");
        assertEquals(0, back.vacantSlots(), "the live one occupies the only slot that is left; the dead one's is spent");
    }

    // ------------------------------------------------------------------ migration

    private static String bot(int index, String name, String state, String extra) {
        return "{\"index\":" + index + ",\"name\":\"" + name + "\",\"state\":\"" + state + "\",\"seed\":" + (100 + index)
                + ",\"x\":10.5,\"y\":64,\"z\":-3.5,\"yaw\":90"
                + ",\"snapshot\":{\"version\":1,\"stacks\":[{\"slot\":0,\"stack\":\"{}\"}],\"health\":9}"
                + extra + "}";
    }

    @Test
    void oldDormantBotsAreReleasedOnLoadAndTheirSlotsAreFreeAgain() {
        PopulationFile.Parsed parsed = ok("{\"dataVersion\":3,\"structures\":{\"" + K1 + "\":{\"dataVersion\":3,"
                + "\"status\":\"POPULATED\",\"plannedBots\":5,\"structureSeed\":77,\"bots\":["
                + bot(0, "Live_0", "SPAWNED", "") + "," + bot(1, "Asleep_1", "DORMANT", "") + ","
                + bot(2, "Asleep_2", "DORMANT", "") + "," + bot(3, "Nobody_3", "FAILED", "") + ","
                + bot(4, "Planned_4", "PLANNED", "") + "]}}}");
        StructureRecord r = parsed.structures().get(StructureKey.parse(K1));
        assertEquals(3, r.bots.size(), "the two dormant, never-seen bots are gone");
        assertEquals("Live_0", r.bots.get(0).name);
        assertEquals(BotState.FAILED, r.bots.get(1).state);
        assertEquals(BotState.PLANNED, r.bots.get(2).state);
        assertEquals(StructureRecord.CURRENT_DATA_VERSION, r.dataVersion);
        assertEquals(5, r.nextBotIndex, "their indices are never reused: a slot refilled later is a different bot");
        // 5 planned - 0 dead - 1 failed - 2 occupied (the live one and the planned one) = 2 free: the two released sleepers' slots
        assertEquals(2, r.vacantSlots());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("2 unseen stored bots released")),
                "one summary line: " + parsed.warnings());
    }

    @Test
    void deathsAlreadyRecordedStayRecordedThroughTheMigration() {
        PopulationFile.Parsed parsed = ok("{\"dataVersion\":3,\"structures\":{\"" + K1 + "\":{\"dataVersion\":3,"
                + "\"status\":\"POPULATED\",\"plannedBots\":3,\"bots\":["
                + bot(0, "Dead_0", "DEAD", "") + "," + bot(1, "Asleep_1", "DORMANT", "") + "]}}}");
        StructureRecord r = parsed.structures().get(StructureKey.parse(K1));
        assertEquals(1, r.deadCount());
        assertEquals(1, r.bots.size());
        assertEquals(2, r.vacantSlots(), "3 planned - 1 dead: the released sleeper's slot and one that was never filled");
    }

    @Test
    void aSeenSleeperIsKeptWhateverTheVersion() {
        PopulationFile.Parsed parsed = ok("{\"dataVersion\":4,\"structures\":{\"" + K1 + "\":{\"dataVersion\":4,"
                + "\"status\":\"POPULATED\",\"plannedBots\":2,\"bots\":["
                + bot(0, "Seen_0", "DORMANT", ",\"seen\":true,\"firstSeenMillis\":5") + ","
                + bot(1, "Live_1", "SPAWNED", "") + "]}}}");
        StructureRecord r = parsed.structures().get(StructureKey.parse(K1));
        assertEquals(2, r.bots.size());
        assertEquals(BotState.DORMANT, r.bots.get(0).state);
        assertNotNull(r.bots.get(0).snapshot);
        assertTrue(parsed.warnings().stream().noneMatch(w -> w.contains("released")));
    }

    @Test
    void aCurrentFileIsNotMigratedAgainAndSaysNothing() {
        PopulationFile.Parsed parsed = ok("{\"dataVersion\":4,\"structures\":{\"" + K1 + "\":{\"dataVersion\":4,"
                + "\"status\":\"POPULATED\",\"plannedBots\":2,\"bots\":["
                + bot(0, "Asleep_0", "DORMANT", "") + "]}}}");
        assertEquals(1, parsed.structures().get(StructureKey.parse(K1)).bots.size(),
                "a version 4 sleeper is what it says: it was put to sleep as a seen bot");
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void theReleaseIsReportedThroughTheStoreLoadReport(@TempDir Path dir) throws IOException {
        write(dir.resolve("populations.json"), "{\"dataVersion\":3,\"structures\":{\"" + K1 + "\":{\"dataVersion\":3,"
                + "\"status\":\"POPULATED\",\"plannedBots\":2,\"bots\":[" + bot(0, "Asleep_0", "DORMANT", "") + ","
                + bot(1, "Asleep_1", "DORMANT", "") + "]}}}");
        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertTrue(report.messages().stream().anyMatch(m -> m.contains("2 unseen stored bots released")), report.messages().toString());
        assertEquals(0, store.find(StructureKey.parse(K1)).orElseThrow().bots.size());
    }
}
