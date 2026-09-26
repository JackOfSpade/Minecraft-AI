package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Comparator;
import java.util.Optional;

/**
 * A bot swarmed by three or more simultaneously aggro'd hostiles (see {@link CombatRegroupGuard})
 * falls back toward its owning player instead of fighting the whole crowd alone. Movement is
 * biased toward the player, but a hostile that is already in melee range is still struck along the
 * way -- this is a fighting retreat, not a flee.
 */
public final class CombatRegroupTask extends AbstractTask {
    private static final double MELEE_STRIKE_RANGE = CombatCore.ATTACK_RANGE;
    private static final int REPATH_INTERVAL_TICKS = 20;

    private int nextRepathElapsed;
    private int lastAggroCount;

    @Override
    public String name() {
        return "combat_regroup";
    }

    @Override
    public String describe() {
        return "Regrouping toward owner, aggro_count=" + lastAggroCount;
    }

    @Override
    public double progress() {
        return 0.5D;
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        nextRepathElapsed = 0;
        lastAggroCount = CombatRegroupGuard.countAggro(bot);
        BotLog.danger(bot, "combat_regroup_started", "aggro_count", lastAggroCount);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        lastAggroCount = CombatRegroupGuard.countAggro(bot);
        Optional<ServerPlayerEntity> owner = CombatRegroupGuard.resolveOwner(bot);
        if (owner.isEmpty()) {
            BotLog.danger(bot, "combat_regroup_owner_missing");
            complete();
            return;
        }
        ServerPlayerEntity player = owner.get();
        strikeAnyAdjacentHostile(bot);
        double distance = bot.distanceTo(player);
        if (distance > CombatRegroupGuard.MAX_REGROUP_DISTANCE) {
            // The player moved (or the bot was pushed) out of range mid-retreat: a run that long
            // is no longer a sensible fallback, so hand control back to ordinary combat.
            bot.getActionPack().stopAll();
            BotLog.danger(bot, "combat_regroup_owner_too_far", "distance", (int) distance);
            complete();
            return;
        }
        if (distance <= CombatRegroupGuard.RETREAT_TARGET_DISTANCE) {
            bot.getActionPack().stopAll();
            BotLog.danger(bot, "combat_regroup_reached_owner", "distance", (int) distance);
            complete();
            return;
        }
        // shouldSprint()'s own distance heuristic resets this every tick otherwise -- a swarmed
        // fighting retreat should always sprint, the same as a real player fleeing a mob crowd.
        bot.getActionPack().setSprinting(true);
        if (elapsed >= nextRepathElapsed && bot.getActionPack().isPathExecutorIdle()) {
            nextRepathElapsed = elapsed + REPATH_INTERVAL_TICKS;
            CombatCore.startApproach(bot, player);
        }
    }

    private void strikeAnyAdjacentHostile(AIPlayerEntity bot) {
        bot.getServerWorld().getEntitiesByClass(LivingEntity.class,
                        bot.getBoundingBox().expand(MELEE_STRIKE_RANGE + 1.0D),
                        entity -> entity instanceof MobEntity mob
                                && mob.isAlive()
                                && mob.getTarget() == bot
                                && CombatCore.hasLineOfSight(bot, mob))
                .stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .filter(mob -> bot.distanceTo(mob) <= MELEE_STRIKE_RANGE)
                .ifPresent(mob -> {
                    CombatCore.ensureMeleeWeapon(bot);
                    CombatCore.strikeIfReady(bot, mob);
                });
    }
}
