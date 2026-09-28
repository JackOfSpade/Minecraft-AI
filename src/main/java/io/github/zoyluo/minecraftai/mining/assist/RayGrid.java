package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Objects;

/**
 * Amanatides-Woo voxel traversal: every block cell a view ray passes through, in order, and the
 * helper that folds one first-hit ray into an {@link ObservedOccupancy}.
 *
 * <p><b>Cells and order.</b> The first cell is {@code floor(origin)} on each axis, whatever the
 * direction. Each following cell shares a face with the previous one (one axis steps by one); when a
 * ray crosses an edge or corner exactly, the axes step in x, y, z order, so one cell that is only
 * touched along the edge is visited on the way. No cell is visited twice. Crossing parameters are
 * recomputed from the origin at every step, so error does not accumulate over long rays.</p>
 *
 * <p><b>Range and boundaries.</b> Distances are ray parameters: blocks, because a direction whose
 * squared length is not within {@value #UNIT_TOLERANCE} of 1 is normalised first (a zero direction
 * visits only the start cell). The tolerance is tight on purpose: the skew it can leave in a distance
 * over {@link #MAX_RANGE} stays far below {@link #END_EPS}, so a Euclidean hit distance measured by
 * the caller identifies the same cell here. {@link #traverse} enters a cell only if the ray crosses
 * into it before {@code range} by more than {@link #END_EPS}: a cell the segment merely touches at
 * its end point is not part of it. {@link #mark} with a hit uses the opposite tie-break, "a boundary
 * point belongs to the cell being entered", so a hit at a solid block's face lands in the block and
 * not in the air before it; hit distances come from vanilla in double precision, and
 * {@link #END_EPS} absorbs the rounding. Ranges above {@link #MAX_RANGE}, and traversals longer than
 * {@link #MAX_CELLS} cells, are cut short. Negative, NaN or infinite inputs, and origins beyond
 * &plusmn;{@value #MAX_ORIGIN}, visit nothing. Null arguments (visitor, occupancy, hit kind) are
 * programmer errors and throw {@link NullPointerException}.</p>
 *
 * <p>All methods are static and allocation-free (apart from what a supplied visitor does).</p>
 */
public final class RayGrid {
    /** Longest traversed distance in blocks; a longer request is clamped. */
    public static final double MAX_RANGE = 256.0D;
    /** Tolerance, in blocks, for "the ray ends exactly on a cell boundary". */
    public static final double END_EPS = 1.0E-9D;
    /** Defensive cap on cells per traversal; a unit ray of {@link #MAX_RANGE} needs at most about 450. */
    public static final int MAX_CELLS = 1024;
    /** Origins farther than this from zero on an axis are rejected. */
    public static final double MAX_ORIGIN = 1.0E9D;
    /**
     * A direction is used as is when its squared length is within this of 1, otherwise it is normalised.
     * Half of it bounds the relative length error, i.e. about 1.3E-12 blocks over {@link #MAX_RANGE}.
     */
    public static final double UNIT_TOLERANCE = 1.0E-14D;

    /** How a ray ended, for {@link #mark}. */
    public enum HitKind {
        /** Reached its clamped range without hitting anything: every cell up to the range is AIR. */
        MISS,
        /** First hit a solid block: the cell containing the hit point is SOLID. */
        SOLID,
        /** First hit a fluid: the cell containing the hit point is FLUID. */
        FLUID
    }

    /** Receives traversed cells in ray order. */
    @FunctionalInterface
    public interface CellVisitor {
        /** @return true to keep going, false to stop the traversal (this cell still counts as visited) */
        boolean visit(int x, int y, int z);
    }

    private RayGrid() {
    }

    /**
     * Visits, in order, every cell the segment {@code origin + t * dir, t in [0, range]} passes
     * through, starting with the cell containing the origin.
     *
     * @return the number of cells visited
     * @throws NullPointerException if {@code visitor} is null
     */
    public static int traverse(double ox, double oy, double oz,
                               double dx, double dy, double dz,
                               double range, CellVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        return walk(ox, oy, oz, dx, dy, dz, range, false, visitor, null, ObservedOccupancy.AIR);
    }

    /**
     * Folds one view ray into {@code occ}. {@code distance} is where the ray ended, measured from the
     * origin along the (unit) direction: the hit distance for SOLID or FLUID, the clamped range for
     * MISS.
     *
     * <ul>
     *   <li>SOLID / FLUID: every cell before the one containing the point at {@code distance} is
     *       marked AIR and that cell is marked SOLID / FLUID. A point on a cell boundary belongs to
     *       the cell being entered. A hit distance above {@link #MAX_RANGE} cannot be trusted to
     *       identify a cell, so it is treated as a MISS to the clamp and marks no SOLID/FLUID.</li>
     *   <li>MISS: every cell the ray passes through up to {@code distance} is marked AIR, including the
     *       one containing the end point. A cell touched only exactly at the end point is not marked.</li>
     * </ul>
     *
     * <p>Later marks overwrite earlier ones. Cells outside the window are skipped, and the walk stops
     * once the ray has left the window, since the terminal cell can then no longer be inside it.</p>
     *
     * <p>The terminal cell comes from the point at {@code distance}, not from the block the caller's
     * raycast reported. They agree except for colliders taller than their cell (fences, walls and fence
     * gates are 1.5 high): a ray from above meets the collider's top inside the cell above the block, so
     * that cell is marked here and the block's own cell stays untouched. A caller that wants the block
     * cell as well follows up with {@code occ.markSolid(hitPos)}.</p>
     *
     * @return the number of cells written into the window
     * @throws NullPointerException if {@code occ} or {@code hit} is null
     */
    public static int mark(ObservedOccupancy occ,
                           double ox, double oy, double oz,
                           double dx, double dy, double dz,
                           double distance, HitKind hit) {
        Objects.requireNonNull(occ, "occ");
        Objects.requireNonNull(hit, "hit");
        if (hit == HitKind.MISS || distance > MAX_RANGE) {
            return walk(ox, oy, oz, dx, dy, dz, distance, false, null, occ, ObservedOccupancy.AIR);
        }
        int terminal = hit == HitKind.FLUID ? ObservedOccupancy.FLUID : ObservedOccupancy.SOLID;
        return walk(ox, oy, oz, dx, dy, dz, distance, true, null, occ, terminal);
    }

    /**
     * The single DDA loop. With {@code occ} set it writes AIR for every cell but the last and
     * {@code terminalState} for the last (counting cells written); with {@code visitor} set it reports
     * every cell (counting cells visited).
     *
     * @param includeEnd true to enter a cell whose crossing lies within {@link #END_EPS} beyond
     *                   {@code range} (hit semantics), false to require it strictly before the end
     */
    private static int walk(double ox, double oy, double oz,
                            double dx, double dy, double dz,
                            double range, boolean includeEnd,
                            CellVisitor visitor, ObservedOccupancy occ, int terminalState) {
        if (!(range >= 0.0D) || !Double.isFinite(range)
                || !(Math.abs(ox) <= MAX_ORIGIN) || !(Math.abs(oy) <= MAX_ORIGIN) || !(Math.abs(oz) <= MAX_ORIGIN)
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return 0;
        }
        if (range > MAX_RANGE) {
            range = MAX_RANGE;
        }
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared < 1.0E-18D || !Double.isFinite(lengthSquared)) {
            dx = 0.0D;
            dy = 0.0D;
            dz = 0.0D;
        } else if (Math.abs(lengthSquared - 1.0D) > UNIT_TOLERANCE) {
            double inverse = 1.0D / Math.sqrt(lengthSquared);
            dx *= inverse;
            dy *= inverse;
            dz *= inverse;
        }

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        int stepX = dx > 0.0D ? 1 : dx < 0.0D ? -1 : 0;
        int stepY = dy > 0.0D ? 1 : dy < 0.0D ? -1 : 0;
        int stepZ = dz > 0.0D ? 1 : dz < 0.0D ? -1 : 0;
        double tMaxX = crossing(ox, dx, x, stepX);
        double tMaxY = crossing(oy, dy, y, stepY);
        double tMaxZ = crossing(oz, dz, z, stepZ);

        double limit = includeEnd ? range + END_EPS : range - END_EPS;
        int counted = 0;
        int visited = 0;
        boolean wasInside = false;
        while (true) {
            int axis;
            double tNext;
            if (tMaxX <= tMaxY) {
                if (tMaxX <= tMaxZ) {
                    axis = 0;
                    tNext = tMaxX;
                } else {
                    axis = 2;
                    tNext = tMaxZ;
                }
            } else if (tMaxY <= tMaxZ) {
                axis = 1;
                tNext = tMaxY;
            } else {
                axis = 2;
                tNext = tMaxZ;
            }
            boolean reachedEnd = includeEnd ? tNext > limit : tNext >= limit;
            boolean last = reachedEnd || visited + 1 >= MAX_CELLS;

            visited++;
            if (occ != null) {
                // a cell that is last only because of the safety cap is not where the ray ended
                if (occ.set(x, y, z, reachedEnd ? terminalState : ObservedOccupancy.AIR)) {
                    counted++;
                    wasInside = true;
                } else if (wasInside) {
                    return counted;
                }
            }
            if (visitor != null) {
                counted++;
                if (!visitor.visit(x, y, z)) {
                    return counted;
                }
            }
            if (last) {
                return counted;
            }

            if (axis == 0) {
                x += stepX;
                tMaxX = crossing(ox, dx, x, stepX);
            } else if (axis == 1) {
                y += stepY;
                tMaxY = crossing(oy, dy, y, stepY);
            } else {
                z += stepZ;
                tMaxZ = crossing(oz, dz, z, stepZ);
            }
        }
    }

    /** Ray parameter at which the ray leaves cell {@code cell} along one axis (infinity if it does not move on it). */
    private static double crossing(double origin, double direction, int cell, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? cell + 1.0D : (double) cell;
        return (boundary - origin) / direction;
    }
}
