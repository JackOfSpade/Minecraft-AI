package dev.spawnbotswrapper.inhabitants.mc;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Hand-off between Fabric's chunk callbacks and the tick that does the real work.
 * <p>
 * The callbacks run inside the chunk-loading task, where almost nothing may be touched safely, so they only
 * record WHICH chunk arrived and whether it was newly generated; this queue is that record. Fabric fires
 * {@code CHUNK_LOAD} first and {@code CHUNK_GENERATE} second for a freshly generated chunk, so
 * {@link #generated} looks the pending entry up by chunk identity and flags it instead of queueing twice.
 * <p>
 * Bounded: when producers outrun the consumer the OLDEST entries are dropped and counted, so a stalled
 * consumer can never hold an unbounded number of chunks in memory. A dropped chunk is not lost forever, the
 * structure it holds is simply detected again the next time the chunk loads.
 *
 * @param <T> the chunk handle; equality must be identity-like (the same chunk object reported twice must be
 *            equal, two different chunks must not be)
 */
public final class ChunkEventQueue<T> {

    /** One queued chunk and whether {@code CHUNK_GENERATE} fired for it. */
    public record Entry<T>(T chunk, boolean generated) {
    }

    private static final class Pending<T> {
        final T chunk;
        volatile boolean generated;

        Pending(T chunk, boolean generated) {
            this.chunk = chunk;
            this.generated = generated;
        }
    }

    private final int capacity;
    private final ConcurrentLinkedQueue<Pending<T>> queue = new ConcurrentLinkedQueue<>();
    private final Map<T, Pending<T>> index = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();

    public ChunkEventQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** A chunk finished loading. Repeats of the same chunk before it is drained are ignored. */
    public void loaded(T chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Pending<T> fresh = new Pending<>(chunk, false);
        if (index.putIfAbsent(chunk, fresh) == null) {
            enqueue(fresh);
        }
    }

    /** {@code CHUNK_GENERATE} fired: flag the pending entry, or queue one if the load was already drained. */
    public void generated(T chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Pending<T> fresh = new Pending<>(chunk, true);
        Pending<T> existing = index.putIfAbsent(chunk, fresh);
        if (existing == null) {
            enqueue(fresh);
        } else {
            existing.generated = true;
        }
    }

    /** Hands at most {@code max} entries, oldest first, to {@code sink}. Returns how many were handed over. */
    public int drain(int max, Consumer<Entry<T>> sink) {
        int handed = 0;
        while (handed < max) {
            Pending<T> p = queue.poll();
            if (p == null) {
                break;
            }
            size.decrementAndGet();
            index.remove(p.chunk, p);
            handed++;
            sink.accept(new Entry<>(p.chunk, p.generated));
        }
        return handed;
    }

    public int size() {
        return size.get();
    }

    /** Entries discarded because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    public void clear() {
        queue.clear();
        index.clear();
        size.set(0);
    }

    private void enqueue(Pending<T> p) {
        queue.add(p);
        if (size.incrementAndGet() > capacity) {
            Pending<T> oldest = queue.poll();
            if (oldest != null) {
                size.decrementAndGet();
                index.remove(oldest.chunk, oldest);
                dropped.incrementAndGet();
            }
        }
    }
}
