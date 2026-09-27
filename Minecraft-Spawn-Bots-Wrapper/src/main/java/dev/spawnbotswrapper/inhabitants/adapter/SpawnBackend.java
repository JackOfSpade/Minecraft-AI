package dev.spawnbotswrapper.inhabitants.adapter;

import java.util.Locale;

/**
 * Which of PvP BOT's two spawn surfaces the operator allows ({@code spawning.backend} in the addon config).
 * <ul>
 *   <li>{@link #AUTO}: the class API, and if that is unusable or throws, the {@code /pvpbot spawn} command.</li>
 *   <li>{@link #CLASS}: only the class API. Never dispatches a command.</li>
 *   <li>{@link #COMMAND}: only the {@code /pvpbot spawn} command.</li>
 * </ul>
 * Removal and re-adoption of orphaned bots are not affected by this choice beyond what each surface can
 * physically do (the command cannot adopt: upstream refuses to adopt an existing player through it).
 */
public enum SpawnBackend {
    AUTO,
    CLASS,
    COMMAND;

    /** Lenient: null, blank or unknown text means {@link #AUTO}; the config validator already warned about it. */
    public static SpawnBackend parse(String value) {
        if (value == null) {
            return AUTO;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (SpawnBackend b : values()) {
            if (b.name().equals(v)) {
                return b;
            }
        }
        return AUTO;
    }
}
