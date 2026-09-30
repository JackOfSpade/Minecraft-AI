package dev.spawnbotswrapper.inhabitants.profile;

import dev.spawnbotswrapper.inhabitants.sample.CoverageSampler;

import java.util.List;

/**
 * The only door through which the generator draws randomness. Every method takes a stable, namespaced
 * key (for example {@code profile.armor.head.tier}) so each facet owns its own {@link
 * dev.spawnbotswrapper.inhabitants.sample.Deck}: ranges are covered bucket by bucket and booleans and
 * categories stay balanced, instead of clustering around the middle like plain random numbers.
 * <p>
 * Keys are a fixed, bounded set (the world-wide deck store persists one deck per key), and a key is
 * never reused for a differently shaped draw.
 */
final class Roller {
    private final CoverageSampler sampler;
    private final int buckets;

    Roller(CoverageSampler sampler, int buckets) {
        this.sampler = sampler;
        this.buckets = Math.max(2, buckets);
    }

    /**
     * Integer in [lo, hi]. Wide ranges (0..64 food) are drawn as a bucketed double and
     * rounded, because the sampler snaps only double edge buckets to the exact endpoints; with plain
     * integer buckets the true minimum and maximum of a 257-value range would be seen once in ~250 draws.
     * Narrow ranges use integer buckets, where every value is dealt once per cycle.
     */
    int count(String key, int lo, int hi) {
        long span = (long) hi - lo + 1L;
        if (span >= 3L * buckets) {
            int v = (int) Math.round(sampler.nextDouble(key, lo, hi));
            return Math.max(lo, Math.min(hi, v));
        }
        return sampler.nextInt(key, lo, hi);
    }

    /** Enchantment-style level 0..max; 0 means "not enchanted". */
    int level(String key, int max) {
        return sampler.nextInt(key, 0, max);
    }

    double fraction(String key, double lo, double hi, int decimals) {
        return sampler.nextDoubleRounded(key, lo, hi, decimals);
    }

    /** Exactly balanced boolean. */
    boolean flag(String key) {
        return sampler.nextBoolean(key);
    }

    /** Index in [0, n); every index is dealt once per cycle. */
    int index(String key, int n) {
        return sampler.pickIndex(key, n);
    }

    /** True for exactly one card in {@code n} per cycle: a rare option that is still guaranteed to show up. */
    boolean oneIn(String key, int n) {
        return index(key, n) == 0;
    }

    /** Uniform pick over a fixed-size list; every entry is dealt once per cycle. */
    <T> T pick(String key, List<T> options) {
        return sampler.pick(key, options);
    }

    /**
     * As {@link #pick} for lists whose size depends on the global capabilities. The size is part of the
     * key so switching a capability never re-purposes (and resets) a deck of another shape.
     */
    <T> T pickVarying(String key, List<T> options) {
        return sampler.pick(key + "/" + options.size(), options);
    }
}
