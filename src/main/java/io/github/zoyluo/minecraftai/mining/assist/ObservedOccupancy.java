package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

/**
 * What one bot has actually seen of the blocks around it: a cubic window of {@value #SIZE}^3 cells,
 * two bits per cell (about 69 KB), each cell UNKNOWN, AIR, SOLID or FLUID. It is written by
 * {@link RayGrid} from first-hit view rays (and by the mining hooks) and read by observed-only
 * planners, which may treat UNKNOWN optimistically but never as evidence of open space.
 *
 * <p><b>Window.</b> The window is {@code [centre - 32, centre + 32]} on every axis (inclusive). Reads
 * outside it return UNKNOWN and writes outside it are ignored. A newer observation always overwrites
 * an older one, so a mined block turns SOLID into AIR and a placed block turns AIR into SOLID.</p>
 *
 * <p><b>Recentring.</b> Storage is toroidal: a world coordinate {@code v} always lives at slot
 * {@code floorMod(v, 65)} on its axis, so moving the window never moves data. {@link #recentre}
 * only clears the slabs of cells that newly enter the window (everything in the overlap is kept),
 * costing at most a few tens of thousands of cell writes for the small shifts the hysteresis
 * allows, and one array fill when the new window is disjoint from the old one. Policy: the caller
 * asks {@link #shouldRecentre(int, int, int)} (true once the bot drifts more than
 * {@link #RECENTRE_MARGIN} cells from the centre on any axis) or simply calls
 * {@link #recentreIfNeeded(int, int, int)} each sensing tick; the cheap check is a few integer
 * comparisons and the window is re-aimed exactly at the bot when it fires.</p>
 *
 * <p>Not thread-safe (server thread only). Reads and writes allocate nothing.</p>
 */
public final class ObservedOccupancy {
    /** Never observed (or forgotten by recentring). The default for every cell. */
    public static final int UNKNOWN = 0;
    /**
     * Observed as a cell a view ray passed through, or a broken/trodden cell. This means "no collider
     * and no fluid was seen there": torches, grass or cobweb read as AIR too, and so does a fluid cell whose
     * partial-height surface a ray only crossed above. Not proof that the cell is empty: a consumer that
     * needs a walkable cell must re-observe it.
     */
    public static final int AIR = 1;
    /** Observed as the first-hit cell of a solid-collider ray. */
    public static final int SOLID = 2;
    /** Observed as the first-hit cell of a fluid ray (water or lava; the two are not told apart). */
    public static final int FLUID = 3;

    /** Cells per axis. */
    public static final int SIZE = 65;
    /** Cells between the centre and each window face on every axis. */
    public static final int HALF = 32;
    public static final int CELL_COUNT = SIZE * SIZE * SIZE;
    /** {@link #shouldRecentre(int, int, int)} fires when the bot is farther than this from the centre on an axis. */
    public static final int RECENTRE_MARGIN = 8;
    /** Centre coordinates are clamped to this magnitude so window arithmetic can never overflow an int. */
    public static final int MAX_CENTRE = 1_000_000_000;

    private static final int CELLS_PER_WORD = 32;

    private final long[] cells = new long[(CELL_COUNT + CELLS_PER_WORD - 1) / CELLS_PER_WORD];
    private int minX;
    private int minY;
    private int minZ;
    // floorMod(min, SIZE) per axis, cached so the hot path needs one compare-and-subtract instead of a modulo
    private int phaseX;
    private int phaseY;
    private int phaseZ;

    public ObservedOccupancy(int centreX, int centreY, int centreZ) {
        place(clampCentre(centreX) - HALF, clampCentre(centreY) - HALF, clampCentre(centreZ) - HALF);
    }

    public ObservedOccupancy(BlockPos centre) {
        this(centre.getX(), centre.getY(), centre.getZ());
    }

    // ---- window geometry ----

    public int centreX() {
        return minX + HALF;
    }

    public int centreY() {
        return minY + HALF;
    }

    public int centreZ() {
        return minZ + HALF;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    /** Inclusive upper bound of the window on X. */
    public int maxX() {
        return minX + SIZE - 1;
    }

    public int maxY() {
        return minY + SIZE - 1;
    }

    public int maxZ() {
        return minZ + SIZE - 1;
    }

    public boolean inWindow(int x, int y, int z) {
        int lx = x - minX;
        int ly = y - minY;
        int lz = z - minZ;
        return (lx | ly | lz) >= 0 && lx < SIZE && ly < SIZE && lz < SIZE;
    }

    public boolean inWindow(BlockPos pos) {
        return inWindow(pos.getX(), pos.getY(), pos.getZ());
    }

    // ---- cell access ----

    /** The cell state, or {@link #UNKNOWN} when the cell is outside the window. */
    public int get(int x, int y, int z) {
        int lx = x - minX;
        int ly = y - minY;
        int lz = z - minZ;
        if ((lx | ly | lz) < 0 || lx >= SIZE || ly >= SIZE || lz >= SIZE) {
            return UNKNOWN;
        }
        int index = slot(lx, ly, lz);
        return (int) (cells[index >> 5] >>> ((index & 31) << 1)) & 3;
    }

    public int get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Stores {@code state} ({@link #UNKNOWN}..{@link #FLUID}, otherwise {@link IllegalArgumentException}).
     *
     * @return true if the cell is inside the window and was written; false if it was ignored
     */
    public boolean set(int x, int y, int z, int state) {
        if ((state & ~3) != 0) {
            throw new IllegalArgumentException("occupancy state out of range: " + state);
        }
        int lx = x - minX;
        int ly = y - minY;
        int lz = z - minZ;
        if ((lx | ly | lz) < 0 || lx >= SIZE || ly >= SIZE || lz >= SIZE) {
            return false;
        }
        int index = slot(lx, ly, lz);
        int shift = (index & 31) << 1;
        int word = index >> 5;
        cells[word] = (cells[word] & ~(3L << shift)) | ((long) state << shift);
        return true;
    }

    public boolean set(BlockPos pos, int state) {
        return set(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public boolean markAir(int x, int y, int z) {
        return set(x, y, z, AIR);
    }

    public boolean markAir(BlockPos pos) {
        return set(pos.getX(), pos.getY(), pos.getZ(), AIR);
    }

    public boolean markSolid(int x, int y, int z) {
        return set(x, y, z, SOLID);
    }

    public boolean markSolid(BlockPos pos) {
        return set(pos.getX(), pos.getY(), pos.getZ(), SOLID);
    }

    public boolean markFluid(int x, int y, int z) {
        return set(x, y, z, FLUID);
    }

    public boolean markFluid(BlockPos pos) {
        return set(pos.getX(), pos.getY(), pos.getZ(), FLUID);
    }

    /** Forgets every observation; the window stays where it is. */
    public void clear() {
        Arrays.fill(cells, 0L);
    }

    // ---- recentring ----

    /** {@link #shouldRecentre(int, int, int, int)} with {@link #RECENTRE_MARGIN}. */
    public boolean shouldRecentre(int posX, int posY, int posZ) {
        return shouldRecentre(posX, posY, posZ, RECENTRE_MARGIN);
    }

    /** True when the point is more than {@code margin} cells from the window centre on any axis. */
    public boolean shouldRecentre(int posX, int posY, int posZ, int margin) {
        long limit = Math.max(0, margin);
        return Math.abs((long) posX - centreX()) > limit
                || Math.abs((long) posY - centreY()) > limit
                || Math.abs((long) posZ - centreZ()) > limit;
    }

    /**
     * Recentres exactly on the given point when {@link #shouldRecentre(int, int, int)} says so.
     *
     * @return true if the window moved
     */
    public boolean recentreIfNeeded(int posX, int posY, int posZ) {
        if (!shouldRecentre(posX, posY, posZ)) {
            return false;
        }
        recentre(posX, posY, posZ);
        return true;
    }

    /**
     * Moves the window so it is centred on the given cell (coordinates clamped to
     * &plusmn;{@link #MAX_CENTRE}). Cells present in both the old and the new window keep their
     * state; every other cell of the new window becomes UNKNOWN.
     */
    public void recentre(int centreX, int centreY, int centreZ) {
        int newMinX = clampCentre(centreX) - HALF;
        int newMinY = clampCentre(centreY) - HALF;
        int newMinZ = clampCentre(centreZ) - HALF;
        int dx = newMinX - minX;
        int dy = newMinY - minY;
        int dz = newMinZ - minZ;
        if ((dx | dy | dz) == 0) {
            return;
        }
        if (Math.abs(dx) >= SIZE || Math.abs(dy) >= SIZE || Math.abs(dz) >= SIZE) {
            Arrays.fill(cells, 0L);
            place(newMinX, newMinY, newMinZ);
            return;
        }
        // One axis at a time: each step clears only the slab that enters the window on that axis,
        // over the extent the other axes have at that moment, so the retained overlap is never touched.
        if (dx != 0) {
            if (dx > 0) {
                clearBox(minX + SIZE, newMinX + SIZE - 1, minY, minY + SIZE - 1, minZ, minZ + SIZE - 1);
            } else {
                clearBox(newMinX, minX - 1, minY, minY + SIZE - 1, minZ, minZ + SIZE - 1);
            }
            minX = newMinX;
            phaseX = Math.floorMod(newMinX, SIZE);
        }
        if (dy != 0) {
            if (dy > 0) {
                clearBox(minX, minX + SIZE - 1, minY + SIZE, newMinY + SIZE - 1, minZ, minZ + SIZE - 1);
            } else {
                clearBox(minX, minX + SIZE - 1, newMinY, minY - 1, minZ, minZ + SIZE - 1);
            }
            minY = newMinY;
            phaseY = Math.floorMod(newMinY, SIZE);
        }
        if (dz != 0) {
            if (dz > 0) {
                clearBox(minX, minX + SIZE - 1, minY, minY + SIZE - 1, minZ + SIZE, newMinZ + SIZE - 1);
            } else {
                clearBox(minX, minX + SIZE - 1, minY, minY + SIZE - 1, newMinZ, minZ - 1);
            }
            minZ = newMinZ;
            phaseZ = Math.floorMod(newMinZ, SIZE);
        }
    }

    public void recentre(BlockPos centre) {
        recentre(centre.getX(), centre.getY(), centre.getZ());
    }

    // ---- misc ----

    /** Size of the packed cell array in bytes (about 69 KB). */
    public int storageBytes() {
        return cells.length * Long.BYTES;
    }

    /** Short name of a state constant, for logs. */
    public static String stateName(int state) {
        return switch (state) {
            case UNKNOWN -> "UNKNOWN";
            case AIR -> "AIR";
            case SOLID -> "SOLID";
            case FLUID -> "FLUID";
            default -> "INVALID(" + state + ")";
        };
    }

    @Override
    public String toString() {
        return "ObservedOccupancy[centre=" + centreX() + "," + centreY() + "," + centreZ() + "]";
    }

    // ---- internals ----

    private void place(int newMinX, int newMinY, int newMinZ) {
        minX = newMinX;
        minY = newMinY;
        minZ = newMinZ;
        phaseX = Math.floorMod(newMinX, SIZE);
        phaseY = Math.floorMod(newMinY, SIZE);
        phaseZ = Math.floorMod(newMinZ, SIZE);
    }

    private static int clampCentre(int value) {
        return Math.max(-MAX_CENTRE, Math.min(MAX_CENTRE, value));
    }

    /** Packed cell index for window-local coordinates, each already known to be in [0, SIZE). */
    private int slot(int lx, int ly, int lz) {
        int sx = lx + phaseX;
        if (sx >= SIZE) {
            sx -= SIZE;
        }
        int sy = ly + phaseY;
        if (sy >= SIZE) {
            sy -= SIZE;
        }
        int sz = lz + phaseZ;
        if (sz >= SIZE) {
            sz -= SIZE;
        }
        return (sx * SIZE + sy) * SIZE + sz;
    }

    /** Sets every cell of the inclusive world-coordinate box to UNKNOWN. The box must lie inside one window's extent. */
    private void clearBox(int x0, int x1, int y0, int y1, int z0, int z1) {
        int startZ = Math.floorMod(z0, SIZE);
        for (int x = x0; x <= x1; x++) {
            int sx = Math.floorMod(x, SIZE);
            for (int y = y0; y <= y1; y++) {
                int base = (sx * SIZE + Math.floorMod(y, SIZE)) * SIZE;
                int sz = startZ;
                for (int z = z0; z <= z1; z++) {
                    int index = base + sz;
                    cells[index >> 5] &= ~(3L << ((index & 31) << 1));
                    if (++sz == SIZE) {
                        sz = 0;
                    }
                }
            }
        }
    }
}
