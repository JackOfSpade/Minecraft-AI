package io.github.zoyluo.minecraftai.mining.assist;

import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeRunStatsTest {
    private static final int N = FreeRunStats.SIZE;
    private static final long EYE = BlockPos.asLong(10, -40, -20);
    private static final int TICK = 1000;
    private static final double FOUR_THIRDS_PI = 4.0D / 3.0D * Math.PI;

    // ---- constants -----------------------------------------------------------------------------

    @Test
    void constantsMatchTheDesign() {
        assertEquals(2048, FreeRunStats.SIZE);
        assertEquals(120, FreeRunStats.MAX_AGE_TICKS);
        assertEquals(4, FreeRunStats.MAX_EYE_SHIFT_BLOCKS);
        assertEquals(640, FreeRunStats.MIN_VALID_ENTRIES);
        assertEquals(12.0D, FreeRunStats.MIN_CAVERN_RADIUS);
        assertEquals(0.175D, FreeRunStats.CAVERN_FRACTION_FLOOR);
        assertEquals(0.554D, FreeRunStats.CAVERN_FRACTION_FLOOR + FreeRunStats.CAVERN_FRACTION_SPAN, 1e-12);
    }

    // ---- volumes: tunnel, room, full sphere ----------------------------------------------------

    @Test
    void fullSphereGivesTheFullSphereVolumeAndFractionOne() {
        double radius = 16.0D;
        FreeRunStats stats = fillFullSweep(0, d -> radius);

        double expected = FOUR_THIRDS_PI * radius * radius * radius;
        assertEquals(N, stats.validCount(TICK, EYE));
        assertEquals(expected, stats.volume(TICK, EYE, radius), expected * 1e-9);
        assertEquals(1.0D, stats.fraction(TICK, EYE, radius), 1e-9);
        assertEquals(radius, stats.meanFreeLength(TICK, EYE, radius), 1e-9);

        FreeRunStats.Openness o = stats.openness(TICK, EYE, radius);
        assertTrue(o.valid());
        assertEquals(N, o.count());
        assertEquals(1.0D, o.fraction(), 1e-9);
        assertEquals(1.0D, o.cavernC() / o.ceilingFactor(), 1e-9, "f = 1 maps to the full channel");
    }

    @Test
    void roomVolumeMatchesTheBoxVolume() {
        double radius = 16.0D;
        // Box of 6 x 6 x 4 blocks centred on the eye; its far corner (4.7) is inside the radius.
        double hx = 3.0D;
        double hy = 3.0D;
        double hz = 2.0D;
        for (int sweep = 0; sweep < 5; sweep++) {
            FreeRunStats stats = fillFullSweep(sweep, d -> boxRunLength(d, hx, hy, hz, radius));
            double expected = 8.0D * hx * hy * hz;
            assertEquals(expected, stats.volume(TICK, EYE, radius), expected * 0.01D, "sweep " + sweep);
        }
    }

    @Test
    void largeRoomIsMoreOpenThanSmallRoomAndBothStayBelowASphere() {
        double radius = 16.0D;
        FreeRunStats small = fillFullSweep(1, d -> boxRunLength(d, 3, 3, 2, radius));
        FreeRunStats large = fillFullSweep(1, d -> boxRunLength(d, 10, 10, 4, radius));
        double fSmall = small.fraction(TICK, EYE, radius);
        double fLarge = large.fraction(TICK, EYE, radius);
        assertTrue(fSmall < fLarge, fSmall + " < " + fLarge);
        assertTrue(fLarge < 1.0D);
        assertTrue(small.meanFreeLength(TICK, EYE, radius) < large.meanFreeLength(TICK, EYE, radius));
    }

    @Test
    void tunnelVolumeMatchesTheCylinderBallIntersection() {
        double radius = 16.0D;
        for (double a : new double[] {2.0D, 3.0D}) {
            // Infinite cylinder of radius a along x, clipped by the perception ball of radius R:
            // V = (4pi/3) * (R^3 - (R^2 - a^2)^(3/2)).
            double expected = FOUR_THIRDS_PI * (radius * radius * radius - Math.pow(radius * radius - a * a, 1.5D));
            for (int sweep = 0; sweep < 5; sweep++) {
                FreeRunStats stats = fillFullSweep(sweep, d -> Math.min(radius, a / Math.sqrt(Math.max(1e-12D, 1.0D - d.dx() * d.dx()))));
                assertEquals(expected, stats.volume(TICK, EYE, radius), expected * 0.03D, "a=" + a + " sweep " + sweep);
            }
        }
    }

    @Test
    void tunnelNeverLooksLikeACavernEvenFromAPartialSweep() {
        double radius = 16.0D;
        for (int rays : new int[] {640, 1024, N}) {
            FreeRunStats stats = fillSweep(rays, 3, d -> Math.min(radius, 2.0D / Math.sqrt(Math.max(1e-12D, 1.0D - d.dx() * d.dx()))));
            FreeRunStats.Openness o = stats.openness(TICK, EYE, radius);
            assertTrue(o.valid(), "rays " + rays);
            assertTrue(o.fraction() < 0.06D, "rays " + rays + " fraction " + o.fraction());
            assertEquals(0.0D, o.cavernC(), "rays " + rays);
        }
    }

    @Test
    void sixHundredFortyRaysGiveAUsableRoomEstimate() {
        double radius = 16.0D;
        FreeRunStats stats = fillSweep(FreeRunStats.MIN_VALID_ENTRIES, 2, d -> boxRunLength(d, 5, 5, 3, radius));
        FreeRunStats.Openness o = stats.openness(TICK, EYE, radius);
        assertTrue(o.valid());
        assertEquals(FreeRunStats.MIN_VALID_ENTRIES, o.count());
        double expected = 8.0D * 5 * 5 * 3;
        assertEquals(expected, o.volume(), expected * 0.05D);
    }

    @Test
    void meanFreeLengthIsTheMeanOfTheValidEntries() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(0, 2.0D, TICK, EYE);
        stats.record(1, 4.0D, TICK, EYE);
        stats.record(2, 9.0D, TICK, EYE);
        assertEquals(5.0D, stats.meanFreeLength(TICK, EYE, 16.0D), 1e-9);
        assertEquals(FOUR_THIRDS_PI * (8.0D + 64.0D + 729.0D) / 3.0D, stats.volume(TICK, EYE, 16.0D), 1e-6);
    }

    // ---- radius normalisation ------------------------------------------------------------------

    @Test
    void openFractionIsNormalisedByTheLiveRadius() {
        double targetFraction = 0.554D;
        double scale = Math.cbrt(targetFraction);
        for (double radius : new double[] {8.0D, 12.0D, 16.0D, 32.0D}) {
            // A spherical cavity whose radius is the same share of the perception radius.
            double cavity = scale * radius;
            FreeRunStats stats = fillFullSweep(0, d -> cavity);

            FreeRunStats.Openness o = stats.openness(TICK, EYE, radius);
            assertTrue(o.valid(), "R=" + radius);
            assertEquals(targetFraction, o.fraction(), 0.002D, "R=" + radius);
            assertEquals(FOUR_THIRDS_PI * cavity * cavity * cavity, o.volume(), o.volume() * 0.002D, "R=" + radius);
        }
    }

    @Test
    void cavernChannelIsDisabledBelowRadiusTwelveOnly() {
        double scale = Math.cbrt(0.554D);

        FreeRunStats.Openness r8 = fillFullSweep(0, d -> scale * 8.0D).openness(TICK, EYE, 8.0D);
        assertTrue(r8.valid());
        assertFalse(r8.cavernActive());
        assertEquals(0.0D, r8.cavernC());
        assertEquals(0.554D, r8.fraction(), 0.002D, "the fraction is still reported for the payload");

        FreeRunStats.Openness justBelow = fillFullSweep(0, d -> scale * 11.99D).openness(TICK, EYE, 11.99D);
        assertFalse(justBelow.cavernActive());
        assertEquals(0.0D, justBelow.cavernC());

        for (double radius : new double[] {12.0D, 16.0D, 32.0D}) {
            FreeRunStats.Openness o = fillFullSweep(0, d -> scale * radius).openness(TICK, EYE, radius);
            assertTrue(o.cavernActive(), "R=" + radius);
            assertTrue(o.cavernC() > 0.6D, "R=" + radius + " C=" + o.cavernC());
            assertEquals(1.0D, o.cavernC() / o.ceilingFactor(), 0.01D, "f=0.554 fills the channel at R=" + radius);
        }
    }

    @Test
    void ceilingDiscountGrowsWithTheRadiusForTheSameRelativeCavity() {
        double scale = Math.cbrt(0.554D);
        double c12 = fillFullSweep(0, d -> scale * 12.0D).openness(TICK, EYE, 12.0D).cavernC();
        double c16 = fillFullSweep(0, d -> scale * 16.0D).openness(TICK, EYE, 16.0D).cavernC();
        double c32 = fillFullSweep(0, d -> scale * 32.0D).openness(TICK, EYE, 32.0D).cavernC();
        // Cavity radii 9.86, 13.14 and 26.3: ceiling factors ~0.75, ~0.89 and 1.0.
        assertEquals(FreeRunStats.ceilingFactor(scale * 12.0D), c12, 0.01D);
        assertEquals(FreeRunStats.ceilingFactor(scale * 16.0D), c16, 0.01D);
        assertEquals(1.0D, c32, 1e-3D, "lengths are quantised, so f is 0.554 to within a hair");
        assertTrue(c12 < c16 && c16 < c32);
    }

    @Test
    void sameAbsoluteCavityReadsDifferentlyAtDifferentRadii() {
        // A lush-cave sized cavity (V about 4000) seen at three perception radii.
        double cavity = Math.cbrt(4000.0D * 3.0D / (4.0D * Math.PI));
        FreeRunStats stats = fillFullSweep(0, d -> cavity);

        double f16 = stats.openness(TICK, EYE, 16.0D).fraction();
        assertEquals(4000.0D / (FOUR_THIRDS_PI * 4096.0D), f16, 0.002D);
        assertEquals(0.233D, f16, 0.003D);

        double f32 = stats.openness(TICK, EYE, 32.0D).fraction();
        assertEquals(f16 / 8.0D, f32, 0.001D);
        assertEquals(0.0D, stats.openness(TICK, EYE, 32.0D).cavernC(), "far below the channel floor at R=32");

        // At R=16 the lush cave stays a weak signal: C well under a quarter.
        double c16 = stats.openness(TICK, EYE, 16.0D).cavernC();
        assertTrue(c16 > 0.05D && c16 < 0.25D, "C=" + c16);
    }

    @Test
    void lengthsBeyondTheLiveRadiusAreClampedToIt() {
        FreeRunStats stats = fillFullSweep(0, d -> 20.0D);
        // Entries were cast at R=20 but the radius was lowered to 16 afterwards.
        assertEquals(1.0D, stats.fraction(TICK, EYE, 16.0D), 1e-9);
        assertEquals(16.0D, stats.meanFreeLength(TICK, EYE, 16.0D), 1e-9);
        FreeRunStats.Openness o = stats.openness(TICK, EYE, 16.0D);
        assertTrue(o.fraction() <= 1.0D);
        assertTrue(o.volume() <= FreeRunStats.sphereVolume(16.0D) * (1.0D + 1e-9D));
    }

    @Test
    void unusableRadiusYieldsNoReading() {
        FreeRunStats stats = fillFullSweep(0, d -> 10.0D);
        for (double bad : new double[] {0.0D, -1.0D, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(stats.openness(TICK, EYE, bad).valid(), "radius " + bad);
            assertEquals(0.0D, stats.volume(TICK, EYE, bad));
            assertEquals(0.0D, stats.fraction(TICK, EYE, bad));
            assertEquals(0.0D, stats.upFree(TICK, EYE, bad));
            assertEquals(0.0D, stats.meanFreeLength(TICK, EYE, bad));
        }
        assertEquals(0.0D, FreeRunStats.fractionOf(100.0D, 0.0D));
        assertEquals(0.0D, FreeRunStats.fractionOf(-5.0D, 16.0D));
    }

    // ---- validity: age, eye shift, threshold ---------------------------------------------------

    @Test
    void entriesAgeOutAfterOneHundredTwentyTicks() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(7, 5.0D, TICK, EYE);

        assertEquals(1, stats.validCount(TICK, EYE), "same tick");
        assertEquals(1, stats.validCount(TICK + 1, EYE));
        assertEquals(1, stats.validCount(TICK + 119, EYE));
        assertEquals(1, stats.validCount(TICK + 120, EYE), "age 120 is still valid");
        assertEquals(0, stats.validCount(TICK + 121, EYE), "age 121 is stale");
        assertEquals(0, stats.validCount(TICK + 100_000, EYE));
        assertEquals(0, stats.validCount(TICK - 1, EYE), "an entry from the future is never valid");
    }

    @Test
    void entriesFromAFarawayEyeCellAreIgnored() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(3, 5.0D, TICK, EYE);
        int x = BlockPos.unpackLongX(EYE);
        int y = BlockPos.unpackLongY(EYE);
        int z = BlockPos.unpackLongZ(EYE);

        assertEquals(1, stats.validCount(TICK, EYE));
        assertEquals(1, stats.validCount(TICK, BlockPos.asLong(x + 4, y, z)), "4 blocks along x");
        assertEquals(1, stats.validCount(TICK, BlockPos.asLong(x, y - 4, z)), "4 blocks down");
        assertEquals(1, stats.validCount(TICK, BlockPos.asLong(x, y, z - 4)), "4 blocks along -z");
        assertEquals(1, stats.validCount(TICK, BlockPos.asLong(x - 2, y + 2, z + 2)), "sqrt(12) blocks");
        assertEquals(0, stats.validCount(TICK, BlockPos.asLong(x + 5, y, z)), "5 blocks along x");
        assertEquals(0, stats.validCount(TICK, BlockPos.asLong(x, y, z + 5)), "5 blocks along z");
    }

    @Test
    void eyeShiftIsMeasuredEuclideanNotChebyshev() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(3, 5.0D, TICK, EYE);
        int x = BlockPos.unpackLongX(EYE);
        int y = BlockPos.unpackLongY(EYE);
        int z = BlockPos.unpackLongZ(EYE);

        // Squared distance 16 is the boundary: (4,0,0) is in, (2,2,2)=12 is in, (3,3,0)=18 and
        // (3,2,3)=22 are out even though every axis is within 4.
        assertTrue(stats.isValid(3, TICK, BlockPos.asLong(x + 4, y, z)));
        assertTrue(stats.isValid(3, TICK, BlockPos.asLong(x + 2, y + 2, z + 2)));
        assertFalse(stats.isValid(3, TICK, BlockPos.asLong(x + 3, y + 3, z)));
        assertFalse(stats.isValid(3, TICK, BlockPos.asLong(x + 3, y + 2, z + 3)));
        assertFalse(stats.isValid(3, TICK, BlockPos.asLong(x + 4, y + 4, z + 4)));
    }

    @Test
    void eyeCellMathWorksForNegativeAndLargeCoordinates() {
        long far = BlockPos.asLong(-29_999_990, -60, 29_999_990);
        FreeRunStats stats = new FreeRunStats();
        stats.record(0, 3.0D, TICK, far);
        assertEquals(1, stats.validCount(TICK, far));
        assertEquals(1, stats.validCount(TICK, BlockPos.asLong(-29_999_993, -62, 29_999_989)));
        assertEquals(0, stats.validCount(TICK, BlockPos.asLong(-29_999_985, -60, 29_999_990)));
        assertEquals(0, stats.validCount(TICK, EYE));
    }

    @Test
    void unwrittenSlotsAreNeverValid() {
        FreeRunStats stats = new FreeRunStats();
        assertEquals(0, stats.validCount(TICK, EYE));
        assertFalse(stats.isValid(0, TICK, EYE));
        assertFalse(stats.openness(TICK, EYE, 16.0D).valid());
        assertEquals(0.0D, stats.volume(TICK, EYE, 16.0D));
        assertEquals(0.0D, stats.fraction(TICK, EYE, 16.0D));
        assertEquals(0.0D, stats.meanFreeLength(TICK, EYE, 16.0D));
        assertEquals(0.0D, stats.upFree(TICK, EYE, 16.0D));
    }

    @Test
    void opennessNeedsAtLeastSixHundredFortyValidEntries() {
        double radius = 16.0D;
        FreeRunStats stats = fillSweep(639, 0, d -> radius);
        FreeRunStats.Openness below = stats.openness(TICK, EYE, radius);
        assertFalse(below.valid());
        assertEquals(639, below.count());
        assertEquals(0.0D, below.volume());
        assertEquals(0.0D, below.fraction());
        assertEquals(0.0D, below.cavernC());
        assertFalse(below.cavernActive());

        // One more entry crosses the threshold.
        stats.record(SphereSchedule.latticeIndex(639), radius, 1.0D, TICK, EYE);
        FreeRunStats.Openness at = stats.openness(TICK, EYE, radius);
        assertTrue(at.valid());
        assertEquals(640, at.count());
        assertEquals(1.0D, at.fraction(), 1e-9);
    }

    @Test
    void staleAndDisplacedEntriesDoNotCountTowardTheThreshold() {
        double radius = 16.0D;
        FreeRunStats stats = new FreeRunStats();
        int x = BlockPos.unpackLongX(EYE);
        int y = BlockPos.unpackLongY(EYE);
        int z = BlockPos.unpackLongZ(EYE);
        long movedEye = BlockPos.asLong(x + 9, y, z);
        for (int k = 0; k < N; k++) {
            SphereSchedule.Dir d = SphereSchedule.direction(k, 5L, 6L, 0);
            int idx = SphereSchedule.latticeIndex(k);
            if (k < 600) {
                stats.record(idx, radius, d.dy(), TICK, EYE);          // fresh, cast here
            } else if (k < 1200) {
                stats.record(idx, radius, d.dy(), TICK - 500, EYE);    // stale
            } else {
                stats.record(idx, radius, d.dy(), TICK, movedEye);     // fresh but cast elsewhere
            }
        }
        assertEquals(600, stats.validCount(TICK, EYE));
        assertFalse(stats.openness(TICK, EYE, radius).valid(), "600 valid entries are not enough");
        assertEquals(N - 1200, stats.validCount(TICK, movedEye), "the moved-eye entries are valid from there");
    }

    @Test
    void overwritingASlotRefreshesItsAge() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(11, 5.0D, TICK, EYE);
        assertEquals(0, stats.validCount(TICK + 200, EYE));
        stats.record(11, 6.0D, TICK + 200, EYE);
        assertEquals(1, stats.validCount(TICK + 200, EYE));
        assertEquals(6.0D, stats.meanFreeLength(TICK + 200, EYE, 16.0D), 1e-9);
    }

    @Test
    void clearForgetsEverything() {
        FreeRunStats stats = fillFullSweep(0, d -> 10.0D);
        assertEquals(N, stats.validCount(TICK, EYE));
        stats.clear();
        assertEquals(0, stats.validCount(TICK, EYE));
    }

    // ---- quantisation and input handling -------------------------------------------------------

    @Test
    void lengthsAreQuantisedToAtMostHalfAQuantum() {
        double[] samples = {0.0D, 0.001D, 1.5D, 7.7777D, 12.3456D, 15.999D, 16.0D, 100.123D};
        for (double sample : samples) {
            FreeRunStats stats = new FreeRunStats();
            stats.record(0, sample, TICK, EYE);
            assertEquals(sample, stats.meanFreeLength(TICK, EYE, 1000.0D),
                    FreeRunStats.LENGTH_QUANTUM / 2.0D + 1e-12D, "sample " + sample);
        }
    }

    @Test
    void extremeLengthsSaturateInsteadOfWrapping() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(0, -5.0D, TICK, EYE);
        assertEquals(0.0D, stats.meanFreeLength(TICK, EYE, 1000.0D), 1e-12, "negative stores as zero");
        stats.record(0, 1e9D, TICK, EYE);
        double saturated = stats.meanFreeLength(TICK, EYE, 1e12D);
        assertTrue(saturated > 255.0D && saturated < 256.0D, "saturates near 256, got " + saturated);
        stats.record(0, 200.0D, TICK, EYE);
        assertEquals(200.0D, stats.meanFreeLength(TICK, EYE, 1000.0D), 1e-9, "values above 128 survive the short");
    }

    @Test
    void nonFiniteLengthsAreDropped() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(0, Double.NaN, TICK, EYE);
        stats.record(1, Double.POSITIVE_INFINITY, TICK, EYE);
        assertEquals(0, stats.validCount(TICK, EYE));
        stats.record(2, 4.0D, TICK, EYE);
        stats.record(2, Double.NaN, TICK, EYE);
        assertEquals(4.0D, stats.meanFreeLength(TICK, EYE, 16.0D), 1e-9, "a NaN record keeps the previous entry");
    }

    @Test
    void outOfRangeSlotsAreRejected() {
        FreeRunStats stats = new FreeRunStats();
        assertThrows(IndexOutOfBoundsException.class, () -> stats.record(-1, 1.0D, TICK, EYE));
        assertThrows(IndexOutOfBoundsException.class, () -> stats.record(N, 1.0D, TICK, EYE));
        assertThrows(IndexOutOfBoundsException.class, () -> stats.isValid(N, TICK, EYE));
    }

    // ---- upFree and the ceiling term -----------------------------------------------------------

    @Test
    void upFreeIsTheHeightOfTheHighestProvenFreePoint() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(0, 10.0D, 0.5D, TICK, EYE);     // 5.0 above the eye
        stats.record(1, 6.0D, 0.9D, TICK, EYE);      // 5.4 above the eye
        stats.record(2, 15.0D, -0.9D, TICK, EYE);    // downward: never counts
        stats.record(3, 15.0D, 0.0D, TICK, EYE);     // horizontal: never counts
        assertEquals(5.4D, stats.upFree(TICK, EYE, 16.0D), 0.01D);
    }

    @Test
    void upFreeIgnoresEntriesWithoutADirectionButKeepsThemForVolume() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(0, 12.0D, TICK, EYE);
        assertEquals(0.0D, stats.upFree(TICK, EYE, 16.0D));
        assertEquals(1, stats.validCount(TICK, EYE));
        stats.record(1, 12.0D, Double.NaN, TICK, EYE);
        assertEquals(0.0D, stats.upFree(TICK, EYE, 16.0D));
    }

    @Test
    void upFreeIgnoresStaleAndDisplacedEntries() {
        FreeRunStats stats = new FreeRunStats();
        stats.record(0, 10.0D, 1.0D, TICK - 500, EYE);
        stats.record(1, 10.0D, 1.0D, TICK, BlockPos.asLong(0, 0, 0));
        assertEquals(0.0D, stats.upFree(TICK, EYE, 16.0D));
        stats.record(2, 8.0D, 1.0D, TICK, EYE);
        assertEquals(8.0D, stats.upFree(TICK, EYE, 16.0D), 0.01D);
    }

    @Test
    void flatCeilingIsMeasuredExactlyWhateverTheRaySlant() {
        double radius = 16.0D;
        for (double height : new double[] {4.0D, 8.0D, 12.0D}) {
            for (int sweep = 0; sweep < 4; sweep++) {
                FreeRunStats stats = fillFullSweep(sweep, d -> d.dy() > 0.0D ? Math.min(radius, height / d.dy()) : radius);
                assertEquals(height, stats.upFree(TICK, EYE, radius), 0.02D, "height " + height + " sweep " + sweep);
            }
        }
    }

    @Test
    void openSkyIsBoundedByTheRadius() {
        double radius = 16.0D;
        for (int sweep = 0; sweep < 8; sweep++) {
            FreeRunStats stats = fillFullSweep(sweep, d -> radius);
            double up = stats.upFree(TICK, EYE, radius);
            assertTrue(up <= radius, "up " + up);
            assertTrue(up > radius * Math.cos(Math.toRadians(5.5D)), "some ray is nearly vertical: " + up);
        }
    }

    @Test
    void ceilingFactorFollowsTheDesignFormula() {
        assertEquals(0.6D, FreeRunStats.ceilingFactor(0.0D), 1e-12);
        assertEquals(0.6D, FreeRunStats.ceilingFactor(6.0D), 1e-12);
        assertEquals(0.8D, FreeRunStats.ceilingFactor(11.0D), 1e-12);
        assertEquals(1.0D, FreeRunStats.ceilingFactor(16.0D), 1e-12);
        assertEquals(1.0D, FreeRunStats.ceilingFactor(20.0D), 1e-12);
        assertEquals(0.6D, FreeRunStats.ceilingFactor(-3.0D), 1e-12);
    }

    @Test
    void cavernChannelFollowsTheDesignFormula() {
        assertEquals(0.0D, FreeRunStats.cavernChannel(0.0D, 20.0D), 1e-12);
        assertEquals(0.0D, FreeRunStats.cavernChannel(0.175D, 20.0D), 1e-12);
        assertEquals(1.0D, FreeRunStats.cavernChannel(0.554D, 20.0D), 1e-12);
        assertEquals(1.0D, FreeRunStats.cavernChannel(0.9D, 20.0D), 1e-12, "clamped to 1");
        assertEquals(0.5D * 0.6D, FreeRunStats.cavernChannel(0.175D + 0.379D / 2.0D, 6.0D), 1e-12);
        assertEquals(0.6D, FreeRunStats.cavernChannel(0.554D, 0.0D), 1e-12, "a low ceiling caps the channel at 0.6");
        // Design fixture: f = 0.554 and upFree 20 give the full channel (T = 0.85 downstream).
        assertEquals(1.0D, FreeRunStats.cavernChannel(0.554D, 20.0D), 1e-12);
    }

    @Test
    void opennessCombinesFractionAndCeiling() {
        double radius = 32.0D;
        double cavity = 20.0D;
        FreeRunStats stats = fillFullSweep(2, d -> cavity);
        FreeRunStats.Openness o = stats.openness(TICK, EYE, radius);
        double f = Math.pow(cavity / radius, 3.0D);
        assertEquals(f, o.fraction(), 0.001D);
        assertTrue(o.upFree() > 19.0D && o.upFree() <= cavity);
        assertEquals(FreeRunStats.ceilingFactor(o.upFree()), o.ceilingFactor(), 1e-12);
        assertEquals(FreeRunStats.cavernChannel(o.fraction(), o.upFree()), o.cavernC(), 1e-12);
        assertTrue(o.cavernActive());
    }

    // ---- determinism ---------------------------------------------------------------------------

    @Test
    void identicalInputsGiveIdenticalReadings() {
        ToDoubleFunction<SphereSchedule.Dir> room = d -> boxRunLength(d, 6, 4, 5, 16.0D);
        FreeRunStats a = fillFullSweep(3, room);
        FreeRunStats b = fillFullSweep(3, room);
        assertEquals(a.openness(TICK, EYE, 16.0D), b.openness(TICK, EYE, 16.0D));
        assertEquals(a.openness(TICK + 50, EYE, 16.0D), b.openness(TICK + 50, EYE, 16.0D));
    }

    // ---- randomised cross-check against a naive model ------------------------------------------

    @Test
    void matchesANaiveReferenceOnRandomRings() {
        int validReadings = 0;
        int invalidReadings = 0;
        for (long seed = 1; seed <= 24; seed++) {
            SplittableRandom random = new SplittableRandom(seed);
            boolean tight = (seed & 1) == 0;             // tight: nearly every write is fresh and near
            int baseX = 100 + random.nextInt(50);
            int baseY = -30 + random.nextInt(20);
            int baseZ = -50 + random.nextInt(50);
            int eyeSpread = tight ? 1 : 5;
            int stampSpread = tight ? 100 : 260;

            FreeRunStats stats = new FreeRunStats();
            double[][] model = new double[N][];
            int writes = 1500 + random.nextInt(4000);
            for (int w = 0; w < writes; w++) {
                int idx = random.nextInt(N);
                double length = random.nextInt(4) == 0 ? random.nextDouble() * 70.0D : random.nextDouble() * 20.0D;
                if (random.nextInt(80) == 0) {
                    length = Double.NaN;
                } else if (random.nextInt(80) == 0) {
                    length = -random.nextDouble() * 5.0D;
                }
                double dirY = random.nextInt(6) == 0 ? Double.NaN : random.nextDouble() * 2.2D - 1.1D;
                int stamp = 1000 + random.nextInt(stampSpread);
                long eye = BlockPos.asLong(
                        baseX + random.nextInt(2 * eyeSpread + 1) - eyeSpread,
                        baseY + random.nextInt(2 * eyeSpread + 1) - eyeSpread,
                        baseZ + random.nextInt(2 * eyeSpread + 1) - eyeSpread);
                stats.record(idx, length, dirY, stamp, eye);
                if (Double.isFinite(length)) {
                    model[idx] = new double[] {
                        Math.round(Math.max(0.0D, Math.min(65535.0D / 256.0D, length)) * 256.0D) / 256.0D,
                        Double.isNaN(dirY) ? Double.NaN : Math.round(Math.max(-1.0D, Math.min(1.0D, dirY)) * 32767.0D) / 32767.0D,
                        stamp, BlockPos.unpackLongX(eye), BlockPos.unpackLongY(eye), BlockPos.unpackLongZ(eye)};
                }
            }

            for (int now : new int[] {1010, 1080, 1150, 1230, 1300}) {
                long eyeNow = BlockPos.asLong(
                        baseX + random.nextInt(2 * eyeSpread + 1) - eyeSpread,
                        baseY + random.nextInt(2 * eyeSpread + 1) - eyeSpread,
                        baseZ + random.nextInt(2 * eyeSpread + 1) - eyeSpread);
                for (double radius : new double[] {6.0D, 11.99D, 12.0D, 16.0D, 32.5D}) {
                    int count = 0;
                    double sumL = 0.0D;
                    double sumL3 = 0.0D;
                    double up = 0.0D;
                    for (int i = 0; i < N; i++) {
                        if (model[i] == null) {
                            continue;
                        }
                        double[] e = model[i];
                        long age = (long) now - (long) e[2];
                        long dx = (long) e[3] - BlockPos.unpackLongX(eyeNow);
                        long dy = (long) e[4] - BlockPos.unpackLongY(eyeNow);
                        long dz = (long) e[5] - BlockPos.unpackLongZ(eyeNow);
                        boolean valid = age >= 0 && age <= 120 && dx * dx + dy * dy + dz * dz <= 16;
                        assertEquals(valid, stats.isValid(i, now, eyeNow), "isValid " + i);
                        if (!valid) {
                            continue;
                        }
                        double length = Math.min(e[0], radius);
                        count++;
                        sumL += length;
                        sumL3 += length * length * length;
                        if (!Double.isNaN(e[1]) && e[1] > 0.0D) {
                            up = Math.max(up, length * e[1]);
                        }
                    }
                    String at = "seed " + seed + " now " + now + " R " + radius;
                    assertEquals(count, stats.validCount(now, eyeNow), at + " count");
                    double volume = count == 0 ? 0.0D : FOUR_THIRDS_PI * (sumL3 / count);
                    double fraction = Math.min(1.0D, volume / (FOUR_THIRDS_PI * radius * radius * radius));
                    assertEquals(count == 0 ? 0.0D : sumL / count, stats.meanFreeLength(now, eyeNow, radius), 1e-9, at + " mean");
                    assertEquals(volume, stats.volume(now, eyeNow, radius), 1e-9 * Math.max(1.0D, volume), at + " volume");
                    assertEquals(fraction, stats.fraction(now, eyeNow, radius), 1e-9, at + " fraction");
                    assertEquals(up, stats.upFree(now, eyeNow, radius), 1e-9, at + " upFree");

                    FreeRunStats.Openness o = stats.openness(now, eyeNow, radius);
                    assertEquals(count, o.count(), at + " openness count");
                    assertEquals(count >= 640, o.valid(), at + " openness valid");
                    if (o.valid()) {
                        validReadings++;
                        double ceiling = 0.6D + 0.4D * Math.max(0.0D, Math.min(1.0D, (up - 6.0D) / 10.0D));
                        double cavern = radius >= 12.0D
                                ? Math.max(0.0D, Math.min(1.0D, (fraction - 0.175D) / 0.379D)) * ceiling
                                : 0.0D;
                        assertEquals(volume, o.volume(), 1e-9 * Math.max(1.0D, volume), at + " o.volume");
                        assertEquals(fraction, o.fraction(), 1e-9, at + " o.fraction");
                        assertEquals(up, o.upFree(), 1e-9, at + " o.upFree");
                        assertEquals(ceiling, o.ceilingFactor(), 1e-9, at + " o.ceiling");
                        assertEquals(cavern, o.cavernC(), 1e-9, at + " o.cavernC");
                        assertEquals(radius >= 12.0D, o.cavernActive(), at + " o.cavernActive");
                        assertTrue(o.fraction() >= 0.0D && o.fraction() <= 1.0D);
                        assertTrue(o.cavernC() >= 0.0D && o.cavernC() <= 1.0D);
                    } else {
                        invalidReadings++;
                        assertEquals(0.0D, o.cavernC(), at);
                        assertFalse(o.cavernActive(), at);
                    }
                }
            }
        }
        assertTrue(validReadings > 100, "the cross-check must exercise valid readings: " + validReadings);
        assertTrue(invalidReadings > 100, "and invalid ones: " + invalidReadings);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Records {@code rays} rays of sweep {@code sweepIndex} as if cast this tick from {@link #EYE}. */
    private static FreeRunStats fillSweep(int rays, int sweepIndex, ToDoubleFunction<SphereSchedule.Dir> freeLength) {
        FreeRunStats stats = new FreeRunStats();
        SphereSchedule.Sweep sweep = SphereSchedule.sweep(0x1122334455667788L, 0x99AABBCCDDEEFF00L, sweepIndex);
        for (int k = 0; k < rays; k++) {
            SphereSchedule.Dir dir = sweep.direction(k);
            stats.record(SphereSchedule.latticeIndex(k), freeLength.applyAsDouble(dir), dir.dy(), TICK, EYE);
        }
        return stats;
    }

    private static FreeRunStats fillFullSweep(int sweepIndex, ToDoubleFunction<SphereSchedule.Dir> freeLength) {
        return fillSweep(N, sweepIndex, freeLength);
    }

    /** Free run inside an axis-aligned box centred on the eye, clamped to the perception radius. */
    private static double boxRunLength(SphereSchedule.Dir d, double hx, double hy, double hz, double radius) {
        double tx = Math.abs(d.dx()) < 1e-12D ? Double.POSITIVE_INFINITY : hx / Math.abs(d.dx());
        double ty = Math.abs(d.dy()) < 1e-12D ? Double.POSITIVE_INFINITY : hy / Math.abs(d.dy());
        double tz = Math.abs(d.dz()) < 1e-12D ? Double.POSITIVE_INFINITY : hz / Math.abs(d.dz());
        return Math.min(radius, Math.min(tx, Math.min(ty, tz)));
    }
}
