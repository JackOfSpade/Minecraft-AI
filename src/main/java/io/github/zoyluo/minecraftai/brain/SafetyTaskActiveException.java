package io.github.zoyluo.minecraftai.brain;

/**
 * Thrown when a model tool tries to start a task while a SAFETY-origin task (a fight, an evade, a
 * shelter) is still handling a real threat. The safety task is not cancelled mid-fight; the request
 * is remembered ({@code BrainCoordinator.deferRequestUntilSafetyEnds}) and restarted automatically
 * once the threat is handled, so the model must not retry it now.
 */
final class SafetyTaskActiveException extends IllegalStateException {
    SafetyTaskActiveException(String activeTask) {
        super("safety_task_active: " + activeTask
                + " is handling a threat right now. Your request is kept and will be started automatically"
                + " once the threat is handled; do not call more task tools now. Tell the player in one"
                + " short say that you will start right after the threat.");
    }
}
