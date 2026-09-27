package dev.spawnbotswrapper.inhabitants.sample;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * World-wide decks whose state can be exported to / imported from plain data (see
 * {@link Snapshot}); persistence itself is the caller's concern (see the store package).
 * Every mutation marks the store dirty so the caller can debounce disk writes.
 */
public final class PersistentDeckStore implements DeckStore {

    /** Serialisable form of one deck. Public fields keep Gson mapping trivial. */
    public static final class Snapshot {
        public int size;
        public int[] order;
        public int cursor;

        public Snapshot() {
        }

        Snapshot(Deck d) {
            this.size = d.size();
            this.order = d.order();
            this.cursor = d.cursor();
        }
    }

    private final Map<String, Deck> decks = new LinkedHashMap<>();
    private boolean dirty;

    @Override
    public Deck deck(String key, int size) {
        Deck d = decks.get(key);
        if (d == null || d.size() != size) {
            d = new Deck(size);
            decks.put(key, d);
        }
        dirty = true; // a draw is about to advance the cursor
        return d;
    }

    /** Loads previously exported state. Invalid entries are ignored (they will be recreated). */
    public void importSnapshots(Map<String, Snapshot> snapshots) {
        decks.clear();
        if (snapshots == null) {
            return;
        }
        for (Map.Entry<String, Snapshot> e : snapshots.entrySet()) {
            Snapshot s = e.getValue();
            if (s == null || s.size < 1) {
                continue;
            }
            decks.put(e.getKey(), Deck.restore(s.size, s.order, s.cursor));
        }
        dirty = false;
    }

    public Map<String, Snapshot> exportSnapshots() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (Map.Entry<String, Deck> e : decks.entrySet()) {
            out.put(e.getKey(), new Snapshot(e.getValue()));
        }
        return out;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }
}
