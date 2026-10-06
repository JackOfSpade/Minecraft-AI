package io.github.zoyluo.minecraftai.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.mining.assist.RayGrid;
import io.github.zoyluo.minecraftai.mode.SightClipContext.Crossing;
import io.github.zoyluo.minecraftai.testsupport.FakeLevel;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

/**
 * What a recorder that walks the cells of a bot's eye ray stores ({@code ObservedNavigationFence.scanRay},
 * {@code SharedWorldSight.captureRay}). The eye passes through leaves, fences, glass and water, and Baritone plans on what is
 * stored, so a cell the ray crossed must keep the state it really holds and must never become air. The cells are walked with
 * {@link RayGrid}, as the recorders do, while the ray itself is vanilla's own traversal driven by {@link SightClipContext}:
 * the two traversals have to agree on every cell a see-through block occupies.
 */
class SightRecordingTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final Vec3 EYE = new Vec3(0.5D, 1.5D, 0.5D);

    /** The ray as {@code ObservableWorldQuery.castViewRay} reports it, from vanilla's traversal. */
    private static ObservableWorldQuery.ViewHit castSight(FakeLevel level, Vec3 from, Vec3 to, ClipContext.Block shape) {
        SightClipContext context = SightClip.context(from, to, shape, ClipContext.Fluid.ANY, CollisionContext.empty(), null, true);
        BlockHitResult hit = level.clip(context);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return new ObservableWorldQuery.ViewHit(false, null, null, from.distanceTo(to), null, context.crossed());
        }
        return new ObservableWorldQuery.ViewHit(true, hit.getBlockPos(), hit.getDirection(), from.distanceTo(hit.getLocation()),
                level.getBlockState(hit.getBlockPos()), context.crossed());
    }

    /** What the fence's {@code scanRay} stores: every cell the ray passed through, in ray order. */
    private static Map<BlockPos, BlockState> record(ObservableWorldQuery.ViewHit view, Vec3 from, Vec3 to) {
        Vec3 direction = to.subtract(from).normalize();
        Map<BlockPos, BlockState> stored = new LinkedHashMap<>();
        RayGrid.traverse(from.x, from.y, from.z, direction.x, direction.y, direction.z, view.distance(), (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState seen = view.seenState(pos);
            stored.put(pos, seen != null ? seen : AIR);
            return true;
        });
        if (view.hit()) {
            stored.put(view.pos(), view.state());
        }
        return stored;
    }

    private static BlockPos at(int x) {
        return new BlockPos(x, 1, 0);
    }

    @Test
    void theLogBehindTwoLeavesIsStoredAsALogAndTheLeavesAsLeavesNeverAsAir() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, LEAF).set(5, 1, 0, LOG);
        Vec3 to = new Vec3(8.5D, 1.5D, 0.5D);
        Map<BlockPos, BlockState> stored = record(castSight(level, EYE, to, ClipContext.Block.COLLIDER), EYE, to);

        assertEquals(LEAF, stored.get(at(2)), "the first leaf is remembered as a leaf, which Baritone cannot walk through");
        assertEquals(LEAF, stored.get(at(3)));
        assertEquals(AIR, stored.get(at(1)));
        assertEquals(AIR, stored.get(at(4)), "the open cell between the leaves and the log is empty space");
        assertEquals(LOG, stored.get(at(5)));
        assertFalse(stored.containsKey(at(6)), "nothing behind the first opaque block is known");
    }

    @Test
    void theStrictRayStopsAtTheFirstLeafAndKnowsNothingBehindIt() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(3, 1, 0, LEAF).set(5, 1, 0, LOG);
        Vec3 to = new Vec3(8.5D, 1.5D, 0.5D);
        ClipContext strict = new ClipContext(EYE, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty());
        BlockHitResult hit = level.clip(strict);
        ObservableWorldQuery.ViewHit view = new ObservableWorldQuery.ViewHit(true, hit.getBlockPos(), hit.getDirection(),
                EYE.distanceTo(hit.getLocation()), level.getBlockState(hit.getBlockPos()));
        Map<BlockPos, BlockState> stored = record(view, EYE, to);

        assertEquals(LEAF, stored.get(at(2)));
        assertFalse(stored.containsKey(at(3)), "the strict ray, the sweeper's, does not see past the leaf");
        assertFalse(stored.containsKey(at(5)));
    }

    @Test
    void fencesGlassAndWaterKeepTheirStateWhileTheRayContinuesToTheStoneBehindThem() {
        FakeLevel level = new FakeLevel().set(1, 1, 0, FENCE).set(2, 1, 0, GLASS).set(3, 1, 0, WATER).set(4, 1, 0, WATER)
                .set(6, 1, 0, STONE);
        Vec3 to = new Vec3(9.5D, 1.5D, 0.5D);
        Map<BlockPos, BlockState> stored = record(castSight(level, EYE, to, ClipContext.Block.COLLIDER), EYE, to);

        assertEquals(FENCE, stored.get(at(1)));
        assertEquals(GLASS, stored.get(at(2)));
        assertEquals(WATER, stored.get(at(3)), "water is remembered as water: a swimmer plans on it, a walker avoids it");
        assertEquals(WATER, stored.get(at(4)));
        assertEquals(AIR, stored.get(at(5)));
        assertEquals(STONE, stored.get(at(6)));
    }

    @Test
    void lavaBehindWaterIsStoredAsLavaAndHidesWhatLiesBeyondIt() {
        FakeLevel level = new FakeLevel().set(2, 1, 0, WATER).set(3, 1, 0, LAVA).set(5, 1, 0, LOG);
        Vec3 to = new Vec3(8.5D, 1.5D, 0.5D);
        Map<BlockPos, BlockState> stored = record(castSight(level, EYE, to, ClipContext.Block.COLLIDER), EYE, to);

        assertEquals(WATER, stored.get(at(2)));
        assertEquals(LAVA, stored.get(at(3)), "a hazard the ray reached is never forgotten, whatever lay in front of it");
        assertFalse(stored.containsKey(at(5)), "eyes do not see through lava");
    }

    @Test
    void aSeeThroughTargetIsTheHitAndIsStoredAsWhatItIs() {
        // The sweeper of a tree asks about the leaf itself: with the leaf as the target the ray ends in it.
        FakeLevel level = new FakeLevel().set(2, 1, 0, LEAF).set(5, 1, 0, LOG);
        SightClipContext context = SightClip.context(EYE, new Vec3(2.001D, 1.5D, 0.5D), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, CollisionContext.empty(), at(2), true);
        BlockHitResult hit = level.clip(context);
        assertEquals(at(2), hit.getBlockPos());
        assertTrue(context.crossed().isEmpty(), "the target is hit, not crossed");
    }

    @Test
    void seenStateAnswersTheHitTheCrossedCellsAndNothingElse() {
        BlockPos leaf = new BlockPos(2, 1, 0);
        BlockPos log = new BlockPos(5, 1, 0);
        ObservableWorldQuery.ViewHit view = new ObservableWorldQuery.ViewHit(true, log, null, 4.5D, LOG, List.of(new Crossing(leaf, LEAF)));
        assertEquals(LOG, view.seenState(log));
        assertEquals(LEAF, view.seenState(leaf));
        assertNull(view.seenState(new BlockPos(3, 1, 0)), "empty space: the recorder stores air");

        ObservableWorldQuery.ViewHit miss = new ObservableWorldQuery.ViewHit(false, null, null, 8.0D, null, List.of(new Crossing(leaf, LEAF)));
        assertEquals(LEAF, miss.seenState(leaf), "a ray that found nothing behind the leaf still crossed it");
        assertNull(miss.seenState(log));

        ObservableWorldQuery.ViewHit strict = new ObservableWorldQuery.ViewHit(true, leaf, null, 1.5D, LEAF);
        assertTrue(strict.crossed().isEmpty());
        assertEquals(LEAF, strict.seenState(leaf));
        assertNull(ObservableWorldQuery.ViewHit.unknown().seenState(leaf));
        assertTrue(ObservableWorldQuery.ViewHit.unknown().isUnknown());
    }

    /**
     * The property the recorders stand on, over random worlds and rays in every direction: a see-through cell that the DDA the
     * recorder walks visits before the hit is a cell vanilla's traversal skipped, so it is stored as itself. A disagreement
     * between the two traversals at an edge or a corner would store foliage as air, which is the failure this guards.
     */
    @Test
    void everySeeThroughCellAnyRayCrossesIsStoredAsItselfOverRandomWorlds() {
        BlockState[] palette = {AIR, AIR, AIR, LEAF, LEAF, FENCE, GLASS, WATER, STONE};
        Random random = new Random(0x5EE7_1356L);
        int checked = 0;
        for (int world = 0; world < 60; world++) {
            FakeLevel level = new FakeLevel();
            BlockState[][][] cells = new BlockState[14][6][14];
            for (int x = 0; x < 14; x++) {
                for (int y = 0; y < 6; y++) {
                    for (int z = 0; z < 14; z++) {
                        cells[x][y][z] = palette[random.nextInt(palette.length)];
                        level.set(x, y, z, cells[x][y][z]);
                    }
                }
            }
            for (int ray = 0; ray < 40; ray++) {
                for (ClipContext.Block shape : new ClipContext.Block[] {ClipContext.Block.COLLIDER, ClipContext.Block.OUTLINE}) {
                    Vec3 from = new Vec3(3.0D + random.nextDouble() * 8.0D, 1.0D + random.nextDouble() * 4.0D,
                            3.0D + random.nextDouble() * 8.0D);
                    Vec3 to = new Vec3(random.nextDouble() * 14.0D, random.nextDouble() * 6.0D, random.nextDouble() * 14.0D);
                    if (from.distanceToSqr(to) < 1.0D) {
                        continue;
                    }
                    ObservableWorldQuery.ViewHit view = castSight(level, from, to, shape);
                    Map<BlockPos, BlockState> stored = record(view, from, to);
                    for (Map.Entry<BlockPos, BlockState> entry : stored.entrySet()) {
                        BlockPos pos = entry.getKey();
                        BlockState real = level.getBlockState(pos);
                        boolean isHit = view.hit() && pos.equals(view.pos());
                        boolean solidToTheRay = shape == ClipContext.Block.COLLIDER
                                ? !real.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty()
                                : !real.getShape(level, pos, CollisionContext.empty()).isEmpty();
                        if (!isHit && SeeThrough.cell(real) && !real.isAir() && (solidToTheRay || !real.getFluidState().isEmpty())) {
                            assertEquals(real, entry.getValue(), "ray " + from + " -> " + to + " (" + shape + ") stored the "
                                    + real + " at " + pos.toShortString() + " as " + entry.getValue());
                            checked++;
                        }
                        if (isHit) {
                            assertEquals(real, entry.getValue());
                        }
                    }
                }
            }
        }
        assertTrue(checked > 2000, "the random worlds exercised the property: " + checked);
    }
}
