package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Waterborne follower.  It first tries an empty nearby boat, then crafts/launches one only when
 * needed, and drives it through the boat's vanilla paddle-input API.
 */
public final class BoatFollowTask extends AbstractTask {
    private static final double BOAT_STOP_DISTANCE = 5.0D;
    private static final double TURN_ONLY_ANGLE = 82.0D;
    // Watchdog for a mounted boat that is being steered but not actually going anywhere (beached,
    // wedged against a bank or wall, hung on a rock).  steerToward()/setInput() only issue paddle
    // input and never confirm it moved anything, so without this the bot would sit in a dead boat
    // forever.  Progress is judged over a whole window, not tick to tick, so a legitimate in-place
    // U-turn (yaw changes, position barely does) or a slow start from rest is never mistaken for
    // being stuck.
    private static final int STUCK_WINDOW_TICKS = 40; // 2s at 20 TPS
    private static final double STUCK_MIN_PROGRESS = 1.0D; // blocks over one window
    private static final float STUCK_MIN_TURN = 20.0F; // degrees over one window
    // First recovery: paddle backwards for a moment to free the hull, then steer again.  Second
    // consecutive stall: give the boat up (dismount + report it so it is never re-boarded).
    private static final int REVERSE_TICKS = 25;
    private static final int MAX_REVERSE_ATTEMPTS = 1;
    private static final int MAX_ABANDONED_BOATS = 2;

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
    // Stuck watchdog state (see STUCK_WINDOW_TICKS).
    private Vec3 windowStartPos;
    private float windowStartYaw;
    private int windowTicks;
    private int reverseTicksLeft;
    private int reverseAttempts;
    private int abandonedBoatCount;
    // Boats this follower gave up on (beached / wedged); shared with the owning FollowTask so a
    // recreated BoatFollowTask never re-boards the very boat that just failed.
    private final Set<UUID> abandonedBoats;

    /**
     * Creates an explicit boat-follow request.  The bot prepares its own boat even if the player
     * has not boarded yet, then waits on the water rather than silently changing to land-follow.
     */
    public BoatFollowTask(String targetName) {
        this(targetName, true, new HashSet<>());
    }

    /**
     * Used by the ordinary follow task after it observes its target boarding a boat.
     *
     * @param abandonedBoats boats an earlier attempt already gave up on; new giveups are added
     */
    static BoatFollowTask automatic(String targetName, Set<UUID> abandonedBoats) {
        return new BoatFollowTask(targetName, false, abandonedBoats);
    }

    private BoatFollowTask(String targetName, boolean prepareBeforeTargetBoards, Set<UUID> abandonedBoats) {
        this.targetName = FollowTargetResolver.normalize(targetName);
        this.prepareBeforeTargetBoards = prepareBeforeTargetBoards;
        this.abandonedBoats = abandonedBoats;
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
        resetStuckWatchdog();
        reverseAttempts = 0;
        abandonedBoatCount = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayer target = target(bot).orElse(null);
        if (target == null || target.level() != bot.level()) {
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

        boolean targetInBoat = target.getVehicle() instanceof AbstractBoat;
        boolean targetSwimming = !targetInBoat
                && (target.isInWater() || target.isUnderWater());
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
                bot.removeVehicle();
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

        AbstractBoat mounted = BoatSupport.mountedBoat(bot).orElse(null);
        if (mounted != null) {
            if (!targetInBoat) {
                BoatAction.stopBoat(mounted);
                waiting = true;
                phase = Phase.WAITING;
                resetStuckWatchdog();
                return;
            }
            phase = Phase.FOLLOWING;
            if (reverseTicksLeft > 0) {
                // Recovery manoeuvre from the stuck watchdog: back the hull off whatever it is
                // wedged on, then resume normal steering (with a fresh progress window).
                mounted.setInput(false, false, false, true);
                reverseTicksLeft--;
                waiting = false;
                if (reverseTicksLeft == 0) {
                    resetStuckWatchdog();
                }
                return;
            }
            waiting = BoatSupport.steerToward(mounted, target.getX(), target.getZ(), BOAT_STOP_DISTANCE, TURN_ONLY_ANGLE);
            if (waiting) {
                // Arrived (within BOAT_STOP_DISTANCE): not stuck, just done for now.
                resetStuckWatchdog();
                reverseAttempts = 0;
                return;
            }
            if (isStuck(mounted)) {
                onBoatStuck(bot, mounted);
            }
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
            boardTask = new BoardBoatTask(abandonedBoats);
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

    private void resetStuckWatchdog() {
        windowStartPos = null;
        windowTicks = 0;
    }

    /**
     * @return true once a whole {@link #STUCK_WINDOW_TICKS} window of active steering produced
     *     neither real displacement nor a real turn (grounded, wedged against a bank or wall, or
     *     otherwise physically stuck -- steerToward/setInput alone cannot detect this, they only
     *     issue paddle input, never confirm it moved anything).
     */
    private boolean isStuck(AbstractBoat mounted) {
        Vec3 current = mounted.position();
        if (windowStartPos == null) {
            windowStartPos = current;
            windowStartYaw = mounted.getYRot();
            windowTicks = 0;
            return false;
        }
        windowTicks++;
        if (windowTicks < STUCK_WINDOW_TICKS) {
            return false;
        }
        double moved = Math.hypot(current.x - windowStartPos.x, current.z - windowStartPos.z);
        float turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(mounted.getYRot() - windowStartYaw));
        boolean progressed = moved >= STUCK_MIN_PROGRESS || turned >= STUCK_MIN_TURN;
        windowStartPos = current;
        windowStartYaw = mounted.getYRot();
        windowTicks = 0;
        return !progressed;
    }

    /**
     * Stuck-boat recovery ladder: first back the hull off (once per stall), then give the boat up:
     * dismount, remember it so it is never re-boarded, and re-acquire another boat (own inventory
     * boat / crafted boat launched from a proper shore).  Too many give-ups fail the task so the
     * owner can fall back to land or swim following.
     */
    private void onBoatStuck(AIPlayerEntity bot, AbstractBoat mounted) {
        if (reverseAttempts < MAX_REVERSE_ATTEMPTS) {
            reverseAttempts++;
            reverseTicksLeft = REVERSE_TICKS;
            BotLog.action(bot, "boat_follow_stuck_reverse",
                    "pos", mounted.blockPosition().toShortString(), "attempt", reverseAttempts);
            return;
        }
        BotLog.action(bot, "boat_follow_stuck_recovered",
                "pos", mounted.blockPosition().toShortString(), "boat_id", mounted.getUUID());
        abandonedBoats.add(mounted.getUUID());
        BoatAction.stopBoat(mounted);
        bot.removeVehicle();
        resetStuckWatchdog();
        reverseAttempts = 0;
        reverseTicksLeft = 0;
        abandonedBoatCount++;
        if (abandonedBoatCount > MAX_ABANDONED_BOATS) {
            fail("boat_stuck_no_progress");
            return;
        }
        boardTask = null;
        launchTask = null;
        phase = Phase.LAUNCH_BOAT;
        waiting = false;
    }

    /** @return true while the boat still needs to reach a dry shore before dismounting. */
    private boolean leaveBoatForLand(AIPlayerEntity bot, ServerPlayer target) {
        return BoatSupport.leaveBoatForLand(bot, target.getX(), target.getZ(), BOAT_STOP_DISTANCE, TURN_ONLY_ANGLE);
    }

    private Optional<ServerPlayer> target(AIPlayerEntity bot) {
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
