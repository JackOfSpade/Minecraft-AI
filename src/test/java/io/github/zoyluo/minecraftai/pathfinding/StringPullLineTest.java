package io.github.zoyluo.minecraftai.pathfinding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The string-pull line must enter every column the centre line crosses (no sampling gaps). */
final class StringPullLineTest {
    private static List<String> columns(int fx, int fz, int tx, int tz) {
        List<String> out = new ArrayList<>();
        for (StringPullLine.Cell cell : StringPullLine.cells(fx, fz, tx, tz)) {
            out.add(cell.x() + "," + cell.z() + (cell.corner() ? "!" : ""));
        }
        return out;
    }

    @Test
    void aShallowSlopeEntersTheColumnTheOldTwoSamplesPerBlockSkipped() {
        // (-1,7) -> (1,6): the line leaves column (0,7) and dips through (0,6) before reaching (1,6).
        // The old sampler landed on (0,7) and (1,6) only and never saw the water-side column (0,6).
        assertEquals(List.of("0,7", "0,6", "1,6"), columns(-1, 7, 1, 6));
    }

    @Test
    void anExactDiagonalEntersThroughACornerAndNamesTheBrushedColumns() {
        assertEquals(List.of("1,1!"), columns(0, 0, 1, 1));
        StringPullLine.Cell cell = StringPullLine.cells(0, 0, 1, 1).get(0);
        assertTrue(cell.corner());
        assertArrayEquals(new int[]{1, 0, 0, 1}, cell.sideXz());
        assertEquals(List.of("1,1!", "2,2!", "3,3!"), columns(0, 0, 3, 3));
    }

    @Test
    void straightAndReversedLinesVisitEveryColumnInOrder() {
        assertEquals(List.of("1,0", "2,0", "3,0"), columns(0, 0, 3, 0));
        assertEquals(List.of("0,-1", "0,-2"), columns(0, 0, 0, -2));
        assertEquals(List.of("-1,0", "-2,0"), columns(0, 0, -2, 0));
        assertTrue(columns(4, 4, 4, 4).isEmpty());
    }

    @Test
    void everyStepMovesOneColumnAndTheLineEndsOnTheTarget() {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                List<StringPullLine.Cell> cells = StringPullLine.cells(10, 20, 10 + dx, 20 + dz);
                int px = 10;
                int pz = 20;
                double lastFraction = 0.0D;
                for (StringPullLine.Cell cell : cells) {
                    int stepX = Math.abs(cell.x() - px);
                    int stepZ = Math.abs(cell.z() - pz);
                    assertTrue(stepX <= 1 && stepZ <= 1 && stepX + stepZ >= 1, dx + "," + dz);
                    assertEquals(stepX == 1 && stepZ == 1, cell.corner(), dx + "," + dz);
                    assertTrue(cell.fraction() >= lastFraction && cell.fraction() <= 1.0D, dx + "," + dz);
                    lastFraction = cell.fraction();
                    px = cell.x();
                    pz = cell.z();
                }
                assertEquals(10 + dx, px);
                assertEquals(20 + dz, pz);
                assertFalse(cells.isEmpty() && (dx != 0 || dz != 0));
            }
        }
    }

    @Test
    void aSteepLineNeverSkipsAColumnItsCentreCrosses() {
        // (0,0) -> (1,3): centre line x = 0.5 + t, z = 0.5 + 3t; it crosses x = 1.0 and z = 2.0 together at t = 0.5.
        assertEquals(List.of("0,1", "1,2!", "1,3"), columns(0, 0, 1, 3));
    }

    @Test
    void theAllocationFreeTraversalVisitsTheSameColumnsAndCanStopEarly() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                List<String> visited = new ArrayList<>();
                boolean all = StringPullLine.traverse(3, 4, 3 + dx, 4 + dz, (x, z, fraction, corner, px, pz) -> {
                    visited.add(x + "," + z + (corner ? "!" : ""));
                    return true;
                });
                assertTrue(all);
                assertEquals(columns(3, 4, 3 + dx, 4 + dz), visited, dx + "," + dz);
            }
        }
        // A corner step reports the column it left, so the brushed neighbours are (x, previousZ) and (previousX, z).
        int[] seen = new int[4];
        StringPullLine.traverse(0, 0, 1, 1, (x, z, fraction, corner, px, pz) -> {
            seen[0] = x;
            seen[1] = pz;
            seen[2] = px;
            seen[3] = z;
            return true;
        });
        assertArrayEquals(new int[]{1, 0, 0, 1}, seen);
        int[] count = {0};
        assertFalse(StringPullLine.traverse(0, 0, 5, 0, (x, z, fraction, corner, px, pz) -> ++count[0] < 2));
        assertEquals(2, count[0]);
    }
}
