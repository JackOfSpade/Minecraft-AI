package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the greedy torch selection that replaced LightAreaTask's old "queue the maxTorches
 * nearest placeable cells" scan -- the behaviour that clustered every torch onto the 4 neighbours
 * of the bot's feet because a live light re-read hadn't caught up yet.
 */
final class TorchPlacementPlannerTest {
    private static final int THRESHOLD = 8;

    @Test
    void predictedLightUsesTheNearestPlacedTorch() {
        BlockPos cell = new BlockPos(0, 64, 0);
        BlockPos near = new BlockPos(2, 64, 0);
        BlockPos far = new BlockPos(0, 64, 10);

        int predicted = TorchPlacementPlanner.predictedBlockLight(cell, 0, List.of(far, near));

        // near is 2 blocks away -> 14 - 2 = 12; far is 10 away -> 14 - 10 = 4; world light 0.
        assertEquals(12, predicted);
    }

    @Test
    void flatSeventeenBySeventeenDarkFloorNeverPlacesOrthogonallyAdjacentTorches() {
        Set<BlockPos> cells = darkSquare(17);
        Map<BlockPos, Integer> worldLight = zeroLight(cells);
        BlockPos bot = new BlockPos(0, 64, 0);
        List<BlockPos> placed = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            BlockPos next = TorchPlacementPlanner.chooseNext(cells, worldLight, placed, bot, THRESHOLD);
            if (next == null) {
                break;
            }
            for (BlockPos existing : placed) {
                assertTrue(TorchPlacementPlanner.manhattanDistance(existing, next) > 1,
                        "new torch " + next + " is orthogonally adjacent (or on) an existing torch " + existing);
            }
            placed.add(next);
        }
        assertTrue(placed.size() >= 2, "expected more than one torch to be needed for a 17x17 floor");
    }

    @Test
    void everyPlacedTorchLightsAtLeastOnePreviouslyDarkCell() {
        Set<BlockPos> cells = darkSquare(17);
        Map<BlockPos, Integer> worldLight = zeroLight(cells);
        BlockPos bot = new BlockPos(0, 64, 0);
        List<BlockPos> placed = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            Set<BlockPos> darkBefore = darkCells(cells, worldLight, placed);
            BlockPos next = TorchPlacementPlanner.chooseNext(cells, worldLight, placed, bot, THRESHOLD);
            if (next == null) {
                break;
            }
            placed.add(next);
            Set<BlockPos> darkAfter = darkCells(cells, worldLight, placed);
            darkBefore.removeAll(darkAfter);
            assertTrue(darkBefore.size() > 0,
                    "torch placed at " + next + " lit no previously-dark cell");
        }
    }

    @Test
    void smallThreeByThreeDarkPocketGetsExactlyOneTorch() {
        Set<BlockPos> cells = darkSquare(3);
        Map<BlockPos, Integer> worldLight = zeroLight(cells);
        BlockPos bot = new BlockPos(0, 64, 0);
        List<BlockPos> placed = new ArrayList<>();

        BlockPos first = TorchPlacementPlanner.chooseNext(cells, worldLight, placed, bot, THRESHOLD);
        assertTrue(first != null, "expected the pocket to need a torch");
        placed.add(first);

        BlockPos second = TorchPlacementPlanner.chooseNext(cells, worldLight, placed, bot, THRESHOLD);
        assertNull(second, "a 3x3 pocket should be fully lit by a single torch");
    }

    @Test
    void alreadyLitAreaGetsNoTorch() {
        Set<BlockPos> cells = darkSquare(5);
        Map<BlockPos, Integer> worldLight = new HashMap<>();
        for (BlockPos cell : cells) {
            worldLight.put(cell, 15); // already fully lit
        }

        BlockPos chosen = TorchPlacementPlanner.chooseNext(
                cells, worldLight, List.of(), new BlockPos(0, 64, 0), THRESHOLD);

        assertNull(chosen);
    }

    @Test
    void neverChoosesACellAlreadyPredictedLit() {
        Set<BlockPos> cells = darkSquare(5);
        Map<BlockPos, Integer> worldLight = zeroLight(cells);
        BlockPos torch = new BlockPos(0, 64, 0);

        // Placing directly on top of an already-lit torch cell must never be chosen again.
        BlockPos chosen = TorchPlacementPlanner.chooseNext(
                Set.of(torch), worldLight, List.of(torch), torch, THRESHOLD);

        assertNull(chosen);
    }

    private static Set<BlockPos> darkSquare(int size) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        int half = size / 2;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                cells.add(new BlockPos(x, 64, z));
            }
        }
        return cells;
    }

    @Test
    void surfaceCellsAreDroppedFromTheLightingPoolWhenTheyArePassedThrough() {
        BlockPos indoors = new BlockPos(0, 60, 0);
        BlockPos mouth = new BlockPos(1, 64, 0);
        BlockPos indoorsToo = new BlockPos(2, 60, 0);
        Set<BlockPos> pool = new LinkedHashSet<>(List.of(indoors, mouth, indoorsToo));

        Set<BlockPos> kept = TorchPlacementPlanner.withoutSurfaceCells(pool, cell -> cell.getY() >= 64);

        assertEquals(List.of(indoors, indoorsToo), new ArrayList<>(kept), "order kept, surface cell dropped");
        assertEquals(3, pool.size(), "the caller's pool is left alone");
        assertTrue(TorchPlacementPlanner.withoutSurfaceCells(new LinkedHashSet<>(List.of(mouth)), cell -> true).isEmpty());
    }

    private static Map<BlockPos, Integer> zeroLight(Set<BlockPos> cells) {
        Map<BlockPos, Integer> light = new HashMap<>();
        for (BlockPos cell : cells) {
            light.put(cell, 0);
        }
        return light;
    }

    private static Set<BlockPos> darkCells(Set<BlockPos> cells, Map<BlockPos, Integer> worldLight,
                                            List<BlockPos> placed) {
        Set<BlockPos> dark = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            if (TorchPlacementPlanner.predictedBlockLight(cell, worldLight.getOrDefault(cell, 0), placed) < THRESHOLD) {
                dark.add(cell);
            }
        }
        return dark;
    }
}
