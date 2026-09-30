package dev.spawnbotswrapper.inhabitants.combat;

/**
 * The pure geometry of "could a human client have targeted this entity with a melee swing", free of Minecraft types so
 * it is unit-testable. A human's crosshair ray starts at the eye; the entity is picked when the ray enters its bounding
 * box within the weapon's attack range and no block with a collision shape stands closer on that ray. The range itself
 * is vanilla's ({@code AttackRange}, per weapon: 3.0 blocks for a sword, up to 4.5 for a spear) and is decided in
 * {@code MeleeLegality} by calling vanilla; so is the block test, which needs the world. This class only answers where
 * a human could aim and how far along the ray the box is entered.
 * <p>
 * Aim points: a human can aim at any visible part of the box, not only its nearest point (a head above a fence is
 * hittable although the nearest point is behind it). {@link #aimPoints} gives the nearest point of the box to the eye
 * (the eye clamped into the box) plus the centre, a head point and a foot point of the box; a swing is legal when ANY of
 * them is reachable on a clear ray.
 * <p>
 * Tolerance: {@link #TOLERANCE} (0.2 blocks) is added to the range (as the tolerance argument of vanilla's
 * {@code AttackRange.isInRange}, and to the aim rays' length). The victim's position the server sees can be a tick or
 * two ahead of what a client's crosshair saw. Vanilla's own server-side check passes a tolerance of 3.0 blocks, an
 * anti-cheat slack for a pick the client already made, which would make this rule meaningless; the client's own pick
 * uses none, so only a small allowance for lag is kept.
 */
public final class MeleeGeometry {
    /** Blocks added to the range for server/client position lag; see the class comment. */
    public static final double TOLERANCE = 0.2;
    /** How far short of the box the block test stops, so the box's own surface is never counted as a block. */
    public static final double SURFACE_EPSILON = 0.01;

    private MeleeGeometry() {
    }

    /** An axis-aligned box. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box inflate(double x, double y, double z) {
            return new Box(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
        }

        public boolean intersects(Box o) {
            return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ;
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** The point of the box nearest to the eye: the eye clamped into the box. */
    public static double[] nearestPoint(double ex, double ey, double ez, Box b) {
        return new double[] {clamp(ex, b.minX, b.maxX), clamp(ey, b.minY, b.maxY), clamp(ez, b.minZ, b.maxZ)};
    }

    /** Distance from the eye to the box (0 when the eye is inside it). */
    public static double distanceToBox(double ex, double ey, double ez, Box b) {
        double[] p = nearestPoint(ex, ey, ez, b);
        return Math.sqrt(sq(p[0] - ex) + sq(p[1] - ey) + sq(p[2] - ez));
    }

    /** The points a human could aim at: nearest point, box centre, a point near the top and one near the bottom. */
    public static double[][] aimPoints(double ex, double ey, double ez, Box b) {
        double cx = (b.minX + b.maxX) / 2.0;
        double cz = (b.minZ + b.maxZ) / 2.0;
        double h = b.maxY - b.minY;
        return new double[][] {
                nearestPoint(ex, ey, ez, b),
                {cx, (b.minY + b.maxY) / 2.0, cz},
                {cx, b.maxY - 0.1 * h, cz},
                {cx, b.minY + 0.1 * h, cz}};
    }

    /**
     * Distance along the ray from the eye toward {@code (tx,ty,tz)} at which it first enters the box, 0 when the eye is
     * inside the box, or -1 when the ray (unbounded beyond the target) never enters it.
     */
    public static double entryDistance(double ex, double ey, double ez, double tx, double ty, double tz, Box b) {
        if (b.contains(ex, ey, ez)) {
            return 0.0;
        }
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-9) {
            return -1.0;
        }
        double tMin = 0.0;
        double tMax = Double.POSITIVE_INFINITY;
        double[] o = {ex, ey, ez};
        double[] d = {dx / len, dy / len, dz / len};
        double[] lo = {b.minX, b.minY, b.minZ};
        double[] hi = {b.maxX, b.maxY, b.maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return -1.0;
                }
                continue;
            }
            double t1 = (lo[i] - o[i]) / d[i];
            double t2 = (hi[i] - o[i]) / d[i];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) {
                return -1.0;
            }
        }
        return tMin;
    }

    /** Whether a hit at this distance from the eye is inside the range plus the tolerance. */
    public static boolean withinReach(double distance, double reach) {
        return distance >= 0.0 && distance <= reach + TOLERANCE;
    }

    /**
     * A vanilla sweeping-edge victim: within one block (horizontally, a quarter block vertically) of the box of the
     * primary victim and closer than 3 blocks (squared distance below 9) to the attacker. Vanilla damages these without
     * any line-of-sight test of their own, so a swing that legally hit the primary victim may legally sweep them.
     */
    public static boolean isSweepVictim(Box primary, Box candidate, double distanceSqToAttacker) {
        return primary.inflate(1.0, 0.25, 1.0).intersects(candidate) && distanceSqToAttacker < 9.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double sq(double v) {
        return v * v;
    }
}
