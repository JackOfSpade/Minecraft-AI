package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure admission and ranking policy of the opportunistic valuables detour (mining-assist design 4.2), plus the
 * small pure rules the pose search and the engine share (lease, pose limits, announce text). No Minecraft
 * registry, no clock: BlockPos, Vec3d, ints and doubles only, so every number of the design is a unit test.
 *
 * <p>Raw values come from {@link ValueTable} (built by P0, unchanged); the sighting ledger supplies the
 * nominations ({@link SightingLedger.Sighting}). A sighting is only a nomination (I1): admission says the
 * numbers justify a walk, never that the block is still there.</p>
 *
 * <h2>Cost, score, admission (design 4.2)</h2>
 * All geometry is {@code ore - feet} in whole blocks, observed positions only:
 * <pre>
 * horizontalCost = max(|dx|,|dz|) + 0.5 * min(|dx|,|dz|)
 * verticalCost   = 1.6 * dy          if dy &gt; 0, else 1.2 * |dy|
 * cost           = horizontalCost + verticalCost
 * clusterSize    = same-block sightings within Chebyshev 3 (3D) of the candidate, itself included
 * score          = value * (1 + 0.15 * min(clusterSize - 1, 6)) / (6 + cost)
 * maxCost(value) = clamp(4 + 0.16 * value, 6, 20)
 * </pre>
 * {@link #admit} checks, first failing reason wins, in this order: raw value below {@code detour.minValue}
 * (VALUE, the cluster multiplier never lifts a value over the threshold), a target that is within
 * {@value #TARGET_NEAR_BLOCKS} blocks (TARGET_NEAR), range (RANGE: eye distance above
 * {@value #MAX_EYE_DISTANCE}, Chebyshev-horizontal distance from the anchor above {@code detour.maxRadius}, or the
 * ore's Y outside {@code [anchor.y - maxDown, anchor.y + maxUp]}), cost above {@code maxCost(value)} (COST),
 * score below {@code detour.minScore} (SCORE), and, with a target locked, score below
 * {@value #LOCKED_TARGET_SCORE_FACTOR} times {@code detour.minScore} (LOCKED_SCORE).
 *
 * <p>Worked examples the tests pin: a diamond at 11 blocks scores 100/17 = 5.882 and is admitted; iron at 8
 * scores 30/14 = 2.143, admitted with no target locked and LOCKED_SCORE while one is locked (2.143 is below 2.4);
 * iron at 10 has cost 10 above maxCost(30) = 8.8 (COST); a lone coal, a cluster of four coal and a cluster of
 * seven copper are all VALUE.</p>
 */
public final class DetourPolicy {
    /** Design 4.2: farthest eye-to-block-centre distance of a candidate, in blocks. */
    public static final double MAX_EYE_DISTANCE = 14.0D;
    /** Design 4.2: same-block sightings within this Chebyshev distance form a cluster. */
    public static final int CLUSTER_RADIUS = 3;
    /** Design 4.2: the cluster multiplier saturates at {@code 1 + CLUSTER_BONUS * CLUSTER_BONUS_STEPS}. */
    public static final int CLUSTER_BONUS_STEPS = 6;
    public static final double CLUSTER_BONUS = 0.15D;
    /** Design 4.2: with a target locked the score must reach this multiple of {@code detour.minScore}. */
    public static final double LOCKED_TARGET_SCORE_FACTOR = 2.0D;
    /** Design 4.2: a locked target closer than this never yields to a detour. */
    public static final double TARGET_NEAR_BLOCKS = 3.0D;
    /** Design 4.5: a stand pose may be at most this many blocks below the anchor's Y. */
    public static final int STAND_BELOW_ANCHOR = 3;
    /** Design 4.5: walk-only never stands below this Y ({@code MIN_Y + 1} of OreDig). */
    public static final int MIN_STAND_Y = -59;
    /** Design 4.7, overworld: at or below this Y the deep lava band starts; a detour there needs spare sealing blocks. */
    public static final int DEEP_LAVA_Y = -50;
    /** Design 4.7 for the Nether: lava is expected at or below this Y (the lava sea is at Y 31). See {@link #lavaBandTopY}. */
    public static final int NETHER_LAVA_Y = 32;
    /** Design 4.3: the observed path length may be at most this multiple of {@code detour.maxRadius}. */
    public static final int PATH_LENGTH_RADIUS_FACTOR = 2;
    /** Chebyshev radius in which another {@code _ore} sighting proves the natural context of a raw block ({@link #naturalContext}). */
    public static final int NATURAL_CONTEXT_RADIUS = 4;

    private static final String DEEPSLATE_PREFIX = "deepslate_";
    private static final String NEVER_DETOUR_ID = "gilded_blackstone";
    private static final String ORE_SUFFIX = "_ore";
    private static final String ANCIENT_DEBRIS_ID = "ancient_debris";

    private static final Comparator<Ranked> RANK_ORDER = Comparator
            .comparingDouble(Ranked::score).reversed()
            .thenComparing(Comparator.comparingInt(Ranked::value).reversed())
            .thenComparingLong(Ranked::distanceSq)
            .thenComparingInt((Ranked r) -> r.sighting().pos().getY())
            .thenComparingInt((Ranked r) -> r.sighting().pos().getZ())
            .thenComparingInt((Ranked r) -> r.sighting().pos().getX());

    /** Whether a target is locked, and if so whether it is within {@value #TARGET_NEAR_BLOCKS} blocks of the bot. */
    public enum TargetLock {
        NONE,
        LOCKED,
        NEAR
    }

    /** Result of {@link #admit}: ADMIT, or the first failing rule in the order documented on the class. */
    public enum Admission {
        ADMIT,
        VALUE,
        TARGET_NEAR,
        RANGE,
        COST,
        SCORE,
        LOCKED_SCORE;

        public boolean admitted() {
            return this == ADMIT;
        }
    }

    /**
     * One admitted candidate. {@code cluster} lists the positions of the same-block sightings within Chebyshev 3
     * of the candidate (itself included, the candidate first, the rest in {@link SightingLedger} retention order);
     * {@code distanceSq} is the squared Euclidean distance from the feet cell to the candidate cell.
     */
    public record Ranked(SightingLedger.Sighting sighting, int value, int clusterSize, List<BlockPos> cluster,
                         double cost, double score, long distanceSq) {
    }

    private DetourPolicy() {
    }

    /** {@code max(|dx|,|dz|) + 0.5 * min(|dx|,|dz|)}. */
    public static double horizontalCost(int dx, int dz) {
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        return Math.max(ax, az) + 0.5D * Math.min(ax, az);
    }

    /** {@code 1.6 * dy} when {@code dy > 0}, else {@code 1.2 * |dy|}. */
    public static double verticalCost(int dy) {
        return dy > 0 ? 1.6D * dy : 1.2D * Math.abs(dy);
    }

    /** {@code horizontalCost(dx, dz) + verticalCost(dy)}. */
    public static double cost(int dx, int dy, int dz) {
        return horizontalCost(dx, dz) + verticalCost(dy);
    }

    /** {@code clamp(4 + 0.16 * value, 6, 20)}. */
    public static double maxCost(int value) {
        double raw = 4.0D + 0.16D * value;
        return Math.max(6.0D, Math.min(20.0D, raw));
    }

    /** {@code value * (1 + 0.15 * min(clusterSize - 1, 6)) / (6 + cost)}. A clusterSize below 1 counts as 1. */
    public static double score(int value, int clusterSize, double cost) {
        int size = Math.max(1, clusterSize);
        double multiplier = 1.0D + CLUSTER_BONUS * Math.min(size - 1, CLUSTER_BONUS_STEPS);
        return value * multiplier / (6.0D + cost);
    }

    /** The positions of {@code all} that hold the same {@code blockId} as {@code candidate} within Chebyshev 3 (3D), candidate included and first. */
    public static List<BlockPos> cluster(SightingLedger.Sighting candidate, List<SightingLedger.Sighting> all) {
        List<BlockPos> result = new ArrayList<>();
        result.add(candidate.pos());
        for (SightingLedger.Sighting s : all) {
            if (s.pos().equals(candidate.pos())) {
                continue;
            }
            if (!s.blockId().equals(candidate.blockId())) {
                continue;
            }
            if (chebyshev3D(candidate.pos(), s.pos()) <= CLUSTER_RADIUS) {
                result.add(s.pos());
            }
        }
        return result;
    }

    /**
     * Admission of one candidate (see the class comment for the order). {@code ore} is the sighted cell,
     * {@code feet} the bot's feet cell, {@code eye} its eye position (distance to the block centre), and
     * {@code anchorFace} the anchor the detour would return to.
     *
     * @param value       the raw {@link ValueTable} value of the block (never the cluster-scaled one)
     * @param clusterSize see {@link #cluster}
     */
    public static Admission admit(int value, int clusterSize, BlockPos ore, BlockPos feet, Vec3d eye,
                                  BlockPos anchorFace, TargetLock lock, MiningAssistConfig.Detour cfg) {
        if (value < cfg.minValue()) {
            return Admission.VALUE;
        }
        if (lock == TargetLock.NEAR) {
            return Admission.TARGET_NEAR;
        }
        double eyeDistance = eye.distanceTo(new Vec3d(ore.getX() + 0.5D, ore.getY() + 0.5D, ore.getZ() + 0.5D));
        if (eyeDistance > MAX_EYE_DISTANCE) {
            return Admission.RANGE;
        }
        int chebyshevHorizontal = Math.max(Math.abs(ore.getX() - anchorFace.getX()), Math.abs(ore.getZ() - anchorFace.getZ()));
        if (chebyshevHorizontal > cfg.maxRadius()) {
            return Admission.RANGE;
        }
        if (ore.getY() > anchorFace.getY() + cfg.maxUp() || ore.getY() < anchorFace.getY() - cfg.maxDown()) {
            return Admission.RANGE;
        }
        int dx = ore.getX() - feet.getX();
        int dy = ore.getY() - feet.getY();
        int dz = ore.getZ() - feet.getZ();
        double c = cost(dx, dy, dz);
        if (c > maxCost(value)) {
            return Admission.COST;
        }
        double s = score(value, clusterSize, c);
        if (s < cfg.minScore()) {
            return Admission.SCORE;
        }
        if (lock == TargetLock.LOCKED && s < LOCKED_TARGET_SCORE_FACTOR * cfg.minScore()) {
            return Admission.LOCKED_SCORE;
        }
        return Admission.ADMIT;
    }

    /**
     * Admits and ranks a sighting snapshot. Returns only {@link Admission#ADMIT} candidates, best first:
     * score descending, then higher raw value, then nearer (smaller {@code distanceSq}), then BlockPos order
     * (y, then z, then x ascending). The value of a sighting is {@code ValueTable.valueOf(blockId)} of its
     * ledger id (the ledger's own {@code rawValue} may only be trusted as far as it was stored; recompute).
     * A candidate whose id is {@link #neverDetour} or fails {@link #naturalContext} is dropped before admission.
     * Deterministic for equal input. Does not touch exclusions, claims or the world: the caller filters those.
     */
    public static List<Ranked> rank(List<SightingLedger.Sighting> sightings, BlockPos feet, Vec3d eye,
                                    BlockPos anchorFace, TargetLock lock, MiningAssistConfig.Detour cfg) {
        List<Ranked> out = new ArrayList<>();
        for (SightingLedger.Sighting s : sightings) {
            if (neverDetour(s.blockId())) {
                continue;
            }
            if (!naturalContext(s, sightings)) {
                continue;
            }
            List<BlockPos> clusterCells = cluster(s, sightings);
            int clusterSize = clusterCells.size();
            int value = ValueTable.valueOf(s.blockId());
            Admission admission = admit(value, clusterSize, s.pos(), feet, eye, anchorFace, lock, cfg);
            if (!admission.admitted()) {
                continue;
            }
            int dx = s.pos().getX() - feet.getX();
            int dy = s.pos().getY() - feet.getY();
            int dz = s.pos().getZ() - feet.getZ();
            double c = cost(dx, dy, dz);
            double sc = score(value, clusterSize, c);
            long distSq = distanceSq(feet, s.pos());
            out.add(new Ranked(s, value, clusterSize, clusterCells, c, sc, distSq));
        }
        out.sort(RANK_ORDER);
        return out;
    }

    /**
     * Design 4.7 per dimension: the Y at or below which lava lakes are expected. The overworld value is
     * {@link #DEEP_LAVA_Y}; the Nether value is {@link #NETHER_LAVA_Y} (the lava sea is at Y 31 and below); the End has
     * no band and returns {@link Integer#MIN_VALUE}; null or an unknown (modded) dimension gets the overworld value. {@code
     * dimensionKey} is {@code BotEdits.dimensionKey(world)} (fail closed).
     */
    public static int lavaBandTopY(String dimensionKey) {
        if (dimensionKey == null || "minecraft:overworld".equals(dimensionKey)) {
            return DEEP_LAVA_Y;
        }
        if ("minecraft:the_nether".equals(dimensionKey)) {
            return NETHER_LAVA_Y;
        }
        return "minecraft:the_end".equals(dimensionKey) ? Integer.MIN_VALUE : DEEP_LAVA_Y;
    }

    /**
     * Whether {@code ledgerId} (bare path or {@code ns:path}) names a block a detour never takes because a
     * bot cannot tell it from a build: {@code gilded_blackstone} (a bastion block, and an easy thing for a player
     * to place), whatever the surroundings. Every other id is decided by {@link #naturalContext}.
     */
    public static boolean neverDetour(String ledgerId) {
        return NEVER_DETOUR_ID.equals(stripNamespace(ledgerId));
    }

    /**
     * The natural-context rule for storage-like valuables (P1 contract, review log). An id that ends with
     * {@code _ore}, or is {@code ancient_debris}, needs no context. Any other valued id (the {@code raw_*_block}
     * family) passes only when {@code all} holds another sighting, not the candidate itself, whose id ends with
     * {@code _ore} within Chebyshev {@value #NATURAL_CONTEXT_RADIUS} (3D) of the candidate: a raw block inside a large ore
     * vein is natural, a raw block among stone bricks is somebody's storage. {@link #neverDetour} ids fail. The
     * ledger id is read after stripping a {@code ns:} prefix.
     */
    public static boolean naturalContext(SightingLedger.Sighting candidate, List<SightingLedger.Sighting> all) {
        String id = stripNamespace(candidate.blockId());
        if (NEVER_DETOUR_ID.equals(id)) {
            return false;
        }
        if (id.endsWith(ORE_SUFFIX) || ANCIENT_DEBRIS_ID.equals(id)) {
            return true;
        }
        for (SightingLedger.Sighting s : all) {
            if (s.pos().equals(candidate.pos())) {
                continue;
            }
            String otherId = stripNamespace(s.blockId());
            if (otherId.endsWith(ORE_SUFFIX) && chebyshev3D(candidate.pos(), s.pos()) <= NATURAL_CONTEXT_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lease length in task ticks of a detour that has made {@code breaks} breaks: {@code min(LEASE_CAP_TICKS,
     * leaseTicks + LEASE_PER_BREAK_TICKS * max(0, breaks))} (300, +60 per break, cap 600 by default).
     */
    public static int leaseTicks(MiningAssistConfig.Detour cfg, int breaks) {
        int b = Math.max(0, breaks);
        long raw = (long) cfg.leaseTicks() + (long) MiningAssistConfig.Detour.LEASE_PER_BREAK_TICKS * b;
        return (int) Math.min(MiningAssistConfig.Detour.LEASE_CAP_TICKS, raw);
    }

    /**
     * Design 4.5 pose limits shared with the pose search: {@code stand.y >= max(minStandY, anchor.y -}
     * {@value #STAND_BELOW_ANCHOR}{@code )} and Chebyshev-horizontal distance from {@code anchorFace} at most
     * {@code detour.maxRadius}.
     */
    public static boolean standWithinLimits(BlockPos stand, BlockPos anchorFace, int minStandY,
                                            MiningAssistConfig.Detour cfg) {
        int floor = Math.max(minStandY, anchorFace.getY() - STAND_BELOW_ANCHOR);
        if (stand.getY() < floor) {
            return false;
        }
        int chebyshevHorizontal = Math.max(Math.abs(stand.getX() - anchorFace.getX()), Math.abs(stand.getZ() - anchorFace.getZ()));
        return chebyshevHorizontal <= cfg.maxRadius();
    }

    /**
     * Whether {@code cell} lies in the 3x3 floor patch under the anchor (Y equal to {@code anchorFace.y - 1},
     * |dx| and |dz| at most 1). The detour never breaks and never stands on it (design 4.5, 4.6 step 4).
     */
    public static boolean inAnchorFloorPatch(BlockPos cell, BlockPos anchorFace) {
        if (cell.getY() != anchorFace.getY() - 1) {
            return false;
        }
        int dx = Math.abs(cell.getX() - anchorFace.getX());
        int dz = Math.abs(cell.getZ() - anchorFace.getZ());
        return dx <= 1 && dz <= 1;
    }

    /** Design 4.3: an observed path length from {@code ObservedReach} is acceptable when it is at most {@code 2 * detour.maxRadius}. */
    public static boolean pathLengthOk(int observedLength, MiningAssistConfig.Detour cfg) {
        return observedLength <= PATH_LENGTH_RADIUS_FACTOR * cfg.maxRadius();
    }

    /** Design 4.13: whether a value earns the rare-find chat line ({@code value >= detour.announceMinValue}). */
    public static boolean announces(int value, MiningAssistConfig.Detour cfg) {
        return value >= cfg.announceMinValue();
    }

    /**
     * Design 4.13: the rare-find chat line, "Spotted diamond ore nearby, grabbing it." The block name is the
     * ledger id without namespace and without a {@code deepslate_} prefix, underscores turned into spaces, lower
     * case. A null or blank id gives "Spotted a valuable nearby, grabbing it." Never longer than 80 characters.
     */
    public static String announceText(String ledgerId) {
        String name = blockDisplayName(ledgerId);
        if (name == null) {
            return "Spotted a valuable nearby, grabbing it.";
        }
        String suffix = " nearby, grabbing it.";
        String prefix = "Spotted ";
        String line = prefix + name + suffix;
        if (line.length() <= 80) {
            return line;
        }
        int maxNameLen = Math.max(0, 80 - prefix.length() - suffix.length());
        String truncated = name.length() > maxNameLen ? name.substring(0, maxNameLen).trim() : name;
        return prefix + truncated + suffix;
    }

    private static String blockDisplayName(String ledgerId) {
        if (ledgerId == null) {
            return null;
        }
        String p = ledgerId.trim();
        if (p.isEmpty()) {
            return null;
        }
        p = p.toLowerCase(Locale.ROOT);
        int colon = p.indexOf(':');
        if (colon >= 0) {
            p = p.substring(colon + 1);
        }
        if (p.startsWith(DEEPSLATE_PREFIX)) {
            p = p.substring(DEEPSLATE_PREFIX.length());
        }
        if (p.isEmpty()) {
            return null;
        }
        return p.replace('_', ' ');
    }

    private static String stripNamespace(String ledgerId) {
        if (ledgerId == null) {
            return "";
        }
        String p = ledgerId.trim().toLowerCase(Locale.ROOT);
        int colon = p.indexOf(':');
        if (colon >= 0) {
            p = p.substring(colon + 1);
        }
        return p;
    }

    private static int chebyshev3D(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, Math.max(dy, dz));
    }

    private static long distanceSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
