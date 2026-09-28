package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins mining-assist design 4.3/C1: the server-wide route-start budget (ms bucket plus one-start-per-tick token). */
class RouteBudgetTest {
    @Test
    void startsFullAndAllowsAStart() {
        RouteBudget b = new RouteBudget(100);
        assertEquals(100.0, b.availableMs(0));
        assertTrue(b.canStart(0, false));
    }

    @Test
    void noteStartTakesTheOneStartPerTickTokenAcrossAllBots() {
        RouteBudget b = new RouteBudget(100);
        assertTrue(b.canStart(5, false));
        b.noteStart(5, 10_000_000L, false); // 10ms
        assertFalse(b.canStart(5, false)); // same tick: token already spent
        assertTrue(b.canStart(6, false)); // next tick: token free again
    }

    @Test
    void noteStartDeductsTheCeilingOfTheMillisecondCost() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(0, 30_000_001L, false); // just over 30ms -> ceil to 31ms
        assertEquals(69.0, b.availableMs(0));
    }

    @Test
    void aNegativeCostCountsAsZero() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(0, -50L, false);
        assertEquals(100.0, b.availableMs(0));
    }

    @Test
    void balanceFloorsAtNegativeCapacity() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(0, 250_000_000L, false); // 250ms cost: far past the 100ms capacity
        assertEquals(-100.0, b.availableMs(0));
    }

    @Test
    void refillAddsFiveMsPerElapsedTickCappedAtCapacity() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(0, 100_000_000L, false); // 100ms cost -> balance 0
        assertEquals(0.0, b.availableMs(0));
        assertEquals(25.0, b.availableMs(5)); // +5 ticks * 5ms
        assertEquals(100.0, b.availableMs(50)); // capped back at capacity
    }

    @Test
    void canStartNeedsAtLeastMinStartMsAfterRefilling() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(0, 79_000_000L, false); // 79ms cost -> balance 21ms (below MIN_START_MS=30)
        assertFalse(b.canStart(1, false)); // +5ms -> 26ms, still short
        assertTrue(b.canStart(2, false)); // +5ms more -> 31ms, enough
    }

    @Test
    void deterministicModeIgnoresTheBucketButStillTakesTheToken() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(0, 100_000_000_000L, false); // drains the bucket to its floor (-100)
        assertEquals(-100.0, b.availableMs(0));
        assertTrue(b.canStart(1, true)); // deterministic: the bucket is not consulted
        b.noteStart(1, 999_000_000L, true); // deterministic: the token is taken, nothing is deducted
        assertFalse(b.canStart(1, true)); // token still held this tick, in either mode
        assertTrue(b.canStart(2, true));
    }

    @Test
    void aBackwardsTickResetsTheRefillClockWithoutRefilling() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(10, 100_000_000L, false); // balance -> 0 at tick 10
        assertEquals(0.0, b.availableMs(10));
        assertEquals(0.0, b.availableMs(5)); // clock moved backwards: no refill
        assertEquals(25.0, b.availableMs(10)); // clock resumes forward from the tick-5 baseline: +5 ticks * 5ms
    }

    @Test
    void reconfigureClampsToAtLeastMinStartMsAndRefillsFull() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(0, 100_000_000L, false); // balance -> 0
        b.reconfigure(10); // below MIN_START_MS: clamped to 30
        assertEquals(30.0, b.availableMs(0));
        assertTrue(b.canStart(1, false));
    }

    @Test
    void resetGivesAFullBucketAndClearsTheToken() {
        RouteBudget b = new RouteBudget(100);
        b.noteStart(5, 100_000_000L, false);
        b.reset();
        assertEquals(100.0, b.availableMs(5));
        assertTrue(b.canStart(5, false)); // token cleared even on the same tick
    }

    @Test
    void sharedIsAProcessWideSingleton() {
        assertSame(RouteBudget.shared(), RouteBudget.shared());
    }
}
