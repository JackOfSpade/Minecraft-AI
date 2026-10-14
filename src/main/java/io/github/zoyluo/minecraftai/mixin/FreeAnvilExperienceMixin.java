package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes every anvil operation free without changing any other experience-using block. */
@Mixin(AnvilMenu.class)
public abstract class FreeAnvilExperienceMixin {
    @Shadow @Final private DataSlot cost;

    /** Keep valid high-cost operations from being discarded as "Too Expensive!" before their cost is cleared. */
    @ModifyConstant(method = "createResult", constant = @Constant(intValue = 40))
    private int minecraftai$removeAnvilCostCap(int vanillaLimit) {
        return Integer.MAX_VALUE;
    }

    /** The synchronized menu cost is also what survival-mode pickup subtracts from the player. */
    @Inject(method = "createResult", at = @At("TAIL"))
    private void minecraftai$makeAnvilOperationsFree(CallbackInfo callback) {
        this.cost.set(0);
    }
}
