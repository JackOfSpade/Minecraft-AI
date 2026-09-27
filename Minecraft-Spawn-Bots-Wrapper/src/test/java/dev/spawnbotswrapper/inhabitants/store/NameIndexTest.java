package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.engine.PopulationView.BotLocation;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class NameIndexTest {

    private final Map<StructureKey, StructureRecord> records = new LinkedHashMap<>();
    private final NameIndex index = new NameIndex();

    private StructureRecord store(StructureKey key, StructureRecord record) {
        records.put(key, record);
        index.index(key, record);
        return record;
    }

    @Test
    void findIsCaseInsensitiveAndReturnsTheLiveRecord() {
        StructureRecord record = store(key(1, 1), pending("Steve_9"));
        BotLocation hit = index.find("sTeVe_9", records);
        assertNotNull(hit);
        assertEquals(key(1, 1), hit.structure());
        assertSame(record.bots.get(0), hit.bot());
        assertNull(index.find("nobody", records));
        assertNull(index.find(null, records));
    }

    @Test
    void namesWithSpecialCasingRulesAreLowercasedLocaleIndependently() {
        // Turkish dotted/dotless i must not change how ASCII names are matched.
        store(key(1, 1), pending("TITLE_1"));
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertNotNull(index.find("title_1", records));
            assertNotNull(index.find("TITLE_1", records));
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void unindexForgetsExactlyThatStructuresNames() {
        store(key(1, 1), pending("One_1", "One_2"));
        store(key(2, 2), pending("Two_1"));
        index.unindex(key(1, 1));
        records.remove(key(1, 1));
        assertNull(index.find("one_1", records));
        assertNull(index.find("one_2", records));
        assertNotNull(index.find("two_1", records));
    }

    @Test
    void botsWithoutANameAreSkipped() {
        StructureRecord record = pending("Named_1");
        record.bots.add(new BotRecord());
        store(key(1, 1), record);
        assertNotNull(index.find("named_1", records));
        assertEquals(0, index.clashes());
    }

    @Test
    void aStaleEntryIsNeverAnswered() {
        StructureRecord record = store(key(1, 1), pending("Old_1"));
        record.bots.get(0).name = "Renamed_1"; // renamed in place, index not told
        assertNull(index.find("old_1", records), "the old name no longer belongs to anyone");
        assertNotNull(index.find("renamed_1", records), "the lookup that found staleness rebuilt the index");

        StructureRecord removed = store(key(2, 2), pending("Gone_1"));
        records.remove(key(2, 2)); // structure vanished without unindex
        assertNull(index.find("gone_1", records));

        StructureRecord shrunk = store(key(3, 3), pending("Dropped_1", "Kept_2"));
        shrunk.bots.remove(0);
        assertNull(index.find("dropped_1", records));
        assertNotNull(index.find("kept_2", records));
        assertNotNull(removed);
    }

    @Test
    void reindexPicksUpRenamesAdditionsAndRemovals() {
        StructureRecord record = store(key(1, 1), pending("A_1", "B_2"));
        record.bots.get(0).name = "A_renamed";
        record.bots.add(new BotRecord(2, "C_3", 3));
        record.bots.remove(1);
        index.reindex(key(1, 1), record);
        assertNotNull(index.find("a_renamed", records));
        assertNotNull(index.find("c_3", records));
        assertNull(index.find("b_2", records));
        assertNull(index.find("a_1", records));
    }

    @Test
    void duplicateNamesAreCountedFirstWinsAndTheOtherTakesOverWhenTheFirstGoes() {
        store(key(1, 1), pending("Twin_1"));
        store(key(2, 2), pending("TWIN_1"));
        assertEquals(1, index.clashes());
        assertEquals(java.util.List.of("TWIN_1"), index.clashExamples());
        assertEquals(key(1, 1), index.find("twin_1", records).structure());

        index.unindex(key(1, 1));
        records.remove(key(1, 1));
        assertEquals(key(2, 2), index.find("twin_1", records).structure());
    }

    @Test
    void rebuildStartsFromScratch() {
        store(key(1, 1), pending("Before_1"));
        Map<StructureKey, StructureRecord> other = new LinkedHashMap<>();
        other.put(key(9, 9), pending("After_9"));
        index.rebuild(other);
        assertNull(index.find("before_1", other));
        assertNotNull(index.find("after_9", other));
        assertEquals(0, index.clashes());
    }

    @Test
    void manyMissesDoNotDisturbTheIndex() {
        store(key(1, 1), pending("Real_1"));
        for (int i = 0; i < 1000; i++) {
            assertNull(index.find("probe_" + i, records));
        }
        assertNotNull(index.find("real_1", records));
    }
}
