package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.pathfinding.FailureReason;
import org.junit.jupiter.api.Test;

final class FollowNoRouteTest {
    @Test
    void onlyAnExhaustedSearchIsAGenuineNoRoute() {
        assertTrue(FollowNoRoute.isGenuine("pathfinding_failed: " + FailureReason.GOAL_UNREACHABLE));
    }

    @Test
    void budgetAndTransientFailuresAreNotAnnounced() {
        for (FailureReason reason : FailureReason.values()) {
            if (reason != FailureReason.GOAL_UNREACHABLE) {
                assertFalse(FollowNoRoute.isGenuine("pathfinding_failed: " + reason), reason.name());
            }
        }
        assertFalse(FollowNoRoute.isGenuine("pathfinding_throttled"));
        assertFalse(FollowNoRoute.isGenuine("path_contract_failed: start_below_minimum_y"));
        assertFalse(FollowNoRoute.isGenuine(""));
        assertFalse(FollowNoRoute.isGenuine(null));
    }
}
