package io.github.zoyluo.minecraftai.pathfinding;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Regression coverage for fluid cells with an empty collision shape. */
public final class NeighborEnumeratorFluidSafetyGameTests {
    @GameTest(maxTicks = 20)
    public void diagonalDoesNotCutThroughAFluidCorner(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 40, 4));
        clear(world, start, 3, 3, 3);
        floor(world, start);
        BlockPos diagonal = start.north().east();
        floor(world, diagonal);
        // The north column is a collision-free water column. The diagonal target itself is dry.
        BlockPos fluidCorner = start.north();
        world.setBlock(fluidCorner.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(fluidCorner, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        List<NeighborCandidate> candidates = new NeighborEnumerator(null, false, false).getNeighbors(start, world);
        require(context, candidates.stream().noneMatch(candidate ->
                        candidate.pos().equals(diagonal) && candidate.moveType() == MoveType.DIAGONAL),
                "a dry diagonal target was reachable by cutting through a water corner");
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void dropDoesNotEnterAFluidColumn(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(4, 40, 4));
        clear(world, start, 3, 3, 4);
        floor(world, start);
        BlockPos target = start.east();
        // The old collision-only drop scan treated this as empty and offered the dry landing below.
        world.setBlock(target, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(target.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(target.below(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        List<NeighborCandidate> candidates = new NeighborEnumerator(null, false, false).getNeighbors(start, world);
        require(context, candidates.stream().noneMatch(candidate ->
                        candidate.pos().equals(target.below()) && candidate.moveType() == MoveType.DROP_DOWN),
                "a drop into a water column was offered as ordinary dry navigation");
        context.succeed();
    }

    private static void clear(ServerLevel world, BlockPos origin, int horizontal, int above, int below) {
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dz = -horizontal; dz <= horizontal; dz++) {
                for (int dy = -below; dy <= above; dy++) {
                    world.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void floor(ServerLevel world, BlockPos feet) {
        world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
