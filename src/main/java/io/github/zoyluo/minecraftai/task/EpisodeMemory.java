package io.github.zoyluo.minecraftai.task;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    }

    public void clearAll() {
        episodes.clear();
    }

    /** Excludes a target point (unreachable/dug out/tried with no result); it automatically revives after the TTL. */
    public void exclude(UUID botId, BlockPos pos, int nowTick, int ttlTicks) {
        BotEpisode ep = of(botId);
        if (ep.excludedUntil.size() >= EXCLUDE_CAP) {
            // Prevents unbounded growth: clears the earliest half by expiry time (simple and effective, no extra data structure needed)
            int median = ep.excludedUntil.values().stream().sorted()
                    .skip(ep.excludedUntil.size() / 2).findFirst().orElse(nowTick);
            ep.excludedUntil.values().removeIf(until -> until <= median);
        }
        ep.excludedUntil.put(pos.toImmutable(), nowTick + ttlTicks);
    }

    public boolean isExcluded(UUID botId, BlockPos pos, int nowTick) {
        BotEpisode ep = episodes.get(botId);
        if (ep == null) {
            return false;
        }
        Integer until = ep.excludedUntil.get(pos);
        if (until == null) {
            return false;
        }
        if (until < nowTick) {
            ep.excludedUntil.remove(pos);
            return false;
        }
        return true;
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
        if (last != null && last.getSquaredDistance(pos) < TRAIL_SPACING * TRAIL_SPACING) {
            return;
        }
        trail.addLast(pos.toImmutable());
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
