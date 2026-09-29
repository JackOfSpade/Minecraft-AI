package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's {@code Entity.isLocalInstanceAuthoritative()} treats any boat with a real
 * {@code Player} passenger as client-authoritative: on the server it always returns false
 * (confirmed with javap -- the server branch is exactly {@code !isClientAuthoritative()}, and
 * {@code isClientAuthoritative()} is true whenever the controlling passenger is a
 * {@code Player}). A real client then simulates the boat locally and reports the result back
 * with a {@code ServerboundMoveVehiclePacket} every tick; our fake {@link AIPlayerEntity} has no client to
 * send that packet, so a boat it rides never moves on the server -- confirmed live (boat position
 * unchanged for ~140 ticks while {@code steerToward} was actively calling {@code setInput}).
 *
 * <p>This makes the server the logical (authoritative) side for a boat's movement specifically
 * when its controlling passenger is our own {@link AIPlayerEntity}, so
 * {@code AbstractBoat.tick()} runs its normal physics (updateVelocity/move/collisions)
 * server-side exactly as it would for a client, instead of zeroing the boat's velocity and
 * waiting forever. A boat driven by a real player, or any other vehicle (horse, minecart, pig,
 * etc.), is completely unaffected: both continue through the untouched vanilla path.</p>
 *
 * @see AIPlayerControlledBoatPaddleMixin
 */
@Mixin(Entity.class)
abstract class AIPlayerControlledBoatLogicalSideMixin {
    @Inject(method = "isLocalInstanceAuthoritative", at = @At("HEAD"), cancellable = true)
    private void minecraftai$aiPlayerBoatIsServerAuthoritative(CallbackInfoReturnable<Boolean> cir) {
        if (!(((Object) this) instanceof AbstractBoat boat)) {
            return;
        }
        if (boat.level().isClientSide()) {
            return;
        }
        if (boat.getControllingPassenger() instanceof AIPlayerEntity) {
            cir.setReturnValue(true);
        }
    }
}
