package io.github.zoyluo.minecraftai.brain;

/**
 * Thrown when a model tool tries to start a task while a SAFETY-origin task (a fight, an evade, a
 * shelter) is still handling a real threat. The safety task is not cancelled mid-fight. When the blocked
 * call belongs to a player instruction the request is remembered
 * ({@code BrainCoordinator.deferRequestUntilSafetyEnds}) and restarted automatically once the threat is
 * handled; a blocked call of an autonomous wake is not remembered. {@link #resultText} words the tool
 * result for whichever of the two happened, so the model is never promised a restart that will not come.
 */
final class SafetyTaskActiveException extends IllegalStateException {
    private final String activeTask;

    SafetyTaskActiveException(String activeTask) {
        super("safety_task_active: " + activeTask + " is handling a threat right now.");
        this.activeTask = activeTask;
    }

    /** The tool result text ({@code deferred}: the blocked request was kept for the end of the threat). */
    String resultText(boolean deferred) {
        return "safety_task_active: " + activeTask + " is handling a threat right now. "
                + (deferred
                ? "Your request is kept and will be started automatically once the threat is handled; "
                        + "do not call more task tools now. Tell the player in one short say that you will "
                        + "start right after the threat."
                : "This call was not kept and will not be restarted; do not start other tasks now.");
    }
}
