package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;

import static io.github.zoyluo.aibot.mining.assist.ObservedOccupancy.AIR;
import static io.github.zoyluo.aibot.mining.assist.ObservedOccupancy.FLUID;
import static io.github.zoyluo.aibot.mining.assist.ObservedOccupancy.SOLID;
import static io.github.zoyluo.aibot.mining.assist.ObservedOccupancy.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RayGridTest {
    private static final double INV_SQRT2 = 1.0D / Math.sqrt(2.0D);
    private static final double INV_SQRT3 = 1.0D / Math.sqrt(3.0D);

    private record Cell(int x, int y, int z) {
    }

    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    private static List<Cell> trace(double ox, double oy, double oz,
                                    double dx, double dy, double dz, double range) {
        List<Cell> cells = new ArrayList<>();
        int visited = RayGrid.traverse(ox, oy, oz, dx, dy, dz, range, (x, y, z) -> {
            cells.add(new Cell(x, y, z));
            return true;
        });
        assertEquals(cells.size(), visited);
        return cells;
    }

    private static double[] randomUnit(SplittableRandom rng) {
        double z = rng.nextDouble(-1.0D, 1.0D);
        double phi = rng.nextDouble(0.0D, 2.0D * Math.PI);
        double r = Math.sqrt(1.0D - z * z);
        return new double[] {r * Math.cos(phi), r * Math.sin(phi), z};
    }

    private static int countKnown(ObservedOccupancy occ) {
        int known = 0;
        for (int x = occ.minX(); x <= occ.maxX(); x++) {
            for (int y = occ.minY(); y <= occ.maxY(); y++) {
                for (int z = occ.minZ(); z <= occ.maxZ(); z++) {
                    if (occ.get(x, y, z) != UNKNOWN) {
                        known++;
                    }
                }
            }
        }
        return known;
    }

    // ---- traverse: axis-aligned, diagonal, negative, boundaries ----

    @Test
    void axisAlignedRaysVisitEveryCellInOrderInAllSixDirections() {
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(2, 0, 0), c(3, 0, 0)),
                trace(0.5, 0.5, 0.5, 1, 0, 0, 3.2));
        assertEquals(List.of(c(0, 0, 0), c(-1, 0, 0), c(-2, 0, 0), c(-3, 0, 0)),
                trace(0.5, 0.5, 0.5, -1, 0, 0, 2.7));
        assertEquals(List.of(c(0, 0, 0), c(0, 1, 0), c(0, 2, 0)),
                trace(0.5, 0.5, 0.5, 0, 1, 0, 2.4));
        assertEquals(List.of(c(0, 0, 0), c(0, -1, 0), c(0, -2, 0)),
                trace(0.5, 0.5, 0.5, 0, -1, 0, 2.4));
        assertEquals(List.of(c(0, 0, 0), c(0, 0, 1), c(0, 0, 2)),
                trace(0.5, 0.5, 0.5, 0, 0, 1, 2.4));
        assertEquals(List.of(c(0, 0, 0), c(0, 0, -1), c(0, 0, -2)),
                trace(0.5, 0.5, 0.5, 0, 0, -1, 2.4));
    }

    @Test
    void negativeCoordinatesUseFloorNotTruncation() {
        List<Cell> cells = trace(-1234.7, -0.2, -0.9, 1, 0, 0, 2.0);
        assertEquals(List.of(c(-1235, -1, -1), c(-1234, -1, -1), c(-1233, -1, -1)), cells);
    }

    @Test
    void perfectDiagonalStepsAxesInXyzOrderOnExactTies() {
        // every crossing of the 3D diagonal hits an edge or corner: x, then y, then z
        List<Cell> cells = trace(0.5, 0.5, 0.5, INV_SQRT3, INV_SQRT3, INV_SQRT3, 1.0);
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(1, 1, 0), c(1, 1, 1)), cells);
    }

    @Test
    void planarDiagonalWithNegativeComponent() {
        List<Cell> cells = trace(0.5, 0.5, 0.5, INV_SQRT2, -INV_SQRT2, 0, 1.5);
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(1, -1, 0)), cells);
    }

    @Test
    void obliqueRayCrossesCellsInTheOrderOfItsCrossings() {
        // slope 1:2 in x:y from a cell centre: x boundary at t=0.5/dx, y boundary at t=0.5/dy
        double len = Math.sqrt(5.0D);
        List<Cell> cells = trace(0.5, 0.5, 0.5, 1.0 / len, 2.0 / len, 0, 3.0);
        // dy is twice dx: y crosses at 0.25*len, x at 0.5*len, y again at 0.75*len, y at 1.25*len ...
        assertEquals(List.of(c(0, 0, 0), c(0, 1, 0), c(1, 1, 0), c(1, 2, 0)), cells.subList(0, 4));
    }

    @Test
    void rayStartingOnPositiveBoundaryStartsInTheFlooredCell() {
        assertEquals(List.of(c(5, 0, 0), c(6, 0, 0), c(7, 0, 0)),
                trace(5.0, 0.5, 0.5, 1, 0, 0, 2.2));
    }

    @Test
    void rayStartingOnBoundaryHeadingNegativeVisitsFlooredCellThenTheNeighbour() {
        // x = 5.0 belongs to cell 5 by floor; the ray leaves it immediately (crossing at t = 0)
        assertEquals(List.of(c(5, 0, 0), c(4, 0, 0), c(3, 0, 0), c(2, 0, 0)),
                trace(5.0, 0.5, 0.5, -1, 0, 0, 2.2));
        assertEquals(List.of(c(0, 5, 0), c(0, 4, 0), c(0, 3, 0)),
                trace(0.5, 5.0, 0.5, 0, -1, 0, 1.2));
    }

    @Test
    void rayStartingOnACornerFollowsTheSameRules() {
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(1, 1, 0)),
                trace(0.0, 0.0, 0.0, INV_SQRT2, INV_SQRT2, 0, 2.0));
        assertEquals(List.of(c(0, 0, 0), c(-1, 0, 0), c(-1, -1, 0)),
                trace(0.0, 0.0, 0.0, -INV_SQRT2, -INV_SQRT2, 0, 1.0));
    }

    @Test
    void zeroComponentsNeverStepThatAxis() {
        for (Cell cell : trace(0.3, 0.6, 0.9, 0.6, 0.0, 0.8, 8.0)) {
            assertEquals(0, cell.y());
        }
        // negative zero behaves like zero
        for (Cell cell : trace(0.3, 0.6, 0.9, -0.0, 1, -0.0, 6.0)) {
            assertEquals(0, cell.x());
            assertEquals(0, cell.z());
        }
    }

    @Test
    void zeroDirectionVisitsOnlyTheStartCell() {
        assertEquals(List.of(c(3, -2, 7)), trace(3.5, -1.5, 7.5, 0, 0, 0, 50.0));
    }

    @Test
    void nonUnitDirectionIsNormalisedSoRangeIsInBlocks() {
        assertEquals(trace(0.5, 0.5, 0.5, 1, 0, 0, 3.2), trace(0.5, 0.5, 0.5, 5, 0, 0, 3.2));
        assertEquals(trace(0.5, 0.5, 0.5, 0, -1, 0, 3.2), trace(0.5, 0.5, 0.5, 0, -0.2, 0, 3.2));
        assertEquals(trace(0.5, 0.5, 0.5, INV_SQRT2, 0, INV_SQRT2, 6.0),
                trace(0.5, 0.5, 0.5, 3, 0, 3, 6.0));
    }

    // ---- traverse: range handling ----

    @Test
    void rangeZeroVisitsOnlyTheStartCell() {
        assertEquals(List.of(c(0, 0, 0)), trace(0.5, 0.5, 0.5, 1, 0, 0, 0.0));
        assertEquals(List.of(c(5, 0, 0)), trace(5.0, 0.5, 0.5, -1, 0, 0, 0.0));
    }

    @Test
    void invalidRangeOrInputVisitsNothing() {
        assertEquals(0, RayGrid.traverse(0.5, 0.5, 0.5, 1, 0, 0, -0.1, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(0.5, 0.5, 0.5, 1, 0, 0, Double.NaN, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(0.5, 0.5, 0.5, 1, 0, 0, Double.POSITIVE_INFINITY, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(Double.NaN, 0.5, 0.5, 1, 0, 0, 5, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(0.5, 0.5, 0.5, Double.NaN, 0, 0, 5, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(0.5, 0.5, 0.5, Double.POSITIVE_INFINITY, 0, 0, 5, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(2.0E9, 0.5, 0.5, 1, 0, 0, 5, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(0.5, -2.0E9, 0.5, 1, 0, 0, 5, (x, y, z) -> true));
    }

    @Test
    void rangeIsClampedToMaxRange() {
        List<Cell> clamped = trace(0.5, 0.5, 0.5, 1, 0, 0, 1.0E6);
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0)), clamped.subList(0, 2));
        // crossings at 0.5, 1.5, ... 255.5 are all before 256 -> cells 0..256
        assertEquals(257, clamped.size());
        assertEquals(c(256, 0, 0), clamped.get(clamped.size() - 1));
        assertEquals(clamped, trace(0.5, 0.5, 0.5, 1, 0, 0, RayGrid.MAX_RANGE));
    }

    @Test
    void cellCountNeverExceedsTheGeometricBound() {
        SplittableRandom rng = new SplittableRandom(11L);
        for (int i = 0; i < 400; i++) {
            double[] d = randomUnit(rng);
            double range = rng.nextDouble(0.0D, RayGrid.MAX_RANGE);
            int count = trace(rng.nextDouble(-30, 30), rng.nextDouble(-30, 30), rng.nextDouble(-30, 30),
                    d[0], d[1], d[2], range).size();
            double bound = 4.0D + range * (Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]));
            assertTrue(count <= bound, "count " + count + " > bound " + bound);
            assertTrue(count < RayGrid.MAX_CELLS);
        }
    }

    @Test
    void crossingExactlyAtTheRangeIsNotEntered() {
        // from x = 0.5 the crossing into cell 2 is at exactly t = 1.5: the segment only touches it
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0)), trace(0.5, 0.5, 0.5, 1, 0, 0, 1.5));
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0)), trace(0.5, 0.5, 0.5, 1, 0, 0, 1.5 - 1.0E-6));
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(2, 0, 0)),
                trace(0.5, 0.5, 0.5, 1, 0, 0, 1.5 + 1.0E-6));
        assertEquals(List.of(c(0, 0, 0)), trace(0.5, 0.5, 0.5, -1, 0, 0, 0.5));
        assertEquals(List.of(c(0, 0, 0), c(-1, 0, 0)), trace(0.5, 0.5, 0.5, -1, 0, 0, 0.5 + 1.0E-6));
    }

    @Test
    void visitorCanStopTheTraversal() {
        List<Cell> seen = new ArrayList<>();
        int visited = RayGrid.traverse(0.5, 0.5, 0.5, 1, 0, 0, 20.0, (x, y, z) -> {
            seen.add(new Cell(x, y, z));
            return seen.size() < 3;
        });
        assertEquals(3, visited);
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(2, 0, 0)), seen);
    }

    // ---- traverse: properties over random rays ----

    private static boolean segmentTouchesCell(double[] o, double[] d, double range, Cell cell, double tol) {
        int[] lo = {cell.x(), cell.y(), cell.z()};
        double tMin = 0.0D;
        double tMax = range;
        for (int i = 0; i < 3; i++) {
            double lower = lo[i] - tol;
            double upper = lo[i] + 1 + tol;
            if (d[i] == 0.0D) {
                if (o[i] < lower || o[i] > upper) {
                    return false;
                }
            } else {
                double t1 = (lower - o[i]) / d[i];
                double t2 = (upper - o[i]) / d[i];
                tMin = Math.max(tMin, Math.min(t1, t2));
                tMax = Math.min(tMax, Math.max(t1, t2));
                if (tMin > tMax) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void randomRaysAreContiguousDuplicateFreeAndCoverEverySampledPoint() {
        SplittableRandom rng = new SplittableRandom(20250926L);
        for (int i = 0; i < 1200; i++) {
            double[] o = {rng.nextDouble(-50, 50), rng.nextDouble(-50, 50), rng.nextDouble(-50, 50)};
            double[] d = randomUnit(rng);
            double range = rng.nextDouble(0.0D, 60.0D);
            List<Cell> cells = trace(o[0], o[1], o[2], d[0], d[1], d[2], range);

            assertEquals(c((int) Math.floor(o[0]), (int) Math.floor(o[1]), (int) Math.floor(o[2])), cells.get(0));
            Set<Cell> unique = new HashSet<>(cells);
            assertEquals(cells.size(), unique.size(), "a cell was visited twice");
            for (int k = 1; k < cells.size(); k++) {
                Cell a = cells.get(k - 1);
                Cell b = cells.get(k);
                int manhattan = Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
                assertEquals(1, manhattan, "consecutive cells must share a face");
            }
            for (Cell cell : cells) {
                assertTrue(segmentTouchesCell(o, d, range, cell, 1.0E-7D), "visited a cell the ray never touches: " + cell);
            }
            for (double t = 0.0D; t < range - 1.0E-6D; t += 0.013D) {
                Cell sampled = c((int) Math.floor(o[0] + d[0] * t), (int) Math.floor(o[1] + d[1] * t),
                        (int) Math.floor(o[2] + d[2] * t));
                assertTrue(unique.contains(sampled), "missed cell " + sampled + " at t=" + t);
            }
        }
    }

    @Test
    void lastCellContainsTheEndPoint() {
        SplittableRandom rng = new SplittableRandom(99L);
        int checked = 0;
        for (int i = 0; i < 1000; i++) {
            double[] o = {rng.nextDouble(-40, 40), rng.nextDouble(-40, 40), rng.nextDouble(-40, 40)};
            double[] d = randomUnit(rng);
            double range = rng.nextDouble(0.0D, 50.0D);
            double[] end = {o[0] + d[0] * range, o[1] + d[1] * range, o[2] + d[2] * range};
            boolean nearBoundary = false;
            for (double v : end) {
                nearBoundary |= Math.abs(v - Math.rint(v)) < 1.0E-6D;
            }
            if (nearBoundary) {
                continue;
            }
            List<Cell> cells = trace(o[0], o[1], o[2], d[0], d[1], d[2], range);
            assertEquals(c((int) Math.floor(end[0]), (int) Math.floor(end[1]), (int) Math.floor(end[2])),
                    cells.get(cells.size() - 1));
            checked++;
        }
        assertTrue(checked > 900);
    }

    /**
     * Independent oracle: the cells of the closed segment in exact (60-digit) arithmetic. It sorts the
     * parameters at which the segment crosses an integer plane and takes the cell containing the
     * midpoint of every interval between them, so it shares no code or rounding with the DDA.
     */
    private static List<Cell> exactCells(double[] o, double[] d, double range) {
        MathContext mc = new MathContext(60);
        BigDecimal[] bo = {new BigDecimal(o[0]), new BigDecimal(o[1]), new BigDecimal(o[2])};
        BigDecimal[] bd = {new BigDecimal(d[0]), new BigDecimal(d[1]), new BigDecimal(d[2])};
        BigDecimal end = new BigDecimal(range);
        TreeSet<BigDecimal> params = new TreeSet<>();
        params.add(BigDecimal.ZERO);
        params.add(end);
        for (int axis = 0; axis < 3; axis++) {
            if (d[axis] == 0.0D) {
                continue;
            }
            double reachA = o[axis];
            double reachB = o[axis] + d[axis] * range;
            for (long plane = (long) Math.floor(Math.min(reachA, reachB)) - 1;
                 plane <= (long) Math.ceil(Math.max(reachA, reachB)) + 1; plane++) {
                BigDecimal t = new BigDecimal(plane).subtract(bo[axis]).divide(bd[axis], mc);
                if (t.signum() > 0 && t.compareTo(end) < 0) {
                    params.add(t);
                }
            }
        }
        List<BigDecimal> ordered = new ArrayList<>(params);
        List<Cell> cells = new ArrayList<>();
        for (int k = 0; k + 1 < ordered.size(); k++) {
            BigDecimal mid = ordered.get(k).add(ordered.get(k + 1)).divide(BigDecimal.valueOf(2), mc);
            int[] cell = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                cell[axis] = bo[axis].add(bd[axis].multiply(mid, mc)).setScale(0, RoundingMode.FLOOR).intValueExact();
            }
            Cell next = c(cell[0], cell[1], cell[2]);
            if (cells.isEmpty() || !cells.get(cells.size() - 1).equals(next)) {
                cells.add(next);
            }
        }
        if (cells.isEmpty()) {
            cells.add(c((int) Math.floor(o[0]), (int) Math.floor(o[1]), (int) Math.floor(o[2])));
        }
        return cells;
    }

    @Test
    void traverseMatchesAnExactArithmeticReferenceAtAnyDistanceFromTheOrigin() {
        SplittableRandom rng = new SplittableRandom(424242L);
        double[] scales = {3.0D, 60.0D, 1.0E6D, 3.0E7D};
        for (int i = 0; i < 1200; i++) {
            double scale = scales[i % scales.length];
            double[] o = {rng.nextDouble(-scale, scale), rng.nextDouble(-64, 320), rng.nextDouble(-scale, scale)};
            double[] d = randomUnit(rng);
            // every fifth ray goes the full clamped distance, where accumulated error would show first
            double range = i % 5 == 0 ? RayGrid.MAX_RANGE : rng.nextDouble(0.0D, 100.0D);
            assertEquals(exactCells(o, d, range), trace(o[0], o[1], o[2], d[0], d[1], d[2], range),
                    "ray " + i + " from " + Arrays.toString(o));
        }
    }

    @Test
    void originsAtTheAcceptedLimitDoNotOverflow() {
        assertEquals(List.of(c(1_000_000_000, 0, 0), c(1_000_000_001, 0, 0), c(1_000_000_002, 0, 0)),
                trace(1.0E9, 0.5, 0.5, 1, 0, 0, 2.5));
        assertEquals(List.of(c(-1_000_000_000, 0, 0), c(-1_000_000_001, 0, 0), c(-1_000_000_002, 0, 0)),
                trace(-1.0E9, 0.5, 0.5, -1, 0, 0, 1.5));
        assertEquals(0, RayGrid.traverse(Math.nextUp(1.0E9), 0.5, 0.5, 1, 0, 0, 1.5, (x, y, z) -> true));
        assertEquals(0, RayGrid.traverse(0.5, 0.5, Math.nextDown(-1.0E9), 1, 0, 0, 1.5, (x, y, z) -> true));
    }

    @Test
    void nullArgumentsFailFastInsteadOfMarkingOrVisitingNothingSilently() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        assertThrows(NullPointerException.class, () -> RayGrid.traverse(0.5, 0.5, 0.5, 1, 0, 0, 2.0, null));
        assertThrows(NullPointerException.class,
                () -> RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 2.3, null));
        assertThrows(NullPointerException.class,
                () -> RayGrid.mark(null, 0.5, 0.5, 0.5, 1, 0, 0, 2.3, RayGrid.HitKind.SOLID));
        assertEquals(0, countKnown(occ));
    }

    @Test
    void traversalIsDeterministic() {
        SplittableRandom first = new SplittableRandom(5L);
        SplittableRandom second = new SplittableRandom(5L);
        for (int i = 0; i < 100; i++) {
            double[] a = randomUnit(first);
            double[] b = randomUnit(second);
            assertEquals(
                    trace(1.25, 2.5, -3.75, a[0], a[1], a[2], 30.0),
                    trace(1.25, 2.5, -3.75, b[0], b[1], b[2], 30.0));
        }
    }

    // ---- mark ----

    @Test
    void solidHitMarksAirThenSolid() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        int written = RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 2.3, RayGrid.HitKind.SOLID);

        assertEquals(3, written);
        assertEquals(AIR, occ.get(0, 0, 0));
        assertEquals(AIR, occ.get(1, 0, 0));
        assertEquals(SOLID, occ.get(2, 0, 0));
        assertEquals(UNKNOWN, occ.get(3, 0, 0));
        assertEquals(3, countKnown(occ));
    }

    @Test
    void fluidHitMarksFluidAtTheHitCell() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        RayGrid.mark(occ, 0.5, 0.5, 0.5, 0, 0, -1, 3.4, RayGrid.HitKind.FLUID);

        assertEquals(AIR, occ.get(0, 0, 0));
        assertEquals(AIR, occ.get(0, 0, -1));
        assertEquals(AIR, occ.get(0, 0, -2));
        assertEquals(FLUID, occ.get(0, 0, -3));
        assertEquals(UNKNOWN, occ.get(0, 0, -4));
    }

    @Test
    void missMarksEveryCellUpToTheRangeAir() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        int written = RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 2.3, RayGrid.HitKind.MISS);

        assertEquals(3, written);
        assertEquals(AIR, occ.get(0, 0, 0));
        assertEquals(AIR, occ.get(1, 0, 0));
        assertEquals(AIR, occ.get(2, 0, 0));
        assertEquals(UNKNOWN, occ.get(3, 0, 0));
        assertEquals(3, countKnown(occ));
    }

    @Test
    void missDoesNotMarkACellTouchedOnlyAtTheEndPoint() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 1.5, RayGrid.HitKind.MISS);

        assertEquals(AIR, occ.get(1, 0, 0));
        assertEquals(UNKNOWN, occ.get(2, 0, 0));
    }

    @Test
    void hitPointOnACellBoundaryBelongsToTheCellBeingEntered() {
        ObservedOccupancy positive = new ObservedOccupancy(0, 0, 0);
        RayGrid.mark(positive, 0.5, 0.5, 0.5, 1, 0, 0, 1.5, RayGrid.HitKind.SOLID);
        assertEquals(AIR, positive.get(0, 0, 0));
        assertEquals(AIR, positive.get(1, 0, 0));
        assertEquals(SOLID, positive.get(2, 0, 0));
        assertEquals(UNKNOWN, positive.get(3, 0, 0));

        ObservedOccupancy negative = new ObservedOccupancy(0, 0, 0);
        RayGrid.mark(negative, 0.5, 0.5, 0.5, -1, 0, 0, 0.5, RayGrid.HitKind.SOLID);
        assertEquals(AIR, negative.get(0, 0, 0));
        assertEquals(SOLID, negative.get(-1, 0, 0));
        assertEquals(UNKNOWN, negative.get(-2, 0, 0));
    }

    @Test
    void hitDistanceRoundingNoiseDoesNotShiftTheHitCell() {
        for (double noise : new double[] {-1.0E-12D, 0.0D, 1.0E-12D}) {
            ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
            RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 1.5 + noise, RayGrid.HitKind.SOLID);
            assertEquals(SOLID, occ.get(2, 0, 0), "noise " + noise);
            assertEquals(AIR, occ.get(1, 0, 0), "noise " + noise);
        }
        // a real gap of 1e-6 blocks is not noise: the ray stopped inside cell 1
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 1.5 - 1.0E-6, RayGrid.HitKind.SOLID);
        assertEquals(SOLID, occ.get(1, 0, 0));
        assertEquals(UNKNOWN, occ.get(2, 0, 0));
    }

    @Test
    void aDirectionThatIsOnlyNearlyUnitStillPutsTheHitOnTheBlockFace() {
        // The caller measured the true Euclidean distance to the face; the direction it passed is unit
        // only to about 1e-10. That is far above rounding noise, so the direction must be renormalised
        // rather than trusted, or the SOLID mark lands on the air cell in front of the block.
        for (double skew : new double[] {-3.0E-10D, -1.0E-12D, 1.0E-12D, 3.0E-10D}) {
            ObservedOccupancy axis = new ObservedOccupancy(100, 0, 0);
            RayGrid.mark(axis, 70.5, 0.5, 0.5, 1.0D + skew, 0, 0, 59.5, RayGrid.HitKind.SOLID);
            assertEquals(SOLID, axis.get(130, 0, 0), "skew " + skew);
            assertEquals(AIR, axis.get(129, 0, 0), "skew " + skew);
            assertEquals(UNKNOWN, axis.get(131, 0, 0), "skew " + skew);

            // oblique: 3-4-5 direction, x face at 90 is met after (90 - 70.5) / 0.6 = 32.5 blocks
            ObservedOccupancy oblique = new ObservedOccupancy(70, 0, 0);
            RayGrid.mark(oblique, 70.5, 0.5, 0.5, 0.6D * (1.0D + skew), 0.8D * (1.0D + skew), 0, 32.5,
                    RayGrid.HitKind.SOLID);
            assertEquals(SOLID, oblique.get(90, 26, 0), "skew " + skew);
            assertEquals(1, countSolid(oblique), "skew " + skew);
        }
    }

    private static int countSolid(ObservedOccupancy occ) {
        int solid = 0;
        for (int x = occ.minX(); x <= occ.maxX(); x++) {
            for (int y = occ.minY(); y <= occ.maxY(); y++) {
                for (int z = occ.minZ(); z <= occ.maxZ(); z++) {
                    if (occ.get(x, y, z) == SOLID) {
                        solid++;
                    }
                }
            }
        }
        return solid;
    }

    @Test
    void eyeLookingStraightDownHitsTheFloorCellBelowTheFeetCell() {
        // eye at y = 64.62 in cell 64; the floor block y = 63 has its top face at 64.0
        ObservedOccupancy occ = new ObservedOccupancy(0, 64, 0);
        RayGrid.mark(occ, 0.5, 64.62, 0.5, 0, -1, 0, 64.62 - 64.0, RayGrid.HitKind.SOLID);

        assertEquals(AIR, occ.get(0, 64, 0));
        assertEquals(SOLID, occ.get(0, 63, 0));
        assertEquals(UNKNOWN, occ.get(0, 62, 0));
    }

    @Test
    void hitAtDistanceZeroMarksTheStartCell() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        assertEquals(1, RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 0.0, RayGrid.HitKind.SOLID));
        assertEquals(SOLID, occ.get(0, 0, 0));
        assertEquals(UNKNOWN, occ.get(1, 0, 0));
    }

    @Test
    void laterObservationsOverwriteEarlierOnes() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 3.4, RayGrid.HitKind.SOLID);
        assertEquals(SOLID, occ.get(3, 0, 0));

        // the block was mined: the same ray now sees through it and hits the next one
        RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 4.4, RayGrid.HitKind.SOLID);
        assertEquals(AIR, occ.get(3, 0, 0));
        assertEquals(SOLID, occ.get(4, 0, 0));

        // a fluid flowed in front of the wall
        RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 2.4, RayGrid.HitKind.FLUID);
        assertEquals(FLUID, occ.get(2, 0, 0));
        assertEquals(SOLID, occ.get(4, 0, 0));
    }

    @Test
    void diagonalRayMarksEachTraversedCellOnceAndEndsSolid() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        double dist = 3.0D;
        int written = RayGrid.mark(occ, 0.5, 0.5, 0.5, INV_SQRT3, INV_SQRT3, INV_SQRT3, dist, RayGrid.HitKind.SOLID);
        List<Cell> cells = trace(0.5, 0.5, 0.5, INV_SQRT3, INV_SQRT3, INV_SQRT3, dist);

        assertEquals(cells.size(), written);
        assertEquals(cells.size(), countKnown(occ));
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            assertEquals(i == cells.size() - 1 ? SOLID : AIR, occ.get(cell.x(), cell.y(), cell.z()));
        }
    }

    // ---- mark: clamps and window behaviour ----

    @Test
    void hitDistanceBeyondMaxRangeIsTreatedAsAMiss() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        int written = RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 300.0, RayGrid.HitKind.SOLID);

        assertEquals(33, written); // cells 0..32 are inside the window
        for (int x = 0; x <= 32; x++) {
            assertEquals(AIR, occ.get(x, 0, 0));
        }
        assertEquals(33, countKnown(occ));
    }

    @Test
    void invalidDistanceOrDirectionMarksNothing() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        for (RayGrid.HitKind kind : RayGrid.HitKind.values()) {
            assertEquals(0, RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, -1.0, kind));
            assertEquals(0, RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, Double.NaN, kind));
            assertEquals(0, RayGrid.mark(occ, 0.5, 0.5, 0.5, Double.NaN, 0, 0, 3.0, kind));
            assertEquals(0, RayGrid.mark(occ, Double.POSITIVE_INFINITY, 0.5, 0.5, 1, 0, 0, 3.0, kind));
        }
        assertEquals(0, countKnown(occ));
    }

    @Test
    void walkStopsOnceTheRayHasLeftTheWindow() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        int written = RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 100.0, RayGrid.HitKind.MISS);

        assertEquals(33, written);
        assertEquals(AIR, occ.get(32, 0, 0));
        assertEquals(UNKNOWN, occ.get(33, 0, 0));
    }

    @Test
    void terminalCellOutsideTheWindowIsNeverMarkedInsideIt() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        int written = RayGrid.mark(occ, 0.5, 0.5, 0.5, 1, 0, 0, 40.5, RayGrid.HitKind.SOLID);

        assertEquals(33, written);
        assertEquals(AIR, occ.get(32, 0, 0)); // the last in-window cell is air, not the hit
        assertEquals(33, countKnown(occ));
    }

    @Test
    void rayEnteringTheWindowFromOutsideMarksOnlyTheInsidePart() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        int written = RayGrid.mark(occ, 100.5, 0.5, 0.5, -1, 0, 0, 100.0, RayGrid.HitKind.SOLID);

        assertEquals(33, written); // x = 32 down to 0
        assertEquals(UNKNOWN, occ.get(33, 0, 0));
        assertEquals(AIR, occ.get(32, 0, 0));
        assertEquals(AIR, occ.get(1, 0, 0));
        assertEquals(SOLID, occ.get(0, 0, 0));
    }

    @Test
    void rayCompletelyOutsideTheWindowWritesNothing() {
        ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);
        assertEquals(0, RayGrid.mark(occ, 100.5, 0.5, 0.5, 1, 0, 0, 50.0, RayGrid.HitKind.SOLID));
        assertEquals(0, RayGrid.mark(occ, 0.5, 100.5, 0.5, 1, 0, 0, 50.0, RayGrid.HitKind.MISS));
        assertEquals(0, countKnown(occ));
    }

    @Test
    void windowEdgeIsWrittenExactlyAtThePlusAndMinusThirtyTwoBoundary() {
        ObservedOccupancy occ = new ObservedOccupancy(10, 20, 30);
        RayGrid.mark(occ, 10.5, 20.5, 30.5, -1, 0, 0, 50.0, RayGrid.HitKind.MISS);

        assertEquals(AIR, occ.get(10 - 32, 20, 30));
        assertEquals(UNKNOWN, occ.get(10 - 33, 20, 30));
    }

    // ---- mark vs traverse ----

    @Test
    void markAgreesWithTraverseAndWithTheCellContainingTheEndPoint() {
        SplittableRandom rng = new SplittableRandom(7L);
        for (int i = 0; i < 1500; i++) {
            double[] o = {rng.nextDouble(-8, 8), rng.nextDouble(-8, 8), rng.nextDouble(-8, 8)};
            double[] d = randomUnit(rng);
            double distance = rng.nextDouble(0.0D, 20.0D);
            RayGrid.HitKind kind = RayGrid.HitKind.values()[rng.nextInt(3)];
            ObservedOccupancy occ = new ObservedOccupancy(0, 0, 0);

            int written = RayGrid.mark(occ, o[0], o[1], o[2], d[0], d[1], d[2], distance, kind);
            List<Cell> cells = trace(o[0], o[1], o[2], d[0], d[1], d[2], distance);

            assertEquals(cells.size(), written);
            int terminal = kind == RayGrid.HitKind.FLUID ? FLUID : SOLID;
            for (int k = 0; k < cells.size(); k++) {
                Cell cell = cells.get(k);
                boolean last = k == cells.size() - 1;
                int expected = last && kind != RayGrid.HitKind.MISS ? terminal : AIR;
                assertEquals(expected, occ.get(cell.x(), cell.y(), cell.z()));
            }
            if (i < 25) {
                assertEquals(cells.size(), countKnown(occ), "marked a cell the ray did not traverse");
            }
            double[] end = {o[0] + d[0] * distance, o[1] + d[1] * distance, o[2] + d[2] * distance};
            boolean nearBoundary = false;
            for (double v : end) {
                nearBoundary |= Math.abs(v - Math.rint(v)) < 1.0E-6D;
            }
            if (!nearBoundary) {
                Cell hitCell = cells.get(cells.size() - 1);
                assertEquals(c((int) Math.floor(end[0]), (int) Math.floor(end[1]), (int) Math.floor(end[2])), hitCell);
            }
        }
    }

    @Test
    void markingAfterRecentringMatchesMarkingIntoAFreshWindowAtTheSameCentre() {
        // storage is toroidal: a wrong phase after recentring would scramble where the ray cells land
        SplittableRandom rng = new SplittableRandom(31L);
        for (int round = 0; round < 25; round++) {
            int cx = rng.nextInt(-300, 300);
            int cy = rng.nextInt(-64, 320);
            int cz = rng.nextInt(-300, 300);
            ObservedOccupancy walked = new ObservedOccupancy(rng.nextInt(-300, 300), rng.nextInt(-64, 320),
                    rng.nextInt(-300, 300));
            walked.recentre(cx, cy, cz);
            ObservedOccupancy fresh = new ObservedOccupancy(cx, cy, cz);
            for (int ray = 0; ray < 20; ray++) {
                double[] d = randomUnit(rng);
                double distance = rng.nextDouble(0.0D, 45.0D);
                RayGrid.HitKind kind = RayGrid.HitKind.values()[rng.nextInt(3)];
                double ox = cx + rng.nextDouble(-2, 2);
                double oy = cy + rng.nextDouble(-2, 2);
                double oz = cz + rng.nextDouble(-2, 2);
                assertEquals(RayGrid.mark(fresh, ox, oy, oz, d[0], d[1], d[2], distance, kind),
                        RayGrid.mark(walked, ox, oy, oz, d[0], d[1], d[2], distance, kind));
            }
            for (int x = cx - 32; x <= cx + 32; x++) {
                for (int y = cy - 32; y <= cy + 32; y++) {
                    for (int z = cz - 32; z <= cz + 32; z++) {
                        if (fresh.get(x, y, z) != walked.get(x, y, z)) {
                            throw new AssertionError("round " + round + " at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void markingIsDeterministicAndIdempotent() {
        ObservedOccupancy first = new ObservedOccupancy(3, 4, 5);
        ObservedOccupancy second = new ObservedOccupancy(3, 4, 5);
        SplittableRandom a = new SplittableRandom(1234L);
        SplittableRandom b = new SplittableRandom(1234L);
        for (int i = 0; i < 200; i++) {
            double[] da = randomUnit(a);
            double[] db = randomUnit(b);
            double distA = a.nextDouble(0.0D, 25.0D);
            double distB = b.nextDouble(0.0D, 25.0D);
            RayGrid.HitKind ka = RayGrid.HitKind.values()[a.nextInt(3)];
            RayGrid.HitKind kb = RayGrid.HitKind.values()[b.nextInt(3)];
            RayGrid.mark(first, 3.5, 4.62, 5.5, da[0], da[1], da[2], distA, ka);
            RayGrid.mark(second, 3.5, 4.62, 5.5, db[0], db[1], db[2], distB, kb);
            // repeating the same observation must change nothing: `second` sees every ray three times
            RayGrid.mark(second, 3.5, 4.62, 5.5, db[0], db[1], db[2], distB, kb);
            RayGrid.mark(second, 3.5, 4.62, 5.5, db[0], db[1], db[2], distB, kb);
        }
        for (int x = first.minX(); x <= first.maxX(); x++) {
            for (int y = first.minY(); y <= first.maxY(); y++) {
                for (int z = first.minZ(); z <= first.maxZ(); z++) {
                    assertEquals(first.get(x, y, z), second.get(x, y, z));
                }
            }
        }
        assertTrue(countKnown(first) > 0);
    }
}
