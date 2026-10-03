package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MissionDecisionCallBudgetTest {
    @Test
    void boundsRepairsWithinOneCheckpointButAllowsASeparateLaterCheckpoint() {
        MissionDecisionCallBudget budget = new MissionDecisionCallBudget(2);

        budget.beginBoundary();
        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertFalse(budget.tryAcquireModelCall());
        assertTrue(budget.exhausted());
        assertEquals(1L, budget.boundarySequence());

        budget.beginBoundary();
        assertFalse(budget.exhausted());
        assertEquals(0, budget.callsUsed());
        assertEquals(2L, budget.boundarySequence());
        assertTrue(budget.tryAcquireModelCall());
    }

    @Test
    void failedSubmissionCanReleaseOnlyItsOwnReservation() {
        MissionDecisionCallBudget budget = new MissionDecisionCallBudget(1);
        budget.beginBoundary();
        assertTrue(budget.tryAcquireModelCall());
        budget.releaseLastReservation();
        assertFalse(budget.exhausted());
        assertTrue(budget.tryAcquireModelCall());
    }
}
