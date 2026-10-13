package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.util.Mth;

/** Vanilla dry-fall damage used to decide whether a bot may deliberately step off a ledge. */
public final class FallSafety {
    private FallSafety() {
    }

    /**
     * True when an unprotected vanilla fall of {@code fallBlocks} cannot kill this bot.
     *
     * <p>This deliberately uses the full unarmoured amount. Armour, Feather Falling, and potion
     * effects can only make the real landing safer, so a route is never admitted on a mitigation
     * guess that might disappear before the bot lands.</p>
     */
    public static boolean isNonLethal(AIPlayerEntity bot, int fallBlocks) {
        return bot != null && fallDamage(fallBlocks) < bot.getHealth() + bot.getAbsorptionAmount();
    }

    /** Largest whole-block dry fall whose unprotected vanilla damage leaves this bot alive. */
    public static int maxNonLethalFall(AIPlayerEntity bot) {
        if (bot == null) {
            return WalkedStepRules.MAX_ZERO_DAMAGE_FALL_BLOCKS;
        }
        // ceil(distance - 3) must remain strictly below health.  Health is float because
        // vanilla damage and absorption are float too; floor makes the result conservative at
        // fractional health values rather than gambling on a one-point landing.
        int survivableDamage = Math.max(0, Mth.floor(bot.getHealth() + bot.getAbsorptionAmount() - 1.0F));
        return WalkedStepRules.MAX_ZERO_DAMAGE_FALL_BLOCKS + survivableDamage;
    }

    /** Vanilla's ordinary dry-fall amount before armour or enchantment reductions. */
    public static int fallDamage(int fallBlocks) {
        return Math.max(0, Mth.ceil(fallBlocks - 3.0D));
    }
}
