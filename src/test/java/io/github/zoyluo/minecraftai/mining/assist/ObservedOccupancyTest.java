package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SplittableRandom;

import static io.github.zoyluo.minecraftai.mining.assist.ObservedOccupancy.AIR;
import static io.github.zoyluo.minecraftai.mining.assist.ObservedOccupancy.FLUID;
import static io.github.zoyluo.minecraftai.mining.assist.ObservedOccupancy.SIZE;
import static io.github.zoyluo.minecraftai.mining.assist.ObservedOccupancy.SOLID;
import static io.github.zoyluo.minecraftai.mining.assist.ObservedOccupancy.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedOccupancyTest {
    private record P(int x, int y, int z) {
    }

    /** A non-UNKNOWN state that depends on the coordinates, so aliasing bugs show up as wrong values. */
    private static int pattern(int x, int y, int z) {
        return 1 + Math.floorMod(x * 7 + y * 13 + z * 31, 3);
    }

    private static void fillWithPattern(ObservedOccupancy occ) {
        for (int x = occ.minX(); x <= occ.maxX(); x++) {
            for (int y = occ.minY(); y <= occ.maxY(); y++) {
                for (int z = occ.minZ(); z <= occ.maxZ(); z++) {
                    assertTrue(occ.set(x, y, z, pattern(x, y, z)));
                }
            }
        }
    }

    // ---- window and defaults ----

    @Test
    void everyCellIsUnknownByDefault() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        for (int x = occ.minX(); x <= occ.maxX(); x++) {
            for (int y = occ.minY(); y <= occ.maxY(); y++) {
                for (int z = occ.minZ(); z <= occ.maxZ(); z++) {
                    assertEquals(UNKNOWN, occ.get(x, y, z));
                }
            }
        }
        assertEquals(UNKNOWN, occ.get(1000, 1000, 1000));
    }

    @Test
    void windowIsSixtyFiveCellsCentredOnTheGivenCell() {
        ObservedOccupancy occ = new ObservedOccupancy(100, -20, -300);

        assertEquals(65, SIZE);
        assertEquals(100, occ.centreX());
        assertEquals(-20, occ.centreY());
        assertEquals(-300, occ.centreZ());
        assertEquals(68, occ.minX());
        assertEquals(132, occ.maxX());
        assertEquals(-52, occ.minY());
        assertEquals(12, occ.maxY());
        assertEquals(-332, occ.minZ());
        assertEquals(-268, occ.maxZ());
        assertEquals(SIZE, occ.maxX() - occ.minX() + 1);
    }

    @Test
    void inWindowIsInclusiveAtPlusAndMinusThirtyTwoOnEveryAxis() {
        ObservedOccupancy occ = new ObservedOccupancy(10, 20, 30);

        assertTrue(occ.inWindow(10, 20, 30));
        assertTrue(occ.inWindow(42, 52, 62));
        assertTrue(occ.inWindow(-22, -12, -2));
        assertFalse(occ.inWindow(43, 20, 30));
        assertFalse(occ.inWindow(-23, 20, 30));
        assertFalse(occ.inWindow(10, 53, 30));
        assertFalse(occ.inWindow(10, -13, 30));
        assertFalse(occ.inWindow(10, 20, 63));
        assertFalse(occ.inWindow(10, 20, -3));
        assertTrue(occ.inWindow(new BlockPos(42, 52, 62)));
        assertFalse(occ.inWindow(new BlockPos(43, 52, 62)));
    }

    @Test
    void storageIsAboutSixtyNineKilobytes() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        assertEquals(274_625, ObservedOccupancy.CELL_COUNT);
        assertEquals(8583 * 8, occ.storageBytes());
        assertTrue(occ.storageBytes() >= 68_000 && occ.storageBytes() <= 70_000);
    }

    // ---- get / set ----

    @Test
    void setThenGetRoundTripsEveryState() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 64, 0);
        assertTrue(occ.set(3, 60, -7, SOLID));
        assertTrue(occ.set(4, 60, -7, FLUID));
        assertTrue(occ.set(5, 60, -7, AIR));
        assertTrue(occ.set(6, 60, -7, UNKNOWN));

        assertEquals(SOLID, occ.get(3, 60, -7));
        assertEquals(FLUID, occ.get(4, 60, -7));
        assertEquals(AIR, occ.get(5, 60, -7));
        assertEquals(UNKNOWN, occ.get(6, 60, -7));
    }

    @Test
    void writesOverwriteAndNeighboursAreUntouched() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.set(1, 2, 3, SOLID);
        occ.set(1, 2, 3, AIR);
        occ.set(1, 2, 4, FLUID);

        assertEquals(AIR, occ.get(1, 2, 3));
        assertEquals(FLUID, occ.get(1, 2, 4));
        assertEquals(UNKNOWN, occ.get(1, 2, 2));
        assertEquals(UNKNOWN, occ.get(0, 2, 3));
        assertEquals(UNKNOWN, occ.get(1, 1, 3));
        assertEquals(UNKNOWN, occ.get(2, 2, 3));
    }

    @Test
    void adjacentCellsPackedInTheSameWordDoNotInterfere() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        // 64 consecutive cells along z cover at least two 64-bit words
        for (int z = -32; z <= 31; z++) {
            occ.set(0, 0, z, 1 + Math.floorMod(z, 3));
        }
        for (int z = -32; z <= 31; z++) {
            assertEquals(1 + Math.floorMod(z, 3), occ.get(0, 0, z));
        }
    }

    @Test
    void markHelpersWriteTheirState() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        assertTrue(occ.markAir(1, 1, 1));
        assertTrue(occ.markSolid(2, 1, 1));
        assertTrue(occ.markFluid(3, 1, 1));
        assertEquals(AIR, occ.get(1, 1, 1));
        assertEquals(SOLID, occ.get(2, 1, 1));
        assertEquals(FLUID, occ.get(3, 1, 1));

        assertTrue(occ.markAir(new BlockPos(-1, -1, -1)));
        assertTrue(occ.markSolid(new BlockPos(-2, -1, -1)));
        assertTrue(occ.markFluid(new BlockPos(-3, -1, -1)));
        assertEquals(AIR, occ.get(new BlockPos(-1, -1, -1)));
        assertEquals(SOLID, occ.get(new BlockPos(-2, -1, -1)));
        assertEquals(FLUID, occ.get(-3, -1, -1));
        assertTrue(occ.set(new BlockPos(5, 5, 5), SOLID));
        assertEquals(SOLID, occ.get(5, 5, 5));
    }

    @Test
    void outOfWindowReadsAreUnknownAndWritesAreIgnored() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);

        assertFalse(occ.set(33, 0, 0, SOLID));
        assertFalse(occ.set(-33, 0, 0, SOLID));
        assertFalse(occ.markAir(0, 33, 0));
        assertFalse(occ.markFluid(0, 0, -33));
        assertEquals(UNKNOWN, occ.get(33, 0, 0));
        assertEquals(UNKNOWN, occ.get(-33, 0, 0));
    }

    @Test
    void anIgnoredWriteNeverAliasesAnInWindowCell() {
        // storage is toroidal; a write one past either edge must not show up on the opposite edge
        ObservedOccupancy occ = new ObservedOccupancy(7, -3, 90);
        occ.set(occ.maxX() + 1, 0, 90, SOLID);
        occ.set(occ.minX() - 1, 0, 90, SOLID);
        occ.set(7, occ.maxY() + 1, 90, SOLID);
        occ.set(7, occ.minY() - 1, 90, SOLID);
        occ.set(7, 0, occ.maxZ() + 1, SOLID);
        occ.set(7, 0, occ.minZ() - 1, SOLID);
        occ.set(occ.maxX() + SIZE, 0, 90, SOLID);

        for (int x = occ.minX(); x <= occ.maxX(); x++) {
            for (int y = occ.minY(); y <= occ.maxY(); y++) {
                for (int z = occ.minZ(); z <= occ.maxZ(); z++) {
                    assertEquals(UNKNOWN, occ.get(x, y, z));
                }
            }
        }
    }

    @Test
    void invalidStateIsRejected() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> occ.set(0, 0, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> occ.set(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> occ.set(1000, 0, 0, 7));
        assertEquals(UNKNOWN, occ.get(0, 0, 0));
    }

    @Test
    void stateNamesAreReadable() {
        assertEquals("UNKNOWN", ObservedOccupancy.stateName(UNKNOWN));
        assertEquals("AIR", ObservedOccupancy.stateName(AIR));
        assertEquals("SOLID", ObservedOccupancy.stateName(SOLID));
        assertEquals("FLUID", ObservedOccupancy.stateName(FLUID));
        assertEquals("INVALID(9)", ObservedOccupancy.stateName(9));
        assertEquals(0, UNKNOWN);
    }

    @Test
    void clearForgetsEverythingButKeepsTheWindow() {
        ObservedOccupancy occ = new ObservedOccupancy(50, 60, 70);
        fillWithPattern(occ);
        occ.clear();

        assertEquals(50, occ.centreX());
        assertEquals(60, occ.centreY());
        assertEquals(70, occ.centreZ());
        for (int x = occ.minX(); x <= occ.maxX(); x += 3) {
            for (int y = occ.minY(); y <= occ.maxY(); y += 3) {
                for (int z = occ.minZ(); z <= occ.maxZ(); z += 3) {
                    assertEquals(UNKNOWN, occ.get(x, y, z));
                }
            }
        }
        assertTrue(occ.set(50, 60, 70, SOLID));
    }

    // ---- recentring ----

    @Test
    void recentreKeepsTheOverlapAndForgetsTheRest() {
        int[][] shifts = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
                {8, 0, 0}, {-9, 0, 0}, {0, 12, 0}, {0, -5, 0}, {0, 0, 33}, {0, 0, -33},
                {3, -4, 5}, {-7, 8, -9}, {64, 0, 0}, {0, -64, 0}, {0, 0, 64}, {20, 20, 20}, {-64, 64, -64},
                {65, 0, 0}, {0, -65, 0}, {0, 0, 200}, {100, 100, 100}, {0, 0, 0},
        };
        for (int[] shift : shifts) {
            ObservedOccupancy occ = new ObservedOccupancy(-100, 60, 3);
            fillWithPattern(occ);
            int oldMinX = occ.minX();
            int oldMinY = occ.minY();
            int oldMinZ = occ.minZ();
            int oldMaxX = occ.maxX();
            int oldMaxY = occ.maxY();
            int oldMaxZ = occ.maxZ();

            occ.recentre(-100 + shift[0], 60 + shift[1], 3 + shift[2]);

            assertEquals(-100 + shift[0], occ.centreX());
            assertEquals(60 + shift[1], occ.centreY());
            assertEquals(3 + shift[2], occ.centreZ());
            String label = "shift " + shift[0] + "," + shift[1] + "," + shift[2];
            for (int x = occ.minX(); x <= occ.maxX(); x++) {
                for (int y = occ.minY(); y <= occ.maxY(); y++) {
                    for (int z = occ.minZ(); z <= occ.maxZ(); z++) {
                        boolean wasInWindow = x >= oldMinX && x <= oldMaxX && y >= oldMinY && y <= oldMaxY
                                && z >= oldMinZ && z <= oldMaxZ;
                        int expected = wasInWindow ? pattern(x, y, z) : UNKNOWN;
                        if (occ.get(x, y, z) != expected) {
                            throw new AssertionError(label + " at " + x + "," + y + "," + z
                                    + ": expected " + expected + " got " + occ.get(x, y, z));
                        }
                    }
                }
            }
        }
    }

    @Test
    void cellsLeavingTheWindowAreForgottenEvenIfTheWindowComesBack() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.set(-30, 0, 0, SOLID);
        occ.recentre(20, 0, 0);          // window x in [-12, 52]: -30 is gone
        assertEquals(UNKNOWN, occ.get(-30, 0, 0));
        occ.recentre(0, 0, 0);           // window back to [-32, 32]
        assertEquals(UNKNOWN, occ.get(-30, 0, 0));
    }

    @Test
    void enteringCellsStartUnknownAfterTheWindowWrapsAroundManyTimes() {
        // walk one cell per step, marking the cell 20 ahead; a cell entering the window shares its
        // storage slot with one that left 65 cells earlier and must not inherit its state
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        for (int step = 0; step < 400; step++) {
            occ.recentre(step, 0, 0);
            occ.markSolid(step + 20, 0, 0);
            for (int x = occ.minX(); x <= occ.maxX(); x++) {
                int markedAtStep = x - 20;
                int expected = markedAtStep >= 0 && markedAtStep <= step ? SOLID : UNKNOWN;
                if (occ.get(x, 0, 0) != expected) {
                    throw new AssertionError("step " + step + " x " + x + ": expected " + expected
                            + " got " + occ.get(x, 0, 0));
                }
            }
        }
    }

    @Test
    void recentreOnTheSameCentreIsANoOp() {
        ObservedOccupancy occ = new ObservedOccupancy(5, 6, 7);
        fillWithPattern(occ);
        occ.recentre(5, 6, 7);
        for (int x = occ.minX(); x <= occ.maxX(); x += 2) {
            for (int y = occ.minY(); y <= occ.maxY(); y += 2) {
                for (int z = occ.minZ(); z <= occ.maxZ(); z += 2) {
                    assertEquals(pattern(x, y, z), occ.get(x, y, z));
                }
            }
        }
    }

    @Test
    void recentreAcceptsABlockPos() {
        ObservedOccupancy occ = new ObservedOccupancy(new BlockPos(1, 2, 3));
        assertEquals(1, occ.centreX());
        occ.markSolid(new BlockPos(2, 2, 3));
        occ.recentre(new BlockPos(6, 2, 3));
        assertEquals(6, occ.centreX());
        assertEquals(SOLID, occ.get(2, 2, 3));
    }

    // ---- recentring policy ----

    @Test
    void shouldRecentreOnlyBeyondTheHysteresisMargin() {
        ObservedOccupancy occ = new ObservedOccupancy(100, 64, -100);
        assertEquals(8, ObservedOccupancy.RECENTRE_MARGIN);

        assertFalse(occ.shouldRecentre(100, 64, -100));
        assertFalse(occ.shouldRecentre(108, 64, -100));
        assertFalse(occ.shouldRecentre(92, 64, -100));
        assertTrue(occ.shouldRecentre(109, 64, -100));
        assertTrue(occ.shouldRecentre(91, 64, -100));
        assertFalse(occ.shouldRecentre(100, 72, -100));
        assertTrue(occ.shouldRecentre(100, 73, -100));
        assertTrue(occ.shouldRecentre(100, 55, -100));
        assertFalse(occ.shouldRecentre(100, 64, -92));
        assertTrue(occ.shouldRecentre(100, 64, -91));
        assertTrue(occ.shouldRecentre(100, 64, -109));
        // any single axis beyond the margin is enough
        assertTrue(occ.shouldRecentre(101, 65, -91));
    }

    @Test
    void shouldRecentreHonoursAnExplicitMargin() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        assertFalse(occ.shouldRecentre(4, 0, 0, 4));
        assertTrue(occ.shouldRecentre(5, 0, 0, 4));
        assertTrue(occ.shouldRecentre(1, 0, 0, 0));
        assertFalse(occ.shouldRecentre(0, 0, 0, 0));
        assertTrue(occ.shouldRecentre(1, 0, 0, -5)); // a negative margin behaves like zero
        assertFalse(occ.shouldRecentre(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE));
        assertTrue(occ.shouldRecentre(Integer.MAX_VALUE, 0, 0));
        assertTrue(occ.shouldRecentre(Integer.MIN_VALUE, 0, 0));
    }

    @Test
    void recentreIfNeededMovesExactlyToThePositionOnlyPastTheMargin() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        occ.markSolid(20, 0, 0);

        assertFalse(occ.recentreIfNeeded(8, -8, 8));
        assertEquals(0, occ.centreX());
        assertEquals(SOLID, occ.get(20, 0, 0));

        assertTrue(occ.recentreIfNeeded(9, 2, -1));
        assertEquals(9, occ.centreX());
        assertEquals(2, occ.centreY());
        assertEquals(-1, occ.centreZ());
        assertEquals(SOLID, occ.get(20, 0, 0)); // overlap preserved
        // right after recentring the bot is at the centre, so nothing more to do
        assertFalse(occ.recentreIfNeeded(9, 2, -1));
    }

    @Test
    void aBotWalkingInALineRecentresOncePerNineCellsAndAlwaysHasTwentyFourCellsAround() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 64, 0);
        int recentres = 0;
        for (int step = 1; step <= 1000; step++) {
            if (occ.recentreIfNeeded(step, 64, 0)) {
                recentres++;
            }
            assertTrue(Math.abs(step - occ.centreX()) <= ObservedOccupancy.RECENTRE_MARGIN);
            assertTrue(occ.inWindow(step + 24, 64, 0), "the bot must see at least 24 cells ahead at step " + step);
            assertTrue(occ.inWindow(step - 24, 64, 0), "and behind at step " + step);
            occ.markSolid(step + 20, 64, 0);
        }
        // the first trigger is at 9 cells from the centre, and each recentre lands exactly on the bot
        assertEquals(1000 / 9, recentres);
        // marks made right around the last recentre survived it
        for (int x = 1000 - 12; x <= 1000; x++) {
            assertEquals(SOLID, occ.get(x + 20, 64, 0), "x " + x);
        }
    }

    // ---- extremes ----

    @Test
    void extremeCentresAreClampedWithoutOverflow() {
        ObservedOccupancy occ = new ObservedOccupancy(Integer.MAX_VALUE, Integer.MIN_VALUE, 0);
        assertEquals(ObservedOccupancy.MAX_CENTRE, occ.centreX());
        assertEquals(-ObservedOccupancy.MAX_CENTRE, occ.centreY());
        assertTrue(occ.markSolid(ObservedOccupancy.MAX_CENTRE, -ObservedOccupancy.MAX_CENTRE, 0));
        assertEquals(SOLID, occ.get(ObservedOccupancy.MAX_CENTRE, -ObservedOccupancy.MAX_CENTRE, 0));
        assertFalse(occ.inWindow(Integer.MIN_VALUE, 0, 0));
        assertFalse(occ.inWindow(Integer.MAX_VALUE, 0, 0));
        assertFalse(occ.set(Integer.MIN_VALUE, -ObservedOccupancy.MAX_CENTRE, 0, SOLID));
        assertEquals(UNKNOWN, occ.get(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));

        occ.recentre(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(-ObservedOccupancy.MAX_CENTRE, occ.centreX());
        assertEquals(ObservedOccupancy.MAX_CENTRE, occ.centreY());
        assertEquals(-ObservedOccupancy.MAX_CENTRE, occ.centreZ());
        assertEquals(UNKNOWN, occ.get(ObservedOccupancy.MAX_CENTRE, -ObservedOccupancy.MAX_CENTRE, 0));

        ObservedOccupancy low = new ObservedOccupancy(-ObservedOccupancy.MAX_CENTRE, 0, 0);
        assertFalse(low.inWindow(Integer.MAX_VALUE, 0, 0));
        assertEquals(UNKNOWN, low.get(Integer.MAX_VALUE, 0, 0));
    }

    @Test
    void negativeCoordinatesAndTheOriginBehaveLikeAnyOtherRegion() {
        ObservedOccupancy occ = new ObservedOccupancy(-5, -64, -5);
        for (int x = -37; x <= 27; x++) {
            occ.set(x, -64, -5, 1 + Math.floorMod(x, 3));
        }
        for (int x = -37; x <= 27; x++) {
            assertEquals(1 + Math.floorMod(x, 3), occ.get(x, -64, -5));
        }
        occ.recentre(0, -64, -5);
        for (int x = -32; x <= 27; x++) {
            assertEquals(1 + Math.floorMod(x, 3), occ.get(x, -64, -5));
        }
        for (int x = 28; x <= 32; x++) {
            assertEquals(UNKNOWN, occ.get(x, -64, -5));
        }
    }

    // ---- model-based randomised check ----

    @Test
    void randomOperationsAgreeWithAHashMapModel() {
        for (long seed : new long[] {1L, 2L, 3L, 20250926L}) {
            SplittableRandom rng = new SplittableRandom(seed);
            int cx = rng.nextInt(-300, 300);
            int cy = rng.nextInt(-64, 320);
            int cz = rng.nextInt(-300, 300);
            ObservedOccupancy occ = new ObservedOccupancy(cx, cy, cz);
            Map<P, Integer> model = new HashMap<>();

            for (int op = 0; op < 400; op++) {
                int pick = rng.nextInt(100);
                if (pick < 60) {
                    int x = cx + rng.nextInt(-40, 41);
                    int y = cy + rng.nextInt(-40, 41);
                    int z = cz + rng.nextInt(-40, 41);
                    int state = rng.nextInt(4);
                    boolean inside = Math.abs(x - cx) <= 32 && Math.abs(y - cy) <= 32 && Math.abs(z - cz) <= 32;
                    assertEquals(inside, occ.set(x, y, z, state));
                    if (inside) {
                        if (state == UNKNOWN) {
                            model.remove(new P(x, y, z));
                        } else {
                            model.put(new P(x, y, z), state);
                        }
                    }
                } else if (pick < 88) {
                    int roll = rng.nextInt(10);
                    int dx = roll < 7 ? rng.nextInt(-12, 13) : rng.nextInt(-90, 91);
                    int dy = roll < 7 ? rng.nextInt(-12, 13) : rng.nextInt(-90, 91);
                    int dz = roll < 7 ? rng.nextInt(-12, 13) : rng.nextInt(-90, 91);
                    if (rng.nextInt(3) == 0) {
                        dy = 0;
                    }
                    cx += dx;
                    cy += dy;
                    cz += dz;
                    occ.recentre(cx, cy, cz);
                    for (Iterator<P> it = model.keySet().iterator(); it.hasNext(); ) {
                        P p = it.next();
                        if (Math.abs(p.x() - cx) > 32 || Math.abs(p.y() - cy) > 32 || Math.abs(p.z() - cz) > 32) {
                            it.remove();
                        }
                    }
                } else if (pick < 91) {
                    occ.clear();
                    model.clear();
                } else {
                    verify(occ, model, rng, cx, cy, cz, 2500);
                }
            }
            verify(occ, model, rng, cx, cy, cz, 20_000);
            // and one exhaustive pass over the whole window
            for (int x = cx - 32; x <= cx + 32; x++) {
                for (int y = cy - 32; y <= cy + 32; y++) {
                    for (int z = cz - 32; z <= cz + 32; z++) {
                        assertEquals(model.getOrDefault(new P(x, y, z), UNKNOWN), occ.get(x, y, z),
                                "seed " + seed + " at " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    private static void verify(ObservedOccupancy occ, Map<P, Integer> model, SplittableRandom rng,
                               int cx, int cy, int cz, int probes) {
        for (Map.Entry<P, Integer> entry : model.entrySet()) {
            P p = entry.getKey();
            assertEquals(entry.getValue(), occ.get(p.x(), p.y(), p.z()));
        }
        for (int i = 0; i < probes; i++) {
            int x = cx + rng.nextInt(-36, 37);
            int y = cy + rng.nextInt(-36, 37);
            int z = cz + rng.nextInt(-36, 37);
            assertEquals(model.getOrDefault(new P(x, y, z), UNKNOWN), occ.get(x, y, z));
        }
    }

    @Test
    void identicalOperationSequencesGiveIdenticalContents() {
        ObservedOccupancy a = new ObservedOccupancy(0, 0, 0);
        ObservedOccupancy b = new ObservedOccupancy(0, 0, 0);
        SplittableRandom ra = new SplittableRandom(77L);
        SplittableRandom rb = new SplittableRandom(77L);
        for (int i = 0; i < 300; i++) {
            apply(a, ra);
            apply(b, rb);
        }
        assertEquals(a.centreX(), b.centreX());
        for (int x = a.minX(); x <= a.maxX(); x++) {
            for (int y = a.minY(); y <= a.maxY(); y += 2) {
                for (int z = a.minZ(); z <= a.maxZ(); z += 2) {
                    assertEquals(a.get(x, y, z), b.get(x, y, z));
                }
            }
        }
    }

    private static void apply(ObservedOccupancy occ, SplittableRandom rng) {
        if (rng.nextInt(10) == 0) {
            occ.recentre(occ.centreX() + rng.nextInt(-15, 16), occ.centreY() + rng.nextInt(-3, 4),
                    occ.centreZ() + rng.nextInt(-15, 16));
        } else {
            occ.set(occ.centreX() + rng.nextInt(-33, 34), occ.centreY() + rng.nextInt(-33, 34),
                    occ.centreZ() + rng.nextInt(-33, 34), rng.nextInt(4));
        }
    }
}
