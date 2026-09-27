package dev.spawnbotswrapper.inhabitants.spawn;

import java.util.HashSet;
import java.util.Set;

/**
 * An INDEPENDENT statement of the walkability rules, written directly from the spec and deliberately
 * sharing no code with the production classes, so a bug there cannot hide in both. It reads the probe
 * directly (no memo) and is intentionally the plain rule: centre-line samples only, no body-width
 * refinements. Everything the planner accepts must pass it; the planner may be stricter.
 * <p>
 * The one thing it must share with the planner is WHICH samples it looks at, because at a diagonal that
 * skips a corner column the answer legitimately depends on the samples: the documented spacing (equal
 * parts of at most a quarter block) and the documented tie rule (a sample exactly on a block boundary
 * belongs to the block being entered).
 */
final class WalkRules {

    private WalkRules() {
    }

    /** Feet standing rule: solid safe floor, two free blocks, inside border and height limits. */
    static boolean standable(BlockProbe p, int x, int y, int z, boolean allowWater) {
        if (y < p.minY() + 1 || y > p.maxY() - 2 || !p.insideBorder(x, z)) {
            return false;
        }
        Cell floor = p.cell(x, y - 1, z);
        Cell feet = p.cell(x, y, z);
        Cell head = p.cell(x, y + 1, z);
        return floor == Cell.SOLID_STANDABLE && free(feet, allowWater) && free(head, allowWater);
    }

    private static boolean free(Cell c, boolean allowWater) {
        return c == Cell.EMPTY || (allowWater && c == Cell.WATER);
    }

    /**
     * At each sample the column must have a dry standing level within +1 up / -2 down of the previous
     * sample's level; the segment must end at exactly {@code y1}. Levels are tracked as a SET so no
     * walkable interpretation is missed.
     */
    static boolean walkable(BlockProbe p, double x0, double z0, int y0, double x1, double z1, int y1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dz * dz);
        int n = 2 * (int) Math.ceil(length / 0.5);
        if (n == 0) {
            return y0 == y1;
        }
        Set<Integer> levels = new HashSet<>();
        levels.add(y0);
        for (int i = 1; i <= n; i++) {
            double x = i == n ? x1 : x0 + dx * ((double) i / n);
            double z = i == n ? z1 : z0 + dz * ((double) i / n);
            int cx = i == n ? (int) Math.floor(x) : entering(x, dx);
            int cz = i == n ? (int) Math.floor(z) : entering(z, dz);
            Set<Integer> next = new HashSet<>();
            for (int y : levels) {
                for (int ny = y - 2; ny <= y + 1; ny++) {
                    if (standable(p, cx, ny, cz, false)) {
                        next.add(ny);
                    }
                }
            }
            if (next.isEmpty()) {
                return false;
            }
            levels = next;
        }
        return levels.contains(y1);
    }

    private static int entering(double v, double travel) {
        return (int) Math.floor(v + Math.signum(travel) * 1e-9);
    }
}
