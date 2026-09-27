package dev.spawnbotswrapper.inhabitants.engine;

/** Manually advanced clock: one tick is 50 ms of "wall time". */
final class FakeClock implements Clock {
    private static final long EPOCH_MILLIS = 1_700_000_000_000L;

    long tick;

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public long nowMillis() {
        return EPOCH_MILLIS + tick * 50;
    }
}
