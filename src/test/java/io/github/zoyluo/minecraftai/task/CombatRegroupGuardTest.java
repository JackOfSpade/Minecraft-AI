package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the squad-regroup policy: at or within fifteen blocks of the owning player, retreat never
 * triggers. Beyond fifteen blocks -- but only while the player is within thirty-two blocks, since a
 * longer run is not a sensible fallback -- three or more hostiles aggro'd within fifteen blocks of
 * the bot force a fighting retreat that continues all the way to within five blocks of the owning
 * player before releasing back to ordinary combat, even if the aggro count drops mid-retreat.
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
                "retreat never starts at or within fifteen blocks of the owning player");
        assertFalse(CombatRegroupGuard.shouldRegroup(12.0D, 5, false),
                "the old ten-block trigger no longer applies");
    }

    @Test
    void triggersOnceBeyondNoRetreatDistanceWhileSwarmed() {
        assertTrue(CombatRegroupGuard.shouldRegroup(16.0D, 3, false));
        assertTrue(CombatRegroupGuard.shouldRegroup(25.0D, 4, false));
    }

    @Test
    void stateBoundaryIsExclusiveAtExactlyNoRetreatDistance() {
        assertFalse(CombatRegroupGuard.shouldRegroup(15.0D, 5, false),
                "the trigger is strictly beyond fifteen blocks, not at it");
    }

    @Test
    void neverRunsToAPlayerBeyondMaxRegroupDistance() {
        assertFalse(CombatRegroupGuard.shouldRegroup(33.0D, 5, false),
                "a player farther than thirty-two blocks is too far to retreat to");
        assertFalse(CombatRegroupGuard.shouldRegroup(100.0D, 10, false));
    }

    @Test
    void maxRegroupDistanceIsInclusive() {
        assertTrue(CombatRegroupGuard.shouldRegroup(32.0D, 3, false),
                "a player exactly thirty-two blocks away is still within range");
        assertFalse(CombatRegroupGuard.shouldRegroup(32.5D, 3, false));
    }

    @Test
    void remainsRegroupingUntilRetreatTargetDistance() {
        assertTrue(CombatRegroupGuard.shouldRegroup(6.0D, 0, true),
                "an active regroup keeps pulling the bot in even if aggro has since dropped");
        assertFalse(CombatRegroupGuard.shouldRegroup(5.0D, 5, true),
                "reaching the retreat target distance releases the forced regroup");
    }

    @Test
    void anActiveRegroupIsAbandonedOnceThePlayerIsBeyondMaxRegroupDistance() {
        assertTrue(CombatRegroupGuard.shouldRegroup(30.0D, 0, true));
        assertFalse(CombatRegroupGuard.shouldRegroup(40.0D, 5, true),
                "a running retreat must not chase a player who has gone out of range");
    }

    @Test
    void releasingDoesNotImmediatelyReTriggerWithinNoRetreatDistance() {
        // Simulates the hysteresis band: once released at <=5, a bot sitting at 12 (still within
        // the fifteen-block no-retreat distance) must not instantly flip back into regroup even
        // while still heavily aggro'd -- that would defeat the whole point of having two thresholds.
        boolean stillRegrouping = false;
        assertFalse(CombatRegroupGuard.shouldRegroup(12.0D, 4, stillRegrouping));
    }

    @Test
    void thresholdsMatchTheRequestedNumbers() {
        assertEquals(3, CombatRegroupGuard.AGGRO_THRESHOLD);
        assertEquals(15.0D, CombatRegroupGuard.AGGRO_SCAN_DISTANCE,
                "the 3+ aggro count is taken over fifteen blocks around the bot");
        assertEquals(15.0D, CombatRegroupGuard.NO_RETREAT_DISTANCE);
        assertEquals(32.0D, CombatRegroupGuard.MAX_REGROUP_DISTANCE);
        assertEquals(5.0D, CombatRegroupGuard.RETREAT_TARGET_DISTANCE);
    }
}
