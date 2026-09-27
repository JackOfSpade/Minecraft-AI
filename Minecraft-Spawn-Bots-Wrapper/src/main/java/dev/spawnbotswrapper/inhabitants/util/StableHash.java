package dev.spawnbotswrapper.inhabitants.util;

import java.nio.charset.StandardCharsets;

/**
 * Hashing primitives whose results are fixed forever (no dependence on JDK version, JVM flags or
 * {@link String#hashCode()} implementation details). Deterministic mode relies on this: the same
 * world seed + dimension + structure id + start position must always yield the same roll.
 */
public final class StableHash {
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private StableHash() {
    }

    /** SplitMix64 finalizer: a bijective avalanche mix of one 64-bit word. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Order-sensitive combination of an accumulated hash with one more value. */
    public static long combine(long accumulated, long value) {
        return mix(mix(accumulated) + value * GOLDEN + GOLDEN);
    }

    /** 64-bit FNV-1a over the UTF-8 bytes of {@code s}, then mixed. Null hashes like the empty string. */
    public static long ofString(String s) {
        long h = FNV_OFFSET;
        if (s != null) {
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                h ^= (b & 0xFFL);
                h *= FNV_PRIME;
            }
        }
        return mix(h);
    }

    /** Packs two ints (e.g. chunk x/z) into one long without loss. */
    public static long pack(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    /** Convenience: fold several longs in order. */
    public static long of(long first, long... rest) {
        long h = mix(first);
        for (long v : rest) {
            h = combine(h, v);
        }
        return h;
    }
}
