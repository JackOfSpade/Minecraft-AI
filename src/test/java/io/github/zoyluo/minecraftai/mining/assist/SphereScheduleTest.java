package io.github.zoyluo.aibot.mining.assist;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphereScheduleTest {
    private static final int N = SphereSchedule.LATTICE_SIZE;
    private static final long UUID_MOST = 0x1234567890ABCDEFL;
    private static final long UUID_LEAST = 0x0FEDCBA987654321L;

    // ---- constants and index order ------------------------------------------------------------

    @Test
    void constantsMatchTheDesign() {
        assertEquals(2048, SphereSchedule.LATTICE_SIZE);
        assertEquals(797, SphereSchedule.STRIDE);
    }

    @Test
    void strideIsCoprimeWithLatticeSize() {
        assertEquals(1, gcd(SphereSchedule.STRIDE, SphereSchedule.LATTICE_SIZE));
    }

    @Test
    void oneSweepVisitsEveryLatticeIndexExactlyOnce() {
        BitSet seen = new BitSet(N);
        for (int k = 0; k < N; k++) {
            int idx = SphereSchedule.latticeIndex(k);
            assertTrue(idx >= 0 && idx < N, "index in range: " + idx);
            assertTrue(!seen.get(idx), "index visited twice: " + idx);
            seen.set(idx);
        }
        assertEquals(N, seen.cardinality());
    }

    @Test
    void latticeIndexFollowsTheStrideFormula() {
        assertEquals(0, SphereSchedule.latticeIndex(0));
        assertEquals(797, SphereSchedule.latticeIndex(1));
        assertEquals((2 * 797) % 2048, SphereSchedule.latticeIndex(2));
        assertEquals((100 * 797) % 2048, SphereSchedule.latticeIndex(100));
        assertEquals((2047 * 797) % 2048, SphereSchedule.latticeIndex(2047));
    }

    @Test
    void latticeIndexWrapsAfterAFullSweepAndAcceptsAnyInt() {
        for (int k = 0; k < 64; k++) {
            assertEquals(SphereSchedule.latticeIndex(k), SphereSchedule.latticeIndex(k + N));
        }
        int[] samples = {-1, -2048, -797, Integer.MIN_VALUE, Integer.MAX_VALUE, 123_456_789};
        for (int k : samples) {
            int expected = (int) Math.floorMod((long) k * 797L, 2048L);
            assertEquals(expected, SphereSchedule.latticeIndex(k), "visit " + k);
        }
    }

    // ---- unit length ---------------------------------------------------------------------------

    @Test
    void latticeDirectionsAreUnitLength() {
        for (int i = 0; i < N; i++) {
            SphereSchedule.Dir d = SphereSchedule.latticeDirection(i);
            assertEquals(1.0D, length(d), 1e-12, "slot " + i);
        }
    }

    @Test
    void rotatedDirectionsAreUnitLengthForManySweeps() {
        for (int sweep = 0; sweep < 200; sweep++) {
            SphereSchedule.Sweep s = SphereSchedule.sweep(UUID_MOST + sweep, UUID_LEAST, sweep);
            for (int k = 0; k < N; k += 7) {
                assertEquals(1.0D, length(s.direction(k)), 1e-12, "sweep " + sweep + " visit " + k);
            }
        }
    }

    @Test
    void latticeIsSymmetricInHeightAndCoversBothPoles() {
        double top = SphereSchedule.latticeDirection(0).dy();
        double bottom = SphereSchedule.latticeDirection(N - 1).dy();
        assertEquals(1.0D - 1.0D / N, top, 1e-12);
        assertEquals(-(1.0D - 1.0D / N), bottom, 1e-12);
    }

    // ---- determinism ---------------------------------------------------------------------------

    @Test
    void directionIsDeterministic() {
        for (int k = 0; k < N; k += 13) {
            assertEquals(
                    SphereSchedule.direction(k, UUID_MOST, UUID_LEAST, 5),
                    SphereSchedule.direction(k, UUID_MOST, UUID_LEAST, 5));
        }
    }

    @Test
    void sweepObjectAgreesWithTheStaticHelper() {
        SphereSchedule.Sweep sweep = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, 9);
        for (int k = 0; k < N; k += 11) {
            assertEquals(SphereSchedule.direction(k, UUID_MOST, UUID_LEAST, 9), sweep.direction(k));
            assertEquals(sweep.direction(k), sweep.latticeDirection(SphereSchedule.latticeIndex(k)));
        }
        assertEquals(SphereSchedule.sweepSeed(UUID_MOST, UUID_LEAST, 9), sweep.seed());
    }

    @Test
    void separatelyBuiltSweepsAreIdentical() {
        SphereSchedule.Sweep a = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, 3);
        SphereSchedule.Sweep b = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, 3);
        for (int i = 0; i < N; i++) {
            assertEquals(a.latticeDirection(i), b.latticeDirection(i));
        }
    }

    @Test
    void sweepWrapsModuloTheLatticeSize() {
        SphereSchedule.Sweep sweep = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, 0);
        assertEquals(sweep.direction(5), sweep.direction(5 + N));
        assertEquals(sweep.direction(0), sweep.direction(N));
    }

    @Test
    void latticeSlotsOutsideTheRangeAreRejected() {
        assertThrows(IndexOutOfBoundsException.class, () -> SphereSchedule.latticeDirection(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> SphereSchedule.latticeDirection(N));
        SphereSchedule.Sweep sweep = SphereSchedule.sweep(1L, 2L, 3);
        assertThrows(IndexOutOfBoundsException.class, () -> sweep.latticeDirection(N));
    }

    // ---- per-sweep rotation --------------------------------------------------------------------

    @Test
    void differentSweepIndexUuidOrHalvesGiveDifferentRotations() {
        Set<Long> seeds = new HashSet<>();
        Set<SphereSchedule.Dir> firstRays = new HashSet<>();
        for (int sweep = 0; sweep < 50; sweep++) {
            for (long uuid = 0; uuid < 20; uuid++) {
                assertTrue(seeds.add(SphereSchedule.sweepSeed(uuid * 0x9E3779B9L, ~uuid, sweep)),
                        "seed collision at uuid " + uuid + " sweep " + sweep);
                assertTrue(firstRays.add(SphereSchedule.direction(0, uuid * 0x9E3779B9L, ~uuid, sweep)),
                        "identical rotation at uuid " + uuid + " sweep " + sweep);
            }
        }
        assertEquals(1000, seeds.size());
    }

    @Test
    void consecutiveSweepsOfOneBotUseDifferentRotations() {
        SphereSchedule.Sweep first = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, 0);
        SphereSchedule.Sweep second = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, 1);
        assertNotEquals(first.direction(0), second.direction(0));
        assertNotEquals(first.direction(1), second.direction(1));
        assertTrue(first.direction(0).angleDegrees(second.direction(0)) > 0.5D
                || first.direction(1).angleDegrees(second.direction(1)) > 0.5D);
    }

    @Test
    void swappingUuidHalvesChangesTheRotation() {
        assertNotEquals(
                SphereSchedule.sweepSeed(UUID_MOST, UUID_LEAST, 4),
                SphereSchedule.sweepSeed(UUID_LEAST, UUID_MOST, 4));
    }

    @Test
    void allZeroInputsStillProduceARealRotation() {
        SphereSchedule.Sweep sweep = SphereSchedule.sweep(0L, 0L, 0);
        boolean moved = false;
        for (int i = 0; i < 8 && !moved; i++) {
            moved = sweep.latticeDirection(i).angleDegrees(SphereSchedule.latticeDirection(i)) > 1.0D;
        }
        assertTrue(moved, "seed 0 must not degenerate to the identity");
    }

    @Test
    void rotationIsRigidAndPreservesHandedness() {
        SphereSchedule.Sweep sweep = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, 17);
        SplittableRandom pick = new SplittableRandom(99);
        for (int n = 0; n < 200; n++) {
            int a = pick.nextInt(N);
            int b = pick.nextInt(N);
            int c = pick.nextInt(N);
            SphereSchedule.Dir a0 = SphereSchedule.latticeDirection(a);
            SphereSchedule.Dir b0 = SphereSchedule.latticeDirection(b);
            SphereSchedule.Dir c0 = SphereSchedule.latticeDirection(c);
            SphereSchedule.Dir a1 = sweep.latticeDirection(a);
            SphereSchedule.Dir b1 = sweep.latticeDirection(b);
            SphereSchedule.Dir c1 = sweep.latticeDirection(c);
            assertEquals(a0.dot(b0), a1.dot(b1), 1e-12, "angles are preserved");
            assertEquals(tripleProduct(a0, b0, c0), tripleProduct(a1, b1, c1), 1e-12,
                    "a proper rotation, not a reflection");
        }
    }

    @Test
    void rotationsAreUniformlySpreadOverTheSphere() {
        SphereSchedule.Dir probe = SphereSchedule.latticeDirection(1000);
        int sweeps = 4000;
        double sumX = 0.0D;
        double sumY = 0.0D;
        double sumZ = 0.0D;
        double sumYY = 0.0D;
        for (int s = 0; s < sweeps; s++) {
            SphereSchedule.Dir d = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, s).latticeDirection(1000);
            sumX += d.dx();
            sumY += d.dy();
            sumZ += d.dz();
            sumYY += d.dy() * d.dy();
        }
        assertEquals(0.0D, sumX / sweeps, 0.05D);
        assertEquals(0.0D, sumY / sweeps, 0.05D);
        assertEquals(0.0D, sumZ / sweeps, 0.05D);
        assertEquals(1.0D / 3.0D, sumYY / sweeps, 0.03D);
        assertEquals(1.0D, length(probe), 1e-12);
    }

    // ---- coverage contracts --------------------------------------------------------------------

    @Test
    void fullLatticeNearestNeighbourGapIsAtMost5_5Degrees() {
        double[][] lattice = latticeArrays();
        double worstNearest = 0.0D;
        double closestPair = 180.0D;
        for (int i = 0; i < N; i++) {
            double bestDot = -1.0D;
            for (int j = 0; j < N; j++) {
                if (i != j) {
                    bestDot = Math.max(bestDot, dot(lattice[i], lattice[j]));
                }
            }
            double gap = acosDegrees(bestDot);
            worstNearest = Math.max(worstNearest, gap);
            closestPair = Math.min(closestPair, gap);
        }
        assertTrue(worstNearest <= 5.5D, "max nearest-neighbour gap " + worstNearest);
        assertTrue(closestPair >= 3.0D, "no two lattice directions crowd together: " + closestPair);
    }

    @Test
    void fullLatticeLeavesNoHoleLargerThan5_5Degrees() {
        double[][] lattice = latticeArrays();
        assertTrue(maxGapToNearest(probeSphere(4096), lattice) <= 5.5D);
    }

    @Test
    void first256VisitedDirectionsLeaveAGapOfAtMost22Degrees() {
        double[][] probes = probeSphere(4096);
        assertTrue(maxGapToNearest(probes, visited(-1, 256)) <= 22.0D, "unrotated");
        for (int sweep = 0; sweep < 30; sweep++) {
            double gap = maxGapToNearest(probes, visited(sweep, 256));
            assertTrue(gap <= 22.0D, "sweep " + sweep + " gap " + gap);
        }
    }

    @Test
    void partialSweepsCoverEvenlyAtEveryPrefix() {
        double[][] probes = probeSphere(2048);
        // The gap must shrink as more rays are visited: this is what the stride buys.
        double gap64 = maxGapToNearest(probes, visited(-1, 64));
        double gap256 = maxGapToNearest(probes, visited(-1, 256));
        double gap1024 = maxGapToNearest(probes, visited(-1, 1024));
        assertTrue(gap64 < 45.0D, "64 rays: " + gap64);
        assertTrue(gap256 < gap64, "256 rays beat 64: " + gap256 + " vs " + gap64);
        assertTrue(gap1024 < gap256, "1024 rays beat 256: " + gap1024 + " vs " + gap256);
    }

    @Test
    void noOctantIsEmptyWithinTheFirst200Rays() {
        assertNoEmptyOctant(-1);
        for (int sweep = 0; sweep < 500; sweep++) {
            assertNoEmptyOctant(sweep);
        }
    }

    @Test
    void firstRaysOfEverySweepAreBalancedAcrossOctants() {
        int[] counts = octantCounts(-1, 200);
        for (int c : counts) {
            assertTrue(c >= 10, "unrotated octant count " + c);
        }
        for (int sweep = 0; sweep < 200; sweep++) {
            for (int c : octantCounts(sweep, 200)) {
                assertTrue(c >= 10, "sweep " + sweep + " octant count " + c);
            }
        }
    }

    @Test
    void someRayPointsNearlyStraightUpInEverySweep() {
        // upFree relies on a near-vertical ray existing regardless of the rotation.
        double cos55 = Math.cos(Math.toRadians(5.5D));
        for (int sweep = 0; sweep < 300; sweep++) {
            SphereSchedule.Sweep s = SphereSchedule.sweep(UUID_MOST, UUID_LEAST, sweep);
            double maxUp = -1.0D;
            for (int i = 0; i < N; i++) {
                maxUp = Math.max(maxUp, s.latticeDirection(i).dy());
            }
            assertTrue(maxUp >= cos55, "sweep " + sweep + " max dy " + maxUp);
        }
    }

    @Test
    void first256VisitedDirectionsAreMutuallyWellSpacedToo() {
        // The other reading of "max gap": each visited direction to its nearest OTHER visited one.
        for (int sweep = -1; sweep < 30; sweep++) {
            double[][] visited = visited(sweep, 256);
            double worst = 0.0D;
            for (int i = 0; i < visited.length; i++) {
                double best = -1.0D;
                for (int j = 0; j < visited.length; j++) {
                    if (i != j) {
                        best = Math.max(best, dot(visited[i], visited[j]));
                    }
                }
                worst = Math.max(worst, acosDegrees(best));
            }
            assertTrue(worst <= 22.0D, "sweep " + sweep + " nearest-visited gap " + worst);
        }
    }

    // ---- independent derivation and pinned known answers --------------------------------------

    @Test
    void rotationMatchesAnIndependentQuaternionDerivation() {
        // Re-derives the sweep from the documented recipe with separate code: an own 64-bit mixer, an
        // own SplittableRandom stream, and the quaternion sandwich product instead of a matrix.
        for (int sweep = -3; sweep < 25; sweep++) {
            long most = UUID_MOST + sweep;
            long seed = independentSeed(most, UUID_LEAST, sweep);
            assertEquals(seed, SphereSchedule.sweepSeed(most, UUID_LEAST, sweep), "seed, sweep " + sweep);

            long state = seed;
            double[] u = new double[3];
            for (int i = 0; i < 3; i++) {
                state += 0x9E3779B97F4A7C15L;
                u[i] = (independentMix(state) >>> 11) * 0x1.0p-53;
            }
            double a = Math.sqrt(1.0D - u[0]);
            double b = Math.sqrt(u[0]);
            double qx = a * Math.sin(2.0D * Math.PI * u[1]);
            double qy = a * Math.cos(2.0D * Math.PI * u[1]);
            double qz = b * Math.sin(2.0D * Math.PI * u[2]);
            double qw = b * Math.cos(2.0D * Math.PI * u[2]);

            SphereSchedule.Sweep actual = SphereSchedule.sweep(most, UUID_LEAST, sweep);
            for (int i = 0; i < N; i += 5) {
                SphereSchedule.Dir l = SphereSchedule.latticeDirection(i);
                double tx = 2.0D * (qy * l.dz() - qz * l.dy());
                double ty = 2.0D * (qz * l.dx() - qx * l.dz());
                double tz = 2.0D * (qx * l.dy() - qy * l.dx());
                SphereSchedule.Dir d = actual.latticeDirection(i);
                assertEquals(l.dx() + qw * tx + (qy * tz - qz * ty), d.dx(), 1e-12, "x, sweep " + sweep + " slot " + i);
                assertEquals(l.dy() + qw * ty + (qz * tx - qx * tz), d.dy(), 1e-12, "y, sweep " + sweep + " slot " + i);
                assertEquals(l.dz() + qw * tz + (qx * ty - qy * tx), d.dz(), 1e-12, "z, sweep " + sweep + " slot " + i);
            }
        }
    }

    @Test
    void latticeMatchesTheGoldenAngleFibonacciConstruction() {
        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int i = 0; i < N; i++) {
            double y = 1.0D - (2.0D * i + 1.0D) / N;
            double ring = Math.sqrt(1.0D - y * y);
            SphereSchedule.Dir d = SphereSchedule.latticeDirection(i);
            assertEquals(ring * Math.cos(golden * i), d.dx(), 1e-12, "x " + i);
            assertEquals(y, d.dy(), 1e-15, "y " + i);
            assertEquals(ring * Math.sin(golden * i), d.dz(), 1e-12, "z " + i);
        }
    }

    @Test
    void knownAnswersPinTheScheduleAcrossReleases() {
        // A change here changes every recorded sweep of every bot and breaks evidence replay.
        assertDir(0.031246185070D, 0.999511718750D, 0.0D, SphereSchedule.latticeDirection(0));
        assertDir(-0.039896642656D, 0.998535156250D, 0.036548592826D, SphereSchedule.latticeDirection(1));
        assertDir(-0.874189922813D, 0.221191406250D, 0.432280395869D, SphereSchedule.latticeDirection(797));
        assertDir(0.023363306633D, -0.999511718750D, -0.020748011582D, SphereSchedule.latticeDirection(2047));

        assertEquals(0xD2F6D0470BE1A7B1L, SphereSchedule.sweepSeed(1L, 2L, 0));
        assertEquals(0x30CFBC46A7E35530L, SphereSchedule.sweepSeed(1L, 2L, -1));
        assertEquals(0xB351046C5921D81AL, SphereSchedule.sweepSeed(1L, 2L, Integer.MIN_VALUE));
        assertEquals(0x2E21C2220B0787ECL, SphereSchedule.sweepSeed(1L, 2L, Integer.MAX_VALUE));
        assertEquals(0xAF9FA5F003C8D373L, SphereSchedule.sweepSeed(UUID_MOST, UUID_LEAST, 7));

        assertDir(-0.459018767724D, -0.157470333011D, 0.874359688628D, SphereSchedule.direction(0, 1L, 2L, 0));
        assertDir(-0.237197549176D, 0.934507783185D, 0.265391269321D, SphereSchedule.direction(1, 1L, 2L, 0));
        assertDir(0.680579819539D, -0.629470803808D, -0.374936816529D, SphereSchedule.direction(2, 1L, 2L, 0));
        assertDir(-0.061371826000D, 0.736600683972D, 0.673537624298D, SphereSchedule.direction(255, 1L, 2L, 0));
        assertDir(-0.465893364790D, 0.872225106064D, 0.148884979084D, SphereSchedule.direction(0, UUID_MOST, UUID_LEAST, 7));
        assertDir(0.181933074058D, 0.153544468505D, 0.971248913902D, SphereSchedule.direction(1, UUID_MOST, UUID_LEAST, 7));
    }

    @Test
    void extremeSweepIndicesGiveDistinctValidRotations() {
        int[] indices = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        Set<Long> seeds = new HashSet<>();
        for (int sweepIndex : indices) {
            assertTrue(seeds.add(SphereSchedule.sweepSeed(UUID_MOST, UUID_LEAST, sweepIndex)), "sweep " + sweepIndex);
            assertEquals(1.0D, length(SphereSchedule.direction(123, UUID_MOST, UUID_LEAST, sweepIndex)), 1e-12);
        }
        assertEquals(indices.length, seeds.size());
    }

    @Test
    void angleBetweenDirectionsIsInDegrees() {
        SphereSchedule.Dir x = new SphereSchedule.Dir(1, 0, 0);
        SphereSchedule.Dir y = new SphereSchedule.Dir(0, 1, 0);
        SphereSchedule.Dir negX = new SphereSchedule.Dir(-1, 0, 0);
        assertEquals(90.0D, x.angleDegrees(y), 1e-9);
        assertEquals(180.0D, x.angleDegrees(negX), 1e-9);
        assertEquals(0.0D, x.angleDegrees(x), 1e-6);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static void assertNoEmptyOctant(int sweep) {
        int[] counts = octantCounts(sweep, 200);
        for (int o = 0; o < 8; o++) {
            assertTrue(counts[o] > 0, "sweep " + sweep + " octant " + o + " is empty");
        }
    }

    private static int[] octantCounts(int sweep, int rays) {
        int[] counts = new int[8];
        SphereSchedule.Sweep s = sweep < 0 ? null : SphereSchedule.sweep(sweep * 31L + 5L, ~sweep * 17L, sweep);
        for (int k = 0; k < rays; k++) {
            SphereSchedule.Dir d = s == null
                    ? SphereSchedule.latticeDirection(SphereSchedule.latticeIndex(k))
                    : s.direction(k);
            int octant = (d.dx() >= 0 ? 1 : 0) | (d.dy() >= 0 ? 2 : 0) | (d.dz() >= 0 ? 4 : 0);
            counts[octant]++;
        }
        return counts;
    }

    /** The first {@code rays} visited directions; {@code sweep < 0} means the unrotated lattice. */
    private static double[][] visited(int sweep, int rays) {
        double[][] out = new double[rays][];
        SphereSchedule.Sweep s = sweep < 0 ? null : SphereSchedule.sweep(UUID_MOST + sweep, UUID_LEAST, sweep);
        for (int k = 0; k < rays; k++) {
            SphereSchedule.Dir d = s == null
                    ? SphereSchedule.latticeDirection(SphereSchedule.latticeIndex(k))
                    : s.direction(k);
            out[k] = new double[] {d.dx(), d.dy(), d.dz()};
        }
        return out;
    }

    private static double[][] latticeArrays() {
        double[][] out = new double[N][];
        for (int i = 0; i < N; i++) {
            SphereSchedule.Dir d = SphereSchedule.latticeDirection(i);
            out[i] = new double[] {d.dx(), d.dy(), d.dz()};
        }
        return out;
    }

    /** An independent, denser Fibonacci lattice used as probe points on the sphere. */
    private static double[][] probeSphere(int count) {
        double[][] out = new double[count][];
        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int i = 0; i < count; i++) {
            double y = 1.0D - (2.0D * i + 1.0D) / count;
            double ring = Math.sqrt(1.0D - y * y);
            out[i] = new double[] {ring * Math.cos(golden * i), y, ring * Math.sin(golden * i)};
        }
        return out;
    }

    /** Largest angular distance, in degrees, from any probe to its nearest sample. */
    private static double maxGapToNearest(double[][] probes, double[][] samples) {
        double worstCos = 1.0D;
        for (double[] probe : probes) {
            double best = -1.0D;
            for (double[] sample : samples) {
                best = Math.max(best, dot(probe, sample));
            }
            worstCos = Math.min(worstCos, best);
        }
        return acosDegrees(worstCos);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double acosDegrees(double cos) {
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, cos))));
    }

    private static double length(SphereSchedule.Dir d) {
        return Math.sqrt(d.dx() * d.dx() + d.dy() * d.dy() + d.dz() * d.dz());
    }

    private static double tripleProduct(SphereSchedule.Dir a, SphereSchedule.Dir b, SphereSchedule.Dir c) {
        double cx = b.dy() * c.dz() - b.dz() * c.dy();
        double cy = b.dz() * c.dx() - b.dx() * c.dz();
        double cz = b.dx() * c.dy() - b.dy() * c.dx();
        return a.dx() * cx + a.dy() * cy + a.dz() * cz;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static void assertDir(double x, double y, double z, SphereSchedule.Dir actual) {
        assertEquals(x, actual.dx(), 1e-9, "x");
        assertEquals(y, actual.dy(), 1e-9, "y");
        assertEquals(z, actual.dz(), 1e-9, "z");
    }

    /** Stafford variant 13, written out separately from the implementation under test. */
    private static long independentMix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long independentSeed(long most, long least, int sweepIndex) {
        long gamma = 0x9E3779B97F4A7C15L;
        long h = independentMix(most + gamma);
        h = independentMix(h ^ least);
        return independentMix(h + ((long) sweepIndex + 1L) * gamma);
    }
}
