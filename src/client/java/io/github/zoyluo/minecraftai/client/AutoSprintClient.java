package io.github.zoyluo.minecraftai.client;

import io.github.zoyluo.minecraftai.network.payload.AutoSprintControlS2C;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Client half of server-planned auto sprint: applies the current Baritone input frame and owns the orbit camera. */
public final class AutoSprintClient {
    public static final AutoSprintClient INSTANCE = new AutoSprintClient();

    private boolean active;
    private boolean keysApplied;
    private AutoSprintControlS2C controls = AutoSprintControlS2C.stop();
    private float cameraYaw;
    private float cameraPitch;

    private AutoSprintClient() {
    }

    /** Receives a server-authoritative movement frame on the client thread. */
    public void apply(AutoSprintControlS2C payload, Minecraft client) {
        if (!payload.active()) {
            deactivate(client);
            return;
        }
        if (!active) {
            cameraYaw = payload.yaw();
            cameraPitch = payload.pitch();
        }
        active = true;
        controls = payload;
        // This feature deliberately owns the perspective for its entire lifetime: the player's look is now camera-only.
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /** Applies the latest route frame after vanilla has polled the physical keyboard, ready for the next player tick. */
    public void tick(Minecraft client) {
        if (!active) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            deactivate(client);
            return;
        }
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        // Keep the body pointed where Baritone planned. Mouse input is redirected to cameraYaw/cameraPitch below.
        player.setYRot(controls.yaw());
        player.setYHeadRot(controls.yaw());
        player.setYBodyRot(controls.yaw());
        player.setXRot(controls.pitch());

        client.options.keyUp.setDown(controls.forward());
        client.options.keyDown.setDown(controls.back());
        client.options.keyLeft.setDown(controls.left());
        client.options.keyRight.setDown(controls.right());
        client.options.keyJump.setDown(controls.jump());
        client.options.keyShift.setDown(controls.sneak());
        client.options.keySprint.setDown(controls.sprint());
        player.setSprinting(controls.sprint());
        keysApplied = true;
    }

    /** Called from the mouse mixin instead of turning the LocalPlayer while auto sprint is active. */
    public boolean rotateCamera(double yawDelta, double pitchDelta) {
        if (!active) {
            return false;
        }
        cameraYaw = Mth.wrapDegrees(cameraYaw + (float) yawDelta * 0.15F);
        cameraPitch = Mth.clamp(cameraPitch + (float) pitchDelta * 0.15F, -90.0F, 90.0F);
        return true;
    }

    public boolean active() {
        return active;
    }

    public float cameraYaw() {
        return cameraYaw;
    }

    public float cameraPitch() {
        return cameraPitch;
    }

    private void deactivate(Minecraft client) {
        if (keysApplied) {
            // Do not clear bindings to false here. A physical key that is already held does not
            // emit another press event after an override ends, so doing that stranded movement
            // until the player released and pressed it again. Vanilla's poll restores every
            // binding from the keyboard/mouse state immediately, including held keys.
            KeyMapping.setAll();
            if (client.player != null) {
                client.player.setSprinting(client.options.keySprint.isDown());
            }
        }
        active = false;
        keysApplied = false;
        controls = AutoSprintControlS2C.stop();
        client.options.setCameraType(CameraType.FIRST_PERSON);
    }
}
