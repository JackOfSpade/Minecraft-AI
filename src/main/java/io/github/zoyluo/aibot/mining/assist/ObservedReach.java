package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

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
 * All moves cost 1. The search is a breadth first search from {@code from} with a node cap of
 * {@value #NODE_CAP} expanded nodes.
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

    private ObservedReach() {
    }

    /** Runs the search described in the class comment. Deterministic; allocation is bounded by {@link #NODE_CAP}. */
    public static Result search(ObservedOccupancy occupancy, BlockPos from, BlockPos to) {
        throw new UnsupportedOperationException("P1 stub: ObservedReach.search");
    }
}
