package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure scoring of a point-of-interest candidate (design 6.2 and 6.3). Deterministic: the result
 * depends only on the {@link PoiSignals}; there is no clock, no randomness and no static state.
 *
 * <pre>
 * sumW = sum weight_b * min(cells_b, cap_b)     (weak buckets only beside >= 2 non-weak buckets)
 * S    = 1 - exp(-sumW / 3);  cluster: S = 1 - (1 - S) * 0.85
 * T    = 1 - (1 - S)(1 - 0.85 * C)(1 - E)
 * </pre>
 *
 * <p>Every threshold comparison tolerates {@value #EPS} of floating point noise so a value that is
 * mathematically on the boundary (for example {@code E = 0.4}) counts as reaching it.</p>
 */
public final class PoiScorer {
    /** Divisor inside {@code S = 1 - exp(-sumW / SUMW_DIVISOR)}. */
    public static final double SUMW_DIVISOR = 3.0D;
    /** Weak buckets are admitted once this many non-weak buckets are present. */
    public static final int WEAK_ADMIT_MIN_NON_WEAK = 2;

    public static final int CLUSTER_MIN_CELLS = 4;
    /** Ball radius in blocks, centred on an evidence cell, inclusive. */
    public static final double CLUSTER_RADIUS = 6.0D;
    /** Residual multiplier {@code (1 - S)} takes when the cluster bonus applies. */
    public static final double CLUSTER_RESIDUAL = 0.85D;

    public static final double OPEN_F_FLOOR = 0.175D;
    public static final double OPEN_F_SPAN = 0.379D;
    public static final double OPEN_CEILING_BASE = 0.6D;
    public static final double OPEN_CEILING_SPAN = 0.4D;
    public static final double OPEN_UPFREE_FLOOR = 6.0D;
    public static final double OPEN_UPFREE_SPAN = 10.0D;
    /** The cavern channel needs a perception radius of at least this many blocks. */
    public static final double CAVERN_MIN_RADIUS = 12.0D;
    /** Weight of {@code C} inside {@code T}. */
    public static final double C_WEIGHT = 0.85D;

    /** Group cap on the entity score {@code E}. */
    public static final double ENTITY_CAP = 0.6D;

    public static final double T_POSSIBLE = 0.40D;
    public static final double E_POSSIBLE = 0.4D;
    public static final double CAVERN_ONLY_S_MAX = 0.25D;
    public static final double CAVERN_ONLY_C_MIN = 0.47D;

    public static final double CERTAIN_S_MIN = 0.80D;
    public static final int CERTAIN_MIN_CELLS = 6;
    public static final int CERTAIN_MIN_NON_WEAK_BUCKETS = 3;

    public static final int MANDATORY_SENSORS = 2;
    public static final int MANDATORY_SCULK_FAMILY_CELLS = 6;
    public static final double MANDATORY_SCULK_FAMILY_C_MIN = 0.3D;

    /** Evaluation cadence and hysteresis window (design 6.3). */
    public static final int EVAL_INTERVAL_TICKS = 20;
    public static final int EVIDENCE_WINDOW_TICKS = 240;

    static final double EPS = 1.0e-9D;

    private static final double ENTITY_CHEST_MINECART = 0.6D;
    private static final double ENTITY_NPC = 0.3D;
    private static final double ENTITY_DECOR = 0.15D;
    private static final double ENTITY_MODDED = 0.4D;

    private PoiScorer() {
    }

    /** Result band. */
    public enum Band {
        NONE,
        POSSIBLE,
        /** POSSIBLE whose only reason is a large natural cavern; it always consults. */
        CAVERN_ONLY,
        STRUCTURE_CERTAIN,
        MANDATORY;

        /** True for the two bands that consult (POSSIBLE and CAVERN_ONLY). */
        public boolean isPossibleClass() {
            return this == POSSIBLE || this == CAVERN_ONLY;
        }
    }

    /**
     * One evaluation. All doubles are in [0, 1]. {@code distinctCells} counts the evidence cells that
     * contribute (weak cells only when admitted), uncapped. {@code possibleGate} is the instantaneous
     * "T >= 0.40 and one qualifying condition" test, independent of MANDATORY and of the certain
     * path; it is what {@link Hysteresis} counts.
     *
     * @param sumW              weighted, capped evidence sum
     * @param s                 structure score after the cluster bonus
     * @param c                 effective cavern channel (0 when disabled, invalid or R &lt; 12)
     * @param e                 entity score, capped at 0.6
     * @param t                 total score
     * @param distinctCells     distinct contributing evidence cells
     * @param nonWeakBuckets    distinct non-weak buckets present
     * @param strongBucket      a STRONG bucket meets its {@code strongMinCells}
     * @param band              result band
     * @param downgradedByHabitation a would-be STRUCTURE_CERTAIN was demoted to POSSIBLE (player base)
     * @param mandatoryTrigger  {@code +}-joined trigger tokens when band is MANDATORY, else empty
     * @param centroid          mean of the contributing cell centres, {@code null} when there are none
     * @param weakCounted       weak buckets were admitted into sumW
     * @param clusterBonus      the 6-ball cluster bonus applied
     * @param cavernActive      the cavern channel was live (valid, dimension enabled, R &gt;= 12)
     * @param possibleGate      instantaneous POSSIBLE gate
     * @param distinctBuckets   {@code nonWeakBuckets} plus the weak buckets that were admitted
     * @param habitationLike    a habitation item is in the evidence and none of SPAWNER, SCULK_STRUCT,
     *                          RAIL, WEB is; true for any band, unlike {@code downgradedByHabitation}
     *                          (used by the design 6.7 "not habitation-like" rows)
     */
    public record PoiScore(
            double sumW,
            double s,
            double c,
            double e,
            double t,
            int distinctCells,
            int nonWeakBuckets,
            boolean strongBucket,
            Band band,
            boolean downgradedByHabitation,
            String mandatoryTrigger,
            Vec3d centroid,
            boolean weakCounted,
            boolean clusterBonus,
            boolean cavernActive,
            boolean possibleGate,
            int distinctBuckets,
            boolean habitationLike) {

        public boolean isMandatory() {
            return band == Band.MANDATORY;
        }

        public boolean isStructureCertain() {
            return band == Band.STRUCTURE_CERTAIN;
        }

        /** The centroid rounded down to a block, or {@code null} when there is no evidence cell. */
        public BlockPos centroidBlock() {
            if (centroid == null) {
                return null;
            }
            return new BlockPos((int) Math.floor(centroid.x), (int) Math.floor(centroid.y), (int) Math.floor(centroid.z));
        }
    }

    public static PoiScore evaluate(PoiSignals signals) {
        Objects.requireNonNull(signals, "signals");

        // Openness first: the channel is gated by dimension, radius and validity, and the mandatory
        // rule below reads the gated value.
        boolean cavernActive = signals.opennessValid()
                && cavernChannelActive(signals.cavernDimensionEnabled(), signals.perceptionRadius());
        double c = cavernActive ? clamp01(signals.opennessC()) : 0.0D;

        // Warden rule (I13): decided on its own, before and independent of any score.
        String mandatory = mandatoryTrigger(signals, c);

        int nonWeak = 0;
        boolean strong = false;
        for (PoiBucket b : PoiBucket.values()) {
            if (b.isNatural() || b.isWeak()) {
                continue;
            }
            int n = signals.cellCount(b);
            if (n <= 0) {
                continue;
            }
            nonWeak++;
            if (b.strength() == PoiBucket.Strength.STRONG && n >= b.strongMinCells()) {
                strong = true;
            }
        }
        boolean weakAdmitted = nonWeak >= WEAK_ADMIT_MIN_NON_WEAK;

        double sumW = 0.0D;
        int weakBuckets = 0;
        List<BlockPos> evidence = new ArrayList<>();
        for (PoiBucket b : PoiBucket.values()) {
            if (b.isNatural() || (b.isWeak() && !weakAdmitted)) {
                continue;
            }
            int n = signals.cellCount(b);
            if (n <= 0) {
                continue;
            }
            if (b.isWeak()) {
                weakBuckets++;
            }
            sumW += b.weight() * Math.min(n, b.cap());
            evidence.addAll(signals.cells(b));
        }
        int distinctCells = evidence.size();

        double s = clamp01(1.0D - Math.exp(-sumW / SUMW_DIVISOR));
        boolean cluster = distinctCells >= CLUSTER_MIN_CELLS && hasCluster(evidence);
        if (cluster) {
            s = clamp01(1.0D - (1.0D - s) * CLUSTER_RESIDUAL);
        }

        double e = Math.min(ENTITY_CAP, clamp01(signals.entityScore()));
        double t = clamp01(1.0D - (1.0D - s) * (1.0D - C_WEIGHT * c) * (1.0D - e));

        boolean structural = nonWeak >= 2 || strong || ge(e, E_POSSIBLE);
        boolean cavernOnlyCondition = s < CAVERN_ONLY_S_MAX - EPS && ge(c, CAVERN_ONLY_C_MIN);
        boolean gate = ge(t, T_POSSIBLE) && (structural || cavernOnlyCondition);
        boolean certain = ge(s, CERTAIN_S_MIN)
                && distinctCells >= CERTAIN_MIN_CELLS
                && nonWeak >= CERTAIN_MIN_NON_WEAK_BUCKETS;
        boolean habitationLike = habitationLike(signals);
        boolean downgraded = false;

        Band band;
        if (!mandatory.isEmpty()) {
            band = Band.MANDATORY;
        } else if (certain && habitationLike) {
            band = Band.POSSIBLE;
            downgraded = true;
        } else if (certain) {
            band = Band.STRUCTURE_CERTAIN;
        } else if (gate) {
            band = structural ? Band.POSSIBLE : Band.CAVERN_ONLY;
        } else {
            band = Band.NONE;
        }

        return new PoiScore(sumW, s, c, e, t, distinctCells, nonWeak, strong, band, downgraded, mandatory,
                centroid(evidence), weakBuckets > 0, cluster, cavernActive, gate, nonWeak + weakBuckets,
                habitationLike);
    }

    /**
     * The cavern channel {@code C = clamp((f - 0.175) / 0.379, 0, 1) * ceiling} with
     * {@code ceiling = 0.6 + 0.4 * clamp((upFree - 6) / 10, 0, 1)}. Non-finite inputs count as 0.
     */
    public static double cavernChannel(double f, double upFree) {
        double fSafe = Double.isFinite(f) ? f : 0.0D;
        double upSafe = Double.isFinite(upFree) ? upFree : 0.0D;
        double open = clamp01((fSafe - OPEN_F_FLOOR) / OPEN_F_SPAN);
        double ceiling = OPEN_CEILING_BASE + OPEN_CEILING_SPAN * clamp01((upSafe - OPEN_UPFREE_FLOOR) / OPEN_UPFREE_SPAN);
        return open * ceiling;
    }

    /**
     * Free fraction {@code f = V / ((4 pi / 3) R^3)} from the mean cubed free length, where
     * {@code V = (4 pi / 3) * mean(L^3)}. The {@code 4 pi / 3} factors cancel, so {@code f = mean(L^3) / R^3}.
     */
    public static double freeFractionFromMeanCube(double meanCubedLength, double radius) {
        if (!(radius > 0.0D) || !(meanCubedLength > 0.0D)) {
            return 0.0D;
        }
        return meanCubedLength / (radius * radius * radius);
    }

    /** Free fraction from an absolute free volume in blocks cubed. */
    public static double freeFractionFromVolume(double volume, double radius) {
        if (!(radius > 0.0D) || !(volume > 0.0D)) {
            return 0.0D;
        }
        return volume / ((4.0D * Math.PI / 3.0D) * radius * radius * radius);
    }

    /** The cavern channel runs only in enabled dimensions and only when {@code R >= 12}. */
    public static boolean cavernChannelActive(boolean cavernDimensionEnabled, double radius) {
        return cavernDimensionEnabled && radius >= CAVERN_MIN_RADIUS;
    }

    /**
     * Entity score {@code E} from the entity type ids that passed the observability filter:
     * {@code chest_minecart} 0.6; villager, pillager, vindicator, evoker, illusioner 0.3 each;
     * item_frame and armor_stand 0.15 each; any non-{@code minecraft} namespace 0.4 (once); group cap
     * 0.6. A warden adds nothing here (see {@link #isWarden}); it is mandatory on its own.
     */
    public static double entityScore(Iterable<String> entityTypeIds) {
        double sum = 0.0D;
        boolean modded = false;
        for (String id : entityTypeIds) {
            if (id == null) {
                continue;
            }
            int colon = id.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
            String path = colon < 0 ? id : id.substring(colon + 1);
            if (!"minecraft".equals(namespace)) {
                modded = true;
                continue;
            }
            switch (path) {
                case "chest_minecart" -> sum += ENTITY_CHEST_MINECART;
                case "villager", "pillager", "vindicator", "evoker", "illusioner" -> sum += ENTITY_NPC;
                case "item_frame", "armor_stand" -> sum += ENTITY_DECOR;
                default -> {
                }
            }
        }
        if (modded) {
            sum += ENTITY_MODDED;
        }
        return Math.min(ENTITY_CAP, sum);
    }

    public static boolean isWarden(String entityTypeId) {
        return "minecraft:warden".equals(entityTypeId) || "warden".equals(entityTypeId);
    }

    /**
     * Player-base look: a habitation item is in the evidence and none of SPAWNER, SCULK_STRUCT, RAIL or
     * WEB is. A structure-certain candidate that is habitation-like is downgraded to POSSIBLE.
     */
    public static boolean habitationLike(PoiSignals signals) {
        return signals.hasHabitationItem()
                && signals.cellCount(PoiBucket.SPAWNER) == 0
                && signals.cellCount(PoiBucket.SCULK_STRUCT) == 0
                && signals.cellCount(PoiBucket.RAIL) == 0
                && signals.cellCount(PoiBucket.WEB) == 0;
    }

    /** Warden rule, evaluated on its own (I13); returns the {@code +}-joined trigger tokens or empty. */
    private static String mandatoryTrigger(PoiSignals signals, double effectiveC) {
        StringBuilder sb = new StringBuilder();
        if (signals.wardenVisible()) {
            append(sb, "warden_visible");
        }
        if (signals.reinforcedDeepslateCount() >= 1) {
            append(sb, "reinforced_deepslate");
        }
        if (signals.sculkShriekerCount() >= 1) {
            append(sb, "sculk_shrieker");
        }
        if (signals.sculkCatalystCount() >= 1) {
            append(sb, "sculk_catalyst");
        }
        if (signals.sculkSensorCount() >= MANDATORY_SENSORS) {
            append(sb, "sculk_sensors");
        }
        if (signals.sculkFamilyWithin12() >= MANDATORY_SCULK_FAMILY_CELLS
                && ge(effectiveC, MANDATORY_SCULK_FAMILY_C_MIN)) {
            append(sb, "sculk_family_cavern");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String token) {
        if (sb.length() > 0) {
            sb.append('+');
        }
        sb.append(token);
    }

    /** True when some evidence cell has at least {@link #CLUSTER_MIN_CELLS} cells (itself included) within the ball. */
    private static boolean hasCluster(List<BlockPos> cells) {
        int n = cells.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = cells.get(i);
            xs[i] = p.getX();
            ys[i] = p.getY();
            zs[i] = p.getZ();
        }
        long r2 = Math.round(CLUSTER_RADIUS * CLUSTER_RADIUS);
        for (int i = 0; i < n; i++) {
            int within = 0;
            for (int j = 0; j < n; j++) {
                long dx = (long) xs[i] - xs[j];
                long dy = (long) ys[i] - ys[j];
                long dz = (long) zs[i] - zs[j];
                if (dx * dx + dy * dy + dz * dz <= r2 && ++within >= CLUSTER_MIN_CELLS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Vec3d centroid(List<BlockPos> cells) {
        if (cells.isEmpty()) {
            return null;
        }
        long sx = 0L;
        long sy = 0L;
        long sz = 0L;
        for (BlockPos p : cells) {
            sx += p.getX();
            sy += p.getY();
            sz += p.getZ();
        }
        double n = cells.size();
        return new Vec3d(sx / n + 0.5D, sy / n + 0.5D, sz / n + 0.5D);
    }

    private static boolean ge(double value, double threshold) {
        return value >= threshold - EPS;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, v));
    }

    /**
     * Per-candidate state for "T &gt;= 0.40 on at least 2 of the last 3 evaluations, 20 ticks apart".
     * Pure: the caller feeds it the server tick and the evaluation outcome; there is no clock.
     *
     * <p>A hit is {@link PoiScore#possibleGate()} (T &gt;= 0.40 <em>and</em> a qualifying condition),
     * the conservative reading of the design. Calls closer than {@link #EVAL_INTERVAL_TICKS} to the last
     * accepted one are ignored, so extra calls cannot stuff the window. Entries older than
     * {@link #EVIDENCE_WINDOW_TICKS} are dropped, and a tick that moves backwards resets the state.</p>
     */
    public static final class Hysteresis {
        public static final int WINDOW = 3;
        public static final int REQUIRED_HITS = 2;

        private final long[] ticks = new long[WINDOW];
        private final boolean[] hitFlags = new boolean[WINDOW];
        private int size;

        /** Records an evaluation and returns {@link #satisfied(long)} at {@code tick}. */
        public boolean record(long tick, PoiScore score) {
            return record(tick, Objects.requireNonNull(score, "score").possibleGate());
        }

        /** Records an evaluation outcome and returns {@link #satisfied(long)} at {@code tick}. */
        public boolean record(long tick, boolean hit) {
            if (size > 0) {
                long last = ticks[size - 1];
                if (tick < last) {
                    reset();
                } else if (tick - last < EVAL_INTERVAL_TICKS) {
                    return satisfied(tick);
                }
            }
            if (size == WINDOW) {
                for (int i = 1; i < WINDOW; i++) {
                    ticks[i - 1] = ticks[i];
                    hitFlags[i - 1] = hitFlags[i];
                }
                size--;
            }
            ticks[size] = tick;
            hitFlags[size] = hit;
            size++;
            return satisfied(tick);
        }

        /** At least {@link #REQUIRED_HITS} hits among the last {@link #WINDOW} recorded, none stale at {@code nowTick}. */
        public boolean satisfied(long nowTick) {
            return hits(nowTick) >= REQUIRED_HITS;
        }

        /** Hit count among the retained entries that are not stale at {@code nowTick}. */
        public int hits(long nowTick) {
            int count = 0;
            for (int i = 0; i < size; i++) {
                if (hitFlags[i] && nowTick - ticks[i] <= EVIDENCE_WINDOW_TICKS) {
                    count++;
                }
            }
            return count;
        }

        /** Number of retained evaluations (at most {@link #WINDOW}). */
        public int evaluations() {
            return size;
        }

        /** Tick of the last accepted evaluation, or {@code Long.MIN_VALUE} when none. */
        public long lastTick() {
            return size == 0 ? Long.MIN_VALUE : ticks[size - 1];
        }

        public void reset() {
            size = 0;
        }
    }
}
