package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.entity.CompanionAllegiance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A melee click on an ally targets the enemy directly behind them when one is on the legal attack ray. */
@Mixin(Player.class)
public abstract class CompanionMeleePassThroughMixin {
    @ModifyVariable(method = "attack", at = @At("HEAD"), argsOnly = true)
    private Entity minecraftai$passMeleeThroughAllies(Entity selected) {
        return CompanionAllegiance.passThroughMeleeAlly((Player) (Object) this, selected);
    }
}
