package dev.spawnbotswrapper.inhabitants.sample;

import java.util.HashMap;
import java.util.Map;

/** Non-persistent decks; see {@link DeckStore}. */
public final class TransientDeckStore implements DeckStore {
    private final Map<String, Deck> decks = new HashMap<>();

    @Override
    public Deck deck(String key, int size) {
        Deck d = decks.get(key);
        if (d == null || d.size() != size) {
            d = new Deck(size);
            decks.put(key, d);
        }
        return d;
    }
}
