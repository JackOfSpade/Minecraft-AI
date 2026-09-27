package dev.spawnbotswrapper.inhabitants.util;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Tiny SplitMix64 generator with every derived operation implemented here, so a given seed produces
 * the same stream on every JDK. (The default methods of {@link RandomGenerator} are not guaranteed
 * to keep their algorithms across JDK releases; deterministic mode must not depend on them.)
 * Not thread-safe; create one per use.
 */
public final class SplitMix64 implements RandomGenerator {
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /** A generator seeded from ambient entropy, for non-deterministic mode. */
    public static SplitMix64 fromEntropy() {
        long seed = System.nanoTime() ^ (new java.security.SecureRandom().nextLong());
        return new SplitMix64(seed);
    }

    @Override
    public long nextLong() {
        state += GOLDEN;
        return StableHash.mix(state);
    }

    @Override
    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    /** Uniform in [0, bound) using rejection sampling (unbiased). */
    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long limit = (1L << 31) - ((1L << 31) % bound);
        long r;
        do {
            r = nextLong() >>> 33; // 31 random bits
        } while (r >= limit);
        return (int) (r % bound);
    }

    /** Uniform in [origin, bound), pinned to this class's own algorithm instead of the JDK default. */
    @Override
    public int nextInt(int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("origin >= bound: " + origin + ".." + bound);
        }
        long span = (long) bound - origin;
        return span <= Integer.MAX_VALUE
                ? origin + nextInt((int) span)
                : origin + (int) Long.remainderUnsigned(nextLong(), span);
    }

    /** Uniform in [origin, bound]. Inclusive upper bound, unlike {@link #nextInt(int, int)}. */
    public int nextIntInclusive(int lo, int hiInclusive) {
        if (hiInclusive < lo) {
            throw new IllegalArgumentException("hi < lo: " + lo + ".." + hiInclusive);
        }
        long span = (long) hiInclusive - lo + 1L;
        if (span > Integer.MAX_VALUE) {
            return lo + (int) (Long.remainderUnsigned(nextLong(), span));
        }
        return lo + nextInt((int) span);
    }

    /** Uniform in [0, 1) with 53 bits of precision. */
    @Override
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    @Override
    public boolean nextBoolean() {
        return nextLong() < 0;
    }

    /** Fisher-Yates shuffle with this generator's own bounded ints. */
    public void shuffle(int[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    public <T> T pick(List<T> list) {
        return list.get(nextInt(list.size()));
    }
}
