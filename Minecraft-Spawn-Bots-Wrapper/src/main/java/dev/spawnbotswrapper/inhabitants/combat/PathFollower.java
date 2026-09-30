package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;

import java.util.List;

/**
 * Follows a planned route: tells the steering which waypoint to walk toward now and when the walk is done or stuck.
 * Pure. One instance per route; a new plan makes a new follower.
 * <p>
 * The waypoint index advances when the bot is within {@value #REACHED} blocks (horizontally, and at most
 * {@value #HEIGHT} blocks off vertically) of the current waypoint, or when it has already passed it (it is closer to the
 * next waypoint than the current waypoint is), so an overshoot never walks back. "Stuck" means neither the waypoint
 * index nor the distance to the current waypoint improved by {@value #PROGRESS} blocks for the given number of ticks.
 */
public final class PathFollower {
    /** Horizontal distance (blocks) at which a waypoint counts as reached. */
    public static final double REACHED = 0.8;
    /** Vertical distance (blocks) within which a waypoint can count as reached. */
    public static final double HEIGHT = 1.6;
    /** Blocks the distance to the current waypoint must shrink to count as progress. */
    public static final double PROGRESS = 0.3;

    private final List<Pos> waypoints;
    private int index;
    private double best = Double.MAX_VALUE;
    private long bestTick;

    public PathFollower(List<Pos> waypoints, long startTick) {
        this.waypoints = List.copyOf(waypoints);
        this.bestTick = startTick;
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    /** True once the bot has reached the last waypoint. */
    public boolean done() {
        return index >= waypoints.size();
    }

    /** The last waypoint (where the route ends), or null when there is none. */
    public Pos end() {
        return waypoints.isEmpty() ? null : waypoints.get(waypoints.size() - 1);
    }

    public int index() {
        return index;
    }

    public int size() {
        return waypoints.size();
    }

    /**
     * Advances past the waypoints the bot has reached or passed and returns the one to walk toward now, or null when the
     * route is done. Reaching a waypoint counts as progress for {@link #stuck}.
     */
    public Pos current(Pos bot, long now) {
        while (index < waypoints.size()) {
            Pos wp = waypoints.get(index);
            boolean near = Math.abs(bot.y() - wp.y()) <= HEIGHT;
            boolean reached = near && bot.horizontalTo(wp) <= REACHED;
            boolean passed = near && index + 1 < waypoints.size() && bot.horizontalTo(wp) <= 1.6
                    && bot.horizontalTo(waypoints.get(index + 1)) < wp.horizontalTo(waypoints.get(index + 1));
            if (!reached && !passed) {
                return wp;
            }
            index++;
            best = Double.MAX_VALUE;
            bestTick = now;
        }
        return null;
    }

    /**
     * Records this tick's progress and says whether the walk is stuck: no waypoint reached and the distance to the current
     * one no shorter than before by {@value #PROGRESS} blocks, for {@code stuckTicks} ticks.
     */
    public boolean stuck(Pos bot, long now, int stuckTicks) {
        if (index >= waypoints.size()) {
            return false;
        }
        Pos wp = waypoints.get(index);
        double d = bot.horizontalTo(wp) + Math.abs(bot.y() - wp.y()) * 0.5;
        if (d < best - PROGRESS) {
            best = d;
            bestTick = now;
            return false;
        }
        return now - bestTick >= stuckTicks;
    }

    /** Length of the rest of the route from the bot's position, in blocks (straight segments). */
    public double remaining(Pos bot) {
        if (index >= waypoints.size()) {
            return 0.0;
        }
        double total = bot.horizontalTo(waypoints.get(index));
        for (int i = index; i + 1 < waypoints.size(); i++) {
            total += waypoints.get(i).horizontalTo(waypoints.get(i + 1));
        }
        return total;
    }
}
