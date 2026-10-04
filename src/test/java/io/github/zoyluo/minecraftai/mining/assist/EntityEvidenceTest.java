package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityEvidenceTest {
    private static final double EPS = 1.0e-9D;

    @Test
    void emptyEvidenceScoresNothing() {
        EntityEvidence evidence = new EntityEvidence();
        assertEquals(0.0D, evidence.rawScore(), EPS);
        assertTrue(evidence.isEmpty());
        assertTrue(evidence.habitation().isEmpty());
    }

    @Test
    void designScoresAdd() {
        EntityEvidence evidence = new EntityEvidence();
        assertTrue(evidence.add("minecraft", "chest_minecart"));
        assertEquals(0.6D, evidence.rawScore(), EPS);
        assertTrue(evidence.add("minecraft", "villager"));
        assertTrue(evidence.add("minecraft", "pillager"));
        assertEquals(1.2D, evidence.rawScore(), EPS, "the scorer applies the group cap, not this class");
        assertEquals(3, evidence.count());
    }

    @Test
    void itemFramesAndArmorStandsScoreAndSetHabitation() {
        EntityEvidence evidence = new EntityEvidence();
        assertTrue(evidence.add("minecraft", "item_frame"));
        assertTrue(evidence.add("minecraft", "glow_item_frame"));
        assertTrue(evidence.add("minecraft", "armor_stand"));
        assertEquals(0.45D, evidence.rawScore(), EPS);
        assertTrue(evidence.habitation().contains(PoiSignals.Habitation.ITEM_FRAME));
        assertTrue(evidence.habitation().contains(PoiSignals.Habitation.ARMOR_STAND));
    }

    @Test
    void anyNumberOfModdedEntitiesAddsTheModdedBonusOnce() {
        EntityEvidence evidence = new EntityEvidence();
        assertTrue(evidence.add("alexsmobs", "crocodile"));
        assertTrue(evidence.add("alexsmobs", "grizzly_bear"));
        assertTrue(evidence.add("othermod", "thing"));
        assertEquals(PoiLexicon.ENTITY_SCORE_MODDED, evidence.rawScore(), EPS);
        evidence.add("minecraft", "villager");
        assertEquals(0.7D, evidence.rawScore(), EPS);
    }

    @Test
    void uninterestingEntitiesContributeNothing() {
        EntityEvidence evidence = new EntityEvidence();
        assertFalse(evidence.add("minecraft", "zombie"));
        assertFalse(evidence.add("minecraft", "cow"));
        assertFalse(evidence.add("minecraft", "player"));
        assertFalse(evidence.add("minecraft", "item"));
        assertFalse(evidence.add("minecraft", "bat"));
        assertTrue(evidence.isEmpty());
        assertEquals(0, evidence.count());
    }

}
