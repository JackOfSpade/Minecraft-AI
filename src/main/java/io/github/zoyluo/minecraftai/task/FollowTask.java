package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.PaceOwner;
import io.github.zoyluo.minecraftai.action.PacePolicy;
import io.github.zoyluo.minecraftai.action.QuietZone;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Mirrors the followed player's travel mode without treating water as ordinary land:
 * land follows pathfind, a boated player triggers boat acquisition/follow, and a swimming player
 * is followed through bounded verified swim steps without launching a boat.
 */
public final class FollowTask extends AbstractTask {
    // +1 block from the previous 2.0: players reported the bot settling uncomfortably close.
    // Swim/boat distances below are unaffected -- a trailing boat or swimmer already keeps a
    // wider berth for its own steering/collision reasons unrelated to this personal-space call.
    private static final double STOP_DISTANCE = 3.0D;
    private static final double MAX_DIRECT_FALLBACK_DISTANCE = 12.0D;
    private static final int REPATH_TICKS = 40;
    /** One out-of-view follow leg never reaches beyond this locally observed horizontal hop. */
    private static final int DIRECTIONAL_PURSUIT_MAX_HOP = 12;
    // Retry delay after a "pathfinding_throttled" answer (ActionPack's success cooldown is 5 ticks).
    private static final int THROTTLED_RETRY_TICKS = 5;
    private static final double SWIM_STOP_DISTANCE = 3.5D;
    private static final double BOAT_TURN_ONLY_ANGLE = 82.0D;
    // How close a boat paddling to a dry shore aims before dismounting. This was the land STOP_DISTANCE
    // (2.0) until r10 widened that to 3.0 and, unintentionally, this standoff with it; the shore
    // approach is unrelated to the follower's personal space, so it keeps its own value.
    private static final double BOAT_SHORE_STANDOFF = 2.0D;
    // A block-snapped bot cannot always land at exactly STOP_DISTANCE: standNear floors to a
    // BlockPos, and the followed player's own continuous (non-integer) position means the real
    // entity-to-entity distance at that cell is only ever approximately STOP_DISTANCE. Without
    // slack, a cell whose true distance is a hair above STOP_DISTANCE (observed at exactly
    // integer separations, e.g. player and bot aligned on one axis) never satisfies the strict
    // arrival check, so the bot re-plans an already-arrived, zero-length route forever -- the
    // real GameTest fixture for this exact shape caught it. Slack is one-sided (an arrival check
    // only, not the offset above), so the bot still settles close to STOP_DISTANCE and never
    // closer than STOP_DISTANCE - 0 (this only ever widens the accepted far edge).
    private static final double STOP_ARRIVAL_SLACK = 0.5D;
    // A failed acquisition (BoatFollowTask.FAILED, e.g. "no water shore nearby yet") must not be
    // retried every tick: BoatLaunchTask.findLaunchSite alone is an O(33^3) visibility scan, and
    // retrying it every tick while the bot has not moved an inch produces the exact same failure
    // every time -- this is the "follow_boat_fallback_swim churn" from the live diagnosis. Back off
    // for a few real seconds between attempts, walking toward water in the meantime instead.
    private static final int BOAT_ACQUIRE_COOLDOWN_TICKS = 60;
    // Failed acquisitions (while the target is boating) before a bot with no boat material swims after
    // it instead of walking to the shore and trying again.
    private static final int BOAT_SWIM_AFTER_FAILURES = 2;
    // Throttles the walk-toward-water helper's own findLaunchSite scan the same way followLand
    // throttles its repathing, so backing off from a failed acquisition does not just trade one
    // every-tick scan for another.
    private static final int BOAT_WATER_SCAN_COOLDOWN_TICKS = 30;
    private static final int BOAT_TARGET_SHORE_RADIUS = 24;

    private final String targetName;
    private int nextRepathTick;
    private boolean waiting;
    // Set only when a full pathfind failed with the target still farther than the direct-walk
    // fallback range, so we deliberately wait out nextRepathTick instead of retrying an expensive
    // search every tick. Left false whenever a path/walk is actually issued, so an ordinary short
    // leg that simply finishes early is never mistaken for this backoff and can re-path immediately.
    private boolean repathBackoff;
    private BoatFollowTask boatFollow;
    private int nextBoatAttemptTick;
    private int boatAcquireFailures;
    private int nextBoatWaterScanTick;
    // Reason the last acquisition failed, and boats already given up on (beached/wedged): both
    // outlive the BoatFollowTask instance that produced them.
    private String lastBoatFailure = "";
    private final Set<UUID> abandonedBoats = new HashSet<>();
    private final ShelterExitDebtRepayer shelterExitDebtRepayer = new ShelterExitDebtRepayer();
    private final FollowStuckRecovery stuckRecovery = new FollowStuckRecovery();
    private int directWalkCount;
    // A failed search whose straight-line fallback was refused (water, a drop, a wall on the line): the follower
    // waits at its bank and re-plans on the normal schedule, and tells the player so once until it next arrives.
    private boolean noRouteAnnounced;
    private int noRouteNotices;
    // Non-genuine failed re-plans in a row (see FollowNoRoute.RepeatedFailures): repeated ones get a generic notice.
    private final FollowNoRoute.RepeatedFailures repeatedFailures = new FollowNoRoute.RepeatedFailures();
    // Where the followed player stood when the resolved goal collapsed onto the bot's own cell (see
    // followLand): the hold is re-evaluated as soon as they move rather than after a full REPATH_TICKS.
    private BlockPos holdTargetPos;
    private int nextHoldReevalTick;
    private final FollowSwimming swimming = new FollowSwimming();
    // ---- nav.engine=baritone (see followLandBaritone): the followed player's last known cell
    // (used solely to decide whether a moving player needs a re-goal), the approach radius in
    // use, and the last route outcome already reacted to. A directional route's physical goal
    // lives in ActionPack as its resolved observed hop, never in this remote target marker.
    private BlockPos baritoneTargetPos;
    private int baritoneRadius = (int) STOP_DISTANCE;
    private boolean baritoneDirectionalPursuit;
    /** True only while a verified owner's live-coordinate route owns Baritone; named/non-owner follows retain strict observation. */
    private boolean baritoneOwnerFollow;
    private NavOutcome handledOutcome;
    /** The no-progress rule of the running Baritone route (see FollowProgressWindow). */
    private final FollowProgressWindow baritoneProgress = new FollowProgressWindow();
    private int baritoneStarts;
    private int baritoneRegoals;
    // ---- pace (see FollowPace) and escort (see FollowEscort)
    /** How long the recent positions of the followed player are kept for measuring its speed (game ticks). */
    private static final int SPEED_WINDOW_TICKS = 10;
    private final QuietZone quietZone = new QuietZone();
    private final FollowEscort escort = new FollowEscort();
    /** Recent positions of the followed player as {gameTime, x, z}, oldest first. */
    private final ArrayDeque<double[]> targetSamples = new ArrayDeque<>();
    private long targetSneakSince = -1L;
    private Gait paceGait = Gait.WALK;
    private long paceSince;
    private ServerPlayer lastTarget;

    /** The player this task resolved on its last tick (empty before the first tick, or while the player is offline). */
    public Optional<ServerPlayer> currentTarget() {
        return Optional.ofNullable(lastTarget);
    }

    /** True while the escort sees a hostile within a few blocks: the task then ticks even under a degraded TPS. */
    boolean escortEngaged() {
        return escort.engaged();
    }

    /** Package-visible for GameTests: swings the escort has landed. */
    int escortStrikes() {
        return escort.strikes();
    }

    /** Package-visible for GameTests: the game tick of the last ready swing that was still turning toward its target. */
    long escortLastAimTick() {
        return escort.lastAimTick();
    }

    /** Package-visible for GameTests: the gait this task last asked for. */
    Gait paceGait() {
        return paceGait;
    }

    /** Package-visible for GameTests: how many Baritone routes land follow has started (not counting re-targeting of a running one). */
    int baritoneStarts() {
        return baritoneStarts;
    }

    /** Package-visible for GameTests: how often a running Baritone route was re-pointed at the moving player. */
    int baritoneRegoals() {
        return baritoneRegoals;
    }

    public FollowTask(String targetName) {
        this.targetName = FollowTargetResolver.normalize(targetName);
    }

    /** Package-visible for GameTests: bounded water-route searches run by the swim/exit logic. */
    int swimRouteSearchCount() {
        return swimming.routeSearchCount();
    }

    /** Package-visible for GameTests: how many times the swimmer turned up for breath. */
    int swimAscendCount() {
        return swimming.ascendCount();
    }

    /** Package-visible for GameTests: whether the stuck-recovery dig-out currently owns the bot. */
    boolean digOutActive() {
        return stuckRecovery.isDigging();
    }

    /** Package-visible for GameTests: how many straight-line walks land follow has started. */
    int directWalkCount() {
        return directWalkCount;
    }

    /** Package-visible for GameTests: true while the goal has collapsed onto the bot's own cell and it is holding there. */
    boolean holdingAtOwnCell() {
        return holdTargetPos != null;
    }

    /** Package-visible for GameTests: how many times the player was told there is no route to them. */
    int noRouteNotices() {
        return noRouteNotices;
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public String describe() {
        return "Following " + (targetName.isBlank() ? "owner" : targetName)
                + (boatFollow != null ? " by boat" : "")
                + (waiting ? " waiting" : "");
    }

    @Override
    public double progress() {
        return waiting ? 0.0D : 0.5D;
    }

    @Override
    public boolean isWaiting() {
        return waiting;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        nextRepathTick = 0;
        swimming.reset(bot);
        waiting = false;
        repathBackoff = false;
        noRouteAnnounced = false;
        repeatedFailures.reset();
        holdTargetPos = null;
        nextHoldReevalTick = 0;
        boatFollow = null;
        nextBoatAttemptTick = 0;
        boatAcquireFailures = 0;
        nextBoatWaterScanTick = 0;
        lastBoatFailure = "";
        abandonedBoats.clear();
        shelterExitDebtRepayer.reset(bot);
        stuckRecovery.reset(bot, elapsed);
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        baritoneTargetPos = null;
        baritoneDirectionalPursuit = false;
        baritoneOwnerFollow = false;
        baritoneProgress.clear();
        baritoneRadius = (int) STOP_DISTANCE;
        handledOutcome = bot.getActionPack().lastRouteOutcome();
        escort.reset();
        targetSamples.clear();
        targetSneakSince = -1L;
        paceGait = Gait.WALK;
        paceSince = bot.level().getGameTime() - FollowPace.DWELL_TICKS;
        lastTarget = null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayer target = target(bot).orElse(null);
        if (target == null || target.level() != bot.level()) {
            lastTarget = null;
            escort.disengage();
            targetSamples.clear();
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            suspendLandRecovery(bot);
            // stopAll() is intentionally a generic cancellation and therefore cannot release
            // FollowSwimming's guarded lease.  Reconcile our exact owner before waiting for an
            // offline/cross-dimension target, or its ActionPack fence would survive forever.
            swimming.cancelStep(bot);
            stopBoatAndActions(bot);
            baritoneTargetPos = null;
            baritoneDirectionalPursuit = false;
            baritoneOwnerFollow = false;
            baritoneProgress.clear();
            waiting = true;
            if (elapsed % 200 == 1) {
                BotLog.action(bot, "follow_target_offline", "target", targetName.isBlank() ? "owner" : targetName);
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "The player I am following is offline or in another dimension, so I will wait here.");
            }
            return;
        }

        lastTarget = target;
        observeTarget(bot, target);
        faceTarget(bot, target);

        // The owner/name lookup above is explicit player authority, not a radius- or
        // line-of-sight-gated entity scan.  If a new instruction cancelled a sealed shelter,
        // first reopen only the task-owned doorway, then continue following that known player.
        if (shelterExitDebtRepayer.repay(bot, target, elapsed)) {
            waiting = shelterExitDebtRepayer.isWaiting();
            return;
        }

        boolean targetInBoat = target.getVehicle() instanceof AbstractBoat;
        boolean targetSwimming = !targetInBoat && isWaterborne(target);
        if (targetInBoat) {
            escort.disengage();
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            swimming.cancelStep(bot);
            suspendLandRecovery(bot);
            dropBaritoneRoute(bot);
            followBoat(bot, target);
            return;
        }
        if (targetSwimming) {
            escort.disengage();
            suspendLandRecovery(bot);
            abandonBoatChild(bot);
            // Swim-follow uses a bounded, individually observed physical-survival routine; a land route
            // that was still running must not keep writing the bot's inputs next to it.
            dropBaritoneRoute(bot);
            followSwimming(bot, target);
            return;
        }

        // The player is on land.  A follower already in a boat first paddles to a genuine dry
        // shore and asks vanilla to dismount, then immediately returns to ordinary land follow.
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        abandonBoatChild(bot);
        if (leaveBoatForLand(bot, target)) {
            escort.disengage();
            suspendLandRecovery(bot);
            dropBaritoneRoute(bot);
            waiting = true;
            return;
        }
        // The player has left the water but the bot is still in it: swim to a dry landing near
        // them first (this renews the narrow swim lease itself), then ordinary land follow runs.
        publishPace(bot, target);
        if (swimming.exitWaterForLand(bot, target, elapsed, STOP_DISTANCE)) {
            escort.disengage();
            suspendLandRecovery(bot);
            dropBaritoneRoute(bot);
            waiting = swimming.isWaiting();
            return;
        }
        followLand(bot, target);
        // After the movement decisions of this tick: a swing never stops or redirects the follow.
        float yawBeforeEscort = bot.getYRot();
        escort.tick(bot, target);
        if (!bot.getActionPack().hasBaritoneRoute()) {
            // Combat aim runs after this bot has already written the next local input. Re-express that same world-space movement
            // under the new combat yaw so the next physics tick keeps following rather than stepping toward the hostile.
            bot.getActionPack().reprojectControllerInputsForYawChange(yawBeforeEscort);
        }
    }

    /**
     * Keeps the last ten ticks of the followed player's horizontal position (by game time, so a throttled tick is fine) and counts
     * how long they have been sneaking: the two things the follow pace reads off the player.
     */
    private void observeTarget(AIPlayerEntity bot, ServerPlayer target) {
        long now = bot.level().getGameTime();
        double[] last = targetSamples.peekLast();
        if (last == null || (long) last[0] != now) {
            targetSamples.addLast(new double[] {now, target.getX(), target.getZ()});
        }
        while (targetSamples.size() > 2 && now - (long) targetSamples.peekFirst()[0] > SPEED_WINDOW_TICKS) {
            targetSamples.pollFirst();
        }
        if (target.isShiftKeyDown()) {
            if (targetSneakSince < 0L) {
                targetSneakSince = now;
            }
        } else {
            targetSneakSince = -1L;
        }
    }

    /** Horizontal blocks per second of the followed player over the kept window; 0 until two samples are apart. */
    private double targetSpeedBps() {
        double[] first = targetSamples.peekFirst();
        double[] last = targetSamples.peekLast();
        if (first == null || last == null || last[0] - first[0] < 2.0D) {
            return 0.0D;
        }
        double dx = last[1] - first[1];
        double dz = last[2] - first[2];
        return Math.sqrt(dx * dx + dz * dz) / ((last[0] - first[0]) / 20.0D);
    }

    /** Decides the gait of this tick (FollowPace) and leases it to the pace policy for every walking or swimming follow mode. */
    private void publishPace(AIPlayerEntity bot, ServerPlayer target) {
        if (!MinecraftAiConfig.get().behaviour().paceOrDefaults().paceEnabled()) {
            return;
        }
        long now = bot.level().getGameTime();
        quietZone.refresh(bot);
        MinecraftAiConfig.Follow follow = MinecraftAiConfig.get().behaviour().followOrDefaults();
        int sneakTicks = targetSneakSince < 0L ? 0 : (int) Math.min(Integer.MAX_VALUE, now - targetSneakSince);
        FollowPace.Input input = new FollowPace.Input(bot.distanceTo(target), targetSpeedBps(), target.isSprinting(), sneakTicks,
                quietZone.level(), PacePolicy.underPressure(bot), quietZone.huntingWardenObserved(),
                quietZone.calmWardenWithin(PacePolicy.CALM_WARDEN_RANGE), paceGait,
                (int) Math.min(Integer.MAX_VALUE, now - paceSince), follow.walkGap(), follow.sprintGap());
        Gait gait = FollowPace.decide(input);
        if (gait != paceGait) {
            BotLog.path(bot, "follow_pace", "from", paceGait, "to", gait, "gap", Math.round(input.gap() * 10.0D) / 10.0D,
                    "target_bps", Math.round(input.targetSpeedBps() * 10.0D) / 10.0D, "target_sprint", input.targetSprinting(),
                    "target_sneak_ticks", sneakTicks, "quiet", input.quiet(), "pressure", input.pressure());
            paceGait = gait;
            paceSince = now;
        }
        bot.getActionPack().requestPace(gait, PaceOwner.FOLLOW);
    }

    /** Another follow mode takes the bot: a Baritone land route of this task ends now (single writer). */
    private void dropBaritoneRoute(AIPlayerEntity bot) {
        baritoneTargetPos = null;
        baritoneDirectionalPursuit = false;
        baritoneOwnerFollow = false;
        baritoneProgress.clear();
        if (bot.getActionPack().hasBaritoneRoute()) {
            bot.getActionPack().cancelBaritoneRoute("follow_mode_changed");
        }
    }

    /**
     * WalkToController only ever touches yaw (it preserves whatever pitch the bot already had), so
     * without a pitch update the bot's head stays frozen at its pre-follow pitch for the whole task
     * -- this is what "looking at the sky" was.
     *
     * <p>The YAW, however, belongs to whichever path/walk controller is steering: this runs at the
     * end of the server tick, after the bot has already moved, and the bearing the controller just
     * set is what the next tick's movement uses. Re-aiming the whole body at the player here turned
     * every path leg into "walk straight at the player" (session log: 7 windows of ~8 s where the
     * bot ignored its path nodes, pressed toward the moving player and ended in walk_failed
     * timeout). While anything is navigating only the pitch follows the player; when idle or
     * arrived the bot turns to face them completely.
     */
    private void faceTarget(AIPlayerEntity bot, ServerPlayer target) {
        if (bot.getActionPack().hasBaritoneRoute()) {
            // A Baritone route owns the aim, yaw AND pitch (single writer, see BaritoneDriver): it looks where the next break or door
            // click needs it to, often steeply down at the lower block of a two-high dig. Pitching the head at the player here, at the
            // end of the tick, put the pitch back before Baritone's next click, so the click never landed on the block it aimed at
            // (a follower that could dig the upper block of a wall and then stood at its lower block for ever).
            return;
        }
        boolean steering = !bot.getActionPack().isPathExecutorIdle() || !bot.getActionPack().isWalkToIdle();
        if (steering) {
            LookAction.lookPitchAt(bot, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        } else if (!escort.holdsFacing(bot.level().getGameTime())) {
            // Right after a swing at a hostile the follower stays as it is instead of turning back to the player at once.
            CombatCore.lookAt(bot, target);
        }
    }

    /**
     * Land stuck recovery (stall clock, forced-repath schedule, and above all a dig-out that owns the mining
     * controller) belongs to land follow only. Whenever swim/boat/exit-water logic takes a tick, or the task
     * pauses/aborts, its state is reset (which also cancels an active dig-out and stops its mining) so nothing
     * stale carries over and nothing keeps breaking blocks while another mode drives the bot.
     */
    private void suspendLandRecovery(AIPlayerEntity bot) {
        stuckRecovery.reset(bot, elapsed);
    }

    private void followBoat(AIPlayerEntity bot, ServerPlayer target) {
        if (boatFollow == null) {
            if (elapsed < nextBoatAttemptTick) {
                // Backing off a recent failed acquisition (see BOAT_ACQUIRE_COOLDOWN_TICKS): make
                // real progress toward water/the target instead of silently doing nothing, but do
                // not re-scan for a boat/launch site every tick while doing it.
                boatAcquireBackoffStep(bot, target);
                return;
            }
            boatFollow = BoatFollowTask.automatic(targetName, abandonedBoats);
            boatFollow.start(bot);
        }
        boatFollow.tick(bot);
        waiting = boatFollow.isWaiting();
        if (boatFollow.state() == TaskState.FAILED) {
            // The automatic priority is encoded inside BoatFollowTask: board a nearby empty
            // boat first, then craft/launch one.  When both are unavailable (or the mounted boat
            // watchdog gave up on a stuck/beached one), back off for a few seconds and walk toward
            // water in the meantime rather than immediately retrying the same failing scan.
            lastBoatFailure = String.valueOf(boatFollow.failureReason());
            boatAcquireFailures++;
            BotLog.action(bot, "follow_boat_acquire_failed_backoff", "reason", lastBoatFailure);
            boatFollow = null;
            nextBoatAttemptTick = elapsed + BOAT_ACQUIRE_COOLDOWN_TICKS;
            boatAcquireBackoffStep(bot, target);
        }
    }

    /**
     * One tick of following while the next boat acquisition attempt is backed off.  A bot that is
     * already in the water keeps following the boating player by swimming, and so does one that
     * cannot get any boat at all (no boat item and no planks for one) once it has retried from the
     * shore; otherwise it walks toward the water so that the next attempt starts from a shore.
     */
    private void boatAcquireBackoffStep(AIPlayerEntity bot, ServerPlayer target) {
        boolean noBoatMaterial = lastBoatFailure.contains(BoatLaunchTask.FAIL_NEED_BOAT_OR_PLANKS);
        // A bot with no boat material only gives up on boarding/launching and swims after the boating
        // player once it has retried the acquisition from the water's edge as well (BOAT_SWIM_AFTER_FAILURES):
        // rushing into the lake on the first failure walks it past an empty boat it could still board.
        boolean swimInstead = noBoatMaterial && boatAcquireFailures >= BOAT_SWIM_AFTER_FAILURES;
        if (swimInstead || bot.isInWater() || bot.isUnderWater()) {
            followSwimming(bot, target);
            return;
        }
        walkTowardBoatWater(bot, target);
    }

    /**
     * Makes real progress toward water while a boat acquisition attempt is backed off, per
     * REQUIRED BEHAVIOUR (A)/(E): walk to the nearest observable launch shore when one is visible,
     * otherwise walk toward the target itself (the boating player is usually near or over water,
     * so closing distance on them tends to bring water into view). The launch-site scan itself is
     * throttled the same way followLand throttles its own repathing -- never done every tick.
     */
    private void walkTowardBoatWater(AIPlayerEntity bot, ServerPlayer target) {
        if (elapsed < nextBoatWaterScanTick) {
            // Strictly time-gated: an arrived (idle) bot must not turn this into an every-tick
            // launch-site scan plus a zero-length walk request.
            waiting = false;
            return;
        }
        nextBoatWaterScanTick = elapsed + BOAT_WATER_SCAN_COOLDOWN_TICKS;
        BoatSupport.LaunchSite site = BoatSupport.findLaunchSite(bot).orElse(null);
        // No visible launch shore yet: close in on the boating player over dry land.  Their boat is
        // on water, so aim at the nearest standable cell to them rather than at the water itself.
        BlockPos destination = site != null
                ? site.shore()
                : BoatSupport.findWaterApproach(bot).orElseGet(() -> Standability.findNearestStandable(
                        bot.level(), target.blockPosition(), BOAT_TARGET_SHORE_RADIUS, 8, 4)
                        .orElse(null));
        if (destination == null) {
            waiting = true;
            return;
        }
        ActionResult path = bot.getActionPack().startPathTo(destination);
        if (path.isFailed() && site != null) {
            bot.getActionPack().startWalkTo(destination.getCenter(), 1.0D);
        }
        waiting = false;
    }

    private void followSwimming(AIPlayerEntity bot, ServerPlayer target) {
        // A swimmer still has a gap to close. The same FOLLOW lease makes a far swimmer sprint
        // and lets a close/friendly player keep the bot at a walk or sneak pace.
        publishPace(bot, target);
        if (BoatSupport.mountedBoat(bot).isPresent()) {
            // The player is swimming, not boating.  Never keep driving or acquire a new boat in
            // this branch; vanilla places the passenger in the adjacent water on dismount.
            BoatSupport.mountedBoat(bot).ifPresent(BoatAction::stopBoat);
            bot.removeVehicle();
            waiting = true;
            return;
        }
        // Swim after the player (entering the water at the nearest observable edge, diving with
        // them under oxygen management, no boat); see FollowSwimming.
        waiting = swimming.follow(bot, target, elapsed, SWIM_STOP_DISTANCE);
    }

    /** Ticks between re-targeting a running Baritone route at the moving player (Baritone re-plans in between by itself). */
    private static final int BARITONE_REGOAL_TICKS = 20;
    /** The player must have moved this far (squared, blocks) from the last actual-player marker before the route is re-pointed. */
    private static final double BARITONE_REGOAL_MOVED_SQ = 4.0D;

    /**
     * Land follow uses a direct {@code GoalNear(player, radius)} for this bot's verified owner, even when the owner is outside
     * view: Baritone can inspect only the current server-captured full-chunk snapshot and never gets a cached or force-loaded
     * cell. A named/non-owner player keeps the strict observed route and its short directional-hop fallback.
     */
    private boolean followLandBaritone(AIPlayerEntity bot, ServerPlayer target) {
        ActionPack pack = bot.getActionPack();
        BlockPos targetPos = target.blockPosition();
        if (bot.distanceTo(target) <= STOP_DISTANCE + STOP_ARRIVAL_SLACK) {
            pack.stopNavigation();
            waiting = true;
            noRouteAnnounced = false;
            repeatedFailures.reset();
            baritoneTargetPos = null;
            baritoneDirectionalPursuit = false;
            baritoneOwnerFollow = false;
            baritoneProgress.clear();
            baritoneRadius = (int) STOP_DISTANCE;
            repathBackoff = false;
            return true;
        }
        if (!pack.isPathExecutorIdle()) {
            waiting = false;
            if (baritoneProgress.stalled(elapsed, bot.distanceTo(target), bot.getX(), bot.getZ())) {
                // A route that gets the bot nowhere (a door it cannot open, a replan loop) is abandoned with a back-off, the way
                // the prior local controller abandoned a frozen executor, instead of spinning until the route deadline.
                boolean directional = baritoneDirectionalPursuit;
                boolean ownerFollow = baritoneOwnerFollow;
                BotLog.action(bot, "follow_route_no_progress", "pos", io.github.zoyluo.minecraftai.log.LogFields.pos(bot.blockPosition()),
                        "distance", bot.distanceTo(target), "directional", directional, "owner_follow", ownerFollow);
                pack.cancelBaritoneRoute("follow_no_progress");
                baritoneProgress.clear();
                if (directional) {
                    noteDirectionalPursuitFailure(bot, targetPos, "follow_no_progress");
                } else {
                    announceNoRoute(bot, targetPos, "follow_no_progress", FollowNoRoute.GENERIC_MESSAGE);
                }
                repathBackoff = true;
                nextRepathTick = elapsed + REPATH_TICKS;
                baritoneTargetPos = null;
                baritoneDirectionalPursuit = false;
                baritoneOwnerFollow = false;
                waiting = true;
                return true;
            }
            if (baritoneTargetPos != null && elapsed >= nextRepathTick
                    && baritoneTargetPos.distSqr(targetPos) >= BARITONE_REGOAL_MOVED_SQ) {
                LandRouteAttempt regoal = startLandRoute(bot, target, targetPos, true);
                baritoneRegoals++;
                nextRepathTick = elapsed + (regoal.result().isFailed() ? REPATH_TICKS : BARITONE_REGOAL_TICKS);
                if (!regoal.result().isFailed()) {
                    acceptLandRoute(targetPos, regoal);
                } else {
                    reportLandRouteFailure(bot, targetPos, regoal);
                    repathBackoff = true;
                    baritoneTargetPos = null;
                    baritoneDirectionalPursuit = false;
                    baritoneOwnerFollow = false;
                    waiting = true;
                }
            }
            return true;
        }
        // No route is running: either none was started yet, or the last one ended.
        NavOutcome ended = pack.lastRouteOutcome();
        if (ended != null && ended != handledOutcome) {
            handledOutcome = ended;
            boolean directional = isDirectionalPursuit(ended);
            boolean ownerFollow = isOwnerFollow(ended);
            baritoneTargetPos = null;
            baritoneDirectionalPursuit = false;
            baritoneOwnerFollow = false;
            if (ended.status() == NavOutcome.Status.FAILED || ended.status() == NavOutcome.Status.TIMEOUT) {
                // A partial directional leg has reached the edge of its current local proof. It
                // is expected to acquire the next cone immediately; every other failure backs
                // off, but remains a standing follow order and keeps retrying indefinitely.
                if ((directional || ownerFollow) && NavRouteRules.PATH_INCOMPLETE.equals(ended.reason())) {
                    repathBackoff = false;
                } else {
                    if (directional) {
                        noteDirectionalPursuitFailure(bot, targetPos, ended.reason());
                    } else {
                        announceNoRoute(bot, targetPos, ended.reason(), noRouteMessage(ended));
                    }
                    repathBackoff = true;
                    nextRepathTick = elapsed + REPATH_TICKS;
                }
            } else if (ended.status() == NavOutcome.Status.SUCCESS) {
                if (directional) {
                    // A completed local hop is real evidence that the last temporary hold has
                    // cleared. Re-arm any diagnostic before continuing toward the next cone.
                    noRouteAnnounced = false;
                    repeatedFailures.reset();
                } else {
                    // Inside the direct GoalNear radius but not close enough for the arrival
                    // rule: aim tighter. A directional hop is already an exact local standoff
                    // leg, so it must keep its independent max-hop size instead of shrinking to
                    // one block.
                    baritoneRadius = Math.max(1, baritoneRadius - 1);
                }
            }
        }
        if (repathBackoff && elapsed < nextRepathTick) {
            waiting = true;
            return true;
        }
        LandRouteAttempt started = startLandRoute(bot, target, targetPos, false);
        baritoneStarts++;
        nextRepathTick = elapsed + (started.result().isFailed() ? REPATH_TICKS : BARITONE_REGOAL_TICKS);
        if (started.result().isFailed()) {
            reportLandRouteFailure(bot, targetPos, started);
            repathBackoff = true;
            baritoneTargetPos = null;
            baritoneDirectionalPursuit = false;
            baritoneOwnerFollow = false;
            waiting = true;
            return true;
        }
        acceptLandRoute(targetPos, started);
        waiting = false;
        return true;
    }

    /** A bot's own owner gets a loaded-chunk direct route; every other selected player stays inside the ordinary observation boundary. */
    private LandRouteAttempt startLandRoute(AIPlayerEntity bot, ServerPlayer target, BlockPos targetPos, boolean refresh) {
        ActionPack pack = bot.getActionPack();
        if (isVerifiedOwner(bot, target)) {
            ActionResult ownerRoute = pack.startOwnerFollowTo(target.getUUID(), targetPos, baritoneRadius, refresh);
            if (!ownerRoute.isFailed()) {
                BotLog.path(bot, "follow_owner_route", "target", io.github.zoyluo.minecraftai.log.LogFields.pos(targetPos),
                        "radius", baritoneRadius, "refresh", refresh, "policy", "walk_only_loaded_chunks");
            }
            return new LandRouteAttempt(ownerRoute, false, true);
        }
        ActionResult direct = pack.startApproachTo(targetPos, baritoneRadius, refresh, true);
        if (!direct.isFailed() || !isObservationAdmissionFailure(direct.reason())) {
            return new LandRouteAttempt(direct, false, false);
        }
        // The actual player position stays in baritoneTargetPos for re-goal comparison. This
        // derived coordinate merely tells the local hop where to stop, preserving STOP_DISTANCE
        // even when the player begins only slightly beyond the observation radius.
        BlockPos standoff = standOffsetFrom(targetPos, bot.blockPosition(), STOP_DISTANCE);
        ActionResult pursuit = pack.startDirectionalPursuitTo(standoff, DIRECTIONAL_PURSUIT_MAX_HOP, refresh, true);
        if (!pursuit.isFailed()) {
            BotLog.path(bot, "follow_directional_pursuit", "target",
                    io.github.zoyluo.minecraftai.log.LogFields.pos(targetPos), "standoff",
                    io.github.zoyluo.minecraftai.log.LogFields.pos(standoff), "hop", DIRECTIONAL_PURSUIT_MAX_HOP,
                    "refresh", refresh);
        }
        return new LandRouteAttempt(pursuit, true, false);
    }

    private static boolean isVerifiedOwner(AIPlayerEntity bot, ServerPlayer target) {
        return target != null && AIPlayerManager.INSTANCE.ownerOf(bot).filter(target.getUUID()::equals).isPresent();
    }

    private static boolean isObservationAdmissionFailure(String reason) {
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case "navigation_goal_unobserved", "navigation_observed_corridor_unavailable",
                    "navigation_goal_without_observed_stance", "navigation_observation_fence_insufficient" -> true;
            default -> false;
        };
    }

    private void acceptLandRoute(BlockPos targetPos, LandRouteAttempt attempt) {
        boolean directional = attempt.directional();
        repathBackoff = false;
        // Admission says only that this local directional cone is safe to try, not that the
        // bot made any progress through it. Keep a prior pursuit-failure episode alive until a
        // hop actually succeeds, so a wall/edge cannot suppress the eventual useful notice by
        // repeatedly accepting and immediately ending one-cell route attempts.
        if (!directional) {
            noRouteAnnounced = false;
            repeatedFailures.reset();
        }
        baritoneTargetPos = targetPos.immutable();
        baritoneDirectionalPursuit = directional;
        baritoneOwnerFollow = attempt.ownerFollow();
        baritoneProgress.clear();
    }

    private void reportLandRouteFailure(AIPlayerEntity bot, BlockPos targetPos, LandRouteAttempt attempt) {
        if (attempt.directional()) {
            // One small visible cone may simply end at a wall or chunk edge. Do not claim the
            // player is unreachable from this one hop; after a sustained series, explain the
            // wait while retaining the permanent follow order.
            noteDirectionalPursuitFailure(bot, targetPos, attempt.result().reason());
        } else {
            announceNoRoute(bot, targetPos, attempt.result().reason(), FollowNoRoute.messageFor(attempt.result().reason()));
        }
    }

    private void noteDirectionalPursuitFailure(AIPlayerEntity bot, BlockPos targetPos, String reason) {
        if (repeatedFailures.recordFailure(elapsed)) {
            announceNoRoute(bot, targetPos, reason, FollowNoRoute.GENERIC_MESSAGE);
        }
    }

    private static boolean isDirectionalPursuit(NavOutcome outcome) {
        return "directional_pursuit".equals(outcome.label());
    }

    private static boolean isOwnerFollow(NavOutcome outcome) {
        return "owner_follow".equals(outcome.label());
    }

    private record LandRouteAttempt(ActionResult result, boolean directional, boolean ownerFollow) {
    }

    /** What the bot says when a Baritone route ended without getting to the player: the dry-route line only for what it is. */
    private static String noRouteMessage(NavOutcome ended) {
        String reason = ended.reason();
        boolean water = NavRouteRules.ROUTE_ENTERED_WATER.equals(reason) || FollowNoRoute.isGenuine(reason);
        return water ? FollowNoRoute.messageFor(reason) : FollowNoRoute.GENERIC_MESSAGE;
    }

    private void followLand(AIPlayerEntity bot, ServerPlayer target) {
        // An arrived follower is intentionally still; do not mistake its personal-space hold
        // for a stalled route. Otherwise recovery owns only its individually observed local
        // step or forces this same Baritone follow loop to replan -- it never completes/cancels
        // the standing follow order just because the player or bot stopped moving.
        if (bot.distanceTo(target) <= STOP_DISTANCE + STOP_ARRIVAL_SLACK) {
            suspendLandRecovery(bot);
            followLandBaritone(bot, target);
            return;
        }
        if (stuckRecovery.tick(bot, target, elapsed, STOP_DISTANCE)) {
            waiting = true;
            return;
        }
        if (stuckRecovery.consumeForcedRepath()) {
            dropBaritoneRoute(bot);
            repathBackoff = false;
            nextRepathTick = elapsed;
        }
        // An unavailable/refused Baritone route is a visible follow hold, never a second
        // navigator. The swimming branch above retains its bounded physical safety moves.
        followLandBaritone(bot, target);
    }

    /**
     * No route to the player and no verified straight walk: the bot stays dry where it is (it swims only to follow
     * a player who is themselves in the water), keeps re-planning on the normal schedule, and says so once per
     * episode: a specific line at once for a genuine no-route result, or, after repeated failures, the specific
     * no-standing-place line for a persistently unstandable goal and a generic line for the rest
     * ({@link FollowNoRoute}). The flag is re-armed when a route is found again or the bot arrives.
     */
    private void announceNoRoute(AIPlayerEntity bot, BlockPos standNear, String reason, String message) {
        if (noRouteAnnounced) {
            return;
        }
        noRouteAnnounced = true;
        noRouteNotices++;
        BotLog.action(bot, "follow_no_dry_route",
                "pos", io.github.zoyluo.minecraftai.log.LogFields.pos(bot.blockPosition()),
                "stand_near", io.github.zoyluo.minecraftai.log.LogFields.pos(standNear),
                "reason", reason,
                "failures_in_a_row", repeatedFailures.failures());
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", message);
    }

    /**
     * A point {@code standoff} blocks from {@code playerPos}, along the horizontal direction from
     * the player toward {@code fromPos} (the bot's current position) -- so approaching the player
     * settles at the requested distance instead of walking onto the player's own block. Falls back
     * to an arbitrary horizontal direction on the rare exact-column coincidence (directly above or
     * below the player).
     */
    private static BlockPos standOffsetFrom(BlockPos playerPos, BlockPos fromPos, double standoff) {
        double dx = fromPos.getX() - playerPos.getX();
        double dz = fromPos.getZ() - playerPos.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 1.0e-6D) {
            dx = 1.0D;
            dz = 0.0D;
            horizontalDist = 1.0D;
        }
        double scale = standoff / horizontalDist;
        return BlockPos.containing(
                playerPos.getX() + dx * scale,
                playerPos.getY(),
                playerPos.getZ() + dz * scale);
    }

    /** @return true while the bot is still aboard and needs another boat tick before land follow. */
    private boolean leaveBoatForLand(AIPlayerEntity bot, ServerPlayer target) {
        return BoatSupport.leaveBoatForLand(bot, target.getX(), target.getZ(), BOAT_SHORE_STANDOFF, BOAT_TURN_ONLY_ANGLE);
    }

    private void abandonBoatChild(AIPlayerEntity bot) {
        boatAcquireFailures = 0;
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.cancel(bot, "follow_mode_changed");
        }
        boatFollow = null;
    }

    private static boolean isWaterborne(ServerPlayer target) {
        return target.isInWater() || target.isUnderWater();
    }

    private static void stopBoatAndActions(AIPlayerEntity bot) {
        BoatSupport.mountedBoat(bot).ifPresent(BoatAction::stopBoat);
        bot.getActionPack().stopAll();
    }

    private Optional<ServerPlayer> target(AIPlayerEntity bot) {
        return FollowTargetResolver.resolve(bot, targetName);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        escort.disengage();
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        suspendLandRecovery(bot);
        swimming.cancelStep(bot);
        swimming.reset(bot);
        shelterExitDebtRepayer.cancel(bot);
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.pause(bot);
        }
        super.onPause(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        suspendLandRecovery(bot);
        holdTargetPos = null;
        if (boatFollow != null && boatFollow.state() == TaskState.PAUSED) {
            boatFollow.resume(bot);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        escort.disengage();
        lastTarget = null;
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        suspendLandRecovery(bot);
        swimming.cancelStep(bot);
        swimming.reset(bot);
        shelterExitDebtRepayer.cancel(bot);
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.abort(bot);
        }
        super.onAbort(bot);
    }
}
