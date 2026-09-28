package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.Hand;

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
        return InteractAction.useItemInAir(player, Hand.MAIN_HAND);
    }
}
