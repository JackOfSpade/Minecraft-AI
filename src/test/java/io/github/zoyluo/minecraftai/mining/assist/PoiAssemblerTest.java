package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.List;
import net.minecraft.core.BlockPos;

import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.OVERWORLD;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the scorer through the real assembler with fixtures shaped like the design 6.3 table, so the
 * window, the flags and the entity evidence are pinned end to end (no world involved).
 */
class PoiAssemblerTest {
    private static final double R = 16.0D;
    private static final List<String> OVERWORLD_ONLY = List.of(OVERWORLD);

    private static void add(PoiEvidenceWindow window, BlockFacts facts, int x, int y, int z) {
        window.observe(new BlockPos(x, y, z), facts.bucket(), facts.poiFlags(), 100, false);
    }

    private static void add(PoiEvidenceWindow window, String path, int x, int y, int z) {
        add(window, facts(path), x, y, z);
    }

    private static PoiSignals assemble(PoiEvidenceWindow window, EntityEvidence entities,
                                       FreeRunStats.Openness openness, String dimension) {
        return PoiAssembler.assemble(window, 0.5D, 64.0D, 0.5D, entities, openness, R, dimension, OVERWORLD_ONLY);
    }

    private static PoiScorer.PoiScore score(PoiEvidenceWindow window, EntityEvidence entities,
                                            FreeRunStats.Openness openness, String dimension) {
        return PoiScorer.evaluate(assemble(window, entities, openness, dimension));
    }

    private static FreeRunStats.Openness fullCavern() {
        FreeRunStats ring = new FreeRunStats();
        long eye = SweepEngine.eyeCell(0.5D, 64.5D, 0.5D);
        for (int slot = 0; slot < SphereSchedule.LATTICE_SIZE; slot++) {
            ring.record(slot, R, SphereSchedule.latticeDirection(slot).dy(), 10, eye);
        }
        FreeRunStats.Openness openness = ring.openness(10, eye, R);
        assertTrue(openness.valid());
        return openness;
    }

    // ---- structures ------------------------------------------------------------------------------

    @Test
    void mineshaftPaletteIsStructureCertainAndLabelledMineshaft() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 8; i++) {
            add(window, i % 2 == 0 ? "oak_planks" : "oak_fence", 3 + i, 64, 0);
        }
        for (int i = 0; i < 3; i++) {
            add(window, "rail", 3 + i, 63, 1);
            add(window, "cobweb", 3 + i, 65, 1);
        }
        add(window, "torch", 5, 65, 0);
        add(window, "wall_torch", 6, 65, 0);
        EntityEvidence entities = new EntityEvidence();
        entities.add("minecraft", "chest_minecart");

        PoiSignals signals = assemble(window, entities, null, OVERWORLD);
        PoiScorer.PoiScore score = PoiScorer.evaluate(signals);
        assertEquals(PoiScorer.Band.STRUCTURE_CERTAIN, score.band());
        assertTrue(score.s() > 0.9D, "S=" + score.s());
        assertEquals(PoiLabeler.MINESHAFT, PoiLabeler.label(signals));
    }

    @Test
    void aSculkShriekerIsMandatoryAndKeepsItsSpecificCounts() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        add(window, "sculk_shrieker", 4, 64, 4);
        PoiSignals signals = assemble(window, null, null, OVERWORLD);
        assertEquals(1, signals.sculkShriekerCount());
        assertEquals(1, signals.sculkFamilyWithin12());
        PoiScorer.PoiScore score = PoiScorer.evaluate(signals);
        assertEquals(PoiScorer.Band.MANDATORY, score.band());
        assertTrue(score.mandatoryTrigger().contains("sculk_shrieker"), score.mandatoryTrigger());
    }

    @Test
    void reinforcedDeepslateAndCatalystAreMandatoryToo() {
        PoiEvidenceWindow reinforced = new PoiEvidenceWindow();
        add(reinforced, "reinforced_deepslate", 2, 64, 2);
        assertEquals(PoiScorer.Band.MANDATORY, score(reinforced, null, null, OVERWORLD).band());

        PoiEvidenceWindow catalyst = new PoiEvidenceWindow();
        add(catalyst, "sculk_catalyst", 2, 64, 2);
        assertEquals(PoiScorer.Band.MANDATORY, score(catalyst, null, null, OVERWORLD).band());
    }

    @Test
    void twoSensorsAreMandatoryButOneIsNot() {
        PoiEvidenceWindow one = new PoiEvidenceWindow();
        add(one, "sculk_sensor", 2, 64, 2);
        assertFalse(score(one, null, null, OVERWORLD).isMandatory());

        PoiEvidenceWindow two = new PoiEvidenceWindow();
        add(two, "sculk_sensor", 2, 64, 2);
        add(two, "calibrated_sculk_sensor", 5, 64, 2);
        assertEquals(PoiScorer.Band.MANDATORY, score(two, null, null, OVERWORLD).band());
    }

    @Test
    void sixPlainSculkCellsWithinTwelveBlocksInAnOpenCavernAreMandatory() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 6; i++) {
            add(window, i % 2 == 0 ? "sculk" : "sculk_vein", 3 + i, 63, 2);
        }
        PoiSignals signals = assemble(window, null, fullCavern(), OVERWORLD);
        assertEquals(6, signals.sculkFamilyWithin12());
        assertEquals(0, signals.totalCells(), "plain sculk is natural and never a scoring cell");
        PoiScorer.PoiScore score = PoiScorer.evaluate(signals);
        assertEquals(PoiScorer.Band.MANDATORY, score.band());
        assertTrue(score.mandatoryTrigger().contains("sculk_family_cavern"));
    }

    @Test
    void sculkWithoutAnOpenCavernOrBeyondTwelveBlocksIsNotMandatory() {
        PoiEvidenceWindow near = new PoiEvidenceWindow();
        for (int i = 0; i < 6; i++) {
            add(near, "sculk", 3 + i, 63, 2);
        }
        assertFalse(score(near, null, null, OVERWORLD).isMandatory(), "no openness signal");

        PoiEvidenceWindow far = new PoiEvidenceWindow();
        for (int i = 0; i < 6; i++) {
            add(far, "sculk", 20 + i, 63, 2);
        }
        PoiSignals signals = assemble(far, null, fullCavern(), OVERWORLD);
        assertEquals(0, signals.sculkFamilyWithin12());
        assertFalse(PoiScorer.evaluate(signals).isMandatory());
    }

    @Test
    void aVisibleWardenIsMandatoryOnItsOwn() {
        EntityEvidence entities = new EntityEvidence();
        entities.add("minecraft", "warden");
        PoiScorer.PoiScore score = score(new PoiEvidenceWindow(), entities, null, OVERWORLD);
        assertEquals(PoiScorer.Band.MANDATORY, score.band());
        assertTrue(score.mandatoryTrigger().contains("warden_visible"));
    }

    @Test
    void dungeonWithMossyCobblestoneSpawnerAndChestsLabelsDungeon() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 8; i++) {
            add(window, "mossy_cobblestone", 3 + i, 64, 0);
        }
        add(window, "spawner", 6, 65, 3);
        add(window, AssistTestSupport.chest(), 4, 64, 3);
        add(window, AssistTestSupport.chest(), 5, 64, 3);
        PoiSignals signals = assemble(window, null, null, OVERWORLD);
        assertTrue(signals.mossyStone());
        PoiScorer.PoiScore score = PoiScorer.evaluate(signals);
        assertEquals(PoiScorer.Band.STRUCTURE_CERTAIN, score.band());
        assertEquals(PoiLabeler.DUNGEON, PoiLabeler.label(signals));
    }

    @Test
    void playerBaseIsDowngradedToPossibleNotACertainStop() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 8; i++) {
            add(window, "oak_planks", 3 + i, 64, 0);
        }
        add(window, "crafting_table", 3, 64, 2);
        add(window, "furnace", 4, 64, 2);
        add(window, "red_bed", 5, 64, 2);
        add(window, AssistTestSupport.chest(), 6, 64, 2);
        add(window, AssistTestSupport.chest(), 7, 64, 2);
        add(window, "torch", 8, 65, 2);
        PoiSignals signals = assemble(window, null, null, OVERWORLD);
        assertTrue(signals.hasHabitationItem());
        assertTrue(signals.habitation().contains(PoiSignals.Habitation.BED));
        assertTrue(signals.habitation().contains(PoiSignals.Habitation.CRAFTING_TABLE));
        PoiScorer.PoiScore score = PoiScorer.evaluate(signals);
        assertEquals(PoiScorer.Band.POSSIBLE, score.band());
        assertTrue(score.downgradedByHabitation());
        assertTrue(score.habitationLike());
    }

    @Test
    void anItemFrameFromTheEntityScanCountsAsHabitation() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        add(window, "oak_planks", 3, 64, 0);
        EntityEvidence entities = new EntityEvidence();
        entities.add("minecraft", "item_frame");
        assertTrue(assemble(window, entities, null, OVERWORLD).habitation().contains(PoiSignals.Habitation.ITEM_FRAME));
    }

    // ---- things that must stay quiet ------------------------------------------------------------

    @Test
    void anEmptyWindowScoresNothing() {
        PoiScorer.PoiScore score = score(new PoiEvidenceWindow(), null, null, OVERWORLD);
        assertEquals(PoiScorer.Band.NONE, score.band());
        assertEquals(0.0D, score.t(), 1.0e-12D);
    }

    @Test
    void ownTorchesAndCobbleWithoutOtherEvidenceAreWeakOnlyAndScoreZero() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 4; i++) {
            add(window, "torch", i, 64, 0);
        }
        for (int i = 0; i < 6; i++) {
            add(window, "cobblestone", i, 63, 0);
        }
        PoiScorer.PoiScore score = score(window, null, null, OVERWORLD);
        assertEquals(0.0D, score.sumW(), 1.0e-12D);
        assertEquals(PoiScorer.Band.NONE, score.band());
    }

    @Test
    void aSingleStrayPlankStaysBelowPossible() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        add(window, "oak_planks", 3, 64, 0);
        PoiScorer.PoiScore score = score(window, null, null, OVERWORLD);
        assertEquals(PoiScorer.Band.NONE, score.band());
        assertTrue(score.t() < 0.2D);
    }

    @Test
    void naturalTerrainNeverReachesTheWindowSoNothingScores() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (String path : List.of("stone", "moss_block", "spore_blossom", "big_dripleaf", "diamond_ore", "dripstone_block")) {
            BlockFacts f = facts(path);
            window.observe(new BlockPos(1, 64, 1), f.bucket(), f.poiFlags(), 5, false);
        }
        assertTrue(window.isEmpty());
        assertEquals(PoiScorer.Band.NONE, score(window, null, null, OVERWORLD).band());
    }

    // ---- cavern channel gating ------------------------------------------------------------------

    @Test
    void aHugeNaturalCavernAloneIsCavernOnlyInTheOverworld() {
        PoiScorer.PoiScore score = score(new PoiEvidenceWindow(), null, fullCavern(), OVERWORLD);
        assertEquals(PoiScorer.Band.CAVERN_ONLY, score.band());
        assertTrue(score.cavernActive());
    }

    @Test
    void theCavernChannelIsOffOutsideTheListedDimensions() {
        PoiScorer.PoiScore score = score(new PoiEvidenceWindow(), null, fullCavern(), "minecraft:the_nether");
        assertEquals(PoiScorer.Band.NONE, score.band());
        assertFalse(score.cavernActive());
        assertEquals(0.0D, score.c(), 1.0e-12D);
    }

    @Test
    void theCavernChannelIsOffBelowTwelveBlocksOfPerceptionRadius() {
        PoiSignals signals = PoiAssembler.assemble(new PoiEvidenceWindow(), 0.5D, 64.0D, 0.5D, null, fullCavern(),
                8.0D, OVERWORLD, OVERWORLD_ONLY);
        PoiScorer.PoiScore score = PoiScorer.evaluate(signals);
        assertFalse(score.cavernActive());
        assertEquals(PoiScorer.Band.NONE, score.band());
    }

    @Test
    void unavailableOpennessGivesNoCavernSignal() {
        PoiScorer.PoiScore score = score(new PoiEvidenceWindow(), null,
                new FreeRunStats().openness(10, 0L, R), OVERWORLD);
        assertEquals(0.0D, score.c(), 1.0e-12D);
        assertEquals(PoiScorer.Band.NONE, score.band());
    }

    // ---- flags across both sub-windows ----------------------------------------------------------

    @Test
    void naturalBlackstoneSetsThePresenceFlagFromTheFlagOnlyWindow() {
        PoiEvidenceWindow window = new PoiEvidenceWindow();
        add(window, "blackstone", 3, 64, 3);
        PoiSignals signals = assemble(window, null, null, OVERWORLD);
        assertTrue(signals.blackstone());
        assertEquals(0, signals.totalCells());
    }
}
