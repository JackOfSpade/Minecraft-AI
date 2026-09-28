package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Minecraft adapter that turns a {@link BlockState} the sensor has ALREADY observed into the pure
 * {@link BlockFacts} the folds understand (mining-assist design 3.3). It reads nothing from the world:
 * the state is handed in by {@code castViewRay} or by a proven break-peek observation, and everything
 * derived from it is cached once per {@link Block} in a small identity map (server thread only, at most
 * one entry per registered block).
 *
 * <p>Classification goes through {@link PoiLexicon#classify}. For vanilla blocks the lexicon's
 * {@code naturalTag} input is the "bot stands in lush caves" flag, which the lexicon honours only for
 * logs and leaves (azalea trees). For non-vanilla blocks it is "the block sits in a natural-terrain
 * tag" (base stone, dirt, sand ... or the common {@code c:ores} tag), so a modded stone is not mistaken
 * for a building. Values come from {@link ValueTable}, which covers everything
 * {@code mining.ValuableScan} lists plus nether quartz.</p>
 *
 * <p>Fluid state depends on the individual state (a waterlogged slab), so it is not cached: use
 * {@link #fluidKind(BlockState)} per hit.</p>
 */
public final class BlockFactsAdapter {
    private static final TagKey<Block> COMMON_ORES = TagKey.of(RegistryKeys.BLOCK, Identifier.of("c", "ores"));
    private static final TagKey<Block> COMMON_STONES = TagKey.of(RegistryKeys.BLOCK, Identifier.of("c", "stones"));

    /** Per block: index 0 is the ordinary classification, index 1 the lush-caves one. */
    private static final Map<Block, BlockFacts[]> CACHE = new IdentityHashMap<>();

    private BlockFactsAdapter() {
    }

    /** Facts of {@code state}'s block, classified as outside lush caves. */
    public static BlockFacts of(BlockState state) {
        return of(state, false);
    }

    /**
     * Facts of {@code state}'s block.
     *
     * @param lush true while the bot's own feet biome is lush caves (the lexicon's naturalTag for vanilla logs and leaves)
     */
    public static BlockFacts of(BlockState state, boolean lush) {
        Block block = state.getBlock();
        int slot = lush ? 1 : 0;
        BlockFacts[] facts = CACHE.get(block);
        if (facts == null) {
            facts = new BlockFacts[2];
            CACHE.put(block, facts);
        }
        BlockFacts cached = facts[slot];
        if (cached == null) {
            cached = compute(block, state, lush);
            facts[slot] = cached;
        }
        return cached;
    }

    /**
     * The hazardous fluid this state holds: LAVA or WATER, or null. Waterlogged blocks and water plants
     * count as water. Other fluids (modded) are not a hazard kind and return null.
     */
    public static HazardField.Kind fluidKind(BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return null;
        }
        if (fluid.isIn(FluidTags.LAVA)) {
            return HazardField.Kind.LAVA;
        }
        if (fluid.isIn(FluidTags.WATER)) {
            return HazardField.Kind.WATER;
        }
        return null;
    }

    /** True when the state holds any fluid at all (drives the occupancy FLUID mark). */
    public static boolean holdsFluid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    /** Forgets every cached fact (tags and registries reload with a new world). */
    public static void clearCache() {
        CACHE.clear();
    }

    /** Number of blocks classified so far (diagnostics). */
    public static int cacheSize() {
        return CACHE.size();
    }

    private static BlockFacts compute(Block block, BlockState state, boolean lush) {
        Identifier id = Registries.BLOCK.getId(block);
        String namespace = id.getNamespace();
        String path = id.getPath();
        boolean hasBlockEntity = state.hasBlockEntity();
        boolean vanilla = AssistRules.isVanilla(namespace);
        boolean naturalTag = vanilla ? lush : inNaturalTerrainTag(state);
        return BlockFacts.derive(namespace, path, hasBlockEntity, block instanceof FallingBlock, naturalTag);
    }

    private static boolean inNaturalTerrainTag(BlockState state) {
        return state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.BASE_STONE_NETHER)
                || state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.SAND)
                || state.isIn(BlockTags.TERRACOTTA)
                || state.isIn(BlockTags.ICE)
                || state.isIn(BlockTags.SNOW)
                || state.isIn(BlockTags.NYLIUM)
                || state.isIn(BlockTags.MOSS_REPLACEABLE)
                || state.isIn(COMMON_ORES)
                || state.isIn(COMMON_STONES);
    }
}
