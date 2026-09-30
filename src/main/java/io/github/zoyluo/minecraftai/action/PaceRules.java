package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.effect.MobEffects;

/**
 * The vanilla movement rules a client applies to its own player and a server-side bot does not, as pure functions plus one
 * adapter that reads them off a bot. Applied once per tick by the enforcers (legacy {@code ActionPack.onUpdate}, Baritone
 * {@code BotInputBridge.apply}) to controller-driven movement, so the bots keep the same limits a player has:
 * <ul>
 *   <li>no sprint at 6 food points or fewer (unless the player may fly), while sneaking, while using an item, while blind, without
 *       forward input, and none on a hard horizontal collision (a wall ends the sprint; a brush against a corner does not);</li>
 *   <li>the movement input of a sneaking player is scaled by 0.3 and the one of a player using an item (eating, blocking, drawing a
 *       bow) by 0.2; both together multiply.</li>
 * </ul>
 */
public final class PaceRules {
    /** Food points a player must have MORE than to start or keep sprinting. */
    public static final int SPRINT_FOOD_FLOOR = 6;
    /** Factor applied to the movement input of a sneaking player (the base value of Attributes.SNEAKING_SPEED). */
    public static final float SNEAK_SCALE = 0.3F;
    /** Factor applied to the movement input of a player who is using an item. */
    public static final float USE_ITEM_SCALE = 0.2F;
    /** Forward input a sprint needs, exclusive (vanilla: the player has to be pushing forward with real strength). */
    public static final float SPRINT_FORWARD_MIN = 0.5F;

    private PaceRules() {
    }

    /** Whether the vanilla rules let a player with these inputs and this state sprint. */
    public static boolean sprintAllowed(float forward, boolean sneaking, boolean usingItem, boolean blind, int food,
                                        boolean mayfly, boolean hardCollision) {
        return forward > SPRINT_FORWARD_MIN
                && !sneaking
                && !usingItem
                && !blind
                && (food > SPRINT_FOOD_FLOOR || mayfly)
                && !hardCollision;
    }

    /** The factor the movement input is multiplied by (1.0 when neither slowdown applies; vanilla applies both, there is no switch). */
    public static float inputScale(boolean sneaking, boolean usingItem) {
        float scale = sneaking ? SNEAK_SCALE : 1.0F;
        if (usingItem) {
            scale *= USE_ITEM_SCALE;
        }
        return scale;
    }

    /** A hard horizontal collision: the bot ran into something (a corner it only brushed does not count). */
    public static boolean hardCollision(AIPlayerEntity bot) {
        return bot.horizontalCollision && !bot.minorHorizontalCollision;
    }

    /** {@link #sprintAllowed(float, boolean, boolean, boolean, int, boolean, boolean)} read off {@code bot}. */
    public static boolean sprintAllowed(AIPlayerEntity bot, float forward, boolean sneaking) {
        return sprintAllowed(forward, sneaking, bot.isUsingItem(), bot.hasEffect(MobEffects.BLINDNESS),
                bot.getFoodData().getFoodLevel(), bot.getAbilities().mayfly, hardCollision(bot));
    }
}
