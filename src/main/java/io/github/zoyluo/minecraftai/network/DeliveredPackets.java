package io.github.zoyluo.minecraftai.network;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * The bots have no socket, so a "sent" packet is complete the moment it is handed over. Since 1.21.11
 * the send callback is a netty {@link ChannelFutureListener}; this reports it as already succeeded.
 */
final class DeliveredPackets {
    private static final EmbeddedChannel CHANNEL = new EmbeddedChannel();

    private DeliveredPackets() {
    }

    static void complete(ChannelFutureListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.operationComplete(CHANNEL.newSucceededFuture());
        } catch (Exception e) {
            throw new IllegalStateException("packet callback failed", e);
        }
    }
}
