package io.github.zoyluo.aibot.mining.assist;

/**
 * The levers the coordinator gets on a live detour (mining-assist design 2.3 steps 3b and 3c), published in
 * {@link MiningAssistState} by the task that owns the detour. They exist so that {@code MiningAssistCoordinator}
 * can stop a detour and settle its books without touching the action pack, the task manager or any task class
 * (the coordinator's source contract forbids all three).
 *
 * <p>Implemented by {@code OreDigTask.DetourHostImpl}: {@link #abortNow} stops movement and mining at once
 * ({@code stopAll()} and cancel the miner) and then calls {@code engine.requestAbort(reason)}, which the engine
 * consumes at its next tick by turning the abort into a RETURN. Idempotent: the first reason wins, later calls
 * only repeat the (harmless) stop. Server thread only.</p>
 */
public interface DetourControl {
    /**
     * Stops what the detour is doing now and asks the engine to abort with {@code reason} (one of the
     * abort reasons of design 4.12, for example {@code degraded_tps} or {@code safety_hurt}).
     */
    void abortNow(String reason);

    /**
     * The coordinator's orphan cleanup found that the task that published the detour is gone or no longer
     * running (cause {@code owner_changed} or {@code not_running}: it failed or completed mid-detour, which
     * bypasses {@code onAbort}). The implementation notes the end of the detour in the mission ledger (not
     * completed, so the next detour of the mission keeps its interval) and forgets the engine state. It must not
     * touch the action pack or the world (a SAFETY task may own them). Called at most once per orphaning; the
     * default does nothing so a test double need not implement it.
     */
    default void abandoned(int serverTick) {
    }
}
