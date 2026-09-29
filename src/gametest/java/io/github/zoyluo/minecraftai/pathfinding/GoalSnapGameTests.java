package io.github.zoyluo.minecraftai.pathfinding;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

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
 * These tests exercise that method directly (a real {@code ServerWorld} is required, so this
 * cannot be a plain unit test) rather than going through full A* connectivity, which the water
 * case does not have (crossing open water is not a legal walk move, by design).
 */
public final class GoalSnapGameTests {
    @GameTest(maxTicks = 20)
    public void goalOverAPitSnapsToTheNearbySurfaceNotThePitFloor(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos platformFeet = context.getAbsolutePos(new BlockPos(4, 40, 4));
        int floorY = preparePlatform(world, platformFeet, 8);

        // A 1-wide mineshaft, 40 blocks deep -- well past both the near window and the 24-block
        // deep-fallback bound -- directly under the goal's own XZ, exactly the log's own shape.
        BlockPos pitRequested = platformFeet.add(3, 0, 0);
        for (int dy = 1; dy <= 40; dy++) {
            world.setBlockState(pitRequested.down(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
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

        context.complete();
    }

    @GameTest(maxTicks = 20)
    public void goalOverDeepWaterNeverResolvesOntoTheLakeBed(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos lakeTopWater = context.getAbsolutePos(new BlockPos(4, 40, 4));
        int radius = 10; // wider than the 8-block search radius, so no shore is ever in reach
        int waterDepth = 6; // shallower than the 24-block deep bound, so only the fluid guard stops the old dive
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos top = lakeTopWater.add(dx, 0, dz);
                for (int dy = 0; dy < waterDepth; dy++) {
                    world.setBlockState(top.down(dy), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                }
                // Dry lake bed directly under the water -- reachable by the old unguarded column
                // scan (well within its 128-block range), and even within this fix's 24-block deep
                // bound, so only the explicit fluid refusal (not the depth bound) protects it.
                world.setBlockState(top.down(waterDepth), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(top.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        // Standability memoizes per absolute position; the previous test in this batch reused the
        // same cells with different blocks, and only AStarPathfinder.findPath (not this direct call)
        // clears the cache first.
        Standability.clearCache();
        Optional<Standability.SnappedGoal> snapped = Standability.findNearestStandableForGoal(
                world, lakeTopWater, 8, 4, 3, 24);
        require(context, snapped.isEmpty(),
                "a goal over open water must never resolve onto the lake bed, got " + snapped + " origin=" + lakeTopWater + " state=" + world.getBlockState(lakeTopWater) + " below=" + world.getBlockState(lakeTopWater.down()) + " up=" + world.getBlockState(lakeTopWater.up()));

        context.complete();
    }

    @GameTest(maxTicks = 20)
    public void goalOverWaterNextToAShoreSnapsToTheShoreNotTheLakeBed(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos shoreFeet = context.getAbsolutePos(new BlockPos(4, 40, 4));
        preparePlatform(world, shoreFeet, 8);
        // A lake filling the platform's eastern half, level with the shore floor's top.
        BlockPos waterGoal = shoreFeet.add(4, -1, 0);
        for (int dx = 2; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos top = shoreFeet.add(dx, -1, dz);
                for (int dy = 0; dy < 6; dy++) {
                    world.setBlockState(top.down(dy), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                }
                world.setBlockState(top.down(6), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
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
        context.complete();
    }

    @GameTest(maxTicks = 20)
    public void goalHighAboveTheGroundStillFallsBackToTheDeepPhase(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos floorFeet = context.getAbsolutePos(new BlockPos(4, 40, 4));
        preparePlatform(world, floorFeet, 8);
        // Well above the NEAR window's 4-down reach, but inside the 24-block deep bound.
        BlockPos highGoal = floorFeet.up(12);
        Standability.clearCache();
        Optional<Standability.SnappedGoal> snapped = Standability.findNearestStandableForGoal(
                world, highGoal, 8, 4, 3, 24);
        require(context, snapped.isPresent(), "expected the deep fallback to find the floor below the goal");
        require(context, snapped.get().phase() == Standability.Phase.DEEP,
                "expected the DEEP phase, got " + snapped.get());
        require(context, snapped.get().pos().getY() == floorFeet.getY(),
                "deep phase should land on the floor level, got " + snapped.get().pos());
        context.complete();
    }

    /** Flat, open, solid-floored platform. @return the floor's Y (one below {@code feet}'s Y). */
    private static int preparePlatform(ServerWorld world, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        return feet.getY() - 1;
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}
