package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencyShelterEatingPolicyTest {
    @Test
    void recoveryShelterTopsHungerAllTheWayToTwenty() {
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(17.0F, 19));
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(17.0F, 18));
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(17.0F, 17));
    }

    @Test
    void everyLivingRecoveryBotCanUseFoodNineteen() {
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(8.1F, 19));
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(8.0F, 19));
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(8.0F, 18));
        assertFalse(EmergencyShelterTask.shouldStartHoldEating(8.0F, 20));
    }

    @Test
    void exitRequiresFullHealthAndFullHunger() {
        assertFalse(EmergencyShelterTask.isFullyRecovered(19.99F, 20.0F, 20));
        assertFalse(EmergencyShelterTask.isFullyRecovered(20.0F, 20.0F, 19));
        assertTrue(EmergencyShelterTask.isFullyRecovered(20.0F, 20.0F, 20));
    }

    // Point 6a vs 6b: running out of food right as health finished healing is not a problem and
    // must not block the exit merely because hunger never made it all the way back to twenty.
    @Test
    void exitAllowsLessThanTwentyFoodOnlyOnceThereIsNoMoreFoodToEat() {
        assertTrue(EmergencyShelterTask.isRecoveredEnoughToExit(20.0F, 20.0F, 19, false),
                "6a: full health with no more food to eat must not wait forever for food it can never reach");
        assertFalse(EmergencyShelterTask.isRecoveredEnoughToExit(20.0F, 20.0F, 19, true),
                "full health with food still available keeps topping hunger before exiting");
        assertTrue(EmergencyShelterTask.isRecoveredEnoughToExit(20.0F, 20.0F, 20, true));
        assertTrue(EmergencyShelterTask.isRecoveredEnoughToExit(20.0F, 20.0F, 20, false));
        assertFalse(EmergencyShelterTask.isRecoveredEnoughToExit(19.99F, 20.0F, 20, false),
                "6b: still below full health must never be treated as safe to exit, food or not");
    }

    // Point 6b: "ran out of food while still hurt" must be a distinct signal from "already full".
    @Test
    void healingStallsOnlyWhenFoodIsGoneAndHealthIsStillShortOfMax() {
        assertTrue(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, false));
        assertFalse(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, true),
                "food is still available, so healing has not actually stalled yet");
        assertFalse(EmergencyShelterTask.isHealingStalledWithoutFood(20.0F, 20.0F, false),
                "6a: already at full health is the normal case, not a stall");
    }

    // Point 6's 50%-HP exception: give up waiting and fight once at/above half health, otherwise
    // stay sealed and wait for a rescue.
    @Test
    void abandonsRescueWaitOnlyAtOrAboveHalfHealth() {
        assertTrue(EmergencyShelterTask.shouldAbandonRescueWaitAndFight(10.0F, 20.0F));
        assertTrue(EmergencyShelterTask.shouldAbandonRescueWaitAndFight(15.0F, 20.0F));
        assertFalse(EmergencyShelterTask.shouldAbandonRescueWaitAndFight(9.99F, 20.0F));
    }
}
