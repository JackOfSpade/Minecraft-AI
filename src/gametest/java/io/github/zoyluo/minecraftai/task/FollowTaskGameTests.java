package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

/**
 * Regression coverage for the 2026-09-28 session-log bug: a bot pathed down its own old mining
 * staircase while following, got wedged one jump short of the surface (the followed player was
 * standing in the exit cell, rejecting the jump as entity-occupied), and StuckWatcher aborted the
 * standing follow order twice in a row after 200 ticks each time -- from the player's view, the
 * bot "gave up" on its own. See {@link FollowStuckRecovery} for the survive-and-recover fix and
 * {@code io.github.zoyluo.minecraftai.pathfinding.GoalSnapGameTests} for the root-cause fix (why
 * the goal resolution dove into the pit at all).
 */
public final class FollowTaskGameTests {
    /** {@code MinecraftAiConfig.Watchdog}'s default {@code stuckWindowTicks()}. */
    private static final int OLD_ABORT_WINDOW_TICKS = 200;

    @GameTest(maxTicks = 500)
    public void followSurvivesAnExitBlockedByTheTargetAndReachesThemOnceItClears(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos platformFeet = context.absolutePos(new BlockPos(4, 8, 4));
        preparePlatform(context, platformFeet, 4);

        // A 1-wide pit one block below the platform; its only way out is a single JUMP_UP through
        // exitCell -- a miniature version of the log's own dig-down staircase.
        BlockPos exitCell = platformFeet.offset(2, 0, 0);
        BlockPos pitCell = exitCell.offset(1, -1, 0);
        carveOneWidePit(world, pitCell, exitCell);

        String botName = "FollowStuckGT";
        AIPlayerEntity bot = spawn(context, botName, pitCell);
        String targetName = "FollowStuckTargetGT";
        BlockPos targetHome = platformFeet.offset(-2, 0, 0);
        AIPlayerEntity targetBot = spawn(context, targetName, targetHome);
        // Keep the target completely inert (HoldTask both stops it from moving and, via
        // isWaiting()==true, keeps IdleCoordinator from ever touching it) until the test itself
        // moves it.
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        // Block the pit's only exit with the followed target itself -- exactly the log's own
        // "entity_occupied" jump rejection.
        teleportTo(world, targetBot, exitCell);

        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_stuck_recovery"));

        int[] tick = {0};
        boolean[] cleared = {false};
        context.failIfEver(() -> {
            tick[0]++;
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early at tick " + tick[0] + ": state=" + followTask.state()
                            + " reason=" + followTask.failureReason());

            if (!cleared[0] && tick[0] > OLD_ABORT_WINDOW_TICKS + 20) {
                // Comfortably past the old 200-tick abort window while still blocked -- proves
                // the standing order survived it -- then the player-stand-in steps out of the way.
                cleared[0] = true;
                teleportTo(world, targetBot, targetHome);
            }

            if (cleared[0] && followTask.isWaiting()) {
                double distance = bot.distanceTo(targetBot);
                if (distance <= 4.5D) {
                    require(context, distance <= 4.0D,
                            "follow settled too far from the target once the exit cleared: " + distance);
                    finish(context, bot, botName, targetBot, targetName);
                }
            }
        });
    }

    @GameTest(maxTicks = 300)
    public void followSettlesAtTheWiderStopDistanceOnFlatGround(GameTestHelper context) {
        BlockPos platformFeet = context.absolutePos(new BlockPos(4, 5, 4));
        preparePlatform(context, platformFeet, 8);

        String targetName = "FollowDistanceTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, platformFeet);
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));

        String botName = "FollowDistanceGT";
        AIPlayerEntity bot = spawn(context, botName, platformFeet.offset(7, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_distance"));

        context.failIfEver(() -> {
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state()
                            + " reason=" + followTask.failureReason());
            double distance = bot.distanceTo(targetBot);
            require(context, distance >= 2.5D,
                    "follow settled closer than 2.5 blocks (STOP_DISTANCE=3.0): " + distance);
            if (followTask.isWaiting() && distance <= 4.0D) {
                finish(context, bot, botName, targetBot, targetName);
            }
        });
    }

    // ---- r10c: follow navigation regressions (yaw hijack, stale executor, throttled repaths,
    // ---- unsafe straight-line fallback, walls).  Each fixture gets its own vertical band so the
    // ---- tests of this class can share a batch without overwriting one another's blocks.

    /**
     * A 2-high wall with a walkable gap at one end lies between the bot and the player.  The
     * session log's "walks straight at the player" windows came from FollowTask re-aiming the body
     * yaw at the player after every tick, overriding the bearing the path walker had set, so the bot
     * pressed into the wall instead of following its own detour.  It must walk around and must not
     * dig (a walkable detour exists).
     */
    @GameTest(maxTicks = 700)
    public void followWalksAroundATwoHighWallInsteadOfPressingIntoIt(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos c = context.absolutePos(new BlockPos(8, 12, 8));
        preparePlatform(context, c, 6);
        java.util.List<BlockPos> wall = buildWall(world, c, -6, 5);

        String targetName = "FollowWallTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.offset(4, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowWallGT";
        AIPlayerEntity bot = spawn(context, botName, c.offset(-4, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_wall_detour"));

        context.failIfEver(() -> {
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            if (followTask.isWaiting() && bot.getX() > c.getX() + 0.5D && bot.distanceTo(targetBot) <= 4.0D) {
                for (BlockPos pos : wall) {
                    require(context, world.getBlockState(pos).is(Blocks.STONE),
                            "the bot dug through the wall at " + pos + " although a walkable detour existed");
                }
                finish(context, bot, botName, targetBot, targetName);
            }
        });
    }

    /**
     * The same wall with no way around it at all: only now may the bot break through (natural
     * stone, with a pickaxe in hand) as the last resort.  It used to keep pushing into the wall
     * until StuckWatcher aborted the follow order.
     */
    @GameTest(maxTicks = 1200)
    public void followDigsThroughAWallOnlyWhenNoWalkableDetourExists(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos c = context.absolutePos(new BlockPos(8, 18, 8));
        preparePlatform(context, c, 6);
        java.util.List<BlockPos> wall = buildWall(world, c, -6, 6);

        String targetName = "FollowDigTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.offset(4, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowDigGT";
        AIPlayerEntity bot = spawn(context, botName, c.offset(-4, 0, 0));
        io.github.zoyluo.minecraftai.action.InventoryAction.giveItem(bot,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_wall_dig"));

        context.failIfEver(() -> {
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            if (followTask.isWaiting() && bot.getX() > c.getX() + 0.5D && bot.distanceTo(targetBot) <= 4.0D) {
                boolean opened = wall.stream().anyMatch(pos -> world.getBlockState(pos).isAir());
                require(context, opened, "the bot crossed a sealed wall without opening it");
                finish(context, bot, botName, targetBot, targetName);
            }
        });
    }

    /**
     * The nearest standable cell to the stand-off point is on the bot's own bank, so the resolved
     * goal becomes the bot's own cell once it arrives there.  Identical re-requests within the
     * success cooldown report "pathfinding_throttled", which used to be read as a failed path and
     * answered with a straight-line walk into the water (the lake incidents in the session log).
     */
    @GameTest(maxTicks = 400)
    public void followAtTheNearBankNeverStraightLinesIntoTheWater(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos c = context.absolutePos(new BlockPos(8, 24, 8));
        preparePlatform(context, c, 6);
        buildWaterStrip(world, c, -1, 2, true);

        String targetName = "FollowNearBankTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.offset(3, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowNearBankGT";
        AIPlayerEntity bot = spawn(context, botName, c.offset(-5, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_near_bank"));

        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            requireDry(context, world, bot);
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            require(context, followTask.directWalkCount() == 0,
                    "follow started " + followTask.directWalkCount() + " straight-line walks toward a goal across water");
            if (tick[0] >= 300) {
                require(context, bot.getX() >= c.getX() - 3.5D,
                        "the bot never walked up to its bank of the water: x=" + bot.getX());
                finish(context, bot, botName, targetBot, targetName);
            }
        });
    }

    /**
     * The stand-off point resolves onto the FAR bank of a moat that is sealed at both ends (bedrock, no
     * walkable, climbable or diggable way round), so there is genuinely no dry route. The failed search
     * used to trigger the unverified straight walk into the water; now the bot may only take a straight
     * segment whose every cell is standable, so it stays dry: it waits on its own bank, re-plans on the
     * normal schedule and tells the player once. It is held for 800 ticks so the whole stall-recovery
     * ladder (recovery steps, forced replans, back-off, the give-up notice) runs while it is watched, and
     * it must never end up on the far side (a snap or teleport across the moat would be dry, too).
     *
     * <p>History: this fixture used to close the moat with one-block stone caps level with the floor,
     * which is a walkable bridge along each end (a real dry route, reached and left by diagonal steps past
     * the water's corner cells). The bot legitimately walked it -- and, when its string-pulled shortcut
     * clipped the corner cell of the water, ended up in the moat now and then.
     */
    @GameTest(maxTicks = 900)
    public void followWithNoRouteAcrossWaterStaysDryInsteadOfWalkingStraightIn(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos c = context.absolutePos(new BlockPos(8, 30, 8));
        preparePlatform(context, c, 6);
        buildWaterStrip(world, c, -1, 0, true);

        String targetName = "FollowFarBankTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.offset(3, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowFarBankGT";
        AIPlayerEntity bot = spawn(context, botName, c.offset(-5, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_far_bank"));

        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            requireDry(context, world, bot);
            require(context, bot.getX() < c.getX() - 1.0D,
                    "the bot left its own bank of a moat with no route (x=" + bot.getX() + " at tick " + tick[0] + ")");
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            require(context, followTask.directWalkCount() == 0,
                    "follow started " + followTask.directWalkCount() + " unverified straight-line walks");
            if (tick[0] >= 800) {
                require(context, followTask.noRouteNotices() == 1,
                        "the player must be told there is no dry route exactly once, not "
                                + followTask.noRouteNotices() + " times");
                finish(context, bot, botName, targetBot, targetName);
            }
        });
    }

    /**
     * The unsealed fixture: the moat's end closures are one-block stone caps, a genuine dry bridge that the
     * bot reaches and leaves by diagonal steps past the water's corner cells. The bot may take it -- it
     * crosses the moat and ends up beside the player -- but on every tick of the way it must stay dry.
     * Regression for a shortcut (string-pulled route segment) whose sampling stepped over the corner
     * column of the water and cut the bot across it.
     */
    @GameTest(maxTicks = 600)
    public void followTakesTheDryBridgeAroundTheMoatWithoutTouchingTheWater(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos c = context.absolutePos(new BlockPos(8, 30, 8));
        preparePlatform(context, c, 6);
        buildWaterStrip(world, c, -1, 0, false);

        String targetName = "FollowBridgeTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.offset(3, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowBridgeGT";
        AIPlayerEntity bot = spawn(context, botName, c.offset(-5, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_dry_bridge"));

        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            requireDry(context, world, bot);
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            require(context, followTask.directWalkCount() == 0,
                    "follow started " + followTask.directWalkCount() + " unverified straight-line walks");
            if (bot.getX() > c.getX() + 0.5D && bot.distanceTo(targetBot) <= 4.0D) {
                finish(context, bot, botName, targetBot, targetName);
                return;
            }
            require(context, tick[0] < 580, "the bot never crossed the dry bridge to the player: at "
                    + bot.blockPosition() + " distance " + bot.distanceTo(targetBot));
        });
    }

    /**
     * The nearest standable cell to the stand-off point is the bot's own, so it holds; once the player
     * moves, that answer is stale and must be re-evaluated within the short re-check window rather than
     * after the full REPATH_TICKS (40) the hold was scheduled for. The bot holds on its own bank of a
     * sealed moat; the player then steps to a reachable spot on that bank, and the bot must set off well
     * inside the long schedule.
     */
    @GameTest(maxTicks = 400)
    public void followReevaluatesAHeldOwnCellGoalAsSoonAsThePlayerMoves(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos c = context.absolutePos(new BlockPos(8, 34, 8));
        preparePlatform(context, c, 6);
        buildWaterStrip(world, c, -1, 2, true);

        String targetName = "FollowHoldTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.offset(3, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowHoldGT";
        AIPlayerEntity bot = spawn(context, botName, c.offset(-5, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_hold_reeval"));

        // Where the player steps to: on the bot's own bank, far enough from where it holds that it must move.
        BlockPos newSpot = c.offset(-5, 0, 4);
        int[] holdSeenAt = {-1};
        int[] movedAt = {-1};
        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            requireDry(context, world, bot);
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            if (movedAt[0] < 0) {
                require(context, tick[0] < 300, "the bot never reached the hold at its own bank");
                if (holdSeenAt[0] < 0 && followTask.holdingAtOwnCell()) {
                    holdSeenAt[0] = tick[0];
                }
                // Six ticks into a fresh hold: past the re-check window (5), far inside the schedule (40).
                if (holdSeenAt[0] >= 0 && tick[0] == holdSeenAt[0] + 6) {
                    teleportTo(world, targetBot, newSpot);
                    movedAt[0] = tick[0];
                }
                return;
            }
            int sinceMove = tick[0] - movedAt[0];
            boolean reacting = !followTask.holdingAtOwnCell()
                    && (!bot.getActionPack().isPathExecutorIdle() || !bot.getActionPack().isWalkToIdle());
            if (reacting) {
                finish(context, bot, botName, targetBot, targetName);
                return;
            }
            require(context, sinceMove < 15,
                    "the bot still held a stale own-cell goal " + sinceMove + " ticks after the player moved");
        });
    }

    /**
     * Arriving must cancel the path executor, not just release the movement keys: a stale path
     * kept forward=1 for up to WalkToController.MAX_TICKS with no replan.
     */
    @GameTest(maxTicks = 120)
    public void followArrivalCancelsAStalePathExecutor(GameTestHelper context) {
        BlockPos c = context.absolutePos(new BlockPos(8, 36, 8));
        preparePlatform(context, c, 6);

        String targetName = "FollowStaleTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c);
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowStaleGT";
        AIPlayerEntity bot = spawn(context, botName, c.offset(2, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_stale_executor"));

        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            if (tick[0] == 3) {
                // A leftover route from some earlier order, leading away from the player (issued
                // after the task has started, which clears earlier actions).
                require(context, !bot.getActionPack().startPathTo(c.offset(5, 0, 5)).isFailed(),
                        "fixture: the leftover route could not be planned");
            }
            if (tick[0] >= 8) {
                require(context, bot.getActionPack().isPathExecutorIdle(),
                        "an arrived follower still owns a live path executor at tick " + tick[0]);
                require(context, bot.getActionPack().isWalkToIdle(), "an arrived follower still owns a walk at tick " + tick[0]);
            }
            if (tick[0] >= 30) {
                require(context, bot.distanceTo(targetBot) <= 4.0D,
                        "the arrived follower walked away from the player: " + bot.distanceTo(targetBot));
                finish(context, bot, botName, targetBot, targetName);
            }
        });
    }

    /** A 2-high stone wall along x = c.x spanning z in [fromZ, toZ] (relative to c). */
    private static java.util.List<BlockPos> buildWall(ServerLevel world, BlockPos c, int fromZ, int toZ) {
        java.util.List<BlockPos> wall = new java.util.ArrayList<>();
        for (int dz = fromZ; dz <= toZ; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = c.offset(0, dy, dz);
                world.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                wall.add(pos);
            }
        }
        return wall;
    }

    /**
     * Water columns x in [fromX, toX] (relative to c), full width of the platform, closed at both ends
     * (z = +-7) so the water cannot spill. {@code sealed} makes those end closures four-high bedrock
     * (nothing can be walked over, stepped up onto or dug), so the water is a true moat with NO dry
     * route around it. Unsealed, the closures are one-block stone caps level with the floor -- which is a
     * real, walkable one-block-wide bridge along each end of the moat (reached and left by diagonal steps
     * past the water's corner cells): that is the shape that used to be mistaken for a "no route" fixture.
     */
    private static void buildWaterStrip(ServerLevel world, BlockPos c, int fromX, int toX, boolean sealed) {
        for (int dx = fromX; dx <= toX; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                BlockPos floor = c.offset(dx, -1, dz);
                boolean cap = Math.abs(dz) == 7;
                world.setBlock(floor.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                if (cap && sealed) {
                    for (int dy = 0; dy <= 3; dy++) {
                        world.setBlock(floor.above(dy), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                } else {
                    world.setBlock(floor, cap ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void requireDry(GameTestHelper context, ServerLevel world, AIPlayerEntity bot) {
        require(context, !bot.isInWater() && world.getFluidState(bot.blockPosition()).isEmpty()
                        && world.getFluidState(bot.blockPosition().below()).isEmpty(),
                "the bot entered the water at " + bot.blockPosition());
    }

    /**
     * A 1-wide, 2-tall pit one block below {@code exitCell}'s level, walled on every side except
     * the single westward JUMP_UP step up onto {@code exitCell} (the pit's east neighbour is
     * {@code exitCell}'s column one block down).
     */
    private static void carveOneWidePit(ServerLevel world, BlockPos pitCell, BlockPos exitCell) {
        world.setBlock(pitCell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pitCell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pitCell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // Jump clearance above the pit itself (NeighborEnumerator.canJumpFrom needs two clear
        // cells above the jumping-off footing).
        world.setBlock(pitCell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // The step between the pit and the platform: solid at pit level (the JUMP_UP front face);
        // exitCell itself is already open platform ground one block above it.
        world.setBlock(exitCell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST}) {
            BlockPos wall = pitCell.relative(side);
            world.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void teleportTo(ServerLevel world, AIPlayerEntity bot, BlockPos pos) {
        bot.teleportTo(world, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.setOnGround(true);
    }

    /**
     * Positive case for {@link FollowDirectWalk}: a straight segment whose every cell is real dry footing
     * (flat, a one-block step up and a one-block drop) IS verified safe, while the same line with a water
     * cell in it is refused. The refusal cases alone cannot show the fallback still works where it should.
     */
    @GameTest(maxTicks = 40)
    public void directWalkVerifiesASafeStraightSegmentAndRefusesWaterOnTheLine(GameTestHelper context) {
        BlockPos platformFeet = context.absolutePos(new BlockPos(4, 5, 4));
        preparePlatform(context, platformFeet, 8);
        ServerLevel world = context.getLevel();
        // West of the platform centre: the test structure's barrier boundary sits two cells above the
        // ground four cells east of it, which would (correctly) count as no headroom.
        BlockPos start = platformFeet.offset(-3, 0, 0);
        BlockPos goal = start.offset(4, 0, 0);
        boolean[] done = {false};
        context.failIfEver(() -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            FollowDirectWalk.Verdict flat = FollowDirectWalk.verify(world, start, goal);
            require(context, flat.safe(), "a flat, dry straight segment was refused: " + flat.reason());

            // A one-block step up at +2, along a raised pair, and a one-block drop at +4: still verified walkable.
            world.setBlock(start.offset(2, 0, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(start.offset(3, 0, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            FollowDirectWalk.Verdict stepped = FollowDirectWalk.verify(world, start, goal);
            require(context, stepped.safe(), "a segment with a one-block step up and drop was refused: " + stepped.reason());

            // Water in the middle of the line: refused, never walked into.
            world.setBlock(start.offset(2, 0, 0), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(start.offset(2, -1, 0), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            FollowDirectWalk.Verdict wet = FollowDirectWalk.verify(world, start, goal);
            require(context, !wet.safe(), "a segment through water was verified safe");
            context.succeed();
        });
    }

    private static void preparePlatform(GameTestHelper context, BlockPos feet, int radius) {
        ServerLevel world = context.getLevel();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        teleportTo(context.getLevel(), bot, feet);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    private static void finish(GameTestHelper context, AIPlayerEntity bot, String botName,
                               AIPlayerEntity targetBot, String targetName) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        TaskManager.INSTANCE.cancelIntentTasks(targetBot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), botName);
        AIPlayerManager.INSTANCE.despawn(targetBot.level().getServer(), targetName);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
