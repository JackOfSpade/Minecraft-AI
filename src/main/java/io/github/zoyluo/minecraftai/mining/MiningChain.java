package io.github.zoyluo.minecraftai.mining;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
 */
public final class MiningChain {

    /** An ore chain entry. bestY = the recommended target Y level to mine down to (the peak layer). */
    public record OreEntry(Block ore, Block deepslate, Item rawDrop, Item smelted, int pickaxeTier, int bestY) {
    }

    private static final OreEntry[] TABLE = {
            //          Ore                     Deepslate variant                  Drop              Smelted product   Pickaxe tier   Peak Y
            // strict_survival can only inspect exposed blocks. Starting a branch mine at the
            // surface therefore turns coal acquisition into an endless dirt/forest spiral. Enter
            // the same rock layer used by OreScan before opening the observable search tunnel.
            new OreEntry(Blocks.COAL_ORE,     Blocks.DEEPSLATE_COAL_ORE,     Items.COAL,         Items.COAL,        ToolTier.WOOD,  48),
            new OreEntry(Blocks.COPPER_ORE,   Blocks.DEEPSLATE_COPPER_ORE,   Items.RAW_COPPER,   Items.COPPER_INGOT, ToolTier.STONE, 48),
            new OreEntry(Blocks.IRON_ORE,     Blocks.DEEPSLATE_IRON_ORE,     Items.RAW_IRON,     Items.IRON_INGOT,  ToolTier.STONE, 16),
            new OreEntry(Blocks.LAPIS_ORE,    Blocks.DEEPSLATE_LAPIS_ORE,    Items.LAPIS_LAZULI, Items.LAPIS_LAZULI, ToolTier.STONE, -1),
            new OreEntry(Blocks.GOLD_ORE,     Blocks.DEEPSLATE_GOLD_ORE,     Items.RAW_GOLD,     Items.GOLD_INGOT,  ToolTier.IRON,  -16),
            new OreEntry(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Items.REDSTONE,     Items.REDSTONE,    ToolTier.IRON,  -59),
            new OreEntry(Blocks.EMERALD_ORE,  Blocks.DEEPSLATE_EMERALD_ORE,  Items.EMERALD,      Items.EMERALD,     ToolTier.IRON,  -16),
            new OreEntry(Blocks.DIAMOND_ORE,  Blocks.DEEPSLATE_DIAMOND_ORE,  Items.DIAMOND,      Items.DIAMOND,     ToolTier.IRON,  -59),
    };

    private static final Map<Block, OreEntry> BY_BLOCK = new HashMap<>();

    static {
        for (OreEntry e : TABLE) {
            BY_BLOCK.put(e.ore(), e);
            BY_BLOCK.put(e.deepslate(), e);
        }
    }

    private MiningChain() {
    }

    /** The chain entry for this ore block (regular or deepslate); returns null for an unknown ore. */
    public static OreEntry forOre(Block block) {
        return BY_BLOCK.get(block);
    }

    /**
     * The recommended target Y level to mine down to for this set of ores: takes the **deepest**
     * layer (the smallest bestY) among all known ores, so a single descent reaches all of them.
     * No known ore -> returns Integer.MAX_VALUE (the caller then does not force a descent).
     */
    public static int bestY(Set<Block> ores) {
        int best = Integer.MAX_VALUE;
        for (Block ore : ores) {
            OreEntry e = BY_BLOCK.get(ore);
            if (e != null) {
                best = Math.min(best, e.bestY());
            }
        }
        return best;
    }

    /** The pickaxe tier required for this set of ores (delegated to the existing ToolTier, keeping pickaxe tier as a single source). */
    public static int pickaxeTier(Set<Block> ores) {
        return ToolTier.requiredPickaxeTier(ores);
    }

}
