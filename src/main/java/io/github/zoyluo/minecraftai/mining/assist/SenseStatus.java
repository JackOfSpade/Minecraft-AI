package io.github.zoyluo.aibot.mining.assist;

/**
 * Per-bot record of "is this bot currently being sensed", used by the coordinator for two things: the
 * {@code assist_sense_enabled} / {@code assist_sense_disabled} log lines and the idle release of a bot's
 * observation memory. Pure and clock-free: time is the server tick the caller passes in.
 *
 * <p>Short interruptions do not log. A sensing session only ends after the bot has gone
 * {@value #DISABLE_GRACE_TICKS} ticks without a sensing tick, so a bot that briefly switches to a crafting
 * task or pops out into daylight and back produces no lines. A bot that has not sensed for
 * {@value #IDLE_RELEASE_TICKS} ticks is considered finished with mining and the coordinator drops its
 * state (lava memory never ages by time, so mission end is the only time-based exit, invariant I15).</p>
 */
public final class SenseStatus {
    /** Sentinel for "never sensed". */
    public static final int NEVER = Integer.MIN_VALUE;
    /** Ticks without sensing before a sensing session counts as ended (2 seconds). */
    public static final int DISABLE_GRACE_TICKS = 40;
    /** Ticks without sensing after which the bot's state is released (2 minutes). */
    public static final int IDLE_RELEASE_TICKS = 2400;

    /** What a call changed. */
    public enum Change {
        NONE,
        ENABLED,
        DISABLED
    }

    private boolean sensing;
    private int lastSensedTick = NEVER;

    /** The sensor ran this tick. Returns {@link Change#ENABLED} when this starts a sensing session. */
    public Change sensed(int tick) {
        boolean started = !sensing;
        sensing = true;
        lastSensedTick = tick;
        return started ? Change.ENABLED : Change.NONE;
    }

    /**
     * The sensor did not run this tick (and no watcher took over). Returns {@link Change#DISABLED} exactly
     * once, when a sensing session has been quiet for {@value #DISABLE_GRACE_TICKS} ticks.
     */
    public Change notSensed(int tick) {
        if (!sensing) {
            return Change.NONE;
        }
        if (tick < lastSensedTick) {
            // The tick counter went backwards (a new server session reusing the state): resynchronise.
            lastSensedTick = tick;
            return Change.NONE;
        }
        if (tick - lastSensedTick >= DISABLE_GRACE_TICKS) {
            sensing = false;
            return Change.DISABLED;
        }
        return Change.NONE;
    }

    public boolean sensing() {
        return sensing;
    }

    /** Tick of the last sensing tick, or {@link #NEVER}. */
    public int lastSensedTick() {
        return lastSensedTick;
    }

    /**
     * True when the bot is not sensing and its last sensing tick is at least {@code ticks} ago. Never true
     * for a bot that has not sensed at all: such a state holds nothing worth releasing.
     */
    public boolean idleFor(int tick, int ticks) {
        return !sensing && lastSensedTick != NEVER && tick >= lastSensedTick && tick - lastSensedTick >= ticks;
    }
}
