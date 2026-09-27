package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.engine.SpawnPlanner;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the waypoints of a patrol that PvP BOT can really walk.
 * <p>
 * PvP BOT follows a path by steering straight at the next waypoint and calls it reached only within 1.5
 * blocks IN 3D, so every waypoint must be an exact standing position and every consecutive pair must be
 * joined by a straight line that {@link StraightWalk} accepts. Picking random points in a disc would almost
 * never satisfy that inside a building, so candidates come from a flood fill over walkable columns around
 * home (bounded by the radius, the structure's soft limit and a column cap); the straight-line test is
 * still what decides, the flood fill only says where it is worth trying. The route is then grown one
 * waypoint at a time as a random walk that keeps only walkable segments and gives up after a bounded number
 * of attempts.
 * <p>
 * EVERY segment must be walkable in both directions, for both stances. A ping-pong walks each segment
 * forward and then back, and a two-block drop is walkable down but not up. A ring only walks forward, but a
 * bot that was pulled off its path by a fight walks back to the waypoint it was heading for from wherever
 * the fight ended, and a ring grown through one-way segments can dead-end behind one and then have to be
 * thrown away entirely. Requiring two-way segments keeps a ring of at least two waypoints available
 * whenever any two-way neighbour exists. A ring additionally needs its last waypoint to lead straight back
 * to the first (forward is enough there); if it cannot, the tail is dropped until it can.
 * <p>
 * Waypoints are kept at least {@value #MIN_WAYPOINT_SPACING} blocks apart, comfortably outside the 1.5-block
 * arrival radius, so the bot never "arrives" at a waypoint it has not left.
 */
final class PatrolPlanner {
    static final int MIN_WAYPOINTS = 2;
    static final int MAX_WAYPOINTS = 6;
    static final double MIN_WAYPOINT_SPACING = 2.0;
    static final double MAX_RADIUS = 128.0;
    /** The structure bounds grow by this much horizontally and vertically; waypoints stay inside. */
    static final int SOFT_LIMIT_MARGIN = 16;
    static final int MAX_FLOOD_COLUMNS = 2500;
    static final int ATTEMPTS_PER_WAYPOINT = 24;

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final StraightWalk walk;

    PatrolPlanner(ProbeView view) {
        this.walk = new StraightWalk(view);
    }

    /** A planned standing position: exact x/z, and the integer feet level. */
    private record Stop(double x, int y, double z) {
    }

    /** A reachable column found by the flood fill, with the feet level it was reached at. */
    private record Node(int x, int y, int z) {
    }

    static IntBox softLimit(IntBox bounds) {
        int m = SOFT_LIMIT_MARGIN;
        return new IntBox(bounds.minX() - m, bounds.minY() - m, bounds.minZ() - m,
                bounds.maxX() + m, bounds.maxY() + m, bounds.maxZ() + m);
    }

    static double horizontalDistance(double ax, double az, double bx, double bz) {
        return Math.hypot(ax - bx, az - bz);
    }

    /**
     * @param home      the first waypoint, exactly as given
     * @param homeLevel the feet level of {@code home}; the caller verified it is a valid dry standing position
     * @param radius    maximum horizontal distance of any waypoint from home
     * @param wanted    number of waypoints to aim for, {@link #MIN_WAYPOINTS}..{@link #MAX_WAYPOINTS}
     * @param limit     box every waypoint must lie in
     * @return the waypoints, starting with home; a single element means no patrol could be built
     */
    List<BotProfile.Waypoint> plan(SpawnPlanner.Position home, int homeLevel, boolean cycle, double radius,
                                   int wanted, IntBox limit, SplitMix64 rng) {
        Stop first = new Stop(home.x(), homeLevel, home.z());
        List<Stop> stops = new ArrayList<>();
        stops.add(first);

        List<Node> reachable = flood(first, radius, limit);
        int[] order = new int[reachable.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }

        while (stops.size() < wanted) {
            Stop from = stops.get(stops.size() - 1);
            boolean finalStop = stops.size() == wanted - 1;
            Stop next = null;
            int attempts = Math.min(ATTEMPTS_PER_WAYPOINT, order.length);
            for (int i = 0; i < attempts && next == null; i++) {
                int j = i + rng.nextInt(order.length - i);
                int swap = order[i];
                order[i] = order[j];
                order[j] = swap;
                next = tryStop(from, first, reachable.get(order[i]), stops, cycle, finalStop, limit);
            }
            if (next == null) {
                break;
            }
            stops.add(next);
        }

        if (cycle) {
            while (stops.size() >= MIN_WAYPOINTS && !closes(stops)) {
                stops.remove(stops.size() - 1);
            }
        }

        List<BotProfile.Waypoint> out = new ArrayList<>(stops.size());
        out.add(new BotProfile.Waypoint(home.x(), home.y(), home.z()));
        for (int i = 1; i < stops.size(); i++) {
            Stop s = stops.get(i);
            out.add(new BotProfile.Waypoint(s.x(), s.y(), s.z()));
        }
        return out;
    }

    private Stop tryStop(Stop from, Stop first, Node candidate, List<Stop> stops, boolean cycle,
                         boolean finalStop, IntBox limit) {
        double cx = candidate.x() + 0.5;
        double cz = candidate.z() + 0.5;
        if (tooClose(stops, cx, candidate.y(), cz)) {
            return null;
        }
        int arrived = walk.walk(from.x(), from.z(), from.y(), cx, cz);
        if (arrived == StraightWalk.NOT_WALKABLE || arrived < limit.minY() || arrived > limit.maxY()
                || tooClose(stops, cx, arrived, cz)) {
            return null;
        }
        if (walk.walk(cx, cz, arrived, from.x(), from.z()) != from.y()) {
            return null;
        }
        if (cycle && finalStop && walk.walk(cx, cz, arrived, first.x(), first.z()) != first.y()) {
            return null;
        }
        return new Stop(cx, arrived, cz);
    }

    private boolean closes(List<Stop> stops) {
        Stop last = stops.get(stops.size() - 1);
        Stop first = stops.get(0);
        return walk.walk(last.x(), last.z(), last.y(), first.x(), first.z()) == first.y();
    }

    private static boolean tooClose(List<Stop> stops, double x, int y, double z) {
        for (Stop s : stops) {
            double dx = s.x() - x;
            double dy = s.y() - y;
            double dz = s.z() - z;
            if (dx * dx + dy * dy + dz * dz < MIN_WAYPOINT_SPACING * MIN_WAYPOINT_SPACING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Breadth-first over columns the bot can step between, nearest first, so the column cap trims the
     * far edge of the radius rather than an arbitrary side. The start column is not a candidate.
     */
    private List<Node> flood(Stop start, double radius, IntBox limit) {
        List<Node> reached = new ArrayList<>();
        Node origin = new Node(floor(start.x()), start.y(), floor(start.z()));
        Set<Node> seen = new HashSet<>();
        seen.add(origin);
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(origin);
        while (!queue.isEmpty() && reached.size() < MAX_FLOOD_COLUMNS) {
            Node at = queue.poll();
            for (int[] d : NEIGHBOURS) {
                int nx = at.x() + d[0];
                int nz = at.z() + d[1];
                if (!inside(nx, nz, start, radius, limit)) {
                    continue;
                }
                int ny = walk.step(at.x(), at.z(), at.y(), nx, nz);
                if (ny == StraightWalk.NOT_WALKABLE || ny < limit.minY() || ny > limit.maxY()) {
                    continue;
                }
                Node next = new Node(nx, ny, nz);
                if (seen.add(next)) {
                    queue.add(next);
                    reached.add(next);
                    if (reached.size() >= MAX_FLOOD_COLUMNS) {
                        break;
                    }
                }
            }
        }
        return reached;
    }

    private static boolean inside(int x, int z, Stop home, double radius, IntBox limit) {
        return x >= limit.minX() && x <= limit.maxX() && z >= limit.minZ() && z <= limit.maxZ()
                && horizontalDistance(x + 0.5, z + 0.5, home.x(), home.z()) <= radius;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
