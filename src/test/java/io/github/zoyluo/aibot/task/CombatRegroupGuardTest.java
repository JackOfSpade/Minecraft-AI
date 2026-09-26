package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the squad-regroup hysteresis: three or more simultaneously aggro'd hostiles fall back to
 * within ten blocks of the owning player, then roam/fight freely until drifting past fifteen
 * blocks while still that heavily aggro'd.
 */
class CombatRegroupGuardTest {

    @Test
    void doesNotTriggerBelowTheAggroThreshold() {
        assertFalse(CombatRegroupGuard.shouldRegroup(20.0D, 2, false),
                "two aggro'd hostiles must not force a regroup");
    }

    @Test
    void doesNotTriggerInsideTheOuterRadiusEvenWhenSwarmed() {
        assertFalse(CombatRegroupGuard.shouldRegroup(12.0D, 5, false),
                "combat is allowed within the outer radius regardless of aggro count");
    }

    @Test
    void triggersOnceBeyondTheOuterRadiusWhileSwarmed() {
        assertTrue(CombatRegroupGuard.shouldRegroup(16.0D, 3, false));
    }

    @Test
    void stateBoundaryIsExclusiveAtExactlyTheOuterRadius() {
        assertFalse(CombatRegroupGuard.shouldRegroup(15.0D, 5, false),
                "the trigger is strictly beyond fifteen blocks, not at it");
    }

    @Test
    void remainsRegroupingUntilInsideTheInnerRadius() {
        assertTrue(CombatRegroupGuard.shouldRegroup(11.0D, 0, true),
                "an active regroup keeps pulling the bot in even if aggro has since dropped");
        assertFalse(CombatRegroupGuard.shouldRegroup(10.0D, 5, true),
                "reaching the inner radius releases the forced regroup");
    }

    @Test
    void releasingDoesNotImmediatelyReTriggerInsideTheOuterRadius() {
        // Simulates the hysteresis band: once released at <=10, a bot sitting at 12 (inside the
        // outer 15-block radius) must not instantly flip back into regroup even while still
        // heavily aggro'd -- that would defeat the whole point of having two thresholds.
        boolean stillRegrouping = false;
        assertFalse(CombatRegroupGuard.shouldRegroup(12.0D, 4, stillRegrouping));
    }

    @Test
    void thresholdsMatchTheRequestedNumbers() {
        assertEquals(3, CombatRegroupGuard.AGGRO_THRESHOLD);
        assertEquals(10.0D, CombatRegroupGuard.INNER_RADIUS);
        assertEquals(15.0D, CombatRegroupGuard.OUTER_RADIUS);
    }
}
