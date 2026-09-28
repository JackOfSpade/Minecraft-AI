package io.github.zoyluo.aibot.network;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class AINetworkHandler extends ServerPlayNetworkHandler {
    public AINetworkHandler(MinecraftServer server,
                            ClientConnection connection,
                            ServerPlayerEntity player,
                            ConnectedClientData clientData) {
        super(server, connection, player, clientData);
    }

    @Override
    public void sendPacket(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener callbacks) {
        DeliveredPackets.complete(callbacks);
    }

    @Override
    public void disconnect(Text reason) {
    }

    @Override
    public void disconnect(DisconnectionInfo disconnectionInfo) {
    }
}
