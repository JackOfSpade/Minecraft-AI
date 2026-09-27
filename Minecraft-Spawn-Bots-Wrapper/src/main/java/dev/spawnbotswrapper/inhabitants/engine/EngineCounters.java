package dev.spawnbotswrapper.inhabitants.engine;

/** Session totals reported by {@code /inhabitants info}; plain fields because only the server thread touches them. */
final class EngineCounters {
    /** Structures accepted for rolling (new to the store and not filtered out). */
    long seen;
    /** Rolls actually performed (occupied and abandoned). */
    long rolled;
    /** Spawn requests sent to the bot side. */
    long requested;
    /** Bots that reached SPAWNED. */
    long spawned;
    /** Bots that reached FAILED. */
    long failed;
}
