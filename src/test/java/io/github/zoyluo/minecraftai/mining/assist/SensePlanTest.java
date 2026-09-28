package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensePlanTest {
    /** Records which checks were evaluated, in order, and answers with a fixed value. */
    private static BooleanSupplier probe(List<String> calls, String name, boolean answer) {
        return () -> {
            calls.add(name);
            return answer;
        };
    }

    private static SensePlan.Verdict plan(boolean handled, boolean mining, boolean gate, boolean underground,
                                          List<String> calls) {
        return SensePlan.decide(handled,
                probe(calls, "task", mining), probe(calls, "gate", gate), probe(calls, "sky", underground));
    }

    @Test
    void aHandledTickDoesNothingAndEvaluatesNoCheck() {
        List<String> calls = new ArrayList<>();
        assertEquals(SensePlan.Verdict.HANDLED, plan(true, true, true, true, calls));
        assertTrue(calls.isEmpty(), "design 2.3 step 3d: if handled, stop here");
    }

    @Test
    void aBotThatIsNotMiningNeverPaysForTheGateOrTheSkyRead() {
        List<String> calls = new ArrayList<>();
        assertEquals(SensePlan.Verdict.NOT_MINING, plan(false, false, true, true, calls));
        assertEquals(List.of("task"), calls);
    }

    @Test
    void aClosedGateNeverPaysForTheSkyRead() {
        List<String> calls = new ArrayList<>();
        assertEquals(SensePlan.Verdict.GATE_CLOSED, plan(false, true, false, true, calls));
        assertEquals(List.of("task", "gate"), calls);
    }

    @Test
    void onTheSurfaceTheSensorStaysOff() {
        List<String> calls = new ArrayList<>();
        assertEquals(SensePlan.Verdict.SURFACE, plan(false, true, true, false, calls));
        assertEquals(List.of("task", "gate", "sky"), calls);
    }

    @Test
    void underGroundInAMiningTaskWithAnOpenGateSenses() {
        List<String> calls = new ArrayList<>();
        SensePlan.Verdict verdict = plan(false, true, true, true, calls);
        assertEquals(SensePlan.Verdict.SENSE, verdict);
        assertTrue(verdict.senses());
        assertEquals(List.of("task", "gate", "sky"), calls, "cheapest check first, gate before the own-cell read");
    }

    @Test
    void onlySenseSensesAndOnlyHandledIsNeutral() {
        for (SensePlan.Verdict verdict : SensePlan.Verdict.values()) {
            assertEquals(verdict == SensePlan.Verdict.SENSE, verdict.senses(), verdict.name());
            assertEquals(verdict == SensePlan.Verdict.HANDLED, verdict.neutral(), verdict.name());
        }
    }

    @Test
    void everyVerdictHasAStableSnakeCaseReasonToken() {
        List<String> seen = new ArrayList<>();
        for (SensePlan.Verdict verdict : SensePlan.Verdict.values()) {
            assertTrue(verdict.reason().matches("[a-z_]+"), verdict.reason());
            assertFalse(seen.contains(verdict.reason()), "reasons are unique: " + verdict.reason());
            seen.add(verdict.reason());
        }
        assertEquals("not_mining_task", SensePlan.Verdict.NOT_MINING.reason());
        assertEquals("gate_closed", SensePlan.Verdict.GATE_CLOSED.reason());
        assertEquals("surface", SensePlan.Verdict.SURFACE.reason());
    }
}
