package io.github.zoyluo.minecraftai.action;

import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The step height WalkToController reads off the block ahead: the half it walks into first, not the block's highest box. */
class WalkToControllerEntryTopTest {
    private static final double EPS = 1.0E-9;
    /** A stair ascending toward +X: the low step (bottom half) everywhere, the high step on the +X half only. */
    private static final List<AABB> STAIR_UP_EAST = List.of(new AABB(0, 0, 0, 1, 0.5, 1), new AABB(0.5, 0.5, 0, 1, 1, 1));

    @Test
    void aStairSeenFromItsLowSideIsAHalfBlockStep() {
        assertEquals(0.5, WalkToController.entryTop(STAIR_UP_EAST, 1.0, 0.0), EPS);
        assertEquals(0.5, WalkToController.entryTop(STAIR_UP_EAST, 0.9, 0.3), EPS);
    }

    @Test
    void aStairSeenFromItsHighSideIsAFullBlock() {
        assertEquals(1.0, WalkToController.entryTop(STAIR_UP_EAST, -1.0, 0.0), EPS);
    }

    @Test
    void aStairSeenSidewaysIsItsFullHeight() {
        assertEquals(1.0, WalkToController.entryTop(STAIR_UP_EAST, 0.0, 1.0), EPS);
        assertEquals(1.0, WalkToController.entryTop(STAIR_UP_EAST, 0.2, -1.0), EPS);
    }

    @Test
    void fullBlocksSlabsAndFencePostsKeepTheirTop() {
        assertEquals(1.0, WalkToController.entryTop(List.of(new AABB(0, 0, 0, 1, 1, 1)), 1.0, 0.0), EPS);
        assertEquals(0.5, WalkToController.entryTop(List.of(new AABB(0, 0, 0, 1, 0.5, 1)), 0.0, -1.0), EPS);
        assertEquals(1.5, WalkToController.entryTop(List.of(new AABB(0.375, 0, 0.375, 0.625, 1.5, 0.625)), -1.0, 0.0), EPS);
    }

    @Test
    void anEmptyEntryHalfFallsBackToTheHighestBox() {
        assertEquals(1.0, WalkToController.entryTop(List.of(new AABB(0.5, 0, 0, 1, 1, 1)), 1.0, 0.0), EPS);
    }
}
