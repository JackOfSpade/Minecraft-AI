package io.github.zoyluo.minecraftai.network;

import io.netty.channel.Channel;

public interface ClientConnectionAccessor {
    void minecraftai$setChannel(Channel channel);
}
