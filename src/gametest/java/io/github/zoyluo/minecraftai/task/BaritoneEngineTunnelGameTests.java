package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.baritone.BaritoneBreakPlacePolicy;
import io.github.zoyluo.minecraftai.baritone.BaritoneEdits;
import io.github.zoyluo.minecraftai.baritone.BaritoneNavigator;
import io.github.zoyluo.minecraftai.baritone.BaritoneRefusals;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.baritone.PolicyRefusalStreak;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Digging on the Baritone engine under strict-survival observability, and the route lifecycle around it. Position-level rules
 * (the block must be observable from the bot's eyes) are enforced only when Baritone clicks, because a search runs on a worker
 * thread against a snapshot; these courses prove that a route the planner builds through natural stone is one the bot can also
 * carry out (no {@code not_observable} refusal for the next dig cell of a staircase or of a diagonal tunnel), and that a route the
 * rules keep refusing ends as a typed failure instead of being re-planned for ever.
 */
public final class BaritoneEngineTunnelGameTests {
    private static final double AT_GOAL = 1.7D;

    /**
     * A solid mass of natural stone (x 0..6, the whole width, up to the ceiling) with a two-cell pocket at height three behind it:
     * the only way to the goal is a staircase up through the stone, and every cell of it is dug from inside the stair as it grows.
     */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tunnel_game_tests_staircase_up_through_natural_stone_needs_no_unobservable_break", maxTicks = 1500)
    public void staircaseUpThroughNaturalStoneNeedsNoUnobservableBreak(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 22, 14, 6);
        stoneMass(arena, 0, 6);
        arena.set(4, 3, 0, Blocks.AIR);
        arena.set(4, 4, 0, Blocks.AIR);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeStairDig", arena.cell(-4, 0, 0));
        giveTools(bot);
        BlockPos goal = arena.cell(4, 3, 0);
        digCourse(context, arena, bot, goal, "staircase");
    }

    /** As above, along a diagonal of the floor: the goal is four blocks to the side and eight ahead, all through stone. */
    @GameTest(environment = "minecraftai-gametest:baritone_engine_tunnel_game_tests_diagonal_tunnel_through_natural_stone_needs_no_unobservable_break", maxTicks = 1500)
    public void diagonalTunnelThroughNaturalStoneNeedsNoUnobservableBreak(GameTestHelper context) {
        BaritoneEngineArena arena = BaritoneEngineArena.build(context, 25, 14, 6);
        stoneMass(arena, 0, 6);
        arena.set(5, 0, 4, Blocks.AIR);
        arena.set(5, 1, 4, Blocks.AIR);
        AIPlayerEntity bot = arena.spawnOnBaritone("BeDiagDig", arena.cell(-3, 0, -3));
        giveTools(bot);
        BlockPos goal = arena.cell(5, 0, 4);
        digCourse(context, arena, bot, goal, "diagonal");
    }

    private static void digCourse(GameTestHelper context, BaritoneEngineArena arena, AIPlayerEntity bot, BlockPos goal, String what) {
        ActionPack pack = bot.getActionPack();
        ActionResult started = pack.startPathTo(goal);
        arena.require(started.isInProgress(), "the " + what + " route was not accepted: " + started.status() + " " + started.reason());
        int[] tick = {0};
        context.failIfEver(() -> {
            int now = ++tick[0];
            arena.require(now < 1400, "the " + what + " route never ended: " + bot.position() + " edits=" + BaritoneEdits.of(bot.getUUID()).size());
            if (pack.hasBaritoneRoute()) {
                return;
            }
            NavOutcome outcome = pack.lastRouteOutcome();
            // (Baritone asks for its mine process once when a route starts; that scanning-process refusal is expected background.)
            List<BaritoneRefusals.Refusal> refusals = BaritoneRefusals.of(bot.getUUID()).stream()
                    .filter(refusal -> refusal.op() == BaritoneRefusals.Op.BREAK || refusal.op() == BaritoneRefusals.Op.PLACE).toList();
            arena.require(outcome != null && outcome.status() == NavOutcome.Status.SUCCESS,
                    "the " + what + " route did not succeed: " + outcome + " refusals=" + refusals);
            arena.require(refusals.stream().noneMatch(refusal -> "not_observable".equals(refusal.reason())),
                    "a dig cell of the " + what + " was refused as not observable: " + refusals);
            arena.require(refusals.isEmpty(), "a break or placement of the " + what + " was refused: " + refusals);
            List<BaritoneEdits.Edit> breaks = BaritoneEdits.of(bot.getUUID(), BaritoneEdits.Kind.BREAK);
            arena.require(breaks.size() >= 4, "too few blocks were dug for a " + what + ": " + breaks.size());
            arena.require(bot.position().distanceTo(goal.getCenter()) <= AT_GOAL, "not at the goal: " + bot.position());
            BotLog.path(bot, "gametest_tunnel_course", "what", what, "breaks", breaks.size(), "ticks", outcome.ticks(), "refusals", refusals.size());
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
        AIPlayerEntity bot = arena.spawnOnBaritone("BePolicyCap", arena.cell(-12, 0, 0));
        BlockPos goal = arena.cell(12, 0, 0);
        BlockPos veto = arena.cell(-12, -1, 0);
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
        AIPlayerEntity bot = arena.spawnOnBaritone("BeReplaced", arena.cell(-12, 0, 0));
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

    private static void giveTools(AIPlayerEntity bot) {
        bot.getInventory().setItem(1, new ItemStack(Items.IRON_AXE));
        bot.getInventory().setItem(2, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setSelectedSlot(1);
    }
}
