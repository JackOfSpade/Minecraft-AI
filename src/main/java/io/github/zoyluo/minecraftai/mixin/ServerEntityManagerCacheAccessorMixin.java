package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes raw entity sections without filtering out their current tracking status. */
@Mixin(PersistentEntitySectionManager.class)
public interface ServerEntityManagerCacheAccessorMixin {
    @Accessor("sectionStorage")
    EntitySectionStorage<Entity> minecraftai$getSectionCache();
}
