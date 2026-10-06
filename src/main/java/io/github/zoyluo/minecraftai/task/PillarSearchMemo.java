package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;

/**
 * Remembers that an observed pillar search found no approach from one stance. The search casts a
 * ray for every cell of a column ring (and, for the broad scan, a whole volume), so a bot that
 * stays where it is must not repeat it on every survey tick.
 *
 * <p>A finding holds while the bot stands in the same cell, no bot has changed the terrain since
 * (the path-cache version moves with every bot-made block change) and no excluded target can have
 * revived: an exclusion lasts at most {@link EpisodeMemory#TTL_UNREACHABLE} ticks, which therefore
 * is also how long this finding is kept.</p>
 */
final class PillarSearchMemo {
    private BlockPos stance;
    private long subject;
    private long worldVersion;
    private int validUntilTick;

    /** Whether a search for {@code subject} (a target, or the extent of a broad scan) already came back empty here. */
    boolean knownEmpty(BlockPos stance, long subject, long worldVersion, int nowTick) {
        return this.stance != null && this.stance.equals(stance) && this.subject == subject
                && this.worldVersion == worldVersion && nowTick <= validUntilTick;
    }

    void rememberEmpty(BlockPos stance, long subject, long worldVersion, int nowTick) {
        this.stance = stance.immutable();
        this.subject = subject;
        this.worldVersion = worldVersion;
        this.validUntilTick = nowTick + EpisodeMemory.TTL_UNREACHABLE;
    }

    void clear() {
        stance = null;
    }
}
