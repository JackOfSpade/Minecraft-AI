package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the block/entity vocabulary of mining-assist design 6.2 and 6.3. */
class PoiLexiconTest {
    private static final String MC = "minecraft";

    private static PoiBucket classify(String path) {
        return PoiLexicon.classify(MC, path, false, false);
    }

    private static void assertBucket(PoiBucket expected, String... paths) {
        for (String path : paths) {
            assertEquals(expected, classify(path), "minecraft:" + path);
        }
    }

    // ------------------------------------------------------------------ 1. natural list

    @Test
    void oresAndRawBlocksAreNatural() {
        assertBucket(PoiBucket.NATURAL,
                "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "lapis_ore",
                "diamond_ore", "emerald_ore", "deepslate_coal_ore", "deepslate_iron_ore",
                "deepslate_copper_ore", "deepslate_gold_ore", "deepslate_redstone_ore",
                "deepslate_lapis_ore", "deepslate_diamond_ore", "deepslate_emerald_ore",
                "nether_gold_ore", "nether_quartz_ore", "ancient_debris",
                "raw_iron_block", "raw_copper_block", "raw_gold_block");
    }

    @Test
    void stoneDeepslateAndTuffFamiliesAreNatural() {
        assertBucket(PoiBucket.NATURAL,
                "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite",
                "basalt", "smooth_basalt", "infested_stone", "infested_deepslate",
                "dripstone_block", "pointed_dripstone");
    }

    @Test
    void soilsSandClayAreNatural() {
        assertBucket(PoiBucket.NATURAL,
                "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "grass_block",
                "gravel", "sand", "red_sand", "clay", "mud", "sandstone", "red_sandstone",
                "terracotta", "white_terracotta", "orange_terracotta", "red_terracotta",
                "yellow_terracotta", "brown_terracotta", "light_gray_terracotta");
    }

    @Test
    void snowAndIceAreNatural() {
        assertBucket(PoiBucket.NATURAL,
                "snow", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice", "frosted_ice");
    }

    @Test
    void lushCaveGrowthIsNatural() {
        assertBucket(PoiBucket.NATURAL,
                "moss_block", "moss_carpet", "pale_moss_block", "pale_moss_carpet", "azalea",
                "flowering_azalea", "azalea_leaves", "flowering_azalea_leaves", "hanging_roots",
                "glow_lichen", "lichen", "vine", "cave_vines", "cave_vines_plant", "spore_blossom",
                "big_dripleaf", "big_dripleaf_stem", "small_dripleaf", "mangrove_roots",
                "muddy_mangrove_roots");
    }

    @Test
    void grassFernsFlowersAndMushroomsAreNatural() {
        assertBucket(PoiBucket.NATURAL,
                "grass", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
                "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac",
                "rose_bush", "peony", "pink_petals", "leaf_litter", "wildflowers",
                "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block",
                "mushroom_stem", "sweet_berry_bush", "kelp", "seagrass", "lily_pad");
    }

    @Test
    void netherTerrainAndFungiAreNatural() {
        assertBucket(PoiBucket.NATURAL,
                "netherrack", "basalt", "blackstone", "gilded_blackstone", "magma_block",
                "soul_sand", "soul_soil", "obsidian", "bedrock", "crimson_nylium", "warped_nylium",
                "crimson_fungus", "warped_fungus", "crimson_stem", "warped_stem", "nether_wart_block",
                "warped_wart_block", "crimson_roots", "warped_roots", "weeping_vines",
                "twisting_vines", "shroomlight", "glowstone", "end_stone");
    }

    @Test
    void plainSculkFluidsAirAndFireAreNatural() {
        assertBucket(PoiBucket.NATURAL,
                "sculk", "sculk_vein", "water", "lava", "air", "cave_air", "void_air",
                "bubble_column", "fire", "soul_fire", "tube_coral", "dead_brain_coral_block",
                "fire_coral_fan");
    }

    @Test
    void glazedTerracottaIsNotNatural() {
        assertBucket(PoiBucket.UNCLASSIFIED, "white_glazed_terracotta", "red_glazed_terracotta");
    }

    @Test
    void naturalWinsOverBlockEntityFlag() {
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "stone", true, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "sculk", true, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "diamond_ore", true, false));
    }

    // ------------------------------------------------------------------ 2. built variants of stone

    @Test
    void polishedBricksChiseledAndCutVariantsAreNeverNatural() {
        assertBucket(PoiBucket.STONE_BUILD,
                "polished_andesite", "polished_granite", "polished_diorite", "polished_basalt",
                "polished_blackstone", "polished_blackstone_stairs", "polished_blackstone_slab",
                "chiseled_sandstone", "chiseled_red_sandstone", "chiseled_quartz_block",
                "chiseled_polished_blackstone", "cut_sandstone", "cut_red_sandstone",
                "cut_sandstone_slab");
        assertBucket(PoiBucket.DEEPSLATE_BUILD,
                "polished_deepslate", "chiseled_deepslate", "deepslate_bricks", "deepslate_tiles");
        assertBucket(PoiBucket.COPPER_TUFF_BUILD, "polished_tuff", "tuff_bricks", "chiseled_tuff");
    }

    @Test
    void shapeVariantsOfPlainNaturalMaterialsAreUnclassifiedNotNatural() {
        assertBucket(PoiBucket.UNCLASSIFIED,
                "stone_stairs", "stone_slab", "cobblestone_stairs", "cobblestone_slab",
                "cobblestone_wall", "andesite_stairs", "granite_slab", "diorite_wall",
                "sandstone_stairs", "red_sandstone_slab", "blackstone_stairs",
                "cobbled_deepslate_stairs", "smooth_stone", "smooth_sandstone", "smooth_quartz",
                "quartz_block", "quartz_pillar");
    }

    // ------------------------------------------------------------------ 3. buckets

    @Test
    void spawnerBucket() {
        assertBucket(PoiBucket.SPAWNER, "spawner", "trial_spawner", "vault");
        assertEquals(PoiBucket.SPAWNER, PoiLexicon.classify(MC, "spawner", true, false));
    }

    @Test
    void containerBucket() {
        assertBucket(PoiBucket.CONTAINER,
                "chest", "trapped_chest", "barrel", "ender_chest", "decorated_pot", "shulker_box",
                "white_shulker_box", "purple_shulker_box");
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(MC, "chest", true, false));
    }

    @Test
    void sculkStructBucket() {
        assertBucket(PoiBucket.SCULK_STRUCT,
                "sculk_shrieker", "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst",
                "reinforced_deepslate");
        assertEquals(PoiBucket.SCULK_STRUCT, PoiLexicon.classify(MC, "sculk_shrieker", true, false));
    }

    @Test
    void deepslateBuildBucket() {
        assertBucket(PoiBucket.DEEPSLATE_BUILD,
                "deepslate_tiles", "deepslate_bricks", "cracked_deepslate_bricks",
                "cracked_deepslate_tiles", "polished_deepslate", "chiseled_deepslate",
                "deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_tile_wall",
                "deepslate_brick_stairs", "deepslate_brick_slab", "deepslate_brick_wall",
                "polished_deepslate_stairs", "polished_deepslate_slab", "polished_deepslate_wall");
    }

    @Test
    void railBucket() {
        assertBucket(PoiBucket.RAIL, "rail", "powered_rail", "detector_rail", "activator_rail");
    }

    @Test
    void webBucket() {
        assertBucket(PoiBucket.WEB, "cobweb");
    }

    @Test
    void stoneBuildBucket() {
        assertBucket(PoiBucket.STONE_BUILD,
                "stone_bricks", "stone_brick_stairs", "stone_brick_slab", "stone_brick_wall",
                "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks",
                "mossy_stone_brick_stairs", "infested_stone_bricks",
                "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks",
                "mossy_cobblestone", "mossy_cobblestone_stairs", "mossy_cobblestone_slab",
                "mossy_cobblestone_wall", "iron_bars", "glass", "glass_pane", "tinted_glass",
                "white_stained_glass", "red_stained_glass_pane", "nether_bricks", "red_nether_bricks",
                "cracked_nether_bricks", "chiseled_nether_bricks", "nether_brick_fence",
                "nether_brick_stairs", "polished_blackstone_bricks",
                "cracked_polished_blackstone_bricks", "polished_blackstone_brick_wall", "bricks",
                "mud_bricks", "end_stone_bricks", "prismarine_bricks");
    }

    @Test
    void copperAndTuffBuildBucket() {
        assertBucket(PoiBucket.COPPER_TUFF_BUILD,
                "copper_bulb", "exposed_copper_bulb", "weathered_copper_bulb", "oxidized_copper_bulb",
                "waxed_copper_bulb", "copper_grate", "waxed_oxidized_copper_grate", "chiseled_copper",
                "waxed_chiseled_copper", "cut_copper", "cut_copper_stairs", "cut_copper_slab",
                "waxed_cut_copper", "waxed_weathered_cut_copper_stairs", "copper_block",
                "exposed_copper", "copper_door", "copper_trapdoor", "tuff_bricks",
                "tuff_brick_stairs", "tuff_brick_slab", "tuff_brick_wall", "polished_tuff",
                "polished_tuff_stairs", "polished_tuff_slab", "polished_tuff_wall", "chiseled_tuff",
                "chiseled_tuff_bricks", "tuff_stairs", "tuff_slab", "tuff_wall");
    }

    @Test
    void woodBuildBucket() {
        assertBucket(PoiBucket.WOOD_BUILD,
                "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
                "dark_oak_planks", "mangrove_planks", "cherry_planks", "pale_oak_planks",
                "bamboo_planks", "crimson_planks", "warped_planks", "oak_fence", "dark_oak_fence",
                "bamboo_fence", "warped_fence",
                "oak_fence_gate", "dark_oak_fence_gate", "spruce_door", "jungle_trapdoor",
                "oak_stairs", "birch_slab", "acacia_stairs", "pale_oak_slab", "oak_sign",
                "oak_wall_sign", "oak_hanging_sign", "oak_wall_hanging_sign", "ladder", "scaffolding",
                "bamboo_mosaic", "bamboo_mosaic_stairs", "bamboo_mosaic_slab");
    }

    @Test
    void woodBuildRequiresAWoodType() {
        assertBucket(PoiBucket.UNCLASSIFIED,
                "iron_door", "iron_trapdoor", "oak_button", "oak_pressure_plate", "oak_sapling",
                "oak_wood", "stripped_oak_log", "stripped_oak_wood", "crimson_hyphae",
                "stripped_crimson_stem", "bamboo_block", "oak");
    }

    @Test
    void furnishingBucket() {
        assertBucket(PoiBucket.FURNISHING,
                "bookshelf", "chiseled_bookshelf", "enchanting_table", "brewing_stand", "furnace",
                "blast_furnace", "smoker", "crafting_table", "white_bed", "red_bed", "black_bed",
                "white_banner", "black_wall_banner", "white_wool", "red_wool", "white_carpet",
                "blue_carpet", "hay_block", "anvil", "chipped_anvil", "damaged_anvil");
        // Furnishings are bucketed by name even though several carry block entities.
        assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, "white_bed", true, false));
        assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, "furnace", true, false));
        assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, "enchanting_table", true, false));
        assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, "chiseled_bookshelf", true, false));
    }

    @Test
    void mossCarpetIsNaturalNotFurnishing() {
        assertEquals(PoiBucket.NATURAL, classify("moss_carpet"));
        assertEquals(PoiBucket.FURNISHING, classify("white_carpet"));
    }

    @Test
    void fossilGeodeBucket() {
        assertBucket(PoiBucket.FOSSIL_GEODE,
                "bone_block", "amethyst_block", "budding_amethyst", "small_amethyst_bud",
                "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster");
    }

    @Test
    void lightDressingBucketIsWeak() {
        assertBucket(PoiBucket.LIGHT_DRESSING,
                "lantern", "soul_lantern", "candle", "white_candle", "red_candle", "torch",
                "wall_torch", "soul_torch", "soul_wall_torch", "campfire", "soul_campfire");
        assertEquals(PoiBucket.LIGHT_DRESSING, PoiLexicon.classify(MC, "campfire", true, false));
        assertEquals(PoiBucket.LIGHT_DRESSING, PoiLexicon.classify(MC, "soul_campfire", true, false));
        assertTrue(classify("torch").isWeak());
    }

    @Test
    void cobbleBucketIsWeak() {
        assertBucket(PoiBucket.COBBLE, "cobblestone", "cobbled_deepslate");
        assertTrue(classify("cobblestone").isWeak());
    }

    @Test
    void lookalikesOfLightDressingAreNotWeak() {
        assertBucket(PoiBucket.UNCLASSIFIED,
                "sea_lantern", "jack_o_lantern", "redstone_torch", "redstone_wall_torch",
                "candle_cake", "white_candle_cake", "end_rod", "redstone_lamp");
    }

    @Test
    void unclassifiedBucket() {
        assertBucket(PoiBucket.UNCLASSIFIED,
                "coal_block", "iron_block", "gold_block", "diamond_block", "redstone_block",
                "crying_obsidian", "dirt_path", "farmland", "cake", "tnt", "lever", "redstone_wire",
                "repeater", "stone_pressure_plate", "stone_button", "end_portal_frame", "packed_mud",
                "slime_block", "honey_block", "tripwire", "tripwire_hook", "loom", "smithing_table",
                "cartography_table", "grindstone", "stonecutter", "composter", "cauldron",
                "potted_poppy", "lightning_rod", "sponge", "note_block", "sticky_piston");
    }

    // ------------------------------------------------------------------ 4. block entity fallback

    @Test
    void otherBlockEntitiesFallBackToContainer() {
        for (String path : List.of("dispenser", "dropper", "hopper", "lectern", "jukebox", "beacon",
                "conduit", "comparator", "daylight_detector", "skeleton_skull", "player_head",
                "wither_skeleton_skull", "bee_nest", "beehive", "suspicious_sand",
                "suspicious_gravel", "command_block", "end_portal", "crafter", "bell")) {
            assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(MC, path, true, false), path);
            // Without the block-entity flag none of them is named by the table.
            assertEquals(PoiBucket.UNCLASSIFIED, PoiLexicon.classify(MC, path, false, false), path);
        }
    }

    @Test
    void blockEntityFlagNeverChangesANamedBucket() {
        List<String> samples = vanillaSamples();
        for (String path : samples) {
            PoiBucket without = PoiLexicon.classify(MC, path, false, false);
            PoiBucket with = PoiLexicon.classify(MC, path, true, false);
            if (without == PoiBucket.UNCLASSIFIED) {
                assertEquals(PoiBucket.CONTAINER, with, path);
            } else {
                assertEquals(without, with, path);
            }
        }
    }

    // ------------------------------------------------------------------ 5. lush / naturalTag

    @Test
    void logsAndLeavesAreUnclassifiedUnlessNaturalTagged() {
        for (String path : List.of("oak_log", "birch_log", "spruce_log", "dark_oak_log", "jungle_log",
                "acacia_log", "mangrove_log", "cherry_log", "pale_oak_log", "oak_leaves",
                "birch_leaves", "spruce_leaves", "jungle_leaves", "mangrove_leaves",
                "cherry_leaves", "pale_oak_leaves")) {
            assertEquals(PoiBucket.UNCLASSIFIED, PoiLexicon.classify(MC, path, false, false), path);
            assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, path, false, true), path);
        }
    }

    @Test
    void strippedLogsAndBarkBlocksAreNeverLiftedByNaturalTag() {
        for (String path : List.of("stripped_oak_log", "stripped_dark_oak_log", "oak_wood",
                "stripped_oak_wood", "crimson_hyphae")) {
            assertEquals(PoiBucket.UNCLASSIFIED, PoiLexicon.classify(MC, path, false, true), path);
        }
    }

    @Test
    void naturalTagCannotHideStructureMarkers() {
        for (String path : List.of("spawner", "trial_spawner", "vault", "chest", "barrel",
                "sculk_shrieker", "reinforced_deepslate", "deepslate_tiles", "rail", "cobweb",
                "stone_bricks", "mossy_cobblestone", "copper_bulb", "tuff_bricks", "oak_planks",
                "white_bed", "torch", "cobblestone", "amethyst_block", "coal_block")) {
            assertEquals(PoiLexicon.classify(MC, path, false, false),
                    PoiLexicon.classify(MC, path, false, true), path);
        }
    }

    @Test
    void naturalTagOnlyEverChangesLogsAndLeaves() {
        for (String path : vanillaSamples()) {
            PoiBucket plain = PoiLexicon.classify(MC, path, false, false);
            PoiBucket tagged = PoiLexicon.classify(MC, path, false, true);
            if (plain != tagged) {
                assertTrue(path.endsWith("_log") || path.endsWith("_leaves"), path);
                assertEquals(PoiBucket.UNCLASSIFIED, plain, path);
                assertEquals(PoiBucket.NATURAL, tagged, path);
            }
        }
    }

    // ------------------------------------------------------------------ 6. modded namespaces

    @Test
    void moddedNonNaturalBlocksAreModded() {
        for (String ns : List.of("terralith", "explorify", "nova_structures", "create", "somemod")) {
            for (String path : List.of("oak_planks", "chest", "spawner", "cobblestone", "torch",
                    "stone_bricks", "amber_lamp", "weird_block", "sculk_shrieker", "rail")) {
                assertEquals(PoiBucket.MODDED, PoiLexicon.classify(ns, path, false, false),
                        ns + ":" + path);
                assertEquals(PoiBucket.MODDED, PoiLexicon.classify(ns, path, true, false),
                        ns + ":" + path + " (block entity)");
            }
        }
    }

    @Test
    void moddedCloneOfVanillaIdIsStillModded() {
        assertEquals(PoiBucket.MODDED, PoiLexicon.classify("fakemod", "oak_planks", false, false));
        assertEquals(PoiBucket.MODDED, PoiLexicon.classify("fakemod", "deepslate_bricks", false, false));
        assertEquals(PoiBucket.MODDED, PoiLexicon.classify("fakemod", "cobweb", false, false));
    }

    @Test
    void moddedNaturalAllowlistCoversTerrainMaterial() {
        for (String path : List.of("ruby_ore", "deepslate_ruby_ore", "raw_ruby_block", "raw_tin_block",
                "yosemite_stone", "volcanic_rock", "cave_sand", "desert_sandstone", "weird_dirt",
                "river_gravel", "white_clay", "glow_moss", "swamp_mud", "rich_soil", "arid_grass_block",
                "dark_deepslate", "warm_tuff", "cold_basalt", "glacial_ice", "frozen_snow",
                "stone", "dirt", "sand", "gravel", "clay", "rock")) {
            assertEquals(PoiBucket.NATURAL, PoiLexicon.classify("terralith", path, false, false),
                    "terralith:" + path);
        }
    }

    @Test
    void moddedAllowlistRejectsCraftedAndDecorativeVariants() {
        for (String path : List.of("polished_stone", "chiseled_stone", "carved_stone", "smooth_stone",
                "cut_sandstone", "cracked_stone", "stone_bricks", "stone_tiles", "roof_tile_stone",
                "brick_stone")) {
            assertEquals(PoiBucket.MODDED, PoiLexicon.classify("terralith", path, false, false),
                    "terralith:" + path);
        }
    }

    @Test
    void moddedNaturalTagMakesAnyModdedBlockNatural() {
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify("terralith", "weird_block", false, true));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify("somemod", "oak_planks", true, true));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify("somemod", "oak_log", false, true));
    }

    @Test
    void moddedLeavesAndLogsAreModdedWithoutTag() {
        assertEquals(PoiBucket.MODDED, PoiLexicon.classify("somemod", "cedar_log", false, false));
        assertEquals(PoiBucket.MODDED, PoiLexicon.classify("somemod", "cedar_leaves", false, false));
    }

    // ------------------------------------------------------------------ 7. robustness

    @Test
    void nullAndBlankPathsCarryNoEvidence() {
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(null, null, false, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, null, true, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "", true, true));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "   ", false, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify("somemod", "", false, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify("somemod", null, true, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "minecraft:", false, false));
    }

    @Test
    void nullAndBlankNamespaceMeanVanilla() {
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(null, "chest", false, false));
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify("", "chest", false, false));
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify("  ", "chest", false, false));
        assertEquals(PoiBucket.WOOD_BUILD, PoiLexicon.classify(null, "oak_planks", false, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(null, "stone", false, false));
    }

    @Test
    void idsAreCaseInsensitiveAndTrimmed() {
        assertEquals(PoiBucket.WOOD_BUILD, PoiLexicon.classify("MINECRAFT", "OAK_PLANKS", false, false));
        assertEquals(PoiBucket.WOOD_BUILD, PoiLexicon.classify("Minecraft", "Oak_Planks", false, false));
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(MC, "  chest\t", false, false));
        assertEquals(PoiBucket.SPAWNER, PoiLexicon.classify(" minecraft ", "Spawner", false, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "DIAMOND_ORE", false, false));
        assertEquals(PoiBucket.MODDED, PoiLexicon.classify("SomeMod", "Oak_Planks", false, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify("SomeMod", "Ruby_Ore", false, false));
    }

    @Test
    void embeddedNamespaceInPathWins() {
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(null, "minecraft:chest", false, false));
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify("terralith", "minecraft:chest", false, false));
        assertEquals(PoiBucket.MODDED, PoiLexicon.classify(MC, "terralith:oak_planks", false, false));
        assertEquals(PoiBucket.SPAWNER, PoiLexicon.classify(null, "Minecraft:Spawner", false, false));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(null, "minecraft:stone", false, false));
    }

    @Test
    void neverReturnsNullAndNeverThrowsOnGarbage() {
        SplittableRandom random = new SplittableRandom(20260926L);
        String alphabet = "abcdefghijklmnopqrstuvwxyz_:/ .-0123456789\té中";
        for (int i = 0; i < 4000; i++) {
            String ns = random.nextInt(4) == 0 ? null : randomString(random, alphabet, random.nextInt(12));
            String path = random.nextInt(8) == 0 ? null : randomString(random, alphabet, random.nextInt(24));
            PoiBucket bucket = PoiLexicon.classify(ns, path, random.nextBoolean(), random.nextBoolean());
            assertNotNull(bucket, ns + " / " + path);
            PoiLexicon.entityScore(ns, path);
            PoiLexicon.isWarden(ns, path);
            PoiLexicon.isHabitationItem(path);
            PoiLexicon.isSculkFamily(path);
            PoiLexicon.isWarnBlockForWarden(path);
        }
    }

    private static String randomString(SplittableRandom random, String alphabet, int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    @Test
    void classificationIsDeterministicAndOrderIndependent() {
        List<String> samples = vanillaSamples();
        List<PoiBucket> first = new ArrayList<>();
        for (String path : samples) {
            first.add(classify(path));
        }
        for (int i = samples.size() - 1; i >= 0; i--) {
            assertEquals(first.get(i), classify(samples.get(i)), samples.get(i));
        }
        for (int run = 0; run < 50; run++) {
            assertEquals(PoiBucket.RAIL, classify("rail"));
            assertEquals(PoiBucket.NATURAL, classify("stone"));
        }
    }

    // ------------------------------------------------------------------ 8. the 6.3 fixture palettes

    @Test
    void mineshaftPaletteBucketsAsThePlannedFixtureNeeds() {
        assertBucket(PoiBucket.WOOD_BUILD, "oak_planks", "oak_fence");
        assertBucket(PoiBucket.RAIL, "rail", "powered_rail");
        assertBucket(PoiBucket.WEB, "cobweb");
        assertBucket(PoiBucket.LIGHT_DRESSING, "torch", "wall_torch");
        assertBucket(PoiBucket.SPAWNER, "spawner");
        assertEquals(0.6D, PoiLexicon.entityScore(MC, "chest_minecart"));
    }

    @Test
    void dungeonPaletteBucketsAsThePlannedFixtureNeeds() {
        assertBucket(PoiBucket.STONE_BUILD, "mossy_cobblestone");
        assertBucket(PoiBucket.SPAWNER, "spawner");
        assertBucket(PoiBucket.CONTAINER, "chest");
        assertBucket(PoiBucket.COBBLE, "cobblestone");
    }

    @Test
    void ancientCityPaletteBucketsAsThePlannedFixtureNeeds() {
        assertBucket(PoiBucket.DEEPSLATE_BUILD, "deepslate_tiles", "deepslate_bricks");
        assertBucket(PoiBucket.LIGHT_DRESSING, "soul_lantern");
        assertBucket(PoiBucket.SCULK_STRUCT, "sculk_shrieker");
        assertBucket(PoiBucket.NATURAL, "sculk", "sculk_vein");
    }

    @Test
    void trialChamberPaletteBucketsAsThePlannedFixtureNeeds() {
        assertBucket(PoiBucket.COPPER_TUFF_BUILD, "tuff_bricks", "polished_tuff", "copper_bulb",
                "copper_grate", "chiseled_copper", "waxed_copper_bulb");
        assertBucket(PoiBucket.SPAWNER, "vault", "trial_spawner");
        assertBucket(PoiBucket.CONTAINER, "chest");
        assertBucket(PoiBucket.NATURAL, "tuff");
    }

    @Test
    void lushCavePaletteIsAllNatural() {
        assertBucket(PoiBucket.NATURAL,
                "spore_blossom", "big_dripleaf", "small_dripleaf", "moss_block", "moss_carpet",
                "azalea", "flowering_azalea", "rooted_dirt", "hanging_roots", "cave_vines",
                "glow_lichen", "clay", "dripstone_block", "pointed_dripstone");
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "oak_log", false, true));
        assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, "azalea_leaves", false, false));
    }

    @Test
    void amethystGeodePaletteIsFossilGeodeShellNatural() {
        assertBucket(PoiBucket.FOSSIL_GEODE, "amethyst_block", "budding_amethyst",
                "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster");
        assertBucket(PoiBucket.NATURAL, "calcite", "smooth_basalt");
    }

    @Test
    void playerBasePaletteBucketsAsThePlannedFixtureNeeds() {
        assertBucket(PoiBucket.FURNISHING, "red_bed", "crafting_table", "furnace");
        assertBucket(PoiBucket.CONTAINER, "chest");
        assertBucket(PoiBucket.WOOD_BUILD, "oak_planks");
        assertBucket(PoiBucket.LIGHT_DRESSING, "torch");
    }

    @Test
    void threeUnclassifiedCellsFixture() {
        assertBucket(PoiBucket.UNCLASSIFIED, "coal_block", "iron_block", "crying_obsidian");
    }

    @Test
    void botResidueFixturesAreWeakOnly() {
        assertTrue(classify("torch").isWeak());
        assertTrue(classify("wall_torch").isWeak());
        assertTrue(classify("cobblestone").isWeak());
        assertTrue(classify("lantern").isWeak());
        assertTrue(classify("candle").isWeak());
    }

    // ------------------------------------------------------------------ 9. bucket properties

    @Test
    void onlyLightDressingAndCobbleAreWeak() {
        for (PoiBucket bucket : PoiBucket.values()) {
            boolean expectedWeak = bucket == PoiBucket.LIGHT_DRESSING || bucket == PoiBucket.COBBLE;
            assertEquals(expectedWeak, bucket.isWeak(), bucket.name());
        }
        for (String path : vanillaSamples()) {
            PoiBucket bucket = classify(path);
            if (bucket.isWeak()) {
                assertTrue(bucket == PoiBucket.LIGHT_DRESSING || bucket == PoiBucket.COBBLE, path);
            }
        }
    }

    @Test
    void everyBucketIsReachableFromTheLexicon() {
        Set<PoiBucket> reached = EnumSet.noneOf(PoiBucket.class);
        for (String path : vanillaSamples()) {
            reached.add(classify(path));
        }
        reached.add(PoiLexicon.classify("somemod", "thing", false, false));
        assertEquals(EnumSet.allOf(PoiBucket.class), reached);
    }

    @Test
    void bucketTableMatchesDesign62() {
        assertTable(PoiBucket.SPAWNER, PoiBucket.Strength.STRONG, 2.0D, 2, 1);
        assertTable(PoiBucket.CONTAINER, PoiBucket.Strength.STRONG, 1.0D, 3, 2);
        assertTable(PoiBucket.SCULK_STRUCT, PoiBucket.Strength.STRONG, 2.5D, 3, 1);
        assertTable(PoiBucket.DEEPSLATE_BUILD, PoiBucket.Strength.STRONG, 1.0D, 6, 4);
        assertTable(PoiBucket.RAIL, PoiBucket.Strength.STRONG, 0.7D, 4, 2);
        assertTable(PoiBucket.WEB, PoiBucket.Strength.STRONG, 0.4D, 4, 1);
        assertTable(PoiBucket.STONE_BUILD, PoiBucket.Strength.STRONG, 0.5D, 8, 1);
        assertTable(PoiBucket.COPPER_TUFF_BUILD, PoiBucket.Strength.STRONG, 0.5D, 6, 1);
        assertTable(PoiBucket.MODDED, PoiBucket.Strength.STRONG, 0.4D, 4, 3);
        assertTable(PoiBucket.WOOD_BUILD, PoiBucket.Strength.NORMAL, 0.5D, 8, 1);
        assertTable(PoiBucket.FURNISHING, PoiBucket.Strength.NORMAL, 0.4D, 4, 1);
        assertTable(PoiBucket.FOSSIL_GEODE, PoiBucket.Strength.NORMAL, 0.25D, 4, 1);
        assertTable(PoiBucket.UNCLASSIFIED, PoiBucket.Strength.NORMAL, 0.25D, 4, 1);
        assertTable(PoiBucket.LIGHT_DRESSING, PoiBucket.Strength.WEAK, 0.5D, 3, 1);
        assertTable(PoiBucket.COBBLE, PoiBucket.Strength.WEAK, 0.1D, 10, 1);
        assertTable(PoiBucket.NATURAL, PoiBucket.Strength.NATURAL, 0.0D, 0, 1);
        assertTrue(PoiBucket.NATURAL.isNatural());
        assertFalse(PoiBucket.UNCLASSIFIED.isNatural());
    }

    private static void assertTable(PoiBucket bucket, PoiBucket.Strength strength, double weight,
            int cap, int strongMin) {
        assertEquals(strength, bucket.strength(), bucket.name());
        assertEquals(weight, bucket.weight(), 1e-12, bucket.name());
        assertEquals(cap, bucket.cap(), bucket.name());
        assertEquals(strongMin, bucket.strongMinCells(), bucket.name());
    }

    @Test
    void unclassifiedCapKeepsItBelowPossibleAlone() {
        double sumW = PoiBucket.UNCLASSIFIED.weight() * PoiBucket.UNCLASSIFIED.cap();
        double s = 1.0D - Math.exp(-sumW / 3.0D);
        assertEquals(1.0D, sumW, 1e-12);
        assertEquals(0.28D, s, 0.005D);
    }

    // ------------------------------------------------------------------ 10. habitation and warden helpers

    @Test
    void habitationItemsMatchDesign63() {
        for (String path : List.of("bed", "white_bed", "red_bed", "light_blue_bed", "crafting_table",
                "furnace", "brewing_stand", "enchanting_table", "bookshelf", "anvil", "item_frame",
                "armor_stand")) {
            assertTrue(PoiLexicon.isHabitationItem(path), path);
            assertTrue(PoiLexicon.isHabitationItem(MC, path), path);
        }
    }

    @Test
    void habitationItemsIncludeFamilyVariants() {
        for (String path : List.of("chipped_anvil", "damaged_anvil", "chiseled_bookshelf",
                "blast_furnace", "smoker", "glow_item_frame")) {
            assertTrue(PoiLexicon.isHabitationItem(path), path);
        }
    }

    @Test
    void structureMarkersAreNotHabitationItems() {
        for (String path : List.of("spawner", "rail", "cobweb", "chest", "barrel", "torch", "oak_planks",
                "stone_bricks", "sculk_shrieker", "hay_block", "white_wool", "white_carpet", "banner",
                "villager", "chest_minecart", "loom", "smithing_table", "", "bedrock", "bedroll")) {
            assertFalse(PoiLexicon.isHabitationItem(path), path);
        }
        assertFalse(PoiLexicon.isHabitationItem(null));
        assertFalse(PoiLexicon.isHabitationItem("terralith", "bed"));
        assertFalse(PoiLexicon.isHabitationItem("terralith:crafting_table"));
    }

    @Test
    void habitationHelperIsCaseInsensitiveAndAcceptsFullIds() {
        assertTrue(PoiLexicon.isHabitationItem("RED_BED"));
        assertTrue(PoiLexicon.isHabitationItem("minecraft:furnace"));
        assertTrue(PoiLexicon.isHabitationItem(null, " Anvil "));
    }

    @Test
    void sculkFamilyCoversPlainSculkAndEveryStructureBlock() {
        for (String path : List.of("sculk", "sculk_vein", "sculk_sensor", "calibrated_sculk_sensor",
                "sculk_shrieker", "sculk_catalyst")) {
            assertTrue(PoiLexicon.isSculkFamily(path), path);
            assertTrue(PoiLexicon.isSculkFamily(MC, path), path);
        }
        for (String path : List.of("reinforced_deepslate", "deepslate", "sculky", "stone",
                "soul_sand", "")) {
            assertFalse(PoiLexicon.isSculkFamily(path), path);
        }
        assertFalse(PoiLexicon.isSculkFamily(null));
        assertFalse(PoiLexicon.isSculkFamily("terralith", "sculk"));
    }

    @Test
    void warnBlocksForWarden() {
        for (String path : List.of("reinforced_deepslate", "sculk_shrieker", "sculk_catalyst",
                "sculk_sensor", "calibrated_sculk_sensor")) {
            assertTrue(PoiLexicon.isWarnBlockForWarden(path), path);
            assertTrue(PoiLexicon.isWarnBlockForWarden(MC, path), path);
        }
        for (String path : List.of("sculk", "sculk_vein", "deepslate", "deepslate_bricks", "spawner",
                "chest", "")) {
            assertFalse(PoiLexicon.isWarnBlockForWarden(path), path);
        }
        assertFalse(PoiLexicon.isWarnBlockForWarden(null));
        assertFalse(PoiLexicon.isWarnBlockForWarden("fakemod", "reinforced_deepslate"));
        assertFalse(PoiLexicon.isWarnBlockForWarden("fakemod:sculk_shrieker"));
    }

    @Test
    void singleTriggerAndSensorSplitMatchesTheMandatoryRule() {
        for (String path : List.of("reinforced_deepslate", "sculk_shrieker", "sculk_catalyst")) {
            assertTrue(PoiLexicon.isWardenSingleTrigger(path), path);
            assertFalse(PoiLexicon.isSculkSensor(path), path);
        }
        for (String path : List.of("sculk_sensor", "calibrated_sculk_sensor")) {
            assertFalse(PoiLexicon.isWardenSingleTrigger(path), path);
            assertTrue(PoiLexicon.isSculkSensor(path), path);
        }
        assertFalse(PoiLexicon.isWardenSingleTrigger("sculk"));
        assertFalse(PoiLexicon.isSculkSensor("sculk_vein"));
        assertFalse(PoiLexicon.isWardenSingleTrigger(null));
        assertFalse(PoiLexicon.isSculkSensor(null));
    }

    @Test
    void everyWarnBlockIsSculkStructBucketed() {
        for (String path : List.of("reinforced_deepslate", "sculk_shrieker", "sculk_catalyst",
                "sculk_sensor", "calibrated_sculk_sensor")) {
            assertEquals(PoiBucket.SCULK_STRUCT, classify(path), path);
        }
    }

    // ------------------------------------------------------------------ 11. entities

    @Test
    void entityScoresMatchDesign62() {
        assertEquals(0.6D, PoiLexicon.entityScore(MC, "chest_minecart"));
        for (String path : List.of("villager", "pillager", "vindicator", "evoker", "illusioner")) {
            assertEquals(0.3D, PoiLexicon.entityScore(MC, path), path);
        }
        for (String path : List.of("item_frame", "glow_item_frame", "armor_stand")) {
            assertEquals(0.15D, PoiLexicon.entityScore(MC, path), path);
        }
    }

    @Test
    void unlistedVanillaEntitiesScoreZero() {
        for (String path : List.of("zombie", "skeleton", "creeper", "spider", "cave_spider", "bat",
                "warden", "hopper_minecart", "minecart", "tnt_minecart", "zombie_villager",
                "wandering_trader", "iron_golem", "item", "experience_orb", "player", "")) {
            assertEquals(0.0D, PoiLexicon.entityScore(MC, path), path);
        }
    }

    @Test
    void moddedEntityNamespaceScoresFlatPointFour() {
        assertEquals(0.4D, PoiLexicon.entityScore("terralith", "custom_mob"));
        assertEquals(0.4D, PoiLexicon.entityScore("somemod", "villager"));
        assertEquals(0.4D, PoiLexicon.entityScore("somemod", "chest_minecart"));
        assertEquals(0.4D, PoiLexicon.entityScore("SomeMod", "Thing"));
        assertEquals(0.4D, PoiLexicon.entityScore(null, "somemod:thing"));
    }

    @Test
    void entityScoreIsNullAndCaseSafe() {
        assertEquals(0.0D, PoiLexicon.entityScore(null, null));
        assertEquals(0.0D, PoiLexicon.entityScore(MC, null));
        assertEquals(0.0D, PoiLexicon.entityScore("somemod", null));
        assertEquals(0.0D, PoiLexicon.entityScore("somemod", ""));
        assertEquals(0.6D, PoiLexicon.entityScore("MINECRAFT", "CHEST_MINECART"));
        assertEquals(0.3D, PoiLexicon.entityScore(null, "minecraft:villager"));
        assertEquals(0.15D, PoiLexicon.entityScore("", " Armor_Stand "));
    }

    @Test
    void groupCapIsExposedButNotAppliedPerEntity() {
        assertEquals(0.6D, PoiLexicon.ENTITY_GROUP_CAP);
        assertEquals(0.6D, PoiLexicon.entityScore(MC, "chest_minecart"));
        // Two villagers would exceed the cap only after summing; one call never does.
        assertTrue(PoiLexicon.entityScore(MC, "villager") * 3 > PoiLexicon.ENTITY_GROUP_CAP);
        for (String path : List.of("chest_minecart", "villager", "item_frame")) {
            assertTrue(PoiLexicon.entityScore(MC, path) <= PoiLexicon.ENTITY_GROUP_CAP, path);
        }
    }

    @Test
    void wardenIsRecognizedOnlyInTheVanillaNamespace() {
        assertTrue(PoiLexicon.isWarden(MC, "warden"));
        assertTrue(PoiLexicon.isWarden(null, "warden"));
        assertTrue(PoiLexicon.isWarden("", "WARDEN"));
        assertTrue(PoiLexicon.isWarden(null, "minecraft:warden"));
        assertFalse(PoiLexicon.isWarden("somemod", "warden"));
        assertFalse(PoiLexicon.isWarden(MC, "zombie"));
        assertFalse(PoiLexicon.isWarden(MC, "wardens"));
        assertFalse(PoiLexicon.isWarden(MC, null));
        assertFalse(PoiLexicon.isWarden(null, null));
        assertFalse(PoiLexicon.isWarden(MC, ""));
    }

    // ------------------------------------------------------------------ 12. registry snapshot

    /**
     * Snapshot of the whole 1.21.5 block registry (ids from the client blockstate list, every one of
     * them classified with {@code hasBlockEntity = false}). It was reviewed bucket by bucket against
     * design 6.2, so a diff here is a deliberate vocabulary change, not noise.
     */
    @Test
    void everyVanilla1215BlockHasItsPinnedBucket() {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<PoiBucket, String> entry : goldenTable().entrySet()) {
            for (String id : ids(entry.getValue())) {
                assertTrue(seen.add(id), "listed twice: " + id);
                assertEquals(entry.getKey(), classify(id), id);
            }
        }
        assertEquals(1104, seen.size());
    }

    @Test
    void vanillaNamespaceNeverYieldsModdedAndModdedNamespaceOnlyNaturalOrModded() {
        for (String id : allVanillaIds()) {
            for (int flags = 0; flags < 4; flags++) {
                boolean blockEntity = (flags & 1) != 0;
                boolean naturalTag = (flags & 2) != 0;
                PoiBucket vanilla = PoiLexicon.classify(MC, id, blockEntity, naturalTag);
                assertTrue(vanilla != PoiBucket.MODDED, id);
                PoiBucket modded = PoiLexicon.classify("fakemod", id, blockEntity, naturalTag);
                assertTrue(modded == PoiBucket.NATURAL || modded == PoiBucket.MODDED, id);
                if (naturalTag) {
                    assertEquals(PoiBucket.NATURAL, modded, id);
                }
            }
        }
    }

    @Test
    void helperPredicatesAgreeWithBucketsOnEveryVanillaId() {
        for (String id : allVanillaIds()) {
            PoiBucket bucket = classify(id);
            if (PoiLexicon.isHabitationItem(id)) {
                assertEquals(PoiBucket.FURNISHING, bucket, id);
            }
            if (PoiLexicon.isWarnBlockForWarden(id)) {
                assertEquals(PoiBucket.SCULK_STRUCT, bucket, id);
            }
            if (PoiLexicon.isWardenSingleTrigger(id) || PoiLexicon.isSculkSensor(id)) {
                assertTrue(PoiLexicon.isWarnBlockForWarden(id), id);
            }
            if (PoiLexicon.isSculkFamily(id)) {
                assertTrue(bucket == PoiBucket.NATURAL || bucket == PoiBucket.SCULK_STRUCT, id);
            }
            if (PoiLexicon.isVault(MC, id)) {
                assertEquals(PoiBucket.SPAWNER, bucket, id);
            }
            if (PoiLexicon.isIronBars(MC, id) || PoiLexicon.isStoneBricks(MC, id)
                    || PoiLexicon.isMossyStone(MC, id) || PoiLexicon.isNetherBricks(MC, id)) {
                assertEquals(PoiBucket.STONE_BUILD, bucket, id);
            }
            if (PoiLexicon.isBlackstoneFamily(MC, id)) {
                assertTrue(bucket == PoiBucket.NATURAL || bucket == PoiBucket.STONE_BUILD
                        || bucket == PoiBucket.UNCLASSIFIED, id);
            }
        }
    }

    @Test
    void classificationIsThreadSafeAndStable() {
        List<String> ids = allVanillaIds();
        Map<String, PoiBucket> expected = new LinkedHashMap<>();
        for (String id : ids) {
            expected.put(id, classify(id));
        }
        long mismatches = IntStream.range(0, 8 * ids.size()).parallel()
                .filter(i -> classify(ids.get(i % ids.size())) != expected.get(ids.get(i % ids.size())))
                .count();
        assertEquals(0L, mismatches);
    }

    // ------------------------------------------------------------------ 13. id families

    private static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
            "red", "black");
    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia",
            "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped");
    private static final List<String> TREES = List.of("oak", "spruce", "birch", "jungle", "acacia",
            "dark_oak", "mangrove", "cherry", "pale_oak");

    @Test
    void everyDyeColourFollowsItsFamily() {
        for (String dye : DYES) {
            assertBucket(PoiBucket.FURNISHING, dye + "_bed", dye + "_wool", dye + "_carpet",
                    dye + "_banner", dye + "_wall_banner");
            assertBucket(PoiBucket.CONTAINER, dye + "_shulker_box");
            assertBucket(PoiBucket.STONE_BUILD, dye + "_stained_glass", dye + "_stained_glass_pane");
            assertBucket(PoiBucket.LIGHT_DRESSING, dye + "_candle");
            assertBucket(PoiBucket.NATURAL, dye + "_terracotta");
            assertBucket(PoiBucket.UNCLASSIFIED, dye + "_candle_cake", dye + "_concrete",
                    dye + "_concrete_powder", dye + "_glazed_terracotta");
            assertTrue(PoiLexicon.isHabitationItem(dye + "_bed"), dye);
            assertEquals("bed", PoiLexicon.habitationKey(MC, dye + "_bed"), dye);
        }
    }

    @Test
    void everyWoodTypeBuildsTheSameWayAndItsRawMaterialDoesNot() {
        for (String wood : WOODS) {
            assertBucket(PoiBucket.WOOD_BUILD, wood + "_planks", wood + "_fence", wood + "_fence_gate",
                    wood + "_door", wood + "_trapdoor", wood + "_stairs", wood + "_slab", wood + "_sign",
                    wood + "_wall_sign", wood + "_hanging_sign", wood + "_wall_hanging_sign");
            assertBucket(PoiBucket.UNCLASSIFIED, wood + "_button", wood + "_pressure_plate");
        }
        for (String tree : TREES) {
            assertBucket(PoiBucket.UNCLASSIFIED, tree + "_log", tree + "_leaves", tree + "_wood",
                    "stripped_" + tree + "_log", "stripped_" + tree + "_wood", tree + "_sapling");
            assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, tree + "_log", false, true), tree);
            assertEquals(PoiBucket.NATURAL, PoiLexicon.classify(MC, tree + "_leaves", false, true), tree);
            assertEquals(PoiBucket.UNCLASSIFIED,
                    PoiLexicon.classify(MC, "stripped_" + tree + "_log", false, true), tree);
        }
    }

    @Test
    void everyCopperStageAndWaxingIsCopperTuffBuildButCopperOreIsNot() {
        for (String waxed : List.of("", "waxed_")) {
            for (String stage : List.of("", "exposed_", "weathered_", "oxidized_")) {
                for (String shape : List.of("copper_bulb", "copper_grate", "copper_door",
                        "copper_trapdoor", "chiseled_copper", "cut_copper", "cut_copper_stairs",
                        "cut_copper_slab")) {
                    String id = waxed + stage + shape;
                    assertEquals(PoiBucket.COPPER_TUFF_BUILD, classify(id), id);
                }
            }
        }
        assertBucket(PoiBucket.COPPER_TUFF_BUILD, "copper_block", "exposed_copper", "weathered_copper",
                "oxidized_copper", "waxed_copper_block", "waxed_oxidized_copper");
        assertBucket(PoiBucket.NATURAL, "copper_ore", "deepslate_copper_ore", "raw_copper_block");
    }

    @Test
    void realBlockEntityBlocksKeepTheirNamedBucket() {
        for (String path : List.of("oak_sign", "oak_wall_sign", "oak_hanging_sign",
                "warped_wall_hanging_sign", "bamboo_sign")) {
            assertEquals(PoiBucket.WOOD_BUILD, PoiLexicon.classify(MC, path, true, false), path);
        }
        for (String dye : DYES) {
            assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, dye + "_banner", true, false));
            assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, dye + "_wall_banner", true, false));
            assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, dye + "_bed", true, false));
            assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(MC, dye + "_shulker_box", true, false));
        }
        assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, "smoker", true, false));
        assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, "blast_furnace", true, false));
        assertEquals(PoiBucket.FURNISHING, PoiLexicon.classify(MC, "brewing_stand", true, false));
        assertEquals(PoiBucket.SCULK_STRUCT, PoiLexicon.classify(MC, "sculk_sensor", true, false));
        assertEquals(PoiBucket.SCULK_STRUCT, PoiLexicon.classify(MC, "sculk_catalyst", true, false));
        assertEquals(PoiBucket.SPAWNER, PoiLexicon.classify(MC, "trial_spawner", true, false));
        assertEquals(PoiBucket.SPAWNER, PoiLexicon.classify(MC, "vault", true, false));
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(MC, "ender_chest", true, false));
        assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(MC, "decorated_pot", true, false));
    }

    @Test
    void remainingBlockEntitiesFallBackToContainerOnlyWhenFlagged() {
        for (String path : List.of("moving_piston", "end_gateway", "structure_block", "jigsaw",
                "repeating_command_block", "chain_command_block", "creaking_heart", "zombie_head",
                "creeper_head", "dragon_head", "piglin_head", "player_wall_head", "test_block",
                "test_instance_block")) {
            assertEquals(PoiBucket.CONTAINER, PoiLexicon.classify(MC, path, true, false), path);
            assertEquals(PoiBucket.UNCLASSIFIED, PoiLexicon.classify(MC, path, false, false), path);
        }
    }

    @Test
    void pottedPlantsAreDecorationEvenWhenThePlantIsARoot() {
        assertBucket(PoiBucket.UNCLASSIFIED, "potted_crimson_roots", "potted_warped_roots",
                "potted_poppy", "potted_fern");
        assertBucket(PoiBucket.NATURAL, "crimson_roots", "warped_roots", "hanging_roots",
                "mangrove_roots");
    }

    @Test
    void oceanFloorGrowthIsNatural() {
        assertBucket(PoiBucket.NATURAL, "sea_pickle", "turtle_egg", "kelp", "seagrass", "tube_coral");
    }

    // ------------------------------------------------------------------ 14. habitation key and label markers

    @Test
    void habitationKeyMapsEveryVariantToTheNineDesignNames() {
        assertEquals("bed", PoiLexicon.habitationKey(MC, "bed"));
        assertEquals("bed", PoiLexicon.habitationKey(MC, "red_bed"));
        assertEquals("crafting_table", PoiLexicon.habitationKey(MC, "crafting_table"));
        assertEquals("furnace", PoiLexicon.habitationKey(MC, "furnace"));
        assertEquals("furnace", PoiLexicon.habitationKey(MC, "blast_furnace"));
        assertEquals("furnace", PoiLexicon.habitationKey(MC, "smoker"));
        assertEquals("brewing_stand", PoiLexicon.habitationKey(MC, "brewing_stand"));
        assertEquals("enchanting_table", PoiLexicon.habitationKey(MC, "enchanting_table"));
        assertEquals("bookshelf", PoiLexicon.habitationKey(MC, "bookshelf"));
        assertEquals("bookshelf", PoiLexicon.habitationKey(MC, "chiseled_bookshelf"));
        assertEquals("anvil", PoiLexicon.habitationKey(MC, "anvil"));
        assertEquals("anvil", PoiLexicon.habitationKey(MC, "chipped_anvil"));
        assertEquals("anvil", PoiLexicon.habitationKey(MC, "damaged_anvil"));
        assertEquals("item_frame", PoiLexicon.habitationKey(MC, "item_frame"));
        assertEquals("item_frame", PoiLexicon.habitationKey(MC, "glow_item_frame"));
        assertEquals("armor_stand", PoiLexicon.habitationKey(MC, "armor_stand"));
    }

    @Test
    void habitationKeysAreExactlyTheDesignNine() {
        Set<String> keys = new HashSet<>();
        for (String path : List.of("bed", "white_bed", "crafting_table", "furnace", "blast_furnace",
                "smoker", "brewing_stand", "enchanting_table", "bookshelf", "chiseled_bookshelf",
                "anvil", "chipped_anvil", "damaged_anvil", "item_frame", "glow_item_frame",
                "armor_stand")) {
            String key = PoiLexicon.habitationKey(null, path);
            assertNotNull(key, path);
            assertEquals(key, key.toLowerCase(Locale.ROOT));
            keys.add(key);
        }
        assertEquals(Set.of("bed", "crafting_table", "furnace", "brewing_stand", "enchanting_table",
                "bookshelf", "anvil", "item_frame", "armor_stand"), keys);
    }

    @Test
    void habitationKeyIsNullForEverythingElse() {
        for (String path : List.of("spawner", "hay_block", "white_wool", "chest", "bedrock", "bedroll",
                "loom", "smithing_table", "villager", "", "   ", "_bed_", "bed_", "minecraft:")) {
            assertNull(PoiLexicon.habitationKey(MC, path), path);
            assertFalse(PoiLexicon.isHabitationItem(MC, path), path);
        }
        assertNull(PoiLexicon.habitationKey(MC, null));
        assertNull(PoiLexicon.habitationKey(null, null));
        assertNull(PoiLexicon.habitationKey("somemod", "bed"));
        assertNull(PoiLexicon.habitationKey(null, "somemod:crafting_table"));
        assertEquals("bed", PoiLexicon.habitationKey(" Minecraft ", "  RED_BED "));
    }

    @Test
    void isHabitationItemAndHabitationKeyNeverDisagree() {
        for (String id : allVanillaIds()) {
            assertEquals(PoiLexicon.habitationKey(MC, id) != null, PoiLexicon.isHabitationItem(MC, id), id);
        }
        for (String path : List.of("item_frame", "glow_item_frame", "armor_stand")) {
            assertEquals(0.15D, PoiLexicon.entityScore(MC, path), path);
            assertTrue(PoiLexicon.isHabitationItem(path), path);
        }
    }

    @Test
    void vaultAndIronBarsMarkers() {
        assertTrue(PoiLexicon.isVault(MC, "vault"));
        assertFalse(PoiLexicon.isVault(MC, "trial_spawner"));
        assertFalse(PoiLexicon.isVault(MC, "spawner"));
        assertFalse(PoiLexicon.isVault("somemod", "vault"));
        assertTrue(PoiLexicon.isIronBars(MC, "iron_bars"));
        assertTrue(PoiLexicon.isIronBars(null, "Minecraft:Iron_Bars"));
        assertFalse(PoiLexicon.isIronBars(MC, "chain"));
        assertFalse(PoiLexicon.isIronBars(MC, "iron_block"));
        assertFalse(PoiLexicon.isIronBars("somemod", "iron_bars"));
    }

    @Test
    void mossyStoneMarkerIsTheCobblestoneFamilyOnly() {
        for (String path : List.of("mossy_cobblestone", "mossy_cobblestone_stairs",
                "mossy_cobblestone_slab", "mossy_cobblestone_wall")) {
            assertTrue(PoiLexicon.isMossyStone(MC, path), path);
        }
        for (String path : List.of("mossy_stone_bricks", "mossy_stone_brick_stairs", "cobblestone",
                "moss_block", "moss_carpet", "infested_mossy_stone_bricks", "stone", "")) {
            assertFalse(PoiLexicon.isMossyStone(MC, path), path);
        }
        assertFalse(PoiLexicon.isMossyStone("somemod", "mossy_cobblestone"));
        assertFalse(PoiLexicon.isMossyStone(MC, null));
    }

    @Test
    void stoneBricksMarkerIsTheStoneBrickFamilyIncludingInfested() {
        for (String path : List.of("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
                "chiseled_stone_bricks", "stone_brick_stairs", "stone_brick_slab", "stone_brick_wall",
                "mossy_stone_brick_stairs", "mossy_stone_brick_slab", "mossy_stone_brick_wall",
                "infested_stone_bricks", "infested_mossy_stone_bricks", "infested_cracked_stone_bricks",
                "infested_chiseled_stone_bricks")) {
            assertTrue(PoiLexicon.isStoneBricks(MC, path), path);
        }
        for (String path : List.of("deepslate_bricks", "nether_bricks", "red_nether_bricks",
                "tuff_bricks", "mud_bricks", "end_stone_bricks", "prismarine_bricks",
                "polished_blackstone_bricks", "bricks", "quartz_bricks", "resin_bricks", "stone",
                "cobblestone", "mossy_cobblestone", "stone_stairs", "infested_stone",
                "infested_cobblestone", "smooth_stone", "")) {
            assertFalse(PoiLexicon.isStoneBricks(MC, path), path);
        }
        assertFalse(PoiLexicon.isStoneBricks("somemod", "stone_bricks"));
        assertFalse(PoiLexicon.isStoneBricks(null, null));
    }

    @Test
    void netherBricksMarkerCoversEveryNetherBrickBlock() {
        for (String path : List.of("nether_bricks", "red_nether_bricks", "cracked_nether_bricks",
                "chiseled_nether_bricks", "nether_brick_fence", "nether_brick_stairs",
                "nether_brick_slab", "nether_brick_wall", "red_nether_brick_stairs",
                "red_nether_brick_slab", "red_nether_brick_wall")) {
            assertTrue(PoiLexicon.isNetherBricks(MC, path), path);
        }
        for (String path : List.of("netherrack", "nether_wart_block", "nether_wart", "nether_gold_ore",
                "nether_portal", "bricks", "stone_bricks", "polished_blackstone_bricks", "")) {
            assertFalse(PoiLexicon.isNetherBricks(MC, path), path);
        }
        assertFalse(PoiLexicon.isNetherBricks("somemod", "nether_bricks"));
    }

    @Test
    void blackstoneMarkerCoversNaturalAndBuiltBlackstone() {
        for (String path : List.of("blackstone", "gilded_blackstone", "polished_blackstone",
                "polished_blackstone_bricks", "cracked_polished_blackstone_bricks",
                "chiseled_polished_blackstone", "blackstone_stairs", "blackstone_slab",
                "blackstone_wall", "polished_blackstone_brick_wall", "polished_blackstone_button")) {
            assertTrue(PoiLexicon.isBlackstoneFamily(MC, path), path);
        }
        for (String path : List.of("stone", "deepslate", "basalt", "netherrack", "obsidian",
                "crying_obsidian", "nether_bricks", "")) {
            assertFalse(PoiLexicon.isBlackstoneFamily(MC, path), path);
        }
        assertFalse(PoiLexicon.isBlackstoneFamily("somemod", "blackstone"));
        assertFalse(PoiLexicon.isBlackstoneFamily(null, null));
    }

    @Test
    void labelMarkersAreCaseInsensitiveAndHonourEmbeddedNamespaces() {
        assertTrue(PoiLexicon.isStoneBricks(null, "MINECRAFT:STONE_BRICKS"));
        assertTrue(PoiLexicon.isNetherBricks(" minecraft ", " Nether_Bricks "));
        assertTrue(PoiLexicon.isBlackstoneFamily("", "Blackstone"));
        assertTrue(PoiLexicon.isMossyStone(null, "minecraft:mossy_cobblestone"));
        assertTrue(PoiLexicon.isVault("MINECRAFT", "VAULT"));
        assertFalse(PoiLexicon.isStoneBricks("minecraft", "terralith:stone_bricks"));
        assertFalse(PoiLexicon.isVault("minecraft", "somemod:vault"));
    }

    @Test
    void wardenBlockHelpersRejectModdedClonesOfEveryTrigger() {
        for (String path : List.of("reinforced_deepslate", "sculk_shrieker", "sculk_catalyst",
                "sculk_sensor", "calibrated_sculk_sensor")) {
            assertFalse(PoiLexicon.isWardenSingleTrigger("fakemod", path), path);
            assertFalse(PoiLexicon.isSculkSensor("fakemod", path), path);
            assertFalse(PoiLexicon.isWarnBlockForWarden("fakemod", path), path);
            assertFalse(PoiLexicon.isSculkFamily("fakemod", path), path);
            assertTrue(PoiLexicon.isWardenSingleTrigger(MC, path)
                    || PoiLexicon.isSculkSensor(MC, path), path);
        }
    }

    // ------------------------------------------------------------------ 15. fuzz on the whole surface

    @Test
    void entityScoreOnlyEverTakesTheDocumentedValues() {
        Set<Double> allowed = Set.of(0.0D, 0.15D, 0.3D, 0.4D, 0.6D);
        SplittableRandom random = new SplittableRandom(1215L);
        String alphabet = "abcdefghijklmnopqrstuvwxyz_:";
        for (int i = 0; i < 3000; i++) {
            String ns = random.nextInt(3) == 0 ? null : randomString(random, alphabet, random.nextInt(8));
            String path = random.nextInt(6) == 0 ? null : randomString(random, alphabet, random.nextInt(16));
            assertTrue(allowed.contains(PoiLexicon.entityScore(ns, path)), ns + " / " + path);
        }
        for (String path : List.of("chest_minecart", "villager", "pillager", "vindicator", "evoker",
                "illusioner", "item_frame", "glow_item_frame", "armor_stand", "warden", "creeper")) {
            assertTrue(allowed.contains(PoiLexicon.entityScore(MC, path)), path);
        }
    }

    @Test
    void everyNewHelperIsNullSafeAndNeverThrows() {
        SplittableRandom random = new SplittableRandom(99L);
        String alphabet = "abcdefghijklmnopqrstuvwxyz_:/ .-0123456789\té";
        for (int i = 0; i < 3000; i++) {
            String ns = random.nextInt(4) == 0 ? null : randomString(random, alphabet, random.nextInt(10));
            String path = random.nextInt(8) == 0 ? null : randomString(random, alphabet, random.nextInt(20));
            PoiLexicon.habitationKey(ns, path);
            PoiLexicon.isHabitationItem(ns, path);
            PoiLexicon.isVault(ns, path);
            PoiLexicon.isIronBars(ns, path);
            PoiLexicon.isMossyStone(ns, path);
            PoiLexicon.isStoneBricks(ns, path);
            PoiLexicon.isNetherBricks(ns, path);
            PoiLexicon.isBlackstoneFamily(ns, path);
            PoiLexicon.isSculkFamily(ns, path);
            PoiLexicon.isWarnBlockForWarden(ns, path);
            PoiLexicon.isWardenSingleTrigger(ns, path);
            PoiLexicon.isSculkSensor(ns, path);
            PoiLexicon.isWarden(ns, path);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<PoiBucket, String> goldenTable() {
        Map<PoiBucket, String> table = new LinkedHashMap<>();
        table.put(PoiBucket.SPAWNER, GOLDEN_SPAWNER);
        table.put(PoiBucket.CONTAINER, GOLDEN_CONTAINER);
        table.put(PoiBucket.SCULK_STRUCT, GOLDEN_SCULK_STRUCT);
        table.put(PoiBucket.DEEPSLATE_BUILD, GOLDEN_DEEPSLATE_BUILD);
        table.put(PoiBucket.RAIL, GOLDEN_RAIL);
        table.put(PoiBucket.WEB, GOLDEN_WEB);
        table.put(PoiBucket.STONE_BUILD, GOLDEN_STONE_BUILD);
        table.put(PoiBucket.COPPER_TUFF_BUILD, GOLDEN_COPPER_TUFF_BUILD);
        table.put(PoiBucket.WOOD_BUILD, GOLDEN_WOOD_BUILD);
        table.put(PoiBucket.FURNISHING, GOLDEN_FURNISHING);
        table.put(PoiBucket.FOSSIL_GEODE, GOLDEN_FOSSIL_GEODE);
        table.put(PoiBucket.UNCLASSIFIED, GOLDEN_UNCLASSIFIED);
        table.put(PoiBucket.LIGHT_DRESSING, GOLDEN_LIGHT_DRESSING);
        table.put(PoiBucket.COBBLE, GOLDEN_COBBLE);
        table.put(PoiBucket.NATURAL, GOLDEN_NATURAL);
        return table;
    }

    private static String[] ids(String golden) {
        return golden.trim().split("\\s+");
    }

    private static List<String> allVanillaIds() {
        List<String> all = new ArrayList<>();
        for (String golden : goldenTable().values()) {
            all.addAll(List.of(ids(golden)));
        }
        return all;
    }

    /** A wide vanilla sample that touches every bucket. */
    private static List<String> vanillaSamples() {
        return List.of(
                "stone", "granite", "deepslate", "tuff", "dirt", "sand", "gravel", "clay", "coal_ore",
                "deepslate_diamond_ore", "raw_iron_block", "moss_block", "azalea", "sculk",
                "sculk_vein", "water", "lava", "air", "netherrack", "obsidian", "calcite",
                "spawner", "trial_spawner", "vault", "chest", "trapped_chest", "barrel", "ender_chest",
                "decorated_pot", "white_shulker_box", "dispenser", "hopper", "lectern", "jukebox",
                "sculk_shrieker", "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst",
                "reinforced_deepslate", "deepslate_tiles", "deepslate_bricks", "polished_deepslate",
                "chiseled_deepslate", "cracked_deepslate_bricks", "rail", "powered_rail",
                "detector_rail", "activator_rail", "cobweb", "stone_bricks", "mossy_stone_bricks",
                "mossy_cobblestone", "iron_bars", "glass", "nether_bricks", "polished_blackstone_bricks",
                "chiseled_stone_bricks", "polished_andesite", "cut_sandstone", "copper_bulb",
                "copper_grate", "chiseled_copper", "cut_copper", "waxed_cut_copper", "tuff_bricks",
                "polished_tuff", "oak_planks", "spruce_fence", "birch_fence_gate", "jungle_door",
                "acacia_trapdoor", "dark_oak_stairs", "mangrove_slab", "cherry_sign", "ladder",
                "scaffolding", "bookshelf", "enchanting_table", "brewing_stand", "furnace",
                "crafting_table", "white_bed", "red_banner", "white_wool", "red_carpet", "hay_block",
                "anvil", "bone_block", "amethyst_block", "budding_amethyst", "small_amethyst_bud",
                "lantern", "soul_lantern", "candle", "torch", "wall_torch", "campfire", "cobblestone",
                "coal_block", "crying_obsidian", "tnt", "lever", "end_portal_frame", "iron_door",
                "oak_log", "oak_leaves", "birch_log", "spruce_leaves", "stripped_oak_log", "oak_wood",
                "comparator", "beacon", "bee_nest", "suspicious_sand");
    }

    // ---- registry snapshot (see everyVanilla1215BlockHasItsPinnedBucket) ----

    private static final String GOLDEN_SPAWNER = """
            spawner trial_spawner vault
            """;

    private static final String GOLDEN_CONTAINER = """
            barrel black_shulker_box blue_shulker_box brown_shulker_box chest cyan_shulker_box
            decorated_pot ender_chest gray_shulker_box green_shulker_box light_blue_shulker_box
            light_gray_shulker_box lime_shulker_box magenta_shulker_box orange_shulker_box
            pink_shulker_box purple_shulker_box red_shulker_box shulker_box trapped_chest
            white_shulker_box yellow_shulker_box
            """;

    private static final String GOLDEN_SCULK_STRUCT = """
            calibrated_sculk_sensor reinforced_deepslate sculk_catalyst sculk_sensor sculk_shrieker
            """;

    private static final String GOLDEN_DEEPSLATE_BUILD = """
            chiseled_deepslate cracked_deepslate_bricks cracked_deepslate_tiles
            deepslate_brick_slab deepslate_brick_stairs deepslate_brick_wall deepslate_bricks
            deepslate_tile_slab deepslate_tile_stairs deepslate_tile_wall deepslate_tiles
            polished_deepslate polished_deepslate_slab polished_deepslate_stairs
            polished_deepslate_wall
            """;

    private static final String GOLDEN_RAIL = """
            activator_rail detector_rail powered_rail rail
            """;

    private static final String GOLDEN_WEB = """
            cobweb
            """;

    private static final String GOLDEN_STONE_BUILD = """
            black_stained_glass black_stained_glass_pane blue_stained_glass blue_stained_glass_pane
            brick_slab brick_stairs brick_wall bricks brown_stained_glass brown_stained_glass_pane
            chiseled_nether_bricks chiseled_polished_blackstone chiseled_quartz_block
            chiseled_red_sandstone chiseled_resin_bricks chiseled_sandstone chiseled_stone_bricks
            cracked_nether_bricks cracked_polished_blackstone_bricks cracked_stone_bricks
            cut_red_sandstone cut_red_sandstone_slab cut_sandstone cut_sandstone_slab
            cyan_stained_glass cyan_stained_glass_pane end_stone_brick_slab end_stone_brick_stairs
            end_stone_brick_wall end_stone_bricks glass glass_pane gray_stained_glass
            gray_stained_glass_pane green_stained_glass green_stained_glass_pane
            infested_chiseled_stone_bricks infested_cracked_stone_bricks
            infested_mossy_stone_bricks infested_stone_bricks iron_bars light_blue_stained_glass
            light_blue_stained_glass_pane light_gray_stained_glass light_gray_stained_glass_pane
            lime_stained_glass lime_stained_glass_pane magenta_stained_glass
            magenta_stained_glass_pane mossy_cobblestone mossy_cobblestone_slab
            mossy_cobblestone_stairs mossy_cobblestone_wall mossy_stone_brick_slab
            mossy_stone_brick_stairs mossy_stone_brick_wall mossy_stone_bricks mud_brick_slab
            mud_brick_stairs mud_brick_wall mud_bricks nether_brick_fence nether_brick_slab
            nether_brick_stairs nether_brick_wall nether_bricks orange_stained_glass
            orange_stained_glass_pane pink_stained_glass pink_stained_glass_pane polished_andesite
            polished_andesite_slab polished_andesite_stairs polished_basalt polished_blackstone
            polished_blackstone_brick_slab polished_blackstone_brick_stairs
            polished_blackstone_brick_wall polished_blackstone_bricks polished_blackstone_button
            polished_blackstone_pressure_plate polished_blackstone_slab polished_blackstone_stairs
            polished_blackstone_wall polished_diorite polished_diorite_slab polished_diorite_stairs
            polished_granite polished_granite_slab polished_granite_stairs prismarine_brick_slab
            prismarine_brick_stairs prismarine_bricks purple_stained_glass
            purple_stained_glass_pane quartz_bricks red_nether_brick_slab red_nether_brick_stairs
            red_nether_brick_wall red_nether_bricks red_stained_glass red_stained_glass_pane
            resin_brick_slab resin_brick_stairs resin_brick_wall resin_bricks stone_brick_slab
            stone_brick_stairs stone_brick_wall stone_bricks tinted_glass white_stained_glass
            white_stained_glass_pane yellow_stained_glass yellow_stained_glass_pane
            """;

    private static final String GOLDEN_COPPER_TUFF_BUILD = """
            chiseled_copper chiseled_tuff chiseled_tuff_bricks copper_block copper_bulb copper_door
            copper_grate copper_trapdoor cut_copper cut_copper_slab cut_copper_stairs
            exposed_chiseled_copper exposed_copper exposed_copper_bulb exposed_copper_door
            exposed_copper_grate exposed_copper_trapdoor exposed_cut_copper exposed_cut_copper_slab
            exposed_cut_copper_stairs oxidized_chiseled_copper oxidized_copper oxidized_copper_bulb
            oxidized_copper_door oxidized_copper_grate oxidized_copper_trapdoor oxidized_cut_copper
            oxidized_cut_copper_slab oxidized_cut_copper_stairs polished_tuff polished_tuff_slab
            polished_tuff_stairs polished_tuff_wall tuff_brick_slab tuff_brick_stairs
            tuff_brick_wall tuff_bricks tuff_slab tuff_stairs tuff_wall waxed_chiseled_copper
            waxed_copper_block waxed_copper_bulb waxed_copper_door waxed_copper_grate
            waxed_copper_trapdoor waxed_cut_copper waxed_cut_copper_slab waxed_cut_copper_stairs
            waxed_exposed_chiseled_copper waxed_exposed_copper waxed_exposed_copper_bulb
            waxed_exposed_copper_door waxed_exposed_copper_grate waxed_exposed_copper_trapdoor
            waxed_exposed_cut_copper waxed_exposed_cut_copper_slab waxed_exposed_cut_copper_stairs
            waxed_oxidized_chiseled_copper waxed_oxidized_copper waxed_oxidized_copper_bulb
            waxed_oxidized_copper_door waxed_oxidized_copper_grate waxed_oxidized_copper_trapdoor
            waxed_oxidized_cut_copper waxed_oxidized_cut_copper_slab
            waxed_oxidized_cut_copper_stairs waxed_weathered_chiseled_copper waxed_weathered_copper
            waxed_weathered_copper_bulb waxed_weathered_copper_door waxed_weathered_copper_grate
            waxed_weathered_copper_trapdoor waxed_weathered_cut_copper
            waxed_weathered_cut_copper_slab waxed_weathered_cut_copper_stairs
            weathered_chiseled_copper weathered_copper weathered_copper_bulb weathered_copper_door
            weathered_copper_grate weathered_copper_trapdoor weathered_cut_copper
            weathered_cut_copper_slab weathered_cut_copper_stairs
            """;

    private static final String GOLDEN_WOOD_BUILD = """
            acacia_door acacia_fence acacia_fence_gate acacia_hanging_sign acacia_planks
            acacia_sign acacia_slab acacia_stairs acacia_trapdoor acacia_wall_hanging_sign
            acacia_wall_sign bamboo_door bamboo_fence bamboo_fence_gate bamboo_hanging_sign
            bamboo_mosaic bamboo_mosaic_slab bamboo_mosaic_stairs bamboo_planks bamboo_sign
            bamboo_slab bamboo_stairs bamboo_trapdoor bamboo_wall_hanging_sign bamboo_wall_sign
            birch_door birch_fence birch_fence_gate birch_hanging_sign birch_planks birch_sign
            birch_slab birch_stairs birch_trapdoor birch_wall_hanging_sign birch_wall_sign
            cherry_door cherry_fence cherry_fence_gate cherry_hanging_sign cherry_planks
            cherry_sign cherry_slab cherry_stairs cherry_trapdoor cherry_wall_hanging_sign
            cherry_wall_sign crimson_door crimson_fence crimson_fence_gate crimson_hanging_sign
            crimson_planks crimson_sign crimson_slab crimson_stairs crimson_trapdoor
            crimson_wall_hanging_sign crimson_wall_sign dark_oak_door dark_oak_fence
            dark_oak_fence_gate dark_oak_hanging_sign dark_oak_planks dark_oak_sign dark_oak_slab
            dark_oak_stairs dark_oak_trapdoor dark_oak_wall_hanging_sign dark_oak_wall_sign
            jungle_door jungle_fence jungle_fence_gate jungle_hanging_sign jungle_planks
            jungle_sign jungle_slab jungle_stairs jungle_trapdoor jungle_wall_hanging_sign
            jungle_wall_sign ladder mangrove_door mangrove_fence mangrove_fence_gate
            mangrove_hanging_sign mangrove_planks mangrove_sign mangrove_slab mangrove_stairs
            mangrove_trapdoor mangrove_wall_hanging_sign mangrove_wall_sign oak_door oak_fence
            oak_fence_gate oak_hanging_sign oak_planks oak_sign oak_slab oak_stairs oak_trapdoor
            oak_wall_hanging_sign oak_wall_sign pale_oak_door pale_oak_fence pale_oak_fence_gate
            pale_oak_hanging_sign pale_oak_planks pale_oak_sign pale_oak_slab pale_oak_stairs
            pale_oak_trapdoor pale_oak_wall_hanging_sign pale_oak_wall_sign scaffolding spruce_door
            spruce_fence spruce_fence_gate spruce_hanging_sign spruce_planks spruce_sign
            spruce_slab spruce_stairs spruce_trapdoor spruce_wall_hanging_sign spruce_wall_sign
            warped_door warped_fence warped_fence_gate warped_hanging_sign warped_planks
            warped_sign warped_slab warped_stairs warped_trapdoor warped_wall_hanging_sign
            warped_wall_sign
            """;

    private static final String GOLDEN_FURNISHING = """
            anvil black_banner black_bed black_carpet black_wall_banner black_wool blast_furnace
            blue_banner blue_bed blue_carpet blue_wall_banner blue_wool bookshelf brewing_stand
            brown_banner brown_bed brown_carpet brown_wall_banner brown_wool chipped_anvil
            chiseled_bookshelf crafting_table cyan_banner cyan_bed cyan_carpet cyan_wall_banner
            cyan_wool damaged_anvil enchanting_table furnace gray_banner gray_bed gray_carpet
            gray_wall_banner gray_wool green_banner green_bed green_carpet green_wall_banner
            green_wool hay_block light_blue_banner light_blue_bed light_blue_carpet
            light_blue_wall_banner light_blue_wool light_gray_banner light_gray_bed
            light_gray_carpet light_gray_wall_banner light_gray_wool lime_banner lime_bed
            lime_carpet lime_wall_banner lime_wool magenta_banner magenta_bed magenta_carpet
            magenta_wall_banner magenta_wool orange_banner orange_bed orange_carpet
            orange_wall_banner orange_wool pink_banner pink_bed pink_carpet pink_wall_banner
            pink_wool purple_banner purple_bed purple_carpet purple_wall_banner purple_wool
            red_banner red_bed red_carpet red_wall_banner red_wool smoker white_banner white_bed
            white_carpet white_wall_banner white_wool yellow_banner yellow_bed yellow_carpet
            yellow_wall_banner yellow_wool
            """;

    private static final String GOLDEN_FOSSIL_GEODE = """
            amethyst_block amethyst_cluster bone_block budding_amethyst large_amethyst_bud
            medium_amethyst_bud small_amethyst_bud
            """;

    private static final String GOLDEN_UNCLASSIFIED = """
            acacia_button acacia_leaves acacia_log acacia_pressure_plate acacia_sapling acacia_wood
            andesite_slab andesite_stairs andesite_wall attached_melon_stem attached_pumpkin_stem
            bamboo_block bamboo_button bamboo_pressure_plate barrier beacon bee_nest beehive
            beetroots bell birch_button birch_leaves birch_log birch_pressure_plate birch_sapling
            birch_wood black_candle_cake black_concrete black_concrete_powder
            black_glazed_terracotta blackstone_slab blackstone_stairs blackstone_wall
            blue_candle_cake blue_concrete blue_concrete_powder blue_glazed_terracotta
            brown_candle_cake brown_concrete brown_concrete_powder brown_glazed_terracotta cake
            candle_cake carrots cartography_table carved_pumpkin cauldron chain chain_command_block
            cherry_button cherry_leaves cherry_log cherry_pressure_plate cherry_sapling cherry_wood
            chorus_flower chorus_plant coal_block cobbled_deepslate_slab cobbled_deepslate_stairs
            cobbled_deepslate_wall cobblestone_slab cobblestone_stairs cobblestone_wall cocoa
            command_block comparator composter conduit crafter creaking_heart creeper_head
            creeper_wall_head crimson_button crimson_hyphae crimson_pressure_plate crying_obsidian
            cyan_candle_cake cyan_concrete cyan_concrete_powder cyan_glazed_terracotta
            dark_oak_button dark_oak_leaves dark_oak_log dark_oak_pressure_plate dark_oak_sapling
            dark_oak_wood dark_prismarine dark_prismarine_slab dark_prismarine_stairs
            daylight_detector diamond_block diorite_slab diorite_stairs diorite_wall dirt_path
            dispenser dragon_egg dragon_head dragon_wall_head dried_kelp_block dropper
            emerald_block end_gateway end_portal end_portal_frame end_rod farmland fletching_table
            flower_pot gold_block granite_slab granite_stairs granite_wall gray_candle_cake
            gray_concrete gray_concrete_powder gray_glazed_terracotta green_candle_cake
            green_concrete green_concrete_powder green_glazed_terracotta grindstone heavy_core
            heavy_weighted_pressure_plate honey_block honeycomb_block hopper infested_cobblestone
            iron_block iron_door iron_trapdoor jack_o_lantern jigsaw jukebox jungle_button
            jungle_leaves jungle_log jungle_pressure_plate jungle_sapling jungle_wood lapis_block
            lava_cauldron lectern lever light light_blue_candle_cake light_blue_concrete
            light_blue_concrete_powder light_blue_glazed_terracotta light_gray_candle_cake
            light_gray_concrete light_gray_concrete_powder light_gray_glazed_terracotta
            light_weighted_pressure_plate lightning_rod lime_candle_cake lime_concrete
            lime_concrete_powder lime_glazed_terracotta lodestone loom magenta_candle_cake
            magenta_concrete magenta_concrete_powder magenta_glazed_terracotta mangrove_button
            mangrove_leaves mangrove_log mangrove_pressure_plate mangrove_propagule mangrove_wood
            melon melon_stem moving_piston nether_portal nether_wart netherite_block note_block
            oak_button oak_leaves oak_log oak_pressure_plate oak_sapling oak_wood observer
            ochre_froglight orange_candle_cake orange_concrete orange_concrete_powder
            orange_glazed_terracotta packed_mud pale_oak_button pale_oak_leaves pale_oak_log
            pale_oak_pressure_plate pale_oak_sapling pale_oak_wood pearlescent_froglight
            petrified_oak_slab piglin_head piglin_wall_head pink_candle_cake pink_concrete
            pink_concrete_powder pink_glazed_terracotta piston piston_head pitcher_crop player_head
            player_wall_head potatoes potted_acacia_sapling potted_allium potted_azalea_bush
            potted_azure_bluet potted_bamboo potted_birch_sapling potted_blue_orchid
            potted_brown_mushroom potted_cactus potted_cherry_sapling potted_closed_eyeblossom
            potted_cornflower potted_crimson_fungus potted_crimson_roots potted_dandelion
            potted_dark_oak_sapling potted_dead_bush potted_fern potted_flowering_azalea_bush
            potted_jungle_sapling potted_lily_of_the_valley potted_mangrove_propagule
            potted_oak_sapling potted_open_eyeblossom potted_orange_tulip potted_oxeye_daisy
            potted_pale_oak_sapling potted_pink_tulip potted_poppy potted_red_mushroom
            potted_red_tulip potted_spruce_sapling potted_torchflower potted_warped_fungus
            potted_warped_roots potted_white_tulip potted_wither_rose powder_snow_cauldron
            prismarine prismarine_slab prismarine_stairs prismarine_wall pumpkin pumpkin_stem
            purple_candle_cake purple_concrete purple_concrete_powder purple_glazed_terracotta
            purpur_block purpur_pillar purpur_slab purpur_stairs quartz_block quartz_pillar
            quartz_slab quartz_stairs red_candle_cake red_concrete red_concrete_powder
            red_glazed_terracotta red_sandstone_slab red_sandstone_stairs red_sandstone_wall
            redstone_block redstone_lamp redstone_torch redstone_wall_torch redstone_wire repeater
            repeating_command_block resin_block resin_clump respawn_anchor sandstone_slab
            sandstone_stairs sandstone_wall sea_lantern skeleton_skull skeleton_wall_skull
            slime_block smithing_table smooth_quartz smooth_quartz_slab smooth_quartz_stairs
            smooth_red_sandstone smooth_red_sandstone_slab smooth_red_sandstone_stairs
            smooth_sandstone smooth_sandstone_slab smooth_sandstone_stairs smooth_stone
            smooth_stone_slab sniffer_egg sponge spruce_button spruce_leaves spruce_log
            spruce_pressure_plate spruce_sapling spruce_wood sticky_piston stone_button
            stone_pressure_plate stone_slab stone_stairs stonecutter stripped_acacia_log
            stripped_acacia_wood stripped_bamboo_block stripped_birch_log stripped_birch_wood
            stripped_cherry_log stripped_cherry_wood stripped_crimson_hyphae stripped_crimson_stem
            stripped_dark_oak_log stripped_dark_oak_wood stripped_jungle_log stripped_jungle_wood
            stripped_mangrove_log stripped_mangrove_wood stripped_oak_log stripped_oak_wood
            stripped_pale_oak_log stripped_pale_oak_wood stripped_spruce_log stripped_spruce_wood
            stripped_warped_hyphae stripped_warped_stem structure_block structure_void
            suspicious_gravel suspicious_sand target test_block test_instance_block tnt
            torchflower_crop tripwire tripwire_hook verdant_froglight warped_button warped_hyphae
            warped_pressure_plate water_cauldron wet_sponge wheat white_candle_cake white_concrete
            white_concrete_powder white_glazed_terracotta wither_skeleton_skull
            wither_skeleton_wall_skull yellow_candle_cake yellow_concrete yellow_concrete_powder
            yellow_glazed_terracotta zombie_head zombie_wall_head
            """;

    private static final String GOLDEN_LIGHT_DRESSING = """
            black_candle blue_candle brown_candle campfire candle cyan_candle gray_candle
            green_candle lantern light_blue_candle light_gray_candle lime_candle magenta_candle
            orange_candle pink_candle purple_candle red_candle soul_campfire soul_lantern
            soul_torch soul_wall_torch torch wall_torch white_candle yellow_candle
            """;

    private static final String GOLDEN_COBBLE = """
            cobbled_deepslate cobblestone
            """;

    private static final String GOLDEN_NATURAL = """
            air allium ancient_debris andesite azalea azalea_leaves azure_bluet bamboo
            bamboo_sapling basalt bedrock big_dripleaf big_dripleaf_stem black_terracotta
            blackstone blue_ice blue_orchid blue_terracotta brain_coral brain_coral_block
            brain_coral_fan brain_coral_wall_fan brown_mushroom brown_mushroom_block
            brown_terracotta bubble_column bubble_coral bubble_coral_block bubble_coral_fan
            bubble_coral_wall_fan bush cactus cactus_flower calcite cave_air cave_vines
            cave_vines_plant clay closed_eyeblossom coal_ore coarse_dirt copper_ore cornflower
            crimson_fungus crimson_nylium crimson_roots crimson_stem cyan_terracotta dandelion
            dead_brain_coral dead_brain_coral_block dead_brain_coral_fan dead_brain_coral_wall_fan
            dead_bubble_coral dead_bubble_coral_block dead_bubble_coral_fan
            dead_bubble_coral_wall_fan dead_bush dead_fire_coral dead_fire_coral_block
            dead_fire_coral_fan dead_fire_coral_wall_fan dead_horn_coral dead_horn_coral_block
            dead_horn_coral_fan dead_horn_coral_wall_fan dead_tube_coral dead_tube_coral_block
            dead_tube_coral_fan dead_tube_coral_wall_fan deepslate deepslate_coal_ore
            deepslate_copper_ore deepslate_diamond_ore deepslate_emerald_ore deepslate_gold_ore
            deepslate_iron_ore deepslate_lapis_ore deepslate_redstone_ore diamond_ore diorite dirt
            dripstone_block emerald_ore end_stone fern fire fire_coral fire_coral_block
            fire_coral_fan fire_coral_wall_fan firefly_bush flowering_azalea
            flowering_azalea_leaves frogspawn frosted_ice gilded_blackstone glow_lichen glowstone
            gold_ore granite grass_block gravel gray_terracotta green_terracotta hanging_roots
            horn_coral horn_coral_block horn_coral_fan horn_coral_wall_fan ice infested_deepslate
            infested_stone iron_ore kelp kelp_plant lapis_ore large_fern lava leaf_litter
            light_blue_terracotta light_gray_terracotta lilac lily_of_the_valley lily_pad
            lime_terracotta magenta_terracotta magma_block mangrove_roots moss_block moss_carpet
            mud muddy_mangrove_roots mushroom_stem mycelium nether_gold_ore nether_quartz_ore
            nether_sprouts nether_wart_block netherrack obsidian open_eyeblossom orange_terracotta
            orange_tulip oxeye_daisy packed_ice pale_hanging_moss pale_moss_block pale_moss_carpet
            peony pink_petals pink_terracotta pink_tulip pitcher_plant podzol pointed_dripstone
            poppy powder_snow purple_terracotta raw_copper_block raw_gold_block raw_iron_block
            red_mushroom red_mushroom_block red_sand red_sandstone red_terracotta red_tulip
            redstone_ore rooted_dirt rose_bush sand sandstone sculk sculk_vein sea_pickle seagrass
            short_dry_grass short_grass shroomlight small_dripleaf smooth_basalt snow snow_block
            soul_fire soul_sand soul_soil spore_blossom stone sugar_cane sunflower sweet_berry_bush
            tall_dry_grass tall_grass tall_seagrass terracotta torchflower tube_coral
            tube_coral_block tube_coral_fan tube_coral_wall_fan tuff turtle_egg twisting_vines
            twisting_vines_plant vine void_air warped_fungus warped_nylium warped_roots warped_stem
            warped_wart_block water weeping_vines weeping_vines_plant white_terracotta white_tulip
            wildflowers wither_rose yellow_terracotta
            """;
}
