package io.github.zoyluo.aibot.mining;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * "Valuable" superset for the player-facing "mine all valuables" command: every vanilla ore
 * family {@link OreScan} already tracks, plus a small, deliberately curated set of other blocks
 * a player would reasonably want auto-mined on sight. Kept separate from {@link OreScan} so its
 * ore-family grouping logic (used by the deterministic {@code mine_ore} goal) stays untangled
 * from this broader, scan-only notion of "worth picking up" -- easy to extend later without
 * touching any ore-specific caller.
 *
 * <p>Deliberately excluded:
 * <ul>
 *   <li>Obsidian / crying obsidian -- almost always a player-built nether portal or enchanting
 *       setup. A "mine valuables" command must never eat a player's own structure.</li>
 *   <li>Mineral storage blocks (iron/gold/diamond/etc. block) -- overwhelmingly player-placed
 *       stored wealth, not something naturally found while exploring. The "raw" ore blocks below
 *       are the one exception the feature request calls out explicitly.</li>
 * </ul>
 */
public final class ValuableScan {
    public static final Set<Block> VALUABLES;

    static {
        Set<Block> set = new LinkedHashSet<>(OreScan.COMMON_ORES);
        set.add(Blocks.ANCIENT_DEBRIS);
        set.add(Blocks.NETHER_GOLD_ORE);
        set.add(Blocks.GILDED_BLACKSTONE);
        set.add(Blocks.RAW_IRON_BLOCK);
        set.add(Blocks.RAW_COPPER_BLOCK);
        set.add(Blocks.RAW_GOLD_BLOCK);
        set.add(Blocks.AMETHYST_CLUSTER);
        VALUABLES = Set.copyOf(set);
    }

    private ValuableScan() {
    }

    public static boolean isValuable(Block block) {
        return block != null && VALUABLES.contains(block);
    }

    public static boolean isValuable(BlockState state) {
        return state != null && isValuable(state.getBlock());
    }
}
