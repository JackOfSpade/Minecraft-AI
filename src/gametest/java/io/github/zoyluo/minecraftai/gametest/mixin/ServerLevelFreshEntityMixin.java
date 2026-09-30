package io.github.zoyluo.minecraftai.gametest.mixin;

import io.github.zoyluo.minecraftai.gametest.GameTestEntityGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test-only (GameTest source set): every entity that code adds to a level ({@code addFreshEntity}, {@code addWithUUID},
 * {@code addDuringTeleport}) goes through {@code ServerLevel.addEntity}; an entity that appears any other way was loaded from a chunk
 * (generated with it, or saved with it by an earlier test). See {@link GameTestEntityGate}.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelFreshEntityMixin {
    @Inject(method = "addEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"))
    private void minecraftai$markFresh(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        GameTestEntityGate.markFresh(entity);
    }
}
