package io.github.zoyluo.aibot.mining.assist;

/**
 * Pure decisions of the coordinator's detour maintenance (mining-assist design 2.3 step 3b and 3c). The
 * coordinator gathers the facts from live state and asks this class, so the rules are unit-testable and the
 * coordinator needs no {@code instanceof} (its source contract counts them).
 *
 * <p><b>Live derivation (3b).</b> A detour is live only if the bot's active task is the very instance that
 * published the tuple, that task is RUNNING, the published phase is not IDLE, and the tuple is at most
 * {@value #MAX_PUBLISH_AGE_TICKS} server ticks old. Nothing depends on {@code onAbort}, because
 * {@code fail()} and {@code complete()} end an OreDig without calling it. When a tuple exists and is not live
 * the coordinator runs the orphan cleanup ({@code OreClaims.releaseAll}, clear the tuple, log
 * {@code ore_dig_detour_orphan}) and never calls {@code stopAll()} (a SAFETY task may already own the action
 * pack).</p>
 *
 * <p><b>Net (3c).</b> While live and in a {@link DetourPhase#netAbortable() net-abortable} phase, degraded TPS
 * (or the headroom abort verdict) or a {@code hurtTime} that rose above the previous tick's value aborts the
 * detour. The hurt trigger is edge based: only a rise counts, a steady non-zero value does not.</p>
 */
public final class DetourLiveness {
    /** A published tuple older than this many server ticks is stale (OreDig ticks only every 5th tick while TPS is degraded). */
    public static final int MAX_PUBLISH_AGE_TICKS = 6;

    /** Reason strings the net passes to {@link DetourControl#abortNow}. */
    public static final String NET_DEGRADED_TPS = "degraded_tps";
    public static final String NET_HURT = "safety_hurt";

    /** Outcome of {@link #check}; every value except LIVE is an orphan {@code cause} (lower case in the log line). */
    public enum Verdict {
        LIVE,
        /** The bot's active task is not the publishing instance (it ended, was replaced, or nothing is active). */
        OWNER_CHANGED,
        /** The publishing task is active but not RUNNING (paused, or a terminal state that has not been removed yet). */
        NOT_RUNNING,
        /** The tuple says IDLE (or has no phase). The engine never publishes IDLE, so this is a broken publisher. */
        PHASE_IDLE,
        /** The tuple was published more than {@value #MAX_PUBLISH_AGE_TICKS} ticks ago, never, or in the future. */
        STALE
    }

    private DetourLiveness() {
    }

    /**
     * The live derivation. Checked in the order OWNER_CHANGED, NOT_RUNNING, PHASE_IDLE, STALE, else LIVE.
     *
     * @param ownerIsActiveTask the active task of the bot is the same instance as the published owner
     * @param running           that task's state is RUNNING
     * @param phase             the published phase (null counts as IDLE)
     * @param publishedTick     server tick of the last publish, or {@link MiningAssistState#NEVER}
     * @param nowTick           the current server tick. The tuple is STALE when {@code publishedTick == NEVER},
     *                          when {@code nowTick < publishedTick}, or when
     *                          {@code nowTick - publishedTick > MAX_PUBLISH_AGE_TICKS} (subtract as long)
     */
    public static Verdict check(boolean ownerIsActiveTask, boolean running, DetourPhase phase,
                                int publishedTick, int nowTick) {
        throw new UnsupportedOperationException("P1 stub: DetourLiveness.check");
    }

    /**
     * The net's decision, or null when it does not fire. Null when {@code phase} is null or not
     * {@link DetourPhase#netAbortable()}. Otherwise {@link #NET_DEGRADED_TPS} when {@code tpsDegraded} or
     * {@code headroomAbort}, else {@link #NET_HURT} when {@code hurtTime > hurtTimeSeen}, else null. TPS is
     * checked first, so both at once report {@code degraded_tps}.
     *
     * @param hurtTime     the bot's {@code hurtTime} this tick
     * @param hurtTimeSeen the value the coordinator recorded on its previous run for this bot
     */
    public static String netReason(DetourPhase phase, boolean tpsDegraded, boolean headroomAbort,
                                   int hurtTime, int hurtTimeSeen) {
        throw new UnsupportedOperationException("P1 stub: DetourLiveness.netReason");
    }
}
