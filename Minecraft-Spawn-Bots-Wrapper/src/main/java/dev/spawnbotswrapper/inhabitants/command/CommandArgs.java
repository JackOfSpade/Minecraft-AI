package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;

import java.util.Locale;
import java.util.Optional;

/**
 * Names, limits and small parsing rules of the command tree, in one place so the Brigadier layer only
 * wires them up and the rules can be tested without a server.
 */
final class CommandArgs {
    static final String ROOT = "inhabitants";
    static final String ALIAS = "pvpbot_inhabitants";

    static final String ARG_RADIUS = "radiusChunks";
    static final String ARG_STRUCTURE = "structureId";
    static final String ARG_CHUNK_X = "chunkX";
    static final String ARG_CHUNK_Z = "chunkZ";
    static final String ARG_BOT = "botName";
    static final String ARG_CATEGORY = "category";
    static final String FLAG_REMOVE_BOTS = "removeBots";

    /** {@code nearby} radius when none is given. */
    static final int DEFAULT_NEARBY_RADIUS = 8;
    static final int MAX_RADIUS = 64;
    /** How far {@code process nearest} looks for an unprocessed structure (loaded chunks only anyway). */
    static final int PROCESS_RADIUS = 16;
    /** How far {@code reset nearest} looks for a processed structure. */
    static final int RESET_RADIUS = 16;
    /** Radius of the population lookups behind tab completion; kept small so completion stays cheap. */
    static final int SUGGEST_RADIUS = 24;
    static final int MAX_SUGGESTIONS = 200;
    /** Chunk coordinate limit: the world border is +-29,999,984 blocks. */
    static final int MAX_CHUNK = 1_875_000;

    private CommandArgs() {
    }

    /** Clamps a requested radius to {@code 1..MAX_RADIUS}. */
    static int radius(int requested) {
        return Math.max(1, Math.min(MAX_RADIUS, requested));
    }

    /** Chunk coordinate of a block coordinate ({@code floor(x) >> 4}), correct for negative positions. */
    static int chunkOf(double blockCoordinate) {
        return (int) Math.floor(blockCoordinate) >> 4;
    }

    /** The literal that selects a {@link ForceMode} in {@code process nearest}. */
    static String modeLiteral(ForceMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Lenient category lookup for {@code catalog <category>}: case-insensitive, {@code -} and spaces count as
     * {@code _}, and an unambiguous prefix is enough ({@code per}, {@code global}, {@code admin}).
     */
    static Optional<Category> parseCategory(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (s.isEmpty()) {
            return Optional.empty();
        }
        Category prefixMatch = null;
        int prefixMatches = 0;
        for (Category c : Category.values()) {
            if (c.name().equals(s)) {
                return Optional.of(c);
            }
            if (c.name().startsWith(s)) {
                prefixMatch = c;
                prefixMatches++;
            }
        }
        return prefixMatches == 1 ? Optional.of(prefixMatch) : Optional.empty();
    }
}
