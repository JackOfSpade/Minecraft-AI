package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;

/**
 * The no-progress rule of a Baritone land-follow route. Moving sideways can re-arm while it extends the route's spatial footprint;
 * otherwise it must get closer to the player (by at least {@link #CLOSER_BY} blocks) or it is stuck after {@link #WINDOW_TICKS}
 * ticks. This abandons a door it cannot open, a ledge it cannot leave, or a replan loop with a back-off,
 * so the follower neither spins in place for the whole route deadline nor restarts the same route at once.
 *
 * <p>This is the Baritone-route counterpart of {@code FollowStuckRecovery}, which watches a local action's real position. A
 * real detour may temporarily move away from the player. The best distance and spatial footprint survive a movement re-arm,
 * so only a real approach fully resets the episode. Pure (numbers only).</p>
 */
final class FollowProgressWindow {
    /** Ticks without progress after which the route is abandoned (five seconds). */
    static final int WINDOW_TICKS = 100;
    /** Blocks closer to the player than ever before in the window that count as progress. */
    static final double CLOSER_BY = 1.0D;
    /** Blocks from the anchor position that count as progress. */
    static final double MOVED = 3.0D;

    private boolean armed;
    private double bestDistance;
    private double anchorX;
    private double anchorZ;
    private int sinceTick;
    private double episodeOriginX;
    private double episodeOriginZ;
    private double maxEpisodeExtent;
    /** The player position for a directional-pursuit episode, if one is active. */
    private BlockPos pursuitTarget;

    /**
     * Starts a route episode. Consecutive short directional legs toward the same player position
     * keep their no-progress clock: completing a one-cell leg is not progress by itself. A direct
     * route or a target change starts a fresh episode.
     */
    void beginRoute(BlockPos target, boolean directional) {
        if (!directional || pursuitTarget == null || !pursuitTarget.equals(target)) {
            clear();
        }
        pursuitTarget = directional ? target.immutable() : null;
    }

    /** A route stops or is replaced: the window is re-armed by the next {@link #stalled} call. */
    void clear() {
        armed = false;
        maxEpisodeExtent = 0.0D;
        pursuitTarget = null;
    }

    /**
     * Feeds one tick of a running route.
     *
     * @param distance the bot's distance to the player now
     * @return true when the route has made no progress for the whole window
     */
    boolean stalled(int now, double distance, double x, double z) {
        if (!armed || distance <= bestDistance - CLOSER_BY) {
            arm(now, distance, x, z, true);
            return false;
        }
        double dx = x - anchorX;
        double dz = z - anchorZ;
        double episodeDx = x - episodeOriginX;
        double episodeDz = z - episodeOriginZ;
        double extent = Math.sqrt(episodeDx * episodeDx + episodeDz * episodeDz);
        if (dx * dx + dz * dz >= MOVED * MOVED && extent >= maxEpisodeExtent + MOVED) {
            // Moving is progress (a detour around a wall gets no closer for a while), but it does not forget how close the bot has
            // been: only a real approach (CLOSER_BY nearer than the best distance so far) resets the episode. This lets a real
            // detour develop, while a small ring of immediately completed routes cannot keep re-arming forever.
            maxEpisodeExtent = extent;
            arm(now, Math.min(bestDistance, distance), x, z, false);
            return false;
        }
        return now - sinceTick >= WINDOW_TICKS;
    }

    private void arm(int now, double distance, double x, double z, boolean resetEpisode) {
        armed = true;
        if (resetEpisode) {
            episodeOriginX = x;
            episodeOriginZ = z;
            maxEpisodeExtent = 0.0D;
        }
        bestDistance = distance;
        anchorX = x;
        anchorZ = z;
        sinceTick = now;
    }
}
