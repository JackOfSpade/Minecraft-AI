package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the server's complete section cache for collision checks during HIDDEN-section ticks. */
@Mixin(ServerLevel.class)
public interface ServerWorldEntityManagerAccessorMixin {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> minecraftai$getEntityManager();
}
