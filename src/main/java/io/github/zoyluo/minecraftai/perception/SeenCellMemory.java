package io.github.zoyluo.minecraftai.perception;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.ToIntFunction;

/**
 * What a bot has seen, one entry per cell, kept in the order the cells were last seen. A cell seen
 * again moves to the newest end, so the oldest sighting is always the first entry: forgetting what is
 * too old, or too much, only ever looks at the head. {@link SharedWorldSight} used to test every
 * remembered cell (up to its 32,768) on every tick of every bot to find the few that had expired.
 *
 * <p>Free of Minecraft types so that the retention rule can be exercised without a registry bootstrap.
 * Sightings must be recorded with a tick that never goes back; the world tick does not.</p>
 *
 * @param <V> a sighting, which knows the tick it was made on
 */
final class SeenCellMemory<V> {
    private final ToIntFunction<V> seenTick;
    private final int capacity;
    private final LinkedHashMap<Long, V> cells = new LinkedHashMap<>(256);

    SeenCellMemory(ToIntFunction<V> seenTick, int capacity) {
        this.seenTick = seenTick;
        this.capacity = capacity;
    }

    /** Records a sighting as the newest, replacing an older one of the same cell, and sheds the oldest beyond the capacity. */
    void remember(long key, V sighting) {
        // A plain put on an existing key would leave it where it was; the order must follow the last sighting.
        cells.remove(key);
        cells.put(key, sighting);
        while (cells.size() > capacity) {
            Iterator<Long> oldest = cells.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** The last sighting of a cell, without counting the lookup as a sighting. */
    V get(long key) {
        return cells.get(key);
    }

    /** Oldest sighting first. */
    Collection<V> sightings() {
        return Collections.unmodifiableCollection(cells.values());
    }

    boolean isEmpty() {
        return cells.isEmpty();
    }

    int size() {
        return cells.size();
    }

    void clear() {
        cells.clear();
    }

    /** Forgets every sighting made more than {@code ttlTicks} before {@code tick}; looks at nothing newer than the first survivor. */
    void expire(int tick, int ttlTicks) {
        Iterator<V> oldest = cells.values().iterator();
        while (oldest.hasNext()) {
            if ((long) tick - seenTick.applyAsInt(oldest.next()) <= ttlTicks) {
                return;
            }
            oldest.remove();
        }
    }
}
