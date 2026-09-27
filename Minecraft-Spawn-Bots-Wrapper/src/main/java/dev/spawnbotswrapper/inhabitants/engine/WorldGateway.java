package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;

/** What the engine needs from the Minecraft world. Server thread only; never blocks or loads chunks. */
public interface WorldGateway {

    /** The overworld seed; the basis of deterministic mode. */
    long worldSeed();

    /** Non-loading block view of the dimension, or null when that dimension is not loaded/known. */
    BlockProbe probe(String dimensionId);
}
