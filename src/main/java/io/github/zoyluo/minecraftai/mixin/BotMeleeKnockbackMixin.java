package io.github.zoyluo.minecraftai.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Melee knockback of a bot. When a player hits a {@code ServerPlayer}, {@code Player.causeExtraKnockback} sends the knockback to that
 * player's client as a {@code ClientboundSetEntityMotionPacket} and then <em>restores</em> the target's old server-side velocity: a
 * real player's movement is client authoritative, so the client applies the push and reports where it ends up. A bot has no client,
 * so the push was thrown away and a bot hit by a player (or another bot) was never knocked back. This makes the check
 * {@code target.hurtMarked} read false for an {@link AIPlayerEntity} only, which skips the packet and the restore: the velocity
 * that {@code LivingEntity.knockback} set (with the target's knockback resistance already applied) stays, the flag stays set and
 * {@code ServerEntity} broadcasts the motion as it does for any mob. A real player is untouched (the original call).
 *
 * <p>Other knockback paths need nothing: a mob's melee and projectiles never restore the velocity of a {@code ServerPlayer}.</p>
 */
@Mixin(Player.class)
public abstract class BotMeleeKnockbackMixin {
    @WrapOperation(method = "causeExtraKnockback",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;hurtMarked:Z", opcode = Opcodes.GETFIELD))
    private boolean minecraftai$botKeepsItsKnockback(Entity target, Operation<Boolean> original) {
        return !(target instanceof AIPlayerEntity) && original.call(target);
    }
}
