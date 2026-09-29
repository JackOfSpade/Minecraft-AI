package io.github.zoyluo.minecraftai.pathfinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

class PathExecutorArrivalTest {
    @Test
    void horizontalToleranceNeverSkipsARequiredElevationChange() {
        BlockPos current = new BlockPos(68, 231, -1);

        assertTrue(PathExecutor.arrivedAt(current, current));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(67, 231, -1)));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(68, 232, -1)));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(67, 232, -1)));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(67, 233, 0)));
    }
}
