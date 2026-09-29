package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.PathCalculationResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.task.NavSafetyNet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The Baritone side of the navigator seam in {@code ActionPack}: turns a walk request ({@link NavRoute}) into a Baritone goal,
 * admits or refuses it, and answers where the route stands. Everything in here may initialise Baritone, so {@code ActionPack}
 * only reaches it through {@code NavEngineSelector.attempt} and only while the engine is {@code baritone}.
 *
 * <p>Admission is synchronous, because the ~40 callers of the ActionPack path API get their answer in the same tick and choose
 * their fallback from it (waypoint relay, digging, another candidate cell): Baritone's own A* runs once inline with a small
 * budget ({@value #ADMISSION_PRIMARY_MS}/{@value #ADMISSION_FAILURE_MS} ms). No path at all is {@code pathfinding_failed:
 * GOAL_UNREACHABLE}; a partial path (the goal is far, or only reachable in stages) is accepted, which is Baritone's answer to the
 * legacy "straight-line fallback". The route itself is then executed by {@code CustomGoalProcess} through the tick driver and
 * re-planned by Baritone as it goes (its own search runs on the worker pool, so the server thread only pays for the admission).</p>
 *
 * <p>Water: a route that does not swim must stay dry. Baritone's cost model has no per-request switch for that, so the global
 * {@code blocksToAvoid} list carries {@code Blocks.WATER} exactly while at least one dry route is active and no swim route is
 * ({@link #applyWaterPolicy}); a dry route that still ends up in the water is abandoned by {@code ActionPack} (rule check).</p>
 */
public final class BaritoneNavigator {
    /** Budget of the inline admission search. */
    static final long ADMISSION_PRIMARY_MS = 40L;
    static final long ADMISSION_FAILURE_MS = 100L;

    /** Budget of the one-off warm-up search (loads and JITs the movement and search classes before the first admission is timed). */
    private static final long WARM_UP_MS = 2000L;
    private static boolean warmedUp;

    /** Bots whose current route is dry / may swim; decides whether water is in Baritone's avoid list. */
    private static final Set<UUID> DRY_ROUTES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> SWIM_ROUTES = ConcurrentHashMap.newKeySet();

    private BaritoneNavigator() {
    }

    /** What the admission found. {@code failure} is the {@code pathfinding_failed: ...} reason when not accepted. */
    public record Admission(boolean accepted, String failure) {
        static Admission ok() {
            return new Admission(true, "");
        }

        static Admission refused(String failure) {
            return new Admission(false, failure);
        }
    }

    /**
     * Starts (or re-targets) the bot's Baritone route.
     *
     * @param admit run the inline search first and refuse the route when Baritone finds no path at all; false re-targets a
     *              route that is already running without paying for another search on the server thread
     */
    public static Admission start(AIPlayerEntity bot, NavRoute route, boolean admit) {
        BaritoneRegistry registry = BaritoneRegistry.INSTANCE;
        IBaritone baritone = registry.get(bot);
        BaritoneSettings.applyNavLimits();
        warmUp(baritone, bot);
        NavRoute.Options options = route.options();
        registry.setPolicy(bot, policyOf(options));
        registry.setWaterAllowed(bot, options.allowWater());
        if (options.allowWater()) {
            // The lease starts with the route, not with the first driven tick: a bot that is already in the water when a swim route
            // is issued would otherwise get one tick of the safety net's rescue in between.
            NavSafetyNet.INSTANCE.renewBaritoneWater(bot);
        }
        applyWaterPolicy(bot.getUUID(), options.allowWater());
        Goal goal = goalOf(bot, route);
        route.setGoalHandle(goal);
        registry.clearLastPathEvent(bot);

        if (admit) {
            BaritonePlanner.Plan plan = BaritonePlanner.planNow(baritone, goal, ADMISSION_PRIMARY_MS, ADMISSION_FAILURE_MS);
            PathCalculationResult.Type type = plan.type();
            BotLog.path(bot, "baritone_admission", "goal", goal, "type", type, "nodes", plan.nodesConsidered(),
                    "moves", plan.movements().size(), "search_ms", plan.searchMillis(), "policy", policyOf(options));
            String refusal = admissionFailure(type, plan.searchMillis(), ADMISSION_FAILURE_MS);
            if (refusal == null && route.shape() == NavRoute.Shape.BLOCK
                    && exhaustedPartial(type, plan.searchMillis(), ADMISSION_PRIMARY_MS, goalColumnLoaded(bot, route))) {
                // An exact-cell request whose search ran out of places to look (before its budget) without reaching the goal: the
                // cell cannot be reached over the loaded terrain. The legacy answer is "unreachable", which is what callers (waypoint
                // relay, digging, the next candidate cell) switch strategy on; Baritone's partial path to the closest point is
                // only wanted for approach requests (follow), where walking as far as possible is the point.
                refusal = NavRouteRules.GOAL_UNREACHABLE;
            }
            if (refusal != null) {
                releaseRoute(bot.getUUID());
                return Admission.refused(refusal);
            }
            if (plan.reachesGoal() && plan.path() != null) {
                route.setResolvedGoal(plan.path().getDest());
            }
        }
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
        return Admission.ok();
    }

    /**
     * The first search of a JVM spends its time loading and compiling classes, which would eat the small admission budget and make
     * the first route of a session fail as "unreachable". One throwaway search towards a nearby column, with a generous budget,
     * pays that cost up front (once, on the server thread, like the class loading it replaces would be).
     */
    private static synchronized void warmUp(IBaritone baritone, AIPlayerEntity bot) {
        if (warmedUp) {
            return;
        }
        warmedUp = true;
        long started = System.nanoTime();
        BlockPos feet = bot.blockPosition();
        BaritonePlanner.Plan plan = BaritonePlanner.planNow(baritone, new GoalXZ(feet.getX() + 12, feet.getZ()), WARM_UP_MS, WARM_UP_MS);
        BotLog.path(bot, "baritone_warm_up", "type", plan.type(), "ms", (System.nanoTime() - started) / 1_000_000L);
    }

    /** Whether Baritone still owns the bot, has arrived at the route's goal, or ended short of it. */
    public static NavRoute.Progress progress(AIPlayerEntity bot, NavRoute route) {
        if (BaritoneRegistry.INSTANCE.isBusy(bot)) {
            return NavRoute.Progress.RUNNING;
        }
        IBaritone baritone = BaritoneRegistry.INSTANCE.find(bot.getUUID());
        if (baritone != null && route.goalHandle() instanceof Goal goal && goal.isInGoal(baritone.getPlayerContext().playerFeet())) {
            return NavRoute.Progress.ARRIVED;
        }
        return NavRoute.Progress.ENDED_SHORT;
    }

    /** Whether Baritone's own search reported a failure for the bot's current route (the reason it ended short of its goal). */
    public static boolean searchFailed(AIPlayerEntity bot) {
        return BaritoneRegistry.INSTANCE.lastPathEvent(bot) == PathEvent.CALC_FAILED;
    }

    /**
     * A partial path that ended before its time budget was used up, towards a goal in a loaded column: the search exhausted
     * everything it could reach, so the goal is not reachable. (A search cut off by its budget, or one that ran into the edge of
     * the loaded world, also returns a partial path; those are the "keep going" cases.)
     */
    static boolean exhaustedPartial(PathCalculationResult.Type type, long searchMillis, long primaryBudgetMillis, boolean goalColumnLoaded) {
        return type == PathCalculationResult.Type.SUCCESS_SEGMENT && goalColumnLoaded && searchMillis + 5L < primaryBudgetMillis;
    }

    private static boolean goalColumnLoaded(AIPlayerEntity bot, NavRoute route) {
        BlockPos target = route.target();
        return bot.level().getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4);
    }

    /**
     * The admission verdict of an inline search in the legacy vocabulary: null when the route may start (a complete path, or a
     * partial one that gets the bot moving), else the {@code pathfinding_failed: ...} reason.
     */
    static String admissionFailure(PathCalculationResult.Type type, long searchMillis, long failureBudgetMillis) {
        return switch (type) {
            case SUCCESS_TO_GOAL, SUCCESS_SEGMENT -> null;
            // A search that ran into its budget without finding anything proves nothing (a cold start loads classes inside the
            // budget, a busy server is slow): the route starts and Baritone's own, longer search on the worker pool decides.
            case FAILURE -> searchMillis + 5L >= failureBudgetMillis ? null : NavRouteRules.GOAL_UNREACHABLE;
            case CANCELLATION -> NavRouteRules.GOAL_UNREACHABLE;
            case EXCEPTION -> "pathfinding_failed: baritone_exception";
        };
    }

    /** Stops whatever Baritone is doing for the bot (goal, path, search, inputs, block being broken) and lets go of it. */
    public static void cancel(AIPlayerEntity bot, String why) {
        BaritoneRegistry.INSTANCE.preempt(bot, why);
    }

    /** The route of {@code botId} is over (any way): its water rule no longer counts. */
    public static void releaseRoute(UUID botId) {
        boolean changed = DRY_ROUTES.remove(botId) | SWIM_ROUTES.remove(botId);
        if (changed) {
            syncWaterAvoidance();
        }
    }

    /** Whether the navigator still counts a route (dry or swimming) for the bot: false once it ended, was cancelled or the bot was removed. */
    public static boolean hasRoute(UUID botId) {
        return DRY_ROUTES.contains(botId) || SWIM_ROUTES.contains(botId);
    }

    /** Every route is over (server stop, registry cleared). */
    static void releaseAllRoutes() {
        DRY_ROUTES.clear();
        SWIM_ROUTES.clear();
        syncWaterAvoidance();
    }

    /** Baritone's goal for the request: the cell itself when it can be stood in, else "within one block of it". */
    static Goal goalOf(AIPlayerEntity bot, NavRoute route) {
        return switch (route.shape()) {
            case NEAR -> new GoalNear(route.target(), route.radius());
            case BLOCK -> Standability.isStandable(bot.level(), route.target())
                    ? new GoalBlock(route.target())
                    : new GoalNear(route.target(), 1);
        };
    }

    /** The per-bot break/place permission that matches the request's options (using the presets, never a new combination). */
    static BaritonePolicy policyOf(NavRoute.Options options) {
        if (options.allowBreak() && options.allowPlace()) {
            return BaritonePolicy.UNRESTRICTED;
        }
        if (options.allowBreak()) {
            return BaritonePolicy.NO_PLACING;
        }
        if (options.allowPlace()) {
            return BaritonePolicy.NO_BREAKING;
        }
        return BaritonePolicy.WALK_ONLY;
    }

    private static void applyWaterPolicy(UUID botId, boolean allowWater) {
        if (allowWater) {
            DRY_ROUTES.remove(botId);
            SWIM_ROUTES.add(botId);
        } else {
            SWIM_ROUTES.remove(botId);
            DRY_ROUTES.add(botId);
        }
        syncWaterAvoidance();
    }

    /** Water is in Baritone's avoid list while some dry route runs and no swim route does. */
    private static synchronized void syncWaterAvoidance() {
        boolean avoid = !DRY_ROUTES.isEmpty() && SWIM_ROUTES.isEmpty();
        Settings settings = BaritoneAPI.getSettings();
        List<Block> current = settings.blocksToAvoid.value;
        if (current.contains(Blocks.WATER) == avoid) {
            return;
        }
        List<Block> next = new ArrayList<>(current);
        if (avoid) {
            next.add(Blocks.WATER);
        } else {
            next.removeIf(Blocks.WATER::equals);
        }
        settings.blocksToAvoid.value = next; // a new list: worker threads read the old one or the new one, never a half-edited one
    }
}
