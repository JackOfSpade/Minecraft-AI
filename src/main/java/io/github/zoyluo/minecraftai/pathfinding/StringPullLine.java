package io.github.zoyluo.minecraftai.pathfinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact grid traversal of the straight line between two block columns (cell centre to cell centre):
 * every column the line's centre point crosses, in order.
 *
 * <p>{@link PathExecutor}'s string-pulling used to sample that line twice per block and test only the
 * sampled columns. A line whose sample points straddle a column boundary skips the column between them
 * entirely, so a shortcut across the corner of a water cell (or a void) passed the support test and the
 * bot's centre ended up over the fluid. This traversal visits every crossed column, so nothing can be
 * skipped, and it flags the exact lattice-point crossings (a diagonal step) separately so the caller can
 * require both corner columns to be open for the 0.6-wide body.
 */
final class StringPullLine {
    /**
     * One column entered by the line.
     *
     * @param x        column x
     * @param z        column z
     * @param fraction progress along the line (0..1) at which the column is entered
     * @param corner   true when the line entered it through an exact corner (both axes stepped at once),
     *                 so its two orthogonal neighbours were brushed but not entered
     * @param sideXz   for a corner step, the two brushed columns as {x1, z1, x2, z2}; otherwise null
     */
    record Cell(int x, int z, double fraction, boolean corner, int[] sideXz) {
    }

    private StringPullLine() {
    }

    /** @return the columns entered after {@code (fromX, fromZ)}, ending with {@code (toX, toZ)}. */
    static List<Cell> cells(int fromX, int fromZ, int toX, int toZ) {
        List<Cell> cells = new ArrayList<>();
        int dx = toX - fromX;
        int dz = toZ - fromZ;
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        int cx = fromX;
        int cz = fromZ;
        int crossedX = 0;
        int crossedZ = 0;
        while (cx != toX || cz != toZ) {
            // The k-th x boundary is crossed at (2k+1) / (2*ax) of the way (centre to centre); compare
            // it with the z boundary by cross-multiplying, all in integers, so ties are exact.
            boolean stepInX;
            boolean stepInZ;
            if (ax != 0 && az != 0) {
                long xTimes = (long) (2 * crossedX + 1) * az;
                long zTimes = (long) (2 * crossedZ + 1) * ax;
                stepInX = xTimes <= zTimes;
                stepInZ = zTimes <= xTimes;
            } else {
                stepInX = ax != 0;
                stepInZ = az != 0;
            }
            double fraction;
            if (stepInX) {
                fraction = (2.0D * crossedX + 1.0D) / (2.0D * ax);
            } else {
                fraction = (2.0D * crossedZ + 1.0D) / (2.0D * az);
            }
            int previousX = cx;
            int previousZ = cz;
            if (stepInX) {
                cx += stepX;
                crossedX++;
            }
            if (stepInZ) {
                cz += stepZ;
                crossedZ++;
            }
            boolean corner = stepInX && stepInZ;
            cells.add(new Cell(cx, cz, Math.min(1.0D, fraction), corner,
                    corner ? new int[]{cx, previousZ, previousX, cz} : null));
        }
        return cells;
    }
}
