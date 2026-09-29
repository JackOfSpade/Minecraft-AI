package io.github.zoyluo.minecraftai.task;

import baritone.api.IBaritone;
import io.github.zoyluo.minecraftai.baritone.BaritoneNavigator;
import io.github.zoyluo.minecraftai.baritone.BaritonePlanner;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Follow on the Baritone engine ({@code nav.engine=baritone}, set for the follower only): the bot follows a still or moving player
 * with {@code GoalNear(player, 3)} instead of walking to a stand-off cell, walks around walls and pits, steps up, opens doors,
 * stops within three blocks, stays dry when the player is across water, and lets go of everything when it is cancelled or removed.
 * The followed player is a legacy-engine bot that either stands still or is moved by the test.
 *
 * <p>The follower's route must be Baritone's: each test requires the task's own count of started Baritone routes and the
 * follower's instance in the registry. Courses are sealed by a bedrock ring (see {@link BaritoneEngineArena}).</p>
 */
public final class BaritoneEngineFollowGameTests {
    /** {@code STOP_DISTANCE} (3.0) plus the arrival slack (0.5) of {@code FollowTask}. */
    private static final double ARRIVED = 3.6D;

    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 500)
    public void followWallDetourOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 0, 14, 9);
        // A two-high stone wall across x = 0 with a gap at the +z end: the walkable detour is the only cheap way.
        for (int dz = -9; dz <= 5; dz++) {
            arena.fill(0, dz, Blocks.STONE, 0, 1);
        }
        AIPlayerEntity target = arena.spawnHolder("BeFollowWallTgt", arena.cell(7, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowWall", arena.cell(-7, 0, 0));
        FollowTask follow = assignFollow(bot, "BeFollowWallTgt", "gametest_baritone_follow_wall");
        double[] maxZ = {Double.NEGATIVE_INFINITY};
        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            requireRunning(arena, follow);
            maxZ[0] = Math.max(maxZ[0], bot.getZ());
            if (follow.isWaiting() && bot.getX() > arena.origin.getX() + 0.5D && bot.distanceTo(target) <= ARRIVED) {
                for (int dz = -9; dz <= 5; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        arena.require(arena.world.getBlockState(arena.cell(0, dy, dz)).is(Blocks.STONE),
                                "the bot broke through the wall at dz=" + dz + " although a walkable detour existed");
                    }
                }
                arena.require(maxZ[0] >= arena.origin.getZ() + 5.5D, "the bot never used the gap (max z offset " + (maxZ[0] - arena.origin.getZ()) + ")");
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void followStepUpOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 1, 12, 6);
        // The last stretch of the course is one block higher: a step, no stairs.
        for (int dx = 2; dx <= 12; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                arena.set(dx, 0, dz, Blocks.STONE);
            }
        }
        AIPlayerEntity target = arena.spawnHolder("BeFollowStepTgt", arena.cell(7, 1, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowStep", arena.cell(-7, 0, 0));
        FollowTask follow = assignFollow(bot, "BeFollowStepTgt", "gametest_baritone_follow_step");
        double[] maxY = {Double.NEGATIVE_INFINITY};
        context.failIfEver(() -> {
            requireRunning(arena, follow);
            maxY[0] = Math.max(maxY[0], bot.getY());
            if (follow.isWaiting() && bot.distanceTo(target) <= ARRIVED) {
                arena.require(maxY[0] >= arena.origin.getY() + 0.95D, "the bot never stood on the step, max y offset " + (maxY[0] - arena.origin.getY()));
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void followPitOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 2, 14, 9);
        // A 5x5 pit, three deep, right on the line between bot and player (a bot that walks the line falls in and cannot climb out).
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                arena.fill(dx, dz, Blocks.AIR, -3, -1);
            }
        }
        AIPlayerEntity target = arena.spawnHolder("BeFollowPitTgt", arena.cell(8, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowPit", arena.cell(-8, 0, 0));
        float health = bot.getHealth();
        FollowTask follow = assignFollow(bot, "BeFollowPitTgt", "gametest_baritone_follow_pit");
        context.failIfEver(() -> {
            requireRunning(arena, follow);
            arena.require(bot.getY() >= arena.origin.getY() - 0.4D, "the bot went down into the pit: " + bot.position());
            if (follow.isWaiting() && bot.getX() > arena.origin.getX() + 2.5D && bot.distanceTo(target) <= ARRIVED) {
                arena.require(bot.getHealth() >= health, "the bot lost health on the way: " + health + " -> " + bot.getHealth());
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void followDoorOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 3, 12, 7);
        // A bedrock wall across the whole course with one closed wooden door in it: the door is the only way.
        arena.bedrockWall(0, -7, 7);
        arena.fill(0, 0, Blocks.AIR, 0, BaritoneEngineArena.CEILING);
        arena.closedDoor(0, 0);
        BlockPos door = arena.cell(0, 0, 0);
        AIPlayerEntity target = arena.spawnHolder("BeFollowDoorTgt", arena.cell(7, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowDoor", arena.cell(-7, 0, 0));
        FollowTask follow = assignFollow(bot, "BeFollowDoorTgt", "gametest_baritone_follow_door");
        boolean[] stoodInDoorway = {false};
        context.failIfEver(() -> {
            requireRunning(arena, follow);
            if (Math.abs(bot.getX() - (door.getX() + 0.5D)) < 0.6D && Math.abs(bot.getZ() - (door.getZ() + 0.5D)) < 0.6D) {
                stoodInDoorway[0] = true;
            }
            if (follow.isWaiting() && bot.getX() > arena.origin.getX() + 0.5D && bot.distanceTo(target) <= ARRIVED) {
                BlockState after = arena.world.getBlockState(door);
                arena.require(after.getBlock() instanceof DoorBlock, "the door is gone: " + after);
                arena.require(stoodInDoorway[0], "the bot never stood in the doorway");
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 900)
    public void followMovingTargetOnBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 4, 24, 12);
        AIPlayerEntity target = arena.spawnHolder("BeFollowMoveTgt", arena.cell(-14, 0, -8));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowMove", arena.cell(-20, 0, 4));
        FollowTask follow = assignFollow(bot, "BeFollowMoveTgt", "gametest_baritone_follow_moving");
        int[] tick = {0};
        double[] maxGap = {0.0D};
        context.failIfEver(() -> {
            int now = ++tick[0];
            requireRunning(arena, follow);
            // The player walks 20 blocks along +x, then 12 along +z (0.25 blocks per tick, a brisk walk), then stands still.
            double x = -14.0D + Math.min(80.0D, now * 0.25D);
            double z = -8.0D + Math.max(0.0D, Math.min(48.0D, (now - 80) * 0.25D));
            if (now <= 128) {
                target.teleportTo(arena.world, arena.origin.getX() + x + 0.5D, arena.origin.getY(), arena.origin.getZ() + z + 0.5D,
                        java.util.Set.of(), 0.0F, 0.0F, true);
                target.setDeltaMovement(Vec3.ZERO);
            }
            maxGap[0] = Math.max(maxGap[0], bot.distanceTo(target));
            if (now > 140 && follow.isWaiting() && bot.distanceTo(target) <= ARRIVED) {
                arena.require(follow.baritoneRegoals() >= 1, "the route was never re-pointed at the moving player (regoals=" + follow.baritoneRegoals() + ")");
                arena.require(follow.baritoneStarts() + follow.baritoneRegoals() <= 60, "the follower thrashed: starts=" + follow.baritoneStarts()
                        + " regoals=" + follow.baritoneRegoals());
                arena.require(maxGap[0] < 30.0D, "the follower fell far behind: " + maxGap[0]);
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void followArrivesWithinThreeBlocksAndStaysThere(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 5, 14, 8);
        AIPlayerEntity target = arena.spawnHolder("BeFollowArriveTgt", arena.cell(0, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowArrive", arena.cell(11, 0, 5));
        FollowTask follow = assignFollow(bot, "BeFollowArrive" + "Tgt", "gametest_baritone_follow_arrive");
        int[] settledAt = {-1};
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            requireRunning(arena, follow);
            double distance = bot.distanceTo(target);
            if (settledAt[0] < 0) {
                if (follow.isWaiting() && distance <= ARRIVED) {
                    settledAt[0] = now;
                    arena.require(distance >= 2.0D, "the bot stands on top of the player: " + distance);
                }
                return;
            }
            // Settled: it stays put, does not circle and does not keep asking Baritone for zero-length routes.
            arena.require(distance <= ARRIVED && distance >= 2.0D, "the settled follower drifted to " + distance);
            arena.require(follow.isWaiting(), "the settled follower stopped waiting");
            if (now >= settledAt[0] + 60) {
                arena.require(follow.baritoneStarts() <= 4, "arrived follower keeps starting routes: " + follow.baritoneStarts());
                arena.require(bot.getActionPack().isPathExecutorIdle(), "the settled follower still owns a route");
                arena.require(!BaritoneRegistry.INSTANCE.isBusy(bot), "Baritone still drives the settled follower");
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void followCancelStopsBaritoneAndLetsGoOfTheBot(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 6, 24, 6);
        AIPlayerEntity target = arena.spawnHolder("BeFollowCancelTgt", arena.cell(20, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowCancel", arena.cell(-20, 0, 0));
        FollowTask follow = assignFollow(bot, "BeFollowCancelTgt", "gametest_baritone_follow_cancel");
        int[] tick = {0};
        int[] cancelledAt = {-1};
        Vec3[] positionAtCancel = {null};
        context.failIfEver(() -> {
            int now = ++tick[0];
            if (cancelledAt[0] < 0) {
                requireRunning(arena, follow);
                if (now == 40) {
                    arena.require(BaritoneRegistry.INSTANCE.isBusy(bot) && bot.getX() > arena.origin.getX() - 19.0D,
                            "fixture: Baritone is not driving the follower yet at tick 40, x offset " + (bot.getX() - arena.origin.getX()));
                    TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_cancel");
                    cancelledAt[0] = now;
                    positionAtCancel[0] = bot.position();
                    arena.require(!BaritoneRegistry.INSTANCE.isBusy(bot), "Baritone still busy right after the follow was cancelled");
                    NavOutcome outcome = bot.getActionPack().lastRouteOutcome();
                    arena.require(outcome != null && outcome.status() == NavOutcome.Status.CANCELLED,
                            "the cancelled route was not recorded as cancelled: " + outcome);
                    arena.require(bot.getActionPack().isPathExecutorIdle(), "the pack still shows a route");
                }
                return;
            }
            arena.require(!BaritoneRegistry.INSTANCE.isBusy(bot), "Baritone took the bot again at tick " + now);
            arena.require(bot.zza == 0.0F && bot.xxa == 0.0F, "movement input left behind: zza=" + bot.zza + " xxa=" + bot.xxa);
            if (now == cancelledAt[0] + 40) {
                double drift = bot.position().distanceTo(positionAtCancel[0]);
                arena.require(drift < 1.6D, "the cancelled bot kept walking: drifted " + drift + " blocks in 40 ticks");
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void followBotRemovedMidPathLeavesNothingBehind(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 7, 24, 6);
        AIPlayerEntity target = arena.spawnHolder("BeFollowGoneTgt", arena.cell(20, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowGone", arena.cell(-20, 0, 0));
        java.util.UUID botId = bot.getUUID();
        assignFollow(bot, "BeFollowGoneTgt", "gametest_baritone_follow_removed");
        int[] tick = {0};
        int[] removedAt = {-1};
        IBaritone[] instance = {null};
        context.failIfEver(() -> {
            int now = ++tick[0];
            if (removedAt[0] < 0) {
                if (now == 40) {
                    arena.require(BaritoneRegistry.INSTANCE.find(botId) != null && BaritoneRegistry.INSTANCE.isBusy(bot),
                            "fixture: Baritone is not driving the follower at tick 40");
                    instance[0] = BaritoneRegistry.INSTANCE.find(botId);
                    AIPlayerManager.INSTANCE.despawn(arena.world.getServer(), "BeFollowGone");
                    removedAt[0] = now;
                }
                return;
            }
            arena.require(BaritoneRegistry.INSTANCE.find(botId) == null, "the removed bot's Baritone instance is still registered");
            arena.require(!BaritoneNavigator.hasRoute(botId), "the navigator still counts a route of the removed bot");
            if (now == removedAt[0] + 60) {
                arena.require(!BaritonePlanner.isInFlight(instance[0]), "a search of the removed bot is still in flight");
                arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "the removed bot still holds a water lease");
                AIPlayerManager.INSTANCE.despawn(arena.world.getServer(), "BeFollowGoneTgt");
                context.succeed();
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void followTargetAcrossSealedMoatStaysDryOnTheBank(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 8, 14, 7);
        // Water at floor level across the whole course (x = 0..3), no way around it and none over it: no dry route to the player.
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                arena.set(dx, -1, dz, Blocks.WATER);
            }
        }
        AIPlayerEntity target = arena.spawnHolder("BeFollowMoatTgt", arena.cell(9, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowMoat", arena.cell(-10, 0, 0));
        FollowTask follow = assignFollow(bot, "BeFollowMoatTgt", "gametest_baritone_follow_moat");
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(follow.state() == TaskState.RUNNING, "follow ended: " + follow.state() + " " + follow.failureReason());
            arena.require(!bot.isInWater() && bot.getY() >= arena.origin.getY() - 0.5D, "the bot went into the water: " + bot.position());
            arena.require(bot.getX() < arena.origin.getX() + 0.5D, "the bot crossed the moat: " + bot.position());
            if (now >= 300) {
                arena.require(follow.noRouteNotices() >= 1, "the player was never told there is no dry route");
                arena.require(follow.noRouteNotices() <= 2, "the no-route notice repeats: " + follow.noRouteNotices());
                arena.require(bot.getX() >= arena.origin.getX() - 4.0D, "the bot did not come up to the bank, x offset " + (bot.getX() - arena.origin.getX()));
                arena.require(follow.isWaiting(), "the bot waiting at the bank does not report waiting");
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    @GameTest(maxTicks = 800)
    public void followTargetAcrossWaterTakesTheDryBridge(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 9, 14, 8);
        // Water across the middle, except for a one-block land bridge along the +z edge: Baritone would swim straight across, the
        // dry route is the long way round over the bridge.
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = -8; dz <= 7; dz++) {
                arena.set(dx, -1, dz, Blocks.WATER);
            }
        }
        AIPlayerEntity target = arena.spawnHolder("BeFollowBridgeTgt", arena.cell(9, 0, 0));
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFollowBridge", arena.cell(-9, 0, 0));
        FollowTask follow = assignFollow(bot, "BeFollowBridgeTgt", "gametest_baritone_follow_bridge");
        context.failIfEver(() -> {
            requireRunning(arena, follow);
            arena.require(!bot.isInWater() && bot.getY() >= arena.origin.getY() - 0.5D, "the bot swam for a target on land: " + bot.position());
            if (follow.isWaiting() && bot.getX() > arena.origin.getX() + 3.5D && bot.distanceTo(target) <= ARRIVED) {
                requireBaritoneDrove(arena, follow, bot);
                arena.finish(bot, target);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static FollowTask assignFollow(AIPlayerEntity bot, String targetName, String reason) {
        FollowTask follow = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, follow, TaskOrigin.of(TaskOrigin.Kind.VERIFY, reason));
        return follow;
    }

    private static void requireRunning(BaritoneEngineArena arena, FollowTask follow) {
        arena.require(follow.state() == TaskState.RUNNING,
                "follow ended early: state=" + follow.state() + " reason=" + follow.failureReason());
    }

    /** The route was Baritone's: the task started Baritone routes, and the follower has an instance in the registry. */
    private static void requireBaritoneDrove(BaritoneEngineArena arena, FollowTask follow, AIPlayerEntity bot) {
        arena.require(follow.baritoneStarts() >= 1, "the follower never started a Baritone route");
        arena.require(BaritoneRegistry.INSTANCE.find(bot.getUUID()) != null, "the follower has no Baritone instance");
    }
}
