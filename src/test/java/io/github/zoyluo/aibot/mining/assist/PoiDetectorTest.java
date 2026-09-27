package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.github.zoyluo.aibot.mining.assist.AssistTestSupport.BOT;
import static io.github.zoyluo.aibot.mining.assist.AssistTestSupport.OVERWORLD;
import static io.github.zoyluo.aibot.mining.assist.AssistTestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the PoiDetector adapter: scheduling, labels and log fields. The world-facing part is source-contract tested. */
class PoiDetectorTest {
    @Test
    void firstCallArmsAStaggerOfUpToFifteenTicksThenEvaluatesEveryTwentyTicks() {
        MiningAssistState state = new MiningAssistState(BOT);
        int stagger = BOT.hashCode() & 15;
        assertFalse(PoiDetector.due(state, 1000), "the first call only arms the stagger");
        assertEquals(1000 + stagger, state.nextPoiEvalTick());
        if (stagger > 0) {
            assertFalse(PoiDetector.due(state, 1000 + stagger - 1));
        }
        assertTrue(PoiDetector.due(state, 1000 + stagger));

        state.setNextPoiEvalTick(1000 + stagger + PoiScorer.EVAL_INTERVAL_TICKS);
        assertFalse(PoiDetector.due(state, 1000 + stagger + PoiScorer.EVAL_INTERVAL_TICKS - 1));
        assertTrue(PoiDetector.due(state, 1000 + stagger + PoiScorer.EVAL_INTERVAL_TICKS));
    }

    @Test
    void differentBotsGetDifferentStaggersSoTheyDoNotAllEvaluateTogether() {
        boolean differs = false;
        int first = -1;
        for (int i = 0; i < 32; i++) {
            MiningAssistState state = new MiningAssistState(new UUID(i * 7919L, i * 104729L));
            PoiDetector.due(state, 0);
            int offset = state.nextPoiEvalTick();
            assertTrue(offset >= 0 && offset <= 15);
            if (first < 0) {
                first = offset;
            } else if (offset != first) {
                differs = true;
            }
        }
        assertTrue(differs);
    }

    @Test
    void aTickThatMovedFarBackwardsIsDueAgainInsteadOfWaitingForever() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.setNextPoiEvalTick(90_000);
        assertTrue(PoiDetector.due(state, 100));
    }

    @Test
    void labelsFollowTheBand() {
        PoiSignals empty = PoiSignals.empty();
        assertEquals("", PoiDetector.labelFor(PoiScorer.Band.NONE, empty));
        assertEquals("cavern", PoiDetector.labelFor(PoiScorer.Band.CAVERN_ONLY, empty));
        assertEquals("warden_risk", PoiDetector.labelFor(PoiScorer.Band.MANDATORY, empty));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, PoiDetector.labelFor(PoiScorer.Band.POSSIBLE, empty));

        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 6; i++) {
            BlockFacts rail = facts("rail");
            window.observe(new BlockPos(i, 64, 0), rail.bucket(), rail.poiFlags(), 1, false);
        }
        PoiSignals mineshaft = PoiAssembler.assemble(window, 0.5D, 64.0D, 0.5D, null, null, 16.0D, OVERWORLD,
                List.of(OVERWORLD));
        assertEquals(PoiLabeler.MINESHAFT, PoiDetector.labelFor(PoiScorer.Band.STRUCTURE_CERTAIN, mineshaft));
    }

    @Test
    void logFieldsAreEvenKeyValuePairsWithoutASingleNullValue() {
        PoiScorer.PoiScore score = PoiScorer.evaluate(PoiSignals.empty());
        PoiDetector.Result result = new PoiDetector.Result(true, PoiScorer.Band.NONE, score, "", false, true,
                new BlockPos(1, 2, 3), 4, 0, 0, "minecraft:lush_caves", false);
        Object[] fields = result.logFields();
        assertEquals(0, fields.length % 2);
        for (int i = 0; i < fields.length; i += 2) {
            assertTrue(fields[i] instanceof String, "key at " + i);
            assertTrue(fields[i + 1] != null, "value of " + fields[i]);
        }
        assertTrue(List.of(fields).contains("band"));
        assertTrue(List.of(fields).contains("1,2,3"));
    }

    @Test
    void theNotRunResultIsInertAndLogsSafely() {
        PoiDetector.Result notRun = PoiDetector.Result.NOT_RUN;
        assertFalse(notRun.evaluated());
        assertFalse(notRun.confirmed());
        assertFalse(notRun.bandChanged());
        assertEquals(0, notRun.logFields().length % 2);
    }
}
