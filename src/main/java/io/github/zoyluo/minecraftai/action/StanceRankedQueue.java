package io.github.zoyluo.minecraftai.action;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * Candidates ranked for the stance the bot holds. What a bot can see changes only when it moves, so
 * the ranking (a ray per candidate) is redone only for a new stance: asked every tick by a caller
 * that has run out of candidates, it costs one ranking, not one per tick.
 */
final class StanceRankedQueue {
    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private BlockPos rankedFrom;

    /** The next candidate for {@code stance}, or null when none is left from there. */
    BlockPos poll(BlockPos stance, Supplier<List<BlockPos>> ranking) {
        if (!stance.equals(rankedFrom)) {
            rankedFrom = stance.immutable();
            queue.clear();
            queue.addAll(ranking.get());
        }
        return queue.poll();
    }
}
