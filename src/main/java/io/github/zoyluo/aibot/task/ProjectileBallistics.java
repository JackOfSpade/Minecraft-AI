package io.github.zoyluo.aibot.task;

/**
 * Solves for the launch pitch of a vanilla arrow so it actually reaches a target's height instead
 * of the naive "look straight at it" pitch, which undershoots at any real range because arrows are
 * genuinely affected by gravity and drag every tick they're airborne (Minecraft Wiki, Arrow &gt;
 * Behavior &gt; Movement):
 * <pre>
 *   position += velocity
 *   velocity *= DRAG        (0.99 in air)
 *   velocity.y -= GRAVITY   (0.05)
 * </pre>
 * That recurrence has a closed form (same source) for position at tick {@code t} given initial
 * velocity {@code v0}, using {@code k = GRAVITY / (1 - DRAG) = 5}:
 * <pre>
 *   horizontal(t) = 100 * (1 - DRAG^t) * v0.horizontal
 *   vertical(t)   = 100 * (1 - DRAG^t) * (v0.vertical + k) - k * t
 * </pre>
 * Fixing a candidate pitch fixes {@code v0}, and the horizontal equation can be solved for the
 * exact tick {@code t} at which the arrow crosses the target's horizontal distance ({@code t} is
 * only used analytically here, never simulated tick by tick). Substituting that {@code t} into the
 * vertical equation gives the arrow's height at that horizontal distance for that pitch; this class
 * bisects over pitch until that height matches the target's.
 */
public final class ProjectileBallistics {
    private ProjectileBallistics() {
    }

    /** Vanilla arrow gravity, blocks/tick^2, subtracted from vertical velocity every tick. */
    public static final double GRAVITY = 0.05;
    /** Vanilla arrow drag, the fraction of velocity kept every tick while airborne in air. */
    public static final double DRAG = 0.99;
    /** {@code GRAVITY / (1 - DRAG)}: the shift the closed-form position/velocity equations use. */
    private static final double GRAVITY_SHIFT = GRAVITY / (1.0 - DRAG);
    /** Launch speed of an arrow fired from a fully-drawn bow, blocks/tick. */
    public static final double FULL_DRAW_ARROW_SPEED = 3.0;

    private static final double MAX_PITCH_DEGREES = 85.0;
    private static final double SEARCH_HALF_WIDTH_DEGREES = 35.0;
    private static final int BISECTION_ITERATIONS = 60;

    /**
     * The pitch (Minecraft convention: negative looks up, positive looks down, degrees) that lands
     * a shot fired at {@code speed} on a target {@code horizontalDistance} blocks away (must be
     * positive) and {@code verticalOffset} blocks above the shooter's eyes (negative if below).
     * <p>
     * Falls back to the naive straight-line pitch (equivalent to the old {@link CombatCore#lookAt}
     * behaviour) if no pitch within {@link #SEARCH_HALF_WIDTH_DEGREES} of it reaches that distance
     * at all -- this never refuses to return a usable angle.
     */
    public static double pitchForShot(double horizontalDistance, double verticalOffset, double speed) {
        double naivePitch = straightLinePitch(horizontalDistance, verticalOffset);
        if (horizontalDistance <= 1.0e-6 || speed <= 1.0e-6) {
            return naivePitch;
        }
        double lo = clampPitch(naivePitch - SEARCH_HALF_WIDTH_DEGREES);
        double hi = clampPitch(naivePitch + SEARCH_HALF_WIDTH_DEGREES);
        double fLo = heightErrorAtPitch(lo, horizontalDistance, verticalOffset, speed);
        double fHi = heightErrorAtPitch(hi, horizontalDistance, verticalOffset, speed);
        if (!oppositeSigns(fLo, fHi)) {
            return naivePitch;
        }
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            double mid = (lo + hi) / 2.0;
            double fMid = heightErrorAtPitch(mid, horizontalDistance, verticalOffset, speed);
            if (oppositeSigns(fLo, fMid)) {
                hi = mid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return (lo + hi) / 2.0;
    }

    /** The pitch a plain "look straight at the target" aim would use; the thing this improves on. */
    static double straightLinePitch(double horizontalDistance, double verticalOffset) {
        return Math.toDegrees(-Math.atan2(verticalOffset, Math.max(horizontalDistance, 1.0e-6)));
    }

    /**
     * Height (blocks above the shooter's eyes) of an arrow launched at {@code pitchDegrees}, at the
     * exact tick it crosses {@code horizontalDistance}, minus {@code verticalOffset} -- the root of
     * this function over pitch is the answer {@link #pitchForShot} is looking for. Returns +infinity
     * for a pitch that never reaches {@code horizontalDistance} at all (drag brings the horizontal
     * speed to zero before it gets there), which pushes the bisection away from it cleanly.
     */
    private static double heightErrorAtPitch(double pitchDegrees, double horizontalDistance,
                                              double verticalOffset, double speed) {
        double upAngle = Math.toRadians(-pitchDegrees);
        double vx0 = speed * Math.cos(upAngle);
        double vy0 = speed * Math.sin(upAngle);
        if (vx0 <= 1.0e-9) {
            return Double.POSITIVE_INFINITY;
        }
        // horizontal(t) = 100 * (1 - DRAG^t) * vx0 = horizontalDistance  =>  solve for (1 - DRAG^t)
        double oneMinusDragToT = horizontalDistance / (100.0 * vx0);
        if (oneMinusDragToT >= 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        double ticks = Math.log(1.0 - oneMinusDragToT) / Math.log(DRAG);
        double height = 100.0 * oneMinusDragToT * (vy0 + GRAVITY_SHIFT) - GRAVITY_SHIFT * ticks;
        return height - verticalOffset;
    }

    private static boolean oppositeSigns(double a, double b) {
        if (Double.isInfinite(a) || Double.isInfinite(b)) {
            return (a > 0) != (b > 0);
        }
        return (a <= 0) != (b <= 0);
    }

    private static double clampPitch(double pitchDegrees) {
        return Math.max(-MAX_PITCH_DEGREES, Math.min(MAX_PITCH_DEGREES, pitchDegrees));
    }
}
