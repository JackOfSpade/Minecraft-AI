package io.github.zoyluo.minecraftai.mixin;

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code BlockOptionalMeta} rolls loot tables on a stub {@link ServerLevel} that has no server. A real level keeps
 * using its own registries; only the stub is handed the registry holder Baritone built for it.
 */
@Mixin(LootContext.Builder.class)
public abstract class BaritoneLootContextBuilderMixin {
    @Shadow
    public abstract ServerLevel getLevel();

    @Redirect(method = "create",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;reloadableRegistries()Lnet/minecraft/server/ReloadableServerRegistries$Holder;"))
    private ReloadableServerRegistries.Holder minecraftai$registriesForBaritoneStub(MinecraftServer server) {
        if (server != null) {
            return server.reloadableRegistries();
        }
        if (getLevel() instanceof BlockOptionalMeta.ServerLevelStub stub) {
            return stub.holder();
        }
        return null;
    }
}
