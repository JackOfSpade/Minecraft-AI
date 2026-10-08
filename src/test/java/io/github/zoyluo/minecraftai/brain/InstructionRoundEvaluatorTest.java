package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.brain.InstructionRoundEvaluator.BudgetReport;
import io.github.zoyluo.minecraftai.brain.InstructionRoundEvaluator.FailureWake;
import io.github.zoyluo.minecraftai.brain.InstructionRoundEvaluator.RoundOutcome;
import io.github.zoyluo.minecraftai.brain.InstructionRoundEvaluator.TextOnlyOutcome;
import io.github.zoyluo.minecraftai.brain.InstructionRoundEvaluator.ToolRound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests of the per-instruction flag lifecycle (request started, say withheld, failure
 * report rounds, budget-end and failure reporting). These are the rules behind "a command must never
 * be silently dropped"; the coordinator itself only wires them to game state.
 */
final class InstructionRoundEvaluatorTest {
    private static ToolRound round(boolean startedBefore,
                                   boolean planSpoken,
                                   boolean failureReportCall,
                                   boolean workActive,
                                   boolean workStartSucceeded,
                                   boolean answerOnly,
                                   boolean controlOnly,
                                   int failed,
                                   boolean planBlocked) {
        return new ToolRound(startedBefore, planSpoken, failureReportCall, workActive, workStartSucceeded,
                false, answerOnly, controlOnly, failed, planBlocked);
    }

    private static ToolRound continuousRound(boolean startedBefore,
                                             boolean planSpoken,
                                             boolean workActive,
                                             boolean workStartSucceeded,
                                             int failed) {
        return new ToolRound(startedBefore, planSpoken, false, workActive, workStartSucceeded,
                true, false, false, failed, false);
    }

    private static ToolRound planOnlyRound(boolean workActive) {
        return round(false, true, false, workActive, false, false, false, 0, false);
    }

    // ---- say withheld after a plan-only round -------------------------------------------------

    @Test
    void aPlanOnlyRoundWithholdsSayFromTheNextCall() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(planOnlyRound(false));

        assertTrue(outcome.missingRequiredAction());
        assertTrue(outcome.withholdSayNextCall());
        assertTrue(outcome.keepGoing());
        assertFalse(outcome.requestStarted());
    }

    @Test
    void aRoundWithoutAPlanNeverWithholdsSay() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                round(false, false, false, false, false, false, false, 0, false));

        assertTrue(outcome.missingRequiredAction());
        assertFalse(outcome.withholdSayNextCall());
    }

    @Test
    void blockedActionCallsStillNeedSayToAnnounceThePlan() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                round(false, true, false, false, false, false, false, 0, true));

        assertFalse(outcome.withholdSayNextCall());
        assertTrue(outcome.lastRoundMissingRequiredAction());
        assertTrue(outcome.keepGoing());
    }

    @Test
    void aTextOnlyRoundKeepsSayWithheldWhileTheAnnouncedPlanIsUnstarted() {
        // The previous call was already withheld (onResponse cleared the flag); the text-only branch
        // must recompute it, otherwise the retry offers say again and the loop resumes.
        TextOnlyOutcome outcome = InstructionRoundEvaluator.evaluateTextOnlyRound(false, true, false);

        assertTrue(outcome.requestOutstanding());
        assertTrue(outcome.withholdSayNextCall());
    }

    @Test
    void aTextOnlyRoundWithoutAPlanKeepsSayAvailable() {
        TextOnlyOutcome outcome = InstructionRoundEvaluator.evaluateTextOnlyRound(false, false, false);

        assertTrue(outcome.requestOutstanding());
        assertFalse(outcome.withholdSayNextCall());
    }

    @Test
    void aTextOnlyReplyAfterTheRequestStartedOrAsAFailureReportIsSimplyDone() {
        for (TextOnlyOutcome outcome : new TextOnlyOutcome[] {
                InstructionRoundEvaluator.evaluateTextOnlyRound(true, true, false),
                InstructionRoundEvaluator.evaluateTextOnlyRound(false, true, true)}) {
            assertFalse(outcome.requestOutstanding());
            assertFalse(outcome.withholdSayNextCall());
        }
    }

    // ---- request started is independent of unrelated work --------------------------------------

    @Test
    void unrelatedRunningWorkDoesNotMarkTheRequestStarted() {
        // A flee or auto-eat is running, the model only announced a plan: the command has NOT started.
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(planOnlyRound(true));

        assertFalse(outcome.requestStarted());
        assertTrue(outcome.missingRequiredAction());
        assertTrue(outcome.withholdSayNextCall());
        assertTrue(outcome.keepGoing());
    }

    @Test
    void aSucceededWorkStartToolStartsTheRequestAndItStaysStarted() {
        RoundOutcome first = InstructionRoundEvaluator.evaluateToolRound(
                round(false, true, false, true, true, false, false, 0, false));
        assertTrue(first.requestStarted());
        assertFalse(first.missingRequiredAction());
        assertFalse(first.withholdSayNextCall());
        assertTrue(first.keepGoing());

        // A later status-only round with the work finished must not un-start it.
        RoundOutcome later = InstructionRoundEvaluator.evaluateToolRound(
                round(first.requestStarted(), true, false, false, false, false, false, 0, false));
        assertTrue(later.requestStarted());
        assertFalse(later.missingRequiredAction());
        assertFalse(later.keepGoing());
    }

    @Test
    void aSucceededStandingOrderCompletesTheDecisionWithoutPollingForever() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                continuousRound(false, true, true, true, 0));

        assertTrue(outcome.requestStarted());
        assertFalse(outcome.missingRequiredAction());
        assertFalse(outcome.keepGoing(), "follow/hold/guard are complete once their standing task is active");

        RoundOutcome failed = InstructionRoundEvaluator.evaluateToolRound(
                continuousRound(false, true, true, true, 1));
        assertTrue(failed.keepGoing(), "a failed call still gets a repair turn even beside a standing order");
    }

    @Test
    void aValidAnswerWhileUnrelatedWorkRunsCompletesInsteadOfLoopingIntoAnApology() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                round(false, false, false, true, false, true, false, 0, false));

        assertFalse(outcome.missingRequiredAction());
        assertFalse(outcome.keepGoing());
        assertFalse(outcome.requestStarted());
    }

    @Test
    void aFailedToolCallKeepsThePlannerGoing() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                round(false, true, false, false, false, false, false, 1, false));

        assertFalse(outcome.missingRequiredAction(), "the failure is repaired, not reported as a missing action");
        assertTrue(outcome.keepGoing());
    }

    @Test
    void anUnstartedRequestIsReportedEvenWhileUnrelatedWorkRuns() {
        // The originally silent case: budget spent, unrelated work active, the command never started.
        assertEquals(BudgetReport.COULD_NOT_START,
                InstructionRoundEvaluator.budgetReport(false, true, false, true, false));
        assertEquals(BudgetReport.COULD_NOT_START,
                InstructionRoundEvaluator.budgetReport(false, false, false, true, false));
    }

    @Test
    void aStartedRequestStaysQuietWhileItsWorkRunsAndApologisesOnlyWhenNothingRuns() {
        assertEquals(BudgetReport.SILENT, InstructionRoundEvaluator.budgetReport(false, true, true, true, false));
        assertEquals(BudgetReport.COULD_NOT_WORK_OUT,
                InstructionRoundEvaluator.budgetReport(false, false, true, true, false));
    }

    @Test
    void aPausedMissionKeepsItsCursorButNeverBuysSilenceForAnUnstartedInstruction() {
        // finishCallBudget counts a paused mission as work for the report (workActive = runtime work OR paused),
        // and as a reason not to reset; only the request-started fact decides what the player hears.
        assertFalse(InstructionRoundEvaluator.budgetEndWork(false, true).resetToIdle(),
                "a paused mission's cursor must survive the budget end");
        assertFalse(InstructionRoundEvaluator.budgetEndWork(true, false).resetToIdle());
        assertFalse(InstructionRoundEvaluator.budgetEndWork(true, true).resetToIdle());
        assertTrue(InstructionRoundEvaluator.budgetEndWork(false, false).resetToIdle());
        assertTrue(InstructionRoundEvaluator.budgetEndWork(false, true).workActive(), "a paused mission counts as work for the report");
        assertTrue(InstructionRoundEvaluator.budgetEndWork(true, false).workActive());
        assertFalse(InstructionRoundEvaluator.budgetEndWork(false, false).workActive());

        boolean workActiveBecauseOfPausedMission = true;
        assertEquals(BudgetReport.COULD_NOT_START,
                InstructionRoundEvaluator.budgetReport(false, workActiveBecauseOfPausedMission, false, true, false),
                "the paused frame protects the mission, not the silence");
        assertEquals(BudgetReport.SILENT,
                InstructionRoundEvaluator.budgetReport(false, workActiveBecauseOfPausedMission, true, true, false),
                "a started instruction whose mission is merely paused stays quiet");
    }

    @Test
    void theBudgetEndIsReportedAtMostOnceAndNeverForAnAutomaticWakeOrANonInstruction() {
        assertEquals(BudgetReport.SILENT, InstructionRoundEvaluator.budgetReport(true, false, false, true, false));
        // An automatic wake is not a request that "never started".
        assertEquals(BudgetReport.SILENT, InstructionRoundEvaluator.budgetReport(false, true, false, true, true));
        // No player instruction at all (an autonomous goal wake): never claims a command failed to start.
        assertEquals(BudgetReport.SILENT, InstructionRoundEvaluator.budgetReport(false, true, false, false, false));
        assertEquals(BudgetReport.COULD_NOT_WORK_OUT,
                InstructionRoundEvaluator.budgetReport(false, false, false, false, false));
    }

    // ---- failure-report rounds leave the request flags alone -----------------------------------

    @Test
    void aSayOnlyFailureReportRoundDoesNotTouchTheRequestFlags() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                round(false, true, true, false, false, false, false, 0, false));

        assertFalse(outcome.requestStarted(), "reporting a failure must not pretend the request started");
        assertFalse(outcome.missingRequiredAction());
        assertFalse(outcome.lastRoundMissingRequiredAction());
        assertFalse(outcome.withholdSayNextCall());
        assertFalse(outcome.keepGoing());
    }

    @Test
    void aFailureReportRoundKeepsAnAlreadyStartedRequestStarted() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                round(true, true, true, false, false, false, false, 0, false));

        assertTrue(outcome.requestStarted());
        assertFalse(outcome.missingRequiredAction());
    }

    @Test
    void aFailureReportRoundNeverDemandsAnActionEvenWithAnAnnouncedPlan() {
        RoundOutcome outcome = InstructionRoundEvaluator.evaluateToolRound(
                round(false, true, true, true, false, false, false, 0, true));

        assertFalse(outcome.missingRequiredAction());
        assertFalse(outcome.withholdSayNextCall());
        assertFalse(outcome.requestStarted(), "running unrelated work still is not the request");
    }

    // ---- exactly one apology per failure -------------------------------------------------------

    @Test
    void aFailureWithACallAvailableIsInjectedNotApologisedForDirectly() {
        assertEquals(FailureWake.INJECT, InstructionRoundEvaluator.failureWake(true, true));
        assertEquals(FailureWake.NONE, InstructionRoundEvaluator.failureWake(false, true));
        assertEquals(FailureWake.NONE, InstructionRoundEvaluator.failureWake(false, false));
    }

    @Test
    void whenEveryCallIsSpentTheFailureIsReportedDirectlyExactlyOnce() {
        PlayerInstructionCallBudget budget =
                new PlayerInstructionCallBudget(PlayerInstructionCallBudget.DEFAULT_MAX_CALLS);
        budget.beginPlayerInstruction();
        while (budget.tryAcquireModelCall()) {
            // planner allowance spent
        }
        // The dedicated failure allowance still serves two reports through the model.
        for (int i = 0; i < PlayerInstructionCallBudget.MAX_FAILURE_REPORT_CALLS; i++) {
            assertEquals(FailureWake.INJECT,
                    InstructionRoundEvaluator.failureWake(true, budget.canAcquireFailureReportCall()));
            budget.tryAcquireFailureReportCall();
        }
        assertTrue(budget.exhausted());

        // Then the failure is reported directly, and that is the whole report: REPORT_DIRECTLY is a
        // distinct verdict (not "no failure"), which is what lets the coordinator return right there
        // instead of falling through to the exhausted-budget apology.
        FailureWake wake = InstructionRoundEvaluator.failureWake(true, budget.canAcquireFailureReportCall());
        assertEquals(FailureWake.REPORT_DIRECTLY, wake);
    }

    @Test
    void aFailureReportCallLeavesTheSayWithholdingFlagAsItWas() {
        // A plan-only loop interrupted by a failure wake: the flag was true before the failure call ...
        RoundOutcome failureRound = InstructionRoundEvaluator.evaluateToolRound(
                round(false, true, true, false, false, false, false, 0, false));
        assertFalse(failureRound.withholdSayNextCall(), "the failure round itself has no opinion (it reports false)");
        // ... and must still be true for the next planner call.
        assertTrue(InstructionRoundEvaluator.nextWithholdSay(true, true, failureRound.withholdSayNextCall()));
        assertFalse(InstructionRoundEvaluator.nextWithholdSay(false, true, failureRound.withholdSayNextCall()));
        // An ordinary call replaces the flag with the round's own verdict, whatever it was before.
        assertTrue(InstructionRoundEvaluator.nextWithholdSay(false, false, true));
        assertFalse(InstructionRoundEvaluator.nextWithholdSay(true, false, false));
        // The text-only variant of a failure report likewise reports false and must not clear the flag.
        TextOnlyOutcome textOnly = InstructionRoundEvaluator.evaluateTextOnlyRound(false, true, true);
        assertTrue(InstructionRoundEvaluator.nextWithholdSay(true, true, textOnly.withholdSayNextCall()));
    }

    @Test
    void anAutonomousWakeDoesNotBlameTheOldPlayerInstruction() {
        InstructionRoundEvaluator.InstructionChain chain = new InstructionRoundEvaluator.InstructionChain();
        assertFalse(chain.playerInstruction());
        chain.beginPlayerInstruction();
        // An answer-only instruction never started work, and its budget ending is reported as never started.
        assertEquals(BudgetReport.COULD_NOT_START,
                InstructionRoundEvaluator.budgetReport(false, false, false, chain.playerInstruction(), false));
        // A later autonomous wake (goal / task-finished / failure) whose chain ends with a non-automatic
        // trigger must not tell the player it could not start that OLD instruction.
        chain.beginAutonomousWake();
        assertFalse(chain.playerInstruction());
        assertEquals(BudgetReport.COULD_NOT_WORK_OUT,
                InstructionRoundEvaluator.budgetReport(false, false, false, chain.playerInstruction(), false));
        // A new player instruction re-arms it.
        chain.beginPlayerInstruction();
        assertTrue(chain.playerInstruction());
    }

    // ---- deferred (SAFETY-blocked) requests: the player hears at most one budget-end report --------

    @Test
    void aDeferredRequestsBudgetEndIsSilentAndTheReWokenRoundReportsExactlyOnce() {
        // Round 1: the model's task tool was blocked by a running SAFETY task; the request is deferred.
        // Whatever the ordinary report would be (the instruction never started), the player hears nothing yet.
        BudgetReport ordinary = InstructionRoundEvaluator.budgetReport(false, false, false, true, false);
        assertEquals(BudgetReport.COULD_NOT_START, ordinary);
        assertEquals(BudgetReport.SILENT, InstructionRoundEvaluator.budgetReportUnlessDeferred(true, ordinary));
        assertEquals(BudgetReport.SILENT, InstructionRoundEvaluator.budgetReportUnlessDeferred(true,
                InstructionRoundEvaluator.budgetReport(false, false, true, true, false)));
        // The deferred silence never marks the round as reported, so the re-woken round (no longer deferred,
        // fresh budget) can still tell the player once if it too never starts ...
        BudgetReport reWoken = InstructionRoundEvaluator.budgetReportUnlessDeferred(false, ordinary);
        assertEquals(BudgetReport.COULD_NOT_START, reWoken);
        // ... and only once: after that report the flag is set and every later budget end is silent.
        assertEquals(BudgetReport.SILENT, InstructionRoundEvaluator.budgetReportUnlessDeferred(false,
                InstructionRoundEvaluator.budgetReport(true, false, false, true, false)));
    }

    @Test
    void theBudgetEndWorkHelperReturnsTheReportSignalAndTheResetDecisionTogether() {
        InstructionRoundEvaluator.BudgetEndWork idle = InstructionRoundEvaluator.budgetEndWork(false, false);
        assertFalse(idle.workActive());
        assertTrue(idle.resetToIdle());
        InstructionRoundEvaluator.BudgetEndWork paused = InstructionRoundEvaluator.budgetEndWork(false, true);
        assertTrue(paused.workActive());
        assertFalse(paused.resetToIdle(), "a paused mission's cursor must survive the budget end");
        InstructionRoundEvaluator.BudgetEndWork running = InstructionRoundEvaluator.budgetEndWork(true, false);
        assertTrue(running.workActive());
        assertFalse(running.resetToIdle());
        // workActive and the reset decision can never disagree (no reset while work is active).
        for (boolean runtime : new boolean[]{false, true}) {
            for (boolean pausedMission : new boolean[]{false, true}) {
                InstructionRoundEvaluator.BudgetEndWork state =
                        InstructionRoundEvaluator.budgetEndWork(runtime, pausedMission);
                assertEquals(!state.workActive(), state.resetToIdle());
            }
        }
    }

    @Test
    void aFailureWakeKeepsThePlanOnlyLoopsSayWithholding() {
        // A failure injected into an unfinished plan-only loop (the flag was set) keeps the flag ...
        assertTrue(InstructionRoundEvaluator.withholdSayAfterAutonomousWake(true, true));
        // ... and the failure-report round then leaves it alone (nextWithholdSay pass-through).
        assertTrue(InstructionRoundEvaluator.nextWithholdSay(
                InstructionRoundEvaluator.withholdSayAfterAutonomousWake(true, true), true, false));
        // Every other wake starts with the flag cleared.
        assertFalse(InstructionRoundEvaluator.withholdSayAfterAutonomousWake(true, false));
        assertFalse(InstructionRoundEvaluator.withholdSayAfterAutonomousWake(false, true));
        assertFalse(InstructionRoundEvaluator.withholdSayAfterAutonomousWake(false, false));
    }
}
