package dev.spawnbotswrapper.inhabitants.store;

/**
 * Lifecycle of one structure instance. Every transition is persisted; nothing ever goes back to "unseen"
 * except an explicit admin reset.
 * <pre>
 *   (unseen) --roll--> ABANDONED                      terminal, zero bots, never rolled again
 *            \-roll--> OCCUPIED_PENDING --all bots resolved--> POPULATED   (>= 1 bot spawned)
 *                                       \-gave up-----------> GAVE_UP     (no bot could be placed)
 * </pre>
 * POPULATED and GAVE_UP are terminal: killed bots are never replaced.
 */
public enum StructureStatus {
    /** Rolled "empty"; permanent. */
    ABANDONED,
    /** Rolled "occupied"; bots are planned but not all are placed yet. Survives restarts and resumes. */
    OCCUPIED_PENDING,
    /** All planned bots reached a terminal state and at least one was spawned. */
    POPULATED,
    /** Rolled occupied but no bot could ever be placed (no valid positions after all attempts). Permanent. */
    GAVE_UP;

    public boolean isTerminal() {
        return this != OCCUPIED_PENDING;
    }
}
