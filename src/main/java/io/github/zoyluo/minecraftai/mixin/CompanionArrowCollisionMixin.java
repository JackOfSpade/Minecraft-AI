package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.entity.CompanionAllegiance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AbstractArrow overrides Projectile's collision predicate, so arrows and tridents need this companion hook too. */
@Mixin(AbstractArrow.class)
public abstract class CompanionArrowCollisionMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void minecraftai$passArrowThroughAllies(Entity candidate,
                                                    CallbackInfoReturnable<Boolean> callback) {
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (CompanionAllegiance.areAllies(arrow.getOwner(), candidate)) {
            callback.setReturnValue(false);
        }
    }
}
