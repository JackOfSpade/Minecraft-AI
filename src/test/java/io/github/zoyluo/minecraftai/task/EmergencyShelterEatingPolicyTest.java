package io.github.zoyluo.aibot.task;

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
}
