package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the squad-regroup policy: at or within ten blocks of the owning player, retreat never
 * triggers. Beyond ten blocks, three or more simultaneously aggro'd hostiles force a fighting
 * retreat that continues all the way to within five blocks of the owning player before releasing
 * back to ordinary combat, even if the aggro count drops mid-retreat.
 */
class CombatRegroupGuardTest {

    @Test
    void doesNotTriggerBelowTheAggroThreshold() {
        assertFalse(CombatRegroupGuard.shouldRegroup(20.0D, 2, false),
                "two aggro'd hostiles must not force a regroup");
    }

    @Test
    void doesNotTriggerWithinNoRetreatDistanceEvenWhenSwarmed() {
        assertFalse(CombatRegroupGuard.shouldRegroup(8.0D, 5, false),
                "retreat never starts at or within ten blocks of the owning player");
    }

    @Test
    void triggersOnceBeyondNoRetreatDistanceWhileSwarmed() {
        assertTrue(CombatRegroupGuard.shouldRegroup(11.0D, 3, false));
    }

    @Test
    void stateBoundaryIsExclusiveAtExactlyNoRetreatDistance() {
        assertFalse(CombatRegroupGuard.shouldRegroup(10.0D, 5, false),
                "the trigger is strictly beyond ten blocks, not at it");
    }

    @Test
    void remainsRegroupingUntilRetreatTargetDistance() {
        assertTrue(CombatRegroupGuard.shouldRegroup(6.0D, 0, true),
                "an active regroup keeps pulling the bot in even if aggro has since dropped");
        assertFalse(CombatRegroupGuard.shouldRegroup(5.0D, 5, true),
                "reaching the retreat target distance releases the forced regroup");
    }

    @Test
    void releasingDoesNotImmediatelyReTriggerWithinNoRetreatDistance() {
        // Simulates the hysteresis band: once released at <=5, a bot sitting at 8 (still within
        // the ten-block no-retreat distance) must not instantly flip back into regroup even while
        // still heavily aggro'd -- that would defeat the whole point of having two thresholds.
        boolean stillRegrouping = false;
        assertFalse(CombatRegroupGuard.shouldRegroup(8.0D, 4, stillRegrouping));
    }

    @Test
    void thresholdsMatchTheRequestedNumbers() {
        assertEquals(3, CombatRegroupGuard.AGGRO_THRESHOLD);
        assertEquals(10.0D, CombatRegroupGuard.NO_RETREAT_DISTANCE);
        assertEquals(5.0D, CombatRegroupGuard.RETREAT_TARGET_DISTANCE);
    }
}
