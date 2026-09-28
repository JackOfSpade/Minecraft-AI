package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client request to move an item between the player and the AI.
 * direction: 0=TAKE (take from the AI's inventory slot into the player's inventory), 1=PUT (put from the player's inventory slot into the AI's inventory).
 * slot: the slot index in the source container (TAKE=AI main slot, PUT=player inventory main slot).
 * amount: the desired amount to move (<=0 means the whole stack; the server clamps it to the actually movable amount).
 */
public record BotItemMoveC2S(String botName, int direction, int slot, int amount) implements CustomPayload {
    public static final int TAKE = 0;
    public static final int PUT = 1;

    public static final Id<BotItemMoveC2S> ID = new Id<>(Identifier.of(MinecraftAiMod.MOD_ID, "item_move"));
    public static final PacketCodec<RegistryByteBuf, BotItemMoveC2S> CODEC =
            PacketCodec.of(BotItemMoveC2S::write, BotItemMoveC2S::new);

    private BotItemMoveC2S(RegistryByteBuf buf) {
        this(buf.readString(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeVarInt(direction);
        buf.writeVarInt(slot);
        buf.writeVarInt(amount);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
