package io.github.zoyluo.minecraftai.brain;

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

    @Test
    void failureReportsHaveTheirOwnGuaranteedCallEvenWhenThePlannerBudgetIsSpent() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();
        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.tryAcquireModelCall());
        assertTrue(budget.exhausted());

        // The live bug: a follow abort long after the planner spent its calls was never reported.
        assertTrue(budget.canAcquireFailureReportCall());
        assertEquals(PlayerInstructionCallBudget.Reservation.FAILURE_REPORT, budget.tryAcquireFailureReportCall());
        assertEquals(3, budget.callsUsed(), "a failure report must not consume the planner's calls");
        assertEquals(1, budget.failureReportCallsUsed());
    }

    @Test
    void failureReportsPreferTheirOwnAllowanceAndKeepPlannerCallsForRetries() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();

        assertEquals(PlayerInstructionCallBudget.Reservation.FAILURE_REPORT, budget.tryAcquireFailureReportCall());
        assertEquals(0, budget.callsUsed());
    }

    @Test
    void failureReportsAreCappedSoAFailingTaskCannotLoopTheModel() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();
        for (int call = 0; call < PlayerInstructionCallBudget.DEFAULT_MAX_CALLS; call++) {
            budget.tryAcquireModelCall();
        }
        for (int report = 0; report < PlayerInstructionCallBudget.MAX_FAILURE_REPORT_CALLS; report++) {
            assertEquals(PlayerInstructionCallBudget.Reservation.FAILURE_REPORT, budget.tryAcquireFailureReportCall());
        }

        assertFalse(budget.canAcquireFailureReportCall());
        assertEquals(PlayerInstructionCallBudget.Reservation.NONE, budget.tryAcquireFailureReportCall());
        assertEquals(3, budget.callsUsed());
    }

    @Test
    void anUnspentPlannerAllowanceBacksUpASpentFailureAllowance() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();
        budget.tryAcquireFailureReportCall();
        budget.tryAcquireFailureReportCall();

        assertEquals(PlayerInstructionCallBudget.Reservation.REGULAR, budget.tryAcquireFailureReportCall());
        assertEquals(1, budget.callsUsed());
    }

    @Test
    void aFailureReservationCanBeRolledBackFromItsOwnAllowance() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();
        PlayerInstructionCallBudget.Reservation reservation = budget.tryAcquireFailureReportCall();

        budget.releaseFailureReportReservation(reservation);

        assertEquals(0, budget.failureReportCallsUsed());
        assertEquals(0, budget.callsUsed());
        budget.releaseFailureReportReservation(PlayerInstructionCallBudget.Reservation.NONE);
        assertThrows(IllegalStateException.class,
                () -> budget.releaseFailureReportReservation(PlayerInstructionCallBudget.Reservation.FAILURE_REPORT));
    }

    @Test
    void aReplacementPlayerInstructionRestoresTheFailureReportAllowanceToo() {
        PlayerInstructionCallBudget budget = new PlayerInstructionCallBudget(3);
        budget.beginPlayerInstruction();
        budget.tryAcquireFailureReportCall();
        budget.tryAcquireFailureReportCall();

        budget.beginPlayerInstruction();

        assertEquals(0, budget.failureReportCallsUsed());
        assertTrue(budget.canAcquireFailureReportCall());
    }
}
