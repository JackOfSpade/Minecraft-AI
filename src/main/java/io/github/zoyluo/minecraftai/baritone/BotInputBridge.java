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
import io.github.zoyluo.minecraftai.action.ActionPack.MovementInputs;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.PacePolicy;
import io.github.zoyluo.minecraftai.action.PaceRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.task.FollowTask;
import io.github.zoyluo.minecraftai.task.TaskManager;
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
 *   <li><b>Pace.</b> How fast the bot goes is the mod's one pace policy ({@link PacePolicy}), shared with bounded local actions: the
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
    /** A re-admitted follow route gets one calculation-only physics tick to receive its predecessor's direction. */
    private static final int FOLLOW_HANDOFF_TICKS = 1;

    private BotInputBridge() {
    }

    /** One executor tick's real world-space keys and gait, retained only across a safe follow re-goal seam. */
    private record FollowHandoff(MovementInputs inputs, boolean sprinting) {
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
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
        boolean executorSuppliedDirection = executor != null && (forward != 0.0F || left != 0.0F);
        boolean handoffSprinting = false;
        if (!executorSuppliedDirection && !jump && !sneak && executor == null
                && baritone.getPathingBehavior().getInProgress().isPresent()) {
            // A fresh path calculation deliberately owns the bot (so no local controller may
            // race it), but has not reached an executor that can force keys yet.  Carry exactly
            // one genuine FollowTask direction through that seam.  The pace calculation below
            // still applies the current item-use slowdown, so a newly raised shield gets 0.2
            // rather than the predecessor's unscaled input.
            FollowHandoff continuation = takeFollowHandoff(bot, entry);
            if (continuation != null) {
                forward = continuation.inputs().forward();
                left = continuation.inputs().strafing();
                handoffSprinting = continuation.sprinting();
            }
        }
        MinecraftAiConfig.Pace config = MinecraftAiConfig.get().behaviour().paceOrDefaults();
        if (!config.paceEnabled()) {
            applyUnpaced(bot, baritone, forward, left, jump, sneak, handoffSprinting);
            if (executorSuppliedDirection) {
                rememberActualDirection(entry, forward, left, bot.getYRot(), bot.isSprinting());
            }
            return;
        }

        ActionPack pack = bot.getActionPack();
        Movement movement = Movement.of(baritone);
        Gait gait = PacePolicy.resolve(bot, goalDistance(bot, pack), true);
        // A sneaking player does not walk off an edge or down a ladder/vine, and the fall/descent/climb has to be made.
        boolean edge = movement.parkour || movement.descending || bot.onClimbable();
        boolean effectiveSneak = sneak || ((gait == Gait.SNEAK || pack.sneakRequested()) && !edge);
        boolean usingItemSlow = bot.isUsingItem();
        float scale = PaceRules.inputScale(effectiveSneak, bot.isUsingItem());
        bot.zza = forward * scale;
        bot.xxa = left * scale;
        bot.setJumping(jump);
        bot.setShiftKeyDown(effectiveSneak);
        boolean sprint = (sprintWanted(baritone) || handoffSprinting)
                && PaceRules.sprintAllowed(bot, forward, effectiveSneak)
                && (movement.parkour || gait == Gait.SPRINT);
        bot.setSprinting(sprint);
        pack.noteEnforced(effectiveSneak ? Gait.SNEAK : sprint ? Gait.SPRINT : Gait.WALK, scale, usingItemSlow);
        if (executorSuppliedDirection) {
            rememberActualDirection(entry, forward, left, bot.getYRot(), sprint);
        }
    }

    /** {@code pace.enabled=false}: what the bridge did before the pace policy. */
    private static void applyUnpaced(AIPlayerEntity bot, IBaritone baritone, float forward, float left, boolean jump, boolean sneak,
                                     boolean handoffSprinting) {
        boolean sprint = (sprintWanted(baritone) || handoffSprinting) && PaceRules.sprintAllowed(bot, forward, sneak);
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

    /** Records a real executor's raw keys; a zero/turning tick never erases the last direction. */
    private static void rememberActualDirection(BaritoneRegistry.Entry entry, float forward, float left, float yaw, boolean sprinting) {
        if (entry == null) {
            return;
        }
        entry.lastBridgeForward = forward;
        entry.lastBridgeStrafing = left;
        entry.lastBridgeYaw = yaw;
        entry.lastBridgeSprinting = sprinting;
        NavRoute route = entry.observedRoute;
        entry.lastBridgeRoute = route;
        entry.hasLastBridgeDirection = true;
        clearFollowHandoff(entry);
    }

    /**
     * Consumes the natural-follow handoff only while a newly admitted FollowTask route is still
     * live and Baritone is calculating rather than executing it.  A cancelled/arrived route is
     * never given a stale key, and the one-use receipt cannot turn a long calculation into a
     * local movement controller.
     */
    private static FollowHandoff takeFollowHandoff(AIPlayerEntity bot, BaritoneRegistry.Entry entry) {
        if (entry == null || !entry.followHandoffAvailable
                || bot.getServer().getTickCount() > entry.followHandoffExpiresAt
                || !(TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof FollowTask)
                || !isLevelFollowHandoff(bot, entry, entry.observedRoute)
                || !bot.getActionPack().hasBaritoneRoute()) {
            clearFollowHandoff(entry);
            return null;
        }
        FollowHandoff continuation = new FollowHandoff(
                ActionPack.reprojectControllerInputs(entry.followHandoffForward, entry.followHandoffStrafing,
                        entry.followHandoffYaw, bot.getYRot()),
                entry.followHandoffSprinting);
        // It is a receipt, not a local controller: consume every part of it before physics can
        // observe the reprojected direction.
        clearFollowHandoff(entry);
        return continuation;
    }

    /** The three route labels FollowTask may admit on land; boat/swim children use other route kinds. */
    private static boolean isLandFollowRoute(NavRoute route) {
        return route != null && switch (route.label()) {
            case "approach", "owner_follow", "directional_pursuit" -> true;
            default -> false;
        };
    }

    /**
     * The route-level prerequisite shared by the receipt issuer and consumer. Owner follow may
     * legitimately swim while it is executing, but a preserved horizontal key is land-only.
     * Directional pursuit keeps its remote target only as heading metadata, so its local resolved
     * hop—not that remote target—must be level with the bot before continuity is allowed.
     */
    static boolean isLevelDryFollowHandoffRoute(NavRoute route, int feetY, boolean inWater) {
        if (!isLandFollowRoute(route) || inWater) {
            return false;
        }
        BlockPos localGoal = route.shape() == NavRoute.Shape.DIRECTIONAL_PURSUIT
                ? route.resolvedGoal() : route.target();
        return localGoal != null && localGoal.getY() == feetY;
    }

    /**
     * The carry key is horizontal-only. It is safe only when both the route that released it and
     * the newly admitted route are dry and level at the same supported feet cell. In particular, a
     * {@code GoalNear} whose target is below (or above) the bot must wait for its own executor:
     * an inherited horizontal key can otherwise enter the goal sphere before the planned drop or
     * climb has actually happened.
     */
    private static boolean isLevelFollowHandoff(AIPlayerEntity bot, BaritoneRegistry.Entry entry, NavRoute route) {
        if (!bot.onGround() || bot.onClimbable()) {
            return false;
        }
        int feetY = bot.blockPosition().getY();
        return entry.followHandoffFeetY == feetY
                && isLevelDryFollowHandoffRoute(route, feetY, bot.isInWater());
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

    /**
     * Lets go of everything after a natural route completion, retaining only the final real
     * FollowTask direction for a one-tick calculation handoff. Explicit cancellations/preemption
     * use {@link #release(AIPlayerEntity)} instead and always clear it.
     */
    static void releaseForFollowHandoff(AIPlayerEntity bot) {
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        NavRoute sourceRoute = entry == null ? null : entry.observedRoute;
        if (entry != null && entry.hasLastBridgeDirection && sourceRoute != null
                && entry.lastBridgeRoute == sourceRoute
                && TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof FollowTask
                && isLevelFollowHandoffAtRelease(bot, sourceRoute)
                && isNaturalFollowHandoffCompletion(BaritoneNavigator.progress(bot, sourceRoute))) {
            entry.followHandoffForward = entry.lastBridgeForward;
            entry.followHandoffStrafing = entry.lastBridgeStrafing;
            entry.followHandoffYaw = entry.lastBridgeYaw;
            entry.followHandoffSprinting = entry.lastBridgeSprinting;
            entry.followHandoffFeetY = bot.blockPosition().getY();
            entry.followHandoffAvailable = true;
            entry.followHandoffExpiresAt = bot.getServer().getTickCount() + FOLLOW_HANDOFF_TICKS;
        } else {
            clearFollowHandoff(entry);
        }
        neutralize(bot);
    }

    /** Only a navigator-confirmed arrival can issue continuity; ended-short and revoked routes must neutralize. */
    static boolean isNaturalFollowHandoffCompletion(NavRoute.Progress progress) {
        return progress == NavRoute.Progress.ARRIVED;
    }

    /** The source route must also have ended dry, level, and supported before it can issue a receipt. */
    private static boolean isLevelFollowHandoffAtRelease(AIPlayerEntity bot, NavRoute route) {
        return bot.onGround() && !bot.onClimbable()
                && isLevelDryFollowHandoffRoute(route, bot.blockPosition().getY(), bot.isInWater());
    }

    /** Lets go of everything: no movement input, no jump, no sneak, no sprint. */
    static void release(AIPlayerEntity bot) {
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        clearFollowInputContinuity(entry);
        neutralize(bot);
    }

    private static void neutralize(AIPlayerEntity bot) {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        bot.setShiftKeyDown(false);
        bot.setSprinting(false);
    }

    static void clearFollowInputContinuity(BaritoneRegistry.Entry entry) {
        if (entry == null) {
            return;
        }
        entry.lastBridgeForward = 0.0F;
        entry.lastBridgeStrafing = 0.0F;
        entry.lastBridgeYaw = 0.0F;
        entry.lastBridgeSprinting = false;
        entry.lastBridgeRoute = null;
        entry.hasLastBridgeDirection = false;
        clearFollowHandoff(entry);
    }

    private static void clearFollowHandoff(BaritoneRegistry.Entry entry) {
        if (entry == null) {
            return;
        }
        entry.followHandoffForward = 0.0F;
        entry.followHandoffStrafing = 0.0F;
        entry.followHandoffYaw = 0.0F;
        entry.followHandoffSprinting = false;
        entry.followHandoffFeetY = Integer.MIN_VALUE;
        entry.followHandoffAvailable = false;
        entry.followHandoffExpiresAt = Integer.MIN_VALUE;
    }
}
