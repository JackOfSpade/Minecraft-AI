package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-initiated teleport request.
 * direction: 0=TO_AI (teleports the player to a standable block near the AI), 1=RECALL_AI (teleports the AI to a standable block near the player).
 */
public record BotTeleportC2S(String botName, int direction) implements CustomPacketPayload {
    public static final int TO_AI = 0;
    public static final int RECALL_AI = 1;

    public static final Type<BotTeleportC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "teleport"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotTeleportC2S> CODEC =
            StreamCodec.ofMember(BotTeleportC2S::write, BotTeleportC2S::new);

    private BotTeleportC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName);
        buf.writeVarInt(direction);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
