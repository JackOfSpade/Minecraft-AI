package io.github.zoyluo.aibot.mining.assist;

import java.util.Locale;
import java.util.Optional;

/**
 * Mining Assist rollout mode (design 2.1). Pure data: no config or environment access here.
 *
 * <ul>
 *   <li>{@link #OFF}: every hook is a single static boolean check.</li>
 *   <li>{@link #SENSE}: sensor plus shadow scoring and logs only.</li>
 *   <li>{@link #DETOUR}: SENSE plus the opportunistic valuables detour.</li>
 *   <li>{@link #POI}: SENSE plus point-of-interest detection and confirmation.</li>
 *   <li>{@link #ALL}: DETOUR and POI.</li>
 * </ul>
 *
 * <p>The declaration order is the rollout order, so {@link #atLeast} compares ordinals. DETOUR and
 * POI are siblings rather than a chain: never infer a capability from {@code atLeast}; use
 * {@link #allowsDetour()} and {@link #allowsPoi()}.</p>
 */
public enum AssistMode {
    OFF,
    SENSE,
    DETOUR,
    POI,
    ALL;

    /**
     * Parses a mode name from config or environment. Case-insensitive, surrounding whitespace
     * ignored. Null, blank and unknown values yield an empty result so the caller can fall through
     * the precedence chain (env, then file, then shipped default).
     */
    public static Optional<AssistMode> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.strip().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        for (AssistMode mode : values()) {
            if (mode.name().equals(key)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** True when this mode is at the same rollout stage as {@code other} or a later one. */
    public boolean atLeast(AssistMode other) {
        return other != null && ordinal() >= other.ordinal();
    }

    /** The sensor and shadow scoring run: every mode except OFF. */
    public boolean allowsSense() {
        return atLeast(SENSE);
    }

    /** The opportunistic detour may act: DETOUR or ALL. */
    public boolean allowsDetour() {
        return this == DETOUR || this == ALL;
    }

    /** POI detection may act (hold, stop, notify): POI or ALL. */
    public boolean allowsPoi() {
        return this == POI || this == ALL;
    }
}
