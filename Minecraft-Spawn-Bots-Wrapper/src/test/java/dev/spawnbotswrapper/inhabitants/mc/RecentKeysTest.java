package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecentKeysTest {

    @Test
    void addReportsWhetherTheKeyWasNew() {
        RecentKeys<String> keys = new RecentKeys<>(4);
        assertFalse(keys.contains("a"));
        assertTrue(keys.add("a"));
        assertFalse(keys.add("a"));
        assertTrue(keys.contains("a"));
        assertEquals(1, keys.size());
    }

    @Test
    void evictsTheLeastRecentlyUsedKeyWhenFull() {
        RecentKeys<String> keys = new RecentKeys<>(3);
        keys.add("a");
        keys.add("b");
        keys.add("c");
        keys.add("d");
        assertEquals(3, keys.size());
        assertFalse(keys.contains("a"), "the oldest key is forgotten");
        assertTrue(keys.contains("b"));
        assertTrue(keys.contains("d"));
    }

    @Test
    void lookingAKeyUpKeepsItAlive() {
        RecentKeys<String> keys = new RecentKeys<>(3);
        keys.add("a");
        keys.add("b");
        keys.add("c");
        assertTrue(keys.contains("a"), "refreshes a");
        keys.add("d");
        assertTrue(keys.contains("a"));
        assertFalse(keys.contains("b"), "b was the least recently used");
    }

    @Test
    void neverGrowsBeyondItsCapacity() {
        RecentKeys<Integer> keys = new RecentKeys<>(100);
        for (int i = 0; i < 10_000; i++) {
            keys.add(i);
        }
        assertEquals(100, keys.size());
        assertTrue(keys.contains(9_999));
        assertFalse(keys.contains(0));
    }

    @Test
    void clearForgetsEverythingAndRejectsBadCapacity() {
        RecentKeys<String> keys = new RecentKeys<>(2);
        keys.add("a");
        keys.clear();
        assertEquals(0, keys.size());
        assertFalse(keys.contains("a"));
        assertThrows(IllegalArgumentException.class, () -> new RecentKeys<String>(0));
    }
}
