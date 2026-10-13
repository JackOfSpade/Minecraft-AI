package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Re-registers a retained fake player with vanilla's entity tracker after its death lifecycle. */
@Mixin(ChunkMap.class)
public interface ChunkMapEntityTrackerInvokerMixin {
    @Invoker("addEntity")
    void minecraftai$addEntity(Entity entity);
}
