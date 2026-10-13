package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A single Baritone-selected movement frame for the player who enabled auto sprint.
 *
 * <p>The route is planned on the server, but the normal client applies these keys so movement continues to use
 * vanilla client prediction and ordinary player movement packets. The yaw is deliberately separate from the camera:
 * it is the direction Baritone needs the player body to face.</p>
 */
public record AutoSprintControlS2C(boolean active, boolean forward, boolean back, boolean left, boolean right,
                                   boolean jump, boolean sneak, boolean sprint, float yaw, float pitch)
        implements CustomPacketPayload {
    public static final Type<AutoSprintControlS2C> ID = new Type<>(
            Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "autosprint_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AutoSprintControlS2C> CODEC =
            StreamCodec.ofMember(AutoSprintControlS2C::write, AutoSprintControlS2C::new);

    private AutoSprintControlS2C(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeBoolean(forward);
        buf.writeBoolean(back);
        buf.writeBoolean(left);
        buf.writeBoolean(right);
        buf.writeBoolean(jump);
        buf.writeBoolean(sneak);
        buf.writeBoolean(sprint);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /** Stops the local movement override and returns its camera to first person. */
    public static AutoSprintControlS2C stop() {
        return new AutoSprintControlS2C(false, false, false, false, false,
                false, false, false, 0.0F, 0.0F);
    }
}
