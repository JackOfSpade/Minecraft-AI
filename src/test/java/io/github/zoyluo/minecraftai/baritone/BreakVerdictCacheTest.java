package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The verdict cache of the break policy: it caches, and an invalidation (server start, tag reload) makes it recompute. */
class BreakVerdictCacheTest {
    @Test
    void computesOncePerKeyUntilInvalidated() {
        BreakVerdictCache<String> cache = new BreakVerdictCache<>();
        AtomicInteger calls = new AtomicInteger();
        assertEquals("a", cache.verdict("k", key -> {
            calls.incrementAndGet();
            return "a";
        }));
        assertEquals("a", cache.verdict("k", key -> {
            calls.incrementAndGet();
            return "changed";
        }));
        assertEquals(1, calls.get(), "the second lookup is served from the cache");
        assertEquals(1, cache.size());
    }

    @Test
    void aTagReloadSurfacesTheNewVerdict() {
        BreakVerdictCache<String> cache = new BreakVerdictCache<>();
        String[] tagState = {"not_natural_terrain"}; // the tags as first bound
        assertEquals("not_natural_terrain", cache.verdict("block", key -> tagState[0]));
        tagState[0] = ""; // /reload changed the tags
        assertEquals("not_natural_terrain", cache.verdict("block", key -> tagState[0]), "stale until invalidated");
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals("", cache.verdict("block", key -> tagState[0]), "recomputed after the invalidation");
    }

    @Test
    void aVerdictComputedAcrossAnInvalidationIsNotKept() {
        BreakVerdictCache<String> cache = new BreakVerdictCache<>();
        String value = cache.verdict("block", key -> {
            cache.clear(); // the tags were reloaded while this verdict was being computed on a worker thread
            return "old";
        });
        assertEquals("old", value, "the caller of the running search still gets its answer");
        assertEquals(0, cache.size(), "but the stale value is not stored");
    }
}
