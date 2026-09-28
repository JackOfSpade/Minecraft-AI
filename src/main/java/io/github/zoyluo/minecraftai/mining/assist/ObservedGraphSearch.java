package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Single-source Dijkstra over observed-standable AIR cells (mining-assist design 5.4): the search
 * L2 cave-frontier excursions use to find the cheapest observed route from the bot to every
 * reachable candidate stand, without ever touching unobserved geometry or the game's own
 * pathfinder -- {@code AStarPathfinder} and {@code isWalkReachable} are never used as ranking
 * oracles (invariant I3), only as the later walk-only execution of an already-chosen frontier.
 *
 * <p>The graph is never materialised: {@link Environment} answers per-cell questions on demand, so
 * a search costs only what it actually visits, bounded by {@link #NODE_CAP}. This class does no
 * world reads of its own and needs no server, so it is fully covered by
 * {@code ObservedGraphSearchTest} against a fake {@link Environment}.</p>
 */
public final class ObservedGraphSearch {
    /** Hard cap on cells expanded, so a search can never run unbounded (design 5.4). */
    public static final int NODE_CAP = 4096;
    /** Chebyshev radius within which an observed lava cell forbids a candidate cell. */
    public static final int LAVA_CLEARANCE = 2;
    /** Base cost of a move (design 5.4's {@code CostModel}: {@code 0.5 + 0.3*fall}). */
    public static final double BASE_MOVE_COST = 0.5D;
    /** Extra cost per block of fall on a drop move. */
    public static final double FALL_COST_PER_BLOCK = 0.3D;
    /** Extra cost added to a move landing on a cell adjacent to observed water. */
    public static final double WATER_ADJACENCY_PENALTY = 10.0D;
    /** Deepest single-move drop (design 5.4: "drop up to 3"). */
    public static final int MAX_DROP = 3;

    /**
     * The observed world, queried once per candidate cell the search actually visits. Every
     * position is the cell a bot would stand in (the feet cell, the AIR cell directly above a SOLID
     * floor). Implementations must be pure functions of already-observed state -- no raycasting, no
     * reads of cells the bot has not actually seen -- so the search never becomes a ranking oracle
     * over unobserved geometry. UNKNOWN cells must answer {@code isStandable == false}, which is how
     * this class satisfies "UNKNOWN cells are not in the graph".
     */
    public interface Environment {
        /**
         * True when {@code pos} is an observed-standable AIR cell: an observed SOLID floor below it,
         * and {@code pos} itself plus the cell above it both observed AIR. False for any UNKNOWN
         * cell in that column.
         */
        boolean isStandable(BlockPos pos);

        /**
         * True when {@code pos} is within {@link #LAVA_CLEARANCE} (Chebyshev) of an observed lava
         * cell, or is itself an observed TRAP cell: both are forbidden regardless of
         * {@link #isStandable}.
         */
        boolean isForbidden(BlockPos pos);

        /** True when {@code pos} is adjacent (any of the 6 faces) to an observed water cell. */
        boolean isAdjacentToWater(BlockPos pos);
    }

    private ObservedGraphSearch() {
    }

    /** One reached cell and its cheapest observed cost from the source, used internally and for logs/tests. */
    public record Reached(BlockPos pos, double cost) {
        public Reached {
            pos = Objects.requireNonNull(pos, "pos").toImmutable();
        }
    }

    /**
     * Runs Dijkstra from {@code source} until every reachable, unforbidden, standable cell has its
     * cheapest observed cost, the frontier empties, or {@link #NODE_CAP} cells have been expanded.
     * The source itself is always included at cost 0, even if {@link Environment#isStandable} would
     * say otherwise (the bot is already standing there, whatever the floor under it looks like from
     * here). Returns cheapest cost by cell.
     *
     * @throws NullPointerException if {@code source} or {@code env} is null
     */
    public static Map<BlockPos, Double> search(BlockPos source, Environment env) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(env, "env");
        BlockPos start = source.toImmutable();
        Map<BlockPos, Double> best = new HashMap<>();
        PriorityQueue<Reached> frontier = new PriorityQueue<>(Comparator.comparingDouble(Reached::cost));
        best.put(start, 0.0D);
        frontier.add(new Reached(start, 0.0D));
        int expansions = 0;
        while (!frontier.isEmpty() && expansions < NODE_CAP) {
            Reached current = frontier.poll();
            Double known = best.get(current.pos());
            if (known == null || current.cost() > known) {
                continue; // a stale queue entry: a cheaper path to this cell already won
            }
            expansions++;
            int cx = current.pos().getX();
            int cy = current.pos().getY();
            int cz = current.pos().getZ();
            for (int[] horizontal : HORIZONTAL) {
                for (int[] vertical : VERTICAL_MOVES) {
                    BlockPos next = new BlockPos(cx + horizontal[0], cy + vertical[0], cz + horizontal[1]);
                    if (env.isForbidden(next) || !env.isStandable(next)) {
                        continue;
                    }
                    double moveCost = BASE_MOVE_COST + FALL_COST_PER_BLOCK * vertical[1];
                    if (env.isAdjacentToWater(next)) {
                        moveCost += WATER_ADJACENCY_PENALTY;
                    }
                    double candidateCost = current.cost() + moveCost;
                    Double existing = best.get(next);
                    if (existing == null || candidateCost < existing) {
                        best.put(next, candidateCost);
                        frontier.add(new Reached(next, candidateCost));
                    }
                }
            }
        }
        return best;
    }

    /** Cells the search actually expanded from, ordered nearest-cost first; a convenience over {@link #search}. */
    public static List<Reached> searchOrdered(BlockPos source, Environment env) {
        Map<BlockPos, Double> best = search(source, env);
        List<Reached> ordered = new ArrayList<>(best.size());
        for (Map.Entry<BlockPos, Double> entry : best.entrySet()) {
            ordered.add(new Reached(entry.getKey(), entry.getValue()));
        }
        ordered.sort(Comparator.comparingDouble(Reached::cost));
        return ordered;
    }

    /**
     * Like {@link #search}, but also returns the cheapest observed route to {@code target} as an ordered
     * list of cells from {@code source} (inclusive) to {@code target} (inclusive) -- the geometry the
     * cave-frontier waypoint execution needs (design 5.4), which {@link #search}'s cost-only result cannot
     * give. Runs its own Dijkstra with predecessor tracking rather than reusing {@link #search}, so the
     * hot path used to score every candidate ({@link FrontierPlanner}) never pays for predecessor
     * bookkeeping it does not need.
     *
     * @return the route, or {@code null} when {@code target} is unreached within {@link #NODE_CAP}
     *         expansions (unstandable, forbidden, UNKNOWN, or simply too far)
     * @throws NullPointerException if any argument is null
     */
    public static List<BlockPos> path(BlockPos source, BlockPos target, Environment env) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(env, "env");
        BlockPos start = source.toImmutable();
        BlockPos goal = target.toImmutable();
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> predecessor = new HashMap<>();
        PriorityQueue<Reached> frontier = new PriorityQueue<>(Comparator.comparingDouble(Reached::cost));
        best.put(start, 0.0D);
        frontier.add(new Reached(start, 0.0D));
        int expansions = 0;
        while (!frontier.isEmpty() && expansions < NODE_CAP) {
            Reached current = frontier.poll();
            Double known = best.get(current.pos());
            if (known == null || current.cost() > known) {
                continue;
            }
            if (current.pos().equals(goal)) {
                return reconstruct(start, goal, predecessor);
            }
            expansions++;
            int cx = current.pos().getX();
            int cy = current.pos().getY();
            int cz = current.pos().getZ();
            for (int[] horizontal : HORIZONTAL) {
                for (int[] vertical : VERTICAL_MOVES) {
                    BlockPos next = new BlockPos(cx + horizontal[0], cy + vertical[0], cz + horizontal[1]);
                    if (env.isForbidden(next) || !env.isStandable(next)) {
                        continue;
                    }
                    double moveCost = BASE_MOVE_COST + FALL_COST_PER_BLOCK * vertical[1];
                    if (env.isAdjacentToWater(next)) {
                        moveCost += WATER_ADJACENCY_PENALTY;
                    }
                    double candidateCost = current.cost() + moveCost;
                    Double existing = best.get(next);
                    if (existing == null || candidateCost < existing) {
                        best.put(next, candidateCost);
                        predecessor.put(next, current.pos());
                        frontier.add(new Reached(next, candidateCost));
                    }
                }
            }
        }
        return best.containsKey(goal) ? reconstruct(start, goal, predecessor) : null;
    }

    private static List<BlockPos> reconstruct(BlockPos start, BlockPos goal, Map<BlockPos, BlockPos> predecessor) {
        List<BlockPos> route = new ArrayList<>();
        BlockPos at = goal;
        route.add(at);
        while (!at.equals(start)) {
            at = predecessor.get(at);
            if (at == null) {
                return null; // defensive; unreachable given the caller only reaches here with a known route
            }
            route.add(at);
        }
        java.util.Collections.reverse(route);
        return route;
    }

    // (dx, dz) for the four cardinal horizontal moves; the same four are used for walk, step-up and every drop depth.
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // (dy, fallBlocks): walk (dy=0), step up 1 (dy=+1), and drop 1..MAX_DROP (dy=-1..-MAX_DROP, fall = -dy).
    private static final int[][] VERTICAL_MOVES = buildVerticalMoves();

    private static int[][] buildVerticalMoves() {
        int[][] moves = new int[2 + MAX_DROP][2];
        moves[0] = new int[] {0, 0};
        moves[1] = new int[] {1, 0};
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            moves[1 + drop] = new int[] {-drop, drop};
        }
        return moves;
    }
}
