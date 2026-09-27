package dev.spawnbotswrapper.inhabitants.sample;

/**
 * Where {@link CoverageSampler} keeps its decks, keyed by setting name.
 * <ul>
 *   <li>{@link TransientDeckStore}: in-memory, discarded when the sampler is. Used in deterministic
 *       mode (one per structure) so results depend only on the structure seed.</li>
 *   <li>{@link PersistentDeckStore}: shared across the whole world and saved to disk, so coverage
 *       carries across server restarts instead of restarting the cycle every session.</li>
 * </ul>
 */
public interface DeckStore {
    /**
     * The deck for {@code key}, created (or reset if its size no longer matches) with {@code size}
     * entries.
     */
    Deck deck(String key, int size);
}
