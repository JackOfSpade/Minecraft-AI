package io.github.zoyluo.aibot.mining.assist;

import java.util.Objects;

/**
 * Pure decision kernel for R2's L1 strip-steering refinement (mining-assist design 5.3, phase P4,
 * off by default until its benchmark passes). Every input is a number the caller's world-touching
 * adapter has already computed (freshness from {@link CoverageGrid}, openness from a handful of view
 * rays, a knowledge-base bearing, a hazard proximity fraction), so this class never reads the world
 * and is fully unit-testable.
 *
 * <p><b>What it replaces.</b> Today's strip mine always turns the same way (clockwise through
 * {@code STRIP_DIRS = {N, E, S, W}}) and grows its leg length on a fixed schedule (48, 48, 96, 96,
 * 144, ... capped at 384). L1 only ever compares that clockwise default against the single
 * counter-clockwise alternative, and only overrides it when the alternative is clearly better; with
 * an empty model (no coverage yet, no open-area signal, no known rich zone, no hazard) every
 * candidate scores the same and the existing schedule survives bit for bit -- see {@link #chooseTurn}.
 * It never reverses (never proposes the direction behind the bot) and never touches the geometric
 * square-spiral growth invariant on its own; the caller decides the length only when this class says
 * to override at all.</p>
 */
public final class LegChooser {
    private LegChooser() {
    }

    /** Weight of {@link DirectionSignal#freshFraction()} in {@link DirectionSignal#utility()}. */
    public static final double FRESH_WEIGHT = 1.0D;
    /** Weight of {@link DirectionSignal#openBias()}. */
    public static final double OPEN_BIAS_WEIGHT = 0.6D;
    /** Weight of {@link DirectionSignal#zoneAttraction()}. */
    public static final double ZONE_WEIGHT = 0.5D;
    /** Weight of {@link DirectionSignal#hazardProximity()}, subtracted. */
    public static final double HAZARD_WEIGHT = 1.5D;

    /**
     * Smallest utility gap (counter-clockwise minus clockwise) that turns the strip away from its
     * default clockwise successor. Below this, {@link #chooseTurn} keeps the clockwise default so a
     * genuinely uninformative model (every term tied) never perturbs today's spiral.
     */
    public static final double OVERRIDE_UTILITY_GAP = 0.15D;
    /** Freshness at or above which an overridden leg gets the long length. */
    public static final double LONG_LEG_FRESH_THRESHOLD = 0.85D;
    /** Leg length used when the overridden corridor is not fresh enough for {@link #LONG_LEG_LENGTH}. */
    public static final int SHORT_LEG_LENGTH = 48;
    /** Leg length used when the overridden corridor is at least {@link #LONG_LEG_FRESH_THRESHOLD} fresh. */
    public static final int LONG_LEG_LENGTH = 96;

    /**
     * The four already-computed signals for one candidate {@code STRIP_DIRS} index, and the
     * {@code Utility(d, L)} the design defines over them. Every component is clamped to a sane
     * range at construction, so a caller's rounding noise can never flip a comparison the wrong way:
     * {@code freshFraction} and {@code openBias} are fractions in {@code [0, 1]}, {@code
     * zoneAttraction} is non-negative (it is already an inverse-distance-weighted bearing score, so
     * it has no natural upper bound but can never pull a direction down), and {@code hazardProximity}
     * is a fraction in {@code [0, 1]}.
     *
     * @param dirIndex        the candidate's absolute {@code STRIP_DIRS} index, {@code [0, 3]}
     * @param freshFraction   fraction of the candidate corridor {@link CoverageGrid} has not marked
     * @param openBias        fraction of the ring rays in the candidate's &plusmn;45&deg; sector with
     *                        free length at least 8
     * @param zoneAttraction  bearing toward a known rich zone or sighting, weighted by 1/distance
     * @param hazardProximity fraction of the candidate corridor within 4 blocks of a known hazard or
     *                        acknowledged point of interest
     */
    public record DirectionSignal(
            int dirIndex, double freshFraction, double openBias, double zoneAttraction, double hazardProximity) {
        public DirectionSignal {
            if (dirIndex < 0 || dirIndex > 3) {
                throw new IllegalArgumentException("dirIndex must be a STRIP_DIRS index in [0, 3]: " + dirIndex);
            }
            freshFraction = clamp01(freshFraction);
            openBias = clamp01(openBias);
            zoneAttraction = Double.isFinite(zoneAttraction) ? Math.max(0.0D, zoneAttraction) : 0.0D;
            hazardProximity = clamp01(hazardProximity);
        }

        /** {@code 1.0*freshFraction + 0.6*openBias + 0.5*zoneAttraction - 1.5*hazardProximity}. */
        public double utility() {
            return FRESH_WEIGHT * freshFraction + OPEN_BIAS_WEIGHT * openBias
                    + ZONE_WEIGHT * zoneAttraction - HAZARD_WEIGHT * hazardProximity;
        }
    }

    /**
     * The turn L1 picked at one leg boundary: the chosen absolute {@code STRIP_DIRS} index, and
     * whether it actually overrode the clockwise default (the caller only recomputes the leg length
     * when this is true; see the class javadoc and design 5.3's "Length is 96 when freshFraction
     * &ge; 0.85, else 48. Override only when the utility gap is at least 0.15.").
     */
    public record TurnChoice(int dirIndex, boolean overridden) {
    }

    /**
     * Chooses between the strip's two legal successors at a leg boundary: {@code clockwise} (today's
     * fixed turn, {@code (currentDir + 1) % 4}) and {@code counterClockwise} (the only other option;
     * L1 never proposes the geometric reverse). Counter-clockwise wins only when it beats clockwise
     * by at least {@link #OVERRIDE_UTILITY_GAP}; a tie or a smaller gap keeps clockwise, so an
     * uninformative model reproduces today's spiral exactly.
     */
    public static TurnChoice chooseTurn(DirectionSignal clockwise, DirectionSignal counterClockwise) {
        Objects.requireNonNull(clockwise, "clockwise");
        Objects.requireNonNull(counterClockwise, "counterClockwise");
        double gap = counterClockwise.utility() - clockwise.utility();
        if (gap >= OVERRIDE_UTILITY_GAP) {
            return new TurnChoice(counterClockwise.dirIndex(), true);
        }
        return new TurnChoice(clockwise.dirIndex(), false);
    }

    /**
     * The leg length for an overridden turn (design 5.3): {@link #LONG_LEG_LENGTH} when the chosen
     * direction's own corridor is at least {@link #LONG_LEG_FRESH_THRESHOLD} fresh, else {@link
     * #SHORT_LEG_LENGTH}. The caller applies this only when {@link TurnChoice#overridden()} is true;
     * an unoverridden leg keeps the existing 48/48/96/96/... growth schedule untouched.
     */
    public static int lengthForFreshFraction(double freshFraction) {
        return freshFraction >= LONG_LEG_FRESH_THRESHOLD ? LONG_LEG_LENGTH : SHORT_LEG_LENGTH;
    }

    /**
     * Scores all four cardinals for a fresh mission's very first leg ({@code stripDirIndex < 0}) and
     * returns the best absolute {@code STRIP_DIRS} index. Ties resolve to NORTH (the array is
     * evaluated in {@code STRIP_DIRS} order -- N, E, S, W -- and only a strictly higher utility
     * replaces the current best), so a fixture with no evidence at all keeps today's fixed start.
     */
    public static int chooseInitialDirection(
            DirectionSignal north, DirectionSignal east, DirectionSignal south, DirectionSignal west) {
        Objects.requireNonNull(north, "north");
        Objects.requireNonNull(east, "east");
        Objects.requireNonNull(south, "south");
        Objects.requireNonNull(west, "west");
        DirectionSignal[] candidates = {north, east, south, west};
        DirectionSignal best = north;
        for (DirectionSignal candidate : candidates) {
            if (candidate.utility() > best.utility()) {
                best = candidate;
            }
        }
        return best.dirIndex();
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
