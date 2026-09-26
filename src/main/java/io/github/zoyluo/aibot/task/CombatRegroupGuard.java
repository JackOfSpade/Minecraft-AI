package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Optional;

/**
 * Decides the per-bot squad-regroup policy, purely in terms of the bot's current distance from its
 * owning player: within {@link #NO_RETREAT_DISTANCE} blocks of the player, never retreat. Beyond
 * that, three or more hostiles aggro'd within {@link #AGGRO_SCAN_DISTANCE} blocks of the bot pull it
 * back toward the player until it reaches {@link #RETREAT_TARGET_DISTANCE} blocks away. Once a
 * retreat has started it keeps going all the way to {@link #RETREAT_TARGET_DISTANCE} regardless of
 * the aggro count fluctuating mid-flight, so a bot hovering near the trigger boundary does not
 * flicker in and out of a forced regroup every tick.
 *
 * <p>This class holds no per-bot state of its own -- {@code currentlyRegrouping} is derived by the
 * caller from whether a {@link CombatRegroupTask} already owns (or is paused for) the bot, so there
 * is nothing here to leak or to clear on despawn/respawn.</p>
 */
final class CombatRegroupGuard {
    static final int AGGRO_THRESHOLD = 3;
    /** At or below this distance from the owning player, retreat never triggers. */
    static final double NO_RETREAT_DISTANCE = 10.0D;
    /** Once triggered, a retreat continues until the bot is this close to the owning player. */
    static final double RETREAT_TARGET_DISTANCE = 5.0D;
    /** How far around the bot itself (not the player) hostiles are counted for the aggro check. */
    private static final double AGGRO_SCAN_DISTANCE = 10.0D;

    private CombatRegroupGuard() {
    }

    static int countAggro(AIPlayerEntity bot) {
        return CombatCore.countAggroedHostiles(bot, AGGRO_SCAN_DISTANCE);
    }

    static Optional<ServerPlayerEntity> resolveOwner(AIPlayerEntity bot) {
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(ownerId -> bot.getServer().getPlayerManager().getPlayer(ownerId))
                .filter(player -> player.isAlive() && player.getServerWorld() == bot.getServerWorld());
    }

    static boolean shouldRegroup(AIPlayerEntity bot, boolean currentlyRegrouping) {
        Optional<ServerPlayerEntity> owner = resolveOwner(bot);
        if (owner.isEmpty()) {
            return false;
        }
        return shouldRegroup(bot.distanceTo(owner.get()), countAggro(bot), currentlyRegrouping);
    }

    /**
     * The single decision point, isolated from world/entity state so it is directly unit-testable.
     * A retreat already in progress keeps going until {@link #RETREAT_TARGET_DISTANCE}, even while
     * passing back through {@link #NO_RETREAT_DISTANCE} -- that threshold only gates whether a NEW
     * retreat starts, not whether one already under way is allowed to finish.
     */
    static boolean shouldRegroup(double distanceToOwner, int aggroCount, boolean currentlyRegrouping) {
        if (currentlyRegrouping) {
            return distanceToOwner > RETREAT_TARGET_DISTANCE;
        }
        return distanceToOwner > NO_RETREAT_DISTANCE && aggroCount >= AGGRO_THRESHOLD;
    }
}
