package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.action.WalkedStep.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules of the in-flight input step that replaces every path-correction teleport. */
class WalkedStepRulesTest {
    @Test
    void eachWalkingKindCoversItsOwnHeightDifference() {
        assertTrue(WalkedStepRules.offsetAllowed(Kind.FLAT, 1, 0, 0));
        assertTrue(WalkedStepRules.offsetAllowed(Kind.FLAT, 1, 0, -1), "a diagonal is still one cell away");
        assertFalse(WalkedStepRules.offsetAllowed(Kind.FLAT, 2, 0, 0));
        assertFalse(WalkedStepRules.offsetAllowed(Kind.FLAT, 0, 0, 0), "a walk has to go somewhere");
        assertFalse(WalkedStepRules.offsetAllowed(Kind.FLAT, 1, 1, 0));

        assertTrue(WalkedStepRules.offsetAllowed(Kind.STEP_UP, 0, 1, 1));
        assertFalse(WalkedStepRules.offsetAllowed(Kind.STEP_UP, 0, 2, 1), "a player does not hop two blocks");
        assertFalse(WalkedStepRules.offsetAllowed(Kind.STEP_UP, 0, 1, 0), "a hop needs a horizontal neighbour");

        assertTrue(WalkedStepRules.offsetAllowed(Kind.STEP_DOWN, 1, -1, 0));
        assertTrue(WalkedStepRules.offsetAllowed(Kind.STEP_DOWN, 1, -3, 0), "three blocks is the damage-free drop");
        assertFalse(WalkedStepRules.offsetAllowed(Kind.STEP_DOWN, 1, -4, 0));
        assertFalse(WalkedStepRules.offsetAllowed(Kind.STEP_DOWN, 0, -1, 0), "walking down needs an edge to walk off");
    }

    @Test
    void inCellKindsStayInTheirCell() {
        for (Kind kind : new Kind[]{Kind.RECENTER, Kind.SNEAK_SHIFT, Kind.PUSH_OUT}) {
            assertTrue(WalkedStepRules.offsetAllowed(kind, 0, 0, 0), kind.name());
            assertFalse(WalkedStepRules.offsetAllowed(kind, 1, 0, 0), kind.name());
            assertFalse(WalkedStepRules.endsInCell(kind), kind.name());
        }
        for (Kind kind : new Kind[]{Kind.FLAT, Kind.STEP_UP, Kind.STEP_DOWN, Kind.SWIM}) {
            assertTrue(WalkedStepRules.endsInCell(kind), kind.name());
        }
        assertTrue(WalkedStepRules.brakes(Kind.RECENTER));
        assertTrue(WalkedStepRules.brakes(Kind.SNEAK_SHIFT));
        assertFalse(WalkedStepRules.brakes(Kind.FLAT), "a cell-step keeps walking until it is in the cell");
    }

    @Test
    void swimMayRiseOrSinkOneCell() {
        assertTrue(WalkedStepRules.offsetAllowed(Kind.SWIM, 0, 1, 0), "straight up a water shaft");
        assertTrue(WalkedStepRules.offsetAllowed(Kind.SWIM, 1, -1, 1));
        assertFalse(WalkedStepRules.offsetAllowed(Kind.SWIM, 0, 2, 0));
        assertFalse(WalkedStepRules.offsetAllowed(Kind.SWIM, 0, 0, 0));
    }

    @Test
    void theKindFollowsTheRealHeightDifference() {
        assertEquals(Kind.FLAT, WalkedStepRules.walkKindFor(0));
        assertEquals(Kind.STEP_UP, WalkedStepRules.walkKindFor(1));
        assertEquals(Kind.STEP_DOWN, WalkedStepRules.walkKindFor(-1));
        assertEquals(Kind.STEP_DOWN, WalkedStepRules.walkKindFor(-3));
        assertNull(WalkedStepRules.walkKindFor(2));
        assertNull(WalkedStepRules.walkKindFor(-4));
    }

    @Test
    void timeoutIsTwentyTicksOrEightPerBlockAndAnItemInUseStretchesItFiveTimes() {
        assertEquals(20.0D, WalkedStepRules.timeoutBudget(1.0D));
        assertEquals(20.0D, WalkedStepRules.timeoutBudget(0.0D));
        assertEquals(40.0D, WalkedStepRules.timeoutBudget(5.0D));
        // A tick at a walk or a sprint spends one; an item in use (input 0.2) spends a fifth, so the budget lasts five times as long.
        assertEquals(1.0D, WalkedStepRules.tickCost(1.0D / 1.3D), 1.0E-9D);
        assertEquals(1.0D, WalkedStepRules.tickCost(1.0D), 1.0E-9D, "a sprint does not spend more than a tick");
        assertEquals(0.2D, WalkedStepRules.tickCost(0.2D / 1.3D), 1.0E-9D);
        assertEquals(5.0D, 1.0D / WalkedStepRules.tickCost(0.2D / 1.3D), 1.0E-9D);
        // A sneak is 1/4.4 of a sprint's ground per tick.
        assertEquals(1.3D / 4.4D, WalkedStepRules.tickCost(1.0D / 4.4D), 1.0E-9D);
        assertTrue(WalkedStepRules.tickCost(0.0D) > 0.0D, "a bot that does not move still runs out of time");
    }

    @Test
    void theKeysAreLetGoOneSlideEarly() {
        assertTrue(WalkedStepRules.shouldBrake(0.2D, 0.216D));
        assertTrue(WalkedStepRules.shouldBrake(0.28D, 0.216D));
        assertFalse(WalkedStepRules.shouldBrake(0.29D, 0.216D));
        assertFalse(WalkedStepRules.shouldBrake(0.05D, 0.0D), "a bot that has not started to move is not braking");
    }

    @Test
    void theNearestFreeSideWinsAndNothingFartherThanABlockIsChosen() {
        assertEquals(2, WalkedStepRules.bestPushDirection(new double[]{0.8D, 0.75D, 0.3D, Double.POSITIVE_INFINITY}));
        assertEquals(0, WalkedStepRules.bestPushDirection(new double[]{0.3D, 0.3D, 0.3D, 0.3D}), "the first of equals: a stable choice");
        assertEquals(-1, WalkedStepRules.bestPushDirection(new double[]{1.5D, Double.POSITIVE_INFINITY, Double.NaN, 1.2D}));
        assertEquals(3, WalkedStepRules.bestPushDirection(new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 2.0D, 1.0D}));
        assertEquals(-1, WalkedStepRules.bestPushDirection(new double[0]));
    }

    @Test
    void thePushIsAVanillaClientNudgeOfAtMostATenthOfABlockPerTick() {
        assertEquals(0.1D, WalkedStepRules.pushSpeed(5.0D), 1.0E-12D);
        assertEquals(0.04D, WalkedStepRules.pushSpeed(0.04D), 1.0E-12D);
        assertEquals(0.0D, WalkedStepRules.pushSpeed(-1.0D), 1.0E-12D);
    }

    @Test
    void theKeysPointFromTheHeadingToTheTarget() {
        // Yaw 0 faces +z: a target straight ahead is forward, a target at +x is to the left (vanilla moveRelative frame).
        float[] ahead = WalkedStepRules.keysToward(0.0D, 0.0D, 3.0D);
        assertEquals(1.0F, ahead[0], 1.0E-5F);
        assertEquals(0.0F, ahead[1], 1.0E-5F);
        float[] left = WalkedStepRules.keysToward(0.0D, 3.0D, 0.0D);
        assertEquals(0.0F, left[0], 1.0E-5F);
        assertEquals(1.0F, left[1], 1.0E-5F);
        // Facing +x (yaw -90) a target at +x is straight ahead.
        float[] turned = WalkedStepRules.keysToward(-90.0D, 3.0D, 0.0D);
        assertEquals(1.0F, turned[0], 1.0E-5F);
        assertEquals(0.0F, turned[1], 1.0E-5F);
        float[] none = WalkedStepRules.keysToward(10.0D, 0.0D, 0.0D);
        assertEquals(0.0F, none[0]);
        assertEquals(0.0F, none[1]);
    }

    @Test
    void aHopPressesJumpOnlyWhileGroundedBelowTheTargetFloor() {
        assertTrue(WalkedStepRules.jumpNow(Kind.STEP_UP, true, 4.0D, 5, false));
        assertFalse(WalkedStepRules.jumpNow(Kind.STEP_UP, false, 4.4D, 5, false), "no air jumps");
        assertTrue(WalkedStepRules.jumpNow(Kind.STEP_UP, false, 4.0D, 5, true), "out of the water the jump key is a swim stroke");
        assertFalse(WalkedStepRules.jumpNow(Kind.STEP_UP, true, 5.0D, 5, false), "already at the target floor");
        assertFalse(WalkedStepRules.jumpNow(Kind.FLAT, true, 4.0D, 5, false));
        assertTrue(WalkedStepRules.jumpNow(Kind.SWIM, false, 4.0D, 4, true), "the head is under water: keep afloat");
        assertTrue(WalkedStepRules.jumpNow(Kind.SWIM, false, 3.0D, 4, false), "the target is higher");
        assertTrue(WalkedStepRules.jumpNow(Kind.SWIM, false, 4.0D, 4, false), "feet in the lower part of the cell: hold the depth");
        assertFalse(WalkedStepRules.jumpNow(Kind.SWIM, false, 4.6D, 4, false), "feet high in the cell: no need to jump");
    }

    @Test
    void aSwimmerHoldsItsDepthAndSinksToALowerCell() {
        // Level: jump only while the feet are in the lower part of the cell (no bobbing up out of it, no sinking below it).
        assertTrue(WalkedStepRules.jumpNow(Kind.SWIM, false, 4.1D, 4, true));
        assertFalse(WalkedStepRules.jumpNow(Kind.SWIM, false, 4.5D, 4, true));
        // Down: the target is a cell lower, the jump key stays up and gravity does the diving.
        assertFalse(WalkedStepRules.jumpNow(Kind.SWIM, false, 4.1D, 3, true));
        // Up: keep jumping until the feet are in the target cell.
        assertTrue(WalkedStepRules.jumpNow(Kind.SWIM, false, 4.9D, 5, true));
        // Afloat against a bank: the hop onto it holds jump although nothing is under the feet.
        assertTrue(WalkedStepRules.jumpNow(Kind.STEP_UP, false, 4.0D, 5, false, true));
        assertFalse(WalkedStepRules.jumpNow(Kind.STEP_UP, false, 4.0D, 5, false, false));
    }

    @Test
    void aSwimStepIsGivenLongerThanAWalkBecauseASwimmerIsSlower() {
        assertEquals(WalkedStepRules.timeoutBudget(2.0D), WalkedStepRules.timeoutBudget(Kind.FLAT, 2.0D));
        assertEquals(WalkedStepRules.timeoutBudget(2.0D) * WalkedStepRules.SWIM_BUDGET_FACTOR,
                WalkedStepRules.timeoutBudget(Kind.SWIM, 2.0D));
        assertTrue(WalkedStepRules.timeoutBudget(Kind.SWIM, 1.0D) >= 40.0D, "one block of swimming is about ten ticks: at least a few times that");
    }
}
