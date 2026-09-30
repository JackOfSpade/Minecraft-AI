package dev.spawnbotswrapper.inhabitants.combat;

import java.util.function.BooleanSupplier;

/**
 * "Has this player actually SEEN this bot?": the pure geometry behind an inhabitant's persistent SEEN flag. It uses the
 * player's own view: the bot's eye or its body centre lies inside the player's view cone (a generous half-angle, 70
 * degrees by default, so wide field-of-view settings are covered), it is within vanilla's line-of-sight range (128), it is
 * not invisible, and nothing blocks the view (the same eye-to-eye and eye-to-body rays as {@link Perception}'s
 * occlusion). The rays are the expensive part, so they are asked LAST, and only when range and cone already say yes.
 * <p>
 * No Minecraft classes here.
 */
public final class SeenGeometry {
    /** Vanilla's line-of-sight cap (hasLineOfSight) and PvP BOT's largest target distance. */
    public static final double MAX_RANGE = 128.0;

    private SeenGeometry() {
    }

    /**
     * @param eye          the player's eye position
     * @param look         the player's look direction (any length)
     * @param botEye       the bot's eye position
     * @param botBody      the bot's body centre
     * @param halfAngleDeg the view cone's half-angle
     * @param invisible    the bot is invisible (vanilla invisibility): never seen
     * @param clearView    asked last: nothing blocks the view (rays eye to eye, then eye to body)
     */
    public static boolean sees(double[] eye, double[] look, double[] botEye, double[] botBody, double halfAngleDeg,
                               boolean invisible, BooleanSupplier clearView) {
        if (invisible) {
            return false;
        }
        double dx = botEye[0] - eye[0];
        double dy = botEye[1] - eye[1];
        double dz = botEye[2] - eye[2];
        if (dx * dx + dy * dy + dz * dz > MAX_RANGE * MAX_RANGE) {
            return false;
        }
        if (!inCone(eye, look, botEye, halfAngleDeg) && !inCone(eye, look, botBody, halfAngleDeg)) {
            return false;
        }
        return clearView.getAsBoolean();
    }

    /** Whether {@code point} lies within {@code halfAngleDeg} of the look direction, seen from {@code eye}. */
    public static boolean inCone(double[] eye, double[] look, double[] point, double halfAngleDeg) {
        double dx = point[0] - eye[0];
        double dy = point[1] - eye[1];
        double dz = point[2] - eye[2];
        if (dx * dx + dy * dy + dz * dz < 1.0e-9) {
            return true; // standing inside each other
        }
        return Perception.angleDeg(look[0], look[1], look[2], dx, dy, dz) <= halfAngleDeg;
    }
}
