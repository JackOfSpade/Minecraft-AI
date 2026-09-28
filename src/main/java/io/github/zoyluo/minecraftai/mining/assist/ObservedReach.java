package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Honest reachability precheck over the bot's own observed occupancy window (mining-assist design 4.3, I3).
 * It runs before a route start is spent: an expensive path search should only run for candidates that observed
 * geometry has not already ruled out. It is <b>optimistic on UNKNOWN</b>: only observed solid walls and observed
 * fluid block a move. Search success is never a ranking input; a failure only feeds the anti-thrash
 * exclusions ({@code unreachable_observed}).
 *
 * <h2>Graph</h2>
 * A node is a standing cell (the feet cell) inside the {@link ObservedOccupancy} window. Cell states are
 * {@link ObservedOccupancy#UNKNOWN}, {@code AIR}, {@code SOLID}, {@code FLUID} (a cell outside the window is
 * UNKNOWN). A cell is <em>passable</em> when it is AIR or UNKNOWN; <em>floor</em> when it is SOLID or UNKNOWN;
 * FLUID is neither. A stand {@code (x, y, z)} needs {@code (x,y,z)} and {@code (x,y+1,z)} passable and
 * {@code (x,y-1,z)} floor. From a stand, for each of the four horizontal neighbours column {@code c}:
 * <ul>
 *   <li><b>walk</b>: stand {@code (c, y)} is valid;</li>
 *   <li><b>step up 1</b>: {@code (x,y+2,z)} is passable (headroom to jump) and stand {@code (c, y+1)} is valid;</li>
 *   <li><b>drop k</b> for k = 1, 2, 3: every cell of column {@code c} from {@code y - k + 1} up to {@code y + 1}
 *       is passable and stand {@code (c, y - k)} is valid (a drop of more than 3 is not offered).</li>
 * </ul>
 * All moves cost 1, so the shortest path is the fewest moves. The search itself is best-first (an A* using an
 * admissible lower bound on the remaining moves: the horizontal Chebyshev-free Manhattan distance, or the
 * vertical distance divided by 1 climbing / {@link #MAX_DROP} dropping, whichever demands more), not a plain
 * queue-order breadth first search: each stand offers up to 20 next moves (4 directions times up to 5 height
 * options), and a plain FIFO search exhausts the {@value #NODE_CAP} node cap well before reaching even a
 * nearby target in a fully open window (P1 contract, section G.3). The result is unaffected: cost is uniform,
 * so this still finds an exact shortest path whenever one is found at all, it only reaches it in far fewer
 * expanded nodes. The node cap of {@value #NODE_CAP} bounds the nodes expanded (fully processed, not merely
 * discovered), a stale re-queued entry that turned out not to improve on an already-known distance does not
 * count as an expansion.
 *
 * <h2>Result</h2>
 * <ul>
 *   <li>REACHABLE with the number of moves ({@code length}) of a shortest path when {@code to} is reached;</li>
 *   <li>UNREACHABLE when the frontier empties within the cap without reaching {@code to}, or when
 *       {@code from} or {@code to} is not a valid stand (an observed wall or fluid in its own cells, or a floor
 *       that is observed AIR or FLUID);</li>
 *   <li>INCONCLUSIVE (length {@link #NO_LENGTH}) when the cap is hit first, or when {@code occupancy} is null or
 *       either endpoint is outside the window. The caller must treat INCONCLUSIVE as "not ruled out" and skip the
 *       path-length check.</li>
 * </ul>
 * {@code from == to} is REACHABLE with length 0 when the stand is valid.
 */
public final class ObservedReach {
    /** Design 4.3: most nodes the breadth first search expands. */
    public static final int NODE_CAP = 1500;
    /** Largest drop a move may take. */
    public static final int MAX_DROP = 3;
    /** {@link Result#length()} of a result that has no path length. */
    public static final int NO_LENGTH = -1;

    public enum Status {
        REACHABLE,
        UNREACHABLE,
        INCONCLUSIVE
    }

    /** @param length moves of a shortest path for REACHABLE, else {@link #NO_LENGTH} */
    public record Result(Status status, int length) {
        public static final Result UNREACHABLE = new Result(Status.UNREACHABLE, NO_LENGTH);
        public static final Result INCONCLUSIVE = new Result(Status.INCONCLUSIVE, NO_LENGTH);

        public static Result reachable(int length) {
            return new Result(Status.REACHABLE, length);
        }

        /** True unless the search proved the target cannot be reached. */
        public boolean notRuledOut() {
            return status != Status.UNREACHABLE;
        }
    }

    /** Horizontal neighbour offsets (dx, dz); order only affects which shortest path is found, not its length. */
    private static final int[][] HORIZONTAL = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private ObservedReach() {
    }

    /** Runs the search described in the class comment. Deterministic; allocation is bounded by {@link #NODE_CAP}. */
    public static Result search(ObservedOccupancy occupancy, BlockPos from, BlockPos to) {
        if (occupancy == null || from == null || to == null) {
            return Result.INCONCLUSIVE;
        }
        if (!occupancy.inWindow(from) || !occupancy.inWindow(to)) {
            return Result.INCONCLUSIVE;
        }
        if (!isValidStand(occupancy, from.getX(), from.getY(), from.getZ())
                || !isValidStand(occupancy, to.getX(), to.getY(), to.getZ())) {
            return Result.UNREACHABLE;
        }
        if (from.equals(to)) {
            return Result.reachable(0);
        }

        int tx = to.getX();
        int ty = to.getY();
        int tz = to.getZ();
        long targetKey = BlockPos.asLong(tx, ty, tz);

        Map<Long, Integer> bestG = new HashMap<>();
        long startKey = BlockPos.asLong(from.getX(), from.getY(), from.getZ());
        bestG.put(startKey, 0);

        PriorityQueue<Candidate> open = new PriorityQueue<>();
        open.add(new Candidate(heuristic(from.getX(), from.getY(), from.getZ(), tx, ty, tz), 0,
                from.getX(), from.getY(), from.getZ()));

        int expanded = 0;
        while (!open.isEmpty()) {
            if (expanded >= NODE_CAP) {
                return Result.INCONCLUSIVE;
            }
            Candidate cur = open.poll();
            long curKey = BlockPos.asLong(cur.x, cur.y, cur.z);
            Integer knownG = bestG.get(curKey);
            if (knownG != null && knownG < cur.g) {
                continue; // a stale entry superseded by a cheaper path found later; not an expansion
            }
            expanded++;
            if (curKey == targetKey) {
                return Result.reachable(cur.g);
            }
            int x = cur.x;
            int y = cur.y;
            int z = cur.z;
            for (int[] dir : HORIZONTAL) {
                int nx = x + dir[0];
                int nz = z + dir[1];

                tryPush(occupancy, bestG, open, nx, y, nz, cur.g, tx, ty, tz);

                if (passable(occupancy, x, y + 2, z)) {
                    tryPush(occupancy, bestG, open, nx, y + 1, nz, cur.g, tx, ty, tz);
                }

                for (int k = 1; k <= MAX_DROP; k++) {
                    if (!columnPassable(occupancy, nx, y - k + 1, y + 1, nz)) {
                        break; // the range only grows with k, so a block here blocks every deeper drop too
                    }
                    tryPush(occupancy, bestG, open, nx, y - k, nz, cur.g, tx, ty, tz);
                }
            }
        }
        return Result.UNREACHABLE;
    }

    /**
     * Validates the candidate stand at {@code (x, y, z)}; when valid and strictly closer than any previously
     * known path to it, records the new distance and pushes it, ranked by {@code g + heuristic}.
     */
    private static void tryPush(ObservedOccupancy occupancy, Map<Long, Integer> bestG, PriorityQueue<Candidate> open,
                                 int x, int y, int z, int curG, int tx, int ty, int tz) {
        if (!isValidStand(occupancy, x, y, z)) {
            return;
        }
        long key = BlockPos.asLong(x, y, z);
        int tentativeG = curG + 1;
        Integer known = bestG.get(key);
        if (known != null && known <= tentativeG) {
            return;
        }
        bestG.put(key, tentativeG);
        open.add(new Candidate(tentativeG + heuristic(x, y, z, tx, ty, tz), tentativeG, x, y, z));
    }

    /**
     * Admissible lower bound on the remaining moves from {@code (x,y,z)} to {@code (tx,ty,tz)}: at least the
     * horizontal Manhattan distance (every move changes exactly one horizontal coordinate by one), at least the
     * vertical rise divided by 1 (a move climbs at most one), and at least the vertical fall divided by
     * {@link #MAX_DROP} (a move drops at most {@value #MAX_DROP}); never an overestimate, so the search below
     * still finds an exact shortest path.
     */
    private static int heuristic(int x, int y, int z, int tx, int ty, int tz) {
        int horizontal = Math.abs(x - tx) + Math.abs(z - tz);
        int dy = ty - y;
        int vertical = dy > 0 ? dy : (dy < 0 ? ((-dy) + MAX_DROP - 1) / MAX_DROP : 0);
        return Math.max(horizontal, vertical);
    }

    /** A best-first search candidate: priority {@code f = g + heuristic}, ties broken deterministically. */
    private static final class Candidate implements Comparable<Candidate> {
        final int f;
        final int g;
        final int x;
        final int y;
        final int z;

        Candidate(int f, int g, int x, int y, int z) {
            this.f = f;
            this.g = g;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int compareTo(Candidate o) {
            if (f != o.f) {
                return Integer.compare(f, o.f);
            }
            if (g != o.g) {
                return Integer.compare(o.g, g); // prefer the candidate closer to the goal on a tie
            }
            if (y != o.y) {
                return Integer.compare(y, o.y);
            }
            if (z != o.z) {
                return Integer.compare(z, o.z);
            }
            return Integer.compare(x, o.x);
        }
    }

    /** A stand needs feet and head passable and floor SOLID/UNKNOWN (class comment). */
    private static boolean isValidStand(ObservedOccupancy occupancy, int x, int y, int z) {
        return passable(occupancy, x, y, z)
                && passable(occupancy, x, y + 1, z)
                && floor(occupancy, x, y - 1, z);
    }

    /** AIR or UNKNOWN. */
    private static boolean passable(ObservedOccupancy occupancy, int x, int y, int z) {
        int s = occupancy.get(x, y, z);
        return s == ObservedOccupancy.UNKNOWN || s == ObservedOccupancy.AIR;
    }

    /** SOLID or UNKNOWN. */
    private static boolean floor(ObservedOccupancy occupancy, int x, int y, int z) {
        int s = occupancy.get(x, y, z);
        return s == ObservedOccupancy.UNKNOWN || s == ObservedOccupancy.SOLID;
    }

    /** True iff every cell of the column at {@code (x, ?, z)} from {@code loY} to {@code hiY} (inclusive) is passable. */
    private static boolean columnPassable(ObservedOccupancy occupancy, int x, int loY, int hiY, int z) {
        for (int y = loY; y <= hiY; y++) {
            if (!passable(occupancy, x, y, z)) {
                return false;
            }
        }
        return true;
    }
}
