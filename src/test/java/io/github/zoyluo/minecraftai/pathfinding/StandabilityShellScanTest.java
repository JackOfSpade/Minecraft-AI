package io.github.zoyluo.minecraftai.pathfinding;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shell-expanding window scan (early exit once the best hit beats the next shell's minimum
 * distance) must return exactly what the original full-window scan returned.
 */
class StandabilityShellScanTest {
    /** The pre-shell algorithm, verbatim: every cell of the window, dx-major. */
    private static Optional<BlockPos> bruteForce(BlockPos origin, int r, int down, int up, Predicate<BlockPos> standable) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        int bestHorizontalSq = Integer.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -down; dy <= up; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!standable.test(candidate)) {
                        continue;
                    }
                    double distSq = candidate.getSquaredDistance(origin);
                    int horizontalSq = dx * dx + dz * dz;
                    boolean better = distSq < bestDistSq
                            || (distSq == bestDistSq && best != null
                            && (horizontalSq < bestHorizontalSq
                            || (horizontalSq == bestHorizontalSq && candidate.getY() < best.getY())));
                    if (better) {
                        best = candidate.toImmutable();
                        bestDistSq = distSq;
                        bestHorizontalSq = horizontalSq;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Test
    void matchesTheFullWindowScanOnRandomTerrain() {
        Random random = new Random(20260929L);
        BlockPos origin = new BlockPos(100, 64, -40);
        for (int trial = 0; trial < 400; trial++) {
            double density = trial % 4 == 0 ? 0.002D : trial % 4 == 1 ? 0.02D : trial % 4 == 2 ? 0.2D : 0.7D;
            long seed = random.nextLong();
            Predicate<BlockPos> standable = pos -> new Random(seed ^ pos.asLong() * 0x9E3779B97F4A7C15L).nextDouble() < density;
            int r = 1 + random.nextInt(8);
            int down = random.nextInt(6);
            int up = random.nextInt(6);
            Optional<BlockPos> expected = bruteForce(origin, r, down, up, standable);
            Optional<BlockPos> actual = Standability.scanWindowInShells(origin, r, down, up, standable);
            // Cells of a fully tied (distance, horizontal distance, height) set are interchangeable
            // for callers; the two scans may only differ inside such a tie.
            assertEquals(expected.isPresent(), actual.isPresent(), "trial " + trial);
            if (expected.isPresent()) {
                BlockPos e = expected.get();
                BlockPos a = actual.get();
                assertEquals(e.getSquaredDistance(origin), a.getSquaredDistance(origin), "trial " + trial);
                assertEquals(horizontalSq(e, origin), horizontalSq(a, origin), "trial " + trial);
                assertEquals(e.getY(), a.getY(), "trial " + trial);
            }
        }
    }

    @Test
    void stopsEarlyOnOrdinaryGround() {
        BlockPos origin = new BlockPos(0, 64, 0);
        int[] probes = {0};
        Optional<BlockPos> hit = Standability.scanWindowInShells(origin, 8, 4, 3, pos -> {
            probes[0]++;
            return pos.getY() == 64;
        });
        assertTrue(hit.isPresent());
        assertEquals(origin, hit.get());
        assertTrue(probes[0] < 300, "the full window is 17*17*8 = 2312 probes; the shell scan needs a handful: " + probes[0]);
    }

    private static int horizontalSq(BlockPos pos, BlockPos origin) {
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }
}
