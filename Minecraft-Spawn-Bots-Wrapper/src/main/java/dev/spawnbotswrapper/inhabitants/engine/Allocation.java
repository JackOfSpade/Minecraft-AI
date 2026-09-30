package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure decisions of nearest-first population: how far a structure is from the players, and which structure gets
 * how many of the {@code processing.maxLiveBots} slots. No Minecraft, no store, no clock, no state: {@link
 * AllocationGovernor} feeds it and acts on the answer.
 * <p>
 * The rule (the user's words: "nearest POI should get priority, it fills until our logic deems that size POI has
 * enough bots, then rest available goes to next closest POI"): sort the candidate structures by 3D distance from the
 * nearest real player to the structure's bounding box, then walk the list giving each structure its full fill target
 * until the budget is used up. Bots that must stay wherever they are (engaged with a player, or SEEN by one and still
 * inside the relevance area) are protected: they always count against the budget first, and count toward their own
 * structure's target.
 */
final class Allocation {
    /** "No player in this dimension": such a structure is never a candidate. */
    static final double UNREACHABLE = Double.POSITIVE_INFINITY;

    private Allocation() {
    }

    /**
     * One structure that may get bots.
     *
     * @param distance      3D distance from the nearest real player of its dimension to its bounding box
     * @param target        its fill target: what the size logic says this structure should have (N - dead - failed)
     * @param protectedLive live bots of it that may not be removed now (engaged, or seen while its chunk is loaded by a player)
     * @param incumbent     it had bots allocated in the previous result: it keeps its place against a rival that is
     *                      not nearer by the hysteresis margin
     */
    record Candidate(StructureKey key, double distance, int target, int protectedLive, boolean incumbent) {
    }

    /** The desired live-bot count per structure, and the nearest-first order it was derived in. */
    record Result(Map<StructureKey, Integer> desired, List<StructureKey> order) {
        int of(StructureKey key) {
            Integer n = desired.get(key);
            return n == null ? 0 : n;
        }
    }

    /**
     * Point-to-box distance: 3D from the closest of the player positions that share the box's dimension, or {@link
     * #UNREACHABLE} when no player does. Inside the box it is 0.
     */
    static double distance(IntBox box, String dimension, List<BotGateway.PlayerPos> players) {
        if (box == null || players == null) {
            return UNREACHABLE;
        }
        double best = Double.POSITIVE_INFINITY;
        for (BotGateway.PlayerPos p : players) {
            if (!p.dimension().equals(dimension)) {
                continue;
            }
            double d = box.distanceSq(p.x(), p.y(), p.z());
            if (d < best) {
                best = d;
            }
        }
        return Double.isInfinite(best) ? UNREACHABLE : Math.sqrt(best);
    }

    /** Same as {@link #distance} but horizontal only: the measure of the relevance area (chunks tick by x/z distance). */
    static double horizontalDistance(IntBox box, String dimension, List<BotGateway.PlayerPos> players) {
        if (box == null || players == null) {
            return UNREACHABLE;
        }
        double best = Double.POSITIVE_INFINITY;
        for (BotGateway.PlayerPos p : players) {
            if (!p.dimension().equals(dimension)) {
                continue;
            }
            double d = box.horizontalDistanceSq(p.x(), p.z());
            if (d < best) {
                best = d;
            }
        }
        return Double.isInfinite(best) ? UNREACHABLE : Math.sqrt(best);
    }

    /**
     * Walks the candidates nearest first and hands out the budget.
     *
     * @param budget         {@code processing.maxLiveBots}; zero or less means unlimited
     * @param protectedTotal every protected live bot in the world (also of structures that are not candidates): they use
     *                       the budget before anybody else
     * @param hysteresis     an incumbent is treated as this many blocks nearer than it is, so two nearly equidistant
     *                       structures do not swap places every pass
     */
    static Result allocate(List<Candidate> candidates, int budget, int protectedTotal, double hysteresis) {
        List<Candidate> sorted = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            if (!Double.isNaN(c.distance()) && !Double.isInfinite(c.distance())) {
                sorted.add(c);
            }
        }
        sorted.sort(Comparator.<Candidate>comparingDouble(c -> c.distance() - (c.incumbent() ? Math.max(0.0, hysteresis) : 0.0))
                .thenComparingDouble(Candidate::distance)
                .thenComparing(c -> c.key().asString()));
        long pool = budget <= 0 ? Long.MAX_VALUE : Math.max(0, budget - Math.max(0, protectedTotal));
        Map<StructureKey, Integer> desired = new HashMap<>(sorted.size() * 2);
        List<StructureKey> order = new ArrayList<>(sorted.size());
        for (Candidate c : sorted) {
            int have = Math.max(0, c.protectedLive());
            int need = Math.max(0, c.target() - have);
            int give = (int) Math.min(need, pool);
            pool -= give;
            desired.put(c.key(), have + give);
            order.add(c.key());
        }
        return new Result(desired, order);
    }

    /** The persisted bounds {minX,minY,minZ,maxX,maxY,maxZ} as a box, or null when missing or malformed. */
    static IntBox boxOf(int[] b) {
        if (b == null || b.length != 6 || b[3] < b[0] || b[4] < b[1] || b[5] < b[2]) {
            return null;
        }
        return new IntBox(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** The vacant slots of a structure: {@code N - dead - failed - (bots that occupy a slot)}, never negative. */
    static int vacant(int planned, int dead, int failed, int occupied) {
        return Math.max(0, planned - dead - failed - occupied);
    }

    /**
     * What is left of a structure that just lost its unseen bots: {@code N - dead - seenAlive}. A death is never in
     * the free count, so no death makes a slot vacant.
     */
    static int vacantAfterUnseenDeleted(int planned, int dead, int seenAlive) {
        return Math.max(0, planned - dead - seenAlive);
    }
}
