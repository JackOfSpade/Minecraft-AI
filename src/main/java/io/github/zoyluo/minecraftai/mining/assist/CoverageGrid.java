package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Per-mission memory of which ground the bot has already dug through or walked (mining-assist design
 * 5.3): a coarse "have I roughly been here" fact, used only to steer {@link LegChooser} away from
 * re-covering the same corridor. Pure data, no world access, so it is fully unit-testable.
 *
 * <p><b>Resolution.</b> Cells are bucketed into {@value #VOXEL_SIZE}x{@value #VOXEL_SIZE}x
 * {@value #VOXEL_SIZE} voxels (the design's "4x4x4 voxel keys"): a coverage fact this coarse is
 * cheap to keep and cheap to query over a corridor tens of blocks long, and strip-mine corridors
 * are themselves several blocks wide, so voxel-grain marking already captures "was near here".</p>
 *
 * <p><b>Capacity.</b> At most {@value #CAPACITY} voxels are held, in a {@link LinkedHashSet} exactly
 * as the design specifies, so a very long mission forgets its oldest ground rather than growing
 * without bound; the bot re-treats it as unexplored, which only ever makes {@link LegChooser} more
 * willing to revisit it, never less safe.</p>
 *
 * <p>Not thread-safe; owned by one {@code OreDigTask} instance, server thread only.</p>
 */
public final class CoverageGrid {
    /** Edge length of one coverage voxel, in blocks (the design's "4x4x4 voxel keys"). */
    public static final int VOXEL_SIZE = 4;
    /** Upper bound on distinct voxels held at once (the design's "LinkedHashSet cap 32k"). */
    public static final int CAPACITY = 32_000;

    private final LinkedHashSet<Long> voxels = new LinkedHashSet<>();

    /** Marks the voxel containing {@code pos} as covered (a dug cell or a trail step). */
    public void mark(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        markVoxel(voxelKey(pos.getX(), pos.getY(), pos.getZ()));
    }

    private void markVoxel(long key) {
        if (voxels.contains(key)) {
            return;
        }
        if (voxels.size() >= CAPACITY) {
            Iterator<Long> oldest = voxels.iterator();
            oldest.next();
            oldest.remove();
        }
        voxels.add(key);
    }

    /** True when the voxel containing {@code pos} has been marked. */
    public boolean isMarked(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        return voxels.contains(voxelKey(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** Number of distinct voxels currently held. */
    public int size() {
        return voxels.size();
    }

    /** Forgets everything (a fresh mission). */
    public void clear() {
        voxels.clear();
    }

    /**
     * Fraction of the corridor that is still fresh (unmarked): a box {@code length} blocks long
     * starting at {@code origin} and extending along {@code dir}, {@code halfWidth} blocks either
     * side of the centreline (perpendicular, {@code 2*halfWidth+1} wide) and {@code yBand} blocks
     * above and below {@code origin}'s height (design 5.3: "corridor of length L, band Y&plusmn;2,
     * half-width 6"). Samples every {@value #VOXEL_SIZE} blocks along the length and across the
     * width (matching the storage grain) and every block of the Y band (the band is narrow enough
     * that voxel-grain Y sampling would skip it almost entirely). Returns {@code 1.0} (fully fresh)
     * for a non-positive length, so an unset corridor never looks stale by default.
     */
    public double freshFraction(BlockPos origin, Direction dir, int length, int yBand, int halfWidth) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(dir, "dir");
        if (length <= 0) {
            return 1.0D;
        }
        Direction across = dir.rotateYClockwise();
        int sampled = 0;
        int fresh = 0;
        for (int along = 0; along < length; along += VOXEL_SIZE) {
            BlockPos onAxis = origin.offset(dir, along);
            for (int side = -halfWidth; side <= halfWidth; side += VOXEL_SIZE) {
                BlockPos onRow = onAxis.offset(across, side);
                for (int up = -yBand; up <= yBand; up++) {
                    sampled++;
                    if (!isMarked(onRow.up(up))) {
                        fresh++;
                    }
                }
            }
        }
        return sampled == 0 ? 1.0D : (double) fresh / sampled;
    }

    /**
     * Packs voxel coordinates {@code (x/4, y/4, z/4)} (floor division) into one {@code long} key.
     * 22 bits each for X and Z (&plusmn;~8M voxels, far past any realistic mission radius) and 20
     * bits for Y (comfortably covers the full build-height range); masking before shifting keeps
     * negative voxel coordinates distinct without needing a sign-aware pack.
     */
    private static long voxelKey(int x, int y, int z) {
        long vx = Math.floorDiv(x, VOXEL_SIZE) & 0x3F_FFFFL;
        long vy = Math.floorDiv(y, VOXEL_SIZE) & 0xF_FFFFL;
        long vz = Math.floorDiv(z, VOXEL_SIZE) & 0x3F_FFFFL;
        return (vx << 42) | (vy << 22) | vz;
    }
}
