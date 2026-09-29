package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BotCommandC2S(String botName, String action, String arg1, String arg2, int count) implements CustomPacketPayload {
    public static final Type<BotCommandC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "bot_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotCommandC2S> CODEC = StreamCodec.ofMember(BotCommandC2S::write, BotCommandC2S::new);

    private BotCommandC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName);
        buf.writeUtf(action);
        buf.writeUtf(arg1);
        buf.writeUtf(arg2);
        buf.writeInt(count);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
