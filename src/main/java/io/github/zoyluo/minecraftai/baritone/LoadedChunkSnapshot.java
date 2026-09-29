package io.github.zoyluo.minecraftai.baritone;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
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
