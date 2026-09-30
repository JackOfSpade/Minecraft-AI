package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.InteractionHand;

public final class EatAction {
    private EatAction() {
    }

    public static ActionResult startEating(AIPlayerEntity player) {
        return startEating(player, false);
    }

    /** {@code safeOnly}: never eat harmful food (rotten flesh, spider eye, ...) even as a last resort. */
    public static ActionResult startEating(AIPlayerEntity player, boolean safeOnly) {
        if (io.github.zoyluo.minecraftai.task.ShieldGuard.usingShield(player)) {
            // The use key holds the shield up (vanilla: one item in use at a time): the bite waits, it never drops the shield.
            return ActionResult.failed("hands_busy");
        }
        int slot = safeOnly ? InventoryAction.findSafeFoodSlot(player) : InventoryAction.findFoodSlot(player);
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
