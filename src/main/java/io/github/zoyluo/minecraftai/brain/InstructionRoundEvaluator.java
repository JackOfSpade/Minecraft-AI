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
                     /* a successfully-started standing order (follow/hold/guard) remains active until replaced */
                     boolean continuousWorkStarted,
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
        // A standing order has already completed the player's request by becoming active; it
        // must not keep an LLM continuation open just because its task correctly never ends.
        // Finite work still gets the ordinary completion/failure continuation, and a failed
        // tool call always gets a repair round even if the same response also started a standing
        // order.
        boolean keepGoing = missing
                || round.planBlockedAction()
                || round.failedToolCalls() > 0
                || (round.workActive() && started && !round.continuousWorkStarted());
        return new RoundOutcome(started, missing, missing || round.planBlockedAction(), withhold, keepGoing);
    }

    /**
     * The say-withholding flag for the NEXT call. A failure-report call is a say-only side job about a
     * task that already ran: it must leave the flag exactly as it was, so a plan-only loop interrupted by a
     * failure wake keeps withholding say afterwards. Every other call replaces the flag with the round's
     * own verdict.
     */
    static boolean nextWithholdSay(boolean currentFlag, boolean failureReportCall, boolean roundVerdict) {
        return failureReportCall ? currentFlag : roundVerdict;
    }

    /**
     * Whether the current model-call chain belongs to a player instruction. Set when the player gives one,
     * cleared when an autonomous wake (goal continuation, task-finished, failure) starts a chain of its
     * own: the last instruction text is never cleared, so its mere presence must not make a later
     * autonomous wake claim that the OLD instruction could not be started.
     */
    static final class InstructionChain {
        private boolean playerInstruction;

        void beginPlayerInstruction() {
            playerInstruction = true;
        }

        void beginAutonomousWake() {
            playerInstruction = false;
        }

        boolean playerInstruction() {
            return playerInstruction;
        }
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

    /**
     * A request that a running SAFETY task blocked is deferred, not dropped: the blocked round's budget
     * end stays silent, whatever the report would otherwise be. The player is then told at most once,
     * by the budget end of the re-woken round (which is no longer deferred and starts with the
     * "already reported" flag cleared).
     */
    static BudgetReport budgetReportUnlessDeferred(boolean requestDeferred, BudgetReport report) {
        return requestDeferred ? BudgetReport.SILENT : report;
    }

    /**
     * What the end of the model-call budget does about the bot's runtime work. {@code workActive} is the
     * work signal for the report and the log: running work, or a mission that a safety task paused.
     * {@code resetToIdle} says whether the budget end may wipe the runtime state (goal plan, task stack,
     * actions): running work protects it, and so does a paused mission -- resetting would destroy that
     * paused cursor although DangerWatcher resumes it once the threat is gone. This only guards the
     * state; whether the player is told anything is decided by {@link #budgetReport} from the
     * request-started fact alone, so a paused mission never buys silence for an instruction that never
     * started.
     */
    record BudgetEndWork(boolean workActive, boolean resetToIdle) {
    }

    static BudgetEndWork budgetEndWork(boolean runtimeWork, boolean pausedMission) {
        boolean workActive = runtimeWork || pausedMission;
        return new BudgetEndWork(workActive, !workActive);
    }

    /**
     * The say-withholding flag after an autonomous wake begins. Every wake starts with the flag cleared,
     * except a failure injected into an unfinished plan-only loop (the flag was set): that report is a
     * side job on the loop, which must keep withholding say afterwards. Without this the flag would be
     * cleared before the report and the {@link #nextWithholdSay} pass-through would preserve nothing.
     */
    static boolean withholdSayAfterAutonomousWake(boolean flagBeforeWake, boolean failureInjected) {
        return failureInjected && flagBeforeWake;
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
