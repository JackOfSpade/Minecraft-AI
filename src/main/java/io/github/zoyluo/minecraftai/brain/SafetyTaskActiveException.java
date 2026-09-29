package io.github.zoyluo.minecraftai.brain;

/**
 * Thrown when a model tool tries to start a task while a SAFETY-origin task (a fight, an evade, a
 * shelter) is still handling a real threat. The request waits until the threat is handled; the
 * model is told to retry instead of the safety task being cancelled mid-fight.
 */
final class SafetyTaskActiveException extends IllegalStateException {
    SafetyTaskActiveException(String activeTask) {
        super("safety_task_active: " + activeTask
                + " is handling a threat right now; retry this request after the threat is handled");
    }
}
