package io.github.zoyluo.minecraftai.network;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

public class FakeClientConnection extends Connection {
    private static final SocketAddress FAKE_ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    public FakeClientConnection(PacketFlow side) {
        super(side);
        ((ClientConnectionAccessor) this).minecraftai$setChannel(new EmbeddedChannel());
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> state, T packetListener) {
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> state) {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener) {
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener callbacks) {
        DeliveredPackets.complete(callbacks);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener callbacks, boolean flush) {
        DeliveredPackets.complete(callbacks);
    }

    @Override
    public void disconnect(Component disconnectReason) {
    }

    @Override
    public void disconnect(DisconnectionDetails disconnectionInfo) {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return FAKE_ADDRESS;
    }

    @Override
    public String getLoggableAddress(boolean useSnooperSetting) {
        return "127.0.0.1";
    }
}
