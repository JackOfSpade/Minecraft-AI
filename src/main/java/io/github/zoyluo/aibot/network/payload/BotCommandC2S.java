package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record BotCommandC2S(String botName, String action, String arg1, String arg2, int count) implements CustomPayload {
    public static final Id<BotCommandC2S> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "bot_command"));
    public static final PacketCodec<RegistryByteBuf, BotCommandC2S> CODEC = PacketCodec.of(BotCommandC2S::write, BotCommandC2S::new);

    private BotCommandC2S(RegistryByteBuf buf) {
        this(buf.readString(), buf.readString(), buf.readString(), buf.readString(), buf.readInt());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeString(action);
        buf.writeString(arg1);
        buf.writeString(arg2);
        buf.writeInt(count);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
