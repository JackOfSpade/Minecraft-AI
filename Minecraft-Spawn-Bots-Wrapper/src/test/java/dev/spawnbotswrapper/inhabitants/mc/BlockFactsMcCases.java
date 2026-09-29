package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.spawn.Cell;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.VoxelShape;

import static org.junit.jupiter.api.Assertions.*;

/** Classification of real vanilla blocks, run inside {@link McSandbox}. */
public final class BlockFactsMcCases {
    private BlockFactsMcCases() {
    }

    private static Cell cell(BlockState state) {
        return BlockClassifier.classify(McBlockProbe.factsOf(state));
    }

    private static Cell cell(Block block) {
        return cell(block.defaultBlockState());
    }

    private static void expect(Cell expected, Block... blocks) {
        for (Block block : blocks) {
            assertEquals(expected, cell(block), BuiltInRegistries.BLOCK.getKey(block) + " should be " + expected);
        }
    }

    public static void solidFullBlocksAreStandable() {
        expect(Cell.SOLID_STANDABLE, Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.OAK_PLANKS,
                Blocks.COBBLESTONE, Blocks.BEDROCK, Blocks.OBSIDIAN, Blocks.NETHERRACK, Blocks.IRON_BLOCK,
                Blocks.OAK_LOG, Blocks.GLASS, Blocks.BRICKS, Blocks.DEEPSLATE, Blocks.END_STONE, Blocks.SANDSTONE);
    }

    public static void airAndPassableDecorationAreEmpty() {
        expect(Cell.EMPTY, Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.SHORT_GRASS, Blocks.POPPY,
                Blocks.TORCH, Blocks.WALL_TORCH, Blocks.RED_CARPET, Blocks.OAK_PRESSURE_PLATE, Blocks.STONE_BUTTON,
                Blocks.LEVER, Blocks.RAIL, Blocks.VINE, Blocks.OAK_SIGN, Blocks.SNOW);
    }

    public static void partialAndTallShapesBlockButAreNotFloors() {
        expect(Cell.SOLID_OTHER, Blocks.OAK_FENCE, Blocks.COBBLESTONE_WALL, Blocks.OAK_FENCE_GATE, Blocks.IRON_BARS,
                Blocks.GLASS_PANE, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.CHEST, Blocks.RED_BED, Blocks.CAULDRON,
                Blocks.WATER_CAULDRON, Blocks.ANVIL, Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR, Blocks.LADDER,
                Blocks.FLOWER_POT, Blocks.SOUL_SAND, Blocks.HONEY_BLOCK);
    }

    public static void anOpenGateHasNoCollisionAndIsEmpty() {
        BlockState open = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        assertEquals(Cell.EMPTY, cell(open));
    }

    public static void anUpperSlabIsAFloorAtBlockHeightButALowerSlabIsNot() {
        BlockState top = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        BlockState bottom = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState full = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        assertEquals(Cell.SOLID_STANDABLE, cell(top));
        assertEquals(Cell.SOLID_OTHER, cell(bottom));
        assertEquals(Cell.SOLID_STANDABLE, cell(full));
    }

    public static void waterLavaAndWaterloggedBlocks() {
        assertEquals(Cell.WATER, cell(Blocks.WATER));
        assertEquals(Cell.HAZARD, cell(Blocks.LAVA));
        // A waterlogged slab or stairs still fills part of the column: standing there would put a bot's
        // feet in the solid half, not resting exactly where planned, so it must not read as open WATER.
        assertEquals(Cell.SOLID_OTHER, cell(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)),
                "a waterlogged slab still occupies half the column");
        assertEquals(Cell.SOLID_OTHER, cell(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)),
                "waterlogged stairs still occupy part of the column");
        assertEquals(Cell.SOLID_OTHER, cell(Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)),
                "a waterlogged fence still blocks movement");
        assertEquals(Cell.HAZARD, cell(Blocks.BUBBLE_COLUMN), "bubble columns push entities around");
    }

    public static void passableHazards() {
        expect(Cell.HAZARD, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE,
                Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY);
        Cell powder = cell(Blocks.POWDER_SNOW);
        assertTrue(powder == Cell.HAZARD || powder == Cell.SOLID_HAZARD, "powder snow is harmful either way: " + powder);
    }

    public static void solidHazards() {
        expect(Cell.SOLID_HAZARD, Blocks.CACTUS, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
                Blocks.LAVA_CAULDRON);
    }

    // Blocks cannot be constructed once the game's registries are frozen, so the "modded" blocks below are
    // allocated without running a constructor; that is enough, hazard detection only asks what a block IS.
    private static final class ModdedCactus extends CactusBlock {
        ModdedCactus(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    private static final class ModdedMagma extends MagmaBlock {
        ModdedMagma(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    private static final class ModdedFire extends FireBlock {
        ModdedFire(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    private static final class ModdedCobweb extends WebBlock {
        ModdedCobweb(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    private static final class ModdedBerries extends SweetBerryBushBlock {
        ModdedBerries(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    private static final class ModdedPortal extends NetherPortalBlock {
        ModdedPortal(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    private static final class ModdedBubbles extends BubbleColumnBlock {
        ModdedBubbles(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    private static BlockState stateOf(Block block) {
        BlockState state = McObjects.opaque(BlockState.class);
        McObjects.setField(state, net.minecraft.world.level.block.state.StateHolder.class, "owner", block);
        return state;
    }

    /** A modded block that extends a vanilla hazard is caught by class, with no registry name involved. */
    public static void moddedSubclassesOfVanillaHazardsAreRecognisedByClass() {
        for (Class<? extends Block> type : List.of(ModdedCactus.class, ModdedMagma.class, ModdedFire.class,
                ModdedCobweb.class, ModdedBerries.class, ModdedPortal.class, ModdedBubbles.class)) {
            assertTrue(McBlockProbe.isHazard(stateOf(McObjects.opaque(type))), type.getSimpleName());
        }
    }

    public static void ordinaryVanillaBlocksAreNotHazardsEvenWhenTagsAreNotBound() {
        for (Block block : List.of(Blocks.STONE, Blocks.OAK_PLANKS, Blocks.OAK_FENCE, Blocks.OAK_SLAB, Blocks.WATER,
                Blocks.SHORT_GRASS, Blocks.AIR, Blocks.CHEST)) {
            assertFalse(McBlockProbe.isHazard(block.defaultBlockState()), BuiltInRegistries.BLOCK.getKey(block).toString());
        }
    }

    /**
     * The geometry rules against vanilla's own view of every block state in the game: nothing throws (also with
     * tags unbound), full cubes are floors unless harmful, collision-free blocks are passable, and a state with
     * water in it is never reported as plain empty or standable.
     */
    public static void everyVanillaBlockStateIsClassifiedConsistently() {
        Map<Cell, Integer> histogram = new EnumMap<>(Cell.class);
        int states = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                states++;
                BlockFacts facts = McBlockProbe.factsOf(state);
                Cell cell = cell(state);
                histogram.merge(cell, 1, Integer::sum);
                String what = BuiltInRegistries.BLOCK.getKey(block) + " " + state + " -> " + cell + " " + facts;

                VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                boolean harmful = facts.hazard() || facts.fluid() != BlockFacts.Fluid.NONE && facts.fluid() != BlockFacts.Fluid.WATER;
                if (state.isAir()) {
                    assertEquals(Cell.EMPTY, cell, what);
                }
                if (shape.isEmpty() && !harmful && facts.fluid() == BlockFacts.Fluid.NONE) {
                    assertEquals(Cell.EMPTY, cell, what);
                }
                if (Block.isShapeFullBlock(shape) && !harmful && facts.fluid() == BlockFacts.Fluid.NONE) {
                    assertEquals(Cell.SOLID_STANDABLE, cell, what);
                }
                if (facts.fluid() == BlockFacts.Fluid.WATER && !harmful) {
                    assertTrue(cell == Cell.WATER || cell == Cell.SOLID_OTHER, what);
                }
                if (cell == Cell.SOLID_STANDABLE) {
                    assertFalse(harmful, what);
                    assertEquals(BlockFacts.Fluid.NONE, facts.fluid(), what);
                }
                assertNotEquals(Cell.UNLOADED, cell, what);
            }
        }
        assertTrue(states > 10_000, "expected the whole block registry, saw " + states + " states");
        for (Cell c : new Cell[]{Cell.EMPTY, Cell.WATER, Cell.HAZARD, Cell.SOLID_STANDABLE, Cell.SOLID_HAZARD, Cell.SOLID_OTHER}) {
            assertTrue(histogram.getOrDefault(c, 0) > 0, "no vanilla state classified as " + c + ": " + histogram);
        }
    }
}
