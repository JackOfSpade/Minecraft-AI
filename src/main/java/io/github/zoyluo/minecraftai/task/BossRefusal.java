package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.world.entity.EntityType;

/**
 * Bosses are never fought: a commanded attack (the {@code attack} tool, the {@code attack} task type and
 * {@code /minecraftai task assign <bot> attack}) is refused up front with {@link #MESSAGE}, and nothing is assigned. A later
 * observed threat is handled by the ordinary {@link EvadeTask} path.
 */
public final class BossRefusal {
    /** The answer to a commanded attack on a boss. */
    public static final String MESSAGE = "I won't fight a boss. I'll run away if one threatens me.";

    private BossRefusal() {
    }

    /** True when a commanded attack on {@code type} is refused. */
    public static boolean refuses(EntityType<?> type) {
        return type == EntityType.WARDEN
                || type == EntityType.WITHER
                || type == EntityType.ENDER_DRAGON
                || type == EntityType.ELDER_GUARDIAN;
    }

    /** Logs the refusal ({@code via} names the door it came through: tool, task_type or command). */
    public static void logRefused(AIPlayerEntity bot, String via) {
        BotLog.action(bot, "combat_refused_boss", "via", via);
    }
}
