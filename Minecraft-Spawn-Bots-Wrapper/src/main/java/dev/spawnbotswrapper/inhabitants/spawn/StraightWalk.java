package dev.spawnbotswrapper.inhabitants.spawn;

/**
 * Answers "can a PvP BOT follower walk from A to B?" the way PvP BOT actually moves: in a STRAIGHT LINE,
 * with no pathfinding at all. All it can do besides walking is hop up one block and side-step a wall, and
 * it has no idea about lava, fire, cactus or voids. So a segment is walkable only when the line itself is
 * safe ground the whole way; going around anything is impossible and never assumed.
 * <p>
 * The line is sampled at equal parts of at most {@value #SAMPLE_STEP} blocks. That is finer than the
 * 0.5-block spacing the rule needs: any spacing under the 0.6-block body width leaves no gap a thin
 * obstacle could hide in. Only the columns the samples fall in are examined. The rule at each sample:
 * <ul>
 *   <li>the floor is {@link Cell#SOLID_STANDABLE} and the two blocks above it are {@link Cell#EMPTY}
 *       (water, hazards and any other solid on the line make the segment unwalkable);</li>
 *   <li>between neighbouring samples the feet level changes by at most +1 (a hop) or -2 (a short drop);</li>
 *   <li>an unreadable block ({@link Cell#UNLOADED}) is never assumed to be fine.</li>
 * </ul>
 * Two refinements make it stricter than that rule, because the bot is a 0.6 x 1.8 body and not a point:
 * a hop needs three blocks of clearance in the column it jumps from, and a diagonal move must not squeeze
 * between two solid corners (both orthogonal neighbours must be free at the level it passes at).
 * <p>
 * The start column is assumed to be a valid standing position (the caller verified it); the walk returns
 * the feet level at the end, so the caller can insist it equals the level it planned for.
 */
final class StraightWalk {

    static final int NOT_WALKABLE = Integer.MIN_VALUE;
    static final double SAMPLE_STEP = 0.25;
    static final int MAX_STEP_UP = 1;
    static final int MAX_DROP = 2;
    /** Longer lines are refused outright: no planner asks for one, and the sample count must not overflow. */
    static final double MAX_SEGMENT_LENGTH = 1024;

    private final ProbeView view;

    StraightWalk(ProbeView view) {
        this.view = view;
    }

    /**
     * Walks the line from (x0, z0) at feet level {@code y0} to (x1, z1).
     *
     * @return the feet level reached at (x1, z1), or {@link #NOT_WALKABLE}
     */
    int walk(double x0, double z0, int y0, double x1, double z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        if (!(Math.sqrt(dx * dx + dz * dz) <= MAX_SEGMENT_LENGTH)) {
            return NOT_WALKABLE;
        }
        int samples = sampleCount(dx, dz);
        int cx = floor(x0);
        int cz = floor(z0);
        int y = y0;
        for (int i = 1; i <= samples; i++) {
            boolean last = i == samples;
            double x = last ? x1 : x0 + dx * ((double) i / samples);
            double z = last ? z1 : z0 + dz * ((double) i / samples);
            int nx = last ? floor(x) : column(x, dx);
            int nz = last ? floor(z) : column(z, dz);
            if (nx == cx && nz == cz) {
                continue;
            }
            y = step(cx, cz, y, nx, nz);
            if (y == NOT_WALKABLE) {
                return NOT_WALKABLE;
            }
            cx = nx;
            cz = nz;
        }
        return y;
    }

    /** Number of equal parts the segment is cut into, each at most {@link #SAMPLE_STEP} long; always even. */
    static int sampleCount(double dx, double dz) {
        double length = Math.sqrt(dx * dx + dz * dz);
        return length == 0 ? 0 : 2 * (int) Math.ceil(length / (2 * SAMPLE_STEP));
    }

    /**
     * One move from column A (feet level {@code y}) into the adjacent column B, in an orthogonal or
     * diagonal direction.
     *
     * @return the feet level in B, or {@link #NOT_WALKABLE}
     */
    int step(int ax, int az, int y, int bx, int bz) {
        if (!view.insideBorder(bx, bz)) {
            return NOT_WALKABLE;
        }
        Cell atFeet = view.cell(bx, y, bz);
        Cell atHead = view.cell(bx, y + 1, bz);
        int landed;
        if (atFeet == Cell.EMPTY && atHead == Cell.EMPTY) {
            landed = descend(bx, y, bz);
        } else if (atFeet == Cell.SOLID_STANDABLE && atHead == Cell.EMPTY
                && view.cell(bx, y + 2, bz) == Cell.EMPTY && view.cell(ax, y + 2, az) == Cell.EMPTY) {
            landed = y + MAX_STEP_UP;
        } else {
            return NOT_WALKABLE;
        }
        if (landed == NOT_WALKABLE || landed > view.maxY() - 2 || landed < view.minY() + 1) {
            return NOT_WALKABLE;
        }
        if (bx != ax && bz != az && !cornersClear(ax, az, bx, bz, Math.max(y, landed))) {
            return NOT_WALKABLE;
        }
        return landed;
    }

    /** Falls through empty blocks until a safe floor; at most {@link #MAX_DROP} blocks of fall. */
    private int descend(int x, int y, int z) {
        for (int drop = 0; drop <= MAX_DROP; drop++) {
            Cell below = view.cell(x, y - drop - 1, z);
            if (below == Cell.SOLID_STANDABLE) {
                return y - drop;
            }
            if (below != Cell.EMPTY) {
                return NOT_WALKABLE;
            }
        }
        return NOT_WALKABLE;
    }

    private boolean cornersClear(int ax, int az, int bx, int bz, int level) {
        return passable(bx, level, az) && passable(ax, level, bz);
    }

    private boolean passable(int x, int y, int z) {
        return view.cell(x, y, z) == Cell.EMPTY && view.cell(x, y + 1, z) == Cell.EMPTY;
    }

    /**
     * The column a sample belongs to. A sample exactly on a block boundary (a centre-to-centre diagonal
     * passes exactly through block corners) belongs to the block the walk is ENTERING. Plain flooring would
     * decide by floating-point noise and by travel direction, so the same segment walked forwards and
     * backwards could touch different columns and disagree about being walkable, which a ping-pong must not.
     */
    private static int column(double v, double travel) {
        double margin = Math.max(1e-9, 16 * Math.ulp(v));
        return (int) Math.floor(v + Math.signum(travel) * margin);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
