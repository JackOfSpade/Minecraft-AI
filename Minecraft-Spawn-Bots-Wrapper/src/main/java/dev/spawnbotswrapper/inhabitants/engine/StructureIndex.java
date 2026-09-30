package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Spatial index of the structures that can host bots (occupied ones), by 16x16-chunk (256 block) cells per
 * dimension: the allocation only ever looks at the structures whose bounding box reaches into the relevance area of
 * a player, never at the whole store. A structure is listed in every cell its bounding box overlaps (usually one to
 * four), so a lookup for a square around a player visits a handful of cells.
 * <p>
 * Pure data structure: no Minecraft, no store, no clock. Server thread only.
 */
final class StructureIndex {
    /** Blocks per cell edge: 16 chunks. */
    static final int CELL_SHIFT = 8;

    /** One indexed structure. {@code stamp} is scratch space for de-duplicating a query. */
    static final class Entry {
        final StructureKey key;
        final IntBox box;
        int stamp;

        Entry(StructureKey key, IntBox box) {
            this.key = key;
            this.box = box;
        }
    }

    private final Map<String, Map<Long, List<Entry>>> cellsByDimension = new HashMap<>();
    private final Map<StructureKey, Entry> byKey = new HashMap<>();
    private int queryStamp;

    int size() {
        return byKey.size();
    }

    boolean contains(StructureKey key) {
        return byKey.containsKey(key);
    }

    Entry find(StructureKey key) {
        return byKey.get(key);
    }

    /** Adds (or replaces) a structure. */
    void put(StructureKey key, IntBox box) {
        remove(key);
        Entry entry = new Entry(key, box);
        byKey.put(key, entry);
        Map<Long, List<Entry>> cells = cellsByDimension.computeIfAbsent(key.dimension(), d -> new HashMap<>());
        int x0 = box.minX() >> CELL_SHIFT;
        int x1 = box.maxX() >> CELL_SHIFT;
        int z0 = box.minZ() >> CELL_SHIFT;
        int z1 = box.maxZ() >> CELL_SHIFT;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                cells.computeIfAbsent(cellKey(cx, cz), k -> new ArrayList<>(2)).add(entry);
            }
        }
    }

    /** Removes a structure; false when it was not indexed. */
    boolean remove(StructureKey key) {
        Entry entry = byKey.remove(key);
        if (entry == null) {
            return false;
        }
        Map<Long, List<Entry>> cells = cellsByDimension.get(key.dimension());
        if (cells == null) {
            return true;
        }
        IntBox box = entry.box;
        for (int cx = box.minX() >> CELL_SHIFT; cx <= box.maxX() >> CELL_SHIFT; cx++) {
            for (int cz = box.minZ() >> CELL_SHIFT; cz <= box.maxZ() >> CELL_SHIFT; cz++) {
                long ck = cellKey(cx, cz);
                List<Entry> list = cells.get(ck);
                if (list != null) {
                    list.remove(entry);
                    if (list.isEmpty()) {
                        cells.remove(ck);
                    }
                }
            }
        }
        return true;
    }

    void clear() {
        cellsByDimension.clear();
        byKey.clear();
    }

    /**
     * Calls {@code sink} once for every indexed structure of {@code dimension} whose box may lie within {@code radius}
     * blocks (horizontally) of {@code (x, z)}: a superset of the exact answer, cheap to compute. The caller refines
     * with the real distance.
     */
    void forEachNear(String dimension, double x, double z, double radius, Consumer<Entry> sink) {
        Map<Long, List<Entry>> cells = cellsByDimension.get(dimension);
        if (cells == null || cells.isEmpty()) {
            return;
        }
        int stamp = ++queryStamp;
        int x0 = (int) Math.floor(x - radius) >> CELL_SHIFT;
        int x1 = (int) Math.floor(x + radius) >> CELL_SHIFT;
        int z0 = (int) Math.floor(z - radius) >> CELL_SHIFT;
        int z1 = (int) Math.floor(z + radius) >> CELL_SHIFT;
        long span = (long) (x1 - x0 + 1) * (z1 - z0 + 1);
        if (span > cells.size()) {
            // A huge area next to few occupied cells: walking the cells that exist is cheaper than walking the area.
            for (Map.Entry<Long, List<Entry>> cell : cells.entrySet()) {
                int cx = (int) (cell.getKey() >> 32);
                int cz = (int) (long) cell.getKey();
                if (cx >= x0 && cx <= x1 && cz >= z0 && cz <= z1) {
                    visit(cell.getValue(), stamp, sink);
                }
            }
            return;
        }
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                List<Entry> list = cells.get(cellKey(cx, cz));
                if (list != null) {
                    visit(list, stamp, sink);
                }
            }
        }
    }

    private static void visit(List<Entry> list, int stamp, Consumer<Entry> sink) {
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            if (e.stamp != stamp) {
                e.stamp = stamp;
                sink.accept(e);
            }
        }
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
