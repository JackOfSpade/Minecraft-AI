package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.task.TaskState;

/**
 * What happens when a model request failed for good (the service stayed unavailable for the whole
 * retry patience, or rejected the request, or answered with something unusable) and what the player
 * is told. Pure decision logic, kept free of any game object so every rule is unit-testable, like
 * {@link InstructionRoundEvaluator}.
 */
final class ApiFailureReport {
    /**
     * How long an autonomous goal wake stays off after a final failure: one retry patience window
     * ({@link LlmRetryPolicy#TOTAL_PATIENCE_MS}) in server ticks of 50 ms. The failed call has just spent
     * that whole window probing the service (or was rejected outright), so waking the brain again ten
     * seconds later would only start another full retry cycle against the same outage. A player's next
     * message clears the wait.
     */
    static final int GOAL_WAKE_COOLDOWN_TICKS = (int) (LlmRetryPolicy.TOTAL_PATIENCE_MS / 50L);

    private ApiFailureReport() {
    }

    enum Decision {
        REPORT,
        /** The player was already told about a failure of this instruction. */
        SILENT_ALREADY_REPORTED,
        /** The request already finished successfully: only the closing words were lost, and no failure text is due. */
        SILENT_REQUEST_COMPLETED
    }

    static Decision decide(boolean alreadyReported, boolean requestCompleted) {
        if (alreadyReported) {
            return Decision.SILENT_ALREADY_REPORTED;
        }
        return requestCompleted ? Decision.SILENT_REQUEST_COMPLETED : Decision.REPORT;
    }

    /**
     * Whether a failed call is worth one more planner call: the service did answer, but with something
     * unusable ({@link LlmApiException.Kind#UNUSABLE_REPLY}), and model output is not deterministic, so
     * the same request may well work the second time. The repair is a model call like any other and is
     * metered by the instruction's call budget. It is asked for once: a reply that is unusable twice in
     * a row is a standing fault (a proxy's error page, a broken schema), and asking a third time in the
     * same second would only burn the budget the way the 2026-10-05 burst did. A failure report is
     * exempt: it has the deterministic fallback line.
     *
     * @param repairCall the failed call was itself the repair of an earlier unusable reply
     */
    static boolean repairWithAnotherCall(LlmApiException.Kind kind,
                                         boolean failureReportCall,
                                         boolean budgetExhausted,
                                         boolean repairCall) {
        return kind == LlmApiException.Kind.UNUSABLE_REPLY && !failureReportCall && !budgetExhausted && !repairCall;
    }

    /**
     * Whether the work this instruction started has demonstrably finished successfully: the request
     * began, nothing is running or paused any more, no task failure is waiting to be reported, no
     * long-term goal is still unfinished, and the last task (and last goal, if any) ended COMPLETED.
     * Anything less is unknown, and an unknown outcome is never worth silence.
     *
     * @param lastGoalStatus the status of the bot's latest goal result, or null when it has none
     */
    static boolean requestCompleted(boolean requestStarted,
                                    boolean workActive,
                                    boolean failurePending,
                                    boolean longTermGoalActive,
                                    TaskState lastTaskState,
                                    GoalResult.Status lastGoalStatus) {
        return requestStarted
                && !workActive
                && !failurePending
                && !longTermGoalActive
                && lastTaskState == TaskState.COMPLETED
                && (lastGoalStatus == null || lastGoalStatus == GoalResult.Status.COMPLETED);
    }

    /** The truthful reason, in the player's terms: the thinking service, not the request, is what failed. */
    static String playerMessage(LlmApiException failure) {
        return switch (failure.kind()) {
            case TRANSIENT -> "Sorry, my thinking service is overloaded or unavailable right now. "
                    + "Please try again in a moment.";
            case AUTH -> "Sorry, my thinking service refused my credentials, so I cannot plan anything. "
                    + "The server owner needs to check the LLM API key.";
            case PERMANENT, UNUSABLE_REPLY -> "Sorry, I could not get a usable answer from my thinking service for that. "
                    + "Please try again; if it keeps happening, check the server log.";
        };
    }
}
