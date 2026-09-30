package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;

import java.util.List;

/**
 * Plans a walkable route for an inhabitant, so the aggro state machine can walk to a last known position, a search point
 * or home around walls instead of straight into them. PvP BOT itself only steers in a straight line (jump, simple
 * avoidance); this is the pathfinding it lacks.
 * <p>
 * The interface is the seam for planners: the standalone one shipped with the wrapper (vanilla pathfinding through a
 * detached helper mob, {@code mc.VanillaPathPlanner}) and, later, one backed by Minecraft-AI's Baritone when that mod is
 * loaded. A planner NEVER breaks or places blocks and NEVER teleports anything: it only answers "which cells, in which
 * order". It is bounded: one call costs at most a fixed node budget, and the caller limits how often it asks.
 * Server thread only.
 */
public interface PathPlanner {

    /** How a plan turned out. */
    enum Outcome {
        /** The route ends at the goal. */
        REACHES,
        /** No complete route exists (or the budget ran out); the route leads to the closest point found. */
        PARTIAL,
        /** Nothing walkable was found at all. */
        NONE,
        /** The planner cannot work right now (unavailable, failed). */
        UNAVAILABLE
    }

    /**
     * A planned route.
     *
     * @param waypoints stand positions in walking order (the first one is not the start); empty when there is no route
     * @param outcome   how the plan turned out
     */
    record Plan(List<Pos> waypoints, Outcome outcome) {
        public Plan {
            waypoints = List.copyOf(waypoints);
        }

        /** A plan without a route. */
        public static Plan none(Outcome why) {
            return new Plan(List.of(), why);
        }

        public boolean hasRoute() {
            return !waypoints.isEmpty();
        }

        public boolean reachesGoal() {
            return outcome == Outcome.REACHES;
        }
    }

    /**
     * Plans a route for {@code bot} to {@code goal}.
     *
     * @param bot      the inhabitant, opaque ({@link AggroWorld.Watcher#handle()})
     * @param goal     where to go
     * @param maxRange the longest route worth planning, in blocks (the caller passes the mod maximum)
     */
    Plan plan(Object bot, Pos goal, double maxRange);

    /** A planner that never plans; what callers get when nothing can (the state machine then steers straight). */
    PathPlanner NONE = (bot, goal, maxRange) -> Plan.none(Outcome.UNAVAILABLE);
}
