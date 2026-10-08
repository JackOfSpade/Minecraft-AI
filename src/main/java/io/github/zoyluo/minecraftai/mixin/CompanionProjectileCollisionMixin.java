package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.entity.CompanionAllegiance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets a player/companion projectile keep travelling when its first entity collision is an ally. */
@Mixin(Projectile.class)
public abstract class CompanionProjectileCollisionMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void minecraftai$passProjectileThroughAllies(Entity candidate,
                                                         CallbackInfoReturnable<Boolean> callback) {
        Projectile projectile = (Projectile) (Object) this;
        if (CompanionAllegiance.areAllies(projectile.getOwner(), candidate)) {
            callback.setReturnValue(false);
        }
    }
}
