package io.github.zoyluo.minecraftai.task;

/**
 * The no-progress rule of a Baritone land-follow route: a route that gets the bot neither closer to the player (by at least
 * {@link #CLOSER_BY} blocks) nor anywhere else (at least {@link #MOVED} blocks from where the last progress was made) for
 * {@link #WINDOW_TICKS} ticks is stuck (a door it cannot open, a ledge it cannot leave, a replan loop) and is abandoned with a
 * back-off, so the follower neither spins in place for the whole route deadline nor restarts the same route at once.
 *
 * <p>This is the Baritone-route counterpart of {@code FollowStuckRecovery}, which watches the legacy executor's real position; a
 * bot that moves (a long detour around a wall) is making progress even while it gets no closer. Pure (numbers only).</p>
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

    /** A new route starts (or none runs): the window is re-armed by the next {@link #stalled} call. */
    void clear() {
        armed = false;
    }

    /**
     * Feeds one tick of a running route.
     *
     * @param distance the bot's distance to the player now
     * @return true when the route has made no progress for the whole window
     */
    boolean stalled(int now, double distance, double x, double z) {
        if (!armed || distance <= bestDistance - CLOSER_BY) {
            arm(now, distance, x, z);
            return false;
        }
        double dx = x - anchorX;
        double dz = z - anchorZ;
        if (dx * dx + dz * dz >= MOVED * MOVED) {
            arm(now, distance, x, z);
            return false;
        }
        return now - sinceTick >= WINDOW_TICKS;
    }

    private void arm(int now, double distance, double x, double z) {
        armed = true;
        bestDistance = distance;
        anchorX = x;
        anchorZ = z;
        sinceTick = now;
    }
}
