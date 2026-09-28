package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Optional;

/**
 * Waterborne follower.  It first tries an empty nearby boat, then crafts/launches one only when
 * needed, and drives it through the boat's vanilla paddle-input API.
 */
public final class BoatFollowTask extends AbstractTask {
    private static final double BOAT_STOP_DISTANCE = 5.0D;
    private static final double TURN_ONLY_ANGLE = 82.0D;

    private enum Phase {
        ACQUIRE_EXISTING_BOAT,
        LAUNCH_BOAT,
        FOLLOWING,
        WAITING
    }

    private final String targetName;
    private final boolean prepareBeforeTargetBoards;
    private Phase phase = Phase.ACQUIRE_EXISTING_BOAT;
    private BoardBoatTask boardTask;
    private BoatLaunchTask launchTask;
    private FollowTask continuedFollow;
    private boolean waiting;
    private boolean observedTargetBoat;

    /**
     * Creates an explicit boat-follow request.  The bot prepares its own boat even if the player
     * has not boarded yet, then waits on the water rather than silently changing to land-follow.
     */
    public BoatFollowTask(String targetName) {
        this(targetName, true);
    }

    /** Used by the ordinary follow task after it observes its target boarding a boat. */
    static BoatFollowTask automatic(String targetName) {
        return new BoatFollowTask(targetName, false);
    }

    private BoatFollowTask(String targetName, boolean prepareBeforeTargetBoards) {
        this.targetName = FollowTargetResolver.normalize(targetName);
        this.prepareBeforeTargetBoards = prepareBeforeTargetBoards;
    }

    @Override
    public String name() {
        return "boat_follow";
    }

    @Override
    public String describe() {
        return "Following " + (targetName.isBlank() ? "owner" : targetName)
                + " by boat phase=" + phase + (waiting ? " waiting" : "");
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case ACQUIRE_EXISTING_BOAT -> 0.15D;
            case LAUNCH_BOAT -> 0.45D;
            case FOLLOWING -> waiting ? 0.50D : 0.75D;
            case WAITING -> 0.50D;
        };
    }

    @Override
    public boolean isWaiting() {
        return waiting;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.ACQUIRE_EXISTING_BOAT;
        boardTask = null;
        launchTask = null;
        continuedFollow = null;
        waiting = false;
        observedTargetBoat = false;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayerEntity target = target(bot).orElse(null);
        if (target == null || target.getEntityWorld() != bot.getEntityWorld()) {
            BoatSupport.mountedBoat(bot).ifPresent(BoatAction::stopBoat);
            waiting = true;
            phase = Phase.WAITING;
            return;
        }

        if (continuedFollow != null) {
            continuedFollow.tick(bot);
            waiting = continuedFollow.isWaiting();
            if (continuedFollow.state() == TaskState.FAILED) {
                fail("continued_follow_failed:" + continuedFollow.failureReason());
            }
            return;
        }

        boolean targetInBoat = target.getVehicle() instanceof AbstractBoatEntity;
        boolean targetSwimming = !targetInBoat
                && (target.isTouchingWater() || target.isSubmergedInWater());
        if (targetInBoat) {
            observedTargetBoat = true;
        }
        if (targetSwimming) {
            // Even an explicitly requested boat follower must not turn a swimmer into a boat
            // request.  Release any boat and hand back to the normal mode-aware follower.
            // Logged because this mode switch is otherwise invisible: continuedFollow is driven
            // directly (never through TaskManager), so nothing else records why an explicit
            // boat_follow request ended up walking/swimming on land instead.
            BotLog.action(bot, "boat_follow_target_swimming", "target", targetName);
            BoatSupport.mountedBoat(bot).ifPresent(boat -> {
                BoatAction.stopBoat(boat);
                bot.dismountVehicle();
            });
            continuedFollow = new FollowTask(targetName);
            continuedFollow.start(bot);
            continuedFollow.tick(bot);
            waiting = continuedFollow.isWaiting();
            return;
        }
        if (!targetInBoat && (observedTargetBoat || !prepareBeforeTargetBoards)) {
            // Every follow mode must release the boat when the player gets off.  The parent
            // FollowTask then retains ownership and continues on land; direct boat_follow has
            // finished its explicit waterborne instruction.
            if (leaveBoatForLand(bot, target)) {
                waiting = true;
                return;
            }
            // Same visibility gap as the swimming case above: the player got off the boat, so this
            // explicit boat-follow instruction is handing off to land-follow now.
            BotLog.action(bot, "boat_follow_target_left_boat", "target", targetName);
            continuedFollow = new FollowTask(targetName);
            continuedFollow.start(bot);
            continuedFollow.tick(bot);
            waiting = continuedFollow.isWaiting();
            return;
        }

        AbstractBoatEntity mounted = BoatSupport.mountedBoat(bot).orElse(null);
        if (mounted != null) {
            if (!targetInBoat) {
                BoatAction.stopBoat(mounted);
                waiting = true;
                phase = Phase.WAITING;
                return;
            }
            waiting = BoatSupport.steerToward(mounted, target.getX(), target.getZ(), BOAT_STOP_DISTANCE, TURN_ONLY_ANGLE);
            phase = Phase.FOLLOWING;
            return;
        }

        if (!targetInBoat && prepareBeforeTargetBoards) {
            // An explicit "follow me by boat" order means prepare the bot's boat now, then wait.
            acquireBoat(bot);
            return;
        }
        acquireBoat(bot);
    }

    private void acquireBoat(AIPlayerEntity bot) {
        if (boardTask != null) {
            boardTask.tick(bot);
            if (boardTask.state() == TaskState.COMPLETED) {
                boardTask = null;
                phase = Phase.FOLLOWING;
                return;
            }
            if (boardTask.state() == TaskState.FAILED) {
                boardTask = null;
                phase = Phase.LAUNCH_BOAT;
            }
            return;
        }
        if (phase == Phase.ACQUIRE_EXISTING_BOAT) {
            boardTask = new BoardBoatTask();
            boardTask.start(bot);
            return;
        }
        if (launchTask == null) {
            launchTask = new BoatLaunchTask(true);
            launchTask.start(bot);
        }
        launchTask.tick(bot);
        if (launchTask.state() == TaskState.COMPLETED) {
            phase = Phase.FOLLOWING;
            return;
        }
        if (launchTask.state() == TaskState.FAILED) {
            fail("boat_unavailable:" + launchTask.failureReason());
        }
    }

    /** @return true while the boat still needs to reach a dry shore before dismounting. */
    private boolean leaveBoatForLand(AIPlayerEntity bot, ServerPlayerEntity target) {
        return BoatSupport.leaveBoatForLand(bot, target.getX(), target.getZ(), BOAT_STOP_DISTANCE, TURN_ONLY_ANGLE);
    }

    private Optional<ServerPlayerEntity> target(AIPlayerEntity bot) {
        return FollowTargetResolver.resolve(bot, targetName);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (boardTask != null && boardTask.state() == TaskState.RUNNING) {
            boardTask.abort(bot);
        }
        if (launchTask != null && launchTask.state() == TaskState.RUNNING) {
            launchTask.abort(bot);
        }
        if (continuedFollow != null && continuedFollow.state() == TaskState.RUNNING) {
            continuedFollow.abort(bot);
        }
        BoatSupport.mountedBoat(bot).ifPresent(BoatAction::stopBoat);
        super.onAbort(bot);
    }
}
