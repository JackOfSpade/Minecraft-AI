package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.baritone.ObservedBaritoneTestRoutes;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Baritone driving a bot through water (the drowning safety net must not take a swimmer over while Baritone owns the route),
 * fail-closed handling when Baritone is unavailable, and default Baritone bootstrap.
 */
public final class BaritoneEngineWaterGameTests {
    private static final double AT_GOAL = 1.7D;

    /**
     * A swim route across a two-deep, four-wide channel for a bot that starts submerged on its bottom (it has just fallen in): with
     * its eyes under the surface the safety net declares a rescue and moves the bot by a block or more per step, off the path
     * Baritone is walking it along. With the Baritone lease it must not: every tick's displacement is swimming speed, no rescue is
     * ever active, and the lease ends with the route.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_water_game_tests_baritone_swim_route_is_not_hijacked_by_the_safety_net", maxTicks = 500)
    public void baritoneSwimRouteIsNotHijackedByTheSafetyNet(GameTestHelper context) {
        BaritoneEngineArena arena = channel(context, 20);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeSwimLease", arena.cell(-6, 0, 0));
        arena.teleportTo(bot, arena.cell(1, -1, 0));
        BlockPos goal = arena.cell(8, 0, 0);
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startSwimRouteTo(goal);
        arena.require(started.isInProgress(), "the swim route was not accepted: " + started.status() + " " + started.reason());
        float health = bot.getHealth();
        Vec3[] last = {bot.position()};
        double[] maxStep = {0.0D};
        int[] inWaterTicks = {0};
        int[] underwaterTicks = {0};
        int[] leaseTicks = {0};
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 480, "the swimmer never arrived: " + bot.position());
            double step = bot.position().distanceTo(last[0]);
            last[0] = bot.position();
            maxStep[0] = Math.max(maxStep[0], step);
            arena.require(step <= 0.75D, "displaced by " + step + " blocks in one tick at tick " + now + ": " + bot.position() + " (a rescue step?)");
            arena.require(!NavSafetyNet.INSTANCE.isWaterRescueActive(bot), "the safety net started a water rescue on the swimmer at tick " + now);
            if (bot.isUnderWater()) {
                underwaterTicks[0]++;
            }
            if (bot.isInWater()) {
                inWaterTicks[0]++;
                if (NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot)) {
                    leaseTicks[0]++;
                }
            }
            if (!pack.hasBaritoneRoute()) {
                NavOutcome outcome = pack.lastRouteOutcome();
                arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS, "the swim route did not succeed: " + outcome);
                arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
                arena.require(underwaterTicks[0] >= 2, "the bot never was submerged (" + underwaterTicks[0] + " ticks): the fixture does not exercise the safety net");
                arena.require(inWaterTicks[0] >= 10, "the bot hardly swam (" + inWaterTicks[0] + " ticks in water): the fixture is wrong");
                arena.require(leaseTicks[0] >= inWaterTicks[0] - 2, "the water lease covered only " + leaseTicks[0] + " of " + inWaterTicks[0] + " swimming ticks");
                arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "the lease outlived the route");
                arena.require(bot.getHealth() >= health, "the swimmer lost health: " + health + " -> " + bot.getHealth());
                BotLog.path(bot, "gametest_swim_route", "in_water_ticks", inWaterTicks[0], "underwater_ticks", underwaterTicks[0],
                        "lease_ticks", leaseTicks[0], "max_step", maxStep[0]);
                arena.finish(bot);
            }
        });
    }

    /** The control of the lease test: revoke the lease after a production-seam swim route starts, so the safety net must intervene. */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_water_game_tests_baritone_swim_route_is_not_hijacked_by_the_safety_net", maxTicks = 500)
    public void withoutTheLeaseTheSafetyNetTakesTheSwimmerOver(GameTestHelper context) {
        BaritoneEngineArena arena = channel(context, 21);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeSwimNoLease", arena.cell(-6, 0, 0));
        arena.teleportTo(bot, arena.cell(1, -1, 0));
        BlockPos goal = arena.cell(8, 0, 0);
        ObservedBaritoneTestRoutes.swim(bot, goal, "swim_without_lease");
        BaritoneRegistry.INSTANCE.setWaterAllowed(bot, false);
        NavSafetyNet.INSTANCE.clearBaritoneWater(bot);
        boolean[] intervened = {false};
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            if (NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                intervened[0] = true;
            }
            if (intervened[0] || now > 350 || !BaritoneRegistry.INSTANCE.isBusy(bot)) {
                arena.require(intervened[0], "the safety net never intervened, so the lease test has no control (tick " + now + ", at " + bot.position() + ")");
                arena.finish(bot);
            }
        });
    }

    /** An unavailable Baritone session refuses navigation without creating an instance or starting a hidden fallback. */
    @GameTest(maxTicks = 160)
    public void baritoneUnavailableStopsNavigation(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 24, 14, 6);
        AIPlayerEntity bot = arena.spawn("BeUnavailable", arena.cell(-9, 0, 0));
        context.runAfterDelay(140, NavEngineSelector::clearFailureForTests);
        try {
            NavEngineSelector.markBaritoneUnavailable("gametest", new NoClassDefFoundError("baritone/api/BaritoneAPI"));
            arena.require(NavEngineSelector.configuredFor(bot.getUUID()) == NavEngine.BARITONE && !NavEngineSelector.baritoneSelectedFor(bot.getUUID()),
                    "fixture: Baritone is still selected after it failed");
            BlockPos goal = arena.cell(9, 0, 0);
            ActionPack pack = bot.getActionPack();
            ActionResult refused = pack.startPathTo(goal);
            arena.require(!refused.isInProgress() && "baritone_unavailable".equals(refused.reason()),
                    "unavailable Baritone accepted a route: " + refused.status() + " " + refused.reason());
            arena.require(!pack.hasBaritoneRoute() && pack.isPathExecutorIdle() && BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null,
                    "unavailable Baritone created a route, an instance, or a fallback controller");
            Vec3 stoppedAt = bot.position();
            context.runAfterDelay(30, () -> {
                try {
                    arena.require(bot.position().distanceTo(stoppedAt) < 0.05D,
                            "bot moved after unavailable navigation was refused: " + bot.position());
                    arena.require(BaritoneRegistry.INSTANCE.size() == 0, "a Baritone instance appeared after an unavailable request");
                } finally {
                    // A failed GameTest must not leak a sticky process-wide failure into later tests.
                    NavEngineSelector.clearFailureForTests();
                }
                arena.finish(bot);
            });
        } catch (RuntimeException failure) {
            NavEngineSelector.clearFailureForTests();
            throw failure;
        }
    }

    /** A Baritone failure after a route started ends that route and later requests still fail closed. */
    @GameTest(maxTicks = 180)
    public void baritoneFailureStopsActiveRoute(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 22, 14, 6);
        AIPlayerEntity bot = arena.spawn("BeFailLive", arena.cell(-10, 0, 0));
        context.runAfterDelay(160, NavEngineSelector::clearFailureForTests);
        ActionPack pack = bot.getActionPack();
        arena.require(pack.startPathTo(arena.cell(3, 0, 0)).isInProgress() && pack.hasBaritoneRoute(),
                "the Baritone route did not start");
        BlockPos retryGoal = arena.cell(3, 0, 3);
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 150, "Baritone failure test did not finish: " + bot.position());
            if (now == 12) {
                try {
                    arena.require(NavEngineSelector.baritoneActive() && BaritoneRegistry.INSTANCE.find(bot.getUUID()) != null,
                            "fixture: Baritone is not live with an instance");
                    NavEngineSelector.markBaritoneUnavailable("gametest_after_live",
                            new NoClassDefFoundError("baritone/pathing/movement/MovementHelper"));
                    arena.require(NavEngineSelector.baritoneLive() && !NavEngineSelector.baritoneActive(), "the flags after the failure are wrong");
                    arena.require(BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null && BaritoneRegistry.INSTANCE.size() == 0,
                            "the instances were not torn down");
                    arena.require(!pack.hasBaritoneRoute() && pack.isPathExecutorIdle(), "the active route outlived Baritone");
                    NavOutcome outcome = pack.lastRouteOutcome();
                    arena.require(outcome != null && outcome.status() == NavOutcome.Status.FAILED
                                    && "baritone_unavailable".equals(outcome.reason()),
                            "the running route was not ended as baritone_unavailable: " + outcome);
                    ActionResult retry = pack.startPathTo(retryGoal);
                    arena.require(!retry.isInProgress() && "baritone_unavailable".equals(retry.reason()),
                            "a route request after Baritone failure did not fail closed: " + retry.status() + " " + retry.reason());
                    context.runAfterDelay(20, () -> {
                        try {
                            // Cancelling input preserves ordinary vanilla momentum; it must not
                            // keep a controller writing fresh movement after the route is gone.
                            arena.require(!pack.hasBaritoneRoute() && pack.isPathExecutorIdle(),
                                    "a route resumed after its Baritone failure");
                            arena.require(bot.zza == 0.0F && bot.xxa == 0.0F && !bot.isSprinting(),
                                    "Baritone inputs were not released after failure");
                            arena.require(BaritoneRegistry.INSTANCE.size() == 0,
                                    "a Baritone instance reappeared after failure");
                        } finally {
                            NavEngineSelector.clearFailureForTests();
                        }
                        arena.finish(bot);
                    });
                } catch (RuntimeException failure) {
                    NavEngineSelector.clearFailureForTests();
                    throw failure;
                }
            }
        });
    }

    /** The default configuration starts a normal route through Baritone without a per-bot switch. */
    @GameTest(maxTicks = 400)
    public void defaultNavigationBootstrapsBaritone(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 23, 14, 6);
        arena.require(NavEngineSelector.configured() == NavEngine.BARITONE,
                "the default navigation engine is not Baritone");
        AIPlayerEntity bot = arena.spawn("BeDefaultBaritone", arena.cell(-9, 0, 0));
        BlockPos goal = arena.cell(4, 0, 0);
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startPathTo(goal);
        arena.require(started.isInProgress() && pack.hasBaritoneRoute() && BaritoneRegistry.INSTANCE.find(bot.getUUID()) != null,
                "the default route did not bootstrap Baritone: " + started.status() + " " + started.reason());
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 380, "the default Baritone route never arrived: " + bot.position());
            if (!pack.hasBaritoneRoute()) {
                NavOutcome outcome = pack.lastRouteOutcome();
                arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS,
                        "the default Baritone route did not succeed: " + outcome);
                arena.require(bot.position().distanceTo(goal.getCenter()) <= 2.0D,
                        "the default Baritone route ended away from the goal: " + bot.position());
                BotLog.path(bot, "gametest_baritone_default_bootstrap", "outcome", outcome.status(), "ticks", now);
                arena.finish(bot);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A 4-wide, two-deep channel across the whole course at x = 0..3 (the floor cell and the cell above it; stone below), walled by
     * the bedrock ring: the bot wades along its bottom with its eyes under the surface, which is what makes the safety net treat a
     * walker as a drowning bot. (Baritone plans a way through such a strip; see BaritonePlanningGameTests.)
     */
    private static BaritoneEngineArena channel(GameTestHelper context, int layer) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, layer, 14, 6);
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    // Placed without neighbour updates or fluid ticks: the strip stays exactly as built.
                    arena.world.setBlock(arena.cell(dx, dy, dz), Blocks.WATER.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
                }
            }
        }
        return arena;
    }
}
