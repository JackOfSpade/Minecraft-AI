package io.github.zoyluo.minecraftai.mining.assist;

import java.util.SplittableRandom;

/**
 * Deterministic view-ray schedule for the sensor (design 3.2): a 2048-direction golden-angle
 * Fibonacci lattice on the unit sphere, visited in the order {@code idx = (k * 797) mod 2048}, and
 * randomly rotated once per sweep.
 *
 * <ul>
 *   <li>The stride 797 is odd, hence coprime with 2048, so one sweep of 2048 visits touches every
 *       lattice index exactly once, and any partial sweep is spread evenly over the sphere.</li>
 *   <li>The rotation is a uniformly distributed random rotation derived only from
 *       {@code (uuidMost, uuidLeast, sweepIndex)}, so a bot never sweeps the same rays twice in a
 *       row, yet every schedule is reproducible.</li>
 *   <li>All trigonometry goes through {@link StrictMath}, so the directions are bit-identical on
 *       every JVM and platform.</li>
 * </ul>
 *
 * <p>The ring slot of a ray is its <em>lattice index</em> ({@link #latticeIndex}), not the rotated
 * direction, so the ring size stays fixed at {@link #LATTICE_SIZE}.</p>
 */
public final class SphereSchedule {
    /** Number of lattice directions, and the length of one full sweep. */
    public static final int LATTICE_SIZE = 2048;
    /** Visit stride; coprime with {@link #LATTICE_SIZE}. */
    public static final int STRIDE = 797;

    private static final double GOLDEN_ANGLE = StrictMath.PI * (3.0D - StrictMath.sqrt(5.0D));
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    /** Unrotated lattice, x/y/z interleaved. Built once, never mutated. */
    private static final double[] LATTICE = buildLattice();

    private SphereSchedule() {
    }

    /** A unit direction in world space (y is up). */
    public record Dir(double dx, double dy, double dz) {
        public double dot(Dir other) {
            return dx * other.dx + dy * other.dy + dz * other.dz;
        }

        /** Angle to another direction in degrees, in [0, 180]. */
        public double angleDegrees(Dir other) {
            double cos = Math.max(-1.0D, Math.min(1.0D, dot(other)));
            return Math.toDegrees(StrictMath.acos(cos));
        }
    }

    /**
     * The rotation of one sweep. Build it once per sweep and call {@link #direction} for every ray;
     * the static {@link SphereSchedule#direction} helper rebuilds the rotation on every call.
     * Immutable.
     */
    public static final class Sweep {
        private final long seed;
        private final double m00;
        private final double m01;
        private final double m02;
        private final double m10;
        private final double m11;
        private final double m12;
        private final double m20;
        private final double m21;
        private final double m22;

        private Sweep(long seed) {
            this.seed = seed;
            SplittableRandom random = new SplittableRandom(seed);
            // Shoemake's method: a uniformly distributed unit quaternion from three uniform numbers.
            double u1 = random.nextDouble();
            double u2 = random.nextDouble();
            double u3 = random.nextDouble();
            double a = StrictMath.sqrt(1.0D - u1);
            double b = StrictMath.sqrt(u1);
            double t1 = 2.0D * StrictMath.PI * u2;
            double t2 = 2.0D * StrictMath.PI * u3;
            double qx = a * StrictMath.sin(t1);
            double qy = a * StrictMath.cos(t1);
            double qz = b * StrictMath.sin(t2);
            double qw = b * StrictMath.cos(t2);
            this.m00 = 1.0D - 2.0D * (qy * qy + qz * qz);
            this.m01 = 2.0D * (qx * qy - qz * qw);
            this.m02 = 2.0D * (qx * qz + qy * qw);
            this.m10 = 2.0D * (qx * qy + qz * qw);
            this.m11 = 1.0D - 2.0D * (qx * qx + qz * qz);
            this.m12 = 2.0D * (qy * qz - qx * qw);
            this.m20 = 2.0D * (qx * qz - qy * qw);
            this.m21 = 2.0D * (qy * qz + qx * qw);
            this.m22 = 1.0D - 2.0D * (qx * qx + qy * qy);
        }

        /** The seed this rotation was derived from ({@link SphereSchedule#sweepSeed}). */
        public long seed() {
            return seed;
        }

        /** Rotated direction of the {@code visitIndex}-th ray of the sweep. Wraps modulo 2048. */
        public Dir direction(int visitIndex) {
            return latticeDirection(latticeIndex(visitIndex));
        }

        /** Rotated direction of one lattice slot. */
        public Dir latticeDirection(int latticeIdx) {
            int base = checkLatticeIndex(latticeIdx) * 3;
            double x = LATTICE[base];
            double y = LATTICE[base + 1];
            double z = LATTICE[base + 2];
            return new Dir(
                    m00 * x + m01 * y + m02 * z,
                    m10 * x + m11 * y + m12 * z,
                    m20 * x + m21 * y + m22 * z);
        }
    }

    /**
     * Lattice slot of the {@code visitIndex}-th ray of a sweep: {@code (visitIndex * 797) mod 2048}.
     * Any int is accepted (the sweep wraps), and the result is always in [0, 2048).
     */
    public static int latticeIndex(int visitIndex) {
        return (int) (((long) visitIndex * STRIDE) & (LATTICE_SIZE - 1));
    }

    /** The unrotated lattice direction of a slot, in [0, 2048). */
    public static Dir latticeDirection(int latticeIdx) {
        int base = checkLatticeIndex(latticeIdx) * 3;
        return new Dir(LATTICE[base], LATTICE[base + 1], LATTICE[base + 2]);
    }

    /** Seed of a sweep's rotation: a fixed 64-bit mix of the bot uuid halves and the sweep index. */
    public static long sweepSeed(long uuidMost, long uuidLeast, int sweepIndex) {
        long h = mix64(uuidMost + GOLDEN_GAMMA);
        h = mix64(h ^ uuidLeast);
        h = mix64(h + (sweepIndex + 1L) * GOLDEN_GAMMA);
        return h;
    }

    /** The rotation of one sweep. */
    public static Sweep sweep(long uuidMost, long uuidLeast, int sweepIndex) {
        return new Sweep(sweepSeed(uuidMost, uuidLeast, sweepIndex));
    }

    /**
     * Direction of the {@code visitIndex}-th ray of sweep {@code sweepIndex} for the bot with the
     * given uuid halves: unit length, deterministic. Convenience for one-off use; per-tick callers
     * should hold a {@link Sweep}.
     */
    public static Dir direction(int visitIndex, long uuidMost, long uuidLeast, int sweepIndex) {
        return sweep(uuidMost, uuidLeast, sweepIndex).direction(visitIndex);
    }

    private static int checkLatticeIndex(int latticeIdx) {
        if (latticeIdx < 0 || latticeIdx >= LATTICE_SIZE) {
            throw new IndexOutOfBoundsException("lattice index " + latticeIdx);
        }
        return latticeIdx;
    }

    private static double[] buildLattice() {
        double[] lattice = new double[LATTICE_SIZE * 3];
        for (int i = 0; i < LATTICE_SIZE; i++) {
            double y = 1.0D - (2.0D * i + 1.0D) / LATTICE_SIZE;
            double ring = StrictMath.sqrt(Math.max(0.0D, 1.0D - y * y));
            double angle = GOLDEN_ANGLE * i;
            lattice[i * 3] = ring * StrictMath.cos(angle);
            lattice[i * 3 + 1] = y;
            lattice[i * 3 + 2] = ring * StrictMath.sin(angle);
        }
        return lattice;
    }

    /** Stafford variant 13 of the murmur3 64-bit finaliser. */
    private static long mix64(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
