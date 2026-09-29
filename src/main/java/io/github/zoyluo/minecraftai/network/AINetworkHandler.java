package io.github.zoyluo.minecraftai.network;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class AINetworkHandler extends ServerGamePacketListenerImpl {
    public AINetworkHandler(MinecraftServer server,
                            Connection connection,
                            ServerPlayer player,
                            CommonListenerCookie clientData) {
        super(server, connection, player, clientData);
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener callbacks) {
        DeliveredPackets.complete(callbacks);
    }

    @Override
    public void disconnect(Component reason) {
    }

    @Override
    public void disconnect(DisconnectionDetails disconnectionInfo) {
    }
}
