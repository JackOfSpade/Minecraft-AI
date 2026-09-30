package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.mining.assist.InventoryHeadroom.Estimate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins mining-assist design 4.6 steps 7-8 (P1 contract section G.1): capacity, unknown drops, yield, tool wear. */
class InventoryHeadroomTest {
    // ---- capacityOk ----

    @Test
    void capacityOkMergesIntoPartialStacks() {
        assertTrue(InventoryHeadroom.capacityOk(3, 1, 1, 3));
        assertFalse(InventoryHeadroom.capacityOk(3, 1, 2, 3));
    }

    @Test
    void capacityOkNeedsExactlyOneSlot() {
        assertTrue(InventoryHeadroom.capacityOk(4, 0, 1, 3));
        assertFalse(InventoryHeadroom.capacityOk(3, 0, 1, 3));
    }

    @Test
    void capacityOkASlotIsNeededEvenForASingleOverflow() {
        assertFalse(InventoryHeadroom.capacityOk(3, 0, 9, 3));
    }

    @Test
    void capacityOkTwoSlotsForAYieldJustOverOneStack() {
        assertTrue(InventoryHeadroom.capacityOk(5, 0, 65, 3));
        assertFalse(InventoryHeadroom.capacityOk(4, 0, 65, 3));
    }

    @Test
    void capacityOkReserveZeroAndNegativeInputsClampToZero() {
        assertTrue(InventoryHeadroom.capacityOk(0, 0, 0, 0));
        assertTrue(InventoryHeadroom.capacityOk(-1, 0, 0, 0));
        assertTrue(InventoryHeadroom.capacityOk(3, 1, 1, -5));
        assertFalse(InventoryHeadroom.capacityOk(0, 0, 1, 0));
    }

    // ---- unknownDropOk ----

    @Test
    void unknownDropOkNeedsAtLeastThreeOrTheReserve() {
        assertFalse(InventoryHeadroom.unknownDropOk(2, 3));
        assertTrue(InventoryHeadroom.unknownDropOk(3, 3));
        assertFalse(InventoryHeadroom.unknownDropOk(4, 5));
        assertTrue(InventoryHeadroom.unknownDropOk(3, 1));
        assertFalse(InventoryHeadroom.unknownDropOk(2, 1));
    }

    // ---- estimate ----

    @Test
    void estimateKnownYields() {
        assertEquals(new Estimate(9, true), InventoryHeadroom.estimate("deepslate_lapis_ore"));
        assertEquals(new Estimate(5, true), InventoryHeadroom.estimate("minecraft:redstone_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("raw_iron_block"));
        assertEquals(new Estimate(5, true), InventoryHeadroom.estimate("copper_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("coal_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("iron_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("gold_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("diamond_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("emerald_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("nether_quartz_ore"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("ancient_debris"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("raw_gold_block"));
        assertEquals(new Estimate(1, true), InventoryHeadroom.estimate("raw_copper_block"));
        assertEquals(new Estimate(6, true), InventoryHeadroom.estimate("nether_gold_ore"));
    }

    @Test
    void estimateUnknownYields() {
        assertEquals(new Estimate(4, false), InventoryHeadroom.estimate("gilded_blackstone"));
        assertEquals(new Estimate(4, false), InventoryHeadroom.estimate("amethyst_cluster"));
        assertEquals(new Estimate(4, false), InventoryHeadroom.estimate("modid:zinc_ore"));
        assertEquals(new Estimate(4, false), InventoryHeadroom.estimate(null));
    }

    // ---- durabilityOk ----

    @Test
    void aWornToolIsNeverRefusedOnlyOneThatCannotFinishTheDetour() {
        // Use it until it breaks: a diamond pick at 38 or 10 uses left still does a one-member detour...
        assertTrue(InventoryHeadroom.durabilityOk(38, 250, 1));
        assertTrue(InventoryHeadroom.durabilityOk(10, 250, 1));
        // ...a stone pick with 26 or 5 uses left too...
        assertTrue(InventoryHeadroom.durabilityOk(26, 131, 1));
        assertTrue(InventoryHeadroom.durabilityOk(5, 131, 1));
        // ...and only a tool that would break before the last planned break is refused (members + 1 uses are needed).
        assertTrue(InventoryHeadroom.durabilityOk(2, 250, 1));
        assertFalse(InventoryHeadroom.durabilityOk(1, 250, 1));
    }

    @Test
    void durabilityOkScalesWithPlannedMembers() {
        assertTrue(InventoryHeadroom.durabilityOk(13, 131, 12));
        assertFalse(InventoryHeadroom.durabilityOk(12, 131, 12));
    }

    @Test
    void durabilityOkNonDamageableToolAlwaysFine() {
        assertTrue(InventoryHeadroom.durabilityOk(0, 0, 100));
        assertTrue(InventoryHeadroom.durabilityOk(-5, -1, 100));
    }
}
