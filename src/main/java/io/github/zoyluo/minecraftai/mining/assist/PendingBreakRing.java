package io.github.zoyluo.minecraftai.mining.assist;

/**
 * Bounded FIFO of block cells the bot has just broken (mining-assist design 3.3 and 2.4, capacity
 * {@value #DEFAULT_CAPACITY}). {@code MiningAssistHooks.onBotBreak} offers packed positions in O(1);
 * the coordinator drains a few per tick for the break peek. When full, the oldest entry is dropped so
 * the newest breaks (the ones nearest the bot) survive. Pure and allocation-free, server thread only.
 */
public final class PendingBreakRing {
    public static final int DEFAULT_CAPACITY = 16;
    /** Returned by {@link #poll()} when the ring is empty (never a valid packed position). */
    public static final long EMPTY = Long.MIN_VALUE;

    private final long[] cells;
    private int head;
    private int size;
    private long dropped;

    public PendingBreakRing() {
        this(DEFAULT_CAPACITY);
    }

    public PendingBreakRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.cells = new long[capacity];
    }

    /** Queues a packed {@code BlockPos}; returns false when an older entry had to be dropped. */
    public boolean offer(long packedPos) {
        boolean clean = true;
        if (size == cells.length) {
            head = (head + 1) % cells.length;
            size--;
            dropped++;
            clean = false;
        }
        cells[(head + size) % cells.length] = packedPos;
        size++;
        return clean;
    }

    /** Removes and returns the oldest queued position, or {@link #EMPTY}. */
    public long poll() {
        if (size == 0) {
            return EMPTY;
        }
        long value = cells[head];
        head = (head + 1) % cells.length;
        size--;
        return value;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int capacity() {
        return cells.length;
    }

    /** Total entries dropped because the ring was full. */
    public long dropped() {
        return dropped;
    }

    public void clear() {
        head = 0;
        size = 0;
    }
}
