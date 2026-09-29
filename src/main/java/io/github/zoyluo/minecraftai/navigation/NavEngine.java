package io.github.zoyluo.minecraftai.navigation;

import java.util.Locale;

/**
 * Which navigator answers an ordinary walk request: the mod's own pathfinder ({@link #LEGACY}, the default) or the vendored
 * Baritone ({@link #BARITONE}). Configured by {@code nav.engine} in {@code minecraftai.json}; see {@code docs/NAVIGATION_ENGINE.md}.
 *
 * <p>This type and everything else in this package is deliberately free of {@code baritone.*} references, so that reading the
 * configured engine (which happens on every path request) can never be what initialises Baritone.</p>
 */
public enum NavEngine {
    LEGACY("legacy"),
    BARITONE("baritone");

    private final String configValue;

    NavEngine(String configValue) {
        this.configValue = configValue;
    }

    /** The value written to and read from the config file. */
    public String configValue() {
        return configValue;
    }

    /** Whether {@code value} names an engine (case-insensitive, surrounding blanks ignored). */
    public static boolean isKnown(String value) {
        return parseOrNull(value) != null;
    }

    /** The engine {@code value} names; anything missing or unknown is {@link #LEGACY}, the safe default. */
    public static NavEngine parse(String value) {
        NavEngine parsed = parseOrNull(value);
        return parsed == null ? LEGACY : parsed;
    }

    private static NavEngine parseOrNull(String value) {
        if (value == null) {
            return null;
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        for (NavEngine engine : values()) {
            if (engine.configValue.equals(normalised)) {
                return engine;
            }
        }
        return null;
    }
}
