package dev.spawnbotswrapper.inhabitants.spawn;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-call memo in front of a {@link BlockProbe}.
 * <p>
 * Column scans and patrol segments read the same cells over and over (every quarter block of a segment,
 * every candidate that shares a wall). A real probe answers each read through a chunk lookup, so one memo
 * per planner call keeps the cost proportional to the number of DISTINCT cells touched. The memo dies with
 * the call, so it can never serve a block that changed later.
 * <p>
 * Reads outside {@code [minY, maxY]} are answered {@link Cell#UNLOADED} without asking the probe: the probe
 * contract lets it treat "outside the build height" as "no chunk", and a position we cannot read is a
 * position we cannot claim is safe.
 */
final class ProbeView {
    private final BlockProbe probe;
    private final int minY;
    private final int maxY;
    private final Map<Long, Cell> memo = new HashMap<>();

    ProbeView(BlockProbe probe) {
        this.probe = probe;
        this.minY = probe.minY();
        this.maxY = probe.maxY();
    }

    int minY() {
        return minY;
    }

    int maxY() {
        return maxY;
    }

    boolean insideBorder(int x, int z) {
        return probe.insideBorder(x, z);
    }

    Cell cell(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return Cell.UNLOADED;
        }
        long key = pack(x, y, z);
        Cell known = memo.get(key);
        if (known == null) {
            Cell fresh = probe.cell(x, y, z);
            known = fresh == null ? Cell.UNLOADED : fresh;
            memo.put(key, known);
        }
        return known;
    }

    /** 26 bits per horizontal axis (the world is +-30M) and 12 for Y (+-2048, above any vanilla height). */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}
