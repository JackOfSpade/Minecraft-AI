package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.mining.assist.ObservedGraphSearch;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * What the bot has to be shown, cell by cell, before a pillar may start a walk away: the walk to the pillar's foot is proved by
 * a search over cardinal steps, so the cells it is shown have to be neighbours of each other.
 */
class PillarWalkLanesTest {
    private static ObservedGraphSearch.Environment standingOn(Set<BlockPos> cells) {
        return new ObservedGraphSearch.Environment() {
            @Override
            public boolean isStandable(BlockPos pos) {
                return cells.contains(pos);
            }

            @Override
            public boolean isForbidden(BlockPos pos) {
                return false;
            }

            @Override
            public boolean isAdjacentToWater(BlockPos pos) {
                return false;
            }
        };
    }

    @Test
    void aFootExactlyDiagonalFromTheBotIsWalkedOverTheLanesAlone() {
        // The real session: the bot at (-3, 0, -4), the foot of the column at (5, 0, 4), eight cells on each axis.
        BlockPos bot = new BlockPos(-3, 0, -4);
        BlockPos foot = new BlockPos(5, 0, 4);
        Set<BlockPos> shown = new HashSet<>(PillarWalkLanes.stances(bot, foot));
        shown.add(bot);

        List<BlockPos> walk = ObservedGraphSearch.path(bot, foot, standingOn(shown));

        assertNotNull(walk, "the lanes alone prove a cardinal walk to a foot that lies exactly diagonal");
        assertEquals(17, walk.size(), "sixteen steps: eight on each axis");
    }

    @Test
    void theLanesAreTheTwoSidesOfTheRectangleBetweenTheBotAndTheFoot() {
        BlockPos bot = new BlockPos(-3, 0, -4);
        BlockPos foot = new BlockPos(5, 0, 4);

        List<BlockPos> lanes = PillarWalkLanes.stances(bot, foot);

        assertEquals(31, lanes.size(), "two lanes of sixteen cells that share the foot");
        assertEquals(31, new HashSet<>(lanes).size(), "each cell once");
        assertTrue(lanes.contains(new BlockPos(5, 0, -4)) && lanes.contains(new BlockPos(-3, 0, 4)),
                "both corners of the rectangle");
        assertFalse(lanes.contains(bot), "the bot's own cell is known to it already");
        assertTrue(lanes.contains(foot));
        assertEquals(new BlockPos(-2, 0, -4), lanes.get(0), "the first lane is walked along x first");
        assertEquals(foot, lanes.get(15), "and ends on the foot");
    }

    @Test
    void aFootOnOneAxisHasOneLaneAndTheFootAtTheBotsOwnCellNeedsNone() {
        BlockPos bot = new BlockPos(4, 70, 9);

        List<BlockPos> lane = PillarWalkLanes.stances(bot, new BlockPos(4, 70, 14));
        assertEquals(List.of(new BlockPos(4, 70, 10), new BlockPos(4, 70, 11), new BlockPos(4, 70, 12),
                new BlockPos(4, 70, 13), new BlockPos(4, 70, 14)), lane);

        assertEquals(List.of(), PillarWalkLanes.stances(bot, bot));
    }

    @Test
    void everyStanceIsAtTheLevelOfTheFoot() {
        List<BlockPos> lanes = PillarWalkLanes.stances(new BlockPos(0, 64, 0), new BlockPos(-3, 64, 2));

        assertEquals(9, lanes.size(), "two lanes of five cells that share the foot");
        assertTrue(lanes.stream().allMatch(cell -> cell.getY() == 64));
    }

    @Test
    void aWalkWithAGapInItsLanesIsNotProved() {
        BlockPos bot = new BlockPos(0, 0, 0);
        BlockPos foot = new BlockPos(3, 0, 3);
        Set<BlockPos> shown = new HashSet<>(PillarWalkLanes.stances(bot, foot));
        shown.add(bot);
        // A wall (or anything the bot does not see) across both lanes.
        shown.remove(new BlockPos(3, 0, 0));
        shown.remove(new BlockPos(0, 0, 3));

        assertNull(ObservedGraphSearch.path(bot, foot, standingOn(shown)),
                "a cell that is not shown standable stays a gap: the lanes read nothing the eyes did not reach");
    }
}
