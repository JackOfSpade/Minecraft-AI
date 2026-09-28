package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JoinHoldGateTest {

    @Test
    void holdsAConnectionThatArrivesWithinTheWindow() {
        assertTrue(JoinHoldGate.shouldHold(1000, 1000, 300));
        assertTrue(JoinHoldGate.shouldHold(1000, 1299, 300));
    }

    @Test
    void releasesOnceTheWindowHasElapsed() {
        assertFalse(JoinHoldGate.shouldHold(1000, 1300, 300));
        assertFalse(JoinHoldGate.shouldHold(1000, 50000, 300));
    }

    /**
     * Vanilla's own ServerLoginNetworkHandler force-disconnects any client still in the LOGIN phase after 600
     * ticks ("Took too long to log in"), independent of and unextendable by this class -- a configured value at
     * or beyond that would just get every held player kicked before this class's own release ever runs, so the
     * effective hold is always capped below it, no matter what connection.joinHoldTicks is actually set to.
     */
    @Test
    void neverHoldsAnywhereNearVanillasOwnSlowLoginDisconnect() {
        assertTrue(JoinHoldGate.shouldHold(1000, 1000 + JoinHoldGate.MAX_SAFE_HOLD_TICKS - 1, 1800));
        assertFalse(JoinHoldGate.shouldHold(1000, 1000 + JoinHoldGate.MAX_SAFE_HOLD_TICKS, 1800));
        assertTrue(JoinHoldGate.MAX_SAFE_HOLD_TICKS <= 300,
                "must leave a wide margin under vanilla's 600-tick disconnect -- a fresh world's own "
                        + "spawn-chunk generation already eats into the same budget");
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
