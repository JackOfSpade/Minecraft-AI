package io.github.zoyluo.minecraftai.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.mode.ReachObstructions.Line;
import io.github.zoyluo.minecraftai.testsupport.FakeLevel;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

/**
 * What a hand would have to clear to reach a block the eyes see, line by line, over a fake level. The bot stands in cell (0,1,0)
 * with its eye at the height of a standing player, the log it wants is in cell (4,1,0), and a wall of whatever the case calls for
 * stands in x = 2 (and x = 3), three cells wide and tall enough to cover every line to the log's near face. The lines are the
 * strict gate's own, a vanilla pick ray (outline shapes, fluids ignored), so the ones left are exactly what must disappear for
 * that gate to pass.
 */
class ReachObstructionsTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState PANE = Blocks.GLASS_PANE.defaultBlockState();
    private static final BlockPos LOG_POS = new BlockPos(4, 1, 0);
    private static final Vec3 EYE = new Vec3(0.5D, 1.62D, 0.5D);

    private static FakeLevel level() {
        return new FakeLevel().set(LOG_POS.getX(), LOG_POS.getY(), LOG_POS.getZ(), LOG);
    }

    private static FakeLevel wall(FakeLevel level, int x, BlockState state) {
        for (int y = 0; y <= 3; y++) {
            for (int z = -2; z <= 2; z++) {
                level.set(x, y, z, state);
            }
        }
        return level;
    }

    /**
     * One row of cells at the height of the lines. A fluid is never stacked in these tests: vanilla caches the shape of a fluid state
     * from the first cell it is asked about (full height when the cell above holds the same fluid), which would leak into every
     * other test of the JVM that expects a source's 8/9 surface.
     */
    private static FakeLevel row(FakeLevel level, int x, BlockState state) {
        for (int z = -2; z <= 2; z++) {
            level.set(x, LOG_POS.getY(), z, state);
        }
        return level;
    }

    private static List<Line> lines(FakeLevel level) {
        return ReachObstructions.lines(level, CollisionContext.empty(), null, EYE, LOG_POS);
    }

    private static List<BlockPos> blocks(Line line) {
        return line.obstructions().stream().map(ReachObstructions.Obstruction::pos).toList();
    }

    private static List<Line> obstructed(List<Line> lines) {
        return lines.stream().filter(line -> !line.obstructions().isEmpty()).toList();
    }

    @Test
    void aLogInTheOpenHasClearLinesAndNothingToBreak() {
        List<Line> lines = lines(level());
        assertFalse(lines.isEmpty());
        for (Line line : lines) {
            assertTrue(line.obstructions().isEmpty(), "a clear line has nothing in front of the log: " + line);
        }
    }

    @Test
    void onlyTheFaceTowardTheEyeHasLines() {
        List<Line> lines = lines(level());
        assertEquals(9, lines.size(), "the 3x3 aim grid of the near face; every other face is struck from behind the log's own near face");
        for (Line line : lines) {
            assertEquals(Direction.WEST, line.face());
        }
    }

    @Test
    void aWallOfLeavesPutsItsLeafOnEveryLine() {
        List<Line> lines = lines(wall(level(), 2, LEAF));
        assertEquals(9, lines.size(), "seeing through foliage keeps every line");
        for (Line line : lines) {
            assertEquals(1, line.obstructions().size(), line.toString());
            assertEquals(2, blocks(line).get(0).getX(), "the leaf of the wall, not the log");
            assertEquals(LEAF, line.obstructions().get(0).state(), "recorded with the state it really holds");
        }
    }

    @Test
    void twoWallsListTheirBlocksNearestTheEyeFirst() {
        List<Line> lines = lines(wall(wall(level(), 2, LEAF), 3, LEAF));
        assertFalse(lines.isEmpty());
        for (Line line : lines) {
            List<BlockPos> blocks = blocks(line);
            assertEquals(2, blocks.size(), line.toString());
            assertEquals(2, blocks.get(0).getX());
            assertEquals(3, blocks.get(1).getX());
        }
    }

    @Test
    void aWallOfStoneKillsEveryLine() {
        assertTrue(lines(wall(level(), 2, Blocks.STONE.defaultBlockState())).isEmpty(),
                "nothing opaque is ever listed as something to break: the log is not seen at all");
        assertTrue(lines(wall(wall(level(), 2, LEAF), 3, Blocks.STONE.defaultBlockState())).isEmpty(),
                "foliage in front of stone changes nothing");
    }

    @Test
    void aLogBehindALeafAndAClosedDoorIsNotSeen() {
        FakeLevel level = wall(level(), 2, LEAF);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        for (int y = 0; y <= 3; y++) {
            for (int z = -2; z <= 2; z++) {
                level.set(3, y, z, y % 2 == 0 ? door : door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
        }
        assertTrue(lines(level).isEmpty(), "a door stops the eye, so there is no line to clear");
    }

    @Test
    void lavaKillsEveryLineBecauseTheEyesStopAtIt() {
        assertTrue(lines(row(level(), 2, Blocks.LAVA.defaultBlockState())).isEmpty(), "lava is opaque");
        assertTrue(lines(row(wall(level(), 2, LEAF), 3, Blocks.LAVA.defaultBlockState())).isEmpty(),
                "a leaf in front of lava changes nothing");
    }

    @Test
    void aHandPassesWaterSoALogUnderItIsReachedAsAPlayerMinesItFromTheShore() {
        List<Line> lines = lines(row(row(level(), 2, Blocks.WATER.defaultBlockState()), 3, Blocks.WATER.defaultBlockState()));
        assertFalse(lines.isEmpty(), "water hides nothing from the eyes");
        for (Line line : lines) {
            assertTrue(line.obstructions().isEmpty(), "a pick ray ignores fluids: water is nothing to break, " + line);
        }
    }

    @Test
    void aWaterloggedLeafIsAnObstructionAndItsWaterIsNot() {
        BlockState wet = LEAF.setValue(BlockStateProperties.WATERLOGGED, true);
        List<Line> lines = lines(row(level(), 2, wet));
        assertFalse(lines.isEmpty());
        for (Line line : lines) {
            assertEquals(List.of(wet), line.obstructions().stream().map(ReachObstructions.Obstruction::state).toList(),
                    "the leaf must go; the water it leaves behind is passed like any other water: " + line);
        }
    }

    @Test
    void aFenceLineWithAGapHasClearLinesThroughTheGap() {
        FakeLevel level = level();
        for (int y = 0; y <= 3; y++) {
            for (int z = -2; z <= 2; z++) {
                if (z != 0) {
                    level.set(2, y, z, FENCE);
                }
            }
        }
        List<Line> lines = lines(level);
        assertFalse(lines.isEmpty());
        for (Line line : lines) {
            assertTrue(line.obstructions().isEmpty(), "the lines all pass through the empty cell of the fence line: " + line);
        }
    }

    @Test
    void aLoneFencePostStopsOnlyTheLinesThroughItsMiddleAndTheOthersSlipPast() {
        // The post is 6/16 to 10/16 of the cell wide: the three lines aimed at the middle of the log's face cross it, the six
        // aimed 0.375 to either side pass beside it, as a player's crosshair does.
        List<Line> lines = lines(level().set(2, 1, 0, FENCE));
        assertEquals(9, lines.size());
        List<Line> stopped = obstructed(lines);
        assertEquals(3, stopped.size(), lines.toString());
        for (Line line : stopped) {
            assertEquals(List.of(new BlockPos(2, 1, 0)), blocks(line));
            assertTrue(line.obstructions().get(0).state().is(Blocks.OAK_FENCE));
            assertEquals(0.5D, line.aim().z, 1.0E-9D, "the lines through the middle");
        }
    }

    @Test
    void aFencesRailsStopTheLinesThatCrossThemAtTheirHeight() {
        BlockState connected = FENCE.setValue(FenceBlock.NORTH, true).setValue(FenceBlock.SOUTH, true);
        List<Line> lines = lines(wall(level(), 2, connected));
        assertFalse(lines.isEmpty());
        List<Line> stopped = obstructed(lines);
        assertFalse(stopped.isEmpty(), "the outline rails of a fence line are in the way of the lines that cross them");
        for (Line line : stopped) {
            assertEquals(1, line.obstructions().size(), line.toString());
            assertTrue(line.obstructions().get(0).state().is(Blocks.OAK_FENCE));
        }
    }

    @Test
    void aFenceBelowTheLineOfSightIsNoObstruction() {
        // The lines cross the cell at the eye's height; a ray's traversal never tests the fence of the cell below it.
        FakeLevel level = level();
        for (int z = -2; z <= 2; z++) {
            level.set(2, 0, z, FENCE.setValue(FenceBlock.NORTH, true).setValue(FenceBlock.SOUTH, true));
        }
        for (Line line : lines(level)) {
            assertTrue(line.obstructions().isEmpty(), "the pick ray passes over a fence of one block: " + line);
        }
    }

    @Test
    void aWallOfCobwebIsOnEveryLineThoughNothingCollidesWithIt() {
        BlockState web = Blocks.COBWEB.defaultBlockState();
        assertTrue(web.getCollisionShape(new FakeLevel(), new BlockPos(2, 1, 0), CollisionContext.empty()).isEmpty());
        List<Line> lines = lines(wall(level(), 2, web));
        assertEquals(9, lines.size());
        for (Line line : lines) {
            assertEquals(List.of(web), line.obstructions().stream().map(ReachObstructions.Obstruction::state).toList(),
                    "a click lands on the web, a collider ray would walk through it: " + line);
        }
    }

    @Test
    void anOpenGateIsOnEveryLineBecauseItsPanelStillTakesTheClick() {
        BlockState open = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.EAST).setValue(FenceGateBlock.OPEN, true);
        assertTrue(open.getCollisionShape(new FakeLevel(), new BlockPos(2, 1, 0), CollisionContext.empty()).isEmpty());
        List<Line> lines = lines(wall(level(), 2, open));
        assertEquals(9, lines.size());
        for (Line line : lines) {
            assertEquals(List.of(open), line.obstructions().stream().map(ReachObstructions.Obstruction::state).toList(), line.toString());
        }
    }

    @Test
    void aPlantInTheWayIsAnObstructionOfALogThoughAColliderRayWalksThroughIt() {
        FakeLevel level = level();
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        for (int z = -1; z <= 1; z++) {
            for (int y = 0; y <= 3; y++) {
                level.set(2, y, z, grass);
            }
        }
        List<Line> lines = lines(level);
        assertEquals(9, lines.size(), "grass hides nothing from the eyes");
        List<Line> stopped = obstructed(lines);
        assertFalse(stopped.isEmpty(), "a click along the line meets the grass before the log");
        for (Line line : stopped) {
            assertEquals(1, line.obstructions().size(), line.toString());
            assertTrue(line.obstructions().get(0).state().is(Blocks.SHORT_GRASS));
        }
    }

    @Test
    void aTorchAsideTheLineIsNoObstructionAndOneOnItIs() {
        List<Line> lines = lines(level().set(2, 1, 0, Blocks.TORCH.defaultBlockState()));
        assertEquals(9, lines.size());
        List<Line> stopped = obstructed(lines);
        assertFalse(stopped.isEmpty(), "the lines through the middle of the cell meet the torch");
        assertTrue(stopped.size() < lines.size(), "the lines beside it do not: a hand aims past a torch");
        for (Line line : stopped) {
            assertTrue(line.obstructions().get(0).state().is(Blocks.TORCH));
        }
    }

    @Test
    void aPlantInFrontOfATorchIsAnObstructionOfItToo() {
        BlockPos torch = new BlockPos(4, 1, 0);
        FakeLevel level = new FakeLevel().set(4, 1, 0, Blocks.TORCH.defaultBlockState());
        for (int y = 0; y <= 3; y++) {
            for (int z = -1; z <= 1; z++) {
                level.set(2, y, z, Blocks.SHORT_GRASS.defaultBlockState());
            }
        }
        List<Line> lines = ReachObstructions.lines(level, CollisionContext.empty(), null, EYE, torch);
        assertFalse(lines.isEmpty(), "the torch is seen through the grass");
        assertTrue(lines.stream().anyMatch(line -> !line.obstructions().isEmpty()
                        && line.obstructions().get(0).state().is(Blocks.SHORT_GRASS)),
                "a block without a collision shape is aimed at on its outline, which the grass on the line stops");
    }

    @Test
    void aPaneInTheWayIsListedForTheLinesThroughItsPlane() {
        BlockState plane = PANE.setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.SOUTH, true);
        List<Line> lines = lines(wall(level(), 2, plane));
        assertFalse(lines.isEmpty());
        for (Line line : lines) {
            assertEquals(1, line.obstructions().size(), line.toString());
            assertTrue(line.obstructions().get(0).state().is(Blocks.GLASS_PANE));
        }
    }

    @Test
    void theTargetIsNeverItsOwnObstructionEvenWhenItIsSeeThrough() {
        // A leaf behind a leaf: the leaf in front is what must go, the target leaf is what is wanted.
        FakeLevel level = new FakeLevel().set(4, 1, 0, LEAF);
        wall(level, 2, LEAF);
        List<Line> lines = lines(level);
        assertFalse(lines.isEmpty());
        for (Line line : lines) {
            assertEquals(List.of(2), blocks(line).stream().map(BlockPos::getX).toList(), line.toString());
        }
    }

    @Test
    void anEyeInsideAFoliageCellListsThatCellFirst() {
        FakeLevel level = level().set(0, 1, 0, LEAF);
        for (Line line : lines(level)) {
            assertEquals(List.of(new BlockPos(0, 1, 0)), blocks(line), "the leaf the head is in is the first thing a hand must clear");
        }
    }

    @Test
    void theLavaABotWadesInDoesNotKillTheLinesFromItsEyes() {
        BlockPos feet = new BlockPos(0, 0, 0);
        FakeLevel level = level().set(0, 0, 0, Blocks.LAVA.defaultBlockState());
        // Lines to a log below the pool's rim dip through the cell the bot stands in: they are the bot's own, whatever it wades in.
        BlockPos low = new BlockPos(1, -1, 0);
        level.set(1, -1, 0, LOG);
        List<Line> others = ReachObstructions.lines(level, CollisionContext.empty(), null, EYE, low);
        List<Line> wading = ReachObstructions.lines(level, CollisionContext.empty(), feet, EYE, low);
        assertTrue(others.size() < wading.size(), "to anyone else the lava is in the way of the lines that dip through it: " + others.size() + " vs " + wading.size());
        assertTrue(wading.stream().allMatch(line -> line.obstructions().isEmpty()),
                "the bot sees, and can reach, what lies beyond the lava it stands in");
    }

    @Test
    void aLineEndsAtThePointItAimsAtTheFaceItNames() {
        for (Line line : lines(level())) {
            assertEquals(LOG_POS.getX() + 0.001D, line.aim().x, 1.0E-9D, "0.001 inside the near face, as the strict proofs aim");
        }
    }

    @Test
    void theLinesAreTheOnesAClickReachesWhenNothingIsInTheWay() {
        FakeLevel level = level();
        for (Line line : lines(level)) {
            var hit = level.clip(new ClipContext(EYE, line.aim(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            assertEquals(LOG_POS, hit.getBlockPos());
            assertEquals(line.face(), hit.getDirection());
        }
    }

    @Test
    void anObstructedLineIsOneAClickDoesNotReachAndAClearOneIs() {
        FakeLevel level = wall(level(), 2, Blocks.COBWEB.defaultBlockState());
        level.set(2, 1, 0, Blocks.AIR.defaultBlockState());
        for (Line line : lines(level)) {
            var click = level.clip(new ClipContext(EYE, line.aim(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            assertEquals(line.obstructions().isEmpty(), click.getBlockPos().equals(LOG_POS),
                    "the vanilla pick ray lands on the log exactly when the line lists nothing: " + line);
        }
    }
}
