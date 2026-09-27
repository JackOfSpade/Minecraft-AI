package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.AuditReport;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.command.Markup.bad;
import static dev.spawnbotswrapper.inhabitants.command.Markup.good;
import static dev.spawnbotswrapper.inhabitants.command.Markup.id;
import static dev.spawnbotswrapper.inhabitants.command.Markup.label;
import static dev.spawnbotswrapper.inhabitants.command.Markup.plain;
import static dev.spawnbotswrapper.inhabitants.command.Markup.title;
import static dev.spawnbotswrapper.inhabitants.command.Markup.warn;

/**
 * Output of {@code /inhabitants catalog [category]}: how the addon classifies every PvP BOT setting, and
 * whether a running PvP BOT still matches the catalog it was audited against. Pure: it takes the catalog
 * contents as plain data so it neither needs the catalog implementation nor a running PvP BOT.
 */
final class CatalogFormatter {
    /** Names of drifted settings listed in the summary; the rest is counted. */
    static final int MAX_DRIFT_NAMES = 12;
    /** Settings listed for one category (the largest category holds a few dozen). */
    static final int MAX_CATEGORY_LINES = 40;

    private CatalogFormatter() {
    }

    /**
     * @param liveVersion   version of the running PvP BOT, for the audit line
     * @param audit         the drift report, or null when no audit was possible
     * @param skippedReason why {@code audit} is null (shown instead of a result); ignored otherwise
     */
    static List<String> summary(String auditedVersion, List<SettingSpec> all, String liveVersion,
                                AuditReport audit, String skippedReason) {
        List<String> out = new ArrayList<>();
        out.add(title("Setting catalog: ") + plain(Fmt.plural(all.size(), "PvP BOT setting"))
                + label(", audited against PvP BOT ") + plain(Fmt.orDash(auditedVersion)));

        Map<Category, List<SettingSpec>> byCategory = group(all);
        for (Category c : Category.values()) {
            List<SettingSpec> list = byCategory.get(c);
            int n = list == null ? 0 : list.size();
            String counts = c == Category.PER_BOT_RANDOMIZABLE && n > 0 ? mechanismCounts(list) : "";
            String detail = counts.isEmpty() ? "" : label("  " + counts);
            out.add(label("  ") + plain(c.name() + ": " + n) + label("  " + describe(c)) + detail);
        }

        if (audit != null) {
            auditLines(out, audit, all.size(), liveVersion);
        } else {
            out.add(label("Audit: ") + warn("skipped") + label(" - " + Markup.esc(
                    skippedReason == null || skippedReason.isBlank() ? "PvP BOT's settings could not be read" : skippedReason)));
        }
        out.add(label("List one category: /inhabitants catalog <" + categoryNames() + ">"));
        return out;
    }

    private static void auditLines(List<String> out, AuditReport audit, int catalogSize, String liveVersion) {
        String against = label("Audit against the running PvP BOT " + Markup.esc(Fmt.orDash(liveVersion)) + ": ");
        if (audit.clean()) {
            out.add(against + good("clean") + label(" - every upstream setting is classified (" + catalogSize + ")"));
            return;
        }
        out.add(against + bad("DRIFT"));
        if (!audit.unknownToCatalog().isEmpty()) {
            out.add(warn("  " + Fmt.plural(audit.unknownToCatalog().size(), "setting") + " unknown to the catalog")
                    + label(" (new upstream? they are not randomized): ") + plain(names(audit.unknownToCatalog())));
        }
        if (!audit.missingUpstream().isEmpty()) {
            out.add(warn("  " + Fmt.plural(audit.missingUpstream().size(), "catalog setting") + " missing upstream")
                    + label(" (renamed or removed?): ") + plain(names(audit.missingUpstream())));
        }
    }

    /** One category's settings, with the mechanism that expresses each per-bot one. */
    static List<String> category(Category c, List<SettingSpec> all) {
        List<String> out = new ArrayList<>();
        List<SettingSpec> list = group(all).getOrDefault(c, List.of());
        out.add(title(c.name() + ": ") + plain(Fmt.plural(list.size(), "setting")) + label("  " + describe(c)));
        if (list.isEmpty()) {
            out.add(label("  (none)"));
            return out;
        }
        List<String> lines = new ArrayList<>();
        for (SettingSpec s : list) {
            lines.add(settingLine(s));
        }
        Fmt.addCapped(out, lines, MAX_CATEGORY_LINES, "");
        return out;
    }

    private static String settingLine(SettingSpec s) {
        StringBuilder sb = new StringBuilder(label("  ")).append(id(s.field()));
        if (s.category() == Category.PER_BOT_RANDOMIZABLE && s.mechanism() != null && s.mechanism() != Mechanism.NONE) {
            sb.append(' ').append(good("[" + s.mechanism().name() + "]"));
        }
        sb.append(label("  " + valueText(s)));
        if (s.profileFacet() != null && !s.profileFacet().isBlank()) {
            sb.append(label("  -> ")).append(plain(s.profileFacet()));
        }
        return sb.toString();
    }

    static String valueText(SettingSpec s) {
        String range = "";
        if (s.type() != ValueType.BOOLEAN && !Double.isNaN(s.min()) && !Double.isNaN(s.max())) {
            range = " " + compact(s.min()) + ".." + compact(s.max());
        }
        String type = s.type() == null ? "?" : s.type().name().toLowerCase(Locale.ROOT);
        return type + range + ", default " + Fmt.orDash(s.defaultValue());
    }

    /** {@code 2.0 -> "2"}, {@code 0.10 -> "0.1"}, {@code 1e-4 -> "0.0001"}. */
    static String compact(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return String.valueOf(d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static Map<Category, List<SettingSpec>> group(List<SettingSpec> all) {
        Map<Category, List<SettingSpec>> m = new EnumMap<>(Category.class);
        for (SettingSpec s : all) {
            if (s.category() != null) {
                m.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
            }
        }
        return m;
    }

    private static String mechanismCounts(List<SettingSpec> perBot) {
        Map<Mechanism, Integer> counts = new EnumMap<>(Mechanism.class);
        for (SettingSpec s : perBot) {
            if (s.mechanism() != null && s.mechanism() != Mechanism.NONE) {
                counts.merge(s.mechanism(), 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((m, n) -> parts.add(m.name() + " " + n));
        return parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
    }

    static String describe(Category c) {
        return switch (c) {
            case PER_BOT_RANDOMIZABLE -> "randomized per bot";
            case GLOBAL_ONLY -> "global in PvP BOT, not randomized";
            case ADMIN_OPERATIONAL -> "admin / performance / debug, never touched";
            case UNSUPPORTED -> "cannot be handled";
        };
    }

    /** The category words offered by tab completion, lower-case. */
    static String categoryNames() {
        List<String> names = new ArrayList<>();
        for (Category c : Category.values()) {
            names.add(c.name().toLowerCase(Locale.ROOT));
        }
        return String.join("|", names);
    }

    private static String names(List<String> names) {
        List<String> shown = names.size() <= MAX_DRIFT_NAMES ? names : names.subList(0, MAX_DRIFT_NAMES);
        return String.join(", ", shown) + (names.size() > shown.size() ? ", ... +" + (names.size() - shown.size()) : "");
    }
}
