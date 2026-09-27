package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The detour's private anti-thrash memory (mining-assist design 2.4, 4.3, 4.5): cells (valuables and stand poses)
 * that a detour must not try again for a while, with a time to live in server ticks. It is deliberately not
 * {@code EpisodeMemory}: that table has a cap of 128 and evicts the older half when full, so a busy detour could
 * push OreDig's own exclusions out. The host's "is this cell excluded" question reads both (this class and
 * {@code EpisodeMemory.isExcluded}, read-only); everything the detour excludes goes only in here.
 *
 * <p>Per bot, one instance in {@link MiningAssistState#exclusions()}, cleared with the state. Server thread
 * only. Not persisted.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Cap {@value #CAP} live entries. An entry is {@code (packed pos, expiresTick)}; it is excluded while
 *       {@code nowTick < expiresTick} (an entry excludes for exactly {@code ttl} ticks).</li>
 *   <li>Re-excluding a cell keeps the later expiry.</li>
 *   <li>When full and a new cell arrives: first drop every expired entry; if still full drop the entry that
 *       expires soonest (ties: the smaller packed position), then insert. So a fresh exclusion always fits.</li>
 *   <li>A non-positive ttl excludes nothing (the call is a no-op).</li>
 *   <li>Skip-log dedupe ({@link #shouldLogSkip}) is a second small table with the same cap and eviction: a cell
 *       is logged at most once per {@value #SKIP_LOG_INTERVAL_TICKS} ticks (design 4.13, "deduped per cell per
 *       600 ticks").</li>
 * </ul>
 */
public final class DetourExclusions {
    /** Most live exclusions. */
    public static final int CAP = 256;
    /** Cells named in a {@code ore_dig_detour_skip} line are logged at most once per this many ticks. */
    public static final int SKIP_LOG_INTERVAL_TICKS = 600;

    /** {@code packed pos -> expiresTick}: the anti-thrash exclusion table. */
    private final Map<Long, Integer> exclusions = new HashMap<>();
    /** {@code packed pos -> nextLoggableTick}: the skip-log dedupe table (design 4.13). Same cap and eviction. */
    private final Map<Long, Integer> skipLog = new HashMap<>();

    public DetourExclusions() {
    }

    /** Excludes {@code pos} until {@code nowTick + ttlTicks}. */
    public void exclude(BlockPos pos, int nowTick, int ttlTicks) {
        if (ttlTicks <= 0) {
            return;
        }
        put(exclusions, pos.asLong(), nowTick, nowTick + ttlTicks);
    }

    /** Excludes every position of {@code cells} (a Chebyshev-3 cluster, a pose) with the same ttl. */
    public void excludeAll(Iterable<BlockPos> cells, int nowTick, int ttlTicks) {
        if (ttlTicks <= 0) {
            return;
        }
        for (BlockPos pos : cells) {
            put(exclusions, pos.asLong(), nowTick, nowTick + ttlTicks);
        }
    }

    /** True while {@code pos} is excluded at {@code nowTick}; an expired entry answers false and is removed. */
    public boolean isExcluded(BlockPos pos, int nowTick) {
        return isLive(exclusions, pos.asLong(), nowTick);
    }

    /**
     * True when a skip line for {@code pos} may be written now (none was written for it within the last
     * {@value #SKIP_LOG_INTERVAL_TICKS} ticks); the call records that it was. False otherwise.
     */
    public boolean shouldLogSkip(BlockPos pos, int nowTick) {
        long key = pos.asLong();
        Integer until = skipLog.get(key);
        if (until != null && nowTick < until) {
            return false;
        }
        put(skipLog, key, nowTick, nowTick + SKIP_LOG_INTERVAL_TICKS);
        return true;
    }

    /** Drops every expired entry of both tables. Returns how many were dropped. */
    public int expire(int nowTick) {
        return expireTable(exclusions, nowTick) + expireTable(skipLog, nowTick);
    }

    /** Number of stored exclusion entries including expired ones not yet dropped. */
    public int size() {
        return exclusions.size();
    }

    public void clear() {
        exclusions.clear();
        skipLog.clear();
    }

    /** True while {@code key} has a live (unexpired) entry in {@code table} at {@code nowTick}; an expired entry is removed. */
    private static boolean isLive(Map<Long, Integer> table, long key, int nowTick) {
        Integer expires = table.get(key);
        if (expires == null) {
            return false;
        }
        if (nowTick >= expires) {
            table.remove(key);
            return false;
        }
        return true;
    }

    /** Drops the entries of {@code table} that are expired at {@code nowTick}. Returns how many were dropped. */
    private static int expireTable(Map<Long, Integer> table, int nowTick) {
        int dropped = 0;
        Iterator<Map.Entry<Long, Integer>> it = table.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer> e = it.next();
            if (nowTick >= e.getValue()) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Sets {@code table[key]} to {@code expiresTick}, keeping the later expiry on a re-exclude. When {@code key} is
     * new and the table is at {@value #CAP}: first drops every entry expired at {@code nowTick}; if still full,
     * drops the entry that expires soonest (ties: the smaller packed position). So a fresh exclusion always fits.
     */
    private static void put(Map<Long, Integer> table, long key, int nowTick, int expiresTick) {
        Integer existing = table.get(key);
        if (existing != null) {
            if (expiresTick > existing) {
                table.put(key, expiresTick);
            }
            return;
        }
        if (table.size() >= CAP) {
            expireTable(table, nowTick);
        }
        if (table.size() >= CAP) {
            evictSoonest(table);
        }
        table.put(key, expiresTick);
    }

    /** Removes the entry that expires soonest, ties broken by the smaller packed position. */
    private static void evictSoonest(Map<Long, Integer> table) {
        long bestKey = 0L;
        int bestExpires = Integer.MAX_VALUE;
        boolean found = false;
        for (Map.Entry<Long, Integer> e : table.entrySet()) {
            long k = e.getKey();
            int exp = e.getValue();
            if (!found || exp < bestExpires || (exp == bestExpires && k < bestKey)) {
                found = true;
                bestKey = k;
                bestExpires = exp;
            }
        }
        if (found) {
            table.remove(bestKey);
        }
    }
}
