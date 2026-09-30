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
    /**
     * Bump when the meaning of stored fields changes incompatibly. History:
     * <ul>
     *   <li>2: {@code rollDetailsKept} exists (older records are backfilled on load);</li>
     *   <li>3: every inhabitant is a fighter. {@code profile.behavior.combatant} is legacy and ignored; records
     *       below 3 have their pacifist profiles rewritten to fighters on load (see {@code PopulationFile}).</li>
     *   <li>4: population by allocation. A bot may be SEEN (persistent), only seen bots are kept when they leave the world
     *       (asleep), unseen ones are deleted without a record; a real death is a DEAD record. Records below 4 have
     *       their dormant, never-seen bots released on load (their slots are free again).</li>
     * </ul>
     */
    public static final int CURRENT_DATA_VERSION = 4;

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

    /**
     * The index the next FRESH bot of this structure gets (its name and seed derive from it); 0 while unset, and never
     * lower than one past the highest index in {@link #bots}. Indices are never reused: a slot refilled after an unseen
     * bot was deleted gets a new index, so it is a different bot (name, loadout) than the one that was deleted.
     */
    public int nextBotIndex;

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
            if (b.state != BotState.SPAWNED && b.state != BotState.FAILED && b.state != BotState.DORMANT
                    && b.state != BotState.DEAD) {
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

    /** True when a bot of this structure was ever placed alive: it is live or asleep now, or it died. */
    public boolean everPlaced() {
        return spawnedCount() > 0 || deadCount() > 0;
    }

    // ------------------------------------------------------------------ the fill arithmetic (no loot farm)

    /**
     * D: the bots of this structure that died while alive, from any cause. Only ever grows; a death is never a free slot.
     */
    public int deadCount() {
        return count(BotState.DEAD);
    }

    /** Bots that could never be placed (no valid position, spawn refused): their slot is spent as well. */
    public int failedCount() {
        return count(BotState.FAILED);
    }

    /** SEEN bots that are alive: awake, asleep, or being brought back. They are kept for good (until they die). */
    public int seenAliveCount() {
        int n = 0;
        for (BotRecord b : bots) {
            if (b.seen && (b.state == BotState.SPAWNED || b.state == BotState.DORMANT || b.state == BotState.REQUESTED)) {
                n++;
            }
        }
        return n;
    }

    /** Bots that occupy a slot right now, alive or about to be: planned, being requested, live or asleep. */
    public int occupiedSlots() {
        int n = 0;
        for (BotRecord b : bots) {
            if (b.state == BotState.PLANNED || b.state == BotState.REQUESTED || b.state == BotState.SPAWNED
                    || b.state == BotState.DORMANT) {
                n++;
            }
        }
        return n;
    }

    /**
     * The most bots this structure can still have at once: N - D - failed. N ({@link #plannedBots}) is rolled once and
     * never re-rolled, so a structure yields at most N bots' worth of kills over the world's lifetime.
     */
    public int fillTarget() {
        return Math.max(0, plannedBots - deadCount() - failedCount());
    }

    /**
     * Free slots: {@code N - D - failed - occupied}. Right after unseen bots were deleted this is
     * {@code N - D - seenAlive} (the seen ones are the only bots left); a killed bot is never here, so a death never
     * frees a slot.
     */
    public int vacantSlots() {
        return Math.max(0, fillTarget() - occupiedSlots());
    }

    /** Reserves and returns the index for a fresh bot (see {@link #nextBotIndex}). */
    public int allocateBotIndex() {
        int next = Math.max(nextBotIndex, 0);
        for (BotRecord b : bots) {
            next = Math.max(next, b.index + 1);
        }
        nextBotIndex = next + 1;
        return next;
    }

    private int count(BotState state) {
        int n = 0;
        for (BotRecord b : bots) {
            if (b.state == state) {
                n++;
            }
        }
        return n;
    }
}
