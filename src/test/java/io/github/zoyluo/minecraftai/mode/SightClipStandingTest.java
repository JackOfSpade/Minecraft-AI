package io.github.zoyluo.minecraftai.mode;

import static io.github.zoyluo.minecraftai.testsupport.HitAssert.assertHitsBlock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.testsupport.FakeLevel;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

/**
 * Eyes do not see through lava, with one exception: the lava a bot stands in, whose surface is below its eyes. A bot that fell
 * into a pool must still see the ground around it to climb out; lava anywhere else, or around an eye that is itself in the lava,
 * hides what lies behind it as always.
 */
class SightClipStandingTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockPos STANDING = new BlockPos(0, 0, 0);
    /** A standing bot's eye above its feet cell, looking down at the middle of the floor block beside the lava. */
    private static final Vec3 EYE = new Vec3(0.5D, 1.62D, 0.5D);
    private static final Vec3 INTO_FLOOR = new Vec3(1.5D, -0.5D, 0.5D);

    private static BlockHitResult ray(FakeLevel level, Vec3 from, Vec3 to, BlockPos standing, BlockPos target) {
        return level.clip(new SightClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty(), target, false, standing));
    }

    @Test
    void theLavaABotWadesInDoesNotHideTheGroundBesideIt() {
        FakeLevel level = new FakeLevel().set(0, 0, 0, LAVA).set(1, -1, 0, STONE);
        // The line to the floor beside the pool dips below the lava surface inside the bot's own cell.
        assertHitsBlock(ray(level, EYE, INTO_FLOOR, null, null), STANDING, "to anyone else the lava is in the way");
        assertHitsBlock(ray(level, EYE, INTO_FLOOR, STANDING, null), new BlockPos(1, -1, 0), "the bot sees the floor it can climb to");
    }

    @Test
    void theLavaABotWadesInIsSkippedButStillRecordedAsLavaSoNoRecorderStoresAirUnderItsFeet() {
        FakeLevel level = new FakeLevel().set(0, 0, 0, LAVA).set(1, -1, 0, STONE);
        SightClipContext context = new SightClipContext(EYE, INTO_FLOOR, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty(), null, true, STANDING);
        assertHitsBlock(level.clip(context), new BlockPos(1, -1, 0), "the floor is seen past the lava the bot stands in");
        assertEquals(List.of(new SightClipContext.Crossing(STANDING, LAVA)), context.crossed(),
                "the cell the ray skipped is the hazard it is, never free air");
        SightClipContext quiet = new SightClipContext(EYE, INTO_FLOOR, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty(), null, false, STANDING);
        level.clip(quiet);
        assertTrue(quiet.crossed().isEmpty(), "nothing is recorded unless asked for");
    }

    @Test
    void lavaInAnyOtherCellStillHidesWhatLiesBehindIt() {
        FakeLevel level = new FakeLevel().set(1, 0, 0, LAVA).set(2, -1, 0, STONE);
        Vec3 into = new Vec3(2.5D, -0.5D, 0.5D);
        assertHitsBlock(ray(level, EYE, into, STANDING, null), new BlockPos(1, 0, 0), "the next pool is opaque");
    }

    @Test
    void anEyeInsideTheLavaIsBlindEvenInItsOwnCell() {
        FakeLevel level = new FakeLevel().set(0, 0, 0, LAVA).set(1, 0, 0, STONE);
        Vec3 submerged = new Vec3(0.5D, 0.5D, 0.5D);
        BlockHitResult hit = ray(level, submerged, new Vec3(1.5D, 0.5D, 0.5D), STANDING, null);
        assertHitsBlock(hit, STANDING, "an eye in the lava sees nothing");
        assertTrue(hit.isInside());
    }

    @Test
    void observingTheLavaCellYouStandInStillHitsIt() {
        FakeLevel level = new FakeLevel().set(0, 0, 0, LAVA);
        Vec3 into = new Vec3(0.5D, 0.5D, 0.5D);
        assertHitsBlock(ray(level, EYE, into, STANDING, STANDING), STANDING, "the target cell is never skipped");
    }
}
