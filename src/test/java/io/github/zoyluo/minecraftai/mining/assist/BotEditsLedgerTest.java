package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotEditsLedgerTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final int CAP = BotEditsLedger.PLACED_CAP_PER_DIMENSION;

    private static BlockPos cell(int i) {
        return new BlockPos(i, 64, -i);
    }

    // ---- placed ledger -------------------------------------------------------------------------

    @Test
    void capsMatchTheDesign() {
        assertEquals(8192, BotEditsLedger.PLACED_CAP_PER_DIMENSION);
        assertEquals(8192, BotEditsLedger.DUG_CAP_PER_BOT);
        assertEquals(1, BotEditsLedger.VERSION);
        assertEquals(1200, BotEditsLedger.SNAPSHOT_MIN_INTERVAL_TICKS);
        assertEquals("mining_assist_edits.json", BotEditsLedger.SIDECAR_FILE_NAME);
    }

    @Test
    void newLedgerIsEmptyCleanAndNotDirty() {
        BotEditsLedger ledger = new BotEditsLedger();

        assertTrue(ledger.loadedCleanly());
        assertEquals("", ledger.loadProblem());
        assertFalse(ledger.isDirty());
        assertEquals(0, ledger.size(OVERWORLD));
        assertFalse(ledger.wasPlaced(OVERWORLD, cell(1)));
    }

    @Test
    void placedCellsAreRememberedPerDimension() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.notePlaced(OVERWORLD, new BlockPos(1, 2, 3));
        ledger.notePlaced(NETHER, new BlockPos(4, 5, 6));

        assertTrue(ledger.wasPlaced(OVERWORLD, new BlockPos(1, 2, 3)));
        assertFalse(ledger.wasPlaced(NETHER, new BlockPos(1, 2, 3)));
        assertTrue(ledger.wasPlaced(NETHER, new BlockPos(4, 5, 6)));
        assertFalse(ledger.wasPlaced(OVERWORLD, new BlockPos(4, 5, 6)));
        assertFalse(ledger.wasPlaced("minecraft:the_end", new BlockPos(1, 2, 3)));
        assertEquals(1, ledger.size(OVERWORLD));
        assertEquals(1, ledger.size(NETHER));
        assertEquals(0, ledger.size("minecraft:the_end"));
    }

    @Test
    void packedAndBlockPosOverloadsAgree() {
        BotEditsLedger ledger = new BotEditsLedger();
        BlockPos pos = new BlockPos(-30, -60, 1234);
        ledger.notePlaced(OVERWORLD, pos.asLong());

        assertTrue(ledger.wasPlaced(OVERWORLD, pos));
        assertTrue(ledger.wasPlaced(OVERWORLD, pos.asLong()));
        assertFalse(ledger.wasPlaced(OVERWORLD, pos.up()));
    }

    @Test
    void nullAndBlankArgumentsAreIgnoredWithoutThrowing() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.notePlaced(null, cell(1));
        ledger.notePlaced("", cell(1));
        ledger.notePlaced("   ", cell(1));
        ledger.notePlaced(OVERWORLD, (BlockPos) null);

        assertFalse(ledger.isDirty());
        assertFalse(ledger.wasPlaced(null, cell(1)));
        assertFalse(ledger.wasPlaced(OVERWORLD, (BlockPos) null));
        assertEquals(0, ledger.size(null));
        assertEquals("{\"version\":1,\"dimensions\":{}}", ledger.toJson());
    }

    @Test
    void placedLedgerEvictsTheOldestCellAtTheCap() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < CAP; i++) {
            ledger.notePlaced(OVERWORLD, cell(i));
        }
        assertEquals(CAP, ledger.size(OVERWORLD));
        assertTrue(ledger.wasPlaced(OVERWORLD, cell(0)));

        ledger.notePlaced(OVERWORLD, cell(CAP));

        assertEquals(CAP, ledger.size(OVERWORLD));
        assertFalse(ledger.wasPlaced(OVERWORLD, cell(0)));
        assertTrue(ledger.wasPlaced(OVERWORLD, cell(1)));
        assertTrue(ledger.wasPlaced(OVERWORLD, cell(CAP)));
    }

    @Test
    void capIsPerDimensionNotGlobal() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < CAP + 10; i++) {
            ledger.notePlaced(OVERWORLD, cell(i));
        }
        ledger.notePlaced(NETHER, cell(0));

        assertEquals(CAP, ledger.size(OVERWORLD));
        assertEquals(1, ledger.size(NETHER));
        assertTrue(ledger.wasPlaced(NETHER, cell(0)));
    }

    @Test
    void renotingACellRefreshesItsRecency() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < CAP; i++) {
            ledger.notePlaced(OVERWORLD, cell(i));
        }
        ledger.notePlaced(OVERWORLD, cell(0));
        ledger.notePlaced(OVERWORLD, cell(CAP));

        assertEquals(CAP, ledger.size(OVERWORLD));
        assertTrue(ledger.wasPlaced(OVERWORLD, cell(0)), "refreshed cell survives");
        assertFalse(ledger.wasPlaced(OVERWORLD, cell(1)), "next-oldest cell was evicted instead");
        assertTrue(ledger.wasPlaced(OVERWORLD, cell(CAP)));
    }

    @Test
    void renotingDoesNotGrowTheLedger() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 20; i++) {
                ledger.notePlaced(OVERWORLD, cell(i));
            }
        }

        assertEquals(20, ledger.size(OVERWORLD));
    }

    @Test
    void wasPlacedIsAPureQueryThatNeitherRefreshesNorDirties() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < CAP; i++) {
            ledger.notePlaced(OVERWORLD, cell(i));
        }
        ledger.markClean();

        for (int i = 0; i < 50; i++) {
            assertTrue(ledger.wasPlaced(OVERWORLD, cell(0)));
        }
        assertFalse(ledger.isDirty());

        ledger.notePlaced(OVERWORLD, cell(CAP));
        assertFalse(ledger.wasPlaced(OVERWORLD, cell(0)), "queries never protected cell 0 from eviction");
    }

    // ---- dirty flag ----------------------------------------------------------------------------

    @Test
    void dirtyFlagFollowsChangesAndMarkClean() {
        BotEditsLedger ledger = new BotEditsLedger();
        assertFalse(ledger.isDirty());

        ledger.notePlaced(OVERWORLD, cell(1));
        assertTrue(ledger.isDirty());

        ledger.markClean();
        assertFalse(ledger.isDirty());

        ledger.notePlaced(OVERWORLD, cell(2));
        assertTrue(ledger.isDirty());
    }

    @Test
    void renotingTheNewestCellChangesNothing() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.notePlaced(OVERWORLD, cell(1));
        ledger.notePlaced(OVERWORLD, cell(2));
        ledger.markClean();

        ledger.notePlaced(OVERWORLD, cell(2));

        assertFalse(ledger.isDirty());
    }

    @Test
    void renotingAnOlderCellReordersSoItDirties() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.notePlaced(OVERWORLD, cell(1));
        ledger.notePlaced(OVERWORLD, cell(2));
        ledger.markClean();

        ledger.notePlaced(OVERWORLD, cell(1));

        assertTrue(ledger.isDirty());
        assertEquals("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":["
                + cell(2).asLong() + "," + cell(1).asLong() + "]}}", ledger.toJson());
    }

    @Test
    void snapshotIsDueOnlyWhenDirtyAndTheIntervalElapsed() {
        BotEditsLedger ledger = new BotEditsLedger();
        assertFalse(ledger.snapshotDue(100_000L, 0L), "clean ledger is never due");

        ledger.notePlaced(OVERWORLD, cell(1));
        assertFalse(ledger.snapshotDue(1199L, 0L));
        assertTrue(ledger.snapshotDue(1200L, 0L));
        assertTrue(ledger.snapshotDue(5000L, 100L));
        assertFalse(ledger.snapshotDue(1299L, 100L));

        ledger.markClean();
        assertFalse(ledger.snapshotDue(100_000L, 0L));
    }

    // ---- codec ---------------------------------------------------------------------------------

    @Test
    void emptyLedgerSerialisesToTheMinimalVersionedDocument() {
        assertEquals("{\"version\":1,\"dimensions\":{}}", new BotEditsLedger().toJson());
    }

    @Test
    void jsonIsStableSortedByDimensionAndOldestFirst() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.notePlaced(OVERWORLD, 30L);
        ledger.notePlaced(NETHER, 5L);
        ledger.notePlaced(OVERWORLD, 10L);
        ledger.notePlaced(OVERWORLD, 20L);

        String expected = "{\"version\":1,\"dimensions\":{"
                + "\"minecraft:overworld\":[30,10,20],"
                + "\"minecraft:the_nether\":[5]}}";
        assertEquals(expected, ledger.toJson());
        assertEquals(expected, ledger.toJson(), "serialising twice gives the same string");
    }

    @Test
    void jsonDoesNotDependOnDimensionInsertionOrder() {
        BotEditsLedger first = new BotEditsLedger();
        first.notePlaced(OVERWORLD, 1L);
        first.notePlaced(NETHER, 2L);
        BotEditsLedger second = new BotEditsLedger();
        second.notePlaced(NETHER, 2L);
        second.notePlaced(OVERWORLD, 1L);

        assertEquals(first.toJson(), second.toJson());
    }

    @Test
    void jsonEscapesDimensionKeys() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.notePlaced("weird\"key\\x", 7L);

        BotEditsLedger loaded = BotEditsLedger.fromJson(ledger.toJson());

        assertTrue(loaded.loadedCleanly());
        assertTrue(loaded.wasPlaced("weird\"key\\x", 7L));
    }

    @Test
    void roundTripPreservesCellsDimensionsAndOrder() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < 300; i++) {
            ledger.notePlaced(OVERWORLD, new BlockPos(i * 7 - 1000, 60 - i % 50, 900 - i * 3));
        }
        for (int i = 0; i < 40; i++) {
            ledger.notePlaced(NETHER, new BlockPos(-i, 10 + i, i));
        }
        ledger.notePlaced(OVERWORLD, new BlockPos(-1000, 60, 900)); // reorder the very first cell

        String json = ledger.toJson();
        BotEditsLedger loaded = BotEditsLedger.fromJson(json);

        assertTrue(loaded.loadedCleanly());
        assertEquals(json, loaded.toJson());
        assertEquals(ledger.size(OVERWORLD), loaded.size(OVERWORLD));
        assertEquals(ledger.size(NETHER), loaded.size(NETHER));
        assertTrue(loaded.wasPlaced(OVERWORLD, new BlockPos(-1000, 60, 900)));
        assertTrue(loaded.wasPlaced(NETHER, new BlockPos(-39, 49, 39)));
        assertFalse(loaded.wasPlaced(NETHER, new BlockPos(-1000, 60, 900)));
        assertFalse(loaded.isDirty(), "a freshly loaded ledger matches the file");
    }

    @Test
    void roundTripKeepsTheEvictionOrder() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < CAP; i++) {
            ledger.notePlaced(OVERWORLD, cell(i));
        }
        ledger.notePlaced(OVERWORLD, cell(0)); // now the newest; cell 1 is the oldest

        BotEditsLedger loaded = BotEditsLedger.fromJson(ledger.toJson());
        loaded.notePlaced(OVERWORLD, cell(CAP));

        assertEquals(CAP, loaded.size(OVERWORLD));
        assertFalse(loaded.wasPlaced(OVERWORLD, cell(1)), "the oldest cell after reload is evicted first");
        assertTrue(loaded.wasPlaced(OVERWORLD, cell(0)));
        assertTrue(loaded.wasPlaced(OVERWORLD, cell(2)));
    }

    @Test
    void extremePackedValuesRoundTripExactly() {
        BotEditsLedger ledger = new BotEditsLedger();
        long[] values = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, 1L << 53, (1L << 53) + 1};
        for (long value : values) {
            ledger.notePlaced(OVERWORLD, value);
        }

        BotEditsLedger loaded = BotEditsLedger.fromJson(ledger.toJson());

        assertTrue(loaded.loadedCleanly());
        for (long value : values) {
            assertTrue(loaded.wasPlaced(OVERWORLD, value), "lost " + value);
        }
        assertEquals(values.length, loaded.size(OVERWORLD));
    }

    @Test
    void emptyDimensionArraysAreOmittedOnWrite() {
        BotEditsLedger loaded = BotEditsLedger.fromJson(
                "{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[],\"minecraft:the_nether\":[3]}}");

        assertTrue(loaded.loadedCleanly());
        assertEquals("{\"version\":1,\"dimensions\":{\"minecraft:the_nether\":[3]}}", loaded.toJson());
    }

    @Test
    void loadingIgnoresUnknownTopLevelKeys() {
        BotEditsLedger loaded = BotEditsLedger.fromJson(
                "{\"version\":1,\"note\":\"hi\",\"dimensions\":{\"minecraft:overworld\":[9]},\"extra\":[1,2]}");

        assertTrue(loaded.loadedCleanly());
        assertTrue(loaded.wasPlaced(OVERWORLD, 9L));
    }

    @Test
    void loadingDeduplicatesAndTheLaterOccurrenceWins() {
        BotEditsLedger loaded = BotEditsLedger.fromJson(
                "{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,2,1,3]}}");

        assertTrue(loaded.loadedCleanly());
        assertEquals(3, loaded.size(OVERWORLD));
        assertEquals("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[2,1,3]}}", loaded.toJson());
    }

    @Test
    void loadingAnOversizedArrayKeepsTheNewestCells() {
        StringBuilder json = new StringBuilder("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[");
        int total = CAP + 8;
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(i);
        }
        json.append("]}}");

        BotEditsLedger loaded = BotEditsLedger.fromJson(json.toString());

        assertTrue(loaded.loadedCleanly());
        assertEquals(CAP, loaded.size(OVERWORLD));
        assertFalse(loaded.wasPlaced(OVERWORLD, 7L));
        assertTrue(loaded.wasPlaced(OVERWORLD, 8L));
        assertTrue(loaded.wasPlaced(OVERWORLD, (long) total - 1));
    }

    @Test
    void aLoadedLedgerBecomesDirtyOnlyAfterAChange() {
        BotEditsLedger loaded = BotEditsLedger.fromJson(
                "{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,2]}}");
        assertFalse(loaded.isDirty());

        loaded.notePlaced(OVERWORLD, 3L);

        assertTrue(loaded.isDirty());
    }

    // ---- fail-open loading ---------------------------------------------------------------------

    private static void assertFailsOpen(String json, String problem) {
        BotEditsLedger loaded = BotEditsLedger.fromJson(json);

        assertFalse(loaded.loadedCleanly(), "expected fail-open for: " + json);
        assertEquals(problem, loaded.loadProblem(), "for: " + json);
        assertFalse(loaded.isDirty());
        assertEquals(0, loaded.size(OVERWORLD));
        assertFalse(loaded.wasPlaced(OVERWORLD, 1L));
        assertEquals("{\"version\":1,\"dimensions\":{}}", loaded.toJson());
        // The fail-open ledger is fully usable.
        loaded.notePlaced(OVERWORLD, 1L);
        assertTrue(loaded.wasPlaced(OVERWORLD, 1L));
    }

    @Test
    void nullBlankAndMalformedInputFailsOpen() {
        assertFailsOpen(null, "blank");
        assertFailsOpen("", "blank");
        assertFailsOpen("  \n\t ", "blank");
        assertFailsOpen("{", "not_json");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,2}}", "not_json");
        assertFailsOpen("{\"version\":1,\"dimensions\":{}} trailing garbage", "not_json");
        assertFailsOpen("[]", "not_object");
        assertFailsOpen("42", "not_object");
        assertFailsOpen("\"text\"", "not_object");
        assertFailsOpen("null", "not_object");
    }

    @Test
    void missingOrWrongVersionFailsOpen() {
        assertFailsOpen("{}", "wrong_version");
        assertFailsOpen("{\"dimensions\":{}}", "wrong_version");
        assertFailsOpen("{\"version\":0,\"dimensions\":{}}", "wrong_version");
        assertFailsOpen("{\"version\":2,\"dimensions\":{\"minecraft:overworld\":[1]}}", "wrong_version");
        assertFailsOpen("{\"version\":\"1\",\"dimensions\":{}}", "wrong_version");
        assertFailsOpen("{\"version\":1.5,\"dimensions\":{}}", "wrong_version");
        assertFailsOpen("{\"version\":null,\"dimensions\":{}}", "wrong_version");
    }

    @Test
    void wrongShapeFailsOpen() {
        assertFailsOpen("{\"version\":1}", "bad_dimensions");
        assertFailsOpen("{\"version\":1,\"dimensions\":[]}", "bad_dimensions");
        assertFailsOpen("{\"version\":1,\"dimensions\":\"x\"}", "bad_dimensions");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":5}}", "bad_dimension_cells");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":{\"a\":1}}}", "bad_dimension_cells");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"\":[1]}}", "blank_dimension_key");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"  \":[1]}}", "blank_dimension_key");
    }

    @Test
    void nonNumericEntriesFailOpen() {
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,\"2\"]}}", "bad_cell");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,null]}}", "bad_cell");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,true]}}", "bad_cell");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,[2]]}}", "bad_cell");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,{\"x\":2}]}}", "bad_cell");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,2.5]}}", "bad_cell");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,1e3]}}", "bad_cell");
        assertFailsOpen("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,99999999999999999999]}}",
                "bad_cell");
    }

    @Test
    void oneBadDimensionDiscardsTheWholeFile() {
        assertFailsOpen("{\"version\":1,\"dimensions\":{"
                + "\"minecraft:overworld\":[1,2,3],\"minecraft:the_nether\":[4,\"x\"]}}", "bad_cell");
    }

    // ---- dug ring ------------------------------------------------------------------------------

    @Test
    void dugCellsAreRememberedPerBot() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.noteDug(1L, cell(5));
        ledger.noteDug(2L, cell(6));

        assertTrue(ledger.wasDug(1L, cell(5)));
        assertFalse(ledger.wasDug(2L, cell(5)));
        assertTrue(ledger.wasDug(2L, cell(6)));
        assertFalse(ledger.wasDug(3L, cell(5)));
        assertEquals(1, ledger.dugSize(1L));
        assertEquals(0, ledger.dugSize(3L));
    }

    @Test
    void dugPackedAndBlockPosOverloadsAgree() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.noteDug(9L, cell(3).asLong());

        assertTrue(ledger.wasDug(9L, cell(3)));
        assertTrue(ledger.wasDug(9L, cell(3).asLong()));
        assertFalse(ledger.wasDug(9L, cell(4)));
        ledger.noteDug(9L, (BlockPos) null);
        assertEquals(1, ledger.dugSize(9L));
        assertFalse(ledger.wasDug(9L, (BlockPos) null));
    }

    @Test
    void dugRingEvictsTheOldestCellAtTheCapPerBot() {
        int cap = BotEditsLedger.DUG_CAP_PER_BOT;
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < cap; i++) {
            ledger.noteDug(1L, cell(i));
        }
        ledger.noteDug(2L, cell(0));
        assertEquals(cap, ledger.dugSize(1L));

        ledger.noteDug(1L, cell(cap));

        assertEquals(cap, ledger.dugSize(1L));
        assertFalse(ledger.wasDug(1L, cell(0)));
        assertTrue(ledger.wasDug(1L, cell(1)));
        assertTrue(ledger.wasDug(1L, cell(cap)));
        assertTrue(ledger.wasDug(2L, cell(0)), "the other bot's ring is independent");
    }

    @Test
    void redugRefreshesRecencyInTheRing() {
        int cap = BotEditsLedger.DUG_CAP_PER_BOT;
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < cap; i++) {
            ledger.noteDug(1L, cell(i));
        }
        ledger.noteDug(1L, cell(0));
        ledger.noteDug(1L, cell(cap));

        assertTrue(ledger.wasDug(1L, cell(0)));
        assertFalse(ledger.wasDug(1L, cell(1)));
    }

    @Test
    void clearBotForgetsOnlyThatBotsDugCells() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.noteDug(1L, cell(1));
        ledger.noteDug(2L, cell(2));
        ledger.notePlaced(OVERWORLD, cell(3));

        ledger.clearBot(1L);

        assertFalse(ledger.wasDug(1L, cell(1)));
        assertEquals(0, ledger.dugSize(1L));
        assertTrue(ledger.wasDug(2L, cell(2)));
        assertTrue(ledger.wasPlaced(OVERWORLD, cell(3)), "clearBot never touches the placed ledger");
        ledger.clearBot(99L); // unknown bot is a no-op
    }

    @Test
    void dugCellsNeverDirtyOrAppearInTheJson() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < 100; i++) {
            ledger.noteDug(1L, cell(i));
        }

        assertFalse(ledger.isDirty());
        assertEquals("{\"version\":1,\"dimensions\":{}}", ledger.toJson());
        assertFalse(BotEditsLedger.fromJson(ledger.toJson()).wasDug(1L, cell(0)));
    }

    @Test
    void placedAndDugAreIndependentStores() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.noteDug(1L, cell(1));
        ledger.notePlaced(OVERWORLD, cell(2));

        assertFalse(ledger.wasPlaced(OVERWORLD, cell(1)));
        assertFalse(ledger.wasDug(1L, cell(2)));
    }

    // ---- robustness and property tests ------------------------------------------------------

    @Test
    void snapshotDueSurvivesSentinelAndStaleLastSnapshotTicks() {
        BotEditsLedger ledger = new BotEditsLedger();
        ledger.notePlaced(OVERWORLD, cell(1));

        assertTrue(ledger.snapshotDue(5L, Long.MIN_VALUE), "a 'never snapshotted' sentinel must not wrap into 'not due'");
        assertTrue(ledger.snapshotDue(0L, Long.MIN_VALUE));
        assertTrue(ledger.snapshotDue(Long.MAX_VALUE, 0L));
        assertTrue(ledger.snapshotDue(Long.MAX_VALUE, -5L), "a difference wrapping past Long.MAX_VALUE is still long ago");
        assertTrue(ledger.snapshotDue(100L, 5_000_000L),
                "a tick counter that restarted (new world) is due, so a stale last tick cannot silence the sidecar");
        assertFalse(ledger.snapshotDue(1_000_000L, 1_000_000L), "no time has passed");
        assertFalse(ledger.snapshotDue(1_001_199L, 1_000_000L));
        assertTrue(ledger.snapshotDue(1_001_200L, 1_000_000L));

        ledger.markClean();
        assertFalse(ledger.snapshotDue(5L, Long.MIN_VALUE), "a clean ledger is never due, whatever the ticks");
        assertFalse(ledger.snapshotDue(100L, 5_000_000L));
    }

    @Test
    void everyTruncationOfAValidFileFailsOpenWithoutThrowing() {
        BotEditsLedger source = new BotEditsLedger();
        for (int i = 0; i < 12; i++) {
            source.notePlaced(OVERWORLD, new BlockPos(i * 31 - 200, 40 + i, 500 - i * 17));
        }
        source.notePlaced(NETHER, new BlockPos(-7, 12, 9));
        String json = source.toJson();

        for (int length = 0; length < json.length(); length++) {
            BotEditsLedger loaded = BotEditsLedger.fromJson(json.substring(0, length));

            assertFalse(loaded.loadedCleanly(), "prefix of length " + length + " must not load: " + json.substring(0, length));
            assertFalse(loaded.isDirty());
            assertEquals(0, loaded.size(OVERWORLD));
        }
        assertTrue(BotEditsLedger.fromJson(json).loadedCleanly());
    }

    @Test
    void corruptedFilesNeverThrowAndNeverExceedTheCaps() {
        BotEditsLedger source = new BotEditsLedger();
        for (int i = 0; i < 40; i++) {
            source.notePlaced(i % 2 == 0 ? OVERWORLD : NETHER, new BlockPos(i * 13 - 300, 60 - i, 200 - i * 7));
        }
        String json = source.toJson();
        SplittableRandom rnd = new SplittableRandom(0xBADF00DL);
        String alphabet = "{}[]\",:-0123456789.eE+ \u0000\\nulltruefalse'/*;=";

        for (int round = 0; round < 3000; round++) {
            StringBuilder mutated = new StringBuilder(json);
            int edits = 1 + rnd.nextInt(4);
            for (int edit = 0; edit < edits; edit++) {
                int at = rnd.nextInt(mutated.length());
                switch (rnd.nextInt(3)) {
                    case 0 -> mutated.setCharAt(at, alphabet.charAt(rnd.nextInt(alphabet.length())));
                    case 1 -> mutated.deleteCharAt(at);
                    default -> mutated.insert(at, alphabet.charAt(rnd.nextInt(alphabet.length())));
                }
                if (mutated.length() == 0) {
                    mutated.append('x');
                }
            }

            BotEditsLedger loaded = BotEditsLedger.fromJson(mutated.toString());

            assertFalse(loaded.isDirty(), mutated.toString());
            assertTrue(loaded.size(OVERWORLD) <= CAP && loaded.size(NETHER) <= CAP);
            if (loaded.loadedCleanly()) {
                // Whatever survived is a well-formed ledger: it re-serialises to a stable, reloadable document.
                String again = loaded.toJson();
                assertEquals(again, BotEditsLedger.fromJson(again).toJson(), mutated.toString());
            } else {
                assertEquals("{\"version\":1,\"dimensions\":{}}", loaded.toJson(), mutated.toString());
                assertFalse(loaded.loadProblem().isEmpty());
            }
        }
    }

    @Test
    void binaryJunkAndControlCharactersFailOpen() {
        assertFalse(BotEditsLedger.fromJson("\u0000\u0000\u0000\u0000").loadedCleanly());
        assertFalse(BotEditsLedger.fromJson("\u0001\u0002{\"version\":1}").loadedCleanly());
        assertFalse(BotEditsLedger.fromJson("PK\u0003\u0004\u0014\u0000").loadedCleanly());
        assertFalse(BotEditsLedger.fromJson("[".repeat(200_000)).loadedCleanly());
        assertFalse(BotEditsLedger.fromJson("{\"version\":1,\"dimensions\":".repeat(20_000)).loadedCleanly());
    }

    /** Independent list-based LRU used as the oracle for the property test below. */
    private static final class ReferenceLru {
        private final ArrayList<Long> cells = new ArrayList<>();

        void note(long packed) {
            cells.remove(Long.valueOf(packed));
            cells.add(packed);
            if (cells.size() > CAP) {
                cells.remove(0);
            }
        }
    }

    @Test
    void placedLedgerMatchesAReferenceLruThroughEvictionAndRefresh() {
        SplittableRandom rnd = new SplittableRandom(0x1234ABCDL);
        BotEditsLedger ledger = new BotEditsLedger();
        ReferenceLru overworld = new ReferenceLru();
        ReferenceLru nether = new ReferenceLru();
        int universe = CAP + CAP / 2;

        for (int op = 0; op < 24_000; op++) {
            // Bias toward a hot subset so refreshes of old cells happen, with a long tail that forces evictions.
            long cell = rnd.nextInt(4) == 0 ? rnd.nextInt(64) : rnd.nextInt(universe);
            if (rnd.nextInt(5) == 0) {
                ledger.notePlaced(NETHER, cell);
                nether.note(cell);
            } else {
                ledger.notePlaced(OVERWORLD, cell);
                overworld.note(cell);
            }
        }

        assertEquals(overworld.cells.size(), ledger.size(OVERWORLD));
        assertEquals(nether.cells.size(), ledger.size(NETHER));
        assertEquals(CAP, ledger.size(OVERWORLD), "the workload is large enough to reach the cap");
        StringBuilder expected = new StringBuilder("{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[");
        appendCells(expected, overworld.cells);
        expected.append("],\"minecraft:the_nether\":[");
        appendCells(expected, nether.cells);
        expected.append("]}}");
        assertEquals(expected.toString(), ledger.toJson(), "same cells in the same oldest-first order");
        Set<Long> inOverworld = new HashSet<>(overworld.cells);
        for (long cell = 0; cell < universe; cell++) {
            assertEquals(inOverworld.contains(cell), ledger.wasPlaced(OVERWORLD, cell), "cell " + cell);
        }
        assertEquals(expected.toString(), BotEditsLedger.fromJson(ledger.toJson()).toJson());
    }

    private static void appendCells(StringBuilder out, List<Long> cells) {
        for (int i = 0; i < cells.size(); i++) {
            out.append(i == 0 ? "" : ",").append(cells.get(i));
        }
    }

    @Test
    void awkwardDimensionKeysRoundTripExactly() {
        String[] keys = {"minecraft:overworld", "a\"b", "back\\slash", "tab\there", "new\nline",
                "caf" + (char) 0xE9, "emoji" + new String(Character.toChars(0x1F600)), "ctl\u0001", "<script>&'=",
                "line" + (char) 0x2028 + "sep", "ns:with space", "ns:\u0000nul"};
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < keys.length; i++) {
            ledger.notePlaced(keys[i], 100L + i);
            ledger.notePlaced(keys[i], -7L - i);
        }

        BotEditsLedger loaded = BotEditsLedger.fromJson(ledger.toJson());

        assertTrue(loaded.loadedCleanly(), loaded.loadProblem());
        assertEquals(ledger.toJson(), loaded.toJson());
        for (int i = 0; i < keys.length; i++) {
            assertEquals(2, loaded.size(keys[i]), keys[i]);
            assertTrue(loaded.wasPlaced(keys[i], 100L + i), keys[i]);
            assertTrue(loaded.wasPlaced(keys[i], -7L - i), keys[i]);
            assertFalse(loaded.wasPlaced(keys[(i + 1) % keys.length], 100L + i), "dimensions stay separate");
        }
    }

    @Test
    void reloadedLedgerKeepsWorkingAtTheCap() {
        BotEditsLedger ledger = new BotEditsLedger();
        for (int i = 0; i < CAP + 100; i++) {
            ledger.notePlaced(OVERWORLD, cell(i));
        }
        BotEditsLedger loaded = BotEditsLedger.fromJson(ledger.toJson());
        assertEquals(CAP, loaded.size(OVERWORLD));

        for (int i = 0; i < 300; i++) {
            loaded.notePlaced(OVERWORLD, cell(CAP + 100 + i));
        }

        assertEquals(CAP, loaded.size(OVERWORLD));
        assertFalse(loaded.wasPlaced(OVERWORLD, cell(100)), "the 300 oldest were evicted in order");
        assertFalse(loaded.wasPlaced(OVERWORLD, cell(399)));
        assertTrue(loaded.wasPlaced(OVERWORLD, cell(400)));
        assertTrue(loaded.wasPlaced(OVERWORLD, cell(CAP + 399)));
    }

    // ---- determinism ---------------------------------------------------------------------------

    @Test
    void sameOperationsGiveTheSameJsonAndTheSameEvictions() {
        List<Integer> script = List.of(5, 9, 5, 1, 12, 9, 33, 5, 2, 2, 8, 1);
        BotEditsLedger first = new BotEditsLedger();
        BotEditsLedger second = new BotEditsLedger();
        for (int step : script) {
            first.notePlaced(OVERWORLD, cell(step));
            second.notePlaced(OVERWORLD, cell(step));
        }
        for (int i = 0; i < CAP + 3; i++) {
            first.notePlaced(NETHER, cell(i));
            second.notePlaced(NETHER, cell(i));
        }

        assertEquals(first.toJson(), second.toJson());
        assertNotEquals("{\"version\":1,\"dimensions\":{}}", first.toJson());
    }
}
