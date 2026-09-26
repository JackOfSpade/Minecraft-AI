package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Optional;

/**
 * Decides the per-bot squad-regroup hysteresis: three or more simultaneously aggro'd hostiles pull
 * a bot back toward its owning player to within {@link #INNER_RADIUS} blocks. Once there, ordinary
 * combat resumes and the bot is free to roam/fight until it drifts past {@link #OUTER_RADIUS}
 * blocks from the player while still that heavily aggro'd, at which point it falls back again.
 *
 * <p>This class holds no per-bot state of its own -- {@code currentlyRegrouping} is derived by the
 * caller from whether a {@link CombatRegroupTask} already owns (or is paused for) the bot, so there
 * is nothing here to leak or to clear on despawn/respawn.</p>
 */
final class CombatRegroupGuard {
    static final int AGGRO_THRESHOLD = 3;
    static final double INNER_RADIUS = 10.0D;
    static final double OUTER_RADIUS = 15.0D;
    private static final double AGGRO_SCAN_RANGE = 24.0D;

    private CombatRegroupGuard() {
    }

    static int countAggro(AIPlayerEntity bot) {
        return CombatCore.countAggroedHostiles(bot, AGGRO_SCAN_RANGE);
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
     * The single hysteresis decision point, isolated from world/entity state so it is directly
     * unit-testable. The two thresholds are intentionally different (start at {@link
     * #OUTER_RADIUS}, release at {@link #INNER_RADIUS}) so a bot hovering near the boundary does
     * not flicker in and out of a forced regroup every tick.
     */
    static boolean shouldRegroup(double distanceToOwner, int aggroCount, boolean currentlyRegrouping) {
        if (currentlyRegrouping) {
            return distanceToOwner > INNER_RADIUS;
        }
        return distanceToOwner > OUTER_RADIUS && aggroCount >= AGGRO_THRESHOLD;
    }
}
