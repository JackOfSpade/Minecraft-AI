package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.mining.assist.ObservedGraphSearch.Environment;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedGraphSearchTest {
    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** A standable flat plane at a fixed Y, forbidding nothing and never adjacent to water, unless overridden. */
    private static final class FakeEnv implements Environment {
        private final int floorY;
        private final Set<BlockPos> extraStandable = new HashSet<>();
        private final Set<BlockPos> forbidden = new HashSet<>();
        private final Set<BlockPos> waterAdjacent = new HashSet<>();
        private boolean planeStandable = true;

        FakeEnv(int floorY) {
            this.floorY = floorY;
        }

        @Override
        public boolean isStandable(BlockPos pos) {
            if (extraStandable.contains(pos)) {
                return true;
            }
            return planeStandable && pos.getY() == floorY;
        }

        @Override
        public boolean isForbidden(BlockPos pos) {
            return forbidden.contains(pos);
        }

        @Override
        public boolean isAdjacentToWater(BlockPos pos) {
            return waterAdjacent.contains(pos);
        }
    }

    // ---- UNKNOWN cells never in the graph (section 9's named property) -----------------------------

    @Test
    void unknownCellsNeverInTheGraph() {
        FakeEnv env = new FakeEnv(64);
        env.planeStandable = false; // every cell answers isStandable == false, as an UNKNOWN cell must
        BlockPos source = at(0, 64, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        assertEquals(Set.of(source), reached.keySet());
        assertEquals(0.0D, reached.get(source));
    }

    @Test
    void sourceIsIncludedAtZeroCostEvenIfItWouldNotOtherwiseBeStandable() {
        FakeEnv env = new FakeEnv(64);
        env.planeStandable = false;
        BlockPos source = at(5, 70, 5);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        assertEquals(0.0D, reached.get(source));
    }

    // ---- move costs (design 5.4 CostModel: 0.5 + 0.3*fall) ------------------------------------------

    @Test
    void walkingOneStepCostsTheBaseMoveCost() {
        FakeEnv env = new FakeEnv(64);
        BlockPos source = at(0, 64, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        assertEquals(ObservedGraphSearch.BASE_MOVE_COST, reached.get(at(1, 64, 0)), 1.0e-9D);
        assertEquals(ObservedGraphSearch.BASE_MOVE_COST, reached.get(at(0, 64, 1)), 1.0e-9D);
    }

    @Test
    void steppingUpOneCostsTheBaseMoveCostWithNoFallTerm() {
        FakeEnv env = new FakeEnv(64);
        env.extraStandable.add(at(1, 66, 0)); // one block higher than the plane
        BlockPos source = at(0, 65, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        assertEquals(ObservedGraphSearch.BASE_MOVE_COST, reached.get(at(1, 66, 0)), 1.0e-9D);
    }

    @Test
    void droppingThreeCostsTheBasePlusFallTerm() {
        FakeEnv env = new FakeEnv(62); // three below the source's level
        env.extraStandable.add(at(0, 65, 0));
        BlockPos source = at(0, 65, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        double expected = ObservedGraphSearch.BASE_MOVE_COST + ObservedGraphSearch.FALL_COST_PER_BLOCK * 3;
        assertEquals(expected, reached.get(at(1, 62, 0)), 1.0e-9D);
    }

    @Test
    void dropsBeyondMaxDropAreNeverReached() {
        FakeEnv env = new FakeEnv(65 - ObservedGraphSearch.MAX_DROP - 1);
        env.extraStandable.add(at(0, 65, 0));
        BlockPos source = at(0, 65, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        assertFalse(reached.containsKey(at(1, 65 - ObservedGraphSearch.MAX_DROP - 1, 0)));
    }

    // ---- forbidden cells (lava clearance / trap) ----------------------------------------------------

    @Test
    void forbiddenCellsAreNeverEntered() {
        FakeEnv env = new FakeEnv(64);
        env.forbidden.add(at(1, 64, 0));
        BlockPos source = at(0, 64, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        assertFalse(reached.containsKey(at(1, 64, 0)));
        // The other three neighbours are still reachable: forbidding one cell does not stop the search.
        assertTrue(reached.containsKey(at(-1, 64, 0)));
        assertTrue(reached.containsKey(at(0, 64, 1)));
        assertTrue(reached.containsKey(at(0, 64, -1)));
    }

    // ---- water adjacency penalty ---------------------------------------------------------------------

    @Test
    void waterAdjacentCellsPayThePenalty() {
        FakeEnv env = new FakeEnv(64);
        env.waterAdjacent.add(at(1, 64, 0));
        BlockPos source = at(0, 64, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        double expected = ObservedGraphSearch.BASE_MOVE_COST + ObservedGraphSearch.WATER_ADJACENCY_PENALTY;
        assertEquals(expected, reached.get(at(1, 64, 0)), 1.0e-9D);
    }

    // ---- Dijkstra correctness: the cheaper of two routes wins ----------------------------------------

    @Test
    void theCheaperOfTwoRoutesToTheSameCellWins() {
        FakeEnv env = new FakeEnv(64);
        // (1,64,1) is reachable two ways: source -> (1,64,0) [water-adjacent, expensive] -> (1,64,1),
        // or source -> (0,64,1) [plain] -> (1,64,1). Dijkstra must settle on the cheaper route.
        env.waterAdjacent.add(at(1, 64, 0));
        BlockPos source = at(0, 64, 0);
        BlockPos target = at(1, 64, 1);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        double expensiveRoute = ObservedGraphSearch.BASE_MOVE_COST + ObservedGraphSearch.WATER_ADJACENCY_PENALTY
                + ObservedGraphSearch.BASE_MOVE_COST;
        double cheapRoute = 2 * ObservedGraphSearch.BASE_MOVE_COST;
        assertTrue(cheapRoute < expensiveRoute, "test setup sanity check");
        assertEquals(cheapRoute, reached.get(target), 1.0e-9D);
    }

    // ---- node cap ---------------------------------------------------------------------------------

    @Test
    void searchNeverExpandsPastTheNodeCapOnAnUnboundedOpenPlane() {
        FakeEnv env = new FakeEnv(64);
        BlockPos source = at(0, 64, 0);

        Map<BlockPos, Double> reached = ObservedGraphSearch.search(source, env);

        // Every reached cell is a cell the search actually expanded from or a frontier neighbour of one;
        // either way the visited set can never run away on an infinite open plane.
        assertTrue(reached.size() <= 4 * ObservedGraphSearch.NODE_CAP + 1);
        assertFalse(reached.isEmpty());
    }

    // ---- null handling ------------------------------------------------------------------------------

    @Test
    void nullArgumentsThrow() {
        FakeEnv env = new FakeEnv(64);
        assertThrows(NullPointerException.class, () -> ObservedGraphSearch.search(null, env));
        assertThrows(NullPointerException.class, () -> ObservedGraphSearch.search(at(0, 64, 0), null));
    }

    @Test
    void searchOrderedIsSortedByCostAscending() {
        FakeEnv env = new FakeEnv(64);
        BlockPos source = at(0, 64, 0);

        var ordered = ObservedGraphSearch.searchOrdered(source, env);

        assertFalse(ordered.isEmpty());
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ordered.get(i - 1).cost() <= ordered.get(i).cost());
        }
        assertEquals(source, ordered.get(0).pos());
    }

    // ---- path (waypoint execution needs the actual route, not just its cost) ------------------------

    @Test
    void pathReturnsTheCheapestRouteInOrderFromSourceToTarget() {
        FakeEnv env = new FakeEnv(64);
        // Same fixture as theCheaperOfTwoRoutesToTheSameCellWins: the direct route through the
        // water-adjacent cell is more expensive than the one-cell detour, so the reconstructed route
        // must take the cheap detour through (0,64,1), not the expensive direct neighbour (1,64,0).
        env.waterAdjacent.add(at(1, 64, 0));
        BlockPos source = at(0, 64, 0);
        BlockPos target = at(1, 64, 1);

        List<BlockPos> route = ObservedGraphSearch.path(source, target, env);

        assertEquals(List.of(source, at(0, 64, 1), at(1, 64, 1)), route);
    }

    @Test
    void pathReturnsNullWhenTargetIsUnreachable() {
        FakeEnv env = new FakeEnv(64);
        // Same fixture as forbiddenCellsAreNeverEntered: the target itself is forbidden, so no route
        // (direct or otherwise) may ever enter it.
        env.forbidden.add(at(1, 64, 0));
        BlockPos source = at(0, 64, 0);

        List<BlockPos> route = ObservedGraphSearch.path(source, at(1, 64, 0), env);

        assertNull(route);
    }
}
