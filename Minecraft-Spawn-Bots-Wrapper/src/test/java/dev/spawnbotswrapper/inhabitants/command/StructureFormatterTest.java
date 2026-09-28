package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.command.StructureFormatter.Found;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.command.Fixtures.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureFormatterTest {

    private static List<String> here(List<Found> found, InhabitantsConfig cfg) {
        return Markup.strip(StructureFormatter.here(Fixtures.OVERWORLD, 100, 70, -20, found, cfg));
    }

    // ---------------------------------------------------------------- structure here

    @Test
    void nothingHereIsSaidClearly() {
        List<String> t = here(List.of(), Fixtures.bareConfig());
        assertEquals("Structures here: none (100, 70, -20 in minecraft:overworld)", t.get(0));
        assertTrue(contains(t, "Only structures in loaded chunks are searched"), t.toString());
    }

    @Test
    void anUnprocessedStructureShowsIdTagsBoxChunkAndWhatWouldHappen() {
        List<String> t = here(List.of(new Found(
                Fixtures.snapshot("minecraft:village_plains", 6, 12, true, "minecraft:village", "minecraft:z_tag"), null)),
                Fixtures.bareConfig());

        assertEquals("Structures here: 1 (100, 70, -20 in minecraft:overworld)", t.get(0));
        assertEquals("minecraft:village_plains", t.get(1));
        assertEquals("  tags: #minecraft:village, #minecraft:z_tag", t.get(2));
        assertEquals("  box: (96,60,192) to (159,90,255)  64x31x64, 1 piece", t.get(3));
        assertEquals("  start chunk: 6,12  |  generated this session", t.get(4));
        assertEquals("  status: not processed yet", t.get(5));
        assertTrue(t.get(6).startsWith("  eligible - would roll 65% occupied, 1-64 bots (default)"), t.get(6));
        assertTrue(t.get(6).contains("/inhabitants process nearest rolls it now"), t.get(6));
    }

    @Test
    void structuresWithoutTagsAndLoadedFromDiskSaySo() {
        List<String> t = here(List.of(new Found(Fixtures.snapshot("somemod:tower", 0, 0, false), null)),
                Fixtures.bareConfig());
        assertTrue(contains(t, "  tags: none"), t.toString());
        assertTrue(contains(t, "loaded from disk"), t.toString());
    }

    @Test
    void theEffectiveRuleNamesWhereItCameFrom() {
        InhabitantsConfig c = Fixtures.bareConfig();
        c.tags.put("#minecraft:village", new InhabitantsConfig.RuleOverride(0.7, 1));
        List<String> t = here(List.of(new Found(
                Fixtures.snapshot("minecraft:village_plains", 0, 0, true, "minecraft:village"), null)), c);
        assertTrue(contains(t, "would roll 70% occupied, 1-64 bots (tag #minecraft:village)"), t.toString());
    }

    @Test
    void ruleTextListsSeparateSourcesWhenTheyDiffer() {
        InhabitantsConfig c = Fixtures.bareConfig();
        c.structures.put("minecraft:pillager_outpost", new InhabitantsConfig.RuleOverride(0.85, null));
        List<String> t = here(List.of(new Found(Fixtures.snapshot("minecraft:pillager_outpost", 0, 0, true), null)), c);
        assertTrue(contains(t, "85% occupied (structure minecraft:pillager_outpost), 1-64 bots (default / size-scaled)"),
                t.toString());
    }

    @Test
    void anExcludedStructureIsFlaggedAndForcingIsMentioned() {
        InhabitantsConfig c = Fixtures.bareConfig();
        c.exclude = new ArrayList<>(List.of("minecraft:buried_treasure"));
        List<String> t = here(List.of(new Found(Fixtures.snapshot("minecraft:buried_treasure", 0, 0, true), null)), c);
        assertTrue(contains(t, "excluded by the config include/exclude lists (a forced process still works)"), t.toString());
        assertFalse(contains(t, "would roll"), t.toString());
    }

    @Test
    void aDisabledAddonOverridesEverything() {
        InhabitantsConfig c = Fixtures.bareConfig();
        c.enabled = false;
        List<String> t = here(List.of(new Found(Fixtures.snapshot("minecraft:mansion", 0, 0, true), null)), c);
        assertTrue(contains(t, "addon is disabled; nothing is rolled"), t.toString());
    }

    @Test
    void caveatsExplainWhyAnEligibleStructureWillNotBeRolledAutomatically() {
        InhabitantsConfig c = Fixtures.bareConfig();
        c.processing.onlyNewlyGenerated = true;
        c.dimensions.exclude = new ArrayList<>(List.of("minecraft:overworld"));
        List<String> t = here(List.of(new Found(Fixtures.snapshot("minecraft:mansion", 0, 0, false), null)), c);
        assertTrue(contains(t, "eligible only by force"), t.toString());
        assertTrue(contains(t, "dimension excluded"), t.toString());
        assertTrue(contains(t, "generated before the addon (processing.onlyNewlyGenerated)"), t.toString());
    }

    @Test
    void aPopulatedStructureListsItsRollAndEveryBotWithItsState() {
        StructureRecord r = Fixtures.populated(
                Fixtures.bot(0, "Inh_Alpha", BotState.SPAWNED),
                Fixtures.bot(1, "Inh_Beta", BotState.REQUESTED),
                Fixtures.bot(2, "Inh_Gamma", BotState.FAILED),
                Fixtures.bot(3, "Inh_Delta", BotState.PLANNED));
        List<String> raw = StructureFormatter.here(Fixtures.OVERWORLD, 1, 2, 3, List.of(
                new Found(Fixtures.snapshot("minecraft:pillager_outpost", 0, 0, true), r)), Fixtures.bareConfig());
        List<String> t = Markup.strip(raw);

        assertTrue(contains(t, "  status: POPULATED  (RANDOM)  roll 0.310 < chance 65%"), t.toString());
        assertTrue(contains(t, "  bots: 1/4 spawned"), t.toString());
        assertTrue(contains(t, "    Inh_Alpha (spawned)") && contains(t, "    Inh_Beta (requested)")
                && contains(t, "    Inh_Gamma (failed)") && contains(t, "    Inh_Delta (planned)"), t.toString());
        assertTrue(raw.stream().anyMatch(l -> l.contains("§aPOPULATED")), "populated is green");
        assertTrue(raw.stream().anyMatch(l -> l.contains("§cInh_Gamma") || l.contains("§c(failed)")),
                "failed is red");
        assertFalse(contains(t, "not processed"), t.toString());
    }

    @Test
    void aPendingStructureIsYellowAndCountsBotsAgainstThePlan() {
        StructureRecord r = Fixtures.occupied(StructureStatus.OCCUPIED_PENDING, 4,
                Fixtures.bot(0, "Inh_A", BotState.SPAWNED), Fixtures.bot(1, "Inh_B", BotState.PLANNED));
        List<String> raw = StructureFormatter.here(Fixtures.OVERWORLD, 0, 0, 0,
                List.of(new Found(Fixtures.snapshot("minecraft:x", 0, 0, true), r)), Fixtures.bareConfig());
        assertTrue(contains(Markup.strip(raw), "status: PENDING"), Markup.strip(raw).toString());
        assertTrue(contains(Markup.strip(raw), "bots: 1/4 spawned"), Markup.strip(raw).toString());
        assertTrue(raw.stream().anyMatch(l -> l.contains("§ePENDING")));
    }

    @Test
    void anAbandonedStructureShowsItsRollWhenKeptAndAdmitsWhenNot() {
        List<String> kept = here(List.of(new Found(Fixtures.snapshot("minecraft:x", 0, 0, true),
                Fixtures.abandonedRolled(0.65, 0.83))), Fixtures.bareConfig());
        assertTrue(contains(kept, "status: ABANDONED  (RANDOM)  roll 0.830 >= chance 65%"), kept.toString());
        assertFalse(contains(kept, "bots:"), kept.toString());

        List<String> notKept = here(List.of(new Found(Fixtures.snapshot("minecraft:x", 0, 0, true),
                Fixtures.synthesizedAbandoned())), Fixtures.bareConfig());
        assertTrue(contains(notKept, "status: ABANDONED  (RANDOM)  roll details not kept"), notKept.toString());
        assertFalse(contains(notKept, "chance 0%"), notKept.toString());
    }

    @Test
    void anAdminForcedRecordSaysNoRollAndShowsItsNote() {
        StructureRecord r = Fixtures.occupied(StructureStatus.GAVE_UP, 2);
        r.source = "ADMIN_FORCED";
        r.note = "no valid positions after 12 attempts";
        List<String> raw = StructureFormatter.here(Fixtures.OVERWORLD, 0, 0, 0,
                List.of(new Found(Fixtures.snapshot("minecraft:x", 0, 0, true), r)), Fixtures.bareConfig());
        List<String> t = Markup.strip(raw);
        assertTrue(contains(t, "status: GAVE UP  (ADMIN_FORCED)  no roll (forced)"), t.toString());
        assertTrue(contains(t, "note: no valid positions after 12 attempts"), t.toString());
        assertTrue(raw.stream().anyMatch(l -> l.contains("§cGAVE UP")));
    }

    @Test
    void aStructureWithManyBotsIsCapped() {
        BotRecord[] bots = new BotRecord[20];
        for (int i = 0; i < bots.length; i++) {
            bots[i] = Fixtures.bot(i, "Inh_Bot" + i, BotState.SPAWNED);
        }
        List<String> t = here(List.of(new Found(Fixtures.snapshot("minecraft:x", 0, 0, true),
                Fixtures.populated(bots))), Fixtures.bareConfig());
        assertTrue(contains(t, "  bots: 20/20 spawned"), t.toString());
        assertTrue(contains(t, "Inh_Bot7") && !contains(t, "Inh_Bot8"), t.toString());
        assertTrue(contains(t, "... and 12 more"), t.toString());
    }

    @Test
    void overlappingStructuresAreListedAndCapped() {
        List<Found> found = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            found.add(new Found(Fixtures.snapshot("minecraft:s" + i, i, 0, true), null));
        }
        List<String> t = here(found, Fixtures.bareConfig());
        assertEquals("Structures here: 9 (100, 70, -20 in minecraft:overworld)", t.get(0));
        assertTrue(contains(t, "minecraft:s5") && !contains(t, "minecraft:s6"), t.toString());
        assertTrue(contains(t, "... and 3 more overlapping structures"), t.toString());
    }

    @Test
    void aBotRecordWithoutNamesOrBotListDoesNotBreakTheOutput() {
        StructureRecord r = Fixtures.populated();
        r.bots = null;
        List<String> t = here(List.of(new Found(Fixtures.snapshot("minecraft:x", 0, 0, true), r)),
                Fixtures.bareConfig());
        assertTrue(contains(t, "status: POPULATED"), t.toString());
        assertFalse(contains(t, "bots:"), t.toString());
    }

    @Test
    void recordTextCannotInjectColour() {
        StructureRecord r = Fixtures.populated(Fixtures.bot(0, "Inh_§4Evil", BotState.SPAWNED));
        r.note = "note §bx";
        List<String> raw = StructureFormatter.here(Fixtures.OVERWORLD, 0, 0, 0,
                List.of(new Found(Fixtures.snapshot("minecraft:x", 0, 0, true), r)), Fixtures.bareConfig());
        assertFalse(raw.stream().anyMatch(l -> l.contains("§4Evil")), raw.toString());
        assertFalse(raw.stream().anyMatch(l -> l.contains("§bx")), raw.toString());
    }

    // ---------------------------------------------------------------- nearby

    private static Map.Entry<StructureKey, StructureRecord> e(String id, int cx, int cz, StructureRecord r) {
        return Fixtures.entry(Fixtures.key(id, cx, cz), r);
    }

    @Test
    void nearbyWithNothingSuggestsWhatToDo() {
        List<String> t = Markup.strip(StructureFormatter.nearby(8, 0, 0, List.of()));
        assertEquals("Processed structures within 8 chunks: none", t.get(0));
        assertTrue(contains(t, "Try /inhabitants process nearest, or a larger radius."), t.toString());
    }

    @Test
    void nearbyShowsOneLinePerStructureWithDistanceStatusBotsAndRoll() {
        StructureRecord populated = Fixtures.populated(Fixtures.bot(0, "A", BotState.SPAWNED),
                Fixtures.bot(1, "B", BotState.FAILED));
        List<Map.Entry<StructureKey, StructureRecord>> entries = List.of(
                e("minecraft:village_plains", 0, 0, populated),
                e("minecraft:pillager_outpost", 3, 4, Fixtures.abandonedRolled(0.85, 0.9)),
                e("somemod:tower", -2, 1, Fixtures.occupied(StructureStatus.OCCUPIED_PENDING, 3)));
        List<String> t = Markup.strip(StructureFormatter.nearby(8, 8.0, 8.0, entries));

        assertEquals("Processed structures within 8 chunks: 3 (nearest first)", t.get(0));
        assertEquals("minecraft:village_plains [0,0] ~0 blocks POPULATED bots 1/2 chance 65% roll 0.31", t.get(1));
        assertEquals("minecraft:pillager_outpost [3,4] ~80 blocks ABANDONED chance 85% roll 0.90", t.get(2));
        assertTrue(t.get(3).startsWith("somemod:tower [-2,1] ~"), t.get(3));
        assertTrue(t.get(3).contains("PENDING bots 0/3"), t.get(3));
    }

    @Test
    void nearbyMarksForcedRecordsAndSynthesizedAbandonedOnesHaveNoRollNoise() {
        StructureRecord forced = Fixtures.occupied(StructureStatus.POPULATED, 1, Fixtures.bot(0, "A", BotState.SPAWNED));
        forced.source = "ADMIN_FORCED";
        List<String> t = Markup.strip(StructureFormatter.nearby(8, 0, 0, List.of(
                e("minecraft:a", 0, 0, forced),
                e("minecraft:b", 1, 1, Fixtures.synthesizedAbandoned()))));
        assertTrue(t.get(1).endsWith("bots 1/1 forced"), t.get(1));
        assertTrue(t.get(2).endsWith("ABANDONED"), t.get(2));
    }

    @Test
    void nearbyIsCappedAtFifteenLinesPlusAMoreLine() {
        List<Map.Entry<StructureKey, StructureRecord>> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entries.add(e("minecraft:s" + i, i, 0, Fixtures.synthesizedAbandoned()));
        }
        List<String> t = Markup.strip(StructureFormatter.nearby(30, 0, 0, entries));

        assertEquals("Processed structures within 30 chunks: 40 (nearest first)", t.get(0));
        assertEquals(1 + 15 + 1, t.size());
        assertTrue(t.get(15).startsWith("minecraft:s14 "), t.get(15));
        assertEquals("... and 25 more (narrow the radius)", t.get(16));
    }

    @Test
    void nearbyToleratesRecordsWithoutBotLists() {
        StructureRecord r = Fixtures.populated();
        r.bots = null;
        List<String> t = Markup.strip(StructureFormatter.nearby(8, 0, 0, List.of(e("minecraft:a", 0, 0, r))));
        assertTrue(t.get(1).contains("POPULATED bots 0/0"), t.get(1));
    }
}
