package dev.spawnbotswrapper.inhabitants.store;

/**
 * Lifecycle of one planned inhabitant. The name and seed are decided when the structure is rolled and
 * persisted immediately, so a crash at any later point can resume without re-rolling or renaming.
 * <pre>
 *   PLANNED --position found + spawn requested--> REQUESTED --entity appeared + profile applied--> SPAWNED
 *      ^                                              |                                             |  ^
 *      +---------- timeout / refusal (attempt++) -----+--> FAILED (attempts exhausted)               v  |
 *                                                                               far from every player, a while --> DORMANT
 * </pre>
 * DORMANT is the one non-terminal exception: a bot a player has SEEN and that had to leave the world (its structure
 * left the allocation, or the lag governor shed it) sleeps with its whole state and is expected to flip back to SPAWNED
 * (same name, position and identity) once its structure is allocated again -- see {@code AllocationGovernor} and
 * {@code Retirer}. A bot nobody saw is deleted instead (no record at all: its slot is vacant). DEAD is a real death and
 * is permanent: the slot is never refilled.
 */
public enum BotState {
    /** Name and seed chosen; no position yet, or a previous attempt failed and it is waiting to retry. */
    PLANNED,
    /** A spawn request was sent to PvP BOT; waiting for the player entity to appear. */
    REQUESTED,
    /** The entity exists, the profile was generated and applied. Terminal except for the DORMANT cycle above. */
    SPAWNED,
    /** Gave up on this bot (no position / spawn refused too often). Terminal. */
    FAILED,
    /** A SEEN bot put to sleep because its structure left the allocation (or the server was overloaded); remembered
     * exactly (state, inventory, position), woken (not re-rolled) when its structure is allocated again. */
    DORMANT,
    /**
     * Killed while alive (by a player, a mob, a fall, the void, anything): a real vanilla death that dropped what it
     * carried. Terminal and permanent: the slot is never refilled. The record only keeps the name and the index, so a
     * structure yields at most its planned number of bots' worth of kills over the world's lifetime.
     */
    DEAD
}
