package io.github.zoyluo.minecraftai.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code forceOverflowForTest} must keep {@code enqueuedCount} balanced with what actually sits in the queue
 * (the writer bumps {@code writtenCount} once per entry it writes), or {@code awaitDrainedForTest} could
 * return before the overflow entries were written. No worker thread runs here, so every accepted entry
 * stays queued and the counters can be compared with the queue directly.
 */
final class BotLogWriterOverflowCountersTest {
    private Field started;
    private BlockingQueue<?> queue;
    private AtomicLong enqueued;
    private AtomicLong dropped;
    private boolean originalStarted;

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = BotLogWriter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        BotLogWriter writer = BotLogWriter.INSTANCE;
        started = field("started");
        queue = (BlockingQueue<?>) field("queue").get(writer);
        enqueued = (AtomicLong) field("enqueuedCount").get(writer);
        dropped = (AtomicLong) field("droppedCount").get(writer);
        originalStarted = started.getBoolean(writer);
        started.setBoolean(writer, true);
        queue.clear();
        enqueued.set(0L);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        started.setBoolean(BotLogWriter.INSTANCE, originalStarted);
        queue.clear();
        enqueued.set(0L);
    }

    @Test
    void overflowKeepsTheEnqueuedCounterEqualToTheQueuedEntries() {
        long droppedBefore = dropped.get();
        BotLogWriter.INSTANCE.forceOverflowForTest(queue.remainingCapacity() + 50);
        assertTrue(dropped.get() > droppedBefore, "the flood must overflow the queue");
        assertEquals(queue.size(), enqueued.get(), "dropped offers must not stay counted as enqueued");
    }

    @Test
    void markerEntryIsCountedToo() {
        // A small flood drops nothing, so the marker entry is offered (and counted) on top of the flood.
        BotLogWriter.INSTANCE.forceOverflowForTest(3);
        assertEquals(4, queue.size());
        assertEquals(queue.size(), enqueued.get());
    }
}
