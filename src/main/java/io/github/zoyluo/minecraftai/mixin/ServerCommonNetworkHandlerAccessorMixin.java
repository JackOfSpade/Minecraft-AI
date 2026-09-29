package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.network.ServerConnectionAccessor;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerAccessorMixin implements ServerConnectionAccessor {
    @Override
    @Accessor("connection")
    public abstract ClientConnection minecraftai$getConnection();
}
