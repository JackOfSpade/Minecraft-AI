package dev.spawnbotswrapper.inhabitants.catalog;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The in-code classification of EVERY PvP BOT setting (BotSettings field) so a future upstream release
 * can be audited mechanically: each setting is classified, and for the ones that can vary per bot the
 * mechanism the addon uses to express it is named.
 * <p>
 * The reason this exists at all: PvP BOT reads ONE process-wide settings object for every bot and the
 * addon never writes it. A setting can therefore only differ between two inhabitants if something PvP
 * BOT reads PER BOT (inventory, a vanilla attribute, its path system) switches the same behaviour, and
 * that has to be decided setting by setting, with the limits of each proxy written down. The data lives
 * in {@code SettingData}; the drift test compares it with a baseline captured from the audited release,
 * and {@link #audit(Set)} lets a running server compare it with the PvP BOT it actually loaded.
 */
public final class SettingCatalog {
    private static final String AUDITED_VERSION = "0.0.15";
    private static final List<SettingSpec> ALL = List.copyOf(SettingData.entries());
    private static final Map<String, SettingSpec> BY_FIELD = index(ALL);

    private SettingCatalog() {
    }

    /** What the addon can do with a setting. */
    public enum Category {
        /** Affects an individual bot AND has a truthful per-bot proxy; randomized per bot by the addon. */
        PER_BOT_RANDOMIZABLE,
        /** Affects bot behaviour but PvP BOT reads it from its process-wide singleton; no truthful per-bot proxy exists. NOT randomized. */
        GLOBAL_ONLY,
        /** Administrative / performance / debugging / persistence switches. Never touched. */
        ADMIN_OPERATIONAL,
        /** Cannot be handled (dead, broken or unsafe to influence). */
        UNSUPPORTED
    }

    /** How a PER_BOT_RANDOMIZABLE setting is expressed per bot. NONE for the other categories. */
    public enum Mechanism {
        NONE,
        /** By what the bot carries (PvP BOT selects behaviour from inventory contents). */
        LOADOUT,
        /** By a vanilla entity attribute PvP BOT actually reads. */
        ATTRIBUTE,
        /** By PvP BOT's per-bot path/patrol mechanism (stance, radius, walk type, attack flag). */
        PATH,
        /** By initial vitals (health / hunger). */
        VITALS
    }

    /** Value type of the upstream field. */
    public enum ValueType { BOOLEAN, INT, DOUBLE }

    /**
     * @param field         upstream Java field name (e.g. {@code moveSpeed})
     * @param commandKey    the {@code /pvpbot settings <key>} literal, or empty if it has none
     * @param type          value type
     * @param min           minimum valid value (NaN for booleans)
     * @param max           maximum valid value (NaN for booleans)
     * @param defaultValue  upstream default, as text
     * @param category      classification
     * @param mechanism     per-bot mechanism (NONE unless PER_BOT_RANDOMIZABLE)
     * @param profileFacet  which profile field(s) express it, empty if none
     * @param note          why it is classified this way / the exact semantics of the proxy (for the README table)
     */
    public record SettingSpec(String field, String commandKey, ValueType type, double min, double max,
                              String defaultValue, Category category, Mechanism mechanism,
                              String profileFacet, String note) {
    }

    /** Result of comparing the catalog with the setting names a running PvP BOT reports. */
    public record AuditReport(List<String> unknownToCatalog, List<String> missingUpstream) {
        public boolean clean() {
            return unknownToCatalog.isEmpty() && missingUpstream.isEmpty();
        }
    }

    /** Every setting of the audited upstream version, in upstream field order. */
    public static List<SettingSpec> all() {
        return ALL;
    }

    /** Exact, case-sensitive lookup by upstream field name; empty for null or unknown names. */
    public static Optional<SettingSpec> find(String field) {
        return field == null ? Optional.empty() : Optional.ofNullable(BY_FIELD.get(field));
    }

    /** The settings of one category, still in upstream field order. */
    public static List<SettingSpec> byCategory(Category category) {
        Objects.requireNonNull(category, "category");
        return ALL.stream().filter(spec -> spec.category() == category).toList();
    }

    /** The upstream version this catalog was audited against, e.g. {@code 0.0.15}. */
    public static String auditedVersion() {
        return AUDITED_VERSION;
    }

    /**
     * Compares against the field names reported by a running PvP BOT
     * ({@code PvpBotOperations#discoverUpstreamSettingNames}). Comparison is exact and case-sensitive; null
     * and blank names are ignored. Both result lists are sorted. An empty input reports every setting as
     * missing, so callers should skip the audit when the discovery itself failed instead of reading that
     * as a real upstream change.
     */
    public static AuditReport audit(Set<String> upstreamFieldNames) {
        Objects.requireNonNull(upstreamFieldNames, "upstreamFieldNames");
        Set<String> unknown = new TreeSet<>();
        Set<String> present = new HashSet<>();
        for (String name : upstreamFieldNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (BY_FIELD.containsKey(name)) {
                present.add(name);
            } else {
                unknown.add(name);
            }
        }
        Set<String> missing = new TreeSet<>();
        for (SettingSpec spec : ALL) {
            if (!present.contains(spec.field())) {
                missing.add(spec.field());
            }
        }
        return new AuditReport(List.copyOf(unknown), List.copyOf(missing));
    }

    /** The README/SETTINGS.md table, one row per setting, Markdown. Cells are escaped; no trailing newline. */
    public static String markdownTable() {
        return SettingTable.render(ALL);
    }

    private static Map<String, SettingSpec> index(List<SettingSpec> specs) {
        Map<String, SettingSpec> byField = new LinkedHashMap<>();
        for (SettingSpec spec : specs) {
            if (byField.put(spec.field(), spec) != null) {
                throw new IllegalStateException("duplicate setting in catalog: " + spec.field());
            }
        }
        return Collections.unmodifiableMap(byField);
    }
}
