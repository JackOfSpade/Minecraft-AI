package dev.spawnbotswrapper.inhabitants.store;

/**
 * Lifecycle of one planned inhabitant. The name and seed are decided when the structure is rolled and
 * persisted immediately, so a crash at any later point can resume without re-rolling or renaming.
 * <pre>
 *   PLANNED --position found + spawn requested--> REQUESTED --entity appeared + profile applied--> SPAWNED
 *      ^                                              |
 *      +---------- timeout / refusal (attempt++) -----+--> FAILED (attempts exhausted)
 * </pre>
 */
public enum BotState {
    /** Name and seed chosen; no position yet, or a previous attempt failed and it is waiting to retry. */
    PLANNED,
    /** A spawn request was sent to PvP BOT; waiting for the player entity to appear. */
    REQUESTED,
    /** The entity exists, the profile was generated and applied. Terminal. */
    SPAWNED,
    /** Gave up on this bot (no position / spawn refused too often). Terminal. */
    FAILED
}
