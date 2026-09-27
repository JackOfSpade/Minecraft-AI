package dev.spawnbotswrapper.inhabitants.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SplitMix64Test {

    @Test
    void matchesPublishedSplitMix64ReferenceVectorForSeedZero() {
        // Reference outputs of the canonical splitmix64 (Vigna) seeded with 0. Deterministic mode
        // depends on this stream never changing.
        SplitMix64 r = new SplitMix64(0L);
        assertEquals(0xE220A8397B1DCDAFL, r.nextLong());
        assertEquals(0x6E789E6AA1B965F4L, r.nextLong());
        assertEquals(0x06C45D188009454FL, r.nextLong());
    }

    @Test
    void sameSeedSameStream() {
        SplitMix64 a = new SplitMix64(123456789L);
        SplitMix64 b = new SplitMix64(123456789L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.nextInt(1000), b.nextInt(1000));
            assertEquals(a.nextDouble(), b.nextDouble());
        }
    }

    @Test
    void boundedIntsStayInRangeAndReachBothEnds() {
        SplitMix64 r = new SplitMix64(42);
        boolean sawZero = false;
        boolean sawMax = false;
        for (int i = 0; i < 20_000; i++) {
            int v = r.nextInt(7);
            assertTrue(v >= 0 && v < 7);
            sawZero |= v == 0;
            sawMax |= v == 6;
        }
        assertTrue(sawZero && sawMax);
    }

    @Test
    void inclusiveIntsReachBothEnds() {
        SplitMix64 r = new SplitMix64(7);
        boolean lo = false;
        boolean hi = false;
        for (int i = 0; i < 5_000; i++) {
            int v = r.nextIntInclusive(-3, 3);
            assertTrue(v >= -3 && v <= 3);
            lo |= v == -3;
            hi |= v == 3;
        }
        assertTrue(lo && hi);
    }

    @Test
    void doublesAreInUnitInterval() {
        SplitMix64 r = new SplitMix64(99);
        for (int i = 0; i < 10_000; i++) {
            double d = r.nextDouble();
            assertTrue(d >= 0.0 && d < 1.0);
        }
    }

    @Test
    void shuffleIsAPermutation() {
        SplitMix64 r = new SplitMix64(5);
        int[] a = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        r.shuffle(a);
        boolean[] seen = new boolean[10];
        for (int v : a) {
            assertFalse(seen[v]);
            seen[v] = true;
        }
    }

    @Test
    void rejectsNonPositiveBound() {
        assertThrows(IllegalArgumentException.class, () -> new SplitMix64(1).nextInt(0));
    }
}
