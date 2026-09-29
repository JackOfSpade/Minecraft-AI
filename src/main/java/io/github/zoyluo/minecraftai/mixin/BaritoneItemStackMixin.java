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
 * drops. Upstream implements it in the client-only {@code MixinItemStack}; this is the same code for the server.
 */
@Mixin(ItemStack.class)
public abstract class BaritoneItemStackMixin implements IItemStack {
    @Shadow
    @Final
    private Item item;

    @Unique
    private int minecraftai$baritoneHash;

    @Shadow
    public abstract int getDamageValue();

    @Unique
    private void minecraftai$recalculateHash() {
        minecraftai$baritoneHash = item == null ? -1 : item.hashCode() + getDamageValue();
    }

    @Inject(method = "setDamageValue", at = @At("TAIL"))
    private void minecraftai$onItemDamageSet(CallbackInfo ci) {
        minecraftai$recalculateHash();
    }

    @Override
    public int getBaritoneHash() {
        // not in an init mixin: getDamageValue() may create items while a stack is still being built
        if (minecraftai$baritoneHash == 0) {
            minecraftai$recalculateHash();
        }
        return minecraftai$baritoneHash;
    }
}
