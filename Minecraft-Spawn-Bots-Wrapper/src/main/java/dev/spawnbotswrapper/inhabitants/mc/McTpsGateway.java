package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.engine.TpsGateway;

/**
 * Measures real tick duration directly -- the wall-clock time between successive END_SERVER_TICK firings --
 * rather than reading vanilla's own internal tick-time buffer, so it needs no version-specific API and matches
 * exactly what an operator sees with {@code /tick query}. {@link #recordTick()} is called once per server tick
 * by the mod entrypoint, as early as possible in the tick; {@link #averageTickMillis()} is read by the engine.
 * Thread-unsafe by design (server thread only), like every other gateway in this addon.
 */
public final class McTpsGateway implements TpsGateway {
    /** Same sample count vanilla's own {@code /tick query} reports over, for numbers an operator already trusts. */
    private static final int WINDOW = 100;

    private final long[] samplesNanos = new long[WINDOW];
    private int count;
    private int cursor;
    private long sum;
    private long lastTickNanos = -1;

    /** Call once per server tick, before anything else that might itself take meaningful time. */
    public void recordTick() {
        long now = System.nanoTime();
        if (lastTickNanos >= 0) {
            long delta = now - lastTickNanos;
            if (count < WINDOW) {
                samplesNanos[cursor] = delta;
                sum += delta;
                count++;
            } else {
                sum -= samplesNanos[cursor];
                samplesNanos[cursor] = delta;
                sum += delta;
            }
            cursor = (cursor + 1) % WINDOW;
        }
        lastTickNanos = now;
    }

    @Override
    public double averageTickMillis() {
        if (count < WINDOW) {
            return -1; // not enough samples yet for a stable reading
        }
        return (sum / (double) count) / 1_000_000.0;
    }
}
