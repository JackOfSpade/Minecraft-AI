package io.github.zoyluo.minecraftai.mining;

import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ONE natural-terrain break rule of the mod: what a bot may dig through, whichever engine drives the pickaxe. Baritone's
 * {@code BaritoneBreakPlacePolicy} (every click of its controller and its cost model) and the legacy diggers ({@code OreDigTask}
 * through {@code BlockMiner}, {@code NeighborEnumerator.isMineable} for the path search's dig-through moves and
 * {@code FollowDigOut}) all ask this class, so a wall a bot refuses to break on one engine it refuses on the other. It names no
 * Baritone type: the legacy engine runs without a Baritone class ever being loaded.
 *
 * <p><b>What a bot digs</b> is a whitelist driven by block tags, so it follows data packs and other mods instead of a list of
 * names: the world generator's stone ({@code base_stone_overworld}/{@code base_stone_nether}, {@code stone_ore_replaceables},
 * {@code deepslate_ore_replaceables}: stone, granite, diorite, andesite, tuff, deepslate, netherrack, basalt, blackstone), soil and
 * sand ({@code dirt}, {@code sand}), terracotta (the badlands bands, plain terracotta), packed and blue ice (never plain ice) and nylium, the nether's wart blocks,
 * leaves and the small plants that grow in the way ({@code replaceable}), every ore ({@code c:ores}, the vanilla ore tags and any
 * {@code *_ore}), the common tag {@code c:stones} (modded stone) and a short list of terrain blocks no tag covers (gravel, clay,
 * snow, sandstone, soul sand and soil, dripstone, calcite, smooth basalt, amethyst, sculk, end stone, glowstone, ancient debris,
 * and the raw iron and raw copper blocks of a large ore vein).
 * Cobblestone stays: a bot cannot tell a placed cobblestone from a natural one, exactly like a player.</p>
 *
 * <p><b>What it never digs</b>, before any tag is consulted: unbreakable blocks, fluids, block entities (chests, furnaces, beds,
 * signs, spawners, banners, barrels, hoppers ...), utility blocks, blocks {@link Standability#isDangerous the navigation code
 * calls dangerous} (lava, fire, magma, cactus, pointed dripstone, powder snow ...) and structure or player-build material: the
 * brick family (stone bricks including cracked, mossy and chiseled, nether and deepslate bricks), tiles, polished and cut and
 * smooth (except smooth basalt) stone, slabs, stairs, walls, planks, fences, doors, wool, glass, concrete, rails, lanterns and so
 * on. Infested blocks are refused whatever they look like: a silverfish nest inside a stronghold's wall is exactly what a bot
 * cannot tell from the outside, and a natural infested vein is rare enough to walk around.</p>
 *
 * <p><b>The two engines agree</b> on every verdict except three categories the legacy code handles itself and that {@link #legacyDenialOf} therefore leaves to it (unbreakable blocks, fluids, and the blocks the navigation code calls dangerous: pointed dripstone stays refused by that danger rule); Baritone enforces those three through this class too. The one deliberate exemption of a legacy digger is OreDig's clearing of a block that re-occupied the bot's own body cells.</p>
 *
 * <p>The verdict is a property of the kind of block (its default state), cached in {@link BreakVerdictCache#BLOCKS}; the cache is
 * dropped on server start and whenever tags load, because tags are data. Safe on any thread.</p>
 */
public final class BreakRule {
    private static final TagKey<Block> COMMON_ORES = common("ores");
    private static final TagKey<Block> COMMON_STONES = common("stones");

    /** Things whose use is the point of a right click: never dug. Also read by Baritone's placement rules. */
    public static final Set<Block> USE_INTERACTIVE = Set.of(
            Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE, Blocks.LOOM,
            Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.LEVER, Blocks.NOTE_BLOCK, Blocks.REPEATER, Blocks.COMPARATOR,
            Blocks.DAYLIGHT_DETECTOR, Blocks.COMPOSTER, Blocks.CAULDRON, Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON,
            Blocks.POWDER_SNOW_CAULDRON, Blocks.RESPAWN_ANCHOR, Blocks.DRAGON_EGG, Blocks.CAKE);

    /** Player-build and structure material, by tag: refused even if a data pack also lists it as terrain. */
    private static final List<TagKey<Block>> STRUCTURE_TAGS = List.of(
            BlockTags.PLANKS, BlockTags.STONE_BRICKS, BlockTags.SLABS, BlockTags.STAIRS, BlockTags.WALLS, BlockTags.DOORS,
            BlockTags.TRAPDOORS, BlockTags.FENCES, BlockTags.FENCE_GATES, BlockTags.WOOL, BlockTags.WOOL_CARPETS,
            BlockTags.BUTTONS, BlockTags.PRESSURE_PLATES, BlockTags.ALL_SIGNS, BlockTags.BANNERS, BlockTags.CANDLES,
            BlockTags.RAILS, BlockTags.LANTERNS, BlockTags.CHAINS, BlockTags.BARS, BlockTags.FLOWER_POTS,
            BlockTags.CAULDRONS, BlockTags.SHULKER_BOXES);

    /** Words of a block's registry path that mark a made block ({@code stone_bricks}, {@code polished_andesite}, {@code white_concrete} ...). */
    private static final Set<String> STRUCTURE_WORDS = Set.of(
            "brick", "bricks", "tile", "tiles", "polished", "chiseled", "cut", "planks", "glass", "concrete");

    /** The whitelist part no tag covers. */
    private static final Set<Block> TERRAIN_BLOCKS = Set.of(
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.GRAVEL, Blocks.CLAY, Blocks.SNOW_BLOCK, Blocks.END_STONE,
            Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
            Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.SMOOTH_BASALT, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST,
            Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
            Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.GLOWSTONE, Blocks.ANCIENT_DEBRIS,
            Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK);

    private BreakRule() {
    }

    private static TagKey<Block> common(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", path));
    }

    /** Null if a bot may break blocks of this kind, else the typed reason it may not. */
    public static String denialOf(Block block) {
        String verdict = BreakVerdictCache.BLOCKS.verdict(block, BreakRule::computeDenial);
        return verdict.isEmpty() ? null : verdict;
    }

    /** The reason a bot may not break this state's kind of block, or null. Air has nothing to break, so it is allowed. */
    public static String denialOf(BlockState state) {
        return state.isAir() ? null : denialOf(state.getBlock());
    }

    public static boolean isBreakable(BlockState state) {
        return denialOf(state) == null;
    }

    /**
     * The denials the legacy diggers enforce with this rule: what they had no rule for at all (structure and player-build
     * material, infested blocks, blocks that store or do something, anything that is not natural terrain). The remaining denials
     * (unbreakable blocks, fluids, dangerous blocks) already have dedicated handling in the legacy code (the timeout of a break that
     * cannot finish, the fluid and hazard preflights), which keeps its own behaviour and its own failure reasons. Baritone enforces
     * all of them ({@link #denialOf}).
     *
     * @return the denial, or null when the legacy diggers may go ahead
     */
    public static String legacyDenialOf(BlockState state) {
        String denial = denialOf(state);
        if (denial == null || denial.equals("unbreakable") || denial.equals("fluid") || denial.equals("dangerous_block")) {
            return null;
        }
        return denial;
    }

    private static String computeDenial(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.isAir()) {
            return "";
        }
        if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0.0F) {
            return "unbreakable";
        }
        if (block instanceof LiquidBlock || !state.getFluidState().isEmpty()) {
            return "fluid";
        }
        if (state.hasBlockEntity()) {
            return "block_entity";
        }
        if (state.is(BlockTags.BEDS) || USE_INTERACTIVE.contains(block) || state.is(BlockTags.ANVIL)) {
            return "protected_block";
        }
        if (Standability.isDangerous(state)) {
            return "dangerous_block";
        }
        if (block instanceof InfestedBlock) {
            return "infested_block";
        }
        if (isStructureMaterial(state, block)) {
            return "structure_block";
        }
        if (block == Blocks.ICE || block == Blocks.FROSTED_ICE) {
            return "ice_releases_water"; // breaking it leaves a water source: it would flood the tunnel
        }
        return naturalTerrain(state, block) ? "" : "not_natural_terrain";
    }

    private static boolean isStructureMaterial(BlockState state, Block block) {
        for (TagKey<Block> tag : STRUCTURE_TAGS) {
            if (state.is(tag)) {
                return true;
            }
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (path.startsWith("smooth_") && !path.equals("smooth_basalt")) {
            return true;
        }
        for (String word : path.split("_")) {
            if (STRUCTURE_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Terrain a world generator puts down and a bot may dig through. */
    private static boolean naturalTerrain(BlockState state, Block block) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.is(COMMON_STONES)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.BADLANDS_TERRACOTTA)
                || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE
                || state.is(BlockTags.NYLIUM)
                || state.is(BlockTags.WART_BLOCKS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.REPLACEABLE)
                || state.is(COMMON_ORES)
                || TERRAIN_BLOCKS.contains(block)
                || OreScan.isOreBlock(block);
    }
}
