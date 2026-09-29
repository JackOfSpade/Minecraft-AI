package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.network.ServerConnectionAccessor;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonNetworkHandlerAccessorMixin implements ServerConnectionAccessor {
    @Override
    @Accessor("connection")
    public abstract Connection minecraftai$getConnection();
}
