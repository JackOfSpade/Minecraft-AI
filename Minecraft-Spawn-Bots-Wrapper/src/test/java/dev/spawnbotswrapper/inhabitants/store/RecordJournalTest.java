package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write-ahead journal that makes a removal durable without a full save (see {@link RecordJournal}): a journaled record
 * survives a crash, an already-folded journal is never replayed over newer data, a torn last line is never trusted, and
 * one journal append is far cheaper than a full save (the numbers are printed).
 */
class RecordJournalTest {
    private static BotSnapshot fullSnapshot(int seed) {
        BotSnapshot s = new BotSnapshot();
        s.health = 14.5f;
        s.foodLevel = 17;
        s.saturation = 3.5f;
        s.xpLevel = seed % 30;
        for (int slot : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 36, 37, 38, 39, 40}) {
            s.stacks.add(new BotSnapshot.Entry(slot, "{\"id\":\"minecraft:diamond_sword\",\"count\":1,\"components\":"
                    + "{\"minecraft:damage\":" + (seed % 100) + ",\"minecraft:enchantments\":{\"minecraft:sharpness\":4,"
                    + "\"minecraft:unbreaking\":3}}}"));
        }
        s.effects.add("{\"id\":\"minecraft:speed\",\"amplifier\":1,\"duration\":1200}");
        return s;
    }

    private static PopulationStore storeWith(Path dir, int structures) {
        PopulationStore store = new PopulationStore(dir);
        store.load();
        for (int i = 0; i < structures; i++) {
            StructureRecord r = populated("Bot_" + (3 * i), "Bot_" + (3 * i + 1), "Bot_" + (3 * i + 2));
            store.put(key(VILLAGE, i, i * 2), r);
        }
        assertTrue(store.saveIfDirty());
        return store;
    }

    @Test
    void aJournaledRecordSurvivesACrashWithoutAFullSave(@TempDir Path dir) {
        PopulationStore store = storeWith(dir, 5);
        StructureKey key = key(VILLAGE, 2, 4);
        BotRecord bot = store.find(key).orElseThrow().bots.get(0);
        bot.state = BotState.DORMANT;
        bot.seen = true;
        bot.removing = true;
        bot.snapshot = fullSnapshot(7);
        store.markDirty();
        assertTrue(store.journal(List.of(key)));

        // the process dies: a new store on the same directory sees the journaled record, the rest as saved
        PopulationStore after = new PopulationStore(dir);
        PopulationStore.LoadReport report = after.load();
        assertTrue(report.usable());
        assertTrue(report.messages().stream().anyMatch(m -> m.contains("replayed 1 record(s)")), report.messages().toString());
        BotRecord back = after.find(key).orElseThrow().bots.get(0);
        assertEquals(BotState.DORMANT, back.state);
        assertTrue(back.removing);
        assertTrue(back.seen);
        assertEquals(fullSnapshot(7), back.snapshot);
        assertEquals(3, after.find(key(VILLAGE, 1, 2)).orElseThrow().bots.size(), "the other records are as they were saved");
    }

    @Test
    void aFullSaveFoldsTheJournalInAndAStaleJournalIsNeverReplayedOverNewerData(@TempDir Path dir) throws IOException {
        PopulationStore store = storeWith(dir, 3);
        StructureKey key = key(VILLAGE, 1, 2);
        StructureRecord rec = store.find(key).orElseThrow();
        rec.bots.get(0).removing = true;
        store.markDirty();
        assertTrue(store.journal(List.of(key)));
        Path journal = dir.resolve(RecordJournal.FILE);
        byte[] stale = Files.readAllBytes(journal);

        // later the mark is cleared and a full save is made: it holds the newer state and the journal is gone
        rec.bots.get(0).removing = false;
        rec.bots.get(0).state = BotState.DEAD;
        store.markDirty();
        assertTrue(store.saveIfDirty());
        assertFalse(Files.exists(journal), "everything the journal held is in the file now");

        // a crash between the file write and the journal delete would have left the old journal behind: it must be ignored
        Files.write(journal, stale);
        PopulationStore after = new PopulationStore(dir);
        assertTrue(after.load().usable());
        BotRecord back = after.find(key).orElseThrow().bots.get(0);
        assertEquals(BotState.DEAD, back.state, "the newer state stands");
        assertFalse(back.removing);
    }

    @Test
    void aTornLastLineIsNeverTrustedAndTheJournalKeepsWorking(@TempDir Path dir) throws IOException {
        PopulationStore store = storeWith(dir, 3);
        StructureKey a = key(VILLAGE, 0, 0);
        StructureKey b = key(VILLAGE, 1, 2);
        store.find(a).orElseThrow().bots.get(0).removing = true;
        assertTrue(store.journal(List.of(a)));
        store.find(b).orElseThrow().bots.get(0).removing = true;
        assertTrue(store.journal(List.of(b)));
        Path journal = dir.resolve(RecordJournal.FILE);
        byte[] all = Files.readAllBytes(journal);
        Files.write(journal, java.util.Arrays.copyOf(all, all.length - 40)); // the crash cut the second line short

        PopulationStore after = new PopulationStore(dir);
        PopulationStore.LoadReport report = after.load();
        assertTrue(report.usable(), report.messages().toString());
        assertTrue(after.find(a).orElseThrow().bots.get(0).removing, "the complete line was replayed");
        assertFalse(after.find(b).orElseThrow().bots.get(0).removing, "the torn line was not");
        // and the next append lands on a clean line
        after.find(b).orElseThrow().bots.get(1).removing = true;
        assertTrue(after.journal(List.of(b)));
        PopulationStore third = new PopulationStore(dir);
        assertTrue(third.load().usable());
        assertTrue(third.find(a).orElseThrow().bots.get(0).removing);
        assertTrue(third.find(b).orElseThrow().bots.get(1).removing);
    }

    @Test
    void aRecordThatWasRemovedFromTheStoreIsSimplyNotJournaled(@TempDir Path dir) {
        PopulationStore store = storeWith(dir, 2);
        assertTrue(store.journal(List.of(key(VILLAGE, 99, 99))));
        assertFalse(Files.exists(dir.resolve(RecordJournal.FILE)));
    }

    /**
     * Review fix (f), measured: a batch of sleeps of realistic seen bots (full inventories) costs one small journal append, not a
     * rewrite of the whole store. 64 seen bots with full saved states in 22 structures, among 2000 structures.
     */
    @Test
    void oneJournalAppendIsMuchCheaperThanAFullSave(@TempDir Path dir) throws IOException {
        PopulationStore store = storeWith(dir, 2000);
        List<StructureKey> seenStructures = new ArrayList<>();
        int bot = 0;
        for (int i = 0; i < 22 && bot < 64; i++) {
            StructureKey key = key(VILLAGE, i, i * 2);
            seenStructures.add(key);
            for (BotRecord b : store.find(key).orElseThrow().bots) {
                if (bot++ < 64) {
                    b.seen = true;
                    b.state = BotState.DORMANT;
                    b.removing = true;
                    b.snapshot = fullSnapshot(bot);
                }
            }
        }
        store.markDirty();
        for (int i = 0; i < 3; i++) {
            store.journal(seenStructures); // warm up
            store.saveIfDirty();
            store.markDirty();
        }
        int runs = 10;
        long journalNanos = 0;
        long fullNanos = 0;
        long oneNanos = 0;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            assertTrue(store.journal(seenStructures));
            journalNanos += System.nanoTime() - t0;
            t0 = System.nanoTime();
            assertTrue(store.journal(List.of(seenStructures.get(0))));
            oneNanos += System.nanoTime() - t0;
            store.markDirty();
            t0 = System.nanoTime();
            assertTrue(store.saveIfDirty());
            fullNanos += System.nanoTime() - t0;
        }
        store.journal(seenStructures);
        long journalBytes = Files.size(dir.resolve(RecordJournal.FILE));
        double journalMs = journalNanos / 1e6 / runs;
        double oneMs = oneNanos / 1e6 / runs;
        double fullMs = fullNanos / 1e6 / runs;
        System.out.printf("removal durability: 64 seen bots with full inventories in %d structures, %d structures stored: "
                        + "one journal append of the whole lot %.1f ms (%d KB), of one structure %.1f ms, "
                        + "a full save of the store %.1f ms (%d KB)%n",
                seenStructures.size(), 2000, journalMs, journalBytes / 1024, oneMs, fullMs,
                Files.size(dir.resolve(PopulationStore.POPULATIONS_FILE)) / 1024);
        assertTrue(journalMs < fullMs, "a journal append is cheaper than a full save: " + journalMs + " vs " + fullMs);
        assertNotNull(store.find(seenStructures.get(0)));
    }

    @Test
    void theJournalLineFormatIsSelfContained(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("j");
        StructureRecord r = populated("A_1", "B_2");
        RecordJournal.append(file, 7, new java.util.LinkedHashMap<>(java.util.Map.of(key(VILLAGE, 1, 2), r)));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("7\t" + key(VILLAGE, 1, 2).asString() + "\t{"));
        assertEquals(1, text.chars().filter(c -> c == '\n').count(), "one line per record");
        RecordJournal.Loaded read = RecordJournal.read(file, 6);
        assertEquals(1, read.entries().size());
        assertEquals(7, read.maxSeq());
        assertEquals(0, RecordJournal.read(file, 7).entries().size(), "lines at or below the folded sequence number are skipped");
    }
}
