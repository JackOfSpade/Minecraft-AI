package io.github.zoyluo.minecraftai.entity;

/**
 * Coalesces repeated damage of the same kind into one log line with a count.
 *
 * <p>A burning, drowning or lava-standing bot is damaged every 10-20 ticks by the same source, and
 * the combat log used to write one {@code damage_taken} line for every call. A run is the same
 * {@code key} (source id + attacker) hit again within {@link #WINDOW_TICKS} of the previous hit, and
 * at most {@link #MAX_RUN_TICKS} long so a long burn still reports progress. The first event of a run
 * is logged immediately by the caller; the events that follow are only counted and reported as one
 * summary when the run ends (a different key, an expired window, death, removal or an idle flush).
 *
 * <p>Pure (no Minecraft types) so it is unit-testable; not thread-safe (server thread only).
 */
public final class DamageLogCoalescer {
    /** Same source/attacker again within this many ticks (2 s) belongs to the current run. */
    public static final int WINDOW_TICKS = 40;
    /** A run never spans more than this many ticks (5 s) before it is summarised. */
    public static final int MAX_RUN_TICKS = 100;

    /** What the caller must do for one recorded event. */
    public record Result(boolean logNow, Summary flushed) {
    }

    /** One finished run with at least one coalesced repeat. */
    public record Summary(String key, int repeats, float totalAmount, int applied,
                          float hpFrom, float hpTo, int firstTick, int lastTick) {
    }

    private String key;
    private int startTick;
    private int lastTick;
    private int repeats;
    private float total;
    private int applied;
    private float hpFrom;
    private float hpTo;

    /**
     * Records one damage event. When {@code logNow} is true the caller logs this event as usual (it
     * starts a new run); otherwise the event was folded into the current run. {@code flushed} is the
     * summary of the run this event closed, or null.
     */
    public Result record(String eventKey, int tick, float amount, boolean wasApplied,
                         float hpBefore, float hpAfter) {
        if (key != null && key.equals(eventKey)
                && tick >= lastTick && tick - lastTick <= WINDOW_TICKS
                && tick - startTick <= MAX_RUN_TICKS) {
            repeats++;
            total += amount;
            if (wasApplied) {
                applied++;
            }
            hpTo = hpAfter;
            lastTick = tick;
            return new Result(false, null);
        }
        Summary flushed = flush();
        key = eventKey;
        startTick = tick;
        lastTick = tick;
        repeats = 0;
        total = 0.0F;
        applied = 0;
        hpFrom = hpBefore;
        hpTo = hpAfter;
        return new Result(true, flushed);
    }

    /** Closes the current run (death, removal, idle) and returns its summary if it coalesced anything. */
    public Summary flush() {
        Summary summary = key != null && repeats > 0
                ? new Summary(key, repeats, total, applied, hpFrom, hpTo, startTick, lastTick)
                : null;
        key = null;
        repeats = 0;
        return summary;
    }

    /** Flushes the current run when its window has expired without another hit. */
    public Summary flushIfIdle(int tick) {
        if (key != null && (tick - lastTick > WINDOW_TICKS || tick < lastTick)) {
            return flush();
        }
        return null;
    }
}
