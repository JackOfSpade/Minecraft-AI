package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private static final double SWIM_STOP_DISTANCE = 3.5D;
    private static final int SWIM_REPATH_TICKS = 30;
    private static final double BOAT_TURN_ONLY_ANGLE = 82.0D;
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
    // Throttles the walk-toward-water helper's own findLaunchSite scan the same way followLand
    // throttles its repathing, so backing off from a failed acquisition does not just trade one
    // every-tick scan for another.
    private static final int BOAT_WATER_SCAN_COOLDOWN_TICKS = 30;
    private static final int BOAT_TARGET_SHORE_RADIUS = 24;

    private final String targetName;
    private int nextRepathTick;
    private int nextSwimRepathTick;
    private boolean waiting;
    // Set only when a full pathfind failed with the target still farther than the direct-walk
    // fallback range, so we deliberately wait out nextRepathTick instead of retrying an expensive
    // search every tick. Left false whenever a path/walk is actually issued, so an ordinary short
    // leg that simply finishes early is never mistaken for this backoff and can re-path immediately.
    private boolean repathBackoff;
    private BoatFollowTask boatFollow;
    private int nextBoatAttemptTick;
    private int nextBoatWaterScanTick;
    // Reason the last acquisition failed, and boats already given up on (beached/wedged): both
    // outlive the BoatFollowTask instance that produced them.
    private String lastBoatFailure = "";
    private final Set<UUID> abandonedBoats = new HashSet<>();
    private final ShelterExitDebtRepayer shelterExitDebtRepayer = new ShelterExitDebtRepayer();
    private final FollowStuckRecovery stuckRecovery = new FollowStuckRecovery();

    public FollowTask(String targetName) {
        this.targetName = FollowTargetResolver.normalize(targetName);
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
        nextSwimRepathTick = 0;
        waiting = false;
        repathBackoff = false;
        boatFollow = null;
        nextBoatAttemptTick = 0;
        nextBoatWaterScanTick = 0;
        lastBoatFailure = "";
        abandonedBoats.clear();
        shelterExitDebtRepayer.reset(bot);
        stuckRecovery.reset(bot, elapsed);
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayerEntity target = target(bot).orElse(null);
        if (target == null || target.getEntityWorld() != bot.getEntityWorld()) {
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            stopBoatAndActions(bot);
            waiting = true;
            if (elapsed % 200 == 1) {
                BotLog.action(bot, "follow_target_offline", "target", targetName.isBlank() ? "owner" : targetName);
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "The player I am following is offline or in another dimension, so I will wait here.");
            }
            return;
        }

        // WalkToController only ever touches yaw (it preserves whatever pitch the bot already had),
        // so without this the bot's head stays frozen at its pre-follow pitch for the whole task --
        // this is what "looking at the sky" was: nothing here was ever setting a sane pitch.
        CombatCore.lookAt(bot, target);

        // The owner/name lookup above is explicit player authority, not a radius- or
        // line-of-sight-gated entity scan.  If a new instruction cancelled a sealed shelter,
        // first reopen only the task-owned doorway, then continue following that known player.
        if (shelterExitDebtRepayer.repay(bot, target, elapsed)) {
            waiting = shelterExitDebtRepayer.isWaiting();
            return;
        }

        boolean targetInBoat = target.getVehicle() instanceof AbstractBoatEntity;
        boolean targetSwimming = !targetInBoat && isWaterborne(target);
        if (targetInBoat) {
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            followBoat(bot, target);
            return;
        }
        if (targetSwimming) {
            abandonBoatChild(bot);
            followSwimming(bot, target);
            return;
        }

        // The player is on land.  A follower already in a boat first paddles to a genuine dry
        // shore and asks vanilla to dismount, then immediately returns to ordinary land follow.
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        abandonBoatChild(bot);
        if (leaveBoatForLand(bot, target)) {
            waiting = true;
            return;
        }
        followLand(bot, target);
    }

    private void followBoat(AIPlayerEntity bot, ServerPlayerEntity target) {
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
            BotLog.action(bot, "follow_boat_acquire_failed_backoff", "reason", lastBoatFailure);
            boatFollow = null;
            nextBoatAttemptTick = elapsed + BOAT_ACQUIRE_COOLDOWN_TICKS;
            boatAcquireBackoffStep(bot, target);
        }
    }

    /**
     * One tick of following while the next boat acquisition attempt is backed off.  A bot that
     * cannot get any boat at all (no boat item and no planks for one) or is already in the water
     * keeps following the boating player by swimming, exactly as before; otherwise it walks toward
     * the water so that the next attempt starts from a shore.
     */
    private void boatAcquireBackoffStep(AIPlayerEntity bot, ServerPlayerEntity target) {
        boolean noBoatMaterial = lastBoatFailure.contains("need_boat_or_five_matching_planks");
        if (noBoatMaterial || bot.isTouchingWater() || bot.isSubmergedInWater()) {
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
    private void walkTowardBoatWater(AIPlayerEntity bot, ServerPlayerEntity target) {
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
                        bot.getEntityWorld(), target.getBlockPos(), BOAT_TARGET_SHORE_RADIUS, 8, 4)
                        .orElse(null));
        if (destination == null) {
            waiting = true;
            return;
        }
        ActionResult path = bot.getActionPack().startPathTo(destination);
        if (path.isFailed() && site != null) {
            bot.getActionPack().startWalkTo(destination.toCenterPos(), 1.0D);
        }
        waiting = false;
    }

    private void followSwimming(AIPlayerEntity bot, ServerPlayerEntity target) {
        if (BoatSupport.mountedBoat(bot).isPresent()) {
            // The player is swimming, not boating.  Never keep driving or acquire a new boat in
            // this branch; vanilla places the passenger in the adjacent water on dismount.
            BoatSupport.mountedBoat(bot).ifPresent(BoatAction::stopBoat);
            bot.dismountVehicle();
            waiting = true;
            return;
        }
        ServerWorld world = bot.getEntityWorld();
        BlockPos feet = bot.getBlockPos();
        if (isSwimCell(world, feet)) {
            NavSafetyNet.INSTANCE.renewFollowSwim(bot);
            double distanceSquared = bot.squaredDistanceTo(target);
            if (distanceSquared <= SWIM_STOP_DISTANCE * SWIM_STOP_DISTANCE) {
                bot.getActionPack().stopMovement();
                waiting = true;
                return;
            }
            waiting = !swimStepToward(bot, target);
            return;
        }

        // Enter water only at a local visible shore.  The one-cell FakePlayerMotion move is the
        // same collision-validated primitive used by the safety net, not an invented teleport.
        BoatSupport.LaunchSite site = BoatSupport.findLaunchSite(bot).orElse(null);
        if (site == null) {
            if (elapsed % 200 == 1) {
                BotLog.action(bot, "follow_swim_no_launch_site");
            }
            waiting = true;
            return;
        }
        if (feet.equals(site.shore())) {
            NavSafetyNet.INSTANCE.renewFollowSwim(bot);
            waiting = !FakePlayerMotion.swimStepTo(bot, site.water(), "follow_swim_enter");
            return;
        }
        if (elapsed >= nextSwimRepathTick) {
            ActionResult path = bot.getActionPack().startPathTo(site.shore());
            if (path.isFailed()) {
                bot.getActionPack().startWalkTo(site.shore().toCenterPos(), 1.0D);
            }
            nextSwimRepathTick = elapsed + SWIM_REPATH_TICKS;
        }
        waiting = false;
    }

    private boolean swimStepToward(AIPlayerEntity bot, ServerPlayerEntity target) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos current = bot.getBlockPos();
        double before = bot.squaredDistanceTo(target);
        List<BlockPos> choices = new ArrayList<>(6);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            choices.add(current.offset(direction));
        }
        choices.add(current.up());
        choices.add(current.down());
        return choices.stream()
                .filter(candidate -> isSafeSwimCell(world, candidate))
                .filter(candidate -> candidate.getSquaredDistance(target.getBlockPos()) + 0.01D < before)
                .sorted(Comparator.comparingDouble(candidate -> candidate.getSquaredDistance(target.getBlockPos())))
                .anyMatch(candidate -> FakePlayerMotion.swimStepTo(bot, candidate, "follow_swim"));
    }

    private void followLand(AIPlayerEntity bot, ServerPlayerEntity target) {
        double distance = bot.distanceTo(target);
        if (distance <= STOP_DISTANCE + STOP_ARRIVAL_SLACK) {
            bot.getActionPack().stopMovement();
            waiting = true;
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
        boolean pathIdle = bot.getActionPack().isPathExecutorIdle();
        boolean walkIdle = bot.getActionPack().isWalkToIdle();

        // The actual walk/path destination is offset STOP_DISTANCE from the player -- never the
        // player's own block -- so the bot's own arrival condition stops it at the requested
        // distance instead of relying solely on the check above to interrupt an in-flight
        // walk/path at exactly the right instant. Real pathfinding (not a direct walk) is used at
        // every distance now, close range included: a direct walk has no obstacle-planning of its
        // own, so a single step-up block right in the way was only ever discovered reactively,
        // after WalkToController's own multi-second stuck/sidle ladder gave up on walking through
        // it -- by then the player had already pulled well ahead. A* plans the jump immediately.
        BlockPos standNear = standOffsetFrom(target.getBlockPos(), bot.getBlockPos(), STOP_DISTANCE);
        // Besides the periodic retarget schedule, also re-path the instant the controller goes
        // idle on its own (a short leg toward a close, moving target often finishes well before
        // nextRepathTick) -- unless we're deliberately backing off a just-failed distant search.
        if (elapsed >= nextRepathTick || (pathIdle && walkIdle && !repathBackoff)) {
            // One ordinary search per repath. A goal that used to resolve down into the bot's own
            // old mining staircase (2026-09-28 session log) is fixed at the source, in goal
            // resolution (AStarPathfinder.resolveEndpoint / Standability.findNearestStandableForGoal:
            // nearby cell first, then a bounded fluid-refusing deep fallback), so no second
            // "surface-first" search is layered on top here.
            ActionResult path = bot.getActionPack().startPathTo(standNear);
            nextRepathTick = elapsed + REPATH_TICKS;
            if (!path.isFailed()) {
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
                bot.getActionPack().startWalkTo(standNear.toCenterPos());
                repathBackoff = false;
                waiting = false;
                return;
            }
            bot.getActionPack().stopMovement();
            repathBackoff = true;
            waiting = true;
            return;
        }

        // A completed/failed controller waits for the scheduled replan rather than looking active
        // while idle.  This is intentional reacquisition, so StuckWatcher must not abort it.
        waiting = pathIdle && walkIdle;
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
        return BlockPos.ofFloored(
                playerPos.getX() + dx * scale,
                playerPos.getY(),
                playerPos.getZ() + dz * scale);
    }

    /** @return true while the bot is still aboard and needs another boat tick before land follow. */
    private boolean leaveBoatForLand(AIPlayerEntity bot, ServerPlayerEntity target) {
        return BoatSupport.leaveBoatForLand(bot, target.getX(), target.getZ(), STOP_DISTANCE, BOAT_TURN_ONLY_ANGLE);
    }

    private void abandonBoatChild(AIPlayerEntity bot) {
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.cancel(bot, "follow_mode_changed");
        }
        boatFollow = null;
    }

    private static boolean isWaterborne(ServerPlayerEntity target) {
        return target.isTouchingWater() || target.isSubmergedInWater();
    }

    private static boolean isSwimCell(ServerWorld world, BlockPos pos) {
        return BoatSupport.isWater(world, pos) || BoatSupport.isWater(world, pos.up());
    }

    private static boolean isSafeSwimCell(ServerWorld world, BlockPos pos) {
        if (!isSwimCell(world, pos)
                || !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                || !world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        return !world.getFluidState(pos).isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                && !world.getFluidState(pos.up()).isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    private static void stopBoatAndActions(AIPlayerEntity bot) {
        BoatSupport.mountedBoat(bot).ifPresent(BoatAction::stopBoat);
        bot.getActionPack().stopAll();
    }

    private Optional<ServerPlayerEntity> target(AIPlayerEntity bot) {
        return FollowTargetResolver.resolve(bot, targetName);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        shelterExitDebtRepayer.cancel(bot);
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.pause(bot);
        }
        super.onPause(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (boatFollow != null && boatFollow.state() == TaskState.PAUSED) {
            boatFollow.resume(bot);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        shelterExitDebtRepayer.cancel(bot);
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.abort(bot);
        }
        super.onAbort(bot);
    }
}
