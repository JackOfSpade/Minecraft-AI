package io.github.zoyluo.minecraftai.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TpsDegradationLatchTest {
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
    void startsNormalAndStaysNormalBelowTheEnterThreshold() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        assertEquals(0, feed(latch, TpsDegradationLatch.ENTER_MS, 1000));
        assertFalse(latch.degraded());
    }

    @Test
    void entersOnlyAfterTheAverageStaysAboveTheThresholdForTheDebounce() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, 20.0D, TpsDegradationLatch.MIN_NORMAL_SAMPLES);
        assertEquals(0, feed(latch, 60.0D, TpsDegradationLatch.ENTER_SAMPLES - 1));
        assertFalse(latch.degraded());
        assertTrue(latch.update(60.0D));
        assertTrue(latch.degraded());
    }

    @Test
    void aSingleBriefSpikeAboveTheThresholdIsIgnored() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, 20.0D, 100);
        for (int i = 0; i < 50; i++) {
            latch.update(60.0D);
            latch.update(20.0D);
        }
        assertFalse(latch.degraded());
    }

    @Test
    void deadBandBetweenTheThresholdsNeverExitsTheDegradedState() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, 60.0D, 200);
        assertTrue(latch.degraded());
        double inBand = (TpsDegradationLatch.ENTER_MS + TpsDegradationLatch.EXIT_MS) / 2.0D;
        assertEquals(0, feed(latch, inBand, 10_000));
        assertTrue(latch.degraded());
    }

    @Test
    void exitNeedsTheDebounceAndTheMinimumDegradedDwell() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        while (!latch.update(60.0D)) {
            // run until the very sample that enters
        }
        assertTrue(latch.degraded());
        // Calm immediately after entering: the dwell holds the state for the full minimum.
        int dwellSamples = TpsDegradationLatch.MIN_DEGRADED_SAMPLES;
        assertEquals(0, feed(latch, 20.0D, dwellSamples - 1));
        assertTrue(latch.degraded());
        assertTrue(latch.update(20.0D));
        assertFalse(latch.degraded());
    }

    @Test
    void oscillationAroundTheOldThresholdProducesNoTransitionsAtAll() {
        // The real session flipped 278 times: the average bounced across a single 55 ms threshold.
        TpsDegradationLatch latch = new TpsDegradationLatch();
        int transitions = 0;
        feed(latch, 40.0D, 100);
        for (int i = 0; i < 5000; i++) {
            double avg = 53.0D + 4.0D * Math.sin(i / 3.0D); // 49..57, straddles 55
            if (latch.update(avg)) {
                transitions++;
            }
        }
        assertTrue(transitions <= 1, "flapped " + transitions + " times");
    }

    @Test
    void transitionsAreNeverCloserThanTheMinimumDwell() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        int lastTransition = -1;
        int minGap = Integer.MAX_VALUE;
        java.util.Random random = new java.util.Random(7L);
        for (int i = 0; i < 200_000; i++) {
            // Slow square wave with occasional dips so the latch actually flips a few thousand times.
            double avg = (i / 300) % 2 == 0 ? 20.0D : (random.nextInt(20) != 0 ? 70.0D : 20.0D);
            if (latch.update(avg)) {
                if (lastTransition >= 0) {
                    minGap = Math.min(minGap, i - lastTransition);
                }
                lastTransition = i;
            }
        }
        assertTrue(minGap >= Math.min(TpsDegradationLatch.MIN_NORMAL_SAMPLES, TpsDegradationLatch.MIN_DEGRADED_SAMPLES),
                "closest transitions were " + minGap + " samples apart");
    }

    @Test
    void nonFiniteSamplesNeverFlipTheStateAndResetTheStreak() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, 20.0D, 100);
        feed(latch, 60.0D, TpsDegradationLatch.ENTER_SAMPLES - 1);
        assertFalse(latch.update(Double.NaN));
        assertFalse(latch.update(60.0D), "the NaN sample must have restarted the debounce");
        assertFalse(latch.degraded());
    }

    @Test
    void resetReturnsToNormalWithAFreshDwell() {
        TpsDegradationLatch latch = new TpsDegradationLatch();
        feed(latch, 60.0D, 200);
        assertTrue(latch.degraded());
        latch.reset();
        assertFalse(latch.degraded());
        assertEquals(0, latch.samplesInState());
    }
}
