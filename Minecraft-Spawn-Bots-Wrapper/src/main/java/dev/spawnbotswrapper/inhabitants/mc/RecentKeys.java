package dev.spawnbotswrapper.inhabitants.mc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded "have I seen this recently" set with least-recently-used eviction. The detector remembers the
 * structures it has just handed over so a start chunk that unloads and reloads a moment later does not cost
 * another (potentially large) piece list; forgetting an old key is harmless because the population engine
 * ignores a structure it already knows. Server thread only.
 */
public final class RecentKeys<K> {
    private final Map<K, Boolean> keys;

    public RecentKeys(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.keys = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Membership test that also refreshes the key's recency. */
    public boolean contains(K key) {
        return keys.get(key) != null;
    }

    /** Remembers the key. Returns true when it was not remembered before. */
    public boolean add(K key) {
        return keys.put(key, Boolean.TRUE) == null;
    }

    public int size() {
        return keys.size();
    }

    public void clear() {
        keys.clear();
    }
}
