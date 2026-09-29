package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure arithmetic of the log-bootstrap exception (GatherToolPolicy.Bootstrap). */
class GatherToolPolicyBootstrapTest {
    @Test
    void emptyInventoryNeedsTableAndAxeLogs() {
        // 4 (table) + 3 (axe) + 2 (sticks) = 9 planks = 3 logs.
        assertEquals(3, GatherToolPolicy.Bootstrap.logsByHand(0, 0, 0, 0, false));
    }

    @Test
    void availableTableDropsTheTableCost() {
        // 3 + 2 = 5 planks = 2 logs.
        assertEquals(2, GatherToolPolicy.Bootstrap.logsByHand(0, 0, 0, 0, true));
    }

    @Test
    void carriedLogsPlanksAndSticksReduceTheHandCount() {
        assertEquals(2, GatherToolPolicy.Bootstrap.logsByHand(1, 0, 0, 0, false));
        assertEquals(0, GatherToolPolicy.Bootstrap.logsByHand(3, 0, 0, 0, false));
        assertEquals(0, GatherToolPolicy.Bootstrap.logsByHand(0, 9, 0, 0, false));
        assertEquals(2, GatherToolPolicy.Bootstrap.logsByHand(0, 4, 0, 0, false));
        assertEquals(1, GatherToolPolicy.Bootstrap.logsByHand(0, 8, 0, 0, false));
        // Sticks already carried: no plank cost for them (4 + 3 = 7 planks -> 2 logs).
        assertEquals(2, GatherToolPolicy.Bootstrap.logsByHand(0, 0, 2, 0, false));
        assertEquals(0, GatherToolPolicy.Bootstrap.logsByHand(0, 3, 2, 0, true));
    }

    @Test
    void craftableStoneAxeNeedsNoBootstrap() {
        assertEquals(0, GatherToolPolicy.Bootstrap.logsByHand(0, 0, 2, 3, true));
        // Without a table (or sticks) a stone axe is not craftable from inventory alone.
        assertEquals(2, GatherToolPolicy.Bootstrap.logsByHand(0, 0, 2, 3, false));
    }
}
