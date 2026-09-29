package io.github.zoyluo.minecraftai.task;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Per-scan memo of {@link SurfaceCheck#isOnSurface} answers, keyed by (x, z) column.
 *
 * <p>Whether a spot is on the surface is monotonic in y within one column: a spot has a roof above it
 * exactly when some roof block lies higher, so if a spot at y is roofed then every spot below it in the
 * same column is roofed too, and if a spot at y is open then every spot above it is open too. Each
 * candidate cell of a lighting scan used to read its whole column to the top of the world on its own;
 * this remembers, per column, the highest spot found roofed and the lowest found open and only reads
 * the world for a spot those two bounds do not already decide. The answers are exactly the ones
 * {@link SurfaceCheck#isOnSurface} would give, provided the world does not change during the scan.
 * Not thread-safe; create one per scan.
 */
final class SurfaceColumnMemo {
    private static final class Bounds {
        int highestRoofedY = Integer.MIN_VALUE;
        int lowestOpenY = Integer.MAX_VALUE;
    }

    private final Map<Long, Bounds> columns = new HashMap<>();
    private int computed;

    /** @param compute the real, uncached answer for (x, y, z); called only when the memo cannot decide */
    boolean isOnSurface(int x, int y, int z, BooleanSupplier compute) {
        Bounds bounds = columns.computeIfAbsent(key(x, z), ignored -> new Bounds());
        if (y <= bounds.highestRoofedY) {
            return false;
        }
        if (y >= bounds.lowestOpenY) {
            return true;
        }
        boolean open = compute.getAsBoolean();
        computed++;
        if (open) {
            bounds.lowestOpenY = y;
        } else {
            bounds.highestRoofedY = y;
        }
        return open;
    }

    /** How many times the real computation ran (for tests and diagnostics). */
    int computedCount() {
        return computed;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
