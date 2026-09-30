package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * One deliberate blow on one entity, the way a person does it when told "hit that": turn to it at human speed, then strike when it is
 * under the crosshair. The {@code attack_entity} tool starts it when its own first strike fails only because the bot is not yet
 * facing the target ({@link InteractAction#NOT_UNDER_CROSSHAIR}): a one-shot tool call then still works with the bot facing away, and
 * the task's own end tells the truth (COMPLETED = the blow landed or the target is gone; FAILED with the refusal otherwise).
 *
 * <p>Bounded: {@value #MAX_TICKS} ticks. Every refusal other than "not under the crosshair yet" (out of reach, no line of sight, a
 * friend) ends it at once; nothing is ever struck through a wall or beyond vanilla reach ({@link StrikeLegality}). When the target's
 * mount or vehicle is what is under the crosshair, a human would hit the mount: the task switches to it when it is a legal hostile,
 * and otherwise gives up with {@code mount_in_the_way} instead of striking through it.
 */
public final class AttackEntityTask extends AbstractTask {
    /** How long the bot may take to turn and land the blow. */
    public static final int MAX_TICKS = 40;

    private Entity target;
    private String lastRefusal = "";

    public AttackEntityTask(Entity target) {
        this.target = target;
    }

    @Override
    public String name() {
        return "attack_entity";
    }

    @Override
    public String describe() {
        return "Striking " + (target == null ? "nothing" : target.getType().getDescriptionId())
                + (lastRefusal.isEmpty() ? "" : " (" + lastRefusal + ")");
    }

    @Override
    public double progress() {
        return Math.min(1.0D, elapsed / (double) MAX_TICKS);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopMovement();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            complete(); // it is gone (dead): nothing left to strike
            return;
        }
        String refusal = StrikeLegality.strikeRefusal(bot, target);
        if (refusal != null) {
            fail(refusal);
            return;
        }
        ActionResult result = InteractAction.attackEntity(bot, target);
        if (result.isSuccess()) {
            complete();
            return;
        }
        lastRefusal = result.reason() == null ? "" : result.reason();
        if (!InteractAction.NOT_UNDER_CROSSHAIR.equals(lastRefusal)) {
            fail(lastRefusal);
            return;
        }
        Entity mount = InteractAction.mountInTheWay(bot, target);
        if (mount != null) {
            if (mount instanceof LivingEntity living && CombatCore.hostileTo(bot, living)
                    && !CombatCore.isMeleeForbiddenThreat(living) && !CombatCore.isFriendly(bot, living)) {
                BotLog.action(bot, "attack_target_switched_to_mount", "mount", mount.getType(), "rider", target.getType());
                target = mount;
                return;
            }
            fail("mount_in_the_way");
            return;
        }
        if (elapsed >= MAX_TICKS) {
            fail(InteractAction.NOT_UNDER_CROSSHAIR);
        }
    }
}
