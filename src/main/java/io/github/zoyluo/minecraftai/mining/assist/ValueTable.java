package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Locale;
import java.util.Map;

/**
 * Raw value of a block as a mining target (mining-assist design 4.2). Pure: registry ids in, an int
 * out. The table is keyed by registry path and ignores the namespace on purpose, so a modded
 * {@code diamond_ore} is worth what a vanilla one is; a modded or unknown {@code *_ore} that is not in
 * the table is worth {@link #UNKNOWN_ORE_VALUE}. A {@code deepslate_} prefix is stripped before the
 * lookup, so {@code deepslate_diamond_ore} equals {@code diamond_ore}.
 *
 * <p>The sensor records every block with a value above zero in the sighting ledger; whether a value
 * justifies a walk (the design's {@code detour.minValue}) is decided later by the detour policy, not
 * here. This class is the shared source of the numbers, the phase that adds the policy reuses it.</p>
 */
public final class ValueTable {
    /** Design 4.2: any {@code *_ore} the table does not name (modded ores). */
    public static final int UNKNOWN_ORE_VALUE = 25;

    private static final String DEEPSLATE_PREFIX = "deepslate_";

    private static final Map<String, Integer> VALUES = Map.ofEntries(
            Map.entry("ancient_debris", 100),
            Map.entry("diamond_ore", 100),
            Map.entry("emerald_ore", 90),
            Map.entry("gold_ore", 45),
            Map.entry("raw_gold_block", 40),
            Map.entry("lapis_ore", 35),
            Map.entry("redstone_ore", 30),
            Map.entry("iron_ore", 30),
            Map.entry("raw_iron_block", 30),
            Map.entry("gilded_blackstone", 30),
            Map.entry("nether_gold_ore", 25),
            Map.entry("copper_ore", 15),
            Map.entry("raw_copper_block", 15),
            Map.entry("coal_ore", 12),
            Map.entry("nether_quartz_ore", 8),
            Map.entry("amethyst_cluster", 6));

    private ValueTable() {
    }

    /**
     * Value of a registry id, 0 when the block is not a valuable. {@code namespace} is accepted for
     * symmetry with the other lexicon calls and is ignored (see the class comment); a path that
     * carries a {@code ns:} prefix is split first. Never throws.
     */
    public static int valueOf(String namespace, String path) {
        if (path == null) {
            return 0;
        }
        String p = path.trim().toLowerCase(Locale.ROOT);
        int colon = p.indexOf(':');
        if (colon >= 0) {
            p = p.substring(colon + 1);
        }
        if (p.isEmpty()) {
            return 0;
        }
        String key = p.startsWith(DEEPSLATE_PREFIX) ? p.substring(DEEPSLATE_PREFIX.length()) : p;
        Integer value = VALUES.get(key);
        if (value != null) {
            return value;
        }
        return p.endsWith("_ore") ? UNKNOWN_ORE_VALUE : 0;
    }

    public static int valueOf(String path) {
        return valueOf(null, path);
    }
}
