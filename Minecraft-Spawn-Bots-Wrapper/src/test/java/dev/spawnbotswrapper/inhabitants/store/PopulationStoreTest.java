package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.engine.PopulationView.BotLocation;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView.PopulationCounts;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.sample.Deck;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PopulationStoreTest {

    // ---------------------------------------------------------------- fresh / round trip

    @Test
    void missingDirectoryIsAFreshUsableStoreAndIsCreated(@TempDir Path tmp) {
        Path dir = tmp.resolve("world/pvpbot_inhabitants");
        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();

        assertTrue(report.usable());
        assertTrue(store.usable());
        assertEquals(PopulationStore.LoadSource.FRESH, store.loadSource());
        assertTrue(report.messages().stream().anyMatch(m -> m.contains("starting a new store")), report.messages().toString());
        assertTrue(Files.isDirectory(dir));
        assertEquals(new PopulationCounts(0, 0, 0, 0, 0, 0), store.counts());
        assertTrue(store.find(key(1, 2)).isEmpty());
        assertTrue(store.nonAbandoned().isEmpty());
        assertTrue(store.nearby(OVERWORLD, 0, 0, 100).isEmpty());
        assertTrue(store.saveIfDirty(), "nothing to write is not a failure");
        assertFalse(Files.exists(dir.resolve(PopulationStore.POPULATIONS_FILE)), "a clean store writes nothing");
    }

    @Test
    void pendingPopulatedAndGaveUpRecordsRoundTripThroughAFreshInstance(@TempDir Path dir) {
        StructureRecord pending = pending("Alpha_1", "Bravo_2", "Charlie_3");
        pending.bots.get(1).state = BotState.REQUESTED;
        pending.bots.get(1).x = 12.5;
        pending.bots.get(1).y = 65;
        pending.bots.get(1).z = -7.75;
        pending.bots.get(1).yaw = 180.25f;
        pending.bots.get(1).spawnAttempts = 1;
        StructureRecord populated = populated("Delta_4", "Echo_5");
        StructureRecord gaveUp = gaveUp();
        StructureKey pendingKey = key(VILLAGE, 10, 20);
        StructureKey populatedKey = new StructureKey(NETHER, "minecraft:fortress", -5, 300);
        StructureKey gaveUpKey = key(OUTPOST, -100, -100);

        PopulationStore store = loaded(dir);
        store.put(pendingKey, pending);
        store.put(populatedKey, populated);
        store.put(gaveUpKey, gaveUp);
        assertTrue(store.saveIfDirty());

        PopulationStore reopened = new PopulationStore(dir);
        assertTrue(reopened.load().usable());
        assertEquals(PopulationStore.LoadSource.MAIN, reopened.loadSource());
        assertRecordEquals(pending, reopened.find(pendingKey).orElseThrow());
        assertRecordEquals(populated, reopened.find(populatedKey).orElseThrow());
        assertRecordEquals(gaveUp, reopened.find(gaveUpKey).orElseThrow());
        assertEquals(populated.bots.get(0).profile, reopened.find(populatedKey).orElseThrow().bots.get(0).profile);
        assertEquals(new PopulationCounts(0, 1, 1, 1, 2, 1), reopened.counts());
    }

    @Test
    void everyPartOfAProfileSurvivesTheRoundTrip(@TempDir Path dir) {
        StructureRecord record = populated("Profiled_1");
        BotProfile original = record.bots.get(0).profile;
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), record);
        store.flush();

        BotProfile back = loaded(dir).find(key(1, 1)).orElseThrow().bots.get(0).profile;
        assertEquals(original, back);
        assertEquals(6, back.loadout().items().size());
        assertEquals(Map.of("minecraft:protection", 4, "minecraft:unbreaking", 3),
                back.loadout().items().get(0).spec().enchantments());
        assertEquals("minecraft:strong_healing", back.loadout().items().get(3).spec().potion());
        assertEquals(-1, back.loadout().items().get(5).index());
        assertEquals(2, back.vitals().attributes().size());
        assertEquals(BotProfile.Op.ADD_MULTIPLIED_BASE, back.vitals().attributes().get("minecraft:knockback_resistance").operation());
        assertEquals(3, back.behavior().waypoints().size());
        assertEquals(new BotProfile.Waypoint(-10.125, 70.0, 0.1), back.behavior().waypoints().get(1));
        assertFalse(back.behavior().combatant());
    }

    @Test
    void nullFieldsAreOmittedFromTheJson(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        StructureRecord record = pending("Solo_1"); // no note, no uuid, no profile, no failure
        store.put(key(1, 1), record);
        store.flush();

        String json = read(dir.resolve(PopulationStore.POPULATIONS_FILE));
        assertFalse(json.contains("null"), json);
        assertFalse(json.contains("\"note\""), json);
        assertFalse(json.contains("\"profile\""), json);
        assertFalse(json.contains("\"uuid\""), json);
        assertTrue(json.contains("\"dataVersion\":" + PopulationStore.DATA_VERSION), json);
    }

    @Test
    void implicitLoadOnFirstUseNeverMistakesAPersistedWorldForAnEmptyOne(@TempDir Path dir) {
        PopulationStore first = loaded(dir);
        first.put(key(3, 4), pending("Keeper_1"));
        first.put(key(9, 9), StructureRecord.abandoned());
        first.flush();

        PopulationStore second = new PopulationStore(dir); // no explicit load()
        assertTrue(second.find(key(3, 4)).isPresent());
        assertEquals(StructureStatus.ABANDONED, second.find(key(9, 9)).orElseThrow().status);
        assertTrue(second.findBot("keeper_1").isPresent());
    }

    @Test
    void loadingAgainDiscardsUnsavedMemoryAndRereadsDisk(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Saved_1"));
        store.flush();
        store.put(key(2, 2), pending("Unsaved_2"));

        assertTrue(store.load().usable());
        assertTrue(store.find(key(1, 1)).isPresent());
        assertTrue(store.find(key(2, 2)).isEmpty());
        assertTrue(store.findBot("Unsaved_2").isEmpty());
        assertTrue(store.findBot("Saved_1").isPresent());
    }

    // ---------------------------------------------------------------- abandoned handling

    @Test
    void anAbandonedDecisionSurvivesWithoutAnySave(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(7, 8), StructureRecord.abandoned());
        // no saveIfDirty(), no flush(): the process "dies" here

        PopulationStore afterCrash = new PopulationStore(dir);
        assertTrue(afterCrash.load().usable());
        assertEquals(StructureStatus.ABANDONED, afterCrash.find(key(7, 8)).orElseThrow().status);
        assertEquals(1, afterCrash.counts().abandoned());
        assertFalse(Files.exists(dir.resolve(PopulationStore.POPULATIONS_FILE)), "the JSON was never written");
    }

    @Test
    void abandonedKeysLiveInTheKeyFileNotInTheJson(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(VILLAGE, 1, 2), StructureRecord.abandoned());
        store.put(key(OUTPOST, -3, 4), StructureRecord.abandoned());
        store.put(key(VILLAGE, 50, 60), pending("Resident_1"));
        store.flush();

        assertEquals(List.of(OVERWORLD + "|" + VILLAGE + "|1,2", OVERWORLD + "|" + OUTPOST + "|-3,4"),
                lines(dir.resolve(PopulationStore.ABANDONED_FILE)));
        String json = read(dir.resolve(PopulationStore.POPULATIONS_FILE));
        assertFalse(json.contains("|1,2"), "abandoned keys must not live in the rewritten JSON");
        assertFalse(json.contains("|-3,4"), json);
        assertTrue(json.contains("|50,60"), json);
    }

    @Test
    void anAbandonedRecordIsSynthesisedFromTheKeyOnly(@TempDir Path dir) {
        StructureRecord detailed = StructureRecord.abandoned();
        detailed.note = "rolled empty";
        detailed.roll = 0.99;
        detailed.structureSeed = 42;
        detailed.bounds = new int[]{1, 2, 3, 4, 5, 6};

        PopulationStore store = loaded(dir);
        store.put(key(1, 1), detailed);
        StructureRecord live = store.find(key(1, 1)).orElseThrow();
        assertEquals(StructureStatus.ABANDONED, live.status);
        assertNull(live.note);
        assertNull(live.bounds);
        assertTrue(live.bots.isEmpty());
        assertNotSame(store.find(key(1, 1)).orElseThrow(), live, "a synthesised record is a fresh object each time");

        StructureRecord restored = loaded(dir).find(key(1, 1)).orElseThrow();
        assertEquals(StructureStatus.ABANDONED, restored.status);
        assertNull(restored.note);
    }

    @Test
    void puttingTheSameAbandonedKeyTwiceAppendsOnce(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), StructureRecord.abandoned());
        store.put(key(1, 1), StructureRecord.abandoned());
        assertEquals(1, lines(dir.resolve(PopulationStore.ABANDONED_FILE)).size());
        assertEquals(1, store.counts().abandoned());
    }

    @Test
    void aNonAbandonedRecordReplacesAnAbandonedEntry(@TempDir Path dir) throws IOException {
        StructureKey k = key(5, 5);
        PopulationStore store = loaded(dir);
        store.put(k, StructureRecord.abandoned());
        store.put(key(6, 6), StructureRecord.abandoned());
        store.flush();

        StructureRecord forced = pending("Forced_1");
        store.put(k, forced);
        assertSame(forced, store.find(k).orElseThrow());
        assertEquals(new PopulationCounts(1, 1, 0, 0, 0, 0), store.counts());
        assertEquals(1, store.nonAbandoned().size());
        assertTrue(store.findBot("forced_1").isPresent());

        // The stale key may only leave abandoned.keys after populations.json holding the record is durable.
        assertTrue(lines(dir.resolve(PopulationStore.ABANDONED_FILE)).contains(OVERWORLD + "|" + VILLAGE + "|5,5"),
                "removed too early: a crash now would forget the structure entirely");

        assertTrue(store.saveIfDirty());
        assertEquals(List.of(OVERWORLD + "|" + VILLAGE + "|6,6"), lines(dir.resolve(PopulationStore.ABANDONED_FILE)));

        PopulationStore reopened = loaded(dir);
        assertRecordEquals(forced, reopened.find(k).orElseThrow());
        assertEquals(new PopulationCounts(1, 1, 0, 0, 0, 0), reopened.counts());
    }

    @Test
    void aCrashBeforeTheReplacementIsSavedLeavesTheStructureAbandoned(@TempDir Path dir) {
        StructureKey k = key(5, 5);
        PopulationStore store = loaded(dir);
        store.put(k, StructureRecord.abandoned());
        store.put(k, pending("Lost_1")); // never saved: crash

        PopulationStore reopened = loaded(dir);
        assertEquals(StructureStatus.ABANDONED, reopened.find(k).orElseThrow().status);
        assertTrue(reopened.findBot("Lost_1").isEmpty());
    }

    @Test
    void aCrashBetweenTheRecordSaveAndTheKeyCleanupKeepsTheRecord(@TempDir Path dir) throws IOException {
        StructureKey k = key(5, 5);
        PopulationStore store = loaded(dir);
        store.put(k, StructureRecord.abandoned());
        store.put(k, pending("Kept_1"));
        // What a crash right after the JSON move (before abandoned.keys is rewritten) leaves behind:
        String staleKeys = read(dir.resolve(PopulationStore.ABANDONED_FILE));
        assertTrue(store.saveIfDirty());
        write(dir.resolve(PopulationStore.ABANDONED_FILE), staleKeys);

        PopulationStore reopened = new PopulationStore(dir);
        PopulationStore.LoadReport report = reopened.load();
        assertTrue(report.usable());
        assertEquals(StructureStatus.OCCUPIED_PENDING, reopened.find(k).orElseThrow().status,
                "the record must win over the stale abandoned line");
        assertEquals(new PopulationCounts(0, 1, 0, 0, 0, 0), reopened.counts());
        assertTrue(report.messages().stream().anyMatch(m -> m.contains("both in abandoned.keys")), report.messages().toString());

        assertTrue(reopened.saveIfDirty(), "the stale line is cleaned up at the next save");
        assertTrue(lines(dir.resolve(PopulationStore.ABANDONED_FILE)).isEmpty());
    }

    @Test
    void anAbandonedDecisionOverAnExistingRecordMovesItToTheKeyLog(@TempDir Path dir) throws IOException {
        StructureKey k = key(2, 2);
        PopulationStore store = loaded(dir);
        store.put(k, pending("Gone_1"));
        store.flush();

        store.put(k, StructureRecord.abandoned());
        assertTrue(store.nonAbandoned().isEmpty());
        assertTrue(store.findBot("Gone_1").isEmpty());
        assertEquals(new PopulationCounts(1, 0, 0, 0, 0, 0), store.counts());
        assertEquals(List.of(OVERWORLD + "|" + VILLAGE + "|2,2"), lines(dir.resolve(PopulationStore.ABANDONED_FILE)));

        store.flush();
        PopulationStore reopened = loaded(dir);
        assertEquals(StructureStatus.ABANDONED, reopened.find(k).orElseThrow().status);
        assertTrue(reopened.nonAbandoned().isEmpty());
    }

    @Test
    void removingAnAbandonedKeyRewritesTheKeyFileAtomically(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), StructureRecord.abandoned());
        store.put(key(2, 2), StructureRecord.abandoned());
        store.put(key(3, 3), StructureRecord.abandoned());

        assertTrue(store.remove(key(2, 2)));
        assertFalse(store.remove(key(2, 2)));
        assertTrue(store.find(key(2, 2)).isEmpty());
        assertEquals(2, lines(dir.resolve(PopulationStore.ABANDONED_FILE)).size());
        assertFalse(Files.exists(dir.resolve("abandoned.keys.tmp")));

        PopulationStore reopened = loaded(dir);
        assertTrue(reopened.find(key(1, 1)).isPresent());
        assertTrue(reopened.find(key(2, 2)).isEmpty());
        assertTrue(reopened.find(key(3, 3)).isPresent());

        assertTrue(store.saveIfDirty(), "remove marks the store dirty like put does");
    }

    @Test
    void removingARecordForgetsItsBotsAndPersists(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), populated("Victim_1", "Victim_2"));
        store.put(key(2, 2), pending("Bystander_3"));
        store.flush();

        assertTrue(store.remove(key(1, 1)));
        assertTrue(store.findBot("victim_1").isEmpty());
        assertTrue(store.findBot("victim_2").isEmpty());
        assertTrue(store.findBot("bystander_3").isPresent());
        assertTrue(store.saveIfDirty());

        PopulationStore reopened = loaded(dir);
        assertTrue(reopened.find(key(1, 1)).isEmpty());
        assertTrue(reopened.find(key(2, 2)).isPresent());
        assertEquals(new PopulationCounts(0, 1, 0, 0, 0, 0), reopened.counts());
    }

    @Test
    void removingAnUnknownKeyReturnsFalseAndLeavesTheStoreClean(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Only_1"));
        store.flush();
        Files.delete(dir.resolve(PopulationStore.POPULATIONS_FILE));

        assertFalse(store.remove(key(9, 9)));
        assertTrue(store.saveIfDirty());
        assertFalse(Files.exists(dir.resolve(PopulationStore.POPULATIONS_FILE)), "removing nothing must not dirty the store");
    }

    @Test
    void aRecordFlippedToAbandonedInPlaceIsDemotedToTheKeyLogAtSave(@TempDir Path dir) throws IOException {
        StructureKey k = key(4, 4);
        StructureRecord record = pending("Flip_1");
        PopulationStore store = loaded(dir);
        store.put(k, record);
        store.flush();

        record.status = StructureStatus.ABANDONED;
        store.markDirty();
        assertEquals(1, store.counts().abandoned(), "counted correctly even before the save");
        assertTrue(store.nonAbandoned().isEmpty());

        assertTrue(store.saveIfDirty());
        assertFalse(read(dir.resolve(PopulationStore.POPULATIONS_FILE)).contains("|4,4"));
        assertEquals(List.of(OVERWORLD + "|" + VILLAGE + "|4,4"), lines(dir.resolve(PopulationStore.ABANDONED_FILE)));
        assertTrue(store.findBot("Flip_1").isEmpty());

        assertEquals(StructureStatus.ABANDONED, loaded(dir).find(k).orElseThrow().status);
    }

    // ---------------------------------------------------------------- dirty tracking / saving

    @Test
    void saveIfDirtyIsANoOpWhenClean(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Clean_1"));
        assertTrue(store.saveIfDirty());
        Path main = dir.resolve(PopulationStore.POPULATIONS_FILE);
        assertTrue(Files.exists(main));

        Files.delete(main);
        assertTrue(store.saveIfDirty());
        assertFalse(Files.exists(main), "clean: nothing may be written");
        assertFalse(Files.exists(dir.resolve(PopulationStore.TEMP_FILE)));

        PopulationStore reopened = loaded(dir); // a load leaves the store clean as well
        assertTrue(reopened.saveIfDirty());
        assertFalse(Files.exists(main));
    }

    @Test
    void everyKindOfChangeMakesTheNextSaveWrite(@TempDir Path dir) throws IOException {
        Path main = dir.resolve(PopulationStore.POPULATIONS_FILE);
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Base_1"));
        store.flush();

        Files.delete(main);
        store.put(key(2, 2), pending("Put_2"));
        assertTrue(store.saveIfDirty());
        assertTrue(Files.exists(main), "put");

        Files.delete(main);
        assertTrue(store.remove(key(2, 2)));
        assertTrue(store.saveIfDirty());
        assertTrue(Files.exists(main), "remove");

        Files.delete(main);
        store.find(key(1, 1)).orElseThrow().attempts = 9;
        store.markDirty();
        assertTrue(store.saveIfDirty());
        assertTrue(Files.exists(main), "markDirty");
        assertEquals(9, loaded(dir).find(key(1, 1)).orElseThrow().attempts);

        Files.delete(main);
        assertFalse(store.decks().isDirty());
        store.decks().deck("height", 8);
        assertTrue(store.decks().isDirty());
        assertTrue(store.saveIfDirty());
        assertTrue(Files.exists(main), "deck change");
        assertFalse(store.decks().isDirty(), "a successful save clears the deck store's dirty flag too");

        Files.delete(main);
        assertTrue(store.saveIfDirty());
        assertFalse(Files.exists(main));
    }

    @Test
    void flushAlwaysWrites(@TempDir Path dir) throws IOException {
        Path main = dir.resolve(PopulationStore.POPULATIONS_FILE);
        PopulationStore store = loaded(dir);
        store.flush();
        assertTrue(Files.exists(main));
        assertTrue(Files.exists(dir.resolve(PopulationStore.ABANDONED_FILE)), "the pair is always created together");
        Files.delete(main);
        store.flush();
        assertTrue(Files.exists(main));
    }

    @Test
    void theSecondSaveKeepsTheFirstAsTheBackup(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("First_1"));
        store.flush();
        String first = read(dir.resolve(PopulationStore.POPULATIONS_FILE));
        assertFalse(Files.exists(dir.resolve(PopulationStore.BACKUP_FILE)));

        store.put(key(2, 2), pending("Second_2"));
        store.flush();
        assertEquals(first, read(dir.resolve(PopulationStore.BACKUP_FILE)));
        assertTrue(read(dir.resolve(PopulationStore.POPULATIONS_FILE)).contains("Second_2"));
        assertFalse(Files.exists(dir.resolve(PopulationStore.TEMP_FILE)));
    }

    @Test
    void aFailingSaveReturnsFalseKeepsTheStoreDirtyAndRecovers(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Blocked_1"));
        // A non-empty directory where the temp file must go makes the write fail on every platform.
        Path blocker = dir.resolve(PopulationStore.TEMP_FILE);
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("keep"), "x");

        assertFalse(store.saveIfDirty());
        assertTrue(store.lastError().isPresent());
        assertFalse(Files.exists(dir.resolve(PopulationStore.POPULATIONS_FILE)));
        assertDoesNotThrow(store::flush, "flush must never throw");
        assertTrue(store.find(key(1, 1)).isPresent(), "the in-memory state is unaffected");

        Files.delete(blocker.resolve("keep"));
        Files.delete(blocker);
        assertTrue(store.saveIfDirty(), "still dirty, so the next attempt writes");
        assertTrue(store.lastError().isEmpty());
        assertTrue(loaded(dir).find(key(1, 1)).isPresent());
    }

    @Test
    void anAbandonedAppendThatFailsIsRetriedAtTheNextSave(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), StructureRecord.abandoned());
        Path keys = dir.resolve(PopulationStore.ABANDONED_FILE);
        // Make appending impossible: swap the file for a directory.
        Files.delete(keys);
        Files.createDirectories(keys);
        Files.writeString(keys.resolve("keep"), "x");

        assertDoesNotThrow(() -> store.put(key(2, 2), StructureRecord.abandoned()));
        assertTrue(store.lastError().isPresent());
        assertEquals(StructureStatus.ABANDONED, store.find(key(2, 2)).orElseThrow().status, "still known in memory");
        assertFalse(store.saveIfDirty());

        Files.delete(keys.resolve("keep"));
        Files.delete(keys);
        assertTrue(store.saveIfDirty(), "the whole key list is rewritten from memory");
        assertEquals(2, lines(keys).size());
        PopulationStore reopened = loaded(dir);
        assertTrue(reopened.find(key(1, 1)).isPresent());
        assertTrue(reopened.find(key(2, 2)).isPresent());
    }

    @Test
    void nonFiniteNumbersDoNotFreezePersistence(@TempDir Path dir) {
        StructureRecord record = populated("Nan_1");
        record.roll = Double.NaN;
        record.occupiedChance = Double.POSITIVE_INFINITY;
        record.bots.get(0).x = Double.NEGATIVE_INFINITY;
        record.bots.get(0).yaw = Float.NaN;
        BotProfile.ItemSpec spec = new BotProfile.ItemSpec("minecraft:stick", 1, Map.of(), Double.NaN, null);
        record.bots.get(0).profile = new BotProfile(1, 1L, "x", new BotProfile.Loadout(
                List.of(new BotProfile.PlacedItem(BotProfile.Slot.HOTBAR, 0, spec))), null, null);

        PopulationStore store = loaded(dir);
        store.put(key(1, 1), record);
        assertTrue(store.saveIfDirty(), "a NaN must not make every future save fail");

        StructureRecord back = loaded(dir).find(key(1, 1)).orElseThrow();
        assertEquals(0.0, back.roll);
        assertEquals(0.0, back.occupiedChance);
        assertEquals(0.0, back.bots.get(0).x);
        assertEquals(0.0f, back.bots.get(0).yaw);
        assertEquals(0.0, back.bots.get(0).profile.loadout().items().get(0).spec().damageFraction());
    }

    @Test
    void decksAreRestoredWithTheirCyclePosition(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        SplitMix64 rng = new SplitMix64(99);
        Deck deck = store.decks().deck("healthFraction", 6);
        List<Integer> dealt = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            dealt.add(deck.draw(rng)); // 4 of 6 dealt: mid-cycle
        }
        store.decks().deck("other", 3); // a deck never drawn from
        assertTrue(store.saveIfDirty());

        PopulationStore reopened = loaded(dir);
        assertFalse(reopened.decks().isDirty(), "loading must not leave the deck store dirty");
        Deck restored = reopened.decks().deck("healthFraction", 6);
        assertEquals(4, restored.cursor());
        assertArrayEquals(deck.order(), restored.order());

        // The remaining two draws complete the permutation: every index appears exactly once per cycle.
        List<Integer> rest = List.of(restored.draw(rng), restored.draw(rng));
        List<Integer> cycle = new ArrayList<>(dealt);
        cycle.addAll(rest);
        assertEquals(List.of(0, 1, 2, 3, 4, 5), cycle.stream().sorted().toList());
        assertEquals(6, restored.cursor());
        assertEquals(3, reopened.decks().exportSnapshots().get("other").size);
    }

    // ---------------------------------------------------------------- bot lookup

    @Test
    void findBotIgnoresCase(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        StructureRecord record = populated("MixedCase_9", "second_2");
        store.put(key(1, 1), record);

        for (String probe : List.of("MixedCase_9", "mixedcase_9", "MIXEDCASE_9", "mIxEdCaSe_9")) {
            BotLocation location = store.findBot(probe).orElseThrow(() -> new AssertionError(probe));
            assertEquals(key(1, 1), location.structure());
            assertSame(record.bots.get(0), location.bot());
        }
        assertSame(record.bots.get(1), store.findBot("SECOND_2").orElseThrow().bot());
        assertTrue(store.findBot("nobody_1").isEmpty());
        assertTrue(store.findBot(null).isEmpty());
        assertTrue(store.findBot("").isEmpty());
    }

    @Test
    void findBotWorksAfterARestartAndIsCaseInsensitiveToo(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), populated("Persist_1"));
        store.flush();
        assertEquals(key(1, 1), loaded(dir).findBot("PERSIST_1").orElseThrow().structure());
    }

    @Test
    void findBotFollowsReplacementAndRemoval(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Old_1", "Old_2"));
        store.put(key(1, 1), pending("New_1")); // replaces: the old names must be forgotten
        assertTrue(store.findBot("old_1").isEmpty());
        assertTrue(store.findBot("old_2").isEmpty());
        assertTrue(store.findBot("new_1").isPresent());

        store.remove(key(1, 1));
        assertTrue(store.findBot("new_1").isEmpty());
    }

    @Test
    void anInPlaceRenameIsPickedUpAfterReindex(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        StructureRecord record = pending("Before_1", "Stays_2");
        store.put(key(1, 1), record);

        record.bots.get(0).name = "After_1"; // the engine renames a bot in place and announces it
        store.reindex(key(1, 1));

        // the new name is asked for FIRST, so only the announcement can explain the hit
        assertSame(record.bots.get(0), store.findBot("AFTER_1").orElseThrow().bot());
        assertTrue(store.findBot("before_1").isEmpty());
        assertSame(record.bots.get(1), store.findBot("stays_2").orElseThrow().bot());
    }

    @Test
    void aStaleNameIsNeverAnsweredEvenIfTheRenameWasNotAnnounced(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        StructureRecord record = pending("Before_1");
        store.put(key(1, 1), record);

        record.bots.get(0).name = "After_1"; // no reindex()
        assertTrue(store.findBot("before_1").isEmpty(), "a name that no longer belongs to the bot must not resolve to it");
    }

    @Test
    void aBotAddedInPlaceIsFoundAfterReindexOrPutOfTheSameInstance(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        StructureRecord record = pending("Existing_1");
        store.put(key(1, 1), record);

        record.bots.add(new BotRecord(1, "Added_2", 7));
        store.reindex(key(1, 1));
        assertTrue(store.findBot("added_2").isPresent());

        record.bots.add(new BotRecord(2, "Added_3", 8));
        store.put(key(1, 1), record);
        assertTrue(store.findBot("added_3").isPresent());
        assertTrue(store.findBot("existing_1").isPresent());
    }

    @Test
    void reindexOfAnUnknownKeyIsHarmless(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        assertDoesNotThrow(() -> store.reindex(key(1, 1)));
        assertDoesNotThrow(store::reindexAll);
    }

    @Test
    void aDuplicateNameKeepsTheFirstAndTheSecondTakesOverWhenTheFirstGoes(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Twin_1"));
        store.put(key(2, 2), pending("Twin_1"));
        assertEquals(key(1, 1), store.findBot("twin_1").orElseThrow().structure());
        store.remove(key(1, 1));
        assertEquals(key(2, 2), store.findBot("twin_1").orElseThrow().structure());
    }

    // ---------------------------------------------------------------- counts

    @Test
    void countsCoverEveryStatusAndBotState(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), StructureRecord.abandoned());
        store.put(key(2, 2), StructureRecord.abandoned());
        store.put(key(3, 3), pending("P_1", "P_2"));
        StructureRecord mixed = populated("M_1", "M_2", "M_3");
        mixed.bots.get(2).state = BotState.FAILED;
        store.put(key(4, 4), mixed);
        store.put(key(5, 5), gaveUp());

        PopulationCounts counts = store.counts();
        assertEquals(2, counts.abandoned());
        assertEquals(1, counts.pending());
        assertEquals(1, counts.populated());
        assertEquals(1, counts.gaveUp());
        assertEquals(2, counts.botsSpawned());
        assertEquals(2, counts.botsFailed()); // one in `mixed`, one in the gave-up record
        assertEquals(5, counts.structures());
    }

    @Test
    void countsSeeInPlaceStateChanges(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        StructureRecord record = pending("A_1", "B_2");
        store.put(key(1, 1), record);
        assertEquals(0, store.counts().botsSpawned());
        record.bots.get(0).state = BotState.SPAWNED;
        record.bots.get(1).state = BotState.FAILED;
        record.status = StructureStatus.POPULATED;
        assertEquals(new PopulationCounts(0, 0, 1, 0, 1, 1), store.counts());
    }

    // ---------------------------------------------------------------- nearby / nonAbandoned

    @Test
    void nearbyIsNearestFirstWithTiesByKeyAndIncludesAbandoned(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        StructureRecord live = pending("Near_1");
        store.put(key(VILLAGE, 1, 1), live);                                     // d2 = 2
        store.put(key(VILLAGE, 0, 3), StructureRecord.abandoned());              // d2 = 9
        store.put(key("minecraft:igloo", 3, 0), StructureRecord.abandoned());    // d2 = 9, ties with the line above
        store.put(key(VILLAGE, 3, 4), pending("Far_2"));                         // d2 = 25
        store.put(key(VILLAGE, -5, 0), StructureRecord.abandoned());             // d2 = 25, ties with the line above
        store.put(key(VILLAGE, 7, 7), StructureRecord.abandoned());              // Chebyshev 7, Euclid 9.9
        store.put(key(VILLAGE, 9, 0), StructureRecord.abandoned());              // Chebyshev 9: outside radius 8
        store.put(new StructureKey(NETHER, VILLAGE, 1, 1), StructureRecord.abandoned()); // other dimension
        store.put(key(VILLAGE, 0, 0), StructureRecord.abandoned());              // d2 = 0

        List<Map.Entry<StructureKey, StructureRecord>> result = store.nearby(OVERWORLD, 0, 0, 8);
        List<String> order = result.stream().map(e -> e.getKey().asString()).toList();
        assertEquals(List.of(
                OVERWORLD + "|" + VILLAGE + "|0,0",
                OVERWORLD + "|" + VILLAGE + "|1,1",
                OVERWORLD + "|minecraft:igloo|3,0",     // "minecraft:igloo" < "minecraft:village_plains"
                OVERWORLD + "|" + VILLAGE + "|0,3",
                OVERWORLD + "|" + VILLAGE + "|-5,0",    // "-5,0" < "3,4" as text
                OVERWORLD + "|" + VILLAGE + "|3,4",
                OVERWORLD + "|" + VILLAGE + "|7,7"), order);
        assertSame(live, result.get(1).getValue(), "records of populated structures are the live objects");
        assertEquals(StructureStatus.ABANDONED, result.get(0).getValue().status);
        assertEquals(StructureStatus.OCCUPIED_PENDING, result.get(5).getValue().status);
    }

    @Test
    void nearbyEdgeCases(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(0, 0), StructureRecord.abandoned());
        store.put(key(VILLAGE, -1, -1), pending("Neg_1"));

        assertEquals(1, store.nearby(OVERWORLD, 0, 0, 0).size(), "radius 0 is exactly the chunk itself");
        assertEquals(2, store.nearby(OVERWORLD, 0, 0, 1).size());
        assertTrue(store.nearby(OVERWORLD, 0, 0, -1).isEmpty());
        assertTrue(store.nearby("minecraft:the_end", 0, 0, 100).isEmpty());
        assertTrue(store.nearby(null, 0, 0, 100).isEmpty());
        assertEquals(2, store.nearby(OVERWORLD, 0, 0, Integer.MAX_VALUE).size(), "a huge radius must not overflow or hang");
        // |0 - MIN| = 2^31 exceeds a radius of MAX: must be excluded, not wrapped around by int overflow
        assertTrue(store.nearby(OVERWORLD, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE).isEmpty());
        // (0,0) is exactly MAX away on x; (-1,-1) is one further
        assertEquals(1, store.nearby(OVERWORLD, Integer.MAX_VALUE, 0, Integer.MAX_VALUE).size());
    }

    @Test
    void nonAbandonedIsAStableCopyInStorageOrder(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(3, 3), pending("C_3"));
        store.put(key(1, 1), StructureRecord.abandoned());
        store.put(key(2, 2), populated("B_2"));
        store.put(key(1, 2), pending("A_1"));

        List<Map.Entry<StructureKey, StructureRecord>> snapshot = store.nonAbandoned();
        assertEquals(List.of(key(3, 3), key(2, 2), key(1, 2)), snapshot.stream().map(Map.Entry::getKey).toList());

        // Mutating the store while the engine iterates the snapshot must be safe and must not change it.
        for (Map.Entry<StructureKey, StructureRecord> e : snapshot) {
            store.remove(e.getKey());
            store.put(key(50 + e.getKey().chunkX(), 0), pending("Later_" + e.getKey().chunkX()));
        }
        assertEquals(3, snapshot.size());
        assertEquals(3, store.nonAbandoned().size());
        assertTrue(store.nonAbandoned().stream().noneMatch(e -> e.getKey().equals(key(3, 3))));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get(0).setValue(new StructureRecord()));
    }

    // ---------------------------------------------------------------- odd identifiers

    @Test
    void namespacedSlashedAndUnicodeIdentifiersRoundTrip(@TempDir Path dir) {
        List<StructureKey> keys = List.of(
                new StructureKey("mod:some/dim.name-1", "namespace:path/with/slashes/and.dots-and_underscores", 10, -10),
                new StructureKey("minecraft:overworld", "modid:str\u00fckt\u00fcr/\u65e5\u672c\u8a9e", 0, 0),
                new StructureKey("d\u00e9j\u00e0:vu", "emoji:\uD83D\uDE00/\uD83C\uDFF0", -1, 1),
                new StructureKey("minecraft:overworld", "a:b", Integer.MIN_VALUE, Integer.MAX_VALUE),
                new StructureKey("minecraft:overworld", "a:b", Integer.MAX_VALUE, Integer.MIN_VALUE),
                new StructureKey("x:y", "a:b,c", 5, 5),   // a comma inside an id
                new StructureKey("", "", 0, 0));           // degenerate, but must not corrupt anything

        PopulationStore store = loaded(dir);
        for (int i = 0; i < keys.size(); i++) {
            store.put(keys.get(i), i % 2 == 0 ? StructureRecord.abandoned() : pending("Odd_" + i));
        }
        store.flush();

        PopulationStore reopened = loaded(dir);
        for (int i = 0; i < keys.size(); i++) {
            StructureKey k = keys.get(i);
            StructureRecord r = reopened.find(k).orElseThrow(() -> new AssertionError("lost " + k));
            assertEquals(i % 2 == 0 ? StructureStatus.ABANDONED : StructureStatus.OCCUPIED_PENDING, r.status, k.toString());
            assertEquals(1, reopened.nearby(k.dimension(), k.chunkX(), k.chunkZ(), 0).stream()
                    .filter(e -> e.getKey().equals(k)).count(), "nearby must see " + k);
        }
        assertEquals(keys.size(), reopened.counts().structures());
    }

    @Test
    void keysThatCouldNotBePersistedUnambiguouslyAreRefusedUpFront(@TempDir Path dir) {
        PopulationStore store = loaded(dir);
        for (StructureKey bad : List.of(
                new StructureKey("minecraft:over|world", "a:b", 0, 0),
                new StructureKey("minecraft:overworld", "a:b|c", 0, 0),
                new StructureKey("minecraft:overworld", "a:b\nc", 0, 0),
                new StructureKey("minecraft:overworld", "a:b\rc", 0, 0),
                new StructureKey("minecraft:overworld", "a:\uD83D", 0, 0),
                new StructureKey("minecraft:overworld", "a:\uDE00b", 0, 0))) {
            assertThrows(IllegalArgumentException.class, () -> store.put(bad, StructureRecord.abandoned()), bad.toString());
            assertThrows(IllegalArgumentException.class, () -> store.put(bad, pending("X_1")), bad.toString());
        }
        assertEquals(0, store.counts().structures());
        assertThrows(NullPointerException.class, () -> store.put(null, pending("X_1")));
        assertThrows(NullPointerException.class, () -> store.put(key(1, 1), null));
    }

    // ---------------------------------------------------------------- thread model

    @Test
    void aStrayThreadCannotCorruptTheIndexes(@TempDir Path dir) throws Exception {
        PopulationStore store = loaded(dir);
        int threads = 6;
        int perThread = 300;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    int x = id * 1000 + i;
                    if (i % 3 == 0) {
                        store.put(key(x, 0), StructureRecord.abandoned());
                    } else {
                        store.put(key(x, 0), pending("T" + id + "_" + i));
                    }
                    store.find(key(x, 0));
                    store.findBot("t" + id + "_" + i);
                    store.nearby(OVERWORLD, x, 0, 20);
                    store.counts();
                    store.nonAbandoned();
                    if (i % 50 == 0) {
                        store.saveIfDirty();
                    }
                    if (i % 7 == 0) {
                        store.remove(key(x - 1, 0));
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        store.flush();
        // every survivor is consistent between find, counts, nearby and the bot index
        PopulationCounts counts = store.counts();
        int surviving = counts.structures();
        assertEquals(surviving, store.nearby(OVERWORLD, 3000, 0, 100_000).size());
        assertEquals(counts.abandoned() + store.nonAbandoned().size(), surviving);
        for (Map.Entry<StructureKey, StructureRecord> e : store.nonAbandoned()) {
            String name = e.getValue().bots.get(0).name;
            assertEquals(e.getKey(), store.findBot(name).orElseThrow().structure());
        }
        PopulationStore reopened = loaded(dir);
        assertEquals(counts, reopened.counts());
    }

    // ---------------------------------------------------------------- misc

    @Test
    void aDirectoryPathThatIsAFileMakesTheStoreUnusable(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("not_a_directory");
        Files.writeString(file, "x");
        PopulationStore store = new PopulationStore(file);
        PopulationStore.LoadReport report = store.load();
        assertFalse(report.usable());
        assertFalse(store.usable());
        assertTrue(report.messages().stream().anyMatch(m -> m.contains("UNUSABLE")));
        store.put(key(1, 1), pending("Ignored_1"));
        assertTrue(store.find(key(1, 1)).isEmpty());
        assertFalse(store.saveIfDirty());
    }

    @Test
    void findOfNullIsEmptyAndDoesNotThrow(@TempDir Path dir) {
        assertEquals(Optional.empty(), loaded(dir).find(null));
    }
}
