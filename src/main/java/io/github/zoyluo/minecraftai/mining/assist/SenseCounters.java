package io.github.zoyluo.minecraftai.mining.assist;

/**
 * Observability counters of one bot's sensor over one reporting window (mining-assist design 3.4:
 * profiler sections are observability only and never feed control). Plain mutable longs, server
 * thread only. {@code MiningAssistState} owns one and swaps it out when a summary line is written.
 */
public final class SenseCounters {
    public long steps;
    public long rays;
    public long unknownRays;
    public long decorRays;
    public long decorEvidence;
    public long sweepsCompleted;
    public long breakthroughs;
    /** Breakthrough restarts refused because the previous one was less than 40 ticks ago. */
    public long breakthroughsDeferred;
    /** {@code assist_poi_band} lines withheld by the per-bot minimum gap (see {@code MiningAssistState#shouldLogPoiBand}). */
    public long poiBandsSuppressed;
    public long sightingsAdded;
    /**
     * Scratch, not a window total: the highest raw value among the sightings added since the coordinator last
     * zeroed it (once per sensing pass). It lets the pass skip the ledger snapshot of the rare-find log line
     * unless something worth announcing was added.
     */
    public int newSightingMaxValue;
    public long sightingsUpdated;
    public long sightingsRejected;
    /** {@code assist_sighting} lines written in this window (capped so a rich vein cannot flood the log). */
    public long sightingsLogged;
    public long lavaCells;
    public long waterCells;
    public long trapCells;
    public long poiCellsAdded;
    public long peekedBreaks;
    /** Breaks whose own cell was not observed as open space afterwards (refused, or not observable): nothing was assumed. */
    public long breaksUnconfirmed;
    public long peekNeighbours;
    public long entityScans;
    public long poiEvaluations;
    public long sweepNanos;
    public long foldNanos;
    public long maxStepNanos;
    public long poiNanos;
    public long maxPoiNanos;
    public long raysThrottledOut;
    // ---- P1 detour counters of the window (incremented by OreDigTask.tickOpportunistic, printed by MiningAssistLog) ----
    public long detourStarts;
    public long detourBreaks;
    public long detourSeals;
    public long detourDropsLost;
    /** Detours that ended with an abort reason (not "done" or "caps"), rebases included. */
    public long detourAborts;
    public long detourRebases;

    /** True when nothing at all happened in this window. */
    public boolean isIdle() {
        return steps == 0 && peekedBreaks == 0 && poiEvaluations == 0 && detourStarts == 0;
    }
}
