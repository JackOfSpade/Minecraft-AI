package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only queries over what has been processed. Used by the admin commands. */
public interface PopulationView {

    /** The record for a structure instance, including a synthesized ABANDONED record; empty if never processed. */
    Optional<StructureRecord> find(StructureKey key);

    /**
     * Processed structures whose start chunk lies within {@code radiusChunks} (Chebyshev) of the given
     * chunk in that dimension, nearest first. Includes abandoned ones.
     */
    List<Map.Entry<StructureKey, StructureRecord>> nearby(String dimensionId, int chunkX, int chunkZ, int radiusChunks);

    /** Which structure and record an inhabitant belongs to, by (case-insensitive) bot name. */
    Optional<BotLocation> findBot(String botName);

    PopulationCounts counts();

    record BotLocation(StructureKey structure, BotRecord bot) {
    }

    record PopulationCounts(int abandoned, int pending, int populated, int gaveUp, int botsSpawned, int botsFailed) {
        public int structures() {
            return abandoned + pending + populated + gaveUp;
        }
    }
}
