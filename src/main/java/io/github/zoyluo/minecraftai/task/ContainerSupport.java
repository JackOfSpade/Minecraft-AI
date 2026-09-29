package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;

/**
 * Shared, byte-identical helpers that were previously reimplemented independently in several
 * task files (SmeltTask, ResupplyTask, StockpileTask, ContainerTask, RaidCropsTask): finding a
 * standable cell adjacent to a container/work-block, and checking whether a container at a
 * position (or an already-resolved inventory) holds a given item.
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

    /** True if the container at {@code pos} (if any) holds at least one stack of {@code item}. */
    static boolean containsItem(AIPlayerEntity bot, BlockPos pos, Item item) {
        Container inventory = ContainerAction.resolve(bot, pos).orElse(null);
        if (inventory == null) {
            return false;
        }
        return containsItem(inventory, item);
    }

    /** True if {@code inventory} holds at least one stack of {@code item}. */
    static boolean containsItem(Container inventory, Item item) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }
}
