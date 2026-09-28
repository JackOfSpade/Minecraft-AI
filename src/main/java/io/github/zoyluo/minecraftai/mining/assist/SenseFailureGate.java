package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failure bookkeeping of the coordinator's exception fence (mining-assist design 2.3: the coordinator
 * never throws out of the tick). A failure of one bot's sensing pass is logged at most once per
 * {@value #LOG_INTERVAL_TICKS} ticks for that bot, and that bot's sensing pauses for
 * {@value #COOLDOWN_TICKS} ticks so a persistent fault costs one failing pass per cooldown instead of one
 * per tick. This is a pause, not a disable: it ends by itself (design 3.4: no permanent per-bot disable).
 * Pure, clock-free: time is the server tick the caller passes in.
 */
public final class SenseFailureGate {
    /** Ticks between two logged failures of the same bot (one minute). */
    public static final int LOG_INTERVAL_TICKS = 1200;
    /** Ticks a bot's sensing stays paused after a failure (five seconds). */
    public static final int COOLDOWN_TICKS = 100;

    private static final class Entry {
        int lastFailTick;
        int lastLogTick;
        boolean quietNextEnable;
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    /** Records a failure at {@code tick}. Returns true when a log line is due for this failure. */
    public boolean recordFailure(UUID botId, int tick) {
        Entry entry = entries.get(botId);
        if (entry == null) {
            entry = new Entry();
            entry.lastFailTick = tick;
            entry.lastLogTick = tick;
            entry.quietNextEnable = true;
            entries.put(botId, entry);
            return true;
        }
        entry.lastFailTick = tick;
        entry.quietNextEnable = true;
        boolean due = tick < entry.lastLogTick || tick - entry.lastLogTick >= LOG_INTERVAL_TICKS;
        if (due) {
            entry.lastLogTick = tick;
        }
        return due;
    }

    /**
     * True exactly once after a failure: the next "sensing started" line of the bot is the recovery of a failed
     * pass (its state was dropped and rebuilt), not a new session, so the caller withholds it. The failure line
     * already told the story, and a persistent fault would otherwise add one such line per cooldown.
     */
    public boolean takeReenableSuppression(UUID botId) {
        Entry entry = entries.get(botId);
        if (entry == null || !entry.quietNextEnable) {
            return false;
        }
        entry.quietNextEnable = false;
        return true;
    }

    /** True while the bot is inside the cooldown that follows its last failure. */
    public boolean coolingDown(UUID botId, int tick) {
        Entry entry = entries.get(botId);
        return entry != null && tick >= entry.lastFailTick && tick - entry.lastFailTick < COOLDOWN_TICKS;
    }

    public void clear(UUID botId) {
        entries.remove(botId);
    }

    public void clearAll() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
