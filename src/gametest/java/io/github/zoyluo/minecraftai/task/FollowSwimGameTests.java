package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live proofs for the swim / dive / dig-out halves of following a player:
 * <ul>
 *   <li>a bot on a shore follows a player who swims across a pond with no boat and no launch-site
 *       pairing, and climbs out after them on the far shore;</li>
 *   <li>a bot diving after a deep player resurfaces before it drowns and then goes back down, but
 *       is never forced up while Water Breathing lets it stay under;</li>
 *   <li>the follow lease and NavSafetyNet's water crisis never fight over a shallow-water swim;</li>
 *   <li>a bot walled off by a natural stone wall with no detour digs through it.</li>
 * </ul>
 *
 * <p>Fixture geometry (relative to the feet-level origin F=(0,0,0)): land tops out at y=-1, so the
 * bot stands at y=0; a pond of the requested depth is carved into the land with its water surface
 * block also at y=-1 (the ordinary flush vanilla bank).</p>
 */
public final class FollowSwimGameTests {
    private static final int MAX_Z = 18;
    private static final int POND_Z0 = 2;
    private static final int POND_Z1 = 14;
    static final int LANE_Z = 8;
    private static final double SWIM_Y = -1.0D + 0.125D;

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_bot_on_shore_swims_after_target_across_pond_without_boat", maxTicks = 1200)
    public void botOnShoreSwimsAfterTargetAcrossPondWithoutBoat(GameTestHelper context) {
        Pond pond = buildPond(context, 8, 19, 3, 26);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnBot(world, "SwimCrossBot", pond.feet().offset(3, 0, LANE_Z));
        AIPlayerEntity target = spawnBot(world, "SwimCrossTgt", pond.feet().offset(9, 0, LANE_Z));
        holdStill(target);
        swimTo(world, pond, target, 9.5D, LANE_Z + 0.5D, SWIM_Y);
        FollowTask follow = new FollowTask("SwimCrossTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_swim_cross"));
        double[] targetX = {9.5D};
        boolean[] sawWater = {false};
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net's water crisis took over a healthy swim follow at tick " + now);
            require(context, bot.getVehicle() == null && noBoats(world, pond),
                    "follow used a boat for a swimming target");
            sawWater[0] |= bot.isInWater();
            // Let the bot reach the shore first, then the player swims east across the pond.
            if (now > 40 && targetX[0] < 20.0D) {
                targetX[0] += 0.12D;
                swimTo(world, pond, target, targetX[0], LANE_Z + 0.5D, SWIM_Y);
            } else if (targetX[0] >= 20.0D) {
                // Climb out onto the far shore and stand there.
                standOn(world, pond, target, 22.5D, LANE_Z + 0.5D, 0.0D);
            } else {
                swimTo(world, pond, target, 9.5D, LANE_Z + 0.5D, SWIM_Y);
            }
            double distance = bot.distanceTo(target);
            if (targetX[0] >= 20.0D && now > 120 && !bot.isInWater()
                    && bot.getX() >= pond.feet().getX() + 19.5D && distance <= 5.0D) {
                require(context, sawWater[0], "the bot reached the far shore without ever entering the water");
                finish(context, pond, bot, target);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_dives_after_target_resurfaces_before_drowning_then_goes_back_down", maxTicks = 1500)
    public void divesAfterTargetResurfacesBeforeDrowningThenGoesBackDown(GameTestHelper context) {
        runDive(context, false, "DiveBot", "DiveTgt");
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_water_breathing_bot_stays_down_with_diving_target", maxTicks = 1200)
    public void waterBreathingBotStaysDownWithDivingTarget(GameTestHelper context) {
        runDive(context, true, "DiveWbBot", "DiveWbTgt");
    }

    /**
     * Shared dive fixture: a 12-deep pond, the target dives to ~9 blocks and stays.  Without water
     * breathing the bot must go down, resurface before drowning and go back down; with it the bot
     * must simply stay near the target for the whole hold and never be forced up.
     */
    private static void runDive(GameTestHelper context, boolean waterBreathing, String botName, String targetName) {
        Pond pond = buildPond(context, 8, 17, 12, 22);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnBot(world, botName, pond.feet().offset(4, 0, LANE_Z));
        AIPlayerEntity target = spawnBot(world, targetName, pond.feet().offset(12, 0, LANE_Z));
        holdStill(target);
        swimTo(world, pond, target, 12.5D, LANE_Z + 0.5D, SWIM_Y);
        if (waterBreathing) {
            bot.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 20 * 60 * 5, 0, false, false));
        }
        FollowTask follow = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_swim_dive"));
        double diveFloorY = SWIM_Y - 9.0D;
        double[] targetY = {SWIM_Y};
        // 0 = waiting for the bot to reach the target at the surface, 1 = target diving/holding,
        // 2 = bot went deep, 3 = bot resurfaced, (non-breathing only, then back down = done).
        int[] stage = {0};
        int[] holdTicks = {0};
        int[] minAir = {Integer.MAX_VALUE};
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            double botDepthY = bot.getY() - pond.feet().getY();
            minAir[0] = Math.min(minAir[0], bot.getAirSupply());
            require(context, bot.getAirSupply() > 0 && bot.getHealth() >= bot.getMaxHealth(),
                    "the bot was drowning while following a diver: air=" + bot.getAirSupply()
                            + " health=" + bot.getHealth() + " y=" + botDepthY + " tick=" + now);
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net had to rescue a healthy dive follow at tick " + now
                            + " air=" + bot.getAirSupply() + " y=" + botDepthY);
            if (stage[0] == 0) {
                swimTo(world, pond, target, 12.5D, LANE_Z + 0.5D, SWIM_Y);
                if (bot.isInWater() && bot.distanceTo(target) <= 6.0D) {
                    stage[0] = 1;
                }
                return;
            }
            // Dive the target down, then hold it there with a topped-up air supply so only the
            // follower's oxygen management is under test.
            targetY[0] = Math.max(diveFloorY, targetY[0] - 0.4D);
            swimTo(world, pond, target, 12.5D, LANE_Z + 0.5D, targetY[0]);
            boolean deep = botDepthY <= -6.5D;
            if (waterBreathing) {
                if (stage[0] == 1 && deep) {
                    stage[0] = 2;
                }
                if (stage[0] == 2) {
                    holdTicks[0]++;
                    require(context, botDepthY <= -5.5D,
                            "a Water Breathing bot was forced up while following a diver: y=" + botDepthY
                                    + " air=" + bot.getAirSupply() + " held=" + holdTicks[0]);
                    if (holdTicks[0] >= 420) {
                        require(context, bot.getAirSupply() >= 250,
                                "Water Breathing bot lost air anyway: " + bot.getAirSupply());
                        finish(context, pond, bot, target);
                    }
                }
                return;
            }
            if (stage[0] == 1 && deep) {
                stage[0] = 2;
            } else if (stage[0] == 2 && botDepthY >= -2.5D && !bot.isUnderWater()) {
                stage[0] = 3;
            } else if (stage[0] == 3 && deep) {
                require(context, minAir[0] > 0, "air hit zero during the dive");
                finish(context, pond, bot, target);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_shallow_water_swim_has_no_follow_and_safety_net_ping_pong", maxTicks = 700)
    public void shallowWaterSwimHasNoFollowAndSafetyNetPingPong(GameTestHelper context) {
        Pond pond = buildPond(context, 8, 19, 2, 26);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnBot(world, "ShallowBot", pond.feet().offset(3, 0, LANE_Z));
        AIPlayerEntity target = spawnBot(world, "ShallowTgt", pond.feet().offset(9, 0, LANE_Z));
        holdStill(target);
        swimTo(world, pond, target, 9.5D, LANE_Z + 0.5D, SWIM_Y);
        FollowTask follow = new FollowTask("ShallowTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_swim_shallow"));
        double[] targetX = {9.5D};
        BlockPos[] history = new BlockPos[2];
        int[] reversals = {0};
        int[] wetTicks = {0};
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net's water crisis fought the shallow swim follow at tick " + now);
            if (bot.isInWater()) {
                wetTicks[0]++;
            }
            BlockPos here = bot.blockPosition();
            if (history[0] != null && history[1] != null
                    && here.equals(history[0]) && !here.equals(history[1])) {
                reversals[0]++;
            }
            history[0] = history[1];
            history[1] = here;
            require(context, reversals[0] <= 10, "the bot ping-ponged between two cells: reversals=" + reversals[0]);
            if (now > 40) {
                targetX[0] = Math.min(19.5D, targetX[0] + 0.08D);
            }
            swimTo(world, pond, target, targetX[0], LANE_Z + 0.5D, SWIM_Y);
            if (targetX[0] >= 19.5D && now > 200 && wetTicks[0] > 60 && bot.isInWater()
                    && bot.distanceTo(target) <= 5.0D) {
                finish(context, pond, bot, target);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_stuck_behind_natural_wall_digs_through_with_its_tools", maxTicks = 1500)
    public void stuckBehindNaturalWallDigsThroughWithItsTools(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(0, 24, 0));
        ServerLevel world = context.getLevel();
        // Sealed island: floor only exists inside the platform, a 2-high, 2-thick natural stone
        // wall splits it completely, and the bot carries no placeable block, so no walking route
        // exists (the follow order alone must get it through, by the ordinary dig-through route or,
        // failing that, the recovery ladder's dig-out).
        buildIsland(context, feet, z -> Blocks.STONE.defaultBlockState());
        AIPlayerEntity bot = spawnBot(world, "DigWallBot", feet.offset(4, 0, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        AIPlayerEntity target = spawnBot(world, "DigWallTgt", feet.offset(17, 0, 6));
        holdStill(target);
        FollowTask follow = new FollowTask("DigWallTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_dig_wall"));
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            for (int x = 4; x <= 17; x++) {
                for (int z = 5; z <= 7; z++) {
                    require(context, !world.getBlockState(feet.offset(x, -1, z)).isAir(),
                            "the follower dug out the floor under a wall-crossing route at " + x + "," + z);
                }
            }
            if (bot.getX() >= feet.getX() + 12.0D && bot.distanceTo(target) <= 4.5D && follow.isWaiting()) {
                require(context, now > 5, "impossible instant arrival");
                despawn(world, bot, target);
                context.succeed();
            }
        });
    }

    /**
     * The recovery ladder's dig-out step, driven directly: four bots stand against the same
     * 2-high, 2-thick wall on a sealed island (no walk route).  Only the bot that carries a pickaxe
     * and faces plain natural stone may dig through -- and only after its whole stall window, as the
     * last step; the bot without a tool, the bot facing a cobblestone wall and the bot facing a
     * planks wall must leave the wall exactly as it was. (Plain stone that a player placed is
     * indistinguishable from natural stone; only building-block types and the bots' own placed-block
     * ledger are refused -- see {@link FollowDigOut}.)
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_stuck_recovery_digs_plain_stone_only_with_tool_never_building_blocks", maxTicks = 700)
    public void stuckRecoveryDigsPlainStoneOnlyWithToolNeverBuildingBlocks(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(0, 24, 0));
        ServerLevel world = context.getLevel();
        buildIsland(context, feet, z -> z >= 10 ? Blocks.OAK_PLANKS.defaultBlockState()
                : z >= 7 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        // Lane z=2: pickaxe + stone.  Lane z=5: stone but no tool.  Lane z=8: pickaxe + cobblestone.
        // Lane z=11: pickaxe + planks.
        AIPlayerEntity digger = spawnBot(world, "RecDigBot", feet.offset(9, 0, 2));
        InventoryAction.giveItem(digger, new ItemStack(Items.IRON_PICKAXE, 1));
        AIPlayerEntity toolless = spawnBot(world, "RecNoToolBot", feet.offset(9, 0, 5));
        AIPlayerEntity cobbler = spawnBot(world, "RecCobbleBot", feet.offset(9, 0, 8));
        InventoryAction.giveItem(cobbler, new ItemStack(Items.IRON_PICKAXE, 1));
        AIPlayerEntity builder = spawnBot(world, "RecPlanksBot", feet.offset(9, 0, 11));
        InventoryAction.giveItem(builder, new ItemStack(Items.IRON_PICKAXE, 1));
        // One target per lane, so no bot is ever "closer" by sidestepping into another lane.
        AIPlayerEntity diggerTarget = spawnBot(world, "RecDigTgt", feet.offset(20, 0, 2));
        AIPlayerEntity toollessTarget = spawnBot(world, "RecNoToolTgt", feet.offset(20, 0, 5));
        AIPlayerEntity cobblerTarget = spawnBot(world, "RecCobbleTgt", feet.offset(20, 0, 8));
        AIPlayerEntity builderTarget = spawnBot(world, "RecPlanksTgt", feet.offset(20, 0, 11));
        for (AIPlayerEntity bot : new AIPlayerEntity[]{digger, toolless, cobbler, builder, diggerTarget,
                toollessTarget, cobblerTarget, builderTarget}) {
            holdStill(bot);
        }
        FollowStuckRecovery diggerRecovery = new FollowStuckRecovery();
        FollowStuckRecovery toollessRecovery = new FollowStuckRecovery();
        FollowStuckRecovery cobblerRecovery = new FollowStuckRecovery();
        FollowStuckRecovery builderRecovery = new FollowStuckRecovery();
        diggerRecovery.reset(digger, 0);
        toollessRecovery.reset(toolless, 0);
        cobblerRecovery.reset(cobbler, 0);
        builderRecovery.reset(builder, 0);
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            diggerRecovery.tick(digger, diggerTarget, now, 3.0D);
            toollessRecovery.tick(toolless, toollessTarget, now, 3.0D);
            cobblerRecovery.tick(cobbler, cobblerTarget, now, 3.0D);
            builderRecovery.tick(builder, builderTarget, now, 3.0D);
            if (now < 95) {
                require(context, wallIntact(world, feet, 0, 12) && digger.getX() < feet.getX() + 10.0D,
                        "dig-out fired before the stall window elapsed (tick " + now + ")");
            }
            require(context, wallIntact(world, feet, 4, 6),
                    "the bot without a tool dug the wall (tick " + now + ")");
            require(context, wallIntact(world, feet, 7, 9),
                    "the bot dug a cobblestone (building block) wall (tick " + now + ")");
            require(context, wallIntact(world, feet, 10, 12),
                    "the bot dug a planks wall (tick " + now + ")");
            require(context, toolless.getX() < feet.getX() + 10.0D && cobbler.getX() < feet.getX() + 10.0D
                            && builder.getX() < feet.getX() + 10.0D,
                    "a bot without a legal dig walked through the wall (tick " + now + ")");
            for (int x = 4; x <= 17; x++) {
                require(context, !world.getBlockState(feet.offset(x, -1, 2)).isAir(),
                        "dig-out removed the floor at x=" + x);
            }
            if (digger.getX() >= feet.getX() + 12.0D) {
                require(context, now >= 95, "impossible instant dig-through");
                require(context, wallIntact(world, feet, 4, 12),
                        "the digger broke more than its own lane");
                despawn(world, digger, toolless, cobbler, builder, diggerTarget, toollessTarget,
                        cobblerTarget, builderTarget);
                context.succeed();
            }
        });
    }

    /**
     * Wading is ordinary walking: a bot one step from a bank, feet wet in a 1-deep shallow, must be led
     * out by land follow (its pathfinder and start snap) without the swim exit ever running a water
     * search.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_wading_bot_next_to_shore_walks_out_with_land_follow_and_no_water_searches", maxTicks = 500)
    public void wadingBotNextToShoreWalksOutWithLandFollowAndNoWaterSearches(GameTestHelper context) {
        Pond pond = buildPond(context, 8, 19, 1, 26);
        ServerLevel world = context.getLevel();
        AIPlayerEntity target = spawnBot(world, "WadeShoreTgt", pond.feet().offset(2, 0, LANE_Z));
        holdStill(target);
        AIPlayerEntity bot = spawnBot(world, "WadeShoreBot", pond.feet().offset(8, -1, LANE_Z));
        FollowTask follow = new FollowTask("WadeShoreTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_wade_shore"));
        AtomicInteger tick = new AtomicInteger();
        boolean[] startedWet = {false};
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            startedWet[0] |= bot.isInWater();
            require(context, follow.swimRouteSearchCount() == 0,
                    "the swim exit ran a water search for a bot that was only wading (tick " + now + ")");
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net's water crisis took over a wading bot at tick " + now);
            if (now > 10 && !bot.isInWater() && bot.distanceTo(target) <= 4.5D && follow.isWaiting()) {
                require(context, startedWet[0], "the bot never stood in the shallow");
                finish(context, pond, bot, target);
            }
        });
    }

    /**
     * In the middle of a shallow land follow has no legal start cell (wet cells are never standable and
     * its snap only reaches a dry neighbour), so the swim exit wades the bot out along a water route.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_bot_wading_mid_shallows_wades_out_to_land_target", maxTicks = 700)
    public void botWadingMidShallowsWadesOutToLandTarget(GameTestHelper context) {
        Pond pond = buildPond(context, 8, 19, 1, 26);
        ServerLevel world = context.getLevel();
        AIPlayerEntity target = spawnBot(world, "WadeMidTgt", pond.feet().offset(2, 0, LANE_Z));
        holdStill(target);
        AIPlayerEntity bot = spawnBot(world, "WadeMidBot", pond.feet().offset(14, -1, LANE_Z));
        FollowTask follow = new FollowTask("WadeMidTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_wade_mid"));
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net's water crisis took over a wading bot at tick " + now);
            require(context, follow.swimRouteSearchCount() <= 4,
                    "wading out ran a water search on every cooldown: " + follow.swimRouteSearchCount());
            if (now > 10 && !bot.isInWater() && bot.distanceTo(target) <= 4.5D && follow.isWaiting()) {
                finish(context, pond, bot, target);
            }
        });
    }

    /**
     * Heading for land does not excuse holding one's breath: a bot on the pond floor with little air
     * whose player has climbed out must still turn up for air first (the follow-first air floor),
     * not dive on toward a distant landing.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_submerged_bot_heading_for_land_still_surfaces_for_air_first", maxTicks = 700)
    public void submergedBotHeadingForLandStillSurfacesForAirFirst(GameTestHelper context) {
        Pond pond = buildPond(context, 8, 19, 8, 26);
        ServerLevel world = context.getLevel();
        AIPlayerEntity target = spawnBot(world, "ExitAirTgt", pond.feet().offset(2, 0, LANE_Z));
        holdStill(target);
        AIPlayerEntity bot = spawnBot(world, "ExitAirBot", pond.feet().offset(14, -7, LANE_Z));
        bot.setAirSupply(FollowOxygen.SURFACE_FLOOR_AIR + 2);
        FollowTask follow = new FollowTask("ExitAirTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_exit_air"));
        AtomicInteger tick = new AtomicInteger();
        int[] minAir = {Integer.MAX_VALUE};
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            minAir[0] = Math.min(minAir[0], bot.getAirSupply());
            require(context, bot.getAirSupply() > FollowOxygen.RESCUE_AIR,
                    "the bot let the drowning rescue take over while climbing out: air=" + bot.getAirSupply()
                            + " tick=" + now);
            if (now > 10 && !bot.isInWater() && bot.distanceTo(target) <= 4.5D && follow.isWaiting()) {
                require(context, follow.swimAscendCount() >= 1,
                        "the bot never turned up for breath while heading for land (min air " + minAir[0] + ")");
                finish(context, pond, bot, target);
            }
        });
    }

    private static boolean wallIntact(ServerLevel world, BlockPos feet, int zMin, int zMax) {
        for (int z = zMin; z <= zMax; z++) {
            for (int x = 10; x <= 11; x++) {
                for (int y = 0; y <= 1; y++) {
                    if (world.getBlockState(feet.offset(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * A sealed island: floor only inside x 0..24, z 0..12, split completely by a 2-high, 2-thick
     * wall at x 10..11 made of {@code wallForZ(z)}.
     */
    private static void buildIsland(GameTestHelper context, BlockPos feet, java.util.function.IntFunction<BlockState> wallForZ) {
        ServerLevel world = context.getLevel();
        forceChunks(context, feet, 26);
        for (int x = -2; x <= 26; x++) {
            for (int z = -2; z <= 14; z++) {
                boolean floor = x >= 0 && x <= 24 && z >= 0 && z <= 12;
                for (int y = -3; y <= 5; y++) {
                    BlockState state = Blocks.AIR.defaultBlockState();
                    if (floor && y <= -1) {
                        state = Blocks.STONE.defaultBlockState();
                    } else if (floor && (x == 10 || x == 11) && y >= 0 && y <= 1) {
                        state = wallForZ.apply(z);
                    }
                    world.setBlock(feet.offset(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }
    }

    // ---- fixture ---------------------------------------------------------------------------

    record Pond(BlockPos feet, int x0, int x1, int maxX, AABB area) {
    }

    /**
     * Land for the whole box except a pond of {@code depth} blocks spanning x in [x0, x1] and
     * z in [{@value #POND_Z0}, {@value #POND_Z1}]; the water surface block is flush with the bank.
     */
    static Pond buildPond(GameTestHelper context, int x0, int x1, int depth, int maxX) {
        ServerLevel world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(0, 24, 0));
        forceChunks(context, feet, maxX);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = -2; x <= maxX + 1; x++) {
            for (int z = -2; z <= MAX_Z + 1; z++) {
                boolean inPond = x >= x0 && x <= x1 && z >= POND_Z0 && z <= POND_Z1;
                for (int y = -depth - 3; y <= 6; y++) {
                    BlockState state;
                    if (inPond) {
                        state = y < -depth ? stone : (y <= -1 ? water : air);
                    } else {
                        state = y <= -1 ? stone : air;
                    }
                    world.setBlock(feet.offset(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }
        AABB area = AABB.encapsulatingFullBlocks(feet.offset(-40, -depth - 10, -40), feet.offset(maxX + 40, 12, MAX_Z + 40));
        // The flat gametest world spawns slimes in slime chunks regardless of light; one slain bot
        // fails an otherwise correct test, so keep the fixture monster-free.
        context.failIfEver(() -> world.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, area,
                mob -> mob instanceof net.minecraft.world.entity.monster.Enemy)
                .forEach(net.minecraft.world.entity.Entity::discard));
        return new Pond(feet, x0, x1, maxX, area);
    }

    /** Force-loads the chunks under the pond/island; released by {@link GameTestChunkForcing} (see there). */
    private static void forceChunks(GameTestHelper context, BlockPos feet, int maxX) {
        GameTestChunkForcing.forceForTest(context,
                (feet.getX() - 2) >> 4, (feet.getX() + maxX + 1) >> 4,
                (feet.getZ() - 2) >> 4, (feet.getZ() + MAX_Z + 1) >> 4);
    }

    static boolean noBoats(ServerLevel world, Pond pond) {
        return world.getEntitiesOfClass(AbstractBoat.class, pond.area(), boat -> true).isEmpty();
    }

    // ---- entity helpers --------------------------------------------------------------------

    static void holdStill(AIPlayerEntity bot) {
        TaskManager.INSTANCE.assign(bot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
    }

    /** Puts {@code swimmer} at a water position, topped up on air, under the narrow follow lease. */
    private static void swimTo(ServerLevel world, Pond pond, AIPlayerEntity swimmer, double x, double z, double y) {
        swimmer.teleportTo(world, pond.feet().getX() + x, pond.feet().getY() + y, pond.feet().getZ() + z,
                Set.of(), 0.0F, 0.0F, true);
        swimmer.setDeltaMovement(Vec3.ZERO);
        swimmer.fallDistance = 0.0F;
        swimmer.setAirSupply(swimmer.getMaxAirSupply());
        NavSafetyNet.INSTANCE.renewFollowSwim(swimmer);
    }

    private static void standOn(ServerLevel world, Pond pond, AIPlayerEntity player, double x, double z, double y) {
        player.teleportTo(world, pond.feet().getX() + x, pond.feet().getY() + y, pond.feet().getZ() + z,
                Set.of(), 0.0F, 0.0F, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setOnGround(true);
    }

    static AIPlayerEntity spawnBot(ServerLevel world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    static void finish(GameTestHelper context, Pond pond, AIPlayerEntity bot, AIPlayerEntity target) {
        TaskManager.INSTANCE.abort(bot);
        despawn(context.getLevel(), bot, target);
        context.succeed();
    }

    private static void despawn(ServerLevel world, AIPlayerEntity... bots) {
        for (AIPlayerEntity bot : bots) {
            DangerWatcher.INSTANCE.clear(bot);
            NavSafetyNet.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
        }
    }

    static void requireRunning(GameTestHelper context, FollowTask follow, AIPlayerEntity bot) {
        require(context, follow.state() == TaskState.RUNNING || follow.state() == TaskState.PAUSED,
                "follow ended early: state=" + follow.state() + " reason=" + follow.failureReason()
                        + " botPos=" + bot.position() + " air=" + bot.getAirSupply());
    }

    static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
