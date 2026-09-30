package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDownward;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementParkour;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.PacePolicy;
import io.github.zoyluo.minecraftai.action.PaceRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.List;
import net.minecraft.core.BlockPos;

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
 *   <li><b>Pace.</b> How fast the bot goes is the mod's one pace policy ({@link PacePolicy}), the same as for the legacy engine: the
 *       gait (sprint, walk, sneak) it resolves decides whether Baritone's sprint request is honoured and whether the bot sneaks.
 *       Baritone's own reasons stay in force: a parkour jump (and the run-up to one) sprints whatever the gait, since a jump made at
 *       a walk falls into the gap, and a sneak is lifted on a descent, a fall and a climbable (a sneaking player does not walk off
 *       an edge and does not climb down a ladder or a vine).</li>
 *   <li><b>Input scaling, once.</b> A sneaking player moves at 0.3 of its input and one that is using an item (eating, blocking) at
 *       0.2 ({@link PaceRules#inputScale}). The client applies that in {@code LocalPlayer}; upstream's {@code PlayerMovementInput}
 *       additionally scales by 0.3 itself, and a server has no client to scale again, so the factor is applied exactly here and
 *       nowhere else (sneak speed equals the vanilla 1.295 blocks/s that {@code ActionCosts} assumes).</li>
 *   <li><b>Sprint rules ({@link PaceRules}).</b> The server never clears the sprint flag by itself. As {@code LocalPlayer.aiStep}
 *       does, a bot sprints only while Baritone wants it ({@code PathingBehavior} answers {@code SprintStateEvent}), there is a
 *       forward input, it is not sneaking or using an item, it has more than 6 food points (or may fly) and is not blind, and it
 *       stops when it runs into a wall.</li>
 * </ul>
 */
final class BotInputBridge {
    /** Factor applied to the movement input of a sneaking player (Attributes.SNEAKING_SPEED base value). */
    static final float SNEAK_SCALE = PaceRules.SNEAK_SCALE;
    /** How many movements ahead of a parkour jump still count as its run-up. */
    private static final int PARKOUR_RUN_UP_MOVEMENTS = 2;

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
        MinecraftAiConfig.Pace config = MinecraftAiConfig.get().behaviour().paceOrDefaults();
        if (!config.paceEnabled()) {
            applyUnpaced(bot, baritone, forward, left, jump, sneak);
            return;
        }

        ActionPack pack = bot.getActionPack();
        Movement movement = Movement.of(baritone);
        Gait gait = PacePolicy.resolve(bot, goalDistance(bot, pack), true);
        // A sneaking player does not walk off an edge or down a ladder/vine, and the fall/descent/climb has to be made.
        boolean edge = movement.parkour || movement.descending || bot.onClimbable();
        boolean effectiveSneak = sneak || ((gait == Gait.SNEAK || pack.sneakRequested()) && !edge);
        boolean usingItemSlow = bot.isUsingItem() && config.itemUseSlowdownEnabled();
        float scale = PaceRules.inputScale(effectiveSneak, bot.isUsingItem(), config.itemUseSlowdownEnabled());
        bot.zza = forward * scale;
        bot.xxa = left * scale;
        bot.setJumping(jump);
        bot.setShiftKeyDown(effectiveSneak);
        boolean sprint = sprintWanted(baritone)
                && PaceRules.sprintAllowed(bot, forward, effectiveSneak)
                && (movement.parkour || gait == Gait.SPRINT);
        bot.setSprinting(sprint);
        pack.noteEnforced(effectiveSneak ? Gait.SNEAK : sprint ? Gait.SPRINT : Gait.WALK, scale, usingItemSlow);
    }

    /** {@code pace.enabled=false}: what the bridge did before the pace policy. */
    private static void applyUnpaced(AIPlayerEntity bot, IBaritone baritone, float forward, float left, boolean jump, boolean sneak) {
        boolean sprint = sprintWanted(baritone) && PaceRules.sprintAllowed(bot, forward, sneak);
        if (sneak) {
            forward *= PaceRules.SNEAK_SCALE;
            left *= PaceRules.SNEAK_SCALE;
        }
        bot.zza = forward;
        bot.xxa = left;
        bot.setJumping(jump);
        bot.setShiftKeyDown(sneak);
        bot.setSprinting(sprint);
    }

    /** Horizontal distance to the goal of the route this pack runs, NaN when a direct Baritone caller drives the bot. */
    private static double goalDistance(AIPlayerEntity bot, ActionPack pack) {
        BlockPos goal = pack.activePathGoal();
        if (goal == null) {
            return Double.NaN;
        }
        double dx = goal.getX() + 0.5D - bot.getX();
        double dz = goal.getZ() + 0.5D - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Whether Baritone wants to sprint this tick ({@code PathingBehavior} answers the {@code SprintStateEvent}). */
    private static boolean sprintWanted(IBaritone baritone) {
        SprintStateEvent request = new SprintStateEvent();
        baritone.getGameEventHandler().onPlayerSprintState(request);
        return Boolean.TRUE.equals(request.getState()); // not asked (or asked to stop): bots never sprint because a key is "held"
    }

    /** What kind of movement Baritone is executing now (and whether a parkour jump is next). */
    private static final class Movement {
        static final Movement NONE = new Movement(false, false);
        final boolean parkour;
        final boolean descending;

        private Movement(boolean parkour, boolean descending) {
            this.parkour = parkour;
            this.descending = descending;
        }

        static Movement of(IBaritone baritone) {
            IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor == null || executor.getPath() == null) {
                return NONE;
            }
            IPath path = executor.getPath();
            List<IMovement> movements = path.movements();
            int position = executor.getPosition();
            if (movements == null || position < 0 || position >= movements.size()) {
                return NONE;
            }
            IMovement current = movements.get(position);
            boolean parkour = current instanceof MovementParkour;
            // The run-up to a jump is part of it: a jump from a standing start at a walk does not clear the gap.
            for (int ahead = 1; !parkour && ahead <= PARKOUR_RUN_UP_MOVEMENTS && position + ahead < movements.size(); ahead++) {
                parkour = movements.get(position + ahead) instanceof MovementParkour;
            }
            boolean descending = current instanceof MovementDescend || current instanceof MovementFall
                    || current instanceof MovementDownward;
            return new Movement(parkour, descending);
        }
    }

    /** Lets go of everything: no movement input, no jump, no sneak, no sprint. */
    static void release(AIPlayerEntity bot) {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        bot.setShiftKeyDown(false);
        bot.setSprinting(false);
    }
}
