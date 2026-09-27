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
    public static final int CURRENT_DATA_VERSION = 1;

    public int dataVersion = CURRENT_DATA_VERSION;
    public StructureStatus status = StructureStatus.OCCUPIED_PENDING;

    /** How the decision was made: RANDOM, DETERMINISTIC, or ADMIN_FORCED. */
    public String source = "RANDOM";
    /** The effective occupied chance in force when rolled, and the uniform roll in [0,1) that was compared to it. */
    public double occupiedChance;
    public double roll;
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

    /** True when every planned bot has reached a terminal state. */
    public boolean allBotsResolved() {
        for (BotRecord b : bots) {
            if (b.state != BotState.SPAWNED && b.state != BotState.FAILED) {
                return false;
            }
        }
        return true;
    }

    public int spawnedCount() {
        int n = 0;
        for (BotRecord b : bots) {
            if (b.state == BotState.SPAWNED) {
                n++;
            }
        }
        return n;
    }
}
