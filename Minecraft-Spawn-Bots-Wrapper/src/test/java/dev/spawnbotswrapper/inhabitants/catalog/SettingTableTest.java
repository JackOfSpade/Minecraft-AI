package dev.spawnbotswrapper.inhabitants.catalog;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingTableTest {

    private static final String UNESCAPED_PIPE = "(?<!\\\\)\\|";

    private static SettingSpec spec(ValueType type, double min, double max, String defaultValue, Category category,
                                    Mechanism mechanism, String facet, String note) {
        return new SettingSpec("someField", "some-key", type, min, max, defaultValue, category, mechanism, facet, note);
    }

    private static String[] cells(String row) {
        String[] parts = row.split(UNESCAPED_PIPE, -1);
        assertEquals("", parts[0], "row starts with a pipe: " + row);
        assertEquals("", parts[parts.length - 1], "row ends with a pipe: " + row);
        String[] inner = new String[parts.length - 2];
        System.arraycopy(parts, 1, inner, 0, inner.length);
        return inner;
    }

    @Test
    void aPipeInFreeTextNeitherAddsAColumnNorLeaksUnescaped() {
        SettingSpec evil = spec(ValueType.BOOLEAN, Double.NaN, Double.NaN, "true", Category.PER_BOT_RANDOMIZABLE,
                Mechanism.LOADOUT, "loadout: a | b", "first | second || third");

        String row = SettingTable.row(evil);

        assertEquals(8, cells(row).length);
        assertTrue(row.contains("first \\| second \\|\\| third"));
        assertTrue(row.contains("loadout: a \\| b"));
    }

    @Test
    void lineBreaksInFreeTextCannotSplitARow() {
        SettingSpec multiLine = spec(ValueType.INT, 1, 5, "2", Category.GLOBAL_ONLY, Mechanism.NONE, "",
                "line one\nline two\r\nline three\rend");

        String table = SettingTable.render(List.of(multiLine));

        assertEquals(3, table.split("\n", -1).length, "header, separator and exactly one row");
        assertFalse(table.contains("\r"));
        assertTrue(table.contains("line one line two line three end"));
    }

    @Test
    void anAngleBracketCannotOpenAnHtmlTag() {
        SettingSpec tag = spec(ValueType.BOOLEAN, Double.NaN, Double.NaN, "false", Category.ADMIN_OPERATIONAL,
                Mechanism.NONE, "", "use <script>alert(1)</script> here");

        String row = SettingTable.row(tag);

        assertFalse(row.contains("<"));
        assertTrue(row.contains("&lt;script>"));
    }

    @Test
    void aBackslashIsEscapedSoItCannotEscapeTheNextCharacter() {
        SettingSpec slash = spec(ValueType.BOOLEAN, Double.NaN, Double.NaN, "true", Category.GLOBAL_ONLY,
                Mechanism.NONE, "", "ends with a backslash\\ | then a pipe");

        String row = SettingTable.row(slash);

        assertEquals(8, cells(row).length);
        assertTrue(row.contains("backslash\\\\ \\| then"));
    }

    @Test
    void emptyFacetAndMechanismOfANonRandomizedSettingRenderAsADash() {
        SettingSpec global = spec(ValueType.DOUBLE, 0.1, 2.0, "1.0", Category.GLOBAL_ONLY, Mechanism.NONE, "", "why");

        String[] cells = cells(SettingTable.row(global));

        assertEquals(" `someField` ", cells[0]);
        assertEquals(" `some-key` ", cells[1]);
        assertEquals(" double 0.1..2.0 ", cells[2]);
        assertEquals(" `1.0` ", cells[3]);
        assertEquals(" GLOBAL_ONLY ", cells[4]);
        assertEquals(" - ", cells[5]);
        assertEquals(" - ", cells[6]);
        assertEquals(" why ", cells[7]);
    }

    @Test
    void aSettingWithoutACommandKeyRendersADashInsteadOfEmptyBackticks() {
        SettingSpec noKey = new SettingSpec("maceRange", "", ValueType.DOUBLE, 3.0, 10.0, "6.0",
                Category.GLOBAL_ONLY, Mechanism.NONE, "", "note");

        assertEquals(" - ", cells(SettingTable.row(noKey))[1]);
    }

    @Test
    void aPerBotSettingShowsItsMechanismAndFacet() {
        SettingSpec perBot = spec(ValueType.BOOLEAN, Double.NaN, Double.NaN, "true", Category.PER_BOT_RANDOMIZABLE,
                Mechanism.PATH, "behavior.walkType", "note");

        String[] cells = cells(SettingTable.row(perBot));

        assertEquals(" boolean ", cells[2]);
        assertEquals(" PATH ", cells[5]);
        assertEquals(" behavior.walkType ", cells[6]);
    }

    @Test
    void integerRangesAreShownWithoutADecimalPoint() {
        SettingSpec whole = spec(ValueType.INT, 50, 10000, "1000", Category.ADMIN_OPERATIONAL, Mechanism.NONE, "", "n");

        assertEquals("int 50..10000", SettingTable.typeAndRange(whole));
    }

    @Test
    void renderStartsWithTheDocumentedColumnsAndHasNoTrailingNewline() {
        String table = SettingTable.render(List.of());

        assertEquals(SettingTable.HEADER + "\n" + SettingTable.SEPARATOR, table);
        assertEquals(8, cells(SettingTable.HEADER).length);
        assertEquals(List.of(" Setting ", " /pvpbot settings key ", " Type & range ", " Default ", " Category ",
                " Per-bot mechanism ", " Profile facet ", " Notes "), List.of(cells(SettingTable.HEADER)));
        assertFalse(table.endsWith("\n"));
    }
}
