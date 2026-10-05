package io.github.zoyluo.minecraftai.mining;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * S2: Single source of truth for the ore mining chain -- collapses "ore block → drop → smelted
 * product → required pickaxe tier → recommended Y level" into one place.
 *
 * Previously this knowledge was scattered: `GoalPlanner.bestMiningY` hardcoded the Y level,
 * `ToolTier` computed pickaxe tier, `HarvestCore.expectedDropsFor` computed drops, and smelting
 * mappings were spread everywhere. This table serves as the single source of truth: Y level and
 * smelted product are provided by this class; pickaxe tier is delegated to the existing
 * {@link ToolTier} (keeping pickaxe tier as a single source).
 *
 * <p>The Y values are deliberately <em>downward-exploration targets</em>, not an instruction to
 * tunnel below a target once it has been reached.  A caller may descend only while its feet are
 * above the selected target; see {@link #shouldDescend(int, int)}.  This is important for ores
 * whose statistically richest band is higher than a typical underground player (notably Nether
 * gold/quartz and mountain emerald): the bot explores its current observed space rather than
 * digging farther away from the requested resource.</p>
 */
public final class MiningChain {

    /** Vanilla dimensions that have natural ore-generation profiles. */
    public enum OreDimension {
        OVERWORLD,
        NETHER
    }

    /** An ore chain entry. {@code bestY} is the documented, safe downward target layer. */
    public record OreEntry(Block ore, Block variant, Item rawDrop, Item smelted, int pickaxeTier,
                           OreDimension dimension, int bestY) {
    }

    private static final OreEntry[] TABLE = {
            //          Ore                     Variant                            Drop              Smelted product     Tier             Dimension             Target Y
            // These values are documented in docs/MINING_EXPLORATION.md with the researched
            // generation references.  They intentionally favour a useful branch-mining layer
            // that a bot can reach by descending safely from ordinary surface play.
            new OreEntry(Blocks.COAL_ORE,     Blocks.DEEPSLATE_COAL_ORE,     Items.COAL,         Items.COAL,          ToolTier.WOOD,  OreDimension.OVERWORLD, 45),
            new OreEntry(Blocks.COPPER_ORE,   Blocks.DEEPSLATE_COPPER_ORE,   Items.RAW_COPPER,   Items.COPPER_INGOT,   ToolTier.STONE, OreDimension.OVERWORLD, 43),
            new OreEntry(Blocks.IRON_ORE,     Blocks.DEEPSLATE_IRON_ORE,     Items.RAW_IRON,     Items.IRON_INGOT,     ToolTier.STONE, OreDimension.OVERWORLD, 14),
            new OreEntry(Blocks.LAPIS_ORE,    Blocks.DEEPSLATE_LAPIS_ORE,    Items.LAPIS_LAZULI, Items.LAPIS_LAZULI,   ToolTier.STONE, OreDimension.OVERWORLD, 0),
            new OreEntry(Blocks.GOLD_ORE,     Blocks.DEEPSLATE_GOLD_ORE,     Items.RAW_GOLD,     Items.GOLD_INGOT,     ToolTier.IRON,  OreDimension.OVERWORLD, -16),
            new OreEntry(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Items.REDSTONE,     Items.REDSTONE,       ToolTier.IRON,  OreDimension.OVERWORLD, -58),
            new OreEntry(Blocks.EMERALD_ORE,  Blocks.DEEPSLATE_EMERALD_ORE,  Items.EMERALD,      Items.EMERALD,        ToolTier.IRON,  OreDimension.OVERWORLD, 85),
            new OreEntry(Blocks.DIAMOND_ORE,  Blocks.DEEPSLATE_DIAMOND_ORE,  Items.DIAMOND,      Items.DIAMOND,        ToolTier.IRON,  OreDimension.OVERWORLD, -58),
            new OreEntry(Blocks.NETHER_QUARTZ_ORE, null,                      Items.QUARTZ,       Items.QUARTZ,         ToolTier.WOOD,  OreDimension.NETHER,    114),
            new OreEntry(Blocks.NETHER_GOLD_ORE, null,                        Items.GOLD_NUGGET,  Items.GOLD_NUGGET,    ToolTier.WOOD,  OreDimension.NETHER,    114),
            new OreEntry(Blocks.ANCIENT_DEBRIS, null,                         Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP, ToolTier.DIAMOND, OreDimension.NETHER,   16),
    };

    private static final Map<Block, OreEntry> BY_BLOCK = new HashMap<>();

    static {
        for (OreEntry e : TABLE) {
            BY_BLOCK.put(e.ore(), e);
            if (e.variant() != null) {
                BY_BLOCK.put(e.variant(), e);
            }
        }
    }

    private MiningChain() {
    }

    /** The chain entry for this ore block (regular or deepslate); returns null for an unknown ore. */
    public static OreEntry forOre(Block block) {
        return BY_BLOCK.get(block);
    }

    /**
     * Backwards-compatible Overworld profile. Runtime mining should call
     * {@link #bestY(ResourceKey, Set)} so Nether-only ores cannot accidentally select an
     * Overworld depth.
     */
    public static int bestY(Set<Block> ores) {
        return bestY(Level.OVERWORLD, ores);
    }

    /**
     * The recommended target Y level for this dimension and requested ore family. Mixed requests
     * take the deepest compatible target, so one safe descent can expose every requested
     * underground family. No compatible known ore returns {@link Integer#MAX_VALUE}; callers must
     * then keep ordinary observed exploration rather than inventing a depth.
     */
    public static int bestY(ResourceKey<Level> dimension, Set<Block> ores) {
        OreDimension requestedDimension = oreDimension(dimension);
        if (requestedDimension == null || ores == null || ores.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (Block ore : ores) {
            OreEntry e = BY_BLOCK.get(ore);
            if (e != null && e.dimension() == requestedDimension) {
                best = Math.min(best, e.bestY());
            }
        }
        return best;
    }

    /** Convenience overload for runtime callers holding a level rather than its key. */
    public static int bestY(Level level, Set<Block> ores) {
        return level == null ? bestY(ores) : bestY(level.dimension(), ores);
    }

    /**
     * A depth target authorizes a staircase only while it is below the bot's current feet.
     * Equality is deliberately false: reaching the optimal layer transfers control to observed
     * cave/branch exploration and prevents the old "keep digging downward" failure mode.
     */
    public static boolean shouldDescend(int currentY, int targetY) {
        return targetY != Integer.MAX_VALUE && currentY > targetY;
    }

    private static OreDimension oreDimension(ResourceKey<Level> dimension) {
        if (Level.OVERWORLD.equals(dimension)) {
            return OreDimension.OVERWORLD;
        }
        if (Level.NETHER.equals(dimension)) {
            return OreDimension.NETHER;
        }
        return null;
    }

    /** The pickaxe tier required for this set of ores (delegated to the existing ToolTier, keeping pickaxe tier as a single source). */
    public static int pickaxeTier(Set<Block> ores) {
        return ToolTier.requiredPickaxeTier(ores);
    }

}
