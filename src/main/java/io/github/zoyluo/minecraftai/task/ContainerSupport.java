package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

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
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(direction);
            if (Standability.isStandable(bot.getEntityWorld(), candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    /** True if the container at {@code pos} (if any) holds at least one stack of {@code item}. */
    static boolean containsItem(AIPlayerEntity bot, BlockPos pos, Item item) {
        Inventory inventory = ContainerAction.resolve(bot, pos).orElse(null);
        if (inventory == null) {
            return false;
        }
        return containsItem(inventory, item);
    }

    /** True if {@code inventory} holds at least one stack of {@code item}. */
    static boolean containsItem(Inventory inventory, Item item) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                return true;
            }
        }
        return false;
    }
}
