package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;

/** What the engine needs from the Minecraft world. Server thread only; never blocks or loads chunks. */
public interface WorldGateway {

    /** The overworld seed; the basis of deterministic mode. */
    long worldSeed();

    /** Non-loading block view of the dimension, or null when that dimension is not loaded/known. */
    BlockProbe probe(String dimensionId);

    /**
     * Whether a bot can stand at this exact position right now ({@code SAFE}), cannot ({@code UNSAFE}), or that cannot be
     * told ({@code UNKNOWN}: not loaded, or a world with no block data). Decides where a sleeping bot wakes: at its saved
     * position when SAFE (or UNKNOWN), else placed again by the structure's own spawn logic.
     */
    default dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.Verdict standing(String dimensionId, double x, double y, double z) {
        return dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.Verdict.UNKNOWN;
    }
}
