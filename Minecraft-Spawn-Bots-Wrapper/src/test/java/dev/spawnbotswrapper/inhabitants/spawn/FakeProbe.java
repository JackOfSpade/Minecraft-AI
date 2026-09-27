package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Map-backed world for the planner tests. Everything not painted is {@link Cell#EMPTY} (air), so a floor
 * has to be painted explicitly; regions can be marked unloaded, a world border and height limits are
 * configurable, and reads are counted so tests can bound the work.
 */
final class FakeProbe implements BlockProbe {
    /** The default floor block: feet stand at {@code FLOOR + 1}. */
    static final int FLOOR = 63;
    static final int FEET = FLOOR + 1;

    private record P(int x, int y, int z) {
    }

    private final Map<P, Cell> cells = new HashMap<>();
    private final List<IntBox> unloaded = new ArrayList<>();
    private final int minY;
    private final int maxY;
    private IntBox border;
    private long reads;
    private long outOfRangeReads;

    FakeProbe() {
        this(-64, 319);
    }

    FakeProbe(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    FakeProbe set(int x, int y, int z, Cell cell) {
        cells.put(new P(x, y, z), cell);
        return this;
    }

    /** Fills the inclusive box with one cell type. */
    FakeProbe fill(int x0, int y0, int z0, int x1, int y1, int z1, Cell cell) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    set(x, y, z, cell);
                }
            }
        }
        return this;
    }

    /** A one-block-thick standable floor layer at {@code floorY}. */
    FakeProbe floor(int x0, int z0, int x1, int z1, int floorY) {
        return fill(x0, floorY, z0, x1, floorY, z1, Cell.SOLID_STANDABLE);
    }

    FakeProbe floor(int x0, int z0, int x1, int z1) {
        return floor(x0, z0, x1, z1, FLOOR);
    }

    FakeProbe unloaded(int x0, int z0, int x1, int z1) {
        unloaded.add(new IntBox(x0, minY, z0, x1, maxY, z1));
        return this;
    }

    FakeProbe border(int minX, int minZ, int maxX, int maxZ) {
        this.border = new IntBox(minX, minY, minZ, maxX, maxY, maxZ);
        return this;
    }

    long reads() {
        return reads;
    }

    long outOfRangeReads() {
        return outOfRangeReads;
    }

    @Override
    public Cell cell(int x, int y, int z) {
        reads++;
        if (y < minY || y > maxY) {
            outOfRangeReads++;
            return Cell.UNLOADED;
        }
        for (IntBox box : unloaded) {
            if (box.contains(x, y, z)) {
                return Cell.UNLOADED;
            }
        }
        return cells.getOrDefault(new P(x, y, z), Cell.EMPTY);
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public boolean insideBorder(int x, int z) {
        return border == null || (x >= border.minX() && x <= border.maxX() && z >= border.minZ() && z <= border.maxZ());
    }
}
