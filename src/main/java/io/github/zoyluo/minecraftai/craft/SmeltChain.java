package io.github.zoyluo.minecraftai.craft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * S5: single source of truth for the smelting chain — input item → smelted output (ingots/cooked
 * meat/glass/charcoal/baked potato).
 *
 * Previously the smelting mapping was scattered across `GoalPlanner.smeltRecipeFor` (ingots/stone/
 * charcoal only), with no way to reverse-derive the food chain (cooked meat). This table is shared
 * by the material chain (A) and the food chain (B): `GoalPlanner` reverse-derives smelting outputs.
 */
public final class SmeltChain {

    // Input → output. Order only affects rawFor's reverse-lookup priority (each output is unique, so there is no ambiguity).
    private static final Map<Item, Item> SMELT = new LinkedHashMap<>();

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
    }

    /** Module B: raw foods that can be cooked (raw materials hunted/fished/dug up need to be cooked for higher hunger/saturation, and to avoid poisoning from things like raw chicken). */
    public static final Set<Item> RAW_FOODS = Set.of(
            Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT,
            Items.COD, Items.SALMON, Items.POTATO);

    private SmeltChain() {
    }

    /** The smelted output for an input item; returns null if it cannot be smelted. */
    public static Item smeltOf(Item input) {
        return SMELT.get(input);
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
}
