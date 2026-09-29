package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.InteractionHand;

public final class EatAction {
    private EatAction() {
    }

    public static ActionResult startEating(AIPlayerEntity player) {
        int slot = InventoryAction.findFoodSlot(player);
        if (slot < 0) {
            return ActionResult.failed("no_food");
        }
        int hotbar = InventoryAction.equipFromSlot(player, slot);
        if (hotbar < 0) {
            return ActionResult.failed("equip_food_failed");
        }
        return InteractAction.useItemInAir(player, InteractionHand.MAIN_HAND);
    }
}
