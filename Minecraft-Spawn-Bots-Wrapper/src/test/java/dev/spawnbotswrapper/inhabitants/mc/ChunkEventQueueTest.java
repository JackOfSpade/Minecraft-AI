package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChunkEventQueueTest {

    /** Identity-equal stand-in for a chunk object: two instances are never equal, even with the same label. */
    private static final class Chunk {
        final String label;

        Chunk(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static List<ChunkEventQueue.Entry<Chunk>> drainAll(ChunkEventQueue<Chunk> q) {
        List<ChunkEventQueue.Entry<Chunk>> out = new ArrayList<>();
        q.drain(Integer.MAX_VALUE, out::add);
        return out;
    }

    @Test
    void drainsOldestFirstAndOnlyAsManyAsAsked() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(10);
        Chunk a = new Chunk("a");
        Chunk b = new Chunk("b");
        Chunk c = new Chunk("c");
        q.loaded(a);
        q.loaded(b);
        q.loaded(c);

        List<ChunkEventQueue.Entry<Chunk>> first = new ArrayList<>();
        assertEquals(2, q.drain(2, first::add));
        assertSame(a, first.get(0).chunk());
        assertSame(b, first.get(1).chunk());
        assertEquals(1, q.size());
        assertEquals(1, drainAll(q).size());
        assertEquals(0, q.size());
        assertEquals(0, q.drain(5, e -> fail("nothing should be left")));
    }

    @Test
    void generateFollowingLoadFlagsTheSameEntryInsteadOfQueueingTwice() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(10);
        Chunk fresh = new Chunk("generated");
        Chunk old = new Chunk("from disk");
        q.loaded(fresh);
        q.generated(fresh);
        q.loaded(old);

        List<ChunkEventQueue.Entry<Chunk>> out = drainAll(q);
        assertEquals(2, out.size());
        assertSame(fresh, out.get(0).chunk());
        assertTrue(out.get(0).generated());
        assertSame(old, out.get(1).chunk());
        assertFalse(out.get(1).generated());
    }

    @Test
    void generateWithoutAPendingLoadStillQueuesTheChunkFlagged() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(10);
        Chunk c = new Chunk("late");
        q.generated(c);
        List<ChunkEventQueue.Entry<Chunk>> out = drainAll(q);
        assertEquals(1, out.size());
        assertTrue(out.get(0).generated());
    }

    @Test
    void theSameChunkLoadedTwiceBeforeDrainingIsQueuedOnce() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(10);
        Chunk c = new Chunk("c");
        q.loaded(c);
        q.loaded(c);
        assertEquals(1, q.size());
        assertEquals(1, drainAll(q).size());
    }

    @Test
    void aChunkThatWasDrainedCanBeQueuedAgainWithoutAStaleFlag() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(10);
        Chunk c = new Chunk("c");
        q.loaded(c);
        q.generated(c);
        assertTrue(drainAll(q).get(0).generated());
        q.loaded(c);
        List<ChunkEventQueue.Entry<Chunk>> again = drainAll(q);
        assertEquals(1, again.size());
        assertFalse(again.get(0).generated(), "the flag belongs to the earlier entry only");
    }

    @Test
    void whenFullTheOldestAreDroppedAndCounted() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(3);
        Chunk[] chunks = new Chunk[6];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = new Chunk("c" + i);
            q.loaded(chunks[i]);
        }
        assertEquals(3, q.size());
        assertEquals(3, q.dropped());
        List<ChunkEventQueue.Entry<Chunk>> out = drainAll(q);
        assertEquals(List.of(chunks[3], chunks[4], chunks[5]), out.stream().map(ChunkEventQueue.Entry::chunk).toList());
    }

    @Test
    void aDroppedChunkCanComeBackAndAGenerateForItDoesNotResurrectTheOldEntry() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(1);
        Chunk dropped = new Chunk("dropped");
        Chunk kept = new Chunk("kept");
        q.loaded(dropped);
        q.loaded(kept);
        assertEquals(1, q.dropped());
        q.generated(dropped);
        // "dropped" is queued anew (flagged), which in turn pushes "kept" out; the queue never exceeds its bound.
        assertEquals(1, q.size());
        List<ChunkEventQueue.Entry<Chunk>> out = drainAll(q);
        assertEquals(1, out.size());
        assertSame(dropped, out.get(0).chunk());
        assertTrue(out.get(0).generated());
    }

    @Test
    void clearForgetsEverything() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(5);
        Chunk c = new Chunk("c");
        q.loaded(c);
        q.clear();
        assertEquals(0, q.size());
        assertTrue(drainAll(q).isEmpty());
        q.loaded(c);
        assertEquals(1, q.size(), "a cleared chunk can be queued again");
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkEventQueue<Chunk>(0));
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(1);
        assertThrows(NullPointerException.class, () -> q.loaded(null));
        assertThrows(NullPointerException.class, () -> q.generated(null));
    }

    @Test
    void anExceptionInTheConsumerDoesNotLoseTheRestOfTheQueue() {
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(5);
        q.loaded(new Chunk("bad"));
        q.loaded(new Chunk("good"));
        assertThrows(IllegalStateException.class, () -> q.drain(2, e -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, q.size(), "the failing entry was consumed, the next one is still there");
        assertEquals(1, drainAll(q).size());
    }

    @Test
    void concurrentProducersLoseNothingBeyondTheBound() throws Exception {
        int producers = 4;
        int perProducer = 5000;
        ChunkEventQueue<Chunk> q = new ChunkEventQueue<>(1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch go = new CountDownLatch(1);
        List<Chunk> all = Collections.synchronizedList(new ArrayList<>());
        for (int p = 0; p < producers; p++) {
            int id = p;
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < perProducer; i++) {
                    Chunk c = new Chunk(id + ":" + i);
                    all.add(c);
                    q.loaded(c);
                    if (i % 3 == 0) {
                        q.generated(c);
                    }
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(producers * perProducer, q.size());
        Set<Chunk> seen = ConcurrentHashMap.newKeySet();
        q.drain(Integer.MAX_VALUE, e -> assertTrue(seen.add(e.chunk()), "no chunk is delivered twice"));
        assertEquals(all.size(), seen.size());
        assertEquals(0, q.size());
        assertEquals(0, q.dropped());
    }
}
