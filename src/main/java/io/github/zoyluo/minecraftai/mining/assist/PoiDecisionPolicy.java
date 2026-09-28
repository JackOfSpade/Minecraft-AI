package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Objects;

/**
 * Pure fallback matrix (design 6.7): what to do about a POI candidate when the LLM advisor is
 * unavailable (keyless, breaker open, budget exhausted, degraded TPS, timeout, or invalid reply).
 * Deterministic and total over every {@link PoiScorer.Band}: {@link PoiScorer.Band#NONE},
 * {@link PoiScorer.Band#MANDATORY} and {@link PoiScorer.Band#STRUCTURE_CERTAIN} have rows here
 * for completeness (a NONE band is never a stop, and MANDATORY/STRUCTURE_CERTAIN always stop
 * regardless of policy), even though {@code PoiCoordinator} only ever routes POSSIBLE and
 * CAVERN_ONLY candidates through {@link #decide}: a mandatory candidate stops unconditionally
 * before this class is consulted, and a structure-certain one stops directly unless the
 * habitation downgrade already turned it into POSSIBLE.
 */
public final class PoiDecisionPolicy {
    /** Design 6.7: the S threshold of the default {@code poi.unavailablePolicy = stop_if_structure}. Not configurable. */
    public static final double FALLBACK_STOP_SCORE = 0.75D;

    /** The fallback verdict for one candidate. */
    public enum Decision { STOP, NOTIFY_ONLY }

    private PoiDecisionPolicy() {
    }

    /**
     * Design 6.7's matrix, in priority order:
     * <ol>
     *   <li>{@link PoiScorer.Band#NONE}: always {@link Decision#NOTIFY_ONLY} — there is no candidate to
     *       stop for, regardless of a stale {@code structureScore};</li>
     *   <li>{@link PoiScorer.Band#MANDATORY} or {@link PoiScorer.Band#STRUCTURE_CERTAIN}: always
     *       {@link Decision#STOP};</li>
     *   <li>{@link PoiScorer.Band#CAVERN_ONLY}: {@link Decision#STOP} when {@code cavernKeylessPolicy} is
     *       {@code STOP_IF_POSSIBLE}, else {@link Decision#NOTIFY_ONLY} — independent of
     *       {@code unavailablePolicy};</li>
     *   <li>{@link PoiScorer.Band#POSSIBLE} (including a candidate the habitation downgrade demoted from
     *       structure-certain): {@code habitationLike} forces {@link Decision#NOTIFY_ONLY} unconditionally;
     *       otherwise {@code unavailablePolicy} decides — {@code NOTIFY_ONLY} always notifies,
     *       {@code STOP_IF_POSSIBLE} always stops, {@code STOP_IF_STRUCTURE} (the default) stops only when
     *       {@code structureScore >= FALLBACK_STOP_SCORE}.</li>
     * </ol>
     *
     * @param band               the evaluation's band
     * @param structureScore     S ({@code score.s()}); read only for the POSSIBLE / STOP_IF_STRUCTURE row
     * @param habitationLike     {@code score.habitationLike()}; overrides {@code unavailablePolicy} unconditionally
     * @param unavailablePolicy  governs POSSIBLE (structural) candidates
     * @param cavernKeylessPolicy governs CAVERN_ONLY candidates, independently of {@code unavailablePolicy}
     */
    public static Decision decide(
            PoiScorer.Band band,
            double structureScore,
            boolean habitationLike,
            MiningAssistConfig.UnavailablePolicy unavailablePolicy,
            MiningAssistConfig.CavernKeylessPolicy cavernKeylessPolicy) {
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(unavailablePolicy, "unavailablePolicy");
        Objects.requireNonNull(cavernKeylessPolicy, "cavernKeylessPolicy");

        if (band == PoiScorer.Band.NONE) {
            return Decision.NOTIFY_ONLY;
        }
        if (band == PoiScorer.Band.MANDATORY || band == PoiScorer.Band.STRUCTURE_CERTAIN) {
            return Decision.STOP;
        }
        if (band == PoiScorer.Band.CAVERN_ONLY) {
            return cavernKeylessPolicy == MiningAssistConfig.CavernKeylessPolicy.STOP_IF_POSSIBLE
                    ? Decision.STOP
                    : Decision.NOTIFY_ONLY;
        }

        // band == POSSIBLE, including a candidate downgraded from STRUCTURE_CERTAIN by the habitation rule.
        if (habitationLike) {
            return Decision.NOTIFY_ONLY;
        }
        return switch (unavailablePolicy) {
            case NOTIFY_ONLY -> Decision.NOTIFY_ONLY;
            case STOP_IF_POSSIBLE -> Decision.STOP;
            case STOP_IF_STRUCTURE -> structureScore >= FALLBACK_STOP_SCORE ? Decision.STOP : Decision.NOTIFY_ONLY;
        };
    }
}
