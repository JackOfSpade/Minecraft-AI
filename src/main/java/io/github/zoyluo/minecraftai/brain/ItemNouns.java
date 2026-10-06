package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.craft.SmeltChain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
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
    /** Words that name creatures in general rather than one entity type. */
    private static final Set<String> CREATURE_GROUPS = Set.of(
            "mob", "monster", "animal", "hostile", "creature", "enemy");
    /** Colloquial one-word names for an item whose registry id is a different word. */
    private static final Map<String, String> ALIASES = Map.of(
            "table", "crafting_table", "workbench", "crafting_table", "cobble", "cobblestone",
            "lapis", "lapis_lazuli", "debris", "ancient_debris", "pork", "porkchop");
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
     * block, wool from sheep (every colour is also a dyeing recipe). The conversion is storage or
     * colouring, not the way the bot gets it.
     */
    private static final Pattern NATURAL_FORM = Pattern.compile(
            "(?:_log|_wood|_stem|_hyphae|_ore|_wool)$|^raw_[^_]+$");
    /**
     * Things the world generates or a creature drops that a recipe also makes (sand to sandstone, clay balls
     * to clay, rabbit hide to leather, a melon's slices back to the melon): that recipe is how vanilla
     * compacts or converts them, not how a player gets them. Listed by id because the recipe tables cannot say
     * so, and because a running server's recipe index holds these recipes where the unit tests' does not, which
     * would otherwise make them "crafted" only in the game. Every recipe result of the game's data that is also a
     * natural resource is here (ItemNounsTest checks the list against the real recipe data).
     */
    private static final Set<String> NATURAL_BLOCKS = Set.of(
            "grass_block", "moss_block", "pale_moss_block", "magma_block", "amethyst_block",
            "andesite", "diorite", "granite", "sandstone", "red_sandstone", "clay", "glowstone",
            "snow", "snow_block", "packed_ice", "blue_ice", "dripstone_block", "bone_block", "nether_wart_block",
            "warped_wart_block", "brown_mushroom_block", "red_mushroom_block", "coarse_dirt", "mossy_cobblestone",
            "muddy_mangrove_roots", "moss_carpet", "pale_moss_carpet", "melon", "melon_seeds", "pumpkin_seeds",
            "prismarine", "dark_prismarine", "sea_lantern", "leather", "magma_cream");
    /** Item ids contain a few grammar words ("lily_of_the_valley", "flint_and_steel"): they name nothing alone. */
    private static final Set<String> GRAMMAR_WORDS = Set.of("the", "of", "on", "a", "an", "and", "or", "in", "at", "to", "for");
    /** Words that say how much or which kind, not what: "stack of", "iron ore", "stone blocks". */
    private static final Set<String> GENERIC_NOUN_WORDS = Set.of(
            "ore", "ores", "block", "blocks", "item", "items", "stack", "stacks", "piece", "pieces", "pile",
            "piles", "dozen", "dozens", "of");
    /** Several words for one thing: the match keys of "wood" and "trees" are the log items' own word. */
    private static final Map<String, String> NOUN_SYNONYMS = Map.of(
            "wood", "log", "woods", "log", "tree", "log", "trees", "log", "trunk", "log", "timber", "log",
            "stone", "cobblestone", "cobble", "cobblestone", "pork", "porkchop");
    private static final int MAX_PHRASE_WORDS = 6;

    private static Map<String, List<Item>> byLastSegment;
    private static Set<String> segments;
    private static Set<Item> handwrittenOutputs;

    private ItemNouns() {
    }

    /** The kind of the item a noun phrase names, trying ever shorter tails ("fresh iron pickaxe" -> "iron pickaxe"). */
    static Kind classify(List<String> words) {
        return classify(words, true);
    }

    /**
     * Same, for a verb that decides how recipes count. {@code recipesCount} is false for a collecting verb
     * ("gather", "mine", "harvest"): a recipe that merely exists in the running game's recipe index (white wool
     * from string, a sea lantern from crystals) does not make a world resource crafted there, so only what is
     * crafted whatever the recipes say (gear, processed forms, the planner's own recipes and smelting chains)
     * is. That also makes the answer the same in a running game and in a bare test JVM.
     */
    static Kind classify(List<String> words, boolean recipesCount) {
        for (int start = 0; start < words.size(); start++) {
            Kind kind = resolve(words.subList(start, words.size()), recipesCount);
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
        return isCraftedOutput(item, true);
    }

    static boolean isCraftedOutput(Item item, boolean recipesCount) {
        if (item == Items.STONE) {
            // Breaking stone drops cobblestone, so "get 64 stone" is a mining request even though
            // stone itself is smelted from cobblestone.
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (NATURAL_FORM.matcher(path).find() || NATURAL_BLOCKS.contains(path)) {
            return false;
        }
        if (item.components().has(DataComponents.MAX_DAMAGE)
                || handwrittenOutputs().contains(item) || SmeltChain.rawFor(item) != null
                || recipesCount && RecipeRegistry.find(item).isPresent()) {
            return true;
        }
        return PROCESSED_FORM.matcher(path).find() || path.endsWith("_block");
    }

    /** True when the word is one whole underscore-separated segment of some item id ("porkchop", "cobblestone"). */
    static boolean isItemSegment(String word) {
        return segments().contains(word);
    }

    /** True when {@code path} (or a namespaced id) is a registered item. */
    static boolean isItemPath(String path) {
        return itemFor(path) != null;
    }

    /** True for a registered item that the world yields (not one the bot crafts); false for crafted or unknown ids. */
    static boolean isRawItemId(String id) {
        Item item = id == null ? null : itemFor(id.trim().toLowerCase(java.util.Locale.ROOT));
        return item != null && !isCraftedOutput(item);
    }

    /** True when the word on its own names an item or a group of them: "logs", "coal", "cobble", "wood". */
    static boolean isNounWord(String word) {
        if (GRAMMAR_WORDS.contains(word)) {
            return false;
        }
        for (String variant : singularVariants(word)) {
            if (isItemSegment(variant) || ALIASES.containsKey(variant)
                    || RAW_GROUPS.contains(variant) || CRAFTED_GROUPS.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the noun phrase names a creature ("cows", "cave spider", "mobs"), the thing a hunter kills,
     * rather than anything it drops.
     */
    static boolean isCreature(List<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        String prefix = words.size() == 1 ? "" : String.join("_", words.subList(0, words.size() - 1)) + "_";
        for (String variant : singularVariants(words.get(words.size() - 1))) {
            if (CREATURE_GROUPS.contains(variant)) {
                return true;
            }
            Identifier id = Identifier.tryParse("minecraft:" + prefix + variant);
            if (id != null && BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                    .filter(type -> type != EntityType.PLAYER).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The words an item has to contain (as one whole id segment) to be what a noun phrase names: "oak logs"
     * gives {oak, log, logs}, "wood" gives {log}. Empty when the phrase says nothing specific ("stacks of").
     */
    static Set<String> matchWords(List<String> noun) {
        Set<String> keys = new LinkedHashSet<>();
        for (String word : noun) {
            if (GENERIC_NOUN_WORDS.contains(word)) {
                continue;
            }
            for (String variant : singularVariants(word)) {
                keys.add(variant);
                String synonym = NOUN_SYNONYMS.get(variant);
                if (synonym != null) {
                    keys.add(synonym);
                }
            }
        }
        return keys;
    }

    /** Whether an item id ("minecraft:oak_log", "raw_iron", the literal "logs") is what {@link #matchWords} describes. */
    static boolean matchesItem(Set<String> keys, String itemId) {
        if (keys.isEmpty()) {
            return true;
        }
        String path = itemId.substring(itemId.indexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
        for (String segment : path.split("_")) {
            if (keys.contains(segment)) {
                return true;
            }
        }
        return false;
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

    private static Kind resolve(List<String> phrase, boolean recipesCount) {
        if (phrase.size() > MAX_PHRASE_WORDS) {
            return null;
        }
        String last = phrase.get(phrase.size() - 1);
        String prefix = phrase.size() == 1 ? "" : String.join("_", phrase.subList(0, phrase.size() - 1)) + "_";
        for (String variant : singularVariants(last)) {
            Item exact = itemFor(prefix + variant);
            if (exact != null) {
                return kindOf(exact, recipesCount);
            }
            if (phrase.size() > 1) {
                continue;
            }
            Item alias = ALIASES.containsKey(variant) ? itemFor(ALIASES.get(variant)) : null;
            if (alias != null) {
                return kindOf(alias, recipesCount);
            }
            if (RAW_GROUPS.contains(variant)) {
                return Kind.RAW;
            }
            if (CRAFTED_GROUPS.contains(variant)) {
                return Kind.CRAFTED;
            }
            Kind family = familyKind(variant, recipesCount);
            if (family != null) {
                return family;
            }
        }
        return null;
    }

    /** "logs", "pickaxe", "ore": the kind shared by every item whose id ends in that word, or null if they disagree. */
    private static Kind familyKind(String word, boolean recipesCount) {
        List<Item> family = byLastSegment().get(word);
        if (family == null) {
            return null;
        }
        Kind shared = null;
        for (Item item : family) {
            Kind kind = kindOf(item, recipesCount);
            if (shared != null && shared != kind) {
                return null;
            }
            shared = kind;
        }
        return shared;
    }

    private static Kind kindOf(Item item, boolean recipesCount) {
        return isCraftedOutput(item, recipesCount) ? Kind.CRAFTED : Kind.RAW;
    }

    private static Item itemFor(String path) {
        Identifier id = Identifier.tryParse(path.indexOf(':') >= 0 ? path : "minecraft:" + path);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).filter(item -> item != Items.AIR).orElse(null);
    }

    private static synchronized Set<Item> handwrittenOutputs() {
        if (handwrittenOutputs == null) {
            Set<Item> outputs = new HashSet<>();
            RecipeRegistry.all().forEach(recipe -> outputs.add(recipe.output()));
            handwrittenOutputs = outputs;
        }
        return handwrittenOutputs;
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
