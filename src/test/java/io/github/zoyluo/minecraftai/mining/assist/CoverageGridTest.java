package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageGridTest {
    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Test
    void markCoversTheWholeFourByFourByFourVoxel() {
        CoverageGrid grid = new CoverageGrid();
        grid.mark(at(0, 64, 0));

        assertTrue(grid.isMarked(at(0, 64, 0)));
        assertTrue(grid.isMarked(at(3, 67, 3)));   // same voxel, far corner
        assertTrue(grid.isMarked(at(1, 65, 2)));   // same voxel, interior
        assertFalse(grid.isMarked(at(4, 64, 0)));  // next voxel over on X
        assertFalse(grid.isMarked(at(0, 68, 0)));  // next voxel over on Y
        assertFalse(grid.isMarked(at(0, 64, -1)));  // next voxel over on Z (negative side)
    }

    @Test
    void marksAroundNegativeCoordinatesUseFlooringNotTruncation() {
        CoverageGrid grid = new CoverageGrid();
        grid.mark(at(-1, 64, -1));

        assertTrue(grid.isMarked(at(-4, 64, -4)));
        assertTrue(grid.isMarked(at(-1, 64, -1)));
        assertFalse(grid.isMarked(at(0, 64, 0)));
    }

    @Test
    void markingTheSameVoxelTwiceIsIdempotentForSize() {
        CoverageGrid grid = new CoverageGrid();
        grid.mark(at(0, 64, 0));
        grid.mark(at(1, 65, 2));
        grid.mark(at(2, 66, 3));

        assertEquals(1, grid.size());
    }

    @Test
    void capEvictsTheOldestVoxelFirst() {
        CoverageGrid grid = new CoverageGrid();
        for (int i = 0; i < CoverageGrid.CAPACITY; i++) {
            grid.mark(at(i * CoverageGrid.VOXEL_SIZE, 64, 0));
        }
        assertEquals(CoverageGrid.CAPACITY, grid.size());
        assertTrue(grid.isMarked(at(0, 64, 0)));

        // One more distinct voxel pushes out the very first one marked.
        grid.mark(at(CoverageGrid.CAPACITY * CoverageGrid.VOXEL_SIZE, 64, 0));

        assertEquals(CoverageGrid.CAPACITY, grid.size());
        assertFalse(grid.isMarked(at(0, 64, 0)));
        assertTrue(grid.isMarked(at(CoverageGrid.VOXEL_SIZE, 64, 0)));
        assertTrue(grid.isMarked(at(CoverageGrid.CAPACITY * CoverageGrid.VOXEL_SIZE, 64, 0)));
    }

    @Test
    void clearForgetsEverything() {
        CoverageGrid grid = new CoverageGrid();
        grid.mark(at(0, 64, 0));
        grid.clear();

        assertEquals(0, grid.size());
        assertFalse(grid.isMarked(at(0, 64, 0)));
    }

    @Test
    void freshFractionIsOneOverAnEmptyGrid() {
        CoverageGrid grid = new CoverageGrid();
        assertEquals(1.0D, grid.freshFraction(at(0, 64, 0), Direction.NORTH, 48, 2, 6));
    }

    @Test
    void freshFractionNonPositiveLengthIsFullyFresh() {
        CoverageGrid grid = new CoverageGrid();
        grid.mark(at(0, 64, 0));
        assertEquals(1.0D, grid.freshFraction(at(0, 64, 0), Direction.NORTH, 0, 2, 6));
        assertEquals(1.0D, grid.freshFraction(at(0, 64, 0), Direction.NORTH, -5, 2, 6));
    }

    @Test
    void freshFractionDropsToZeroWhenTheWholeCorridorIsMarked() {
        // NORTH: dz = -1, dx = 0; NORTH.rotateYClockwise() = EAST (dx = +1), the "side" axis.
        CoverageGrid grid = new CoverageGrid();
        BlockPos origin = at(0, 64, 0);
        int length = 16;
        int yBand = 2;
        int halfWidth = 6;
        for (int along = 0; along < length; along += CoverageGrid.VOXEL_SIZE) {
            for (int side = -halfWidth; side <= halfWidth; side += CoverageGrid.VOXEL_SIZE) {
                for (int up = -yBand; up <= yBand; up++) {
                    grid.mark(origin.add(side, up, -along));
                }
            }
        }
        assertEquals(0.0D, grid.freshFraction(origin, Direction.NORTH, length, yBand, halfWidth));
    }

    @Test
    void freshFractionIsPartialWhenOnlyPartOfTheCorridorIsMarked() {
        CoverageGrid grid = new CoverageGrid();
        BlockPos origin = at(0, 64, 0);
        // Mark only the near half of a NORTH-facing corridor.
        for (int side = -6; side <= 6; side += CoverageGrid.VOXEL_SIZE) {
            for (int up = -2; up <= 2; up++) {
                grid.mark(origin.add(side, up, 0));
            }
        }
        double fraction = grid.freshFraction(origin, Direction.NORTH, 32, 2, 6);
        assertTrue(fraction > 0.0D && fraction < 1.0D,
                "expected a strictly partial fresh fraction, got " + fraction);
    }
}
