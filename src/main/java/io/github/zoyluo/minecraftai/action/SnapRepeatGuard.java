package io.github.zoyluo.minecraftai.action;

import net.minecraft.util.math.BlockPos;

/**
 * Allows at most one physical start re-snap per origin cell per {@link #WINDOW_TICKS}.
 *
 * <p>{@code ActionPack.snapPlayerToNearestStandable} nudges a bot standing in a non-standable cell
 * onto a legal neighbour before a path search. If something then walks the bot straight back
 * into the same bad cell (the session log's straight-line follow fallback did, 42 snaps and a
 * 179 &lt;-&gt; 180 yo-yo in about 70 s), every failed search would snap again, so the bot oscillated
 * for as long as the order stood. A second snap out of the very same cell inside the window is
 * refused: that is one snap per stall, not one per failed search.
 */
final class SnapRepeatGuard {
    static final int WINDOW_TICKS = 200;

    private BlockPos lastFrom;
    private int lastTick;

    /** @return true when a physical snap out of {@code from} may run at {@code nowTick}. */
    boolean allows(BlockPos from, int nowTick) {
        return lastFrom == null || !lastFrom.equals(from) || nowTick - lastTick > WINDOW_TICKS
                || nowTick < lastTick;
    }

    void record(BlockPos from, int nowTick) {
        lastFrom = from.toImmutable();
        lastTick = nowTick;
    }
}
