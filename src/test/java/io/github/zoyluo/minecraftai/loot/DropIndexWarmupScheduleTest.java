package io.github.zoyluo.minecraftai.loot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DropIndexWarmupScheduleTest {
    @Test
    void anIdleMomentBuildsImmediatelyEvenOnTheFirstEligibleTick() {
        assertTrue(DropIndexWarmupSchedule.buildNow(true, false, 0));
        assertTrue(DropIndexWarmupSchedule.buildNow(true, true, 0));
    }

    @Test
    void aBusyServerWaitsForAnIdleMomentUntilTheBoundIsReached() {
        for (int waited = 0; waited < DropIndexWarmupSchedule.MAX_IDLE_WAIT_TICKS; waited++) {
            assertFalse(DropIndexWarmupSchedule.buildNow(false, true, waited),
                    "still inside the bound at " + waited + " ticks: keep waiting for idle");
        }
    }

    @Test
    void afterTheBoundTheBuildIsForcedAtALowLoadTick() {
        assertTrue(DropIndexWarmupSchedule.buildNow(false, true, DropIndexWarmupSchedule.MAX_IDLE_WAIT_TICKS));
        assertTrue(DropIndexWarmupSchedule.buildNow(false, true, Integer.MAX_VALUE));
    }

    @Test
    void afterTheBoundAHighLoadTickStillDefersTheBuild() {
        assertFalse(DropIndexWarmupSchedule.buildNow(false, false, DropIndexWarmupSchedule.MAX_IDLE_WAIT_TICKS));
        assertFalse(DropIndexWarmupSchedule.buildNow(false, false, Integer.MAX_VALUE));
    }

    @Test
    void theBoundIsAboutThirtySecondsAtTwentyTicksPerSecond() {
        assertEquals(600, DropIndexWarmupSchedule.MAX_IDLE_WAIT_TICKS);
    }

    @Test
    void aBotThatAlwaysHoldsATaskStillGetsTheBuildAfterTheBound() {
        // Simulates the tick loop: never idle, load low every tick. The build must happen exactly at the bound.
        int waited = 0;
        int builtAt = -1;
        for (int tick = 0; tick < 10_000 && builtAt < 0; tick++) {
            if (DropIndexWarmupSchedule.buildNow(false, true, waited)) {
                builtAt = tick;
            } else {
                waited++;
            }
        }
        assertEquals(DropIndexWarmupSchedule.MAX_IDLE_WAIT_TICKS, builtAt);
    }
}
