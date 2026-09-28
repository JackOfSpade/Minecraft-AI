package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

public final class InteractAction {
    private InteractAction() {
    }

    public static ActionResult attackEntity(AIPlayerEntity player, Entity target) {
        Vec3d targetCenter = target.getEntityPos().add(0.0D, target.getHeight() * 0.5D, 0.0D);
        LookAction.lookAt(player, targetCenter);
        player.attack(target);
        player.swingHand(Hand.MAIN_HAND);
        player.resetTicksSinceLastAttack();
        player.updateLastActionTime();
        BotLog.action(player, "attack", "target_type", target.getType(), "target_id", target.getId());
        return ActionResult.SUCCESS;
    }

    public static ActionResult useItemOnEntity(AIPlayerEntity player, Entity target, Hand hand) {
        net.minecraft.util.ActionResult result = target.interact(player, hand);
        return result.isAccepted() ? ActionResult.SUCCESS : ActionResult.failed("interact_entity_" + result.getClass().getSimpleName());
    }

    public static ActionResult useItemInAir(AIPlayerEntity player, Hand hand) {
        net.minecraft.util.ActionResult result = player.interactionManager.interactItem(
                player,
                player.getEntityWorld(),
                player.getStackInHand(hand),
                hand);
        return result.isAccepted() ? ActionResult.SUCCESS : ActionResult.failed("interact_item_" + result.getClass().getSimpleName());
    }
}
