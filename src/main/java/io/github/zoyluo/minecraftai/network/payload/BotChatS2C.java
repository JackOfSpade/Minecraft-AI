package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record BotChatS2C(String botName, String role, String text) implements CustomPayload {
    public static final Id<BotChatS2C> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "bot_chat"));
    public static final PacketCodec<RegistryByteBuf, BotChatS2C> CODEC = PacketCodec.of(BotChatS2C::write, BotChatS2C::new);

    private BotChatS2C(RegistryByteBuf buf) {
        this(buf.readString(), buf.readString(), buf.readString());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeString(role);
        buf.writeString(text);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
