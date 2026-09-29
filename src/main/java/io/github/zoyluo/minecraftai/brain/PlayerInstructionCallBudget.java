package io.github.zoyluo.minecraftai.brain;

/**
 * Counts model requests made while carrying out one player instruction.
 *
 * <p>The owner must call {@link #beginPlayerInstruction()} only when a new
 * player instruction supersedes the previous one. Autonomous task/goal
 * wake-ups deliberately reuse the same planner budget ({@link #DEFAULT_MAX_CALLS}),
 * so they cannot escape the cap by opening a new decision epoch. Task-failure
 * reports are the one exception: they draw first from a separate small
 * allowance ({@link #MAX_FAILURE_REPORT_CALLS}) so a failure is still reported
 * after the planner spent its calls, falling back to the planner budget only
 * once that allowance is spent.</p>
 */
final class PlayerInstructionCallBudget {
    static final int DEFAULT_MAX_CALLS = 3;
    /**
     * Model calls per player instruction that exist only to report a task failure to the player.
     * They are deliberately separate from {@link #DEFAULT_MAX_CALLS}: a follow that is aborted long
     * after the planner spent its three calls must still be reported instead of silently vanishing,
     * while the small cap keeps a repeatedly failing task from looping the model forever.
     */
    static final int MAX_FAILURE_REPORT_CALLS = 2;

    /** Which allowance a failure-report reservation was taken from (needed to roll it back). */
    enum Reservation {
        NONE,
        FAILURE_REPORT,
        REGULAR
    }

    private final int maxCalls;
    private long instructionSequence;
    private int callsUsed;
    private int failureReportCallsUsed;

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
        failureReportCallsUsed = 0;
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

    /** Whether {@link #tryAcquireFailureReportCall()} would succeed right now (nothing is reserved). */
    boolean canAcquireFailureReportCall() {
        return failureReportCallsUsed < MAX_FAILURE_REPORT_CALLS || callsUsed < maxCalls;
    }

    /**
     * Reserves one model call for reporting a task failure to the player. The dedicated failure
     * allowance is used first so the planner's own calls stay available for a retry; only once it
     * is spent does the regular allowance serve as a fallback. {@link Reservation#NONE} means the
     * caller must report the failure without a model call.
     */
    Reservation tryAcquireFailureReportCall() {
        if (failureReportCallsUsed < MAX_FAILURE_REPORT_CALLS) {
            failureReportCallsUsed++;
            return Reservation.FAILURE_REPORT;
        }
        if (callsUsed < maxCalls) {
            callsUsed++;
            return Reservation.REGULAR;
        }
        return Reservation.NONE;
    }

    /** Rolls back a {@link #tryAcquireFailureReportCall()} reservation when nothing could be queued. */
    void releaseFailureReportReservation(Reservation reservation) {
        switch (reservation) {
            case FAILURE_REPORT -> {
                if (failureReportCallsUsed <= 0) {
                    throw new IllegalStateException("no_failure_report_reservation_to_release");
                }
                failureReportCallsUsed--;
            }
            case REGULAR -> releaseLastReservation();
            case NONE -> {
            }
        }
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

    int failureReportCallsUsed() {
        return failureReportCallsUsed;
    }

    int callsRemaining() {
        return maxCalls - callsUsed;
    }

    boolean exhausted() {
        return callsUsed >= maxCalls;
    }
}
