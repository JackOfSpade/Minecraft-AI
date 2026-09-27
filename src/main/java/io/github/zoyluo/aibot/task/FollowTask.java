package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BoatAction;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Mirrors the followed player's travel mode without treating water as ordinary land:
 * land follows pathfind, a boated player triggers boat acquisition/follow, and a swimming player
 * is followed through bounded verified swim steps without launching a boat.
 */
public final class FollowTask extends AbstractTask {
    private static final double STOP_DISTANCE = 3.0D;
    private static final double START_DISTANCE = 4.5D;
    private static final double MAX_DIRECT_FALLBACK_DISTANCE = 12.0D;
    private static final int REPATH_TICKS = 40;
    private static final double SWIM_STOP_DISTANCE = 3.5D;
    private static final int SWIM_REPATH_TICKS = 30;
    private static final double BOAT_TURN_ONLY_ANGLE = 82.0D;

    private final String targetName;
    private int nextRepathTick;
    private int nextSwimRepathTick;
    private boolean waiting;
    private BoatFollowTask boatFollow;
    private final BlockMiner shelterExitMiner = new BlockMiner();
    private final Set<BlockPos> rejectedShelterEgress = new HashSet<>();
    private EmergencyShelterTask.ExitDebt shelterExitDebt;
    private BlockPos activeShelterEgress;

    public FollowTask(String targetName) {
        this.targetName = targetName == null ? "" : targetName.trim();
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
        boatFollow = null;
        shelterExitMiner.cancel(bot);
        rejectedShelterEgress.clear();
        shelterExitDebt = EmergencyShelterTask.pendingExitDebt(bot).orElse(null);
        activeShelterEgress = null;
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
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "The player I am following is offline or in another dimension, so I will wait here.");
            }
            return;
        }

        // The owner/name lookup above is explicit player authority, not a radius- or
        // line-of-sight-gated entity scan.  If a new instruction cancelled a sealed shelter,
        // first reopen only the task-owned doorway, then continue following that known player.
        if (repayShelterExitDebt(bot, target)) {
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
            boatFollow = BoatFollowTask.automatic(targetName);
            boatFollow.start(bot);
        }
        boatFollow.tick(bot);
        waiting = boatFollow.isWaiting();
        if (boatFollow.state() == TaskState.FAILED) {
            // The automatic priority is encoded inside BoatFollowTask: board a nearby empty
            // boat first, then craft/launch one.  When both are unavailable, keep following by
            // swimming instead of standing still or attempting a second arbitrary boat route.
            boatFollow = null;
            followSwimming(bot, target);
        }
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
        if (distance <= STOP_DISTANCE) {
            bot.getActionPack().stopMovement();
            waiting = true;
            return;
        }
        boolean pathIdle = bot.getActionPack().isPathExecutorIdle();
        boolean walkIdle = bot.getActionPack().isWalkToIdle();

        // The hysteresis band still needs one bounded direct approach.  The previous code left
        // this 3.0-4.5 block range marked active with no controller, which the stuck watcher then
        // interpreted as a failed follow.
        if (distance < START_DISTANCE) {
            if (pathIdle && walkIdle && elapsed >= nextRepathTick) {
                bot.getActionPack().startWalkTo(target.getEntityPos());
                nextRepathTick = elapsed + REPATH_TICKS;
                waiting = false;
            } else {
                waiting = pathIdle && walkIdle;
            }
            return;
        }

        // ServerPlayerEntity is an explicitly authorized follow target even when it is outside
        // ordinary perception.  Snapshot that known position for deterministic route replacement;
        // do not substitute an observable-entity query here.
        BlockPos trackedTarget = target.getBlockPos().toImmutable();
        if (elapsed >= nextRepathTick) {
            ActionResult path = bot.getActionPack().startPathTo(trackedTarget);
            nextRepathTick = elapsed + REPATH_TICKS;
            if (!path.isFailed()) {
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
                bot.getActionPack().startWalkTo(target.getEntityPos());
                waiting = false;
                return;
            }
            bot.getActionPack().stopMovement();
            waiting = true;
            return;
        }

        // A completed/failed controller waits for the scheduled replan rather than looking active
        // while idle.  This is intentional reacquisition, so StuckWatcher must not abort it.
        waiting = pathIdle && walkIdle;
    }

    /**
     * Repays a sealed shelter's cancellation debt before pathing.  It can mine only a block whose
     * exact state was recorded as bot-owned by {@link EmergencyShelterTask}; arbitrary enclosure
     * blocks are rejected rather than punched through.
     */
    private boolean repayShelterExitDebt(AIPlayerEntity bot, ServerPlayerEntity target) {
        if (shelterExitDebt == null) {
            return false;
        }
        if (!shelterExitDebt.matchesDimension(bot)
                || !bot.getBlockPos().equals(shelterExitDebt.anchor())) {
            finishShelterExitDebt(bot);
            return false;
        }
        BlockPos egress = activeShelterEgress == null
                ? selectShelterEgress(bot, target)
                : activeShelterEgress;
        if (egress == null) {
            bot.getActionPack().stopMovement();
            waiting = true;
            return true;
        }
        activeShelterEgress = egress;
        BlockPos obstruction = firstShelterExitObstruction(bot, egress);
        if (obstruction != null) {
            if (!shelterExitDebt.ownsCurrentPlacement(bot, obstruction)) {
                rejectedShelterEgress.add(egress);
                activeShelterEgress = null;
                waiting = true;
                return true;
            }
            if (!obstruction.equals(shelterExitMiner.target())) {
                shelterExitMiner.begin(bot, obstruction);
            }
            BlockMiner.Status status = shelterExitMiner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                rejectedShelterEgress.add(egress);
                activeShelterEgress = null;
            }
            waiting = true;
            return true;
        }
        Standability.clearCache();
        if (!Standability.isStandable(bot.getEntityWorld(), egress)
                || !FakePlayerMotion.stepToStandable(bot, egress, "follow_shelter_exit")) {
            rejectedShelterEgress.add(egress);
            activeShelterEgress = null;
            waiting = true;
            return true;
        }
        // The body is now physically outside a cancelled shell.  Promote only this exact owned
        // state proof to low-priority cleanup before forgetting the doorway debt; no player-built
        // blocks can enter the registry.
        EmergencyShelterTask.promoteExitDebtForCleanup(bot, shelterExitDebt);
        finishShelterExitDebt(bot);
        waiting = false;
        return false;
    }

    private BlockPos selectShelterEgress(AIPlayerEntity bot, ServerPlayerEntity target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : shelterExitDebt.egressCandidates()) {
            if (rejectedShelterEgress.contains(candidate)
                    || !hasSafeShelterExitSupport(bot, candidate)) {
                continue;
            }
            BlockPos obstruction = firstShelterExitObstruction(bot, candidate);
            if (obstruction != null && !shelterExitDebt.ownsCurrentPlacement(bot, obstruction)) {
                rejectedShelterEgress.add(candidate);
                continue;
            }
            double targetDistance = candidate.getSquaredDistance(target.getBlockPos());
            if (best == null || targetDistance < bestDistance) {
                best = candidate;
                bestDistance = targetDistance;
            }
        }
        return best;
    }

    private static BlockPos firstShelterExitObstruction(AIPlayerEntity bot, BlockPos egress) {
        if (!isPassableShelterExitCell(bot, egress.up())) {
            return egress.up().toImmutable();
        }
        if (!isPassableShelterExitCell(bot, egress)) {
            return egress.toImmutable();
        }
        return null;
    }

    private static boolean hasSafeShelterExitSupport(AIPlayerEntity bot, BlockPos egress) {
        var world = bot.getEntityWorld();
        var support = world.getBlockState(egress.down());
        return support.getFluidState().isEmpty()
                && !support.getCollisionShape(world, egress.down()).isEmpty()
                && !Standability.isDangerous(support);
    }

    private static boolean isPassableShelterExitCell(AIPlayerEntity bot, BlockPos position) {
        var world = bot.getEntityWorld();
        var state = world.getBlockState(position);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(world, position).isEmpty()
                && !Standability.isDangerous(state);
    }

    private void finishShelterExitDebt(AIPlayerEntity bot) {
        shelterExitMiner.cancel(bot);
        EmergencyShelterTask.clearExitDebt(bot, shelterExitDebt);
        shelterExitDebt = null;
        rejectedShelterEgress.clear();
        activeShelterEgress = null;
    }

    /** @return true while the bot is still aboard and needs another boat tick before land follow. */
    private boolean leaveBoatForLand(AIPlayerEntity bot, ServerPlayerEntity target) {
        AbstractBoatEntity boat = BoatSupport.mountedBoat(bot).orElse(null);
        if (boat == null) {
            return false;
        }
        if (BoatSupport.nearbySafeDismountShore(bot, boat).isPresent()) {
            BoatAction.stopBoat(boat);
            bot.dismountVehicle();
            return bot.getVehicle() instanceof AbstractBoatEntity;
        }
        driveBoatToward(boat, target.getX(), target.getZ());
        return true;
    }

    private static void driveBoatToward(AbstractBoatEntity boat, double targetX, double targetZ) {
        double dx = targetX - boat.getX();
        double dz = targetZ - boat.getZ();
        if (Math.hypot(dx, dz) <= STOP_DISTANCE) {
            BoatAction.stopBoat(boat);
            return;
        }
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float turn = MathHelper.wrapDegrees(desiredYaw - boat.getYaw());
        boat.setInputs(turn < -4.0F, turn > 4.0F,
                Math.abs(turn) < BOAT_TURN_ONLY_ANGLE, false);
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
        if (!targetName.isBlank()) {
            return Optional.ofNullable(bot.getEntityWorld().getServer().getPlayerManager().getPlayer(targetName));
        }
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.getEntityWorld().getServer().getPlayerManager().getPlayer(uuid));
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        shelterExitMiner.cancel(bot);
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
        shelterExitMiner.cancel(bot);
        if (boatFollow != null && boatFollow.state() == TaskState.RUNNING) {
            boatFollow.abort(bot);
        }
        super.onAbort(bot);
    }
}
