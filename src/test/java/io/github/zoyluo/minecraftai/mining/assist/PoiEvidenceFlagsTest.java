package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiEvidenceFlagsTest {
    private static int flags(String path) {
        return PoiEvidenceFlags.compute("minecraft", path);
    }

    @Test
    void labelerPresenceFlags() {
        assertTrue(PoiEvidenceFlags.has(flags("mossy_cobblestone"), PoiEvidenceFlags.MOSSY_STONE));
        assertFalse(PoiEvidenceFlags.has(flags("mossy_stone_bricks"), PoiEvidenceFlags.MOSSY_STONE));
        assertTrue(PoiEvidenceFlags.has(flags("mossy_stone_bricks"), PoiEvidenceFlags.STONE_BRICKS));
        assertTrue(PoiEvidenceFlags.has(flags("vault"), PoiEvidenceFlags.VAULT));
        assertTrue(PoiEvidenceFlags.has(flags("iron_bars"), PoiEvidenceFlags.IRON_BARS));
        assertTrue(PoiEvidenceFlags.has(flags("nether_bricks"), PoiEvidenceFlags.NETHER_BRICKS));
        assertTrue(PoiEvidenceFlags.has(flags("blackstone"), PoiEvidenceFlags.BLACKSTONE));
        assertTrue(PoiEvidenceFlags.has(flags("polished_blackstone_bricks"), PoiEvidenceFlags.BLACKSTONE));
    }

    @Test
    void habitationMarkerIsPackedAndRecoverable() {
        assertEquals(PoiSignals.Habitation.BED, PoiEvidenceFlags.habitation(flags("red_bed")));
        assertEquals(PoiSignals.Habitation.CRAFTING_TABLE, PoiEvidenceFlags.habitation(flags("crafting_table")));
        assertEquals(PoiSignals.Habitation.FURNACE, PoiEvidenceFlags.habitation(flags("blast_furnace")));
        assertEquals(PoiSignals.Habitation.BREWING_STAND, PoiEvidenceFlags.habitation(flags("brewing_stand")));
        assertEquals(PoiSignals.Habitation.ENCHANTING_TABLE, PoiEvidenceFlags.habitation(flags("enchanting_table")));
        assertEquals(PoiSignals.Habitation.BOOKSHELF, PoiEvidenceFlags.habitation(flags("chiseled_bookshelf")));
        assertEquals(PoiSignals.Habitation.ANVIL, PoiEvidenceFlags.habitation(flags("chipped_anvil")));
        assertNull(PoiEvidenceFlags.habitation(flags("oak_planks")));
        assertNull(PoiEvidenceFlags.habitation(0));
    }

    @Test
    void everyHabitationConstantSurvivesPackingAndDoesNotDisturbTheFlagBits() {
        for (PoiSignals.Habitation habitation : PoiSignals.Habitation.values()) {
            int packed = PoiEvidenceFlags.withHabitation(PoiEvidenceFlags.BLACKSTONE | PoiEvidenceFlags.VAULT,
                    habitation);
            assertEquals(habitation, PoiEvidenceFlags.habitation(packed));
            assertEquals(PoiEvidenceFlags.BLACKSTONE | PoiEvidenceFlags.VAULT, packed & PoiEvidenceFlags.FLAG_MASK);
        }
        assertNull(PoiEvidenceFlags.habitation(PoiEvidenceFlags.withHabitation(
                PoiEvidenceFlags.withHabitation(0, PoiSignals.Habitation.ANVIL), null)));
    }

    @Test
    void ordinaryAndModdedBlocksCarryNoFlags() {
        assertEquals(0, flags("stone"));
        assertEquals(0, flags("oak_planks"));
        assertEquals(0, flags("diamond_ore"));
        assertEquals(0, PoiEvidenceFlags.compute("somemod", "sculk_shrieker"));
        assertEquals(0, PoiEvidenceFlags.compute("somemod", "crafting_table"));
        assertEquals(0, PoiEvidenceFlags.compute(null, null));
    }
}
