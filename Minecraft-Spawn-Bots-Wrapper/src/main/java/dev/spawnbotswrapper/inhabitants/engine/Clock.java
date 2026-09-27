package dev.spawnbotswrapper.inhabitants.engine;

/**
 * Time source. Game logic (delays, timeouts, retry intervals) is measured in SERVER TICKS so it pauses
 * with the server; wall-clock millis are only ever stored for information.
 */
public interface Clock {
    /** Monotonic server tick counter (advances only while the server ticks). */
    long tick();

    long nowMillis();
}
