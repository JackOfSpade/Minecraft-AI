package io.github.zoyluo.minecraftai.mixin;

import baritone.api.utils.BlockOptionalMeta;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code BlockOptionalMeta} rolls loot tables on a stub {@link ServerLevel} that has no server. A real level keeps
 * using its own registries (the original call, so any other mod that wraps or redirects the same call still sees it); only the
 * stub is handed the registry holder Baritone built for it. A non-exclusive {@code @WrapOperation}, not a {@code @Redirect}:
 * this mixin is applied at start-up whatever the navigation engine is, and another mod may inject into the same call.
 */
@Mixin(LootContext.Builder.class)
public abstract class BaritoneLootContextBuilderMixin {
    @Shadow
    public abstract ServerLevel getLevel();

    @WrapOperation(method = "create",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;reloadableRegistries()Lnet/minecraft/server/ReloadableServerRegistries$Holder;"))
    private ReloadableServerRegistries.Holder minecraftai$registriesForBaritoneStub(
            MinecraftServer server, Operation<ReloadableServerRegistries.Holder> original) {
        if (server != null) {
            return original.call(server);
        }
        if (getLevel() instanceof BlockOptionalMeta.ServerLevelStub stub) {
            return stub.holder();
        }
        return null;
    }
}
