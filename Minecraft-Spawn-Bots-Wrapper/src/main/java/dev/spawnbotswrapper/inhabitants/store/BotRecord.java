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

    public BotRecord() {
    }

    public BotRecord(int index, String name, long seed) {
        this.index = index;
        this.name = name;
        this.seed = seed;
    }
}
