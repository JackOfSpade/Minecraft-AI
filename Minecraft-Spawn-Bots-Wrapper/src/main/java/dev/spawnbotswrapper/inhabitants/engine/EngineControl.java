package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;

/**
 * Testing/admin operations. These are deliberately NOT part of normal gameplay: nothing here is ever
 * triggered by the world itself.
 */
public interface EngineControl {

    /**
     * Rolls a structure that has no record yet (per {@code mode}) and queues its population, bypassing the
     * "only newly generated" and dimension/include filters but NOT the exclude list unless {@code mode}
     * forces it. Returns what happened.
     */
    ProcessOutcome process(StructureSnapshot snapshot, ForceMode mode);

    /**
     * Forgets the record of ONE structure so it can be rolled again the next time it is encountered
     * (deterministic mode reproduces the same roll). When {@code removeBots} is true, its living
     * inhabitants are removed through PvP BOT first. Returns false if there was no record.
     */
    boolean reset(StructureKey key, boolean removeBots);

    /** Counters for {@code /inhabitants info}. */
    EngineStats stats();

    /** Result of {@link #process}. */
    record ProcessOutcome(Kind kind, String message) {
        public enum Kind {
            /** Rolled abandoned. */
            ABANDONED,
            /** Rolled occupied; population queued. */
            OCCUPIED_QUEUED,
            /** A record already existed; nothing changed. */
            ALREADY_PROCESSED,
            /** Not eligible (excluded / addon disabled / integration unavailable). */
            REJECTED
        }
    }

    record EngineStats(
            long structuresSeen,
            long structuresRolled,
            long botsRequested,
            long botsSpawned,
            long botsFailed,
            int queuedStructures,
            int botsInFlight,
            int liveBots) {
    }
}
