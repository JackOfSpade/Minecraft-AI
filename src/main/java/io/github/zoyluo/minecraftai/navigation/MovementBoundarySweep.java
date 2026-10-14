package io.github.zoyluo.minecraftai.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Conservative physical guard for a route's discrete feet boundary.
 *
 * <p>Vanilla resolves an entity move one axis at a time when collision or step-up is involved.
 * A mathematical diagonal between two cells inside a circular site can therefore still visit an
 * outside tangent cell while one horizontal component is applied before the other. The sweep
 * deliberately checks the whole axis-order supercover of a proposed displacement, not merely
 * the ideal line segment.</p>
 */
public final class MovementBoundarySweep {
    private static final int MAX_CELLS = 4_096;
    private static final int BINARY_SEARCH_STEPS = 56;

    private MovementBoundarySweep() {
    }

    /** The largest safe prefix of one proposed physical move, plus one denied cell from its supercover. */
    public record Result(Vec3 permittedDelta, boolean refused, BlockPos deniedCell) {
        public Result {
            permittedDelta = permittedDelta == null ? Vec3.ZERO : permittedDelta;
        }
    }

    /**
     * Returns the requested move unchanged when every feet cell its collision-axis supercover
     * can occupy is legal. Otherwise returns a prefix ending strictly before an unsafe boundary
     * cell. The reported denied cell comes from a conservative supercover, rather than claiming
     * the physical axis-resolution order. Invalid coordinates and implausibly large sweeps fail
     * closed.
     */
    public static Result clip(NavRoute.MovementBoundary boundary, Vec3 origin, Vec3 requestedDelta) {
        if (boundary == null) {
            return new Result(requestedDelta, false, null);
        }
        if (!isFinite(origin) || !isFinite(requestedDelta)) {
            return new Result(Vec3.ZERO, true, null);
        }
        SweepCheck start = check(boundary, origin, Vec3.ZERO);
        if (!start.allowed()) {
            return new Result(Vec3.ZERO, true, start.deniedCell());
        }
        SweepCheck whole = check(boundary, origin, requestedDelta);
        if (whole.allowed()) {
            return new Result(requestedDelta, false, null);
        }

        // Cell coverage only grows as a prefix gets longer, so this safely finds the last
        // permitted displacement without relying on the exact order vanilla resolves axes.
        double allowed = 0.0D;
        double denied = 1.0D;
        for (int step = 0; step < BINARY_SEARCH_STEPS; step++) {
            double midpoint = (allowed + denied) * 0.5D;
            if (check(boundary, origin, requestedDelta.scale(midpoint)).allowed()) {
                allowed = midpoint;
            } else {
                denied = midpoint;
            }
        }
        // Do not leave an exact block-edge rounding decision to Entity.move.
        double safePrefix = Math.max(0.0D, Math.nextDown(allowed));
        Vec3 permitted = requestedDelta.scale(safePrefix);
        if (!check(boundary, origin, permitted).allowed()) {
            permitted = Vec3.ZERO;
        }
        return new Result(permitted, true, whole.deniedCell());
    }

    /**
     * Replaces only a downward component that vanilla's own collision calculation has already
     * proven will be shortened. Looking at {@code onGround} alone is not sufficient: it is the
     * previous tick's state and a horizontal move can walk a body off its current support.
     */
    public static Vec3 collisionAwareDelta(Vec3 requestedDelta, Vec3 collisionResolvedDelta) {
        if (!isFinite(requestedDelta) || !isFinite(collisionResolvedDelta)
                || requestedDelta.y >= 0.0D || collisionResolvedDelta.y <= requestedDelta.y) {
            return requestedDelta;
        }
        return new Vec3(requestedDelta.x, collisionResolvedDelta.y, requestedDelta.z);
    }

    /**
     * Runs the same initial collision query that {@code Entity.move} uses before deciding a
     * physical boundary. This is intentionally only a prediction: the caller still delegates to
     * vanilla for the actual movement, step-up, and callbacks.
     */
    public static Vec3 collisionResolvedDelta(Entity entity, Vec3 requestedDelta) {
        if (entity == null || entity.noPhysics || !isFinite(requestedDelta) || requestedDelta.y >= 0.0D) {
            return requestedDelta;
        }
        return Entity.collideBoundingBox(entity, requestedDelta, entity.getBoundingBox(), entity.level(),
                entity.level().getEntityCollisions(entity, entity.getBoundingBox().expandTowards(requestedDelta)));
    }

    /**
     * Whether collision fully stops a small downward probe at the body's current footprint.
     * A partial descent is not support for a minimum-feet boundary: it could still enter the
     * lower block cell on the next physical move.
     */
    public static boolean hasCollisionSupport(Entity entity) {
        Vec3 probe = new Vec3(0.0D, -0.08D, 0.0D);
        Vec3 resolved = collisionResolvedDelta(entity, probe);
        return isFinite(resolved) && resolved.y >= 0.0D;
    }

    private static SweepCheck check(NavRoute.MovementBoundary boundary, Vec3 origin, Vec3 delta) {
        CellRange range = CellRange.of(origin, origin.add(delta));
        if (range == null || range.cellCount() > MAX_CELLS) {
            return SweepCheck.denied(null);
        }
        for (long y = range.minY(); y <= range.maxY(); y++) {
            for (long x = range.minX(); x <= range.maxX(); x++) {
                for (long z = range.minZ(); z <= range.maxZ(); z++) {
                    BlockPos cell = new BlockPos((int) x, (int) y, (int) z);
                    if (!boundary.allowsFeet(cell)) {
                        return SweepCheck.denied(cell);
                    }
                }
            }
        }
        return SweepCheck.permitted();
    }

    private static boolean isFinite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private record SweepCheck(boolean allowed, BlockPos deniedCell) {
        static SweepCheck permitted() {
            return new SweepCheck(true, null);
        }

        static SweepCheck denied(BlockPos cell) {
            return new SweepCheck(false, cell);
        }
    }

    private record CellRange(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        static CellRange of(Vec3 from, Vec3 to) {
            Integer fromX = floor(from.x);
            Integer fromY = floor(from.y);
            Integer fromZ = floor(from.z);
            Integer toX = floor(to.x);
            Integer toY = floor(to.y);
            Integer toZ = floor(to.z);
            if (fromX == null || fromY == null || fromZ == null
                    || toX == null || toY == null || toZ == null) {
                return null;
            }
            return new CellRange(Math.min(fromX, toX), Math.max(fromX, toX),
                    Math.min(fromY, toY), Math.max(fromY, toY),
                    Math.min(fromZ, toZ), Math.max(fromZ, toZ));
        }

        long cellCount() {
            long x = (long) maxX - minX + 1L;
            long y = (long) maxY - minY + 1L;
            long z = (long) maxZ - minZ + 1L;
            if (x > MAX_CELLS || y > MAX_CELLS || z > MAX_CELLS) {
                return Long.MAX_VALUE;
            }
            long xy = x * y;
            return xy > MAX_CELLS / z ? Long.MAX_VALUE : xy * z;
        }

        private static Integer floor(double coordinate) {
            double floored = Math.floor(coordinate);
            return floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE ? null : (int) floored;
        }
    }
}
