package io.github.zoyluo.minecraftai.navigation;

import java.util.Locale;

/**
 * Navigation engine identity. Shipping navigation is Baritone-only; {@link #LEGACY} remains only
 * as a historical label for archived measurements and old config migration, never as a selectable
 * runtime executor. See {@code docs/NAVIGATION_ENGINE.md}.
 *
 * <p>This type and everything else in this package is deliberately free of {@code baritone.*} references, so that reading the
 * configured engine (which happens on every path request) can never be what initialises Baritone.</p>
 */
public enum NavEngine {
    /** Historical measurement/config-migration label; production parsing canonicalises it to {@link #BARITONE}. */
    @Deprecated
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

    /** Whether {@code value} is a recognised current value or the one-time legacy migration alias. */
    public static boolean isKnown(String value) {
        return isBaritoneValue(value) || isLegacyAlias(value);
    }

    /** Shipping policy: missing, old, and unknown values all canonicalise to the only runtime engine, Baritone. */
    public static NavEngine parse(String value) {
        return BARITONE;
    }

    /** True for an old config value that is accepted only to make migration non-breaking. */
    public static boolean isLegacyAlias(String value) {
        return normalise(value).equals(LEGACY.configValue);
    }

    private static boolean isBaritoneValue(String value) {
        return normalise(value).equals(BARITONE.configValue);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
