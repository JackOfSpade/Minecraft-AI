package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class ChunkGridTest {

    private static Set<StructureKey> within(ChunkGrid grid, String dim, int cx, int cz, int radius) {
        Set<StructureKey> out = new HashSet<>();
        grid.forEachWithin(dim, cx, cz, radius, (d, id, x, z) -> assertTrue(out.add(new StructureKey(d, id, x, z)),
                "visited twice: " + d + id + x + "," + z));
        return out;
    }

    private static Set<StructureKey> bruteForce(List<StructureKey> all, String dim, int cx, int cz, int radius) {
        Set<StructureKey> out = new HashSet<>();
        if (radius < 0) {
            return out;
        }
        for (StructureKey k : all) {
            if (k.dimension().equals(dim)
                    && Math.abs((long) k.chunkX() - cx) <= radius
                    && Math.abs((long) k.chunkZ() - cz) <= radius) {
                out.add(k);
            }
        }
        return out;
    }

    @Test
    void addContainsRemoveAndSize() {
        ChunkGrid grid = new ChunkGrid();
        StructureKey a = new StructureKey("minecraft:overworld", "minecraft:village_plains", 3, -4);
        assertFalse(grid.contains(a));
        assertTrue(grid.add(a));
        assertFalse(grid.add(a), "a second add of the same key reports false");
        assertEquals(1, grid.size());
        assertTrue(grid.contains(a));
        assertFalse(grid.contains(new StructureKey("minecraft:overworld", "minecraft:village_desert", 3, -4)), "id is part of the key");
        assertFalse(grid.contains(new StructureKey("minecraft:the_nether", "minecraft:village_plains", 3, -4)), "dimension is part of the key");
        assertFalse(grid.contains(new StructureKey("minecraft:overworld", "minecraft:village_plains", 3, -5)));

        assertFalse(grid.remove(new StructureKey("minecraft:overworld", "minecraft:village_plains", 9, 9)));
        assertTrue(grid.remove(a));
        assertFalse(grid.remove(a));
        assertEquals(0, grid.size());
        assertTrue(within(grid, "minecraft:overworld", 3, -4, 100).isEmpty());
    }

    @Test
    void severalStructuresMayStartInTheSameChunk() {
        ChunkGrid grid = new ChunkGrid();
        for (int i = 0; i < 40; i++) { // more than one cell's initial capacity, forcing growth
            assertTrue(grid.add("d:d", "s:s" + i, 5, 5));
        }
        assertEquals(40, grid.size());
        assertEquals(40, within(grid, "d:d", 5, 5, 0).size());
        for (int i = 0; i < 40; i += 2) {
            assertTrue(grid.remove(new StructureKey("d:d", "s:s" + i, 5, 5)));
        }
        assertEquals(20, grid.size());
        assertEquals(20, within(grid, "d:d", 5, 5, 0).size());
        assertTrue(grid.contains(new StructureKey("d:d", "s:s1", 5, 5)));
        assertFalse(grid.contains(new StructureKey("d:d", "s:s0", 5, 5)));
    }

    @Test
    void cellBoundariesAreExactOnBothSidesOfZero() {
        ChunkGrid grid = new ChunkGrid();
        int[] edge = {-33, -32, -17, -16, -15, -1, 0, 1, 15, 16, 17, 31, 32};
        List<StructureKey> all = new ArrayList<>();
        for (int x : edge) {
            for (int z : edge) {
                StructureKey k = new StructureKey("d:d", "s:s", x, z);
                grid.add(k);
                all.add(k);
            }
        }
        for (int cx : edge) {
            for (int cz : edge) {
                for (int radius = 0; radius <= 34; radius++) {
                    assertEquals(bruteForce(all, "d:d", cx, cz, radius), within(grid, "d:d", cx, cz, radius),
                            "center " + cx + "," + cz + " radius " + radius);
                }
            }
        }
    }

    @Test
    void randomDataAgreesWithABruteForceScan() {
        SplittableRandom rnd = new SplittableRandom(12345);
        ChunkGrid grid = new ChunkGrid();
        List<StructureKey> all = new ArrayList<>();
        Set<StructureKey> seen = new HashSet<>();
        String[] dims = {"minecraft:overworld", "minecraft:the_nether", "mod:some/dim"};
        String[] ids = {"minecraft:village_plains", "minecraft:igloo", "mod:deep/nested/structure"};
        while (all.size() < 6000) {
            StructureKey k = new StructureKey(dims[rnd.nextInt(dims.length)], ids[rnd.nextInt(ids.length)],
                    rnd.nextInt(-400, 400), rnd.nextInt(-400, 400));
            if (seen.add(k)) {
                grid.add(k);
                all.add(k);
            }
        }
        for (int q = 0; q < 400; q++) {
            String dim = dims[rnd.nextInt(dims.length)];
            int cx = rnd.nextInt(-500, 500);
            int cz = rnd.nextInt(-500, 500);
            int radius = switch (q % 4) {
                case 0 -> rnd.nextInt(0, 3);
                case 1 -> rnd.nextInt(0, 40);
                case 2 -> rnd.nextInt(0, 300);
                default -> rnd.nextInt(0, 5000);
            };
            assertEquals(bruteForce(all, dim, cx, cz, radius), within(grid, dim, cx, cz, radius),
                    dim + " " + cx + "," + cz + " r=" + radius);
        }
        // removal keeps the index exact
        for (int i = 0; i < 3000; i++) {
            StructureKey k = all.get(i);
            assertTrue(grid.remove(k));
        }
        List<StructureKey> rest = all.subList(3000, all.size());
        assertEquals(rest.size(), grid.size());
        for (int q = 0; q < 100; q++) {
            String dim = dims[rnd.nextInt(dims.length)];
            int cx = rnd.nextInt(-500, 500);
            int cz = rnd.nextInt(-500, 500);
            int radius = rnd.nextInt(0, 200);
            assertEquals(bruteForce(rest, dim, cx, cz, radius), within(grid, dim, cx, cz, radius));
        }
    }

    @Test
    void extremeCoordinatesAndRadiiDoNotOverflow() {
        ChunkGrid grid = new ChunkGrid();
        int[] values = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        List<StructureKey> all = new ArrayList<>();
        for (int x : values) {
            for (int z : values) {
                StructureKey k = new StructureKey("d:d", "s:s", x, z);
                grid.add(k);
                all.add(k);
            }
        }
        int[] radii = {0, 1, 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int cx : values) {
            for (int cz : values) {
                for (int radius : radii) {
                    assertEquals(bruteForce(all, "d:d", cx, cz, radius), within(grid, "d:d", cx, cz, radius),
                            "center " + cx + "," + cz + " radius " + radius);
                }
            }
        }
    }

    @Test
    void aNegativeRadiusAndAnUnknownDimensionVisitNothing() {
        ChunkGrid grid = new ChunkGrid();
        grid.add("d:d", "s:s", 0, 0);
        assertTrue(within(grid, "d:d", 0, 0, -1).isEmpty());
        assertTrue(within(grid, "d:other", 0, 0, 10).isEmpty());
        assertTrue(within(grid, "d:d", 0, 0, Integer.MIN_VALUE).isEmpty());
    }

    @Test
    void forEachVisitsEverythingExactlyOnce() {
        ChunkGrid grid = new ChunkGrid();
        Set<StructureKey> expected = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            StructureKey k = new StructureKey(i % 2 == 0 ? "d:a" : "d:b", "s:" + (i % 7), i * 3 - 700, i * 11 - 2000);
            grid.add(k);
            expected.add(k);
        }
        Set<StructureKey> seen = new HashSet<>();
        grid.forEach((d, id, x, z) -> assertTrue(seen.add(new StructureKey(d, id, x, z))));
        assertEquals(expected, seen);
    }

    @Test
    void structureIdStringsAreSharedBetweenEntries() {
        ChunkGrid grid = new ChunkGrid();
        // Distinct String objects with equal content, as a file parser produces them.
        grid.add("d:d", new String("minecraft:village_plains"), 1, 1);
        grid.add("d:d", new String("minecraft:village_plains"), 500, 500);
        List<String> ids = new ArrayList<>();
        grid.forEach((d, id, x, z) -> ids.add(id));
        assertEquals(2, ids.size());
        assertSame(ids.get(0), ids.get(1), "keys loaded from disk must not each keep their own copy of the id");
    }

    @Test
    void clearEmptiesTheGrid() {
        ChunkGrid grid = new ChunkGrid();
        grid.add("d:d", "s:s", 1, 1);
        grid.clear();
        assertEquals(0, grid.size());
        assertFalse(grid.contains(new StructureKey("d:d", "s:s", 1, 1)));
        assertTrue(grid.add("d:d", "s:s", 1, 1));
    }
}
