package dev.spawnbotswrapper.inhabitants.mc;

import java.util.function.IntSupplier;

/**
 * "Every N ticks" without a per-tick counter: remembers the tick of the last run and says when the interval
 * has elapsed. The interval is read on every call so a config reload changes it live, and a tick counter that
 * moved backwards (a new server session) restarts the cycle instead of waiting for the old value to be
 * reached again. The first call only records a starting point, so nothing runs on the very first tick.
 */
public final class TickSchedule {
    private final IntSupplier interval;
    private boolean started;
    private int last;

    public TickSchedule(IntSupplier interval) {
        this.interval = interval;
    }

    /** True when the interval has elapsed since the last time this returned true (then the cycle restarts). */
    public boolean due(int nowTick) {
        if (!started || nowTick < last) {
            started = true;
            last = nowTick;
            return false;
        }
        if (nowTick - last >= Math.max(1, interval.getAsInt())) {
            last = nowTick;
            return true;
        }
        return false;
    }
}
