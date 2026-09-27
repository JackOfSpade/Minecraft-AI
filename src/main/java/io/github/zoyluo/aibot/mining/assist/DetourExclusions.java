package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

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

    public DetourExclusions() {
    }

    /** Excludes {@code pos} until {@code nowTick + ttlTicks}. */
    public void exclude(BlockPos pos, int nowTick, int ttlTicks) {
        throw new UnsupportedOperationException("P1 stub: DetourExclusions.exclude");
    }

    /** Excludes every position of {@code cells} (a Chebyshev-3 cluster, a pose) with the same ttl. */
    public void excludeAll(Iterable<BlockPos> cells, int nowTick, int ttlTicks) {
        throw new UnsupportedOperationException("P1 stub: DetourExclusions.excludeAll");
    }

    /** True while {@code pos} is excluded at {@code nowTick}; an expired entry answers false and is removed. */
    public boolean isExcluded(BlockPos pos, int nowTick) {
        throw new UnsupportedOperationException("P1 stub: DetourExclusions.isExcluded");
    }

    /**
     * True when a skip line for {@code pos} may be written now (none was written for it within the last
     * {@value #SKIP_LOG_INTERVAL_TICKS} ticks); the call records that it was. False otherwise.
     */
    public boolean shouldLogSkip(BlockPos pos, int nowTick) {
        throw new UnsupportedOperationException("P1 stub: DetourExclusions.shouldLogSkip");
    }

    /** Drops every expired entry of both tables. Returns how many were dropped. */
    public int expire(int nowTick) {
        throw new UnsupportedOperationException("P1 stub: DetourExclusions.expire");
    }

    /** Number of stored exclusion entries including expired ones not yet dropped. */
    public int size() {
        throw new UnsupportedOperationException("P1 stub: DetourExclusions.size");
    }

    public void clear() {
        throw new UnsupportedOperationException("P1 stub: DetourExclusions.clear");
    }
}
