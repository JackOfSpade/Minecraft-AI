package dev.spawnbotswrapper.inhabitants.sample;

import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.List;

/**
 * Coverage-oriented random values.
 * <p>
 * Plain uniform (and especially normal) sampling of a range clusters results and leaves the extremes
 * rarely seen. Here every setting owns a {@link Deck} of buckets: the range is cut into N buckets and
 * each is dealt exactly once, in random order, before any repeats. Within the dealt bucket the value
 * is uniform. So over any N consecutive bots you get N distinct regions of the range - very slow
 * through extremely fast - while individual values still look random.
 * <ul>
 *   <li>Booleans use a 2-card deck, so they are exactly balanced over every pair of draws.</li>
 *   <li>Categorical picks use one card per option: every option appears once per cycle.</li>
 *   <li>The first and last bucket of a numeric range snap to the exact endpoint with a small
 *       probability, so the true minimum and maximum are actually reachable.</li>
 * </ul>
 * Not thread-safe (uses one {@link SplitMix64} and mutable decks); use from the server thread.
 */
public final class CoverageSampler {
    /** Default number of buckets a numeric range is split into. */
    public static final int DEFAULT_BUCKETS = 8;
    /** Probability that a draw from an edge bucket returns the exact endpoint. */
    public static final double EDGE_SNAP_CHANCE = 0.2;

    private final SplitMix64 rng;
    private final DeckStore decks;
    private final int buckets;

    public CoverageSampler(SplitMix64 rng, DeckStore decks) {
        this(rng, decks, DEFAULT_BUCKETS);
    }

    public CoverageSampler(SplitMix64 rng, DeckStore decks, int buckets) {
        this.rng = rng;
        this.decks = decks;
        this.buckets = Math.max(2, buckets);
    }

    public SplitMix64 rng() {
        return rng;
    }

    /** Uniform-within-bucket double in [lo, hi], bucket chosen by the deck for {@code key}. */
    public double nextDouble(String key, double lo, double hi) {
        return nextDouble(key, lo, hi, buckets);
    }

    public double nextDouble(String key, double lo, double hi, int bucketCount) {
        if (hi < lo) {
            throw new IllegalArgumentException("hi < lo for " + key + ": " + lo + ".." + hi);
        }
        if (hi == lo) {
            return lo;
        }
        int n = Math.max(2, bucketCount);
        int b = decks.deck(key, n).draw(rng);
        double width = (hi - lo) / n;
        double v = lo + width * (b + rng.nextDouble());
        if (b == 0 && rng.nextDouble() < EDGE_SNAP_CHANCE) {
            v = lo;
        } else if (b == n - 1 && rng.nextDouble() < EDGE_SNAP_CHANCE) {
            v = hi;
        }
        return Math.min(hi, Math.max(lo, v));
    }

    /** As {@link #nextDouble(String, double, double)} but rounded to {@code decimals} places, then re-clamped. */
    public double nextDoubleRounded(String key, double lo, double hi, int decimals) {
        double scale = Math.pow(10, decimals);
        double v = Math.round(nextDouble(key, lo, hi) * scale) / scale;
        return Math.min(hi, Math.max(lo, v));
    }

    /** Integer in [lo, hiInclusive]; the integer range is split evenly into at most {@code buckets} groups. */
    public int nextInt(String key, int lo, int hiInclusive) {
        if (hiInclusive < lo) {
            throw new IllegalArgumentException("hi < lo for " + key + ": " + lo + ".." + hiInclusive);
        }
        long span = (long) hiInclusive - lo + 1L;
        if (span == 1) {
            return lo;
        }
        int n = (int) Math.min(buckets, span);
        int b = decks.deck(key, n).draw(rng);
        long from = lo + (span * b) / n;
        long to = lo + (span * (b + 1)) / n - 1;
        return (int) (from + rng.nextIntInclusive(0, (int) (to - from)));
    }

    /** Exactly balanced: every two consecutive draws for the same key yield one true and one false. */
    public boolean nextBoolean(String key) {
        return decks.deck(key, 2).draw(rng) == 0;
    }

    /** Index in [0, n); every index is dealt once per cycle. */
    public int pickIndex(String key, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("nothing to pick for " + key);
        }
        if (n == 1) {
            return 0;
        }
        return decks.deck(key, n).draw(rng);
    }

    public <T> T pick(String key, List<T> options) {
        return options.get(pickIndex(key, options.size()));
    }
}
