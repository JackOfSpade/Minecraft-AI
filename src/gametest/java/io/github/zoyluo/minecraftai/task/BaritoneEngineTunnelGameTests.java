package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.baritone.BaritoneBreakPlacePolicy;
import io.github.zoyluo.minecraftai.baritone.BaritoneEdits;
import io.github.zoyluo.minecraftai.baritone.BaritoneNavigator;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.baritone.PolicyRefusalStreak;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Baritone route lifecycle under strict-survival observability. Hidden stone must never become a
 * tunnel merely because it is in a loaded chunk: the first two courses pin admission refusal and
 * zero edits. The remaining courses cover ordinary policy refusal and route replacement.
 */
public final class BaritoneEngineTunnelGameTests {
    private static final double AT_GOAL = 1.7D;

    /**
     * A solid mass of natural stone hides a pocket behind it. The previously accepted staircase
     * tunnel would discover terrain through excavation, so the hidden goal now refuses before
     * Baritone can mine a single block.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tunnel_game_tests_staircase_up_through_natural_stone_needs_no_unobservable_break", maxTicks = 1500)
    public void hiddenStaircaseGoalIsRefusedWithoutExcavation(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 22, 14, 6);
        stoneMass(arena, 0, 6);
        arena.set(4, 3, 0, Blocks.AIR);
        arena.set(4, 4, 0, Blocks.AIR);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeStairDig", arena.cell(-4, 0, 0));
        BlockPos goal = arena.cell(4, 3, 0);
        refuseHiddenTunnelGoal(context, arena, bot, goal, "staircase");
    }

    /** The same policy holds for a diagonal hidden goal: geometry cannot turn it into a scan. */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tunnel_game_tests_diagonal_tunnel_through_natural_stone_needs_no_unobservable_break", maxTicks = 1500)
    public void hiddenDiagonalGoalIsRefusedWithoutExcavation(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 25, 14, 6);
        stoneMass(arena, 0, 6);
        arena.set(5, 0, 4, Blocks.AIR);
        arena.set(5, 1, 4, Blocks.AIR);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeDiagDig", arena.cell(-3, 0, -3));
        BlockPos goal = arena.cell(5, 0, 4);
        refuseHiddenTunnelGoal(context, arena, bot, goal, "diagonal");
    }

    private static void refuseHiddenTunnelGoal(GameTestHelper context, BaritoneEngineArena arena,
                                                AIPlayerEntity bot, BlockPos goal, String what) {
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startPathTo(goal);
        arena.require(started.isFailed() && "navigation_goal_unobserved".equals(started.reason()),
                "the hidden " + what + " goal was not refused at admission: " + started.status() + " " + started.reason());
        arena.require(!pack.hasBaritoneRoute() && !BaritoneNavigator.hasRoute(bot.getUUID()),
                "the refused hidden " + what + " goal left a route active");
        arena.require(BaritoneEdits.of(bot.getUUID()).isEmpty(),
                "the hidden " + what + " goal edited terrain at admission: " + BaritoneEdits.of(bot.getUUID()));
        context.runAfterDelay(20, () -> {
            arena.require(!pack.hasBaritoneRoute() && !BaritoneNavigator.hasRoute(bot.getUUID()),
                    "the refused hidden " + what + " goal started a route later");
            arena.require(BaritoneEdits.of(bot.getUUID()).isEmpty(),
                    "the hidden " + what + " goal later edited terrain: " + BaritoneEdits.of(bot.getUUID()));
            arena.finish(bot);
        });
    }

    /**
     * A route that the strict-survival rules keep refusing (here: a walk-only route whose bot is asked to break a block every tick,
     * which is what a route through blocks it may not touch amounts to) ends after the cap as {@code FAILED policy_refused}, and
     * Baritone has let go of the bot.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tunnel_game_tests_a_route_that_is_vetoed_again_and_again_ends_as_policy_refused", maxTicks = 600)
    public void aRouteThatIsVetoedAgainAndAgainEndsAsPolicyRefused(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, -1, 14, 6);
        // Keep the goal inside the strict player-observation radius: this course exercises
        // repeated policy vetoes, not blind waypoint admission.
        AIPlayerEntity bot = arena.spawnOnBaritone("BePolicyCap", arena.cell(-3, 0, 0));
        BlockPos goal = arena.cell(12, 0, 0);
        BlockPos veto = arena.cell(-3, -1, 0);
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startSurfacePathTo(goal);
        arena.require(started.isInProgress(), "the walk was not accepted: " + started.status() + " " + started.reason());
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 500, "the vetoed route never ended: " + bot.position());
            if (pack.hasBaritoneRoute()) {
                arena.require(PolicyRefusalStreak.of(bot.getUUID()) < PolicyRefusalStreak.CAP + 2, "the streak went past the cap and the route runs on");
                // The bot's own permission says walk only, so this break is refused (and counted) whatever the block is.
                arena.require(!BaritoneBreakPlacePolicy.checkBreak(bot, veto).allowed(), "a walk-only route was allowed to break");
                return;
            }
            NavOutcome outcome = pack.lastRouteOutcome();
            arena.require(outcome != null && outcome.status() == NavOutcome.Status.FAILED && "policy_refused".equals(outcome.reason()),
                    "the route did not end as policy_refused: " + outcome);
            arena.require(now >= PolicyRefusalStreak.CAP, "the route ended before the cap (tick " + now + ")");
            arena.require(!BaritoneRegistry.INSTANCE.isBusy(bot), "Baritone still drives the bot after the route ended");
            arena.require(!BaritoneNavigator.hasRoute(bot.getUUID()), "the navigator still counts the ended route");
            arena.finish(bot);
        });
    }

    /**
     * A route replaced by a newer request is recorded as cancelled ("replaced"), not silently overwritten, and the navigator's
     * bookkeeping follows the newer route to its end.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tunnel_game_tests_a_route_replaced_by_anewer_request_is_recorded_as_cancelled", maxTicks = 600)
    public void aRouteReplacedByANewerRequestIsRecordedAsCancelled(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, -2, 14, 6);
        // Both replacement goals are genuinely visible from this start. A rejected hidden
        // replacement would only test the observation fence, not route lifecycle bookkeeping.
        AIPlayerEntity bot = arena.spawnOnBaritone("BeReplaced", arena.cell(-3, 0, 0));
        BlockPos first = arena.cell(12, 0, -4);
        BlockPos second = arena.cell(12, 0, 4);
        ActionPack pack = bot.getActionPack();
        arena.require(pack.startPathTo(first).isInProgress(), "the first route was not accepted");
        int[] tick = {0};
        boolean[] replaced = {false};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 550, "the routes never ended: " + bot.position());
            if (now == 15) {
                arena.require(pack.hasBaritoneRoute(), "the first route ended before it was replaced");
                arena.require(pack.startPathTo(second).isInProgress(), "the second route was not accepted");
                NavOutcome outcome = pack.lastRouteOutcome();
                arena.require(outcome != null && outcome.status() == NavOutcome.Status.CANCELLED && "replaced".equals(outcome.reason())
                                && outcome.goal().equals(first),
                        "the replaced route was not recorded as cancelled/replaced: " + outcome);
                arena.require(BaritoneNavigator.hasRoute(bot.getUUID()), "the newer route lost its water bookkeeping to the replaced one");
                replaced[0] = true;
                return;
            }
            if (replaced[0] && !pack.hasBaritoneRoute()) {
                NavOutcome outcome = pack.lastRouteOutcome();
                arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS && outcome.goal().equals(second),
                        "the newer route did not succeed: " + outcome);
                arena.require(bot.position().distanceTo(second.getCenter()) <= AT_GOAL, "not at the second goal: " + bot.position());
                arena.require(!BaritoneNavigator.hasRoute(bot.getUUID()), "the navigator still counts the ended route");
                arena.finish(bot);
            }
        });
    }

    /**
     * A swim route that is refused at admission (its goal is sealed in bedrock) leaves nothing behind: no dry/swim route entry, no
     * water lease that would suppress the drowning safety net for a bot in the water.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tunnel_game_tests_a_refused_swim_route_leaves_no_lease_and_no_route_entry", maxTicks = 300)
    public void aRefusedSwimRouteLeavesNoLeaseAndNoRouteEntry(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, -3, 14, 6);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                arena.world.setBlock(arena.cell(dx, 0, dz), Blocks.WATER.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
            }
        }
        // The goal cell is a pocket in a solid bedrock cube: nothing can reach it.
        for (int dx = 7; dx <= 9; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    arena.set(dx, dy, dz, Blocks.BEDROCK);
                }
            }
        }
        arena.set(8, 0, 0, Blocks.AIR);
        arena.set(8, 1, 0, Blocks.AIR);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeRefusedSwim", arena.cell(0, 0, 0));
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startSwimRouteTo(arena.cell(8, 0, 0));
        arena.require(started.isFailed(), "the sealed goal was accepted: " + started.status());
        arena.require(!pack.hasBaritoneRoute(), "a refused request left a route");
        arena.require(!BaritoneNavigator.hasRoute(bot.getUUID()), "a refused request left a dry/swim route entry");
        arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "a refused request left a water lease");
        context.runAfterDelay(20, () -> {
            arena.require(!NavSafetyNet.INSTANCE.hasBaritoneWaterLease(bot), "the lease appeared later");
            arena.finish(bot);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Natural stone from column {@code fromDx} to {@code toDx}, across the whole width, floor to just under the ceiling. */
    private static void stoneMass(BaritoneEngineArena arena, int fromDx, int toDx) {
        for (int dx = fromDx; dx <= toDx; dx++) {
            for (int dz = -arena.halfZ; dz <= arena.halfZ; dz++) {
                arena.fill(dx, dz, Blocks.STONE, 0, BaritoneEngineArena.CEILING - 1);
            }
        }
    }

}
