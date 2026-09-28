package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmaTest {
    @Test
    void startsUnseededAtZero() {
        Ema ema = new Ema(0.1D);
        assertFalse(ema.isSeeded());
        assertEquals(0.0D, ema.value());
        assertEquals(0.1D, ema.alpha());
    }

    @Test
    void firstSampleSeedsTheValue() {
        Ema ema = new Ema(0.1D);
        assertEquals(60.0D, ema.update(60.0D));
        assertTrue(ema.isSeeded());
        assertEquals(60.0D, ema.value());
    }

    @Test
    void laterSamplesBlendByAlpha() {
        Ema ema = new Ema(0.1D);
        ema.update(60.0D);
        assertEquals(55.0D, ema.update(10.0D), 1e-12);
        assertEquals(50.5D, ema.update(10.0D), 1e-12);
        assertEquals(50.5D, ema.value(), 1e-12);

        Ema half = new Ema(0.5D);
        half.update(8.0D);
        assertEquals(4.0D, half.update(0.0D), 1e-12);
        assertEquals(2.0D, half.update(0.0D), 1e-12);
    }

    @Test
    void alphaOneTracksTheLastSample() {
        Ema ema = new Ema(1.0D);
        ema.update(5.0D);
        assertEquals(9.0D, ema.update(9.0D), 1e-12);
        assertEquals(-3.0D, ema.update(-3.0D), 1e-12);
    }

    @Test
    void convergesToAConstantInput() {
        Ema ema = new Ema(0.1D);
        ema.update(100.0D);
        for (int i = 0; i < 200; i++) {
            ema.update(20.0D);
        }
        assertEquals(20.0D, ema.value(), 1e-6);
    }

    @Test
    void firstSampleOfZeroStillSeeds() {
        Ema ema = new Ema(0.1D);
        ema.update(0.0D);
        assertTrue(ema.isSeeded());
        assertEquals(10.0D * 0.1D, ema.update(10.0D), 1e-12, "blends from the seed 0, not re-seeds");
    }

    @Test
    void nonFiniteSamplesAreIgnored() {
        Ema ema = new Ema(0.1D);
        assertEquals(0.0D, ema.update(Double.NaN));
        assertFalse(ema.isSeeded(), "a NaN must not seed");
        ema.update(10.0D);
        assertEquals(10.0D, ema.update(Double.POSITIVE_INFINITY));
        assertEquals(10.0D, ema.update(Double.NEGATIVE_INFINITY));
        assertEquals(10.0D, ema.update(Double.NaN));
        assertEquals(10.0D, ema.value());
    }

    @Test
    void resetForgetsTheSeed() {
        Ema ema = new Ema(0.1D);
        ema.update(50.0D);
        ema.reset();
        assertFalse(ema.isSeeded());
        assertEquals(0.0D, ema.value());
        assertEquals(7.0D, ema.update(7.0D));
    }

    @Test
    void rejectsAnAlphaOutsideZeroExclusiveToOneInclusive() {
        assertThrows(IllegalArgumentException.class, () -> new Ema(0.0D));
        assertThrows(IllegalArgumentException.class, () -> new Ema(-0.1D));
        assertThrows(IllegalArgumentException.class, () -> new Ema(1.0001D));
        assertThrows(IllegalArgumentException.class, () -> new Ema(Double.NaN));
    }
}
