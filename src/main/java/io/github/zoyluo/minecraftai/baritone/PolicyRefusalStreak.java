package io.github.zoyluo.minecraftai.baritone;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consecutive policy refusals of a bot's Baritone route: how often the strict-survival rules ({@link BaritoneBreakPlacePolicy})
 * vetoed a break or a placement since the route started or since the last break/placement that went through.
 *
 * <p>Why: the position-level rules (observability, the bot's own break permission) can only be enforced at execution, because a
 * search runs on a worker thread against a snapshot. A route through blocks the bot may not touch is therefore vetoed at the first
 * break, and Baritone (which knows nothing of the veto) may re-plan the very same route and be vetoed again, for ever. The
 * navigator ends a route whose streak reaches {@link #CAP} as {@code FAILED policy_refused}, so the caller can choose another
 * way. Any allowed break or placement, and the start of a route, ends a streak.</p>
 *
 * <p>Pure bookkeeping (UUID and ints), so it is unit-testable without a server.</p>
 */
public final class PolicyRefusalStreak {
    /**
     * Refusals in a row that end a route. Baritone re-issues a refused click every tick or two, and a refusal that is only
     * transient (the bot is still turning towards the block) resolves within a handful of ticks, so this is about two seconds of
     * uninterrupted vetoes.
     */
    public static final int CAP = 40;

    private static final Map<UUID, AtomicInteger> STREAKS = new ConcurrentHashMap<>();

    private PolicyRefusalStreak() {
    }

    /** A break or placement of the bot was refused. */
    static void refused(UUID botId) {
        STREAKS.computeIfAbsent(botId, id -> new AtomicInteger()).incrementAndGet();
    }

    /** A break or placement went through, or a new route starts: the streak is over. */
    static void reset(UUID botId) {
        STREAKS.remove(botId);
    }

    /** The bot's current streak. */
    public static int of(UUID botId) {
        AtomicInteger streak = STREAKS.get(botId);
        return streak == null ? 0 : streak.get();
    }

    /** Whether the bot's streak has reached the cap. */
    public static boolean capReached(UUID botId) {
        return of(botId) >= CAP;
    }

    /** Test hook. */
    public static void clearAll() {
        STREAKS.clear();
    }
}
