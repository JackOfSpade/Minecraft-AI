package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Companion to {@link AIPlayerControlledBoatLogicalSideMixin}: once the server becomes the logical
 * side for one of our {@link AIPlayerEntity}'s boats, {@code AbstractBoat.tick()} still only
 * calls its private {@code controlBoat()} -- the method that turns
 * {@code setInput(left, right, forward, back)} into yaw/velocity change -- under
 * {@code if (world.isClientSide)} (confirmed with javap: it sits right after the {@code
 * updateVelocity()} call, guarded by that check, followed by sending a
 * {@code ServerboundPaddleBoatPacket} that only makes sense from an actual client). A real client
 * applies its own inputs locally that way and reports the outcome back over the network; our fake
 * player has no client to do either half of that. Without this, {@code BoatSupport.steerToward()}
 * would keep calling {@code setInput(...)} forever with no server-side code ever consuming it.
 *
 * <p>This runs {@code controlBoat()} directly on the server, in the same relative position the
 * client would (immediately after {@code updateVelocity()}, before {@code move()}), and only when
 * the boat's controlling passenger is our own {@link AIPlayerEntity} -- a real player's boat is
 * untouched and keeps driving its paddles client-side exactly as before.</p>
 */
@Mixin(AbstractBoat.class)
abstract class AIPlayerControlledBoatPaddleMixin {
    @Invoker("controlBoat")
    abstract void minecraftai$invokeUpdatePaddles();

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;floatBoat()V",
            shift = At.Shift.AFTER))
    private void minecraftai$driveAiPlayerPaddlesServerSide(CallbackInfo ci) {
        AbstractBoat self = (AbstractBoat) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        if (self.getControllingPassenger() instanceof AIPlayerEntity) {
            this.minecraftai$invokeUpdatePaddles();
        }
    }
}
