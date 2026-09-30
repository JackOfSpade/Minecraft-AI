package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vanilla sprint and input-scale rules the pace enforcers apply (pure functions; no Minecraft). */
class PaceRulesTest {
    private static boolean allowed(float forward, boolean sneaking, boolean usingItem, boolean blind, int food, boolean mayfly,
                                   boolean hardCollision) {
        return PaceRules.sprintAllowed(forward, sneaking, usingItem, blind, food, mayfly, hardCollision);
    }

    @Test
    void aHealthyBotPushingForwardMaySprint() {
        assertTrue(allowed(1.0F, false, false, false, 20, false, false));
    }

    @Test
    void noSprintAtSixFoodPointsButAtSeven() {
        assertFalse(allowed(1.0F, false, false, false, 6, false, false), "vanilla stops sprinting at 6 food points");
        assertTrue(allowed(1.0F, false, false, false, 7, false, false));
        assertFalse(allowed(1.0F, false, false, false, 0, false, false));
    }

    @Test
    void aPlayerThatMayFlySprintsOnAnEmptyStomach() {
        assertTrue(allowed(1.0F, false, false, false, 0, true, false));
        assertTrue(allowed(1.0F, false, false, false, 6, true, false));
    }

    @Test
    void usingAnItemBlindnessAndSneakingEndTheSprint() {
        assertFalse(allowed(1.0F, false, true, false, 20, false, false), "eating, blocking or drawing a bow");
        assertFalse(allowed(1.0F, false, false, true, 20, false, false), "blindness");
        assertFalse(allowed(1.0F, true, false, false, 20, false, false), "sneaking");
        assertFalse(allowed(1.0F, false, true, false, 20, true, false), "mayfly does not lift the item rule");
    }

    @Test
    void aHardCollisionEndsTheSprintAMinorOneDoesNot() {
        assertFalse(allowed(1.0F, false, false, false, 20, false, true));
        // The adapter reads hardCollision = horizontalCollision && !minorHorizontalCollision; a brush is "false" here.
        assertTrue(allowed(1.0F, false, false, false, 20, false, false));
    }

    @Test
    void sprintNeedsRealForwardInput() {
        assertFalse(allowed(0.0F, false, false, false, 20, false, false));
        assertFalse(allowed(0.3F, false, false, false, 20, false, false), "a sneak-scaled forward input");
        assertFalse(allowed(0.5F, false, false, false, 20, false, false), "the bound is exclusive");
        assertTrue(allowed(0.51F, false, false, false, 20, false, false));
        assertFalse(allowed(-1.0F, false, false, false, 20, false, false), "walking backwards");
    }

    @Test
    void inputScaleIsOneWhenNothingSlowsTheBotDown() {
        assertEquals(1.0F, PaceRules.inputScale(false, false, true));
    }

    @Test
    void sneakingScalesByThreeTenths() {
        assertEquals(0.3F, PaceRules.inputScale(true, false, true));
        assertEquals(0.3F, PaceRules.inputScale(true, false, false));
    }

    @Test
    void usingAnItemScalesByTwoTenthsWhenTheSlowdownIsOn() {
        assertEquals(0.2F, PaceRules.inputScale(false, true, true));
        assertEquals(1.0F, PaceRules.inputScale(false, true, false), "pace.itemUseSlowdown=false");
    }

    @Test
    void sneakingAndUsingAnItemMultiply() {
        assertEquals(0.3F * 0.2F, PaceRules.inputScale(true, true, true), 1.0E-7F);
        assertEquals(0.3F, PaceRules.inputScale(true, true, false), 1.0E-7F);
    }

    @Test
    void gaitsAreOrderedSlowestFirstAndClocksFollowTheSpeeds() {
        assertTrue(Gait.SNEAK.compareTo(Gait.WALK) < 0 && Gait.WALK.compareTo(Gait.SPRINT) < 0);
        assertEquals(Gait.SNEAK, Gait.min(Gait.SNEAK, Gait.SPRINT));
        assertEquals(Gait.SPRINT, Gait.max(Gait.WALK, Gait.SPRINT));
        assertEquals(1.0D, Gait.SPRINT.clockWeight());
        assertEquals(1.0D / 1.3D, Gait.WALK.clockWeight(), 1.0E-12D);
        assertEquals(1.0D / 4.4D, Gait.SNEAK.clockWeight(), 1.0E-12D);
    }

    @Test
    void ownerPrioritiesRankWardenAboveEvadeAboveTaskAboveFollow() {
        assertTrue(PaceOwner.WARDEN.priority() > PaceOwner.EVADE.priority());
        assertTrue(PaceOwner.EVADE.priority() > PaceOwner.TASK.priority());
        assertTrue(PaceOwner.TASK.priority() > PaceOwner.FOLLOW.priority());
        assertEquals(90, PaceOwner.WARDEN.priority());
        assertEquals(30, PaceOwner.FOLLOW.priority());
    }
}
