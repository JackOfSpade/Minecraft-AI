package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.baritone.BaritoneNavigator;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.pathfinding.FailureReason;
import io.github.zoyluo.minecraftai.pathfinding.PathExecutor;
import io.github.zoyluo.minecraftai.pathfinding.PathfindingResult;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Collections;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class ActionPack {
    /** Failure reason of a request identical to the previous one still inside its cooldown. */
    public static final String PATHFINDING_THROTTLED = "pathfinding_throttled";
    private static final int PATHFIND_SUCCESS_COOLDOWN_TICKS = 5;
    private static final int PATHFIND_FAILURE_COOLDOWN_TICKS = 20;
    // NAV-OPT two-phase pathfinding budget: pure walking only searches air cells (small search
    // space, so give it a generous allowance); the dig-through cap is smaller, to contain the 3D
    // volume search blowing up when trapped/underground.
    private static final int WALK_MAX_NODES = 10_000;
    private static final int DIG_MAX_NODES = 4_000;
    // Large budget dedicated to the approach primitive: approaching ore enclosed in stone
    // necessarily requires digging, so it goes straight to DIG with an enlarged budget (the
    // digging neighbor branching factor is small, so 24k nodes covers ~40 blocks of direct
    // through-mountain travel; the small-budget DIG in ordinary startPathTo is only a walking
    // fallback, and its semantics are unchanged).
    private static final int DIG_APPROACH_MAX_NODES = 24_000;
    private static final long PATHFIND_MAX_MILLIS = 50L;

    private final AIPlayerEntity player;

    private float forward;
    private float strafing;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    private int jumpTicks;

    private WalkToController walkTo;
    private MiningController mining;
    /** Vanilla client destroyDelay: ticks a finished multi-tick break makes the next one wait (see {@link #tickMining}). */
    static final int DESTROY_DELAY_TICKS = 5;
    /** Game time before which the running mining controller does nothing (the post-break delay); 0 when none. */
    private long nextBreakAt;
    private PathExecutor pathExecutor;
    private PathRequestIdentity lastPathRequest;
    private PathRequestIdentity activePathRequest;
    private BlockPos activePathGoal;
    private int nextPathfindTick;
    private final SnapRepeatGuard physicalSnapGuard = new SnapRepeatGuard();
    // The route Baritone executes for this pack (engine=baritone), and how the previous one ended. Never set with the legacy
    // engine; nothing below touches a Baritone class while it is null.
    private NavRoute route;
    private NavOutcome lastRouteOutcome;

    public ActionPack(AIPlayerEntity player) {
        this.player = player;
    }

    public AIPlayerEntity player() {
        return player;
    }

    /**
     * Single-writer hand-over with Baritone (see {@code BaritoneDriver}): while Baritone drives the bot, this executor is idle; an
     * order that would make it act (start a walk, a path, mining, hold an input) first takes the bot back, which stops
     * Baritone and releases the inputs it wrote. Releasing (stop, zero, false) never takes the bot.
     */
    private void claim(String why) {
        releaseBaritone(why);
    }

    /**
     * Whatever Baritone is doing for this bot stops: the route this pack started (recorded as cancelled) or, for a caller that
     * drives Baritone directly, its goal and path. Nothing Baritone-related is touched while Baritone has never been initialised.
     */
    private void releaseBaritone(String why) {
        if (route != null) {
            cancelBaritoneRoute(why);
        } else {
            NavEngineSelector.hook("baritone_preempt", () -> BaritoneRegistry.INSTANCE.preempt(player, why));
        }
    }

    /**
     * Baritone takes the bot over: everything this executor was doing is dropped without a trace of it left in the inputs, so
     * the two never write at once. Called by {@code BaritoneDriver} on the first tick it drives the bot.
     */
    public void yieldToBaritone() {
        clearActivePathExecutor();
        stopMining();
        this.walkTo = null;
        stopMovement();
    }

    public void setForward(float value) {
        if (value != 0.0F) {
            claim("set_forward");
        }
        this.forward = clampInput(value);
    }

    public void setStrafing(float value) {
        if (value != 0.0F) {
            claim("set_strafing");
        }
        this.strafing = clampInput(value);
    }

    public void setSneaking(boolean sneaking) {
        if (sneaking) {
            claim("set_sneaking");
        }
        this.sneaking = sneaking;
        player.setShiftKeyDown(sneaking);
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }

    public void setSprinting(boolean sprinting) {
        if (sprinting) {
            claim("set_sprinting");
        }
        this.sprinting = sprinting;
        player.setSprinting(sprinting);
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }

    public void setJumping(boolean jumping) {
        if (jumping) {
            claim("set_jumping");
        }
        this.jumping = jumping;
    }

    public void jumpOnce() {
        claim("jump_once");
        this.jumpTicks = 2;
    }

    public ActionResult startWalkTo(Vec3 target) {
        return startWalkTo(target, 0.6D);
    }

    /** Starts a direct walk with a caller-defined horizontal arrival tolerance. */
    public ActionResult startWalkTo(Vec3 target, double arrivalThreshold) {
        claim("walk_to");
        logEngine("walk_to", BlockPos.containing(target), NavEngine.LEGACY, "straight_line_walk");
        clearActivePathExecutor();
        this.walkTo = new WalkToController(target, arrivalThreshold);
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    // Unified entry point for the approach primitive: dig-aware pathfinding (large-budget DIG
    // straight to the goal; the goal may be a solid cell that is "dug open, then stood on" -- see
    // the dig-endpoint exemption in AStarPathfinder.resolveEndpoint). Use this for approaching ore
    // enclosed in stone / going straight through a mountain; ordinary walking still uses
    // startPathTo (WALK first, then small-budget DIG).
    public ActionResult startDigPathTo(BlockPos goal) {
        return startDigPathTo(goal, 0);
    }

    /**
     * Starts a digging approach while preserving a caller-scoped mining-stone reserve.
     * The same reserve gates initial pillar planning, physical pillar execution and replanning.
     */
    public ActionResult startDigPathTo(BlockPos goal, int protectedStoneLikeReserve) {
        claim("dig_path_to");
        logEngine("dig_path_to", goal, NavEngine.LEGACY, "dig_approach");
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int now = player.level().getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        boolean canPillar = PathExecutor.hasPlaceableBlock(player, reserve);
        PathRequestIdentity request = new PathRequestIdentity(
                immutableGoal, canPillar, true, reserve,
                PathExecutor.RouteContract.unrestricted());
        if (preparePathRequest(request, now)) {
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        if (!snapPlayerToNearestStandable("path_start_invalid")) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        PathfindingResult result = new AStarPathfinder(player, player.level(), player.blockPosition(), goal,
                DIG_APPROACH_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true, 10.0D).findPath();
        if (!result.success()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathRequest = request;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        activePathRequest = request;
        this.pathExecutor = new PathExecutor(
                result.path(), resolvedGoal, canPillar, true, reserve);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startPathTo(BlockPos goal) {
        return startPathTo(goal, 0);
    }

    /**
     * Starts an ordinary route while preserving a caller-scoped mining-stone reserve.
     * Non-reserve path supports remain eligible; protected stone is exposed only above the
     * requested floor.
     */
    public ActionResult startPathTo(BlockPos goal, int protectedStoneLikeReserve) {
        int reserve = Math.max(0, protectedStoneLikeReserve);
        return startPathTo(
                goal, PathExecutor.hasPlaceableBlock(player, reserve), true, reserve);
    }

    /**
     * Starts a surface-exploration path without digging or disposable pillar shortcuts.
     * Hunt/Gather roaming must be able to keep moving after it reaches a waypoint; a path that
     * spends the last few dirt blocks pillaring out of a depression is not a reusable surface route.
     */
    public ActionResult startSurfacePathTo(BlockPos goal) {
        return startPathTo(goal, false, false, 0);
    }

    /**
     * Starts a no-dig/no-pillar surface route that must remain at or above {@code minimumY}.
     * This overload does not require a round-trip proof.
     */
    public ActionResult startSurfacePathTo(BlockPos goal, int minimumY) {
        return startSurfacePathTo(goal, minimumY, null);
    }

    /**
     * Starts a contract-bound surface route. A non-null {@code returnAnchor} additionally requires
     * an exact no-dig/no-pillar route from the requested goal back to that anchor under the same
     * Y floor and search budget.
     */
    public ActionResult startSurfacePathTo(
            BlockPos goal, int minimumY, BlockPos returnAnchor) {
        return startPathTo(goal, false, false, 0,
                PathExecutor.RouteContract.constrainedSurface(minimumY, returnAnchor));
    }

    /**
     * Contract-bound surface route whose outbound leg may dig through obstacles once the
     * walk-only phase has no solution (a hunter digs a stair through a hill instead of
     * rejecting the whole herd). The dig fallback stays above the same Y floor, and the
     * return proof from the goal remains strictly walk-only: a dug stair is itself
     * walk-only returnable, while a goal whose return needs fresh digging is still rejected.
     */
    public ActionResult startSurfaceDigFallbackPathTo(
            BlockPos goal, int minimumY, BlockPos returnAnchor) {
        return startPathTo(goal, false, true, 0,
                PathExecutor.RouteContract.constrainedSurface(minimumY, returnAnchor));
    }

    private ActionResult startPathTo(BlockPos goal, boolean canPillar,
                                     boolean allowDigFallback,
                                     int protectedStoneLikeReserve) {
        return startPathTo(goal, canPillar, allowDigFallback, protectedStoneLikeReserve,
                PathExecutor.RouteContract.unrestricted());
    }

    private ActionResult startPathTo(BlockPos goal, boolean canPillar,
                                     boolean allowDigFallback,
                                     int protectedStoneLikeReserve,
                                     PathExecutor.RouteContract routeContract) {
        // Engine seam: with nav.engine=baritone an ordinary walk (not a contract route) is Baritone's. A null answer means
        // "not routed" (legacy engine, contract route, or Baritone failed to initialise) and the legacy code below carries on.
        ActionResult routed = routeOnBaritone("path_to", goal, canPillar, allowDigFallback, protectedStoneLikeReserve, routeContract);
        if (routed != null) {
            return routed;
        }
        claim("path_to");
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int now = player.level().getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        PathRequestIdentity request = new PathRequestIdentity(
                immutableGoal, canPillar, allowDigFallback, reserve, routeContract);
        if (preparePathRequest(request, now)) {
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        if (routeContract.constrained()
                && player.blockPosition().getY() < routeContract.minimumY()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("path_contract_failed: start_below_minimum_y");
        }
        boolean startReady = routeContract.constrained()
                ? recenterPlayerInCurrentStandableCell("path_start_invalid")
                : snapPlayerToNearestStandable("path_start_invalid");
        if (!startReady) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        ServerLevel world = player.level();
        BlockPos from = player.blockPosition();
        // NAV-OPT two-phase pathfinding: try pure walking first (no digging allowed, search
        // space = air cells, so it converges fast and won't be blown out to SEARCH_LIMIT by
        // dig-through neighbors); only if pure walking has no solution do we allow the dig-through
        // fallback (tunneling/breaking obstacles), with a smaller dig budget to bound the 3D
        // volume search blowing up when trapped/underground.
        AStarPathfinder walkFinder =
                new AStarPathfinder(
                        player, world, from, goal, WALK_MAX_NODES, PATHFIND_MAX_MILLIS,
                        canPillar, false);
        PathfindingResult result = routeContract.constrained()
                ? walkFinder.findPathUncachedAtOrAbove(routeContract.minimumY())
                : walkFinder.findPath();
        boolean dugOutbound = false;
        if (!result.success() && allowDigFallback) {
            AStarPathfinder digFinder = new AStarPathfinder(
                    player, world, from, goal, DIG_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true);
            PathfindingResult dig = routeContract.constrained()
                    ? digFinder.findPathUncachedAtOrAbove(routeContract.minimumY())
                    : digFinder.findPath();
            if (dig.success()) {
                result = dig;
                dugOutbound = true;
            }
        }
        if (!result.success()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        PathfindingResult returnProof = null;
        if (routeContract.requiresReturnProof()) {
            if (dugOutbound && PathExecutor.isReversibleStair(result)) {
                // The pre-dig world cannot prove the walk-only return yet; the dug stair is
                // itself that return, so derive the proof from the reversed outbound nodes.
                java.util.List<io.github.zoyluo.minecraftai.pathfinding.Node> reversed =
                        new java.util.ArrayList<>(result.path());
                java.util.Collections.reverse(reversed);
                returnProof = new PathfindingResult(
                        reversed, true, FailureReason.NONE,
                        result.nodesExplored(), result.elapsedMs(),
                        result.resolvedGoal(), result.resolvedStart());
            } else {
                returnProof = new AStarPathfinder(
                        player, world, immutableGoal, routeContract.returnAnchor(),
                        WALK_MAX_NODES, PATHFIND_MAX_MILLIS, false, false)
                        .findPathUncachedAtOrAbove(routeContract.minimumY());
            }
        }
        PathExecutor.RouteValidation validation = PathExecutor.validateRouteContract(
                result, immutableGoal, routeContract, returnProof);
        if (!validation.accepted()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("path_contract_failed: " + validation.reason());
        }
        lastPathRequest = request;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        activePathRequest = request;
        this.pathExecutor = routeContract.constrained()
                ? new PathExecutor(
                result.path(), resolvedGoal, canPillar, allowDigFallback, reserve, routeContract)
                : new PathExecutor(
                result.path(), resolvedGoal, canPillar, allowDigFallback, reserve);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public BlockPos activePathGoal() {
        if (route != null) {
            settleRoute();
            if (route != null) {
                return route.resolvedGoal() != null ? route.resolvedGoal() : route.target();
            }
        }
        return activePathGoal;
    }

    // ==================== Navigator seam (nav.engine = baritone) ====================

    /** Failure reason of {@link #startApproachTo} when the Baritone engine is not the one answering requests. */
    public static final String ENGINE_NOT_BARITONE = "engine_not_baritone";

    /**
     * Routes an ordinary walk to Baritone. Returns null when the request is not Baritone's (legacy engine, a contract-bound
     * route, Baritone unavailable) and the caller runs the legacy navigator; otherwise the same answer the legacy code would give:
     * {@code IN_PROGRESS}, {@code failed("pathfinding_failed: GOAL_UNREACHABLE")} or {@code failed(PATHFINDING_THROTTLED)}.
     *
     * <p>Routing table (docs/NAVIGATION_BARITONE_PLAN.md): ordinary walks and surface-only walks are Baritone's (surface-only:
     * no breaking, no placing); contract routes, dig approaches, straight-line walks and one-cell safety moves stay legacy.</p>
     */
    private ActionResult routeOnBaritone(String kind, BlockPos goal, boolean canPillar, boolean allowDigFallback,
                                         int protectedStoneLikeReserve, PathExecutor.RouteContract routeContract) {
        if (routeContract.constrained()) {
            logEngine(kind, goal, NavEngine.LEGACY, "contract_route");
            return null;
        }
        if (!NavEngineSelector.baritoneSelectedFor(player.getUUID())) {
            logEngine(kind, goal, NavEngine.LEGACY, NavEngineSelector.baritoneFailed() ? "baritone_unavailable" : "engine_legacy");
            return null;
        }
        int reserve = Math.max(0, protectedStoneLikeReserve);
        // A caller that keeps a stone reserve must not have it spent on pillars/bridges by a planner that cannot see the reserve.
        NavRoute.Options options = NavRouteRules.optionsFor(allowDigFallback, canPillar, reserve, player.isInWater());
        NavRoute request = new NavRoute(NavRoute.Shape.BLOCK, goal, 0, options, kind, serverTick());
        PathRequestIdentity identity = new PathRequestIdentity(goal, canPillar, allowDigFallback, reserve, routeContract);
        return NavEngineSelector.attempt(player.getUUID(), kind, () -> startBaritoneRoute(request, identity, true), () -> null);
    }

    /**
     * Baritone-only: walk until within {@code radius} blocks of {@code target} (a {@code GoalNear}: no stand-off cell, no goal
     * snapping); what follow and approach use. Not throttled (the caller has its own schedule).
     *
     * @param refresh    re-target the route that is already running without another admission search on the server thread
     * @param allowBreak whether breaking through an obstacle is allowed as a last resort when there is no way around
     * @return {@link #ENGINE_NOT_BARITONE} failure when the engine is not (or no longer) Baritone: the caller uses its legacy walk
     */
    public ActionResult startApproachTo(BlockPos target, int radius, boolean refresh, boolean allowBreak) {
        if (!NavEngineSelector.baritoneSelectedFor(player.getUUID())) {
            return ActionResult.failed(ENGINE_NOT_BARITONE);
        }
        NavRoute.Options options = new NavRoute.Options(allowBreak, false, player.isInWater());
        NavRoute request = new NavRoute(NavRoute.Shape.NEAR, target, radius, options, "approach", serverTick());
        boolean admit = !(refresh && route != null);
        return NavEngineSelector.attempt(player.getUUID(), "approach", () -> startBaritoneRoute(request, null, admit),
                () -> ActionResult.failed(ENGINE_NOT_BARITONE));
    }

    /**
     * Baritone-only: a walk that may cross water (the route is leased against the drowning safety net for as long as Baritone
     * drives it). No breaking, no placing.
     */
    public ActionResult startSwimRouteTo(BlockPos goal) {
        if (!NavEngineSelector.baritoneSelectedFor(player.getUUID())) {
            return ActionResult.failed(ENGINE_NOT_BARITONE);
        }
        NavRoute request = new NavRoute(NavRoute.Shape.BLOCK, goal, 0, NavRoute.Options.SWIM, "swim_route", serverTick());
        return NavEngineSelector.attempt(player.getUUID(), "swim_route", () -> startBaritoneRoute(request, null, true),
                () -> ActionResult.failed(ENGINE_NOT_BARITONE));
    }

    private ActionResult startBaritoneRoute(NavRoute request, PathRequestIdentity identity, boolean admit) {
        int now = serverTick();
        if (identity != null && identity.equals(lastPathRequest) && now < nextPathfindTick) {
            logEngine(request.label(), request.target(), NavEngine.BARITONE, "throttled");
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        // Single writer: whatever the legacy executor was doing is dropped before Baritone is asked to move the bot.
        if (pathExecutor != null || walkTo != null || mining != null || forward != 0.0F || strafing != 0.0F
                || sneaking || sprinting || jumping || jumpTicks > 0) {
            yieldToBaritone();
        }
        // A route that already ended is recorded as it ended, not as "replaced".
        settleRoute();
        NavRoute previous = route;
        BaritoneNavigator.Admission admission;
        try {
            admission = BaritoneNavigator.start(player, request, admit);
        } catch (Throwable failure) {
            if (previous != null) {
                cancelBaritoneRoute("start_failed");
            }
            throw failure;
        }
        if (!admission.accepted()) {
            if (previous != null) {
                cancelBaritoneRoute("rejected_request");
            }
            if (identity != null) {
                lastPathRequest = identity;
                nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            }
            logEngine(request.label(), request.target(), NavEngine.BARITONE, "rejected: " + admission.failure());
            return ActionResult.failed(admission.failure());
        }
        double dx = request.target().getX() + 0.5D - player.getX();
        double dz = request.target().getZ() + 0.5D - player.getZ();
        request.setDeadlineTick(now + NavRouteRules.deadlineTicks(Math.sqrt(dx * dx + dz * dz)));
        if (previous != null && admit) {
            // A newer request took the bot over: the route it replaced is over and says so. (A deliberate re-goal refresh of the
            // same follow route, admit=false, is the same route and is not an ending.) The water bookkeeping belongs to the new one.
            finishRoute(NavOutcome.Status.CANCELLED, NavRouteRules.REPLACED, false);
        }
        route = request;
        if (identity != null) {
            lastPathRequest = identity;
            nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        }
        logEngine(request.label(), request.target(), NavEngine.BARITONE,
                previous == null ? "started" : "regoal" + (admit ? "" : "_no_admission"));
        return ActionResult.IN_PROGRESS;
    }

    /** How the last Baritone route of this pack ended, or null if none has yet. */
    public NavOutcome lastRouteOutcome() {
        return lastRouteOutcome;
    }

    /** Whether a Baritone route of this pack is running (settles it first: a route that ended is not running). */
    public boolean hasBaritoneRoute() {
        settleRoute();
        return route != null;
    }

    /**
     * Ends this pack's Baritone route on the caller's order: Baritone lets go of the bot (goal, path, search, inputs, the block
     * being broken) and the route is recorded as cancelled, unless it had already ended by itself.
     *
     * @return true if a route was cancelled
     */
    public boolean cancelBaritoneRoute(String why) {
        settleRoute();
        if (route == null) {
            return false;
        }
        NavEngineSelector.hook("baritone_cancel", () -> BaritoneNavigator.cancel(player, why));
        finishRoute(NavOutcome.Status.CANCELLED, "cancelled: " + why, true);
        return true;
    }

    /**
     * The per-tick check of a Baritone route: arrived, ended short (failed), timed out, or a dry route that got wet. Called by
     * {@link #onUpdate} for a bot that is not driven and by {@code BaritoneDriver} at the end of a driven tick.
     */
    public void onBaritoneTick() {
        settleRoute();
    }

    private void settleRoute() {
        NavRoute current = route;
        if (current == null) {
            return;
        }
        if (!NavEngineSelector.baritoneActive()) {
            // Baritone was given up on while the route ran: there is nothing left to ask, and the bot is the legacy navigator's.
            finishRoute(NavOutcome.Status.FAILED, NavRouteRules.BARITONE_UNAVAILABLE, false);
            return;
        }
        NavRoute.Progress progress;
        boolean searchFailed = false;
        try {
            progress = BaritoneNavigator.progress(player, current);
            if (progress == NavRoute.Progress.ENDED_SHORT) {
                searchFailed = BaritoneNavigator.searchFailed(player);
            }
        } catch (Throwable failure) {
            // Reached from ~40 callers every tick, outside NavEngineSelector.attempt: whatever Baritone throws here ends the route
            // (a linkage-type failure also retires Baritone) and the callers just see an idle pack.
            boolean retired = NavEngineSelector.handleFailure("baritone_progress", failure);
            if (!retired) {
                NavEngineSelector.hook("baritone_cancel", () -> BaritoneNavigator.cancel(player, "progress_failed"));
            }
            finishRoute(NavOutcome.Status.FAILED, retired ? NavRouteRules.BARITONE_UNAVAILABLE : NavRouteRules.BARITONE_ERROR, !retired);
            return;
        }
        boolean dryRouteWet = !current.options().allowWater() && player.isInWater();
        boolean pastDeadline = serverTick() > current.deadlineTick();
        NavRouteRules.Verdict verdict = NavRouteRules.verdict(progress, dryRouteWet, pastDeadline, searchFailed);
        if (!verdict.ended()) {
            return;
        }
        if (progress == NavRoute.Progress.RUNNING || progress == NavRoute.Progress.POLICY_REFUSED) {
            // A dry route that got wet (a follower waits on its bank; the drowning safety net owns the bot from here), one that ran
            // out of time, or one the strict-survival rules keep vetoing: Baritone lets go of the bot before the route is recorded.
            NavEngineSelector.hook("baritone_cancel", () -> BaritoneNavigator.cancel(player, verdict.reason()));
        }
        finishRoute(verdict.status(), verdict.reason(), true);
    }

    /** @param releaseWater whether the route's water bookkeeping ends with it (false when a replacement has just registered its own) */
    private void finishRoute(NavOutcome.Status status, String reason, boolean releaseWater) {
        NavRoute finished = route;
        if (finished == null) {
            return;
        }
        route = null;
        BlockPos goal = finished.resolvedGoal() != null ? finished.resolvedGoal() : finished.target();
        NavOutcome outcome = new NavOutcome(status, reason, finished.label(), goal, serverTick() - finished.startTick());
        lastRouteOutcome = outcome;
        if (releaseWater) {
            NavEngineSelector.hook("baritone_release_route", () -> BaritoneNavigator.releaseRoute(player.getUUID()));
        }
        switch (status) {
            case SUCCESS -> BotLog.path(player, outcome.event(), "engine", "baritone", "ticks", outcome.ticks());
            case FAILED -> BotLog.warn(LogCategory.ERROR, player, outcome.event(),
                    "engine", "baritone", "reason", reason, "goal", LogFields.pos(goal), "ticks", outcome.ticks());
            default -> BotLog.path(player, outcome.event(), "engine", "baritone", "reason", reason,
                    "goal", LogFields.pos(goal), "ticks", outcome.ticks());
        }
    }

    private void logEngine(String kind, BlockPos goal, NavEngine engine, String why) {
        BotLog.path(player, "nav_engine", "engine", engine.configValue(), "configured", NavEngineSelector.configuredFor(player.getUUID()).configValue(),
                "kind", kind, "goal", LogFields.pos(goal), "why", why);
    }

    private int serverTick() {
        return player.level().getServer().getTickCount();
    }

    /**
     * Repairs only fractional body overlap inside the current supported cell.
     * Constrained routes must never relocate to another block before their full contract is proven.
     */
    public boolean recenterPlayerInCurrentStandableCell(String reason) {
        ServerLevel world = player.level();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        if (!Standability.isStandable(world, current)) {
            return false;
        }
        if (FakePlayerMotion.isBlockCollisionFree(player)) {
            return true;
        }
        if (!FakePlayerMotion.returnToBlockCenter(
                player, current, "path_start_body_collision:" + reason)) {
            return false;
        }
        Standability.clearCache();
        return player.blockPosition().equals(current)
                && Standability.isStandable(world, current)
                && FakePlayerMotion.isBlockCollisionFree(player);
    }

    public boolean snapPlayerToNearestStandable(String reason) {
        ServerLevel world = player.level();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        boolean currentCellStandable = Standability.isStandable(world, current);
        if (currentCellStandable && FakePlayerMotion.isBlockCollisionFree(player)) {
            return true;
        }
        // A floored air column can be standable while an off-centre 0.6-wide player body overlaps
        // raised terrain in a neighbouring column. Dedicated-server console commands spawn at the
        // lower corner of the world-spawn BlockPos, which reproduced this exact condition on seed
        // 3000. Re-centre only the collided pose; collision-free fractional positions are valid
        // physical state and must be preserved for edge/pickup transactions.
        if (currentCellStandable
                && FakePlayerMotion.returnToBlockCenter(
                        player, current, "path_start_body_collision:" + reason)) {
            Standability.clearCache();
            if (FakePlayerMotion.isBlockCollisionFree(player)) {
                return true;
            }
        }
        // A fake player can end a jump/drop fractionally inside the neighbouring cell even though
        // an adjacent legal landing exists. Recover that one-cell movement physically before asking
        // for the privileged long-distance snap. In strict_survival this is the difference between
        // continuing a hunt and every subsequent path request failing NO_START in one tick.
        if (physicalSnapSuppressed(current, reason)) {
            // A suppressed snap is a refusal to re-snap out of a cell the bot was just walked back into --
            // it must NOT fall through to the privileged relocation below, which would turn the
            // yo-yo guard into a teleport. The caller's search simply fails this time.
            return false;
        }
        if (tryPhysicalSnap(world, current, reason)) {
            return true;
        }
        // A valid current start is ordinary pathfinding and must not require an emergency
        // capability. Only the fallback relocation to a different cell is privileged.
        if (!io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(
                player, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "action_pack_snap:" + reason).allowed()) {
            return false;
        }
        Optional<BlockPos> snapped = Standability.findNearestStandable(world, current, 8, 128, 32);
        if (snapped.isEmpty()) {
            BotLog.warn(LogCategory.PATH, player, "path_start_snap_failed", "reason", reason, "from", io.github.zoyluo.minecraftai.log.LogFields.pos(current));
            return false;
        }
        BlockPos safe = snapped.get();
        stopMovement();
        player.teleportTo(world,
                safe.getX() + 0.5D,
                safe.getY(),
                safe.getZ() + 0.5D,
                Collections.emptySet(),
                player.getYRot(),
                player.getXRot(),
                true);
        // findNearestStandable only just verified a solid landing at `safe`; this can relocate the
        // player up to 128 blocks vertically (e.g. away from a genuine, in-progress vanilla fall),
        // so any real fallDistance/velocity carried into the jump must be cleared here too, or a
        // later unrelated on-ground transition applies stale fall damage for a fall that this exact
        // teleport already resolved.
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setOnGround(true);
        Standability.clearCache();
        BotLog.path(player, "path_start_snapped",
                "reason", reason,
                "from", io.github.zoyluo.minecraftai.log.LogFields.pos(current),
                "to", io.github.zoyluo.minecraftai.log.LogFields.pos(safe));
        return true;
    }

    /**
     * True when a second physical snap out of the same cell inside the guard window is being refused:
     * whatever walked the bot back in would just be undone again (see SnapRepeatGuard). One re-snap
     * per stall.
     */
    private boolean physicalSnapSuppressed(BlockPos current, String reason) {
        int nowTick = player.level().getServer().getTickCount();
        if (physicalSnapGuard.allows(current, nowTick)) {
            return false;
        }
        BotLog.path(player, "path_start_physical_snap_suppressed",
                "reason", reason, "from", LogFields.pos(current));
        return true;
    }

    private boolean tryPhysicalSnap(ServerLevel world, BlockPos current, String reason) {
        int nowTick = player.level().getServer().getTickCount();
        // Same-level steps first, then a one-block drop, finally a vanilla-style jump. A vertical
        // move may include one horizontal axis; three-axis corner jumps are never legitimate.
        int[][] horizontalOffsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {0, 0}
        };
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] offset : horizontalOffsets) {
                int dx = offset[0];
                int dz = offset[1];
                if ((dx == 0 && dz == 0 && dy == 0)
                        || (dy != 0 && Math.abs(dx) + Math.abs(dz) > 1)) {
                    continue;
                }
                BlockPos candidate = current.offset(dx, dy, dz);
                if (!Standability.isStandable(world, candidate)) {
                    continue;
                }
                boolean moved = dy > 0
                        ? FakePlayerMotion.jumpTo(player, candidate, "path_start_physical_snap:" + reason)
                        : FakePlayerMotion.stepTo(player, candidate, "path_start_physical_snap:" + reason);
                if (!moved) {
                    continue;
                }
                Standability.clearCache();
                physicalSnapGuard.record(current, nowTick);
                BotLog.path(player, "path_start_physical_snap",
                        "reason", reason,
                        "from", io.github.zoyluo.minecraftai.log.LogFields.pos(current),
                        "to", io.github.zoyluo.minecraftai.log.LogFields.pos(candidate));
                return true;
            }
        }
        return false;
    }

    /**
     * Actively sinks the bot down one cell into the given (already-air) block.
     * Key point: the bot is a ServerPlayer, and the server side **does not run travel()**
     * (a real player's movement/gravity is driven by the client, and a fake player has no
     * client), so there is **no passive gravity** -- digging out the floor beneath it will not
     * make it fall automatically. Shaft-digging-down tasks (DigDownTask /
     * OreDigTask.digDownOneLayer) must actively drive the sink through this method, or the bot
     * will stand there idling until the watchdog fails (observed in practice: dig_down with y
     * constant the whole time, stuck at 200t no_progress -- this is the shared root cause).
     * Idempotent: if the bot is already at or below that layer, it does not move. teleport clears
     * fallDistance, so no fall damage is taken.
     */
    public boolean descendInto(BlockPos target) {
        if (player.blockPosition().getY() <= target.getY()) {
            return player.blockPosition().equals(target);
        }
        return io.github.zoyluo.minecraftai.mode.FakePlayerMotion.stepToStandable(
                player, target, "descend_into");
    }

    public ActionResult startMining(BlockPos pos, Direction face) {
        claim("mining");
        this.mining = new MiningController(pos, face);
        clearActivePathExecutor();
        this.forward = 0.0F;
        this.strafing = 0.0F;
        return ActionResult.IN_PROGRESS;
    }

    public void stopMining() {
        if (this.mining != null) {
            this.mining.abort(player);
            this.mining = null;
        }
    }

    public void stopMovement() {
        setSneaking(false);
        setSprinting(false);
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        player.setJumping(false);
    }

    /**
     * Cancels the active path executor and direct walk and releases the movement keys.  Unlike
     * {@link #stopMovement()} (keys only -- a live executor re-presses forward on its very next
     * tick and keeps walking a stale route), this really stops navigation, but leaves mining and
     * item use alone.
     */
    public void stopNavigation() {
        if (route != null) {
            cancelBaritoneRoute("stop_navigation");
        }
        clearActivePathExecutor();
        this.walkTo = null;
        stopMovement();
    }

    public void stopAll() {
        releaseBaritone("stop_all");
        clearActivePathExecutor();
        stopMining();
        this.walkTo = null;
        stopMovement();
        player.releaseUsingItem();
    }

    public boolean hasActiveActions() {
        return NavEngineSelector.query("baritone_busy", () -> BaritoneRegistry.INSTANCE.isBusy(player), false)
                || pathExecutor != null
                || walkTo != null
                || mining != null
                || forward != 0.0F
                || strafing != 0.0F
                || sneaking
                || sprinting
                || jumping
                || jumpTicks > 0
                || player.isUsingItem();
    }

    public boolean isPathExecutorIdle() {
        settleRoute();
        return pathExecutor == null && route == null;
    }

    public boolean isWalkToIdle() {
        return walkTo == null;
    }

    public boolean isMiningIdle() {
        return mining == null;
    }

    public void onUpdate() {
        settleRoute();
        tickPathExecutor();
        tickWalkTo();
        tickMining();

        float velocity = sneaking ? 0.3F : 1.0F;
        player.zza = forward * velocity;
        player.xxa = strafing * velocity;
        boolean jumpNow = jumping || jumpTicks > 0;
        player.setJumping(jumpNow);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    private void tickWalkTo() {
        if (walkTo == null) {
            return;
        }

        ActionResult result = walkTo.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "walk_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "walk_failed", "reason", result.reason());
        }
        walkTo = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickPathExecutor() {
        if (pathExecutor == null) {
            return;
        }

        ActionResult result = pathExecutor.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
        }
        pathExecutor = null;
        activePathGoal = null;
        activePathRequest = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    /**
     * Ticks one break controller under vanilla's destroyDelay: after a break that took more than one tick a client waits five ticks
     * (continueDestroyBlock returns early while the counter runs) before it starts on the next block. A bot that chained breaks back
     * to back would dig faster than any player, so the next controller does nothing (IN_PROGRESS) until that delay is over. An
     * instant break (hardness 0, or a tool that mines the block in the first tick) sets no delay, as in vanilla. The ONE place
     * the delay lives: {@link #tickMining} and the route executor's dig-through sub-miners both tick through here.
     */
    public ActionResult tickBreak(MiningController controller) {
        if (player.level().getGameTime() < nextBreakAt) {
            return ActionResult.IN_PROGRESS;
        }
        ActionResult result = controller.tick(this);
        if (result.isSuccess() && controller.elapsedTicks() > 1) {
            nextBreakAt = player.level().getGameTime() + DESTROY_DELAY_TICKS + 1L;
        }
        return result;
    }

    private void tickMining() {
        if (mining == null) {
            return;
        }
        ActionResult result = tickBreak(mining);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            // Auditable break record (see docs/LOGGING.md "Auditing a gather"): block is captured
            // BEFORE the break by MiningController, so this reports what was actually destroyed
            // even though the world cell is air by now.
            BlockState brokenState = mining.brokenBlockState();
            ItemStack tool = player.getMainHandItem();
            BotLog.action(player, "mine_complete",
                    "block", brokenState == null ? "unknown" : BuiltInRegistries.BLOCK.getKey(brokenState.getBlock()).toString(),
                    "pos", LogFields.pos(mining.pos()),
                    "tool", tool.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(tool.getItem()).toString(),
                    "ticks", mining.elapsedTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "mine_failed", "reason", result.reason());
        }
        mining = null;
    }

    /**
     * Returns true when an identical request is still inside its cooldown.
     * A different active identity is stopped before cooldown evaluation so an old, weaker route
     * can never keep moving merely because the replacement happens to share the same goal.
     */
    private boolean preparePathRequest(PathRequestIdentity request, int now) {
        if (pathExecutor != null && !request.equals(activePathRequest)) {
            clearActivePathExecutor();
        }
        if (request.equals(lastPathRequest) && now < nextPathfindTick) {
            return true;
        }
        // An explicit restart after cooldown owns the controller from this point onward.
        clearActivePathExecutor();
        return false;
    }

    private void clearActivePathExecutor() {
        if (pathExecutor != null) {
            pathExecutor.abort(this);
            pathExecutor = null;
        }
        activePathGoal = null;
        activePathRequest = null;
    }

    private record PathRequestIdentity(
            BlockPos goal,
            boolean canPillar,
            boolean allowDig,
            int protectedStoneLikeReserve,
            PathExecutor.RouteContract routeContract) {
        private PathRequestIdentity {
            goal = goal.immutable();
            protectedStoneLikeReserve = Math.max(0, protectedStoneLikeReserve);
            routeContract = java.util.Objects.requireNonNull(routeContract, "routeContract");
        }
    }

    private static float clampInput(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
}
