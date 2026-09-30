package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.world.entity.EntityType;

/**
 * Wardens are never fought: a commanded attack on one (the {@code attack} tool, the {@code attack} task type and
 * {@code /minecraftai task assign <bot> attack}) is refused up front with {@link #MESSAGE}, and nothing is assigned. What the bot
 * does about a warden instead is {@link EvadeTask}: creep away from a calm one, outrun a hunting one.
 */
public final class WardenRefusal {
    /** The answer to a commanded attack on a warden. */
    public static final String MESSAGE = "I won't fight a warden. I'll sneak away from it instead.";

    private WardenRefusal() {
    }

    /** True when a commanded attack on {@code type} is refused. */
    public static boolean refuses(EntityType<?> type) {
        return type == EntityType.WARDEN;
    }

    /** Logs the refusal ({@code via} names the door it came through: tool, task_type or command). */
    public static void logRefused(AIPlayerEntity bot, String via) {
        BotLog.action(bot, "combat_refused_warden", "via", via);
    }
}
