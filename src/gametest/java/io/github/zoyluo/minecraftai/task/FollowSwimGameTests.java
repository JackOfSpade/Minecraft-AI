package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.baritone.BaritoneGoals;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
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
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
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

    /**
     * A FollowTask runs before NavSafetyNet. In a twelve-cell shaft, 140 air is above the old
     * fixed 120 floor but below the depth-aware rescue boundary. Follow must therefore write no
     * swim step, hand the writer to NavSafetyNet, and keep its hands off that rescue step on the
     * following task tick.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_deep_water_follow_hands_writer_to_safety_without_ping_pong", maxTicks = 80)
    public void deepWaterFollowHandsWriterToSafetyWithoutPingPong(GameTestHelper context) {
        DeepFollowShaft shaft = buildDeepFollowShaft(context);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnBot(world, "DeepFollowHandoffBot", shaft.lower());
        AIPlayerEntity target = spawnBot(world, "DeepFollowHandoffTarget", shaft.lower().east(4).above());
        submerge(world, bot, shaft.lower());
        submerge(world, target, shaft.lower().east(4).above());
        // A teleport updates the fake player's position immediately, but its water flags on the
        // next entity tick. Wait for that genuine fluid tick before any Follow/Safety writer runs.
        context.runAfterDelay(2, () -> {
            require(context, world.getFluidState(shaft.lower()).is(FluidTags.WATER)
                            && world.getFluidState(shaft.lower().east(4).above()).is(FluidTags.WATER),
                    "deep follow shaft did not retain water at the two submerged spawn cells");
            require(context, bot.isUnderWater() && target.isUnderWater(),
                    "fixture did not leave both follower and target genuinely submerged");
            bot.setAirSupply(140);
            target.setAirSupply(target.getMaxAirSupply());
            TeleportAudit.reset(bot);

            FollowTask follow = new FollowTask(target.getGameProfile().name());
            TaskManager.INSTANCE.assign(bot, follow,
                    TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_follow_swim_deep_handoff"));

            // This is the production writer order: TaskManager gives Follow its tick, then the safety
            // coordinator decides whether it owns the same tick.  It is deliberately not a safety-net
            // unit test: the assertion before tickBot proves Follow did not write the competing step.
            TaskManager.INSTANCE.tickAll(world.getServer());
            requireRunning(context, follow, bot);
            require(context, bot.isUnderWater() && target.isUnderWater(),
                    "fixture lost a submerged follower or target before the writer handoff");
            require(context, NavSafetyNet.surfaceAirThresholdForDepth(DEEP_FOLLOW_SHAFT_DEPTH) > bot.getAirSupply(),
                    "fixture air did not fall below the deep-water rescue boundary");
            require(context, bot.getActionPack().stepIdle(),
                    "Follow wrote a swim step after the depth-aware rescue boundary");

            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "NavSafetyNet did not take the writer after Follow yielded");
            require(context, bot.getActionPack().stepInFlightFor("navsafe_water_surface", shaft.lower().above(), WalkedStep.Kind.SWIM),
                    "NavSafetyNet did not begin its exact physical upward rescue step");

            // On the next real task writer turn, Follow still yields instead of cancelling or replacing
            // the rescue step. A second safety turn must retain that same exact owner: no ping-pong.
            TaskManager.INSTANCE.tickAll(world.getServer());
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == follow,
                    "the follow task was unexpectedly replaced during the writer handoff");
            require(context, bot.getActionPack().stepInFlightFor("navsafe_water_surface", shaft.lower().above(), WalkedStep.Kind.SWIM),
                    "Follow cancelled or replaced NavSafetyNet's exact rescue step");
            require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                    "NavSafetyNet did not retain its physical rescue writer");
            require(context, bot.getActionPack().stepInFlightFor("navsafe_water_surface", shaft.lower().above(), WalkedStep.Kind.SWIM),
                    "the follow and safety writers ping-ponged NavSafetyNet's exact rescue step away");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "deep-water handoff used a correction teleport: " + TeleportAudit.lastCaller(bot));

            TaskManager.INSTANCE.abort(bot);
            despawn(world, bot, target);
            context.succeed();
        });
    }

    /**
     * A healthy swimmer in a clear shaft deeper than one cooperative vertical slice still has an
     * unfinished air-column cursor, not proof that air is absent. It must keep its ordinary
     * Follow stroke instead of cancelling it to wait for a nonexistent terminal result.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_pending_air_column_keeps_healthy_follow_stroke", maxTicks = 30)
    public void strictPendingAirColumnKeepsHealthyFollowStroke(GameTestHelper context) {
        DeepFollowShaft shaft = buildDeepFollowShaft(context);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnBot(world, "StrictPendingAirBot", shaft.lower());
        AIPlayerEntity target = spawnBot(world, "StrictPendingAirTarget", shaft.lower().east(4).above());
        submerge(world, bot, shaft.lower());
        submerge(world, target, shaft.lower().east(4).above());
        context.runAfterDelay(2, () -> {
            MinecraftAiConfig original = MinecraftAiConfig.get();
            FollowSwimming follower = new FollowSwimming();
            try {
                installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
                require(context, bot.isUnderWater() && target.isUnderWater(),
                        "pending-column fixture did not leave both bots submerged");
                require(context, DEEP_FOLLOW_SHAFT_DEPTH > 8,
                        "fixture must exceed FollowSwimming's bounded vertical air slice");
                bot.setAirSupply(bot.getMaxAirSupply());
                target.setAirSupply(target.getMaxAirSupply());
                follower.follow(bot, target, 1, 0.25D);
                require(context, bot.getActionPack().stepInFlightFor(
                                "follow_swim", shaft.lower().east(), WalkedStep.Kind.SWIM),
                        "a pending clear air column cancelled healthy follow instead of starting its visible swim stroke");
            } finally {
                follower.cancelStep(bot);
                installConfig(original);
                despawn(world, bot, target);
            }
            context.succeed();
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

    /**
     * A visible local water neighbour is allowed to be explored later, but the follow route
     * planner must not turn a shore hidden behind an opaque bend into an EXIT route. This is the
     * route-level companion to SurfaceWaterRecoveryGameTests' visible-local-step rescue proof.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_route_does_not_plan_through_a_hidden_shore", maxTicks = 20)
    public void strictRouteDoesNotPlanThroughAHiddenShore(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -36));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        // Pick fixed, non-overlapping cardinal cells rather than relying on the 26-neighbour
        // enumeration order (whose first entry is now diagonal).
        BlockPos visibleDeadEnd = start.west();
        BlockPos hiddenRouteFirst = start.north();
        require(context, NavSafetyNet.waterEscapeNeighbors(start).containsAll(
                        List.of(visibleDeadEnd, hiddenRouteFirst)),
                "water rescue neighbourhood lost required cardinal cells");
        int routeDx = hiddenRouteFirst.getX() - start.getX();
        int routeDz = hiddenRouteFirst.getZ() - start.getZ();
        // Keep every later route cell more than one diagonal move from the origin. The shared
        // rescue/follow neighbourhood deliberately includes diagonal strokes, so a one-cell bend
        // would otherwise let the raw control skip the intended hidden first edge.
        BlockPos forwardTwo = hiddenRouteFirst.offset(routeDx, 0, routeDz);
        int turnDx = -routeDz;
        int turnDz = routeDx;
        BlockPos bendOne = forwardTwo.offset(turnDx, 0, turnDz);
        BlockPos bendTwo = bendOne.offset(turnDx, 0, turnDz);
        BlockPos shore = bendTwo.offset(turnDx, 0, turnDz);
        List<BlockPos> waterRoute = List.of(start, visibleDeadEnd, hiddenRouteFirst, forwardTwo, bendOne, bendTwo);
        require(context, waterRoute.stream().distinct().count() == waterRoute.size(),
                "hidden-route differential accidentally reuses a water cell: " + waterRoute);
        for (BlockPos cell : waterRoute) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // Keep the eye above the source water.  Fluid-aware sight rays must reach the exposed
        // local step itself, not terminate on a waterlogged decoration in the bot's own column.
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        AIPlayerEntity bot = spawnBot(world, "StrictFollowRouteBot", start);
        poseWaterObserver(bot, start);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            // This must be a genuinely useful differential fixture, rather than merely proving
            // that strict mode returns no route in an accidentally disconnected maze.  The raw
            // operator planner knows the complete physical route and selects its hidden first
            // water step; strict survival must reject exactly that hidden knowledge.
            installConfig(withProfile(original, OperatingProfile.OPERATOR));
            require(context, CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "operator_follow_route_gametest").allowed(),
                    "operator control did not allow the raw water planner");
            Optional<List<BlockPos>> operatorRoute = SwimRoute.search(
                    bot, world, start, shore, SwimRoute.Goal.EXIT, 0.0D);
            require(context, operatorRoute.isPresent() && !operatorRoute.get().isEmpty(),
                    "operator planner did not find the physical hidden-shore route: " + operatorRoute);
            require(context, operatorRoute.get().get(0).equals(hiddenRouteFirst),
                    "operator planner did not choose the route's hidden first water step: " + operatorRoute);

            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_follow_route_gametest").allowed(),
                    "strict_survival unexpectedly allowed hidden water scans");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, visibleDeadEnd)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, visibleDeadEnd.above()),
                    "fixture must offer a visible local water cell");
            require(context, !ObservableWorldQuery.canObserveCellThroughFluids(bot, shore),
                    "fixture must keep the dry shore behind the opaque turn");

            Optional<List<BlockPos>> route = SwimRoute.search(bot, world, start, shore, SwimRoute.Goal.EXIT, 0.0D);
            require(context, route.isEmpty(),
                    "strict follow route planned to an unseen shore: " + route);
        } finally {
            installConfig(original);
            despawn(world, bot);
        }
        context.succeed();
    }

    /**
     * A visible lake edge may be farther than a tiny historical six-block heuristic.  It is still
     * ordinary player knowledge when it is inside the configured perception radius: strict follow
     * must make physical land progress to that observed shore and enter the water, not wait forever
     * or borrow a hidden scan.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_follow_approaches_a_visible_far_water_edge", maxTicks = 180)
    public void strictFollowApproachesAVisibleFarWaterEdge(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(0, 6, -52));
        for (int dx = -2; dx <= 13; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos shore = start.east(7);
        BlockPos water = shore.east();
        BlockPos targetWater = water.east(2);
        for (int dx = 8; dx <= 12; dx++) {
            BlockPos cell = start.east(dx);
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        AIPlayerEntity bot = spawnBot(world, "StrictFarWaterBot", start);
        AIPlayerEntity target = spawnBot(world, "StrictFarWaterTarget", targetWater);
        target.teleportTo(world, targetWater.getX() + 0.5D, targetWater.getY() + 0.125D,
                targetWater.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        target.setAirSupply(target.getMaxAirSupply());
        FollowSwimming follower = new FollowSwimming();
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withPerceptionRadius(withProfile(original, OperatingProfile.STRICT_SURVIVAL), 12));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_far_water_edge_gametest").allowed(),
                    "strict fixture unexpectedly enabled hidden-world scanning");
            require(context, MinecraftAiConfig.get().perception().radius() == 12
                            && SwimRoute.observationRadius(bot) == 12,
                    "strict route clamped its observation radius below the configured value");
            require(context, bot.getEyePosition().distanceToSqr(shore.getCenter()) > 36.0D
                            && bot.getEyePosition().distanceToSqr(water.getCenter()) > 36.0D,
                    "fixture edge did not actually exceed the old six-block heuristic");
            require(context, ObservableWorldQuery.canObserveCellThroughFluids(bot, shore)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, shore.above())
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, water)
                            && ObservableWorldQuery.canObserveCellThroughFluids(bot, water.above()),
                    "fixture did not leave the farther shore and water edge visibly exposed");
            TeleportAudit.reset(bot);
            require(context, !follower.follow(bot, target, 1, 1.0D),
                    "strict follow waited instead of beginning a physical approach to a visible water edge");
            require(context, bot.getActionPack().stepInFlightFor(
                            "follow_swim_observed_land_approach", start.east(), WalkedStep.Kind.FLAT),
                    "strict non-adjacent edge used a hidden path planner instead of its observed local land step");
        } finally {
            // The initial edge proof above is the behavior under the explicit radius. The remaining
            // real server ticks use the normal strict default, which is at least as permissive, and
            // this keeps a failing asynchronous GameTest from leaking a global config into another.
            installConfig(original);
        }

        int[] elapsed = {1};
        boolean[] sawLandProgress = {false};
        context.failIfEver(() -> {
            elapsed[0]++;
            follower.follow(bot, target, elapsed[0], 1.0D);
            sawLandProgress[0] |= bot.getX() > start.getX() + 1.25D;
            require(context, TeleportAudit.corrections(bot) == 0,
                    "strict far-edge follow used a correction teleport: " + TeleportAudit.lastCaller(bot));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_far_water_edge_progress").allowed(),
                    "strict far-edge follow enabled a hidden-world scan while approaching");
            boolean enteredWater = world.getFluidState(bot.blockPosition()).is(FluidTags.WATER);
            if (enteredWater) {
                require(context, sawLandProgress[0],
                        "bot entered the water without physically approaching the observed shore");
                follower.cancelStep(bot);
                despawn(world, bot, target);
                context.succeed();
                return;
            }
            require(context, elapsed[0] < 140,
                    "strict follow permanently waited before reaching its visible water edge");
        });
    }

    /**
     * Before a distant lake enters the configured perception radius, strict follow still knows its
     * named waterborne target. It may therefore take one real, currently visible dry step toward
     * that target. This fixture leaves a diagonal landing as the only dry local candidate; the two
     * corner cells are open but unsupported, so the test also protects legal diagonal land motion.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_no_edge_uses_diagonal_observed_land_approach", maxTicks = 30)
    public void strictNoEdgeUsesDiagonalObservedLandApproach(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(0, 6, -56));
        BlockPos diagonal = start.east().north();
        BlockPos shore = start.east(12).north();
        BlockPos water = shore.east();
        BlockPos targetWater = water.east();
        for (int dx = -1; dx <= 15; dx++) {
            for (int dz = -2; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // The origin and only diagonal landing have real footing. East and north are deliberately
        // unsupported but open: a diagonal walked step may sweep through them, whereas strict
        // approach must not select either as a dry destination.
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(diagonal.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos cell : List.of(water, targetWater)) {
            world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        AIPlayerEntity bot = spawnBot(world, "StrictDiagonalApproachBot", start);
        AIPlayerEntity target = spawnBot(world, "StrictDiagonalApproachTarget", targetWater);
        target.teleportTo(world, targetWater.getX() + 0.5D, targetWater.getY() + 0.125D,
                targetWater.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        FollowSwimming follower = new FollowSwimming();
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withPerceptionRadius(withProfile(original, OperatingProfile.STRICT_SURVIVAL), 12));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_diagonal_land_approach").allowed(),
                    "diagonal strict fixture unexpectedly enabled a hidden scan");
            require(context, !ObservableWorldQuery.canObserveCellThroughFluids(bot, shore)
                            && !ObservableWorldQuery.canObserveCellThroughFluids(bot, water),
                    "fixture accidentally exposed an entry edge before the local approach step");
            require(context, SwimRoute.observedCell(bot, world, diagonal, false) == SwimRoute.Cell.DRY,
                    "fixture lost its only visible diagonal dry landing");
            require(context, SwimRoute.observedCell(bot, world, start.east(), false) == null
                            && SwimRoute.observedCell(bot, world, start.north(), false) == null,
                    "cardinal corner cells accidentally became strict dry approach candidates");
            TeleportAudit.reset(bot);
            require(context, !follower.follow(bot, target, 1, 0.25D),
                    "strict follow waited despite a legal observed diagonal approach step");
            require(context, bot.getActionPack().stepInFlightFor(
                            "follow_swim_observed_land_approach", diagonal, WalkedStep.Kind.FLAT),
                    "strict no-edge approach did not start its only legal diagonal walked step");
            require(context, TeleportAudit.corrections(bot) == 0,
                    "strict diagonal land approach used a correction teleport: " + TeleportAudit.lastCaller(bot));
        } finally {
            follower.cancelStep(bot);
            installConfig(original);
            despawn(world, bot, target);
        }
        context.succeed();
    }

    /**
     * A destination can be visible even though a diagonal walk's corner column is behind a
     * separate opaque block. Strict follow must decline before {@link WalkedStep#refusal} reads
     * that hidden column; the exposed lower landing itself remains an ordinary observed dry cell.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_refusal_envelope_rejects_hidden_diagonal_corner", maxTicks = 30)
    public void strictRefusalEnvelopeRejectsHiddenDiagonalCorner(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(0, 6, -64));
        BlockPos landing = start.east().north().below();
        BlockPos blockedCornerHead = start.east().above();
        buildStrictRefusalEnvelopeFixture(world, start, landing, blockedCornerHead);
        // The north-offset eye enters these source-side cells before the diagonal landing shaft.
        // Keep that landing ray honest without opening the deliberately blocked east-corner head
        // cell that the refusal-envelope assertion must still reject.
        world.setBlock(start.north(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.north().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // It is a ray tunnel only, never a competing descending or flat landing for strict
        // follow's complete three-block-down local envelope.
        for (int dy = 1; dy <= 4; dy++) {
            world.setBlock(start.north().below(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos targetGoal = landing.east(2);
        clearDryTarget(world, targetGoal);

        AIPlayerEntity bot = spawnBot(world, "StrictHiddenCornerBot", start);
        // Look from the north side of the source cell: the landing ray enters its own diagonal
        // shaft before the blocked east-corner column, while the direct east-corner ray still
        // strikes that blocker.
        poseRaisedObserver(bot, start, 0.40D, -0.40D);
        AIPlayerEntity target = spawnBot(world, "StrictHiddenCornerTarget", targetGoal);
        FollowSwimming follower = new FollowSwimming();
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, SwimRoute.observedCell(bot, world, landing, false) == SwimRoute.Cell.DRY,
                    "the lower diagonal landing itself must remain visibly provable");
            require(context, SwimRoute.observedCell(bot, world, start.north(), false) != SwimRoute.Cell.DRY,
                    "the diagonal landing ray tunnel accidentally became a competing dry step");
            require(context, !ObservableWorldQuery.canObserveCellThroughFluids(bot, start.east()),
                    "fixture did not hide the diagonal corner column behind its head blocker");
            require(context, !SwimRoute.canObserveWalkedStepRefusalEnvelope(
                            bot, landing, WalkedStep.Kind.STEP_DOWN),
                    "strict diagonal fixture did not reject the hidden corner refusal envelope");
            TeleportAudit.reset(bot);
            require(context, follower.follow(bot, target, 1, 0.25D),
                    "strict follow started a diagonal step despite an unobservable corner envelope");
            require(context, bot.getActionPack().stepIdle() && bot.blockPosition().equals(start)
                            && TeleportAudit.corrections(bot) == 0,
                    "hidden diagonal-corner validation moved the bot instead of failing closed");
        } finally {
            follower.cancelStep(bot);
            installConfig(original);
            despawn(world, bot, target);
        }
        context.succeed();
    }

    /**
     * The destination and its support may be exposed below a ledge while a cell in a three-block
     * fall column is not. Strict follow must reject the whole DROP/STEP_DOWN sweep before the raw
     * validator probes its hidden vertical cells.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_refusal_envelope_rejects_hidden_deep_drop_column", maxTicks = 30)
    public void strictRefusalEnvelopeRejectsHiddenDeepDropColumn(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(0, 6, -72));
        BlockPos landing = start.east().below(2);
        BlockPos hiddenFallCellBlocker = landing.above(4);
        buildStrictRefusalEnvelopeFixture(world, start, landing, hiddenFallCellBlocker);
        BlockPos targetGoal = landing.east(2);
        clearDryTarget(world, targetGoal);

        AIPlayerEntity bot = spawnBot(world, "StrictHiddenDropBot", start);
        // Keep feet in the source cell but put the eye high enough that the far ledge hides the
        // upper swept cell, without blocking the lower landing or its support.
        poseRaisedObserver(bot, start, 0.90D);
        AIPlayerEntity target = spawnBot(world, "StrictHiddenDropTarget", targetGoal);
        FollowSwimming follower = new FollowSwimming();
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, SwimRoute.observedCell(bot, world, landing, false) == SwimRoute.Cell.DRY,
                    "the lower landing and its support must remain visibly provable");
            require(context, !ObservableWorldQuery.canObserveCellThroughFluids(bot, landing.above(3)),
                    "fixture did not hide an intermediate deep-drop sweep cell");
            require(context, !SwimRoute.canObserveWalkedStepRefusalEnvelope(
                            bot, landing, WalkedStep.Kind.STEP_DOWN),
                    "strict deep-drop fixture did not reject the hidden fall-column envelope");
            TeleportAudit.reset(bot);
            require(context, follower.follow(bot, target, 1, 0.25D),
                    "strict follow started a deep drop despite an unobservable fall-column cell");
            require(context, bot.getActionPack().stepIdle() && bot.blockPosition().equals(start)
                            && TeleportAudit.corrections(bot) == 0,
                    "hidden deep-drop validation moved the bot instead of failing closed");
        } finally {
            follower.cancelStep(bot);
            installConfig(original);
            despawn(world, bot, target);
        }
        context.succeed();
    }

    /**
     * A long, completely visible water corridor takes more than one strict search work slice.
     * PENDING must remain distinct from EMPTY and retain the partially consumed neighbour cursor,
     * otherwise a strict follow would either report a false no-route result or restart forever.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_route_search_resumes_after_pending_slice", maxTicks = 30)
    public void strictRouteSearchResumesAfterPendingSlice(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -80));
        for (int dx = -2; dx <= 6; dx++) {
            for (int dz = -4; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // Four water cells force more than one bounded 96-operation search slice because every
        // expanded node still consumes its full 26-cell neighbour envelope.  Under the scoped
        // water-navigation observer, a player can see through the water medium but not terrain,
        // so a straight, solid-walled lane gives the far shore an unambiguous physical sightline.
        BlockPos first = start.east();
        BlockPos second = start.east(2);
        BlockPos third = start.east(3);
        BlockPos shore = start.east(4);
        List<BlockPos> waterRoute = List.of(start, first, second, third);
        for (BlockPos cell : waterRoute) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // The shore's strict dry proof also observes its support. Open this one water-lane
        // support cell so the raised swimmer can honestly see that support ray rather than
        // turning an otherwise visible route into retained UNKNOWN work.
        world.setBlock(third.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        AIPlayerEntity bot = spawnBot(world, "StrictPendingRouteBot", start);
        poseWaterObserver(bot, start);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withPerceptionRadius(withProfile(original, OperatingProfile.STRICT_SURVIVAL), 20));
            require(context, SwimRoute.observedCell(bot, world, shore, false) == SwimRoute.Cell.DRY,
                    "the far shore and its support must be visibly/physically provable within the configured strict perception radius");
            SwimRoute.SearchProgress progress = SwimRoute.startSearch(
                    start, shore, SwimRoute.Goal.EXIT, 0.0D, false);
            SwimRoute.SearchResult result = progress.advance(bot, world,
                    SwimRoute.STRICT_SEARCH_CANDIDATES_PER_TICK);
            require(context, result.status() == SwimRoute.SearchStatus.PENDING && result.path().isEmpty(),
                    "a bounded strict route slice reported an empty/final route instead of PENDING: " + result.status());

            int slices = 1;
            while (result.status() == SwimRoute.SearchStatus.PENDING && slices < 8) {
                result = progress.advance(bot, world, SwimRoute.STRICT_SEARCH_CANDIDATES_PER_TICK);
                slices++;
            }
            require(context, slices > 1 && result.status() == SwimRoute.SearchStatus.FOUND,
                    "strict search did not resume its retained frontier to the visible shore: status="
                            + result.status() + " slices=" + slices);
            require(context, !result.path().isEmpty()
                            && result.path().get(0).equals(first)
                            && result.path().get(result.path().size() - 1).equals(shore),
                    "resumed route lost its first edge or visible shore: " + result.path());
        } finally {
            installConfig(original);
            despawn(world, bot);
        }
        context.succeed();
    }

    /**
     * A strict search may retain unknown side edges, but it must not make an actually proved
     * adjacent EXIT wait for an exhaustive score pass. The first bounded slice must publish this
     * visible shore as an actionable physical route.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_strict_route_search_returns_adjacent_visible_exit", maxTicks = 30)
    public void strictRouteSearchReturnsAdjacentVisibleExit(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -84));
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos shore = start.east();
        world.setBlock(start, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(shore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        AIPlayerEntity bot = spawnBot(world, "StrictAdjacentExitBot", start);
        poseWaterObserver(bot, start);
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withPerceptionRadius(withProfile(original, OperatingProfile.STRICT_SURVIVAL), 12));
            require(context, SwimRoute.observedCell(bot, world, shore, false) == SwimRoute.Cell.DRY,
                    "adjacent strict EXIT shore was not visibly/physically provable");
            SwimRoute.SearchResult result = SwimRoute.startSearch(
                            start, shore, SwimRoute.Goal.EXIT, 0.0D, false)
                    .advance(bot, world, SwimRoute.STRICT_SEARCH_CANDIDATES_PER_TICK);
            require(context, result.status() == SwimRoute.SearchStatus.FOUND
                            && result.path().size() == 1 && result.path().get(0).equals(shore),
                    "strict route delayed a proved adjacent EXIT behind exhaustive search work: "
                            + result.status() + " " + result.path());
        } finally {
            installConfig(original);
            despawn(world, bot);
        }
        context.succeed();
    }

    /**
     * An in-flight FollowSwimming step has a capability provenance just like a cached route. If an
     * operator step loses that provenance mid-step, strict survival must cancel it before it can
     * advance and then require a new live observation proof.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_operator_step_is_cancelled_before_strict_reproof", maxTicks = 30)
    public void operatorStepIsCancelledBeforeStrictReproof(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -60));
        for (int dx = -1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos next = start.east();
        BlockPos targetWater = next.east();
        for (BlockPos cell : List.of(start, next, targetWater)) {
            world.setBlock(cell, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        AIPlayerEntity bot = spawnBot(world, "FollowProvenanceBot", start);
        AIPlayerEntity target = spawnBot(world, "FollowProvenanceTarget", targetWater);
        target.teleportTo(world, targetWater.getX() + 0.5D, targetWater.getY() + 0.125D,
                targetWater.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        FollowSwimming follower = new FollowSwimming();
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.OPERATOR));
            require(context, CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "operator_follow_step_provenance").allowed(),
                    "operator fixture did not enable the hidden-world capability");
            TeleportAudit.reset(bot);
            follower.follow(bot, target, 1, 0.25D);
            require(context, bot.getActionPack().stepInFlightFor("follow_swim", next, WalkedStep.Kind.SWIM),
                    "operator follow did not start its provenance-bearing swim step");
            Vec3 before = bot.position();

            // The old operator admission is deliberately made invalid before strict gets a turn.
            // A continued action would either pass into this new blocker or carry privileged state
            // across the profile boundary; a correct strict tick cancels and re-proves first.
            world.setBlock(next, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(next.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Standability.clearCache();
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                            "strict_follow_step_provenance").allowed(),
                    "strict fixture unexpectedly retained hidden-world capability");
            // A generic controller deliberately asks for an indistinguishable same-cell/kind
            // step. The old reason/cell/kind ownership check could mistake this unguarded
            // replacement for Follow's own admission; the opaque ActionPack lease must reject it
            // before the pre-owner ActionPack tick gets a chance to raw-validate the blocker.
            ActionPack.StepLease foreignLease = bot.getActionPack().runStep(
                    WalkedStep.begin(bot, next, WalkedStep.Kind.SWIM, "follow_swim"));
            require(context, foreignLease == null
                            && bot.getActionPack().stepInFlightFor("follow_swim", next, WalkedStep.Kind.SWIM),
                    "an unguarded same-cell/kind step replaced Follow's guarded strict admission");
            // This is the ordering used by AIPlayerEntity.tick(): ActionPack advances its
            // owned step before the END_SERVER_TICK Follow owner gets a chance to reconcile the
            // profile.  The guard must therefore stop the old operator step at this boundary,
            // before WalkedStep can perform its first raw terrain validation.
            bot.getActionPack().onUpdate();
            WalkedStep.Result preOwner = bot.getActionPack().stepResult();
            require(context, bot.getActionPack().stepIdle()
                            && preOwner != null && preOwner.failed()
                            && "continuation_guard".equals(preOwner.reason()),
                    "the ActionPack pre-owner tick did not reject the operator step: "
                            + (preOwner == null ? "no result" : preOwner.status() + " " + preOwner.reason()));
            require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                    "the pre-owner profile guard advanced the bot: " + before + " -> " + bot.position());

            // The ordinary owner reconciliation is intentionally second. It may only plan a
            // fresh strict action, never resurrect the just-refused privileged step.
            follower.follow(bot, target, 2, 0.25D);
            require(context, bot.getActionPack().stepIdle(),
                    "strict follow let an operator-admitted in-flight step continue after reproof failed");
            require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                    "profile transition advanced the bot before strict reproof: " + before + " -> " + bot.position());
            require(context, TeleportAudit.corrections(bot) == 0,
                    "profile transition used a correction teleport: " + TeleportAudit.lastCaller(bot));
        } finally {
            follower.cancelStep(bot);
            installConfig(original);
            despawn(world, bot, target);
        }
        context.succeed();
    }

    /**
     * A real Follow-owned guarded water step may be interrupted by a higher-priority buried-player
     * escape. Once that emergency has installed its own physical successor, it owns movement
     * against stale Follow planning, generic interruption, direct key release, and Baritone until
     * NavSafetyNet performs its exact lifecycle cleanup.
     */
    @GameTest(environment = "minecraftai-gametest:follow_swim_game_tests_emergency_preemption_does_not_let_stale_follow_cancel_successor", maxTicks = 40)
    public void emergencyPreemptionDoesNotLetStaleFollowCancelSuccessor(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 6, -176));
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos followNext = start.east();
        BlockPos targetWater = followNext.east();
        BlockPos emergencyShore = start.west();
        for (BlockPos water : List.of(start, followNext, targetWater)) {
            world.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(water.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(water.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // The direct suffocation-input helper sees precisely one ordinary physical landing after
        // it preempts Follow: east is water, every other neighbour remains solid, and west is dry.
        world.setBlock(emergencyShore, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(emergencyShore.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(emergencyShore.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();

        AIPlayerEntity bot = spawnBot(world, "FollowEmergencyPreemptBot", start);
        poseWaterObserver(bot, start);
        AIPlayerEntity target = spawnBot(world, "FollowEmergencyPreemptTarget", targetWater);
        poseWaterObserver(target, targetWater);
        FollowSwimming follower = new FollowSwimming();
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            installConfig(withProfile(original, OperatingProfile.STRICT_SURVIVAL));
            SwimRoute.Cell observedFollowNext = SwimRoute.observedCell(bot, world, followNext, false);
            require(context, observedFollowNext != null && observedFollowNext.isWater(),
                    "fixture did not expose Follow's first guarded water stroke: " + observedFollowNext);
            follower.follow(bot, target, 1, 0.25D);
            require(context, bot.getActionPack().stepInFlightFor(
                            "follow_swim", followNext, WalkedStep.Kind.SWIM),
                    "Follow did not install its owned guarded swim step before the emergency");

            TeleportAudit.reset(bot);
            require(context, NavSafetyNet.INSTANCE.escapeSuffocationByInputs(bot, world, start),
                    "the suffocation emergency did not preempt Follow's guarded lease");
            ActionPack pack = bot.getActionPack();
            require(context, pack.stepInFlightFor(
                            "path_start:navsafe_suffocation", emergencyShore, WalkedStep.Kind.FLAT),
                    "the suffocation emergency did not install its exact physical dry successor");
            require(context, pack.baritoneControlBlocked(),
                    "the live Nav emergency successor did not retain the controller/Baritone fence");

            // Tick the actual physical successor once, then attempt every foreign generic
            // shutdown and zero/false key release. None may erase its inputs or lease outside
            // ActionPack's own guarded-step tick.
            pack.onUpdate();
            int emergencyTicks = pack.activeStepTicks();
            float emergencyForward = bot.zza;
            require(context, emergencyTicks > 0 && emergencyForward > 0.0F,
                    "the physical emergency successor did not write its real forward input before interference");
            pack.cancelStep();
            pack.stopMovement();
            pack.stopNavigation();
            pack.stopAll();
            pack.setForward(0.0F);
            pack.setStrafing(0.0F);
            pack.setSneaking(false);
            pack.setSprinting(false);
            pack.setJumping(false);
            require(context, pack.stepInFlightFor(
                            "path_start:navsafe_suffocation", emergencyShore, WalkedStep.Kind.FLAT)
                            && pack.activeStepTicks() == emergencyTicks && bot.zza == emergencyForward,
                    "a foreign generic stop or zero/false input interfered with the live emergency successor");

            follower.cancelStep(bot);
            require(context, pack.stepInFlightFor(
                            "path_start:navsafe_suffocation", emergencyShore, WalkedStep.Kind.FLAT),
                    "stale Follow cancellation reached the active emergency successor");

            // Calling Follow again reaches its private stepInFlight reconciliation with the old
            // Follow lease no longer active and the emergency successor still running. Use the
            // ordinary follow standoff so this catches both stale reconciliation and a later
            // planner attempt in the same tick.
            follower.follow(bot, target, 2, 0.25D);
            require(context, pack.stepInFlightFor(
                            "path_start:navsafe_suffocation", emergencyShore, WalkedStep.Kind.FLAT),
                    "stale Follow reconciliation cancelled the active emergency successor");

            ActionPack.StepLease foreignLease = pack.runStep(
                    WalkedStep.begin(bot, followNext, WalkedStep.Kind.SWIM, "foreign_emergency_replacement"));
            ActionResult blockedPath = pack.startSurfacePathTo(targetWater);
            BaritoneGoals.Outcome blockedGoal = BaritoneGoals.walkTo(bot, targetWater);
            require(context, foreignLease == null && blockedPath.isFailed()
                            && ActionPack.GUARDED_STEP_FENCE.equals(blockedPath.reason())
                            && !blockedGoal.accepted()
                            && ActionPack.GUARDED_STEP_FENCE.equals(blockedGoal.reason()),
                    "a foreign step, path, or direct Baritone goal bypassed the live emergency successor");

            // Re-entering the direct emergency helper must recognize its own live lease rather
            // than treating it as a foreign guarded step and preempting it.
            require(context, NavSafetyNet.INSTANCE.escapeSuffocationByInputs(bot, world, start)
                            && pack.stepInFlightFor(
                            "path_start:navsafe_suffocation", emergencyShore, WalkedStep.Kind.FLAT)
                            && pack.activeStepTicks() == emergencyTicks,
                    "the suffocation helper self-preempted its own live physical successor");

            NavSafetyNet.INSTANCE.clear(bot);
            require(context, pack.stepIdle() && !pack.baritoneControlBlocked(),
                    "NavSafetyNet.clear did not exactly cancel and release its emergency successor");
            ActionPack.StepLease afterClear = pack.runStep(
                    WalkedStep.begin(bot, emergencyShore, WalkedStep.Kind.FLAT, "after_nav_clear"));
            require(context, afterClear != null && pack.stepInFlightFor(afterClear),
                    "exact Nav lifecycle cleanup left a stale controller fence behind");
            pack.cancelStep(afterClear);
            require(context, TeleportAudit.corrections(bot) == 0,
                    "emergency handoff used a correction teleport: " + TeleportAudit.lastCaller(bot));
        } finally {
            follower.cancelStep(bot);
            bot.getActionPack().cancelStep();
            installConfig(original);
            despawn(world, bot, target);
        }
        context.succeed();
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

    private static final int DEEP_FOLLOW_SHAFT_DEPTH = 12;

    /** A broad enough submerged column for a real Follow target, with no horizontal dry rescue route. */
    private static DeepFollowShaft buildDeepFollowShaft(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos lower = context.absolutePos(new BlockPos(8, 24, -24));
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -16; dy <= 16; dy++) {
                    world.setBlock(lower.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (int dx = -1; dx <= 5; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy < DEEP_FOLLOW_SHAFT_DEPTH; dy++) {
                    world.setBlock(lower.offset(dx, dy, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
                world.setBlock(lower.offset(dx, DEEP_FOLLOW_SHAFT_DEPTH, dz),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return new DeepFollowShaft(lower.immutable());
    }

    private record DeepFollowShaft(BlockPos lower) {
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

    /** Force-loads the chunks under the pond/island; force-only, see {@link GameTestChunkForcing}. */
    private static void forceChunks(GameTestHelper context, BlockPos feet, int maxX) {
        GameTestChunkForcing.forceForTest(context,
                (feet.getX() - 2) >> 4, (feet.getX() + maxX + 1) >> 4,
                (feet.getZ() - 2) >> 4, (feet.getZ() + MAX_Z + 1) >> 4);
    }

    static boolean noBoats(ServerLevel world, Pond pond) {
        return world.getEntitiesOfClass(AbstractBoat.class, pond.area(), boat -> true).isEmpty();
    }

    /** Builds one exposed lower landing while every competing local dry landing stays sealed. */
    private static void buildStrictRefusalEnvelopeFixture(ServerLevel world, BlockPos start, BlockPos landing,
                                                           BlockPos hiddenBlocker) {
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -3; dz <= 2; dz++) {
                for (int dy = -5; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // The eye-to-low-landing ray crosses this cell before it turns into the diagonal shaft.
        // It must be open so the destination itself is genuinely visible, independently of the
        // higher blocker that hides the validator's auxiliary corner/fall cell.
        for (int y = start.getY() - 1; y <= start.getY(); y++) {
            world.setBlock(new BlockPos(landing.getX(), y, start.getZ()),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // The exposed destination proof needs its whole feet/head shaft through the upper swept
        // cell. The caller's blocker remains one cell above this opening, so a deep-drop
        // validation ray still has an intentionally hidden auxiliary cell to reject.
        for (int y = landing.getY(); y <= start.getY() + 1; y++) {
            world.setBlock(new BlockPos(landing.getX(), y, landing.getZ()),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(hiddenBlocker, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
    }

    private static void clearDryTarget(ServerLevel world, BlockPos target) {
        world.setBlock(target.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(target.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(target.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
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

    /**
     * Put a waterborne test bot just high enough that its eye is above its own source block.  The
     * feet still resolve to {@code feet} and the body remains in water, but a fluid-aware sight
     * ray can prove an exposed neighbouring water/shore cell instead of immediately striking the
     * source water below the eye.
     */
    private static void poseWaterObserver(AIPlayerEntity bot, BlockPos feet) {
        poseRaisedObserver(bot, feet, 0.125D);
    }

    /** Keeps the physical feet block fixed while giving a low-landing fixture a clear eye ray. */
    private static void poseRaisedObserver(AIPlayerEntity bot, BlockPos feet, double yOffset) {
        poseRaisedObserver(bot, feet, yOffset, 0.0D);
    }

    /** Keeps the physical feet block fixed while moving the observer inside that source cell. */
    private static void poseRaisedObserver(AIPlayerEntity bot, BlockPos feet, double yOffset, double zOffset) {
        bot.teleportTo(bot.level(), feet.getX() + 0.5D, feet.getY() + yOffset, feet.getZ() + 0.5D + zOffset,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
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

    /** Test-only immutable-config replacement; always restore it before a test yields another tick. */
    private static MinecraftAiConfig withProfile(MinecraftAiConfig config, OperatingProfile profile) {
        return new MinecraftAiConfig(profile, config.operatorCapabilities(), config.llm(), config.perception(),
                config.brain(), config.watchdog(), config.logging(), config.survival(), config.combat(), config.night(),
                config.mining(), config.goal(), config.nav(), config.pickup(), config.conversation(), config.storage(),
                config.behaviour());
    }

    private static MinecraftAiConfig withPerceptionRadius(MinecraftAiConfig config, int radius) {
        MinecraftAiConfig.Perception perception = config.perception();
        return new MinecraftAiConfig(config.profile(), config.operatorCapabilities(), config.llm(),
                new MinecraftAiConfig.Perception(radius, perception.maxBlocks(), perception.maxEntities(),
                        perception.maxItems(), perception.includeRawLists()),
                config.brain(), config.watchdog(), config.logging(), config.survival(), config.combat(), config.night(),
                config.mining(), config.goal(), config.nav(), config.pickup(), config.conversation(), config.storage(),
                config.behaviour());
    }

    private static void installConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install GameTest config", exception);
        }
    }

    private static void submerge(ServerLevel world, AIPlayerEntity bot, BlockPos feet) {
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY() + 0.125D, feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, false);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
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
