package io.github.zoyluo.minecraftai.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a bot that looks for a resource remembers about the search so far: from where it has already
 * looked around, which way it was walking, and which headings were refused where it stands. A person
 * who found nothing here walks on into ground not yet seen instead of circling back over it.
 *
 * <p>Only a search the bot really made counts. A caller marks a stance after it scanned what it can
 * perceive from there (within the perception radius) and found nothing; nothing is inferred about
 * terrain the bot has not looked at, and the compass headings are directions, never destinations.</p>
 */
final class ExplorationMemory {
    /** Only the first stance marked in each 4x4 columns is kept: standing still adds nothing, a walk adds a point per few blocks. */
    private static final int GRID = 4;
    /** Probe points of the disc a hop would reveal: its centre, a ring at half the radius, a ring near the edge. */
    private static final double[][] PROBES = probes();

    private final Set<Long> searchedKeys = new HashSet<>();
    private final List<int[]> searched = new ArrayList<>();
    private int lastDirection;
    private int refusedX;
    private int refusedZ;
    private int refusedMask;
    /** The stance and remembered resource of the last guided hop that was refused, or null. */
    private int[] guidedRefused;

    /** Records that the surroundings of a stance were searched without finding the resource. */
    void markSearched(int x, int z) {
        int cellX = Math.floorDiv(x, GRID);
        int cellZ = Math.floorDiv(z, GRID);
        if (searchedKeys.add(((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL))) {
            searched.add(new int[] {x, z});
        }
    }

    /**
     * How many of the probe points of the disc of {@code radius} around (x, z) lie outside every
     * searched disc: the ground a bot standing there could still reveal.
     */
    int unsearchedProbes(int x, int z, int radius) {
        long radiusSquared = (long) radius * radius;
        int unsearched = 0;
        for (double[] probe : PROBES) {
            int probeX = x + (int) Math.round(probe[0] * radius);
            int probeZ = z + (int) Math.round(probe[1] * radius);
            boolean covered = false;
            for (int[] centre : searched) {
                long dx = probeX - centre[0];
                long dz = probeZ - centre[1];
                if (dx * dx + dz * dz <= radiusSquared) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                unsearched++;
            }
        }
        return unsearched;
    }

    /**
     * The compass direction whose next hop of {@code hop} blocks would reveal the most ground not yet
     * searched. A tie keeps the heading the bot was already walking, then the nearest turns to it, so
     * a bot walks out in a line until the way is closed, and turns only as far as it must. A direction
     * refused from this very stance is not offered again unless every direction was.
     */
    int chooseDirection(int[][] directions, int x, int z, int hop, int radius) {
        int count = directions.length;
        int refused = refusedMask(x, z);
        if (refused == (1 << count) - 1) {
            refused = 0;
        }
        int best = -1;
        int bestUnsearched = -1;
        for (int step = 0; step < count; step++) {
            int turn = (step + 1) / 2 * ((step & 1) == 1 ? 1 : -1);
            int direction = Math.floorMod(lastDirection + turn, count);
            if ((refused & (1 << direction)) != 0) {
                continue;
            }
            double length = Math.hypot(directions[direction][0], directions[direction][1]);
            int hopX = x + (int) Math.round(directions[direction][0] / length * hop);
            int hopZ = z + (int) Math.round(directions[direction][1] / length * hop);
            int unsearched = unsearchedProbes(hopX, hopZ, radius);
            if (unsearched > bestUnsearched) {
                best = direction;
                bestUnsearched = unsearched;
            }
        }
        return best;
    }

    /** The admitted hop heads this way: the next one keeps the heading while it still pays. */
    void noteHeading(int direction) {
        lastDirection = direction;
    }

    /** The navigation fence turned this heading down at (x, z); it will not be chosen again from there. */
    void noteRefused(int x, int z, int direction) {
        if (refusedMask == 0 || refusedX != x || refusedZ != z) {
            refusedX = x;
            refusedZ = z;
            refusedMask = 0;
        }
        refusedMask |= 1 << direction;
    }

    /**
     * The navigation fence turned down the heading toward a remembered resource at (x, z); it is not asked
     * for again from there. A compass heading that was refused is passed over the same way (see
     * {@link #noteRefused}), but a guided hop has no compass direction to mark, so without this the same
     * refused hop would be requested on every tick until the whole search budget was gone.
     */
    void noteGuidedRefused(int x, int z, int hintX, int hintZ) {
        guidedRefused = new int[] {x, z, hintX, hintZ};
    }

    boolean isGuidedRefused(int x, int z, int hintX, int hintZ) {
        return guidedRefused != null && guidedRefused[0] == x && guidedRefused[1] == z
                && guidedRefused[2] == hintX && guidedRefused[3] == hintZ;
    }

    private int refusedMask(int x, int z) {
        return refusedX == x && refusedZ == z ? refusedMask : 0;
    }

    private static double[][] probes() {
        List<double[]> points = new ArrayList<>();
        points.add(new double[] {0.0D, 0.0D});
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(60.0D * i);
            points.add(new double[] {0.5D * Math.cos(angle), 0.5D * Math.sin(angle)});
        }
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(30.0D * i);
            points.add(new double[] {0.9D * Math.cos(angle), 0.9D * Math.sin(angle)});
        }
        return points.toArray(new double[0][]);
    }
}
