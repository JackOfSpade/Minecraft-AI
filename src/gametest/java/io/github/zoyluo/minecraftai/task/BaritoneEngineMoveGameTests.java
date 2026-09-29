package io.github.zoyluo.minecraftai.task;

import baritone.api.IBaritone;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.baritone.BaritoneNavigator;
import io.github.zoyluo.minecraftai.baritone.BaritonePlanner;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
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

    @GameTest(maxTicks = 500)
    public void moveWallDetourOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 10, 14, 9);
        for (int dz = -9; dz <= 5; dz++) {
            arena.fill(0, dz, Blocks.STONE, 0, 1);
        }
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveWall", arena.cell(-7, 0, 0));
        BlockPos goal = arena.cell(7, 0, 0);
        MoveTask move = assignMove(bot, goal, "gametest_baritone_move_wall");
        double[] maxZ = {Double.NEGATIVE_INFINITY};
        int[] settling = {0};
        context.failIfEver(() -> {
            maxZ[0] = Math.max(maxZ[0], bot.getZ());
            if (move.state() == TaskState.COMPLETED) {
                if (bot.getActionPack().hasBaritoneRoute()) {
                    arena.require(++settling[0] < 80, "the route never settled after the task completed");
                    return;
                }
                for (int dz = -9; dz <= 5; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        arena.require(arena.world.getBlockState(arena.cell(0, dy, dz)).is(Blocks.STONE), "the bot broke through the wall at dz=" + dz);
                    }
                }
                arena.require(maxZ[0] >= arena.origin.getZ() + 5.5D, "the bot never used the gap, max z offset " + (maxZ[0] - arena.origin.getZ()));
                arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
                requireSuccessOutcome(arena, bot);
                arena.finish(bot);
                return;
            }
            arena.require(move.state() == TaskState.RUNNING, "move ended: " + move.state() + " " + move.failureReason());
        });
    }

    @GameTest(maxTicks = 400)
    public void moveStepUpOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 11, 12, 6);
        for (int dx = 2; dx <= 12; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                arena.set(dx, 0, dz, Blocks.STONE);
            }
        }
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveStep", arena.cell(-7, 0, 0));
        BlockPos goal = arena.cell(8, 1, 0);
        MoveTask move = assignMove(bot, goal, "gametest_baritone_move_step");
        double[] maxY = {Double.NEGATIVE_INFINITY};
        int[] settling = {0};
        context.failIfEver(() -> {
            maxY[0] = Math.max(maxY[0], bot.getY());
            if (move.state() == TaskState.COMPLETED) {
                if (bot.getActionPack().hasBaritoneRoute()) {
                    arena.require(++settling[0] < 80, "the route never settled after the task completed");
                    return;
                }
                arena.require(maxY[0] >= arena.origin.getY() + 0.95D, "the bot never stood on the step");
                arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
                requireSuccessOutcome(arena, bot);
                arena.finish(bot);
                return;
            }
            arena.require(move.state() == TaskState.RUNNING, "move ended: " + move.state() + " " + move.failureReason());
        });
    }

    @GameTest(maxTicks = 500)
    public void movePitOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 12, 14, 9);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                arena.fill(dx, dz, Blocks.AIR, -3, -1);
            }
        }
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMovePit", arena.cell(-8, 0, 0));
        BlockPos goal = arena.cell(8, 0, 0);
        float health = bot.getHealth();
        MoveTask move = assignMove(bot, goal, "gametest_baritone_move_pit");
        int[] settling = {0};
        context.failIfEver(() -> {
            arena.require(bot.getY() >= arena.origin.getY() - 0.4D, "the bot went down into the pit: " + bot.position());
            if (move.state() == TaskState.COMPLETED) {
                if (bot.getActionPack().hasBaritoneRoute()) {
                    arena.require(++settling[0] < 80, "the route never settled after the task completed");
                    return;
                }
                arena.require(bot.getHealth() >= health, "the bot lost health: " + health + " -> " + bot.getHealth());
                arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
                requireSuccessOutcome(arena, bot);
                arena.finish(bot);
                return;
            }
            arena.require(move.state() == TaskState.RUNNING, "move ended: " + move.state() + " " + move.failureReason());
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

    @GameTest(maxTicks = 300)
    public void moveStopAllCancelsTheBaritoneRoute(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 14, 24, 6);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveStop", arena.cell(-20, 0, 0));
        BlockPos goal = arena.cell(20, 0, 0);
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startPathTo(goal);
        arena.require(started.isInProgress(), "the route was not accepted: " + started.status() + " " + started.reason());
        arena.require(pack.hasBaritoneRoute(), "the accepted route is not recorded");
        int[] tick = {0};
        int[] stoppedAt = {-1};
        Vec3Holder position = new Vec3Holder();
        int[] settling = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            if (stoppedAt[0] < 0) {
                if (now == 40) {
                    arena.require(BaritoneRegistry.INSTANCE.isBusy(bot) && bot.getX() > arena.origin.getX() - 19.0D,
                            "fixture: Baritone is not driving the bot yet at tick 40");
                    pack.stopAll();
                    stoppedAt[0] = now;
                    position.value = bot.position();
                    arena.require(!BaritoneRegistry.INSTANCE.isBusy(bot), "Baritone still busy after stopAll");
                    NavOutcome outcome = pack.lastRouteOutcome();
                    arena.require(outcome != null && outcome.status() == NavOutcome.Status.CANCELLED && outcome.reason().contains("stop_all"),
                            "the route was not recorded as cancelled by stop_all: " + outcome);
                    arena.require(!pack.hasBaritoneRoute() && pack.isPathExecutorIdle(), "the pack still shows a route");
                }
                return;
            }
            arena.require(!BaritoneRegistry.INSTANCE.isBusy(bot), "Baritone took the bot again at tick " + now);
            arena.require(bot.zza == 0.0F && bot.xxa == 0.0F, "input left behind: zza=" + bot.zza + " xxa=" + bot.xxa);
            arena.require(pack.lastRouteOutcome().status() == NavOutcome.Status.CANCELLED, "the outcome changed after the cancel");
            if (now == stoppedAt[0] + 40) {
                double drift = bot.position().distanceTo(position.value);
                arena.require(drift < 1.6D, "the stopped bot kept walking: " + drift);
                arena.finish(bot);
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void moveBotRemovedMidRouteLeavesNothingBehind(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 15, 24, 6);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveGone", arena.cell(-20, 0, 0));
        java.util.UUID botId = bot.getUUID();
        assignMove(bot, arena.cell(20, 0, 0), "gametest_baritone_move_removed");
        int[] tick = {0};
        int[] removedAt = {-1};
        IBaritone[] instance = {null};
        int[] settling = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            if (removedAt[0] < 0) {
                if (now == 40) {
                    arena.require(BaritoneRegistry.INSTANCE.find(botId) != null && BaritoneRegistry.INSTANCE.isBusy(bot),
                            "fixture: Baritone is not driving the bot at tick 40");
                    instance[0] = BaritoneRegistry.INSTANCE.find(botId);
                    AIPlayerManager.INSTANCE.despawn(arena.world.getServer(), "BeMoveGone");
                    removedAt[0] = now;
                }
                return;
            }
            arena.require(BaritoneRegistry.INSTANCE.find(botId) == null, "the removed bot's Baritone instance is still registered");
            arena.require(!BaritoneNavigator.hasRoute(botId), "the navigator still counts a route of the removed bot");
            if (now == removedAt[0] + 60) {
                arena.require(!BaritonePlanner.isInFlight(instance[0]), "a search of the removed bot is still in flight");
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void moveToSealedGoalIsRefusedWithTheLegacyReason(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 16, 9, 4);
        // The goal cell sits in a bedrock box (nothing to walk over, nothing that can be broken).
        for (int dx = 3; dx <= 7; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    boolean inside = dx == 5 && dz == 0 && dy <= 1;
                    arena.set(dx, dy, dz, inside ? Blocks.AIR : Blocks.BEDROCK);
                }
            }
        }
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveSealed", arena.cell(-7, 0, 0));
        ActionPack pack = bot.getActionPack();
        ActionResult result = pack.startPathTo(arena.cell(5, 0, 0));
        arena.require(result.isFailed() && result.reason().equals("pathfinding_failed: GOAL_UNREACHABLE"),
                "an unreachable goal must answer with the legacy reason, got " + result.status() + " '" + result.reason() + "'");
        arena.require(pack.isPathExecutorIdle() && !pack.hasBaritoneRoute(), "a refused request left a route behind");
        arena.require(!BaritoneRegistry.INSTANCE.isBusy(bot), "a refused request left Baritone busy");
        // The identical request inside the cooldown is answered like the legacy navigator does.
        ActionResult again = pack.startPathTo(arena.cell(5, 0, 0));
        arena.require(again.isFailed() && ActionPack.PATHFINDING_THROTTLED.equals(again.reason()),
                "the repeat of a refused request must be throttled, got " + again.reason());
        arena.finish(bot);
    }

    @GameTest(maxTicks = 700)
    public void moveAcrossWaterTakesTheDryBridge(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 17, 14, 8);
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = -8; dz <= 7; dz++) {
                arena.set(dx, -1, dz, Blocks.WATER);
            }
        }
        AIPlayerEntity bot = arena.spawnOnBaritone("BeMoveBridge", arena.cell(-9, 0, 0));
        BlockPos goal = arena.cell(9, 0, 0);
        MoveTask move = assignMove(bot, goal, "gametest_baritone_move_bridge");
        double[] maxZ = {Double.NEGATIVE_INFINITY};
        int[] settling = {0};
        context.failIfEver(() -> {
            arena.require(!bot.isInWater() && bot.getY() >= arena.origin.getY() - 0.5D, "the bot swam although a dry bridge existed: " + bot.position());
            maxZ[0] = Math.max(maxZ[0], bot.getZ());
            if (move.state() == TaskState.COMPLETED) {
                if (bot.getActionPack().hasBaritoneRoute()) {
                    arena.require(++settling[0] < 80, "the route never settled after the task completed");
                    return;
                }
                arena.require(maxZ[0] >= arena.origin.getZ() + 7.5D, "the bot never used the bridge, max z offset " + (maxZ[0] - arena.origin.getZ()));
                arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
                requireSuccessOutcome(arena, bot);
                arena.finish(bot);
                return;
            }
            arena.require(move.state() == TaskState.RUNNING, "move ended: " + move.state() + " " + move.failureReason());
        });
    }

    /** The config switch itself: with {@code nav.engine=baritone} in the config a bot without an override is routed by Baritone. */
    @GameTest(maxTicks = 400)
    public void configEngineSwitchRoutesAnOrdinaryBotThroughBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 18, 14, 6);
        AIPlayerEntity bot = arena.spawn("BeMoveConfig", arena.cell(-9, 0, 0));
        BlockPos goal = arena.cell(9, 0, 0);
        ActionPack pack = bot.getActionPack();
        MinecraftAiConfig original = MinecraftAiConfig.get();
        ActionResult started;
        try {
            setConfig(withEngine(original, NavEngine.BARITONE));
            started = pack.startPathTo(goal);
        } finally {
            setConfig(original); // the route below does not consult the engine again
        }
        arena.require(started.isInProgress() && pack.hasBaritoneRoute(), "the configured Baritone engine did not take the request: " + started.status() + " " + started.reason());
        int[] tick = {0};
        int[] settling = {0};
        context.failIfEver(() -> {
            tick[0]++;
            arena.require(tick[0] < 380, "the route never finished: " + bot.position());
            if (!pack.hasBaritoneRoute()) {
                NavOutcome outcome = pack.lastRouteOutcome();
                arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS, "the route did not succeed: " + outcome);
                arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
                arena.finish(bot);
            }
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

    private static MinecraftAiConfig withEngine(MinecraftAiConfig config, NavEngine engine) {
        MinecraftAiConfig.Nav nav = config.nav();
        MinecraftAiConfig.Nav chosen = new MinecraftAiConfig.Nav(nav.jumpReach(), nav.sidleAfter(), nav.sidleLimit(), nav.hardLimit(),
                nav.lookahead(), nav.nodeRetry(), nav.sprintMinDist(), nav.maxSafeFall(), engine.configValue());
        return new MinecraftAiConfig(config.profile(), config.operatorCapabilities(), config.llm(), config.perception(), config.brain(),
                config.watchdog(), config.logging(), config.survival(), config.combat(), config.night(), config.mining(), config.goal(),
                chosen, config.pickup(), config.conversation());
    }

    private static void setConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install GameTest config", exception);
        }
    }

    private static final class Vec3Holder {
        net.minecraft.world.phys.Vec3 value;
    }
}
