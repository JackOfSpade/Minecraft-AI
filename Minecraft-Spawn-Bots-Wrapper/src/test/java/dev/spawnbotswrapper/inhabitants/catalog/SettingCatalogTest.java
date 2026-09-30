package dev.spawnbotswrapper.inhabitants.catalog;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.AuditReport;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingCatalogTest {

    private static SettingSpec spec(String field) {
        return SettingCatalog.find(field).orElseThrow(() -> new AssertionError("not in catalog: " + field));
    }

    private static Set<String> baselineNames() {
        return new LinkedHashSet<>(UpstreamBaseline.fields());
    }

    private static Set<String> fieldsIn(Category category) {
        Set<String> fields = new TreeSet<>();
        SettingCatalog.byCategory(category).forEach(spec -> fields.add(spec.field()));
        return fields;
    }

    // ------------------------------------------------------------------ baseline coverage

    @Test
    void coversExactlyTheBaselineFieldsInUpstreamOrder() {
        List<String> baseline = UpstreamBaseline.fields();
        List<String> catalog = SettingCatalog.all().stream().map(SettingSpec::field).toList();

        assertEquals(68, baseline.size(), "the audited release has 68 settings");
        assertEquals(baseline, catalog, "same fields, none missing, none extra, same order");
        assertEquals(baseline.size(), new HashSet<>(catalog).size(), "fields are unique");
    }

    @Test
    void typesRangesDefaultsAndCommandKeysMatchTheBaseline() {
        for (UpstreamBaseline.Row row : UpstreamBaseline.load()) {
            SettingSpec spec = spec(row.field());
            assertEquals(row.type(), spec.type(), row.field() + " type");
            assertEquals(row.min(), spec.min(), 0.0, row.field() + " min");
            assertEquals(row.max(), spec.max(), 0.0, row.field() + " max");
            assertEquals(row.defaultValue(), spec.defaultValue(), row.field() + " default");
            assertEquals(row.commandKey(), spec.commandKey(), row.field() + " command key");
        }
    }

    @Test
    void baselineMatchesTheKnownShapeOfTheAuditedRelease() {
        List<UpstreamBaseline.Row> rows = UpstreamBaseline.load();
        Set<String> withoutKey = new TreeSet<>();
        for (UpstreamBaseline.Row row : rows) {
            if (row.commandKey().isEmpty()) {
                withoutKey.add(row.field());
            }
        }

        assertEquals(54, rows.size() - withoutKey.size(), "54 settings have a /pvpbot settings key");
        assertEquals(new TreeSet<>(List.of("maceRange", "spearEnabled", "crystalPvpEnabled", "anchorPvpEnabled",
                "spearRange", "spearChargeRange", "mendDurabilityThreshold", "shieldHealthThreshold",
                "minHungerToEat", "cobwebEnabled", "retreatHealthPercent", "criticalHealthPercent",
                "factionsEnabled", "botsRelogs")), withoutKey);
    }

    @Test
    void auditedVersionNamesTheBaselineItWasCheckedAgainst() {
        assertEquals("0.0.15", SettingCatalog.auditedVersion());
        assertNotNull(SettingCatalogTest.class.getResource("/" + UpstreamBaseline.RESOURCE));
    }

    // ------------------------------------------------------------------ mechanical field data

    @Test
    void valuesAreConsistentWithTheirType() {
        for (SettingSpec spec : SettingCatalog.all()) {
            String where = spec.field();
            switch (spec.type()) {
                case BOOLEAN -> {
                    assertTrue(Double.isNaN(spec.min()) && Double.isNaN(spec.max()), where + " booleans have no range");
                    assertTrue(spec.defaultValue().equals("true") || spec.defaultValue().equals("false"), where);
                }
                case INT -> {
                    assertTrue(spec.min() < spec.max(), where + " range");
                    assertEquals(Math.rint(spec.min()), spec.min(), where + " integral min");
                    assertEquals(Math.rint(spec.max()), spec.max(), where + " integral max");
                    int value = Integer.parseInt(spec.defaultValue());
                    assertTrue(value >= spec.min() && value <= spec.max(), where + " default inside range");
                }
                case DOUBLE -> {
                    assertTrue(spec.min() < spec.max(), where + " range");
                    double value = Double.parseDouble(spec.defaultValue());
                    assertTrue(value >= spec.min() && value <= spec.max(), where + " default inside range");
                }
            }
        }
    }

    @Test
    void commandKeysAreUniqueLowerKebabCase() {
        Set<String> seen = new HashSet<>();
        for (SettingSpec spec : SettingCatalog.all()) {
            if (spec.commandKey().isEmpty()) {
                continue;
            }
            assertTrue(spec.commandKey().matches("[a-z]+(-[a-z]+)*"), spec.field() + ": " + spec.commandKey());
            assertTrue(seen.add(spec.commandKey()), "duplicate command key " + spec.commandKey());
        }
        assertEquals(54, seen.size());
    }

    @Test
    void healthAndDurabilityThresholdsAreFractionsNotPercents() {
        for (String field : List.of("mendDurabilityThreshold", "shieldHealthThreshold", "retreatHealthPercent",
                "criticalHealthPercent")) {
            assertTrue(spec(field).max() <= 1.0, field + " is a 0..1 fraction upstream despite its name");
        }
        for (String field : List.of("missChance", "mistakeChance", "shieldBreakChance")) {
            assertEquals(100.0, spec(field).max(), 0.0, field + " is an integer percent");
        }
    }

    // ------------------------------------------------------------------ classification invariants

    @Test
    void everySettingIsExplainedInPlainAscii() {
        for (SettingSpec spec : SettingCatalog.all()) {
            assertFalse(spec.note().isBlank(), spec.field() + " needs a note");
            List<String> texts = List.of(spec.field(), spec.commandKey(), spec.defaultValue(),
                    spec.profileFacet(), spec.note());
            for (String text : texts) {
                assertTrue(text.chars().allMatch(c -> c >= 0x20 && c < 0x7F), spec.field() + " has non-ASCII text");
                assertTrue(text.chars().noneMatch(c -> c == '|' || c == '<' || c == '>'),
                        spec.field() + " has a character that is unsafe in a Markdown table");
            }
        }
    }

    @Test
    void mechanismAndFacetExistExactlyForPerBotSettings() {
        for (SettingSpec spec : SettingCatalog.all()) {
            if (spec.category() == Category.PER_BOT_RANDOMIZABLE) {
                assertNotEquals(Mechanism.NONE, spec.mechanism(), spec.field() + " needs a mechanism");
                assertFalse(spec.profileFacet().isBlank(), spec.field() + " needs a profile facet");
            } else {
                assertEquals(Mechanism.NONE, spec.mechanism(), spec.field() + " is not randomized");
                assertEquals("", spec.profileFacet(), spec.field() + " has no profile facet");
            }
        }
    }

    @Test
    void everyPerBotNoteStatesWhatVariesAndWhatDoesNot() {
        for (SettingSpec spec : SettingCatalog.byCategory(Category.PER_BOT_RANDOMIZABLE)) {
            assertTrue(spec.note().contains("Varies:"), spec.field() + " must say what varies");
            assertTrue(spec.note().contains("Does not vary"), spec.field() + " must say what does not vary");
        }
    }

    @Test
    void everyPerBotNoteOpensWithHowFaithfulTheProxyIs() {
        for (SettingSpec spec : SettingCatalog.byCategory(Category.PER_BOT_RANDOMIZABLE)) {
            assertTrue(spec.note().startsWith("Truthful") || spec.note().startsWith("Partial"),
                    spec.field() + " must open with Truthful or Partial");
        }
    }

    @Test
    void everyNonRandomizedNoteSaysWhyOrWhatIsUntouched() {
        for (SettingSpec spec : SettingCatalog.all()) {
            if (spec.category() == Category.GLOBAL_ONLY) {
                assertTrue(spec.note().startsWith("No per-bot proxy"), spec.field() + " must open with the reason");
            }
        }
    }

    // ------------------------------------------------------------------ profile facets

    private static final Pattern FACET_SEGMENT = Pattern.compile(
            "^(loadout|vitals\\.[A-Za-z]+(\\[[^\\]]+\\])?|behavior\\.[A-Za-z]+)(: .+)?$");

    private static List<String> facetPaths(SettingSpec spec) {
        List<String> paths = new ArrayList<>();
        for (String segment : spec.profileFacet().split("; ")) {
            Matcher m = FACET_SEGMENT.matcher(segment);
            assertTrue(m.matches(), spec.field() + ": malformed facet segment '" + segment + "'");
            paths.add(m.group(1));
        }
        return paths;
    }

    private static Class<?> resolveInBotProfile(String where, String path) {
        String dotted = path.contains("[") ? path.substring(0, path.indexOf('[')) : path;
        Class<?> type = BotProfile.class;
        for (String part : dotted.split("\\.")) {
            assertTrue(type.isRecord(), where + ": cannot descend into " + type.getSimpleName());
            RecordComponent found = null;
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(part)) {
                    found = component;
                }
            }
            assertNotNull(found, where + ": BotProfile has no field '" + part + "' (facet " + path + ")");
            type = found.getType();
        }
        if (path.contains("[")) {
            assertTrue(Map.class.isAssignableFrom(type), where + ": only map fields take a [key]");
        }
        return type;
    }

    @Test
    void facetsNameRealBotProfileFields() {
        for (SettingSpec spec : SettingCatalog.byCategory(Category.PER_BOT_RANDOMIZABLE)) {
            for (String path : facetPaths(spec)) {
                resolveInBotProfile(spec.field(), path);
            }
        }
    }

    @Test
    void theFirstFacetIsTheOneTheMechanismPromises() {
        for (SettingSpec spec : SettingCatalog.byCategory(Category.PER_BOT_RANDOMIZABLE)) {
            String first = facetPaths(spec).get(0);
            boolean matches = switch (spec.mechanism()) {
                case LOADOUT -> first.equals("loadout");
                case ATTRIBUTE -> first.startsWith("vitals.attributes[minecraft:");
                case PATH -> first.startsWith("behavior.");
                case VITALS -> first.equals("vitals.healthFraction") || first.equals("vitals.foodLevel");
                case NONE -> false;
            };
            assertTrue(matches,
                    spec.field() + ": " + spec.mechanism() + " does not fit facet '" + spec.profileFacet() + "'");
        }
    }

    @Test
    void onlyAttributesThatPvpBotActuallyReadsAreUsedAsProxies() {
        Set<String> used = new TreeSet<>();
        Pattern key = Pattern.compile("\\[(.+)]");
        for (SettingSpec spec : SettingCatalog.byCategory(Category.PER_BOT_RANDOMIZABLE)) {
            for (String path : facetPaths(spec)) {
                Matcher m = key.matcher(path);
                if (m.find()) {
                    used.add(m.group(1));
                }
            }
        }

        assertEquals(Set.of(), used, "an inhabitant has the attributes of a vanilla player, so no setting is randomized "
                + "through an attribute any more");
    }

    // ------------------------------------------------------------------ the recorded decisions

    private static final Map<String, Mechanism> PER_BOT_DECISIONS = Map.ofEntries(
            Map.entry("autoEquipArmor", Mechanism.LOADOUT),
            Map.entry("rangedEnabled", Mechanism.LOADOUT),
            Map.entry("maceEnabled", Mechanism.LOADOUT),
            Map.entry("spearEnabled", Mechanism.LOADOUT),
            Map.entry("crystalPvpEnabled", Mechanism.LOADOUT),
            Map.entry("anchorPvpEnabled", Mechanism.LOADOUT),
            Map.entry("autoEatEnabled", Mechanism.LOADOUT),
            Map.entry("autoShieldEnabled", Mechanism.LOADOUT),
            Map.entry("autoMendEnabled", Mechanism.LOADOUT),
            Map.entry("shieldBreakEnabled", Mechanism.LOADOUT),
            Map.entry("preferSword", Mechanism.LOADOUT),
            Map.entry("autoPotionEnabled", Mechanism.LOADOUT),
            Map.entry("cobwebEnabled", Mechanism.LOADOUT),
            Map.entry("retreatEnabled", Mechanism.LOADOUT),
            Map.entry("bhopEnabled", Mechanism.PATH),
            Map.entry("idleWanderEnabled", Mechanism.PATH),
            Map.entry("idleWanderRadius", Mechanism.PATH));

    @Test
    void randomizedSettingsAreExactlyTheOnesWithATruthfulPerBotLever() {
        assertEquals(new TreeSet<>(PER_BOT_DECISIONS.keySet()), fieldsIn(Category.PER_BOT_RANDOMIZABLE));

        PER_BOT_DECISIONS.forEach((field, mechanism) -> assertEquals(mechanism, spec(field).mechanism(), field));
    }

    @Test
    void perBotLeversPointAtTheProfileFieldsThatExpressThem() {
        assertTrue(spec("bhopEnabled").profileFacet().contains("behavior.walkType"));
        assertTrue(spec("idleWanderEnabled").profileFacet().contains("behavior.stance"));
        assertTrue(spec("idleWanderRadius").profileFacet().contains("behavior.patrolRadius"));
        assertTrue(spec("rangedEnabled").profileFacet().contains("bow/crossbow + arrows"));
        assertTrue(spec("crystalPvpEnabled").profileFacet().contains("obsidian"));
        assertTrue(spec("crystalPvpEnabled").profileFacet().contains("end_crystal"));
    }

    @Test
    void reachAndAttackTempoAreGlobalNowThatNoAttributeIsRolled() {
        String note = spec("meleeRange").note();
        assertEquals(Category.GLOBAL_ONLY, spec("meleeRange").category());
        assertTrue(note.contains("min(entity_interaction_range, global meleeRange)"), "effective reach formula");
        assertTrue(note.contains("no reach check"), "the default jump-crit path ignores the attribute");
        assertEquals(Category.GLOBAL_ONLY, spec("attackCooldown").category());
        assertTrue(spec("attackCooldown").note().contains("no longer carries an attack speed modifier"));
    }

    private static final List<String> MUST_STAY_GLOBAL = List.of(
            "autoEquipWeapon", "dropWorseArmor", "dropWorseWeapons", "combatEnabled",
            "revengeEnabled", "autoTargetEnabled", "targetPlayers", "targetHostileMobs", "targetOtherBots",
            "maxTargetDistance", "rangedMinRange", "rangedOptimalRange", "rangedMaxRange", "maceRange",
            "moveSpeed", "criticalsEnabled", "criticalFallTicks", "bowMinDrawTime", "spearRange", "spearChargeRange",
            "mendDurabilityThreshold", "shieldHealthThreshold", "shieldHoldTicks", "shieldRaiseTicks",
            "minHungerToEat", "retreatHealthPercent", "criticalHealthPercent", "factionsEnabled",
            "friendlyFireEnabled", "missChance", "mistakeChance", "shieldBreakChance", "attackInvincible",
            "aimSpeed", "shieldMace", "arrowPredictionEnabled", "rangedStrafeEnabled", "rangedRetreatOnClose",
            "meleeRange", "attackCooldown", "autoTotemEnabled", "totemPriority");

    @Test
    void behaviourSettingsWithoutATruthfulProxyAreNeverRandomized() {
        for (String field : MUST_STAY_GLOBAL) {
            assertEquals(Category.GLOBAL_ONLY, spec(field).category(), field);
        }
        assertEquals(new TreeSet<>(MUST_STAY_GLOBAL), fieldsIn(Category.GLOBAL_ONLY));
    }

    @Test
    void moveSpeedIsNotAnAttributeUpstreamAndSaysSo() {
        SettingSpec moveSpeed = spec("moveSpeed");
        assertEquals(Category.GLOBAL_ONLY, moveSpeed.category());
        assertTrue(moveSpeed.note().contains("not an attribute upstream"));
        assertTrue(moveSpeed.note().contains("Audits split"), "the disagreement between the audits is recorded");
    }

    @Test
    void administrativeSettingsAreNeverTouchedByTheAddon() {
        List<String> admin = List.of("dropDistance", "checkInterval", "botsRelogs", "useSpecialNames",
                "botLeaveOnDeath", "maxMassSpawn", "profileLagFix", "safeSpawn", "clearOnRemove");
        assertEquals(new TreeSet<>(admin), fieldsIn(Category.ADMIN_OPERATIONAL));
        for (String field : admin) {
            assertEquals(Mechanism.NONE, spec(field).mechanism(), field);
        }
    }

    @Test
    void noSettingOfTheAuditedReleaseIsUnsupported() {
        assertTrue(SettingCatalog.byCategory(Category.UNSUPPORTED).isEmpty(),
                "every 0.0.15 setting is consumed by PvP BOT; the category is reserved for future releases");
    }

    @Test
    void settingsWhoseBehaviourNeedsAGlobalSwitchNameThatSwitch() {
        assertTrue(spec("spearEnabled").note().contains("dormant at the default"));
        assertTrue(spec("combatEnabled").note().contains("attack=true"), "every path is built as a fighter's path");
        assertTrue(spec("shieldMace").note().contains("OPPONENT"), "the trigger is the opponent, not the bot");
    }

    // ------------------------------------------------------------------ cross-checks with the frozen contract

    @Test
    void everyToggleTheGeneratorReadsFromGlobalCapabilitiesIsACataloguedBooleanWithTheSameDefault() throws Exception {
        GlobalCapabilities defaults = GlobalCapabilities.upstreamDefaults();
        for (RecordComponent component : GlobalCapabilities.class.getRecordComponents()) {
            SettingSpec spec = spec(component.getName());
            assertEquals(ValueType.BOOLEAN, spec.type(), component.getName());
            Object value = component.getAccessor().invoke(defaults);
            assertEquals(Boolean.parseBoolean(spec.defaultValue()), value,
                    component.getName() + ": GlobalCapabilities.upstreamDefaults() must equal the upstream default");
        }
    }

    // ------------------------------------------------------------------ lookups

    @Test
    void findIsExactAndNullSafe() {
        assertEquals("move-speed", spec("moveSpeed").commandKey());
        assertTrue(SettingCatalog.find("nothingLikeThis").isEmpty());
        assertTrue(SettingCatalog.find("MOVESPEED").isEmpty(), "case-sensitive: the field name is the identity");
        assertTrue(SettingCatalog.find("move-speed").isEmpty(), "the command key is not the field name");
        assertTrue(SettingCatalog.find("").isEmpty());
        assertTrue(SettingCatalog.find(null).isEmpty());
    }

    @Test
    void byCategoryPartitionsTheCatalogAndKeepsUpstreamOrder() {
        List<String> order = SettingCatalog.all().stream().map(SettingSpec::field).toList();
        Map<Category, List<SettingSpec>> parts = new EnumMap<>(Category.class);
        int total = 0;
        for (Category category : Category.values()) {
            List<SettingSpec> part = SettingCatalog.byCategory(category);
            parts.put(category, part);
            total += part.size();
            int last = -1;
            for (SettingSpec spec : part) {
                assertEquals(category, spec.category());
                int index = order.indexOf(spec.field());
                assertTrue(index > last, category + " must keep upstream order");
                last = index;
            }
        }

        assertEquals(68, total);
        assertEquals(17, parts.get(Category.PER_BOT_RANDOMIZABLE).size());
        assertEquals(42, parts.get(Category.GLOBAL_ONLY).size());
        assertEquals(9, parts.get(Category.ADMIN_OPERATIONAL).size());
        assertEquals(0, parts.get(Category.UNSUPPORTED).size());
    }

    @Test
    void byCategoryRejectsNull() {
        assertThrows(NullPointerException.class, () -> SettingCatalog.byCategory(null));
    }

    @Test
    void theExposedListsCannotBeModified() {
        assertThrows(UnsupportedOperationException.class, () -> SettingCatalog.all().remove(0));
        assertThrows(UnsupportedOperationException.class,
                () -> SettingCatalog.byCategory(Category.GLOBAL_ONLY).remove(0));
    }

    // ------------------------------------------------------------------ audit

    @Test
    void auditIsCleanForTheBaseline() {
        AuditReport report = SettingCatalog.audit(baselineNames());

        assertTrue(report.clean());
        assertTrue(report.unknownToCatalog().isEmpty());
        assertTrue(report.missingUpstream().isEmpty());
    }

    @Test
    void auditDetectsAnUnknownSetting() {
        Set<String> upstream = baselineNames();
        upstream.add("zeroKnockback");
        upstream.add("aardvarkMode");

        AuditReport report = SettingCatalog.audit(upstream);

        assertFalse(report.clean());
        assertEquals(List.of("aardvarkMode", "zeroKnockback"), report.unknownToCatalog(), "sorted");
        assertTrue(report.missingUpstream().isEmpty());
    }

    @Test
    void auditDetectsARemovedSetting() {
        Set<String> upstream = baselineNames();
        upstream.remove("moveSpeed");
        upstream.remove("autoEquipArmor");

        AuditReport report = SettingCatalog.audit(upstream);

        assertFalse(report.clean());
        assertEquals(List.of("autoEquipArmor", "moveSpeed"), report.missingUpstream(), "sorted");
        assertTrue(report.unknownToCatalog().isEmpty());
    }

    @Test
    void auditReportsARenameAsOneRemovalAndOneAddition() {
        Set<String> upstream = baselineNames();
        upstream.remove("aimSpeed");
        upstream.add("aimSpeedDegrees");

        AuditReport report = SettingCatalog.audit(upstream);

        assertEquals(List.of("aimSpeedDegrees"), report.unknownToCatalog());
        assertEquals(List.of("aimSpeed"), report.missingUpstream());
    }

    @Test
    void auditComparesNamesCaseSensitively() {
        Set<String> upstream = baselineNames();
        upstream.remove("autoEquipArmor");
        upstream.add("AutoEquipArmor");

        AuditReport report = SettingCatalog.audit(upstream);

        assertEquals(List.of("AutoEquipArmor"), report.unknownToCatalog());
        assertEquals(List.of("autoEquipArmor"), report.missingUpstream());
    }

    @Test
    void anEmptyDiscoveryReportsEverySettingMissing() {
        AuditReport report = SettingCatalog.audit(Set.of());

        assertTrue(report.unknownToCatalog().isEmpty());
        assertEquals(68, report.missingUpstream().size());
        assertEquals(new ArrayList<>(new TreeSet<>(UpstreamBaseline.fields())), report.missingUpstream());
    }

    @Test
    void auditIgnoresNullAndBlankNames() {
        Set<String> upstream = baselineNames();
        upstream.add(null);
        upstream.add("");
        upstream.add("   ");

        assertTrue(SettingCatalog.audit(upstream).clean());
    }

    @Test
    void auditRejectsANullSet() {
        assertThrows(NullPointerException.class, () -> SettingCatalog.audit(null));
    }

    @Test
    void auditDoesNotModifyItsInputAndReturnsImmutableLists() {
        Set<String> upstream = new HashSet<>(baselineNames());
        upstream.add("extra");
        upstream.remove("safeSpawn");
        Set<String> before = new HashSet<>(upstream);

        AuditReport report = SettingCatalog.audit(upstream);

        assertEquals(before, upstream);
        assertThrows(UnsupportedOperationException.class, () -> report.unknownToCatalog().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> report.missingUpstream().add("x"));
    }

    @Test
    void auditListsAreSortedNaturally() {
        Set<String> upstream = new HashSet<>(Arrays.asList("zeta", "Alpha", "mid"));

        List<String> unknown = SettingCatalog.audit(upstream).unknownToCatalog();

        assertEquals(List.of("Alpha", "mid", "zeta"), unknown);
        List<String> missing = SettingCatalog.audit(upstream).missingUpstream();
        assertEquals(new ArrayList<>(new TreeSet<>(missing)), missing);
    }

    // ------------------------------------------------------------------ markdown

    @Test
    void markdownTableHasOneRowPerSettingInCatalogOrder() {
        String[] lines = SettingCatalog.markdownTable().split("\n", -1);

        assertEquals(2 + 68, lines.length, "header, separator and 68 rows, no trailing newline");
        assertTrue(lines[0].startsWith("| Setting | /pvpbot settings key | Type & range | Default | Category | "
                + "Per-bot mechanism | Profile facet | Notes |"));
        List<SettingSpec> specs = SettingCatalog.all();
        for (int i = 0; i < specs.size(); i++) {
            String[] cells = lines[i + 2].split("(?<!\\\\)\\|", -1);
            assertEquals(10, cells.length, specs.get(i).field() + ": 8 cells between the outer pipes");
            assertEquals(" `" + specs.get(i).field() + "` ", cells[1]);
            assertEquals(" " + specs.get(i).category().name() + " ", cells[5]);
        }
    }

    @Test
    void markdownTableIsDeterministic() {
        assertEquals(SettingCatalog.markdownTable(), SettingCatalog.markdownTable());
    }
}
