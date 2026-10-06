package io.github.zoyluo.minecraftai.mode;

import static io.github.zoyluo.minecraftai.testsupport.HitAssert.assertSameHit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.testsupport.FakeLevel;
import io.github.zoyluo.minecraftai.testsupport.VanillaRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

/**
 * Seeded random worlds against vanilla. The promise of the sight ray is that it changes nothing except what it is meant to skip:
 * through opaque blocks it is the vanilla ray, and through anything else it is the vanilla ray of the world with the see-through
 * blocks and the water taken out.
 */
class SightClipPropertyTest {
    static {
        // Before the Blocks constants below: touching Blocks on an unbootstrapped registry fails the class for good.
        VanillaRegistries.ensureReady();
    }

    private static final int SIZE_X = 12;
    private static final int SIZE_Y = 6;
    private static final int SIZE_Z = 12;
    private static final CollisionContext CONTEXT = CollisionContext.empty();
    private static final ClipContext.Block[] SHAPES = {ClipContext.Block.COLLIDER, ClipContext.Block.OUTLINE, ClipContext.Block.VISUAL};
    private static final ClipContext.Fluid[] FLUIDS = ClipContext.Fluid.values();

    /** Opaque blocks of every kind of shape: full cubes, slabs, stairs, doors, thin plates, walls, block entities. */
    private static final List<Block> OPAQUE_BLOCKS = List.of(Blocks.STONE, Blocks.OAK_PLANKS, Blocks.OAK_SLAB, Blocks.STONE_SLAB,
            Blocks.OAK_STAIRS, Blocks.OAK_DOOR, Blocks.IRON_DOOR, Blocks.OAK_TRAPDOOR, Blocks.CHEST, Blocks.WHITE_BED,
            Blocks.COBBLESTONE_WALL, Blocks.BLUE_ICE, Blocks.PACKED_ICE, Blocks.OAK_SIGN, Blocks.STONE_BUTTON,
            Blocks.STONE_PRESSURE_PLATE, Blocks.ANVIL, Blocks.HOPPER, Blocks.LANTERN, Blocks.CANDLE, Blocks.FLOWER_POT,
            Blocks.CAULDRON, Blocks.SHULKER_BOX, Blocks.ENCHANTING_TABLE, Blocks.BREWING_STAND, Blocks.CAKE, Blocks.LEVER,
            Blocks.POWDER_SNOW);

    /** Everything a bot sees through, with and without water, plus the water itself (never lava: it is opaque). */
    private static final List<Block> SEE_THROUGH_BLOCKS = List.of(Blocks.OAK_LEAVES, Blocks.CHERRY_LEAVES, Blocks.OAK_FENCE,
            Blocks.NETHER_BRICK_FENCE, Blocks.OAK_FENCE_GATE, Blocks.GLASS, Blocks.BLUE_STAINED_GLASS, Blocks.TINTED_GLASS,
            Blocks.GLASS_PANE, Blocks.IRON_BARS, Blocks.ICE, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.IRON_CHAIN,
            Blocks.LADDER, Blocks.SCAFFOLDING, Blocks.SHORT_GRASS, Blocks.WHEAT, Blocks.TORCH, Blocks.COBWEB, Blocks.VINE,
            Blocks.RAIL, Blocks.REDSTONE_WIRE, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.BUBBLE_COLUMN,
            Blocks.WATER, Blocks.NETHER_PORTAL, Blocks.SPAWNER, Blocks.BAMBOO);

    private static List<BlockState> statesOf(List<Block> blocks, boolean withFluid) {
        List<BlockState> states = new ArrayList<>();
        for (Block block : blocks) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (withFluid || state.getFluidState().isEmpty()) {
                    states.add(state);
                }
            }
        }
        return states;
    }

    private static FakeLevel randomWorld(Random random, List<BlockState> palette, double density) {
        FakeLevel level = new FakeLevel();
        for (int x = 0; x < SIZE_X; x++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    if (random.nextDouble() < density) {
                        level.set(x, y, z, palette.get(random.nextInt(palette.size())));
                    }
                }
            }
        }
        return level;
    }

    private static Vec3 randomPoint(Random random) {
        return new Vec3(random.nextDouble() * SIZE_X, random.nextDouble() * SIZE_Y, random.nextDouble() * SIZE_Z);
    }

    private static BlockHitResult vanilla(BlockGetter level, Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid) {
        return level.clip(new ClipContext(from, to, shape, fluid, CONTEXT));
    }

    @Test
    void throughOpaqueBlocksTheSightRayIsTheVanillaRayInEveryShapeAndFluidKind() {
        Random random = new Random(20261006L);
        List<BlockState> palette = statesOf(OPAQUE_BLOCKS, false);
        for (Block block : OPAQUE_BLOCKS) {
            assertFalse(SeeThrough.block(block), block + " must be opaque for this to be the opaque property");
        }
        int hits = 0;
        int rays = 0;
        for (int world = 0; world < 60; world++) {
            FakeLevel level = randomWorld(random, palette, 0.15D + 0.4D * random.nextDouble());
            for (int ray = 0; ray < 25; ray++) {
                Vec3 from = randomPoint(random);
                Vec3 to = randomPoint(random);
                for (ClipContext.Block shape : SHAPES) {
                    for (ClipContext.Fluid fluid : FLUIDS) {
                        BlockHitResult expected = vanilla(level, from, to, shape, fluid);
                        assertSameHit(expected, SightClip.clip(level, from, to, shape, fluid, CONTEXT, null),
                                "world " + world + " ray " + from + " -> " + to + " " + shape + " " + fluid);
                        rays++;
                        if (expected.getType() == HitResult.Type.BLOCK) {
                            hits++;
                        }
                    }
                }
            }
        }
        assertTrue(hits > rays / 10, "the worlds are dense enough for the rays to hit things: " + hits + " of " + rays);
        assertTrue(rays - hits > rays / 20, "and open enough for some to miss: " + hits + " of " + rays);
    }

    @Test
    void throughAnythingElseTheSightRayIsTheVanillaRayOfTheWorldWithTheSeeThroughBlocksAndWaterTakenOut() {
        Random random = new Random(7L);
        List<BlockState> palette = new ArrayList<>(statesOf(OPAQUE_BLOCKS, true));
        palette.addAll(statesOf(SEE_THROUGH_BLOCKS, true));
        for (int i = 0; i < 6; i++) {
            palette.addAll(statesOf(List.of(Blocks.OAK_LEAVES, Blocks.GLASS, Blocks.WATER, Blocks.OAK_FENCE), true));
        }
        int skipped = 0;
        for (int world = 0; world < 60; world++) {
            Random worldRandom = new Random(random.nextLong());
            FakeLevel level = randomWorld(worldRandom, palette, 0.2D + 0.4D * worldRandom.nextDouble());
            FakeLevel stripped = stripped(level);
            for (int ray = 0; ray < 25; ray++) {
                Vec3 from = randomPoint(worldRandom);
                Vec3 to = randomPoint(worldRandom);
                for (ClipContext.Block shape : SHAPES) {
                    for (ClipContext.Fluid fluid : FLUIDS) {
                        BlockHitResult sight = SightClip.clip(level, from, to, shape, fluid, CONTEXT, null);
                        assertSameHit(vanilla(stripped, from, to, shape, fluid), sight,
                                "world " + world + " ray " + from + " -> " + to + " " + shape + " " + fluid);
                        if (!sameAsVanilla(vanilla(level, from, to, shape, fluid), sight)) {
                            skipped++;
                        }
                    }
                }
            }
        }
        assertTrue(skipped > 500, "the rays really do pass through see-through blocks: " + skipped);
    }

    /** The same world with every see-through cell empty and every opaque block drained of its water. */
    private static FakeLevel stripped(FakeLevel level) {
        FakeLevel out = new FakeLevel();
        for (int x = 0; x < SIZE_X; x++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (SeeThrough.cell(state) || state.isAir()) {
                        continue;
                    }
                    if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                        state = state.setValue(BlockStateProperties.WATERLOGGED, false);
                    }
                    out.set(x, y, z, state);
                }
            }
        }
        return out;
    }

    private static boolean sameAsVanilla(BlockHitResult a, BlockHitResult b) {
        return a.getType() == b.getType() && a.getBlockPos().equals(b.getBlockPos()) && a.getLocation().equals(b.getLocation());
    }

    @Test
    void theLineOfSightTestAgreesWithVanillaOnWorldsOfOpaqueBlocks() {
        // LivingEntity.hasLineOfSight is "a COLLIDER ray ignoring fluids ends without a hit".
        Random random = new Random(128L);
        List<BlockState> palette = statesOf(OPAQUE_BLOCKS, false);
        int clear = 0;
        int blocked = 0;
        for (int world = 0; world < 60; world++) {
            FakeLevel level = randomWorld(random, palette, 0.03D + 0.2D * random.nextDouble());
            for (int ray = 0; ray < 50; ray++) {
                Vec3 from = randomPoint(random);
                Vec3 to = randomPoint(random);
                boolean vanillaClear = vanilla(level, from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE).getType()
                        == HitResult.Type.MISS;
                assertEquals(vanillaClear, SightClip.clear(level, CONTEXT, from, to), "world " + world + " " + from + " -> " + to);
                if (vanillaClear) {
                    clear++;
                } else {
                    blocked++;
                }
            }
        }
        assertTrue(clear > 100 && blocked > 100, "both answers occur: clear " + clear + " blocked " + blocked);
    }
}
