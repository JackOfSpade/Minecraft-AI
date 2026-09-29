package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SubscribeBotC2S(String botName, boolean subscribe) implements CustomPacketPayload {
    public static final Type<SubscribeBotC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "subscribe_bot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SubscribeBotC2S> CODEC = StreamCodec.ofMember(SubscribeBotC2S::write, SubscribeBotC2S::new);

    private SubscribeBotC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName);
        buf.writeBoolean(subscribe);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
