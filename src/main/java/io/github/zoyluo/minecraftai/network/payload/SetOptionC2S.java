package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetOptionC2S(String botName, String key, boolean value) implements CustomPacketPayload {
    public static final Type<SetOptionC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "set_option"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetOptionC2S> CODEC = StreamCodec.ofMember(SetOptionC2S::write, SetOptionC2S::new);

    private SetOptionC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName);
        buf.writeUtf(key);
        buf.writeBoolean(value);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
