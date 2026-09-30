package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.FollowTask;
import io.github.zoyluo.minecraftai.task.HoldTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * No micro-teleports (R5): on the legacy engine a bot corrects its position with real movement inputs, never by moving itself. Every
 * test resets {@link TeleportAudit} for the bot under test after the fixture is built (fixture moves are {@code TEST} teleports) and
 * asserts {@code TeleportAudit.corrections(bot) == 0} at the end, in the default strict-survival profile (no emergency teleport exists).
 * Each test has its own world layer.
 */
public final class NaturalMovementGameTests {
    private static final int BASE_Y = 120;
    private static final int LAYER_STEP = 12;

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    /** A stone floor (top at feet level - 1) with air above and a light grid (no natural spawns), on its own layer. */
    private static final class Arena {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;

        private Arena(GameTestHelper context, BlockPos feet) {
            this.context = context;
            this.world = context.getLevel();
            this.feet = feet;
        }

        static Arena build(GameTestHelper context, int layer, int fromX, int toX, int fromZ, int toZ) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 8));
            for (int dx = fromX; dx <= toX; dx++) {
                for (int dz = fromZ; dz <= toZ; dz++) {
                    for (int dy = -5; dy <= 7; dy++) {
                        Block block = dy == -1 ? Blocks.STONE : Blocks.AIR;
                        world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            for (int dx = fromX; dx <= toX; dx += 4) {
                for (int dz = fromZ; dz <= toZ; dz += 4) {
                    world.setBlock(feet.offset(dx, 5, dz), Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            return new Arena(context, feet);
        }

        BlockPos at(int dx, int dy, int dz) {
            return feet.offset(dx, dy, dz);
        }

        void set(int dx, int dy, int dz, Block block) {
            world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
        }

        /** A legacy-engine bot at {@code where}, full health and food, no fixture teleport counted against it. */
        AIPlayerEntity spawn(String name, BlockPos where) {
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            world.getServer(), name, world, Vec3.atBottomCenterOf(where), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.LEGACY);
            BotFixtureMoves.place(bot, where);
            bot.setOnGround(true);
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(20.0F);
            Standability.clearCache();
            TeleportAudit.reset(bot);
            return bot;
        }

        void finish(AIPlayerEntity bot) {
            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
            bot.getActionPack().stopAll();
            bot.getActionPack().clearPace();
            NavEngineSelector.clearBotEngine(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
            context.succeed();
        }
    }

    private static String audit(AIPlayerEntity bot) {
        return "corrections=" + TeleportAudit.corrections(bot) + " last=" + TeleportAudit.lastCaller(bot);
    }

    private static void requireNoCorrections(GameTestHelper context, AIPlayerEntity bot, String what) {
        require(context, TeleportAudit.corrections(bot) == 0, what + ": the bot was teleported (" + audit(bot) + ")");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A route start that is not standable is walked off, never snapped
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The bot starts one block above the floor with nothing under it (a cell that is not standable): it falls into the layer below
     * and the route it asked for begins with a walked step onto an adjacent standable cell. A second start out of the same cell inside
     * the guard window is refused (no yo-yo). Nothing teleports the bot.
     */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_non_standable_start_walks_to_neighbour_cell", maxTicks = 260)
    public void nonStandableStartWalksToNeighbourCell(GameTestHelper context) {
        Arena arena = Arena.build(context, 0, -3, 12, -3, 3);
        BlockPos invalid = arena.at(0, 1, 0);
        BlockPos goal = arena.at(6, 0, 0);
        AIPlayerEntity bot = arena.spawn("NonStandableStartGT", invalid);
        require(context, !Standability.isStandable(arena.world, invalid), "fixture: the start cell is standable");

        ActionResult started = bot.getActionPack().startPathTo(goal);
        require(context, !started.isFailed(), "the route from a non-standable start was refused: " + started.reason());
        boolean[] refusedChecked = {false};
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            if (bot.getActionPack().isPathExecutorIdle() && tick[0] > 3) {
                requireNoCorrections(context, bot, "non-standable start");
                require(context, bot.blockPosition().distManhattan(goal) <= 1,
                        "the bot did not reach the goal: " + bot.blockPosition().toShortString());
                if (!refusedChecked[0]) {
                    refusedChecked[0] = true;
                    // The same non-standable cell again within the window: refused, and the bot is not moved.
                    BotFixtureMoves.place(bot, invalid);
                    Standability.clearCache();
                    Vec3 before = bot.position();
                    require(context, !bot.getActionPack().snapPlayerToNearestStandable("gametest_second_start"),
                            "a second start out of the same cell inside the window was not refused");
                    require(context, bot.position().distanceToSqr(before) < 1.0E-12D, "the refused start moved the bot");
                    requireNoCorrections(context, bot, "refused start");
                    arena.finish(bot);
                }
            }
            require(context, tick[0] < 250, "timed out at " + bot.blockPosition().toShortString());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Pillar and hop
    // ---------------------------------------------------------------------------------------------------------------

    /** The route to a ledge three blocks up is pillar, pillar, hop: real jumps and real placements, no rescue teleport. */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_pillar_up_without_rescue_teleport", maxTicks = 500)
    public void pillarUpWithoutRescueTeleport(GameTestHelper context) {
        Arena arena = Arena.build(context, 1, -3, 8, -3, 3);
        // A platform block whose top is 3 above the floor, one cell east of the bot's column.
        arena.set(1, 2, 0, Blocks.STONE);
        arena.set(1, 1, 0, Blocks.STONE);
        arena.set(1, 0, 0, Blocks.STONE);
        arena.set(2, 2, 0, Blocks.STONE);
        arena.set(2, 1, 0, Blocks.STONE);
        arena.set(2, 0, 0, Blocks.STONE);
        BlockPos goal = arena.at(2, 3, 0);
        AIPlayerEntity bot = arena.spawn("PillarNoRescueGT", arena.at(-1, 0, 0));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        ActionResult started = bot.getActionPack().startPathTo(goal);
        require(context, !started.isFailed(), "no route to the ledge: " + started.reason());
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            if (bot.getActionPack().isPathExecutorIdle() && tick[0] > 3) {
                requireNoCorrections(context, bot, "pillar");
                require(context, bot.blockPosition().equals(goal),
                        "the pillar route ended away from the ledge: " + bot.blockPosition().toShortString());
                arena.finish(bot);
            }
            require(context, tick[0] < 480, "timed out at " + bot.blockPosition().toShortString());
        });
    }

    /**
     * A hop onto a ledge is stopped by a ceiling that appears right after the run-up: the bot cannot rise, so it stalls at the base
     * (the old code teleported it onto the ledge after 20 ticks). Now the stall is diagnosed and the hop is retried or the node failed and
     * the route planned again, always with inputs. Then the ceiling is removed and a fresh route makes the hop.
     */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_jump_up_stall_retries_by_inputs", maxTicks = 700)
    public void jumpUpStallRetriesByInputs(GameTestHelper context) {
        Arena arena = Arena.build(context, 2, -3, 12, -3, 3);
        for (int x = 2; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                arena.set(x, 0, z, Blocks.STONE);
            }
        }
        BlockPos goal = arena.at(5, 1, 0);
        BlockPos ceiling = arena.at(1, 2, 0);
        AIPlayerEntity bot = arena.spawn("JumpStallGT", arena.at(-1, 0, 0));
        ActionResult started = bot.getActionPack().startPathTo(goal);
        require(context, !started.isFailed(), "no route to the ledge: " + started.reason());
        int[] phase = {0};
        int[] mark = {0};
        int[] tick = {0};
        StringBuilder trace = new StringBuilder();
        context.onEachTick(() -> {
            tick[0]++;
            if (tick[0] % 40 == 1) {
                trace.append(" t").append(tick[0]).append('=').append(bot.blockPosition().toShortString())
                        .append(bot.getActionPack().isPathExecutorIdle() ? " idle" : " walking");
            }
            switch (phase[0]) {
                case 0 -> {
                    // The run-up reaches the base cell: a ceiling appears one block above the bot's head.
                    if (bot.blockPosition().getX() >= arena.feet.getX() + 1) {
                        arena.world.setBlock(ceiling, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        Standability.clearCache();
                        phase[0] = 1;
                        mark[0] = tick[0];
                    }
                }
                case 1 -> {
                    if (bot.getActionPack().isPathExecutorIdle() && tick[0] > mark[0] + 5) {
                        requireNoCorrections(context, bot, "stalled hop");
                        phase[0] = 2;
                        if (bot.blockPosition().equals(goal)) {
                            // The route was planned again and found its own way up: that is fine, too.
                            arena.finish(bot);
                            return;
                        }
                        arena.world.setBlock(ceiling, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        Standability.clearCache();
                        io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder.invalidateCache("gametest_ceiling_removed");
                        ActionResult again = bot.getActionPack().startPathTo(goal);
                        require(context, !again.isFailed() || "pathfinding_throttled".equals(again.reason()),
                                "no route once the ceiling was gone: " + again.reason());
                        mark[0] = tick[0];
                    }
                }
                default -> {
                    if (bot.blockPosition().equals(goal) && bot.getActionPack().isPathExecutorIdle()) {
                        requireNoCorrections(context, bot, "hop after the ceiling was removed");
                        arena.finish(bot);
                    } else if (bot.getActionPack().isPathExecutorIdle() && tick[0] > mark[0] + 8) {
                        // A throttled first request: ask again.
                        bot.getActionPack().startPathTo(goal);
                        mark[0] = tick[0];
                    }
                }
            }
            require(context, tick[0] < 680, "timed out in phase " + phase[0] + " at " + bot.blockPosition().toShortString()
                    + " start=" + arena.at(-1, 0, 0).toShortString() + " trace:" + trace);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Suffocation escape (strict survival: no emergency teleport, no unobserved scan)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A gravel column falls into the cell the bot stands in, in a tunnel with an open neighbour: the body is inside a block. In strict
     * survival the old escape teleported the bot to the neighbour; now it is shoved out (or walks out) with inputs, and takes no more
     * than two hearts of damage.
     */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_suffocation_in_gravel_escapes_by_inputs_in_strict", maxTicks = 200)
    public void suffocationInGravelEscapesByInputsInStrict(GameTestHelper context) {
        Arena arena = Arena.build(context, 3, -4, 6, -3, 3);
        // A tunnel along x: walls at z = -1 and z = 1, a roof at dy = 2, open at the west end; the bot at x = 0 with a shaft above.
        for (int x = -4; x <= 4; x++) {
            for (int dy = 0; dy <= 1; dy++) {
                arena.set(x, dy, -1, Blocks.STONE);
                arena.set(x, dy, 1, Blocks.STONE);
            }
            if (x != 0) {
                arena.set(x, 2, 0, Blocks.STONE);
            }
        }
        arena.set(5, 0, 0, Blocks.STONE);
        arena.set(5, 1, 0, Blocks.STONE);
        arena.set(1, 0, 0, Blocks.STONE);
        arena.set(1, 1, 0, Blocks.STONE);
        BlockPos stand = arena.at(0, 0, 0);
        AIPlayerEntity bot = arena.spawn("GravelBuriedGT", stand);
        Standability.clearCache();
        // The gravel sits four blocks up the shaft and falls onto the bot.
        arena.set(0, 4, 0, Blocks.GRAVEL);
        int[] tick = {0};
        boolean[] buried = {false};
        context.onEachTick(() -> {
            tick[0]++;
            if (!buried[0] && !FakePlayerMotion.isBlockCollisionFree(bot)) {
                buried[0] = true;
            }
            if (buried[0] && FakePlayerMotion.isBlockCollisionFree(bot) && tick[0] > 10) {
                requireNoCorrections(context, bot, "gravel burial");
                require(context, bot.getHealth() >= bot.getMaxHealth() - 4.0F,
                        "the bot took more than two hearts: " + bot.getHealth());
                require(context, bot.isAlive(), "the bot died");
                arena.finish(bot);
            }
            require(context, tick[0] < 190, "timed out (buried=" + buried[0] + ", free="
                    + FakePlayerMotion.isBlockCollisionFree(bot) + ", hp=" + bot.getHealth() + ", " + audit(bot) + ")");
        });
    }

    /**
     * The bot is fully enclosed in dirt (its own cells are dirt) with a shovel: nothing is standable next to it and nothing may be
     * scanned or teleported to in strict survival, so it digs itself out at the real break time, head first, then the feet cell.
     */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_buried_bot_mines_out_in_strict", maxTicks = 400)
    public void buriedBotMinesOutInStrict(GameTestHelper context) {
        Arena arena = Arena.build(context, 4, -4, 4, -4, 4);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    arena.set(dx, dy, dz, Blocks.DIRT);
                }
            }
        }
        AIPlayerEntity bot = arena.spawn("BuriedDirtGT", arena.at(0, 0, 0));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SHOVEL));
        Standability.clearCache();
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            BlockPos feet = bot.blockPosition();
            boolean open = FakePlayerMotion.isBlockCollisionFree(bot)
                    && arena.world.getBlockState(feet).getCollisionShape(arena.world, feet).isEmpty()
                    && arena.world.getBlockState(feet.above()).getCollisionShape(arena.world, feet.above()).isEmpty();
            if (open && tick[0] > 10) {
                requireNoCorrections(context, bot, "buried in dirt");
                require(context, bot.isAlive() && bot.getHealth() >= 10.0F, "the bot nearly died digging out: " + bot.getHealth());
                arena.finish(bot);
            }
            require(context, tick[0] < 390, "still buried at tick " + tick[0] + " hp=" + bot.getHealth() + " " + audit(bot));
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Follow: off-centre starts and a long course, no correction teleport
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A follower that starts with its body overlapping a wall column, and is later put on a half-slab edge, follows a target along a
     * 40-block course (a slab step, two one-block step-ups, a two-block drop, a fence corner). It corrects its off-centre poses by
     * walking (a shove out of the block, a first leg away from it): no correction teleport, no damage, and it ends by the target.
     */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_follow_repaths_from_off_centre_cells_without_teleport", maxTicks = 1300)
    public void followRepathsFromOffCentreCellsWithoutTeleport(GameTestHelper context) {
        Arena arena = Arena.build(context, 5, -3, 46, -5, 5);
        // The wall column the follower's body overlaps at the start.
        arena.set(2, 0, -1, Blocks.STONE);
        arena.set(2, 1, -1, Blocks.STONE);
        // A bottom slab in the way at x = 8 (a half block: walked up without a jump).
        arena.world.setBlock(arena.at(8, 0, 0),
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), Block.UPDATE_ALL);
        for (int z = -5; z <= 5; z++) {
            for (int x = 12; x <= 17; x++) {
                arena.set(x, 0, z, Blocks.STONE);
                if (x >= 14) {
                    arena.set(x, 1, z, Blocks.STONE);
                }
            }
        }
        // A fence wall along z with a gap at its south end: the way to the target behind it is around the corner.
        for (int z = -5; z <= 0; z++) {
            arena.set(23, 0, z, Blocks.OAK_FENCE);
        }
        List<BlockPos> waypoints = List.of(arena.at(6, 0, 0), arena.at(10, 0, 0), arena.at(16, 2, 0), arena.at(20, 0, 0),
                arena.at(29, 0, -3), arena.at(42, 0, 0));
        String targetName = "FollowCourseTargetGT";
        AIPlayerEntity target = arena.spawn(targetName, waypoints.get(0));
        TaskManager.INSTANCE.assign(target, new HoldTask(), TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
        String name = "FollowCourseGT";
        AIPlayerEntity bot = arena.spawn(name, arena.at(2, 0, 0));
        // Off centre: the lower corner of the cell, the body reaching into the wall column at z = -1.
        BotFixtureMoves.place(bot, new Vec3(arena.feet.getX() + 2.5D, arena.feet.getY(), arena.feet.getZ() + 0.15D));
        bot.setOnGround(true);
        require(context, !FakePlayerMotion.isBlockCollisionFree(bot), "fixture: the start pose does not overlap the wall");
        TeleportAudit.reset(bot);
        FollowTask follow = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, follow, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_course"));

        int[] waypoint = {0};
        int[] sinceWaypoint = {0};
        boolean[] edgePlaced = {false};
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            sinceWaypoint[0]++;
            require(context, follow.state() == TaskState.RUNNING,
                    "follow ended early at tick " + tick[0] + ": state=" + follow.state() + " reason=" + follow.failureReason());
            require(context, bot.getHealth() >= bot.getMaxHealth() - 0.01F, "the bot took damage: " + bot.getHealth());
            requireNoCorrections(context, bot, "follow course tick " + tick[0] + " at " + bot.blockPosition().toShortString());
            BlockPos now = waypoints.get(waypoint[0]);
            boolean near = Math.hypot(bot.getX() - (now.getX() + 0.5D), bot.getZ() - (now.getZ() + 0.5D)) <= 5.0D
                    && Math.abs(bot.getY() - now.getY()) < 2.5D;
            if (waypoint[0] == 1 && !edgePlaced[0] && bot.getX() >= arena.feet.getX() + 6.0D) {
                // The half-slab edge: put the follower where its body reaches into the slab block.
                edgePlaced[0] = true;
                BotFixtureMoves.place(bot, new Vec3(arena.feet.getX() + 7.72D, arena.feet.getY(), arena.feet.getZ() + 0.5D));
                bot.setOnGround(true);
                TeleportAudit.reset(bot);
            }
            if ((near && sinceWaypoint[0] > 30) || sinceWaypoint[0] > 320) {
                if (waypoint[0] + 1 < waypoints.size()) {
                    waypoint[0]++;
                    sinceWaypoint[0] = 0;
                    BotFixtureMoves.place(target, waypoints.get(waypoint[0]));
                    target.setOnGround(true);
                } else if (follow.isWaiting() && bot.distanceTo(target) <= 3.5D) {
                    TaskManager.INSTANCE.cancelIntentTasks(target, "gametest_complete");
                    AIPlayerManager.INSTANCE.despawn(arena.world.getServer(), targetName);
                    arena.finish(bot);
                    return;
                }
            }
            require(context, tick[0] < 1290, "timed out at " + bot.blockPosition().toShortString()
                    + " waypoint=" + waypoint[0] + " dist=" + bot.distanceTo(target));
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A Baritone request that is refused takes the stale route lease of the legacy route it interrupted with it
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code startBaritoneRoute} hands the bot over from the legacy executor before the admission search runs, and keeps the route lease
     * for the route that is about to start. When the request is then refused (an exact cell that cannot be reached) there is no route
     * for the lease to belong to, so the clockless ROUTE lease of the old legacy route must not stay in force for an idle bot.
     */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_a_refused_baritone_request_drops_the_stale_route_lease", maxTicks = 80)
    public void aRefusedBaritoneRequestDropsTheStaleRouteLease(GameTestHelper context) {
        Arena arena = Arena.build(context, 6, -3, 12, -3, 3);
        // A sealed box, so the exact-cell search exhausts quickly, and a bedrock shell around the goal cell that no tool opens.
        for (int dx = -4; dx <= 13; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -1; dy <= 8; dy++) {
                    boolean shell = dx == -4 || dx == 13 || dz == -4 || dz == 4 || dy == 8;
                    if (shell) {
                        arena.set(dx, dy, dz, Blocks.BEDROCK);
                    }
                }
            }
        }
        for (int dx = 7; dx <= 9; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    if (dx != 8 || dz != 0 || dy < 0 || dy > 1) {
                        arena.set(dx, dy, dz, Blocks.BEDROCK);
                    }
                }
            }
        }
        BlockPos sealed = arena.at(8, 0, 0);
        AIPlayerEntity bot = arena.spawn("StaleLeaseGT", arena.at(0, 0, 0));
        // A legacy route with a route lease...
        ActionResult legacy = bot.getActionPack().startPathTo(arena.at(5, 0, 0));
        require(context, !legacy.isFailed(), "the legacy route was refused: " + legacy.reason());
        bot.getActionPack().requestRoutePace(Gait.SNEAK, PaceOwner.TASK);
        require(context, bot.getActionPack().leasedGait() == Gait.SNEAK, "fixture: the route lease was not granted");
        // ...is interrupted by a Baritone request that is refused.
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
        ActionResult refused = bot.getActionPack().startPathTo(sealed);
        require(context, refused.isFailed(), "the sealed goal was not refused (" + refused.status() + "): the fixture is wrong");
        require(context, bot.getActionPack().leasedGait() == null,
                "the refused request left the old route's lease in force: " + bot.getActionPack().leasedGait());
        require(context, bot.getActionPack().isPathExecutorIdle(), "the legacy route survived the hand-over");
        arena.finish(bot);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The other kinds of WalkedStep: recentre, sneak over an edge, drop off an edge, swim up a shaft
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * RECENTER walks an off-centre bot back to the centre of its cell, SNEAK_SHIFT then sneaks it a little over the edge of a one-block
     * support (sneaking keeps it from falling off the 0.45 block it goes past the centre), and STEP_DOWN walks off an edge onto the
     * floor two blocks lower. All by keys, none by a teleport.
     */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_walked_step_recentres_sneaks_over_an_edge_and_drops_off_one", maxTicks = 300)
    public void walkedStepRecentresSneaksOverAnEdgeAndDropsOffOne(GameTestHelper context) {
        Arena arena = Arena.build(context, 7, -3, 8, -3, 3);
        // A one-block support at (0, 1) (top at feet level 2) standing alone: the floor below is the arena floor two blocks lower.
        arena.set(0, 0, 0, Blocks.STONE);
        arena.set(0, 1, 0, Blocks.STONE);
        arena.set(0, 2, 0, Blocks.AIR);
        BlockPos support = arena.at(0, 2, 0);
        BlockPos floorCell = arena.at(1, 0, 0);
        AIPlayerEntity bot = arena.spawn("WalkedKindsGT", support);
        BotFixtureMoves.place(bot, new Vec3(support.getX() + 0.5D + 0.3D, support.getY(), support.getZ() + 0.5D));
        bot.setOnGround(true);
        TeleportAudit.reset(bot);
        Vec3 centre = Vec3.atBottomCenterOf(support);
        Vec3 edge = new Vec3(support.getX() + 0.5D + 0.45D, support.getY(), support.getZ() + 0.5D);
        int[] phase = {0};
        int[] ticks = {0};
        bot.getActionPack().runStep(WalkedStep.begin(bot, centre, WalkedStep.Kind.RECENTER, "gametest_recenter"));
        context.onEachTick(() -> {
            ticks[0]++;
            requireNoCorrections(context, bot, "walked step kinds, phase " + phase[0]);
            require(context, ticks[0] < 290, "timed out in phase " + phase[0] + " at " + bot.position());
            if (!bot.getActionPack().stepIdle()) {
                return;
            }
            WalkedStep.Result result = bot.getActionPack().stepResult();
            require(context, result != null && result.succeeded(),
                    "phase " + phase[0] + " failed: " + (result == null ? "no result" : result.reason()));
            switch (phase[0]) {
                case 0 -> {
                    require(context, Math.hypot(bot.getX() - centre.x, bot.getZ() - centre.z) <= 0.25D,
                            "RECENTER ended away from the cell centre: " + bot.position());
                    phase[0] = 1;
                    bot.getActionPack().runStep(WalkedStep.begin(bot, edge, WalkedStep.Kind.SNEAK_SHIFT, "gametest_sneak_shift"));
                }
                case 1 -> {
                    require(context, bot.blockPosition().equals(support) && bot.getY() >= support.getY() - 1.0E-6D,
                            "SNEAK_SHIFT fell off the support: " + bot.position());
                    require(context, Math.hypot(bot.getX() - edge.x, bot.getZ() - edge.z) <= 0.3D,
                            "SNEAK_SHIFT ended away from the edge point: " + bot.position());
                    require(context, bot.getActionPack().sneakRequested(), "SNEAK_SHIFT let go of the sneak key at the edge");
                    phase[0] = 2;
                    bot.getActionPack().stopMovement();
                    bot.getActionPack().runStep(WalkedStep.begin(bot, floorCell, WalkedStep.Kind.STEP_DOWN, "gametest_drop"));
                }
                default -> {
                    require(context, bot.blockPosition().equals(floorCell) && WalkedStep.supported(bot),
                            "STEP_DOWN did not land on the floor cell: " + bot.blockPosition().toShortString());
                    require(context, bot.getHealth() >= bot.getMaxHealth() - 0.01F, "the two-block drop cost health");
                    arena.finish(bot);
                }
            }
        });
    }

    /** SWIM presses forward and jump up a two-cell water shaft, one cell per step, and ends in the water cell it was given. */
    @GameTest(environment = "minecraftai-gametest:natural_movement_game_tests_walked_step_swims_up_a_shaft", maxTicks = 200)
    public void walkedStepSwimsUpAShaft(GameTestHelper context) {
        Arena arena = Arena.build(context, 8, -3, 6, -3, 3);
        // A water shaft at (2, 0..2, 0), walled by stone on every side, open at the top.
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = 1; dx <= 3; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean shaft = dx == 2 && dz == 0 && dy <= 2;
                    arena.set(dx, dy, dz, shaft ? Blocks.WATER : Blocks.STONE);
                }
            }
        }
        arena.set(2, 3, 0, Blocks.AIR);
        BlockPos bottom = arena.at(2, 0, 0);
        AIPlayerEntity bot = arena.spawn("WalkedSwimGT", bottom);
        BlockPos[] cells = {arena.at(2, 1, 0), arena.at(2, 2, 0)};
        TeleportAudit.reset(bot);
        int[] step = {0};
        int[] ticks = {0};
        bot.getActionPack().runStep(WalkedStep.begin(bot, cells[0], WalkedStep.Kind.SWIM, "gametest_swim_1"));
        context.onEachTick(() -> {
            ticks[0]++;
            requireNoCorrections(context, bot, "swim step " + (step[0] + 1));
            require(context, ticks[0] < 190, "timed out at " + bot.position() + " step " + (step[0] + 1));
            if (!bot.getActionPack().stepIdle()) {
                return;
            }
            WalkedStep.Result result = bot.getActionPack().stepResult();
            require(context, result != null && result.succeeded(),
                    "swim step " + (step[0] + 1) + " failed: " + (result == null ? "no result" : result.reason()));
            require(context, bot.blockPosition().equals(cells[step[0]]),
                    "swim step " + (step[0] + 1) + " ended in " + bot.blockPosition().toShortString());
            step[0]++;
            if (step[0] == cells.length) {
                arena.finish(bot);
                return;
            }
            bot.getActionPack().runStep(WalkedStep.begin(bot, cells[step[0]], WalkedStep.Kind.SWIM, "gametest_swim_2"));
        });
    }
}
