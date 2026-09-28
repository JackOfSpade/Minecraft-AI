package dev.spawnbotswrapper.inhabitants.store;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted result of processing one structure instance. Mutable plain object (Gson-bound).
 * <p>
 * The store keeps records for ABANDONED structures in a separate compact append-only key list (there can
 * be hundreds of thousands of them in a well-explored world); see {@code PopulationStore}. In memory an
 * abandoned structure is still represented by a {@code StructureRecord} with status ABANDONED.
 */
public final class StructureRecord {
    /** Bump when the meaning of stored fields changes incompatibly. */
    public static final int CURRENT_DATA_VERSION = 2;

    public int dataVersion = CURRENT_DATA_VERSION;
    public StructureStatus status = StructureStatus.OCCUPIED_PENDING;

    /** How the decision was made: RANDOM, DETERMINISTIC, or ADMIN_FORCED. */
    public String source = "RANDOM";
    /** The effective occupied chance in force when rolled, and the uniform roll in [0,1) that was compared to it. */
    public double occupiedChance;
    public double roll;
    /**
     * True only for a full record actually produced by a roll (see {@code PopulationEngine.roll()}), never for
     * the compact placeholder {@link #abandoned()} synthesises for entries that live only in the abandoned-key
     * log with no details retained. {@code occupiedChance}/{@code roll} alone cannot tell those two apart: a
     * real roll of exactly {@code 0.0} against a genuinely 0% chance is rare but possible, and would otherwise
     * be mistaken for "no data" (wrapperB-1). Records persisted before this field existed (dataVersion &lt; 2)
     * are backfilled with the old numeric heuristic on load -- see {@code PopulationFile.normalise}.
     */
    public boolean rollDetailsKept;
    /** Seed all per-structure randomness derives from (positions, bot names, bot seeds). */
    public long structureSeed;
    /** Number of bots planned when the structure was rolled occupied. */
    public int plannedBots;
    /** Population attempts made while the structure's chunks were loaded. */
    public int attempts;
    /** Wall-clock millis when the roll happened (informational only; never used for logic). */
    public long rolledAtMillis;
    /** Human-readable note (why GAVE_UP, admin action, ...). */
    public String note;

    /** Structure bounding box {minX,minY,minZ,maxX,maxY,maxZ}, kept so admin tools can locate it while unloaded. */
    public int[] bounds;

    public List<BotRecord> bots = new ArrayList<>();

    public StructureRecord() {
    }

    public static StructureRecord abandoned() {
        StructureRecord r = new StructureRecord();
        r.status = StructureStatus.ABANDONED;
        return r;
    }

    /**
     * True when every planned bot has reached a terminal state, or DORMANT -- which counts as resolved too:
     * that bot has already been placed successfully at least once and is merely away, not unaccounted for.
     */
    public boolean allBotsResolved() {
        for (BotRecord b : bots) {
            if (b.state != BotState.SPAWNED && b.state != BotState.FAILED && b.state != BotState.DORMANT) {
                return false;
            }
        }
        return true;
    }

    /** SPAWNED and DORMANT both count: a dormant bot was placed successfully, it is just away right now. */
    public int spawnedCount() {
        int n = 0;
        for (BotRecord b : bots) {
            if (b.state == BotState.SPAWNED || b.state == BotState.DORMANT) {
                n++;
            }
        }
        return n;
    }
}
