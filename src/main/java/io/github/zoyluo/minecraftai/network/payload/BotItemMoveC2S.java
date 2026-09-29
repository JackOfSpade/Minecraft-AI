package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client request to move an item between the player and the AI.
 * direction: 0=TAKE (take from the AI's inventory slot into the player's inventory), 1=PUT (put from the player's inventory slot into the AI's inventory).
 * slot: the slot index in the source container (TAKE=AI main slot, PUT=player inventory main slot).
 * amount: the desired amount to move (<=0 means the whole stack; the server clamps it to the actually movable amount).
 */
public record BotItemMoveC2S(String botName, int direction, int slot, int amount) implements CustomPacketPayload {
    public static final int TAKE = 0;
    public static final int PUT = 1;

    public static final Type<BotItemMoveC2S> ID = new Type<>(Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "item_move"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotItemMoveC2S> CODEC =
            StreamCodec.ofMember(BotItemMoveC2S::write, BotItemMoveC2S::new);

    private BotItemMoveC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName);
        buf.writeVarInt(direction);
        buf.writeVarInt(slot);
        buf.writeVarInt(amount);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
