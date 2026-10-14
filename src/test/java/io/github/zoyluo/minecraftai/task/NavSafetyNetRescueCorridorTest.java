package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure boundaries for the one-cell current allowance of an admitted water-rescue stroke. */
final class NavSafetyNetRescueCorridorTest {
    @Test
    void horizontalStrokeMayTolerateOneReprovedCurrentCell() {
        BlockPos origin = new BlockPos(10, 64, 10);
        BlockPos destination = origin.east();

        assertTrue(NavSafetyNet.withinRescueStepCurrentEnvelope(origin.east(2), origin, destination));
    }

    @Test
    void verticalStrokeNeverTreatsAnExternalSecondYCellAsCurrentDrift() {
        BlockPos origin = new BlockPos(10, 64, 10);
        BlockPos destination = origin.above();

        assertFalse(NavSafetyNet.withinRescueStepCurrentEnvelope(destination.above(), origin, destination));
    }
}
