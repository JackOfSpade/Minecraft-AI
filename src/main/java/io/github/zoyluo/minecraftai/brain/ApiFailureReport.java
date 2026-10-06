package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.task.TaskState;

/**
 * What the player is told when a model request failed for good (the service stayed unavailable for
 * the whole retry patience, or rejected the request). Pure decision logic, kept free of any game
 * object so every rule is unit-testable, like {@link InstructionRoundEvaluator}.
 */
final class ApiFailureReport {
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
     * Whether the work this instruction started has demonstrably finished successfully: the request
     * began, nothing is running or paused any more, no task failure is waiting to be reported, and
     * the last task (and last goal, if any) ended COMPLETED. Anything less is unknown, and an unknown
     * outcome is never worth silence.
     *
     * @param lastGoalStatus the status of the bot's latest goal result, or null when it has none
     */
    static boolean requestCompleted(boolean requestStarted,
                                    boolean workActive,
                                    boolean failurePending,
                                    TaskState lastTaskState,
                                    GoalResult.Status lastGoalStatus) {
        return requestStarted
                && !workActive
                && !failurePending
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
            case PERMANENT -> "Sorry, I could not get a usable answer from my thinking service for that. "
                    + "Please try again; if it keeps happening, check the server log.";
        };
    }
}
