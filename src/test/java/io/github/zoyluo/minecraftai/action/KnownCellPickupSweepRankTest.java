package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The cell choice of the pickup sweep around a break cell the bot cannot see into (a log high in a
 * canopy): it may only name cells the bot can see to be standable, and must stay cheap once none is left.
 */
class KnownCellPickupSweepRankTest {
    private static final BlockPos ORIGIN = new BlockPos(39, 119, -19);
    private static final BlockPos BOT = new BlockPos(39, 114, -19);

    @Test
    void cellsTheBotCannotSeeAreNeverNamedAndNeverRead() {
        AtomicInteger asked = new AtomicInteger();
        Set<BlockPos> seenStands = Set.of(ORIGIN.east(), ORIGIN.below().west());

        // The predicate is what the sweep composes from "in view" and "standable"; a cell that fails the
        // first part must not reach the second, so a hidden cell's state is never consulted.
        List<BlockPos> ranked = KnownCellPickupSweep.rank(ORIGIN, BOT, new HashSet<>(), cell -> {
            asked.incrementAndGet();
            return seenStands.contains(cell);
        });

        assertEquals(Set.copyOf(seenStands), Set.copyOf(ranked));
        assertEquals(5 * 5 * 3, asked.get(), "every candidate cell is asked exactly once");
    }

    @Test
    void ranksNearestToTheBreakCellFirstAndTheBotBreaksTies() {
        BlockPos adjacentNearBot = ORIGIN.north();
        BlockPos adjacentFarFromBot = ORIGIN.above();
        BlockPos farther = ORIGIN.east(2);
        Set<BlockPos> usable = Set.of(farther, adjacentFarFromBot, adjacentNearBot);

        List<BlockPos> ranked = KnownCellPickupSweep.rank(ORIGIN, BOT, new HashSet<>(), usable::contains);

        assertEquals(List.of(adjacentNearBot, adjacentFarFromBot, farther), ranked);
    }

    @Test
    void aSweepAroundTheCellAnItemRestsInReachesTheEdgeOfTheHoleThatTheBreakCellSweepNeverDoes() {
        // A log felled ten up over a one-block hole: its drop comes to rest in the hole, and the floor of the hole is seen
        // only from its edge (a ray over the near lip meets the far wall), so the cell it rests in is no stand yet.
        BlockPos hole = new BlockPos(7, -1, 5);
        BlockPos breakCell = hole.above(11);
        BlockPos nearBot = new BlockPos(5, 0, 6);
        BlockPos edgeBesideBot = new BlockPos(6, 0, 5);
        Set<BlockPos> floorSeenFromHere = new HashSet<>();
        for (int x = 3; x <= 9; x++) {
            for (int z = 3; z <= 9; z++) {
                if (x != hole.getX() || z != hole.getZ()) {
                    floorSeenFromHere.add(new BlockPos(x, 0, z));
                }
            }
        }

        List<BlockPos> aroundTheBreak = KnownCellPickupSweep.rank(breakCell, nearBot, new HashSet<>(), floorSeenFromHere::contains);
        List<BlockPos> aroundTheItem = KnownCellPickupSweep.rank(hole, nearBot, new HashSet<>(), floorSeenFromHere::contains);

        assertEquals(List.of(), aroundTheBreak, "no stand lies within two cells of a break cell eleven up");
        assertFalse(aroundTheItem.contains(hole), "the hole's own cell is not named: it is not seen to be a stand");
        assertEquals(edgeBesideBot, aroundTheItem.get(0),
                "the edge cell right beside the hole, nearest the bot, comes first: from there the hole's floor is in view");
    }

    @Test
    void visitedCellsAreNotAskedAgain() {
        Set<BlockPos> visited = new HashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                visited.add(ORIGIN.offset(dx, 0, dz));
            }
        }
        AtomicInteger asked = new AtomicInteger();

        List<BlockPos> ranked = KnownCellPickupSweep.rank(ORIGIN, BOT, visited, cell -> {
            asked.incrementAndGet();
            return true;
        });

        assertEquals(50, ranked.size(), "the two other layers remain");
        assertEquals(50, asked.get());
        assertFalse(ranked.stream().anyMatch(visited::contains));
    }

    @Test
    void aSweepWithNoCellLeftRanksOncePerStanceNotOncePerTick() {
        StanceRankedQueue queue = new StanceRankedQueue();
        AtomicInteger rankings = new AtomicInteger();

        // Every tick of the pickup window asks again; the bot has not moved and sees no standable cell.
        for (int tick = 0; tick < 200; tick++) {
            assertNull(queue.poll(BOT, () -> {
                rankings.incrementAndGet();
                return List.of();
            }));
        }

        assertEquals(1, rankings.get(), "two hundred ticks at one stance cost one ranking, not two hundred");
    }

    @Test
    void movingToANewStanceRanksAgainAndAStayingBotKeepsItsRemainingCells() {
        StanceRankedQueue queue = new StanceRankedQueue();
        AtomicInteger rankings = new AtomicInteger();
        List<BlockPos> cells = List.of(ORIGIN.east(), ORIGIN.west());

        assertEquals(ORIGIN.east(), queue.poll(BOT, () -> {
            rankings.incrementAndGet();
            return cells;
        }));
        assertEquals(ORIGIN.west(), queue.poll(BOT, () -> {
            rankings.incrementAndGet();
            return cells;
        }), "the next cell comes from the same ranking while the bot stays put");
        assertEquals(1, rankings.get());

        BlockPos moved = BOT.east();
        assertEquals(ORIGIN.east(), queue.poll(moved, () -> {
            rankings.incrementAndGet();
            return cells;
        }), "a new stance sees different cells, so it ranks again");
        assertEquals(2, rankings.get());
        assertNotNull(queue.poll(moved, () -> List.of()), "the second cell of the new ranking is still queued");
    }
}
