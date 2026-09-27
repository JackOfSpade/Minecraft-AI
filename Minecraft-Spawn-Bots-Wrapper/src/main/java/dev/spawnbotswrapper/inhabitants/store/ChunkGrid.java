package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Spatial index over structure keys: dimension, then a coarse grid of 16x16-chunk cells, then a few
 * entries per cell. It exists because a well-explored world has hundreds of thousands of abandoned
 * structures, so answering "what is near this chunk" must touch only the handful of cells around it,
 * and because holding each key as an object would cost ~100 bytes apiece: an entry here is two ints and
 * a shared string reference.
 * <p>
 * Structure ids and dimension ids are canonicalised, so keys loaded from disk share their strings.
 * Not thread-safe; the store serialises access.
 */
final class ChunkGrid {
    private static final int CELL_SHIFT = 4;

    @FunctionalInterface
    interface Visitor {
        void accept(String dimension, String structureId, int chunkX, int chunkZ);
    }

    private static final class Cell {
        int size;
        int[] xs = new int[4];
        int[] zs = new int[4];
        String[] ids = new String[4];

        int indexOf(int x, int z, String id) {
            for (int i = 0; i < size; i++) {
                if (xs[i] == x && zs[i] == z && ids[i].equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        void add(int x, int z, String id) {
            if (size == xs.length) {
                int capacity = size * 2;
                xs = Arrays.copyOf(xs, capacity);
                zs = Arrays.copyOf(zs, capacity);
                ids = Arrays.copyOf(ids, capacity);
            }
            xs[size] = x;
            zs[size] = z;
            ids[size] = id;
            size++;
        }

        void removeAt(int index) {
            size--;
            xs[index] = xs[size];
            zs[index] = zs[size];
            ids[index] = ids[size];
            ids[size] = null;
        }
    }

    private static final class Dimension {
        final String id;
        final Map<Long, Cell> cells = new HashMap<>();

        Dimension(String id) {
            this.id = id;
        }
    }

    private final Map<String, Dimension> dimensions = new HashMap<>();
    private final Map<String, String> canonicalIds = new HashMap<>();
    private int size;

    int size() {
        return size;
    }

    void clear() {
        dimensions.clear();
        canonicalIds.clear();
        size = 0;
    }

    /** Returns false if the key was already present. */
    boolean add(StructureKey key) {
        return add(key.dimension(), key.structureId(), key.chunkX(), key.chunkZ());
    }

    boolean add(String dimension, String structureId, int chunkX, int chunkZ) {
        String id = canonicalIds.computeIfAbsent(structureId, s -> s);
        Dimension d = dimensions.computeIfAbsent(dimension, Dimension::new);
        Cell cell = d.cells.computeIfAbsent(cellKey(chunkX >> CELL_SHIFT, chunkZ >> CELL_SHIFT), k -> new Cell());
        if (cell.indexOf(chunkX, chunkZ, id) >= 0) {
            return false;
        }
        cell.add(chunkX, chunkZ, id);
        size++;
        return true;
    }

    boolean contains(StructureKey key) {
        Dimension d = dimensions.get(key.dimension());
        if (d == null) {
            return false;
        }
        Cell cell = d.cells.get(cellKey(key.chunkX() >> CELL_SHIFT, key.chunkZ() >> CELL_SHIFT));
        return cell != null && cell.indexOf(key.chunkX(), key.chunkZ(), key.structureId()) >= 0;
    }

    boolean remove(StructureKey key) {
        Dimension d = dimensions.get(key.dimension());
        if (d == null) {
            return false;
        }
        long cellKey = cellKey(key.chunkX() >> CELL_SHIFT, key.chunkZ() >> CELL_SHIFT);
        Cell cell = d.cells.get(cellKey);
        if (cell == null) {
            return false;
        }
        int index = cell.indexOf(key.chunkX(), key.chunkZ(), key.structureId());
        if (index < 0) {
            return false;
        }
        cell.removeAt(index);
        if (cell.size == 0) {
            d.cells.remove(cellKey);
        }
        size--;
        return true;
    }

    /**
     * Visits every entry of {@code dimension} whose start chunk is within {@code radius} chunks of the
     * given chunk on BOTH axes (Chebyshev, i.e. a square), in no particular order. The visitor must not
     * modify the grid.
     */
    void forEachWithin(String dimension, int chunkX, int chunkZ, int radius, Visitor visitor) {
        if (radius < 0) {
            return;
        }
        Dimension d = dimensions.get(dimension);
        if (d == null) {
            return;
        }
        int cellMinX = cell(Math.max((long) chunkX - radius, Integer.MIN_VALUE));
        int cellMaxX = cell(Math.min((long) chunkX + radius, Integer.MAX_VALUE));
        int cellMinZ = cell(Math.max((long) chunkZ - radius, Integer.MIN_VALUE));
        int cellMaxZ = cell(Math.min((long) chunkZ + radius, Integer.MAX_VALUE));

        long cellsInRange = (long) (cellMaxX - cellMinX + 1) * (long) (cellMaxZ - cellMinZ + 1);
        if (cellsInRange <= d.cells.size()) {
            for (int cx = cellMinX; cx <= cellMaxX; cx++) {
                for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                    Cell cell = d.cells.get(cellKey(cx, cz));
                    if (cell != null) {
                        visitCell(d, cell, chunkX, chunkZ, radius, visitor);
                    }
                }
            }
        } else {
            // A huge radius would otherwise walk millions of empty cells; walk the occupied ones instead.
            for (Map.Entry<Long, Cell> e : d.cells.entrySet()) {
                int cx = (int) (e.getKey() >> 32);
                int cz = (int) (long) e.getKey();
                if (cx >= cellMinX && cx <= cellMaxX && cz >= cellMinZ && cz <= cellMaxZ) {
                    visitCell(d, e.getValue(), chunkX, chunkZ, radius, visitor);
                }
            }
        }
    }

    /** Visits every entry. The visitor must not modify the grid. */
    void forEach(Visitor visitor) {
        for (Dimension d : dimensions.values()) {
            for (Cell cell : d.cells.values()) {
                for (int i = 0; i < cell.size; i++) {
                    visitor.accept(d.id, cell.ids[i], cell.xs[i], cell.zs[i]);
                }
            }
        }
    }

    private static void visitCell(Dimension d, Cell cell, int chunkX, int chunkZ, int radius, Visitor visitor) {
        for (int i = 0; i < cell.size; i++) {
            if (Math.abs((long) cell.xs[i] - chunkX) <= radius && Math.abs((long) cell.zs[i] - chunkZ) <= radius) {
                visitor.accept(d.id, cell.ids[i], cell.xs[i], cell.zs[i]);
            }
        }
    }

    private static int cell(long chunk) {
        return (int) (chunk >> CELL_SHIFT);
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }
}
