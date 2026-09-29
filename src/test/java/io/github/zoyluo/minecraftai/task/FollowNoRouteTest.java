package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.pathfinding.FailureReason;
import org.junit.jupiter.api.Test;

final class FollowNoRouteTest {
    @Test
    void anExhaustedSearchAndAnUnstandableGoalAreGenuine() {
        assertTrue(FollowNoRoute.isGenuine("pathfinding_failed: " + FailureReason.GOAL_UNREACHABLE));
        assertTrue(FollowNoRoute.isGenuine("pathfinding_failed: " + FailureReason.GOAL_NOT_STANDABLE));
    }

    @Test
    void budgetAndTransientFailuresAreNotGenuine() {
        for (FailureReason reason : FailureReason.values()) {
            if (reason != FailureReason.GOAL_UNREACHABLE && reason != FailureReason.GOAL_NOT_STANDABLE) {
                assertFalse(FollowNoRoute.isGenuine("pathfinding_failed: " + reason), reason.name());
            }
        }
        assertFalse(FollowNoRoute.isGenuine("pathfinding_throttled"));
        assertFalse(FollowNoRoute.isGenuine("path_contract_failed: start_below_minimum_y"));
        assertFalse(FollowNoRoute.isGenuine(""));
        assertFalse(FollowNoRoute.isGenuine(null));
    }

    @Test
    void theMessageNamesWhatWasNotFound() {
        assertEquals(FollowNoRoute.NO_STANDING_PLACE_MESSAGE,
                FollowNoRoute.messageFor("pathfinding_failed: " + FailureReason.GOAL_NOT_STANDABLE));
        assertEquals(FollowNoRoute.NO_DRY_ROUTE_MESSAGE,
                FollowNoRoute.messageFor("pathfinding_failed: " + FailureReason.GOAL_UNREACHABLE));
        assertNotEquals(FollowNoRoute.NO_STANDING_PLACE_MESSAGE, FollowNoRoute.GENERIC_MESSAGE);
        assertTrue(FollowNoRoute.NO_STANDING_PLACE_MESSAGE.contains("place to stand"));
    }

    @Test
    void repeatedNonGenuineFailuresAnnounceOnlyAfterThreeSpanningTenSeconds() {
        FollowNoRoute.RepeatedFailures streak = new FollowNoRoute.RepeatedFailures();
        assertFalse(streak.recordFailure(0));
        assertFalse(streak.recordFailure(40));
        // Third failure, but the streak only spans 80 ticks: not yet.
        assertFalse(streak.recordFailure(80));
        assertFalse(streak.recordFailure(160));
        // 200 ticks after the first: due.
        assertTrue(streak.recordFailure(200));
        assertEquals(5, streak.failures());
    }

    @Test
    void threeFailuresAreNotEnoughWhenTheyAllHappenWithinASecond() {
        FollowNoRoute.RepeatedFailures streak = new FollowNoRoute.RepeatedFailures();
        assertFalse(streak.recordFailure(1000));
        assertFalse(streak.recordFailure(1010));
        assertFalse(streak.recordFailure(1020));
    }

    @Test
    void aSuccessfulRouteRearmsTheStreak() {
        FollowNoRoute.RepeatedFailures streak = new FollowNoRoute.RepeatedFailures();
        streak.recordFailure(0);
        streak.recordFailure(100);
        assertTrue(streak.recordFailure(240));
        streak.reset();
        assertEquals(0, streak.failures());
        // A fresh episode starts counting from its own first failure.
        assertFalse(streak.recordFailure(300));
        assertFalse(streak.recordFailure(340));
        assertFalse(streak.recordFailure(380));
        assertTrue(streak.recordFailure(500));
    }
}
