package io.github.zoyluo.minecraftai.mixin;

import baritone.utils.accessor.IChunkArray;
import baritone.utils.accessor.IClientChunkProvider;
import io.github.zoyluo.minecraftai.baritone.LoadedChunkSnapshot;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Baritone's {@code BlockStateInterface} asks {@code world.getChunkSource()} for a thread-safe copy of the loaded
 * chunks (upstream implements this on the client's {@code ClientChunkCache} with a client-only mixin, which is not part
 * of this build). On a server the copy is an O(1) {@link LoadedChunkSnapshot}.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheBaritoneMixin implements IClientChunkProvider {
    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    @Final
    private ServerLevel level;

    @Override
    public ChunkSource createThreadSafeCopy() {
        return new LoadedChunkSnapshot(level, ((ChunkMapVisibleChunksAccessorMixin) chunkMap).minecraftai$visibleChunkMap());
    }

    @Override
    public IChunkArray extractReferenceArray() {
        // The client's ring buffer of chunks does not exist here; nothing on the server path asks for it.
        throw new UnsupportedOperationException("ServerChunkCache has no client chunk array");
    }
}
