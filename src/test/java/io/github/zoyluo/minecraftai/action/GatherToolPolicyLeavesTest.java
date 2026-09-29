package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tool policy for leaves (GatherToolPolicy.Leaves): shears are only mandatory when the leaf block is wanted. */
final class GatherToolPolicyLeavesTest {
    @Test
    void gatheringLeafBlocksAlwaysNeedsShears() {
        assertEquals(GatherToolPolicy.Category.SHEARS, GatherToolPolicy.Leaves.category(true, false, false));
        assertEquals(GatherToolPolicy.Category.SHEARS, GatherToolPolicy.Leaves.category(true, false, true));
        assertEquals(GatherToolPolicy.Category.SHEARS, GatherToolPolicy.Leaves.category(true, true, true));
    }

    @Test
    void breakingLeavesForACountUsesShearsThenHoeAndNeverDemandsATool() {
        assertEquals(GatherToolPolicy.Category.SHEARS, GatherToolPolicy.Leaves.category(false, true, true));
        assertEquals(GatherToolPolicy.Category.SHEARS, GatherToolPolicy.Leaves.category(false, true, false));
        assertEquals(GatherToolPolicy.Category.HOE, GatherToolPolicy.Leaves.category(false, false, true));
        // A bare bot must not fail "break 32 leaves" with missing_tool:shears.
        assertEquals(GatherToolPolicy.Category.NONE, GatherToolPolicy.Leaves.category(false, false, false));
    }
}
