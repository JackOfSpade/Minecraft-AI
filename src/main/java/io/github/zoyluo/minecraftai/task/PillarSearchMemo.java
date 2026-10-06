package io.github.zoyluo.minecraftai.task;

import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/**
 * Remembers that an observed pillar search found no approach from one stance. The search casts a
 * ray for every cell of a column ring (and, for the broad scan, a whole volume), so a bot that
 * stays where it is must not repeat it on every survey tick.
 *
 * <p>A finding holds while the bot stands in the same cell, no bot has changed the terrain since
 * (the path-cache version moves with every bot-made block change) and nothing it was told to
 * ignore can have come back: a target the scan skipped as excluded revives when its exclusion
 * ends, so the finding ends then too. Like an exclusion, a finding is a "not reachable from here"
 * verdict, so it is kept for no longer than {@link EpisodeMemory#TTL_UNREACHABLE} ticks; that is
 * also how long a change the bot did not make (a leaf that decays, another player's edit) can go
 * unnoticed by it. An exclusion that disappears before its end ends the finding too, which
 * {@link EpisodeMemory#earlyRevivals()} reports.</p>
 */
final class PillarSearchMemo {
    private BlockPos stance;
    private long subject;
    private long worldVersion;
    private int validUntilTick;
    /** The first tick after which an exclusion that held a candidate back from the scan in progress is gone. */
    private int earliestRevival = Integer.MAX_VALUE;
    /**
     * {@link EpisodeMemory#earlyRevivals()} when the scan in progress began (-1 outside a scan): the exclusions it
     * relied on can vanish before their recorded end, for instance when a full table sheds its older half.
     */
    private long earlyRevivalsAtScan = -1L;
    private long earlyRevivalsAtFinding;

    /** Whether a search for {@code subject} (a target, or the extent of a broad scan) already came back empty here. */
    boolean knownEmpty(BlockPos stance, long subject, long worldVersion, int nowTick) {
        return this.stance != null && this.stance.equals(stance) && this.subject == subject
                && this.worldVersion == worldVersion && nowTick <= validUntilTick
                && earlyRevivalsAtFinding == EpisodeMemory.INSTANCE.earlyRevivals();
    }

    /**
     * The candidate filter of the scan this memo will be told the outcome of: it refuses a target
     * excluded at {@code nowTick} and notes when the first of them is available again.
     */
    Predicate<BlockPos> scanFilter(UUID botId, int nowTick) {
        earliestRevival = Integer.MAX_VALUE;
        earlyRevivalsAtScan = EpisodeMemory.INSTANCE.earlyRevivals();
        return pos -> {
            int excludedUntil = EpisodeMemory.INSTANCE.excludedUntil(botId, pos, nowTick);
            if (excludedUntil < 0) {
                return true;
            }
            earliestRevival = Math.min(earliestRevival, excludedUntil);
            return false;
        };
    }

    void rememberEmpty(BlockPos stance, long subject, long worldVersion, int nowTick) {
        this.stance = stance.immutable();
        this.subject = subject;
        this.worldVersion = worldVersion;
        this.validUntilTick = Math.min(nowTick + EpisodeMemory.TTL_UNREACHABLE, earliestRevival);
        // A search that never filtered by exclusions has none to lose, so only now is as far back as it can look.
        this.earlyRevivalsAtFinding = earlyRevivalsAtScan >= 0 ? earlyRevivalsAtScan : EpisodeMemory.INSTANCE.earlyRevivals();
        earlyRevivalsAtScan = -1L;
    }

    void clear() {
        stance = null;
        earliestRevival = Integer.MAX_VALUE;
    }
}
