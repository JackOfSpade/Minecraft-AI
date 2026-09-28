package io.github.zoyluo.minecraftai.craft;

import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime recipe index (knowledge-layer, data-driven): after the server starts, scans all crafting
 * recipes from RecipeManager to build an index, letting the planner reverse-derive **items not
 * covered by the hand-written table** — including any mod items (e.g. Twilight Forest recipes work automatically).
 *
 * Two-tier strategy (see RecipeRegistry.find): the hand-written table takes priority (pins down
 * vanilla's critical path, zero behavior regression); only on a miss does it check this index
 * (long-tail/mod fallback). When multiple recipes produce the same item, pick the one with the
 * fewest distinct ingredient types (deterministic, helps reverse-derivation converge).
 */
public final class RuntimeRecipeIndex {
    private static final Map<Item, RecipeRegistry.Recipe> INDEX = new HashMap<>();
    private static volatile boolean ready;

    private RuntimeRecipeIndex() {
    }

    public static void rebuild(MinecraftServer server) {
        Map<Item, RecipeRegistry.Recipe> fresh = new HashMap<>();
        int scanned = 0;
        int indexed = 0;
        for (RecipeEntry<?> entry : server.getRecipeManager().values()) {
            scanned++;
            try {
                if (!(entry.value() instanceof CraftingRecipe crafting)) {
                    continue;
                }
                // After the 1.21.3 recipe refactor, result has no public getter: shaped/shapeless
                // craft() implementations just do result.copy() (ignoring the input), so calling it
                // with an empty input still yields the product; special recipes (dyeing, etc.) may
                // throw/return empty, which is skipped by the outer catch and the isEmpty check.
                ItemStack result = crafting.craft(
                        net.minecraft.recipe.input.CraftingRecipeInput.EMPTY, server.getRegistryManager());
                if (result == null || result.isEmpty()) {
                    continue;
                }
                List<RecipeRegistry.Ingredient> ingredients = convertIngredients(crafting);
                if (ingredients == null || ingredients.isEmpty()) {
                    continue;
                }
                boolean needsTable = needsCraftingTable(crafting, ingredients);
                RecipeRegistry.Recipe candidate = new RecipeRegistry.Recipe(
                        result.getItem(), result.getCount(), ingredients, needsTable);
                RecipeRegistry.Recipe existing = fresh.get(result.getItem());
                // Same item, multiple recipes: pick the one with the fewest distinct ingredient
                // types (faster, deterministic reverse-derivation convergence); ties keep whichever came first.
                if (existing == null || candidate.ingredients().size() < existing.ingredients().size()) {
                    fresh.put(result.getItem(), candidate);
                }
                indexed++;
            } catch (RuntimeException ignored) {
                // A single bad recipe (mod-custom serialization, etc.) does not ruin the whole index
            }
        }
        pruneReciprocalPairs(fresh);
        synchronized (INDEX) {
            INDEX.clear();
            INDEX.putAll(fresh);
        }
        ready = true;
        BotLog.comm(null, "runtime_recipe_index_built", "scanned", scanned, "indexed", INDEX.size());
    }

    // Prune reciprocal recipe pairs (block<->item storage-compaction conversions:
    // iron_block<->9 iron_ingot / raw_iron_block<->9 raw_iron / coal_block<->9 coal...): these are
    // storage compaction, not an acquisition path — if left in the index they would be picked over
    // the real path by the "fewest ingredient types" rule, turning reverse-derivation into an
    // A->B->A infinite loop (observed in practice: fixed the cycle for iron_block/ingot, then it
    // recurred for raw_iron_block — a systemic problem needs a systemic fix).
    private static void pruneReciprocalPairs(Map<Item, RecipeRegistry.Recipe> index) {
        List<Item> toRemove = new ArrayList<>();
        for (Map.Entry<Item, RecipeRegistry.Recipe> e : index.entrySet()) {
            RecipeRegistry.Recipe r = e.getValue();
            if (r.ingredients().size() != 1 || r.ingredients().get(0).anyOf().size() != 1) {
                continue;
            }
            Item material = r.ingredients().get(0).anyOf().get(0);
            RecipeRegistry.Recipe back = index.get(material);
            if (back != null && back.ingredients().size() == 1
                    && back.ingredients().get(0).anyOf().size() == 1
                    && back.ingredients().get(0).anyOf().get(0) == e.getKey()) {
                toRemove.add(e.getKey());
                toRemove.add(material);
            }
        }
        toRemove.forEach(index::remove);
    }

    /** Fallback lookup for when the hand-written table misses (see RecipeRegistry.find's two-tier strategy). Returns empty if the index hasn't been built yet (unit tests / early startup). */
    public static Optional<RecipeRegistry.Recipe> find(Item item) {
        if (!ready) {
            return Optional.empty();
        }
        synchronized (INDEX) {
            return Optional.ofNullable(INDEX.get(item));
        }
    }

    public static void clear() {
        synchronized (INDEX) {
            INDEX.clear();
        }
        ready = false;
    }

    // Ingredient (multiple candidates per slot) -> our anyOf structure; multiple slots of the same
    // material merge their count.
    // 1.21.3: ingredients are uniformly read from getIngredientPlacement().getIngredients() (same
    // API for shaped/shapeless); items are read via getMatchingItems() (a list of RegistryEntry);
    // hasNoPlacement = a dynamic special recipe, skipped by the caller.
    private static List<RecipeRegistry.Ingredient> convertIngredients(CraftingRecipe crafting) {
        if (crafting.getIngredientPlacement().hasNoPlacement()) {
            return List.of();
        }
        Map<List<Item>, Integer> merged = new HashMap<>();
        for (Ingredient ing : crafting.getIngredientPlacement().getIngredients()) {
            List<Item> anyOf = new ArrayList<>();
            for (net.minecraft.registry.entry.RegistryEntry<Item> e : ing.getMatchingItems().toList()) {
                Item item = e.value();
                if (!anyOf.contains(item)) {
                    anyOf.add(item);
                }
            }
            if (anyOf.isEmpty()) {
                continue;
            }
            merged.merge(anyOf, 1, Integer::sum);
        }
        List<RecipeRegistry.Ingredient> out = new ArrayList<>();
        for (Map.Entry<List<Item>, Integer> e : merged.entrySet()) {
            out.add(new RecipeRegistry.Ingredient(List.copyOf(e.getKey()), e.getValue()));
        }
        return out;
    }

    // Only a 3x3 grid needs a crafting table: for shaped recipes check width/height, for shapeless
    // check total ingredient slot count (>4 needs one).
    private static boolean needsCraftingTable(CraftingRecipe crafting, List<RecipeRegistry.Ingredient> ingredients) {
        if (crafting instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        int slots = ingredients.stream().mapToInt(RecipeRegistry.Ingredient::count).sum();
        return slots > 4;
    }
}
