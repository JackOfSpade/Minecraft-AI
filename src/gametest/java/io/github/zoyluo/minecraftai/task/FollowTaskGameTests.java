package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

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
    public void followSurvivesAnExitBlockedByTheTargetAndReachesThemOnceItClears(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos platformFeet = context.getAbsolutePos(new BlockPos(4, 8, 4));
        preparePlatform(context, platformFeet, 4);

        // A 1-wide pit one block below the platform; its only way out is a single JUMP_UP through
        // exitCell -- a miniature version of the log's own dig-down staircase.
        BlockPos exitCell = platformFeet.add(2, 0, 0);
        BlockPos pitCell = exitCell.add(1, -1, 0);
        carveOneWidePit(world, pitCell, exitCell);

        String botName = "FollowStuckGT";
        AIPlayerEntity bot = spawn(context, botName, pitCell);
        String targetName = "FollowStuckTargetGT";
        BlockPos targetHome = platformFeet.add(-2, 0, 0);
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
        context.runAtEveryTick(() -> {
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
    public void followSettlesAtTheWiderStopDistanceOnFlatGround(TestContext context) {
        BlockPos platformFeet = context.getAbsolutePos(new BlockPos(4, 5, 4));
        preparePlatform(context, platformFeet, 8);

        String targetName = "FollowDistanceTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, platformFeet);
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));

        String botName = "FollowDistanceGT";
        AIPlayerEntity bot = spawn(context, botName, platformFeet.add(7, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_distance"));

        context.runAtEveryTick(() -> {
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
    public void followWalksAroundATwoHighWallInsteadOfPressingIntoIt(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos c = context.getAbsolutePos(new BlockPos(8, 12, 8));
        preparePlatform(context, c, 6);
        java.util.List<BlockPos> wall = buildWall(world, c, -6, 5);

        String targetName = "FollowWallTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.add(4, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowWallGT";
        AIPlayerEntity bot = spawn(context, botName, c.add(-4, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_wall_detour"));

        context.runAtEveryTick(() -> {
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            if (followTask.isWaiting() && bot.getX() > c.getX() + 0.5D && bot.distanceTo(targetBot) <= 4.0D) {
                for (BlockPos pos : wall) {
                    require(context, world.getBlockState(pos).isOf(Blocks.STONE),
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
    public void followDigsThroughAWallOnlyWhenNoWalkableDetourExists(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos c = context.getAbsolutePos(new BlockPos(8, 18, 8));
        preparePlatform(context, c, 6);
        java.util.List<BlockPos> wall = buildWall(world, c, -6, 6);

        String targetName = "FollowDigTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.add(4, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowDigGT";
        AIPlayerEntity bot = spawn(context, botName, c.add(-4, 0, 0));
        io.github.zoyluo.minecraftai.action.InventoryAction.giveItem(bot,
                new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_PICKAXE));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_wall_dig"));

        context.runAtEveryTick(() -> {
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
    public void followAtTheNearBankNeverStraightLinesIntoTheWater(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos c = context.getAbsolutePos(new BlockPos(8, 24, 8));
        preparePlatform(context, c, 6);
        buildWaterStrip(world, c, -1, 2);

        String targetName = "FollowNearBankTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.add(3, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowNearBankGT";
        AIPlayerEntity bot = spawn(context, botName, c.add(-5, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_near_bank"));

        int[] tick = {0};
        context.runAtEveryTick(() -> {
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
     * The stand-off point resolves onto the FAR bank of a moat, where no walkable route exists.
     * The failed search used to trigger the unverified straight walk into the water; now the bot
     * may only take a straight segment whose every cell is standable, so it stays dry.
     */
    @GameTest(maxTicks = 400)
    public void followWithNoRouteAcrossWaterStaysDryInsteadOfWalkingStraightIn(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos c = context.getAbsolutePos(new BlockPos(8, 30, 8));
        preparePlatform(context, c, 6);
        buildWaterStrip(world, c, -1, 0);

        String targetName = "FollowFarBankTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c.add(3, 0, 0));
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowFarBankGT";
        AIPlayerEntity bot = spawn(context, botName, c.add(-5, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_far_bank"));

        int[] tick = {0};
        context.runAtEveryTick(() -> {
            tick[0]++;
            requireDry(context, world, bot);
            require(context, followTask.state() == TaskState.RUNNING,
                    "follow ended early: state=" + followTask.state() + " reason=" + followTask.failureReason());
            require(context, followTask.directWalkCount() == 0,
                    "follow started " + followTask.directWalkCount() + " unverified straight-line walks");
            if (tick[0] >= 300) {
                finish(context, bot, botName, targetBot, targetName);
            }
        });
    }

    /**
     * Arriving must cancel the path executor, not just release the movement keys: a stale path
     * kept forward=1 for up to WalkToController.MAX_TICKS with no replan.
     */
    @GameTest(maxTicks = 120)
    public void followArrivalCancelsAStalePathExecutor(TestContext context) {
        BlockPos c = context.getAbsolutePos(new BlockPos(8, 36, 8));
        preparePlatform(context, c, 6);

        String targetName = "FollowStaleTargetGT";
        AIPlayerEntity targetBot = spawn(context, targetName, c);
        TaskManager.INSTANCE.assign(targetBot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String botName = "FollowStaleGT";
        AIPlayerEntity bot = spawn(context, botName, c.add(2, 0, 0));
        FollowTask followTask = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, followTask,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_stale_executor"));

        int[] tick = {0};
        context.runAtEveryTick(() -> {
            tick[0]++;
            if (tick[0] == 3) {
                // A leftover route from some earlier order, leading away from the player (issued
                // after the task has started, which clears earlier actions).
                require(context, !bot.getActionPack().startPathTo(c.add(5, 0, 5)).isFailed(),
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
    private static java.util.List<BlockPos> buildWall(ServerWorld world, BlockPos c, int fromZ, int toZ) {
        java.util.List<BlockPos> wall = new java.util.ArrayList<>();
        for (int dz = fromZ; dz <= toZ; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = c.add(0, dy, dz);
                world.setBlockState(pos, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                wall.add(pos);
            }
        }
        return wall;
    }

    /** Water columns x in [fromX, toX] (relative to c), full width of the platform, capped at both ends. */
    private static void buildWaterStrip(ServerWorld world, BlockPos c, int fromX, int toX) {
        for (int dx = fromX; dx <= toX; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                BlockPos floor = c.add(dx, -1, dz);
                boolean cap = Math.abs(dz) == 7;
                world.setBlockState(floor.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(floor, cap ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState(),
                        Block.NOTIFY_ALL);
            }
        }
    }

    private static void requireDry(TestContext context, ServerWorld world, AIPlayerEntity bot) {
        require(context, !bot.isTouchingWater() && world.getFluidState(bot.getBlockPos()).isEmpty()
                        && world.getFluidState(bot.getBlockPos().down()).isEmpty(),
                "the bot entered the water at " + bot.getBlockPos());
    }

    /**
     * A 1-wide, 2-tall pit one block below {@code exitCell}'s level, walled on every side except
     * the single westward JUMP_UP step up onto {@code exitCell} (the pit's east neighbour is
     * {@code exitCell}'s column one block down).
     */
    private static void carveOneWidePit(ServerWorld world, BlockPos pitCell, BlockPos exitCell) {
        world.setBlockState(pitCell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(pitCell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(pitCell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        // Jump clearance above the pit itself (NeighborEnumerator.canJumpFrom needs two clear
        // cells above the jumping-off footing).
        world.setBlockState(pitCell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        // The step between the pit and the platform: solid at pit level (the JUMP_UP front face);
        // exitCell itself is already open platform ground one block above it.
        world.setBlockState(exitCell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST}) {
            BlockPos wall = pitCell.offset(side);
            world.setBlockState(wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(wall.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    private static void teleportTo(ServerWorld world, AIPlayerEntity bot, BlockPos pos) {
        bot.teleport(world, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setVelocity(Vec3d.ZERO);
        bot.fallDistance = 0.0F;
        bot.setOnGround(true);
    }

    private static void preparePlatform(TestContext context, BlockPos feet, int radius) {
        ServerWorld world = context.getWorld();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        teleportTo(context.getWorld(), bot, feet);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return bot;
    }

    private static void finish(TestContext context, AIPlayerEntity bot, String botName,
                               AIPlayerEntity targetBot, String targetName) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        TaskManager.INSTANCE.cancelIntentTasks(targetBot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getEntityWorld().getServer(), botName);
        AIPlayerManager.INSTANCE.despawn(targetBot.getEntityWorld().getServer(), targetName);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}
