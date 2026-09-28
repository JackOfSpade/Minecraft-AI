package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client-initiated teleport request.
 * direction: 0=TO_AI (teleports the player to a standable block near the AI), 1=RECALL_AI (teleports the AI to a standable block near the player).
 */
public record BotTeleportC2S(String botName, int direction) implements CustomPayload {
    public static final int TO_AI = 0;
    public static final int RECALL_AI = 1;

    public static final Id<BotTeleportC2S> ID = new Id<>(Identifier.of(MinecraftAiMod.MOD_ID, "teleport"));
    public static final PacketCodec<RegistryByteBuf, BotTeleportC2S> CODEC =
            PacketCodec.of(BotTeleportC2S::write, BotTeleportC2S::new);

    private BotTeleportC2S(RegistryByteBuf buf) {
        this(buf.readString(), buf.readVarInt());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeVarInt(direction);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
