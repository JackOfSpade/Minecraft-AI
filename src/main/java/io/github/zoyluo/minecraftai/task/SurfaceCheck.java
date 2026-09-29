package io.github.zoyluo.minecraftai.task;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Is a spot on the surface (open to the sky) rather than underground or indoors?
 *
 * <p>The answer is what looking straight up from the spot shows: air and fluids do not block, natural
 * tree and mushroom growth does not block either (a tree canopy is still the surface), and any other
 * block is a roof. Reaching the top of the world without a roof means the surface. The Nether and
 * other bedrock-ceilinged dimensions therefore never count as the surface.
 *
 * <p>This reads only the column straight above the spot, exactly what the bot sees looking up; it is
 * not resource discovery. Its one consumer is the automatic torch reflexes, which must never spend
 * torches lighting the open surface (see {@code DangerWatcher} and the {@code night} config section).
 *
 * <p>Cost: an underground spot stops at its ceiling, a surface spot reads the column up to the sky
 * limit (a few hundred {@code getBlockState} calls), and callers run it only when an automatic
 * lighting trigger is about to fire.
 */
public final class SurfaceCheck {
    private SurfaceCheck() {
    }

    /**
     * The one place that lists what does NOT count as a roof. Everything else does.
     * <ul>
     *   <li>Air and fluids ({@link SurfaceColumn.Cell#OPEN}).</li>
     *   <li>Trees, every variation: all logs/wood/stems/hyphae (stripped ones included), leaves of every
     *       tree, vines, cocoa, bee nests and hives, mangrove roots and propagules, pale hanging moss,
     *       moss carpets, leaf litter, pink petals, snow layers resting on a canopy.</li>
     *   <li>Mushrooms and fungi: big mushroom caps and stems, small mushrooms, fungi, nether/warped wart
     *       blocks, shroomlight.</li>
     *   <li>Soft ground cover a bot walks through, whose upper half fills the bot's head cell: tall grass
     *       and every other replaceable plant (tag {@code replaceable}), flowers, saplings, sugar cane,
     *       bamboo. Without this a bot standing in tall grass would read as "under a roof".</li>
     * </ul>
     */
    static SurfaceColumn.Cell classify(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return SurfaceColumn.Cell.OPEN;
        }
        if (state.isIn(BlockTags.LOGS)
                || state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.WART_BLOCKS)
                || state.isIn(BlockTags.REPLACEABLE)
                || state.isIn(BlockTags.FLOWERS)
                || state.isIn(BlockTags.SAPLINGS)
                || isNaturalGrowth(state.getBlock())) {
            return SurfaceColumn.Cell.CANOPY;
        }
        return SurfaceColumn.Cell.ROOF;
    }

    private static boolean isNaturalGrowth(Block block) {
        return block == Blocks.VINE
                || block == Blocks.COCOA
                || block == Blocks.BEE_NEST
                || block == Blocks.BEEHIVE
                || block == Blocks.MANGROVE_ROOTS
                || block == Blocks.MUDDY_MANGROVE_ROOTS
                || block == Blocks.MANGROVE_PROPAGULE
                || block == Blocks.MOSS_CARPET
                || block == Blocks.PALE_MOSS_CARPET
                || block == Blocks.PALE_HANGING_MOSS
                || block == Blocks.LEAF_LITTER
                || block == Blocks.PINK_PETALS
                || block == Blocks.SNOW
                || block == Blocks.BROWN_MUSHROOM_BLOCK
                || block == Blocks.RED_MUSHROOM_BLOCK
                || block == Blocks.MUSHROOM_STEM
                || block == Blocks.BROWN_MUSHROOM
                || block == Blocks.RED_MUSHROOM
                || block == Blocks.CRIMSON_FUNGUS
                || block == Blocks.WARPED_FUNGUS
                || block == Blocks.SHROOMLIGHT
                || block == Blocks.SUGAR_CANE
                || block == Blocks.BAMBOO
                || block == Blocks.BAMBOO_SAPLING;
    }

    /**
     * True when the column above {@code pos} (from the cell just above it to the top of the world) holds
     * no roof. {@code pos} is a bot's feet cell or a candidate torch cell; either way the cell above it
     * is the head/ceiling cell that decides.
     */
    public static boolean isOnSurface(World world, BlockPos pos) {
        int top = world.getBottomY() + world.getHeight();
        Iterator<SurfaceColumn.Cell> column = new Iterator<>() {
            private final BlockPos.Mutable cursor = pos.mutableCopy();
            private int y = pos.getY() + 1;

            @Override
            public boolean hasNext() {
                return y < top;
            }

            @Override
            public SurfaceColumn.Cell next() {
                if (y >= top) {
                    throw new NoSuchElementException();
                }
                cursor.setY(y++);
                return classify(world.getBlockState(cursor));
            }
        };
        return SurfaceColumn.isOpenToSky(column);
    }
}
