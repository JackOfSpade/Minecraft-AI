package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** The key-log primitives in isolation: how lines are framed, appended, replaced and cut. */
class AbandonedLogTest {

    private static List<StructureKey> readAll(Path file) throws IOException {
        List<StructureKey> keys = new ArrayList<>();
        AbandonedLog.read(file, keys::add);
        return keys;
    }

    @Test
    void appendCreatesTheFileAndItsDirectoryAndReadsBackInOrder(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("not/yet/there/abandoned.keys");
        AbandonedLog.append(file, key(1, 2));
        AbandonedLog.append(file, key(OUTPOST, -3, 4));
        assertEquals(List.of(OVERWORLD + "|" + VILLAGE + "|1,2", OVERWORLD + "|" + OUTPOST + "|-3,4"), lines(file));
        assertEquals(List.of(key(1, 2), key(OUTPOST, -3, 4)), readAll(file));
    }

    @Test
    void readReportsFramingOfACleanFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abandoned.keys");
        AbandonedLog.append(file, key(1, 2));
        AbandonedLog.append(file, key(3, 4));
        AbandonedLog.Loaded loaded = AbandonedLog.read(file, k -> {
        });
        assertEquals(2, loaded.keys());
        assertEquals(0, loaded.malformed());
        assertEquals(0, loaded.truncatedTail());
        assertEquals(Files.size(file), loaded.validLength());
    }

    @Test
    void readReportsAnUnterminatedTailWithoutUsingIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abandoned.keys");
        String complete = OVERWORLD + "|" + VILLAGE + "|1,2\n";
        Files.writeString(file, complete + OVERWORLD + "|" + VILLAGE + "|3,4");
        List<StructureKey> keys = readAll(file);
        assertEquals(List.of(key(1, 2)), keys);
        AbandonedLog.Loaded loaded = AbandonedLog.read(file, k -> {
        });
        assertEquals(complete.length(), loaded.validLength());
        assertEquals(Files.size(file) - complete.length(), loaded.truncatedTail());
    }

    @Test
    void linesThatSpanTheInternalReadBufferAreReassembled(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abandoned.keys");
        StringBuilder text = new StringBuilder();
        List<StructureKey> expected = new ArrayList<>();
        for (int i = 0; i < 6000; i++) { // ~ 400 KB, several times the 64 KB read buffer
            StructureKey k = new StructureKey(OVERWORLD, "modid:some/longer/structure/name_" + (i % 13), i, -i);
            expected.add(k);
            text.append(k.asString()).append('\n');
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        assertEquals(expected, readAll(file));
    }

    @Test
    void multiByteCharactersAreDecodedStrictlyPerLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abandoned.keys");
        StructureKey unicode = new StructureKey("déjà:vu", "emoji:😀/日本", 1, 1);
        AbandonedLog.append(file, unicode);
        assertEquals(List.of(unicode), readAll(file));
    }

    @Test
    void readOfAMissingFileThrows(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class, () -> AbandonedLog.read(dir.resolve("nope"), k -> {
        }));
    }

    @Test
    void rewriteReplacesTheWholeFileAndLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abandoned.keys");
        Path temp = dir.resolve("abandoned.keys.tmp");
        AbandonedLog.append(file, key(1, 1));
        AbandonedLog.append(file, key(2, 2));
        AbandonedLog.rewrite(file, temp, sink -> {
            sink.accept(key(3, 3));
            sink.accept(key(4, 4));
            sink.accept(key(5, 5));
        });
        assertEquals(List.of(key(3, 3), key(4, 4), key(5, 5)), readAll(file));
        assertFalse(Files.exists(temp));
        AbandonedLog.rewrite(file, temp, sink -> {
        });
        assertEquals(0, Files.size(file));
    }

    @Test
    void aFailedRewriteLeavesTheOriginalUntouchedAndCleansUp(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abandoned.keys");
        Path temp = dir.resolve("abandoned.keys.tmp");
        AbandonedLog.append(file, key(1, 1));
        String before = read(file);

        // The source blows up half way: the old file must survive and no half-written temp file may remain.
        assertThrows(IllegalStateException.class, () -> AbandonedLog.rewrite(file, temp, sink -> {
            sink.accept(key(9, 9));
            throw new IllegalStateException("boom");
        }));
        assertEquals(before, read(file));
        assertFalse(Files.exists(temp));
    }

    @Test
    void truncateCutsToTheGivenLengthAndReportsFailure(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abandoned.keys");
        Files.writeString(file, "abcdef");
        assertTrue(AbandonedLog.truncate(file, 3));
        assertEquals("abc", read(file));
        assertFalse(AbandonedLog.truncate(dir.resolve("missing"), 0));
    }
}
