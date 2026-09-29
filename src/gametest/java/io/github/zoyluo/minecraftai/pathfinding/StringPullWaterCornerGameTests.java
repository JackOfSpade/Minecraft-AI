package io.github.zoyluo.minecraftai.pathfinding;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * A string-pulled shortcut across two route nodes must never clip the corner column of a water cell.
 *
 * <p>The follow no-route fixture had a one-block stone cap at each end of a water strip (a real dry
 * bridge). The bot walked it, and now and then its pulled shortcut from the cap's first cell straight to
 * the platform cell beyond the water's far corner cut through the (water-floored) corner column: the old
 * check sampled the line twice per block, both samples missed that column, and the bot's centre ended up
 * over the water. The exact traversal ({@link StringPullLine}) sees every column the line enters.
 *
 * <p>Layout (x east, z south, level with the floor; W = water floor, S = stone floor, . = void):
 * <pre>
 *        x: 0 1 2
 *   z = -1  . W S      the column (1, -1) is the water corner
 *   z =  0  S S S      the bridge along z = 0 is dry
 * </pre>
 * The pull from (0, 0) to (2, -1) leaves column (1, 0) and dips through the water column (1, -1) before it
 * reaches (2, -1): refused. The old sampler (two samples per block, floor() of the sample point) landed on
 * z = 0.0 exactly at the middle of this line and read it as column z = 0, so it never looked at (1, -1)
 * (the mirrored line towards +z is sampled correctly, so the flaw showed only in some directions).
 *
 * <p>The same trip in legal pieces (east along the bridge, then a step north) is accepted, and so is the exact
 * diagonal (1, 0) to (2, -1), whose centre line passes through the lattice point and never through the water
 * column (its brushed neighbours are dry at feet and head). The same diagonal is refused once a brushed column
 * holds feet-level water: the 0.6-wide body would touch it.
 */
public final class StringPullWaterCornerGameTests {
    @GameTest(maxTicks = 20)
    public void aShortcutThroughTheCornerColumnOfAWaterCellIsRefused(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(4, 6, 4));
        // Clear the working volume, then lay the floor level with origin.below().
        for (int dx = -1; dx <= 3; dx++) {
            for (int dz = -2; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (int dx = 0; dx <= 2; dx++) {
            world.setBlock(origin.offset(dx, -1, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(origin.offset(1, -1, -1), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.offset(1, -2, -1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(origin.offset(2, -1, -1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        BlockPos start = origin;
        BlockPos beyondTheCorner = origin.offset(2, 0, -1);
        require(context, Standability.isStandable(world, start) && Standability.isStandable(world, beyondTheCorner),
                "fixture: both ends of the shortcut must be dry standable footing");
        require(context, !PathExecutor.lineClearForStringPull(world, start, beyondTheCorner),
                "a shortcut whose line enters the water-floored corner column was accepted");

        // The same trip in legal pieces: along the bridge, then a step north off its far end.
        require(context, PathExecutor.lineClearForStringPull(world, start, origin.offset(2, 0, 0)),
                "a straight run along the dry bridge was refused");
        require(context, PathExecutor.lineClearForStringPull(world, origin.offset(2, 0, 0), beyondTheCorner),
                "a straight step onto the dry footing beside the bridge was refused");
        // The exact diagonal only brushes the water corner (its centre line passes through the lattice
        // point): the pre-existing diagonal rule (both corner columns passable) applies unchanged.
        require(context, PathExecutor.lineClearForStringPull(world, origin.offset(1, 0, 0), beyondTheCorner),
                "an exact diagonal step past the water corner was refused");
        // A brushed column is not allowed to hold water either: a shallow (feet-level) water cell beside
        // the exact diagonal would touch the 0.6-wide body, so the same diagonal is now refused. The
        // check is synchronous (no fluid tick runs), and the cell is put back to air right away.
        BlockPos brushed = origin.offset(1, 0, -1);
        world.setBlock(brushed, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        boolean diagonalBesideShallowWater = PathExecutor.lineClearForStringPull(world, origin.offset(1, 0, 0), beyondTheCorner);
        world.setBlock(brushed, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        require(context, !diagonalBesideShallowWater,
                "an exact diagonal brushing a water column (feet-level water) was accepted");
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
