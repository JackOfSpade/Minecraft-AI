package io.github.zoyluo.minecraftai.task;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stair OreDig digs up to an ore that hangs out of reach, played out cell by cell on a grid of
 * solid rock: whatever the ore's offset and height, the stair must end beside the ore at head
 * height, every tread must have a floor that was never dug, and the cell under the ore must stay
 * solid so that it holds the ore's drop.
 */
class OreClimbTest {
    private static final int ORE_Y = 20;

    /** The break envelope OreDig mines from (the geometry of its recoverable pose, without the reach test); the ore is at (0, ORE_Y, 0). */
    private static boolean atWorkPose(int rx, int rz, int v) {
        return OreDigTask.isRecoverableBreakPose(new BlockPos(rx, ORE_Y - v, rz), new BlockPos(0, ORE_Y, 0));
    }

    @Test
    void theRingIsWalkedClockwiseAndClosesAfterEightSteps() {
        // North, north-east, east, south-east, south, south-west, west, north-west, seen from above.
        int[][] ring = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
        for (int i = 0; i < ring.length; i++) {
            int[] here = ring[i];
            int[] next = ring[(i + 1) % ring.length];
            List<OreClimb.Move> moves = OreClimb.moves(here[0], here[1], 9);
            assertEquals(1, moves.size(), "one way round from " + i);
            Direction direction = moves.get(0).direction();
            assertEquals(next[0], here[0] + direction.getStepX(), "ring cell " + i + " x");
            assertEquals(next[1], here[1] + direction.getStepZ(), "ring cell " + i + " z");
            assertTrue(moves.get(0).rise(), "a ring step gains a block while the ore is far above");
        }
    }

    @Test
    void aLevelTunnelHeadsForTheColumnAlongTheLongerAxis() {
        assertEquals(Direction.WEST, OreClimb.moves(5, 2, 9).get(0).direction());
        assertEquals(Direction.EAST, OreClimb.moves(-5, 2, 9).get(0).direction());
        assertEquals(Direction.NORTH, OreClimb.moves(1, 4, 9).get(0).direction());
        assertEquals(Direction.SOUTH, OreClimb.moves(1, -4, 9).get(0).direction());
        assertFalse(OreClimb.moves(5, 2, 9).get(0).rise(), "the tunnel to the ring does not climb");
    }

    @Test
    void directlyBeneathTheOreAnyOfFourSidesStartsTheRing() {
        List<OreClimb.Move> moves = OreClimb.moves(0, 0, 6);
        assertEquals(List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST),
                moves.stream().map(OreClimb.Move::direction).toList());
        assertTrue(moves.stream().allMatch(OreClimb.Move::rise));
        // Rising from three below needs the cell right under the ore, which must keep holding the drop.
        assertTrue(OreClimb.moves(0, 0, 3).stream().noneMatch(OreClimb.Move::rise));
        assertTrue(OreClimb.moves(0, 0, 2).isEmpty(), "two below the ore in its own column is already a work pose");
    }

    @Test
    void aRiseOpensTheCellAboveTheHeadThenTheLandingAndItsHead() {
        BlockPos feet = new BlockPos(10, 64, 10);
        OreClimb.Move rise = new OreClimb.Move(Direction.EAST, true);
        assertEquals(new BlockPos(11, 65, 10), OreClimb.landing(feet, rise));
        assertEquals(List.of(new BlockPos(10, 66, 10), new BlockPos(11, 65, 10), new BlockPos(11, 66, 10)),
                OreClimb.bodyCells(feet, rise));

        OreClimb.Move level = new OreClimb.Move(Direction.NORTH, false);
        assertEquals(new BlockPos(10, 64, 9), OreClimb.landing(feet, level));
        assertEquals(List.of(new BlockPos(10, 65, 9), new BlockPos(10, 64, 9)),
                OreClimb.bodyCells(feet, level));
    }

    @Test
    void everyStartClimbsToAWorkPoseBesideTheOreOnFloorsThatWereNeverDug() {
        for (int rx = -7; rx <= 7; rx++) {
            for (int rz = -7; rz <= 7; rz++) {
                for (int height = 3; height <= 18; height++) {
                    climb(rx, rz, height);
                }
            }
        }
    }

    /** Plays the stair out from {@code (rx, rz)} blocks off the ore's column, {@code height} below the ore. */
    private static void climb(int rx, int rz, int height) {
        String start = "from (" + rx + ", " + rz + ") " + height + " below the ore";
        BlockPos feet = new BlockPos(rx, ORE_Y - height, rz);
        // The bot's own pit is the only open ground at the start.
        Set<BlockPos> pit = Set.of(feet, feet.above());
        Set<BlockPos> dug = new HashSet<>(pit);
        int steps = 0;
        while (!atWorkPose(feet.getX(), feet.getZ(), ORE_Y - feet.getY())) {
            assertTrue(++steps < 200, "the stair never ends " + start);
            List<OreClimb.Move> moves = OreClimb.moves(feet.getX(), feet.getZ(), ORE_Y - feet.getY());
            assertFalse(moves.isEmpty(), "no step at " + feet + " " + start);
            OreClimb.Move move = moves.get(0);
            BlockPos landing = OreClimb.landing(feet, move);
            assertFalse(dug.contains(landing.below()),
                    "the tread at " + landing + " has no floor " + start);
            dug.addAll(OreClimb.bodyCells(feet, move));
            feet = landing;
        }
        int ring = Math.max(Math.abs(feet.getX()), Math.abs(feet.getZ()));
        if (steps > 0) {
            assertEquals(1, ring, "the climb ends beside the ore's column " + start);
            assertEquals(1, ORE_Y - feet.getY(), "the ore is at head height at the end " + start);
        }
        for (BlockPos cell : dug) {
            boolean inTheColumn = cell.getX() == 0 && cell.getZ() == 0;
            assertFalse(inTheColumn && cell.getY() >= ORE_Y - 1 && !pit.contains(cell),
                    "the ore or the cell that holds its drop was dug at " + cell + " " + start);
        }
    }
}
