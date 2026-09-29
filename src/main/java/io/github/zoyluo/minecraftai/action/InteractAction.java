package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class InteractAction {
    private InteractAction() {
    }

    /**
     * One melee attack. {@code ServerPlayer.attack} checks nothing itself (vanilla checks reach in
     * the packet handler, which this direct call skips), so the survival-legal preconditions are
     * enforced here for every caller: never the owner or another bot, the target's box inside the
     * vanilla entity interaction range, and no colliding block in the way. A refused strike does
     * not swing, does not reset the cooldown and does not turn the bot.
     */
    public static ActionResult attackEntity(AIPlayerEntity player, Entity target) {
        String refusal = StrikeLegality.strikeRefusal(player, target);
        if (refusal != null) {
            BotLog.action(player, "attack_refused", "reason", refusal,
                    "target_type", target.getType(), "target_id", target.getId());
            return ActionResult.failed(refusal);
        }
        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        LookAction.lookAt(player, targetCenter);
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND);
        player.resetOnlyAttackStrengthTicker();
        player.resetLastActionTime();
        BotLog.action(player, "attack", "target_type", target.getType(), "target_id", target.getId(),
                "target_hp", target instanceof net.minecraft.world.entity.LivingEntity living ? living.getHealth() : -1.0F);
        return ActionResult.SUCCESS;
    }

    public static ActionResult useItemOnEntity(AIPlayerEntity player, Entity target, InteractionHand hand) {
        net.minecraft.world.InteractionResult result = target.interact(player, hand);
        return result.consumesAction() ? ActionResult.SUCCESS : ActionResult.failed("interact_entity_" + result.getClass().getSimpleName());
    }

    public static ActionResult useItemInAir(AIPlayerEntity player, InteractionHand hand) {
        net.minecraft.world.InteractionResult result = player.gameMode.useItem(
                player,
                player.level(),
                player.getItemInHand(hand),
                hand);
        return result.consumesAction() ? ActionResult.SUCCESS : ActionResult.failed("interact_item_" + result.getClass().getSimpleName());
    }
}
