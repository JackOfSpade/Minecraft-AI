package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory companion of an OCCUPIED_PENDING record: the snapshot (positions need the piece geometry, which is
 * not persisted) and the scheduling state. Nothing here is persisted; after a restart the snapshot is supplied
 * again by the next detection of the structure and this object is rebuilt.
 * <p>
 * Timing fields hold ticks at which something HAPPENED, never deadlines computed from the configuration, so a
 * reloaded config applies immediately.
 */
final class PendingStructure {
    /** "Long ago" without risking overflow when subtracted from a tick. */
    static final long NEVER = Long.MIN_VALUE / 4;

    final StructureKey key;
    final StructureSnapshot snapshot;
    /** Tick at which it was queued; the initial delay counts from here. */
    final long queuedAtTick;
    /** Admin-forced structures skip the initial delay. */
    final boolean skipInitialDelay;

    /** Positions found but not yet used, by bot index. Recomputed after a restart or when they go stale. */
    final Map<Integer, SpawnPlanner.Position> assigned = new HashMap<>();
    long assignedAtTick = NEVER;
    /** Last tick a planning attempt (or an error / spawn failure) happened; retries wait from here. */
    long lastPlanTick = NEVER;
    /**
     * Last tick a write-ahead save failed. Requests for this structure wait one retry interval from here, so a
     * disk that stays broken is probed occasionally instead of being hammered every tick.
     */
    long saveFailedAtTick = NEVER;
    /** True until the first look at the record has adopted or released bots a previous session left REQUESTED. */
    boolean needsAudit = true;
    boolean done;
    /** The live record last seen in the store (identity re-checked whenever it matters). */
    StructureRecord record;

    PendingStructure(StructureSnapshot snapshot, long queuedAtTick, boolean skipInitialDelay) {
        this.key = snapshot.key();
        this.snapshot = snapshot;
        this.queuedAtTick = queuedAtTick;
        this.skipInitialDelay = skipInitialDelay;
    }

    boolean awake(long now, int initialDelayTicks) {
        return skipInitialDelay || now - queuedAtTick >= Math.max(0, initialDelayTicks);
    }
}
