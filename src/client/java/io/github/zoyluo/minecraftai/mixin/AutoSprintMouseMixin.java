package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.client.AutoSprintClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps mouse look on the free-look camera rather than rotating the player body Baritone is steering. */
@Mixin(MouseHandler.class)
public abstract class AutoSprintMouseMixin {
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void minecraftai$turnAutoSprintCamera(LocalPlayer player, double yawDelta, double pitchDelta) {
        if (!AutoSprintClient.INSTANCE.rotateCamera(yawDelta, pitchDelta)) {
            player.turn(yawDelta, pitchDelta);
        }
    }
}
