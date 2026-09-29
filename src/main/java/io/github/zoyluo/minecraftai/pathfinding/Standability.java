package io.github.zoyluo.minecraftai.pathfinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class Standability {
    private static final Map<CacheKey, Boolean> CACHE = new ConcurrentHashMap<>(4096);
    private static volatile long version;

    private Standability() {
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static void invalidateAll() {
        version++;
        CACHE.clear();
    }

    public static boolean isStandable(ServerWorld world, BlockPos pos) {
        CacheKey key = new CacheKey(world.getRegistryKey().getValue().toString(), version, pos);
        Boolean cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = compute(world, pos);
        CACHE.put(key, result);
        return result;
    }

    /**
     * {@link #isStandable} read straight from the world: neither reads nor writes the shared memo.
     * For callers that must see the current block state of a handful of cells (a bot that may have
     * just dug or been walled in) without evicting every other bot's cached results, which is what
     * {@link #clearCache()} does.
     */
    public static boolean isStandableFresh(ServerWorld world, BlockPos pos) {
        return compute(world, pos);
    }

    public static Optional<BlockPos> findNearestStandable(ServerWorld world,
                                                          BlockPos origin,
                                                          int horizontalRadius,
                                                          int verticalDown,
                                                          int verticalUp) {
        Optional<BlockPos> sameColumn = findStandableInColumn(world, origin, verticalDown, verticalUp);
        if (sameColumn.isPresent()) {
            return sameColumn;
        }

        int radiusLimit = Math.max(0, horizontalRadius);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int radius = 1; radius <= radiusLimit; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Optional<BlockPos> candidate = findStandableInColumn(world, origin.add(dx, 0, dz), verticalDown, verticalUp);
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    double distance = candidate.get().getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        best = candidate.get();
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) {
                return Optional.of(best.toImmutable());
            }
        }
        return Optional.empty();
    }

    /**
     * Goal-oriented snap used by {@code AStarPathfinder.resolveEndpoint} (see its header for the
     * 2026-09-28 session-log evidence this was written against): unlike {@link #findNearestStandable},
     * which searches {@code origin}'s OWN COLUMN as deep as the caller allows before ever trying a
     * lateral cell -- exactly right for a bot's own start position, which really can be standing at
     * the bottom of a shaft -- this is for a GOAL cell a caller only knows the XZ of, where a deep
     * same-column descent means "snapped into whatever pit, mineshaft, or lake happens to be under
     * that XZ" even though the requester (e.g. a followed player) was standing on the surface the
     * whole time.
     *
     * <p>Phase 1 tries a small window around {@code origin} first (bounded both down and up, not
     * just down) and, unlike the ring-expanding search below, scores every standable cell in that
     * whole window by true 3D distance to {@code origin} rather than returning the first ring that
     * has any hit -- ties broken toward the lower cell, matching {@link #findStandableInColumn}'s
     * own tie-break. Phase 2 -- a bounded-depth fallback shaped like {@link #findNearestStandable}'s
     * ring search -- only runs when phase 1 finds nothing, and every column it considers stops
     * dead the instant it meets a fluid cell: a goal over open water must resolve onto a nearby
     * standable shore/surface cell (or fail outright), never dive through the water column to the
     * seabed. Dedicated swim-follow code is the only path allowed to enter water on purpose.
     */
    public static Optional<SnappedGoal> findNearestStandableForGoal(ServerWorld world,
                                                                     BlockPos origin,
                                                                     int horizontalRadius,
                                                                     int nearVerticalDown,
                                                                     int nearVerticalUp,
                                                                     int deepVerticalDown) {
        Optional<BlockPos> near = findNearestInWindow(world, origin, horizontalRadius, nearVerticalDown, nearVerticalUp);
        if (near.isPresent()) {
            return Optional.of(new SnappedGoal(near.get(), Phase.NEAR));
        }
        Optional<BlockPos> deep = findNearestStandableNoFluidDescent(world, origin, horizontalRadius, deepVerticalDown);
        return deep.map(pos -> new SnappedGoal(pos, Phase.DEEP));
    }

    /** Result of {@link #findNearestStandableForGoal}: the snapped cell plus which phase found it. */
    public record SnappedGoal(BlockPos pos, Phase phase) {
    }

    public enum Phase {
        NEAR, DEEP
    }

    private static Optional<BlockPos> findNearestInWindow(ServerWorld world, BlockPos origin,
                                                           int horizontalRadius, int verticalDown, int verticalUp) {
        return scanWindowInShells(origin, horizontalRadius, verticalDown, verticalUp, pos -> isStandable(world, pos));
    }

    /**
     * Best cell of the {@code horizontalRadius x [-verticalDown, +verticalUp] x horizontalRadius}
     * window around {@code origin} by true 3D distance, ties broken toward the origin's own column
     * (smaller horizontal distance) and then the lower cell.
     *
     * <p>Cells are visited in shells of equal Chebyshev distance from {@code origin}. Every cell of
     * shell {@code s + 1} or beyond is at least {@code s + 1} blocks away, so once the best hit so
     * far is strictly closer than that the rest of the window cannot beat (or tie) it and the scan
     * stops: a goal on ordinary ground resolves after a handful of probes instead of the whole
     * 17 x 17 x 8 window. The result is identical to scanning the entire window.
     */
    static Optional<BlockPos> scanWindowInShells(BlockPos origin, int horizontalRadius,
                                                 int verticalDown, int verticalUp,
                                                 java.util.function.Predicate<BlockPos> standable) {
        int maxShell = Math.max(horizontalRadius, Math.max(verticalDown, verticalUp));
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        int bestHorizontalSq = Integer.MAX_VALUE;
        int bestDx = 0;
        int bestDz = 0;
        for (int shell = 0; shell <= maxShell; shell++) {
            for (int dx = -Math.min(shell, horizontalRadius); dx <= Math.min(shell, horizontalRadius); dx++) {
                for (int dz = -Math.min(shell, horizontalRadius); dz <= Math.min(shell, horizontalRadius); dz++) {
                    boolean horizontalOnShell = Math.max(Math.abs(dx), Math.abs(dz)) == shell;
                    int dyFrom = Math.max(-verticalDown, -shell);
                    int dyTo = Math.min(verticalUp, shell);
                    for (int dy = dyFrom; dy <= dyTo; dy++) {
                        if (!horizontalOnShell && Math.abs(dy) != shell) {
                            continue;
                        }
                        BlockPos candidate = origin.add(dx, dy, dz);
                        if (!standable.test(candidate)) {
                            continue;
                        }
                        double distSq = candidate.getSquaredDistance(origin);
                        int horizontalSq = dx * dx + dz * dz;
                        // Ties: the goal's own column first (a goal offered on a solid block means "stand
                        // on top of it", which callers such as the surface-water search rely on to
                        // climb a rim and look over it), then the lower cell (findStandableInColumn's
                        // own safer tie-break).
                        boolean better = distSq < bestDistSq
                                || (distSq == bestDistSq && best != null
                                && (horizontalSq < bestHorizontalSq
                                || (horizontalSq == bestHorizontalSq && (candidate.getY() < best.getY()
                                || (candidate.getY() == best.getY()
                                && (dx < bestDx || (dx == bestDx && dz < bestDz)))))));
                        if (better) {
                            best = candidate.toImmutable();
                            bestDistSq = distSq;
                            bestHorizontalSq = horizontalSq;
                            bestDx = dx;
                            bestDz = dz;
                        }
                    }
                }
            }
            double nextShellMinimum = (double) (shell + 1) * (shell + 1);
            if (best != null && bestDistSq < nextShellMinimum) {
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<BlockPos> findNearestStandableNoFluidDescent(ServerWorld world, BlockPos origin,
                                                                          int horizontalRadius, int verticalDown) {
        Optional<BlockPos> sameColumn = findStandableBelowWithoutFluid(world, origin, verticalDown);
        if (sameColumn.isPresent()) {
            return sameColumn;
        }
        int radiusLimit = Math.max(0, horizontalRadius);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int radius = 1; radius <= radiusLimit; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Optional<BlockPos> candidate =
                            findStandableBelowWithoutFluid(world, origin.add(dx, 0, dz), verticalDown);
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    double distance = candidate.get().getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        best = candidate.get();
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) {
                return Optional.of(best.toImmutable());
            }
        }
        return Optional.empty();
    }

    /**
     * Scans straight down from {@code origin} (never up -- the deep fallback's whole job is
     * finding a floor under a goal phase 1 couldn't place), stopping short -- and reporting no
     * candidate in this column at all -- the instant a fluid cell is met, instead of continuing
     * through it to whatever solid floor lies beneath.
     */
    private static Optional<BlockPos> findStandableBelowWithoutFluid(ServerWorld world, BlockPos origin, int verticalDown) {
        int minY = Math.max(world.getBottomY() + 1, origin.getY() - Math.max(0, verticalDown));
        for (int y = origin.getY(); y >= minY; y--) {
            BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
            if (!world.getBlockState(candidate).getFluidState().isEmpty()) {
                return Optional.empty();
            }
            if (isStandable(world, candidate)) {
                return Optional.of(candidate.toImmutable());
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findStandableInColumn(ServerWorld world, BlockPos origin, int verticalDown, int verticalUp) {
        int topY = world.getBottomY() + world.getHeight();
        int minY = Math.max(world.getBottomY() + 1, origin.getY() - Math.max(0, verticalDown));
        int maxY = Math.min(topY - 2, origin.getY() + Math.max(0, verticalUp));
        int originY = Math.max(minY, Math.min(maxY, origin.getY()));
        int maxDelta = Math.max(originY - minY, maxY - originY);
        for (int delta = 0; delta <= maxDelta; delta++) {
            // Preserve the safer lower-cell tie break, but compare vertical distance before
            // direction. The old two-pass scan searched as many as 128 blocks downward before
            // considering a stand only one block above, so a surface waypoint could resolve into
            // a cave below it even though the obstacle itself was directly jumpable.
            int downY = originY - delta;
            if (downY >= minY) {
                BlockPos candidate = new BlockPos(origin.getX(), downY, origin.getZ());
                if (isStandable(world, candidate)) {
                    return Optional.of(candidate.toImmutable());
                }
            }
            int upY = originY + delta;
            if (delta > 0 && upY <= maxY) {
                BlockPos candidate = new BlockPos(origin.getX(), upY, origin.getZ());
                if (isStandable(world, candidate)) {
                    return Optional.of(candidate.toImmutable());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean compute(ServerWorld world, BlockPos pos) {
        int topY = world.getBottomY() + world.getHeight();
        if (pos.getY() < world.getBottomY() + 1 || pos.getY() >= topY - 1) {
            return false;
        }

        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        // "Standable" is a dry footing contract. A water cell has no collision shape and used to
        // pass the checks below whenever it had a solid lake bed, so A* emitted DROP_DOWN nodes
        // into water. Clientless fake players cannot execute normal swimming travel; NavSafety
        // would lift them back onto the bank and the unchanged path immediately dropped them in
        // again. Dedicated rescue code may traverse water explicitly, ordinary navigation may not.
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        if (!feet.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        if (isDangerous(feet) || isDangerous(head) || isDangerous(below)) {
            return false;
        }
        // NAV-11: Ladders/vines and other climbable blocks only need the bot standing inside them; no support below is required.
        if (feet.isIn(BlockTags.CLIMBABLE)) {
            return true;
        }
        if (below.isAir()) {
            return false;
        }
        return below.getCollisionShape(world, pos.down()).getMax(Direction.Axis.Y) > 0.0D;
    }

    public static boolean isDangerous(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.isIn(FluidTags.LAVA)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.WITHER_ROSE)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.POINTED_DRIPSTONE);
    }

    private record CacheKey(String dimension, long version, BlockPos pos) {
        private CacheKey {
            pos = pos.toImmutable();
        }
    }
}
