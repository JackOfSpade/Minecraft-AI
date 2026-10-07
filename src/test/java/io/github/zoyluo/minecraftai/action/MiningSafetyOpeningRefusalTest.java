package io.github.zoyluo.minecraftai.action;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Whether a digger may open a cell is decided from what it has seen of the cell and its neighbours, and
 * never from a cell it has not seen: the verdict is a function of the view, so it is tested on one. Fluids
 * are judged by tag, which a plain JVM does not load: MiningSafetyOpeningRefusalGameTests covers them in a world.
 */
final class MiningSafetyOpeningRefusalTest {
    private static final BlockPos CELL = new BlockPos(10, 20, 10);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A view of a few cells; everything else is unseen and asking for its state is a failure. */
    private static final class View implements MiningSafety.OpeningView {
        private final Map<BlockPos, BlockState> seen = new HashMap<>();
        private final Set<BlockPos> underPlayer = new HashSet<>();

        View see(BlockPos pos, BlockState state) {
            seen.put(pos, state);
            return this;
        }

        @Override
        public boolean observed(BlockPos pos) {
            return seen.containsKey(pos);
        }

        @Override
        public BlockState stateAt(BlockPos pos) {
            BlockState state = seen.get(pos);
            if (state == null) {
                fail("the verdict read a cell that was never seen: " + pos);
            }
            return state;
        }

        @Override
        public boolean playerSupports(BlockPos pos) {
            return underPlayer.contains(pos);
        }
    }

    @Test
    void aCellThatIsNotInViewIsJudgedOnceItIs() {
        // Nothing is seen, so nothing is read (the view fails the test if it is asked for a cell).
        assertNull(MiningSafety.openingRefusal(new View(), CELL));
    }

    @Test
    void aFallingBlockInTheCellItselfIsRefused() {
        assertEquals("gravity", MiningSafety.openingRefusal(new View().see(CELL, Blocks.GRAVEL.defaultBlockState()), CELL));
        assertEquals("gravity", MiningSafety.openingRefusal(new View().see(CELL, Blocks.SAND.defaultBlockState()), CELL));
        assertEquals("gravity", MiningSafety.openingRefusal(new View().see(CELL, Blocks.RED_SAND.defaultBlockState()), CELL));
    }

    @Test
    void aFallingBlockOverTheCellBuriesIt() {
        BlockState air = Blocks.AIR.defaultBlockState();
        assertEquals("gravity", MiningSafety.openingRefusal(
                new View().see(CELL, air).see(CELL.above(), Blocks.GRAVEL.defaultBlockState()), CELL));
    }

    @Test
    void whatIsNotInViewAboveOrBesideTheCellIsNeverGuessedAt() {
        BlockState air = Blocks.AIR.defaultBlockState();
        // Only the cell is seen: the cells over and beside it are behind rock, and are not read.
        assertNull(MiningSafety.openingRefusal(new View().see(CELL, air), CELL));
    }

    @Test
    void aSeenDryNeighbourhoodOfAnOpenCellIsAllowed() {
        BlockState air = Blocks.AIR.defaultBlockState();
        View view = new View().see(CELL, air).see(CELL.above(), air).see(CELL.east(), Blocks.OBSIDIAN.defaultBlockState())
                .see(CELL.below(), Blocks.OBSIDIAN.defaultBlockState());
        assertNull(MiningSafety.openingRefusal(view, CELL));
    }
}
