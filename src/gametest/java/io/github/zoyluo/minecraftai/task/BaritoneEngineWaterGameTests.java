package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
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
 * NavSafetyNet's Baritone water-lease behavior, fail-closed handling when Baritone is unavailable,
 * and default Baritone bootstrap. The lease tests exercise the live safety net directly; route
 * admission and driver renewal/release are covered by the Baritone contract tests.
 */
public final class BaritoneEngineWaterGameTests {
    /** A renewed live water lease keeps the safety net from taking over, and release hands the swimmer back to it. */
    @GameTest(maxTicks = 160)
    public void renewedWaterLeaseSuppressesRescueUntilItEnds(GameTestHelper context) {
        BaritoneEngineArena arena = channel(context, 20);
        AIPlayerEntity bot = arena.spawn("BeWaterLease", arena.cell(1, -1, 0));
        NavSafetyNet.INSTANCE.renewBaritoneWater(bot);
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 130, "the safety net did not take over after the water lease ended");
            if (now <= 12) {
                NavSafetyNet.INSTANCE.renewBaritoneWater(bot);
                arena.require(NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "the renewed water lease was not held");
                arena.require(!NavSafetyNet.INSTANCE.isWaterRescueActive(bot), "the safety net took over a renewed water lease");
                return;
            }
            if (now == 13) {
                NavSafetyNet.INSTANCE.clearBaritoneWater(bot);
                arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "the water lease remained after release");
            }
            if (NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "a released water lease reappeared during rescue");
                arena.finish(bot);
            }
        });
    }

    /** A lease revoked before the next safety tick does not hide an accidental water entry. */
    @GameTest(maxTicks = 160)
    public void revokedWaterLeaseLetsTheSafetyNetTakeOver(GameTestHelper context) {
        BaritoneEngineArena arena = channel(context, 21);
        AIPlayerEntity bot = arena.spawn("BeWaterNoLease", arena.cell(1, -1, 0));
        NavSafetyNet.INSTANCE.renewBaritoneWater(bot);
        NavSafetyNet.INSTANCE.clearBaritoneWater(bot);
        arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "fixture: the water lease was not revoked");
        int[] tick = {0};
        context.failIfEver(() -> {
            arena.require(++tick[0] < 130, "the safety net did not take over a revoked water lease");
            if (NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "the revoked water lease returned during rescue");
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

    private static BaritoneEngineArena channel(GameTestHelper context, int layer) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, layer, 14, 6);
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    arena.world.setBlock(arena.cell(dx, dy, dz), Blocks.WATER.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
                }
            }
        }
        return arena;
    }

}
