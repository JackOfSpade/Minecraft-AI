package io.github.zoyluo.minecraftai.pathfinding;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.Optional;

/**
 * Regression coverage for the 2026-09-28 session-log bug (see {@code FollowTaskGameTests} for the
 * follow-level symptom): {@code Standability.findNearestStandable} used to search a goal's own
 * column as deep as 128 blocks down before ever trying a lateral cell, so a goal offered just
 * above a pit/mineshaft resolved into the pit, and a goal offered just above open water resolved
 * onto the lake bed (live evidence: {@code findpath_goal_snapped from=93,63,136 to=93,-18,136},
 * each followed by two TIMEOUT A* searches at the node budget once the goal was underground/
 * underwater). {@link Standability#findNearestStandableForGoal} is the fix: a small nearby window
 * is tried first, and only a bounded, fluid-refusing deep fallback runs if that finds nothing.
 * These tests exercise that method directly (a real {@code ServerLevel} is required, so this
 * cannot be a plain unit test) rather than going through full A* connectivity, which the water
 * case does not have (crossing open water is not a legal walk move, by design).
 */
public final class GoalSnapGameTests {
    @GameTest(maxTicks = 20)
    public void goalOverAPitSnapsToTheNearbySurfaceNotThePitFloor(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos platformFeet = context.absolutePos(new BlockPos(4, 40, 4));
        int floorY = preparePlatform(world, platformFeet, 8);

        // A 1-wide mineshaft, 40 blocks deep -- well past both the near window and the 24-block
        // deep-fallback bound -- directly under the goal's own XZ, exactly the log's own shape.
        BlockPos pitRequested = platformFeet.offset(3, 0, 0);
        for (int dy = 1; dy <= 40; dy++) {
            world.setBlock(pitRequested.below(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        // Standability memoizes per absolute position; the previous test in this batch reused the
        // same cells with different blocks, and only AStarPathfinder.findPath (not this direct call)
        // clears the cache first.
        Standability.clearCache();
        Optional<Standability.SnappedGoal> snapped = Standability.findNearestStandableForGoal(
                world, pitRequested, 8, 4, 3, 24);
        require(context, snapped.isPresent(),
                "expected a nearby standable snap next to the pit, found none");
        require(context, snapped.get().phase() == Standability.Phase.NEAR,
                "a lateral platform cell one block away should be found by the NEAR window, got phase="
                        + snapped.get().phase() + " pos=" + snapped.get().pos());
        int snappedY = snapped.get().pos().getY();
        require(context, snappedY >= floorY - 1,
                "snapped too deep toward the pit floor: y=" + snappedY + " (platform floor y=" + floorY + ")");

        // End-to-end wiring check: AStarPathfinder.resolveEndpoint must actually use the fix (not
        // just Standability having it available), and the resulting route must stay near the
        // platform rather than descending into the shaft.
        AStarPathfinder finder = new AStarPathfinder(world, platformFeet, pitRequested,
                10_000, 2_000L, false, false);
        PathfindingResult result = finder.findPath();
        require(context, result.success(), "expected the walk-only search to reach the snapped goal: " + result.reason());
        require(context, result.resolvedGoal() != null && result.resolvedGoal().getY() >= floorY - 1,
                "AStarPathfinder resolved the goal too deep into the pit: " + result.resolvedGoal());

        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void goalOverDeepWaterNeverResolvesOntoTheLakeBed(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos lakeTopWater = context.absolutePos(new BlockPos(4, 40, 4));
        int radius = 10; // wider than the 8-block search radius, so no shore is ever in reach
        int waterDepth = 6; // shallower than the 24-block deep bound, so only the fluid guard stops the old dive
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos top = lakeTopWater.offset(dx, 0, dz);
                for (int dy = 0; dy < waterDepth; dy++) {
                    world.setBlock(top.below(dy), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
                // Dry lake bed directly under the water -- reachable by the old unguarded column
                // scan (well within its 128-block range), and even within this fix's 24-block deep
                // bound, so only the explicit fluid refusal (not the depth bound) protects it.
                world.setBlock(top.below(waterDepth), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(top.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        // Standability memoizes per absolute position; the previous test in this batch reused the
        // same cells with different blocks, and only AStarPathfinder.findPath (not this direct call)
        // clears the cache first.
        Standability.clearCache();
        Optional<Standability.SnappedGoal> snapped = Standability.findNearestStandableForGoal(
                world, lakeTopWater, 8, 4, 3, 24);
        require(context, snapped.isEmpty(),
                "a goal over open water must never resolve onto the lake bed, got " + snapped + " origin=" + lakeTopWater + " state=" + world.getBlockState(lakeTopWater) + " below=" + world.getBlockState(lakeTopWater.below()) + " up=" + world.getBlockState(lakeTopWater.above()));

        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void goalOverWaterNextToAShoreSnapsToTheShoreNotTheLakeBed(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos shoreFeet = context.absolutePos(new BlockPos(4, 40, 4));
        preparePlatform(world, shoreFeet, 8);
        // A lake filling the platform's eastern half, level with the shore floor's top.
        BlockPos waterGoal = shoreFeet.offset(4, -1, 0);
        for (int dx = 2; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos top = shoreFeet.offset(dx, -1, dz);
                for (int dy = 0; dy < 6; dy++) {
                    world.setBlock(top.below(dy), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
                world.setBlock(top.below(6), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        Standability.clearCache();
        Optional<Standability.SnappedGoal> snapped = Standability.findNearestStandableForGoal(
                world, waterGoal, 8, 4, 3, 24);
        require(context, snapped.isPresent(), "a shore is within reach; expected a snap");
        require(context, snapped.get().pos().getY() >= shoreFeet.getY(),
                "goal over water snapped below the shore level (toward the lake bed): " + snapped.get().pos());
        require(context, snapped.get().pos().getX() <= shoreFeet.getX() + 1,
                "goal over water must snap onto the dry shore, got " + snapped.get().pos());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void goalHighAboveTheGroundStillFallsBackToTheDeepPhase(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos floorFeet = context.absolutePos(new BlockPos(4, 40, 4));
        preparePlatform(world, floorFeet, 8);
        // Well above the NEAR window's 4-down reach, but inside the 24-block deep bound.
        BlockPos highGoal = floorFeet.above(12);
        Standability.clearCache();
        Optional<Standability.SnappedGoal> snapped = Standability.findNearestStandableForGoal(
                world, highGoal, 8, 4, 3, 24);
        require(context, snapped.isPresent(), "expected the deep fallback to find the floor below the goal");
        require(context, snapped.get().phase() == Standability.Phase.DEEP,
                "expected the DEEP phase, got " + snapped.get());
        require(context, snapped.get().pos().getY() == floorFeet.getY(),
                "deep phase should land on the floor level, got " + snapped.get().pos());
        context.succeed();
    }

    /** Flat, open, solid-floored platform. @return the floor's Y (one below {@code feet}'s Y). */
    private static int preparePlatform(ServerLevel world, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return feet.getY() - 1;
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
