package io.github.zoyluo.minecraftai.task;

import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Pure, world-free torch placement selection used by {@link LightAreaTask}.
 *
 * <p>LightAreaTask used to queue the maxTorches NEAREST placeable cells and re-check each one
 * against a live world light read just before placing it. At night/underground every candidate
 * reads block light 0, and placements happen only ~2 ticks apart -- before the light engine has
 * propagated the previous torch's light -- so every neighbour still read dark and the task simply
 * clustered every torch onto the 4 cells orthogonally adjacent to the bot's feet.
 *
 * <p>This planner instead predicts the light a torch contributes rather than trusting a live read:
 * {@code predictedBlockLight(cell) = max(worldBlockLight, max over placed torches of
 * (TORCH_LUMINANCE - manhattanDistance(torch, cell)))}. This mirrors vanilla torch luminance (14,
 * -1 per block step) but is a conservative approximation that ignores occlusion -- a wall between
 * the torch and the cell would actually block more light than this predicts, so this may in rare
 * cases treat a cell as lit when a real light update would not. That is an accepted simplification:
 * it can only make the planner UNDER-light a cell (place a torch that is not actually needed
 * elsewhere), never reproduce the old cluster bug, because a cell is only skipped as "already lit"
 * once a torch has actually been chosen near it.
 */
final class TorchPlacementPlanner {
    static final int TORCH_LUMINANCE = 14;

    private TorchPlacementPlanner() {
    }

    static int manhattanDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
    }

    /** max(world block light at cell, max over torches placed by this task of (14 - manhattanDistance)). */
    static int predictedBlockLight(BlockPos cell, int worldBlockLight, Collection<BlockPos> placedTorches) {
        int predicted = worldBlockLight;
        for (BlockPos torch : placedTorches) {
            int fromTorch = TORCH_LUMINANCE - manhattanDistance(torch, cell);
            if (fromTorch > predicted) {
                predicted = fromTorch;
            }
        }
        return predicted;
    }

    /**
     * Chooses one torch placement, greedily. {@code cells} is the fixed pool of placeable /
     * dark-spawnable floor cells (precomputed once by the caller; this method does not mutate or
     * re-derive it). Among cells whose predicted light is still below {@code threshold}, picks the
     * one that would bring the most currently-dark cells in {@code cells} up to {@code threshold};
     * ties break by darkness (lowest predicted light at the candidate itself), then by distance to
     * {@code botPos}.
     *
     * @return the chosen cell, or {@code null} if no remaining candidate would light any remaining
     *         dark cell (either every cell is already predicted lit, or placing anywhere would help
     *         nothing).
     */
    static BlockPos chooseNext(Set<BlockPos> cells,
                                Map<BlockPos, Integer> worldBlockLight,
                                Collection<BlockPos> placedTorches,
                                BlockPos botPos,
                                int threshold) {
        BlockPos best = null;
        int bestNewlyLit = 0;
        int bestDarkness = Integer.MAX_VALUE;
        long bestDistSq = Long.MAX_VALUE;
        for (BlockPos candidate : cells) {
            int candidateLight = predictedBlockLight(
                    candidate, worldBlockLight.getOrDefault(candidate, 0), placedTorches);
            if (candidateLight >= threshold) {
                continue; // never place a torch at a cell whose predicted light is already lit
            }
            int newlyLit = 0;
            for (BlockPos cell : cells) {
                int before = predictedBlockLight(cell, worldBlockLight.getOrDefault(cell, 0), placedTorches);
                if (before >= threshold) {
                    continue; // already lit by an earlier decision; not a target of this comparison
                }
                int fromCandidate = TORCH_LUMINANCE - manhattanDistance(candidate, cell);
                if (Math.max(before, fromCandidate) >= threshold) {
                    newlyLit++;
                }
            }
            if (newlyLit == 0) {
                continue;
            }
            long distSq = squaredDistance(candidate, botPos);
            boolean better = newlyLit > bestNewlyLit
                    || (newlyLit == bestNewlyLit && candidateLight < bestDarkness)
                    || (newlyLit == bestNewlyLit && candidateLight == bestDarkness && distSq < bestDistSq);
            if (better) {
                best = candidate;
                bestNewlyLit = newlyLit;
                bestDarkness = candidateLight;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private static long squaredDistance(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
