package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig.CavernKeylessPolicy;
import static io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig.UnavailablePolicy;
import static io.github.zoyluo.minecraftai.mining.assist.PoiDecisionPolicy.Decision.NOTIFY_ONLY;
import static io.github.zoyluo.minecraftai.mining.assist.PoiDecisionPolicy.Decision.STOP;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig.CavernKeylessPolicy;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig.UnavailablePolicy;

/** Design 6.7: the full fallback matrix, including the fix that makes {@link PoiDecisionPolicy} actually total
 * over every {@link PoiScorer.Band} (a {@code NONE} band must never leak a stop through a stale score). */
class PoiDecisionPolicyTest {

    @Test
    void noneIsAlwaysNotifyOnlyRegardlessOfStructureScore() {
        for (double score : new double[] {0.0D, 0.74D, 0.75D, 1.0D}) {
            assertEquals(NOTIFY_ONLY, PoiDecisionPolicy.decide(PoiScorer.Band.NONE, score, false,
                    UnavailablePolicy.STOP_IF_STRUCTURE, CavernKeylessPolicy.STOP_IF_POSSIBLE),
                    "NONE with score " + score + " must never stop even under the most stop-eager policies");
            assertEquals(NOTIFY_ONLY, PoiDecisionPolicy.decide(PoiScorer.Band.NONE, score, true,
                    UnavailablePolicy.STOP_IF_POSSIBLE, CavernKeylessPolicy.STOP_IF_POSSIBLE));
        }
    }

    @Test
    void structureCertainAlwaysStopsUnderEveryPolicyCombination() {
        for (PoiScorer.Band band : new PoiScorer.Band[] {PoiScorer.Band.STRUCTURE_CERTAIN}) {
            for (UnavailablePolicy up : UnavailablePolicy.values()) {
                for (CavernKeylessPolicy cp : CavernKeylessPolicy.values()) {
                    for (boolean habitationLike : new boolean[] {false, true}) {
                        assertEquals(STOP, PoiDecisionPolicy.decide(band, 0.0D, habitationLike, up, cp),
                                band + "/" + up + "/" + cp + "/habitation=" + habitationLike);
                    }
                }
            }
        }
    }

    @Test
    void cavernOnlyFollowsOnlyTheCavernKeylessPolicyIndependentOfUnavailablePolicy() {
        for (UnavailablePolicy up : UnavailablePolicy.values()) {
            assertEquals(STOP, PoiDecisionPolicy.decide(PoiScorer.Band.CAVERN_ONLY, 0.0D, false, up,
                    CavernKeylessPolicy.STOP_IF_POSSIBLE));
            assertEquals(NOTIFY_ONLY, PoiDecisionPolicy.decide(PoiScorer.Band.CAVERN_ONLY, 1.0D, false, up,
                    CavernKeylessPolicy.NOTIFY_ONLY));
        }
    }

    @Test
    void possibleHabitationLikeIsAlwaysNotifyOnlyRegardlessOfUnavailablePolicy() {
        for (UnavailablePolicy up : UnavailablePolicy.values()) {
            assertEquals(NOTIFY_ONLY, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE, 1.0D, true, up,
                    CavernKeylessPolicy.NOTIFY_ONLY), up.name());
        }
    }

    @Test
    void possibleNotifyOnlyPolicyNeverStopsRegardlessOfScore() {
        assertEquals(NOTIFY_ONLY, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE, 1.0D, false,
                UnavailablePolicy.NOTIFY_ONLY, CavernKeylessPolicy.NOTIFY_ONLY));
        assertEquals(NOTIFY_ONLY, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE, 0.0D, false,
                UnavailablePolicy.NOTIFY_ONLY, CavernKeylessPolicy.NOTIFY_ONLY));
    }

    @Test
    void possibleStopIfPossiblePolicyAlwaysStopsRegardlessOfScore() {
        assertEquals(STOP, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE, 0.0D, false,
                UnavailablePolicy.STOP_IF_POSSIBLE, CavernKeylessPolicy.NOTIFY_ONLY));
        assertEquals(STOP, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE, 1.0D, false,
                UnavailablePolicy.STOP_IF_POSSIBLE, CavernKeylessPolicy.NOTIFY_ONLY));
    }

    @Test
    void possibleStopIfStructurePolicyStopsOnlyAtOrAboveTheFallbackScoreThreshold() {
        assertEquals(NOTIFY_ONLY, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE,
                PoiDecisionPolicy.FALLBACK_STOP_SCORE - 0.01D, false,
                UnavailablePolicy.STOP_IF_STRUCTURE, CavernKeylessPolicy.NOTIFY_ONLY));
        assertEquals(STOP, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE,
                PoiDecisionPolicy.FALLBACK_STOP_SCORE, false,
                UnavailablePolicy.STOP_IF_STRUCTURE, CavernKeylessPolicy.NOTIFY_ONLY));
        assertEquals(STOP, PoiDecisionPolicy.decide(PoiScorer.Band.POSSIBLE, 1.0D, false,
                UnavailablePolicy.STOP_IF_STRUCTURE, CavernKeylessPolicy.NOTIFY_ONLY));
    }

    @Test
    void theFallbackStopScoreConstantIsTheDesignLiteral075() {
        assertEquals(0.75D, PoiDecisionPolicy.FALLBACK_STOP_SCORE, 1.0e-12D);
    }
}
