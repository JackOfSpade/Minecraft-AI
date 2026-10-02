package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bounded breadth-first search over the water (and its dry landings) around a swimming bot.  It
 * reuses {@link NavSafetyNet}'s physical-movement predicates -- an adjacent cell whose feet and head
 * are collision-free and that is either a water cell or a dry standable landing -- so every cell of
 * a returned route is one the walked swim and cell steps can actually take.
 * Used by follow to leave the water toward a player who walked out, to climb toward the nearest
 * breathable cell when a straight-up ascent is blocked, and to get around an obstacle that a
 * greedy step toward a swimmer cannot.
 *
 * <p>Strict survival expands only water and dry cells that the bot can currently observe: feet and
 * head are proved before their state is read, and a dry cell's support collider is proved before its
 * fresh standability check. Its visibility-filtered frontier is deliberately a local exploration
 * aid, not a hidden-world route planner. The retained {@link PrivilegedCapability#HIDDEN_BLOCK_SCAN} enum value
 * is centrally retired in every profile, so no route may use the full-world planner. Every returned cell is also
 * re-verified against the live world by the {@code WalkedStep} rules as it is taken (a route is
 * dropped after repeated refused steps).
 */
final class SwimRoute {
    enum Goal {
        /** Nearest cell where the bot's head is out of the water. */
        AIR,
        /** Best dry standable landing, preferring one about {@code standoff} blocks from the target. */
        EXIT,
        /** Reachable swim cell closest to the target (must improve on the start). */
        APPROACH
    }

    private static final int MAX_NODES = 4000;
    private static final int RADIUS_H = 20;
    private static final int RADIUS_V = 20;
    /**
     * Strict route searches spend this many newly examined cells per server tick. It is a work
     * scheduler, not a route limit: {@link SearchProgress} retains its queue/parents until the
     * complete established geometric volume is exhausted in strict survival. The historic
     * {@link #MAX_NODES} compatibility cutoff remains only for deliberate operator scans.
     */
    static final int STRICT_SEARCH_CANDIDATES_PER_TICK = 96;

    enum Cell {
        WATER,
        WATER_WITH_AIR_ABOVE,
        DRY;

        boolean isWater() {
            return this != DRY;
        }
    }

    private SwimRoute() {
    }

    /**
     * Synchronous convenience for focused tests and one-shot tooling. Production Follow callers
     * use {@link SearchProgress} in both profiles: hidden-world capability changes the permitted
     * terrain proof, not whether a broad route search may monopolise a server tick.
     *
     * @return the cells to step through, in order, excluding {@code start}; empty when none.
     */
    static Optional<List<BlockPos>> search(AIPlayerEntity bot, ServerLevel world, BlockPos start, BlockPos target,
                                           Goal goal, double standoff) {
        return search(bot, world, start, target, goal, standoff, hiddenWorldScanAllowed(bot));
    }

    /** Same route search with the capability decision latched by the caller for its cache key. */
    static Optional<List<BlockPos>> search(AIPlayerEntity bot, ServerLevel world, BlockPos start,
                                           BlockPos target, Goal goal, double standoff,
                                           boolean hiddenWorldScan) {
        SearchProgress progress = new SearchProgress(start, target, goal, standoff, hiddenWorldScan);
        SearchResult result;
        do {
            result = progress.advance(bot, world, Integer.MAX_VALUE);
        } while (result.pending());
        return result.found() ? Optional.of(result.path()) : Optional.empty();
    }

    /** Starts a retained route search for a strict caller to advance over several server ticks. */
    static SearchProgress startSearch(BlockPos start, BlockPos target, Goal goal, double standoff,
                                      boolean hiddenWorldScan) {
        return new SearchProgress(start, target, goal, standoff, hiddenWorldScan);
    }

    enum SearchStatus {
        PENDING,
        /** A strict frontier is waiting for a new viewpoint or its bounded later-tick reproof. */
        UNKNOWN,
        /** A prior completed empty search for this same query is throttling its next retry. */
        COOLDOWN,
        FOUND,
        EMPTY
    }

    record SearchResult(SearchStatus status, List<BlockPos> path) {
        private static SearchResult pendingResult() {
            return new SearchResult(SearchStatus.PENDING, List.of());
        }

        private static SearchResult foundResult(List<BlockPos> path) {
            return new SearchResult(SearchStatus.FOUND, path);
        }

        private static SearchResult unknownResult() {
            return new SearchResult(SearchStatus.UNKNOWN, List.of());
        }

        private static SearchResult emptyResult() {
            return new SearchResult(SearchStatus.EMPTY, List.of());
        }

        boolean pending() {
            return status == SearchStatus.PENDING;
        }

        boolean waitingForViewpoint() {
            return status == SearchStatus.PENDING || status == SearchStatus.UNKNOWN;
        }

        boolean found() {
            return status == SearchStatus.FOUND;
        }
    }

    /**
     * Resumable counterpart to {@link #search}. A caller advances it with a bounded amount of
     * server work and must keep {@link SearchStatus#PENDING} distinct from a completed empty
     * route. It holds no world-state result: every candidate is still classified at the tick it
     * is examined, and each later walked step repeats its live observation proof.
     */
    static final class SearchProgress {
        private final BlockPos origin;
        private final BlockPos target;
        private final Goal goal;
        private final double standoff;
        private final boolean hiddenWorldScan;
        private final int observationRadius;
        private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        private final HashSet<BlockPos> queued = new HashSet<>();
        private final Map<BlockPos, BlockPos> parent = new HashMap<>();
        private final Map<BlockPos, Integer> depth = new HashMap<>();
        private final HashSet<BlockPos> visited = new HashSet<>();
        /** Strict candidates whose state was unknown from this viewpoint, retried one at a time. */
        private final ArrayDeque<UnknownCandidate> unknowns = new ArrayDeque<>();
        private final HashSet<BlockPos> unknownCells = new HashSet<>();
        private final double startDistance;
        private BlockPos current;
        private List<BlockPos> currentNeighbors;
        private int nextNeighbor;
        private int nodes;
        private BlockPos lastFeet;
        private int observationEpoch;
        /** Last server tick whose one stationary UNKNOWN retry allowance was reset. */
        private int lastObservationTick = Integer.MIN_VALUE;
        /** A fixed viewpoint re-proves at most one retained unknown edge on each later tick. */
        private boolean stationaryUnknownRetryUsed;
        private boolean retryTurn = true;
        private BlockPos best;
        private double bestScore = Double.MAX_VALUE;
        private SearchResult result;

        private SearchProgress(BlockPos start, BlockPos target, Goal goal, double standoff,
                               boolean hiddenWorldScan) {
            this.origin = start.immutable();
            this.target = target.immutable();
            this.goal = goal;
            this.standoff = standoff;
            this.hiddenWorldScan = hiddenWorldScan;
            this.observationRadius = Math.max(1, MinecraftAiConfig.get().perception().radius());
            enqueue(this.origin);
            visited.add(this.origin);
            depth.put(this.origin, 0);
            lastFeet = this.origin;
            startDistance = this.origin.distSqr(this.target);
        }

        /**
         * Whether the retained physical search can continue. Its target is intentionally a last
         * observed snapshot: a player walking into a different block while the cooperative BFS is
         * pending must not restart and starve the frontier every tick. Once this snapshot route
         * yields or exhausts, the caller naturally starts a new search against the current target.
         */
        boolean matchesContext(BlockPos start, Goal goal, double standoff,
                               boolean hiddenWorldScan, int currentObservationRadius) {
            return this.goal == goal
                    && Double.compare(this.standoff, standoff) == 0
                    && this.hiddenWorldScan == hiddenWorldScan
                    && this.observationRadius == currentObservationRadius
                    && withinSearchBounds(start)
                    && (start.equals(lastFeet) || adjacent(lastFeet, start));
        }

        SearchResult advance(AIPlayerEntity bot, ServerLevel world, int workBudget) {
            if (result != null) {
                return result;
            }
            if (!admitCurrentFeet(bot.blockPosition())) {
                // The caller will start a new session for a non-physical displacement. This is
                // deliberately not EMPTY: it says nothing about the retained visible volume.
                return SearchResult.unknownResult();
            }
            beginObservationTick(world.getServer().getTickCount());
            int work = 0;
            while (work < workBudget) {
                boolean retryReady = hasEligibleUnknown();
                boolean frontierReady = current != null || !queue.isEmpty();
                if (!retryReady && !frontierReady) {
                    // A physical route already proved to a goal is actionable even if farther
                    // alternatives remain unknown from this viewpoint. Withholding it would turn
                    // an occluded side branch into an invented obstruction.
                    if (best != null || unknowns.isEmpty()) {
                        return finish();
                    }
                    // Unknown is distinct from a searched-empty world. The session retains the
                    // exact candidate/parent pairs and becomes runnable after a physical view
                    // change or one bounded later-tick reproof, instead of starting an empty-
                    // route cooldown.
                    return SearchResult.unknownResult();
                }

                WaterAdmission admission;
                if (retryReady && (retryTurn || !frontierReady)) {
                    work++;
                    admission = retryOneUnknown(bot, world);
                    retryTurn = false;
                } else {
                    work++;
                    admission = advanceOneFrontierOperation(bot, world);
                    retryTurn = true;
                }
                if (admission != null && admission.result() != null) {
                    return admission.result();
                }
            }
            return SearchResult.pendingResult();
        }

        private SearchResult finish() {
            if (best == null) {
                result = SearchResult.emptyResult();
                return result;
            }
            List<BlockPos> path = pathFromCurrent(parent, lastFeet, best);
            result = path.isEmpty() ? SearchResult.emptyResult() : SearchResult.foundResult(path);
            return result;
        }

        /** Adds one physical fallback landing to the retained observed tree and opens a new view epoch. */
        private boolean admitCurrentFeet(BlockPos feet) {
            if (feet.equals(lastFeet)) {
                return true;
            }
            observationEpoch++;
            BlockPos currentFeet = feet.immutable();
            if (!adjacent(lastFeet, currentFeet)) {
                return false;
            }
            if (!connected(currentFeet)) {
                if (!connected(lastFeet)) {
                    return false;
                }
                visited.add(currentFeet);
                unknownCells.remove(currentFeet);
                parent.put(currentFeet, lastFeet.immutable());
                depth.put(currentFeet, depth.getOrDefault(lastFeet, 0) + 1);
                enqueue(currentFeet);
            }
            lastFeet = currentFeet;
            return true;
        }

        /**
         * A ray's result is current observation, not a permanent fact about a stationary bot.
         * A nearby occluder may be removed without a legal physical fallback, so each later
         * server tick grants one retained strict UNKNOWN edge a fresh ray. A real viewpoint
         * change still releases its old frontier through the normal bounded work slice.
         */
        private void beginObservationTick(int now) {
            if (lastObservationTick == now) {
                return;
            }
            lastObservationTick = now;
            stationaryUnknownRetryUsed = false;
        }

        private boolean hasEligibleUnknown() {
            UnknownCandidate unknown = unknowns.peekFirst();
            return unknown != null && (unknown.observationEpoch() < observationEpoch
                    || (!stationaryUnknownRetryUsed && unknown.observedTick() < lastObservationTick));
        }

        /** Performs exactly one dequeue/cursor/neighbour operation from the visible frontier. */
        private WaterAdmission advanceOneFrontierOperation(AIPlayerEntity bot, ServerLevel world) {
            if (current == null) {
                // Operator compatibility preserves the historic node ceiling. Strict survival
                // instead searches every cell inside its full configured-visible extent.
                if (hiddenWorldScan && nodes >= MAX_NODES) {
                    queue.clear();
                    queued.clear();
                    return null;
                }
                current = queue.pollFirst();
                if (current == null) {
                    return null;
                }
                queued.remove(current);
                currentNeighbors = NavSafetyNet.waterEscapeNeighbors(current);
                nextNeighbor = 0;
                nodes++;
                return null;
            }
            if (nextNeighbor >= currentNeighbors.size()) {
                current = null;
                currentNeighbors = null;
                return null;
            }
            BlockPos candidate = currentNeighbors.get(nextNeighbor++);
            if (!withinSearchBounds(candidate) || !visited.add(candidate)) {
                return null;
            }
            CellProbe probe = probeCell(bot, world, candidate, hiddenWorldScan);
            Cell cellKind = probe.cell();
            if (cellKind == null) {
                if (!hiddenWorldScan && probe.unknown()) {
                    rememberUnknown(candidate, current);
                }
                return null;
            }
            return admitObservedCell(candidate, current, cellKind);
        }

        /** Re-checks one strict unknown edge after a new viewpoint or its later-tick reproof. */
        private WaterAdmission retryOneUnknown(AIPlayerEntity bot, ServerLevel world) {
            UnknownCandidate unknown = unknowns.removeFirst();
            // A changed viewpoint may recheck several older edges under this search's normal
            // work budget. At a fixed view this makes the requeued edge (and every sibling)
            // wait until the next tick, avoiding an unbounded stationary fan-out.
            stationaryUnknownRetryUsed = true;
            if (!unknownCells.remove(unknown.cell()) || connected(unknown.cell())
                    || !connected(unknown.parent())) {
                return null;
            }
            CellProbe probe = probeCell(bot, world, unknown.cell(), false);
            Cell cellKind = probe.cell();
            if (cellKind == null) {
                if (probe.unknown()) {
                    rememberUnknown(unknown.cell(), unknown.parent());
                }
                return null;
            }
            return admitObservedCell(unknown.cell(), unknown.parent(), cellKind);
        }

        private WaterAdmission admitObservedCell(BlockPos candidate, BlockPos parentCell, Cell cellKind) {
            BlockPos cell = candidate.immutable();
            parent.put(cell, parentCell.immutable());
            int currentDepth = depth.getOrDefault(parentCell, 0) + 1;
            depth.put(cell, currentDepth);
            boolean dry = cellKind == Cell.DRY;
            switch (goal) {
                case AIR -> {
                    if (dry || cellKind == Cell.WATER_WITH_AIR_ABOVE) {
                        return new WaterAdmission(finishedPath(cell));
                    }
                }
                case EXIT -> {
                    if (dry) {
                        double score = currentDepth
                                + 1.5D * Math.abs(Math.sqrt(cell.distSqr(target)) - standoff);
                        if (score < bestScore) {
                            bestScore = score;
                            best = cell;
                        }
                        // A proved shore is immediately actionable. Searching every farther
                        // visible water cell merely to optimise the standoff score can strand a
                        // low-air swimmer beside a legal bank for hundreds of slices.
                        return new WaterAdmission(finishedPath(cell));
                    }
                }
                case APPROACH -> {
                    if (!dry) {
                        double score = cell.distSqr(target) * 1000.0D + currentDepth;
                        if (cell.distSqr(target) + 0.01D < startDistance && score < bestScore) {
                            bestScore = score;
                            best = cell;
                            // Like EXIT, a real observed improvement beats an exhaustive score
                            // refinement that would hold the bot stationary in open water.
                            return new WaterAdmission(finishedPath(cell));
                        }
                    }
                }
            }
            if (cellKind.isWater()) {
                enqueue(cell);
            }
            return null;
        }

        private SearchResult finishedPath(BlockPos destination) {
            List<BlockPos> path = pathFromCurrent(parent, lastFeet, destination);
            result = path.isEmpty() ? SearchResult.emptyResult() : SearchResult.foundResult(path);
            return result;
        }

        private void rememberUnknown(BlockPos candidate, BlockPos parentCell) {
            BlockPos unknown = candidate.immutable();
            if (unknownCells.add(unknown)) {
                unknowns.addLast(new UnknownCandidate(unknown, parentCell.immutable(), observationEpoch,
                        lastObservationTick));
            }
        }

        private void enqueue(BlockPos cell) {
            if (queued.add(cell)) {
                queue.addLast(cell);
            }
        }

        /** Strict extent is the configured perception radius; operator keeps the legacy volume. */
        private boolean withinSearchBounds(BlockPos candidate) {
            int horizontal = hiddenWorldScan ? RADIUS_H : observationRadius;
            int vertical = hiddenWorldScan ? RADIUS_V : observationRadius;
            return Math.abs(candidate.getX() - origin.getX()) <= horizontal
                    && Math.abs(candidate.getZ() - origin.getZ()) <= horizontal
                    && Math.abs(candidate.getY() - origin.getY()) <= vertical;
        }

        private boolean connected(BlockPos cell) {
            return origin.equals(cell) || parent.containsKey(cell);
        }

        private static boolean adjacent(BlockPos from, BlockPos to) {
            int dx = Math.abs(to.getX() - from.getX());
            int dy = Math.abs(to.getY() - from.getY());
            int dz = Math.abs(to.getZ() - from.getZ());
            return dx <= 1 && dy <= 1 && dz <= 1 && (dx != 0 || dy != 0 || dz != 0);
        }

        private record UnknownCandidate(BlockPos cell, BlockPos parent, int observationEpoch, int observedTick) {
        }

        /** Nullable wrapper distinguishes a normal slice operation from an immediate goal result. */
        private record WaterAdmission(SearchResult result) {
        }
    }

    /** Whether this follow-water operation may deliberately plan through unseen cells. */
    static boolean hiddenWorldScanAllowed(AIPlayerEntity bot) {
        return CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "follow_swim_route").allowed();
    }

    /**
     * Classifies a cell after the strict observation proof while preserving the distinction
     * between a visible physical rejection and a cell whose body/support proof is unavailable.
     * A retained BFS may retry only the latter from a new viewpoint or its bounded later-tick
     * reproof; retrying visible stone or a known hazard forever would turn a genuine empty route
     * into a stalled UNKNOWN result.
     */
    static CellProbe probeCell(AIPlayerEntity bot, ServerLevel world, BlockPos candidate,
                               boolean hiddenWorldScan) {
        if (!hiddenWorldScan && !canObserveColumn(bot, candidate)) {
            return CellProbe.unobserved();
        }
        BlockState feet = world.getBlockState(candidate);
        BlockState head = world.getBlockState(candidate.above());
        if (!feet.getCollisionShape(world, candidate).isEmpty()
                || !head.getCollisionShape(world, candidate.above()).isEmpty()
                || Standability.isDangerous(feet)
                || Standability.isDangerous(head)) {
            return CellProbe.invalid();
        }
        boolean feetWater = feet.getFluidState().is(FluidTags.WATER);
        if (feetWater || head.getFluidState().is(FluidTags.WATER)) {
            return CellProbe.valid(feetWater && !head.getFluidState().is(FluidTags.WATER)
                    ? Cell.WATER_WITH_AIR_ABOVE : Cell.WATER);
        }
        // The support can itself be empty. Cell visibility proves the raw standability read is
        // permitted, then Standability classifies a visible missing floor as known-invalid rather
        // than retaining it as an UNKNOWN collider forever.
        if (!hiddenWorldScan && !ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.below())) {
            return CellProbe.unobserved();
        }
        return Standability.isStandableFresh(world, candidate)
                ? CellProbe.valid(Cell.DRY) : CellProbe.invalid();
    }

    /** Convenience view for immediate callers that do not need to retain unknown candidates. */
    static Cell observedCell(AIPlayerEntity bot, ServerLevel world, BlockPos candidate,
                             boolean hiddenWorldScan) {
        return probeCell(bot, world, candidate, hiddenWorldScan).cell();
    }

    record CellProbe(Cell cell, boolean observed) {
        private static CellProbe valid(Cell cell) {
            return new CellProbe(cell, true);
        }

        private static CellProbe invalid() {
            return new CellProbe(null, true);
        }

        private static CellProbe unobserved() {
            return new CellProbe(null, false);
        }

        boolean unknown() {
            return !observed;
        }
    }

    /** Feet and head must be locally visible before strict mode reads either cell's state. */
    static boolean canObserveColumn(AIPlayerEntity bot, BlockPos candidate) {
        return withinObservationRange(bot, candidate)
                && withinObservationRange(bot, candidate.above())
                && ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate)
                && ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.above());
    }

    /** Cheap range rejection protects strict route and edge scans from futile raycasts. */
    static boolean withinObservationRange(AIPlayerEntity bot, BlockPos candidate) {
        double radius = observationRadius(bot);
        return bot.getEyePosition().distanceToSqr(candidate.getCenter()) <= radius * radius;
    }

    static int observationRadius(AIPlayerEntity bot) {
        return Math.max(1, MinecraftAiConfig.get().perception().radius());
    }

    /**
     * Proves the auxiliary cells that {@link WalkedStep#refusal(AIPlayerEntity, BlockPos,
     * WalkedStep.Kind)} reads after a strict caller has already proved its destination with
     * {@link #observedCell(AIPlayerEntity, ServerLevel, BlockPos, boolean)} (or the equivalent
     * water-rescue proof). A diagonal dry move checks both corner columns, a step-up checks the
     * takeoff headroom column, and a descent checks every cell of the fall column. Those checks
     * are vanilla movement validation, but strict survival must still see their world state before
     * it asks the validator to read it.
     *
     * <p>This deliberately contains no world-state reads. Call it immediately before
     * {@code WalkedStep.refusal}; its only job is to admit the validator's finite sweep envelope,
     * not to choose a route or impose a smaller movement envelope.</p>
     */
    static boolean canObserveWalkedStepRefusalEnvelope(AIPlayerEntity bot, BlockPos target,
                                                        WalkedStep.Kind kind) {
        BlockPos here = bot.blockPosition();
        int dx = target.getX() - here.getX();
        int dy = target.getY() - here.getY();
        int dz = target.getZ() - here.getZ();

        // swimHazard reads only the destination, which the caller's observed-cell proof already
        // covered. Its startedWet input is the bot's own physical water contact, not a remote
        // terrain fact that can authorize a route.
        if (kind == WalkedStep.Kind.SWIM) {
            return true;
        }

        int sweepY = Math.max(here.getY(), target.getY());
        if (dx != 0 && dz != 0
                && (!canObserveColumn(bot, new BlockPos(here.getX() + dx, sweepY, here.getZ()))
                || !canObserveColumn(bot, new BlockPos(here.getX(), sweepY, here.getZ() + dz)))) {
            return false;
        }
        if (kind == WalkedStep.Kind.STEP_UP && !canObserveColumn(bot, here.above())) {
            return false;
        }
        if (kind == WalkedStep.Kind.STEP_DOWN || kind == WalkedStep.Kind.DROP) {
            for (int y = target.getY() + 1; y <= here.getY() + 1; y++) {
                if (!canObserveCell(bot, new BlockPos(target.getX(), y, target.getZ()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** One empty or solid world cell that a strict movement validator is about to inspect. */
    private static boolean canObserveCell(AIPlayerEntity bot, BlockPos cell) {
        return withinObservationRange(bot, cell) && ObservableWorldQuery.canObserveCellThroughFluids(bot, cell);
    }

    /**
     * Reconstructs from the bot's current retained-tree cell. A strict caller may have taken an
     * adjacent visible fallback while its frontier was pending; walking up to the shared ancestor
     * and back down to the goal keeps the first route cell physically adjacent to that real feet
     * position rather than snapping back to the original search root.
     */
    private static List<BlockPos> pathFromCurrent(Map<BlockPos, BlockPos> parent,
                                                  BlockPos current, BlockPos goal) {
        HashSet<BlockPos> currentAncestors = new HashSet<>();
        for (BlockPos cursor = current; cursor != null; cursor = parent.get(cursor)) {
            currentAncestors.add(cursor);
        }
        ArrayList<BlockPos> goalToAncestor = new ArrayList<>();
        BlockPos common = goal;
        while (!currentAncestors.contains(common)) {
            goalToAncestor.add(common);
            common = parent.get(common);
            if (common == null) {
                return List.of();
            }
        }
        ArrayList<BlockPos> path = new ArrayList<>();
        for (BlockPos cursor = current; !cursor.equals(common); cursor = parent.get(cursor)) {
            BlockPos next = parent.get(cursor);
            if (next == null) {
                return List.of();
            }
            path.add(next);
        }
        Collections.reverse(goalToAncestor);
        path.addAll(goalToAncestor);
        return path;
    }
}
