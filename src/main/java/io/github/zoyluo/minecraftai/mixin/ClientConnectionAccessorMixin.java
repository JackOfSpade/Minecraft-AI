package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.network.ClientConnectionAccessor;
import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionAccessorMixin implements ClientConnectionAccessor {
    @Override
    @Accessor("channel")
    public abstract void minecraftai$setChannel(Channel channel);

    @Override
    @Accessor("channel")
    public abstract Channel minecraftai$getChannel();
}
