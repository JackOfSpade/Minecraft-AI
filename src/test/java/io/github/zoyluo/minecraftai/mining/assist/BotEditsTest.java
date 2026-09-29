package io.github.zoyluo.minecraftai.mining.assist;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sidecar I/O and dug-ring plumbing of BotEdits (the world-facing overloads need a server and are covered by source contract). */
class BotEditsTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final BlockPos TORCH = new BlockPos(5, 64, -7);

    @BeforeEach
    @AfterEach
    void reset() {
        MiningAssistRuntime.resetForTests();
        BotEdits.resetForTests();
    }

    @Test
    void placedCellsAreQueryableByDimensionAndPackedPosition() {
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        assertTrue(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
        assertFalse(BotEdits.wasPlaced("minecraft:the_nether", TORCH.asLong()));
        assertFalse(BotEdits.wasPlaced(OVERWORLD, TORCH.above().asLong()));
    }

    @Test
    void botKeyIsStablePerUuidAndDistinctAcrossBots() {
        UUID a = new UUID(1L, 2L);
        UUID b = new UUID(3L, 4L);
        assertEquals(BotEdits.botKey(a), BotEdits.botKey(new UUID(1L, 2L)));
        assertTrue(BotEdits.botKey(a) != BotEdits.botKey(b));
    }

    @Test
    void clearBotForgetsOnlyThatBotsDugCells() {
        UUID a = new UUID(1L, 2L);
        UUID b = new UUID(3L, 4L);
        BotEdits.ledger().noteDug(BotEdits.botKey(a), TORCH);
        BotEdits.ledger().noteDug(BotEdits.botKey(b), TORCH);
        BotEdits.clearBot(a);
        assertEquals(0, BotEdits.ledger().dugSize(BotEdits.botKey(a)));
        assertEquals(1, BotEdits.ledger().dugSize(BotEdits.botKey(b)));
    }

    // ---- sidecar ---------------------------------------------------------------------------------

    @Test
    void missingSidecarLoadsAnEmptyLedger(@TempDir Path dir) {
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        assertFalse(BotEdits.loadFromDisk(dir.resolve("nope.json")));
        assertFalse(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()), "loading replaces the in-memory ledger");
        assertFalse(BotEdits.ledger().isDirty());
    }

    @Test
    void snapshotThenLoadRoundTripsThePlacedLedger(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("minecraftai").resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        BotEdits.ledger().notePlaced("minecraft:the_nether", TORCH.above());
        assertTrue(BotEdits.ledger().isDirty());

        assertTrue(BotEdits.snapshotIfDue(5000L, file), "dirty and never written: due");
        assertFalse(BotEdits.ledger().isDirty(), "the string was captured, so the ledger is clean");
        BotEdits.flushSync(file);

        assertTrue(Files.isRegularFile(file));
        assertEquals(1, JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt());

        BotEdits.resetForTests();
        assertFalse(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
        assertTrue(BotEdits.loadFromDisk(file));
        assertTrue(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
        assertTrue(BotEdits.wasPlaced("minecraft:the_nether", TORCH.above().asLong()));
    }

    @Test
    void snapshotsAreRateLimitedToOnePerTwelveHundredTicks(@TempDir Path dir) {
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        assertTrue(BotEdits.snapshotIfDue(10_000L, file));
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH.above());
        assertFalse(BotEdits.snapshotIfDue(10_500L, file), "only 500 ticks since the last snapshot");
        assertTrue(BotEdits.ledger().isDirty());
        assertTrue(BotEdits.snapshotIfDue(10_000L + BotEditsLedger.SNAPSHOT_MIN_INTERVAL_TICKS, file));
        BotEdits.flushSync(file);
    }

    @Test
    void aCleanLedgerIsNeverWritten(@TempDir Path dir) {
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        assertFalse(BotEdits.snapshotIfDue(99_999L, file));
        BotEdits.flushSync(file);
        assertFalse(Files.exists(file));
    }

    @Test
    void flushSyncWritesADirtyLedgerThatNeverGotItsSnapshot(@TempDir Path dir) {
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        BotEdits.flushSync(file);
        assertTrue(Files.isRegularFile(file));
        assertFalse(BotEdits.ledger().isDirty());
    }

    @Test
    void corruptSidecarFailsOpenToAnEmptyLedger(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        Files.writeString(file, "{\"version\":1,\"dimensions\":{\"minecraft:overworld\":[1,2,\"x\"]}}");
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        assertFalse(BotEdits.loadFromDisk(file));
        assertFalse(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
        assertFalse(BotEdits.wasPlaced(OVERWORLD, 1L));
        Files.writeString(file, "definitely not json");
        assertFalse(BotEdits.loadFromDisk(file));
    }

    @Test
    void oversizedSidecarIsRefusedBeforeItIsRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        try (var out = Files.newOutputStream(file)) {
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 9; i++) {
                out.write(chunk);
            }
        }
        assertFalse(BotEdits.loadFromDisk(file));
        assertFalse(BotEdits.ledger().isDirty());
    }

    @Test
    void aFailedBackgroundWriteIsRetriedAtTheNextSnapshotSlotEvenThoughTheLedgerIsClean(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path bad = blocker.resolve(BotEditsLedger.SIDECAR_FILE_NAME); // cannot be created below a regular file
        Path good = dir.resolve("good").resolve(BotEditsLedger.SIDECAR_FILE_NAME);

        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        assertTrue(BotEdits.snapshotIfDue(5000L, bad));
        assertFalse(BotEdits.ledger().isDirty(), "the capture marks the ledger clean before the writer has run");
        BotEdits.flushSync(bad); // drains the writer; the write to the bad path failed
        assertTrue(BotEdits.lastWriteFailed());

        assertFalse(BotEdits.snapshotIfDue(5000L + 100L, good), "inside the minimum interval");
        assertTrue(BotEdits.snapshotIfDue(5000L + BotEditsLedger.SNAPSHOT_MIN_INTERVAL_TICKS, good),
                "the failure makes the clean ledger due again");
        BotEdits.flushSync(good);
        assertTrue(Files.isRegularFile(good), "the retry wrote the full ledger");
        assertFalse(BotEdits.lastWriteFailed());

        BotEdits.resetForTests();
        assertTrue(BotEdits.loadFromDisk(good));
        assertTrue(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
    }

    @Test
    void theSynchronousFlushAtStopRetriesAFailedWriteEvenWithACleanLedger(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path bad = blocker.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        Path good = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);

        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        assertTrue(BotEdits.snapshotIfDue(5000L, bad));
        BotEdits.flushSync(bad);
        assertTrue(BotEdits.lastWriteFailed());
        assertFalse(BotEdits.ledger().isDirty());

        BotEdits.flushSync(good);
        assertTrue(Files.isRegularFile(good), "a failed write is not forgotten at server stop");
    }

    @Test
    void theSidecarIsNotReadWhileTheSwitchIsOff(@TempDir Path dir) {
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        BotEdits.flushSync(file);
        assertTrue(Files.exists(file));
        BotEdits.resetForTests();

        MiningAssistRuntime.install(JsonParser.parseString("{\"miningAssist\":{\"mode\":\"off\"}}").getAsJsonObject(),
                key -> null);
        assertFalse(MiningAssistRuntime.senseConfigured());
        assertFalse(BotEdits.loadFromDisk(file), "off means no start-up read");
        assertFalse(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
    }

    @Test
    void sidecarSwitchOffKeepsTheInMemoryLedgerButWritesNothing(@TempDir Path dir) {
        MiningAssistRuntime.install(JsonParser.parseString("{\"miningAssist\":{\"edits\":{\"sidecar\":false}}}")
                .getAsJsonObject(), key -> null);
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        assertFalse(BotEdits.snapshotIfDue(99_999L, file));
        BotEdits.flushSync(file);
        assertFalse(Files.exists(file));
        assertTrue(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
    }

    @Test
    void sidecarSwitchOffLoadsAnEmptyLedgerEvenIfAFileExists(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(BotEditsLedger.SIDECAR_FILE_NAME);
        BotEdits.ledger().notePlaced(OVERWORLD, TORCH);
        BotEdits.flushSync(file);
        assertTrue(Files.exists(file));
        BotEdits.resetForTests();
        MiningAssistRuntime.install(JsonParser.parseString("{\"miningAssist\":{\"edits\":{\"sidecar\":false}}}")
                .getAsJsonObject(), key -> null);
        BotEdits.loadFromDisk(file);
        assertFalse(BotEdits.wasPlaced(OVERWORLD, TORCH.asLong()));
    }
}
