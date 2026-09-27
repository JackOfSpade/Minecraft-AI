package dev.spawnbotswrapper.inhabitants.sample;

import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.Arrays;

/**
 * A shuffled "deck" of the indices 0..size-1. Every index is dealt exactly once before the deck is
 * reshuffled, which is what gives {@link CoverageSampler} its guaranteed spread: over any run of
 * {@code size} consecutive draws each bucket / option appears exactly once.
 * <p>
 * Plain mutable state, deliberately trivially serialisable ({@link #order()} + {@link #cursor()}).
 */
public final class Deck {
    private final int[] order;
    private int cursor;

    /** A fresh deck that will shuffle on first draw. */
    public Deck(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("deck size must be >= 1: " + size);
        }
        this.order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        this.cursor = size; // forces a shuffle on first draw
    }

    /**
     * Restores a persisted deck. Returns a fresh deck when the persisted data is not a valid
     * permutation of the expected size (e.g. the configured bucket count changed).
     */
    public static Deck restore(int expectedSize, int[] persistedOrder, int persistedCursor) {
        Deck fresh = new Deck(expectedSize);
        if (persistedOrder == null || persistedOrder.length != expectedSize
                || persistedCursor < 0 || persistedCursor > expectedSize) {
            return fresh;
        }
        boolean[] seen = new boolean[expectedSize];
        for (int v : persistedOrder) {
            if (v < 0 || v >= expectedSize || seen[v]) {
                return fresh;
            }
            seen[v] = true;
        }
        System.arraycopy(persistedOrder, 0, fresh.order, 0, expectedSize);
        fresh.cursor = persistedCursor;
        return fresh;
    }

    public int size() {
        return order.length;
    }

    /** Deals the next index, reshuffling when the deck is exhausted. */
    public int draw(SplitMix64 rng) {
        if (cursor >= order.length) {
            rng.shuffle(order);
            cursor = 0;
        }
        return order[cursor++];
    }

    /** Copy of the current permutation, for persistence. */
    public int[] order() {
        return Arrays.copyOf(order, order.length);
    }

    public int cursor() {
        return cursor;
    }
}
