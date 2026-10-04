package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.baritone.BaritoneNavigator;
import io.github.zoyluo.minecraftai.baritone.BaritonePlanner;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Move-to (and the ActionPack path API underneath it) on the Baritone engine, set for the walking bot only: walls, steps, pits and
 * doors on the way, the legacy result vocabulary ({@code path_complete}, {@code pathfinding_failed: GOAL_UNREACHABLE},
 * cancelled), cancelling with {@code stopAll}, the bot being removed in the middle of a route, and the dry-route rule.
 * The global engine stays legacy; one test flips it through the config to show the config switch reaches the seam.
 */
public final class BaritoneEngineMoveGameTests {
    private static final double AT_GOAL = 1.7D;

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A three-layer snow surface leaves a real player physically in the snow block, while its
     * Baritone grid position must be the air cell above it. The course begins with one layer,
     * then crosses into three layers, so both partial-support cases share one navigation model.
     * The remote coordinate below is only a heading: the assertion on {@code hop} proves strict
     * directional pursuit chose and reached a local observed destination without breaking or
     * placing any part of the course.
     */
    @GameTest(maxTicks = 500)
    public void directionalPursuitCrossesThreeLayerSnowWithoutBreakingOrPlacing(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 20, 14, 5);
        BlockState shallowSnow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1);
        BlockState deepSnow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3);
        // The shared arena normally uses level-15 ceiling lights.  Vanilla melts snow layers
        // under that light during scheduled ticks, including cells far from the route, which
        // makes this no-world-edit assertion depend on the scheduler rather than Baritone.
        // This course uses only direct line-of-sight observation, so it needs no artificial
        // lighting; clear the fixture lights before placing the snow surface.
        for (int dx = -14; dx <= 14; dx += 4) {
            for (int dz = -5; dz <= 5; dz += 4) {
                arena.world.setBlock(arena.cell(dx, 3, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // Keep the 1 -> 3 layer boundary immediately ahead of the bot.  This makes the
        // regression specifically about normalising the two partial supports to the same
        // air-cell grid, rather than relying on a long, separately-observed shallow-snow lane.
        int firstDeepSnowX = -7;
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                arena.world.setBlock(arena.cell(dx, 0, dz), dx >= firstDeepSnowX ? deepSnow : shallowSnow, Block.UPDATE_ALL);
            }
        }
        BlockPos rawShallowSnowCell = arena.cell(-8, 0, 0);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveSnow", rawShallowSnowCell.above());
        ActionPack pack = bot.getActionPack();
        BlockPos remoteHeading = arena.cell(30, 1, 0);
        BlockPos[] hop = {null};
        boolean[] requested = {false};
        boolean[] launched = {false};
        boolean[] occupiedDeepSnow = {false};
        double[] startX = {Double.NaN};
        double[] furthestX = {Double.NEGATIVE_INFINITY};
        int[] tick = {0};
        int[] requestTick = {-1};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 480, "snow directional pursuit never settled: " + bot.position());
            if (!requested[0]) {
                // Wait until vanilla collision has placed the bot inside the shallow snow cell.
                // The route must then make the transition into the three-layer surface.
                if (!bot.blockPosition().equals(rawShallowSnowCell)) {
                    return;
                }
                startX[0] = bot.getX();
                ActionResult started = pack.startDirectionalPursuitTo(remoteHeading, 8, false, false);
                arena.require(started.isInProgress(),
                        "strict snow pursuit was not accepted: " + started.status() + " " + started.reason());
                // Baritone publishes CALC_STARTED after this server-tick callback. Calling
                // hasBaritoneRoute() while that hand-off is still in flight can settle the
                // just-admitted route as an early-ended search, so inspect the local goal on
                // the next driven tick instead.
                requested[0] = true;
                requestTick[0] = now;
                return;
            }
            if (!launched[0]) {
                if (now <= requestTick[0] + 1) {
                    return;
                }
                arena.require(pack.hasBaritoneRoute(),
                        "accepted snow pursuit stopped before Baritone took it over: " + pack.lastRouteOutcome());
                hop[0] = pack.activePathGoal();
                arena.require(hop[0] != null && hop[0].getX() >= arena.origin.getX() + firstDeepSnowX
                                && hop[0].getY() == rawShallowSnowCell.getY() + 1,
                        "pursuit did not resolve a forward snow-top hop: " + hop[0]);
                launched[0] = true;
                return;
            }
            furthestX[0] = Math.max(furthestX[0], bot.getX());
            if (bot.blockPosition().getX() >= arena.origin.getX() + firstDeepSnowX
                    && bot.blockPosition().getY() == rawShallowSnowCell.getY()) {
                occupiedDeepSnow[0] = true;
            }
            if (pack.hasBaritoneRoute()) {
                return;
            }
            NavOutcome outcome = pack.lastRouteOutcome();
            arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS
                            && outcome.goal().equals(hop[0]),
                    "snow directional pursuit did not reach its local hop: " + outcome);
            arena.require(furthestX[0] > startX[0] + 2.0D,
                    "bot never made a meaningful forward move over snow: " + furthestX[0] + " from " + startX[0]);
            arena.require(occupiedDeepSnow[0],
                    "bot never physically entered the three-layer snow surface");
            arena.require(bot.position().distanceTo(hop[0].getCenter()) <= AT_GOAL,
                    "bot did not arrive at the observed snow hop: " + bot.position() + " -> " + hop[0]);
            for (int dx = -14; dx <= 14; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockState surface = arena.world.getBlockState(arena.cell(dx, 0, dz));
                    if (dx >= firstDeepSnowX) {
                        arena.require(surface.equals(deepSnow),
                                "strict pursuit broke or changed snow at " + dx + "," + dz);
                    } else {
                        arena.require(surface.equals(shallowSnow),
                                "strict pursuit broke or changed shallow snow at " + dx + "," + dz);
                    }
                    arena.require(arena.world.getBlockState(arena.cell(dx, 1, dz)).isAir(),
                            "strict pursuit placed a block above snow at " + dx + "," + dz);
                }
            }
            arena.finish(bot);
        });
    }

    @GameTest(maxTicks = 600)
    public void moveDoorOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 13, 12, 7);
        arena.bedrockWall(0, -7, 7);
        arena.fill(0, 0, Blocks.AIR, 0, BaritoneEngineArena.CEILING);
        arena.closedDoor(0, 0);
        BlockPos door = arena.cell(0, 0, 0);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveDoor", arena.cell(-7, 0, 0));
        BlockPos goal = arena.cell(7, 0, 0);
        MoveTask move = assignMove(bot, goal, "gametest_baritone_move_door");
        boolean[] stoodInDoorway = {false};
        int[] settling = {0};
        context.failIfEver(() -> {
            if (Math.abs(bot.getX() - (door.getX() + 0.5D)) < 0.6D && Math.abs(bot.getZ() - (door.getZ() + 0.5D)) < 0.6D) {
                stoodInDoorway[0] = true;
            }
            if (move.state() == TaskState.COMPLETED) {
                if (bot.getActionPack().hasBaritoneRoute()) {
                    arena.require(++settling[0] < 80, "the route never settled after the task completed");
                    return;
                }
                BlockState after = arena.world.getBlockState(door);
                arena.require(after.getBlock() instanceof DoorBlock, "the door is gone: " + after);
                arena.require(stoodInDoorway[0], "the bot never stood in the doorway");
                arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
                requireSuccessOutcome(arena, bot);
                arena.finish(bot);
                return;
            }
            arena.require(move.state() == TaskState.RUNNING, "move ended: " + move.state() + " " + move.failureReason());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static MoveTask assignMove(AIPlayerEntity bot, BlockPos goal, String reason) {
        MoveTask move = new MoveTask(bot, goal);
        TaskManager.INSTANCE.assign(bot, move, TaskOrigin.of(TaskOrigin.Kind.VERIFY, reason));
        return move;
    }

    private static void requireSuccessOutcome(BaritoneEngineArena arena, AIPlayerEntity bot) {
        NavOutcome outcome = bot.getActionPack().lastRouteOutcome();
        arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS && outcome.ticks() > 0,
                "the Baritone route was not recorded as a success: " + outcome);
        arena.require(BaritoneRegistry.INSTANCE.find(bot.getUUID()) != null, "the bot has no Baritone instance");
    }

}
