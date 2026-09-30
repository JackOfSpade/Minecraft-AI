package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code Villager#updateSpecialPrices}: what opening a villager's trade screen does to the offers for that player. */
@Mixin(Villager.class)
public interface VillagerInvokerMixin {
    @Invoker("updateSpecialPrices")
    void minecraftai$invokeUpdateSpecialPrices(Player player);
}
