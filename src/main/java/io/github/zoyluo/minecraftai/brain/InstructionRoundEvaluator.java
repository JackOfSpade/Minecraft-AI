package io.github.zoyluo.minecraftai.brain;

/**
 * The pure decision logic behind the per-instruction flag lifecycle of {@link BrainCoordinator}:
 * whether the player's request has started, whether the round still owes an action, whether the
 * next call must be forced into an action tool (say withheld), and what a budget end or a task
 * failure must tell the player. Kept free of any game object so every rule is unit-testable.
 *
 * <p>The central distinction is "this instruction's request has started" versus "some runtime work
 * is active". Unrelated autonomous work (a flee, auto-eat, a leftover task) says nothing about the
 * player's command, and treating it as progress was the "commands silently dropped" bug.</p>
 */
final class InstructionRoundEvaluator {
    private InstructionRoundEvaluator() {
    }

    /** What one tool-call round looked like. */
    record ToolRound(boolean requestStartedBefore,
                     /* a plan was spoken this instruction, including one announced in this very round */
                     boolean planSpoken,
                     /* this call was made only to report a task failure to the player */
                     boolean failureReportCall,
                     /* any runtime work at all is active (goal, task, action pack), whoever started it */
                     boolean workActive,
                     /* a work-start tool of THIS round succeeded */
                     boolean workStartToolSucceeded,
                     boolean answerOnly,
                     boolean controlOnly,
                     int failedToolCalls,
                     boolean planBlockedAction) {
    }

    /** The instruction-level state after a round. */
    record RoundOutcome(boolean requestStarted,
                        boolean missingRequiredAction,
                        boolean lastRoundMissingRequiredAction,
                        boolean withholdSayNextCall,
                        /* whether the planner loop must go on (another model call) */
                        boolean keepGoing) {
    }

    static RoundOutcome evaluateToolRound(ToolRound round) {
        boolean started = round.requestStartedBefore() || round.workStartToolSucceeded();
        if (round.failureReportCall()) {
            // A failure report is a say-only job about a task that already ran: it is not an
            // unfinished player request, so it neither demands an action nor forces the next call,
            // and it leaves the request flags exactly as they were (barring a real work start).
            boolean keepGoing = round.failedToolCalls() > 0 || (round.workActive() && started);
            return new RoundOutcome(started, false, false, false, keepGoing);
        }
        boolean missing = !round.answerOnly()
                && !started
                && !round.controlOnly()
                && round.failedToolCalls() == 0;
        boolean plannedWithoutActing = round.planSpoken() && !started;
        boolean withhold = shouldWithholdSay(missing, round.planBlockedAction(), started, plannedWithoutActing);
        // Unrelated running work must not keep the loop (and its apology) alive after a valid answer.
        boolean keepGoing = missing
                || round.planBlockedAction()
                || round.failedToolCalls() > 0
                || (round.workActive() && started);
        return new RoundOutcome(started, missing, missing || round.planBlockedAction(), withhold, keepGoing);
    }

    /** What a response with no tool call at all means for the request. */
    record TextOnlyOutcome(boolean requestOutstanding, boolean withholdSayNextCall) {
    }

    static TextOnlyOutcome evaluateTextOnlyRound(boolean requestStarted, boolean planSpoken, boolean failureReportCall) {
        if (requestStarted || failureReportCall) {
            return new TextOnlyOutcome(false, false);
        }
        // Still nothing started: a previously announced plan keeps the say tool withheld, otherwise
        // the model is offered say again on the retry.
        return new TextOnlyOutcome(true, shouldWithholdSay(true, false, false, planSpoken));
    }

    static boolean shouldWithholdSay(boolean missingRequiredAction,
                                     boolean planBlockedAction,
                                     boolean requestStarted,
                                     boolean roundAnnouncedPlan) {
        return missingRequiredAction
                && !planBlockedAction
                && !requestStarted
                && roundAnnouncedPlan;
    }

    /** What the player is told when the model-call budget ends. */
    enum BudgetReport {
        /** The request is under way (or already reported): stay quiet. */
        SILENT,
        /** The planner never started the instruction. */
        COULD_NOT_START,
        /** Started, but nothing is running any more. */
        COULD_NOT_WORK_OUT
    }

    static BudgetReport budgetReport(boolean alreadyReported,
                                     boolean workActive,
                                     boolean requestStarted,
                                     boolean hasInstruction,
                                     boolean automaticWake) {
        boolean neverStarted = hasInstruction && !requestStarted && !automaticWake;
        if (alreadyReported || (workActive && !neverStarted)) {
            return BudgetReport.SILENT;
        }
        return neverStarted ? BudgetReport.COULD_NOT_START : BudgetReport.COULD_NOT_WORK_OUT;
    }

    /** What a pending task failure does when the brain wakes for it. */
    enum FailureWake {
        /** No failure is pending. */
        NONE,
        /** Feed the failure to the model and submit a failure-report call. */
        INJECT,
        /** No call is left: the player was told directly, and that is the only report. */
        REPORT_DIRECTLY
    }

    static FailureWake failureWake(boolean failurePending, boolean failureReportCallAvailable) {
        if (!failurePending) {
            return FailureWake.NONE;
        }
        return failureReportCallAvailable ? FailureWake.INJECT : FailureWake.REPORT_DIRECTLY;
    }
}
