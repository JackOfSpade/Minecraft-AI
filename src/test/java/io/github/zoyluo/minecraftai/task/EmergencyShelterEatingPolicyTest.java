package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, false, false));
        assertFalse(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, true, false),
                "food is still available, so healing has not actually stalled yet");
        assertFalse(EmergencyShelterTask.isHealingStalledWithoutFood(20.0F, 20.0F, false, false),
                "6a: already at full health is the normal case, not a stall");
        assertFalse(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, false, true),
                "a bot that ate its last item to a full hunger bar still regenerates: it holds, it does not"
                        + " cry for help or give up at half health");
    }

    // Vanilla natural regeneration needs a hunger bar of 18 or more and the gamerule on.
    @Test
    void naturalRegenerationNeedsHungerEighteenAndTheGamerule() {
        assertTrue(EmergencyShelterTask.canRegenerateNaturally(20, true));
        assertTrue(EmergencyShelterTask.canRegenerateNaturally(18, true));
        assertFalse(EmergencyShelterTask.canRegenerateNaturally(17, true));
        assertFalse(EmergencyShelterTask.canRegenerateNaturally(20, false));
    }

    // The hold-while-regenerating rule is not a promise of full health: each natural heal burns 6 exhaustion
    // (about 1.5 hunger), so with no food and no saturation the bar drops below 18 after roughly two hit points
    // and the very same bot becomes stalled. The predicate must flip exactly at the hunger-18 boundary.
    @Test
    void stalledFlipsExactlyAtTheHungerEighteenBoundaryWhenNoFoodIsLeft() {
        assertFalse(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, false,
                EmergencyShelterTask.canRegenerateNaturally(18, true)));
        assertTrue(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, false,
                EmergencyShelterTask.canRegenerateNaturally(17, true)));
        assertTrue(EmergencyShelterTask.isHealingStalledWithoutFood(10.0F, 20.0F, false,
                EmergencyShelterTask.canRegenerateNaturally(20, false)),
                "the naturalRegeneration gamerule off stalls even a full hunger bar");
    }

    // Point 6's 50%-HP exception: give up waiting and fight once at/above half health, otherwise
    // stay sealed and wait for a rescue.
    @Test
    void abandonsRescueWaitOnlyAtOrAboveHalfHealth() {
        assertTrue(EmergencyShelterTask.shouldAbandonRescueWaitAndFight(10.0F, 20.0F));
        assertTrue(EmergencyShelterTask.shouldAbandonRescueWaitAndFight(15.0F, 20.0F));
        assertFalse(EmergencyShelterTask.shouldAbandonRescueWaitAndFight(9.99F, 20.0F));
    }

    @Test
    void recoveryStatusStartsImmediatelyThenStaysAtAReadableCadence() {
        assertTrue(EmergencyShelterTask.isRecoveryStatusDue(60, -1));
        assertFalse(EmergencyShelterTask.isRecoveryStatusDue(159, 60));
        assertTrue(EmergencyShelterTask.isRecoveryStatusDue(160, 60));
        assertEquals("Shelter status: 8/20 HP, hunger 20/20. Still healing before I resume defense.",
                EmergencyShelterTask.recoveryStatusMessage(7.5F, 20.0F, 20, false));
        assertEquals("Shelter status: 8/20 HP, hunger 17/20. I need food before I can keep healing.",
                EmergencyShelterTask.recoveryStatusMessage(7.5F, 20.0F, 17, true));
        assertEquals("Shelter status: 20/20 HP, hunger 20/20. Fully healed; I am waiting for a safe moment to leave and resume defense.",
                EmergencyShelterTask.recoveryStatusMessage(20.0F, 20.0F, 20, false));
    }
}
