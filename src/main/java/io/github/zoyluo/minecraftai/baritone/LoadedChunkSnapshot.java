package io.github.zoyluo.minecraftai.baritone;

import baritone.utils.accessor.IClientChunkProvider;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * A read-only, never-blocking view of the chunks a {@link ServerLevel} had loaded at one instant, safe to read from
 * Baritone's path-finding thread.
 *
 * <p>{@code ServerChunkCache.getChunk} would hop onto the server thread and wait for it when called from anywhere
 * else, and {@code getChunkNow} returns null off-thread. Neither is usable from a worker. The map this class wraps is
 * {@code ChunkMap#visibleChunkMap}: vanilla never mutates it after publishing, it swaps in a fresh clone whenever a
 * chunk is added or removed. Holding the reference is therefore an O(1) snapshot, and reading a holder's finished
 * chunk is a volatile read. Only chunks that reached {@link ChunkStatus#FULL} are handed out (the same rule as
 * {@code ServerChunkCache.getChunkNow}), which includes chunks outside the simulation distance that a
 * ticking-chunk lookup would miss. The chunk <em>contents</em> stay live, exactly as on the client.</p>
 */
public final class LoadedChunkSnapshot extends ChunkSource {
    private final ServerLevel level;
    private final Long2ObjectMap<ChunkHolder> holders;

    public LoadedChunkSnapshot(ServerLevel level, Long2ObjectMap<ChunkHolder> holders) {
        this.level = level;
        this.holders = holders;
    }

    /**
     * Captures the same never-loading full-chunk view Baritone gives a planning worker. This is deliberately obtained from the
     * server-thread chunk-source adapter rather than through {@code ServerLevel#getChunk}: it cannot request generation or wait on
     * the server thread. A missing adapter fails closed; a direct owner-follow route then has no cells to plan through.
     */
    public static LoadedChunkSnapshot capture(ServerLevel level) {
        Object source = level == null ? null : level.getChunkSource();
        if (!(source instanceof IClientChunkProvider provider)) {
            return null;
        }
        ChunkSource copy = provider.createThreadSafeCopy();
        return copy instanceof LoadedChunkSnapshot snapshot ? snapshot : null;
    }

    /** True only while this snapshot still has a fully generated/readable chunk at the requested column. */
    public boolean hasFullChunk(int chunkX, int chunkZ) {
        return getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) instanceof LevelChunk chunk && !chunk.isEmpty();
    }

    /** True when this snapshot can supply a state for the exact block coordinate. */
    public boolean hasCell(int x, int y, int z) {
        int relativeY = y - level.dimensionType().minY();
        return relativeY >= 0 && relativeY < level.dimensionType().height() && hasFullChunk(x >> 4, z >> 4);
    }

    /** Whether this immutable snapshot belongs to the supplied live level (teleports/dimension changes must fail closed). */
    public boolean belongsTo(ServerLevel candidate) {
        return level == candidate;
    }

    /**
     * Reads a block state only from this snapshot's full chunks. It returns null rather than consulting Baritone's cache or the live
     * {@code Level} when the column was not captured (or has since become unavailable), which is what lets owner-follow expose
     * coordinate navigation without an unseen-terrain cache leak.
     */
    public BlockState stateAt(int x, int y, int z) {
        int relativeY = y - level.dimensionType().minY();
        if (!hasCell(x, y, z)) {
            return null;
        }
        ChunkAccess access = getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk) || chunk.isEmpty()) {
            return null;
        }
        LevelChunkSection section = chunk.getSections()[relativeY >> 4];
        return section == null ? null
                : section.hasOnlyAir() ? Blocks.AIR.defaultBlockState()
                : section.getBlockState(x & 15, relativeY & 15, z & 15);
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) {
        // load is ignored on purpose: this view never triggers chunk loading or generation
        ChunkHolder holder = holders.get(ChunkPos.asLong(x, z));
        if (holder == null) {
            return null;
        }
        ChunkAccess chunk = holder.getChunkIfPresent(status);
        if (status == ChunkStatus.FULL && !(chunk instanceof LevelChunk)) {
            return null; // ChunkSource#getChunk(int, int, boolean) casts a FULL result to LevelChunk
        }
        return chunk;
    }

    @Override
    public void tick(BooleanSupplier hasTimeLeft, boolean tickChunks) {
    }

    @Override
    public String gatherStats() {
        return "LoadedChunkSnapshot: " + holders.size();
    }

    @Override
    public int getLoadedChunksCount() {
        return holders.size();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public BlockGetter getLevel() {
        return level;
    }
}
