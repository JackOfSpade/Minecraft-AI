package dev.spawnbotswrapper.inhabitants.combat;

import java.util.function.BooleanSupplier;

/**
 * Realistic noticing: the ONE pure decision {@code notice(observer, subject) -> SIGHT | HEARING | NONE}, shared (as
 * the same function, checked by the same golden vectors in {@code docs/perception/vectors.json}) with the
 * Minecraft-AI mod. See {@code docs/PERCEPTION.md}.
 * <ul>
 *   <li><b>Cone.</b> Within {@code frontHalfAngleDeg} of the look direction the subject is seen at the full range;
 *       out to {@code peripheralHalfAngleDeg} at {@code peripheralFactor} of it; beyond that (behind) it is not seen.</li>
 *   <li><b>Subject.</b> Sneaking multiplies the sight range by {@code sneakFactor}; invisibility and worn mob heads by
 *       the {@code visibility} factor (the vanilla one, with its own sneak factor divided out so sneaking is not
 *       counted twice).</li>
 *   <li><b>Hearing.</b> A close, noisy subject is noticed even from behind: within its noise radius (walking 4,
 *       sprinting 8, combat noise 12, mobs by kind; sneaking or standing still is silent), capped at the base sight
 *       range. Hearing never works through walls.</li>
 *   <li><b>Occlusion</b> is asked LAST (a callback), only when range and angle already say yes: the caller casts its
 *       expensive rays lazily, at most once.</li>
 * </ul>
 * Awareness memory (an engaged combatant keeps following for {@code awarenessTicks}) lives in the caller: for the
 * aggro controller it is the engagement itself. No Minecraft classes here.
 */
public final class Perception {
    private Perception() {
    }

    /** How a subject was noticed. */
    public enum Notice {
        NONE, SIGHT, HEARING
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

    /** The tunables (the {@code perception} config block of either mod). */
    public record Params(boolean enabled, double frontHalfAngleDeg, double peripheralHalfAngleDeg,
                         double peripheralFactor, double sneakFactor, double hearWalk, double hearSprint,
                         double hearCombat, double hearNoisyMob, double hearPrimedCreeper, double hearWarden,
                         double hearAnimal, int combatNoiseTicks, int awarenessTicks) {
        public static Params defaults() {
            return new Params(true, 60.0, 100.0, 0.5, 0.5, 4.0, 8.0, 12.0, 8.0, 16.0, 24.0, 4.0, 10, 200);
        }

        /** The same tunables with perception switched off: plain omnidirectional line of sight, as before. */
        public Params disabled() {
            return new Params(false, frontHalfAngleDeg, peripheralHalfAngleDeg, peripheralFactor, sneakFactor,
                    hearWalk, hearSprint, hearCombat, hearNoisyMob, hearPrimedCreeper, hearWarden, hearAnimal,
                    combatNoiseTicks, awarenessTicks);
        }
    }

    /** No noise event has happened for so long that it never counts. */
    public static final int NO_NOISE = Integer.MAX_VALUE;

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

    /** The share of the sight range that applies at this angle off the look direction: 1, the peripheral factor, or 0. */
    public static double coneFactor(Params p, double thetaDeg) {
        double t = Math.abs(thetaDeg);
        if (t <= p.frontHalfAngleDeg()) {
            return 1.0;
        }
        return t <= p.peripheralHalfAngleDeg() ? p.peripheralFactor() : 0.0;
    }

    /** The distance out to which the subject is SEEN at this angle: base x cone x sneak x visibility. */
    public static double sightRange(Params p, double baseRange, double thetaDeg, Subject s) {
        return baseRange * coneFactor(p, thetaDeg) * (s.sneaking() ? p.sneakFactor() : 1.0)
                * Math.max(0.0, s.visibility());
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
     * Whether the observer notices the subject.
     *
     * @param baseRange      the observer's base sight range (the aggro acquire range)
     * @param thetaDeg       the angle between the observer's look direction and the direction to the subject
     * @param distance       the distance between them
     * @param occlusionClear asked last, at most once, only when range and angle already allow noticing; true when the
     *                       line of sight is clear (eye ray, then a body-centre ray)
     */
    public static Notice notice(Params p, double baseRange, double thetaDeg, double distance, Subject s,
                                BooleanSupplier occlusionClear) {
        if (!p.enabled()) {
            return distance <= baseRange && occlusionClear.getAsBoolean() ? Notice.SIGHT : Notice.NONE;
        }
        boolean sees = distance <= sightRange(p, baseRange, thetaDeg, s);
        boolean hears = distance <= Math.min(noiseRadius(p, s), baseRange);
        if (!sees && !hears) {
            return Notice.NONE;
        }
        if (!occlusionClear.getAsBoolean()) {
            return Notice.NONE;
        }
        return sees ? Notice.SIGHT : Notice.HEARING;
    }
}
