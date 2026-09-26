package io.github.zoyluo.aibot.mixin;

import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MerchantEntity.class)
public interface MerchantEntityInvokerMixin {
    @Invoker("afterUsing")
    void aibot$invokeAfterUsing(TradeOffer offer);
}
