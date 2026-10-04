package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * Per-bot POI evidence window (mining-assist design 2.4 and 3.3): the non-natural cells the sensor
 * has seen, capped at {@value #STRUCTURAL_CAP} and expired {@value #EXPIRE_TICKS} ticks after they
 * were last seen. Pure (BlockPos and {@link PoiBucket} only), server thread only.
 *
 * <p>Two sub-windows keep one noisy source from starving the other:</p>
 * <ul>
 *   <li><b>structural</b>: cells whose bucket is not {@link PoiBucket#NATURAL}. They are the scorer's
 *       cells and carry the specific-block and habitation flags of their id.</li>
 *   <li><b>flag-only</b> (cap {@value #FLAG_ONLY_CAP}): natural cells that still matter for a
 *       presence flag, such as natural blackstone. They never push structural evidence out of the
 *       structural window.</li>
 * </ul>
 * A cell that is natural and carries no flag is not evidence and is never stored. Eviction at a cap
 * drops the least recently seen cell; expiry is a full pass, so it does not depend on ticks being
 * monotonic. Callers exclude bot-placed cells before they reach {@link #observe}.
 */
public final class PoiEvidenceWindow {
    public static final int STRUCTURAL_CAP = 512;
    public static final int FLAG_ONLY_CAP = 128;
    /** Unseen-for age after which a cell drops out (the scorer's evidence window). */
    public static final int EXPIRE_TICKS = PoiScorer.EVIDENCE_WINDOW_TICKS;

    /** One remembered cell. {@code viaDecor} is true when only the OUTLINE pass has ever seen it. */
    public record Entry(BlockPos pos, PoiBucket bucket, int flags, int lastTick, boolean viaDecor) {
        public Entry {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            Objects.requireNonNull(bucket, "bucket");
        }
    }

    private final Map<Long, Entry> structural = new LinkedHashMap<>(64, 0.75F, true);
    private final Map<Long, Entry> flagOnly = new LinkedHashMap<>(32, 0.75F, true);

    /**
     * Records that {@code pos} was seen at {@code tick} as {@code bucket} carrying {@code flags}.
     * Returns true when the cell is new to the window. A natural cell without flags removes any
     * earlier entry for the position (it was re-observed as something harmless).
     */
    public boolean observe(BlockPos pos, PoiBucket bucket, int flags, int tick, boolean viaDecor) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(bucket, "bucket");
        long key = pos.asLong();
        boolean natural = bucket.isNatural();
        if (natural && flags == 0) {
            structural.remove(key);
            flagOnly.remove(key);
            return false;
        }
        Map<Long, Entry> target = natural ? flagOnly : structural;
        Map<Long, Entry> other = natural ? structural : flagOnly;
        other.remove(key);
        Entry existing = target.get(key);
        boolean decor = existing == null ? viaDecor : existing.viaDecor() && viaDecor;
        target.put(key, new Entry(pos, bucket, flags, tick, decor));
        int cap = natural ? FLAG_ONLY_CAP : STRUCTURAL_CAP;
        if (target.size() > cap) {
            Iterator<Long> eldest = target.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        return existing == null;
    }

    /**
     * Forgets a position (mined, replaced, or observed as something harmless). Returns true if one
     * existed. Both sub-windows are always checked -- a position lives in at most one of them, but
     * nothing guarantees the caller knows which, so both removes run unconditionally as independent
     * statements rather than relying on {@code ||} short-circuiting to skip the second one.
     */
    public boolean remove(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        long key = pos.asLong();
        boolean removedStructural = structural.remove(key) != null;
        boolean removedFlagOnly = flagOnly.remove(key) != null;
        return removedStructural || removedFlagOnly;
    }

    /** Drops every entry unseen for more than {@link #EXPIRE_TICKS}. Returns how many were dropped. */
    public int expire(int nowTick) {
        return expireFrom(structural, nowTick) + expireFrom(flagOnly, nowTick);
    }

    private static int expireFrom(Map<Long, Entry> map, int nowTick) {
        int dropped = 0;
        Iterator<Entry> it = map.values().iterator();
        while (it.hasNext()) {
            if ((long) nowTick - it.next().lastTick() > EXPIRE_TICKS) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    public boolean contains(BlockPos pos) {
        long key = Objects.requireNonNull(pos, "pos").asLong();
        return structural.containsKey(key) || flagOnly.containsKey(key);
    }

    /** Non-natural cells, least recently seen first. Live view: do not modify the window while iterating. */
    public Collection<Entry> structuralEntries() {
        return Collections.unmodifiableCollection(structural.values());
    }

    /** Natural cells kept for a presence flag, least recently seen first. Live view. */
    public Collection<Entry> flagOnlyEntries() {
        return Collections.unmodifiableCollection(flagOnly.values());
    }

    public int structuralSize() {
        return structural.size();
    }

    public int flagOnlySize() {
        return flagOnly.size();
    }

    public int size() {
        return structural.size() + flagOnly.size();
    }

    public boolean isEmpty() {
        return structural.isEmpty() && flagOnly.isEmpty();
    }

    public void clear() {
        structural.clear();
        flagOnly.clear();
    }
}
