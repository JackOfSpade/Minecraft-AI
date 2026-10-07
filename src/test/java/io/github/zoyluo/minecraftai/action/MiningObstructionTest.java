package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.mining.BreakRule;
import io.github.zoyluo.minecraftai.mode.ReachObstructions.Line;
import io.github.zoyluo.minecraftai.mode.ReachObstructions.Obstruction;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Which see-through block the bot breaks first to get at a block it sees, and which it never breaks: the pure choice over lines
 * built by hand, with the mod-wide break rule as the policy the real code asks. The lines are what {@code ReachObstructions}
 * would report for a bot whose eye is at the origin.
 */
class MiningObstructionTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState PANE = Blocks.GLASS_PANE.defaultBlockState();
    private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final Vec3 EYE = Vec3.ZERO;

    private static Obstruction at(int x, int y, BlockState state) {
        return new Obstruction(new BlockPos(x, y, 0), state);
    }

    private static Line line(Obstruction... obstructions) {
        return new Line(Direction.WEST, new Vec3(5.0D, 1.0D, 0.0D), List.of(obstructions));
    }

    private static MiningObstruction.Plan choose(List<Line> lines) {
        return MiningObstruction.choose(lines, EYE, obstruction -> BreakRule.denialOf(obstruction.state()),
                obstruction -> true, "minecraft:oak_log");
    }

    @Test
    void aSingleLeafInTheWayIsTheOneToBreak() {
        MiningObstruction.Plan plan = choose(List.of(line(at(2, 1, LEAF))));
        assertEquals(MiningObstruction.Kind.CLEAR, plan.kind());
        assertEquals(new BlockPos(2, 1, 0), plan.obstruction().pos());
        assertEquals(1, plan.remaining());
        assertNull(plan.refusal());
        assertFalse(plan.refused());
        assertEquals("minecraft:oak_log", plan.targetBlock());
    }

    @Test
    void theNearestBlockOfTheLineIsBrokenFirstWhateverOrderTheLeavesWereListedIn() {
        MiningObstruction.Plan plan = choose(List.of(line(at(2, 1, LEAF), at(3, 1, LEAF))));
        assertEquals(MiningObstruction.Kind.CLEAR, plan.kind());
        assertEquals(new BlockPos(2, 1, 0), plan.obstruction().pos(), "the one a hand would hit first");
        assertEquals(2, plan.remaining(), "two leaves stand on the line, this is the first of them");
    }

    @Test
    void theLineWithTheFewestBlocksWins() {
        MiningObstruction.Plan plan = choose(List.of(
                line(at(2, 1, LEAF), at(3, 1, LEAF)),
                line(at(2, 2, LEAF)),
                line(at(2, 0, LEAF), at(3, 0, LEAF), at(4, 0, LEAF))));
        assertEquals(new BlockPos(2, 2, 0), plan.obstruction().pos());
        assertEquals(1, plan.remaining());
    }

    @Test
    void amongEquallyShortLinesTheOneWhoseFirstBlockIsNearestWins() {
        MiningObstruction.Plan plan = choose(List.of(
                line(at(3, 1, LEAF)),
                line(at(2, 2, LEAF)),
                line(at(4, 1, LEAF))));
        assertEquals(new BlockPos(2, 2, 0), plan.obstruction().pos());
    }

    @Test
    void equalLinesKeepTheOrderOfTheLines() {
        MiningObstruction.Plan plan = choose(List.of(line(at(2, 2, LEAF)), line(at(2, -3, LEAF))));
        assertEquals(new BlockPos(2, 2, 0), plan.obstruction().pos(), "a stable choice, not a coin toss, for equally good lines");
    }

    @Test
    void aLineThatCrossesABlockThatMayNotBeBrokenIsNeverStartedEvenIfItIsTheShortest() {
        // The pane is somebody's: breaking the leaf in front of it would open nothing.
        Line withPane = line(at(2, 1, LEAF), at(3, 1, PANE));
        Line longer = line(at(2, 2, LEAF), at(3, 2, LEAF), at(4, 2, LEAF));
        MiningObstruction.Plan plan = choose(List.of(withPane, longer));
        assertEquals(MiningObstruction.Kind.CLEAR, plan.kind());
        assertEquals(new BlockPos(2, 2, 0), plan.obstruction().pos(), "the next best line, all leaves");
        assertEquals(3, plan.remaining());
    }

    @Test
    void whenEveryLineIsBlockedByAProtectedBlockTheTargetIsRefusedAndNothingIsBroken() {
        MiningObstruction.Plan plan = choose(List.of(line(at(2, 1, LEAF), at(3, 1, PANE)), line(at(2, 2, FENCE))));
        assertEquals(MiningObstruction.Kind.PROTECTED, plan.kind());
        assertTrue(plan.refused());
        assertEquals(MiningController.TARGET_OBSTRUCTED, plan.refusal());
        assertEquals(new BlockPos(2, 2, 0), plan.obstruction().pos(), "the first protected block, in the order the lines are tried: the shorter line first");
        assertEquals(BreakRule.denialOf(FENCE), plan.reason());
    }

    @Test
    void theTypedReasonNamesWhyTheBlockMayNotBeBroken() {
        assertEquals("structure_block", choose(List.of(line(at(2, 1, FENCE)))).reason(),
                "a fence is player-built material");
        assertEquals("structure_block", choose(List.of(line(at(2, 1, Blocks.GLASS.defaultBlockState())))).reason());
        assertEquals("structure_block", choose(List.of(line(at(2, 1, Blocks.IRON_BARS.defaultBlockState())))).reason());
    }

    @Test
    void aLineWithNothingToClearIsNotALineToClear() {
        assertEquals(MiningObstruction.Kind.NOT_SEEN, choose(List.of(line())).kind(),
                "the strict proof failed for another reason; breaking something would not help");
        assertEquals(MiningObstruction.Kind.NOT_SEEN, choose(List.of()).kind(), "no line at all: the block is not seen");
        assertEquals(MiningController.TARGET_NOT_OBSERVED, choose(List.of()).refusal());
        assertTrue(choose(List.of()).refused());
    }

    @Test
    void aFirstBlockThatCannotBeBrokenFromHereMakesTheNextLineTheBestOne() {
        Set<BlockPos> unreachable = Set.of(new BlockPos(2, 1, 0));
        MiningObstruction.Plan plan = MiningObstruction.choose(
                List.of(line(at(2, 1, LEAF)), line(at(2, 2, LEAF), at(3, 2, LEAF))), EYE,
                obstruction -> BreakRule.denialOf(obstruction.state()),
                obstruction -> !unreachable.contains(obstruction.pos()), "minecraft:oak_log");
        assertEquals(MiningObstruction.Kind.CLEAR, plan.kind());
        assertEquals(new BlockPos(2, 2, 0), plan.obstruction().pos());
    }

    @Test
    void aBlockThatCannotBeBrokenFromHereIsNotAProtectedBlock() {
        MiningObstruction.Plan plan = MiningObstruction.choose(List.of(line(at(2, 1, LEAF))), EYE,
                obstruction -> BreakRule.denialOf(obstruction.state()), obstruction -> false, "minecraft:oak_log");
        assertEquals(MiningObstruction.Kind.NOT_SEEN, plan.kind(), "unobserved from here, as it was before: not a refusal about the block");
    }

    @Test
    void theBotsOwnReasonsToKeepABlockAreHonouredLikeTheBreakRule() {
        Set<BlockPos> standingOn = Set.of(new BlockPos(2, 1, 0));
        MiningObstruction.Plan plan = MiningObstruction.choose(
                List.of(line(at(2, 1, LEAF)), line(at(2, 2, LEAF), at(3, 2, LEAF))), EYE,
                obstruction -> standingOn.contains(obstruction.pos()) ? "self_support" : BreakRule.denialOf(obstruction.state()),
                obstruction -> true, "minecraft:oak_log");
        assertEquals(new BlockPos(2, 2, 0), plan.obstruction().pos(), "the leaf the bot stands on is not broken");

        MiningObstruction.Plan only = MiningObstruction.choose(List.of(line(at(2, 1, LEAF))), EYE,
                obstruction -> "exposes_lava", obstruction -> true, "minecraft:oak_log");
        assertEquals(MiningObstruction.Kind.PROTECTED, only.kind());
        assertEquals("exposes_lava", only.reason());
    }

    @Test
    void aBlockIsJudgedOnceHoweverManyLinesCrossIt() {
        List<BlockPos> asked = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            lines.add(line(at(2, 1, LEAF), at(3, 1, FENCE)));
        }
        MiningObstruction.Plan plan = MiningObstruction.choose(lines, EYE, obstruction -> {
            asked.add(obstruction.pos());
            return BreakRule.denialOf(obstruction.state());
        }, obstruction -> true, "minecraft:oak_log");
        assertEquals(MiningObstruction.Kind.PROTECTED, plan.kind());
        assertEquals(new HashSet<>(asked).size(), asked.size(), "each distinct cell costs one verdict, however many lines cross it: " + asked);
    }

    @Test
    void aBlockThisOperationAlreadyBrokenAndIsStillThereIsNotBrokenAgain() {
        Set<BlockPos> cleared = Set.of(new BlockPos(2, 1, 0));
        MiningObstruction.Plan plan = MiningObstruction.choose(List.of(line(at(2, 1, LEAF))), EYE,
                obstruction -> cleared.contains(obstruction.pos()) ? "obstruction_persisted" : null,
                obstruction -> true, "minecraft:oak_log");
        assertEquals(MiningObstruction.Kind.PROTECTED, plan.kind(), "a break the server did not carry out must end the operation, not loop");
        assertEquals("obstruction_persisted", plan.reason());
    }

    @Test
    void aPlannerLeavesLeavesSmallPlantsAndWaterOutOfWhatShutsALineAndKeepsEverythingElseIn() {
        for (BlockState passed : new BlockState[] {LEAF, Blocks.SPRUCE_LEAVES.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(), Blocks.WATER.defaultBlockState()}) {
            assertTrue(MiningObstruction.handGetsPast(passed), passed.getBlock() + " is cleared or passed by the bot's hand");
        }
        for (BlockState shut : new BlockState[] {FENCE, PANE, Blocks.GLASS.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.COBWEB.defaultBlockState(), Blocks.LAVA.defaultBlockState()}) {
            assertFalse(MiningObstruction.handGetsPast(shut), shut.getBlock() + " stays in the way of a break");
        }
    }

    @Test
    void theBreakRuleLetsABotClearFoliageAndNothingSomebodyBuilt() {
        // Natural terrain only: the leaves of every tree and the small plants that grow in the way.
        for (BlockState natural : new BlockState[] {LEAF, Blocks.BIRCH_LEAVES.defaultBlockState(),
                Blocks.AZALEA_LEAVES.defaultBlockState(), Blocks.MANGROVE_LEAVES.defaultBlockState(),
                Blocks.CHERRY_LEAVES.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState()}) {
            assertNull(BreakRule.denialOf(natural), natural.getBlock() + " is in the way and may be cleared");
        }
        for (BlockState made : new BlockState[] {FENCE, Blocks.OAK_FENCE_GATE.defaultBlockState(),
                Blocks.NETHER_BRICK_FENCE.defaultBlockState(), Blocks.GLASS.defaultBlockState(), PANE,
                Blocks.WHITE_STAINED_GLASS.defaultBlockState(), Blocks.TINTED_GLASS.defaultBlockState(),
                Blocks.IRON_BARS.defaultBlockState(), Blocks.IRON_CHAIN.defaultBlockState(), Blocks.LADDER.defaultBlockState(),
                Blocks.SCAFFOLDING.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(),
                Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.ICE.defaultBlockState(),
                Blocks.SPAWNER.defaultBlockState(), Blocks.BARRIER.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState()}) {
            assertNotNull(BreakRule.denialOf(made), made.getBlock() + " is not the bot's to break");
        }
    }
}
