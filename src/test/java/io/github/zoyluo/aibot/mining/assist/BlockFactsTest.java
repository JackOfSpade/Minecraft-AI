package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import static io.github.zoyluo.aibot.mining.assist.AssistTestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure derivation behind BlockFactsAdapter (the adapter only adds the registry lookup and a cache). */
class BlockFactsTest {
    @Test
    void oresAreValuableButNaturalAndNotEvidence() {
        BlockFacts diamond = facts("diamond_ore");
        assertEquals(100, diamond.rawValue());
        assertTrue(diamond.valuable());
        assertTrue(diamond.natural());
        assertFalse(diamond.evidence());
        assertEquals("diamond_ore", diamond.ledgerId());
        assertEquals("minecraft:diamond_ore", diamond.id());
    }

    @Test
    void amethystClusterIsBothAValuableAndGeodeEvidence() {
        BlockFacts cluster = facts("amethyst_cluster");
        assertEquals(6, cluster.rawValue());
        assertEquals(PoiBucket.FOSSIL_GEODE, cluster.bucket());
        assertTrue(cluster.evidence());
    }

    @Test
    void buildingBlocksAreEvidenceWithoutValue() {
        BlockFacts planks = facts("oak_planks");
        assertEquals(PoiBucket.WOOD_BUILD, planks.bucket());
        assertTrue(planks.evidence());
        assertFalse(planks.valuable());
        assertEquals(PoiBucket.RAIL, facts("rail").bucket());
        assertEquals(PoiBucket.WEB, facts("cobweb").bucket());
        assertEquals(PoiBucket.LIGHT_DRESSING, facts("torch").bucket());
        assertEquals(PoiBucket.COBBLE, facts("cobblestone").bucket());
    }

    @Test
    void plainSculkIsNaturalButStillEvidenceThroughItsFlag() {
        BlockFacts sculk = facts("sculk");
        assertTrue(sculk.natural());
        assertTrue(sculk.evidence());
        assertTrue(PoiEvidenceFlags.has(sculk.poiFlags(), PoiEvidenceFlags.SCULK_FAMILY));
    }

    @Test
    void trapsAreFlaggedAndOrdinaryBlocksAreNot() {
        assertTrue(facts("tnt").trap());
        assertTrue(facts("dispenser").trap());
        assertTrue(facts("stone_pressure_plate").trap());
        assertFalse(facts("stone").trap());
        assertFalse(facts("dropper").trap());
    }

    @Test
    void naturalStoneAndAirAreNotEvidenceAndNotValuable() {
        for (String path : new String[] {"stone", "deepslate", "dirt", "gravel", "air", "cave_air", "water", "lava"}) {
            BlockFacts f = facts(path);
            assertTrue(f.natural(), path);
            assertFalse(f.evidence(), path);
            assertFalse(f.valuable(), path);
        }
    }

    @Test
    void blockEntityBlocksFallBackToContainer() {
        BlockFacts chest = AssistTestSupport.chest();
        assertEquals(PoiBucket.CONTAINER, chest.bucket());
        assertTrue(chest.hasBlockEntity());
        assertEquals(PoiBucket.CONTAINER,
                BlockFacts.derive("minecraft", "some_future_block", true, false, false).bucket());
    }

    @Test
    void lushCavesNaturalTagOnlyHidesLogsAndLeaves() {
        assertEquals(PoiBucket.UNCLASSIFIED, BlockFacts.derive("minecraft", "oak_log", false, false, false).bucket());
        assertEquals(PoiBucket.NATURAL, BlockFacts.derive("minecraft", "oak_log", false, false, true).bucket());
        assertEquals(PoiBucket.NATURAL, BlockFacts.derive("minecraft", "azalea_leaves", false, false, true).bucket());
        assertEquals(PoiBucket.SPAWNER, BlockFacts.derive("minecraft", "spawner", true, false, true).bucket(),
                "the tag can never hide a structure marker");
        assertEquals(PoiBucket.WOOD_BUILD, BlockFacts.derive("minecraft", "oak_planks", false, false, true).bucket());
    }

    @Test
    void moddedBlocksAreModdedUnlessTheyAreNaturalTerrain() {
        assertEquals(PoiBucket.MODDED, BlockFacts.derive("somemod", "weird_lamp", false, false, false).bucket());
        assertEquals(PoiBucket.NATURAL, BlockFacts.derive("somemod", "weird_lamp", false, false, true).bucket());
        BlockFacts moddedOre = facts("create", "zinc_ore");
        assertEquals(ValueTable.UNKNOWN_ORE_VALUE, moddedOre.rawValue());
        assertEquals("create:zinc_ore", moddedOre.ledgerId());
    }

    @Test
    void negativeValueIsClampedAndFallingIsCarried() {
        BlockFacts f = new BlockFacts("minecraft", "gravel", -5, PoiBucket.NATURAL, false, false, true, 0);
        assertEquals(0, f.rawValue());
        assertTrue(f.falling());
        assertFalse(BlockFacts.plain("stone").evidence());
    }
}
