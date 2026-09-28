package io.github.zoyluo.minecraftai.mining.assist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared, cross-bot cache of R4 advisor verdicts (mining-assist design 6.6): "Cache key: (dimension, 24x16x24
 * coarse cell, sorted top-6 block ids), shared across bots. TTL is 12000 ticks for stop and 6000 for
 * continue. Mandatory never reads or writes it." Static, server wide, plain {@code HashMap}, server thread
 * only -- the same idiom as {@link MissionAssistLedger} and {@link PoiRegistry}.
 *
 * <p>"Block ids" here are the {@link PoiBucket} names {@link PoiPrompt} itself reports as evidence (see that
 * class's javadoc for why): two candidates that were classified into the same top buckets in the same coarse
 * cell are treated as the same site for caching purposes.</p>
 */
public final class PoiCache {
    /** Coarse cell size on x and z (design's "24x16x24": x, y, z). */
    public static final int CELL_XZ = 24;
    /** Coarse cell size on y (design: 16). */
    public static final int CELL_Y = 16;
    /** Design: at most the top 6 block/bucket ids feed the key, sorted for order independence. */
    public static final int TOP_IDS_FOR_KEY = 6;
    public static final int STOP_TTL_TICKS = 12000;
    public static final int CONTINUE_TTL_TICKS = 6000;
    private static final int NEVER = Integer.MIN_VALUE;

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static int lastPruneTick = NEVER;

    private PoiCache() {
    }

    /** One cached verdict. {@code stop} is the design's STOP/CONTINUE outcome (mandatory is never cached, so
     * only those two exist here); {@code label} lets a cache-hit stop render the same notice a fresh one
     * would. */
    public record Entry(boolean stop, String label, int recordedTick, int ttlTicks) {
        public boolean expired(int nowTick) {
            return (long) nowTick - recordedTick > ttlTicks;
        }
    }

    /**
     * The cache key: {@code dimensionKey + "@" + cellX + "," + cellY + "," + cellZ + "#" + sortedTopIds},
     * where the cell coordinates are {@code Math.floorDiv} of the anchor by the design's coarse cell size and
     * {@code sortedTopIds} is {@code topBlockIds} (already the top {@link #TOP_IDS_FOR_KEY} by count, per
     * {@link PoiPrompt}'s evidence ordering) sorted lexicographically and joined with {@code ","} so two
     * candidates with the same bucket set in different discovery order still hit the same entry.
     */
    public static String keyFor(String dimensionKey, int x, int y, int z, List<String> topBlockIds) {
        Objects.requireNonNull(dimensionKey, "dimensionKey");
        int cellX = Math.floorDiv(x, CELL_XZ);
        int cellY = Math.floorDiv(y, CELL_Y);
        int cellZ = Math.floorDiv(z, CELL_XZ);
        List<String> ids = new ArrayList<>(topBlockIds == null ? List.of() : topBlockIds);
        if (ids.size() > TOP_IDS_FOR_KEY) {
            ids = ids.subList(0, TOP_IDS_FOR_KEY);
        }
        ids.sort(Comparator.naturalOrder());
        return dimensionKey + "@" + cellX + "," + cellY + "," + cellZ + "#" + String.join(",", ids);
    }

    /** The live entry for {@code key}, or {@code null} when absent or expired (an expired entry is dropped). */
    public static Entry get(String key, int nowTick) {
        maybePrune(nowTick);
        Entry e = ENTRIES.get(key);
        if (e == null) {
            return null;
        }
        if (e.expired(nowTick)) {
            ENTRIES.remove(key);
            return null;
        }
        return e;
    }

    /** Records a verdict for {@code key}, TTL {@link #STOP_TTL_TICKS} for a stop, {@link #CONTINUE_TTL_TICKS}
     * for a continue. Overwrites any earlier entry for the same key. */
    public static void put(String key, boolean stop, String label, int nowTick) {
        ENTRIES.put(key, new Entry(stop, label == null ? "" : label, nowTick,
                stop ? STOP_TTL_TICKS : CONTINUE_TTL_TICKS));
    }

    private static void maybePrune(int nowTick) {
        if (lastPruneTick != NEVER && (long) nowTick - (long) lastPruneTick < 1200) {
            return;
        }
        lastPruneTick = nowTick;
        ENTRIES.entrySet().removeIf(en -> en.getValue().expired(nowTick));
    }

    /** Number of stored entries (diagnostics and tests). */
    public static int size() {
        return ENTRIES.size();
    }

    /** World unload. */
    public static void clearAll() {
        ENTRIES.clear();
        lastPruneTick = NEVER;
    }
}
