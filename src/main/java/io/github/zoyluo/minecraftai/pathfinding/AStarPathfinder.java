package io.github.zoyluo.minecraftai.pathfinding;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavigationMeasurement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class AStarPathfinder {
    private static final int DEFAULT_MAX_NODES = 10_000;
    /** Production wall-clock budget for an ordinary synchronous route search. */
    static final long DEFAULT_MAX_MILLIS = 50L;
    private static final int MAX_CACHE_ENTRIES = 256;
    // Goal-snap window/fallback bounds -- see resolveEndpoint's goal branch and
    // Standability.findNearestStandableForGoal's header.
    private static final int GOAL_NEAR_HORIZONTAL_RADIUS = 8;
    private static final int GOAL_NEAR_VERTICAL_DOWN = 4;
    private static final int GOAL_NEAR_VERTICAL_UP = 3;
    private static final int GOAL_DEEP_VERTICAL_DOWN = 24;
    private static final long SUCCESS_CACHE_MILLIS = 2_000L;
    private static final long FAILURE_CACHE_MILLIS = 5_000L;
    // Cross-bot cache-reuse note (bot identity is deliberately NOT part of CacheKey below):
    // NeighborEnumerator's DIG_THROUGH preflight now gates its lava/water peek on the acting
    // bot's own real-time observation (see the constructor taking AIPlayerEntity). That makes a
    // cached PathfindingResult, in principle, a function of which bot planned it -- two bots at
    // the exact same start/goal could, in theory, get different digEnterable() answers if one of
    // them can currently see a hazard the other cannot. Adding bot identity to the key was
    // considered and rejected: this cache's TTL is a couple of seconds (SUCCESS_CACHE_MILLIS/
    // FAILURE_CACHE_MILLIS below) and its key already pins the exact start AND goal BlockPos, so
    // two different bots colliding on a cache hit at all is already a rare coincidence. More
    // importantly, plan-time digEnterable() is only ever a proactive optimization now, never the
    // safety boundary: PathExecutor.tickDigThrough() is the sole executor of MoveType.DIG_THROUGH
    // and reactively re-observes every newly-exposed neighbour with the REAL executing bot the
    // instant mining opens it, regardless of which bot's observation shaped the cached plan. So a
    // stale cross-bot hit can at worst make a plan slightly less proactively hazard-aware (it
    // still gets caught reactively, never silently walked into) or slightly more conservative
    // (a route another bot could rule out that this bot would have allowed) -- never unsafe.
    private static final Map<CacheKey, CachedResult> RESULT_CACHE = new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedResult> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private final ServerLevel world;
    /** The acting bot when this is a live route/replan; null for world-only proofs. */
    private final AIPlayerEntity bot;
    private final BlockPos start;
    private final BlockPos goal;
    private final NeighborEnumerator enumerator;
    private final boolean canPillar;
    private final boolean allowDig;
    // Weighted A* (ε-admissible): in mining-approach scenarios the heuristic (Euclidean x1) is
    // far below the true digging cost (x8), so the search degenerates into omnidirectional
    // flood-fill and reliably TIMEOUTs at 50ms (confirmed empirically on the geo matrix). ε=3
    // trades optimality for convergence speed -- mining doesn't need the shortest path.
    private final double heuristicWeight;
    private final int maxNodes;
    private final long maxMillis;
    private static volatile long cacheVersion;

    /**
     * Harness switch (GameTest and verify lanes): every search keeps its node budget but its wall-clock budget is this many times longer,
     * so a cold JIT, a garbage collection or another process on a shared machine cannot turn a reachable goal into a TIMEOUT. A
     * GameTest is counted in ticks, never in milliseconds. Production never sets this.
     */
    private static volatile long harnessTimeScale = 1L;

    public static void setHarnessTimeScale(long scale) {
        harnessTimeScale = Math.max(1L, scale);
    }

    /**
     * Read-only diagnostic seam for the opt-in navigation evidence fixture. This exposes the
     * effective allowance multiplier without providing another way to change gameplay timing.
     */
    public static long harnessTimeScaleForDiagnostics() {
        return harnessTimeScale;
    }

    public AStarPathfinder(ServerLevel world, BlockPos start, BlockPos goal) {
        this(null, world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, false);
    }

    public AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal) {
        this(bot, world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, false);
    }

    public AStarPathfinder(ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis) {
        this(null, world, start, goal, maxNodes, maxMillis, false);
    }

    public AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis) {
        this(bot, world, start, goal, maxNodes, maxMillis, false);
    }

    // NAV-9: canPillar=true allows pillaring (placing a block underfoot) to cross obstacles (passed in by callers that have blocks available).
    public AStarPathfinder(ServerLevel world, BlockPos start, BlockPos goal, boolean canPillar) {
        this(null, world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, canPillar);
    }

    public AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal, boolean canPillar) {
        this(bot, world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, canPillar);
    }

    /** Replans with the caller's original movement-capability ceiling intact. */
    public AStarPathfinder(ServerLevel world, BlockPos start, BlockPos goal,
                           boolean canPillar, boolean allowDig) {
        this(null, world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, canPillar, allowDig);
    }

    /** Replans with the caller's original movement-capability ceiling intact. */
    public AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal,
                           boolean canPillar, boolean allowDig) {
        this(bot, world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, canPillar, allowDig);
    }

    public AStarPathfinder(ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis, boolean canPillar) {
        this(null, world, start, goal, maxNodes, maxMillis, canPillar, true);
    }

    public AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis, boolean canPillar) {
        this(bot, world, start, goal, maxNodes, maxMillis, canPillar, true);
    }

    // NAV-OPT: allowDig distinguishes between two search modes, "walk-only" and "dig-through allowed", supporting two-phase pathfinding (walk-only first, dig-through as fallback).
    public AStarPathfinder(ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis, boolean canPillar, boolean allowDig) {
        this(null, world, start, goal, maxNodes, maxMillis, canPillar, allowDig, 1.0D);
    }

    /**
     * Same search, plus the acting bot so NeighborEnumerator's DIG_THROUGH preflight
     * (digEnterable/adjacentHazardFluid) can gate its lava/water peek on what that bot can
     * genuinely observe right now, instead of treating every unmined neighbour as unknown risk
     * without ever proactively avoiding a hazard the bot can already see. {@code bot} may be
     * null (e.g. a walk-only search that never invokes digEnterable, or a caller with no bot
     * handy); OreScan.observeDangerFluid treats a null bot as "cannot observe," which safely
     * degrades to the same allow-unknown behavior as the no-bot constructors below.
     */
    public AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis, boolean canPillar, boolean allowDig) {
        this(bot, world, start, goal, maxNodes, maxMillis, canPillar, allowDig, 1.0D);
    }

    // Weighted constructor (the unified approach primitive uses ε=3): see the heuristicWeight comment.
    public AStarPathfinder(ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis, boolean canPillar, boolean allowDig, double heuristicWeight) {
        this(null, world, start, goal, maxNodes, maxMillis, canPillar, allowDig, heuristicWeight, true);
    }

    public AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis, boolean canPillar, boolean allowDig, double heuristicWeight) {
        this(bot, world, start, goal, maxNodes, maxMillis, canPillar, allowDig, heuristicWeight, true);
    }

    /**
     * A startup performance probe must observe the real production wall-clock allowance even when a GameTest has deliberately
     * stretched ordinary searches. It is package-private because callers must not use it to make gameplay routes flaky in a harness.
     */
    static AStarPathfinder withRealTimeBudget(ServerLevel world, BlockPos start, BlockPos goal,
                                               int maxNodes, long maxMillis, boolean canPillar, boolean allowDig) {
        return new AStarPathfinder(null, world, start, goal, maxNodes, maxMillis, canPillar, allowDig, 1.0D, false);
    }

    private AStarPathfinder(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos goal,
                            int maxNodes, long maxMillis, boolean canPillar, boolean allowDig,
                            double heuristicWeight, boolean applyHarnessTimeScale) {
        this.bot = bot;
        this.world = world;
        this.start = start.immutable();
        this.goal = goal.immutable();
        this.canPillar = canPillar;
        this.allowDig = allowDig;
        this.heuristicWeight = heuristicWeight;
        this.enumerator = new NeighborEnumerator(bot, canPillar, allowDig);
        this.maxNodes = maxNodes;
        this.maxMillis = maxMillis * (applyHarnessTimeScale ? harnessTimeScale : 1L);
    }

    /**
     * Counts the block changes bots made (break, place, use, bucket) and world-boundary resets, so a
     * caller that cached a finding about the terrain can tell that the terrain may have changed.
     */
    public static long cacheVersion() {
        return cacheVersion;
    }

    public static void invalidateCache(String reason) {
        synchronized (RESULT_CACHE) {
            cacheVersion++;
            RESULT_CACHE.clear();
        }
        Standability.invalidateAll();
        BotLog.path(null, "findpath_cache_invalidated", "reason", reason, "version", cacheVersion);
    }

    public PathfindingResult findPath() {
        return findPath(true, Integer.MIN_VALUE);
    }

    /**
     * Runs a topology-fresh search without reading or populating the ordinary TTL result cache.
     *
     * <p>Safety contracts use this seam for outbound/return admission and runtime return leases.
     * A cached route is useful for ordinary navigation, but cannot prove that a reversible corridor
     * still exists after an external block, piston, explosion or fluid update.</p>
     */
    public PathfindingResult findPathUncached() {
        return findPath(false, Integer.MIN_VALUE);
    }

    /**
     * Runs an uncached search whose graph cannot expand below {@code minimumY}.
     * This finds a longer legal surface route when the ordinary shortest route uses a forbidden
     * descent, rather than finding that descent first and rejecting the whole destination.
     */
    public PathfindingResult findPathUncachedAtOrAbove(int minimumY) {
        return findPath(false, minimumY);
    }

    /**
     * The one measurement seam for every legacy A* invocation, including executor-time replans
     * and return proofs. Callers must not duplicate this at their request sites: doing so misses
     * later replans and double-counts the initial route.
     */
    private PathfindingResult findPath(boolean useResultCache, int minimumY) {
        boolean capturePlanner = NavigationMeasurement.isCapturing(bot);
        long measurementStarted = capturePlanner ? System.nanoTime() : 0L;
        PathfindingResult result = findPathInternal(useResultCache, minimumY);
        if (capturePlanner) {
            NavigationMeasurement.recordPlanner(bot, NavEngine.LEGACY,
                    useResultCache ? "astar_cached" : "astar_uncached",
                    System.nanoTime() - measurementStarted, result.elapsedMs(), result.nodesExplored(), result.path().size(),
                    result.success() ? "SUCCESS" : result.reason().name());
        }
        return result;
    }

    private PathfindingResult findPathInternal(boolean useResultCache, int minimumY) {
        long startTime = System.currentTimeMillis();
        BotLog.path(null, "findpath_start", "start", LogFields.pos(start), "goal", LogFields.pos(goal));
        Standability.clearCache();
        BlockPos effectiveStart = resolveEndpoint(start, true);
        if (effectiveStart == null || effectiveStart.getY() < minimumY) {
            return done(PathfindingResult.failure(FailureReason.NO_START, 0, elapsed(startTime)));
        }
        BlockPos effectiveGoal = resolveEndpoint(goal, false);
        if (effectiveGoal == null || effectiveGoal.getY() < minimumY) {
            return done(PathfindingResult.failure(FailureReason.GOAL_NOT_STANDABLE, 0, elapsed(startTime)));
        }
        enumerator.setPathGoal(effectiveGoal);
        CacheKey cacheKey = new CacheKey(
                world,
                effectiveStart, effectiveGoal,
                (int) (maxNodes + heuristicWeight * 1000), maxMillis,
                canPillar, allowDig, minimumY, cacheVersion);
        if (useResultCache) {
            PathfindingResult cached = cached(cacheKey, startTime);
            if (cached != null) {
                return cached;
            }
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble(Node::fCost)
                .thenComparingDouble(Node::hCost));
        Map<BlockPos, Double> gScore = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node startNode = new Node(effectiveStart, 0.0D, CostModel.heuristic(effectiveStart, effectiveGoal) * heuristicWeight, MoveType.WALK, null);
        open.add(startNode);
        gScore.put(effectiveStart, 0.0D);

        int explored = 0;
        while (!open.isEmpty()) {
            if (explored >= maxNodes) {
                return finish(cacheKey,
                        PathfindingResult.failure(
                                FailureReason.SEARCH_LIMIT, explored, elapsed(startTime)),
                        startTime, useResultCache);
            }
            if (elapsed(startTime) > maxMillis) {
                BotLog.comm(null, "findpath_timeout_diag", "explored", explored, "ms", elapsed(startTime), "open", open.size(), "dig", allowDig);
                return finish(cacheKey,
                        PathfindingResult.failure(
                                FailureReason.TIMEOUT, explored, elapsed(startTime)),
                        startTime, useResultCache);
            }

            Node current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            explored++;
            if (current.pos().equals(effectiveGoal)) {
                return finish(cacheKey,
                        PathfindingResult.success(
                                reconstruct(current), explored, elapsed(startTime)),
                        startTime, useResultCache);
            }

            for (NeighborCandidate neighbor : enumerator.getNeighbors(current.pos(), world)) {
                if (neighbor.pos().getY() < minimumY
                        || closed.contains(neighbor.pos())) {
                    continue;
                }
                double tentativeG = current.gCost() + CostModel.stepCost(current, neighbor, world);
                double knownG = gScore.getOrDefault(neighbor.pos(), Double.POSITIVE_INFINITY);
                if (knownG <= tentativeG) {
                    continue;
                }
                gScore.put(neighbor.pos(), tentativeG);
                open.add(new Node(
                        neighbor.pos(),
                        tentativeG,
                        CostModel.heuristic(neighbor.pos(), effectiveGoal) * heuristicWeight,
                        neighbor.moveType(),
                        current));
            }
        }
        return finish(cacheKey,
                PathfindingResult.failure(
                        FailureReason.GOAL_UNREACHABLE, explored, elapsed(startTime)),
                startTime, useResultCache);
    }

    private static PathfindingResult finish(
            CacheKey key, PathfindingResult result, long nowMillis,
            boolean useResultCache) {
        return done(useResultCache ? cache(key, result, nowMillis) : result);
    }

    private BlockPos resolveEndpoint(BlockPos requested, boolean startPoint) {
        if (Standability.isStandable(world, requested)) {
            return requested;
        }
        // Goal exemption for dig mode (the key to the unified approach primitive): when the target
        // isn't standable but is itself diggable (typically = an ore neighbor position encased in
        // stone), don't snap -- DIG_THROUGH neighbors already allow a "solid cell that will be dug
        // out" to be a path node, and the executor will dig it out. The original hard check that
        // "the goal must have a ready-made standable position" was exactly the root cause of
        // OreSeek repeatedly getting stuck on encased ore back then, forcing the invention of a
        // task-private "controlled direct-dig" (no standable position within 8 cells -> snap null
        // -> the whole pathfinding request rejected).
        if (allowDig && !startPoint && isDiggableColumn(requested)) {
            return requested;
        }
        if (startPoint) {
            // The bot's own current position really can be at the bottom of a shaft it just dug;
            // finding where it ACTUALLY is standing means searching its own column deeply before
            // ever relocating it sideways. Unchanged from before this fix.
            Optional<BlockPos> snapped = Standability.findNearestStandable(world, requested, 8, 128, 32);
            if (snapped.isEmpty()) {
                return null;
            }
            BotLog.path(null, "findpath_start_snapped",
                    "from", LogFields.pos(requested),
                    "to", LogFields.pos(snapped.get()));
            return snapped.get();
        }
        // A goal is offered by a caller that generally only knows an XZ (or a followed entity's
        // feet) -- see GOAL_NEAR_HORIZONTAL_RADIUS et al.'s header on Standability for the
        // 2026-09-28 evidence this two-phase snap was written against (a surface goal resolving
        // into the bot's own old mining staircase, and the same bug's water counterpart resolving
        // onto a lake bed).
        Optional<Standability.SnappedGoal> snapped = Standability.findNearestStandableForGoal(
                world, requested,
                GOAL_NEAR_HORIZONTAL_RADIUS, GOAL_NEAR_VERTICAL_DOWN, GOAL_NEAR_VERTICAL_UP,
                GOAL_DEEP_VERTICAL_DOWN);
        if (snapped.isEmpty()) {
            return null;
        }
        BotLog.path(null, "findpath_goal_snapped",
                "from", LogFields.pos(requested),
                "to", LogFields.pos(snapped.get().pos()),
                "phase", snapped.get().phase());
        return snapped.get().pos();
    }

    // Goal cell "becomes standable once dug out": both the foot and head positions are (diggable solid OR already passable), with no fluid -- after digging through it becomes a legal standing position.
    private boolean isDiggableColumn(BlockPos pos) {
        return diggableOrPassable(pos) && diggableOrPassable(pos.above());
    }

    private boolean diggableOrPassable(BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false; // a fluid cell can't be dug into a foothold (water/lava would flow in)
        }
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;  // already passable
        }
        // Keep endpoint admission in lockstep with neighbor expansion. A goal behind a protected
        // or player-built block must be snapped or rejected, not accepted as a dig target that
        // no DIG_THROUGH edge can ever enter.
        return NeighborEnumerator.isMineable(world, pos);
    }

    private static PathfindingResult cached(CacheKey key, long startTime) {
        synchronized (RESULT_CACHE) {
            CachedResult cached = RESULT_CACHE.get(key);
            if (cached == null || cached.expiredAtMillis < startTime) {
                RESULT_CACHE.remove(key);
                return null;
            }
            PathfindingResult result = cached.toResult();
            BotLog.path(null, "findpath_cache_hit",
                    "success", result.success(),
                    "fail_reason", result.reason(),
                    "ttl_ms", cached.expiredAtMillis - startTime);
            return done(result);
        }
    }

    private static PathfindingResult cache(CacheKey key, PathfindingResult result, long nowMillis) {
        long ttl = result.success() ? SUCCESS_CACHE_MILLIS : FAILURE_CACHE_MILLIS;
        synchronized (RESULT_CACHE) {
            RESULT_CACHE.put(key, new CachedResult(result, nowMillis + ttl));
        }
        return result;
    }

    private static PathfindingResult done(PathfindingResult result) {
        BotLog.path(null, "findpath_done",
                "success", result.success(),
                "nodes", result.nodesExplored(),
                "ms", result.elapsedMs(),
                "fail_reason", result.reason());
        return result;
    }

    private static List<Node> reconstruct(Node end) {
        List<Node> path = new ArrayList<>();
        for (Node current = end; current != null; current = current.parent()) {
            path.add(current);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private static long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * The world instance belongs in the key: distinct ServerLevels may share a dimension id while
     * holding different terrain (notably during sequential server lifecycles in one JVM). The
     * bounded, short-lived cache is cleared at every runtime world boundary.
     */
    private record CacheKey(
            ServerLevel world,
            BlockPos start,
            BlockPos goal,
            int maxNodes,
            long maxMillis,
            boolean canPillar,
            boolean allowDig,
            int minimumY,
            long version) {
        private CacheKey {
            start = start.immutable();
            goal = goal.immutable();
        }
    }

    private record CachedResult(List<Node> path, boolean success, FailureReason reason, int nodesExplored, long expiredAtMillis) {
        private CachedResult(PathfindingResult result, long expiredAtMillis) {
            this(List.copyOf(result.path()), result.success(), result.reason(), result.nodesExplored(), expiredAtMillis);
        }

        private PathfindingResult toResult() {
            if (success) {
                return PathfindingResult.success(path, nodesExplored, 0L);
            }
            return PathfindingResult.failure(reason, nodesExplored, 0L);
        }
    }
}
