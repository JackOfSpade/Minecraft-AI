package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;

/**
 * Persisted data of one inhabitant. Mutable plain object (Gson-bound); only the engine mutates it and
 * marks the store dirty afterwards.
 * <p>
 * Bot identity upstream is the NAME only (PvP BOT keys everything by name), so {@link #name} is the
 * authoritative handle; {@link #uuid} is recorded for diagnostics once the entity exists.
 */
public final class BotRecord {
    /** Index of this bot within its structure (0-based); part of the deterministic seed derivation. */
    public int index;
    /** Unique bot name, [A-Za-z0-9_]{3,16}. Decided at roll time. */
    public String name;
    /** Player UUID once the entity has been seen, else null. */
    public String uuid;
    /** Seed the profile is (re)derived from in deterministic mode; always recorded. */
    public long seed;
    public BotState state = BotState.PLANNED;

    /** Chosen spawn position (valid once state >= REQUESTED). */
    public double x;
    public double y;
    public double z;
    public float yaw;

    /** Spawn attempts made for this bot (position found + request sent). */
    public int spawnAttempts;
    /** Wall-clock millis when the bot reached SPAWNED (informational). */
    public long spawnedAtMillis;
    /** Last failure reason, for diagnostics. */
    public String failure;

    /** Generated profile; null until the bot spawns. Authoritative once set: never regenerated. */
    public BotProfile profile;
    /** Version of the profile format {@link #profile} was written with. */
    public int profileVersion;
    /** True once the loadout/vitals/behaviour were applied to the live entity. */
    public boolean profileApplied;
    /**
     * The latest live state of the bot (inventory, health, hunger, effects, ...), refreshed while it is online and
     * taken once more when it goes dormant or the server stops; null for a bot that was never snapshotted (records
     * from before snapshots existed, or a bot that is not yet dressed). A bot that comes back is restored from it
     * rather than dressed again from {@link #profile}, so what it used up stays used up.
     */
    public BotSnapshot snapshot;

    /**
     * True once a real player has SEEN this bot (in the player's view cone, unobstructed). Persistent: it survives chunk
     * unloads, sleep and restarts and is never cleared while the bot lives. A seen bot is never removed for good by
     * anything but its own death; it goes to sleep (state DORMANT, its saved state kept) instead. An unseen bot is
     * ephemeral: when it has to go it is deleted, no record kept, and its slot is free for a fresh roll.
     */
    public boolean seen;
    /** Wall-clock millis of the first and of the latest sighting (informational); 0 while never seen. */
    public long firstSeenMillis;
    public long lastSeenMillis;
    /**
     * Dimension the saved position {@link #x}/{@link #y}/{@link #z} is in when it is not the structure's own (a bot
     * that walked through a portal); null otherwise.
     */
    public String dimension;
    /**
     * True from the moment a sleep of this (seen) bot was recorded until its entity is really gone. A record found
     * DORMANT with this flag after a crash and a live entity is finished (cleared and removed) on the next start, so the
     * store never holds both a live bot and a restorable copy of what it carries.
     */
    public boolean removing;
    /**
     * True once the one-time sanitize pass of a bot from before issued items were marked has run (every pearl and disabled
     * enchantment stripped from all its stacks). Afterwards the sweeps judge only stacks the wrapper issued (marked), so what
     * the bot picked up in the world is never touched. False for a record written by an older version, and for a new bot
     * until its first sweep (the pass then finds nothing: everything it carries is issued and already judged).
     */
    public boolean itemsMigrated;

    public BotRecord() {
    }

    public BotRecord(int index, String name, long seed) {
        this.index = index;
        this.name = name;
        this.seed = seed;
    }
}
