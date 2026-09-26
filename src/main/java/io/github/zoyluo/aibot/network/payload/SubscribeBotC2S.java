package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SubscribeBotC2S(String botName, boolean subscribe) implements CustomPayload {
    public static final Id<SubscribeBotC2S> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "subscribe_bot"));
    public static final PacketCodec<RegistryByteBuf, SubscribeBotC2S> CODEC = PacketCodec.of(SubscribeBotC2S::write, SubscribeBotC2S::new);

    private SubscribeBotC2S(RegistryByteBuf buf) {
        this(buf.readString(), buf.readBoolean());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeBoolean(subscribe);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
