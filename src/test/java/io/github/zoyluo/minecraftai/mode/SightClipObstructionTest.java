package io.github.zoyluo.minecraftai.mode;

import static io.github.zoyluo.minecraftai.testsupport.HitAssert.assertHitsBlock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.mode.SightClipContext.Crossing;
import io.github.zoyluo.minecraftai.testsupport.FakeLevel;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What the sight ray reports besides its hit: which skipped see-through blocks a vanilla pick ray along the same segment would
 * still hit ({@link SightClipContext#obstructions()}, modelled with the OUTLINE shape), and which cells it crossed
 * ({@link SightClipContext#crossed()}). Rays run from the middle of cell (0,1,0) along +x.
 */
class SightClipObstructionTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final Vec3 EYE = new Vec3(0.5D, 1.5D, 0.5D);
    private static final Vec3 INTO_LOG = new Vec3(5.001D, 1.5D, 0.5D);
    private static final BlockPos LOG_POS = new BlockPos(5, 1, 0);

    /** One ray: what it hit and what its context recorded on the way. */
    private record Trace(BlockHitResult hit, SightClipContext context) {
        boolean reachObstructed() {
            return context.reachObstructed();
        }

        List<Crossing> obstructions() {
            return context.obstructions();
        }

        List<Crossing> crossed() {
            return context.crossed();
        }
    }

    private static Trace trace(FakeLevel level, Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid,
                               BlockPos target, boolean recordCrossed) {
        SightClipContext context = SightClip.context(from, to, shape, fluid, CollisionContext.empty(), target, recordCrossed)
                .trackObstructions();
        return new Trace(level.clip(context), context);
    }

    private static Trace trace(FakeLevel level, ClipContext.Block shape) {
        return trace(level, EYE, INTO_LOG, shape, ClipContext.Fluid.ANY, null, true);
    }

    private static List<BlockPos> positions(List<Crossing> crossings) {
        return crossings.stream().map(Crossing::pos).toList();
    }

    private static BlockPos at(int x) {
        return new BlockPos(x, 1, 0);
    }

    @ParameterizedTest
    @EnumSource(value = ClipContext.Block.class, names = {"COLLIDER", "OUTLINE"})
    void aLeafOnTheSegmentIsAnObstructionOfTheClickLineWhateverShapeTheSightRayUses(ClipContext.Block shape) {
        Trace t = trace(new FakeLevel().set(2, 1, 0, LEAF).set(5, 1, 0, LOG), shape);
        assertHitsBlock(t.hit(), LOG_POS, "the log is seen");
        assertTrue(t.reachObstructed());
        assertEquals(List.of(new Crossing(at(2), LEAF)), t.obstructions());
    }

    @Test
    void aRayThatMeetsNothingSeeThroughIsNotObstructed() {
        Trace t = trace(new FakeLevel().set(2, 1, 0, STONE).set(5, 1, 0, LOG), ClipContext.Block.COLLIDER);
        assertHitsBlock(t.hit(), at(2), "the stone is the hit");
        assertFalse(t.reachObstructed());
        assertTrue(t.obstructions().isEmpty());
        assertTrue(t.crossed().isEmpty());
    }

    @Test
    void aRayPassingBesideAFencePostCrossesTheCellButNotTheFence() {
        // The post is 6/16 to 10/16 of the cell wide: a ray 0.2 into the cell slips past it, through a gap of the fence line.
        FakeLevel level = new FakeLevel().set(2, 1, 0, FENCE).set(5, 1, 0, LOG);
        Trace t = trace(level, new Vec3(0.5D, 1.5D, 0.2D), new Vec3(5.001D, 1.5D, 0.2D), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, null, true);
        assertHitsBlock(t.hit(), LOG_POS, "sight");
        assertFalse(t.reachObstructed(), "a click line there reaches the log");
        assertTrue(t.obstructions().isEmpty());
        assertEquals(List.of(new Crossing(at(2), FENCE)), t.crossed(), "but the cell is a fence, not air");
    }

    @Test
    void aFenceArmIsAnObstructionAtAnyHeight() {
        BlockState arms = FENCE.setValue(FenceBlock.NORTH, true).setValue(FenceBlock.SOUTH, true);
        FakeLevel level = new FakeLevel().set(2, 1, 0, arms).set(5, 1, 0, LOG);
        for (double y : new double[] {1.125D, 1.5D, 1.875D}) {
            Trace t = trace(level, new Vec3(0.5D, y, 0.2D), new Vec3(5.001D, y, 0.2D), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, null, false);
            assertHitsBlock(t.hit(), LOG_POS, "sight at " + y);
            assertEquals(List.of(new Crossing(at(2), arms)), t.obstructions(), "height " + y);
        }
    }

    @Test
    void anOpenGateHasNoCollisionButItsOutlineStillStopsTheClickLine() {
        // A player walks through an open gate, yet the crosshair still lands on its panel: the outline does not follow OPEN.
        BlockPos pos = at(2);
        BlockState open = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true);
        assertTrue(open.getCollisionShape(new FakeLevel(), pos, CollisionContext.empty()).isEmpty());
        assertNotNull(open.getShape(new FakeLevel(), pos, CollisionContext.empty()).clip(EYE, INTO_LOG, pos));
        FakeLevel level = new FakeLevel().set(2, 1, 0, open).set(5, 1, 0, LOG);

        Trace collider = trace(level, ClipContext.Block.COLLIDER);
        assertHitsBlock(collider.hit(), LOG_POS, "nothing collides with an open gate");
        assertEquals(List.of(new Crossing(pos, open)), collider.obstructions());
        assertEquals(List.of(new Crossing(pos, open)), collider.crossed(), "the collider ray never needed to skip it, but the cell is a gate");

        Trace outline = trace(level, ClipContext.Block.OUTLINE);
        assertHitsBlock(outline.hit(), LOG_POS, "the outline ray skips the gate");
        assertEquals(List.of(new Crossing(pos, open)), outline.obstructions());
        assertEquals(List.of(new Crossing(pos, open)), outline.crossed());
    }

    @Test
    void aFencePostOnTheRayIsAnObstruction() {
        Trace t = trace(new FakeLevel().set(2, 1, 0, FENCE).set(5, 1, 0, LOG), ClipContext.Block.COLLIDER);
        assertEquals(List.of(new Crossing(at(2), FENCE)), t.obstructions());
    }

    @Test
    void obstructionsAreListedNearestTheEyeFirstAndWaterIsNeverOne() {
        FakeLevel level = new FakeLevel().set(1, 1, 0, LEAF).set(2, 1, 0, FENCE).set(3, 1, 0, GLASS).set(4, 1, 0, WATER)
                .set(5, 1, 0, LOG);
        Trace t = trace(level, ClipContext.Block.COLLIDER);
        assertHitsBlock(t.hit(), LOG_POS, "the log is seen");
        assertEquals(List.of(new Crossing(at(1), LEAF), new Crossing(at(2), FENCE), new Crossing(at(3), GLASS)), t.obstructions());
        assertEquals(List.of(at(1), at(2), at(3), at(4)), positions(t.crossed()), "every skipped cell, water included, in ray order");
        assertEquals(WATER, t.crossed().get(3).state());
    }

    @Test
    void theListsStopWhereTheRayStops() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, STONE).set(4, 1, 0, LEAF).set(5, 1, 0, LOG);
        Trace t = trace(level, ClipContext.Block.COLLIDER);
        assertHitsBlock(t.hit(), at(3), "the stone is the hit");
        assertEquals(List.of(at(2)), positions(t.obstructions()));
        assertEquals(List.of(at(2)), positions(t.crossed()));
    }

    @Test
    void waterAloneNeverObstructsAHandButIsStillRecordedWhateverFluidTheRayAsksFor() {
        for (ClipContext.Fluid fluid : new ClipContext.Fluid[] {ClipContext.Fluid.ANY, ClipContext.Fluid.NONE}) {
            FakeLevel level = new FakeLevel().set(2, 1, 0, WATER).set(3, 1, 0, WATER).set(5, 1, 0, LOG);
            Trace t = trace(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, fluid, null, true);
            assertHitsBlock(t.hit(), LOG_POS, "fluid " + fluid);
            assertFalse(t.reachObstructed(), "fluid " + fluid + ": a pick ray ignores fluids");
            assertEquals(List.of(at(2), at(3)), positions(t.crossed()), "fluid " + fluid);
        }
    }

    @Test
    void aWaterloggedFenceIsRecordedOnceWithItsRealState() {
        BlockState wet = FENCE.setValue(BlockStateProperties.WATERLOGGED, true);
        Trace t = trace(new FakeLevel().set(2, 1, 0, wet).set(5, 1, 0, LOG), ClipContext.Block.COLLIDER);
        assertEquals(List.of(new Crossing(at(2), wet)), t.crossed());
        assertEquals(List.of(new Crossing(at(2), wet)), t.obstructions());
    }

    @Test
    void aWaterloggedSlabIsCrossedByItsWaterAboveTheSlabAndDoesNotObstruct() {
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        Trace t = trace(new FakeLevel().set(2, 1, 0, slab).set(5, 1, 0, LOG), new Vec3(0.5D, 1.75D, 0.5D),
                new Vec3(5.001D, 1.75D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null, true);
        assertHitsBlock(t.hit(), LOG_POS, "over the slab");
        assertFalse(t.reachObstructed());
        assertEquals(List.of(new Crossing(at(2), slab)), t.crossed(), "the cell holds a slab full of water, not air");
    }

    @Test
    void aThingOnlyTheClickLineWouldHitIsListedEvenThoughTheSightRayNeverNeededToSkipIt() {
        // A torch has no collision: a collider ray passes it unaided, but the crosshair (outline) would hit it.
        BlockState torch = Blocks.TORCH.defaultBlockState();
        Vec3 low = new Vec3(0.5D, 1.3D, 0.5D);
        Vec3 lowEnd = new Vec3(5.001D, 1.3D, 0.5D);
        FakeLevel level = new FakeLevel().set(2, 1, 0, torch).set(5, 1, 0, LOG);
        Trace collider = trace(level, low, lowEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null, true);
        assertHitsBlock(collider.hit(), LOG_POS, "the log behind the torch is seen");
        assertEquals(List.of(new Crossing(at(2), torch)), collider.obstructions());
        assertEquals(List.of(new Crossing(at(2), torch)), collider.crossed(),
                "the collider ray walks through it unaided, yet the cell holds a torch and a recorder must never store air there");
        Trace outline = trace(level, low, lowEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, null, true);
        assertHitsBlock(outline.hit(), LOG_POS, "the log behind the torch is seen");
        assertEquals(List.of(new Crossing(at(2), torch)), outline.obstructions());
        assertEquals(List.of(new Crossing(at(2), torch)), outline.crossed(), "an outline ray skipped it");
    }

    @Test
    void theTargetIsNeverListedAsAnObstructionOrACrossing() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, LEAF);
        Trace t = trace(level, EYE, new Vec3(3.001D, 1.5D, 0.5D), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, at(3), true);
        assertHitsBlock(t.hit(), at(3), "the target leaf is what the ray ends at");
        assertEquals(List.of(at(2)), positions(t.obstructions()));
        assertEquals(List.of(at(2)), positions(t.crossed()));
    }

    @Test
    void lavaIsNeitherAnObstructionNorACrossingBecauseItIsTheHit() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, Blocks.LAVA.defaultBlockState()).set(5, 1, 0, LOG);
        Trace t = trace(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null, true);
        assertHitsBlock(t.hit(), at(3), "the lava");
        assertEquals(List.of(at(2)), positions(t.obstructions()));
        assertEquals(List.of(at(2)), positions(t.crossed()));
    }

    @Test
    void anEyeInsideALeafStartsInsideAnObstruction() {
        Trace t = trace(new FakeLevel().set(0, 1, 0, LEAF).set(5, 1, 0, LOG), ClipContext.Block.COLLIDER);
        assertHitsBlock(t.hit(), LOG_POS, "the log is seen");
        assertEquals(List.of(new Crossing(at(0), LEAF)), t.obstructions());
    }

    @Test
    void crossedCellsAreRecordedOnlyWhenAskedForAndTheListsCannotBeChanged() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(5, 1, 0, LOG);
        Trace quiet = trace(level, EYE, INTO_LOG, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null, false);
        assertTrue(quiet.crossed().isEmpty());
        assertTrue(quiet.reachObstructed(), "the obstruction was asked for");
        assertThrows(UnsupportedOperationException.class, () -> quiet.obstructions().clear());
        Trace recording = trace(level, ClipContext.Block.COLLIDER);
        assertThrows(UnsupportedOperationException.class, () -> recording.crossed().clear());
        assertThrows(UnsupportedOperationException.class, () -> recording.crossed().add(new Crossing(at(9), LEAF)));
    }

    @Test
    void theObstructionsOfThePickRayAreTrackedOnlyWhenAskedFor() {
        // A leaf, a torch the outline ray meets and a fence arm: all three are on the pick line, none is reported untracked.
        BlockState arms = FENCE.setValue(FenceBlock.NORTH, true).setValue(FenceBlock.SOUTH, true);
        FakeLevel level = new FakeLevel().set(1, 1, 0, LEAF).set(3, 1, 0, Blocks.TORCH.defaultBlockState()).set(4, 1, 0, arms)
                .set(5, 1, 0, LOG);
        Vec3 from = new Vec3(0.5D, 1.3D, 0.5D);
        Vec3 to = new Vec3(5.001D, 1.3D, 0.5D);
        SightClipContext untracked = SightClip.context(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty(), null, true);
        assertHitsBlock(level.clip(untracked), LOG_POS, "sight is the same either way");
        assertFalse(untracked.reachObstructed());
        assertTrue(untracked.obstructions().isEmpty());
        assertEquals(List.of(at(1), at(3), at(4)), positions(untracked.crossed()), "the crossings are recorded independently");

        Trace tracked = trace(level, from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null, true);
        assertHitsBlock(tracked.hit(), LOG_POS, "sight");
        assertEquals(List.of(at(1), at(3), at(4)), positions(tracked.obstructions()));
        assertEquals(positions(untracked.crossed()), positions(tracked.crossed()), "tracking never changes what is recorded");
    }

    @Test
    void thePickFactoryIsTheLineAClickFollowsWaterPassedLavaStoppingAndEveryOutlineInTheWay() {
        FakeLevel level = new FakeLevel().set(1, 1, 0, WATER).set(2, 1, 0, LEAF).set(5, 1, 0, LOG);
        SightClipContext click = SightClip.pick(EYE, INTO_LOG, CollisionContext.empty(), null, null);
        assertHitsBlock(level.clip(click), LOG_POS, "the log is the end of the line");
        assertEquals(List.of(new Crossing(at(2), LEAF)), click.obstructions(), "the leaf is what the click lands on, the water is not");
        assertTrue(click.crossed().isEmpty(), "a click line records nothing but what it would hit");

        FakeLevel lava = new FakeLevel().set(1, 1, 0, WATER).set(3, 1, 0, Blocks.LAVA.defaultBlockState()).set(5, 1, 0, LOG);
        assertHitsBlock(lava.clip(SightClip.pick(EYE, INTO_LOG, CollisionContext.empty(), null, null)), at(3),
                "a hand does not reach what the eyes cannot see through");

        // The same shapes the vanilla pick ray uses, so the click and the factory never disagree on a gap.
        FakeLevel gap = new FakeLevel().set(2, 1, 0, FENCE).set(5, 1, 0, LOG);
        SightClipContext beside = SightClip.pick(new Vec3(0.5D, 1.5D, 0.2D), new Vec3(5.001D, 1.5D, 0.2D),
                CollisionContext.empty(), null, null);
        assertHitsBlock(gap.clip(beside), LOG_POS, "past the post");
        assertFalse(beside.reachObstructed(), "a click through the gap of a fence line lands on the log");
        BlockHitResult vanilla = gap.clip(new ClipContext(new Vec3(0.5D, 1.5D, 0.2D), new Vec3(5.001D, 1.5D, 0.2D),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        assertHitsBlock(vanilla, LOG_POS, "vanilla's own pick ray agrees");
    }
}
