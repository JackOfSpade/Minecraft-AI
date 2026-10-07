package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.MiningController;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;

/**
 * Working memory (layer 1 of the three-layer memory model): what has been tried / where has
 * been walked within a single goal (episode).
 *
 * Consolidates three previously scattered patches of the same kind — the prospect blacklist
 * (static+TTL), ore_dig ignored (+one-time pardon), and roam's repeated point selection
 * (observed in testing: n=3,4,5 consecutively picking the same unreachable point) — which were
 * all, at root, the absence of working memory, each hand-written separately, causing the same
 * bug to be fixed three times.
 *
 * Lifecycle is tied to the goal: GoalExecutor calls reset() when building the plan for a
 * **new** goal (a replan of the same goal does **not** reset it — the blacklist must survive
 * across replans, otherwise rebuilding the task instance would pick the same unreachable
 * target again and loop forever, which is exactly why PROSPECT_BLACKLIST was originally made
 * static); clear()/reset also clears it.
 *
 * Exclusions carry a TTL: "unreachable/dug out" is a short-lived fact, and once it expires the
 * target automatically revives for a retry (terrain/reachability may have changed).
 * The trail is used to avoid repetition while roaming: point selection avoids areas walked
 * recently, instead of circling blindly.
 */
public final class EpisodeMemory {
    public static final EpisodeMemory INSTANCE = new EpisodeMemory();

    /** Default exclusion duration for an unreachable/unminable target point (60s, same tier as the original prospect blacklist). */
    public static final int TTL_UNREACHABLE = 1200;
    /** Short exclusion: for cases like a mining approach failure (30s, a more nuanced version of the original ore_dig pardon — it naturally revives on TTL expiry instead of a one-time blanket pardon). */
    public static final int TTL_SHORT = 600;
    private static final int TRAIL_MAX = 32;       // Trail sample cap (roughly the last 32 footholds)
    private static final double TRAIL_SPACING = 4.0D; // Minimum spacing between adjacent sample points (debounce)
    private static final int EXCLUDE_CAP = 128;    // Exclusion table cap (prevents unbounded growth; when full, clears the oldest half)

    private final Map<UUID, BotEpisode> episodes = new ConcurrentHashMap<>();
    /** Moves whenever exclusions were dropped before their time ran out; see {@link #earlyRevivals()}. */
    private final AtomicLong earlyRevivals = new AtomicLong();

    private EpisodeMemory() {
    }

    private static final class BotEpisode {
        final Map<BlockPos, Integer> excludedUntil = new HashMap<>();
        final Map<String, Deque<BlockPos>> trails = new HashMap<>();
    }

    private BotEpisode of(UUID botId) {
        return episodes.computeIfAbsent(botId, k -> new BotEpisode());
    }

    /** New goal starts / external reset: this episode's working memory is invalidated (both exclusions and trails are only meaningful for "this particular task"). */
    public void reset(UUID botId) {
        episodes.remove(botId);
        earlyRevivals.incrementAndGet();
    }

    public void clearAll() {
        episodes.clear();
        earlyRevivals.incrementAndGet();
    }

    /**
     * A counter that moves whenever exclusions disappeared before their recorded end: the table was full and shed
     * its older half, or an episode was reset. A caller that derived "this target stays excluded until tick T" from
     * {@link #excludedUntil} keeps that conclusion only while the counter has not moved since.
     */
    public long earlyRevivals() {
        return earlyRevivals.get();
    }

    /**
     * How long a target the miner refused stays excluded, by the typed refusal. A block seen only through blocks the bot may not
     * break (a pane, a fence, a cobweb) is refused from every stance that looks at it through them, so it stays out as long as an
     * unreachable one ({@link #TTL_UNREACHABLE}). Anything else (the block left the bot's sight, the break could not go on) is a
     * state of the moment: another stance may show it, or a line to it, again ({@link #TTL_SHORT}).
     */
    public static int ttlAfterMiningRefusal(String reason) {
        return MiningController.TARGET_OBSTRUCTED.equals(reason) ? TTL_UNREACHABLE : TTL_SHORT;
    }

    /**
     * How long a target stays excluded after a route to it was refused, by the reason. A refusal for want of sight is a state of
     * the moment (the bot had not seen the way from where it stood, and another stance may show it): {@link #TTL_SHORT}. A route
     * that Baritone found no way along, or that ended on its own, is a verdict on the target: {@link #TTL_UNREACHABLE}.
     */
    public static int ttlAfterRouteRefusal(String reason) {
        return NavRouteRules.isObservationRefusal(reason) ? TTL_SHORT : TTL_UNREACHABLE;
    }

    /** Excludes a target point (unreachable/dug out/tried with no result); it automatically revives after the TTL. */
    public void exclude(UUID botId, BlockPos pos, int nowTick, int ttlTicks) {
        BotEpisode ep = of(botId);
        if (ep.excludedUntil.size() >= EXCLUDE_CAP) {
            // Prevents unbounded growth: clears the earliest half by expiry time (simple and effective, no extra data structure needed)
            int median = ep.excludedUntil.values().stream().sorted()
                    .skip(ep.excludedUntil.size() / 2).findFirst().orElse(nowTick);
            ep.excludedUntil.values().removeIf(until -> until <= median);
            earlyRevivals.incrementAndGet();
        }
        ep.excludedUntil.put(pos.immutable(), nowTick + ttlTicks);
    }

    public boolean isExcluded(UUID botId, BlockPos pos, int nowTick) {
        return excludedUntil(botId, pos, nowTick) >= 0;
    }

    /** The last tick on which {@code pos} is still excluded (it revives on the next one), or -1 when it is not excluded. */
    public int excludedUntil(UUID botId, BlockPos pos, int nowTick) {
        BotEpisode ep = episodes.get(botId);
        if (ep == null) {
            return -1;
        }
        Integer until = ep.excludedUntil.get(pos);
        if (until == null) {
            return -1;
        }
        if (until < nowTick) {
            ep.excludedUntil.remove(pos);
            return -1;
        }
        return until;
    }

    public int excludedCount(UUID botId) {
        BotEpisode ep = episodes.get(botId);
        return ep == null ? 0 : ep.excludedUntil.size();
    }

    /** Records a trail sample (auto-debounced: skipped if the distance to the previous sample point is less than TRAIL_SPACING). */
    public void recordTrail(UUID botId, BlockPos pos) {
        recordTrail(botId, "default", pos);
    }

    /** Records a purpose-scoped search trail so gathering does not poison later hunting frontiers. */
    public void recordTrail(UUID botId, String purpose, BlockPos pos) {
        BotEpisode ep = of(botId);
        Deque<BlockPos> trail = ep.trails.computeIfAbsent(normalizePurpose(purpose), ignored -> new ArrayDeque<>());
        BlockPos last = trail.peekLast();
        if (last != null && last.distSqr(pos) < TRAIL_SPACING * TRAIL_SPACING) {
            return;
        }
        trail.addLast(pos.immutable());
        while (trail.size() > TRAIL_MAX) {
            trail.pollFirst();
        }
    }

    /** Whether pos falls within radius of the recent trail — roam point selection avoids areas just searched (instead of circling blindly). */
    public boolean nearTrail(UUID botId, BlockPos pos, double radius) {
        return nearTrail(botId, "default", pos, radius);
    }

    /** Horizontal search-area membership for a purpose-scoped trail. Elevation changes are the same region. */
    public boolean nearTrail(UUID botId, String purpose, BlockPos pos, double radius) {
        BotEpisode ep = episodes.get(botId);
        if (ep == null) {
            return false;
        }
        Deque<BlockPos> trail = ep.trails.get(normalizePurpose(purpose));
        if (trail == null) {
            return false;
        }
        double r2 = radius * radius;
        for (BlockPos p : trail) {
            long dx = (long) p.getX() - pos.getX();
            long dz = (long) p.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePurpose(String purpose) {
        return purpose == null || purpose.isBlank() ? "default" : purpose;
    }
}
