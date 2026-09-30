package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class InteractAction {
    /** The refusal reason of a legal strike whose target is not (yet) under the bot's crosshair. */
    public static final String NOT_UNDER_CROSSHAIR = "not_under_crosshair";
    /** The refusal of a strike while an item is in use (a raised shield, a drawn bow, food): the client drops the attack click. */
    public static final String HANDS_BUSY = "hands_busy";

    private InteractAction() {
    }

    /**
     * One melee attack. {@code ServerPlayer.attack} checks nothing itself (vanilla checks reach in
     * the packet handler, which this direct call skips), so the survival-legal preconditions are
     * enforced here for every caller: never the owner or another bot, the target's box inside the
     * vanilla entity interaction range, and no colliding block in the way. A refused strike does
     * not swing and does not reset the cooldown.
     *
     * <p>Human aim ({@link HumanAim}): a legal strike first turns the bot toward the target at human speed (at most the
     * per-tick turn budget, shared with the caller's own look), and only lands when the target is then the entity under the
     * bot's crosshair (vanilla's pick along its real look vector within its vanilla attack range). While the bot is still
     * turning the strike fails with {@code not_under_crosshair}; a caller that strikes every tick simply tries again.
     *
     * <p>A MOUNT IN THE WAY: when the target rides something (a horse, a boat, a spider under its jockey) and that vehicle is nearer
     * on the crosshair ray, the crosshair holds the vehicle and the strike stays {@code not_under_crosshair}: a human would hit the
     * mount, and the bot never strikes THROUGH it. {@link #mountInTheWay} names that vehicle so the caller can switch to it when it is a
     * legal hostile ({@code CombatTask}, {@code AttackEntityTask}) or change its angle.
     */
    public static ActionResult attackEntity(AIPlayerEntity player, Entity target) {
        if (player.isUsingItem()) {
            // A player cannot attack while the use key is down (a raised shield, a drawn bow, food): the client drops the click
            // (Minecraft.handleKeybinds). A raised shield is lowered by its owner first (CombatCore.strikeIfReady), never here.
            return ActionResult.failed(HANDS_BUSY);
        }
        String refusal = StrikeLegality.strikeRefusal(player, target);
        if (refusal != null) {
            BotLog.action(player, "attack_refused", "reason", refusal,
                    "target_type", target.getType(), "target_id", target.getId());
            return ActionResult.failed(refusal);
        }
        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        HumanAim.lookToward(player, targetCenter);
        if (!HumanAim.isUnderCrosshair(player, target)) {
            return ActionResult.failed(NOT_UNDER_CROSSHAIR);
        }
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND);
        player.resetOnlyAttackStrengthTicker();
        player.resetLastActionTime();
        BotLog.action(player, "attack", "target_type", target.getType(), "target_id", target.getId(),
                "target_hp", target instanceof net.minecraft.world.entity.LivingEntity living ? living.getHealth() : -1.0F);
        return ActionResult.SUCCESS;
    }

    /**
     * The mount or vehicle of {@code target} that the bot's crosshair holds instead of {@code target} (it is nearer on the ray), or
     * null when the crosshair holds nothing of the kind: the reason a legal, aimed strike on a rider does not land. It only reads what
     * the bot's own crosshair picks (vanilla's pick along the real look vector), never a hidden state.
     */
    public static Entity mountInTheWay(AIPlayerEntity player, Entity target) {
        Entity under = HumanAim.crosshairEntity(player);
        if (under == null || under == target) {
            return null;
        }
        for (Entity vehicle = target.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle()) {
            if (vehicle == under) {
                return under;
            }
        }
        return null;
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
