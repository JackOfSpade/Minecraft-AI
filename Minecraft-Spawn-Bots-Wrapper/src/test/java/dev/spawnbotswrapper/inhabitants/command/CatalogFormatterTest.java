package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.AuditReport;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.spawnbotswrapper.inhabitants.command.Fixtures.containsStripped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogFormatterTest {

    private static SettingSpec perBot(String field, Mechanism m, String facet) {
        return new SettingSpec(field, field.toLowerCase(), ValueType.DOUBLE, 0.1, 2.0, "1.0",
                Category.PER_BOT_RANDOMIZABLE, m, facet, "note");
    }

    private static SettingSpec other(String field, Category c, ValueType t) {
        return new SettingSpec(field, field.toLowerCase(), t, t == ValueType.BOOLEAN ? Double.NaN : 1,
                t == ValueType.BOOLEAN ? Double.NaN : 100, t == ValueType.BOOLEAN ? "true" : "20", c, Mechanism.NONE, "", "note");
    }

    private static List<SettingSpec> sample() {
        return List.of(
                perBot("moveSpeed", Mechanism.ATTRIBUTE, "vitals.attributes"),
                perBot("attackSpeed", Mechanism.ATTRIBUTE, "vitals.attributes"),
                perBot("autoTotem", Mechanism.LOADOUT, "loadout"),
                perBot("patrolRadius", Mechanism.PATH, "behavior.patrolRadius"),
                other("combat", Category.GLOBAL_ONLY, ValueType.BOOLEAN),
                other("saveInterval", Category.ADMIN_OPERATIONAL, ValueType.INT));
    }


    // ---------------------------------------------------------------- summary

    @Test
    void summaryCountsEachCategoryAndBreaksDownTheMechanisms() {
        List<String> t = Markup.strip(CatalogFormatter.summary("0.0.15", sample(), "0.0.15",
                new AuditReport(List.of(), List.of()), null));

        assertEquals("Setting catalog: 6 PvP BOT settings, audited against PvP BOT 0.0.15", t.get(0));
        assertTrue(containsStripped(t, "  PER_BOT_RANDOMIZABLE: 4  randomized per bot  (LOADOUT 1, ATTRIBUTE 2, PATH 1)"), t.toString());
        assertTrue(containsStripped(t, "  GLOBAL_ONLY: 1  global in PvP BOT, not randomized"), t.toString());
        assertTrue(containsStripped(t, "  ADMIN_OPERATIONAL: 1  admin / performance / debug, never touched"), t.toString());
        assertTrue(containsStripped(t, "  UNSUPPORTED: 0  cannot be handled"), t.toString());
        assertTrue(containsStripped(t, "List one category: /inhabitants catalog <per_bot_randomizable|global_only|admin_operational|unsupported>"),
                t.toString());
    }

    @Test
    void aCleanAuditIsGreen() {
        List<String> raw = CatalogFormatter.summary("0.0.15", sample(), "0.0.15", new AuditReport(List.of(), List.of()), null);
        assertTrue(containsStripped(raw, "Audit against the running PvP BOT 0.0.15: clean - every upstream setting is classified (6)"),
                Markup.strip(raw).toString());
        assertTrue(raw.stream().anyMatch(l -> l.contains("§aclean")));
    }

    @Test
    void driftListsUnknownAndMissingSettings() {
        AuditReport drift = new AuditReport(List.of("newFeature", "otherNew"), List.of("removedThing"));
        List<String> raw = CatalogFormatter.summary("0.0.15", sample(), "0.0.16", drift, null);
        List<String> t = Markup.strip(raw);

        assertTrue(containsStripped(t, "Audit against the running PvP BOT 0.0.16: DRIFT"), t.toString());
        assertTrue(containsStripped(t, "  2 settings unknown to the catalog (new upstream? they are not randomized): newFeature, otherNew"),
                t.toString());
        assertTrue(containsStripped(t, "  1 catalog setting missing upstream (renamed or removed?): removedThing"), t.toString());
        assertTrue(raw.stream().anyMatch(l -> l.contains("§cDRIFT")));
    }

    @Test
    void aHugeDriftListIsCapped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add("s" + i);
        }
        List<String> t = Markup.strip(CatalogFormatter.summary("0.0.15", sample(), "0.0.20",
                new AuditReport(many, List.of()), null));
        String line = t.stream().filter(l -> l.contains("unknown to the catalog")).findFirst().orElseThrow();
        assertTrue(line.contains("s11") && !line.contains("s12,") && line.endsWith(", ... +88"), line);
    }

    @Test
    void aSkippedAuditSaysWhy() {
        List<String> t = Markup.strip(CatalogFormatter.summary("0.0.15", sample(), null, null, "PvP BOT is unavailable"));
        assertTrue(containsStripped(t, "Audit: skipped - PvP BOT is unavailable"), t.toString());
        assertFalse(containsStripped(t, "DRIFT"), t.toString());

        List<String> generic = Markup.strip(CatalogFormatter.summary("0.0.15", sample(), null, null, null));
        assertTrue(containsStripped(generic, "Audit: skipped - PvP BOT's settings could not be read"), generic.toString());
    }

    @Test
    void anEmptyCatalogStillProducesAReadableSummary() {
        List<String> t = Markup.strip(CatalogFormatter.summary(null, List.of(), null, null, "no data"));
        assertEquals("Setting catalog: 0 PvP BOT settings, audited against PvP BOT -", t.get(0));
        assertTrue(containsStripped(t, "  PER_BOT_RANDOMIZABLE: 0  randomized per bot"), t.toString());
    }

    // ---------------------------------------------------------------- category

    @Test
    void aCategoryListsItsSettingsWithMechanismRangeDefaultAndFacet() {
        List<String> t = Markup.strip(CatalogFormatter.category(Category.PER_BOT_RANDOMIZABLE, sample()));

        assertEquals("PER_BOT_RANDOMIZABLE: 4 settings  randomized per bot", t.get(0));
        assertEquals("  moveSpeed [ATTRIBUTE]  double 0.1..2, default 1.0  -> vitals.attributes", t.get(1));
        assertTrue(containsStripped(t, "  autoTotem [LOADOUT]"), t.toString());
        assertTrue(containsStripped(t, "  patrolRadius [PATH]  double 0.1..2, default 1.0  -> behavior.patrolRadius"), t.toString());
        assertEquals(5, t.size());
    }

    @Test
    void nonPerBotCategoriesShowNoMechanismTag() {
        List<String> global = Markup.strip(CatalogFormatter.category(Category.GLOBAL_ONLY, sample()));
        assertEquals("GLOBAL_ONLY: 1 setting  global in PvP BOT, not randomized", global.get(0));
        assertEquals("  combat  boolean, default true", global.get(1));

        List<String> admin = Markup.strip(CatalogFormatter.category(Category.ADMIN_OPERATIONAL, sample()));
        assertEquals("  saveInterval  int 1..100, default 20", admin.get(1));
    }

    @Test
    void anEmptyCategoryIsStated() {
        List<String> t = Markup.strip(CatalogFormatter.category(Category.UNSUPPORTED, sample()));
        assertEquals("UNSUPPORTED: 0 settings  cannot be handled", t.get(0));
        assertEquals("  (none)", t.get(1));
    }

    @Test
    void aBigCategoryIsCapped() {
        List<SettingSpec> big = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            big.add(other("setting" + i, Category.GLOBAL_ONLY, ValueType.INT));
        }
        List<String> t = Markup.strip(CatalogFormatter.category(Category.GLOBAL_ONLY, big));
        assertEquals("GLOBAL_ONLY: 100 settings  global in PvP BOT, not randomized", t.get(0));
        assertEquals(1 + 40 + 1, t.size());
        assertEquals("... and 60 more", t.get(41));
    }

    @Test
    void aSpecWithMissingOptionalFieldsStillRenders() {
        SettingSpec sparse = new SettingSpec("odd", null, null, 0, 0, null, Category.PER_BOT_RANDOMIZABLE, null, null, null);
        List<String> t = Markup.strip(CatalogFormatter.category(Category.PER_BOT_RANDOMIZABLE, List.of(sparse)));
        assertEquals("  odd  ? 0..0, default -", t.get(1));
        List<String> summary = Markup.strip(CatalogFormatter.summary("0.0.15", List.of(sparse), "0.0.15", null, null));
        assertTrue(summary.contains("  PER_BOT_RANDOMIZABLE: 1  randomized per bot"), summary.toString());
    }

    @Test
    void valueTextHandlesTypesRangesAndMissingData() {
        assertEquals("boolean, default true", CatalogFormatter.valueText(other("a", Category.GLOBAL_ONLY, ValueType.BOOLEAN)));
        assertEquals("int 1..100, default 20", CatalogFormatter.valueText(other("a", Category.GLOBAL_ONLY, ValueType.INT)));
        SettingSpec noDefault = new SettingSpec("x", "", ValueType.DOUBLE, Double.NaN, 5.0, null,
                Category.UNSUPPORTED, Mechanism.NONE, "", "");
        assertEquals("double, default -", CatalogFormatter.valueText(noDefault));
    }

    @Test
    void compactPrintsNumbersWithoutNoise() {
        assertEquals("2", CatalogFormatter.compact(2.0));
        assertEquals("0.1", CatalogFormatter.compact(0.10));
        assertEquals("0.0001", CatalogFormatter.compact(1e-4));
        assertEquals("1000000", CatalogFormatter.compact(1e6));
        assertEquals("-5", CatalogFormatter.compact(-5));
        assertEquals("NaN", CatalogFormatter.compact(Double.NaN));
    }

    @Test
    void categoryWordsMatchTheLowerCaseEnumNames() {
        assertEquals("per_bot_randomizable|global_only|admin_operational|unsupported", CatalogFormatter.categoryNames());
    }
}
