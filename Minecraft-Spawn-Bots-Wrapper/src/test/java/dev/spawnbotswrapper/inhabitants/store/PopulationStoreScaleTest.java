package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A well-explored world: 100 000 abandoned structures. The limits are deliberately generous (CI disks and
 * virus scanners are slow); the point is that nothing here is quadratic, not a benchmark.
 */
class PopulationStoreScaleTest {
    private static final int COUNT = 100_000;
    private static final String[] DIMENSIONS = {OVERWORLD, NETHER, "modid:custom/dimension"};
    private static final String[] STRUCTURES = {
            "minecraft:village_plains", "minecraft:village_desert", "minecraft:pillager_outpost", "minecraft:igloo",
            "minecraft:mineshaft", "minecraft:desert_pyramid", "minecraft:jungle_pyramid", "minecraft:swamp_hut",
            "minecraft:shipwreck", "minecraft:ruined_portal", "minecraft:stronghold", "minecraft:trail_ruins",
            "modid:some/deeply/nested/structure", "othermod:tower"};

    private static List<StructureKey> keys;

    @BeforeAll
    static void generate() {
        SplittableRandom rnd = new SplittableRandom(20240607L);
        Set<StructureKey> unique = new LinkedHashSet<>();
        while (unique.size() < COUNT) {
            unique.add(new StructureKey(DIMENSIONS[rnd.nextInt(DIMENSIONS.length)], STRUCTURES[rnd.nextInt(STRUCTURES.length)],
                    rnd.nextInt(-1500, 1500), rnd.nextInt(-1500, 1500)));
        }
        keys = List.copyOf(unique);
    }

    private static Path writeKeyFile(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(PopulationStore.ABANDONED_FILE);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (StructureKey k : keys) {
                w.write(k.asString());
                w.write('\n');
            }
        }
        return file;
    }

    private static List<StructureKey> expectedNearby(List<StructureKey> universe, String dim, int cx, int cz, int radius) {
        List<StructureKey> out = new ArrayList<>();
        for (StructureKey k : universe) {
            if (k.dimension().equals(dim) && Math.abs((long) k.chunkX() - cx) <= radius && Math.abs((long) k.chunkZ() - cz) <= radius) {
                out.add(k);
            }
        }
        out.sort(Comparator.comparingDouble((StructureKey k) -> {
            double dx = k.chunkX() - cx;
            double dz = k.chunkZ() - cz;
            return dx * dx + dz * dz;
        }).thenComparing(StructureKey::asString));
        return out;
    }

    @Test
    void loadingAndQueryingAHundredThousandAbandonedStructuresIsFastAndCorrect(@TempDir Path dir) throws IOException {
        Path keyFile = writeKeyFile(dir);
        byte[] original = Files.readAllBytes(keyFile);
        PopulationStore store = new PopulationStore(dir);

        long t0 = System.nanoTime();
        PopulationStore.LoadReport report = store.load();
        long loadMillis = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(report.usable(), report.messages().toString());
        assertEquals(COUNT, store.counts().abandoned());
        assertTrue(loadMillis < 20_000, "load took " + loadMillis + " ms");

        // point lookups
        SplittableRandom rnd = new SplittableRandom(7);
        long t1 = System.nanoTime();
        for (int i = 0; i < COUNT; i++) {
            assertTrue(store.find(keys.get(rnd.nextInt(COUNT))).isPresent());
        }
        StructureKey never = new StructureKey(OVERWORLD, "minecraft:village_plains", 99_999, 99_999);
        assertTrue(store.find(never).isEmpty());
        long findMillis = (System.nanoTime() - t1) / 1_000_000;
        assertTrue(findMillis < 10_000, "100k find() calls took " + findMillis + " ms");

        // many small area queries: a linear scan per call would need billions of comparisons here
        long t2 = System.nanoTime();
        long worstMicros = 0;
        int results = 0;
        for (int i = 0; i < 2000; i++) {
            String dim = DIMENSIONS[rnd.nextInt(DIMENSIONS.length)];
            long q0 = System.nanoTime();
            results += store.nearby(dim, rnd.nextInt(-1500, 1500), rnd.nextInt(-1500, 1500), 32).size();
            worstMicros = Math.max(worstMicros, (System.nanoTime() - q0) / 1000);
        }
        long queryMillis = (System.nanoTime() - t2) / 1_000_000;
        assertTrue(results > 1000, "the queries should actually hit something, got " + results);
        assertTrue(queryMillis < 10_000, "2000 nearby() calls took " + queryMillis + " ms");
        assertTrue(worstMicros < 2_000_000, "the slowest nearby() took " + worstMicros + " us");

        // correctness against a brute-force scan, including ordering, radius edge cases and a huge radius
        int[][] cases = {{0, 0, 32}, {-1500, 1499, 10}, {1499, -1500, 64}, {123, -456, 0}, {5, 5, 200}, {0, 0, 5000}};
        for (String dim : DIMENSIONS) {
            for (int[] c : cases) {
                List<StructureKey> expected = expectedNearby(keys, dim, c[0], c[1], c[2]);
                List<StructureKey> actual = store.nearby(dim, c[0], c[1], c[2]).stream().map(Map.Entry::getKey).toList();
                assertEquals(expected, actual, dim + " " + c[0] + "," + c[1] + " r=" + c[2]);
            }
        }

        // an abandoned decision costs one appended line, not a rewrite of the file
        List<StructureKey> fresh = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            StructureKey k = new StructureKey(OVERWORLD, "minecraft:village_plains", 10_000 + i, 10_000);
            fresh.add(k);
            store.put(k, StructureRecord.abandoned());
        }
        byte[] afterAppends = Files.readAllBytes(keyFile);
        assertArrayEquals(original, java.util.Arrays.copyOf(afterAppends, original.length), "existing lines must be untouched by appends");
        assertEquals(COUNT + 30, lines(keyFile).size());

        // the JSON never carries them, however many there are
        store.put(key(1, 1), pending("Resident_1"));
        assertTrue(store.saveIfDirty());
        assertTrue(Files.size(dir.resolve(PopulationStore.POPULATIONS_FILE)) < 20_000,
                "populations.json must stay small: " + Files.size(dir.resolve(PopulationStore.POPULATIONS_FILE)));

        // a fresh instance sees all of it
        long t3 = System.nanoTime();
        PopulationStore reopened = loaded(dir);
        long reloadMillis = (System.nanoTime() - t3) / 1_000_000;
        assertTrue(reloadMillis < 20_000, "reload took " + reloadMillis + " ms");
        assertEquals(COUNT + 30, reopened.counts().abandoned());
        assertEquals(1, reopened.counts().pending());
        for (StructureKey k : fresh) {
            assertTrue(reopened.find(k).isPresent());
        }

        // removing one rewrites the file atomically without losing any other key
        StructureKey victim = keys.get(COUNT / 2);
        long t4 = System.nanoTime();
        assertTrue(reopened.remove(victim));
        long removeMillis = (System.nanoTime() - t4) / 1_000_000;
        assertTrue(removeMillis < 20_000, "remove took " + removeMillis + " ms");
        assertEquals(COUNT + 29, lines(keyFile).size());
        Set<String> written = new HashSet<>(lines(keyFile));
        assertFalse(written.contains(victim.asString()));
        assertEquals(COUNT + 29, written.size(), "no duplicates or losses");
        PopulationStore afterRemove = loaded(dir);
        assertTrue(afterRemove.find(victim).isEmpty());
        assertEquals(COUNT + 29, afterRemove.counts().abandoned());
    }

    @Test
    void mixedAbandonedAndPopulatedStructuresAreMergedNearestFirst(@TempDir Path dir) throws IOException {
        writeKeyFile(dir);
        PopulationStore store = loaded(dir);
        SplittableRandom rnd = new SplittableRandom(99);
        List<StructureKey> universe = new ArrayList<>(keys);
        Set<StructureKey> taken = new HashSet<>(keys);
        int added = 0;
        while (added < 400) {
            StructureKey k = new StructureKey(DIMENSIONS[rnd.nextInt(DIMENSIONS.length)], "minecraft:fortress",
                    rnd.nextInt(-1500, 1500), rnd.nextInt(-1500, 1500));
            if (taken.add(k)) {
                store.put(k, pending("Resident_" + added));
                universe.add(k);
                added++;
            }
        }
        assertEquals(COUNT, store.counts().abandoned());
        assertEquals(400, store.counts().pending());

        for (int i = 0; i < 40; i++) {
            String dim = DIMENSIONS[rnd.nextInt(DIMENSIONS.length)];
            int cx = rnd.nextInt(-1500, 1500);
            int cz = rnd.nextInt(-1500, 1500);
            int radius = rnd.nextInt(0, 150);
            List<StructureKey> expected = expectedNearby(universe, dim, cx, cz, radius);
            List<Map.Entry<StructureKey, StructureRecord>> actual = store.nearby(dim, cx, cz, radius);
            assertEquals(expected, actual.stream().map(Map.Entry::getKey).toList(), dim + " " + cx + "," + cz + " r=" + radius);
            for (Map.Entry<StructureKey, StructureRecord> e : actual) {
                boolean populated = e.getKey().structureId().equals("minecraft:fortress");
                assertEquals(populated ? StructureStatus.OCCUPIED_PENDING : StructureStatus.ABANDONED, e.getValue().status);
            }
        }
    }
}
