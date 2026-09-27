package dev.spawnbotswrapper.inhabitants.catalog;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;

import java.util.List;

/**
 * Renders the catalog as one GitHub-flavoured Markdown table. Every cell is escaped so free text can
 * never add a column, break a row or open an HTML tag: the output is embedded verbatim in
 * {@code docs/SETTINGS.md} and compared against it byte for byte by a test.
 */
final class SettingTable {
    static final String HEADER = "| Setting | /pvpbot settings key | Type & range | Default | Category | "
            + "Per-bot mechanism | Profile facet | Notes |";
    static final String SEPARATOR = "|---|---|---|---|---|---|---|---|";
    private static final String EMPTY = "-";

    private SettingTable() {
    }

    /** Header, separator and one row per spec, joined by {@code \n} with no trailing newline. */
    static String render(List<SettingSpec> specs) {
        StringBuilder table = new StringBuilder(HEADER).append('\n').append(SEPARATOR);
        for (SettingSpec spec : specs) {
            table.append('\n').append(row(spec));
        }
        return table.toString();
    }

    static String row(SettingSpec spec) {
        boolean perBot = spec.category() == SettingCatalog.Category.PER_BOT_RANDOMIZABLE;
        return "| " + String.join(" | ",
                "`" + escape(spec.field()) + "`",
                spec.commandKey().isEmpty() ? EMPTY : "`" + escape(spec.commandKey()) + "`",
                escape(typeAndRange(spec)),
                "`" + escape(spec.defaultValue()) + "`",
                spec.category().name(),
                perBot ? spec.mechanism().name() : EMPTY,
                cell(spec.profileFacet()),
                cell(spec.note())) + " |";
    }

    static String typeAndRange(SettingSpec spec) {
        return switch (spec.type()) {
            case BOOLEAN -> "boolean";
            case INT -> "int " + (long) spec.min() + ".." + (long) spec.max();
            case DOUBLE -> "double " + spec.min() + ".." + spec.max();
        };
    }

    private static String cell(String text) {
        return text == null || text.isBlank() ? EMPTY : escape(text);
    }

    /** Pipes would end the cell, line breaks would end the row, and a bare angle bracket may start an HTML tag. */
    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("<", "&lt;")
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
