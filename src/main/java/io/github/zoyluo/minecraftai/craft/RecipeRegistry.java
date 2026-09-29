package io.github.zoyluo.minecraftai.craft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class RecipeRegistry {
    public record Ingredient(List<Item> anyOf, int count) {
        /**
         * Consumes up to {@code count} units of this ingredient from {@code counts} (mutated in
         * place), taking greedily from each candidate item in {@link #anyOf()} order. Shared by
         * CraftingHelper's atomic material planner and GoalPlanner's backward-chaining planner, so
         * the ingredient-consumption primitive only has to be gotten right once.
         */
        public void consumeFrom(Map<Item, Integer> counts, int count) {
            int remaining = count;
            for (Item item : anyOf) {
                if (remaining <= 0) {
                    return;
                }
                int available = counts.getOrDefault(item, 0);
                int take = Math.min(available, remaining);
                if (take > 0) {
                    counts.put(item, available - take);
                    remaining -= take;
                }
            }
        }
    }

    public record Recipe(Item output, int outputCount, List<Ingredient> ingredients, boolean needsCraftingTable) {
    }

    public static final List<Item> LOGS = List.of(
            Items.OAK_LOG,
            Items.SPRUCE_LOG,
            Items.BIRCH_LOG,
            Items.JUNGLE_LOG,
            Items.ACACIA_LOG,
            Items.DARK_OAK_LOG,
            Items.MANGROVE_LOG,
            Items.CHERRY_LOG,
            Items.PALE_OAK_LOG);

    public static final List<Item> PLANKS = List.of(
            Items.OAK_PLANKS,
            Items.SPRUCE_PLANKS,
            Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS,
            Items.MANGROVE_PLANKS,
            Items.CHERRY_PLANKS,
            Items.PALE_OAK_PLANKS);

    private static final List<Item> STICKS = List.of(Items.STICK);
    // Stone-like family: regular cobblestone / cobbled deepslate / blackstone -- all three can make
    // furnaces and stone tools in MC (observed: at Y=-59 the bot finds only cobbled deepslate; the old
    // recipe recognized only cobblestone, causing failures to craft furnaces at depth, MINE stone hitting
    // bedrock, and a goal-replan infinite loop).
    private static final List<Item> STONE_LIKE = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
    private static final Map<Item, Recipe> BY_OUTPUT = new HashMap<>();

    static {
        registerAll();
    }

    private RecipeRegistry() {
    }

    // Two-tier lookup: the handwritten table takes priority (pins down vanilla's critical chains, zero
    // regression in determinism); on a miss, fall back to the runtime index (full RecipeAccess coverage,
    // a fallback for mod/long-tail items -- e.g. Twilight Forest mod recipes can be auto-derived).
    public static Optional<Recipe> find(Item output) {
        Recipe handwritten = BY_OUTPUT.get(output);
        if (handwritten != null) {
            return Optional.of(handwritten);
        }
        // Items with a smelting chain (iron ingot / gold ingot / glass ...) skip the runtime recipe
        // fallback: the index would learn reverse recipes like "break an iron block into 9 ingots"
        // (picked because it has the fewest ingredient types), overriding the correct smelting path, and
        // derive an ingot -> block -> ingot infinite loop (observed in llm_iron testing:
        // cycle:iron_block plan_failed). The correct smelting path is handled by the SmeltChain branch of
        // acquireBaseItem.
        if (SmeltChain.rawFor(output) != null) {
            return Optional.empty();
        }
        return RuntimeRecipeIndex.find(output);
    }

    // S1: for dependency-chain auditing (/minecraftai deplint) to iterate over all known recipes.
    public static java.util.Collection<Recipe> all() {
        return java.util.Collections.unmodifiableCollection(BY_OUTPUT.values());
    }

    private static void registerAll() {
        for (int index = 0; index < LOGS.size(); index++) {
            put(new Recipe(PLANKS.get(index), 4, List.of(new Ingredient(List.of(LOGS.get(index)), 1)), false));
        }
        put(new Recipe(Items.STICK, 4, List.of(new Ingredient(PLANKS, 2)), false));
        put(new Recipe(Items.CRAFTING_TABLE, 1, List.of(new Ingredient(PLANKS, 4)), false));
        // Boats are a 2x2 survival recipe.  Keep wood species paired so a bot carrying birch
        // logs can make a birch boat rather than failing an arbitrary oak-only request.
        boat(Items.OAK_BOAT, Items.OAK_PLANKS);
        boat(Items.SPRUCE_BOAT, Items.SPRUCE_PLANKS);
        boat(Items.BIRCH_BOAT, Items.BIRCH_PLANKS);
        boat(Items.JUNGLE_BOAT, Items.JUNGLE_PLANKS);
        boat(Items.ACACIA_BOAT, Items.ACACIA_PLANKS);
        boat(Items.DARK_OAK_BOAT, Items.DARK_OAK_PLANKS);
        boat(Items.MANGROVE_BOAT, Items.MANGROVE_PLANKS);
        boat(Items.CHERRY_BOAT, Items.CHERRY_PLANKS);
        boat(Items.PALE_OAK_BOAT, Items.PALE_OAK_PLANKS);
        put(new Recipe(Items.TORCH, 4, List.of(
                new Ingredient(List.of(Items.COAL, Items.CHARCOAL), 1),
                new Ingredient(STICKS, 1)), false));
        put(new Recipe(Items.BOWL, 4, List.of(new Ingredient(PLANKS, 3)), false));
        put(new Recipe(Items.BREAD, 1, List.of(new Ingredient(List.of(Items.WHEAT), 3)), false));
        // Bone meal: 1 bone -> 3 (a 1x1 recipe, so no table); FarmTask crafts it from carried bones to speed crops up.
        put(new Recipe(Items.BONE_MEAL, 3, List.of(new Ingredient(List.of(Items.BONE), 1)), false));
        // Cake chain: sugar <- sugar cane; bucket <- 3 iron; cake = 3 milk buckets + 2 sugar + 1 egg + 3
        // wheat (egg is a passive product, must already be in inventory, see GoalPlanner).
        put(new Recipe(Items.SUGAR, 1, List.of(new Ingredient(List.of(Items.SUGAR_CANE), 1)), false));
        put(new Recipe(Items.BUCKET, 1, List.of(new Ingredient(List.of(Items.IRON_INGOT), 3)), true));
        put(new Recipe(Items.CAKE, 1, List.of(
                new Ingredient(List.of(Items.MILK_BUCKET), 3),
                new Ingredient(List.of(Items.SUGAR), 2),
                new Ingredient(List.of(Items.EGG), 1),
                new Ingredient(List.of(Items.WHEAT), 3)), true));

        put(new Recipe(Items.FURNACE, 1, List.of(new Ingredient(STONE_LIKE, 8)), true));
        put(new Recipe(Items.CHEST, 1, List.of(new Ingredient(PLANKS, 8)), true));
        put(new Recipe(Items.LADDER, 3, List.of(new Ingredient(STICKS, 7)), true));

        tool(Items.WOODEN_PICKAXE, PLANKS, 3);
        tool(Items.STONE_PICKAXE, STONE_LIKE, 3);
        tool(Items.IRON_PICKAXE, List.of(Items.IRON_INGOT), 3);
        tool(Items.WOODEN_AXE, PLANKS, 3);
        tool(Items.STONE_AXE, STONE_LIKE, 3);
        tool(Items.IRON_AXE, List.of(Items.IRON_INGOT), 3);
        tool(Items.WOODEN_SHOVEL, PLANKS, 1);
        tool(Items.STONE_SHOVEL, STONE_LIKE, 1);
        tool(Items.IRON_SHOVEL, List.of(Items.IRON_INGOT), 1);
        sword(Items.WOODEN_SWORD, PLANKS, 2);
        sword(Items.STONE_SWORD, STONE_LIKE, 2);
        sword(Items.IRON_SWORD, List.of(Items.IRON_INGOT), 2);

        // P3: hoe (2 head material + 2 sticks), for farming-chain derivation.
        tool(Items.WOODEN_HOE, PLANKS, 2);
        tool(Items.STONE_HOE, STONE_LIKE, 2);
        tool(Items.IRON_HOE, List.of(Items.IRON_INGOT), 2);

        // Layer 3: iron armor (for equipment-prerequisite derivation). Vanilla quantities -- helmet 5 /
        // chestplate 8 / leggings 7 / boots 4, pure metal, no sticks.
        armorOf(Items.IRON_HELMET, Items.IRON_INGOT, 5);
        armorOf(Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8);
        armorOf(Items.IRON_LEGGINGS, Items.IRON_INGOT, 7);
        armorOf(Items.IRON_BOOTS, Items.IRON_INGOT, 4);

        // S1: diamond/gold tools (upgrade after mining diamonds, efficient mining).
        tool(Items.DIAMOND_PICKAXE, List.of(Items.DIAMOND), 3);
        tool(Items.DIAMOND_AXE, List.of(Items.DIAMOND), 3);
        tool(Items.DIAMOND_SHOVEL, List.of(Items.DIAMOND), 1);
        tool(Items.DIAMOND_HOE, List.of(Items.DIAMOND), 2);
        sword(Items.DIAMOND_SWORD, List.of(Items.DIAMOND), 2);
        tool(Items.GOLDEN_PICKAXE, List.of(Items.GOLD_INGOT), 3);
        sword(Items.GOLDEN_SWORD, List.of(Items.GOLD_INGOT), 2);

        // S1: diamond armor + gold armor (used by the armor-upgrade chain S30/S34).
        armorOf(Items.DIAMOND_HELMET, Items.DIAMOND, 5);
        armorOf(Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8);
        armorOf(Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7);
        armorOf(Items.DIAMOND_BOOTS, Items.DIAMOND, 4);
        armorOf(Items.GOLDEN_HELMET, Items.GOLD_INGOT, 5);
        armorOf(Items.GOLDEN_CHESTPLATE, Items.GOLD_INGOT, 8);
        armorOf(Items.GOLDEN_LEGGINGS, Items.GOLD_INGOT, 7);
        armorOf(Items.GOLDEN_BOOTS, Items.GOLD_INGOT, 4);

        // S1: shield (armor S32) -- 6 planks + 1 iron ingot.
        put(new Recipe(Items.SHIELD, 1, List.of(
                new Ingredient(PLANKS, 6),
                new Ingredient(List.of(Items.IRON_INGOT), 1)), true));

        // S1: animal husbandry/pen infrastructure (Module E) -- fence, hay block.
        put(new Recipe(Items.OAK_FENCE, 3, List.of(
                new Ingredient(PLANKS, 4),
                new Ingredient(STICKS, 2)), true));
        put(new Recipe(Items.HAY_BLOCK, 1, List.of(new Ingredient(List.of(Items.WHEAT), 9)), false));
    }

    private static void tool(Item output, List<Item> head, int headCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(head, headCount), new Ingredient(STICKS, 2)), true));
    }

    private static void sword(Item output, List<Item> head, int headCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(head, headCount), new Ingredient(STICKS, 1)), true));
    }

    private static void boat(Item output, Item plank) {
        put(new Recipe(output, 1, List.of(new Ingredient(List.of(plank), 5)), false));
    }

    // Armor (pure metal, no sticks). material = iron ingot / diamond / gold ingot.
    private static void armorOf(Item output, Item material, int ingotCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(List.of(material), ingotCount)), true));
    }

    private static void put(Recipe recipe) {
        BY_OUTPUT.put(recipe.output(), recipe);
    }
}
