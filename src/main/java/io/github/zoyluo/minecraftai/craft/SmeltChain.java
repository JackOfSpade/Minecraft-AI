package io.github.zoyluo.minecraftai.craft;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * S5: single source of truth for the smelting chain — input item → smelted output (ingots/cooked
 * meat/glass/charcoal/baked potato), plus fuel burn duration.
 *
 * Previously the smelting mapping was scattered across `GoalPlanner.smeltRecipeFor` (ingots/stone/
 * charcoal only), with no way to reverse-derive the food chain (cooked meat). This table is shared
 * by the material chain (A) and the food chain (B): `GoalPlanner` reverse-derives smelting outputs,
 * and `SmeltTask` reads this table to validate that an input is "smeltable".
 */
public final class SmeltChain {

    // Input → output. Order only affects rawFor's reverse-lookup priority (each output is unique, so there is no ambiguity).
    private static final Map<Item, Item> SMELT = new LinkedHashMap<>();
    // Fuel → number of items it can smelt (vanilla: coal/charcoal 8, logs/planks 1.5, sticks 0.5; approximated here as a "burnable item count", rounded up by the planning layer as needed).
    private static final Map<Item, Double> FUEL = new LinkedHashMap<>();

    static {
        // Ore smelting
        SMELT.put(Items.RAW_IRON, Items.IRON_INGOT);
        SMELT.put(Items.RAW_COPPER, Items.COPPER_INGOT);
        SMELT.put(Items.RAW_GOLD, Items.GOLD_INGOT);
        SMELT.put(Items.COBBLESTONE, Items.STONE);
        SMELT.put(Items.OAK_LOG, Items.CHARCOAL); // Any log works; the planner defaults to oak
        // Food smelting (used by module B: cooked meat gives far more hunger/saturation than raw meat)
        SMELT.put(Items.BEEF, Items.COOKED_BEEF);
        SMELT.put(Items.PORKCHOP, Items.COOKED_PORKCHOP);
        SMELT.put(Items.CHICKEN, Items.COOKED_CHICKEN);
        SMELT.put(Items.MUTTON, Items.COOKED_MUTTON);
        SMELT.put(Items.RABBIT, Items.COOKED_RABBIT);
        SMELT.put(Items.COD, Items.COOKED_COD);
        SMELT.put(Items.SALMON, Items.COOKED_SALMON);
        SMELT.put(Items.POTATO, Items.BAKED_POTATO);
        // Other
        SMELT.put(Items.SAND, Items.GLASS);

        FUEL.put(Items.COAL, 8.0);
        FUEL.put(Items.CHARCOAL, 8.0);
        FUEL.put(Items.OAK_LOG, 1.5);
        FUEL.put(Items.OAK_PLANKS, 1.5);
        FUEL.put(Items.STICK, 0.5);
    }

    /** Module B: raw foods that can be cooked (raw materials hunted/fished/dug up need to be cooked for higher hunger/saturation, and to avoid poisoning from things like raw chicken). */
    public static final Set<Item> RAW_FOODS = Set.of(
            Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT,
            Items.COD, Items.SALMON, Items.POTATO);

    /** Module B: the corresponding cooked foods (only these count toward meeting the food-reserve target — hunger/saturation is far higher than raw). */
    public static final Set<Item> COOKED_FOODS = Set.of(
            Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON,
            Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.BAKED_POTATO);

    private SmeltChain() {
    }

    /** The smelted output for an input item; returns null if it cannot be smelted. */
    public static Item smeltOf(Item input) {
        return SMELT.get(input);
    }

    /** Whether this input item can be smelted (used by SmeltTask to validate its input). */
    public static boolean isSmeltable(Item input) {
        return SMELT.containsKey(input);
    }

    /** Reverse lookup: the input item needed to produce this smelted output; null if none exists (used by GoalPlanner for reverse derivation). */
    public static Item rawFor(Item output) {
        for (Map.Entry<Item, Item> e : SMELT.entrySet()) {
            if (e.getValue() == output) {
                return e.getKey();
            }
        }
        return null;
    }

    /** How many items this fuel can smelt (0 = not a fuel). */
    public static double burnYield(Item fuel) {
        return FUEL.getOrDefault(fuel, 0.0);
    }

    public static boolean isFuel(Item item) {
        return FUEL.containsKey(item);
    }
}
