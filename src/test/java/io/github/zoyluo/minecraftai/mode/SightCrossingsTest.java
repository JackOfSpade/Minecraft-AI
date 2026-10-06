package io.github.zoyluo.minecraftai.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.zoyluo.minecraftai.testsupport.FakeLevel;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

/**
 * A recorder asks a ray's crossings for the state of every cell it walks, and a ray through water crosses a cell of it per block, so
 * the answer must not be a scan of the whole list per cell (a hundred-block underwater ray was a hundred lookups of up to a hundred
 * entries each). Short rays are scanned, which is cheaper than building an index for a handful of cells.
 */
class SightCrossingsTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    private static SightClipContext.Crossings crossingsOfARowOf(int cells) {
        FakeLevel level = new FakeLevel().set(cells + 1, 1, 0, STONE);
        for (int x = 1; x <= cells; x++) {
            level.set(x, 1, 0, x % 3 == 0 ? LEAF : WATER);
        }
        SightClipContext ray = SightClip.context(new Vec3(0.5D, 1.5D, 0.5D), new Vec3(cells + 3.0D, 1.5D, 0.5D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty(), null, true);
        level.clip(ray);
        return ray.crossed();
    }

    private static Object index(SightClipContext.Crossings crossings) throws ReflectiveOperationException {
        Field field = SightClipContext.Crossings.class.getDeclaredField("index");
        field.setAccessible(true);
        return field.get(crossings);
    }

    @Test
    void aLongRayAnswersFromAnIndexBuiltOnTheFirstLookupAndEveryAnswerIsRight() throws ReflectiveOperationException {
        SightClipContext.Crossings crossings = crossingsOfARowOf(60);
        assertEquals(60, crossings.size());
        assertNull(index(crossings), "nothing is built before a recorder asks");
        for (int x = 1; x <= 60; x++) {
            assertEquals(x % 3 == 0 ? LEAF : WATER, crossings.stateAt(BlockPos.asLong(x, 1, 0)), "cell " + x);
        }
        assertNotNull(index(crossings), "sixty cells are looked up through an index, not scanned sixty times");
        assertNull(crossings.stateAt(BlockPos.asLong(0, 1, 0)), "the eye's own cell was not crossed");
        assertNull(crossings.stateAt(BlockPos.asLong(61, 1, 0)), "the stone was the hit, not a crossing");
        assertNull(crossings.stateAt(BlockPos.asLong(5, 2, 0)), "a cell beside the ray");
    }

    @Test
    void aShortRayIsScannedAndNeedsNoIndex() throws ReflectiveOperationException {
        SightClipContext.Crossings crossings = crossingsOfARowOf(4);
        assertEquals(WATER, crossings.stateAt(BlockPos.asLong(1, 1, 0)));
        assertEquals(LEAF, crossings.stateAt(BlockPos.asLong(3, 1, 0)));
        assertNull(index(crossings), "four cells are cheaper to scan than to index");
    }

    @Test
    void theIndexAnswersFromTheListOfARayThatEndedThroughTheStaticLookup() {
        SightClipContext.Crossings crossings = crossingsOfARowOf(30);
        assertEquals(WATER, SightClip.crossedState(crossings, BlockPos.asLong(1, 1, 0)));
        assertEquals(LEAF, SightClip.crossedState(crossings, BlockPos.asLong(30, 1, 0)));
        assertNull(SightClip.crossedState(crossings, BlockPos.asLong(31, 1, 0)));
    }
}
