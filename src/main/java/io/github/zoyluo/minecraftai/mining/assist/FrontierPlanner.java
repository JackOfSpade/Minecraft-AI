package io.github.zoyluo.aibot.mining.assist;

import java.util.Objects;

/**
 * L2 cave-frontier excursion scoring (mining-assist design 5.4): given a candidate stand's
 * already-computed evidence terms, the ranking utility U, the acceptance distance band and the
 * hysteresis that keeps a running excursion from flapping between two near-equal frontiers. Every
 * input is a number the caller's world-touching adapter has already computed (an UNKNOWN-fraction
 * window, a capped sighting count, a {@code MiningChain}-derived Y closeness, and
 * {@link ObservedGraphSearch}'s cheapest observed cost), so this class never reads the world and is
 * fully unit-testable.
 *
 * <p><b>U = (6.0*unknownFrac + 3.0*min(sightingsWithin6, 3) + 1.5*yBand) / (observedPathCost +
 * 4.0)</b> (design 5.4). A candidate is accepted only inside the distance band
 * [{@link #MIN_DISTANCE}, {@link #MAX_DISTANCE}] and at or above the caller's own
 * {@code frontier_min_utility}.</p>
 */
public final class FrontierPlanner {
    private FrontierPlanner() {
    }

    /** Weight of {@link Candidate#unknownFrac()} in {@link Candidate#utility()}. */
    public static final double UNKNOWN_WEIGHT = 6.0D;
    /** Weight of the sighting term, after capping at {@link #SIGHTING_CAP}. */
    public static final double SIGHTING_WEIGHT = 3.0D;
    /** Upper bound {@link Candidate#sightingsWithin6()} is capped at before weighting. */
    public static final int SIGHTING_CAP = 3;
    /** Weight of {@link Candidate#yBand()}. */
    public static final double Y_BAND_WEIGHT = 1.5D;
    /**
     * Added to {@link Candidate#observedPathCost()} in the utility denominator, so a zero-cost stand
     * (the bot's own cell) never divides by zero.
     */
    public static final double PATH_COST_OFFSET = 4.0D;

    /** Nearest a frontier stand may be, in blocks, to be accepted (design 5.4). */
    public static final double MIN_DISTANCE = 6.0D;
    /** Farthest a frontier stand may be, in blocks, to be accepted (design 5.4). */
    public static final double MAX_DISTANCE = 24.0D;
    /** {@link #keepPrevious} hysteresis band: the running frontier is kept while within this of the best. */
    public static final double HYSTERESIS_GAP = 0.5D;

    /**
     * One candidate stand's already-computed evidence (design 5.4).
     *
     * @param unknownFrac      UNKNOWN fraction of the 7x7x7 window around the stand, clamped to {@code [0, 1]}
     * @param sightingsWithin6 count of remembered sightings within 6 blocks of the stand, clamped to
     *                         at least 0 ({@link #utility()} additionally caps it at {@link #SIGHTING_CAP})
     * @param yBand            {@code MiningChain}-derived closeness of the stand's Y to the target ore
     *                         set's best Y, clamped to {@code [0, 1]}
     * @param observedPathCost {@link ObservedGraphSearch}'s cheapest observed-route cost from the bot
     *                         to the stand, clamped to at least 0
     * @param distanceFromBot  straight-line distance from the bot to the stand, in blocks, clamped to
     *                         at least 0
     */
    public record Candidate(
            double unknownFrac, int sightingsWithin6, double yBand, double observedPathCost, double distanceFromBot) {
        public Candidate {
            unknownFrac = clamp01(unknownFrac);
            sightingsWithin6 = Math.max(0, sightingsWithin6);
            yBand = clamp01(yBand);
            observedPathCost = Double.isFinite(observedPathCost) ? Math.max(0.0D, observedPathCost) : 0.0D;
            distanceFromBot = Double.isFinite(distanceFromBot) ? Math.max(0.0D, distanceFromBot) : 0.0D;
        }

        /** U, the ranking utility (design 5.4's formula). */
        public double utility() {
            int sightingTerm = Math.min(sightingsWithin6, SIGHTING_CAP);
            double numerator = UNKNOWN_WEIGHT * unknownFrac + SIGHTING_WEIGHT * sightingTerm + Y_BAND_WEIGHT * yBand;
            return numerator / (observedPathCost + PATH_COST_OFFSET);
        }

        /** True while the stand's distance is inside the accepted band [{@link #MIN_DISTANCE}, {@link #MAX_DISTANCE}]. */
        public boolean inRange() {
            return distanceFromBot >= MIN_DISTANCE && distanceFromBot <= MAX_DISTANCE;
        }
    }

    /**
     * The best in-range candidate whose utility is at least {@code minUtility} (the caller's
     * {@code frontier_min_utility}), or null when none qualifies. A candidate outside
     * [{@link #MIN_DISTANCE}, {@link #MAX_DISTANCE}] is never returned even when its utility is
     * highest. Ties (equal utility) keep the first candidate seen, so a caller wanting a specific
     * tie-break orders its input deterministically.
     */
    public static Candidate bestAccepted(Iterable<Candidate> candidates, double minUtility) {
        Objects.requireNonNull(candidates, "candidates");
        Candidate best = null;
        double bestUtility = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            if (candidate == null || !candidate.inRange()) {
                continue;
            }
            double utility = candidate.utility();
            if (utility < minUtility) {
                continue;
            }
            if (best == null || utility > bestUtility) {
                best = candidate;
                bestUtility = utility;
            }
        }
        return best;
    }

    /**
     * Hysteresis (design 5.4): true when the running excursion's frontier {@code previous} should be
     * kept over the freshly recomputed {@code best} because it is still within
     * {@link #HYSTERESIS_GAP} of the best utility -- avoids abandoning a frontier the bot is already
     * travelling toward for a marginally better one. {@code previous} must itself still be a
     * currently valid, in-range candidate (the caller re-evaluates its evidence every check, since
     * the world may have changed); a null or out-of-range {@code previous}, or a null {@code best}
     * (nothing currently qualifies), never wins.
     */
    public static boolean keepPrevious(Candidate previous, Candidate best) {
        if (previous == null || best == null || !previous.inRange()) {
            return false;
        }
        return best.utility() - previous.utility() <= HYSTERESIS_GAP;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
