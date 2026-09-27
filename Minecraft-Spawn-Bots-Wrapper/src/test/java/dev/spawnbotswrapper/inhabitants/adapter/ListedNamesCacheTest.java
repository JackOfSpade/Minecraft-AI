package dev.spawnbotswrapper.inhabitants.adapter;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListedNamesCacheTest {

    private final AtomicInteger loads = new AtomicInteger();

    private ListedNamesCache.Loader loader(String name) {
        return () -> {
            loads.incrementAndGet();
            return Map.of("k", name + loads.get());
        };
    }

    @Test
    void oneTickReadsTheListOnce() throws Throwable {
        ListedNamesCache cache = new ListedNamesCache();
        Map<String, String> a = cache.get(100, loader("v"));
        Map<String, String> b = cache.get(100, loader("v"));
        assertSame(a, b);
        assertEquals(1, loads.get());
    }

    @Test
    void aNewTickReadsAgain() throws Throwable {
        ListedNamesCache cache = new ListedNamesCache();
        cache.get(100, loader("v"));
        cache.get(101, loader("v"));
        assertEquals(2, loads.get());
    }

    @Test
    void anUnknownTickIsNeverCached() throws Throwable {
        ListedNamesCache cache = new ListedNamesCache();
        cache.get(-1, loader("v"));
        cache.get(-1, loader("v"));
        cache.get(-1, loader("v"));
        assertEquals(3, loads.get());
    }

    @Test
    void invalidationForcesARead() throws Throwable {
        ListedNamesCache cache = new ListedNamesCache();
        cache.get(100, loader("v"));
        cache.invalidate();
        cache.get(100, loader("v"));
        assertEquals(2, loads.get());
    }

    @Test
    void aFailingLoadIsNotCachedAndPropagates() throws Throwable {
        ListedNamesCache cache = new ListedNamesCache();
        cache.get(100, loader("v"));
        cache.invalidate();
        assertThrows(IllegalStateException.class, () -> cache.get(100, () -> {
            throw new IllegalStateException("upstream broke");
        }));
        cache.get(100, loader("v"));
        assertEquals(2, loads.get(), "the failure left nothing behind, so the next call reads");
    }

    @Test
    void aFailureAfterACachedTickDoesNotServeTheOldValueForALaterTick() throws Throwable {
        ListedNamesCache cache = new ListedNamesCache();
        cache.get(100, loader("v"));
        assertThrows(IllegalStateException.class, () -> cache.get(101, () -> {
            throw new IllegalStateException("upstream broke");
        }));
        cache.get(101, loader("v"));
        assertEquals(2, loads.get());
    }

    @Test
    void withoutAKnownServerTickEveryQuestionReadsThrough() {
        // Upstream copies its whole set on every call, which is why a known tick caches the read (tests
        // above). With no server instance the tick is unknown and the adapter must not guess: it reads fresh.
        AdapterFixture f = AdapterFixture.probed();
        org.stepan1411.testdouble.Recorder.LISTED.add("Inh_Foo");
        int before = org.stepan1411.testdouble.Recorder.getAllBotsCalls;
        for (int i = 0; i < 50; i++) {
            f.adapter.isManaged("Inh_Foo");
        }
        assertEquals(before + 50, org.stepan1411.testdouble.Recorder.getAllBotsCalls);
    }
}
