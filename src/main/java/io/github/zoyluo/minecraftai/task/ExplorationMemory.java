package io.github.zoyluo.minecraftai.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a bot that looks for a resource remembers about the search so far: from where it has already
 * looked around, how far it could see in each direction when it did, which way it was walking, and
 * which headings were refused where it stands. A person who found nothing here walks on into ground
 * not yet seen instead of circling back over it.
 *
 * <p>Only what the bot really saw counts. A caller records a stance after it looked around there
 * (see {@link #markSearched}): the ground it could see within the perception radius is searched, and
 * the ground behind whatever blocked its view is not, because it was never seen. Ground it could not
 * see is not worth walking toward either, since nothing says the way there is open: it is neither
 * credited nor sought. The compass headings are directions, never destinations.</p>
 */
final class ExplorationMemory {
    /** How finely the horizon around a stance is divided. */
    static final int SECTORS = 64;
    /** Only the first stance marked in each 4x4 columns is kept: standing still adds nothing, a walk adds a point per few blocks. */
    private static final int GRID = 4;
    /**
     * Probe points of the disc a hop would reveal: its centre, a ring at half the radius, a ring near the edge.
     * Eight and sixteen points keep the set the same under a quarter of the compass's turn, so with nothing
     * known every compass heading is worth exactly the same and the bot's own heading decides.
     */
    private static final double[][] PROBES = probes();

    /** One look around: where the bot stood, how far it could see, and how far it saw in each sector of the horizon. */
    private record Stance(int x, int z, int radius, double[] sight) {
    }

    private final Set<Long> searchedKeys = new HashSet<>();
    private final List<Stance> searched = new ArrayList<>();
    private int lastDirection;
    private int refusedX;
    private int refusedZ;
    private int refusedMask;
    /** The stance and remembered resource of the last guided hop that was refused, or null. */
    private int[] guidedRefused;

    /** Whether a look around from here would add anything: no stance of this 4x4 column was recorded yet. */
    boolean wantsLookAround(int x, int z) {
        return !searchedKeys.contains(key(x, z));
    }

    /**
     * Records that the bot looked around from a stance and searched what it saw there without finding the
     * resource.
     *
     * @param radius how far the bot searches from where it stands (its perception radius)
     * @param sight  for each of the {@link #SECTORS} sectors of the horizon, counted from the direction of
     *               negative X by increasing azimuth ({@link #sector}), how far the bot saw along it:
     *               {@code radius} where nothing blocked its view, less where something did, 0 where it
     *               could not look at all
     */
    void markSearched(int x, int z, int radius, double[] sight) {
        if (sight.length != SECTORS) {
            throw new IllegalArgumentException("one sight distance per sector");
        }
        if (searchedKeys.add(key(x, z))) {
            searched.add(new Stance(x, z, radius, sight.clone()));
        }
    }

    /** The sector of the horizon in which the offset (dx, dz) lies. */
    static int sector(double dx, double dz) {
        double turn = (Math.atan2(dz, dx) + Math.PI) / (2.0D * Math.PI);
        return Math.min(SECTORS - 1, (int) (turn * SECTORS));
    }

    /** The azimuth, as a direction (dx, dz) with length 1, at the middle of a sector. */
    static double[] sectorCentre(int sector) {
        double azimuth = (sector + 0.5D) / SECTORS * 2.0D * Math.PI - Math.PI;
        return new double[] {Math.cos(azimuth), Math.sin(azimuth)};
    }

    /**
     * How many of the probe points of the disc of {@code radius} around (x, z) a bot standing there could
     * still reveal: ground that was neither seen from a stance already searched nor hidden from one by
     * something that blocked its view.
     */
    int unsearchedProbes(double x, double z, int radius) {
        int unsearched = 0;
        for (double[] probe : PROBES) {
            if (!seenOrHidden(x + probe[0] * radius, z + probe[1] * radius)) {
                unsearched++;
            }
        }
        return unsearched;
    }

    /**
     * Whether the point was seen from a searched stance, or lies behind something that stance's view ran
     * into: only a point no stance accounts for is ground that walking there could reveal.
     */
    private boolean seenOrHidden(double probeX, double probeZ) {
        boolean hidden = false;
        for (Stance stance : searched) {
            double dx = probeX - stance.x();
            double dz = probeZ - stance.z();
            double distance = Math.hypot(dx, dz);
            // Past twice the radius a single sector of the horizon is too wide to say anything about the point.
            if (distance > 2.0D * stance.radius()) {
                continue;
            }
            double seen = stance.sight()[sector(dx, dz)];
            if (distance <= seen) {
                return true;
            }
            hidden |= seen < stance.radius();
        }
        return hidden;
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
            int unsearched = unsearchedProbes(
                    x + directions[direction][0] / length * hop, z + directions[direction][1] / length * hop, radius);
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

    private static long key(int x, int z) {
        return ((long) Math.floorDiv(x, GRID) << 32) ^ (Math.floorDiv(z, GRID) & 0xFFFFFFFFL);
    }

    private static double[][] probes() {
        List<double[]> points = new ArrayList<>();
        points.add(new double[] {0.0D, 0.0D});
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(45.0D * i);
            points.add(new double[] {0.5D * Math.cos(angle), 0.5D * Math.sin(angle)});
        }
        for (int i = 0; i < 16; i++) {
            double angle = Math.toRadians(22.5D * i);
            points.add(new double[] {0.9D * Math.cos(angle), 0.9D * Math.sin(angle)});
        }
        return points.toArray(new double[0][]);
    }
}
