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
     * Whether a call that failed on a Gemini continuation failed because the service no longer has the
     * stored interaction it names: it rejected the request itself (HTTP 400/404, not a credentials
     * problem and not an outage) while the call pointed at an earlier interaction. Interactions are
     * kept only for a limited time (a day on the free tier), and such a request can never succeed, so
     * the conversation carries on in a fresh interaction rebuilt from the brain's own history. That is
     * another planner call, metered by the call budget; and since a fresh interaction names no earlier
     * one, a second rejection is no longer a lost interaction and is reported like any other failure.
     *
     * @param continuationCall the failed request named a previous interaction id
     */
    static boolean interactionLost(LlmApiException failure,
                                   boolean continuationCall,
                                   boolean failureReportCall,
                                   boolean budgetExhausted) {
        return continuationCall && !failureReportCall && !budgetExhausted
                && failure.kind() == LlmApiException.Kind.PERMANENT
                && (failure.httpStatus() == 400 || failure.httpStatus() == 404);
    }

    /**
     * Whether the player who waits on a request the service keeps failing is told, once, that the bot
     * is still trying. Only a player who is actually waiting hears it: nothing of the request runs yet
     * (a running task is a bot that visibly works, whatever its next planner call does), the request
     * did not already finish, and the same instruction did not already say so.
     *
     * @param failureReportCall the waiting call only words a task failure, which has its own fallback line
     */
    static boolean tellPlayerStillTrying(boolean alreadyTold,
                                         boolean failureReportCall,
                                         boolean workActive,
                                         boolean requestCompleted) {
        return !alreadyTold && !failureReportCall && !workActive && !requestCompleted;
    }

    /** What the player hears while the service keeps failing: the bot is not stuck, it is waiting. */
    static String stillTryingMessage() {
        return "Hold on, my thinking service is slow to answer right now. I'm still trying.";
    }

    /**
     * Whether the work this instruction started has demonstrably finished successfully: the request
     * began, nothing is running or paused any more, no task failure is waiting to be reported, no
     * further work is expected, and the last task (and last goal, if any) ended COMPLETED.
     * Anything less is unknown, and an unknown outcome is never worth silence.
     *
     * @param moreWorkExpected a long-term goal is still unfinished, or the plan the model announced for
     *                         this instruction names further steps after the task that just finished
     * @param lastGoalStatus the status of the bot's latest goal result, or null when it has none
     */
    static boolean requestCompleted(boolean requestStarted,
                                    boolean workActive,
                                    boolean failurePending,
                                    boolean moreWorkExpected,
                                    TaskState lastTaskState,
                                    GoalResult.Status lastGoalStatus) {
        return requestStarted
                && !workActive
                && !failurePending
                && !moreWorkExpected
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
