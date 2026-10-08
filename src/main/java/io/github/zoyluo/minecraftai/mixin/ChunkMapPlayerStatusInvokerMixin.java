package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Grants the companion respawn path access to vanilla's package-private chunk-player lifecycle hook. */
@Mixin(ChunkMap.class)
public interface ChunkMapPlayerStatusInvokerMixin {
    @Invoker("updatePlayerStatus")
    void minecraftai$updatePlayerStatus(ServerPlayer player, boolean active);
}
