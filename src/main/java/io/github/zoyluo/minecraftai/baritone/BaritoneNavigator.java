package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.movement.movements.MovementFall;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.FallSafety;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import io.github.zoyluo.minecraftai.navigation.NavigationMeasurement;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.task.NavSafetyNet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The Baritone side of the navigator seam in {@code ActionPack}: turns a walk request ({@link NavRoute}) into a Baritone goal,
 * admits or refuses it, and answers where the route stands. Everything in here may initialise Baritone, so {@code ActionPack}
 * only reaches it through {@code NavEngineSelector.attempt} while Baritone is available.
 *
 * <p>Admission is synchronous, because the ~40 callers of the ActionPack path API get their answer in the same tick and choose
 * their fallback from it (waypoint relay, digging, another candidate cell): Baritone's own A* runs once inline with a small
 * budget ({@value #ADMISSION_PRIMARY_MS}/{@value #ADMISSION_FAILURE_MS} ms). No path at all is {@code pathfinding_failed:
 * GOAL_UNREACHABLE}; a partial path (the goal is far, or only reachable in stages) is accepted where it is safe to continue. A dry
 * route forbidden from placing is the exception: it must have a complete observed path before it can put physical momentum near an
 * edge. The route itself is then executed by {@code CustomGoalProcess} through the tick driver and
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
    /** One bounded retry when an observed hostile makes the strict, frozen-terrain search wider. */
    private static final long OBSERVED_HOSTILE_RETRY_PRIMARY_MS = 200L;
    private static final long OBSERVED_HOSTILE_RETRY_FAILURE_MS = 250L;

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

    /** Mutable route authority that must survive a failed candidate until that candidate takes over the goal process. */
    private record StartState(BaritonePolicy policy, boolean waterAllowed, ObservedNavigationFence fence,
                              NavRoute observedRoute, boolean registeredRoute) {
    }

    /** A fully planned candidate that still has no published route authority. */
    private record CandidateStart(Goal goal, String refusal) {
        static CandidateStart ready(Goal goal) {
            return new CandidateStart(java.util.Objects.requireNonNull(goal, "goal"), null);
        }

        static CandidateStart refused(String reason) {
            return new CandidateStart(null, java.util.Objects.requireNonNull(reason, "reason"));
        }
    }

    /**
     * Starts (or re-targets) the bot's Baritone route.
     *
     * @param admit run the inline search first and refuse the route when Baritone finds no path at all; false re-targets a
     *              route that is already running without paying for another search on the server thread
     */
    public static Admission start(AIPlayerEntity bot, NavRoute route, boolean admit) {
        // ActionPack is the only production caller, but keep the mutation boundary defensive:
        // a guarded physical step must not even initialise or retarget a Baritone process before
        // its exact owner reconciles the lease.
        if (bot.getActionPack().baritoneControlBlocked()) {
            return Admission.refused(ActionPack.GUARDED_STEP_FENCE);
        }
        if (route.shape() == NavRoute.Shape.OWNER_FOLLOW && !isAuthorizedOwnerFollow(bot, route)) {
            // OWNER_FOLLOW is deliberately not a generic x-ray navigation shape. It is admitted only for the bot's current owner,
            // only toward that owner's current live cell, and only as a no-place route.
            return Admission.refused("owner_follow_target_not_owner");
        }
        BaritoneRegistry registry = BaritoneRegistry.INSTANCE;
        IBaritone baritone = registry.get(bot);
        // The route must earn an immutable observation snapshot before even the one-off warm-up
        // search exists. That ordering is the hard boundary that prevents Baritone from inspecting
        // a merely loaded chunk while deciding whether a route is possible.
        MinecraftAiConfig.Nav candidateNav = MinecraftAiConfig.get().nav();
        MinecraftAiConfig.BaritoneCaps observationCaps = admit
                ? candidateNav.baritoneCaps() : BaritoneSettings.activeNavigationCaps(bot);
        ObservedNavigationFence.Capture observed = ObservedNavigationFence.admit(
                bot, route, registry.observationMemory(bot), registry.observationFence(bot).generation() + 1L, observationCaps);
        if (!observed.accepted()) {
            BotLog.path(bot, "nav_goal_rejected", "goal", route.target(), "reason", observed.failure(),
                    "rays", observed.rays(), "fresh_cells", observed.freshCells());
            return Admission.refused(observed.failure());
        }
        NavRoute.Options options = route.options();
        // What a refused start puts back: the permission of the route that is still running (a re-target that fails leaves it running),
        // or walk-only when nothing runs, so a refused start never leaves a bot that just failed to route with the new route's break/place rights.
        BaritonePolicy previousPolicy = registry.isBusy(bot) ? registry.policy(bot) : BaritonePolicy.WALK_ONLY;
        BaritonePolicy routePolicy = observed.provenance() == ObservedNavigationFence.TargetProvenance.REMEMBERED
                ? BaritonePolicy.WALK_ONLY : policyOf(options);
        BaritoneRegistry.Entry entry = java.util.Objects.requireNonNull(registry.entry(bot.getUUID()), "baritone entry");
        StartState predecessor = new StartState(previousPolicy, entry.waterAllowed, entry.context.observationFence(),
                entry.observedRoute, hasRoute(bot.getUUID()));
        ServerPlayerContext.NavigationLimits previousNavigationLimits = null;
        // This becomes irreversible before cancelling the old executor: every later failure must
        // clear the candidate instead of restoring metadata for a route that no longer exists.
        boolean replacementPreempted = false;
        boolean goalHandedOff = false;
        boolean accepted = false;
        try {
            // From here onward every mutation belongs to this candidate and the finally block
            // revokes it on every refusal or Throwable. Candidate terrain, permissions, water,
            // and movement switches remain server-thread-local until the goal hand-off succeeds.
            route.setRevalidateRememberedTarget(observed.provenance() == ObservedNavigationFence.TargetProvenance.REMEMBERED);
            route.setObservedPillarGoal(observed.pillarGoal());
            route.setPillarPlacementColumn(observed.pillarBase());
            CandidateStart candidate = entry.context.withCandidateRouteAuthority(
                    observed.fence(), route, routePolicy, options.allowWater(), () -> {
                if (admit) {
                    warmUp(baritone, bot, candidateNav);
                }
                Goal goal = goalOf(bot, route);
                if (!admit) {
                    return CandidateStart.ready(goal);
                }
                boolean capturePlanner = NavigationMeasurement.isCapturing(bot);
                long admissionStarted = capturePlanner ? System.nanoTime() : 0L;
                BaritonePlanner.Plan plan = BaritonePlanner.planNow(baritone, goal,
                        ADMISSION_PRIMARY_MS, ADMISSION_FAILURE_MS, candidateNav);
                if (capturePlanner) {
                    NavigationMeasurement.recordPlanner(bot, io.github.zoyluo.minecraftai.navigation.NavEngine.BARITONE,
                            "admission", System.nanoTime() - admissionStarted, plan.searchMillis(), plan.nodesConsidered(),
                            plan.movements().size(), plan.type().name());
                }
                PathCalculationResult.Type type = plan.type();
                int observedHostiles = observedHostileCount(baritone, candidateNav.baritoneCaps().mobAvoidanceEnabled());
                long primaryBudgetMs = ADMISSION_PRIMARY_MS;
                long failureBudgetMs = ADMISSION_FAILURE_MS;
                // Baritone checks its timeout only in batches of nodes. A visible hostile makes a
                // genuine detour graph wider, so one 40ms segment can end before the same frozen
                // observation fence has yielded a complete safe route. Re-run only in that case;
                // it receives no new world/entity reads and a partial retry remains refused below.
                if (needsObservedHostileAdmissionRetry(route, type, observedHostiles, plan.searchMillis())) {
                    BaritonePlanner.Plan firstPlan = plan;
                    long retryStarted = capturePlanner ? System.nanoTime() : 0L;
                    plan = BaritonePlanner.planNow(baritone, goal,
                            OBSERVED_HOSTILE_RETRY_PRIMARY_MS, OBSERVED_HOSTILE_RETRY_FAILURE_MS, candidateNav);
                    primaryBudgetMs = OBSERVED_HOSTILE_RETRY_PRIMARY_MS;
                    failureBudgetMs = OBSERVED_HOSTILE_RETRY_FAILURE_MS;
                    if (capturePlanner) {
                        NavigationMeasurement.recordPlanner(bot, io.github.zoyluo.minecraftai.navigation.NavEngine.BARITONE,
                                "admission_observed_hostile_retry", System.nanoTime() - retryStarted,
                                plan.searchMillis(), plan.nodesConsidered(), plan.movements().size(), plan.type().name());
                    }
                    BotLog.action(bot, "baritone_observed_hostile_admission_retry",
                            "hostiles", observedHostiles,
                            "first_type", firstPlan.type(),
                            "first_nodes", firstPlan.nodesConsidered(),
                            "first_moves", firstPlan.movements().size(),
                            "first_search_ms", firstPlan.searchMillis(),
                            "retry_primary_ms", OBSERVED_HOSTILE_RETRY_PRIMARY_MS,
                            "retry_failure_ms", OBSERVED_HOSTILE_RETRY_FAILURE_MS,
                            "retry_type", plan.type(),
                            "retry_nodes", plan.nodesConsidered(),
                            "retry_moves", plan.movements().size(),
                            "retry_search_ms", plan.searchMillis());
                    type = plan.type();
                }
                BotLog.path(bot, "baritone_admission", "goal", goal, "type", type, "nodes", plan.nodesConsidered(),
                        "moves", plan.movements().size(), "search_ms", plan.searchMillis(), "policy", routePolicy,
                        "observation_cells", observed.fence().cellCount(), "observed_hostiles", observedHostiles);
                // A no-place route cannot safely use Baritone's ordinary partial-path
                // continuation: a segment that stops at a visible gap can carry the bot's
                // momentum over the edge before a later replan has an answer. A complete
                // observed break route remains valid; only the partial result is fenced.
                BaritonePlanner.Plan admissionPlan = plan;
                String refusal = BaritoneSettings.withCandidateNavCapabilities(bot, candidateNav,
                        () -> observedAdmissionSafetyFailure(bot, route, admissionPlan));
                if (refusal == null) {
                    refusal = admissionFailure(type, plan.searchMillis(), failureBudgetMs);
                }
                if (refusal == null && route.shape() == NavRoute.Shape.BLOCK
                        && exhaustedPartial(type, plan.searchMillis(), primaryBudgetMs, goalColumnLoaded(bot, route))) {
                    // An exact-cell request whose search ran out of places to look (before its budget) without reaching the goal: the
                    // cell cannot be reached over the admitted terrain. The route answer is "unreachable", which is what callers (waypoint
                    // relay, digging, the next candidate cell) switch strategy on; Baritone's partial path to the closest point is
                    // only wanted for approach requests (follow), where walking as far as possible is the point.
                    refusal = NavRouteRules.GOAL_UNREACHABLE;
                }
                if (refusal != null) {
                    BotLog.action(bot, "baritone_admission_refused",
                            "reason", refusal,
                            "goal", route.target().toShortString(),
                            "resolved_goal", route.resolvedGoal() == null
                                    ? "-" : route.resolvedGoal().toShortString(),
                            "type", type,
                            "nodes", plan.nodesConsidered(),
                            "moves", plan.movements().size(),
                            "observation_cells", observed.fence().cellCount(),
                            "policy", routePolicy);
                    return CandidateStart.refused(refusal);
                }
                if (plan.reachesGoal() && plan.path() != null) {
                    route.setResolvedGoal(plan.path().getDest());
                }
                return CandidateStart.ready(goal);
            });
            if (candidate.refusal() != null) {
                return Admission.refused(candidate.refusal());
            }
            Goal goal = candidate.goal();
            // An accepted replacement first halts its predecessor while that predecessor's
            // authority is still the only globally published state. The candidate scope above
            // was server-thread-local and has now closed, so neither the old executor nor an
            // old worker can observe its fence, permissions, water rule, or movement caps.
            replacementPreempted = true;
            registry.cancelForReplacement(bot, "route_replaced");
            registry.setObservationFence(bot, observed.fence(), route);
            BotLog.path(bot, "nav_observation_fence_updated", "goal", route.target(), "generation",
                    observed.fence().generation(), "cells", observed.fence().cellCount(), "rays", observed.rays(),
                    "fresh_cells", observed.freshCells(), "provenance", observed.provenance());
            if (route.revalidateRememberedTarget()) {
                BotLog.path(bot, "observed_target_route_started", "target", route.target(), "policy", "walk_only",
                        "memory_age", Math.max(0, bot.getServer().getTickCount() - observed.fence().lastObservationTick()));
            }
            registry.setPolicy(bot, routePolicy);
            registry.setWaterAllowed(bot, options.allowWater());
            applyWaterPolicy(bot.getUUID(), options.allowWater());
            PolicyRefusalStreak.reset(bot.getUUID());
            route.setGoalHandle(goal);
            registry.clearLastPathEvent(bot);
            // If this call throws after changing the process, cancellation is safer than trying
            // to resurrect a predecessor whose goal may already have been replaced.
            goalHandedOff = true;
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            if (admit) {
                // setGoalAndPath only records the process hand-off; its CalculationContext is
                // built on the next driver tick. Commit immediately after that hand-off, so a
                // candidate that was refused by the synchronous search never reaches workers.
                previousNavigationLimits = BaritoneSettings.commitNavCapabilities(bot, candidateNav);
                BaritoneSettings.applyNavLimits(bot);
            }
            if (options.allowWater()) {
                // The lease starts with the route, not with the first driven tick: a bot that is already in the water when a swim route
                // is issued would otherwise get one tick of the safety net's rescue in between. Granted only to an admitted route
                // (a refused one must not leave a rescue suppression behind).
                NavSafetyNet.INSTANCE.renewBaritoneWater(bot);
            }
            accepted = true;
            return Admission.ok();
        } finally {
            if (!accepted) {
                abandonStart(bot, predecessor, previousNavigationLimits, replacementPreempted, goalHandedOff);
            }
        }
    }

    /** A start that was refused or failed: the route's break/place permission, its water rule, its swim permission and its lease are taken back. */
    private static void abandonStart(AIPlayerEntity bot, StartState predecessor,
                                     ServerPlayerContext.NavigationLimits previousNavigationLimits,
                                      boolean replacementPreempted, boolean goalHandedOff) {
        if (replacementPreempted || goalHandedOff) {
            // A candidate has either stopped its predecessor or partly replaced the process
            // goal. It has no safe predecessor to resume, so unconditionally halt it before
            // clearing route authority. `preempt` intentionally does nothing in the one-tick
            // not-yet-busy window; reset does not, which matters immediately after hand-off.
            // Do not resurrect the predecessor's capability snapshot here: its executor is
            // already gone, and reset clears the only authority that could run under it.
            BaritoneRegistry.INSTANCE.reset(bot, "start_abandoned");
            return;
        }
        // A refusal before the process hand-off must not silently tear down a live predecessor.
        // Restore its immutable fence together with policy/water bookkeeping; no candidate
        // authority remains published when the caller receives the refusal.
        try {
            restoreNavigationLimits(bot, previousNavigationLimits);
        } finally {
            BaritoneRegistry.INSTANCE.setPolicy(bot, predecessor.policy());
            BaritoneRegistry.INSTANCE.setWaterAllowed(bot, predecessor.waterAllowed());
            if (predecessor.observedRoute() == null) {
                BaritoneRegistry.INSTANCE.clearObservationFence(bot);
            } else {
                BaritoneRegistry.INSTANCE.setObservationFence(bot, predecessor.fence(), predecessor.observedRoute());
            }
            if (predecessor.registeredRoute()) {
                applyWaterPolicy(bot.getUUID(), predecessor.waterAllowed());
            } else {
                releaseRoute(bot.getUUID());
                NavSafetyNet.INSTANCE.clearBaritoneWater(bot);
            }
        }
    }

    /** Replays the predecessor's globally mirrored values only after its immutable context is back in place. */
    private static void restoreNavigationLimits(AIPlayerEntity bot, ServerPlayerContext.NavigationLimits previousNavigationLimits) {
        if (previousNavigationLimits == null) {
            return;
        }
        BaritoneSettings.restoreNavCapabilities(bot, previousNavigationLimits);
        // Revert the last global replay too. The next driven bot will replay its own route
        // snapshot, but a failed hand-off must not leave this bot's candidate in between.
        BaritoneSettings.applyNavLimits(bot);
    }

    /**
     * The first search of a JVM spends its time loading and compiling classes, which would eat the small admission budget and make
     * the first route of a session fail as "unreachable". One throwaway search towards a nearby column, with a generous budget,
     * pays that cost up front (once, on the server thread, like the class loading it replaces would be).
     */
    private static synchronized void warmUp(IBaritone baritone, AIPlayerEntity bot, MinecraftAiConfig.Nav candidateNav) {
        if (warmedUp) {
            return;
        }
        warmedUp = true;
        long started = System.nanoTime();
        BlockPos feet = bot.blockPosition();
        // Do not probe an arbitrary loaded column for warm-up. The bot's physical current stance
        // is already part of the admitted snapshot and is enough to initialise the planner safely.
        BaritonePlanner.Plan plan = BaritonePlanner.planNow(baritone, new GoalBlock(feet),
                WARM_UP_MS, WARM_UP_MS, candidateNav);
        BotLog.path(bot, "baritone_warm_up", "type", plan.type(), "ms", (System.nanoTime() - started) / 1_000_000L);
    }

    /** Whether Baritone still owns the bot, has arrived at the route's goal, or ended short of it. */
    public static NavRoute.Progress progress(AIPlayerEntity bot, NavRoute route) {
        // A refresh or teleport may have revoked the immutable terrain authority before the
        // process naturally stopped. Preserve that distinction for ActionPack rather than
        // flattening it to an ordinary incomplete Baritone path. The entity-level movement
        // boundary is also revoked through this safe post-physics seam, but it is a concrete
        // containment refusal—not lost terrain authority—so it keeps its typed result.
        String revocationReason = BaritoneRegistry.INSTANCE.observationRevocationReason(bot);
        if (NavRouteRules.MOVEMENT_BOUNDARY_REFUSED.equals(revocationReason)) {
            return NavRoute.Progress.MOVEMENT_BOUNDARY_REFUSED;
        }
        if (revocationReason != null) {
            return NavRoute.Progress.OBSERVATION_LOST;
        }
        if (BaritoneRegistry.INSTANCE.isBusy(bot)) {
            // Position-level rules (observability, the bot's break permission) are only enforced at execution, so a route through
            // blocks the bot may not touch is vetoed at its first break, and Baritone may re-plan the same route for ever.
            return PolicyRefusalStreak.capReached(bot.getUUID()) ? NavRoute.Progress.POLICY_REFUSED : NavRoute.Progress.RUNNING;
        }
        IBaritone baritone = BaritoneRegistry.INSTANCE.find(bot.getUUID());
        if (baritone != null && route.goalHandle() instanceof Goal goal && goal.isInGoal(baritone.getPlayerContext().playerFeet())) {
            if (route.revalidateRememberedTarget()) {
                ObservedNavigationFence fence = BaritoneRegistry.INSTANCE.observationFence(bot);
                boolean revalidated = ObservedNavigationFence.revalidateRememberedTarget(bot, route, fence);
                BotLog.path(bot, "observed_target_revalidated", "target", route.target(), "result", revalidated);
                if (!revalidated) {
                    BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, bot, "observed_target_memory_revoked",
                            "target", route.target(), "reason", "live_reproof_failed");
                    return NavRoute.Progress.OBSERVATION_LOST;
                }
            }
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

    /** Whether a seen hostile turned the bounded strict route into a larger, but still observed, search. */
    static boolean needsObservedHostileAdmissionRetry(NavRoute route, PathCalculationResult.Type type,
                                                      int observedHostiles, long searchMillis) {
        return observedHostiles > 0 && searchMillis + 5L >= ADMISSION_PRIMARY_MS
                && type == PathCalculationResult.Type.SUCCESS_SEGMENT
                && requiresCompleteObservedGoal(route);
    }

    /** Counts only Baritone's perception-filtered hostile list; it never queries the level. */
    private static int observedHostileCount(IBaritone baritone, boolean mobAvoidanceEnabled) {
        if (!mobAvoidanceEnabled) {
            return 0;
        }
        int count = 0;
        for (var entity : baritone.getPlayerContext().entities()) {
            if (entity instanceof Mob mob && mob instanceof Enemy && mob.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private static boolean goalColumnLoaded(AIPlayerEntity bot, NavRoute route) {
        BlockPos target = route.target();
        return bot.level().getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4);
    }

    /**
     * The admission verdict of an inline search in the established route vocabulary: null when the route may start (a complete path, or a
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

    /**
     * Fail-closed admission rule for dry, no-place routes. A full path may include an already
     * observed natural-block break; a partial one is not an authority to walk up to an edge and
     * discover the continuation with physical momentum. Water and construction routes have their
     * own explicitly modelled movement rules, and flee goals deliberately have no fixed endpoint.
     */
    static String observedAdmissionSafetyFailure(AIPlayerEntity bot, NavRoute route, BaritonePlanner.Plan plan) {
        if (plan == null) {
            return requiresCompleteObservedGoal(route) ? "navigation_observed_corridor_unavailable" : null;
        }
        if (plan.type() == PathCalculationResult.Type.EXCEPTION || plan.type() == PathCalculationResult.Type.CANCELLATION) {
            return null;
        }
        String movementFailure = observedPathSafetyFailure(bot, route, plan.path(), 0);
        if (movementFailure != null) {
            return movementFailure;
        }
        return requiresCompleteObservedGoal(route) && plan.type() != PathCalculationResult.Type.SUCCESS_TO_GOAL
                ? "navigation_observed_corridor_unavailable" : null;
    }

    /** Compatibility overload for route-only checks that cannot evaluate a bot-specific fall. */
    static String observedAdmissionSafetyFailure(NavRoute route, BaritonePlanner.Plan plan) {
        return observedAdmissionSafetyFailure(null, route, plan);
    }

    /**
     * Rechecks the path Baritone is about to execute after every PRE tick, so a worker replan
     * cannot bypass the admission's no-partial and maximum-fall boundaries.
     */
    static String activeObservedPathSafetyFailure(AIPlayerEntity bot, IBaritone baritone) {
        IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) {
            return null;
        }
        return observedExecutionPathSafetyFailure(bot, executor.getPath(), executor.getPosition());
    }

    /**
     * The host callback used by the vendored executor before it evaluates a movement. A Baritone
     * path without an admitted route is never allowed to execute on this server; upstream contexts
     * retain their permissive callback default.
     */
    static String observedExecutionPathSafetyFailure(AIPlayerEntity bot, IPath path, int firstMovement) {
        NavRoute route = BaritoneRegistry.INSTANCE.observedRoute(bot);
        return route == null ? "navigation_observation_fence_unavailable"
                : observedPathSafetyFailure(bot, route, path, firstMovement);
    }

    /**
     * Pure path proof: it reads only Baritone's already-built movement list and route permissions.
     *
     * <p>Owner follow is deliberately stricter than ordinary navigation. It can break an observed
     * obstruction or cross snapshot-proved water, but cannot place blocks. A companion may take
     * Baritone's {@link MovementFall} only when its current unprotected health can survive the
     * vanilla landing. This permits a necessary, non-lethal descent while still refusing lava,
     * water-bucket, and lethal fall moves. The check protects both the inline admission path and every later
     * worker replan before the input bridge can press movement keys.</p>
     */
    static String observedPathSafetyFailure(AIPlayerEntity bot, NavRoute route, IPath path, int firstMovement) {
        if (route == null) {
            return null;
        }
        // A knockback can move the body outside a local-site boundary after admission. Revoke the
        // route before the bridge can submit another Baritone input; the owning task decides
        // whether a bounded retry or its immediate-threat fallback is appropriate.
        if (route.movementBoundary() != null && bot != null
                && !route.allowsMovementCell(
                        bot.blockPosition().getX(), bot.blockPosition().getY(), bot.blockPosition().getZ())) {
            return NavRouteRules.MOVEMENT_BOUNDARY_REFUSED;
        }
        if (path != null) {
            int safeFall = BaritoneSettings.maxSafeDryFall(bot);
            boolean ownerFollow = route.shape() == NavRoute.Shape.OWNER_FOLLOW;
            List<IMovement> movements = path.movements();
            for (int index = Math.max(0, firstMovement); index < movements.size(); index++) {
                IMovement movement = movements.get(index);
                if (movement == null) {
                    continue;
                }
                if (movement.getSrc() == null || movement.getDest() == null
                        || !route.allowsMovementCell(
                                movement.getSrc().getX(), movement.getSrc().getY(), movement.getSrc().getZ())
                        || !route.allowsMovementCell(
                                movement.getDest().getX(), movement.getDest().getY(), movement.getDest().getZ())) {
                    return NavRouteRules.MOVEMENT_BOUNDARY_REFUSED;
                }
                // Source/destination alone are not a full body proof. Several Baritone moves
                // deliberately use adjacent feet cells while climbing, descending, or settling
                // an overshoot; its pure declared footprint makes those cells visible before a
                // candidate can take over the goal process.
                List<? extends BlockPos> movementCells = movement.getDeclaredMovementCells();
                if (movementCells == null) {
                    return NavRouteRules.MOVEMENT_BOUNDARY_REFUSED;
                }
                for (BlockPos movementCell : movementCells) {
                    if (movementCell == null || !route.allowsMovementCell(
                            movementCell.getX(), movementCell.getY(), movementCell.getZ())) {
                        return NavRouteRules.MOVEMENT_BOUNDARY_REFUSED;
                    }
                }
                // Keep this deliberately separate from the final per-click gate. These are
                // immutable movement metadata, so admission and worker replans can reject an
                // outside break/place corner synchronously without mutating the executor's
                // observation-failure state. The later gate still proves every actual ray hit.
                List<? extends BlockPos> actionCells = movement.getDeclaredActionCells();
                if (actionCells == null) {
                    return NavRouteRules.MOVEMENT_BOUNDARY_REFUSED;
                }
                for (BlockPos actionCell : actionCells) {
                    if (!route.allowsMovementActionCell(actionCell)) {
                        return NavRouteRules.MOVEMENT_BOUNDARY_REFUSED;
                    }
                }
                int fallBlocks = movement.getSrc().getY() - movement.getDest().getY();
                if (ownerFollow && movement instanceof MovementFall && !FallSafety.isNonLethal(bot, fallBlocks)) {
                    return "navigation_owner_follow_fall_refused";
                }
                if (!route.options().allowWater() && fallBlocks > safeFall) {
                    return "navigation_unsafe_fall";
                }
            }
        }
        if (!requiresCompleteObservedGoal(route)) {
            return null;
        }
        if (path == null || path.getGoal() == null || !path.getGoal().isInGoal(path.getDest())) {
            return "navigation_observed_corridor_unavailable";
        }
        return null;
    }

    private static boolean requiresCompleteObservedGoal(NavRoute route) {
        // A directional pursuit has already resolved its remote heading to one nearby observed
        // GoalBlock. It may execute a bounded partial segment inside that immutable fence, then
        // reports back to FollowTask for a new hop; unlike NEAR/BLOCK it never gains authority
        // to continue toward the unobserved remote coordinate.
        return route != null && route.shape() != NavRoute.Shape.RUN_AWAY
                && route.shape() != NavRoute.Shape.DIRECTIONAL_PURSUIT
                && route.shape() != NavRoute.Shape.OWNER_FOLLOW
                && !route.options().allowWater() && !route.options().allowPlace();
    }

    /** Validates the narrow exception before a route gets a chunk snapshot or a Baritone process. */
    private static boolean isAuthorizedOwnerFollow(AIPlayerEntity bot, NavRoute route) {
        if (route.ownerUuid() == null || route.options().allowPlace()) {
            return false;
        }
        ServerPlayer owner = currentOwnerFollowTarget(bot, route);
        return owner != null && owner.blockPosition().equals(route.target());
    }

    /** The live owner identity/dimension check shared by first admission and active-route refreshes. */
    private static boolean isCurrentOwnerFollow(AIPlayerEntity bot, NavRoute route) {
        return route.ownerUuid() != null && !route.options().allowPlace()
                && currentOwnerFollowTarget(bot, route) != null;
    }

    /** Returns the currently connected owner in this bot's level, or null when that authority has gone away. */
    private static ServerPlayer currentOwnerFollowTarget(AIPlayerEntity bot, NavRoute route) {
        if (AIPlayerManager.INSTANCE.ownerOf(bot).filter(route.ownerUuid()::equals).isEmpty()) {
            return null;
        }
        ServerPlayer owner = bot.getServer().getPlayerList().getPlayer(route.ownerUuid());
        return owner != null && owner.level() == bot.level() ? owner : null;
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
        BaritoneRegistry.INSTANCE.clearObservationFence(botId);
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
            case OWNER_FOLLOW -> new GoalNear(route.target(), route.radius());
            case DIRECTIONAL_PURSUIT -> {
                BlockPos hop = route.resolvedGoal();
                if (hop == null) {
                    // The observation admission must install a local stance before Baritone is
                    // even initialized. Do not fall back to the remote heading as a goal.
                    throw new IllegalStateException("directional pursuit has no observed hop");
                }
                yield new GoalBlock(hop);
            }
            // Baritone's own flee goal: satisfied at radius blocks (horizontally) from the observed source cell; its search
            // picks the way, this mod does not project a flee target by hand.
            case RUN_AWAY -> new GoalRunAway(route.radius(), route.target());
            case BLOCK -> {
                if (route.options().exactWaterGoal()) {
                    // An explicit swim route was admitted against the actual observed water/shore cell.
                    // Snapping that goal to a dry neighbour would make a bot claim arrival
                    // before it crossed the water it was asked to traverse.
                    yield new GoalBlock(route.target());
                }
                if (route.observedPillarGoal()) {
                    // Admission captured a visible, air-only placement column from a proven base.
                    // The usual stance resolution would reject it precisely because Baritone has
                    // not placed that footing yet; all planner cells remain fence-backed.
                    yield new GoalBlock(route.target());
                }
                BlockPos stance = BaritoneRegistry.INSTANCE.observationFence(bot).nearestObservedStance(route.target());
                if (stance == null) {
                    // Admission guarantees this cannot happen; throwing here makes a lifecycle
                    // regression fail closed instead of asking Standability to read live terrain.
                    throw new IllegalStateException("observed navigation goal lost its stance");
                }
                yield new GoalBlock(stance);
            }
        };
    }

    /** Refreshes the active route's view cone before a Baritone PRE tick. Server thread only. */
    public static boolean refreshObservationFence(AIPlayerEntity bot) {
        BaritoneRegistry registry = BaritoneRegistry.INSTANCE;
        NavRoute route = registry.observedRoute(bot);
        if (route == null) {
            return true;
        }
        // A direct coordinate route remains a privilege of the current owner, not merely of
        // the UUID that owned the bot when the route began. The exact cell is intentionally
        // allowed to lag until FollowTask's normal re-goal cadence (the owner may be walking),
        // but an ownership, disconnect, or dimension change must revoke the terrain snapshot
        // before another Baritone PRE tick can use it.
        if (route.shape() == NavRoute.Shape.OWNER_FOLLOW && !isCurrentOwnerFollow(bot, route)) {
            registry.revokeObservation(bot, "owner_follow_authority_lost", false);
            return false;
        }
        ObservedNavigationFence prior = registry.observationFence(bot);
        ObservedNavigationFence.Capture refreshed = ObservedNavigationFence.refresh(
                bot, route, prior, prior.generation() + 1L);
        if (refreshed.accepted()) {
            if (refreshed.fence() != prior) {
                registry.setObservationFence(bot, refreshed.fence(), route);
            }
            return true;
        }
        BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, bot, "route_observation_lost",
                "goal", route.target(), "reason", refreshed.failure(), "rays", refreshed.rays());
        registry.revokeObservation(bot, refreshed.failure(), false);
        return false;
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
