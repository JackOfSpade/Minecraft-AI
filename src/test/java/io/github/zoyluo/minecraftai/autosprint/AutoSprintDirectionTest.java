package io.github.zoyluo.minecraftai.autosprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AutoSprintDirectionTest {
    @Test
    void cardinalsUseMinecraftWorldAxes() {
        assertDirection(AutoSprintDirection.NORTH, 0, -1);
        assertDirection(AutoSprintDirection.SOUTH, 0, 1);
        assertDirection(AutoSprintDirection.EAST, 1, 0);
        assertDirection(AutoSprintDirection.WEST, -1, 0);
    }

    private static void assertDirection(AutoSprintDirection direction, int x, int z) {
        assertEquals(x, direction.stepX());
        assertEquals(z, direction.stepZ());
    }
}
