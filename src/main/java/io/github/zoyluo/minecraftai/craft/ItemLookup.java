package io.github.zoyluo.minecraftai.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Read-only fact lookup behind the {@code lookup_recipe} tool. The language model's built-in
 * knowledge of Minecraft is older than the running game (it once denied copper tools and the Lunge
 * enchantment to the player), so anything about item/enchantment existence and crafting is answered
 * from the live registries and {@link RecipeRegistry} (hand-written table first, then the
 * {@link RuntimeRecipeIndex} of every recipe loaded on this server). Output is one short line.
 */
public final class ItemLookup {
    private static final int MAX_INGREDIENT_ALTERNATIVES = 3;
    private static final int MAX_SUGGESTIONS = 6;

    private ItemLookup() {
    }

    /** Answers a {@code lookup_recipe} query. Never throws for an unknown name. */
    public static String describe(String query, RegistryAccess registries) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("missing_or_bad_arg: name");
        }
        Identifier id = Identifier.tryParse(normalized);
        if (id != null) {
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id).filter(found -> found != Items.AIR);
            if (item.isPresent()) {
                return describeItem(item.get());
            }
            Optional<Registry<Enchantment>> enchantments = registries.lookup(Registries.ENCHANTMENT);
            if (enchantments.isPresent()) {
                Optional<Enchantment> enchantment = enchantments.get().getOptional(id);
                if (enchantment.isPresent()) {
                    return formatEnchantment(id.toString(), enchantment.get().getMaxLevel());
                }
            }
        }
        List<String> similar = new ArrayList<>(suggestions(
                BuiltInRegistries.ITEM.keySet().stream().map(Identifier::toString).toList(), normalized));
        registries.lookup(Registries.ENCHANTMENT).ifPresent(registry -> similar.addAll(suggestions(
                registry.keySet().stream().map(Identifier::toString).toList(), normalized)));
        return formatNotFound(normalized, similar);
    }

    private static String describeItem(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        var recipe = RecipeRegistry.find(item);
        if (recipe.isPresent()) {
            RecipeRegistry.Recipe found = recipe.get();
            List<String> ingredients = new ArrayList<>();
            for (RecipeRegistry.Ingredient ingredient : found.ingredients()) {
                List<String> names = new ArrayList<>();
                for (Item option : ingredient.anyOf()) {
                    names.add(BuiltInRegistries.ITEM.getKey(option).toString());
                }
                ingredients.add(formatIngredient(ingredient.count(), names));
            }
            return formatCraftable(id, found.outputCount(), ingredients, found.needsCraftingTable());
        }
        Item raw = SmeltChain.rawFor(item);
        if (raw != null) {
            return formatSmelted(id, BuiltInRegistries.ITEM.getKey(raw).toString());
        }
        return formatNoRecipe(id, AcquisitionHints.source(item));
    }

    // ---- Pure formatting / parsing (unit-testable without a bootstrapped game) ----

    /** Lower-cases, trims and turns spaces into underscores; a bare name gets the minecraft namespace. */
    static String normalize(String query) {
        if (query == null) {
            return "";
        }
        String cleaned = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (cleaned.isEmpty()) {
            return "";
        }
        return cleaned.indexOf(':') >= 0 ? cleaned : "minecraft:" + cleaned;
    }

    static String formatIngredient(int count, List<String> options) {
        String shown = options.size() <= MAX_INGREDIENT_ALTERNATIVES
                ? String.join("|", options)
                : String.join("|", options.subList(0, MAX_INGREDIENT_ALTERNATIVES))
                        + "|+" + (options.size() - MAX_INGREDIENT_ALTERNATIVES) + " more";
        return count + "x " + shown;
    }

    static String formatCraftable(String id, int outputCount, List<String> ingredients, boolean needsTable) {
        return id + " exists in Minecraft 1.21.11. Craft " + outputCount + " from " + String.join(", ", ingredients)
                + (needsTable ? " at a crafting table" : " (2x2 inventory grid)") + ".";
    }

    static String formatSmelted(String id, String rawId) {
        return id + " exists in Minecraft 1.21.11. Not crafted: smelt " + rawId + " in a furnace.";
    }

    static String formatNoRecipe(String id, String acquisition) {
        return id + " exists in Minecraft 1.21.11. No crafting recipe on this server"
                + ("unknown".equals(acquisition) ? " (obtain it by mining, loot, trading or other means)."
                        : " (usually obtained by: " + acquisition + ").");
    }

    static String formatEnchantment(String id, int maxLevel) {
        return id + " is an enchantment that exists in Minecraft 1.21.11 (max level " + maxLevel
                + "). It is not an item: it is applied at an enchanting table or anvil, or found on loot.";
    }

    static String formatNotFound(String normalized, List<String> similar) {
        String base = normalized + " is neither an item nor an enchantment in Minecraft 1.21.11 under that id.";
        if (similar.isEmpty()) {
            return base + " No similar ids found; check the spelling (ids use underscores).";
        }
        return base + " Similar ids: " + String.join(", ", similar.subList(0, Math.min(similar.size(), MAX_SUGGESTIONS))) + ".";
    }

    /** Ids whose path contains the query's path (or, failing that, any of its underscore-separated words). */
    static List<String> suggestions(List<String> ids, String normalized) {
        String path = normalized.substring(normalized.indexOf(':') + 1);
        TreeSet<String> exact = new TreeSet<>();
        TreeSet<String> byWord = new TreeSet<>();
        for (String id : ids) {
            String idPath = id.substring(id.indexOf(':') + 1);
            if (idPath.contains(path)) {
                exact.add(id);
                continue;
            }
            for (String word : path.split("_")) {
                if (word.length() >= 4 && idPath.contains(word)) {
                    byWord.add(id);
                    break;
                }
            }
        }
        TreeSet<String> chosen = exact.isEmpty() ? byWord : exact;
        return chosen.stream().limit(MAX_SUGGESTIONS).toList();
    }
}
