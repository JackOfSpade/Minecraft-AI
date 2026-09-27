package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JoinHoldGateTest {

    @Test
    void holdsAConnectionThatArrivesWithinTheWindow() {
        assertTrue(JoinHoldGate.shouldHold(1000, 1000, 1800));
        assertTrue(JoinHoldGate.shouldHold(1000, 2799, 1800));
    }

    @Test
    void releasesOnceTheWindowHasElapsed() {
        assertFalse(JoinHoldGate.shouldHold(1000, 2800, 1800));
        assertFalse(JoinHoldGate.shouldHold(1000, 50000, 1800));
    }

    @Test
    void zeroTicksDisablesTheHoldEntirely() {
        assertFalse(JoinHoldGate.shouldHold(1000, 1000, 0));
    }

    @Test
    void negativeJoinHoldTicksIsTreatedAsDisabled() {
        assertFalse(JoinHoldGate.shouldHold(1000, 1000, -5));
    }

    @Test
    void beforeServerStartedHasRunNothingIsEverHeld() {
        assertFalse(JoinHoldGate.shouldHold(-1, 5000, 1800));
    }

    @Test
    void aTickCounterThatMovedBackwardsNeverExtendsTheHold() {
        // Defensive: nowTick < startupTick should never be treated as "still within the window".
        assertFalse(JoinHoldGate.shouldHold(5000, 1000, 1800));
    }
}
