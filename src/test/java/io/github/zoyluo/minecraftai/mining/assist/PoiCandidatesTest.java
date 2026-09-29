package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.OVERWORLD;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

class PoiCandidatesTest {
    private static final int RADIUS = 40;

    /** A POSSIBLE-class score: two non-weak buckets and enough weight (planks + rails). */
    private static PoiScorer.PoiScore possible() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 4; i++) {
            BlockFacts planks = facts("oak_planks");
            window.observe(new BlockPos(i, 64, 0), planks.bucket(), planks.poiFlags(), 1, false);
        }
        for (int i = 0; i < 4; i++) {
            BlockFacts rail = facts("rail");
            window.observe(new BlockPos(i, 63, 0), rail.bucket(), rail.poiFlags(), 1, false);
        }
        PoiScorer.PoiScore score = PoiScorer.evaluate(PoiAssembler.assemble(window, 0.5D, 64.0D, 0.5D, null, null,
                16.0D, OVERWORLD, java.util.List.of(OVERWORLD)));
        assertTrue(score.possibleGate(), "fixture must pass the possible gate: " + score);
        return score;
    }

    private static PoiScorer.PoiScore quiet() {
        return PoiScorer.evaluate(PoiSignals.empty());
    }

    private static final BlockPos SITE = new BlockPos(10, 64, 10);

    @Test
    void aQuietEvaluationCreatesNoCandidate() {
        PoiCandidates candidates = new PoiCandidates();
        PoiCandidates.Tracked tracked = candidates.record(SITE, quiet(), 100, RADIUS);
        assertNull(tracked.candidate());
        assertFalse(tracked.satisfied());
        assertEquals(0, candidates.size());
    }

    @Test
    void twoGatedEvaluationsTwentyTicksApartSatisfyTheHysteresis() {
        PoiCandidates candidates = new PoiCandidates();
        PoiCandidates.Tracked first = candidates.record(SITE, possible(), 100, RADIUS);
        assertFalse(first.satisfied());
        assertEquals(1, first.hits());
        PoiCandidates.Tracked second = candidates.record(SITE.east(), possible(), 120, RADIUS);
        assertTrue(second.satisfied());
        assertSame(first.candidate(), second.candidate(), "same site: same candidate");
        assertEquals(1, candidates.size());
    }

    @Test
    void aMissBetweenTwoHitsStillSatisfiesTwoOfThree() {
        PoiCandidates candidates = new PoiCandidates();
        candidates.record(SITE, possible(), 100, RADIUS);
        assertFalse(candidates.record(SITE, quiet(), 120, RADIUS).satisfied());
        assertTrue(candidates.record(SITE, possible(), 140, RADIUS).satisfied());
    }

    @Test
    void evaluationsCloserThanTwentyTicksDoNotStuffTheWindow() {
        PoiCandidates candidates = new PoiCandidates();
        candidates.record(SITE, possible(), 100, RADIUS);
        assertFalse(candidates.record(SITE, possible(), 105, RADIUS).satisfied());
        assertFalse(candidates.record(SITE, possible(), 110, RADIUS).satisfied());
        assertTrue(candidates.record(SITE, possible(), 120, RADIUS).satisfied());
    }

    @Test
    void aFarAwaySiteIsAnotherCandidate() {
        PoiCandidates candidates = new PoiCandidates();
        PoiCandidates.Tracked a = candidates.record(SITE, possible(), 100, RADIUS);
        PoiCandidates.Tracked b = candidates.record(SITE.offset(200, 0, 0), possible(), 120, RADIUS);
        assertNotSame(a.candidate(), b.candidate());
        assertFalse(b.satisfied());
        assertEquals(2, candidates.size());
    }

    @Test
    void candidatesAreCappedAndTheStalestIsEvicted() {
        PoiCandidates candidates = new PoiCandidates();
        for (int i = 0; i < PoiCandidates.MAX_CANDIDATES; i++) {
            candidates.record(SITE.offset(i * 500, 0, 0), possible(), 100 + i, RADIUS);
        }
        assertEquals(PoiCandidates.MAX_CANDIDATES, candidates.size());
        candidates.record(SITE.offset(9999, 0, 0), possible(), 110, RADIUS);
        assertEquals(PoiCandidates.MAX_CANDIDATES, candidates.size());
        assertTrue(candidates.snapshot().stream().noneMatch(c -> c.anchor().equals(SITE)),
                "the candidate with the oldest last tick was dropped");
    }

    @Test
    void aSilentCandidateExpiresWithTheEvidenceWindow() {
        PoiCandidates candidates = new PoiCandidates();
        candidates.record(SITE, possible(), 100, RADIUS);
        assertEquals(0, candidates.prune(100 + PoiScorer.EVIDENCE_WINDOW_TICKS));
        assertEquals(1, candidates.prune(100 + PoiScorer.EVIDENCE_WINDOW_TICKS + 1));
        assertEquals(0, candidates.size());
    }

    @Test
    void theAnchorFollowsTheLatestEvaluation() {
        PoiCandidates candidates = new PoiCandidates();
        candidates.record(SITE, possible(), 100, RADIUS);
        PoiCandidates.Tracked moved = candidates.record(SITE.offset(20, 0, 0), possible(), 120, RADIUS);
        assertEquals(SITE.offset(20, 0, 0), moved.candidate().anchor());
        assertEquals(100, moved.candidate().firstTick());
        assertEquals(120, moved.candidate().lastTick());
    }

    @Test
    void clearForgetsEverything() {
        PoiCandidates candidates = new PoiCandidates();
        candidates.record(SITE, possible(), 100, RADIUS);
        candidates.clear();
        assertEquals(0, candidates.size());
    }
}
