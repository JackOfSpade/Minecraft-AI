package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.pathfinding.FailureReason;

/**
 * Decides which failed route searches justify telling the player there is no way to them.
 *
 * <p>A search that ran to exhaustion ({@link FailureReason#GOAL_UNREACHABLE}: the open set emptied without
 * reaching the goal) says something definite about the world, so it is announced at once, once per episode.
 * Everything else says nothing about connectivity on its own, so the bot just retries on the normal schedule:
 * a budget failure (node limit, time limit), a bad start cell, an unloaded-chunk hiccup, and also
 * {@link FailureReason#GOAL_NOT_STANDABLE} (no standable cell near the player), because a player who is
 * momentarily mid-air (a jump, a fall, a knockback) or at a chunk edge has no standable cell for a moment
 * yet is fine a second later. When such failures keep repeating, though, silence is worse than a line (see
 * {@link RepeatedFailures}): the player would otherwise watch a follower stand still with no explanation.
 * The line then says what was not found ({@link #messageFor}): the specific "no place to stand" for a
 * persistent unstandable goal, a generic one for the rest.
 *
 * <p>Known limitation: the search treats an unloaded chunk as blocked, so a route that leaves the loaded area
 * is reported exactly like a genuinely severed one ("no route" while the player is merely far away or across
 * the render edge). The announcement is per-episode and re-armed on the next successful route, so the cost of
 * a false positive is one chat line.
 */
final class FollowNoRoute {
    /** The failure prefix ActionPack puts on a failed search: {@code "pathfinding_failed: <reason>"}. */
    private static final String FAILED_PREFIX = "pathfinding_failed: ";

    /** Non-genuine failures needed in a row before the generic notice... */
    static final int REPEATED_FAILURES_BEFORE_NOTICE = 3;
    /** ...and the time they must span (10 s) so a burst of quick retries cannot trigger it. */
    static final int REPEATED_FAILURE_SPAN_TICKS = 200;

    static final String NO_DRY_ROUTE_MESSAGE =
            "I can't find a dry way to you from here, so I'll wait here and keep looking.";
    static final String NO_STANDING_PLACE_MESSAGE =
            "I can't find a place to stand near you, so I'll wait here and keep looking.";
    static final String GENERIC_MESSAGE = "I can't find a way to you right now, so I'll keep trying.";

    private FollowNoRoute() {
    }

    /** @param reason the failed {@code ActionResult.reason()} of a path request (may be null) */
    static boolean isGenuine(String reason) {
        return reason != null && reason.equals(FAILED_PREFIX + FailureReason.GOAL_UNREACHABLE.name());
    }

    /**
     * The line for an announced failure: what exactly the bot could not find. A persistent unstandable goal
     * gets its specific line, an exhausted search the dry-route line, any other repeated failure the generic one.
     */
    static String messageFor(String reason) {
        if (reason == null) {
            return GENERIC_MESSAGE;
        }
        if (reason.equals(FAILED_PREFIX + FailureReason.GOAL_NOT_STANDABLE.name())) {
            return NO_STANDING_PLACE_MESSAGE;
        }
        return reason.equals(FAILED_PREFIX + FailureReason.GOAL_UNREACHABLE.name())
                ? NO_DRY_ROUTE_MESSAGE
                : GENERIC_MESSAGE;
    }

    /**
* Counts consecutive NON-genuine failed re-plans (an unstandable goal included) and says when a notice is     * due: at least {@link #REPEATED_FAILURES_BEFORE_NOTICE} of them, spanning at least     * {@link #REPEATED_FAILURE_SPAN_TICKS}.
     * Any successful route, arrival or genuine failure ends the streak. Pure (no game objects).
     */
    static final class RepeatedFailures {
        private int failures;
        private int firstFailureTick;

        /** Records one non-genuine failed re-plan at {@code tick}; true when the generic notice is now due. */
        boolean recordFailure(int tick) {
            if (failures == 0) {
                firstFailureTick = tick;
            }
            failures++;
            return failures >= REPEATED_FAILURES_BEFORE_NOTICE
                    && tick - firstFailureTick >= REPEATED_FAILURE_SPAN_TICKS;
        }

        void reset() {
            failures = 0;
            firstFailureTick = 0;
        }

        int failures() {
            return failures;
        }
    }
}
