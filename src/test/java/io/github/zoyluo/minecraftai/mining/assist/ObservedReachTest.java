package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static io.github.zoyluo.minecraftai.mining.assist.ObservedReach.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.mining.assist.ObservedReach.Status;
import net.minecraft.core.BlockPos;

/**
 * Pins the design 4.3 reachability rules of {@link ObservedReach} (P1 contract section B.1, G.3): the
 * optimistic-on-UNKNOWN graph, the four stand/move rules, INCONCLUSIVE vs UNREACHABLE, and the node cap.
 */
class ObservedReachTest {

    // ---- optimism on an all-UNKNOWN window ----

    @Test
    void allUnknownWindowIsOptimisticAndCostsOnePerHop() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);

        ObservedReach.Result straight = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(5, 0, 0));
        assertEquals(Status.REACHABLE, straight.status());
        assertEquals(5, straight.length());

        ObservedReach.Result diagonal = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(5, 0, 3));
        assertEquals(Status.REACHABLE, diagonal.status());
        assertEquals(8, diagonal.length());
    }

    @Test
    void fromEqualsToIsReachableWithLengthZero() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(3, 3, 3), new BlockPos(3, 3, 3));
        assertEquals(Status.REACHABLE, r.status());
        assertEquals(0, r.length());
    }

    // ---- stand validity: feet/head passable, floor SOLID/UNKNOWN ----

    @Test
    void observedSolidAtOwnFeetBlocksTheStand() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markSolid(1, 0, 0);
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    @Test
    void observedSolidAtHeadBlocksTheStand() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markSolid(1, 1, 0);
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    @Test
    void observedFluidAtFeetBlocksTheStand() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markFluid(1, 0, 0);
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    @Test
    void observedFluidAtHeadBlocksTheStand() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markFluid(1, 1, 0);
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    @Test
    void observedFluidAtTheFloorBlocksTheStand() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markFluid(1, -1, 0);
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    @Test
    void fromWithSolidInItsOwnCellsIsUnreachable() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markSolid(0, 0, 0); // the start's own feet cell is solid: not a valid stand
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(5, 0, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    @Test
    void startEnclosedBySolidGivesUnreachableQuickly() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        // Seal off every horizontal neighbour of the start at every height a step-up or a drop could reach;
        // nothing can ever move away from it.
        for (int y = -4; y <= 4; y++) {
            occ.markSolid(1, y, 0);
            occ.markSolid(-1, y, 0);
            occ.markSolid(0, y, 1);
            occ.markSolid(0, y, -1);
        }
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(10, 0, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    // ---- window / null handling ----

    @Test
    void nullOccupancyIsInconclusive() {
        ObservedReach.Result r = ObservedReach.search(null, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertEquals(Status.INCONCLUSIVE, r.status());
        assertTrue(r.notRuledOut());
    }

    @Test
    void anEndpointOutsideTheWindowIsInconclusive() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        // Window is centre +/- 32; 40 is outside it.
        ObservedReach.Result fromOutside = ObservedReach.search(occ, new BlockPos(40, 0, 0), new BlockPos(0, 0, 0));
        assertEquals(Status.INCONCLUSIVE, fromOutside.status());

        ObservedReach.Result toOutside = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(40, 0, 0));
        assertEquals(Status.INCONCLUSIVE, toOutside.status());
    }

    // ---- drops of 1, 2, 3 allowed; 4 is never offered as a single move ----

    @Test
    void aPitFloorIsNotAStandButDropsOfOneTwoThreeAreOffered() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markAir(1, -1, 0); // the floor under (1,0,0) is open: a pit

        ObservedReach.Result atZero = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        assertEquals(Status.UNREACHABLE, atZero.status(), "a pit floor is not a stand");

        ObservedReach.Result drop1 = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, -1, 0));
        assertEquals(Status.REACHABLE, drop1.status());
        assertEquals(1, drop1.length());

        ObservedReach.Result drop2 = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, -2, 0));
        assertEquals(Status.REACHABLE, drop2.status());
        assertEquals(1, drop2.length());

        ObservedReach.Result drop3 = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, -3, 0));
        assertEquals(Status.REACHABLE, drop3.status());
        assertEquals(1, drop3.length());
    }

    @Test
    void aDropOfFourIsNeverOfferedAsASingleMove() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        // Two-column shaft, fully sealed on every other side: column (0,0) only stands at y in {0,1},
        // column (1,0) only stands at y in [-4, 1] (with a pit at y=-1). Nothing outside those two
        // columns and those two ranges can ever be a valid stand, so a bounce back through column
        // (0,0) at some other height (the trick that WOULD reach y=-4 in two hops) is impossible.
        fenceTwoColumnShaft(occ, 0, 1, -4, 1);
        occ.markAir(1, -1, 0);

        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, -4, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    // ---- step up 1 needs headroom, and 2 is never offered as a single move ----

    @Test
    void stepUpOneSucceedsWhenOpen() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 1, 0));
        assertEquals(Status.REACHABLE, r.status());
        assertEquals(1, r.length());
    }

    @Test
    void stepUpNeedsHeadroomAboveTheStartingStand() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        // Seal the start into a box whose only opening is the walk-only neighbour at height 0; the
        // headroom cell above the start is solid, so the step-up move has nowhere to come from.
        fenceTwoColumnShaft(occ, 0, 0, 0, 1);
        occ.markSolid(0, 2, 0);

        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 1, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    @Test
    void stepUpOfTwoIsNeverOfferedAsASingleMove() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        // Column (0,0) only stands at y in {0,1} (so a bounce back up cannot reach a launch point for
        // a second step); column (1,0) is open from 0 to 3 (room for the target's own head), reachable
        // only through the single entry at height 0, which is a dead end.
        fenceTwoColumnShaft(occ, 0, 1, 0, 3);

        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(1, 2, 0));
        assertEquals(Status.UNREACHABLE, r.status());
    }

    // ---- a maze with a forced detour ----

    @Test
    void mazeWithAVerticalWallForcesTheShortestDetour() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        // A full vertical wall directly east of the start blocks every height; the only way around it
        // is two blocks out and back through z, giving length 4 instead of the direct length 2.
        for (int y = -3; y <= 3; y++) {
            occ.markSolid(1, y, 0);
        }
        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(2, 0, 0));
        assertEquals(Status.REACHABLE, r.status());
        assertEquals(4, r.length());
    }

    // ---- determinism ----

    @Test
    void searchIsDeterministic() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        for (int y = -3; y <= 3; y++) {
            occ.markSolid(1, y, 0);
        }
        ObservedReach.Result first = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(2, 0, 0));
        ObservedReach.Result second = ObservedReach.search(occ, new BlockPos(0, 0, 0), new BlockPos(2, 0, 0));
        assertEquals(first, second);
    }

    // ---- node cap ----

    @Test
    void aLongWindingCorridorExceedsTheNodeCapEvenThoughItIsActuallyReachable() {
        // A one-cell-wide corridor that snakes back and forth (walls between consecutive rows except
        // for a single connector cell) so the ONLY path is far longer than a straight-line distance
        // would suggest, and its true length (about 1858 moves) exceeds NODE_CAP. There is no shortcut
        // (every other direction is walled), so no search strategy can do better than exhausting the
        // cap here: this is the "far target" case of the class comment, made airtight against a
        // heuristic-guided search finding a shortcut that a fully open field would have offered.
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        int rows = 30;
        int xMin = -30;
        int xMax = 30;
        int zStart = -30;

        for (int x = xMin - 1; x <= xMax + 1; x++) {
            for (int z = zStart - 1; z <= zStart + 2 * (rows - 1) + 1; z++) {
                for (int y = -2; y <= 3; y++) {
                    occ.markSolid(x, y, z);
                }
            }
        }

        int entryX = xMin;
        int targetX = xMin;
        int targetZ = zStart;
        for (int r = 0; r < rows; r++) {
            int z = zStart + 2 * r;
            for (int x = xMin; x <= xMax; x++) {
                carveOpenColumn(occ, x, z);
            }
            int exitX = entryX == xMin ? xMax : xMin;
            if (r < rows - 1) {
                carveOpenColumn(occ, exitX, z + 1);
                entryX = exitX;
            } else {
                targetX = exitX;
                targetZ = z;
            }
        }

        ObservedReach.Result r = ObservedReach.search(occ, new BlockPos(xMin, 0, zStart),
                new BlockPos(targetX, 0, targetZ));
        assertEquals(Status.INCONCLUSIVE, r.status());
        assertEquals(ObservedReach.NO_LENGTH, r.length());
        assertTrue(r.notRuledOut());
    }

    /** Opens a stand's feet and head cell (floor stays whatever the surrounding fence already set). */
    private static void carveOpenColumn(ObservedOccupancy occ, int x, int z) {
        occ.set(x, 0, z, ObservedOccupancy.UNKNOWN);
        occ.set(x, 1, z, ObservedOccupancy.UNKNOWN);
    }

    @Test
    void notRuledOutIsFalseOnlyForUnreachable() {
        assertFalse(ObservedReach.Result.UNREACHABLE.notRuledOut());
        assertTrue(ObservedReach.Result.INCONCLUSIVE.notRuledOut());
        assertTrue(ObservedReach.Result.reachable(3).notRuledOut());
    }

    /**
     * Seals a small local box (x in [-1,2], z in [-1,1], y in [-8,8]) so that only two adjacent
     * columns, {@code (0,0)} and {@code (1,0)}, have any valid stand at all, each restricted to the
     * given inclusive y range; every other cell in the box becomes SOLID. This is the only way to make
     * an UNREACHABLE result for a "the move type is not offered" case airtight: an unconfined open
     * field always has some multi-hop bounce path around a single missing move.
     */
    private static void fenceTwoColumnShaft(ObservedOccupancy occ, int col0LoY, int col0HiY,
                                            int col1LoY, int col1HiY) {
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -8; y <= 8; y++) {
                    boolean col0Open = x == 0 && z == 0 && y >= col0LoY && y <= col0HiY;
                    boolean col1Open = x == 1 && z == 0 && y >= col1LoY && y <= col1HiY;
                    if (!col0Open && !col1Open) {
                        occ.markSolid(x, y, z);
                    }
                }
            }
        }
    }
}
