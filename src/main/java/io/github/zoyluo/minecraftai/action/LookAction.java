package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class LookAction {
    private LookAction() {
    }

    /**
     * Converts any accumulated entity yaw into vanilla's canonical display range.  Baritone and
     * a restored fake player may legitimately carry an unwrapped angle internally, but persisting
     * or broadcasting thousands of degrees makes the client interpolate needless full turns.
     */
    public static float canonicalYaw(float yaw) {
        return Mth.wrapDegrees(yaw);
    }

    public static ActionResult setYawPitch(AIPlayerEntity player, float yaw, float pitch) {
        float canonicalYaw = canonicalYaw(yaw);
        float clampedPitch = Mth.clamp(pitch, -90.0F, 90.0F);
        player.setYRot(canonicalYaw);
        player.setYHeadRot(canonicalYaw);
        player.setYBodyRot(canonicalYaw);
        player.setXRot(clampedPitch);
        return ActionResult.SUCCESS;
    }

    public static ActionResult lookAt(AIPlayerEntity player, Vec3 target) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        return setYawPitch(player, player.getYRot(), player.getXRot());
    }

    /**
     * Tilts the head toward {@code target} and leaves the body/head yaw untouched.  For callers that
     * want the bot to look at something while a path or walk controller is steering it: the
     * controllers face the direction of travel, and a full {@link #lookAt} between ticks would turn
     * the body toward the target and walk the bot into whatever lies between them.
     */
    public static ActionResult lookPitchAt(AIPlayerEntity player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        double dy = target.y - eye.y;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        return ActionResult.SUCCESS;
    }

    public static ActionResult lookAtBlock(AIPlayerEntity player, BlockPos pos, Direction face) {
        Vec3 target = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
        return lookAt(player, target);
    }

    public static ActionResult lookHorizontallyAt(AIPlayerEntity player, Vec3 target) {
        Vec3 current = player.position();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D));
        return setYawPitch(player, yaw, player.getXRot());
    }
}
