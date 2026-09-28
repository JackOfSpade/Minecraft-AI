package dev.spawnbotswrapper.inhabitants.engine;

/**
 * Server tick health, measured independently of game-tick pacing (see {@link Clock}). Server thread only.
 */
public interface TpsGateway {
    /**
     * Rolling average milliseconds per tick over a recent window, or a negative number before enough samples
     * exist (e.g. just after server start). 50ms/tick is the vanilla target of 20 TPS.
     */
    double averageTickMillis();
}
