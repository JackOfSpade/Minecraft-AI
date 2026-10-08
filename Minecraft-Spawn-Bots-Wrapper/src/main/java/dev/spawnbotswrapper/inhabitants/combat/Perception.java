package dev.spawnbotswrapper.inhabitants.combat;

import java.util.function.BooleanSupplier;

/**
 * Realistic noticing: the ONE pure decision "does observer O notice subject S, and how long must it be exposed first",
 * shared (as the same function, checked by the same golden vectors in {@code docs/perception/vectors.json}) with the
 * Minecraft-AI mod. See {@code docs/PERCEPTION.md}. Sight has NO distance limit inside the view cone; seeing takes
 * TIME, one continuous formula in real seconds (no steps, no rounding):
 * <pre>
 *   requiredSeconds = (base + (at64 - base) * distance / 64) * angleFactor(theta) * sneakFactor / visibility
 * </pre>
 * <ul>
 *   <li><b>distance</b> is eye to eye, in blocks, continuous: 0.5 s up close, 2.0 s at 32 blocks by default, every distance
 *       its own number. The 32 is {@link #ENGAGE_LIMIT}, the only hard-coded distance rule (a bot may still SEE farther, but
 *       never engages beyond it; that decision is the caller's).</li>
 *   <li><b>angleFactor</b>: 1 up to {@code fullAttentionHalfAngleDeg} (30), rising linearly to {@code peripheralMultiplier}
 *       (2) at {@code peripheralHalfAngleDeg} (100); beyond that the subject is not seen at all.</li>
 *   <li><b>sneakFactor</b> {@code sneakMultiplier} (2) while the subject sneaks; <b>visibility</b> is vanilla
 *       {@code getVisibilityPercent} with its own sneak factor divided out (invisibility, mob heads), 0 = never.</li>
 *   <li><b>Hearing</b> is an INPUT, not computed here: the caller runs vanilla's vibration system (the sculk sensor and
 *       Warden logic) and says whether the subject was heard (a sound at its position; vanilla decides radius, sneaking,
 *       wool). A subject that is heard AND has a clear line counts with angleFactor 1 (the observer turned to the sound), so
 *       hearing only removes the view-cone requirement; it restricts nothing. A sound without a clear line is a place to
 *       investigate (the caller's business), never a notice.</li>
 *   <li><b>Occlusion</b> is asked LAST (a callback), only when angle and hearing already allow sensing: the caller casts its
 *       rays lazily, at most once.</li>
 * </ul>
 * Exposure accumulates in real seconds ({@link #TICK_SECONDS} per tick, continuous while sighted; see
 * {@link ExposureTracker}); the notice happens on the first tick whose exposure reaches the required seconds
 * ({@link #noticed}). {@code enabled=false} is exactly vanilla {@code hasLineOfSight}: a clear line is a notice at once, no
 * cone, no sneaking, no time. No Minecraft classes here.
 */
public final class Perception {
    private Perception() {
    }

    /**
     * The only hard-coded distance rule: a bot never engages (acquires, chases, pursues) a target it measures farther than
     * this many blocks. Sight itself has no such limit; the reaction-time formula still reaches its legacy-named {@code at64}
     * value at 64 blocks, independently of this engagement cap.
     */
    public static final double ENGAGE_LIMIT = 32.0;

    /** One server tick in seconds: the granularity of exposure. */
    public static final double TICK_SECONDS = 0.05;

    /** What an observer made of a subject this tick. */
    public enum Sense {
        NONE,
        /** In view (front cone or periphery) with a clear line. */
        SIGHT,
        /** Heard, and a clear line: the observer turned to the sound (angle factor 1). */
        HEARING
    }

    /** The tunables (the {@code aggro.perception} config block of the wrapper). */
    public record Params(boolean enabled, double reactionBaseSeconds, double reactionAt64Seconds,
                         double fullAttentionHalfAngleDeg, double peripheralHalfAngleDeg, double peripheralMultiplier,
                         double sneakMultiplier) {
        public static Params defaults() {
            return new Params(true, 0.5, 2.0, 30.0, 100.0, 2.0, 2.0);
        }

        /** The same tunables with perception switched off: exactly vanilla hasLineOfSight (see the class comment). */
        public Params disabled() {
            return new Params(false, reactionBaseSeconds, reactionAt64Seconds, fullAttentionHalfAngleDeg,
                    peripheralHalfAngleDeg, peripheralMultiplier, sneakMultiplier);
        }
    }

    /** "Never": the required exposure of something that cannot be sighted at all (behind, fully invisible). */
    public static final double NEVER = Double.POSITIVE_INFINITY;

    /**
     * What the subject looks like this tick, as far as noticing goes.
     *
     * @param sneaking   crouching (shift key or crouching pose)
     * @param visibility invisibility and disguise factor (1.0 = plain): the vanilla getVisibilityPercent with the sneak
     *                   factor divided out
     */
    public record Subject(boolean sneaking, double visibility) {
        /** A player or bot in the given stance, fully visible. */
        public static Subject player(boolean sneaking) {
            return new Subject(sneaking, 1.0);
        }
    }

    /**
     * What the observer made of the subject this tick.
     *
     * @param sense           how it was sensed (or not)
     * @param requiredSeconds for {@link Sense#SIGHT} / {@link Sense#HEARING}: the continuous exposure (seconds) after which it
     *                        is noticed; {@link #NEVER} otherwise
     */
    public record Reading(Sense sense, double requiredSeconds) {
        static final Reading NOTHING = new Reading(Sense.NONE, NEVER);

        /** True when the subject is sensed by sight or hearing (exposure builds up). */
        public boolean exposed() {
            return sense == Sense.SIGHT || sense == Sense.HEARING;
        }
    }

    // ------------------------------------------------------------------ pieces

    /**
     * How many times longer the reaction takes at this angle off the look direction: 1 up to the full-attention half
     * angle, then rising linearly (continuous) to the peripheral multiplier at the peripheral half angle;
     * {@link #NEVER} beyond that (not sighted at all).
     */
    public static double angleFactor(Params p, double thetaDeg) {
        double t = Math.abs(thetaDeg);
        if (t <= p.fullAttentionHalfAngleDeg()) {
            return 1.0;
        }
        if (t > p.peripheralHalfAngleDeg()) {
            return NEVER;
        }
        double span = p.peripheralHalfAngleDeg() - p.fullAttentionHalfAngleDeg();
        return 1.0 + (t - p.fullAttentionHalfAngleDeg()) / span * (p.peripheralMultiplier() - 1.0);
    }

    /** The distance part of the reaction time in seconds: {@code base + (at64 - base) * distance / 64}, linear and continuous. */
    public static double distanceSeconds(Params p, double distance) {
        return p.reactionBaseSeconds()
                + (p.reactionAt64Seconds() - p.reactionBaseSeconds()) * Math.max(0.0, distance) / 64.0;
    }

    /**
     * The continuous exposure (seconds) after which a subject at this angle and distance is noticed:
     * {@code distanceSeconds * angleFactor * sneakFactor / visibility}; {@link #NEVER} when it is outside the view field or
     * fully invisible.
     *
     * @param sneakApplies false to ignore the subject's sneaking (a hit tells the victim where the attacker is)
     */
    public static double requiredSeconds(Params p, double thetaDeg, double distance, Subject s, boolean sneakApplies) {
        double angle = angleFactor(p, thetaDeg);
        if (angle == NEVER || s.visibility() <= 0.0) {
            return NEVER;
        }
        double sneak = sneakApplies && s.sneaking() ? p.sneakMultiplier() : 1.0;
        return distanceSeconds(p, distance) * angle * sneak / s.visibility();
    }

    /** The exposure in seconds of {@code ticks} consecutive ticks (the tick a run starts on is 0). */
    public static double exposureSeconds(long ticks) {
        return ticks * TICK_SECONDS;
    }

    /** The first tick of a run (0 = the tick it starts on) whose exposure reaches {@code requiredSeconds}; -1 for {@link #NEVER}. */
    public static long noticeTick(double requiredSeconds) {
        if (requiredSeconds == NEVER) {
            return -1;
        }
        return (long) Math.max(0.0, Math.ceil(requiredSeconds / TICK_SECONDS - 1e-9));
    }

    /** The 3D angle in degrees between the look direction and the direction to the subject (0 = straight ahead). */
    public static double angleDeg(double lx, double ly, double lz, double dx, double dy, double dz) {
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (ll < 1e-9 || dl < 1e-9) {
            return 0.0;
        }
        double cos = (lx * dx + ly * dy + lz * dz) / (ll * dl);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
    }

    /**
     * What the observer makes of the subject THIS tick.
     *
     * @param thetaDeg       the angle between the observer's look direction and the direction to the subject
     * @param distance       the distance between them (eye to eye)
     * @param heardNear      a vanilla vibration was heard at the subject's position recently (the observer turned to it)
     * @param occlusionClear asked last, at most once, only when the angle or the hearing already allows sensing; true when
     *                       the line of sight is clear (eye ray, then a body-centre ray)
     */
    public static Reading read(Params p, double thetaDeg, double distance, Subject s, boolean heardNear,
                               BooleanSupplier occlusionClear) {
        if (!p.enabled()) {
            return occlusionClear.getAsBoolean() ? new Reading(Sense.SIGHT, 0.0) : Reading.NOTHING;
        }
        double see = requiredSeconds(p, thetaDeg, distance, s, true);
        double hear = heardNear ? requiredSeconds(p, 0.0, distance, s, true) : NEVER;
        if (see == NEVER && hear == NEVER) {
            return Reading.NOTHING;
        }
        if (!occlusionClear.getAsBoolean()) {
            return Reading.NOTHING;
        }
        return hear < see ? new Reading(Sense.HEARING, hear) : new Reading(Sense.SIGHT, see);
    }

    /** True when {@code exposureSeconds} of continuous exposure is enough for {@code requiredSeconds}. */
    public static boolean noticed(double exposureSeconds, double requiredSeconds) {
        return requiredSeconds != NEVER && exposureSeconds + 1e-9 >= requiredSeconds;
    }
}
