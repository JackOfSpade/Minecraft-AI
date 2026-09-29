package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Companion to {@link AIPlayerControlledBoatLogicalSideMixin}: once the server becomes the logical
 * side for one of our {@link AIPlayerEntity}'s boats, {@code AbstractBoatEntity.tick()} still only
 * calls its private {@code updatePaddles()} -- the method that turns
 * {@code setInputs(left, right, forward, back)} into yaw/velocity change -- under
 * {@code if (world.isClient)} (confirmed with javap: it sits right after the {@code
 * updateVelocity()} call, guarded by that check, followed by sending a
 * {@code BoatPaddleStateC2SPacket} that only makes sense from an actual client). A real client
 * applies its own inputs locally that way and reports the outcome back over the network; our fake
 * player has no client to do either half of that. Without this, {@code BoatSupport.steerToward()}
 * would keep calling {@code setInputs(...)} forever with no server-side code ever consuming it.
 *
 * <p>This runs {@code updatePaddles()} directly on the server, in the same relative position the
 * client would (immediately after {@code updateVelocity()}, before {@code move()}), and only when
 * the boat's controlling passenger is our own {@link AIPlayerEntity} -- a real player's boat is
 * untouched and keeps driving its paddles client-side exactly as before.</p>
 */
@Mixin(AbstractBoatEntity.class)
abstract class AIPlayerControlledBoatPaddleMixin {
    @Invoker("updatePaddles")
    abstract void minecraftai$invokeUpdatePaddles();

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/vehicle/AbstractBoatEntity;updateVelocity()V",
            shift = At.Shift.AFTER))
    private void minecraftai$driveAiPlayerPaddlesServerSide(CallbackInfo ci) {
        AbstractBoatEntity self = (AbstractBoatEntity) (Object) this;
        if (self.getEntityWorld().isClient()) {
            return;
        }
        if (self.getControllingPassenger() instanceof AIPlayerEntity) {
            this.minecraftai$invokeUpdatePaddles();
        }
    }
}
