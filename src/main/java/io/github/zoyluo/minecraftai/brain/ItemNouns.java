package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.craft.SmeltChain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What the item noun in a player's chat names: a resource the world yields (raw) or something the
 * bot produces by crafting/smelting (crafted). The words are resolved against the item registry
 * rather than a hand-written noun list, so a modded or newly added item resolves the same way, and
 * the raw/crafted decision uses the recipe knowledge the bot already plans with.
 */
final class ItemNouns {
    enum Kind { RAW, CRAFTED, UNKNOWN }

    /** Group words with no registry id of their own (an id always names one concrete variant). */
    private static final Set<String> RAW_GROUPS = Set.of(
            "wood", "tree", "flower", "meat", "food", "fish", "crop", "gem", "material", "resource");
    private static final Set<String> CRAFTED_GROUPS = Set.of(
            "armor", "armour", "tool", "gear", "weapon", "equipment", "kit", "set");
    /** Colloquial one-word names for an item whose registry id is a different word. */
    private static final Map<String, String> ALIASES = Map.of(
            "table", "crafting_table", "workbench", "crafting_table", "cobble", "cobblestone",
            "lapis", "lapis_lazuli", "debris", "ancient_debris");
    /**
     * Items whose registry id says "processed form": every ingot/nugget/storage block/plank/slab and
     * so on is made from something else. Recipes alone do not say so for storage blocks: the runtime
     * recipe index prunes ingot-to-block pairs on purpose because they are not an acquisition path.
     */
    private static final Pattern PROCESSED_FORM = Pattern.compile(
            "(?:^|_)(?:ingot|nugget|planks|slab|stairs|wall|fence|fence_gate|door|trapdoor|button|"
                    + "pressure_plate|sign|hanging_sign|boat|chest_boat|raft|chest_raft|bed|bricks?)$");
    /**
     * What the world yields even though a recipe converts it: wood from logs, raw metal from its
     * block. The conversion is storage, not the way the bot gets it.
     */
    private static final Pattern NATURAL_FORM = Pattern.compile("(?:_log|_wood|_stem|_hyphae|_ore)$|^raw_[^_]+$");
    /** Naturally generated "_block" items: the suffix does not make these a storage block. */
    private static final Set<String> NATURAL_BLOCKS = Set.of(
            "grass_block", "moss_block", "pale_moss_block", "magma_block", "amethyst_block");
    private static final int MAX_PHRASE_WORDS = 6;

    private static Map<String, List<Item>> byLastSegment;
    private static Set<String> segments;

    private ItemNouns() {
    }

    /** The kind of the item a noun phrase names, trying ever shorter tails ("fresh iron pickaxe" -> "iron pickaxe"). */
    static Kind classify(List<String> words) {
        for (int start = 0; start < words.size(); start++) {
            Kind kind = resolve(words.subList(start, words.size()));
            if (kind != null) {
                return kind;
            }
        }
        return Kind.UNKNOWN;
    }

    /**
     * Whether the item is something the bot makes rather than collects. Tools, weapons and armor
     * carry durability; the recipe tables cover everything else the planner crafts or smelts.
     */
    static boolean isCraftedOutput(Item item) {
        if (item == Items.STONE) {
            // Breaking stone drops cobblestone, so "get 64 stone" is a mining request even though
            // stone itself is smelted from cobblestone.
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (NATURAL_FORM.matcher(path).find()) {
            return false;
        }
        if (item.components().has(DataComponents.MAX_DAMAGE)
                || RecipeRegistry.find(item).isPresent() || SmeltChain.rawFor(item) != null) {
            return true;
        }
        return PROCESSED_FORM.matcher(path).find()
                || (path.endsWith("_block") && !NATURAL_BLOCKS.contains(path));
    }

    /** True when the word is one whole underscore-separated segment of some item id ("porkchop", "cobblestone"). */
    static boolean isItemSegment(String word) {
        return segments().contains(word);
    }

    /** True when {@code path} (or a namespaced id) is a registered item. */
    static boolean isItemPath(String path) {
        return itemFor(path) != null;
    }

    /** The spellings a plural player word can have as a registry word ("torches" -> "torch"). */
    static List<String> singularVariants(String word) {
        List<String> variants = new ArrayList<>(4);
        variants.add(word);
        if (word.length() > 3 && word.endsWith("ies")) {
            variants.add(word.substring(0, word.length() - 3) + "y");
        }
        if (word.length() > 3 && word.endsWith("ves")) {
            variants.add(word.substring(0, word.length() - 3) + "f");
            variants.add(word.substring(0, word.length() - 3) + "fe");
        }
        if (word.length() > 3 && word.endsWith("es")) {
            variants.add(word.substring(0, word.length() - 2));
        }
        if (word.length() > 2 && word.endsWith("s")) {
            variants.add(word.substring(0, word.length() - 1));
        }
        return variants;
    }

    private static Kind resolve(List<String> phrase) {
        if (phrase.size() > MAX_PHRASE_WORDS) {
            return null;
        }
        String last = phrase.get(phrase.size() - 1);
        String prefix = phrase.size() == 1 ? "" : String.join("_", phrase.subList(0, phrase.size() - 1)) + "_";
        for (String variant : singularVariants(last)) {
            Item exact = itemFor(prefix + variant);
            if (exact != null) {
                return kindOf(exact);
            }
            if (phrase.size() > 1) {
                continue;
            }
            Item alias = ALIASES.containsKey(variant) ? itemFor(ALIASES.get(variant)) : null;
            if (alias != null) {
                return kindOf(alias);
            }
            if (RAW_GROUPS.contains(variant)) {
                return Kind.RAW;
            }
            if (CRAFTED_GROUPS.contains(variant)) {
                return Kind.CRAFTED;
            }
            Kind family = familyKind(variant);
            if (family != null) {
                return family;
            }
        }
        return null;
    }

    /** "logs", "pickaxe", "ore": the kind shared by every item whose id ends in that word, or null if they disagree. */
    private static Kind familyKind(String word) {
        List<Item> family = byLastSegment().get(word);
        if (family == null) {
            return null;
        }
        Kind shared = null;
        for (Item item : family) {
            Kind kind = kindOf(item);
            if (shared != null && shared != kind) {
                return null;
            }
            shared = kind;
        }
        return shared;
    }

    private static Kind kindOf(Item item) {
        return isCraftedOutput(item) ? Kind.CRAFTED : Kind.RAW;
    }

    private static Item itemFor(String path) {
        Identifier id = Identifier.tryParse(path.indexOf(':') >= 0 ? path : "minecraft:" + path);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).filter(item -> item != Items.AIR).orElse(null);
    }

    private static synchronized Map<String, List<Item>> byLastSegment() {
        if (byLastSegment == null) {
            index();
        }
        return byLastSegment;
    }

    private static synchronized Set<String> segments() {
        if (segments == null) {
            index();
        }
        return segments;
    }

    private static void index() {
        Map<String, List<Item>> last = new HashMap<>();
        Set<String> all = new HashSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            String[] parts = path.split("_");
            all.addAll(List.of(parts));
            last.computeIfAbsent(parts[parts.length - 1], key -> new ArrayList<>()).add(item);
        }
        byLastSegment = last;
        segments = all;
    }
}
