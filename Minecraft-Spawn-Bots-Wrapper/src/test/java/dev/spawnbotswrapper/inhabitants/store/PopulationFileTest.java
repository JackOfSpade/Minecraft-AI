package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.store.PopulationFile.Outcome.INVALID;
import static dev.spawnbotswrapper.inhabitants.store.PopulationFile.Outcome.MISSING;
import static dev.spawnbotswrapper.inhabitants.store.PopulationFile.Outcome.OK;
import static dev.spawnbotswrapper.inhabitants.store.PopulationFile.Outcome.TOO_NEW;
import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** The JSON codec on its own: strict where damage could hide, tolerant where compatibility demands it. */
class PopulationFileTest {
    private static final String K1 = "minecraft:overworld|minecraft:village_plains|1,2";
    private static final String K2 = "minecraft:the_nether|minecraft:fortress|-3,4";

    private static PopulationFile.ReadResult parse(String json) {
        return PopulationFile.parseText(json);
    }

    private static PopulationFile.Parsed ok(String json) {
        PopulationFile.ReadResult r = parse(json);
        assertEquals(OK, r.outcome(), r.detail());
        return r.parsed();
    }

    private static String doc(String structuresBody) {
        return "{\"dataVersion\":1,\"structures\":{" + structuresBody + "}}";
    }

    private static Path writeAndGet(Path dir, Map<StructureKey, StructureRecord> records,
                                    Map<String, PersistentDeckStore.Snapshot> decks) throws IOException {
        Path file = dir.resolve("populations.json");
        PopulationFile.write(file, 1, records, decks);
        return file;
    }

    // ---------------------------------------------------------------- round trip

    @Test
    void writeThenReadPreservesRecordsOrderAndDecks(@TempDir Path dir) throws IOException {
        Map<StructureKey, StructureRecord> records = new LinkedHashMap<>();
        records.put(key(VILLAGE, 9, 9), populated("Zed_1", "Amy_2"));
        records.put(key(OUTPOST, -1, -1), pending("Bob_3"));
        records.put(new StructureKey(NETHER, "minecraft:fortress", 4, 5), gaveUp());
        PersistentDeckStore store = new PersistentDeckStore();
        store.deck("a", 4);
        store.deck("b", 2);
        Map<String, PersistentDeckStore.Snapshot> decks = store.exportSnapshots();

        Path file = writeAndGet(dir, records, decks);
        PopulationFile.ReadResult read = PopulationFile.read(file);
        assertEquals(OK, read.outcome(), read.detail());
        assertEquals(1, read.parsed().dataVersion());
        assertEquals(List.copyOf(records.keySet()), List.copyOf(read.parsed().structures().keySet()), "storage order is kept");
        for (Map.Entry<StructureKey, StructureRecord> e : records.entrySet()) {
            assertRecordEquals(e.getValue(), read.parsed().structures().get(e.getKey()));
        }
        assertEquals(2, read.parsed().decks().size());
        assertEquals(4, read.parsed().decks().get("a").size);
        assertArrayEquals(decks.get("a").order, read.parsed().decks().get("a").order);
        assertTrue(read.parsed().warnings().isEmpty());
    }

    @Test
    void theFileIsCompactJsonWithNoNulls(@TempDir Path dir) throws IOException {
        Path file = writeAndGet(dir, Map.of(key(1, 1), pending("Solo_1")), Map.of());
        String text = Files.readString(file);
        assertFalse(text.contains("null"));
        assertFalse(text.contains("\n"));
        assertTrue(text.startsWith("{\"dataVersion\":1,\"structures\":{"), text);
    }

    @Test
    void aMissingFileIsReportedAsMissingNotInvalid(@TempDir Path dir) {
        assertEquals(MISSING, PopulationFile.read(dir.resolve("nope.json")).outcome());
    }

    @Test
    void controlCharactersUnicodeAndLineSeparatorsInTextFieldsRoundTrip(@TempDir Path dir) throws IOException {
        StructureRecord record = pending("Text_1");
        record.note = "line1\nline2\ttab \"quoted\" back\\slash     \u0000 😀 é <b>&amp;</b>";
        record.bots.get(0).failure = "日本語";
        Path file = writeAndGet(dir, Map.of(key(1, 1), record), Map.of());
        StructureRecord back = PopulationFile.read(file).parsed().structures().get(key(1, 1));
        assertEquals(record.note, back.note);
        assertEquals(record.bots.get(0).failure, back.bots.get(0).failure);
        assertTrue(Files.readString(file).contains("<b>&amp;</b>"), "no HTML escaping: the file stays readable");
    }

    @Test
    void nonFiniteNumbersAreWrittenAsZeroSoTheDocumentStaysValidJson(@TempDir Path dir) throws IOException {
        StructureRecord record = pending("Nan_1");
        record.roll = Double.NaN;
        record.bots.get(0).yaw = Float.POSITIVE_INFINITY;
        Path file = writeAndGet(dir, Map.of(key(1, 1), record), Map.of());
        StructureRecord back = PopulationFile.read(file).parsed().structures().get(key(1, 1));
        assertEquals(0.0, back.roll);
        assertEquals(0.0f, back.bots.get(0).yaw);
    }

    // ---------------------------------------------------------------- compatibility

    @Test
    void unknownFieldsAreIgnoredAtEveryLevel() {
        PopulationFile.Parsed parsed = ok("""
                {"dataVersion":1,"futureTopLevel":{"a":[1,2,3]},
                 "structures":{"%s":{
                    "status":"POPULATED","futureField":"x","source":"ADMIN_FORCED",
                    "bots":[{"index":0,"name":"Alpha_1","state":"SPAWNED","futureBotField":[{"deep":true}],
                             "profile":{"version":1,"seed":7,"archetype":"tank","futureProfileField":1,
                                        "loadout":{"items":[{"slot":"hotbar","index":0,"futurePlaced":1,
                                                     "spec":{"item":"minecraft:stick","count":1,"futureSpec":"y"}}],"futureLoadout":0},
                                        "vitals":{"healthFraction":0.5,"foodLevel":10,"attributes":{},"futureVitals":1},
                                        "behavior":{"stance":"STAND","combatant":true,"walkType":"walk","patrolRadius":0,
                                                    "waypointCount":0,"waypoints":[],"futureBehavior":1},
                                        "futureProfilePart":{"x":1}}}]}},
                 "decks":{"height":{"size":3,"order":[2,0,1],"cursor":1,"futureDeckField":1}}}
                """.formatted(K1));
        StructureRecord record = parsed.structures().get(StructureKey.parse(K1));
        assertEquals(StructureStatus.POPULATED, record.status);
        assertEquals("ADMIN_FORCED", record.source);
        BotRecord bot = record.bots.get(0);
        assertEquals("Alpha_1", bot.name);
        assertEquals("tank", bot.profile.archetype());
        assertEquals(0.5, bot.profile.vitals().healthFraction());
        assertEquals("minecraft:stick", bot.profile.loadout().items().get(0).spec().item());
        assertEquals(1, parsed.decks().get("height").cursor);
    }

    @Test
    void aRecordFromAnOlderLayoutWithMissingFieldsGetsSaneDefaults() {
        PopulationFile.Parsed parsed = ok(doc("\"" + K1 + "\":{\"status\":\"POPULATED\",\"bots\":[{\"name\":\"Old_1\",\"state\":\"SPAWNED\"}]},"
                + "\"" + K2 + "\":{}"));

        StructureRecord old = parsed.structures().get(StructureKey.parse(K1));
        assertEquals(StructureRecord.CURRENT_DATA_VERSION, old.dataVersion);
        assertEquals("RANDOM", old.source);
        assertEquals(0.0, old.occupiedChance);
        assertEquals(0, old.attempts);
        assertNull(old.note);
        assertNull(old.bounds);
        BotRecord bot = old.bots.get(0);
        assertEquals("Old_1", bot.name);
        assertEquals(BotState.SPAWNED, bot.state);
        assertNull(bot.uuid);
        assertNull(bot.profile);
        assertFalse(bot.profileApplied);
        assertEquals(0, bot.spawnAttempts);

        StructureRecord empty = parsed.structures().get(StructureKey.parse(K2));
        assertEquals(StructureStatus.OCCUPIED_PENDING, empty.status, "the record's own default applies");
        assertNotNull(empty.bots);
        assertTrue(empty.bots.isEmpty());
        assertTrue(parsed.decks().isEmpty());
    }

    @Test
    void aProfileWithMissingPartsIsFilledInByTheProfileDefaults() {
        PopulationFile.Parsed parsed = ok(doc("\"" + K1 + "\":{\"bots\":[{\"name\":\"Bare_1\",\"profile\":{\"seed\":5}}]}"));
        BotProfile profile = parsed.structures().get(StructureKey.parse(K1)).bots.get(0).profile;
        assertEquals(5L, profile.seed());
        assertEquals("", profile.archetype());
        assertTrue(profile.loadout().items().isEmpty());
        assertEquals(20, profile.vitals().foodLevel());
        assertEquals(BotProfile.Stance.STAND, profile.behavior().stance());
        assertEquals(BotProfile.WalkType.BHOP, profile.behavior().walkType());
    }

    @Test
    void explicitNullsAreRepairedNotPropagated() {
        PopulationFile.Parsed parsed = ok(doc("\"" + K1 + "\":{\"bots\":null,\"note\":null},"
                + "\"" + K2 + "\":{\"bots\":[null,{\"name\":\"Kept_1\",\"state\":\"PLANNED\"},null]}"));
        assertTrue(parsed.structures().get(StructureKey.parse(K1)).bots.isEmpty());
        List<BotRecord> bots = parsed.structures().get(StructureKey.parse(K2)).bots;
        assertEquals(1, bots.size());
        assertEquals("Kept_1", bots.get(0).name);
    }

    @Test
    void decksWithAnAbsurdSizeAreDroppedInsteadOfBeingAllocated() {
        PopulationFile.Parsed parsed = ok("{\"dataVersion\":1,\"structures\":{},\"decks\":{"
                + "\"huge\":{\"size\":2000000000,\"order\":[0],\"cursor\":0},"
                + "\"zero\":{\"size\":0},"
                + "\"negative\":{\"size\":-5},"
                + "\"fine\":{\"size\":3,\"order\":[1,0,2],\"cursor\":2},"
                + "\"missing\":null}}");
        assertEquals(java.util.Set.of("fine", "missing"), parsed.decks().keySet());
        assertEquals(3, parsed.warnings().size(), parsed.warnings().toString());
        assertTrue(parsed.warnings().stream().allMatch(w -> w.contains("unusable size")));
        // and the deck store itself copes with what is left (a null entry is ignored)
        PersistentDeckStore store = new PersistentDeckStore();
        store.importSnapshots(parsed.decks());
        assertEquals(2, store.deck("fine", 3).cursor());
        assertEquals(3, store.deck("huge", 3).size(), "a dropped deck is simply recreated");
    }

    @Test
    void aMissingDataVersionIsToleratedWithAWarning() {
        PopulationFile.Parsed parsed = ok("{\"structures\":{}}");
        assertEquals(StructureRecord.CURRENT_DATA_VERSION, parsed.dataVersion());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("dataVersion")), parsed.warnings().toString());
    }

    @Test
    void aBotWithoutANameIsKeptButWarnedAbout() {
        PopulationFile.Parsed parsed = ok(doc("\"" + K1 + "\":{\"bots\":[{\"state\":\"PLANNED\"}]}"));
        assertEquals(1, parsed.structures().get(StructureKey.parse(K1)).bots.size());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("no name")), parsed.warnings().toString());
    }

    @Test
    void unknownEnumConstantsAreCorruptionNotSilentDefaults() {
        assertEquals(INVALID, parse(doc("\"" + K1 + "\":{\"status\":\"SOMETHING_NEW\"}")).outcome());
        assertEquals(INVALID, parse(doc("\"" + K1 + "\":{\"bots\":[{\"name\":\"A_1\",\"state\":\"TELEPORTING\"}]}")).outcome());
        assertEquals(INVALID, parse(doc("\"" + K1 + "\":{\"status\":null}")).outcome());
    }

    // ---------------------------------------------------------------- strictness (damage must not hide)

    @Test
    void structurallyDamagedDocumentsAreInvalid() {
        for (String bad : List.of(
                "",
                "   ",
                "null",
                "[]",
                "42",
                "\"text\"",
                "{}",                                                   // recognisably not one of ours
                "{\"dataVersion\":1}",                                  // no structures
                "{\"dataVersion\":1,\"structures\":[]}",
                "{\"dataVersion\":1,\"structures\":{",                  // truncated
                "{\"dataVersion\":1,\"structures\":{}",                 // truncated: missing final brace
                "{\"dataVersion\":1,\"structures\":{},}",               // trailing comma
                "{'dataVersion':1,'structures':{}}",                    // single quotes
                "{dataVersion:1,structures:{}}",                        // unquoted names
                "// note\n{\"dataVersion\":1,\"structures\":{}}",       // comments
                "{\"dataVersion\":1,\"structures\":{}} {}",             // two documents
                "{\"dataVersion\":1,\"structures\":{}}garbage",
                "{\"dataVersion\":\"one\",\"structures\":{}}",
                "{\"dataVersion\":1.5,\"structures\":{}}",
                "{\"dataVersion\":0,\"structures\":{}}",
                "{\"dataVersion\":-1,\"structures\":{}}",
                "{\"dataVersion\":1,\"dataVersion\":1,\"structures\":{}}",
                "{\"dataVersion\":1,\"structures\":{},\"structures\":{}}",
                doc("\"" + K1 + "\":\"not an object\""),
                doc("\"" + K1 + "\":[]"),
                doc("\"" + K1 + "\":null"),
                doc("\"not a key\":{}"),
                doc("\"" + K1 + "\":{},\"" + K1 + "\":{}"),             // duplicate key
                doc("\"" + K1 + "\":{\"roll\":\"abc\"}"),
                doc("\"" + K1 + "\":{\"attempts\":1.5}"),
                doc("\"" + K1 + "\":{\"roll\":NaN}"),
                doc("\"" + K1 + "\":{\"bots\":\"none\"}"),
                doc("\"" + K1 + "\":{\"bots\":[{\"name\":{},\"state\":\"PLANNED\"}]}"),
                doc("\"" + K1 + "\":{\"bounds\":{}}"),
                doc("\"" + K1 + "\":{\"bots\":[{\"state\":\"PLANNED\",\"profile\":[]}]}"),
                "{\"dataVersion\":1,\"structures\":{},\"decks\":[]}",
                "{\"dataVersion\":1,\"structures\":{},\"decks\":[[\"a\",{\"size\":2,\"order\":[0,1],\"cursor\":0}]]}",
                "{\"dataVersion\":1,\"structures\":{},\"decks\":null}",
                "{\"dataVersion\":1,\"structures\":{},\"decks\":{\"a\":{\"size\":2,\"order\":[0,1],\"cursor\":0},"
                        + "\"a\":{\"size\":2,\"order\":[0,1],\"cursor\":0}}}",
                "{\"dataVersion\":1,\"structures\":{},\"decks\":{\"a\":{\"size\":\"x\"}}}")) {
            PopulationFile.ReadResult r = parse(bad);
            assertEquals(INVALID, r.outcome(), "should be rejected: " + bad + " -> " + r.detail());
            assertNull(r.parsed());
            assertFalse(r.detail().isBlank());
        }
    }

    @Test
    void aRecordWhoseRecordConstructorRejectsItsValuesIsInvalidNotACrash() {
        // Map.copyOf throws NullPointerException inside BotProfile's constructor for a null value
        PopulationFile.ReadResult r = parse(doc("\"" + K1 + "\":{\"bots\":[{\"name\":\"A_1\",\"state\":\"PLANNED\","
                + "\"profile\":{\"loadout\":{\"items\":[{\"slot\":\"hotbar\",\"index\":0,\"spec\":{\"item\":\"x\",\"enchantments\":{\"e\":null}}}]}}}]}"));
        assertEquals(INVALID, r.outcome(), r.detail());
    }

    @Test
    void invalidUtf8IsInvalidNotReplacedSilently(@TempDir Path dir) throws IOException {
        byte[] good = "{\"dataVersion\":1,\"structures\":{\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = "\":{\"note\":\"x\"}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] key = K1.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] all = new byte[good.length + key.length + 2 + tail.length];
        System.arraycopy(good, 0, all, 0, good.length);
        System.arraycopy(key, 0, all, good.length, key.length);
        all[good.length + key.length] = (byte) 0xC3;      // a lead byte with no continuation
        all[good.length + key.length + 1] = (byte) 0x28;
        System.arraycopy(tail, 0, all, good.length + key.length + 2, tail.length);
        Path file = dir.resolve("populations.json");
        Files.write(file, all);
        assertEquals(INVALID, PopulationFile.read(file).outcome());
    }

    @Test
    void aFileThatIsADirectoryIsInvalidNotMissing(@TempDir Path dir) throws IOException {
        Path asDir = dir.resolve("populations.json");
        Files.createDirectories(asDir);
        assertEquals(INVALID, PopulationFile.read(asDir).outcome());
    }

    @Test
    void trailingWhitespaceAfterTheDocumentIsFine() {
        assertEquals(OK, parse("{\"dataVersion\":1,\"structures\":{}}\n\n  ").outcome());
    }

    // ---------------------------------------------------------------- newer versions

    @Test
    void aNewerDataVersionIsTooNewWhateverElseItContains() {
        assertEquals(TOO_NEW, parse("{\"dataVersion\":2,\"structures\":{}}").outcome());
        assertEquals(TOO_NEW, parse("{\"dataVersion\":2,\"structures\":\"a new kind of thing\"}").outcome());
        assertEquals(TOO_NEW, parse("{\"dataVersion\":2}").outcome());
        assertEquals(TOO_NEW, parse("{\"dataVersion\":2,\"structures\":{\"" + K1 + "\":{\"status\":\"BRAND_NEW\"}}}").outcome());
        assertEquals(TOO_NEW, parse(doc("\"" + K1 + "\":{\"dataVersion\":3}")).outcome());
    }
}
