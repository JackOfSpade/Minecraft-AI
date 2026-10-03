package io.github.zoyluo.minecraftai.brain;

/**
 * Bounded model-call allowance for one verified adaptive-mission boundary.
 *
 * <p>This is intentionally separate from {@link PlayerInstructionCallBudget}: a long mission may
 * cross many materially new world states, but a malformed response or provider retry at one state
 * must still be capped.  Starting a new boundary never restores the player's ordinary planning
 * budget.</p>
 */
final class MissionDecisionCallBudget {
    static final int DEFAULT_MAX_CALLS_PER_BOUNDARY = 3;

    private final int maxCalls;
    private long boundarySequence;
    private int callsUsed;

    MissionDecisionCallBudget() {
        this(DEFAULT_MAX_CALLS_PER_BOUNDARY);
    }

    MissionDecisionCallBudget(int maxCalls) {
        if (maxCalls <= 0) {
            throw new IllegalArgumentException("maxCalls must be positive");
        }
        this.maxCalls = maxCalls;
    }

    void beginBoundary() {
        boundarySequence = boundarySequence == Long.MAX_VALUE ? 1L : boundarySequence + 1L;
        callsUsed = 0;
    }

    boolean tryAcquireModelCall() {
        if (callsUsed >= maxCalls) {
            return false;
        }
        callsUsed++;
        return true;
    }

    void releaseLastReservation() {
        if (callsUsed <= 0) {
            throw new IllegalStateException("no_mission_decision_reservation_to_release");
        }
        callsUsed--;
    }

    boolean exhausted() {
        return callsUsed >= maxCalls;
    }

    long boundarySequence() {
        return boundarySequence;
    }

    int callsUsed() {
        return callsUsed;
    }

    int callsRemaining() {
        return maxCalls - callsUsed;
    }
}
