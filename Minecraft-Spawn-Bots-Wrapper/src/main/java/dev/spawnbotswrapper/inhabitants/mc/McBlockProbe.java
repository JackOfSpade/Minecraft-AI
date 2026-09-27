package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;
import dev.spawnbotswrapper.inhabitants.spawn.Cell;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BubbleColumnBlock;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.LavaCauldronBlock;
import net.minecraft.block.MagmaBlock;
import net.minecraft.block.PowderSnowBlock;
import net.minecraft.block.Portal;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.WitherRoseBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.WorldChunk;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link BlockProbe} over one loaded {@link ServerWorld} that can never load, generate or wait for a chunk.
 * <p>
 * Chunks come from {@link ServerChunkManager#getWorldChunk(int, int)}, which answers null unless the chunk
 * is fully loaded right now, and blocks are read from that {@link WorldChunk} itself. The usual
 * {@code World.getBlockState} is avoided on purpose: on an unloaded chunk it synchronously generates it, and
 * inside a chunk-load callback it would wait for the very task it is running in.
 * <p>
 * Shapes are queried against {@link EmptyBlockView}, exactly as vanilla builds its own per-state shape
 * cache, so the answer depends on the block state alone and never reaches into neighbouring chunks.
 * Server thread only; instances are cheap and meant to be short-lived (the state-to-cell memo is only
 * valid for the tags and blocks that existed when it was filled).
 */
public final class McBlockProbe implements BlockProbe {
    private final Chunks chunks;
    private final Supplier<WorldBorder> borderSource;
    private final int minY;
    private final int maxY;
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final Map<BlockState, Cell> memo = new IdentityHashMap<>();
    private WorldBorder border;

    /** How the probe asks for a chunk: the loaded chunk at these chunk coordinates, or null. Never loads. */
    interface Chunks {
        WorldChunk loaded(int chunkX, int chunkZ);
    }

    public McBlockProbe(ServerWorld world) {
        this(world.getChunkManager()::getWorldChunk, world.getBottomY(), world.getTopYInclusive(), world::getWorldBorder);
    }

    McBlockProbe(Chunks chunks, int minY, int maxY, Supplier<WorldBorder> border) {
        this.chunks = chunks;
        this.minY = minY;
        this.maxY = maxY;
        this.borderSource = border;
    }

    @Override
    public Cell cell(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return Cell.UNLOADED;
        }
        WorldChunk chunk = chunks.loaded(x >> 4, z >> 4);
        if (chunk == null) {
            return Cell.UNLOADED;
        }
        BlockState state = chunk.getBlockState(cursor.set(x, y, z));
        return memo.computeIfAbsent(state, s -> BlockClassifier.classify(factsOf(s)));
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public boolean insideBorder(int x, int z) {
        if (border == null) {
            border = borderSource.get();
        }
        return border.contains(cursor.set(x, 0, z));
    }

    /**
     * Reads the classification inputs of one block state. Never throws: a block whose collision shape cannot
     * be evaluated without a real world (some modded blocks) is treated as an opaque non-standable obstacle,
     * the conservative answer.
     */
    static BlockFacts factsOf(BlockState state) {
        if (state.isAir()) {
            return BlockFacts.NOTHING;
        }
        BlockFacts.Fluid fluid = fluidOf(state.getFluidState());
        boolean hazard = isHazard(state);
        try {
            VoxelShape shape = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            if (shape.isEmpty()) {
                return new BlockFacts(fluid, hazard, false, 0.0, false);
            }
            double top = shape.getMax(Direction.Axis.Y);
            boolean topFull = Block.isFaceFullSquare(shape, Direction.UP);
            return new BlockFacts(fluid, hazard, true, top, topFull);
        } catch (RuntimeException e) {
            return new BlockFacts(fluid, hazard, true, 1.0, false);
        }
    }

    private static BlockFacts.Fluid fluidOf(FluidState fluid) {
        if (fluid.isEmpty()) {
            return BlockFacts.Fluid.NONE;
        }
        if (fluid.isOf(Fluids.WATER) || fluid.isOf(Fluids.FLOWING_WATER) || fluidTagged(fluid, FluidTags.WATER)) {
            return BlockFacts.Fluid.WATER;
        }
        if (fluid.isOf(Fluids.LAVA) || fluid.isOf(Fluids.FLOWING_LAVA) || fluidTagged(fluid, FluidTags.LAVA)) {
            return BlockFacts.Fluid.LAVA;
        }
        return BlockFacts.Fluid.OTHER;
    }

    /**
     * Blocks that harm, trap or teleport an entity that touches them. Recognised by block CLASS (so modded
     * subclasses count) and by the vanilla tags modders are expected to extend, never by registry name.
     */
    static boolean isHazard(BlockState state) {
        Block block = state.getBlock();
        return block instanceof AbstractFireBlock
                || block instanceof CobwebBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof PowderSnowBlock
                || block instanceof WitherRoseBlock
                || block instanceof Portal
                || block instanceof BubbleColumnBlock
                || block instanceof CactusBlock
                || block instanceof MagmaBlock
                || block instanceof CampfireBlock
                || block instanceof LavaCauldronBlock
                || blockTagged(state, BlockTags.FIRE)
                || blockTagged(state, BlockTags.CAMPFIRES)
                || blockTagged(state, BlockTags.INVALID_SPAWN_INSIDE);
    }

    /** Tags are only bound once a server has loaded its data packs; an unbound tag simply does not match. */
    private static boolean blockTagged(BlockState state, TagKey<Block> tag) {
        try {
            return state.isIn(tag);
        } catch (IllegalStateException unbound) {
            return false;
        }
    }

    private static boolean fluidTagged(FluidState fluid, TagKey<net.minecraft.fluid.Fluid> tag) {
        try {
            return fluid.isIn(tag);
        } catch (IllegalStateException unbound) {
            return false;
        }
    }
}
