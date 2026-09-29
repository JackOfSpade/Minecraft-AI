package io.github.zoyluo.minecraftai.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the map vanilla publishes for other threads ({@code ChunkMap#visibleChunkMap}, replaced by a fresh clone on every
 * change and never mutated afterwards) so a Baritone search can read the loaded chunks without touching the server thread.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapVisibleChunksAccessorMixin {
    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> minecraftai$visibleChunkMap();
}
