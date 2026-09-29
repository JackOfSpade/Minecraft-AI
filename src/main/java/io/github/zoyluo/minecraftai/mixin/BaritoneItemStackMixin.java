package io.github.zoyluo.minecraftai.mixin;

import baritone.api.utils.accessor.IItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Baritone's cheap item identity ({@code item.hashCode() + damage}) used by {@code BlockOptionalMeta} to match a block's
 * drops. Upstream implements it in the client-only {@code MixinItemStack} and recomputes the hash on every damage change; this
 * version only forgets it: {@code setDamageValue} runs for every damaged item stack on the server (tools, armor), and with the
 * default engine nobody ever reads the hash, so the injected code is one field write and no hash arithmetic. The hash is computed
 * on the first read after a change ({@link #getBaritoneHash}), so what Baritone reads is exactly what upstream's eager recompute
 * gives: {@code item.hashCode() + getDamageValue()} of the stack as it is at that moment.
 */
@Mixin(ItemStack.class)
public abstract class BaritoneItemStackMixin implements IItemStack {
    @Shadow
    @Final
    private Item item;

    /** 0 = not computed since the last damage change. */
    @Unique
    private int minecraftai$baritoneHash;

    @Shadow
    public abstract int getDamageValue();

    @Inject(method = "setDamageValue", at = @At("TAIL"))
    private void minecraftai$onItemDamageSet(CallbackInfo ci) {
        minecraftai$baritoneHash = 0;
    }

    @Override
    public int getBaritoneHash() {
        // not in an init mixin: getDamageValue() may create items while a stack is still being built
        int hash = minecraftai$baritoneHash;
        if (hash == 0) {
            hash = item == null ? -1 : item.hashCode() + getDamageValue();
            minecraftai$baritoneHash = hash;
        }
        return hash;
    }
}
