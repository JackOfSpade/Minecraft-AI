package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;
import dev.spawnbotswrapper.inhabitants.spawn.Cell;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LavaCauldronBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@link BlockProbe} over one loaded {@link ServerLevel} that can never load, generate or wait for a chunk.
 * <p>
 * Chunks come from {@link ServerChunkCache#getChunkNow(int, int)}, which answers null unless the chunk
 * is fully loaded right now, and blocks are read from that {@link LevelChunk} itself. The usual
 * {@code Level.getBlockState} is avoided on purpose: on an unloaded chunk it synchronously generates it, and
 * inside a chunk-load callback it would wait for the very task it is running in.
 * <p>
 * Shapes are queried against {@link EmptyBlockGetter}, exactly as vanilla builds its own per-state shape
 * cache, so the answer depends on the block state alone and never reaches into neighbouring chunks.
 * Server thread only; instances are cheap and meant to be short-lived (the state-to-cell memo is only
 * valid for the tags and blocks that existed when it was filled).
 */
public final class McBlockProbe implements BlockProbe {
    private final Chunks chunks;
    private final Supplier<WorldBorder> borderSource;
    private final int minY;
    private final int maxY;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final Map<BlockState, Cell> memo = new IdentityHashMap<>();
    private WorldBorder border;

    /** How the probe asks for a chunk: the loaded chunk at these chunk coordinates, or null. Never loads. */
    interface Chunks {
        LevelChunk loaded(int chunkX, int chunkZ);
    }

    public McBlockProbe(ServerLevel world) {
        this(world.getChunkSource()::getChunkNow, world.getMinY(), world.getMaxY(), world::getWorldBorder);
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
        LevelChunk chunk = chunks.loaded(x >> 4, z >> 4);
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
        return border.isWithinBounds(cursor.set(x, 0, z));
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
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                return new BlockFacts(fluid, hazard, false, 0.0, false);
            }
            double top = shape.max(Direction.Axis.Y);
            boolean topFull = Block.isFaceFull(shape, Direction.UP);
            return new BlockFacts(fluid, hazard, true, top, topFull);
        } catch (RuntimeException e) {
            return new BlockFacts(fluid, hazard, true, 1.0, false);
        }
    }

    private static BlockFacts.Fluid fluidOf(FluidState fluid) {
        if (fluid.isEmpty()) {
            return BlockFacts.Fluid.NONE;
        }
        if (fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER) || fluidTagged(fluid, FluidTags.WATER)) {
            return BlockFacts.Fluid.WATER;
        }
        if (fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA) || fluidTagged(fluid, FluidTags.LAVA)) {
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
        return block instanceof BaseFireBlock
                || block instanceof WebBlock
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
            return state.is(tag);
        } catch (IllegalStateException unbound) {
            return false;
        }
    }

    private static boolean fluidTagged(FluidState fluid, TagKey<net.minecraft.world.level.material.Fluid> tag) {
        try {
            return fluid.is(tag);
        } catch (IllegalStateException unbound) {
            return false;
        }
    }
}
