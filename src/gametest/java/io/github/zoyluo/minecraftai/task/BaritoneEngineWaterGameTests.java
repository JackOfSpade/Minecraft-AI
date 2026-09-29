package io.github.zoyluo.minecraftai.task;

import baritone.api.pathing.goals.GoalBlock;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Baritone driving a bot through water (the drowning safety net must not take a swimmer over while Baritone owns the route),
 * the fail-soft fallback to the legacy navigator, and the guarantee that the legacy engine never loads Baritone.
 */
public final class BaritoneEngineWaterGameTests {
    private static final double AT_GOAL = 1.7D;

    /**
     * A swim route across a two-deep, four-wide channel for a bot that starts submerged on its bottom (it has just fallen in): with
     * its eyes under the surface the safety net declares a rescue and moves the bot by a block or more per step (the spike measured
     * a 1.43-block displacement), off the path Baritone is walking it along. With the Baritone lease it must not: every tick's
     * displacement is swimming speed, no rescue is ever active, and the lease ends with the route.
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
                System.out.println("BARITONE_SWIM in_water_ticks=" + inWaterTicks[0] + " underwater_ticks=" + underwaterTicks[0] + " lease_ticks=" + leaseTicks[0] + " max_step=" + maxStep[0]);
                arena.finish(bot);
            }
        });
    }

    /**
     * The control of the test above: the same bot in the same place, driven by Baritone directly with no swim permission recorded
     * (what the spike did). The safety net must interfere here, otherwise the lease test would prove nothing.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_water_game_tests_baritone_swim_route_is_not_hijacked_by_the_safety_net", maxTicks = 500)
    public void withoutTheLeaseTheSafetyNetTakesTheSwimmerOver(GameTestHelper context) {
        BaritoneEngineArena arena = channel(context, 21);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeSwimNoLease", arena.cell(-6, 0, 0));
        arena.teleportTo(bot, arena.cell(1, -1, 0));
        BlockPos goal = arena.cell(8, 0, 0);
        BaritoneRegistry.INSTANCE.get(bot).getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
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

    /** Baritone is the configured engine but cannot be initialised: the request is answered by the legacy navigator. */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_water_game_tests_baritone_unavailable_falls_back_to_the_legacy_navigator", maxTicks = 400)
    public void baritoneUnavailableFallsBackToTheLegacyNavigator(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 24, 14, 6);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeFallback", arena.cell(-9, 0, 0));
        // Whatever happens, the failure flag is not left behind for the tests that follow.
        context.runAfterDelay(380, NavEngineSelector::clearFailureForTests);
        NavEngineSelector.markBaritoneUnavailable("gametest", new NoClassDefFoundError("baritone/api/BaritoneAPI"));
        arena.require(NavEngineSelector.configuredFor(bot.getUUID()) == NavEngine.BARITONE && !NavEngineSelector.baritoneSelectedFor(bot.getUUID()),
                "fixture: Baritone is still selected after it failed");
        BlockPos goal = arena.cell(9, 0, 0);
        ActionPack pack = bot.getActionPack();
        MoveTask move = new MoveTask(bot, goal);
        TaskManager.INSTANCE.assign(bot, move, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_baritone_fallback"));
        int[] tick = {0};
        context.failIfEver(() -> {
            tick[0]++;
            arena.require(!pack.hasBaritoneRoute() && BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null,
                    "a bot whose Baritone failed to initialise got a Baritone route or instance");
            if (move.state() == TaskState.COMPLETED) {
                arena.require(bot.position().distanceTo(goal.getCenter()) <= 2.0D, "the legacy fallback did not get there: " + bot.position());
                arena.require(pack.lastRouteOutcome() == null, "a Baritone outcome was recorded");
                NavEngineSelector.clearFailureForTests();
                arena.finish(bot);
                return;
            }
            arena.require(move.state() == TaskState.RUNNING, "move ended: " + move.state() + " " + move.failureReason());
        });
    }

    /**
     * With the default (legacy) engine no Baritone class is loaded by the mod's own hooks: the per-tick driver hook, the lifecycle
     * hooks and every ActionPack entry work without initialising anything. (The mixins that expose vanilla classes to Baritone
     * are applied at start-up and load a few interfaces; the classes checked here are the ones that hold behaviour or state.)
     * If an earlier test of the same server already used Baritone the check cannot tell and only the legacy route is verified.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_water_game_tests_legacy_engine_loads_no_baritone_classes", maxTicks = 400)
    public void legacyEngineLoadsNoBaritoneClasses(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 23, 14, 6);
        String[] markers = {
                "baritone.api.BaritoneAPI", "baritone.Baritone", "baritone.behavior.PathingBehavior",
                "io.github.zoyluo.minecraftai.baritone.BaritoneNavigator",
                "io.github.zoyluo.minecraftai.baritone.BaritoneHost",
                "io.github.zoyluo.minecraftai.baritone.BaritoneRegistry", "io.github.zoyluo.minecraftai.baritone.BaritoneDriver",
                "io.github.zoyluo.minecraftai.baritone.BaritoneExecutor", "io.github.zoyluo.minecraftai.baritone.BaritoneNavigator",
                "io.github.zoyluo.minecraftai.baritone.ServerPlayerContext", "io.github.zoyluo.minecraftai.baritone.BaritoneSettings"};
        boolean conclusive = loadedMarkers(markers).isEmpty() && !NavEngineSelector.baritoneLive();
        arena.require(NavEngineSelector.configured() == NavEngine.LEGACY, "the default engine is not legacy");
        AIPlayerEntity bot = arena.spawn("BeLegacyLazy", arena.cell(-9, 0, 0));
        BlockPos goal = arena.cell(9, 0, 0);
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startPathTo(goal);
        arena.require(started.isInProgress() && !pack.isPathExecutorIdle() && !pack.hasBaritoneRoute(), "the legacy navigator did not take the route");
        pack.hasActiveActions();
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            if (now == 20) {
                // Every ActionPack entry a caller may use, mid-route.
                pack.stopNavigation();
                pack.startWalkTo(goal.getCenter());
                pack.stopAll();
                arena.require(pack.startPathTo(goal).isInProgress(), "the legacy navigator did not restart");
                arena.require(pack.lastRouteOutcome() == null, "a Baritone outcome was recorded by the legacy engine");
            }
            if (pack.isPathExecutorIdle() && now > 25) {
                arena.require(bot.position().distanceTo(goal.getCenter()) <= 2.0D, "the legacy route did not arrive: " + bot.position());
                java.util.List<String> loaded = loadedMarkers(markers);
                System.out.println("BARITONE_LAZY conclusive=" + conclusive + " loaded_after=" + loaded);
                if (conclusive) {
                    arena.require(loaded.isEmpty(), "the legacy engine loaded Baritone classes: " + loaded);
                    arena.require(!NavEngineSelector.baritoneLive(), "the legacy engine marked Baritone live");
                }
                arena.finish(bot);
            }
            arena.require(now < 380, "the legacy route never arrived: " + bot.position());
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

    private static java.util.List<String> loadedMarkers(String[] names) {
        java.util.List<String> loaded = new java.util.ArrayList<>();
        for (String name : names) {
            if (FabricLauncherBase.getLauncher().isClassLoaded(name)) {
                loaded.add(name);
            }
        }
        return loaded;
    }
}
