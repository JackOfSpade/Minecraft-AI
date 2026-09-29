package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BotChatS2C(String botName, String role, String text) implements CustomPacketPayload {
    public static final Type<BotChatS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "bot_chat"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotChatS2C> CODEC = StreamCodec.ofMember(BotChatS2C::write, BotChatS2C::new);

    private BotChatS2C(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readUtf(), buf.readUtf());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName);
        buf.writeUtf(role);
        buf.writeUtf(text);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
