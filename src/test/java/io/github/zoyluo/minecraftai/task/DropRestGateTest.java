package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DropRestGateTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void onGroundIsAlwaysAtRest() {
        assertTrue(new DropRestGate().atRest(A, 1, true, false, 0.5D));
    }

    @Test
    void anAirborneApexReadingAloneIsNotAtRest() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D), "single slow airborne sample is the pop apex");
    }

    @Test
    void apexFollowedByFallingIsNeverAtRest() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
        assertFalse(gate.atRest(A, 6, false, false, 0.0016D));
        assertFalse(gate.atRest(A, 7, false, false, 1.0E-6D), "the slow streak was broken by the fast sample");
    }

    @Test
    void twoSuccessiveSlowSamplesOfTheSameEntityAreAtRest() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
        assertTrue(gate.atRest(A, 6, false, false, 1.0E-6D));
    }

    @Test
    void aSlowSampleOfADifferentEntityDoesNotInheritTheStreak() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
        assertFalse(gate.atRest(B, 6, false, false, 1.0E-6D));
    }

    @Test
    void aSameTickRepeatIsNotASecondSample() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
    }

    @Test
    void slowWhileTouchingWaterIsAtRestImmediatelyButFastIsNot() {
        assertTrue(new DropRestGate().atRest(A, 5, false, true, 1.0E-6D));
        assertFalse(new DropRestGate().atRest(A, 5, false, true, 0.01D));
    }

    @Test
    void twoSlowSamplesFarApartNeverCombine() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
        assertFalse(gate.atRest(A, 5 + DropRestGate.MAX_SAMPLE_GAP_TICKS + 1, false, false, 1.0E-6D),
                "the ticks in between were never observed: the item may have been falling");
        assertTrue(gate.atRest(A, 5 + DropRestGate.MAX_SAMPLE_GAP_TICKS + 2, false, false, 1.0E-6D),
                "a fresh close pair after the gap is a real rest");
    }

    @Test
    void aSlowSampleWithinTheGapBoundCombines() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
        assertTrue(gate.atRest(A, 5 + DropRestGate.MAX_SAMPLE_GAP_TICKS, false, false, 1.0E-6D));
    }

    @Test
    void aBackwardsClockNeverCombines() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 50, false, false, 1.0E-6D));
        assertFalse(gate.atRest(A, 49, false, false, 1.0E-6D));
    }

    @Test
    void resetForgetsTheTrackedItem() {
        DropRestGate gate = new DropRestGate();
        assertFalse(gate.atRest(A, 5, false, false, 1.0E-6D));
        gate.reset();
        assertFalse(gate.atRest(A, 6, false, false, 1.0E-6D), "the streak must not survive a reset");
        assertTrue(gate.atRest(A, 7, false, false, 1.0E-6D));
    }
}
