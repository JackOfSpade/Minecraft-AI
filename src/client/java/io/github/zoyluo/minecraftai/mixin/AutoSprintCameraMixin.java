package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.client.AutoSprintClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses the independent free-look angles before vanilla places the third-person camera behind the player. */
@Mixin(Camera.class)
public abstract class AutoSprintCameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    // 1.21.11 first calls setRotation for the minecart-only branch. The walking-player branch is ordinal 1.
    @Inject(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1, shift = At.Shift.AFTER))
    private void minecraftai$useAutoSprintCameraRotation(CallbackInfo ci) {
        if (AutoSprintClient.INSTANCE.active()) {
            setRotation(AutoSprintClient.INSTANCE.cameraYaw(), AutoSprintClient.INSTANCE.cameraPitch());
        }
    }
}
