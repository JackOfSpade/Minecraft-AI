package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * String-in / enum-out vocabulary of the point-of-interest detector (mining-assist design 6.2 and
 * 6.3): which {@link PoiBucket} a block id belongs to, which ids are habitation, warden-risk or
 * structure-label markers, and how much an entity id contributes as evidence. Pure and stateless: no
 * registry access, no time, no randomness, so it is safe to call from any thread and from plain JUnit.
 *
 * <p><b>Input handling.</b> Ids are trimmed and lower-cased ({@link Locale#ROOT}). A {@code null} or
 * blank namespace means {@code minecraft}. A path that itself carries a {@code namespace:} prefix is
 * split, and the embedded namespace wins. A {@code null} or blank path carries no information, so it
 * classifies as {@link PoiBucket#NATURAL} (no evidence) rather than inventing a false positive.</p>
 *
 * <p><b>Classification order of {@link #classify}.</b></p>
 * <ol>
 *   <li>Non-{@code minecraft} namespace: {@link PoiBucket#NATURAL} when {@code naturalTag} is set or
 *       the path matches the small modded-natural allowlist, otherwise {@link PoiBucket#MODDED}. A
 *       modded clone of a vanilla id (an {@code oak_planks} from another mod) is MODDED by design.</li>
 *   <li>Vanilla natural list (ores, stone/soil/snow/ice families, cave vegetation, nether terrain,
 *       plain sculk, air and fluids): {@link PoiBucket#NATURAL}. Built variants (polished, bricks,
 *       chiseled, cut) are not on the list.</li>
 *   <li>Vanilla buckets by id pattern, most specific first: SPAWNER, CONTAINER, SCULK_STRUCT,
 *       DEEPSLATE_BUILD, RAIL, WEB, COPPER_TUFF_BUILD, STONE_BUILD, WOOD_BUILD, FURNISHING,
 *       FOSSIL_GEODE, LIGHT_DRESSING, COBBLE.</li>
 *   <li>Any other block entity: CONTAINER. Everything else: UNCLASSIFIED.</li>
 * </ol>
 *
 * <p><b>The {@code naturalTag} flag</b> is the caller's assertion that this block is naturally
 * occurring in its current context (a natural block tag such as a base-stone, dirt or ore tag, or a
 * lush biome for logs and leaves). It is honoured only where it cannot hide evidence: for vanilla
 * {@code *_log} (not stripped) and {@code *_leaves} it makes the block NATURAL (design: natural only in
 * a lush biome, otherwise UNCLASSIFIED), and for a non-{@code minecraft} namespace it makes the block
 * NATURAL. It never overrides a vanilla structure marker, so a biome-wide flag passed for every block
 * cannot turn a spawner, chest or stone brick into terrain.</p>
 */
public final class PoiLexicon {
    public static final String VANILLA_NAMESPACE = "minecraft";

    /** Evidence contributed by a {@code chest_minecart}. */
    public static final double ENTITY_SCORE_CHEST_MINECART = 0.6D;
    /** Evidence contributed by each villager, pillager, vindicator, evoker or illusioner. */
    public static final double ENTITY_SCORE_VILLAGER_CLASS = 0.3D;
    /** Evidence contributed by each item frame or armor stand. */
    public static final double ENTITY_SCORE_DECOR = 0.15D;
    /** Evidence contributed by any entity whose namespace is not {@code minecraft}. */
    public static final double ENTITY_SCORE_MODDED = 0.4D;
    /** Cap of the summed entity group. Applied by the scorer, never by {@link #entityScore}. */
    public static final double ENTITY_GROUP_CAP = 0.6D;

    /** Normalized (namespace, path) pair. */
    private record Id(String namespace, String path) {
        boolean vanilla() {
            return VANILLA_NAMESPACE.equals(namespace);
        }
    }

    // ---- Vanilla natural list (design 6.2 step 1) ----

    private static final Set<String> NATURAL = setOf(
            // air and fluids
            "air", "cave_air", "void_air", "water", "lava", "bubble_column",
            // stone, deepslate, tuff and friends: the plain material only. polished_*, *_bricks,
            // chiseled_* and cut_* are builds and never natural.
            "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite",
            "basalt", "smooth_basalt", "infested_stone", "infested_deepslate",
            "dripstone_block", "pointed_dripstone",
            "blackstone", "gilded_blackstone", "netherrack", "magma_block", "soul_sand", "soul_soil",
            "obsidian", "bedrock", "end_stone", "ancient_debris",
            // soils
            "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "grass_block",
            "gravel", "sand", "red_sand", "clay", "mud", "sandstone", "red_sandstone", "terracotta",
            // snow and ice
            "snow", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice", "frosted_ice",
            // moss, lush caves and hanging growth
            "moss_block", "moss_carpet", "pale_moss_block", "pale_moss_carpet", "pale_hanging_moss",
            "azalea", "flowering_azalea", "azalea_leaves", "flowering_azalea_leaves",
            "glow_lichen", "lichen", "vine", "cave_vines", "cave_vines_plant",
            "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant",
            "spore_blossom", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf",
            // ground cover and water plants
            "grass", "short_grass", "tall_grass", "fern", "large_fern", "short_dry_grass",
            "tall_dry_grass", "dead_bush", "bush", "firefly_bush", "leaf_litter", "wildflowers",
            "cactus", "cactus_flower", "sugar_cane", "bamboo", "bamboo_sapling", "sweet_berry_bush",
            "kelp", "kelp_plant", "seagrass", "tall_seagrass", "lily_pad", "frogspawn",
            "sea_pickle", "turtle_egg",
            // flowers
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "wither_rose", "torchflower", "pitcher_plant", "sunflower", "lilac", "rose_bush", "peony",
            "pink_petals", "open_eyeblossom", "closed_eyeblossom",
            // mushrooms, fungi, nylium, glow
            "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block",
            "mushroom_stem", "crimson_fungus", "warped_fungus", "crimson_stem", "warped_stem",
            "nether_wart_block", "warped_wart_block", "crimson_nylium", "warped_nylium",
            "nether_sprouts", "shroomlight", "glowstone",
            // plain sculk is counted separately for the warden rule, not as structure evidence
            "sculk", "sculk_vein",
            // stray flames
            "fire", "soul_fire");

    // ---- Vanilla bucket vocabularies (design 6.2 step 2) ----

    private static final Set<String> SPAWNER_FAMILY = setOf("spawner", "trial_spawner", "vault");

    private static final Set<String> SCULK_STRUCT = setOf(
            "sculk_shrieker", "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst",
            "reinforced_deepslate");

    private static final Set<String> RAILS = setOf(
            "rail", "powered_rail", "detector_rail", "activator_rail");

    private static final String[] WOOD_TYPES = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "pale_oak", "bamboo", "crimson", "warped"};

    /** What may follow a wood type to make a wooden build block ({@code oak_fence_gate}). */
    private static final Set<String> WOOD_BUILD_SUFFIXES = setOf(
            "fence", "fence_gate", "door", "trapdoor", "stairs", "slab", "sign", "wall_sign",
            "hanging_sign", "wall_hanging_sign", "mosaic", "mosaic_stairs", "mosaic_slab");

    private static final Set<String> FURNISHING = setOf(
            "bookshelf", "chiseled_bookshelf", "enchanting_table", "brewing_stand", "furnace",
            "blast_furnace", "smoker", "crafting_table", "bed", "banner", "wool", "carpet",
            "hay_block", "anvil", "chipped_anvil", "damaged_anvil");

    private static final Set<String> FOSSIL_GEODE = setOf(
            "bone_block", "amethyst_block", "budding_amethyst", "small_amethyst_bud",
            "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster");

    private static final Set<String> LIGHT_DRESSING = setOf(
            "lantern", "soul_lantern", "candle", "torch", "wall_torch", "soul_torch",
            "soul_wall_torch", "campfire", "soul_campfire");

    private static final Set<String> COBBLE = setOf("cobblestone", "cobbled_deepslate");

    // ---- Habitation, warden and entity vocabularies (design 6.3) ----

    /**
     * Habitation ids mapped to the design's nine canonical names (bed, crafting_table, furnace,
     * brewing_stand, enchanting_table, bookshelf, anvil, item_frame, armor_stand). Coloured beds
     * ({@code *_bed}) are handled by suffix in {@link #habitationKey}.
     */
    private static final Map<String, String> HABITATION_KEYS = Map.ofEntries(
            Map.entry("bed", "bed"),
            Map.entry("crafting_table", "crafting_table"),
            Map.entry("furnace", "furnace"),
            Map.entry("blast_furnace", "furnace"),
            Map.entry("smoker", "furnace"),
            Map.entry("brewing_stand", "brewing_stand"),
            Map.entry("enchanting_table", "enchanting_table"),
            Map.entry("bookshelf", "bookshelf"),
            Map.entry("chiseled_bookshelf", "bookshelf"),
            Map.entry("anvil", "anvil"),
            Map.entry("chipped_anvil", "anvil"),
            Map.entry("damaged_anvil", "anvil"),
            Map.entry("item_frame", "item_frame"),
            Map.entry("glow_item_frame", "item_frame"),
            Map.entry("armor_stand", "armor_stand"));

    private static final Set<String> WARDEN_SINGLE_TRIGGER = setOf(
            "reinforced_deepslate", "sculk_shrieker", "sculk_catalyst");

    private static final Set<String> SCULK_SENSORS = setOf("sculk_sensor", "calibrated_sculk_sensor");

    private static final Set<String> VILLAGER_CLASS = setOf(
            "villager", "pillager", "vindicator", "evoker", "illusioner");

    private static final Set<String> ENTITY_DECOR = setOf("item_frame", "glow_item_frame", "armor_stand");

    // ---- Modded natural allowlist (design: "extend this list for Terralith cave material") ----

    /**
     * Path suffixes of non-vanilla blocks that read as ordinary terrain. Deliberately small: every
     * entry costs recall on modded structure blocks that happen to share the suffix, and never adds a
     * false positive. Extend when a concrete modded cave material shows up in a log.
     */
    private static final String[] MODDED_NATURAL_SUFFIXES = {
            "_ore", "_stone", "_rock", "_sand", "_sandstone", "_dirt", "_gravel", "_clay", "_moss",
            "_mud", "_soil", "_grass_block", "_deepslate", "_tuff", "_basalt", "_ice", "_snow"};

    private static final Set<String> MODDED_NATURAL_EXACT = setOf(
            "stone", "dirt", "sand", "gravel", "clay", "rock");

    /** A modded path starting with one of these is a crafted or decorative block, not terrain. */
    private static final String[] MODDED_BUILT_PREFIXES = {
            "polished_", "chiseled_", "carved_", "smooth_", "cut_", "cracked_"};

    private PoiLexicon() {
    }

    // ---------------------------------------------------------------------------------------
    // Block classification
    // ---------------------------------------------------------------------------------------

    /**
     * Buckets a block id. Never returns {@code null}. See the class comment for the exact order.
     *
     * @param namespace     registry namespace, {@code null} or blank meaning {@code minecraft}
     * @param path          registry path ({@code "oak_planks"}); {@code null} or blank yields NATURAL
     * @param hasBlockEntity whether the block carries a block entity (fallback to CONTAINER)
     * @param naturalTag    caller's "naturally occurring here" assertion, honoured only for vanilla
     *                      logs and leaves and for non-{@code minecraft} namespaces
     */
    public static PoiBucket classify(String namespace, String path, boolean hasBlockEntity,
            boolean naturalTag) {
        Id id = parse(namespace, path);
        String p = id.path();
        if (p.isEmpty()) {
            return PoiBucket.NATURAL;
        }
        if (!id.vanilla()) {
            return naturalTag || isModdedNatural(p) ? PoiBucket.NATURAL : PoiBucket.MODDED;
        }
        return classifyVanilla(p, hasBlockEntity, naturalTag);
    }

    private static PoiBucket classifyVanilla(String p, boolean hasBlockEntity, boolean naturalTag) {
        if (isVanillaNatural(p)) {
            return PoiBucket.NATURAL;
        }
        if (naturalTag && isContextNatural(p)) {
            return PoiBucket.NATURAL;
        }
        if (SPAWNER_FAMILY.contains(p)) {
            return PoiBucket.SPAWNER;
        }
        if (isContainerName(p)) {
            return PoiBucket.CONTAINER;
        }
        if (SCULK_STRUCT.contains(p)) {
            return PoiBucket.SCULK_STRUCT;
        }
        if (isDeepslateBuild(p)) {
            return PoiBucket.DEEPSLATE_BUILD;
        }
        if (RAILS.contains(p)) {
            return PoiBucket.RAIL;
        }
        if (p.equals("cobweb")) {
            return PoiBucket.WEB;
        }
        // The copper/tuff rows precede the generic chiseled_/polished_/cut_ rule of STONE_BUILD so
        // chiseled_copper and polished_tuff land in their own bucket.
        if (isCopperTuffBuild(p)) {
            return PoiBucket.COPPER_TUFF_BUILD;
        }
        if (isStoneBuild(p)) {
            return PoiBucket.STONE_BUILD;
        }
        if (isWoodBuild(p)) {
            return PoiBucket.WOOD_BUILD;
        }
        if (isFurnishing(p)) {
            return PoiBucket.FURNISHING;
        }
        if (FOSSIL_GEODE.contains(p)) {
            return PoiBucket.FOSSIL_GEODE;
        }
        if (isLightDressing(p)) {
            return PoiBucket.LIGHT_DRESSING;
        }
        if (COBBLE.contains(p)) {
            return PoiBucket.COBBLE;
        }
        return hasBlockEntity ? PoiBucket.CONTAINER : PoiBucket.UNCLASSIFIED;
    }

    private static boolean isVanillaNatural(String p) {
        return NATURAL.contains(p)
                || p.endsWith("_ore")
                || p.startsWith("raw_")
                // potted_crimson_roots and friends are flower-pot decoration, like every other potted_*
                || (p.endsWith("_roots") && !p.startsWith("potted_"))
                || (p.endsWith("_terracotta") && !p.endsWith("glazed_terracotta"))
                || p.contains("coral");
    }

    /** Vanilla blocks that are natural only in the right context (the lush-biome rule). */
    private static boolean isContextNatural(String p) {
        return p.endsWith("_leaves") || (p.endsWith("_log") && !p.startsWith("stripped_"));
    }

    private static boolean isContainerName(String p) {
        return p.equals("chest") || p.endsWith("_chest")
                || p.equals("barrel") || p.equals("decorated_pot")
                || p.equals("shulker_box") || p.endsWith("_shulker_box");
    }

    private static boolean isDeepslateBuild(String p) {
        return p.contains("deepslate_tile") || p.contains("deepslate_brick")
                || p.contains("polished_deepslate") || p.contains("chiseled_deepslate")
                || p.startsWith("cracked_deepslate");
    }

    private static boolean isCopperTuffBuild(String p) {
        // Natural copper ore and raw copper never reach here (natural list runs first).
        return p.contains("copper")
                || p.startsWith("waxed_")
                || p.startsWith("tuff_")
                || p.startsWith("polished_tuff")
                || p.startsWith("chiseled_tuff");
    }

    private static boolean isStoneBuild(String p) {
        // Any *brick* block: stone/mossy/cracked stone bricks, nether and red nether bricks, polished
        // blackstone bricks, plus their stairs, slabs, walls and fences. Deepslate and tuff bricks were
        // taken by their own rows first.
        if (p.contains("brick")) {
            return true;
        }
        if (p.equals("mossy_cobblestone") || p.startsWith("mossy_cobblestone_")) {
            return true;
        }
        if (p.equals("iron_bars") || p.equals("glass") || p.equals("glass_pane")
                || p.equals("tinted_glass")
                || p.endsWith("_stained_glass") || p.endsWith("_stained_glass_pane")) {
            return true;
        }
        if (p.equals("chiseled_bookshelf")) {
            return false;
        }
        return p.startsWith("chiseled_") || p.startsWith("polished_") || p.startsWith("cut_");
    }

    private static boolean isWoodBuild(String p) {
        if (p.endsWith("_planks") || p.equals("ladder") || p.equals("scaffolding")) {
            return true;
        }
        for (String type : WOOD_TYPES) {
            if (p.length() > type.length() + 1 && p.startsWith(type) && p.charAt(type.length()) == '_') {
                return WOOD_BUILD_SUFFIXES.contains(p.substring(type.length() + 1));
            }
        }
        return false;
    }

    private static boolean isFurnishing(String p) {
        return FURNISHING.contains(p)
                || p.endsWith("_bed") || p.endsWith("_banner")
                || p.endsWith("_wool") || p.endsWith("_carpet");
    }

    private static boolean isLightDressing(String p) {
        return LIGHT_DRESSING.contains(p) || p.endsWith("_candle");
    }

    private static boolean isModdedNatural(String p) {
        if (p.endsWith("_ore") || p.startsWith("raw_")) {
            return true;
        }
        for (String prefix : MODDED_BUILT_PREFIXES) {
            if (p.startsWith(prefix)) {
                return false;
            }
        }
        if (p.contains("brick") || p.contains("tile")) {
            return false;
        }
        if (MODDED_NATURAL_EXACT.contains(p)) {
            return true;
        }
        for (String suffix : MODDED_NATURAL_SUFFIXES) {
            if (p.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Habitation, sculk and warden helpers (namespace-less overloads accept vanilla ids only)
    // ---------------------------------------------------------------------------------------

    /**
     * True for the player-base markers of the habitation downgrade: bed (any colour), crafting table,
     * furnace (also blast furnace and smoker), brewing stand, enchanting table, bookshelf (also
     * chiseled), anvil (also chipped and damaged), item frame (also glow) and armor stand. Works on
     * block and entity paths alike.
     */
    public static boolean isHabitationItem(String path) {
        return isHabitationItem(null, path);
    }

    public static boolean isHabitationItem(String namespace, String path) {
        return habitationKey(namespace, path) != null;
    }

    /**
     * The design's canonical habitation name of an id, or {@code null} when it is not a habitation
     * item: {@code bed} (any colour), {@code crafting_table}, {@code furnace} (also blast furnace and
     * smoker), {@code brewing_stand}, {@code enchanting_table}, {@code bookshelf} (also chiseled),
     * {@code anvil} (also chipped and damaged), {@code item_frame} (also glow) and {@code armor_stand}.
     * Upper-casing the result gives the {@code PoiSignals.Habitation} constant. Vanilla ids only.
     */
    public static String habitationKey(String namespace, String path) {
        Id id = parse(namespace, path);
        if (!id.vanilla()) {
            return null;
        }
        String p = id.path();
        if (p.endsWith("_bed")) {
            return "bed";
        }
        return HABITATION_KEYS.get(p);
    }

    /** Plain sculk, sculk_vein, sculk_sensor, calibrated_sculk_sensor, sculk_shrieker, sculk_catalyst. */
    public static boolean isSculkFamily(String path) {
        return isSculkFamily(null, path);
    }

    public static boolean isSculkFamily(String namespace, String path) {
        Id id = parse(namespace, path);
        String p = id.path();
        return id.vanilla() && (p.equals("sculk") || p.startsWith("sculk_")
                || p.equals("calibrated_sculk_sensor"));
    }

    /**
     * Blocks that make the mandatory warden rule fire: reinforced_deepslate, sculk_shrieker,
     * sculk_catalyst (one is enough) and sculk_sensor, calibrated_sculk_sensor (two are needed).
     */
    public static boolean isWarnBlockForWarden(String path) {
        return isWarnBlockForWarden(null, path);
    }

    public static boolean isWarnBlockForWarden(String namespace, String path) {
        return isWardenSingleTrigger(namespace, path) || isSculkSensor(namespace, path);
    }

    /** The subset of warn blocks where a single cell fires the mandatory rule. */
    public static boolean isWardenSingleTrigger(String path) {
        return isWardenSingleTrigger(null, path);
    }

    public static boolean isWardenSingleTrigger(String namespace, String path) {
        Id id = parse(namespace, path);
        return id.vanilla() && WARDEN_SINGLE_TRIGGER.contains(id.path());
    }

    /** The subset of warn blocks where two cells are needed: sculk_sensor, calibrated_sculk_sensor. */
    public static boolean isSculkSensor(String path) {
        return isSculkSensor(null, path);
    }

    public static boolean isSculkSensor(String namespace, String path) {
        Id id = parse(namespace, path);
        return id.vanilla() && SCULK_SENSORS.contains(id.path());
    }

    // ---------------------------------------------------------------------------------------
    // Label markers (design 6.3): raw-id inputs of PoiSignals' boolean flags. Buckets alone cannot
    // say them (iron_bars and stone bricks are both STONE_BUILD, blackstone is NATURAL), so the caller
    // runs these on every observed block id, natural cells included. Vanilla ids only.
    // ---------------------------------------------------------------------------------------

    /** {@code vault}. */
    public static boolean isVault(String namespace, String path) {
        Id id = parse(namespace, path);
        return id.vanilla() && id.path().equals("vault");
    }

    /** {@code iron_bars}. */
    public static boolean isIronBars(String namespace, String path) {
        Id id = parse(namespace, path);
        return id.vanilla() && id.path().equals("iron_bars");
    }

    /**
     * The {@code mossy_cobblestone} family: the block and its stairs, slab and wall. Mossy stone
     * bricks are not included, they belong to {@link #isStoneBricks}.
     */
    public static boolean isMossyStone(String namespace, String path) {
        Id id = parse(namespace, path);
        String p = id.path();
        return id.vanilla() && (p.equals("mossy_cobblestone") || p.startsWith("mossy_cobblestone_"));
    }

    /**
     * The {@code stone_bricks} family: plain, mossy, cracked and chiseled stone bricks, the stairs,
     * slab and wall of the plain and mossy ones, and the silverfish-infested variants (the stronghold
     * marker). Nether, deepslate, tuff, mud and end stone bricks are separate families.
     */
    public static boolean isStoneBricks(String namespace, String path) {
        Id id = parse(namespace, path);
        if (!id.vanilla()) {
            return false;
        }
        String p = id.path();
        if (p.startsWith("infested_")) {
            p = p.substring("infested_".length());
        }
        return p.startsWith("stone_brick") || p.startsWith("mossy_stone_brick")
                || p.startsWith("cracked_stone_brick") || p.startsWith("chiseled_stone_brick");
    }

    /**
     * The nether bricks family: nether, red nether, cracked and chiseled nether bricks and the
     * nether/red nether brick fence, stairs, slab and wall.
     */
    public static boolean isNetherBricks(String namespace, String path) {
        Id id = parse(namespace, path);
        return id.vanilla() && id.path().contains("nether_brick");
    }

    /** Any blackstone block, natural (blackstone, gilded) or built (polished, bricks, chiseled, shapes). */
    public static boolean isBlackstoneFamily(String namespace, String path) {
        Id id = parse(namespace, path);
        return id.vanilla() && id.path().contains("blackstone");
    }

    // ---------------------------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------------------------

    /**
     * Evidence value of one entity. The group cap {@link #ENTITY_GROUP_CAP} is the scorer's job.
     * chest_minecart 0.6; villager, pillager, vindicator, evoker, illusioner 0.3 each; item_frame,
     * glow_item_frame, armor_stand 0.15 each; any non-{@code minecraft} namespace 0.4; the rest 0.
     */
    public static double entityScore(String namespace, String path) {
        Id id = parse(namespace, path);
        String p = id.path();
        if (p.isEmpty()) {
            return 0.0D;
        }
        if (!id.vanilla()) {
            return ENTITY_SCORE_MODDED;
        }
        if (p.equals("chest_minecart")) {
            return ENTITY_SCORE_CHEST_MINECART;
        }
        if (VILLAGER_CLASS.contains(p)) {
            return ENTITY_SCORE_VILLAGER_CLASS;
        }
        if (ENTITY_DECOR.contains(p)) {
            return ENTITY_SCORE_DECOR;
        }
        return 0.0D;
    }

    /** A visible {@code minecraft:warden} is a mandatory stop on its own. */
    public static boolean isWarden(String namespace, String path) {
        Id id = parse(namespace, path);
        return id.vanilla() && id.path().equals("warden");
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private static Id parse(String namespace, String path) {
        String p = path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
        String ns = namespace == null ? "" : namespace.trim().toLowerCase(Locale.ROOT);
        int colon = p.indexOf(':');
        if (colon >= 0) {
            String embedded = p.substring(0, colon).trim();
            p = p.substring(colon + 1).trim();
            if (!embedded.isEmpty()) {
                ns = embedded;
            }
        }
        if (ns.isEmpty()) {
            ns = VANILLA_NAMESPACE;
        }
        return new Id(ns, p);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
