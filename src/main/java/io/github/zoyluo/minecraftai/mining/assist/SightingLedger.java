package io.github.zoyluo.minecraftai.mining.assist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * Bounded per-bot ledger of valuable sightings (mining-assist design 2.4 and 3.3, cap {@value #CAP}).
 *
 * <p>A sighting is only a nomination (invariant I1): whoever acts on one must re-prove the cell with a
 * live observation first. The ledger therefore never claims a block is still there, it just remembers
 * what was seen and when.</p>
 *
 * <p>One entry per position. Re-observing a position keeps the earliest first tick, the latest last tick
 * and the highest raw value. All orderings are total and deterministic:</p>
 * <ul>
 *   <li><b>Retention rank</b> (best first): higher raw value, then more recent last tick, then BlockPos
 *       order (y, z, x ascending). {@link #snapshotSortedByValueDesc()} lists in this order.</li>
 *   <li><b>Eviction</b> when full: the entry ranked last among the stored entries and the incoming one
 *       is dropped. The incoming sighting can therefore be rejected. Keeping the best {@value #CAP} of a
 *       stream under a fixed total order does not depend on arrival order.</li>
 *   <li><b>Nearest</b>: squared Euclidean distance ascending, then higher raw value, then BlockPos order.</li>
 * </ul>
 *
 * <p>Time is always the caller's server tick. Not thread-safe: server thread only.</p>
 */
public final class SightingLedger {
    public static final int CAP = 64;

    /**
     * One remembered valuable. {@code blockId} is the registry path the caller reported (for example
     * {@code diamond_ore}); {@code rawValue} is the caller's value-table value, negative values are
     * clamped to 0.
     */
    public record Sighting(BlockPos pos, String blockId, int rawValue, int firstTick, int lastTick) {
        public Sighting {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            Objects.requireNonNull(blockId, "blockId");
            rawValue = Math.max(0, rawValue);
        }
    }

    public enum Outcome {
        /** A new position was stored (possibly evicting a lower-ranked entry). */
        ADDED,
        /** The position was already stored and its entry was merged. */
        UPDATED,
        /** The ledger is full and the sighting ranks below every stored entry. */
        REJECTED
    }

    /** BlockPos order: y, then z, then x (same as {@code Vec3i.compareTo}, without overflow). */
    private static final Comparator<BlockPos> POS_ORDER = Comparator
            .<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ)
            .thenComparingInt(BlockPos::getX);

    private static final Comparator<Sighting> RETENTION_ORDER = Comparator
            .comparingInt(Sighting::rawValue).reversed()
            .thenComparing(Comparator.comparingInt(Sighting::lastTick).reversed())
            .thenComparing(Sighting::pos, POS_ORDER);

    private final Map<Long, Sighting> byPos = new HashMap<>();

    /**
     * Records a sighting of {@code blockId} worth {@code rawValue} at {@code pos}, seen at {@code tick}.
     *
     * <p>Merge rule for an existing position: {@code firstTick = min}, {@code lastTick = max},
     * {@code rawValue = max}. The stored block id belongs to the observation that supplied the
     * winning value: a strictly higher value replaces it, an equal value replaces it only when the
     * new observation is at least as recent, a lower value never does. That keeps id and value
     * consistent even if a different valuable appears at a remembered position.</p>
     */
    public Outcome observe(BlockPos pos, String blockId, int rawValue, int tick) {
        Sighting incoming = new Sighting(pos, blockId, rawValue, tick, tick);
        long key = incoming.pos().asLong();
        Sighting existing = byPos.get(key);
        if (existing != null) {
            boolean incomingWins = incoming.rawValue() > existing.rawValue()
                    || (incoming.rawValue() == existing.rawValue() && tick >= existing.lastTick());
            byPos.put(key, new Sighting(
                    existing.pos(),
                    incomingWins ? incoming.blockId() : existing.blockId(),
                    Math.max(existing.rawValue(), incoming.rawValue()),
                    Math.min(existing.firstTick(), tick),
                    Math.max(existing.lastTick(), tick)));
            return Outcome.UPDATED;
        }
        if (byPos.size() >= CAP) {
            Sighting worst = lowestRanked();
            if (RETENTION_ORDER.compare(incoming, worst) > 0) {
                return Outcome.REJECTED;
            }
            byPos.remove(worst.pos().asLong());
        }
        byPos.put(key, incoming);
        return Outcome.ADDED;
    }

    /** Forgets the sighting at {@code pos} (mined, consumed or observed gone). Returns true if one existed. */
    public boolean markGone(BlockPos pos) {
        return byPos.remove(Objects.requireNonNull(pos, "pos").asLong()) != null;
    }

    /** The sighting at {@code pos}, or null. */
    public Sighting get(BlockPos pos) {
        return byPos.get(Objects.requireNonNull(pos, "pos").asLong());
    }

    public boolean contains(BlockPos pos) {
        return get(pos) != null;
    }

    /** A new list of every sighting in retention-rank order: value descending, then last tick descending, then BlockPos order. */
    public List<Sighting> snapshotSortedByValueDesc() {
        List<Sighting> all = new ArrayList<>(byPos.values());
        all.sort(RETENTION_ORDER);
        return all;
    }

    /**
     * Up to {@code limit} sightings nearest to {@code from} by squared Euclidean distance, ties by higher
     * raw value then BlockPos order. A new list; {@code limit <= 0} gives an empty one.
     */
    public List<Sighting> nearestTo(BlockPos from, int limit) {
        Objects.requireNonNull(from, "from");
        if (limit <= 0 || byPos.isEmpty()) {
            return new ArrayList<>();
        }
        List<Sighting> all = new ArrayList<>(byPos.values());
        all.sort(Comparator.comparingLong((Sighting s) -> distSq(s.pos(), from))
                .thenComparing(Comparator.comparingInt(Sighting::rawValue).reversed())
                .thenComparing(Sighting::pos, POS_ORDER));
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }

    /**
     * Drops every sighting whose age ({@code nowTick - lastTick}) is strictly greater than
     * {@code maxAge}. Returns the number dropped.
     */
    public int expire(int nowTick, int maxAge) {
        int before = byPos.size();
        byPos.values().removeIf(s -> (long) nowTick - s.lastTick() > maxAge);
        return before - byPos.size();
    }

    public int size() {
        return byPos.size();
    }

    public boolean isEmpty() {
        return byPos.isEmpty();
    }

    public void clear() {
        byPos.clear();
    }

    private Sighting lowestRanked() {
        Sighting worst = null;
        for (Sighting candidate : byPos.values()) {
            if (worst == null || RETENTION_ORDER.compare(candidate, worst) > 0) {
                worst = candidate;
            }
        }
        return worst;
    }

    private static long distSq(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
