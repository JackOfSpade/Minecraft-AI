package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.engine.PopulationView.PopulationCounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.spawnbotswrapper.inhabitants.store.PopulationStore.ABANDONED_FILE;
import static dev.spawnbotswrapper.inhabitants.store.PopulationStore.BACKUP_FILE;
import static dev.spawnbotswrapper.inhabitants.store.PopulationStore.CORRUPT_FILE;
import static dev.spawnbotswrapper.inhabitants.store.PopulationStore.POPULATIONS_FILE;
import static dev.spawnbotswrapper.inhabitants.store.PopulationStore.TEMP_FILE;
import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** What is left on disk when the process dies at the worst moment, and what the next start makes of it. */
class PopulationStoreCrashTest {
    private static final String GARBAGE = "{\"dataVersion\":1,\"structures\":{\"minecraft:overworld|a:b|1,1\":{\"status\":\"OCC";

    /** main = generation 2 (Gen_1 + Gen_2), backup = generation 1 (Gen_1 only). */
    private static void twoGenerations(Path dir) {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Gen_1"));
        store.flush();
        store.put(key(2, 2), pending("Gen_2"));
        store.flush();
    }

    private static boolean has(List<String> messages, String fragment) {
        return messages.stream().anyMatch(m -> m.contains(fragment));
    }

    // ---------------------------------------------------------------- populations.json

    @Test
    void aLeftoverTempFileIsIgnoredAndReplacedByTheNextSave(@TempDir Path dir) throws IOException {
        twoGenerations(dir);
        write(dir.resolve(TEMP_FILE), GARBAGE); // kill -9 in the middle of writing the next generation

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertEquals(PopulationStore.LoadSource.MAIN, store.loadSource());
        assertTrue(store.find(key(2, 2)).isPresent(), "the intact main file wins");
        assertTrue(has(report.messages(), TEMP_FILE), report.messages().toString());

        store.put(key(3, 3), pending("Gen_3"));
        assertTrue(store.saveIfDirty());
        assertFalse(Files.exists(dir.resolve(TEMP_FILE)));
        assertTrue(loaded(dir).find(key(3, 3)).isPresent());
    }

    @Test
    void aCorruptMainFileFallsBackToTheBackupAndKeepsTheCorruptCopy(@TempDir Path dir) throws IOException {
        twoGenerations(dir);
        Path main = dir.resolve(POPULATIONS_FILE);
        Path backup = dir.resolve(BACKUP_FILE);
        String backupBefore = read(backup);
        write(main, GARBAGE);

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertEquals(PopulationStore.LoadSource.BACKUP, store.loadSource());
        assertTrue(has(report.messages(), "recovered from " + BACKUP_FILE), report.messages().toString());
        assertTrue(store.find(key(1, 1)).isPresent());
        assertTrue(store.find(key(2, 2)).isEmpty(), "generation 2 is lost with the corrupt main file");
        assertTrue(store.findBot("gen_1").isPresent());

        assertEquals(GARBAGE, read(main), "loading must not touch the damaged file");
        assertTrue(store.saveIfDirty(), "recovery marks the store dirty so the main file is repaired");
        assertEquals(GARBAGE, read(dir.resolve(CORRUPT_FILE)), "the damaged file is kept for inspection");
        assertEquals(backupBefore, read(backup), "the good backup must NOT be replaced by the corrupt main file");
        PopulationStore repaired = new PopulationStore(dir);
        repaired.load();
        assertEquals(PopulationStore.LoadSource.MAIN, repaired.loadSource());
        assertTrue(repaired.find(key(1, 1)).isPresent());

        // afterwards the normal cycle resumes: the repaired main becomes the backup on the next save
        String repairedMain = read(main);
        store.put(key(4, 4), pending("Gen_4"));
        store.flush();
        assertEquals(repairedMain, read(backup));
        assertEquals(GARBAGE, read(dir.resolve(CORRUPT_FILE)));
    }

    @Test
    void aMainFileTruncatedAtAnyByteNeverYieldsPartialState(@TempDir Path dir) throws IOException {
        PopulationStore builder = loaded(dir);
        builder.put(key(1, 1), populated("Gen_1"));
        builder.flush();
        builder.put(key(2, 2), populated("Gen_2", "Gen_3"));
        builder.flush();
        Path main = dir.resolve(POPULATIONS_FILE);
        byte[] good = Files.readAllBytes(main);

        // Every byte near both ends (where a document is easiest to get wrong), then a sweep through the middle.
        java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>();
        for (int i = 0; i < 64; i++) {
            cuts.add(i);
            cuts.add(good.length - 1 - i);
        }
        for (int cut = 0; cut < good.length; cut += Math.max(1, good.length / 150)) {
            cuts.add(cut);
        }
        for (int cut : cuts) {
            if (cut < 0 || cut >= good.length) {
                continue;
            }
            Files.write(main, Arrays.copyOf(good, cut));
            PopulationStore store = new PopulationStore(dir);
            assertTrue(store.load().usable(), "cut at " + cut);
            assertEquals(PopulationStore.LoadSource.BACKUP, store.loadSource(), "cut at " + cut);
            assertTrue(store.find(key(1, 1)).isPresent(), "cut at " + cut);
            assertTrue(store.find(key(2, 2)).isEmpty(), "cut at " + cut + " leaked half a record");
        }
        Files.write(main, good);
        PopulationStore whole = new PopulationStore(dir);
        whole.load();
        assertEquals(PopulationStore.LoadSource.MAIN, whole.loadSource());
        assertEquals(2, whole.nonAbandoned().size());
    }

    @Test
    void garbageAfterTheEndOfTheDocumentMakesTheMainFileInvalid(@TempDir Path dir) throws IOException {
        twoGenerations(dir);
        Path main = dir.resolve(POPULATIONS_FILE);
        write(main, read(main) + "\n{\"more\":1}");
        PopulationStore store = new PopulationStore(dir);
        store.load();
        assertEquals(PopulationStore.LoadSource.BACKUP, store.loadSource());
    }

    @Test
    void aMissingMainFileFallsBackToTheBackup(@TempDir Path dir) throws IOException {
        twoGenerations(dir);
        Files.delete(dir.resolve(POPULATIONS_FILE));
        PopulationStore store = new PopulationStore(dir);
        assertTrue(store.load().usable());
        assertEquals(PopulationStore.LoadSource.BACKUP, store.loadSource());
        assertTrue(store.find(key(1, 1)).isPresent());
    }

    @Test
    void aSaveInterruptedBetweenTheTwoRenamesLosesNothing(@TempDir Path dir) throws IOException {
        twoGenerations(dir);
        PopulationStore store = loaded(dir);
        store.put(key(3, 3), pending("Gen_3"));
        store.flush();
        // Crash instant: the old file was moved to .bak, the new (fsynced, complete) file is still the temp file.
        Files.move(dir.resolve(POPULATIONS_FILE), dir.resolve(TEMP_FILE));
        String backupBefore = read(dir.resolve(BACKUP_FILE));

        PopulationStore after = new PopulationStore(dir);
        PopulationStore.LoadReport report = after.load();
        assertTrue(report.usable());
        assertEquals(PopulationStore.LoadSource.INTERRUPTED_SAVE, after.loadSource());
        assertTrue(after.find(key(3, 3)).isPresent(), "the newest state must survive");
        assertTrue(has(report.messages(), TEMP_FILE), report.messages().toString());

        assertTrue(after.saveIfDirty());
        assertEquals(backupBefore, read(dir.resolve(BACKUP_FILE)));
        assertFalse(Files.exists(dir.resolve(TEMP_FILE)));
        PopulationStore repaired = new PopulationStore(dir);
        repaired.load();
        assertEquals(PopulationStore.LoadSource.MAIN, repaired.loadSource());
        assertTrue(repaired.find(key(3, 3)).isPresent());
    }

    @Test
    void anIncompleteTempFileWithoutAnyMainFileIsJustAFreshStore(@TempDir Path dir) throws IOException {
        write(dir.resolve(TEMP_FILE), GARBAGE); // the very first save died half way
        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertEquals(PopulationStore.LoadSource.FRESH, store.loadSource());
        assertEquals(0, store.counts().structures());
        assertTrue(has(report.messages(), "incomplete"), report.messages().toString());
    }

    @Test
    void whenNothingIsReadableTheStoreIsUnusableAndNothingIsTouched(@TempDir Path dir) throws IOException {
        write(dir.resolve(POPULATIONS_FILE), GARBAGE);
        write(dir.resolve(BACKUP_FILE), "not json at all");
        write(dir.resolve(ABANDONED_FILE), OVERWORLD + "|" + VILLAGE + "|1,1\n" + OVERWORLD + "|" + VILLAGE + "|2,2\n");
        String main = read(dir.resolve(POPULATIONS_FILE));
        String backup = read(dir.resolve(BACKUP_FILE));
        String keys = read(dir.resolve(ABANDONED_FILE));

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertFalse(report.usable());
        assertFalse(store.usable());
        assertTrue(has(report.messages(), "UNUSABLE"), report.messages().toString());
        assertTrue(has(report.messages(), "duplicate"), "the message must explain why refusing is the safe choice");

        // Every mutation is refused, nothing is written, and it never throws.
        store.put(key(9, 9), pending("Refused_1"));
        store.put(key(8, 8), StructureRecord.abandoned());
        assertFalse(store.remove(key(1, 1)));
        store.markDirty();
        assertFalse(store.saveIfDirty());
        assertDoesNotThrow(store::flush);
        assertTrue(store.find(key(9, 9)).isEmpty());
        assertTrue(store.find(key(1, 1)).isEmpty(), "an unusable store must not pretend to know anything");
        assertEquals(new PopulationCounts(0, 0, 0, 0, 0, 0), store.counts());
        assertTrue(store.nearby(OVERWORLD, 0, 0, 100).isEmpty());
        assertTrue(store.findBot("Refused_1").isEmpty());
        assertTrue(store.nonAbandoned().isEmpty());

        assertEquals(main, read(dir.resolve(POPULATIONS_FILE)));
        assertEquals(backup, read(dir.resolve(BACKUP_FILE)));
        assertEquals(keys, read(dir.resolve(ABANDONED_FILE)));
        assertFalse(Files.exists(dir.resolve(TEMP_FILE)));
        assertFalse(Files.exists(dir.resolve(CORRUPT_FILE)));
    }

    @Test
    void aCorruptMainFileWithoutAnyBackupIsUnusable(@TempDir Path dir) throws IOException {
        write(dir.resolve(POPULATIONS_FILE), GARBAGE);
        PopulationStore store = new PopulationStore(dir);
        assertFalse(store.load().usable());
        assertFalse(store.usable());
        assertEquals(GARBAGE, read(dir.resolve(POPULATIONS_FILE)));
    }

    @Test
    void anEmptyMainFileIsCorruptNotFresh(@TempDir Path dir) throws IOException {
        write(dir.resolve(POPULATIONS_FILE), "");
        assertFalse(new PopulationStore(dir).load().usable());
    }

    @Test
    void aBackupThatIsCorruptWhileTheMainFileIsMissingIsUnusable(@TempDir Path dir) throws IOException {
        write(dir.resolve(BACKUP_FILE), GARBAGE);
        assertFalse(new PopulationStore(dir).load().usable());
    }

    // ---------------------------------------------------------------- newer data

    private static final String NEWER_EMPTY = "{\"dataVersion\":99,\"structures\":{}}";

    @Test
    void aNewerDataVersionIsUnusableEvenWithAValidOlderBackup(@TempDir Path dir) throws IOException {
        twoGenerations(dir);
        write(dir.resolve(POPULATIONS_FILE), NEWER_EMPTY);
        String backup = read(dir.resolve(BACKUP_FILE));

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertFalse(report.usable(), "falling back to the old backup would later overwrite the newer data");
        assertTrue(has(report.messages(), "newer"), report.messages().toString());
        store.put(key(9, 9), pending("Refused_1"));
        assertFalse(store.saveIfDirty());
        assertEquals(NEWER_EMPTY, read(dir.resolve(POPULATIONS_FILE)));
        assertEquals(backup, read(dir.resolve(BACKUP_FILE)));
    }

    @Test
    void aNewerLayoutThatThisBuildCannotParseIsReportedAsNewerNotCorrupt(@TempDir Path dir) throws IOException {
        twoGenerations(dir);
        write(dir.resolve(POPULATIONS_FILE),
                "{\"dataVersion\":7,\"structures\":{\"minecraft:overworld|a:b|1,1\":[\"a totally different shape\"]},\"x\":1}");
        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertFalse(report.usable());
        assertTrue(has(report.messages(), "newer"), report.messages().toString());
        assertFalse(has(report.messages(), "is unreadable"), "must not be presented as corruption: " + report.messages());
    }

    @Test
    void aNewerBackupIsUnusableToo(@TempDir Path dir) throws IOException {
        write(dir.resolve(POPULATIONS_FILE), GARBAGE);
        write(dir.resolve(BACKUP_FILE), NEWER_EMPTY);
        assertFalse(new PopulationStore(dir).load().usable());
    }

    @Test
    void aNewerTempFileIsUnusableToo(@TempDir Path dir) throws IOException {
        write(dir.resolve(TEMP_FILE), NEWER_EMPTY);
        assertFalse(new PopulationStore(dir).load().usable());
    }

    @Test
    void aRecordFromANewerVersionInsideAnOlderFileIsUnusable(@TempDir Path dir) throws IOException {
        write(dir.resolve(POPULATIONS_FILE), "{\"dataVersion\":1,\"structures\":{\"minecraft:overworld|a:b|1,1\":"
                + "{\"dataVersion\":3,\"status\":\"POPULATED\"}}}");
        PopulationStore store = new PopulationStore(dir);
        assertFalse(store.load().usable());
    }

    @Test
    void aMinimalFileWithoutDecksLoads(@TempDir Path dir) throws IOException {
        write(dir.resolve(POPULATIONS_FILE), "{\"dataVersion\":1,\"structures\":{}}");
        assertTrue(new PopulationStore(dir).load().usable());
    }

    // ---------------------------------------------------------------- abandoned.keys

    private static String k(int x, int z) {
        return OVERWORLD + "|" + VILLAGE + "|" + x + "," + z;
    }

    @Test
    void aTruncatedFinalLineIsIgnoredEvenWhenItParsesAsADifferentKey(@TempDir Path dir) throws IOException {
        Path keys = dir.resolve(ABANDONED_FILE);
        // The last key was really 7,890 - "7,89" is a perfectly valid, but wrong, key.
        write(keys, k(1, 234) + "\n" + k(5, 6) + "\n" + k(7, 89));

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertEquals(2, store.counts().abandoned());
        assertTrue(store.find(key(1, 234)).isPresent());
        assertTrue(store.find(key(5, 6)).isPresent());
        assertTrue(store.find(key(7, 89)).isEmpty(), "a crash artifact must never become an abandoned structure");
        assertTrue(has(report.messages(), "unterminated final line"), report.messages().toString());
        assertEquals(k(1, 234) + "\n" + k(5, 6) + "\n", read(keys), "the partial tail is cut off");

        // The next append must start on a clean line instead of gluing itself to the debris.
        store.put(key(7, 890), StructureRecord.abandoned());
        assertEquals(List.of(k(1, 234), k(5, 6), k(7, 890)), lines(keys));
        PopulationStore reopened = loaded(dir);
        assertEquals(3, reopened.counts().abandoned());
        assertTrue(reopened.find(key(7, 89)).isEmpty());
        assertTrue(reopened.find(key(7, 890)).isPresent());
    }

    @Test
    void aLeftoverTempFromACrashedKeyFileRewriteIsIgnoredAndReplaced(@TempDir Path dir) throws IOException {
        Path keys = dir.resolve(ABANDONED_FILE);
        Path temp = dir.resolve("abandoned.keys.tmp");
        write(keys, k(1, 1) + "\n" + k(2, 2) + "\n");
        write(temp, k(9, 9) + "\n" + OVERWORLD + "|par"); // an interrupted rewrite: never the real file

        PopulationStore store = loaded(dir);
        assertEquals(2, store.counts().abandoned());
        assertTrue(store.find(key(9, 9)).isEmpty(), "a temp file is never a source of truth");

        assertTrue(store.remove(key(1, 1)));
        assertFalse(Files.exists(temp));
        assertEquals(List.of(k(2, 2)), lines(keys));
    }

    @Test
    void aFileWithOnlyAPartialLineIsEmptyNotMalformed(@TempDir Path dir) throws IOException {
        write(dir.resolve(ABANDONED_FILE), "minecraft:overwo");
        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertEquals(0, store.counts().abandoned());
        assertFalse(has(report.messages(), "malformed"), report.messages().toString());
        assertEquals("", read(dir.resolve(ABANDONED_FILE)));
    }

    @Test
    void malformedLinesAreSkippedCountedAndTheRestStillLoads(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});                    // a BOM in front of the first key
        bytes.write((k(1, 1) + "\n").getBytes(StandardCharsets.UTF_8));
        bytes.write("this is not a key\n".getBytes(StandardCharsets.UTF_8));               // malformed 1
        bytes.write("\n".getBytes(StandardCharsets.UTF_8));                                  // blank: ignored silently
        bytes.write((OVERWORLD + "|" + VILLAGE + "|1,x\n").getBytes(StandardCharsets.UTF_8)); // malformed 2
        bytes.write(new byte[]{(byte) 0xC3, (byte) 0x28, '\n'});                             // malformed 3: invalid UTF-8
        bytes.write(("x".repeat(6000) + "\n").getBytes(StandardCharsets.UTF_8));            // malformed 4: absurdly long
        bytes.write((k(2, 2) + "\r\n").getBytes(StandardCharsets.UTF_8));                    // CRLF from a Windows editor
        bytes.write((k(1, 1) + "\n").getBytes(StandardCharsets.UTF_8));                      // duplicate
        bytes.write((k(3, 3) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(ABANDONED_FILE), bytes.toByteArray());

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertEquals(3, store.counts().abandoned());
        assertTrue(store.find(key(1, 1)).isPresent(), "BOM must not corrupt the first key");
        assertTrue(store.find(key(2, 2)).isPresent(), "CRLF must be tolerated");
        assertTrue(store.find(key(3, 3)).isPresent());
        assertTrue(has(report.messages(), "skipped 4 malformed"), report.messages().toString());
    }

    @Test
    void anUnreadableKeyFileMakesTheStoreUnusable(@TempDir Path dir) throws IOException {
        Path keys = dir.resolve(ABANDONED_FILE);
        Files.createDirectories(keys);
        Files.writeString(keys.resolve("keep"), "x");
        write(dir.resolve(POPULATIONS_FILE), "{\"dataVersion\":1,\"structures\":{}}");

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertFalse(report.usable());
        assertTrue(has(report.messages(), ABANDONED_FILE), report.messages().toString());
        store.put(key(1, 1), StructureRecord.abandoned());
        assertTrue(Files.isDirectory(keys), "nothing was touched");
    }

    @Test
    void aMissingKeyFileNextToAPopulationFileIsUsableButWarned(@TempDir Path dir) throws IOException {
        PopulationStore store = loaded(dir);
        store.put(key(1, 1), pending("Only_1"));
        store.flush();
        Files.delete(dir.resolve(ABANDONED_FILE));

        PopulationStore reopened = new PopulationStore(dir);
        PopulationStore.LoadReport report = reopened.load();
        assertTrue(report.usable());
        assertTrue(has(report.messages(), ABANDONED_FILE + " is missing"), report.messages().toString());
        assertTrue(reopened.find(key(1, 1)).isPresent());
    }

    @Test
    void anAbandonedRecordHandWrittenIntoTheJsonMovesToTheKeyLog(@TempDir Path dir) throws IOException {
        write(dir.resolve(POPULATIONS_FILE), "{\"dataVersion\":1,\"structures\":{\"" + k(4, 4) + "\":{\"status\":\"ABANDONED\"}}}");
        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable());
        assertEquals(new PopulationCounts(1, 0, 0, 0, 0, 0), store.counts());
        assertTrue(store.nonAbandoned().isEmpty());
        assertTrue(store.saveIfDirty());
        assertEquals(List.of(k(4, 4)), lines(dir.resolve(ABANDONED_FILE)));
        assertFalse(read(dir.resolve(POPULATIONS_FILE)).contains("ABANDONED"));
    }

    // ---------------------------------------------------------------- a real process death

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Test
    void aProcessKilledWithoutAnyCleanupKeepsEveryAbandonedDecisionAndTheLastSave(@TempDir Path dir) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path java = Files.exists(javaHome.resolve("bin/java.exe")) ? javaHome.resolve("bin/java.exe") : javaHome.resolve("bin/java");
        assumeTrue(Files.exists(java), "no java launcher to fork");
        String classpath = String.join(File.pathSeparator,
                location(PopulationStore.class), location(CrashChild.class),
                location(com.google.gson.Gson.class), location(org.slf4j.LoggerFactory.class));

        Process child = new ProcessBuilder(java.toString(), "-cp", classpath, CrashChild.class.getName(), dir.toString())
                .redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child did not finish: " + output);
        assertEquals(137, child.exitValue(), "the child must die by halt(), not exit cleanly or fail: " + output);

        PopulationStore store = new PopulationStore(dir);
        PopulationStore.LoadReport report = store.load();
        assertTrue(report.usable(), report.messages().toString());
        assertEquals(CrashChild.SAVED_ABANDONED + CrashChild.UNSAVED_ABANDONED, store.counts().abandoned(),
                "every abandoned decision was durable the moment it was made");
        for (int i = 0; i < CrashChild.UNSAVED_ABANDONED; i++) {
            assertTrue(store.find(key(2000 + i, 0)).isPresent(), "unsaved abandoned decision " + i);
        }
        assertTrue(store.find(key(1, 1)).isPresent(), "the saved record survives");
        assertTrue(store.find(key(2, 2)).isEmpty(), "an unsaved record may be lost");
        assertFalse(has(report.messages(), "malformed"), report.messages().toString());
    }
}
