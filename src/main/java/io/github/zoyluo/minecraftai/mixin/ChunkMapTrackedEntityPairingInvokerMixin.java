package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.server.level.ServerEntity;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Replays one viewer's vanilla remove/add pairing for a tracked entity.
 *
 * <p>This is deliberately narrower than rebuilding the viewer's chunk subscriptions: it refreshes
 * just the companion entity and preserves the owner's loaded world state.</p>
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface ChunkMapTrackedEntityPairingInvokerMixin {
    @Accessor("serverEntity")
    ServerEntity minecraftai$getServerEntity();

    @Accessor("seenBy")
    Set<ServerPlayerConnection> minecraftai$getSeenBy();

    @Invoker("removePlayer")
    void minecraftai$removePlayer(ServerPlayer player);

    @Invoker("updatePlayer")
    void minecraftai$updatePlayer(ServerPlayer player);
}
