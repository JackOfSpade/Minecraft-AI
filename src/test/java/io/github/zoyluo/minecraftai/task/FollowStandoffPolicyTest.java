package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the arrived follower's departure hysteresis. */
final class FollowStandoffPolicyTest {

    @Test
    void anArrivedFollowerDoesNotStartTinyRoutesInsideTheDepartureBand() {
        assertFalse(FollowTask.shouldResumeStandoffHold(true, 4.9D));
        assertTrue(FollowTask.shouldResumeStandoffHold(true, 5.0D));
    }

    @Test
    void aFollowerThatWasNotHoldingMayStartItsFirstRouteImmediately() {
        assertTrue(FollowTask.shouldResumeStandoffHold(false, 4.0D));
    }
}
