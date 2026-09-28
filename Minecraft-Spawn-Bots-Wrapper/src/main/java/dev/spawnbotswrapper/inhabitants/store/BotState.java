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
 * DORMANT is the one non-terminal exception to "death is permanent": unlike FAILED, it is expected to flip
 * back to SPAWNED (same name, position and profile) once its structure is near a real player again -- see
 * {@code DormancyGovernor} and {@code PopulationDriver#restoreDormant}. A bot removed for server load instead
 * (see {@code TpsGovernor}) is left SPAWNED and simply never comes back online, exactly like a real death.
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
    /** Was SPAWNED, despawned for being far from every real player for a while; remembered exactly, restored
     * (not re-rolled) the next time its structure is near a real player again. */
    DORMANT
}
