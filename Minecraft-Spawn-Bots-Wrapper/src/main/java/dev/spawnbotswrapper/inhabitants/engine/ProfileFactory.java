package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.sample.DeckStore;

/**
 * Port to the profile generator so the engine can be tested with a trivial fake.
 * <p>
 * The engine owns the randomness policy and passes it in: in normal mode {@code botSeed} is random and
 * {@code decks} is the world-wide persistent store (coverage carries across bots and restarts); in
 * deterministic mode {@code botSeed} is derived from the structure and {@code decks} is a fresh transient
 * store per structure, so the result depends only on the structure identity.
 */
public interface ProfileFactory {
    BotProfile create(long botSeed, GlobalCapabilities capabilities, DeckStore decks);
}
