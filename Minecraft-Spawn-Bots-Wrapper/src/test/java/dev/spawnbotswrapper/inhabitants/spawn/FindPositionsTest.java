package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.SpawnPlanner.Position;
import dev.spawnbotswrapper.inhabitants.engine.SpawnPlanner.PositionResult;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FEET;
import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FLOOR;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.box;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.planner;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.single;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.snapshot;
import static org.junit.jupiter.api.Assertions.*;

class FindPositionsTest {

    private static PositionResult find(DefaultSpawnPlanner p, StructureSnapshot s, BlockProbe probe, int count, long seed) {
        return p.findPositions(s, probe, count, List.of(), new SplitMix64(seed));
    }

    private static boolean insideAnyBox(Position p, StructureSnapshot s) {
        int x = (int) Math.floor(p.x());
        int z = (int) Math.floor(p.z());
        for (IntBox b : s.sampleBoxes()) {
            if (b.contains(x, (int) p.y(), z)) {
                return true;
            }
        }
        return false;
    }

    private static double distance(Position a, Position b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void assertEveryPositionValid(BlockProbe probe, StructureSnapshot s, List<Position> found, boolean submerged) {
        for (Position p : found) {
            int x = (int) Math.floor(p.x());
            int z = (int) Math.floor(p.z());
            assertEquals(x + 0.5, p.x(), 0.0, "x is a block centre");
            assertEquals(z + 0.5, p.z(), 0.0, "z is a block centre");
            assertEquals(Math.rint(p.y()), p.y(), 0.0, "feet Y is an integer block level");
            assertTrue(WalkRules.standable(probe, x, (int) p.y(), z, submerged), "not standable: " + p);
            assertTrue(insideAnyBox(p, s), "outside every piece: " + p);
            assertTrue(p.yaw() >= 0f && p.yaw() < 360f, "yaw out of range: " + p.yaw());
        }
    }

    private static void assertPairwiseAtLeast(List<Position> a, double min) {
        for (int i = 0; i < a.size(); i++) {
            for (int j = i + 1; j < a.size(); j++) {
                assertTrue(distance(a.get(i), a.get(j)) >= min, "too close: " + a.get(i) + " / " + a.get(j));
            }
        }
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    void villageLikePiecesOnFlatGroundYieldValidSpreadOutPositions() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 79, 79);
        StructureSnapshot village = snapshot(box(0, FLOOR, 0, 79, FLOOR + 10, 79),
                box(10, FLOOR, 10, 18, FLOOR + 7, 18),
                box(30, FLOOR, 10, 36, FLOOR + 5, 16),
                box(50, FLOOR, 50, 52, FLOOR + 3, 52),
                box(0, FLOOR, 30, 79, FLOOR + 2, 31));
        for (long seed = 0; seed < 40; seed++) {
            PositionResult r = find(planner(), village, probe, 6, seed);
            assertEquals(6, r.positions().size(), "seed " + seed);
            assertFalse(r.incompleteBecauseUnloaded());
            assertTrue(r.candidatesTried() >= 6);
            assertEveryPositionValid(probe, village, r.positions(), false);
            assertPairwiseAtLeast(r.positions(), 3.0);
        }
    }

    @Test
    void whenThereAreNoPiecesTheBoundsAreSampled() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 20, 20);
        StructureSnapshot noPieces = snapshot(box(5, FLOOR, 5, 15, FLOOR + 6, 15));
        PositionResult r = find(planner(), noPieces, probe, 3, 1);
        assertEquals(3, r.positions().size());
        for (Position p : r.positions()) {
            assertTrue(p.x() >= 5 && p.x() < 16 && p.z() >= 5 && p.z() < 16, "outside bounds: " + p);
        }
    }

    @Test
    void nonPositiveCountFindsNothingAndReadsNothing() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 9, 9);
        StructureSnapshot s = single(box(0, FLOOR, 0, 9, FLOOR + 5, 9));
        for (int count : new int[]{0, -3}) {
            PositionResult r = find(planner(), s, probe, count, 1);
            assertTrue(r.positions().isEmpty());
            assertFalse(r.incompleteBecauseUnloaded());
            assertEquals(0, r.candidatesTried());
        }
        assertEquals(0, probe.reads());
    }

    @Test
    void feetAreOnTheFloorAndPositionsUseBlockCentres() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 0, 0);
        PositionResult r = find(planner(), single(box(0, FLOOR, 0, 0, FLOOR + 4, 0)), probe, 1, 1);
        assertEquals(1, r.positions().size());
        Position p = r.positions().get(0);
        assertEquals(0.5, p.x());
        assertEquals(FEET, p.y());
        assertEquals(0.5, p.z());
    }

    // ---------------------------------------------------------------- multi-storey

    private static FakeProbe threeStoreyHouse() {
        FakeProbe probe = new FakeProbe();
        probe.floor(10, 10, 20, 20, 63);
        probe.floor(10, 10, 20, 20, 67);
        probe.floor(10, 10, 20, 20, 72);
        return probe;
    }

    @Test
    void everyStoreyOfAHouseIsReachable() {
        FakeProbe probe = threeStoreyHouse();
        StructureSnapshot house = single(box(10, 63, 10, 20, 80, 20));
        Set<Integer> levels = new TreeSet<>();
        for (long seed = 0; seed < 120; seed++) {
            PositionResult r = find(planner(), house, probe, 1, seed);
            assertEquals(1, r.positions().size());
            levels.add((int) r.positions().get(0).y());
            assertEveryPositionValid(probe, house, r.positions(), false);
        }
        assertEquals(Set.of(64, 68, 73), levels, "ground floor, upper floor and roof");
    }

    @Test
    void feetMustBeInsideThePieceSoAHouseRoofOutsideItsBoxIsNeverUsed() {
        FakeProbe probe = threeStoreyHouse();
        StructureSnapshot house = single(box(10, 63, 10, 20, 72, 20));
        Set<Integer> levels = new TreeSet<>();
        for (long seed = 0; seed < 120; seed++) {
            for (Position p : find(planner(), house, probe, 3, seed).positions()) {
                levels.add((int) p.y());
            }
        }
        assertEquals(Set.of(64, 68), levels, "roof feet are at 73, above the box");
    }

    @Test
    void aTowerColumnWithSeveralFloorsFitsOneBotPerFloor() {
        FakeProbe probe = new FakeProbe();
        probe.floor(5, 5, 5, 5, 63).floor(5, 5, 5, 5, 67).floor(5, 5, 5, 5, 72);
        StructureSnapshot tower = single(box(5, 63, 5, 5, 80, 5));
        PositionResult r = find(planner(), tower, probe, 3, 1);
        Set<Integer> levels = new TreeSet<>();
        r.positions().forEach(p -> levels.add((int) p.y()));
        assertEquals(Set.of(64, 68, 73), levels);
    }

    @Test
    void fewerBotsThanStoreysStillSpreadAcrossDifferentStoreysInsteadOfClusteringOnOne() {
        // Two separate columns, each independently offering the same 4 storeys: without the storey-
        // diversity bias, each column's own random level pick is independent, so two bots have a real
        // chance of both landing on (say) the ground floor purely by coincidence.
        FakeProbe probe = new FakeProbe();
        for (int x = 5; x <= 6; x++) {
            probe.floor(x, 5, x, 5, 63).floor(x, 5, x, 5, 67).floor(x, 5, x, 5, 72).floor(x, 5, x, 5, 76);
        }
        StructureSnapshot house = single(box(5, 63, 5, 6, 90, 5));
        for (long seed = 0; seed < 50; seed++) {
            PositionResult r = find(planner(), house, probe, 2, seed);
            assertEquals(2, r.positions().size());
            assertNotEquals(r.positions().get(0).y(), r.positions().get(1).y(),
                    "2 bots across 2 columns of a 4-storey house must not land on the same storey, seed " + seed);
        }
    }

    @Test
    void separationIsMeasuredInThreeDimensions() {
        FakeProbe probe = new FakeProbe();
        probe.floor(5, 5, 5, 5, 63).floor(5, 5, 5, 5, 67);
        StructureSnapshot tower = single(box(5, 63, 5, 5, 80, 5));
        assertEquals(2, find(planner(s -> s.minBotSeparation = 3.0), tower, probe, 2, 1).positions().size(),
                "floors 4 apart satisfy a separation of 3");
        assertEquals(1, find(planner(s -> s.minBotSeparation = 5.0), tower, probe, 2, 1).positions().size(),
                "floors 4 apart do not satisfy a separation of 5");
    }

    // ---------------------------------------------------------------- what counts as standable

    private static FakeProbe column(Cell floor, Cell feet, Cell head) {
        return new FakeProbe().set(5, FLOOR, 5, floor).set(5, FEET, 5, feet).set(5, FEET + 1, 5, head);
    }

    private static StructureSnapshot columnPiece() {
        return single(box(5, FLOOR - 3, 5, 5, FLOOR + 8, 5));
    }

    private static int found(FakeProbe probe, boolean submerged) {
        return find(planner(s -> s.allowSubmerged = submerged), columnPiece(), probe, 4, 3).positions().size();
    }

    @Test
    void aSolidFloorWithTwoFreeBlocksIsValid() {
        assertEquals(1, found(column(Cell.SOLID_STANDABLE, Cell.EMPTY, Cell.EMPTY), false));
    }

    @Test
    void headroomOfExactlyOneBlockIsInvalid() {
        FakeProbe low = new FakeProbe().set(5, FLOOR, 5, Cell.SOLID_STANDABLE).set(5, FEET + 1, 5, Cell.SOLID_OTHER);
        assertEquals(0, found(low, false), "ceiling one block above the feet");
        FakeProbe ok = new FakeProbe().set(5, FLOOR, 5, Cell.SOLID_STANDABLE).set(5, FEET + 2, 5, Cell.SOLID_OTHER);
        assertEquals(1, found(ok, false), "ceiling two blocks above the feet leaves exactly the headroom needed");
    }

    @Test
    void hazardousOrUnstandableFloorsAreRejected() {
        assertEquals(0, found(column(Cell.HAZARD, Cell.EMPTY, Cell.EMPTY), false), "lava");
        assertEquals(0, found(column(Cell.HAZARD, Cell.EMPTY, Cell.EMPTY), true), "lava, even when submerged is allowed");
        assertEquals(0, found(column(Cell.SOLID_HAZARD, Cell.EMPTY, Cell.EMPTY), false), "cactus / magma");
        assertEquals(0, found(column(Cell.WATER, Cell.EMPTY, Cell.EMPTY), false), "water surface");
        assertEquals(0, found(column(Cell.WATER, Cell.WATER, Cell.WATER), true), "water floor is never standable");
        assertEquals(0, found(column(Cell.SOLID_OTHER, Cell.EMPTY, Cell.EMPTY), false), "fence or partial block");
        assertEquals(0, found(column(Cell.EMPTY, Cell.EMPTY, Cell.EMPTY), false), "no floor at all");
    }

    @Test
    void hazardsAndSolidsInsideTheBodyAreRejected() {
        assertEquals(0, found(column(Cell.SOLID_STANDABLE, Cell.HAZARD, Cell.EMPTY), false), "fire at the feet");
        assertEquals(0, found(column(Cell.SOLID_STANDABLE, Cell.HAZARD, Cell.EMPTY), true), "fire, even when submerged is allowed");
        assertEquals(0, found(column(Cell.SOLID_STANDABLE, Cell.EMPTY, Cell.HAZARD), false), "cobweb at the head");
        assertEquals(0, found(column(Cell.SOLID_STANDABLE, Cell.SOLID_HAZARD, Cell.EMPTY), false), "cactus at the feet");
        assertEquals(0, found(column(Cell.SOLID_STANDABLE, Cell.SOLID_OTHER, Cell.EMPTY), false), "a fence at the feet");
    }

    @Test
    void aBlockAtTheHeadIsTheFloorOfTheNextLevelUpNotAValidHeadSpace() {
        FakeProbe probe = column(Cell.SOLID_STANDABLE, Cell.EMPTY, Cell.SOLID_STANDABLE);
        PositionResult r = find(planner(), columnPiece(), probe, 4, 3);
        assertEquals(1, r.positions().size());
        assertEquals(FEET + 2, r.positions().get(0).y(), "stands on top of the block, not inside it");
    }

    @Test
    void submergedStandingNeedsTheFlag() {
        FakeProbe deep = column(Cell.SOLID_STANDABLE, Cell.WATER, Cell.WATER);
        assertEquals(0, found(deep, false));
        assertEquals(1, found(deep, true));
        FakeProbe wading = column(Cell.SOLID_STANDABLE, Cell.WATER, Cell.EMPTY);
        assertEquals(0, found(wading, false));
        assertEquals(1, found(wading, true));
        FakeProbe headUnderOnly = column(Cell.SOLID_STANDABLE, Cell.EMPTY, Cell.WATER);
        assertEquals(0, found(headUnderOnly, false));
        assertEquals(1, found(headUnderOnly, true));
    }

    @Test
    void aWaterBlockAboveTheSeaFloorIsNotAFloor() {
        FakeProbe probe = new FakeProbe().set(5, FLOOR, 5, Cell.WATER).set(5, FLOOR + 1, 5, Cell.WATER);
        assertEquals(0, found(probe, true));
    }

    // ---------------------------------------------------------------- unloaded chunks

    @Test
    void anEntirelyUnloadedStructureIsIncompleteNotEmptyHanded() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 9, 9).unloaded(0, 0, 9, 9);
        PositionResult r = find(planner(), single(box(0, FLOOR, 0, 9, FLOOR + 4, 9)), probe, 2, 1);
        assertTrue(r.positions().isEmpty());
        assertTrue(r.incompleteBecauseUnloaded());
    }

    @Test
    void unloadedColumnsAreSkippedAndReportedWhileLoadedOnesStillYield() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 9, 9).unloaded(5, 0, 9, 9);
        StructureSnapshot s = single(box(0, FLOOR, 0, 9, FLOOR + 4, 9));
        PositionResult r = find(planner(x -> x.minBotSeparation = 1.0), s, probe, 100, 1);
        assertEquals(50, r.positions().size(), "every loaded column of the left half, none of the right");
        assertTrue(r.incompleteBecauseUnloaded());
        for (Position p : r.positions()) {
            assertTrue(p.x() < 5, "placed in an unloaded chunk: " + p);
        }
    }

    @Test
    void incompleteIsFalseWhenTheStructureSimplyHasNoValidFloor() {
        FakeProbe probe = new FakeProbe().fill(0, FLOOR, 0, 9, FLOOR, 9, Cell.HAZARD);
        PositionResult r = find(planner(), single(box(0, FLOOR, 0, 9, FLOOR + 4, 9)), probe, 3, 1);
        assertTrue(r.positions().isEmpty());
        assertFalse(r.incompleteBecauseUnloaded(), "a lava floor is a definite no, not a retry");
    }

    @Test
    void incompleteIsFalseWhenEnoughWasFoundDespiteUnloadedColumns() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 39, 39).unloaded(0, 0, 39, 4);
        PositionResult r = find(planner(), single(box(0, FLOOR, 0, 39, FLOOR + 4, 39)), probe, 2, 1);
        assertEquals(2, r.positions().size());
        assertFalse(r.incompleteBecauseUnloaded());
    }

    @Test
    void aColumnWithAnyUnreadableBlockIsSkippedNotGuessed() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 0, 0);
        probe.unloaded(0, 0, 0, 0);
        assertTrue(find(planner(), single(box(0, FLOOR, 0, 0, FLOOR + 4, 0)), probe, 1, 1).incompleteBecauseUnloaded());
    }

    // ---------------------------------------------------------------- separation, counts, duplicates

    @Test
    void positionsKeepTheConfiguredSeparationFromEachOther() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 19, 19);
        StructureSnapshot s = single(box(0, FLOOR, 0, 19, FLOOR + 4, 19));
        for (long seed = 0; seed < 30; seed++) {
            PositionResult r = find(planner(o -> o.minBotSeparation = 5.0), s, probe, 8, seed);
            assertFalse(r.positions().isEmpty());
            assertPairwiseAtLeast(r.positions(), 5.0);
        }
    }

    @Test
    void positionsKeepTheSeparationFromAlreadyTakenPositions() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 19, 19);
        StructureSnapshot s = single(box(0, FLOOR, 0, 19, FLOOR + 4, 19));
        List<Position> taken = List.of(new Position(10.5, FEET, 10.5, 0), new Position(3.5, FEET, 3.5, 0));
        for (long seed = 0; seed < 30; seed++) {
            PositionResult r = planner(o -> o.minBotSeparation = 6.0)
                    .findPositions(s, probe, 6, taken, new SplitMix64(seed));
            assertFalse(r.positions().isEmpty());
            for (Position p : r.positions()) {
                for (Position t : taken) {
                    assertTrue(distance(p, t) >= 6.0, p + " is too close to " + t);
                }
            }
            assertPairwiseAtLeast(r.positions(), 6.0);
        }
    }

    @Test
    void aTakenPositionInAnotherStoreyDoesNotBlockTheColumn() {
        FakeProbe probe = new FakeProbe().floor(5, 5, 5, 5, 63).floor(5, 5, 5, 5, 67);
        StructureSnapshot tower = single(box(5, 63, 5, 5, 80, 5));
        List<Position> taken = List.of(new Position(5.5, 64, 5.5, 0));
        PositionResult r = planner().findPositions(tower, probe, 2, taken, new SplitMix64(1));
        assertEquals(1, r.positions().size());
        assertEquals(68.0, r.positions().get(0).y());
    }

    @Test
    void askingForMoreThanExistsReturnsExactlyWhatExists() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 2, 0);
        StructureSnapshot corridor = single(box(0, FLOOR, 0, 2, FLOOR + 4, 0));
        PositionResult adjacent = find(planner(o -> o.minBotSeparation = 1.0), corridor, probe, 10, 7);
        assertEquals(3, adjacent.positions().size(), "all three block centres, one block apart");
        Set<Double> xs = new HashSet<>();
        adjacent.positions().forEach(p -> xs.add(p.x()));
        assertEquals(Set.of(0.5, 1.5, 2.5), xs);
        assertFalse(adjacent.incompleteBecauseUnloaded());

        PositionResult wide = find(planner(o -> o.minBotSeparation = 3.0), corridor, probe, 10, 7);
        assertEquals(1, wide.positions().size(), "a 3-block corridor holds one bot when they must be 3 apart");
    }

    @Test
    void neverReturnsTheSamePositionTwiceEvenWithoutSeparation() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 2, 2);
        StructureSnapshot s = single(box(0, FLOOR, 0, 2, FLOOR + 4, 2));
        PositionResult r = find(planner(o -> o.minBotSeparation = 0.0), s, probe, 20, 4);
        assertEquals(9, r.positions().size());
        Set<String> distinct = new HashSet<>();
        r.positions().forEach(p -> distinct.add(p.x() + "," + p.y() + "," + p.z()));
        assertEquals(9, distinct.size());
    }

    @Test
    void anExistingPositionIsNeverDuplicatedEvenWithoutSeparation() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 0, 0);
        StructureSnapshot s = single(box(0, FLOOR, 0, 0, FLOOR + 4, 0));
        List<Position> taken = List.of(new Position(0.5, FEET, 0.5, 90));
        PositionResult r = planner(o -> o.minBotSeparation = 0.0).findPositions(s, probe, 1, taken, new SplitMix64(1));
        assertTrue(r.positions().isEmpty());
    }

    @Test
    void negativeOrNanSeparationBehavesLikeZero() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 1, 1);
        StructureSnapshot s = single(box(0, FLOOR, 0, 1, FLOOR + 4, 1));
        assertEquals(4, find(planner(o -> o.minBotSeparation = -5), s, probe, 9, 1).positions().size());
        assertEquals(4, find(planner(o -> o.minBotSeparation = Double.NaN), s, probe, 9, 1).positions().size());
    }

    @Test
    void duplicatePiecesAreCollapsed() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 1, 0);
        IntBox b = box(0, FLOOR, 0, 1, FLOOR + 4, 0);
        PositionResult r = find(planner(o -> o.minBotSeparation = 1.0), snapshot(b, b, b, b), probe, 10, 1);
        assertEquals(2, r.positions().size());
        assertEquals(2, r.candidatesTried(), "each distinct column is read once");
    }

    // ---------------------------------------------------------------- bounded work

    @Test
    void workIsBoundedByTheAttemptBudgetTimesTheColumnHeight() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 199, 199);
        int height = 28;
        StructureSnapshot big = single(box(0, FLOOR, 0, 199, FLOOR + height - 1, 199));
        int attempts = 80;
        int count = 4;
        PositionResult r = find(planner(o -> o.positionAttemptsPerBot = attempts), big, probe, count, 1);
        assertEquals(count, r.positions().size());
        assertTrue(r.candidatesTried() <= count * attempts);
        assertTrue(probe.reads() <= (long) count * attempts * (height + 2), "reads " + probe.reads());
    }

    @Test
    void anImpossibleStructureStopsAtTheBudgetAndReadsBoundedly() {
        FakeProbe probe = new FakeProbe().fill(0, FLOOR, 0, 199, FLOOR, 199, Cell.HAZARD);
        int height = 20;
        StructureSnapshot big = single(box(0, FLOOR, 0, 199, FLOOR + height - 1, 199));
        PositionResult r = find(planner(o -> o.positionAttemptsPerBot = 30), big, probe, 3, 1);
        assertTrue(r.positions().isEmpty());
        assertEquals(90, r.candidatesTried(), "exactly count * attempts columns, then it gives up");
        assertTrue(probe.reads() <= 90L * (height + 2));
        assertFalse(r.incompleteBecauseUnloaded());
    }

    @Test
    void anUnloadedStructureFailsEachColumnAtTheFirstBlocks() {
        FakeProbe probe = new FakeProbe().unloaded(0, 0, 99, 99);
        PositionResult r = find(planner(o -> o.positionAttemptsPerBot = 10), single(box(0, FLOOR, 0, 99, FLOOR + 50, 99)), probe, 5, 1);
        assertEquals(50, r.candidatesTried());
        assertTrue(probe.reads() <= 3L * r.candidatesTried(), "an unloaded column costs at most one window of reads");
        assertTrue(r.incompleteBecauseUnloaded());
    }

    @Test
    void aSmallStructureIsSearchedExhaustivelyAndNeverBeyondItsColumns() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 3, 3);
        StructureSnapshot s = single(box(0, FLOOR, 0, 3, FLOOR + 4, 3));
        PositionResult r = find(planner(o -> o.positionAttemptsPerBot = 1000), s, probe, 500, 1);
        assertTrue(r.candidatesTried() <= 16, "16 columns exist, tried " + r.candidatesTried());
    }

    @Test
    void neverReadsOutsideTheWorldHeight() {
        FakeProbe probe = new FakeProbe(0, 20);
        probe.floor(0, 0, 0, 0, 0);
        probe.floor(2, 0, 2, 0, 17);
        probe.floor(4, 0, 4, 0, 18);
        probe.floor(6, 0, 6, 0, 19);
        StructureSnapshot s = single(box(0, 0, 0, 6, 20, 0));
        PositionResult r = find(planner(o -> o.minBotSeparation = 1.0), s, probe, 20, 1);
        Set<String> spots = new TreeSet<>();
        r.positions().forEach(p -> spots.add((int) p.x() + "/" + (int) p.y()));
        assertEquals(Set.of("0/1", "2/18"), spots,
                "floor at the very bottom is fine; feet must leave two blocks below the top; nothing above");
        assertEquals(0, probe.outOfRangeReads(), "the probe was asked about a block outside the world");
    }

    // ---------------------------------------------------------------- border

    @Test
    void columnsOutsideTheWorldBorderAreNeverUsed() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 29, 29).border(0, 0, 9, 29);
        StructureSnapshot s = single(box(0, FLOOR, 0, 29, FLOOR + 4, 29));
        for (long seed = 0; seed < 20; seed++) {
            PositionResult r = find(planner(), s, probe, 10, seed);
            assertFalse(r.positions().isEmpty());
            for (Position p : r.positions()) {
                assertTrue(p.x() < 10, "outside the border: " + p);
            }
        }
    }

    // ---------------------------------------------------------------- reachability of small pieces

    @Test
    void aTinyPieceIsFoundEvenNextToAHugeOneThatHasNoFloor() {
        FakeProbe probe = new FakeProbe().floor(300, 300, 300, 300);
        StructureSnapshot s = snapshot(box(0, FLOOR, 0, 300, FLOOR + 5, 300),
                box(0, FLOOR, 0, 299, FLOOR + 5, 299),
                box(300, FLOOR, 300, 300, FLOOR + 5, 300));
        for (long seed = 0; seed < 60; seed++) {
            PositionResult r = find(planner(), s, probe, 1, seed);
            assertEquals(1, r.positions().size(), "seed " + seed);
            assertEquals(300.5, r.positions().get(0).x());
        }
    }

    @Test
    void largerPiecesAreMoreLikelyButSmallOnesStillGetBots() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 29, 29).floor(100, 100, 102, 102);
        StructureSnapshot s = snapshot(box(0, FLOOR, 0, 102, FLOOR + 5, 102),
                box(0, FLOOR, 0, 29, FLOOR + 5, 29),
                box(100, FLOOR, 100, 102, FLOOR + 5, 102));
        int small = 0;
        int big = 0;
        int runs = 600;
        for (long seed = 0; seed < runs; seed++) {
            Position p = find(planner(), s, probe, 1, seed).positions().get(0);
            if (p.x() >= 100) {
                small++;
            } else {
                big++;
            }
        }
        assertTrue(big > runs * 0.6, "the 900-column piece must dominate: " + big);
        assertTrue(small > runs * 0.05, "the 9-column piece must still be reached often: " + small);
        assertTrue(small < runs * 0.35, "but it must not be picked as often as the big one: " + small);
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void sameGeneratorStateGivesTheSameAnswer() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 39, 39);
        StructureSnapshot s = single(box(0, FLOOR, 0, 39, FLOOR + 6, 39));
        for (long seed = 0; seed < 10; seed++) {
            assertEquals(find(planner(), s, probe, 5, seed), find(planner(), s, probe, 5, seed));
        }
    }

    @Test
    void differentGeneratorsGiveDifferentPlacements() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 39, 39);
        StructureSnapshot s = single(box(0, FLOOR, 0, 39, FLOOR + 6, 39));
        Set<String> columns = new HashSet<>();
        Set<Float> yaws = new HashSet<>();
        for (long seed = 0; seed < 30; seed++) {
            Position p = find(planner(), s, probe, 1, seed).positions().get(0);
            columns.add(p.x() + "," + p.z());
            yaws.add(p.yaw());
        }
        assertTrue(columns.size() >= 25, "placements should vary: " + columns.size());
        assertTrue(yaws.size() >= 25, "yaw should vary");
    }

    @Test
    void yawCoversTheWholeCircle() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 39, 39);
        StructureSnapshot s = single(box(0, FLOOR, 0, 39, FLOOR + 6, 39));
        float min = 360;
        float max = -1;
        for (long seed = 0; seed < 400; seed++) {
            float yaw = find(planner(), s, probe, 1, seed).positions().get(0).yaw();
            min = Math.min(min, yaw);
            max = Math.max(max, yaw);
        }
        assertTrue(min < 20 && max > 340 && min >= 0 && max < 360, min + ".." + max);
    }

    @Test
    void optionsAreReadOnEveryCall() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 1, 0);
        StructureSnapshot s = single(box(0, FLOOR, 0, 1, FLOOR + 4, 0));
        InhabitantsConfig.Spawning opts = new InhabitantsConfig.Spawning();
        DefaultSpawnPlanner p = new DefaultSpawnPlanner(() -> opts);
        opts.minBotSeparation = 3.0;
        assertEquals(1, find(p, s, probe, 5, 1).positions().size());
        opts.minBotSeparation = 1.0;
        assertEquals(2, find(p, s, probe, 5, 1).positions().size());
    }

    // ---------------------------------------------------------------- spreading across pieces

    @Test
    void everyPieceGetsABotBeforeAnyPieceGetsASecondOne() {
        FakeProbe probe = new FakeProbe()
                .floor(0, 0, 4, 4).floor(20, 0, 24, 4).floor(40, 0, 44, 4).floor(60, 0, 64, 4);
        StructureSnapshot village = snapshot(box(0, FLOOR, 0, 64, FLOOR + 4, 4),
                box(0, FLOOR, 0, 4, FLOOR + 4, 4), box(20, FLOOR, 0, 24, FLOOR + 4, 4),
                box(40, FLOOR, 0, 44, FLOOR + 4, 4), box(60, FLOOR, 0, 64, FLOOR + 4, 4));
        for (long seed = 0; seed < 40; seed++) {
            PositionResult r = find(planner(o -> o.minBotSeparation = 0.0), village, probe, 4, seed);
            assertEquals(4, r.positions().size(), "seed " + seed);
            Set<Integer> pieceStarts = new TreeSet<>();
            for (Position p : r.positions()) {
                pieceStarts.add(((int) Math.floor(p.x()) / 20) * 20);
            }
            assertEquals(4, pieceStarts.size(), "seed " + seed + ": every piece must hold exactly one of the 4 bots, got " + pieceStarts);
        }
    }

    @Test
    void oncePiecesAreAllUsedFurtherBotsMayShareOne() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 4, 4).floor(20, 0, 24, 4);
        StructureSnapshot s = snapshot(box(0, FLOOR, 0, 24, FLOOR + 4, 4),
                box(0, FLOOR, 0, 4, FLOOR + 4, 4), box(20, FLOOR, 0, 24, FLOOR + 4, 4));
        PositionResult r = find(planner(o -> o.minBotSeparation = 0.0), s, probe, 6, 1);
        assertEquals(6, r.positions().size(), "2 pieces of 25 columns each hold 6 bots easily once sharing is allowed");
        boolean sawFirst = false;
        boolean sawSecond = false;
        for (Position p : r.positions()) {
            if (Math.floor(p.x()) < 20) {
                sawFirst = true;
            } else {
                sawSecond = true;
            }
        }
        assertTrue(sawFirst && sawSecond, "both pieces must be used, not just one");
    }

    @Test
    void aSingleHugePieceSpreadsFarBeyondTheFlatConfiguredSeparation() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 59, 59);
        StructureSnapshot hall = single(box(0, FLOOR, 0, 59, FLOOR + 4, 59));
        // A flat 1-block separation would let bots huddle in one corner; the adaptive floor for 4 bots over
        // a 60x60 room (sqrt(3600/4) * 0.6 = 18.0) must force them apart far more than that.
        for (long seed = 0; seed < 15; seed++) {
            PositionResult r = find(planner(o -> o.minBotSeparation = 1.0), hall, probe, 4, seed);
            assertEquals(4, r.positions().size(), "seed " + seed);
            assertPairwiseAtLeast(r.positions(), 10.0);
        }
    }

    @Test
    void aSingleHugePieceWithOnlyOneBotNeedsNoAdaptiveSeparation() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 59, 59);
        StructureSnapshot hall = single(box(0, FLOOR, 0, 59, FLOOR + 4, 59));
        // count == 1 never has a second bot to separate from; this must not somehow reject every column.
        PositionResult r = find(planner(o -> o.minBotSeparation = 1.0), hall, probe, 1, 1);
        assertEquals(1, r.positions().size());
    }

    @Test
    void anOperatorsWiderSeparationThanTheAdaptiveFloorIsNeverShrunk() {
        FakeProbe probe = new FakeProbe().floor(0, 0, 39, 39);
        StructureSnapshot hall = single(box(0, FLOOR, 0, 39, FLOOR + 4, 39));
        // The adaptive floor for 2 bots here is small (sqrt(1600/2)*0.6 = 17.0); an operator asking for
        // more than that must still get at least what they configured.
        PositionResult r = find(planner(o -> o.minBotSeparation = 25.0), hall, probe, 2, 1);
        assertEquals(2, r.positions().size());
        assertPairwiseAtLeast(r.positions(), 25.0);
    }

    @Test
    void multiplePiecesAreNeverGivenTheSingleBoxSpreadTreatment() {
        // Two separate small pieces, far apart, each too small on its own to satisfy a large single-box
        // adaptive separation: proves the adaptive floor only applies when sampleBoxes() has exactly one box.
        FakeProbe probe = new FakeProbe().floor(0, 0, 2, 2).floor(100, 100, 102, 102);
        StructureSnapshot s = snapshot(box(0, FLOOR, 0, 102, FLOOR + 4, 102),
                box(0, FLOOR, 0, 2, FLOOR + 4, 2), box(100, FLOOR, 100, 102, FLOOR + 4, 102));
        PositionResult r = find(planner(o -> o.minBotSeparation = 1.0), s, probe, 2, 1);
        assertEquals(2, r.positions().size(), "two small, separate pieces must not be starved by single-box spread math");
    }

    // ---------------------------------------------------------------- property: random worlds

    @Test
    void randomWorldsNeverProduceAnInvalidPosition() {
        for (long seed = 0; seed < 300; seed++) {
            SplitMix64 r = new SplitMix64(seed * 7919 + 1);
            FakeProbe probe = new FakeProbe();
            List<IntBox> pieces = new ArrayList<>();
            int n = 1 + r.nextInt(6);
            for (int i = 0; i < n; i++) {
                int x0 = r.nextInt(40);
                int z0 = r.nextInt(40);
                int x1 = x0 + r.nextInt(12);
                int z1 = z0 + r.nextInt(12);
                int y0 = 58 + r.nextInt(8);
                IntBox b = box(x0, y0, z0, x1, y0 + 1 + r.nextInt(12), z1);
                pieces.add(b);
                int floors = 1 + r.nextInt(3);
                for (int f = 0; f < floors; f++) {
                    probe.floor(x0, z0, x1, z1, y0 + f * (3 + r.nextInt(3)));
                }
            }
            for (int i = 0; i < 40; i++) {
                int x = r.nextInt(52);
                int z = r.nextInt(52);
                int y = 58 + r.nextInt(14);
                Cell[] junk = {Cell.SOLID_OTHER, Cell.HAZARD, Cell.WATER, Cell.SOLID_HAZARD, Cell.EMPTY};
                probe.set(x, y, z, junk[r.nextInt(junk.length)]);
            }
            if (r.nextInt(3) == 0) {
                probe.unloaded(r.nextInt(30), r.nextInt(30), 30 + r.nextInt(20), 30 + r.nextInt(20));
            }
            IntBox bounds = pieces.get(0);
            for (IntBox b : pieces) {
                bounds = bounds.union(b);
            }
            StructureSnapshot s = snapshot(bounds, pieces.toArray(new IntBox[0]));
            boolean submerged = r.nextBoolean();
            double sep = r.nextInt(5);
            PositionResult res = planner(o -> {
                o.allowSubmerged = submerged;
                o.minBotSeparation = sep;
            }).findPositions(s, probe, 1 + r.nextInt(8), List.of(), new SplitMix64(seed));
            assertEveryPositionValid(probe, s, res.positions(), submerged);
            assertPairwiseAtLeast(res.positions(), Math.max(sep, 1e-9));
            Set<String> spots = new HashSet<>();
            res.positions().forEach(p -> spots.add(p.x() + "," + p.y() + "," + p.z()));
            assertEquals(res.positions().size(), spots.size(), "duplicates, seed " + seed);
            assertEquals(0, probe.outOfRangeReads());
        }
    }
}
