package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;

import java.util.List;

/**
 * Everything needed to build one PvP BOT path for one bot, already validated. A plan that could crash
 * PvP BOT cannot be constructed: a ping-pong loop with fewer than two points makes the upstream tick read
 * index -1 (trap P1), so the invariant is enforced here, at the last possible moment, whatever the caller did.
 *
 * @param pathName upstream path name, see {@link PatrolPlanner#pathName}
 * @param points   waypoints in walking order; never empty
 * @param loop     upstream "loop" flag: TRUE means walk back and forth (ping-pong), FALSE means restart (ring)
 * @param attack   upstream "attack" flag: FALSE makes the follower a pacifist
 * @param walkType one of bhop, sprint, walk
 * @param notes    what the planner adjusted (diagnostics only)
 */
record PatrolPlan(String pathName, List<BotProfile.Waypoint> points, boolean loop, boolean attack,
                  String walkType, List<String> notes) {

    PatrolPlan {
        if (pathName == null || pathName.isEmpty()) {
            throw new IllegalArgumentException("a path needs a name");
        }
        points = List.copyOf(points);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("a path needs at least one point");
        }
        if (loop && points.size() < 2) {
            throw new IllegalArgumentException(
                    "a single-point ping-pong loop crashes PvP BOT's tick (path trap P1); use loop=false");
        }
        notes = List.copyOf(notes);
    }
}
