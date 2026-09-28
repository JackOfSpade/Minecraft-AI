package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Arrays;
import java.util.Objects;

import net.minecraft.util.math.BlockPos;

/**
 * The ring of view-ray free lengths and the openness statistics derived from it (design 3.3, 6.3).
 * One slot per {@link SphereSchedule} lattice index holds the free length of the most recent ray
 * cast through that slot (quantised to a {@code short}), the server tick it was cast, and the eye
 * cell it was cast from. Pure data: no world access, time is always a caller-supplied server tick.
 *
 * <p><b>Validity.</b> The sweep does not restart when the bot moves, so an entry is used only if it
 * is fresh and was cast near where the bot is now: its age {@code nowTick - stampTick} is in
 * [0, {@link #MAX_AGE_TICKS}] and the Euclidean distance between the stored eye cell and the
 * current one is at most {@link #MAX_EYE_SHIFT_BLOCKS} blocks (cell coordinates, squared distance
 * at most 16; stricter than a Chebyshev cube, which would admit shifts of about 6.9 blocks).
 * Eye cells are packed with {@link BlockPos#asLong()}. Openness needs at least
 * {@link #MIN_VALID_ENTRIES} valid entries.</p>
 *
 * <p><b>Radius.</b> Every free length is clamped to the live perception radius {@code R} passed to
 * the query. A ray that reaches its range contributes {@code R}, so a fully open surrounding gives
 * {@code V = (4pi/3) R^3} and {@code f = 1}. Clamping is a no-op for entries cast at this radius; it
 * only matters when the radius was lowered after entries were written.</p>
 *
 * <p>Not thread-safe; owned by one bot's sensing state on the server thread.</p>
 */
public final class FreeRunStats {
    public static final int SIZE = SphereSchedule.LATTICE_SIZE;
    /** Oldest usable entry, in server ticks. */
    public static final int MAX_AGE_TICKS = 120;
    /** Largest eye shift, in blocks (Euclidean, on cell coordinates). */
    public static final int MAX_EYE_SHIFT_BLOCKS = 4;
    /** Fewest valid entries for a usable openness reading. */
    public static final int MIN_VALID_ENTRIES = 640;
    /** The cavern channel is disabled below this live perception radius. */
    public static final double MIN_CAVERN_RADIUS = 12.0D;
    /** Open fraction that maps to a cavern score of 0. */
    public static final double CAVERN_FRACTION_FLOOR = 0.175D;
    /** Width of the open-fraction range mapped onto [0, 1]: 0.554 - 0.175. */
    public static final double CAVERN_FRACTION_SPAN = 0.379D;
    /** Free-length quantum: lengths are stored as unsigned 16-bit multiples of 1/256 block. */
    public static final double LENGTH_QUANTUM = 1.0D / 256.0D;

    private static final double MAX_STORED_LENGTH = 65535.0D * LENGTH_QUANTUM;
    private static final short DIR_Y_UNKNOWN = Short.MIN_VALUE;
    private static final double DIR_Y_SCALE = 32767.0D;
    private static final double FOUR_THIRDS_PI = 4.0D / 3.0D * Math.PI;
    private static final int MAX_EYE_SHIFT_SQ = MAX_EYE_SHIFT_BLOCKS * MAX_EYE_SHIFT_BLOCKS;

    /**
     * Result of {@link #openness}. When {@code valid} is false every other numeric field is zero
     * except {@code count}, and no cavern signal exists.
     *
     * @param valid         at least {@link #MIN_VALID_ENTRIES} entries were valid
     * @param count         number of valid entries
     * @param volume        V = (4pi/3) * mean(L^3), in blocks cubed
     * @param fraction      f = V / ((4pi/3) R^3), in [0, 1]
     * @param upFree        largest observed free height above the eye, in blocks (see {@link #upFree})
     * @param ceilingFactor 0.6 + 0.4 * clamp((upFree - 6) / 10, 0, 1)
     * @param cavernC       clamp((f - 0.175) / 0.379, 0, 1) * ceilingFactor, or 0 when not active
     * @param cavernActive  valid and R at least {@link #MIN_CAVERN_RADIUS}; the caller additionally
     *                      gates on the dimension list
     */
    public record Openness(
            boolean valid,
            int count,
            double volume,
            double fraction,
            double upFree,
            double ceilingFactor,
            double cavernC,
            boolean cavernActive) {

        static Openness notValid(int count) {
            return new Openness(false, count, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, false);
        }
    }

    private final short[] freeQ = new short[SIZE];
    private final short[] dirYQ = new short[SIZE];
    private final int[] stampTick = new int[SIZE];
    private final long[] eyeCell = new long[SIZE];
    private final boolean[] filled = new boolean[SIZE];

    /**
     * Stores the result of one ray cast through lattice slot {@code latticeIdx}, replacing the
     * previous entry of that slot.
     *
     * @param freeLength distance from the eye to the first hit, or the clamped range on a miss; a
     *                   negative value is stored as 0 and a non-finite value drops the record
     * @param dirY       world-space y component of the ray direction, in [-1, 1] (from the rotated
     *                   {@link SphereSchedule.Dir}); NaN when unknown, in which case the entry
     *                   counts toward volume but never toward {@link #upFree}
     * @param stampTick  server tick of the cast
     * @param eyeCell    {@link BlockPos#asLong()} of the block containing the eye at cast time
     */
    public void record(int latticeIdx, double freeLength, double dirY, int stampTick, long eyeCell) {
        Objects.checkIndex(latticeIdx, SIZE);
        if (!Double.isFinite(freeLength)) {
            return;
        }
        this.freeQ[latticeIdx] = quantiseLength(freeLength);
        this.dirYQ[latticeIdx] = Double.isFinite(dirY)
                ? (short) Math.round(Math.max(-1.0D, Math.min(1.0D, dirY)) * DIR_Y_SCALE)
                : DIR_Y_UNKNOWN;
        this.stampTick[latticeIdx] = stampTick;
        this.eyeCell[latticeIdx] = eyeCell;
        this.filled[latticeIdx] = true;
    }

    /** {@link #record(int, double, double, int, long)} without a direction: the entry never feeds upFree. */
    public void record(int latticeIdx, double freeLength, int stampTick, long eyeCell) {
        record(latticeIdx, freeLength, Double.NaN, stampTick, eyeCell);
    }

    /** Forgets every entry. */
    public void clear() {
        Arrays.fill(filled, false);
    }

    /** True if slot {@code latticeIdx} holds an entry that is valid for the given tick and eye cell. */
    public boolean isValid(int latticeIdx, int nowTick, long eyeCellNow) {
        Objects.checkIndex(latticeIdx, SIZE);
        return entryValid(latticeIdx, nowTick,
                BlockPos.unpackLongX(eyeCellNow), BlockPos.unpackLongY(eyeCellNow), BlockPos.unpackLongZ(eyeCellNow));
    }

    /** Number of valid entries. */
    public int validCount(int nowTick, long eyeCellNow) {
        return scan(nowTick, eyeCellNow, Double.POSITIVE_INFINITY).count;
    }

    /** Mean free length over valid entries, clamped to {@code radius}; 0.0 when none are valid. */
    public double meanFreeLength(int nowTick, long eyeCellNow, double radius) {
        if (!radiusUsable(radius)) {
            return 0.0D;
        }
        Scan scan = scan(nowTick, eyeCellNow, radius);
        return scan.count == 0 ? 0.0D : scan.sumL / scan.count;
    }

    /**
     * Estimated open volume V = (4pi/3) * mean(L^3) over valid entries, in blocks cubed; 0.0 when
     * none are valid or the radius is not positive. Unlike {@link #openness} this does not require
     * {@link #MIN_VALID_ENTRIES}.
     */
    public double volume(int nowTick, long eyeCellNow, double radius) {
        if (!radiusUsable(radius)) {
            return 0.0D;
        }
        Scan scan = scan(nowTick, eyeCellNow, radius);
        return scan.count == 0 ? 0.0D : FOUR_THIRDS_PI * (scan.sumL3 / scan.count);
    }

    /** Open fraction f = V / ((4pi/3) R^3) in [0, 1]; 0.0 when none are valid. */
    public double fraction(int nowTick, long eyeCellNow, double radius) {
        return fractionOf(volume(nowTick, eyeCellNow, radius), radius);
    }

    /**
     * Largest free height above the eye among valid entries: the maximum of {@code L * dirY} over
     * entries whose ray points upward (dirY above 0) and whose direction is known. A ray that
     * travels {@code L} blocks through open air at vertical component {@code dirY} proves the air
     * extends {@code L * dirY} blocks above the eye, so this is a lower bound of the ceiling
     * height, and it is exact for a flat ceiling no matter how slanted the ray. Because L is
     * clamped to R it never exceeds R. 0.0 when nothing qualifies.
     */
    public double upFree(int nowTick, long eyeCellNow, double radius) {
        return radiusUsable(radius) ? scan(nowTick, eyeCellNow, radius).upFree : 0.0D;
    }

    /**
     * Openness reading for the cavern channel. Not valid (and without a cavern signal) when fewer
     * than {@link #MIN_VALID_ENTRIES} entries are valid or the radius is not a positive number.
     * The cavern score is zero when the radius is below {@link #MIN_CAVERN_RADIUS}.
     */
    public Openness openness(int nowTick, long eyeCellNow, double radius) {
        boolean radiusOk = radiusUsable(radius);
        Scan scan = scan(nowTick, eyeCellNow, radiusOk ? radius : Double.POSITIVE_INFINITY);
        if (!radiusOk || scan.count < MIN_VALID_ENTRIES) {
            return Openness.notValid(scan.count);
        }
        double volume = FOUR_THIRDS_PI * (scan.sumL3 / scan.count);
        double fraction = fractionOf(volume, radius);
        double ceiling = ceilingFactor(scan.upFree);
        boolean cavernActive = radius >= MIN_CAVERN_RADIUS;
        double cavernC = cavernActive ? cavernChannel(fraction, scan.upFree) : 0.0D;
        return new Openness(true, scan.count, volume, fraction, scan.upFree, ceiling, cavernC, cavernActive);
    }

    /** Volume of a sphere of the given radius, (4pi/3) R^3. */
    public static double sphereVolume(double radius) {
        return FOUR_THIRDS_PI * radius * radius * radius;
    }

    /** {@code volume / sphereVolume(radius)} clamped to [0, 1]; 0.0 for a non-positive radius. */
    public static double fractionOf(double volume, double radius) {
        if (!radiusUsable(radius) || !(volume > 0.0D)) {
            return 0.0D;
        }
        return Math.min(1.0D, volume / sphereVolume(radius));
    }

    /** {@code 0.6 + 0.4 * clamp((upFree - 6) / 10, 0, 1)}: a low ceiling discounts the cavern score. */
    public static double ceilingFactor(double upFree) {
        return 0.6D + 0.4D * clamp01((upFree - 6.0D) / 10.0D);
    }

    /**
     * {@code clamp((f - 0.175) / 0.379, 0, 1) * ceilingFactor(upFree)}. No radius or dimension
     * gating here; {@link #openness} applies the radius gate.
     */
    public static double cavernChannel(double fraction, double upFree) {
        return clamp01((fraction - CAVERN_FRACTION_FLOOR) / CAVERN_FRACTION_SPAN) * ceilingFactor(upFree);
    }

    private record Scan(int count, double sumL, double sumL3, double upFree) {
    }

    private Scan scan(int nowTick, long eyeCellNow, double radius) {
        int ex = BlockPos.unpackLongX(eyeCellNow);
        int ey = BlockPos.unpackLongY(eyeCellNow);
        int ez = BlockPos.unpackLongZ(eyeCellNow);
        int count = 0;
        double sumL = 0.0D;
        double sumL3 = 0.0D;
        double up = 0.0D;
        for (int i = 0; i < SIZE; i++) {
            if (!entryValid(i, nowTick, ex, ey, ez)) {
                continue;
            }
            double length = Math.min(dequantiseLength(freeQ[i]), radius);
            count++;
            sumL += length;
            sumL3 += length * length * length;
            short dy = dirYQ[i];
            if (dy != DIR_Y_UNKNOWN && dy > 0) {
                up = Math.max(up, length * (dy / DIR_Y_SCALE));
            }
        }
        return new Scan(count, sumL, sumL3, up);
    }

    private boolean entryValid(int i, int nowTick, int ex, int ey, int ez) {
        if (!filled[i]) {
            return false;
        }
        long age = (long) nowTick - stampTick[i];
        if (age < 0L || age > MAX_AGE_TICKS) {
            return false;
        }
        long dx = (long) BlockPos.unpackLongX(eyeCell[i]) - ex;
        long dy = (long) BlockPos.unpackLongY(eyeCell[i]) - ey;
        long dz = (long) BlockPos.unpackLongZ(eyeCell[i]) - ez;
        return dx * dx + dy * dy + dz * dz <= MAX_EYE_SHIFT_SQ;
    }

    private static short quantiseLength(double length) {
        double clamped = Math.max(0.0D, Math.min(MAX_STORED_LENGTH, length));
        return (short) (int) Math.round(clamped / LENGTH_QUANTUM);
    }

    private static double dequantiseLength(short stored) {
        return (stored & 0xFFFF) * LENGTH_QUANTUM;
    }

    private static boolean radiusUsable(double radius) {
        return radius > 0.0D && Double.isFinite(radius);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
