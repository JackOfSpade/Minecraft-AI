package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared "first cell among these that needs clearing" terrain checks, used by dig-navigation and
 * dive-shaft/descent tasks to find the next block that blocks a multi-cell body from passing.
 *
 * <p>Before this class existed, {@code DigNav}, {@code DescendToYTask}, {@code DigDownTask} and
 * {@code OreDigTask} each carried their own private copy. The copies are not all behaviourally
 * identical: some skip fluid cells (only a truly solid, non-air, non-fluid block counts) and some
 * don't (any non-air block, fluid included, counts). Each method below keeps one of those exact
 * behaviours; do not merge {@link #firstSolid(ServerLevel, BlockPos, BlockPos)} and
 * {@link #firstNonAir} even though they share a signature shape, since they disagree on fluids.</p>
 */
public final class TerrainProbe {
    private TerrainProbe() {
    }

    /**
     * Returns, in order, the first of {@code a}, {@code b}, {@code c} that is solid and not a fluid
     * (a fluid cell is skipped, never counted -- avoids treating a lava/water cell as something that
     * needs digging). Used to clear the three body cells of a descent/dive stair step (the head cell,
     * the headroom cell above it, and the foot cell), ensuring the tunnel has 2-cell walkable height.
     */
    public static BlockPos firstSolid(ServerLevel world, BlockPos a, BlockPos b, BlockPos c) {
        for (BlockPos p : new BlockPos[]{a, b, c}) {
            if (!world.getBlockState(p).isAir() && world.getFluidState(p).isEmpty()) {
                return p.immutable();
            }
        }
        return null;
    }

    /** Two-cell version of {@link #firstSolid(ServerLevel, BlockPos, BlockPos, BlockPos)}: skips fluid cells. */
    public static BlockPos firstSolid(ServerLevel world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir() && world.getFluidState(a).isEmpty()) {
            return a.immutable();
        }
        if (!world.getBlockState(b).isAir() && world.getFluidState(b).isEmpty()) {
            return b.immutable();
        }
        return null;
    }

    /**
     * The first of {@code a}, {@code b} that is non-air -- unlike {@link #firstSolid(ServerLevel,
     * BlockPos, BlockPos)}, a fluid cell counts here (it still needs to be dug/cleared for the bot to
     * pass through it).
     */
    public static BlockPos firstNonAir(ServerLevel world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir()) {
            return a.immutable();
        }
        if (!world.getBlockState(b).isAir()) {
            return b.immutable();
        }
        return null;
    }
}
