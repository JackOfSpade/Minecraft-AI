package io.github.zoyluo.minecraftai.mode;

import static io.github.zoyluo.minecraftai.testsupport.HitAssert.assertHitsBlock;
import static io.github.zoyluo.minecraftai.testsupport.HitAssert.assertSameHit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.testsupport.FakeLevel;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What an eye ray passes and what stops it, over a fake level. Every ray runs from the middle of cell (0,1,0) along +x; a log in
 * cell (5,1,0) is the thing being looked at. The contrast with a plain vanilla {@link ClipContext} is part of each case: it is
 * what a bot saw before.
 */
class SightClipSkipTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockPos LOG_POS = new BlockPos(5, 1, 0);
    private static final Vec3 EYE = new Vec3(0.5D, 1.5D, 0.5D);
    /** Ends 0.001 inside the log, as the observation rays of {@code FaceAim} do. */
    private static final Vec3 INTO_LOG = new Vec3(5.001D, 1.5D, 0.5D);

    private static BlockHitResult vanilla(BlockGetter level, Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid) {
        return level.clip(new ClipContext(from, to, shape, fluid, CollisionContext.empty()));
    }

    private static BlockHitResult sight(BlockGetter level, Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid,
                                        BlockPos target) {
        return SightClip.clip(level, from, to, shape, fluid, CollisionContext.empty(), target);
    }

    private static void assertSeesTheLog(BlockHitResult hit, String what) {
        assertHitsBlock(hit, LOG_POS, what);
        assertEquals(Direction.WEST, hit.getDirection(), what + ": the face turned toward the eye");
        assertEquals(5.0D, hit.getLocation().x, 1.0E-9D, what + ": on the near face");
    }

    @ParameterizedTest
    @EnumSource(value = ClipContext.Block.class, names = {"COLLIDER", "OUTLINE"})
    void aRaySeesTheLogBehindTwoLeavesAsIfTheyWereNotThere(ClipContext.Block shape) {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, LEAF).set(5, 1, 0, LOG);
        assertHitsBlock(vanilla(level, EYE, INTO_LOG, shape, ClipContext.Fluid.ANY), new BlockPos(2, 1, 0),
                "vanilla stops at the first leaf");
        BlockHitResult hit = sight(level, EYE, INTO_LOG, shape, ClipContext.Fluid.ANY, null);
        assertSeesTheLog(hit, "two leaves");
        assertSameHit(vanilla(new FakeLevel().set(5, 1, 0, LOG), EYE, INTO_LOG, shape, ClipContext.Fluid.ANY), hit,
                "the same hit as in a world without the leaves");
    }

    @Test
    void aFencePostAtTheHeightOfTheRayIsSeenThrough() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, FENCE).set(5, 1, 0, LOG);
        assertHitsBlock(vanilla(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE), new BlockPos(2, 1, 0),
                "vanilla hits the post");
        assertSeesTheLog(sight(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null), "fence post");
    }

    @Test
    void aFenceArmIsSeenThrough() {
        // A fence with neighbours north and south: its arms are solid across the whole cell, a sight ray still passes.
        BlockState arms = FENCE.setValue(FenceBlock.NORTH, true).setValue(FenceBlock.SOUTH, true);
        FakeLevel level = new FakeLevel().set(2, 1, 0, arms).set(5, 1, 0, LOG);
        Vec3 from = new Vec3(0.5D, 1.125D, 0.2D);
        Vec3 to = new Vec3(5.001D, 1.125D, 0.2D);
        assertHitsBlock(vanilla(level, from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE), new BlockPos(2, 1, 0),
                "vanilla hits the arm");
        assertHitsBlock(sight(level, from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null), LOG_POS, "fence arm");
    }

    @ParameterizedTest
    @EnumSource(value = ClipContext.Block.class, names = {"COLLIDER", "OUTLINE"})
    void aFenceGateIsSeenThroughClosedAndOpen(ClipContext.Block shape) {
        for (boolean open : new boolean[] {false, true}) {
            BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, open);
            FakeLevel level = new FakeLevel().set(2, 1, 0, gate).set(5, 1, 0, LOG);
            // An open gate has no collision (a player walks through it) but still an outline: only a pick ray meets it.
            if (!open || shape == ClipContext.Block.OUTLINE) {
                assertHitsBlock(vanilla(level, EYE, INTO_LOG, shape, ClipContext.Fluid.NONE), new BlockPos(2, 1, 0),
                        "vanilla hits the gate (open=" + open + ")");
            }
            assertSeesTheLog(sight(level, EYE, INTO_LOG, shape, ClipContext.Fluid.NONE, null), "gate open=" + open);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClipContext.Fluid.class, names = {"ANY", "NONE", "SOURCE_ONLY", "WATER"})
    void waterIsSeenThroughWhateverFluidTheRayAsksFor(ClipContext.Fluid fluid) {
        FakeLevel level = new FakeLevel().set(2, 1, 0, Blocks.WATER.defaultBlockState())
                .set(3, 1, 0, Blocks.WATER.defaultBlockState()).set(5, 1, 0, LOG);
        assertSeesTheLog(sight(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, fluid, null), "two water cells, fluid " + fluid);
    }

    @Test
    void aVanillaRayThatAsksForWaterStopsAtItAndTheSightRayDoesNot() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, Blocks.WATER.defaultBlockState()).set(5, 1, 0, LOG);
        assertHitsBlock(vanilla(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY), new BlockPos(2, 1, 0),
                "vanilla Fluid.ANY stops at the surface");
        assertSeesTheLog(sight(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null), "water");
    }

    @ParameterizedTest
    @EnumSource(value = ClipContext.Fluid.class, names = {"ANY", "NONE", "SOURCE_ONLY"})
    void lavaStopsTheRayEvenWhenTheRayAsksForNoFluid(ClipContext.Fluid fluid) {
        FakeLevel level = new FakeLevel().set(2, 1, 0, Blocks.LAVA.defaultBlockState()).set(5, 1, 0, LOG);
        BlockHitResult hit = sight(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, fluid, null);
        assertHitsBlock(hit, new BlockPos(2, 1, 0), "lava with fluid " + fluid);
        assertEquals(Direction.WEST, hit.getDirection());
    }

    @Test
    void vanillaWithNoFluidSawThroughLavaWhichIsWhatTheBotsNoLongerDo() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, Blocks.LAVA.defaultBlockState()).set(5, 1, 0, LOG);
        assertSeesTheLog(vanilla(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE), "vanilla NONE");
    }

    @Test
    void aRayAboveTheLavaSurfaceAndTheLavaBehindWaterBehaveAsInVanilla() {
        FakeLevel pool = new FakeLevel().set(2, 1, 0, Blocks.LAVA.defaultBlockState()).set(5, 1, 0, LOG);
        Vec3 high = new Vec3(0.5D, 1.95D, 0.5D);
        BlockHitResult over = sight(pool, high, new Vec3(5.001D, 1.95D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null);
        assertHitsBlock(over, LOG_POS, "a source lava surface is 8/9 high: a ray at 0.95 passes over it");

        FakeLevel behindWater = new FakeLevel().set(2, 1, 0, Blocks.WATER.defaultBlockState())
                .set(3, 1, 0, Blocks.LAVA.defaultBlockState()).set(5, 1, 0, LOG);
        assertHitsBlock(sight(behindWater, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null),
                new BlockPos(3, 1, 0), "water is passed, the lava behind it is not");
    }

    @Test
    void aWaterloggedFenceIsSeenThrough() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, FENCE.setValue(BlockStateProperties.WATERLOGGED, true)).set(5, 1, 0, LOG);
        assertSeesTheLog(sight(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null), "waterlogged fence");
    }

    @Test
    void aWaterloggedSlabKeepsItsShapeAndOnlyItsWaterIsSkipped() {
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        FakeLevel level = new FakeLevel().set(2, 1, 0, slab).set(5, 1, 0, LOG);
        Vec3 low = new Vec3(0.5D, 1.25D, 0.5D);
        Vec3 high = new Vec3(0.5D, 1.75D, 0.5D);
        assertHitsBlock(sight(level, low, new Vec3(5.001D, 1.25D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null),
                new BlockPos(2, 1, 0), "a ray through the slab itself stops at it");
        assertHitsBlock(vanilla(level, high, new Vec3(5.001D, 1.75D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY),
                new BlockPos(2, 1, 0), "vanilla Fluid.ANY stops at the slab's water above the slab");
        assertHitsBlock(sight(level, high, new Vec3(5.001D, 1.75D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null),
                LOG_POS, "a ray over the slab passes its water");
    }

    @Test
    void glassIsSkippedAndABottomSlabIsKept() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, Blocks.GLASS.defaultBlockState())
                .set(3, 1, 0, Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM))
                .set(5, 1, 0, LOG);
        Vec3 low = new Vec3(0.5D, 1.25D, 0.5D);
        BlockHitResult hit = sight(level, low, new Vec3(5.001D, 1.25D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null);
        assertHitsBlock(hit, new BlockPos(3, 1, 0), "the glass is passed, the slab below the ray's height is not");
        assertEquals(Direction.WEST, hit.getDirection());
    }

    @ParameterizedTest
    @EnumSource(value = ClipContext.Block.class, names = {"COLLIDER", "OUTLINE"})
    void stoneAndTheOtherOpaqueBlocksAreExactlyVanilla(ClipContext.Block shape) {
        for (BlockState opaque : new BlockState[] {Blocks.STONE.defaultBlockState(), Blocks.BLUE_ICE.defaultBlockState(),
                Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.CHEST.defaultBlockState(), Blocks.OAK_TRAPDOOR.defaultBlockState()}) {
            FakeLevel level = new FakeLevel().set(2, 1, 0, opaque).set(5, 1, 0, LOG);
            for (ClipContext.Fluid fluid : ClipContext.Fluid.values()) {
                assertSameHit(vanilla(level, EYE, INTO_LOG, shape, fluid), sight(level, EYE, INTO_LOG, shape, fluid, null),
                        opaque.getBlock() + " " + shape + " " + fluid);
            }
        }
    }

    @Test
    void aClosedDoorWhosePlaneLiesOnTheRayIsOpaque() {
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        FakeLevel level = new FakeLevel().set(2, 1, 0, door).set(5, 1, 0, LOG);
        BlockHitResult hit = sight(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null);
        assertHitsBlock(hit, new BlockPos(2, 1, 0), "the door stays a wall");
        assertSameHit(vanilla(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE), hit, "vanilla agrees");
    }

    @Test
    void theTargetCellIsNeverSkippedSoALeafAFenceOrWaterCanBeObservedItself() {
        Vec3 intoThirdCell = new Vec3(3.001D, 1.5D, 0.5D);
        BlockPos target = new BlockPos(3, 1, 0);
        FakeLevel leaves = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, LEAF).set(5, 1, 0, LOG);
        BlockHitResult leaf = sight(leaves, EYE, intoThirdCell, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target);
        assertHitsBlock(leaf, target, "the observed leaf is returned, the leaf before it is passed");
        assertEquals(Direction.WEST, leaf.getDirection());
        assertEquals(HitResult.Type.MISS, sight(leaves, EYE, intoThirdCell, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null).getType(),
                "without a target the same ray misses");

        // The post of a fence is 6/16 to 10/16 of the cell: a ray observing it ends inside the post, as FaceAim's rays do.
        FakeLevel fences = new FakeLevel().set(2, 1, 0, FENCE).set(3, 1, 0, FENCE);
        assertHitsBlock(sight(fences, EYE, new Vec3(3.4D, 1.5D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target), target,
                "a fence");

        FakeLevel water = new FakeLevel().set(2, 1, 0, Blocks.WATER.defaultBlockState()).set(3, 1, 0, Blocks.WATER.defaultBlockState());
        assertHitsBlock(sight(water, EYE, intoThirdCell, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, target), target, "a water cell");
        assertEquals(HitResult.Type.MISS, sight(water, EYE, intoThirdCell, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null).getType());
    }

    @Test
    void aTargetBehindTheSeeThroughRunIsFoundThroughIt() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, FENCE).set(5, 1, 0, LOG);
        assertSeesTheLog(sight(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, LOG_POS), "target behind foliage");
    }

    @Test
    void aTargetCellOnlyExemptsItselfNotItsNeighbours() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, LEAF).set(5, 1, 0, LOG);
        BlockHitResult hit = sight(level, EYE, new Vec3(2.5D, 1.5D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                new BlockPos(3, 1, 0));
        assertEquals(HitResult.Type.MISS, hit.getType(), "the ray ends inside the first leaf, whose cell is not the target");
    }

    @Test
    void anEyeInsideALeafOrWaterSeesOutOfIt() {
        FakeLevel leaf = new FakeLevel().set(0, 1, 0, LEAF).set(5, 1, 0, LOG);
        BlockHitResult vanillaHit = vanilla(leaf, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY);
        assertHitsBlock(vanillaHit, new BlockPos(0, 1, 0), "vanilla hits the leaf the eye is in");
        assertTrue(vanillaHit.isInside());
        assertSeesTheLog(sight(leaf, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null), "eye in a leaf");

        FakeLevel water = new FakeLevel().set(0, 1, 0, Blocks.WATER.defaultBlockState()).set(5, 1, 0, LOG);
        assertTrue(vanilla(water, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY).isInside());
        assertSeesTheLog(sight(water, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null), "eye in water");
    }

    @Test
    void aRayThatMeetsNothingMissesExactlyLikeVanilla() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, Blocks.WATER.defaultBlockState());
        Vec3 to = new Vec3(6.5D, 1.5D, 0.5D);
        BlockHitResult hit = sight(level, EYE, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null);
        assertEquals(HitResult.Type.MISS, hit.getType());
        assertSameHit(vanilla(new FakeLevel(), EYE, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY), hit, "a miss");
        assertEquals(to, hit.getLocation());
        assertSameHit(vanilla(level, EYE, EYE, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY),
                sight(level, EYE, EYE, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null), "a zero-length ray");
    }

    @Test
    void clearIsTheMissTestOfALinearColliderRayThatIgnoresVanillaFluids() {
        CollisionContext context = CollisionContext.empty();
        Vec3 to = new Vec3(6.5D, 1.5D, 0.5D);
        assertTrue(SightClip.clear(new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, FENCE).set(4, 1, 0, Blocks.WATER.defaultBlockState()),
                context, EYE, to));
        assertFalse(SightClip.clear(new FakeLevel().set(2, 1, 0, Blocks.STONE.defaultBlockState()), context, EYE, to));
        assertFalse(SightClip.clear(new FakeLevel().set(2, 1, 0, Blocks.LAVA.defaultBlockState()), context, EYE, to),
                "vanilla's hasLineOfSight (Fluid.NONE) sees through lava, an eye does not");
    }
}
