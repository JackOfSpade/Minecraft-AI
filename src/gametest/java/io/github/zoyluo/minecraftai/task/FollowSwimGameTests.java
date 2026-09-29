package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

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
    public void botOnShoreSwimsAfterTargetAcrossPondWithoutBoat(TestContext context) {
        Pond pond = buildPond(context, 8, 19, 3, 26);
        ServerWorld world = context.getWorld();
        AIPlayerEntity bot = spawnBot(world, "SwimCrossBot", pond.feet().add(3, 0, LANE_Z));
        AIPlayerEntity target = spawnBot(world, "SwimCrossTgt", pond.feet().add(9, 0, LANE_Z));
        holdStill(target);
        swimTo(world, pond, target, 9.5D, LANE_Z + 0.5D, SWIM_Y);
        FollowTask follow = new FollowTask("SwimCrossTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_swim_cross"));
        double[] targetX = {9.5D};
        boolean[] sawWater = {false};
        AtomicInteger tick = new AtomicInteger();
        context.runAtEveryTick(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net's water crisis took over a healthy swim follow at tick " + now);
            require(context, bot.getVehicle() == null && noBoats(world, pond),
                    "follow used a boat for a swimming target");
            sawWater[0] |= bot.isTouchingWater();
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
            if (targetX[0] >= 20.0D && now > 120 && !bot.isTouchingWater()
                    && bot.getX() >= pond.feet().getX() + 19.5D && distance <= 5.0D) {
                require(context, sawWater[0], "the bot reached the far shore without ever entering the water");
                finish(context, pond, bot, target);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_dives_after_target_resurfaces_before_drowning_then_goes_back_down", maxTicks = 1500)
    public void divesAfterTargetResurfacesBeforeDrowningThenGoesBackDown(TestContext context) {
        runDive(context, false, "DiveBot", "DiveTgt");
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_water_breathing_bot_stays_down_with_diving_target", maxTicks = 1200)
    public void waterBreathingBotStaysDownWithDivingTarget(TestContext context) {
        runDive(context, true, "DiveWbBot", "DiveWbTgt");
    }

    /**
     * Shared dive fixture: a 12-deep pond, the target dives to ~9 blocks and stays.  Without water
     * breathing the bot must go down, resurface before drowning and go back down; with it the bot
     * must simply stay near the target for the whole hold and never be forced up.
     */
    private static void runDive(TestContext context, boolean waterBreathing, String botName, String targetName) {
        Pond pond = buildPond(context, 8, 17, 12, 22);
        ServerWorld world = context.getWorld();
        AIPlayerEntity bot = spawnBot(world, botName, pond.feet().add(4, 0, LANE_Z));
        AIPlayerEntity target = spawnBot(world, targetName, pond.feet().add(12, 0, LANE_Z));
        holdStill(target);
        swimTo(world, pond, target, 12.5D, LANE_Z + 0.5D, SWIM_Y);
        if (waterBreathing) {
            bot.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 20 * 60 * 5, 0, false, false));
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
        context.runAtEveryTick(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            double botDepthY = bot.getY() - pond.feet().getY();
            minAir[0] = Math.min(minAir[0], bot.getAir());
            require(context, bot.getAir() > 0 && bot.getHealth() >= bot.getMaxHealth(),
                    "the bot was drowning while following a diver: air=" + bot.getAir()
                            + " health=" + bot.getHealth() + " y=" + botDepthY + " tick=" + now);
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net had to rescue a healthy dive follow at tick " + now
                            + " air=" + bot.getAir() + " y=" + botDepthY);
            if (stage[0] == 0) {
                swimTo(world, pond, target, 12.5D, LANE_Z + 0.5D, SWIM_Y);
                if (bot.isTouchingWater() && bot.distanceTo(target) <= 6.0D) {
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
                                    + " air=" + bot.getAir() + " held=" + holdTicks[0]);
                    if (holdTicks[0] >= 420) {
                        require(context, bot.getAir() >= 250,
                                "Water Breathing bot lost air anyway: " + bot.getAir());
                        finish(context, pond, bot, target);
                    }
                }
                return;
            }
            if (stage[0] == 1 && deep) {
                stage[0] = 2;
            } else if (stage[0] == 2 && botDepthY >= -2.5D && !bot.isSubmergedInWater()) {
                stage[0] = 3;
            } else if (stage[0] == 3 && deep) {
                require(context, minAir[0] > 0, "air hit zero during the dive");
                finish(context, pond, bot, target);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_shallow_water_swim_has_no_follow_and_safety_net_ping_pong", maxTicks = 700)
    public void shallowWaterSwimHasNoFollowAndSafetyNetPingPong(TestContext context) {
        Pond pond = buildPond(context, 8, 19, 2, 26);
        ServerWorld world = context.getWorld();
        AIPlayerEntity bot = spawnBot(world, "ShallowBot", pond.feet().add(3, 0, LANE_Z));
        AIPlayerEntity target = spawnBot(world, "ShallowTgt", pond.feet().add(9, 0, LANE_Z));
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
        context.runAtEveryTick(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                    "the safety net's water crisis fought the shallow swim follow at tick " + now);
            if (bot.isTouchingWater()) {
                wetTicks[0]++;
            }
            BlockPos here = bot.getBlockPos();
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
            if (targetX[0] >= 19.5D && now > 200 && wetTicks[0] > 60 && bot.isTouchingWater()
                    && bot.distanceTo(target) <= 5.0D) {
                finish(context, pond, bot, target);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_stuck_behind_natural_wall_digs_through_with_its_tools", maxTicks = 1500)
    public void stuckBehindNaturalWallDigsThroughWithItsTools(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(0, 24, 0));
        ServerWorld world = context.getWorld();
        // Sealed island: floor only exists inside the platform, a 2-high, 2-thick natural stone
        // wall splits it completely, and the bot carries no placeable block, so no walking route
        // exists (the follow order alone must get it through, by the ordinary dig-through route or,
        // failing that, the recovery ladder's dig-out).
        buildIsland(world, feet, z -> Blocks.STONE.getDefaultState());
        AIPlayerEntity bot = spawnBot(world, "DigWallBot", feet.add(4, 0, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        AIPlayerEntity target = spawnBot(world, "DigWallTgt", feet.add(17, 0, 6));
        holdStill(target);
        FollowTask follow = new FollowTask("DigWallTgt");
        TaskManager.INSTANCE.assign(bot, follow,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_dig_wall"));
        AtomicInteger tick = new AtomicInteger();
        context.runAtEveryTick(() -> {
            int now = tick.incrementAndGet();
            requireRunning(context, follow, bot);
            for (int x = 4; x <= 17; x++) {
                for (int z = 5; z <= 7; z++) {
                    require(context, !world.getBlockState(feet.add(x, -1, z)).isAir(),
                            "the follower dug out the floor under a wall-crossing route at " + x + "," + z);
                }
            }
            if (bot.getX() >= feet.getX() + 12.0D && bot.distanceTo(target) <= 4.5D && follow.isWaiting()) {
                require(context, now > 5, "impossible instant arrival");
                forceChunks(world, feet, 26, false);
                despawn(world, bot, target);
                context.complete();
            }
        });
    }

    /**
     * The recovery ladder's dig-out step, driven directly: three bots stand against the same
     * 2-high, 2-thick wall on a sealed island (no walk route).  Only the bot that carries a pickaxe
     * and faces natural stone may dig through -- and only after its whole stall window, as the last
     * step; the bot without a tool and the bot facing a player-built planks wall must leave the wall
     * exactly as it was.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_stuck_recovery_digs_natural_stone_only_with_a_tool_and_never_player_built_walls", maxTicks = 700)
    public void stuckRecoveryDigsNaturalStoneOnlyWithAToolAndNeverPlayerBuiltWalls(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(0, 24, 0));
        ServerWorld world = context.getWorld();
        buildIsland(world, feet, z -> z >= 8 ? Blocks.OAK_PLANKS.getDefaultState() : Blocks.STONE.getDefaultState());
        // Lane z=2: pickaxe + stone.  Lane z=5: stone but no tool.  Lane z=10: pickaxe + planks.
        AIPlayerEntity digger = spawnBot(world, "RecDigBot", feet.add(9, 0, 2));
        InventoryAction.giveItem(digger, new ItemStack(Items.IRON_PICKAXE, 1));
        AIPlayerEntity toolless = spawnBot(world, "RecNoToolBot", feet.add(9, 0, 5));
        AIPlayerEntity builder = spawnBot(world, "RecPlanksBot", feet.add(9, 0, 10));
        InventoryAction.giveItem(builder, new ItemStack(Items.IRON_PICKAXE, 1));
        // One target per lane, so no bot is ever "closer" by sidestepping into another lane.
        AIPlayerEntity diggerTarget = spawnBot(world, "RecDigTgt", feet.add(20, 0, 2));
        AIPlayerEntity toollessTarget = spawnBot(world, "RecNoToolTgt", feet.add(20, 0, 5));
        AIPlayerEntity builderTarget = spawnBot(world, "RecPlanksTgt", feet.add(20, 0, 10));
        for (AIPlayerEntity bot : new AIPlayerEntity[]{digger, toolless, builder, diggerTarget, toollessTarget, builderTarget}) {
            holdStill(bot);
        }
        FollowStuckRecovery diggerRecovery = new FollowStuckRecovery();
        FollowStuckRecovery toollessRecovery = new FollowStuckRecovery();
        FollowStuckRecovery builderRecovery = new FollowStuckRecovery();
        diggerRecovery.reset(digger, 0);
        toollessRecovery.reset(toolless, 0);
        builderRecovery.reset(builder, 0);
        AtomicInteger tick = new AtomicInteger();
        context.runAtEveryTick(() -> {
            int now = tick.incrementAndGet();
            diggerRecovery.tick(digger, diggerTarget, now, 3.0D);
            toollessRecovery.tick(toolless, toollessTarget, now, 3.0D);
            builderRecovery.tick(builder, builderTarget, now, 3.0D);
            if (now < 95) {
                require(context, wallIntact(world, feet, 0, 12) && digger.getX() < feet.getX() + 10.0D,
                        "dig-out fired before the stall window elapsed (tick " + now + ")");
            }
            require(context, wallIntact(world, feet, 4, 6),
                    "the bot without a tool dug the wall (tick " + now + ")");
            require(context, wallIntact(world, feet, 8, 12),
                    "the bot dug a player-built planks wall (tick " + now + ")");
            require(context, toolless.getX() < feet.getX() + 10.0D && builder.getX() < feet.getX() + 10.0D,
                    "a bot without a legal dig walked through the wall (tick " + now + ")");
            for (int x = 4; x <= 17; x++) {
                require(context, !world.getBlockState(feet.add(x, -1, 2)).isAir(),
                        "dig-out removed the floor at x=" + x);
            }
            if (digger.getX() >= feet.getX() + 12.0D) {
                require(context, now >= 95, "impossible instant dig-through");
                require(context, wallIntact(world, feet, 4, 12),
                        "the digger broke more than its own lane");
                despawn(world, digger, toolless, builder, diggerTarget, toollessTarget, builderTarget);
                context.complete();
            }
        });
    }

    private static boolean wallIntact(ServerWorld world, BlockPos feet, int zMin, int zMax) {
        for (int z = zMin; z <= zMax; z++) {
            for (int x = 10; x <= 11; x++) {
                for (int y = 0; y <= 1; y++) {
                    if (world.getBlockState(feet.add(x, y, z)).isAir()) {
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
    private static void buildIsland(ServerWorld world, BlockPos feet, java.util.function.IntFunction<BlockState> wallForZ) {
        forceChunks(world, feet, 26, true);
        for (int x = -2; x <= 26; x++) {
            for (int z = -2; z <= 14; z++) {
                boolean floor = x >= 0 && x <= 24 && z >= 0 && z <= 12;
                for (int y = -3; y <= 5; y++) {
                    BlockState state = Blocks.AIR.getDefaultState();
                    if (floor && y <= -1) {
                        state = Blocks.STONE.getDefaultState();
                    } else if (floor && (x == 10 || x == 11) && y >= 0 && y <= 1) {
                        state = wallForZ.apply(z);
                    }
                    world.setBlockState(feet.add(x, y, z), state, Block.NOTIFY_ALL);
                }
            }
        }
    }

    // ---- fixture ---------------------------------------------------------------------------

    record Pond(BlockPos feet, int x0, int x1, int maxX, Box area) {
    }

    /**
     * Land for the whole box except a pond of {@code depth} blocks spanning x in [x0, x1] and
     * z in [{@value #POND_Z0}, {@value #POND_Z1}]; the water surface block is flush with the bank.
     */
    static Pond buildPond(TestContext context, int x0, int x1, int depth, int maxX) {
        ServerWorld world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(new BlockPos(0, 24, 0));
        forceChunks(world, feet, maxX, true);
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState water = Blocks.WATER.getDefaultState();
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
                    world.setBlockState(feet.add(x, y, z), state, Block.NOTIFY_ALL);
                }
            }
        }
        Box area = Box.enclosing(feet.add(-40, -depth - 10, -40), feet.add(maxX + 40, 12, MAX_Z + 40));
        // The flat gametest world spawns slimes in slime chunks regardless of light; one slain bot
        // fails an otherwise correct test, so keep the fixture monster-free.
        context.runAtEveryTick(() -> world.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, area,
                mob -> mob instanceof net.minecraft.entity.mob.Monster)
                .forEach(net.minecraft.entity.Entity::discard));
        return new Pond(feet, x0, x1, maxX, area);
    }

    private static void forceChunks(ServerWorld world, BlockPos feet, int maxX, boolean forced) {
        for (int cx = (feet.getX() - 2) >> 4; cx <= (feet.getX() + maxX + 1) >> 4; cx++) {
            for (int cz = (feet.getZ() - 2) >> 4; cz <= (feet.getZ() + MAX_Z + 1) >> 4; cz++) {
                world.setChunkForced(cx, cz, forced);
            }
        }
    }

    static boolean noBoats(ServerWorld world, Pond pond) {
        return world.getEntitiesByClass(AbstractBoatEntity.class, pond.area(), boat -> true).isEmpty();
    }

    // ---- entity helpers --------------------------------------------------------------------

    static void holdStill(AIPlayerEntity bot) {
        TaskManager.INSTANCE.assign(bot, new HoldTask(),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hold_still"));
    }

    /** Puts {@code swimmer} at a water position, topped up on air, under the narrow follow lease. */
    private static void swimTo(ServerWorld world, Pond pond, AIPlayerEntity swimmer, double x, double z, double y) {
        swimmer.teleport(world, pond.feet().getX() + x, pond.feet().getY() + y, pond.feet().getZ() + z,
                Set.of(), 0.0F, 0.0F, true);
        swimmer.setVelocity(Vec3d.ZERO);
        swimmer.fallDistance = 0.0F;
        swimmer.setAir(swimmer.getMaxAir());
        NavSafetyNet.INSTANCE.renewFollowSwim(swimmer);
    }

    private static void standOn(ServerWorld world, Pond pond, AIPlayerEntity player, double x, double z, double y) {
        player.teleport(world, pond.feet().getX() + x, pond.feet().getY() + y, pond.feet().getZ() + z,
                Set.of(), 0.0F, 0.0F, true);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0.0F;
        player.setOnGround(true);
    }

    static AIPlayerEntity spawnBot(ServerWorld world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return bot;
    }

    static void finish(TestContext context, Pond pond, AIPlayerEntity bot, AIPlayerEntity target) {
        forceChunks(context.getWorld(), pond.feet(), pond.maxX(), false);
        TaskManager.INSTANCE.abort(bot);
        despawn(context.getWorld(), bot, target);
        context.complete();
    }

    private static void despawn(ServerWorld world, AIPlayerEntity... bots) {
        for (AIPlayerEntity bot : bots) {
            DangerWatcher.INSTANCE.clear(bot);
            NavSafetyNet.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
        }
    }

    static void requireRunning(TestContext context, FollowTask follow, AIPlayerEntity bot) {
        require(context, follow.state() == TaskState.RUNNING || follow.state() == TaskState.PAUSED,
                "follow ended early: state=" + follow.state() + " reason=" + follow.failureReason()
                        + " botPos=" + bot.getEntityPos() + " air=" + bot.getAir());
    }

    static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}
