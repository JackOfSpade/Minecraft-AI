package io.github.zoyluo.minecraftai.task;

import com.mojang.logging.LogUtils;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * No micro-teleports in the water and recovery paths (R5): a bot swims, climbs out of a pit, digs through a wall and leaves a
 * cancelled shelter by real movement inputs ({@link WalkedStep}), never by moving itself a cell at a time. Every test resets
 * {@link TeleportAudit} for the bot under test after its fixture is built (fixture moves are {@code TEST} teleports) and asserts
 * {@code TeleportAudit.corrections(bot) == 0} at the end, in the default strict-survival profile (no emergency teleport exists),
 * plus the safety outcome the old teleports guaranteed (no drowning damage, no suffocation, arrival).
 *
 * <p>Geometry is the one of {@link FollowSwimGameTests}: land tops out at y=-1 (the bot stands at y=0) and a pond of the requested
 * depth is carved into it with its water surface flush with the bank.</p>
 */
public final class NaturalSwimGameTests {
    private static final int LANE_Z = FollowSwimGameTests.LANE_Z;
    private static final Logger LOGGER = LogUtils.getLogger();

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    private static String audit(AIPlayerEntity bot) {
        return "corrections=" + TeleportAudit.corrections(bot) + " last=" + TeleportAudit.lastCaller(bot);
    }

    private static void requireNoCorrections(GameTestHelper context, AIPlayerEntity bot, String what) {
        require(context, TeleportAudit.corrections(bot) == 0, what + ": the bot was teleported (" + audit(bot) + ")");
    }

    private static void requireUnhurt(GameTestHelper context, AIPlayerEntity bot, String what, int tick) {
        require(context, bot.getHealth() >= bot.getMaxHealth(),
                what + ": the bot took damage (health=" + bot.getHealth() + " air=" + bot.getAirSupply()
                        + " pos=" + bot.position() + " tick=" + tick + ")");
    }

    /** A legacy-engine survival bot placed (as a fixture move, not counted) at {@code where}, its audit reset. */
    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos where) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = FollowSwimGameTests.spawnBot(world, name, where);
        NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.LEGACY);
        BotFixtureMoves.place(bot, where);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(20.0F);
        bot.setAirSupply(bot.getMaxAirSupply());
        Standability.clearCache();
        TeleportAudit.reset(bot);
        return bot;
    }

    private static void despawn(GameTestHelper context, AIPlayerEntity... bots) {
        ServerLevel world = context.getLevel();
        for (AIPlayerEntity bot : bots) {
            TaskManager.INSTANCE.abort(bot);
            bot.getActionPack().stopAll();
            DangerWatcher.INSTANCE.clear(bot);
            NavSafetyNet.INSTANCE.clear(bot);
            ShelterCleanupRegistry.forgetCleanupDebtsOwnedBy(bot);
            NavEngineSelector.clearBotEngine(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
        }
    }

    /** Land (top at y=-1) over x -2..30, z -2..20 with air above and no wall or pond: the arena of the walking recovery tests. */
    private static BlockPos buildLand(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(0, 24, 0));
        io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing.forceForTest(context,
                (feet.getX() - 2) >> 4, (feet.getX() + 30) >> 4, (feet.getZ() - 2) >> 4, (feet.getZ() + 20) >> 4);
        for (int x = -2; x <= 30; x++) {
            for (int z = -2; z <= 20; z++) {
                for (int y = -4; y <= 6; y++) {
                    BlockState state = y <= -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    world.setBlock(feet.offset(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }
        // The flat gametest world spawns slimes in slime chunks; one slain bot would fail an otherwise correct test.
        context.failIfEver(() -> world.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                net.minecraft.world.phys.AABB.encapsulatingFullBlocks(feet.offset(-40, -10, -40), feet.offset(70, 12, 60)),
                mob -> mob instanceof net.minecraft.world.entity.monster.Enemy)
                .forEach(net.minecraft.world.entity.Entity::discard));
        return feet;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The probe that settles "inputs alone do not travel in water": a legacy bot swims and surfaces by SWIM steps
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A legacy bot on the floor of a 4-deep pool swims up to the surface and then across the pool by {@link WalkedStep} SWIM steps
     * (forward and jump keys, nothing else). The measured surfacing time and horizontal speed are logged as {@code SWIMPROBE} lines.
     */
    @GameTest(environment = "minecraftai-gametest:natural_swim_game_tests_legacy_inputs_swim_and_surface", maxTicks = 700)
    public void legacyInputsSwimAndSurface(GameTestHelper context) {
        FollowSwimGameTests.Pond pond = FollowSwimGameTests.buildPond(context, 8, 19, 4, 26);
        BlockPos floorCell = pond.feet().offset(9, -4, LANE_Z);
        AIPlayerEntity bot = spawn(context, "SwimProbeGT", floorCell);
        int surfaceY = pond.feet().getY() - 1;
        int[] phase = {0};
        int[] phaseStart = {0};
        int[] stepFailures = {0};
        double[] startX = {0.0D};
        int[] surfaceTicks = {0};
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            NavSafetyNet.INSTANCE.renewFollowSwim(bot);
            bot.setAirSupply(bot.getMaxAirSupply());
            var pack = bot.getActionPack();
            if (!pack.stepIdle()) {
                return;
            }
            if (pack.stepResult() != null && pack.stepResult().failed()) {
                stepFailures[0]++;
                require(context, stepFailures[0] < 6, "SWIM steps keep failing: " + pack.stepResult().reason()
                        + " at " + bot.position());
            }
            BlockPos here = bot.blockPosition();
            if (phase[0] == 0) {
                if (here.getY() < surfaceY) {
                    pack.runStep(WalkedStep.begin(bot, here.above(), WalkedStep.Kind.SWIM, "swim_probe_up"));
                    return;
                }
                surfaceTicks[0] = now;
                phase[0] = 1;
                phaseStart[0] = now;
                startX[0] = bot.getX();
                LOGGER.info("SWIMPROBE surfaced ticks={} y={} air={} under_water={}",
                        now, bot.getY(), bot.getAirSupply(), bot.isUnderWater());
            }
            if (phase[0] == 1) {
                if (here.getX() < pond.feet().getX() + 17) {
                    pack.runStep(WalkedStep.begin(bot, here.east(), WalkedStep.Kind.SWIM, "swim_probe_east"));
                    return;
                }
                double blocks = bot.getX() - startX[0];
                int ticks = now - phaseStart[0];
                double bps = blocks * 20.0D / Math.max(1, ticks);
                LOGGER.info("SWIMPROBE crossed blocks={} ticks={} bps={} step_failures={}",
                        blocks, ticks, bps, stepFailures[0]);
                require(context, surfaceTicks[0] <= 120, "surfacing from a 4-deep floor took " + surfaceTicks[0] + " ticks");
                require(context, bps >= 1.0D, "horizontal swim speed " + bps + " b/s is not a swim");
                requireNoCorrections(context, bot, "legacy swim probe");
                despawn(context, bot);
                context.succeed();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Following across a pond
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The target crosses a 12-wide, 3-deep pond and climbs out on the far shore; the follower swims after it with real inputs:
     * no correction teleport, no drowning damage, it ends on the far shore near the target.
     */
    @GameTest(environment = "minecraftai-gametest:natural_swim_game_tests_follow_across_pond_without_teleport", maxTicks = 1600)
    public void followAcrossPondWithoutTeleport(GameTestHelper context) {
        FollowSwimGameTests.Pond pond = FollowSwimGameTests.buildPond(context, 8, 19, 3, 26);
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawn(context, "NatSwimCrossBot", pond.feet().offset(3, 0, LANE_Z));
        AIPlayerEntity target = FollowSwimGameTests.spawnBot(world, "NatSwimCrossTgt", pond.feet().offset(9, 0, LANE_Z));
        FollowSwimGameTests.holdStill(target);
        placeSwimmer(pond, target, 9.5D, LANE_Z + 0.5D, -1.0D + 0.125D);
        TeleportAudit.reset(bot);
        FollowTask follow = new FollowTask("NatSwimCrossTgt");
        TaskManager.INSTANCE.assign(bot, follow, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_natural_swim_cross"));
        double[] targetX = {9.5D};
        boolean[] sawWater = {false};
        int[] minAir = {Integer.MAX_VALUE};
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            FollowSwimGameTests.requireRunning(context, follow, bot);
            requireUnhurt(context, bot, "crossing the pond", now);
            minAir[0] = Math.min(minAir[0], bot.getAirSupply());
            sawWater[0] |= bot.isInWater();
            if (now > 40 && targetX[0] < 20.0D) {
                targetX[0] += 0.09D;
                placeSwimmer(pond, target, targetX[0], LANE_Z + 0.5D, -1.0D + 0.125D);
            } else if (targetX[0] >= 20.0D) {
                standOn(pond, target, 22.5D, LANE_Z + 0.5D);
            } else {
                placeSwimmer(pond, target, 9.5D, LANE_Z + 0.5D, -1.0D + 0.125D);
            }
            if (targetX[0] >= 20.0D && now > 120 && !bot.isInWater() && bot.getX() >= pond.feet().getX() + 19.5D
                    && bot.distanceTo(target) <= 5.0D) {
                require(context, sawWater[0], "the bot reached the far shore without ever entering the water");
                requireNoCorrections(context, bot, "swim follow across the pond");
                LOGGER.info("SWIMPROBE follow_across_pond done tick={} min_air={} distance={}",
                        now, minAir[0], bot.distanceTo(target));
                FollowSwimGameTests.finish(context, pond, bot, target);
                TeleportAudit.reset(bot);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The safety net's water rescue
    // ---------------------------------------------------------------------------------------------------------------

    /** A bot dropped into a 5-deep pool with nothing to do reaches dry land by inputs alone, unhurt. */
    @GameTest(environment = "minecraftai-gametest:natural_swim_game_tests_water_rescue_swims_to_shore", maxTicks = 900)
    public void waterRescueSwimsToShore(GameTestHelper context) {
        FollowSwimGameTests.Pond pond = FollowSwimGameTests.buildPond(context, 8, 19, 5, 26);
        AIPlayerEntity bot = spawn(context, "NatRescueBot", pond.feet().offset(13, -3, LANE_Z));
        AtomicInteger tick = new AtomicInteger();
        boolean[] wasInWater = {false};
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            requireUnhurt(context, bot, "water rescue", now);
            wasInWater[0] |= bot.isInWater();
            if (now > 10 && wasInWater[0] && !bot.isInWater() && !bot.isUnderWater()
                    && Standability.isStandable(context.getLevel(), bot.blockPosition())
                    && !NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                requireNoCorrections(context, bot, "water rescue");
                LOGGER.info("SWIMPROBE water_rescue done tick={} air={}", now, bot.getAirSupply());
                despawn(context, bot);
                context.succeed();
            }
        });
    }

    /**
     * A bot on the pool floor with only 60 air left surfaces before any drowning damage: real swimming is slower than the old
     * teleport per tick, so this pins that the rescue is still fast enough.
     */
    @GameTest(environment = "minecraftai-gametest:natural_swim_game_tests_drowning_pressure_still_surfaces_in_time", maxTicks = 500)
    public void drowningPressureStillSurfacesInTime(GameTestHelper context) {
        FollowSwimGameTests.Pond pond = FollowSwimGameTests.buildPond(context, 8, 19, 4, 26);
        AIPlayerEntity bot = spawn(context, "NatDrownBot", pond.feet().offset(13, -4, LANE_Z));
        bot.setAirSupply(60);
        AtomicInteger tick = new AtomicInteger();
        int[] minAir = {Integer.MAX_VALUE};
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            minAir[0] = Math.min(minAir[0], bot.getAirSupply());
            requireUnhurt(context, bot, "surfacing with 60 air", now);
            if (now > 5 && !bot.isUnderWater() && bot.getAirSupply() > 0) {
                requireNoCorrections(context, bot, "drowning pressure");
                LOGGER.info("SWIMPROBE drowning_pressure surfaced tick={} min_air={}", now, minAir[0]);
                despawn(context, bot);
                context.succeed();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Follow's recovery ladder and the shelter exit walk instead of stepping by teleport
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A follower wedged in a one-block pit, with nothing else moving it, recovers after its stall window by a walked step up onto
     * the neighbouring cell toward the player: no correction teleport.
     */
    @GameTest(environment = "minecraftai-gametest:natural_swim_game_tests_follow_stuck_recovery_walks_out", maxTicks = 500)
    public void followStuckRecoveryWalksOut(GameTestHelper context) {
        BlockPos feet = buildLand(context);
        context.getLevel().setBlock(feet.offset(10, -1, 8), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Standability.clearCache();
        AIPlayerEntity target = FollowSwimGameTests.spawnBot(context.getLevel(), "NatStuckTgt", feet.offset(22, 0, 8));
        FollowSwimGameTests.holdStill(target);
        AIPlayerEntity bot = spawn(context, "NatStuckBot", feet.offset(10, -1, 8));
        FollowStuckRecovery recovery = new FollowStuckRecovery();
        recovery.reset(bot, 0);
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            recovery.tick(bot, target, now, 3.0D);
            requireUnhurt(context, bot, "stuck recovery", now);
            if (now < 95) {
                require(context, bot.blockPosition().equals(feet.offset(10, -1, 8)),
                        "the bot left the pit before its stall window elapsed (tick " + now + ")");
            }
            if (bot.blockPosition().getY() >= feet.getY() && bot.blockPosition().getX() > feet.getX() + 10) {
                require(context, now >= 95, "impossible instant recovery");
                requireNoCorrections(context, bot, "stuck recovery");
                LOGGER.info("SWIMPROBE stuck_recovery done tick={} position={}", now, bot.blockPosition().toShortString());
                despawn(context, bot, target);
                context.succeed();
            }
        });
    }

    /**
     * The dig-out step: the bot breaks the two cells of a 2-thick natural stone wall in its way and walks into each opened cell
     * (it is never placed there), then stands on the far side. No correction teleport, the floor stays.
     */
    @GameTest(environment = "minecraftai-gametest:natural_swim_game_tests_follow_dig_out_by_walking", maxTicks = 900)
    public void followDigOutByWalking(GameTestHelper context) {
        BlockPos feet = buildLand(context);
        ServerLevel world = context.getLevel();
        for (int x = 10; x <= 11; x++) {
            for (int z = -2; z <= 20; z++) {
                for (int y = 0; y <= 1; y++) {
                    world.setBlock(feet.offset(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        Standability.clearCache();
        AIPlayerEntity target = FollowSwimGameTests.spawnBot(world, "NatDigTgt", feet.offset(20, 0, 8));
        FollowSwimGameTests.holdStill(target);
        AIPlayerEntity bot = spawn(context, "NatDigBot", feet.offset(9, 0, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        FollowDigOut digOut = new FollowDigOut();
        require(context, digOut.start(bot, target), "the dig-out did not start against a plain natural wall");
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            boolean active = digOut.tick(bot);
            requireUnhurt(context, bot, "dig out", now);
            for (int x = 9; x <= 14; x++) {
                require(context, !world.getBlockState(feet.offset(x, -1, 8)).isAir(),
                        "the dig-out removed the floor at x=" + x);
            }
            if (!active) {
                require(context, bot.getX() >= feet.getX() + 11.0D, "the dig-out ended before the wall was crossed: x="
                        + (bot.getX() - feet.getX()));
                require(context, world.getBlockState(feet.offset(10, 0, 8)).isAir() && world.getBlockState(feet.offset(11, 1, 8)).isAir(),
                        "the wall cells on the bot's lane were not opened");
                requireNoCorrections(context, bot, "dig out");
                LOGGER.info("SWIMPROBE dig_out done tick={}", now);
                despawn(context, bot, target);
                context.succeed();
            }
        });
    }

    /**
     * A cancelled shelter's doorway: the follower breaks the bot-owned blocks of the doorway on the player's side and then WALKS
     * through it (no teleport), and the owned exit debt is repaid.
     */
    @GameTest(environment = "minecraftai-gametest:natural_swim_game_tests_shelter_exit_debt_walks_out", maxTicks = 900)
    public void shelterExitDebtWalksOut(GameTestHelper context) {
        BlockPos feet = buildLand(context);
        ServerLevel world = context.getLevel();
        BlockPos anchor = feet.offset(8, 0, 8);
        BlockState owned = Blocks.DIRT.defaultBlockState();
        Map<BlockPos, BlockState> placements = new java.util.LinkedHashMap<>();
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int rise = 0; rise <= 1; rise++) {
                BlockPos cell = anchor.relative(side).above(rise);
                world.setBlock(cell, owned, Block.UPDATE_ALL);
                placements.put(cell, owned);
            }
        }
        Standability.clearCache();
        AIPlayerEntity target = FollowSwimGameTests.spawnBot(world, "NatShelterTgt", feet.offset(20, 0, 8));
        FollowSwimGameTests.holdStill(target);
        AIPlayerEntity bot = spawn(context, "NatShelterBot", anchor);
        BlockPos egress = anchor.east();
        EmergencyShelterTask.ExitDebt debt = new EmergencyShelterTask.ExitDebt(
                world.dimension().identifier().toString(), anchor,
                List.of(egress, anchor.north(), anchor.south(), anchor.west()), placements);
        ShelterCleanupRegistry.recordExitDebt(bot, debt);
        ShelterExitDebtRepayer repayer = new ShelterExitDebtRepayer();
        repayer.reset(bot);
        require(context, EmergencyShelterTask.pendingExitDebt(bot).isPresent(), "fixture: the exit debt was not recorded");
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            boolean busy = repayer.repay(bot, target, now);
            requireUnhurt(context, bot, "shelter exit", now);
            if (!busy) {
                require(context, now > 5, "impossible instant exit");
                require(context, bot.blockPosition().equals(egress),
                        "the bot did not end in the doorway cell: " + bot.blockPosition().toShortString());
                require(context, world.getBlockState(egress).isAir() && world.getBlockState(egress.above()).isAir(),
                        "the doorway was not opened");
                require(context, EmergencyShelterTask.pendingExitDebt(bot).isEmpty(), "the exit debt was not repaid");
                requireNoCorrections(context, bot, "shelter exit");
                LOGGER.info("SWIMPROBE shelter_exit done tick={}", now);
                despawn(context, bot, target);
                context.succeed();
            }
        });
    }

    // ---- fixture helpers ---------------------------------------------------------------------------------------------

    /** Puts a (target) swimmer at a water position, topped up on air, under the follow lease. */
    private static void placeSwimmer(FollowSwimGameTests.Pond pond, AIPlayerEntity swimmer, double x, double z, double y) {
        BotFixtureMoves.place(swimmer, new Vec3(pond.feet().getX() + x, pond.feet().getY() + y, pond.feet().getZ() + z));
        swimmer.fallDistance = 0.0F;
        swimmer.setAirSupply(swimmer.getMaxAirSupply());
        NavSafetyNet.INSTANCE.renewFollowSwim(swimmer);
    }

    private static void standOn(FollowSwimGameTests.Pond pond, AIPlayerEntity player, double x, double z) {
        BotFixtureMoves.place(player, new Vec3(pond.feet().getX() + x, pond.feet().getY(), pond.feet().getZ() + z));
        player.setOnGround(true);
    }
}
