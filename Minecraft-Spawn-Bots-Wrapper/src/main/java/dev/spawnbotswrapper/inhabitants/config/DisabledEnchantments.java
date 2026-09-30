package dev.spawnbotswrapper.inhabitants.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code profiles.disabledEnchantments} denylist: enchantments no inhabitant may be given or keep.
 * <p>
 * Ids are accepted with or without the {@code minecraft:} namespace and in any letter case; everything downstream
 * (the loadout roller, the profile applier, the sweeps) works with the canonical lower-case
 * {@code namespace:path} form produced here. The default is Piercing: a piercing crossbow bolt ignores a raised
 * shield in vanilla Java (it skips shield blocking for arrows with a pierce level above zero), so a player could
 * not block a hostile inhabitant's crossbow at all, which is too strong for something that is supposed to be a
 * beatable structure guardian. An empty list turns the feature off.
 */
public final class DisabledEnchantments {
    /** What a config without the key gets. */
    public static final List<String> DEFAULT = List.of("minecraft:piercing");

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    /** Every enchantment of Minecraft 1.21.11 (a typo in the minecraft namespace is worth a warning at load). */
    private static final Set<String> VANILLA = Set.of(
            "protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection",
            "respiration", "aqua_affinity", "thorns", "depth_strider", "frost_walker", "binding_curse", "soul_speed",
            "swift_sneak", "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting",
            "sweeping_edge", "efficiency", "silk_touch", "unbreaking", "fortune", "power", "punch", "flame",
            "infinity", "luck_of_the_sea", "lure", "loyalty", "impaling", "riptide", "channeling", "multishot",
            "quick_charge", "piercing", "density", "breach", "wind_burst", "mending", "vanishing_curse", "lunge");

    private DisabledEnchantments() {
    }

    /** The canonical {@code namespace:path} id for {@code raw}, or null when it is not a valid identifier. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        int colon = s.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : s.substring(0, colon);
        String path = colon < 0 ? s : s.substring(colon + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            return null;
        }
        return namespace + ":" + path;
    }

    /**
     * The canonical ids of a configured list, silently skipping anything invalid (used at run time on a list that
     * {@link #clean} normally already repaired). Never null; a null list is the empty set.
     */
    public static Set<String> parse(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw) {
                String id = normalize(s);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /**
     * Repairs a configured list at load time: canonical ids, no duplicates, one warning per entry that is not a
     * valid id or names a {@code minecraft:} enchantment that does not exist (that entry is ignored). Other
     * namespaces cannot be checked without the game's registries and are kept as they are.
     */
    public static List<String> clean(List<String> raw, String where, List<String> warnings) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw) {
                String id = normalize(s);
                if (id == null) {
                    warnings.add(where + ": '" + s + "' is not a valid enchantment id; ignored");
                } else if (id.startsWith("minecraft:") && !VANILLA.contains(id.substring("minecraft:".length()))) {
                    warnings.add(where + ": '" + s + "' is not a Minecraft enchantment; ignored");
                } else {
                    out.add(id);
                }
            }
        }
        return new ArrayList<>(out);
    }
}
