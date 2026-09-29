package io.github.zoyluo.minecraftai.navigation;

import net.minecraft.core.BlockPos;

/**
 * How the last Baritone route of a bot ended, in the vocabulary the legacy navigator uses: {@code SUCCESS}
 * ({@code path_complete}), {@code FAILED} (a {@code pathfinding_failed: <FailureReason>} answer, or a path that ended short of
 * its goal), {@code TIMEOUT} ({@code path_timeout}) and {@code CANCELLED} ({@code path_cancelled}: stopped by the caller, taken
 * over by another order, the bot removed or reset).
 *
 * @param reason  the failure/cancel reason ({@code pathfinding_failed: GOAL_UNREACHABLE}, {@code path_incomplete},
 *                {@code cancelled: stop_all}, ...); empty for {@code SUCCESS}
 * @param ticks   how long the route ran
 */
public record NavOutcome(Status status, String reason, String label, BlockPos goal, int ticks) {
    public enum Status {
        SUCCESS,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    /** The log event of this outcome, named like the legacy executor's ({@code path_complete}, {@code path_failed}, ...). */
    public String event() {
        return switch (status) {
            case SUCCESS -> "path_complete";
            case FAILED -> "path_failed";
            case TIMEOUT -> "path_timeout";
            case CANCELLED -> "path_cancelled";
        };
    }
}
