package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.pathfinding.FailureReason;

/**
 * Decides which failed route searches justify telling the player there is no dry way to them. Only a
 * search that ran to exhaustion ({@link FailureReason#GOAL_UNREACHABLE}: the open set emptied without
 * reaching the goal, i.e. the reachable area really does not contain it) is a genuine "no route". A budget
 * failure (node limit, time limit), a bad start cell, an unstandable goal or an unloaded-chunk hiccup says
 * nothing about connectivity, so the bot just retries on the normal schedule without claiming there is no
 * way.
 */
final class FollowNoRoute {
    /** The failure prefix ActionPack puts on a failed search: {@code "pathfinding_failed: <reason>"}. */
    private static final String FAILED_PREFIX = "pathfinding_failed: ";

    private FollowNoRoute() {
    }

    /** @param reason the failed {@code ActionResult.reason()} of a path request (may be null) */
    static boolean isGenuine(String reason) {
        return reason != null && reason.equals(FAILED_PREFIX + FailureReason.GOAL_UNREACHABLE.name());
    }
}
