package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Shared helper for the container/work-block tasks (SmeltTask, ResupplyTask, StockpileTask,
 * ContainerTask, RaidCropsTask): finding a standable cell adjacent to a container/work-block.
 * The former "does the container at pos hold item" helpers were removed on purpose: they read the
 * contents of containers the bot had not opened. What a bot knows about container contents comes
 * from its container ledger (see the memory package), written only when it opens one.
 */
final class ContainerSupport {
    private ContainerSupport() {
    }

    /** First horizontally-adjacent standable cell next to {@code pos}, or null if none. */
    static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = pos.relative(direction);
            if (Standability.isStandable(bot.level(), candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }
}
