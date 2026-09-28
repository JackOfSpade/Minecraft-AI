package io.github.zoyluo.minecraftai.brain;

/**
 * Counts model requests made while carrying out one player instruction.
 *
 * <p>The owner must call {@link #beginPlayerInstruction()} only when a new
 * player instruction supersedes the previous one. Autonomous task/goal/failure
 * wake-ups deliberately reuse the same budget, so they cannot escape the cap
 * by opening a new decision epoch.</p>
 */
final class PlayerInstructionCallBudget {
    static final int DEFAULT_MAX_CALLS = 3;

    private final int maxCalls;
    private long instructionSequence;
    private int callsUsed;

    PlayerInstructionCallBudget(int maxCalls) {
        if (maxCalls <= 0) {
            throw new IllegalArgumentException("maxCalls must be positive");
        }
        // A config may opt into fewer calls, but a player instruction may never
        // exceed its initial call plus two repair attempts.
        this.maxCalls = Math.min(maxCalls, DEFAULT_MAX_CALLS);
    }

    /** Starts a replacement player instruction and restores its full allowance. */
    void beginPlayerInstruction() {
        beginPlayerInstruction(0);
    }

    /**
     * Starts a replacement player instruction after a separate control-plane model call has
     * already been made for it (for example, normal-chat recipient routing).
     */
    void beginPlayerInstruction(int callsAlreadyUsed) {
        if (callsAlreadyUsed < 0 || callsAlreadyUsed > maxCalls) {
            throw new IllegalArgumentException("callsAlreadyUsed_out_of_range");
        }
        if (instructionSequence == Long.MAX_VALUE) {
            instructionSequence = 1L;
        } else {
            instructionSequence++;
        }
        callsUsed = callsAlreadyUsed;
    }

    /**
     * Reserves one model call. Returns false once this player instruction has
     * consumed its allowance; callers must not submit an HTTP request then.
     */
    boolean tryAcquireModelCall() {
        if (callsUsed >= maxCalls) {
            return false;
        }
        callsUsed++;
        return true;
    }

    /** Rolls back a reservation when the HTTP work could not be queued at all. */
    void releaseLastReservation() {
        if (callsUsed <= 0) {
            throw new IllegalStateException("no_model_call_reservation_to_release");
        }
        callsUsed--;
    }

    long instructionSequence() {
        return instructionSequence;
    }

    int callsUsed() {
        return callsUsed;
    }

    int callsRemaining() {
        return maxCalls - callsUsed;
    }

    boolean exhausted() {
        return callsUsed >= maxCalls;
    }
}
