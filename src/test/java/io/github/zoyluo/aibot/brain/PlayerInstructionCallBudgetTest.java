package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerInstructionCallBudgetTest {
    @Test
    void permitsInitialCallAndExactlyTwoRetries() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();

        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertEquals(3, budget.callsUsed());
        assertEquals(0, budget.callsRemaining());
        assertTrue(budget.exhausted());
        assertFalse(budget.tryAcquireModelCall());
    }

    @Test
    void automaticContinuationCannotResetThePlayerInstructionAllowance() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();
        assertTrue(budget.tryAcquireModelCall()); // Initial request.
        assertTrue(budget.tryAcquireModelCall()); // First automatic repair.

        // A task/goal/failure wake deliberately does not call beginPlayerInstruction().
        assertTrue(budget.tryAcquireModelCall());
        assertFalse(budget.tryAcquireModelCall());
    }

    @Test
    void onlyAReplacementPlayerInstructionRestoresTheAllowance() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();
        budget.tryAcquireModelCall();
        budget.tryAcquireModelCall();
        budget.tryAcquireModelCall();
        long firstInstruction = budget.instructionSequence();

        budget.beginPlayerInstruction();

        assertEquals(firstInstruction + 1, budget.instructionSequence());
        assertEquals(0, budget.callsUsed());
        assertEquals(3, budget.callsRemaining());
        assertTrue(budget.tryAcquireModelCall());
    }

    @Test
    void recipientRoutingConsumesOneOfTheThreeAllowedModelCalls() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);

        budget.beginPlayerInstruction(1);

        assertEquals(1, budget.callsUsed());
        assertEquals(2, budget.callsRemaining());
        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertFalse(budget.tryAcquireModelCall());
    }

    @Test
    void rejectsAnInvalidCap() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerInstructionCallBudget(0));
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        assertThrows(IllegalArgumentException.class, () -> budget.beginPlayerInstruction(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.beginPlayerInstruction(4));
    }

    @Test
    void configuredValueCannotRaiseTheHardThreeCallCeiling() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(12);
        budget.beginPlayerInstruction();

        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertFalse(budget.tryAcquireModelCall());
    }
}
