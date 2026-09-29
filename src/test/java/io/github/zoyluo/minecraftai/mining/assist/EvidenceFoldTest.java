package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;

import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.BOT;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.NOT_PLACED;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceFoldTest {
    private static final BlockPos P = new BlockPos(10, 20, 30);

    private static MiningAssistState state() {
        return new MiningAssistState(BOT);
    }

    private static int hit(MiningAssistState s, BlockPos pos, BlockFacts f, HazardField.Kind fluid, int tick) {
        return EvidenceFold.foldHit(s, pos, f, fluid, NOT_PLACED, tick);
    }

    // ---- valuables ------------------------------------------------------------------------------

    @Test
    void valuableHitIsRecordedOnceThenUpdated() {
        MiningAssistState s = state();
        int first = hit(s, P, facts("diamond_ore"), null, 5);
        assertEquals(EvidenceFold.NEW_SIGHTING, first & EvidenceFold.NEW_SIGHTING);
        assertEquals(100, s.sightings().get(P).rawValue());
        assertEquals("diamond_ore", s.sightings().get(P).blockId());
        int again = hit(s, P, facts("diamond_ore"), null, 9);
        assertEquals(0, again & EvidenceFold.NEW_SIGHTING);
        assertEquals(9, s.sightings().get(P).lastTick());
        assertEquals(1, s.counters().sightingsAdded);
        assertEquals(1, s.counters().sightingsUpdated);
    }

    @Test
    void reobservingASightingAsSomethingElseMarksItGone() {
        MiningAssistState s = state();
        hit(s, P, facts("iron_ore"), null, 1);
        assertTrue(s.sightings().contains(P));
        hit(s, P, facts("stone"), null, 2);
        assertFalse(s.sightings().contains(P));
    }

    @Test
    void lowValueBlocksAreStillRecordedForThePolicyToDecide() {
        MiningAssistState s = state();
        hit(s, P, facts("coal_ore"), null, 1);
        assertEquals(12, s.sightings().get(P).rawValue());
    }

    // ---- hazards --------------------------------------------------------------------------------

    @Test
    void lavaAndWaterFluidsGoToTheHazardField() {
        MiningAssistState s = state();
        int lava = hit(s, P, facts("lava"), HazardField.Kind.LAVA, 3);
        assertEquals(EvidenceFold.NEW_HAZARD, lava & EvidenceFold.NEW_HAZARD);
        hit(s, P.above(), facts("water"), HazardField.Kind.WATER, 3);
        assertTrue(s.hazards().isLava(P));
        assertEquals(HazardField.Kind.WATER, s.hazards().kindAt(P.above()));
        assertEquals(1, s.counters().lavaCells);
        assertEquals(1, s.counters().waterCells);
    }

    @Test
    void trapBlocksAreRememberedAsTraps() {
        MiningAssistState s = state();
        hit(s, P, facts("tnt"), null, 3);
        assertEquals(HazardField.Kind.TRAP, s.hazards().kindAt(P));
        assertEquals(1, s.counters().trapCells);
    }

    @Test
    void aHazardCellReobservedAsAPlainBlockIsForgotten() {
        MiningAssistState s = state();
        hit(s, P, facts("lava"), HazardField.Kind.LAVA, 3);
        hit(s, P, facts("obsidian"), null, 4);
        assertNull(s.hazards().kindAt(P), "lava that cooled to obsidian is no longer lava");
    }

    @Test
    void aStaleClearNeverErasesANewerHazardObservation() {
        MiningAssistState s = state();
        hit(s, P, facts("lava"), HazardField.Kind.LAVA, 10);
        hit(s, P, facts("stone"), null, 4);
        assertTrue(s.hazards().isLava(P));
    }

    @Test
    void waterlogNeverOverridesRememberedLava() {
        MiningAssistState s = state();
        hit(s, P, facts("lava"), HazardField.Kind.LAVA, 1);
        hit(s, P, facts("oak_slab"), HazardField.Kind.WATER, 2);
        assertTrue(s.hazards().isLava(P));
    }

    // ---- POI evidence ---------------------------------------------------------------------------

    @Test
    void nonNaturalBlocksEnterThePoiWindow() {
        MiningAssistState s = state();
        int result = hit(s, P, facts("rail"), null, 7);
        assertEquals(EvidenceFold.NEW_POI, result & EvidenceFold.NEW_POI);
        assertEquals(PoiBucket.RAIL, s.poiWindow().structuralEntries().iterator().next().bucket());
        assertEquals(1, s.counters().poiCellsAdded);
    }

    @Test
    void naturalBlocksDoNotEnterButPlainSculkEntersTheFlagOnlyWindow() {
        MiningAssistState s = state();
        hit(s, P, facts("stone"), null, 1);
        assertTrue(s.poiWindow().isEmpty());
        hit(s, P.east(), facts("sculk"), null, 1);
        assertEquals(1, s.poiWindow().flagOnlySize());
        assertEquals(0, s.poiWindow().structuralSize());
    }

    @Test
    void weakBucketsAreRecordedToo() {
        MiningAssistState s = state();
        hit(s, P, facts("torch"), null, 1);
        hit(s, P.above(), facts("cobblestone"), null, 1);
        assertEquals(2, s.poiWindow().structuralSize());
    }

    @Test
    void botPlacedCellsNeverBecomePoiEvidenceAndEvictEarlierEntries() {
        MiningAssistState s = state();
        hit(s, P, facts("torch"), null, 1);
        assertEquals(1, s.poiWindow().structuralSize());
        LongPredicate placed = packed -> packed == P.asLong();
        int result = EvidenceFold.foldHit(s, P, facts("torch"), null, placed, 2);
        assertEquals(0, result & EvidenceFold.NEW_POI);
        assertEquals(0, s.poiWindow().structuralSize());
        EvidenceFold.foldHit(s, P.above(), facts("torch"), null, placed, 3);
        assertEquals(1, s.poiWindow().structuralSize());
    }

    @Test
    void thePlacedLedgerIsOnlyConsultedForCellsThatWouldBeEvidence() {
        MiningAssistState s = state();
        int[] calls = {0};
        LongPredicate counting = packed -> {
            calls[0]++;
            return false;
        };
        EvidenceFold.foldHit(s, P, facts("stone"), null, counting, 1);
        EvidenceFold.foldHit(s, P.above(), facts("diamond_ore"), null, counting, 1);
        assertEquals(0, calls[0]);
        EvidenceFold.foldHit(s, P.below(), facts("rail"), null, counting, 1);
        assertEquals(1, calls[0]);
    }

    @Test
    void aPoiCellReobservedAsNaturalIsForgotten() {
        MiningAssistState s = state();
        hit(s, P, facts("oak_planks"), null, 1);
        hit(s, P, facts("stone"), null, 2);
        assertTrue(s.poiWindow().isEmpty());
    }

    // ---- decor ----------------------------------------------------------------------------------

    @Test
    void decorHitsFeedOnlyThePoiWindowMarkedViaDecor() {
        MiningAssistState s = state();
        assertTrue(EvidenceFold.foldDecor(s, P, facts("rail"), NOT_PLACED, 4));
        PoiEvidenceWindow.Entry entry = s.poiWindow().structuralEntries().iterator().next();
        assertTrue(entry.viaDecor());
        assertEquals(1, s.counters().decorEvidence);
        assertTrue(s.hazards().isEmpty());
        assertTrue(s.sightings().isEmpty());
    }

    @Test
    void decorNeverClearsAnything() {
        MiningAssistState s = state();
        hit(s, P, facts("oak_planks"), null, 1);
        hit(s, P.above(), facts("lava"), HazardField.Kind.LAVA, 1);
        assertFalse(EvidenceFold.foldDecor(s, P, facts("stone"), NOT_PLACED, 2));
        assertFalse(EvidenceFold.foldDecor(s, P.above(), facts("stone"), NOT_PLACED, 2));
        assertEquals(1, s.poiWindow().structuralSize());
        assertTrue(s.hazards().isLava(P.above()));
    }

    @Test
    void decorSkipsBotPlacedCells() {
        MiningAssistState s = state();
        assertFalse(EvidenceFold.foldDecor(s, P, facts("torch"), packed -> true, 1));
        assertTrue(s.poiWindow().isEmpty());
    }

    // ---- combined -------------------------------------------------------------------------------

    @Test
    void oneBlockCanBeAValuableAndPoiEvidenceAtOnce() {
        MiningAssistState s = state();
        int result = hit(s, P, facts("amethyst_cluster"), null, 1);
        assertEquals(EvidenceFold.NEW_SIGHTING | EvidenceFold.NEW_POI, result);
        assertNotNull(s.sightings().get(P));
        assertEquals(1, s.poiWindow().structuralSize());
    }

    @Test
    void mandatoryBlocksKeepTheirFlagsInTheWindow() {
        MiningAssistState s = state();
        hit(s, P, facts("sculk_shrieker"), null, 1);
        PoiEvidenceWindow.Entry entry = s.poiWindow().structuralEntries().iterator().next();
        assertEquals(PoiBucket.SCULK_STRUCT, entry.bucket());
        assertTrue(PoiEvidenceFlags.has(entry.flags(), PoiEvidenceFlags.SCULK_SHRIEKER));
    }
}
