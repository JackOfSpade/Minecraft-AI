package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-bot memory of observed lava, water and trap cells (mining-assist design 3.3, invariant I15).
 *
 * <p>Ageing rules, all measured against caller-supplied server ticks:</p>
 * <ul>
 *   <li>{@link Kind#LAVA} never ages. A lava cell leaves the field only through
 *       {@link #observeClear} or {@link #observeNotFluid} (the cell was re-observed as non-fluid),
 *       {@link #clear()} (mission end) or capacity eviction.</li>
 *   <li>{@link Kind#WATER} is dropped by {@link #expire} once its age exceeds
 *       {@value #WATER_MAX_AGE_TICKS} ticks; {@link Kind#TRAP} once it exceeds
 *       {@value #TRAP_MAX_AGE_TICKS}. Age is {@code now - lastObservedTick}, so a cell aged exactly
 *       the limit is still kept (the conservative side for a hazard).</li>
 * </ul>
 *
 * <p>Cells live in 4x4x4 buckets keyed by packed bucket coordinates, so a radius query probes only the
 * buckets overlapping its cube. Capacity is {@value #CAP} cells; {@link #evictIfOver} drops the
 * {@value #EVICT_BATCH} cells farthest from the bot. Radius queries use a Chebyshev cube:
 * {@code |dx|, |dy|, |dz| <= radius}. Every result is deterministic (explicit tie-breaks, never
 * hash-iteration order). Not thread-safe: server thread only.</p>
 */
public final class HazardField {
    public enum Kind { LAVA, WATER, TRAP }

    /** Snapshot view of one stored cell. */
    public record Cell(BlockPos pos, Kind kind, int lastTick) {
        public Cell {
            pos = Objects.requireNonNull(pos, "pos").toImmutable();
            Objects.requireNonNull(kind, "kind");
        }
    }

    public static final int CAP = 4096;
    public static final int EVICT_BATCH = 256;
    public static final int WATER_MAX_AGE_TICKS = 1200;
    public static final int TRAP_MAX_AGE_TICKS = 3600;
    /** Returned by {@link #nearestDistanceSq} when no matching cell lies inside the cube. */
    public static final long NONE = -1L;

    private static final int SHIFT = 2;
    private static final int LOCAL_MASK = (1 << SHIFT) - 1;
    private static final int BUCKET_CELLS = 1 << (3 * SHIFT);
    /** Clamps for query cubes, far beyond any valid BlockPos, so bucket keys never alias. */
    private static final int XZ_LIMIT = 1 << 26;
    private static final int Y_LIMIT = 1 << 13;

    private static final Kind[] KINDS = Kind.values();
    private static final int LAVA = Kind.LAVA.ordinal();
    private static final int WATER = Kind.WATER.ordinal();
    private static final int TRAP = Kind.TRAP.ordinal();

    /** BlockPos order: y, then z, then x (same as {@code Vec3i.compareTo}, without overflow). */
    private static final Comparator<BlockPos> POS_ORDER = Comparator
            .comparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ)
            .thenComparingInt(BlockPos::getX);

    /**
     * Eviction order: farthest from the bot first; on equal distance non-lava before lava (lava is the
     * hazard we least want to lose); then BlockPos order so the result never depends on insertion order.
     */
    private static final Comparator<Victim> FARTHEST_FIRST = Comparator
            .comparingLong(Victim::distSq).reversed()
            .thenComparing(Victim::lava)
            .thenComparingInt(Victim::y)
            .thenComparingInt(Victim::z)
            .thenComparingInt(Victim::x);

    private record Victim(int x, int y, int z, boolean lava, long distSq) {}

    private record Found(int x, int y, int z, long distSq) {}

    @FunctionalInterface
    private interface CellVisitor {
        /** Returns true to stop the scan. */
        boolean visit(int x, int y, int z, int lastTick);
    }

    private static final class Bucket {
        final long key;
        final int bx;
        final int by;
        final int bz;
        long occupied;
        int count;
        final byte[] kinds = new byte[BUCKET_CELLS];
        final int[] ticks = new int[BUCKET_CELLS];

        Bucket(long key, int bx, int by, int bz) {
            this.key = key;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
        }

        boolean has(int local) {
            return (occupied >>> local & 1L) != 0L;
        }
    }

    private final Map<Long, Bucket> buckets = new HashMap<>();
    private final int[] perKind = new int[KINDS.length];
    private int size;
    private long bucketProbes;

    // ---- writes -------------------------------------------------------------------------------

    /**
     * Records {@code kind} at {@code pos} as seen at {@code tick}. Returns true when the set of hazards
     * changed (a new cell, or an existing cell changing kind); false for a pure refresh or an ignored
     * observation.
     *
     * <p>Conflict rules for a cell that already exists: the same kind keeps the newest tick. Lava
     * dominates: observing WATER or TRAP on a lava cell is ignored (lava leaves only through
     * {@link #observeClear} or {@link #observeNotFluid}), and observing LAVA on a WATER or TRAP cell
     * upgrades it. WATER versus
     * TRAP: the newest observation wins and an older one is ignored.</p>
     *
     * <p>If the caller never calls {@link #evictIfOver}, a hard ceiling of
     * {@code CAP + EVICT_BATCH} cells is still enforced here by evicting the farthest cells from
     * {@code pos} (observations are first-hits near the bot).</p>
     */
    public boolean observe(BlockPos pos, Kind kind, int tick) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(kind, "kind");
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int local = localIndex(x, y, z);
        long key = bucketKey(x >> SHIFT, y >> SHIFT, z >> SHIFT);
        Bucket bucket = buckets.get(key);
        if (bucket != null && bucket.has(local)) {
            return refresh(bucket, local, kind, tick);
        }
        if (size >= CAP + EVICT_BATCH) {
            evictFarthest(x, y, z, Math.max(EVICT_BATCH, size - CAP));
            bucket = buckets.get(key);
        }
        if (bucket == null) {
            bucket = new Bucket(key, x >> SHIFT, y >> SHIFT, z >> SHIFT);
            buckets.put(key, bucket);
        }
        bucket.occupied |= 1L << local;
        bucket.count++;
        bucket.kinds[local] = (byte) kind.ordinal();
        bucket.ticks[local] = tick;
        perKind[kind.ordinal()]++;
        size++;
        return true;
    }

    private boolean refresh(Bucket bucket, int local, Kind kind, int tick) {
        int existing = bucket.kinds[local];
        int existingTick = bucket.ticks[local];
        if (existing == kind.ordinal()) {
            bucket.ticks[local] = Math.max(existingTick, tick);
            return false;
        }
        if (existing == LAVA) {
            return false;
        }
        if (kind == Kind.LAVA) {
            retag(bucket, local, existing, LAVA, Math.max(existingTick, tick));
            return true;
        }
        if (tick < existingTick) {
            return false;
        }
        retag(bucket, local, existing, kind.ordinal(), tick);
        return true;
    }

    private void retag(Bucket bucket, int local, int from, int to, int tick) {
        perKind[from]--;
        perKind[to]++;
        bucket.kinds[local] = (byte) to;
        bucket.ticks[local] = tick;
    }

    /**
     * The block state at {@code pos} was actually read (a first hit or a break peek) and it is neither
     * fluid nor trap: forget the cell, whatever kind it holds. Returns true if a cell was removed. An
     * observation older than the cell's last sighting ({@code tick <} its last-seen tick) cannot
     * contradict it and is ignored.
     *
     * <p>Do not call this for a cell a ray merely passed through: that proves the cell holds no fluid
     * but says nothing about a pressure plate or tripwire (a collider ray passes straight through them).
     * Use {@link #observeNotFluid} for that case.</p>
     */
    public boolean observeClear(BlockPos pos, int tick) {
        return clearCell(pos, tick, false);
    }

    /**
     * A fluid-stopping ray travelled through {@code pos}, so the cell holds no fluid. Removes a LAVA or
     * WATER cell there and returns true; a TRAP cell is left alone because passing through it proves
     * nothing about traps. Same staleness rule as {@link #observeClear}. Cheap when the field holds no
     * lava or water, which makes it safe to call for every traversed cell.
     */
    public boolean observeNotFluid(BlockPos pos, int tick) {
        return clearCell(pos, tick, true);
    }

    private boolean clearCell(BlockPos pos, int tick, boolean fluidOnly) {
        Objects.requireNonNull(pos, "pos");
        if (size == 0 || (fluidOnly && perKind[LAVA] + perKind[WATER] == 0)) {
            return false;
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int local = localIndex(x, y, z);
        Bucket bucket = buckets.get(bucketKey(x >> SHIFT, y >> SHIFT, z >> SHIFT));
        if (bucket == null || !bucket.has(local) || tick < bucket.ticks[local]
                || (fluidOnly && bucket.kinds[local] == TRAP)) {
            return false;
        }
        removeCell(bucket, local);
        return true;
    }

    /**
     * Drops WATER cells whose age exceeds {@value #WATER_MAX_AGE_TICKS} and TRAP cells whose age exceeds
     * {@value #TRAP_MAX_AGE_TICKS}. LAVA is never touched. Linear in the cell count, so call it every
     * few seconds rather than every tick. Returns the number of cells removed.
     */
    public int expire(int nowTick) {
        if (perKind[WATER] == 0 && perKind[TRAP] == 0) {
            return 0;
        }
        int removed = 0;
        Iterator<Bucket> it = buckets.values().iterator();
        while (it.hasNext()) {
            Bucket bucket = it.next();
            long remaining = bucket.occupied;
            while (remaining != 0L) {
                int local = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                int kind = bucket.kinds[local];
                int maxAge = kind == WATER ? WATER_MAX_AGE_TICKS : kind == TRAP ? TRAP_MAX_AGE_TICKS : -1;
                if (maxAge >= 0 && (long) nowTick - bucket.ticks[local] > maxAge) {
                    bucket.occupied &= ~(1L << local);
                    bucket.count--;
                    perKind[kind]--;
                    size--;
                    removed++;
                }
            }
            if (bucket.count == 0) {
                it.remove();
            }
        }
        return removed;
    }

    /**
     * When more than {@value #CAP} cells are stored, removes the {@value #EVICT_BATCH} cells farthest
     * from {@code botPos} (at least enough to get back to the cap), farthest first. Lava is not exempt:
     * the field keeps what is near the bot. Ties on distance evict non-lava first, then by BlockPos
     * order. Returns the number of cells removed (0 when the cap is not exceeded).
     */
    public int evictIfOver(BlockPos botPos) {
        Objects.requireNonNull(botPos, "botPos");
        if (size <= CAP) {
            return 0;
        }
        return evictFarthest(botPos.getX(), botPos.getY(), botPos.getZ(), Math.max(EVICT_BATCH, size - CAP));
    }

    /** Forgets everything (mission end). */
    public void clear() {
        buckets.clear();
        Arrays.fill(perKind, 0);
        size = 0;
    }

    private int evictFarthest(int rx, int ry, int rz, int wanted) {
        List<Victim> victims = new ArrayList<>(size);
        for (Bucket bucket : buckets.values()) {
            long remaining = bucket.occupied;
            while (remaining != 0L) {
                int local = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                int x = (bucket.bx << SHIFT) + (local & LOCAL_MASK);
                int z = (bucket.bz << SHIFT) + (local >> SHIFT & LOCAL_MASK);
                int y = (bucket.by << SHIFT) + (local >> (2 * SHIFT) & LOCAL_MASK);
                victims.add(new Victim(x, y, z, bucket.kinds[local] == LAVA, distSq(x, y, z, rx, ry, rz)));
            }
        }
        victims.sort(FARTHEST_FIRST);
        int count = Math.min(wanted, victims.size());
        for (int i = 0; i < count; i++) {
            Victim victim = victims.get(i);
            removeAt(victim.x(), victim.y(), victim.z());
        }
        return count;
    }

    private void removeAt(int x, int y, int z) {
        Bucket bucket = buckets.get(bucketKey(x >> SHIFT, y >> SHIFT, z >> SHIFT));
        int local = localIndex(x, y, z);
        if (bucket != null && bucket.has(local)) {
            removeCell(bucket, local);
        }
    }

    private void removeCell(Bucket bucket, int local) {
        perKind[bucket.kinds[local]]--;
        bucket.occupied &= ~(1L << local);
        bucket.count--;
        size--;
        if (bucket.count == 0) {
            buckets.remove(bucket.key);
        }
    }

    // ---- point reads --------------------------------------------------------------------------

    /** The kind stored at {@code pos}, or null when the cell is not a remembered hazard. */
    public Kind kindAt(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        Bucket bucket = buckets.get(bucketKey(x >> SHIFT, y >> SHIFT, z >> SHIFT));
        int local = localIndex(x, y, z);
        return bucket != null && bucket.has(local) ? KINDS[bucket.kinds[local]] : null;
    }

    public boolean isLava(BlockPos pos) {
        return kindAt(pos) == Kind.LAVA;
    }

    /** The stored cell at {@code pos}, or null. */
    public Cell cellAt(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        Bucket bucket = buckets.get(bucketKey(x >> SHIFT, y >> SHIFT, z >> SHIFT));
        int local = localIndex(x, y, z);
        if (bucket == null || !bucket.has(local)) {
            return null;
        }
        return new Cell(pos, KINDS[bucket.kinds[local]], bucket.ticks[local]);
    }

    // ---- radius queries (Chebyshev cube around centre) ------------------------------------------

    /** True if any cell of {@code kind} lies in the cube {@code centre +- radius}. Negative radius: false. */
    public boolean anyWithin(Kind kind, BlockPos centre, int radius) {
        return scan(kind, centre, radius, (x, y, z, tick) -> true);
    }

    /** Lava clearance check, e.g. the 4.4 {@code lavaClearRadius}. */
    public boolean anyLavaWithin(BlockPos centre, int radius) {
        return anyWithin(Kind.LAVA, centre, radius);
    }

    /** Trap clearance check, e.g. the 4.4 {@code trap_spot} radius of 3. */
    public boolean anyTrapWithin(BlockPos centre, int radius) {
        return anyWithin(Kind.TRAP, centre, radius);
    }

    /**
     * Squared Euclidean distance from {@code centre} to the nearest cell of {@code kind} inside the cube
     * {@code centre +- radius}, or {@link #NONE}. Callers wanting a sphere compare against
     * {@code r * r}.
     */
    public long nearestDistanceSq(Kind kind, BlockPos centre, int radius) {
        long[] best = {NONE};
        int cx = centre.getX();
        int cy = centre.getY();
        int cz = centre.getZ();
        scan(kind, centre, radius, (x, y, z, tick) -> {
            long d = distSq(x, y, z, cx, cy, cz);
            if (best[0] == NONE || d < best[0]) {
                best[0] = d;
            }
            return false;
        });
        return best[0];
    }

    public long nearestLavaDistanceSq(BlockPos centre, int radius) {
        return nearestDistanceSq(Kind.LAVA, centre, radius);
    }

    /**
     * All cells of {@code kind} inside the cube, as a new list ordered nearest first (squared distance to
     * {@code centre}), ties by BlockPos order (y, z, x).
     */
    public List<BlockPos> cellsWithin(Kind kind, BlockPos centre, int radius) {
        List<Found> found = new ArrayList<>();
        int cx = centre.getX();
        int cy = centre.getY();
        int cz = centre.getZ();
        scan(kind, centre, radius, (x, y, z, tick) -> {
            found.add(new Found(x, y, z, distSq(x, y, z, cx, cy, cz)));
            return false;
        });
        found.sort(Comparator.comparingLong(Found::distSq)
                .thenComparingInt(Found::y).thenComparingInt(Found::z).thenComparingInt(Found::x));
        List<BlockPos> result = new ArrayList<>(found.size());
        for (Found f : found) {
            result.add(new BlockPos(f.x(), f.y(), f.z()));
        }
        return result;
    }

    public List<BlockPos> lavaCellsWithin(BlockPos centre, int radius) {
        return cellsWithin(Kind.LAVA, centre, radius);
    }

    // ---- bookkeeping --------------------------------------------------------------------------

    public int count() {
        return size;
    }

    public int count(Kind kind) {
        return perKind[kind.ordinal()];
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Every stored cell in BlockPos order (y, z, x); for logs and tests. */
    public List<Cell> snapshot() {
        List<Cell> cells = new ArrayList<>(size);
        for (Bucket bucket : buckets.values()) {
            long remaining = bucket.occupied;
            while (remaining != 0L) {
                int local = Long.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1L;
                int x = (bucket.bx << SHIFT) + (local & LOCAL_MASK);
                int z = (bucket.bz << SHIFT) + (local >> SHIFT & LOCAL_MASK);
                int y = (bucket.by << SHIFT) + (local >> (2 * SHIFT) & LOCAL_MASK);
                cells.add(new Cell(new BlockPos(x, y, z), KINDS[bucket.kinds[local]], bucket.ticks[local]));
            }
        }
        cells.sort(Comparator.comparing(Cell::pos, POS_ORDER));
        return cells;
    }

    /** Cumulative number of buckets touched by radius queries; a test hook for the "only relevant buckets" rule. */
    long bucketProbes() {
        return bucketProbes;
    }

    // ---- internals ----------------------------------------------------------------------------

    private boolean scan(Kind kind, BlockPos centre, int radius, CellVisitor visitor) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(centre, "centre");
        if (radius < 0 || perKind[kind.ordinal()] == 0) {
            return false;
        }
        int minX = clamp((long) centre.getX() - radius, XZ_LIMIT);
        int maxX = clamp((long) centre.getX() + radius, XZ_LIMIT);
        int minY = clamp((long) centre.getY() - radius, Y_LIMIT);
        int maxY = clamp((long) centre.getY() + radius, Y_LIMIT);
        int minZ = clamp((long) centre.getZ() - radius, XZ_LIMIT);
        int maxZ = clamp((long) centre.getZ() + radius, XZ_LIMIT);
        int minBx = minX >> SHIFT;
        int maxBx = maxX >> SHIFT;
        int minBy = minY >> SHIFT;
        int maxBy = maxY >> SHIFT;
        int minBz = minZ >> SHIFT;
        int maxBz = maxZ >> SHIFT;
        int wanted = kind.ordinal();
        long volume = (long) (maxBx - minBx + 1) * (maxBy - minBy + 1) * (maxBz - minBz + 1);
        if (volume <= buckets.size()) {
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int by = minBy; by <= maxBy; by++) {
                    for (int bz = minBz; bz <= maxBz; bz++) {
                        bucketProbes++;
                        Bucket bucket = buckets.get(bucketKey(bx, by, bz));
                        if (bucket != null
                                && scanBucket(bucket, wanted, minX, maxX, minY, maxY, minZ, maxZ, visitor)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        // A huge cube covers more buckets than exist: walk the stored buckets instead.
        for (Bucket bucket : buckets.values()) {
            bucketProbes++;
            if (bucket.bx < minBx || bucket.bx > maxBx || bucket.by < minBy || bucket.by > maxBy
                    || bucket.bz < minBz || bucket.bz > maxBz) {
                continue;
            }
            if (scanBucket(bucket, wanted, minX, maxX, minY, maxY, minZ, maxZ, visitor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanBucket(Bucket bucket, int wanted, int minX, int maxX, int minY, int maxY,
                                      int minZ, int maxZ, CellVisitor visitor) {
        long remaining = bucket.occupied;
        while (remaining != 0L) {
            int local = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            if (bucket.kinds[local] != wanted) {
                continue;
            }
            int x = (bucket.bx << SHIFT) + (local & LOCAL_MASK);
            int z = (bucket.bz << SHIFT) + (local >> SHIFT & LOCAL_MASK);
            int y = (bucket.by << SHIFT) + (local >> (2 * SHIFT) & LOCAL_MASK);
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                continue;
            }
            if (visitor.visit(x, y, z, bucket.ticks[local])) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(long value, int limit) {
        return (int) Math.max(-limit, Math.min(limit - 1, value));
    }

    private static int localIndex(int x, int y, int z) {
        return ((y & LOCAL_MASK) << (2 * SHIFT)) | ((z & LOCAL_MASK) << SHIFT) | (x & LOCAL_MASK);
    }

    /**
     * Collision-free for |x|, |z| below 2^27 and |y| below 2^13 (26 and 12 bucket bits); BlockPos itself
     * packs 26 bits for x and z and 12 for y, so every valid position fits.
     */
    private static long bucketKey(int bx, int by, int bz) {
        return ((long) (bx & 0x3FFFFFF) << 38) | ((long) (bz & 0x3FFFFFF) << 12) | (by & 0xFFF);
    }

    private static long distSq(int x, int y, int z, int rx, int ry, int rz) {
        long dx = (long) x - rx;
        long dy = (long) y - ry;
        long dz = (long) z - rz;
        return dx * dx + dy * dy + dz * dz;
    }
}
