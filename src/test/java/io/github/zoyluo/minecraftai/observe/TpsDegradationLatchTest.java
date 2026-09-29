package io.github.zoyluo.minecraftai.observe;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The latch is fed an EMA of the END_SERVER_TICK interval, so the values below are the ones a real server
 * produces: 50 ms is a healthy, idle server (the floor of the metric), 52.9-59 ms is this pack's normal busy
 * operation, and anything sustained above 62.5 ms (below 16 TPS) is degraded.
 */
final class TpsDegradationLatchTest {
    private static final double HEALTHY = 50.0D;
    private static final double NORMAL_LOW = 52.87D;
    private static final double NORMAL_HIGH = 59.0D;
    private static final double DEGRADED = 80.0D;

    /** Feeds {@code value} for {@code samples} ticks and returns how many transitions happened. */
    private static int feed(TpsDegradationLatch latch, double value, int samples) {
        int transitions = 0;
        for (int i = 0; i < samples; i++) {
            if (latch.update(value)) {
                transitions++;
            }
        }
        return transitions;
    }

    @Test
    void theRealThresholdsAndDwellValuesArePinned() {
        assertEquals(62.5D, TpsDegradationLatch.ENTER_MS, 0.0D);
        assertEquals(58.0D, TpsDegradationLatch.EXIT_MS, 0.0D);
        assertEquals(10, TpsDegradationLatch.ENTER_SAMPLES);
        assertEquals(40, TpsDegradationLatch.EXIT_SAMPLES);
        assertEquals(100, TpsDegradationLatch.MIN_DEGRADED_SAMPLES);
        assertEquals(40, TpsDegradationLatch.MIN_NORMAL_SAMPLES);
        // Both thresholds must be reachable for a metric that cannot go below 50 ms, and normal busy operation
        // (up to 59 ms) must sit below the enter threshold.
        assertTrue(TpsDegradationLatch.EXIT_MS > HEALTHY, "exit must be reachable: the metric never reads below 50 ms");
        assertTrue(TpsDegradationLatch.ENTER_MS > NORMAL_HIGH, "normal operation must not be degraded");
        assertTrue(TpsDegradationLatch.EXIT_MS < TpsDegradationLatch.ENTER_MS, "there must be a dead band");
        assertEquals(16.0D, 1000.0D / TpsDegradationLatch.ENTER_MS, 1e-9, "enter is 16 TPS");
    }

    @Test
    void normalOperationAcrossTheWholeObservedBandIsNeverDegraded() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        assertEquals(0, feed(latch, HEALTHY, 1000));
        assertEquals(0, feed(latch, NORMAL_LOW, 1000));
        assertEquals(0, feed(latch, NORMAL_HIGH, 100_000));
        assertFalse(latch.degraded());
    }

    @Test
    void theOldFlappingScenarioAtFiftyFiveMillisecondsProducesNoTransitions() {
        // The real session flipped 278 times: the average swung 52.87..59 across the old single 55 ms threshold.
        TpsDegradationLatch latch = new TpsDegradationLatch();
        int transitions = 0;
        for (int i = 0; i < 50_000; i++) {
            double avg = (NORMAL_LOW + NORMAL_HIGH) / 2.0D + (NORMAL_HIGH - NORMAL_LOW) / 2.0D * Math.sin(i / 3.0D);
            if (latch.update(avg)) {
                transitions++;
            }
        }
        assertEquals(0, transitions);
        assertFalse(latch.degraded());
    }

    @Test
    void exactlyTheEnterThresholdIsStillNormal() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        assertEquals(0, feed(latch, TpsDegradationLatch.ENTER_MS, 10_000));
        assertFalse(latch.degraded());
    }

    @Test
    void entersOnlyAfterTheAverageStaysDegradedForTheDebounce() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, HEALTHY, TpsDegradationLatch.MIN_NORMAL_SAMPLES);
        assertEquals(0, feed(latch, 65.0D, TpsDegradationLatch.ENTER_SAMPLES - 1));
        assertFalse(latch.degraded());
        assertTrue(latch.update(65.0D));
        assertTrue(latch.degraded());
    }

    @Test
    void aBriefSpikeAboveTheThresholdIsIgnored() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, NORMAL_LOW, 100);
        for (int i = 0; i < 200; i++) {
            feed(latch, 100.0D, TpsDegradationLatch.ENTER_SAMPLES - 1);
            latch.update(56.0D);
        }
        assertFalse(latch.degraded());
    }

    @Test
    void firstEntryIsBlockedUntilTheInitialNormalDwellHasPassed() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        // Degraded from sample one: the debounce alone (10) would flip early, the 40-sample normal dwell holds it.
        assertEquals(0, feed(latch, DEGRADED, TpsDegradationLatch.MIN_NORMAL_SAMPLES - 1));
        assertFalse(latch.degraded());
        assertTrue(latch.update(DEGRADED));
        assertTrue(latch.degraded());
    }

    @Test
    void recoversToNormalAfterTheDegradedDwellAndTheExitDebounce() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, HEALTHY, 100);
        feed(latch, DEGRADED, 200);
        assertTrue(latch.degraded());
        // The server is back to its usual busy 56 ms: inside the normal band, below the exit threshold.
        assertEquals(0, feed(latch, 56.0D, TpsDegradationLatch.EXIT_SAMPLES - 1));
        assertTrue(latch.degraded(), "one sample short of the exit debounce");
        assertTrue(latch.update(56.0D));
        assertFalse(latch.degraded());
        // ...and it stays normal at 59 ms (the top of the observed band), as the enter threshold is higher.
        assertEquals(0, feed(latch, NORMAL_HIGH, 10_000));
        assertFalse(latch.degraded());
    }

    @Test
    void exitAtExactlyTheExitThresholdCountsAsRecovered() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, DEGRADED, 200);
        assertTrue(latch.degraded());
        assertEquals(1, feed(latch, TpsDegradationLatch.EXIT_MS, TpsDegradationLatch.EXIT_SAMPLES));
        assertFalse(latch.degraded());
    }

    @Test
    void justAboveTheExitThresholdNeverRecovers() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, DEGRADED, 200);
        assertEquals(0, feed(latch, TpsDegradationLatch.EXIT_MS + 0.01D, 100_000));
        assertTrue(latch.degraded());
    }

    @Test
    void deadBandBetweenTheThresholdsNeverChangesEitherState() {
        double inBand = (TpsDegradationLatch.ENTER_MS + TpsDegradationLatch.EXIT_MS) / 2.0D; // 60.25
        TpsDegradationLatch normal = new TpsDegradationLatch();
        assertEquals(0, feed(normal, inBand, 10_000));
        assertFalse(normal.degraded(), "in the band a normal latch stays normal");

        TpsDegradationLatch degraded = new TpsDegradationLatch();
        feed(degraded, DEGRADED, 200);
        assertTrue(degraded.degraded());
        assertEquals(0, feed(degraded, inBand, 10_000));
        assertTrue(degraded.degraded(), "in the band a degraded latch stays degraded");
    }

    @Test
    void theMinimumDegradedDwellHoldsEvenIfTheServerRecoversImmediately() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        while (!latch.update(DEGRADED)) {
            // run until the very sample that enters
        }
        assertTrue(latch.degraded());
        assertEquals(0, latch.samplesInState());
        assertEquals(0, feed(latch, HEALTHY, TpsDegradationLatch.MIN_DEGRADED_SAMPLES - 1));
        assertTrue(latch.degraded());
        assertTrue(latch.update(HEALTHY));
        assertFalse(latch.degraded());
    }

    @Test
    void theMinimumNormalDwellHoldsAfterRecovery() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, DEGRADED, 200);
        while (!latch.update(HEALTHY)) {
            // run until the very sample that recovers
        }
        assertFalse(latch.degraded());
        // Straight back into overload: the 40-sample normal dwell delays re-entry (debounce alone would be 10).
        assertEquals(0, feed(latch, DEGRADED, TpsDegradationLatch.MIN_NORMAL_SAMPLES - 1));
        assertFalse(latch.degraded());
        assertTrue(latch.update(DEGRADED));
        assertTrue(latch.degraded());
    }

    @Test
    void fastOscillationAroundTheEnterThresholdNeverEnters() {
        // 61 / 64 alternating straddles 62.5 every sample but is never above it for 10 consecutive samples.
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, HEALTHY, 100);
        int transitions = 0;
        for (int i = 0; i < 100_000; i++) {
            if (latch.update(i % 2 == 0 ? 61.0D : 64.0D)) {
                transitions++;
            }
        }
        assertEquals(0, transitions);
        assertFalse(latch.degraded());
    }

    @Test
    void fastOscillationAroundTheExitThresholdNeverRecovers() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, DEGRADED, 200);
        assertTrue(latch.degraded());
        // 57 / 59 alternating straddles 58 every sample: the 40-sample low streak never builds.
        int transitions = 0;
        for (int i = 0; i < 100_000; i++) {
            if (latch.update(i % 2 == 0 ? 57.0D : 59.0D)) {
                transitions++;
            }
        }
        assertEquals(0, transitions);
        assertTrue(latch.degraded());
    }

    @Test
    void slowSwingAcrossBothThresholdsFlipsAtMostOncePerHalfCycleAndRespectsTheDwells() {
        // Slow sine 54..71 crossing both thresholds; ~630-sample period. Each cycle: one enter, one exit.
        TpsDegradationLatch latch = new TpsDegradationLatch();
        int cycles = 40;
        int period = 630;
        int transitions = 0;
        int lastTransition = -1;
        int minGap = Integer.MAX_VALUE;
        for (int i = 0; i < cycles * period; i++) {
            double avg = 62.5D + 8.5D * Math.sin(2.0D * Math.PI * i / period);
            if (latch.update(avg)) {
                transitions++;
                if (lastTransition >= 0) {
                    minGap = Math.min(minGap, i - lastTransition);
                }
                lastTransition = i;
            }
        }
        assertTrue(transitions >= cycles, "a real overload swing must be tracked (" + transitions + ")");
        assertTrue(transitions <= 2 * cycles, "flapped " + transitions + " times over " + cycles + " cycles");
        assertTrue(minGap >= TpsDegradationLatch.MIN_NORMAL_SAMPLES, "gap " + minGap);
    }

    @Test
    void noisyNormalOperationNeverLatchesDegraded() {
        // Random noise centred on 56 ms with excursions to 51 and 61: never sustained above 62.5.
        TpsDegradationLatch latch = new TpsDegradationLatch();
        Random random = new Random(11L);
        int transitions = 0;
        for (int i = 0; i < 200_000; i++) {
            double avg = 56.0D + (random.nextDouble() - 0.5D) * 10.0D;
            if (latch.update(avg)) {
                transitions++;
            }
        }
        assertEquals(0, transitions);
    }

    @Test
    void transitionsAreNeverCloserThanTheMinimumDwell() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        int lastTransition = -1;
        int minGap = Integer.MAX_VALUE;
        int transitions = 0;
        Random random = new Random(7L);
        for (int i = 0; i < 200_000; i++) {
            // Slow square wave (healthy / overloaded) with occasional dips so the latch flips thousands of times.
            double avg = (i / 300) % 2 == 0 ? HEALTHY : (random.nextInt(20) != 0 ? 75.0D : HEALTHY);
            if (latch.update(avg)) {
                transitions++;
                if (lastTransition >= 0) {
                    minGap = Math.min(minGap, i - lastTransition);
                }
                lastTransition = i;
            }
        }
        assertTrue(transitions > 100, "the wave must actually exercise the latch (" + transitions + ")");
        assertTrue(minGap >= Math.min(TpsDegradationLatch.MIN_NORMAL_SAMPLES, TpsDegradationLatch.MIN_DEGRADED_SAMPLES),
                "closest transitions were " + minGap + " samples apart");
    }

    @Test
    void nonFiniteSamplesNeverFlipTheStateAndResetTheStreak() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, HEALTHY, 100);
        feed(latch, DEGRADED, TpsDegradationLatch.ENTER_SAMPLES - 1);
        assertFalse(latch.update(Double.NaN));
        assertFalse(latch.update(DEGRADED), "the NaN sample must have restarted the debounce");
        assertFalse(latch.degraded());
    }

    @Test
    void resetReturnsToNormalWithAFreshDwell() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, DEGRADED, 200);
        assertTrue(latch.degraded());
        latch.reset();
        assertFalse(latch.degraded());
        assertEquals(0, latch.samplesInState());
    }
}
