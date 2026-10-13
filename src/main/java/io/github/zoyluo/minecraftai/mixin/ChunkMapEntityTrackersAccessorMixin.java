package io.github.zoyluo.minecraftai.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the live vanilla tracker that owns the server/client pairing for one entity. */
@Mixin(ChunkMap.class)
public interface ChunkMapEntityTrackersAccessorMixin {
    @Accessor("entityMap")
    Int2ObjectMap<?> minecraftai$getEntityTrackers();
}
