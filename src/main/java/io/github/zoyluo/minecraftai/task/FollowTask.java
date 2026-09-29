package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
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
        swimming.reset();
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
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayer target = target(bot).orElse(null);
        if (target == null || target.level() != bot.level()) {
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            suspendLandRecovery(bot);
            stopBoatAndActions(bot);
            waiting = true;
            if (elapsed % 200 == 1) {
                BotLog.action(bot, "follow_target_offline", "target", targetName.isBlank() ? "owner" : targetName);
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "The player I am following is offline or in another dimension, so I will wait here.");
            }
            return;
        }

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
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            suspendLandRecovery(bot);
            followBoat(bot, target);
            return;
        }
        if (targetSwimming) {
            suspendLandRecovery(bot);
            abandonBoatChild(bot);
            followSwimming(bot, target);
            return;
        }

        // The player is on land.  A follower already in a boat first paddles to a genuine dry
        // shore and asks vanilla to dismount, then immediately returns to ordinary land follow.
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        abandonBoatChild(bot);
        if (leaveBoatForLand(bot, target)) {
            suspendLandRecovery(bot);
            waiting = true;
            return;
        }
        // The player has left the water but the bot is still in it: swim to a dry landing near
        // them first (this renews the narrow swim lease itself), then ordinary land follow runs.
        if (swimming.exitWaterForLand(bot, target, elapsed, STOP_DISTANCE)) {
            suspendLandRecovery(bot);
            waiting = swimming.isWaiting();
            return;
        }
        followLand(bot, target);
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
    private static void faceTarget(AIPlayerEntity bot, ServerPlayer target) {
        boolean steering = !bot.getActionPack().isPathExecutorIdle() || !bot.getActionPack().isWalkToIdle();
        if (steering) {
            LookAction.lookPitchAt(bot, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        } else {
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

    private void followLand(AIPlayerEntity bot, ServerPlayer target) {
        ActionPack pack = bot.getActionPack();
        double distance = bot.distanceTo(target);
        if (distance <= STOP_DISTANCE + STOP_ARRIVAL_SLACK) {
            // stopMovement() alone only releases the keys: a live PathExecutor re-presses forward
            // on its next tick and keeps walking its stale route for up to WalkToController's
            // MAX_TICKS with no replan.  Arriving must cancel the navigation itself.
            pack.stopNavigation();
            waiting = true;
            noRouteAnnounced = false;
            repeatedFailures.reset();
            stuckRecovery.reset(bot, elapsed);
            return;
        }
        // A standing follow order must survive path/goal-resolution quirks that report bogus
        // "success" without the bot's real position ever changing (see FollowStuckRecovery's
        // header). This runs before -- and, while active, instead of -- the ordinary repath
        // logic below; StuckWatcher never sees a frozen sample because isWaiting() stays true for
        // every tick this owns.
        if (stuckRecovery.tick(bot, target, elapsed, STOP_DISTANCE)) {
            waiting = true;
            return;
        }
        // Recovery may have just decided this specific tick needs an immediate fresh repath
        // (see FollowStuckRecovery's forced-replan window) rather than either handling the tick
        // itself or waiting for the ordinary schedule below -- honour that by pulling the
        // schedule forward instead of adding a second, parallel path-triggering codepath.
        if (stuckRecovery.consumeForcedRepath()) {
            nextRepathTick = elapsed;
        }
        boolean pathIdle = pack.isPathExecutorIdle();
        boolean walkIdle = pack.isWalkToIdle();

        // The actual walk/path destination is offset STOP_DISTANCE from the player -- never the
        // player's own block -- so the bot's own arrival condition stops it at the requested
        // distance instead of relying solely on the check above to interrupt an in-flight
        // walk/path at exactly the right instant. Real pathfinding (not a direct walk) is used at
        // every distance now, close range included: a direct walk has no obstacle-planning of its
        // own, so a single step-up block right in the way was only ever discovered reactively,
        // after WalkToController's own multi-second stuck/sidle ladder gave up on walking through
        // it -- by then the player had already pulled well ahead. A* plans the jump immediately.
        BlockPos standNear = standOffsetFrom(target.blockPosition(), bot.blockPosition(), STOP_DISTANCE);
        // The goal collapsed onto the bot's own cell earlier and the bot is holding: as soon as the player
        // has moved (rate-limited), that answer is stale -- re-evaluate now instead of idling the full
        // REPATH_TICKS while they walk away.
        if (holdTargetPos != null && elapsed >= nextHoldReevalTick
                && !holdTargetPos.equals(target.blockPosition())) {
            holdTargetPos = null;
            nextRepathTick = elapsed;
            repathBackoff = false;
        }
        // Besides the periodic retarget schedule, also re-path the instant the controller goes
        // idle on its own (a short leg toward a close, moving target often finishes well before
        // nextRepathTick) -- unless we're deliberately backing off a just-failed distant search.
        if (elapsed >= nextRepathTick || (pathIdle && walkIdle && !repathBackoff)) {
            // One ordinary search per repath. A goal that used to resolve down into the bot's own
            // old mining staircase (2026-09-28 session log) is fixed at the source, in goal
            // resolution (AStarPathfinder.resolveEndpoint / Standability.findNearestStandableForGoal:
            // nearby cell first, then a bounded fluid-refusing deep fallback), so no second
            // "surface-first" search is layered on top here.
            holdTargetPos = null;
            ActionResult path = pack.startPathTo(standNear);
            nextRepathTick = elapsed + REPATH_TICKS;
            if (ActionPack.PATHFINDING_THROTTLED.equals(path.reason()) && path.isFailed()) {
                // An identical request inside ActionPack's cooldown is not a failed route: it means
                // "the plan you already have stands".  Treating it as a failure drove the
                // straight-line fallback and ~1,090 one-node walk_complete spins in the session
                // log.  Keep whatever is running; when nothing is, just retry once the cooldown ends.
                nextRepathTick = elapsed + THROTTLED_RETRY_TICKS;
                if (pathIdle && walkIdle) {
                    repathBackoff = true;
                    waiting = true;
                } else {
                    waiting = false;
                }
                return;
            }
            if (!path.isFailed()) {
                // A route exists again: a later loss of it is a new no-route episode worth announcing.
                noRouteAnnounced = false;
                repeatedFailures.reset();
                if (pack.activePathGoal() != null && pack.activePathGoal().equals(bot.blockPosition())) {
                    // The goal resolved onto the very cell the bot stands in (the nearest standable
                    // cell to the stand-off point IS this one): a zero-length route that "completes"
                    // instantly and would be re-requested every tick.  This is as close as ordinary
                    // walking gets; hold here and re-evaluate on the normal schedule.
                    pack.stopNavigation();
                    BotLog.action(bot, "follow_at_nearest_standable",
                            "pos", io.github.zoyluo.minecraftai.log.LogFields.pos(bot.blockPosition()),
                            "stand_near", io.github.zoyluo.minecraftai.log.LogFields.pos(standNear));
                    repathBackoff = true;
                    holdTargetPos = target.blockPosition().immutable();
                    nextHoldReevalTick = elapsed + THROTTLED_RETRY_TICKS;
                    waiting = true;
                    return;
                }
                repathBackoff = false;
                waiting = false;
                return;
            }

            // A failed route must not recreate WalkToController every tick: doing so resets its
            // progress/stuck accounting forever.  Keep an existing walker alive, otherwise use a
            // short, bounded fallback and wait to replan when the target is too far away.
            if (!walkIdle) {
                waiting = false;
                return;
            }
            if (distance <= MAX_DIRECT_FALLBACK_DISTANCE) {
                FollowDirectWalk.Verdict verdict = FollowDirectWalk.verify(
                        bot.level(), bot.blockPosition(), standNear);
                if (verdict.safe()) {
                    directWalkCount++;
                    BotLog.action(bot, "follow_direct_walk",
                            "reason", "path_failed:" + path.reason(),
                            "verified", verdict.reason(),
                            "to", io.github.zoyluo.minecraftai.log.LogFields.pos(standNear));
                    pack.startWalkTo(standNear.getCenter());
                    repeatedFailures.reset();
                    repathBackoff = false;
                    waiting = false;
                    return;
                }
                BotLog.action(bot, "follow_direct_walk_refused",
                        "reason", verdict.reason(),
                        "path_reason", path.reason(),
                        "to", io.github.zoyluo.minecraftai.log.LogFields.pos(standNear));
            }
            pack.stopNavigation();
            repathBackoff = true;
            waiting = true;
            if (FollowNoRoute.isGenuine(path.reason())) {
                repeatedFailures.reset();
                announceNoRoute(bot, standNear, path.reason(), FollowNoRoute.messageFor(path.reason()));
            } else if (repeatedFailures.recordFailure(elapsed)) {
                // Budget/transient/unstandable-goal failures alone say nothing definite, but a follower that keeps failing
                // to plan for 10+ seconds owes the player one honest line (specific for an unstandable goal, else generic).
                announceNoRoute(bot, standNear, path.reason(), FollowNoRoute.messageFor(path.reason()));
            }
            return;
        }

        // A completed/failed controller waits for the scheduled replan rather than looking active
        // while idle.  This is intentional reacquisition, so StuckWatcher must not abort it.
        waiting = pathIdle && walkIdle;
    }

    /**
     * No route to the player and no verified straight walk: the bot stays dry where it is (it swims only to follow
     * a player who is themselves in the water), keeps re-planning on the normal schedule, and says so once per
     * episode, either with a specific line for a genuine no-route / no-standing-place result or, after repeated
     * transient failures, with a generic one ({@link FollowNoRoute}). The flag is re-armed when a route is found
     * again or the bot arrives.
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
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        suspendLandRecovery(bot);
        swimming.reset();
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
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        suspendLandRecovery(bot);
        swimming.reset();
        shelterExitDebtRepayer.cancel(bot);
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.abort(bot);
        }
        super.onAbort(bot);
    }
}
