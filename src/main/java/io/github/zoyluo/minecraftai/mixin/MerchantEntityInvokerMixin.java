package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractVillager.class)
public interface MerchantEntityInvokerMixin {
    @Invoker("rewardTradeXp")
    void minecraftai$invokeAfterUsing(MerchantOffer offer);
}
