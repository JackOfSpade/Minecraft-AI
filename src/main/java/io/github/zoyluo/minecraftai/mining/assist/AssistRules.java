package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Locale;

/**
 * Small pure id rules shared by the sensing adapters (mining-assist design 3.3 and 6.2). Strings in,
 * booleans out, no Minecraft classes, so every rule is unit-testable without a booted registry.
 */
public final class AssistRules {
    private static final String VANILLA = "minecraft";

    private AssistRules() {
    }

    /**
     * Blocks the hazard field remembers as kind TRAP: every pressure plate (wood, stone, metal and
     * weighted), tripwire, tripwire hook, TNT and dispenser (design 3.3). Vanilla ids only. Note a
     * collider ray passes straight through plates and tripwire, so in practice the sensor sees TNT
     * and dispensers on first hit.
     */
    public static boolean isTrap(String namespace, String path) {
        if (!isVanilla(namespace) || path == null) {
            return false;
        }
        String p = path.trim().toLowerCase(Locale.ROOT);
        return p.endsWith("_pressure_plate")
                || p.equals("tripwire")
                || p.equals("tripwire_hook")
                || p.equals("tnt")
                || p.equals("dispenser");
    }

    /** True for a null, blank or {@code minecraft} namespace. */
    public static boolean isVanilla(String namespace) {
        return namespace == null || namespace.isBlank() || VANILLA.equals(namespace.trim().toLowerCase(Locale.ROOT));
    }

    /** The deep dark biome id ({@code minecraft:deep_dark}); the design's deep_dark_biome veto. */
    public static boolean isDeepDarkBiome(String biomeId) {
        return "minecraft:deep_dark".equals(normalize(biomeId));
    }

    /**
     * The lush caves biome id. Logs and leaves are natural terrain only inside it (the lexicon's
     * {@code naturalTag} contract, design 6.2), otherwise they are unclassified building evidence.
     */
    public static boolean isLushBiome(String biomeId) {
        return "minecraft:lush_caves".equals(normalize(biomeId));
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
