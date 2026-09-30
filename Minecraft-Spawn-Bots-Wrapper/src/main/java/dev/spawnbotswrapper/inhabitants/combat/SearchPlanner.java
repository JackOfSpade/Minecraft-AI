package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.SearchSpot;

import java.util.List;

/**
 * Picks where an inhabitant that lost sight of a player looks next, "smartly": among the reachable cells around the last
 * known position it prefers
 * <ul>
 *   <li>the direction the player was last heading (alignment with the last heading),</li>
 *   <li>cells with an OPENING: corners, doorways and corridor branches, i.e. cells from which space that is hidden
 *       from where the bot stands becomes visible (the opening value of a {@link SearchSpot}),</li>
 *   <li>cells not visited yet in this search, and closer ones (walking time is what a 10 second search has little of).</li>
 * </ul>
 * Pure: the candidate cells and their opening values come from the world view. Ties break deterministically.
 */
public final class SearchPlanner {
    private SearchPlanner() {
    }

    /** A spot within this many blocks of an already visited point counts as visited. */
    public static final double VISITED_RADIUS = 3.0;
    /** Walking speed used to judge what is reachable in the time that is left (blocks per tick, about 4.3 blocks per second). */
    public static final double WALK_BLOCKS_PER_TICK = 0.2;

    static final double W_ALIGN = 1.0;
    static final double W_OPENING = 2.0;
    static final double W_NEAR = 0.03;

    /**
     * The best spot to check next, or null when nothing is worth walking to.
     *
     * @param bot            where the inhabitant stands
     * @param focus          the search focus: the last known position, or the latest sound
     * @param headingX       the last heading of the player, horizontal unit vector (0,0 when it was standing still)
     * @param headingZ       see {@code headingX}
     * @param spots          candidate cells
     * @param visited        points already checked
     * @param ticksRemaining what is left of the search window; a spot that cannot be walked to in time is skipped
     */
    public static SearchSpot choose(Pos bot, Pos focus, double headingX, double headingZ, List<SearchSpot> spots,
                                    List<Pos> visited, long ticksRemaining) {
        SearchSpot best = null;
        double bestScore = -Double.MAX_VALUE;
        for (SearchSpot s : spots) {
            if (isVisited(s.pos(), visited)) {
                continue;
            }
            double walk = bot.horizontalTo(s.pos());
            // a route is longer than the straight line; leave room for it and for looking around at the end
            if (walk * 1.3 > ticksRemaining * WALK_BLOCKS_PER_TICK) {
                continue;
            }
            double score = score(bot, focus, headingX, headingZ, s);
            if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) <= 1e-9 && best != null && before(s.pos(), best.pos()))) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    /** The score of one spot (higher is better); public for the tests. */
    public static double score(Pos bot, Pos focus, double headingX, double headingZ, SearchSpot s) {
        double alignment = 0.0;
        double hx = headingX;
        double hz = headingZ;
        double hl = Math.sqrt(hx * hx + hz * hz);
        if (hl > 1e-6) {
            double dx = s.pos().x() - focus.x();
            double dz = s.pos().z() - focus.z();
            double dl = Math.sqrt(dx * dx + dz * dz);
            if (dl > 1e-6) {
                // 0 when opposite the heading, 1 when straight along it
                alignment = ((dx * hx + dz * hz) / (dl * hl) + 1.0) * 0.5;
            }
        }
        return W_ALIGN * alignment + W_OPENING * s.opening() - W_NEAR * bot.horizontalTo(s.pos());
    }

    /** True when {@code p} is within {@link #VISITED_RADIUS} of any visited point. */
    public static boolean isVisited(Pos p, List<Pos> visited) {
        for (Pos v : visited) {
            if (p.horizontalTo(v) <= VISITED_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static boolean before(Pos a, Pos b) {
        if (a.x() != b.x()) {
            return a.x() < b.x();
        }
        return a.z() < b.z();
    }
}
