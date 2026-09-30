package dev.spawnbotswrapper.inhabitants.combat;

import java.util.function.BooleanSupplier;

/**
 * Realistic noticing: the ONE pure decision "does observer O notice subject S, and how long must it be exposed first",
 * shared (as the same function, checked by the same golden vectors in {@code docs/perception/vectors.json}) with the
 * Minecraft-AI mod. See {@code docs/PERCEPTION.md}. There is NO distance restriction beyond the mod maximum: a subject
 * is sighted whenever it is in view; seeing takes TIME (a person needs a moment to register somebody), and the moment is
 * longer for what is harder to see.
 * <ul>
 *   <li><b>Sighted</b> this tick: within the mod maximum, in the view cone (front {@code frontHalfAngleDeg}, or the
 *       peripheral field out to {@code peripheralHalfAngleDeg}; behind is never sighted), the view unobstructed, and
 *       not fully invisible.</li>
 *   <li><b>Reaction time.</b> The subject must stay sighted for {@code reactionTicks * cone * sneak / visibility +
 *       distanceTicksPer32 * distance / 32} ticks (cone: 1 in front, {@code peripheralMultiplier} in the periphery;
 *       sneak: {@code sneakMultiplier} while the subject sneaks; visibility: vanilla invisibility and worn mob heads with
 *       the sneak factor divided out). The distance term is a soft scaling (far figures take longer), not a limit.</li>
 *   <li><b>Hearing.</b> A close, noisy subject (walking 4, sprinting 8, combat noise 12; a sneaking or standing subject is
 *       silent) with a clear line counts as sighted in the FRONT cone: the observer turns to the sound. Heard but
 *       occluded is only an {@link Sense#INVESTIGATE} hint (a place to go and look), never a notice. Hearing only ADDS
 *       awareness from behind; it restricts nothing.</li>
 *   <li><b>Occlusion</b> is asked LAST (a callback), only when range and angle already say yes: the caller casts its
 *       expensive rays lazily, at most once.</li>
 * </ul>
 * Exposure (how long the subject has been sighted) is bookkeeping of the caller, see {@link ExposureTracker}; awareness
 * memory (an engaged combatant follows by plain occlusion) is the caller's engagement. {@code enabled=false} is exactly
 * vanilla {@code hasLineOfSight}: in range and unobstructed is a notice at once, no cone, no sneaking, no time. No
 * Minecraft classes here.
 */
public final class Perception {
    private Perception() {
    }

    /** What an observer made of a subject this tick. */
    public enum Sense {
        NONE, SIGHT, HEARING,
        /** Heard, but the line is blocked: a place to go and look, never a notice. */
        INVESTIGATE
    }

    /** What kind of creature makes noise the way mobs do; {@link #NONE} for a player or a bot (they use the stance). */
    public enum MobKind {
        /** A player or a bot: noise comes from the stance (moving, sprinting, sneaking, combat). */
        NONE,
        /** Zombie/skeleton/spider families, pillagers, witches, enderman, blaze, ghast, piglins, hoglin, phantom, slimes. */
        NOISY,
        /** A creeper that is not swelling: silent. */
        CREEPER,
        /** A swelling creeper. */
        CREEPER_PRIMED,
        WARDEN,
        /** Animals and other creatures: noisy while moving, silent while idle. */
        ANIMAL
    }

    /** The tunables (the {@code aggro} and {@code aggro.perception} config blocks of the wrapper). */
    public record Params(boolean enabled, double frontHalfAngleDeg, double peripheralHalfAngleDeg,
                         double peripheralMultiplier, double sneakMultiplier, double reactionTicks,
                         double distanceTicksPer32, double hearWalk, double hearSprint, double hearCombat,
                         double hearNoisyMob, double hearPrimedCreeper, double hearWarden, double hearAnimal,
                         int combatNoiseTicks) {
        public static Params defaults() {
            return new Params(true, 60.0, 100.0, 2.0, 2.0, 5.0, 5.0, 4.0, 8.0, 12.0, 8.0, 16.0, 24.0, 4.0, 10);
        }

        /** The same tunables with perception switched off: exactly vanilla hasLineOfSight (see the class comment). */
        public Params disabled() {
            return new Params(false, frontHalfAngleDeg, peripheralHalfAngleDeg, peripheralMultiplier, sneakMultiplier,
                    reactionTicks, distanceTicksPer32, hearWalk, hearSprint, hearCombat, hearNoisyMob,
                    hearPrimedCreeper, hearWarden, hearAnimal, combatNoiseTicks);
        }
    }

    /** No noise event has happened for so long that it never counts. */
    public static final int NO_NOISE = Integer.MAX_VALUE;

    /** "Never": the required exposure of something that cannot be sighted at all (behind, fully invisible). */
    public static final double NEVER = Double.POSITIVE_INFINITY;

    /**
     * What the subject is doing this tick, as far as noticing goes.
     *
     * @param sneaking       crouching (shift key or crouching pose)
     * @param moving         horizontally moving (not just standing or turning)
     * @param sprinting      sprinting (sprint-jumping included)
     * @param combatNoiseAge ticks since its latest swing, hurt, bow/crossbow/trident use, block break or place,
     *                       eating or drinking; {@link #NO_NOISE} when none
     * @param mob            {@link MobKind#NONE} for players and bots
     * @param visibility     invisibility and disguise factor (1.0 = plain): the vanilla getVisibilityPercent with the
     *                       sneak factor divided out
     */
    public record Subject(boolean sneaking, boolean moving, boolean sprinting, int combatNoiseAge, MobKind mob,
                          double visibility) {
        /** A player or bot in the given stance, no combat noise, fully visible. */
        public static Subject player(boolean sneaking, boolean moving, boolean sprinting) {
            return new Subject(sneaking, moving, sprinting, NO_NOISE, MobKind.NONE, 1.0);
        }
    }

    /**
     * What the observer made of the subject this tick.
     *
     * @param sense         how it was sensed (or not)
     * @param requiredTicks for {@link Sense#SIGHT} / {@link Sense#HEARING}: the continuous exposure (ticks) after which it
     *                      is noticed; {@link #NEVER} otherwise
     */
    public record Reading(Sense sense, double requiredTicks) {
        static final Reading NOTHING = new Reading(Sense.NONE, NEVER);

        /** True when the subject is sensed by sight or hearing (exposure builds up). */
        public boolean exposed() {
            return sense == Sense.SIGHT || sense == Sense.HEARING;
        }
    }

    // ------------------------------------------------------------------ pieces

    /** The noise radius (blocks) the subject makes this tick: the largest of what applies; 0 is silent. */
    public static double noiseRadius(Params p, Subject s) {
        double r = 0.0;
        switch (s.mob()) {
            case NONE -> {
                if (s.moving() && !s.sneaking()) {
                    r = s.sprinting() ? p.hearSprint() : p.hearWalk();
                }
            }
            case NOISY -> r = p.hearNoisyMob();
            case CREEPER -> r = 0.0;
            case CREEPER_PRIMED -> r = p.hearPrimedCreeper();
            case WARDEN -> r = p.hearWarden();
            case ANIMAL -> r = s.moving() ? p.hearAnimal() : 0.0;
        }
        if (s.combatNoiseAge() >= 0 && s.combatNoiseAge() < p.combatNoiseTicks()) {
            r = Math.max(r, p.hearCombat());
        }
        return r;
    }

    /**
     * How many times longer the reaction takes at this angle off the look direction: 1 in the front cone, the
     * peripheral multiplier in the peripheral field, {@link #NEVER} behind (not sighted at all).
     */
    public static double coneMultiplier(Params p, double thetaDeg) {
        double t = Math.abs(thetaDeg);
        if (t <= p.frontHalfAngleDeg()) {
            return 1.0;
        }
        return t <= p.peripheralHalfAngleDeg() ? p.peripheralMultiplier() : NEVER;
    }

    /** The soft distance term of the reaction time: {@code distanceTicksPer32} extra ticks per 32 blocks. */
    public static double distanceTicks(Params p, double distance) {
        return p.distanceTicksPer32() * Math.max(0.0, distance) / 32.0;
    }

    /**
     * The continuous exposure (ticks) after which a subject SIGHTED at this angle and distance is noticed:
     * {@code reactionTicks * cone * sneak / visibility + distance term}; {@link #NEVER} when it is behind the observer or
     * fully invisible.
     */
    public static double sightTicks(Params p, double thetaDeg, double distance, Subject s) {
        double cone = coneMultiplier(p, thetaDeg);
        if (cone == NEVER || s.visibility() <= 0.0) {
            return NEVER;
        }
        double sneak = s.sneaking() ? p.sneakMultiplier() : 1.0;
        return p.reactionTicks() * cone * sneak / s.visibility() + distanceTicks(p, distance);
    }

    /** The exposure after which a HEARD subject is noticed: the front-cone reaction time (the observer turns to the sound). */
    public static double hearingTicks(Params p, double distance) {
        return p.reactionTicks() + distanceTicks(p, distance);
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
     * @param modMax         the mod maximum in blocks (128: vanilla hasLineOfSight's cap and PvP BOT's largest targeting
     *                       distance); the only distance limit there is
     * @param thetaDeg       the angle between the observer's look direction and the direction to the subject
     * @param distance       the distance between them
     * @param occlusionClear asked last, at most once, only when range and angle already allow sensing; true when the
     *                       line of sight is clear (eye ray, then a body-centre ray)
     */
    public static Reading read(Params p, double modMax, double thetaDeg, double distance, Subject s,
                               BooleanSupplier occlusionClear) {
        if (distance > modMax) {
            return Reading.NOTHING;
        }
        if (!p.enabled()) {
            return occlusionClear.getAsBoolean() ? new Reading(Sense.SIGHT, 0.0) : Reading.NOTHING;
        }
        double seeTicks = sightTicks(p, thetaDeg, distance, s);
        boolean sightPossible = seeTicks != NEVER;
        boolean heard = distance <= noiseRadius(p, s);
        if (!sightPossible && !heard) {
            return Reading.NOTHING;
        }
        if (!occlusionClear.getAsBoolean()) {
            return heard ? new Reading(Sense.INVESTIGATE, NEVER) : Reading.NOTHING;
        }
        double hearTicks = heard ? hearingTicks(p, distance) : NEVER;
        if (sightPossible && seeTicks <= hearTicks) {
            return new Reading(Sense.SIGHT, seeTicks);
        }
        return new Reading(Sense.HEARING, hearTicks);
    }

    /** True when {@code exposureTicks} of continuous exposure is enough for {@code requiredTicks}. */
    public static boolean noticed(double exposureTicks, double requiredTicks) {
        return requiredTicks != NEVER && exposureTicks >= requiredTicks;
    }
}
