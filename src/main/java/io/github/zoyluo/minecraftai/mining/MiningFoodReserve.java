package io.github.zoyluo.minecraftai.mining;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Conservative food units for long mining expeditions. Raw or harmful foods remain available to
 * emergency survival code, but they never satisfy a planned deep-mine reserve.
 */
public final class MiningFoodReserve {
    public static final int MIN_DEEP_MINE_UNITS = 2;

    private MiningFoodReserve() {
    }

    public static int units(Map<Item, Integer> counts) {
        int oneUnitItems = 0;
        for (Item item : ReserveItems.ONE_UNIT_PRIORITY) {
            oneUnitItems += Math.max(0, counts.getOrDefault(item, 0));
        }
        return normalizedUnits(oneUnitItems, counts.getOrDefault(Items.SWEET_BERRIES, 0));
    }

    public static int units(Container inventory) {
        int units = 0;
        int berries = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (ReserveItems.ONE_UNIT_ITEMS.contains(stack.getItem())) {
                units += stack.getCount();
            } else if (stack.is(Items.SWEET_BERRIES)) {
                berries += stack.getCount();
            }
        }
        return normalizedUnits(units, berries);
    }

    /** Returns the safest available reserve item, using berries only after full-unit foods. */
    public static Optional<Item> firstReserveItem(Container inventory) {
        for (Item item : ReserveItems.ONE_UNIT_PRIORITY) {
            if (contains(inventory, item)) {
                return Optional.of(item);
            }
        }
        return contains(inventory, Items.SWEET_BERRIES)
                ? Optional.of(Items.SWEET_BERRIES)
                : Optional.empty();
    }

    public static int itemsForUnits(Item item, int unitCount) {
        if (item == Items.SWEET_BERRIES) {
            return physicalItemsForUnits(unitCount, true);
        }
        if (ReserveItems.ONE_UNIT_ITEMS.contains(item)) {
            return physicalItemsForUnits(unitCount, false);
        }
        throw new IllegalArgumentException("not_mining_food_reserve:" + item);
    }

    static int normalizedUnits(int oneUnitItems, int berries) {
        return Math.max(0, oneUnitItems) + Math.max(0, berries) / 2;
    }

    static int physicalItemsForUnits(int unitCount, boolean berries) {
        int units = Math.max(0, unitCount);
        return berries ? Math.multiplyExact(units, 2) : units;
    }

    private static boolean contains(Container inventory, Item item) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    /** Delays Minecraft item registry access so pure unit arithmetic can run without bootstrapping. */
    private static final class ReserveItems {
        private static final List<Item> ONE_UNIT_PRIORITY = List.of(
                Items.COOKED_BEEF,
                Items.COOKED_PORKCHOP,
                Items.COOKED_MUTTON,
                Items.COOKED_CHICKEN,
                Items.COOKED_RABBIT,
                Items.COOKED_COD,
                Items.COOKED_SALMON,
                Items.BREAD,
                Items.BAKED_POTATO);
        private static final Set<Item> ONE_UNIT_ITEMS = Set.copyOf(ONE_UNIT_PRIORITY);

        private ReserveItems() {
        }
    }
}
