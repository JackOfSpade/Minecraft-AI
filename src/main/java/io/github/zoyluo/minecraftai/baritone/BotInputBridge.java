package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.effect.MobEffects;

/**
 * The input bridge: what Baritone's {@code InputOverrideHandler} says is held down (the state upstream turns into a
 * {@code PlayerMovementInput} for the client player) becomes the movement fields of the bot that vanilla physics reads.
 *
 * <p>Verified against vanilla by the physics probes of this integration: writing {@code zza}/{@code xxa} (1.0 per held
 * direction, unnormalised, exactly what {@code PlayerMovementInput} produces), {@code setJumping} and {@code setShiftKeyDown}
 * before the bot's physics tick reproduces the client's walking (4.317 blocks/s), sprinting (5.612), sneaking (1.295),
 * step-up jump, sprint-jump distance, ladder and door behavior, so Baritone's tick-cost tables hold unchanged.</p>
 *
 * <p>What a client player does on its own and a server-side bot does not, this class does:</p>
 * <ul>
 *   <li><b>Sneak scaling, once.</b> A sneaking player moves at 0.3 of its input. The client applies that in
 *       {@code LocalPlayer}; upstream's {@code PlayerMovementInput} additionally scales by 0.3 itself, and a server has no
 *       client to scale again, so the factor is applied exactly here and nowhere else (sneak speed equals the vanilla 1.295
 *       blocks/s that {@code ActionCosts} assumes).</li>
 *   <li><b>Sprint rules.</b> The server never clears the sprint flag by itself. As {@code LocalPlayer.aiStep} does, a bot
 *       sprints only while Baritone wants it ({@code PathingBehavior} answers {@code SprintStateEvent}), there is a forward
 *       input, it is not sneaking or using an item, it has more than 6 food points (or may fly) and is not blind, and it
 *       stops when it runs into a wall.</li>
 * </ul>
 */
final class BotInputBridge {
    /** Factor applied to the movement input of a sneaking player (Attributes.SNEAKING_SPEED base value). */
    static final float SNEAK_SCALE = 0.3F;
    /** Food points a player needs to start or keep sprinting (exclusive lower bound). */
    private static final int SPRINT_FOOD_LEVEL = 6;

    private BotInputBridge() {
    }

    /** Writes Baritone's held inputs into the bot. Called every tick, before the bot's physics. */
    static void apply(AIPlayerEntity bot, IBaritone baritone) {
        IInputOverrideHandler keys = baritone.getInputOverrideHandler();
        boolean forwardKey = keys.isInputForcedDown(Input.MOVE_FORWARD);
        boolean backKey = keys.isInputForcedDown(Input.MOVE_BACK);
        boolean leftKey = keys.isInputForcedDown(Input.MOVE_LEFT);
        boolean rightKey = keys.isInputForcedDown(Input.MOVE_RIGHT);
        boolean jump = keys.isInputForcedDown(Input.JUMP);
        boolean sneak = keys.isInputForcedDown(Input.SNEAK);

        float forward = (forwardKey ? 1.0F : 0.0F) - (backKey ? 1.0F : 0.0F);
        float left = (leftKey ? 1.0F : 0.0F) - (rightKey ? 1.0F : 0.0F);
        if (sneak) {
            forward *= SNEAK_SCALE;
            left *= SNEAK_SCALE;
        }
        bot.zza = forward;
        bot.xxa = left;
        bot.setJumping(jump);
        bot.setShiftKeyDown(sneak);
        bot.setSprinting(sprints(bot, baritone, forward, sneak));
    }

    /** Lets go of everything: no movement input, no jump, no sneak, no sprint. */
    static void release(AIPlayerEntity bot) {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        bot.setShiftKeyDown(false);
        bot.setSprinting(false);
    }

    private static boolean sprints(AIPlayerEntity bot, IBaritone baritone, float forward, boolean sneak) {
        SprintStateEvent request = new SprintStateEvent();
        baritone.getGameEventHandler().onPlayerSprintState(request);
        if (!Boolean.TRUE.equals(request.getState())) {
            return false; // not asked (or asked to stop); bots never sprint because a key is "held"
        }
        if (forward <= 0.0F || sneak || bot.isUsingItem() || bot.hasEffect(MobEffects.BLINDNESS)) {
            return false;
        }
        if (bot.getFoodData().getFoodLevel() <= SPRINT_FOOD_LEVEL && !bot.getAbilities().mayfly) {
            return false;
        }
        // Sprinting into a wall ends the sprint (LocalPlayer.aiStep); a brush against a corner does not.
        return !(bot.horizontalCollision && !bot.minorHorizontalCollision);
    }
}
