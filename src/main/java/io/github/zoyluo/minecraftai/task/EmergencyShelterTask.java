package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EmergencyShelterTask extends AbstractTask {
    /** Four optional foundations, eight side cells, one roof support and one center roof. */
    public static final int MAX_PLACEMENT_BLOCKS = 14;
    private static final int NO_PROGRESS_LIMIT = 20;
    private static final int ANCHOR_RECOVERY_LIMIT = 20;
    private static final int BUILD_LIMIT = 120;
    /** A shelter starts only after an actual escape leg has opened this much room. */
    private static final int PREBUILD_RETREAT_DISTANCE = 10;
    private static final int PREBUILD_RETREAT_LIMIT = 260;
    private static final int EXIT_LIMIT = 500;
    private static final int DAYLIGHT_GRACE_TICKS = 100;
    /**
     * A non-surface shelter's HOLD phase must wait out a real minimum window before treating the
     * bot as recovered enough to leave -- otherwise a bot that spawns already at full health/food
     * (the common case) exits the instant the envelope seals, defeating the point of a recovery
     * shelter. Surface shelters use the pre-existing daylight counter for the same purpose.
     */
    private static final int MIN_HOLD_TICKS = 100;
    private static final String ENVIRONMENTAL_ESCAPE_REQUIRED =
            "shelter_environmental_escape_required";
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    // The world-global exit-debt/cleanup-claim registry (maps, related constants, and the pure
    // claim/registry static methods) lives in ShelterCleanupRegistry (shelterdig-refactor-1); this
    // class keeps only ExitDebt/ShelterCleanupDebt themselves (their construction needs private
    // access to this class's own state below) plus thin delegator methods for outside callers.

    private enum Phase {
        RETREAT_TO_SAFE_ANCHOR,
        BUILD,
        HOLD,
        OPEN_EXIT,
        STEP_OUT
    }

    private record ShelterPlan(BlockPos egressFeet,
                               BlockPos roofSupport,
                               BlockPos roofSupportBase,
                               List<BlockPos> targets) {
    }

    private final Queue<BlockPos> targets = new LinkedList<>();
    private final Map<BlockPos, BlockState> ownedPlacements = new LinkedHashMap<>();
    private final Set<Direction> rejectedEgress = EnumSet.noneOf(Direction.class);
    private final Set<Direction> pressuredEgress = EnumSet.noneOf(Direction.class);
    private final BlockMiner exitMiner = new BlockMiner();
    private int placed;
    private int lastProgressTick;
    private int phaseStartedElapsed;
    private int exitStartedElapsed;
    private int anchorRecoveryStartedElapsed;
    private BlockPos shelterFeet;
    private BlockPos roofSupport;
    private BlockPos roofSupportBase;
    private BlockPos egressFeet;
    private BlockPos exitMiningTarget;
    private Direction lastDeferredForcedDirection;
    private boolean elevatedForRoofSupport;
    private boolean surfaceShelter;
    private boolean forcePressureExit;
    private int consecutiveDaylightTicks;
    private int observationReseals;
    private EatTask holdEatTask;
    /**
     * Rescue lifecycle (spec: no-food/cry-for-help/rescue), all reset in {@link #onStart}, so a
     * later, separate emergency-shelter episode always starts this bookkeeping over from scratch.
     */
    private boolean waitingForRescue;
    private boolean criedForHelp;
    private boolean rescueResolvedAnnounced;
    private final LivingEntity initiatingThreat;
    private final BlockPos rememberedThreatPos;
    private BlockPos retreatGoal;
    private boolean cleanupDebtRegistered;
    private Phase phase = Phase.BUILD;
    private String pendingFailure;
    private boolean terminalRecorded;

    /** Manual/legacy shelter construction keeps its existing immediate-anchor behaviour. */
    public EmergencyShelterTask() {
        this(null);
    }

    /**
     * Automatic lethal-pressure shelter: retain only the observed threat direction so the task
     * can open real distance before placing its first wall.  It never discovers a new hidden
     * entity on its own.
     */
    EmergencyShelterTask(Threat initiatingThreat) {
        this.initiatingThreat = initiatingThreat == null ? null : initiatingThreat.entity();
        this.rememberedThreatPos = initiatingThreat == null || initiatingThreat.pos() == null
                ? null : initiatingThreat.pos().immutable();
    }

    @Override
    public String name() {
        return "shelter";
    }

    @Override
    public String describe() {
        return "Emergency shelter phase=" + phase + " placed=" + placed
                + " remaining=" + targets.size()
                + " observation_reseals=" + observationReseals
                + " pressured_egress=" + pressuredEgress.size()
                + " daylight_ticks=" + consecutiveDaylightTicks
                + " exit_age=" + exitAge()
                + " force_pressure_exit=" + forcePressureExit
                + " waiting_for_rescue=" + waitingForRescue
                + " cried_for_help=" + criedForHelp
                + " rescue_resolved=" + rescueResolvedAnnounced;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case RETREAT_TO_SAFE_ANCHOR -> Math.min(0.25D, elapsed / (double) PREBUILD_RETREAT_LIMIT * 0.25D);
            case BUILD -> {
                int total = placed + targets.size();
                yield total == 0 ? 0.0D : Math.min(0.65D, (double) placed / total * 0.65D);
            }
            case HOLD -> 0.75D;
            case OPEN_EXIT -> 0.9D;
            case STEP_OUT -> 0.95D;
        };
    }

    @Override
    public boolean isWaiting() {
        // Every phase owns a tighter domain watchdog. A generic stuck abort cannot repay the
        // asynchronous owned-block exit debt and would recreate the sealed terminal state.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        targets.clear();
        ownedPlacements.clear();
        rejectedEgress.clear();
        pressuredEgress.clear();
        placed = 0;
        lastProgressTick = 0;
        phaseStartedElapsed = 0;
        exitStartedElapsed = -1;
        anchorRecoveryStartedElapsed = -1;
        phase = Phase.BUILD;
        pendingFailure = null;
        terminalRecorded = false;
        exitMiningTarget = null;
        lastDeferredForcedDirection = null;
        surfaceShelter = false;
        forcePressureExit = false;
        consecutiveDaylightTicks = 0;
        observationReseals = 0;
        holdEatTask = null;
        waitingForRescue = false;
        criedForHelp = false;
        rescueResolvedAnnounced = false;
        retreatGoal = null;
        cleanupDebtRegistered = false;
        if (initiatingThreat != null || rememberedThreatPos != null) {
            phase = Phase.RETREAT_TO_SAFE_ANCHOR;
            phaseStartedElapsed = 0;
            beginRetreatToSafeAnchor(bot);
            return;
        }
        beginBuildAtCurrentPose(bot);
    }

    /** Starts the atomic enclosure only after the retreat/water handoff has proved a dry pose. */
    private void beginBuildAtCurrentPose(AIPlayerEntity bot) {
        if (!canStartAtCurrentPose(bot)) {
            failShelter(bot, "shelter_origin_not_stable");
            return;
        }
        BlockPos feet = bot.blockPosition();
        // A path/evade owner can stop with its BlockPos still equal to this cell while the body
        // box straddles an edge and retains horizontal velocity. Vanilla placement then rejects
        // the adjacent wall because it would collide with the player itself. Establish the same
        // stationary, centered pose a real client reports before beginning the fixed enclosure.
        if (!isCenteredAnchorEntitySpaceClear(bot, feet)
                || !FakePlayerMotion.returnToBlockCenter(bot, feet, "shelter_anchor_settle")) {
            failShelter(bot, "shelter_origin_not_centerable");
            return;
        }
        // Capture this before the shelter itself adds a roof. Nearby no-leaves terrain height
        // keeps a shallow canopy or overhang in the surface-night transaction while the Y floor
        // excludes an open deep mine that happens to have a vertical view of the sky.
        surfaceShelter = isSurfaceShelterAnchor(bot, feet);
        shelterFeet = feet.immutable();
        Optional<ShelterPlan> planned = planShelter(bot, shelterFeet);
        if (planned.isEmpty()) {
            failShelter(bot, "shelter_no_owned_egress");
            return;
        }
        ShelterPlan plan = planned.orElseThrow();
        egressFeet = plan.egressFeet();
        roofSupport = plan.roofSupport();
        roofSupportBase = plan.roofSupportBase();
        elevatedForRoofSupport = false;
        targets.addAll(plan.targets());
        int required = requiredShelterBlocks(bot, plan);
        int available = countShelterBlocks(bot);
        if (available < required) {
            failShelter(bot,
                    "missing shelter_blocks required=" + required + " available=" + available);
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (handleEnvironmentalOwnershipConflict(bot)) {
            return;
        }
        if (surfaceShelter && !bot.level().isBrightOutside()) {
            consecutiveDaylightTicks = 0;
        }
        switch (phase) {
            case RETREAT_TO_SAFE_ANCHOR -> tickRetreatToSafeAnchor(bot);
            case BUILD -> tickBuild(bot);
            case HOLD -> tickHold(bot);
            case OPEN_EXIT -> tickOpenExit(bot);
            case STEP_OUT -> tickStepOut(bot);
        }
    }

    /**
     * A wall constructed while a hostile occupies the adjacent cell is both likely to fail and
     * likely to trap the bot.  Move through a normal, bounded path first; in water, defer the
     * physical movement to NavSafetyNet's connected nearest-dry-shore controller.
     */
    private void tickRetreatToSafeAnchor(AIPlayerEntity bot) {
        if (hasBodyFluid(bot) || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            return;
        }
        LivingEntity pressure = observableInitiatingThreat(bot);
        if (isSafeRetreatAnchor(bot, pressure)) {
            bot.getActionPack().stopAll();
            phase = Phase.BUILD;
            phaseStartedElapsed = elapsed;
            beginBuildAtCurrentPose(bot);
            return;
        }
        if (phaseAge() > PREBUILD_RETREAT_LIMIT) {
            bot.getActionPack().stopAll();
            failShelter(bot, "shelter_prebuild_retreat_timeout");
            return;
        }
        boolean reachedGoal = retreatGoal != null
                && bot.blockPosition().distSqr(retreatGoal) <= 6.25D;
        if (retreatGoal == null || reachedGoal || bot.getActionPack().isPathExecutorIdle()) {
            if (!startRetreatPath(bot, pressure)) {
                // A pressure source can disappear behind a factual wall while the old path is
                // still unresolved.  In that case the current dry, stable cell is a safer build
                // anchor than inventing a blind movement direction.
                if (pressure == null && canStartAtCurrentPose(bot)) {
                    phase = Phase.BUILD;
                    phaseStartedElapsed = elapsed;
                    beginBuildAtCurrentPose(bot);
                }
            }
        }
    }

    private void beginRetreatToSafeAnchor(AIPlayerEntity bot) {
        if (hasBodyFluid(bot)) {
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            return;
        }
        startRetreatPath(bot, observableInitiatingThreat(bot));
    }

    private LivingEntity observableInitiatingThreat(AIPlayerEntity bot) {
        if (initiatingThreat == null
                || !DangerWatcher.isActiveHostileThreat(bot, initiatingThreat)
                || !ObservableWorldQuery.canObserveEntity(bot, initiatingThreat)
                || !CombatCore.hasLineOfSight(bot, initiatingThreat)) {
            return null;
        }
        return initiatingThreat;
    }

    private boolean isSafeRetreatAnchor(AIPlayerEntity bot, LivingEntity pressure) {
        if (!canStartAtCurrentPose(bot)) {
            return false;
        }
        return pressure == null
                || bot.distanceToSqr(pressure)
                >= (double) PREBUILD_RETREAT_DISTANCE * PREBUILD_RETREAT_DISTANCE;
    }

    /**
     * Uses ordinary task navigation (not a snap/teleport) over a short fan behind the factual
     * source.  Unlike EvadeTask's surface-only policy this permits a dry, standable cave ledge,
     * which is exactly where a last-resort shelter is useful.
     */
    private boolean startRetreatPath(AIPlayerEntity bot, LivingEntity pressure) {
        Vec3 source = pressure == null
                ? rememberedThreatPos == null ? null : Vec3.atCenterOf(rememberedThreatPos)
                : pressure.position();
        if (source == null) {
            return false;
        }
        Vec3 away = bot.position().subtract(source);
        away = new Vec3(away.x, 0.0D, away.z);
        if (away.lengthSqr() < 0.01D) {
            return false;
        }
        double[] fan = {0.0D, Math.PI / 4.0D, -Math.PI / 4.0D, Math.PI / 2.0D, -Math.PI / 2.0D};
        Vec3 normalized = away.normalize();
        for (double angle : fan) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3 direction = new Vec3(
                    normalized.x * cos - normalized.z * sin,
                    0.0D,
                    normalized.x * sin + normalized.z * cos);
            BlockPos candidate = findRetreatStandable(bot, direction);
            if (candidate == null) {
                continue;
            }
            ActionResult path = bot.getActionPack().startPathTo(candidate);
            if (!path.isFailed()) {
                retreatGoal = bot.getActionPack().activePathGoal();
                if (retreatGoal == null) {
                    retreatGoal = candidate.immutable();
                }
                bot.getActionPack().setSprinting(true);
                return true;
            }
        }
        bot.getActionPack().stopAll();
        retreatGoal = null;
        return false;
    }

    private BlockPos findRetreatStandable(AIPlayerEntity bot, Vec3 direction) {
        Vec3 projected = bot.position().add(direction.scale(PREBUILD_RETREAT_DISTANCE));
        BlockPos base = new BlockPos(
                net.minecraft.util.Mth.floor(projected.x),
                bot.blockPosition().getY(),
                net.minecraft.util.Mth.floor(projected.z));
        for (int radius = 0; radius <= 4; radius++) {
            for (BlockPos candidate : BlockPos.betweenClosed(
                    base.offset(-radius, -2, -radius), base.offset(radius, 2, radius))) {
                if (Standability.isStandable(bot.level(), candidate)
                        && !hasFluidAt(bot, candidate)) {
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    private static boolean hasFluidAt(AIPlayerEntity bot, BlockPos feet) {
        var world = bot.level();
        return !world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.above()).isEmpty();
    }

    private void tickBuild(AIPlayerEntity bot) {
        if (phaseAge() > BUILD_LIMIT) {
            BlockPos blockedTarget = targets.peek();
            if (blockedTarget != null
                    && placementBlockingHostile(bot, blockedTarget).isPresent()) {
                rejectBlockedPlacementDirection(directionTo(blockedTarget));
            }
            if (elevatedForRoofSupport) {
                returnToShelterFeet(bot);
                elevatedForRoofSupport = false;
            }
            beginExit(bot, "shelter_timeout placed=" + placed + " remaining=" + targets.size());
            return;
        }
        if (elevatedForRoofSupport) {
            placeRoofSupportFromRaisedView(bot);
            return;
        }
        if (!recoverAnchorOrRelease(bot)) {
            return;
        }
        // Only remove a target after the world proves it is sealed. The old unconditional poll
        // discarded unsupported roof/side placements even when BuildAction failed, publishing a
        // COMPLETED shelter with holes that skeleton arrows could still cross.
        String lastFailure = "none";
        while (!targets.isEmpty()) {
            BlockPos target = targets.peek();
            if (isSealed(bot, target)) {
                targets.poll();
                lastProgressTick = elapsed;
                continue;
            }
            if (settleAnchorBeforePlacement(bot, target)) {
                return;
            }
            if (handlePlacementBlockingHostile(bot, target)) {
                return;
            }
            OptionalInt blockSlot = findShelterBlockSlot(bot);
            if (blockSlot.isEmpty()) {
                beginExit(bot, "missing shelter_block");
                return;
            }
            if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
                beginExit(bot, "cannot_equip_shelter_block");
                return;
            }
            if (target.equals(roofSupport)
                    && isSealed(bot, roofSupportBase)
                    && FakePlayerMotion.jumpTo(bot, shelterFeet.above(), "shelter_roof_support_view")) {
                elevatedForRoofSupport = true;
                lastProgressTick = elapsed;
                return;
            }
            ActionResult result = isFoundation(target)
                    ? placeFoundationFromEdge(bot, target)
                    : BuildAction.placeBlockAt(bot, target);
            if (result.isSuccess() && isSealed(bot, target)) {
                targets.poll();
                rememberPlacement(bot, target);
                placed++;
                lastProgressTick = elapsed;
                break; // one physical placement per tick
            }
            lastFailure = result.reason();
            // Every queue element is a prerequisite for the element after it: foundations precede
            // side feet, each side foot precedes its head, roofSupportBase precedes roofSupport,
            // and center roof is last. Retrying the head preserves that dependency graph; rotating
            // a failed target lets the roof occupy the jump clearance before its support exists.
            break;
        }
        if (targets.isEmpty()) {
            if (!isEnvelopeSealed(bot)) {
                beginExit(bot, "shelter_envelope_not_sealed");
                return;
            }
            phase = Phase.HOLD;
            phaseStartedElapsed = elapsed;
            bot.getActionPack().stopAll();
            BotLog.action(bot, "shelter_hold_started", "placed", placed);
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            beginExit(bot, "shelter_unsealable:" + lastFailure);
        }
    }

    /**
     * Re-centres only when the next full-cube wall actually overlaps the bot. Admission already
     * clears residual path velocity; treating any later sub-pixel velocity as displacement would
     * misclassify the ordinary roof-support landing before its vertical motion settles. This
     * remains a same-cell, support-checked fake-client move and never crosses a block boundary.
     * A {@code true} result means the build tick must stop; a successful return deliberately
     * yields {@code false} so blocker inspection and placement can continue atomically this tick.
     */
    private boolean settleAnchorBeforePlacement(AIPlayerEntity bot, BlockPos target) {
        // Only the horizontal foot/head envelope can be invaded by edge drift. The center roof is
        // deliberately placed immediately after the raised-view transaction; its vertical faces
        // can differ by floating-point contact while gravity settles, without representing the
        // side-cell collision that this guard exists to repair.
        if (target.getY() < shelterFeet.getY()
                || target.getY() > shelterFeet.getY() + 1
                || directionTo(target) == null
                || !new AABB(target).intersects(bot.getBoundingBox())) {
            return false;
        }
        if (!isCenteredAnchorEntitySpaceClear(bot, shelterFeet)) {
            Direction blockedDirection = directionTo(target);
            rejectBlockedPlacementDirection(blockedDirection);
            beginExit(bot, "shelter_anchor_center_entity_occupied");
            BotLog.action(bot, "shelter_anchor_center_entity_occupied",
                    "target", target,
                    "direction", blockedDirection);
            return true;
        }
        if (!FakePlayerMotion.returnToBlockCenter(bot, shelterFeet,
                "shelter_build_anchor_settle")) {
            beginExit(bot, "shelter_anchor_recovery_failed");
            return true;
        }
        if (new AABB(target).intersects(bot.getBoundingBox())) {
            beginExit(bot, "shelter_anchor_still_blocks_wall");
            return true;
        }
        // Re-centering restores the precondition for work; it is not durable build progress and
        // must not extend the no-progress watchdog under repeated contact pressure.
        return false;
    }

    /**
     * Vanilla correctly refuses to place a wall through a living entity. Keep the ordered queue
     * intact and allow at most one ordinary cooldown/LOS constrained melee hit to create real
     * placement clearance. The occupied cell is re-read immediately: a cleared cell may be sealed
     * in this same tick, while persistent pressure rejects that side and starts the physical exit
     * transaction. Explosive contact threats are never struck.
     */
    private boolean handlePlacementBlockingHostile(AIPlayerEntity bot, BlockPos target) {
        Optional<LivingEntity> blocker = placementBlockingHostile(bot, target);
        if (blocker.isEmpty()) {
            return false;
        }
        LivingEntity hostile = blocker.orElseThrow();
        Direction blockedDirection = directionTo(target);
        if (CombatCore.isMeleeForbiddenThreat(hostile)) {
            rejectBlockedPlacementDirection(blockedDirection);
            beginExit(bot, "shelter_wall_blocked_by_melee_forbidden_hostile");
            return true;
        }
        boolean canStrike = CombatCore.inMeleeRange(bot, hostile)
                && CombatCore.hasLineOfSight(bot, hostile);
        boolean struck = false;
        if (canStrike) {
            bot.getActionPack().stopMovement();
            CombatCore.ensureMeleeWeapon(bot);
            struck = CombatCore.strikeIfReady(bot, hostile);
            if (!struck) {
                // A newly equipped weapon may need a few ordinary cooldown ticks. Waiting here is
                // not progress and remains bounded by the build-phase deadline; once the first
                // real hit lands, this target is never attacked a second time by shelter.
                return true;
            }
        }
        if (struck) {
            BotLog.action(bot, "shelter_wall_blocker_struck",
                    "target", target,
                    "direction", blockedDirection,
                    "entity", hostile.getType(),
                    "entity_id", hostile.getId());
        }
        if (placementBlockingHostile(bot, target).isEmpty()) {
            return false;
        }
        rejectBlockedPlacementDirection(blockedDirection);
        beginExit(bot, "shelter_wall_blocked_by_persistent_hostile");
        BotLog.action(bot, "shelter_wall_blocker_persisted",
                "target", target,
                "direction", blockedDirection,
                "entity", hostile.getType(),
                "entity_id", hostile.getId(),
                "struck", struck);
        return true;
    }

    /** Prevents a same-cell correction from teleporting through blocks or collidable entities. */
    private static boolean isCenteredAnchorEntitySpaceClear(AIPlayerEntity bot, BlockPos anchor) {
        double centerX = anchor.getX() + 0.5D;
        double centerZ = anchor.getZ() + 0.5D;
        AABB centeredBox = bot.getBoundingBox().move(
                centerX - bot.getX(), anchor.getY() - bot.getY(), centerZ - bot.getZ());
        return bot.level().noCollision(bot, centeredBox);
    }

    private Optional<LivingEntity> placementBlockingHostile(AIPlayerEntity bot, BlockPos target) {
        AABB placementBox = new AABB(target);
        return bot.level().getEntitiesOfClass(
                        LivingEntity.class,
                        placementBox,
                        entity -> entity != bot
                                && DangerWatcher.isActiveHostileThreat(bot, entity)
                                && ObservableWorldQuery.canObserveEntity(bot, entity))
                .stream()
                .filter(entity -> placementBox.intersects(entity.getBoundingBox()))
                .min(Comparator.comparingDouble(bot::distanceToSqr));
    }

    private void rejectBlockedPlacementDirection(Direction direction) {
        if (direction == null) {
            return;
        }
        rejectedEgress.add(direction);
        if (direction == directionTo(egressFeet)) {
            egressFeet = null;
        }
    }

    private void tickHold(AIPlayerEntity bot) {
        if (!recoverAnchorOrRelease(bot)) {
            return;
        }
        if (!isEnvelopeSealed(bot)) {
            beginExit(bot, "shelter_breached_during_hold");
            return;
        }
        if (!forcePressureExit
                && exitStartedElapsed >= 0
                && exitAge() >= EXIT_LIMIT) {
            beginForcedPressureExit(bot);
            return;
        }
        if (surfaceShelter) {
            if (!bot.level().isBrightOutside()) {
                consecutiveDaylightTicks = 0;
            } else if (consecutiveDaylightTicks < DAYLIGHT_GRACE_TICKS) {
                consecutiveDaylightTicks++;
            }
            if (consecutiveDaylightTicks < DAYLIGHT_GRACE_TICKS) {
                return;
            }
        } else if (phaseAge() < MIN_HOLD_TICKS) {
            return;
        }
        // A rescue in progress (or a healing run that just stalled for lack of food) is resolved
        // before the ordinary eating primitive gets a turn, so a just-delivered food item is
        // acknowledged on the same tick that it lets eating resume, and a genuinely stalled bot
        // never wastes a tick asking EatTask to work with an empty inventory.
        if (tickFoodRescueState(bot)) {
            return;
        }
        // Healing belongs inside the sealed safety transaction. Scheduling EatTask only after the
        // door opens exposes a critical-health bot to the exact hostile the shelter was built for.
        // Tick the ordinary physical eating primitive here so inventory selection, use duration,
        // food consumption and vanilla regeneration all remain survival-authentic.
        if (tickHoldEating(bot)) {
            return;
        }
        // This is a recovery shelter, not a night-time camp.  Do not reopen merely because a
        // daylight timer elapsed or health crossed an arbitrary near-full threshold: vanilla
        // recovery gets a genuine safe window only after food is full and health is maxed (or,
        // per isRecoveredEnoughToExit, until no more food remains to top it off with).
        if (!isRecoveredEnoughToExit(bot)) {
            return;
        }
        beginRecoveredExit(bot);
    }

    /**
     * Point 3/4/6 of the rescue contract: reacts to a healing run that has genuinely stalled for
     * lack of food -- a materially different condition from simply having already reached full
     * health (that case is left to {@link #isRecoveredEnoughToExit}). Below half health this
     * seals the bot in and waits for a player-delivered rescue, crying for help exactly once per
     * episode and re-checking every tick whether food has arrived (vanilla item pickup delivers
     * it automatically once the player tosses it through a reopened wall block); at or above half
     * health it gives up waiting and exits to fight instead, since sitting still cannot make it
     * any healthier once there is nothing left to eat.
     *
     * @return true when tickHold must stop for this tick (still waiting, or a give-up exit began)
     */
    private boolean tickFoodRescueState(AIPlayerEntity bot) {
        boolean hasFoodAvailable = InventoryAction.findFoodSlot(bot) >= 0;
        if (waitingForRescue && hasFoodAvailable) {
            waitingForRescue = false;
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot",
                    "Got the food, thank you! I will keep healing now.");
            BotLog.action(bot, "shelter_rescue_food_received",
                    "health", bot.getHealth(), "food", bot.getFoodData().getFoodLevel());
            return false; // let the ordinary eating primitive pick it up later this same tick
        }
        if (!isHealingStalledWithoutFood(bot.getHealth(), bot.getMaxHealth(), hasFoodAvailable,
                canRegenerateNaturally(bot))) {
            return false;
        }
        if (shouldAbandonRescueWaitAndFight(bot.getHealth(), bot.getMaxHealth())) {
            beginOutOfFoodExit(bot);
            return true;
        }
        waitingForRescue = true;
        if (!criedForHelp) {
            criedForHelp = true;
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot",
                    "I'm out of food and stuck healing at " + (int) bot.getHealth() + "/"
                            + (int) bot.getMaxHealth() + " HP inside my emergency shelter. "
                            + "Please bring me food: break one wall block, toss the food in, "
                            + "then seal the block back up.");
            BotLog.action(bot, "shelter_rescue_needed",
                    "health", bot.getHealth(), "anchor", shelterFeet);
        }
        return true;
    }

    private boolean tickHoldEating(AIPlayerEntity bot) {
        if (holdEatTask == null) {
            if (!shouldStartHoldEating(
                    bot.getHealth(), bot.getFoodData().getFoodLevel())
                    || InventoryAction.findFoodSlot(bot) < 0) {
                return false;
            }
            holdEatTask = new EatTask();
            holdEatTask.start(bot);
            BotLog.action(bot, "shelter_hold_eat_started",
                    "health", bot.getHealth(),
                    "food", bot.getFoodData().getFoodLevel());
        }
        holdEatTask.tick(bot);
        if (holdEatTask.state() == TaskState.RUNNING) {
            return true;
        }
        if (holdEatTask.state() == TaskState.FAILED) {
            BotLog.action(bot, "shelter_hold_eat_failed",
                    "reason", holdEatTask.failureReason());
        }
        holdEatTask = null;
        return true;
    }

    static boolean shouldStartHoldEating(float health, int foodLevel) {
        // Every recovery shelter tops hunger to the vanilla maximum.  Natural regeneration starts
        // at 18, but stopping there is precisely how a bot emerged half-healed after one more
        // incoming hit; hunger 20 gives the recovery window its best available durability.
        return health > 0.0F && foodLevel < 20;
    }

    static boolean isFullyRecovered(float health, float maxHealth, int foodLevel) {
        return foodLevel >= 20 && health >= maxHealth;
    }

    private static boolean isFullyRecovered(AIPlayerEntity bot) {
        return isFullyRecovered(bot.getHealth(), bot.getMaxHealth(), bot.getFoodData().getFoodLevel());
    }

    /**
     * Point 6a vs 6b of the rescue contract: exiting the shelter needs full health, always -- but
     * whether it also needs food topped all the way to twenty depends on WHY food stopped short
     * of that. While more food remains, {@link #shouldStartHoldEating} keeps spending it for the
     * extra durability buffer described there. Once there is none left, waiting any longer cannot
     * buy anything: a bot that reached full health right as its last food item ran out (6a, the
     * ordinary case) is safe to leave immediately, exactly like one that still has food to spare.
     * (A bot that ran out of food while STILL below full health is the different, genuinely stuck
     * case -- see {@link #isHealingStalledWithoutFood} -- and never reaches this method at all
     * while that remains true, since the {@code health < maxHealth} guard below rejects it.)
     */
    static boolean isRecoveredEnoughToExit(float health,
                                           float maxHealth,
                                           int foodLevel,
                                           boolean hasFoodAvailable) {
        if (health < maxHealth) {
            return false;
        }
        return foodLevel >= 20 || !hasFoodAvailable;
    }

    private static boolean isRecoveredEnoughToExit(AIPlayerEntity bot) {
        return isRecoveredEnoughToExit(bot.getHealth(), bot.getMaxHealth(),
                bot.getFoodData().getFoodLevel(), InventoryAction.findFoodSlot(bot) >= 0);
    }

    /**
     * True once healing has genuinely stalled: no food remains to eat, health is still below
     * max and vanilla natural regeneration cannot make up for it (hunger bar under 18, or the
     * gamerule off). A bot that just ate its last item to a full hunger bar keeps healing on its
     * own, so it holds sealed for as long as natural regeneration is actually running. That is
     * not a promise to reach full health: every natural heal costs 6 exhaustion (about 1.5 hunger
     * points), so with no food and no saturation the bar falls under 18 after roughly two hit
     * points and the bot then IS stalled (rescue wait, or give up and fight from half health).
     * Treating the full-bar phase as stalled made it cry for help, or give up at 50 percent and
     * leave the shelter half healed, with a full hunger bar. This is a materially different condition from simply having reached full health with
     * food merely not (or no longer) toppable back up to twenty -- see
     * {@link #isRecoveredEnoughToExit}, which handles that case instead. Point 6 of the rescue
     * contract: "ran out of food while still hurt" must be distinguished from "reached full
     * health and happened to run out of food around the same time".
     */
    static boolean isHealingStalledWithoutFood(float health,
                                               float maxHealth,
                                               boolean hasFoodAvailable,
                                               boolean canRegenerate) {
        return !hasFoodAvailable && health < maxHealth && !canRegenerate;
    }

    /** Vanilla natural regeneration needs a hunger bar of at least 18 (and the gamerule on). */
    static final int NATURAL_REGENERATION_FOOD_LEVEL = 18;

    static boolean canRegenerateNaturally(int foodLevel, boolean naturalRegenerationRule) {
        return naturalRegenerationRule && foodLevel >= NATURAL_REGENERATION_FOOD_LEVEL;
    }

    private static boolean canRegenerateNaturally(AIPlayerEntity bot) {
        return canRegenerateNaturally(bot.getFoodData().getFoodLevel(),
                bot.level().getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION));
    }

    /**
     * Point 6's 50%-health exception: once healing has stalled for lack of food, a bot already at
     * half health or more gives up waiting and comes out to fight rather than sitting on a rescue
     * that may never arrive -- it is not going to get any healthier waiting with nothing to eat,
     * and half health is enough to fight with. Below half health it stays sealed and cries for
     * help instead; see {@link #tickFoodRescueState}.
     */
    static boolean shouldAbandonRescueWaitAndFight(float health, float maxHealth) {
        return health >= maxHealth * 0.5F;
    }

    private void tickOpenExit(AIPlayerEntity bot) {
        if (!forcePressureExit
                && exitStartedElapsed >= 0
                && exitAge() >= EXIT_LIMIT) {
            beginForcedPressureExit(bot);
        }
        // NavSafety can physically move a wet bot out through a diagonal corner before the shelter
        // miner has reopened its planned doorway. That movement makes the player safe, but it does
        // not repay the task's owned enclosure debt. Keep operating the nearby owned door and only
        // publish the environmental terminal once a genuine two-cell, collision-free side exists.
        boolean environmentalDebt = ENVIRONMENTAL_ESCAPE_REQUIRED.equals(pendingFailure);
        if (!forcePressureExit && environmentalDebt && hasPassableEnvelopeSide(bot)) {
            BotLog.action(bot, "shelter_environmental_exit_repaid",
                    "anchor", shelterFeet,
                    "actual", bot.blockPosition());
            failShelter(bot, ENVIRONMENTAL_ESCAPE_REQUIRED);
            return;
        }
        if (!forcePressureExit && !environmentalDebt && !recoverAnchorOrRelease(bot)) {
            return;
        }
        if (egressFeet == null || !isRecoverableEgress(bot, egressFeet)) {
            if (forcePressureExit) {
                egressFeet = findForcedEgress(bot);
            } else {
                BlockPos supportedEgress = findRecoverableEgress(bot);
                if (supportedEgress != null) {
                    egressFeet = supportedEgress;
                } else if (egressFeet == null || !isOpenableEgress(bot, egressFeet)) {
                    egressFeet = findOpenableEgress(bot);
                }
            }
            if (egressFeet == null) {
                if (!forcePressureExit
                        && !pressuredEgress.isEmpty()
                        && findOwnedOpenableEgressIgnoringPressure(bot) != null) {
                    pressuredEgress.clear();
                    returnToPressureHold(bot, "shelter_pressure_cycle_restarted");
                    return;
                }
                if (forcePressureExit || !hasOpenEnvelopeSide(bot)) {
                    // A typed pressure timeout may not become terminal at the sealed anchor. Keep
                    // retrying until an owned, non-hard-rejected side can be made physically
                    // passable; STEP_OUT is the only terminal boundary for the forced transaction.
                    return;
                }
                // A build can lose its remaining material immediately after preflight, before any
                // wall closes around the bot. There is then no supported adjacent landing to step
                // onto (for example a single-pillar spawn), but publishing the pending failure at
                // the original safe feet is truthful: the task has not created an enclosure or an
                // exit debt. Do not spin forever in OPEN_EXIT waiting for a route that never existed.
                String reason = pendingFailure == null
                        ? "shelter_exit_unavailable"
                        : pendingFailure;
                BotLog.action(bot, "shelter_exit_unavailable", "reason", reason,
                        "open_side", hasOpenEnvelopeSide(bot));
                failShelter(bot, reason);
                return;
            }
        }
        if (exitMiningTarget != null) {
            BlockMiner.Status status = exitMiner.tick(bot);
            if (status == BlockMiner.Status.MINING) {
                return;
            }
            if (status == BlockMiner.Status.FAILED) {
                rejectCurrentEgress(bot, "shelter_exit_mine_failed:" + exitMiner.failureReason());
                return;
            }
            if (status == BlockMiner.Status.DONE) {
                ownedPlacements.remove(exitMiningTarget);
                exitMiningTarget = null;
            }
        }
        if (!forcePressureExit && resealObservedPressure(bot)) {
            return;
        }
        BlockPos obstruction = firstExitObstruction(bot, egressFeet);
        if (obstruction != null) {
            if (!ownsCurrentPlacement(bot, obstruction)) {
                rejectCurrentEgress(bot, "shelter_exit_blocked_unowned");
                return;
            }
            exitMiningTarget = obstruction.immutable();
            exitMiner.begin(bot, exitMiningTarget);
            exitMiner.tick(bot);
            return;
        }
        Standability.clearCache();
        if (!Standability.isStandable(bot.level(), egressFeet)) {
            if (forcePressureExit) {
                deferForcedEgress(bot, "shelter_exit_not_standable");
                return;
            }
            // The doorway has now been physically reopened and every removed block was owned by
            // this task. If its landing support disappeared meanwhile, stepping out would invent
            // movement into a ravine. Publish a bounded failure at the original safe feet instead.
            String reason = pendingFailure == null
                    ? "shelter_exit_not_standable"
                    : pendingFailure;
            BotLog.action(bot, "shelter_exit_opened_unsupported", "reason", reason,
                    "egress", egressFeet);
            failShelter(bot, reason);
            return;
        }
        phase = Phase.STEP_OUT;
        phaseStartedElapsed = elapsed;
    }

    /**
     * The head cell is deliberately opened before the foot cell, creating a one-block observation
     * port that cannot be walked through. Only facts visible through that port count as pressure.
     * If pressure exists, restore the exact owned head wall and return to a sealed HOLD transaction
     * without ever opening the passable foot cell.
     */
    private boolean resealObservedPressure(AIPlayerEntity bot) {
        if (egressFeet == null
                || ENVIRONMENTAL_ESCAPE_REQUIRED.equals(pendingFailure)
                || !isOpenCell(bot, egressFeet.above())
                || !ownsCurrentPlacement(bot, egressFeet)) {
            return false;
        }
        int visiblePressure = observableExitPressure(bot);
        if (visiblePressure <= 0) {
            return false;
        }
        OptionalInt blockSlot = findShelterBlockSlot(bot);
        if (blockSlot.isEmpty()
                || InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            // Fail closed in the non-passable observation state and retry while the exit watchdog
            // remains bounded. Never mine the foot wall merely because the spare block is delayed.
            return true;
        }
        BlockPos observationPort = egressFeet.above().immutable();
        ActionResult result = BuildAction.placeBlockAt(bot, observationPort);
        if (!result.isSuccess() || !isSealed(bot, observationPort)) {
            return true;
        }
        rememberPlacement(bot, observationPort);
        placed++;
        observationReseals++;
        Direction pressuredDirection = directionTo(egressFeet);
        if (pressuredDirection != null) {
            pressuredEgress.add(pressuredDirection);
        }
        BlockPos resealedEgress = egressFeet;
        egressFeet = null;
        lastProgressTick = elapsed;
        BotLog.action(bot, "shelter_observation_resealed",
                "port", observationPort,
                "egress", resealedEgress,
                "direction", pressuredDirection,
                "visible_hostiles", visiblePressure,
                "reseals", observationReseals);
        returnToPressureHold(bot, "shelter_pressure_hold_started");
        return true;
    }

    private int observableExitPressure(AIPlayerEntity bot) {
        return bot.level()
                .getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(egressFeet).inflate(CombatCore.hostilePressureScanRange()),
                        entity -> DangerWatcher.isActiveHostileThreat(bot, entity)
                                && ObservableWorldQuery.canObserveEntity(bot, entity)
                                && CombatCore.isWithinHostilePressureEnvelope(bot, entity))
                .size();
    }

    private void tickStepOut(AIPlayerEntity bot) {
        if (!forcePressureExit
                && exitStartedElapsed >= 0
                && exitAge() >= EXIT_LIMIT) {
            beginForcedPressureExit(bot);
            return;
        }
        if (bot.blockPosition().equals(egressFeet)
                || FakePlayerMotion.stepToStandable(bot, egressFeet, "shelter_owned_egress")) {
            if (!bot.blockPosition().equals(egressFeet)) {
                noteExitFailure("shelter_exit_pose_unverified");
                phase = Phase.OPEN_EXIT;
                return;
            }
            // The worker is physically outside before this artifact enters the cooperative
            // cleanup registry.  This ordering is what makes exact ownership safe even if another
            // nearby bot wins the cleanup claim on the next idle scan.
            registerOwnedCleanupDebt(bot);
            if (pendingFailure == null) {
                completeShelter(bot);
            } else {
                failShelter(bot, pendingFailure);
            }
            return;
        }
        phase = Phase.OPEN_EXIT;
    }

    private void placeRoofSupportFromRaisedView(AIPlayerEntity bot) {
        if (isSealed(bot, roofSupport)) {
            if (!targets.isEmpty() && targets.peek().equals(roofSupport)) {
                targets.poll();
            } else {
                targets.remove(roofSupport);
            }
            if (!returnToShelterFeet(bot)) {
                beginExit(bot, "shelter_elevation_return_failed");
                return;
            }
            elevatedForRoofSupport = false;
            lastProgressTick = elapsed;
            return;
        }
        OptionalInt blockSlot = findShelterBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            returnToShelterFeet(bot);
            elevatedForRoofSupport = false;
            beginExit(bot, "missing shelter_block");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            returnToShelterFeet(bot);
            elevatedForRoofSupport = false;
            beginExit(bot, "cannot_equip_shelter_block");
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, roofSupport);
        if (result.isSuccess() && isSealed(bot, roofSupport)) {
            rememberPlacement(bot, roofSupport);
            placed++;
            lastProgressTick = elapsed;
            return; // next tick physically drop back before placing the center roof
        }
        if (!returnToShelterFeet(bot)) {
            beginExit(bot, "shelter_elevation_return_failed:" + result.reason());
            return;
        }
        elevatedForRoofSupport = false;
    }

    private boolean returnToShelterFeet(AIPlayerEntity bot) {
        if (bot.blockPosition().equals(shelterFeet)) {
            return true;
        }
        Standability.clearCache();
        return Standability.isStandable(bot.level(), shelterFeet)
                && FakePlayerMotion.stepToStandable(
                bot, shelterFeet, "shelter_roof_support_return");
    }

    /**
     * A shelter owns a fixed, supported center. Knockback or gravity may move the player before
     * the shell is sealed; repeatedly trying a one-cell snap back while an enemy keeps attacking
     * turns the atomic-exit guarantee into a death lock. A player who has already landed safely
     * outside the anchor has no enclosure debt and can release the safety scheduler immediately.
     * An airborne/unsettled pose gets one second to return to a still-standable anchor, then fails
     * closed so combat or navigation rescue can take over.
     */
    private boolean recoverAnchorOrRelease(AIPlayerEntity bot) {
        if (bot.blockPosition().equals(shelterFeet)) {
            anchorRecoveryStartedElapsed = -1;
            return true;
        }
        Standability.clearCache();
        BlockPos here = bot.blockPosition();
        if (Standability.isStandable(bot.level(), here)) {
            failDisplacedAnchor(bot, "shelter_anchor_displaced");
            return false;
        }
        if (anchorRecoveryStartedElapsed < 0) {
            anchorRecoveryStartedElapsed = elapsed;
        }
        if (returnToShelterFeet(bot)) {
            anchorRecoveryStartedElapsed = -1;
            return true;
        }
        if (elapsed - anchorRecoveryStartedElapsed > ANCHOR_RECOVERY_LIMIT) {
            failDisplacedAnchor(bot, "shelter_anchor_recovery_timeout");
        }
        return false;
    }

    private void failDisplacedAnchor(AIPlayerEntity bot, String reason) {
        String terminalReason = pendingFailure == null ? reason : pendingFailure;
        exitMiner.cancel(bot);
        exitMiningTarget = null;
        bot.getActionPack().stopAll();
        BotLog.action(bot, "shelter_anchor_released",
                "reason", reason,
                "anchor", shelterFeet,
                "actual", bot.blockPosition(),
                "owned", ownedPlacements.size(),
                "phase", phase,
                "terminal_reason", terminalReason);
        failShelter(bot, terminalReason);
    }

    static boolean canStartAtCurrentPose(AIPlayerEntity bot) {
        Standability.clearCache();
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        // NavSafety owns the complete water-recovery episode. A fixed shelter anchor must not
        // compete for movement or place an enclosure around either a pending rescue waypoint or a
        // body column whose fluid shape can change underneath it.
        if (NavSafetyNet.INSTANCE.isWaterRescueActive(bot) || hasBodyFluid(bot)) {
            return false;
        }
        if (!Standability.isStandable(world, feet)) {
            return false;
        }
        if (bot.onGround()) {
            return true;
        }
        // Clientless players can retain a stale false onGround bit immediately after a verified
        // spawn/teleport. Accept that bit only when Minecraft's own micro-support probe finds the
        // exact block under this collision box. A genuinely falling player over an open shaft has
        // no such support and remains ineligible for a fixed shelter anchor.
        AABB box = bot.getBoundingBox();
        AABB supportProbe = new AABB(
                box.minX, box.minY - 1.0E-6D, box.minZ,
                box.maxX, box.minY, box.maxZ);
        return world.findSupportingBlock(bot, supportProbe)
                .filter(feet.below()::equals)
                .isPresent();
    }

    static boolean isSurfaceShelterAnchor(AIPlayerEntity bot, BlockPos origin) {
        if (origin.getY() < 32) {
            return false;
        }
        for (int dx = -8; dx <= 8; dx += 4) {
            for (int dz = -8; dz <= 8; dz += 4) {
                int topY = bot.level().getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        origin.getX() + dx,
                        origin.getZ() + dz);
                if (Math.abs(topY - origin.getY()) <= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFoundation(BlockPos target) {
        return target.getY() == shelterFeet.getY() - 1
                && Math.abs(target.getX() - shelterFeet.getX())
                + Math.abs(target.getZ() - shelterFeet.getZ()) == 1;
    }

    private ActionResult placeFoundationFromEdge(AIPlayerEntity bot, BlockPos target) {
        int dx = target.getX() - shelterFeet.getX();
        int dz = target.getZ() - shelterFeet.getZ();
        Direction direction = null;
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (candidate.getStepX() == dx && candidate.getStepZ() == dz) {
                direction = candidate;
                break;
            }
        }
        if (direction == null
                || !FakePlayerMotion.shiftToSupportEdge(
                        bot, shelterFeet, direction, "shelter_foundation")) {
            return ActionResult.failed("foundation_edge_unreachable");
        }
        ActionResult result = BuildAction.placeBlock(
                bot, shelterFeet.below(), direction, InteractionHand.MAIN_HAND);
        if (!FakePlayerMotion.returnToBlockCenter(bot, shelterFeet, "shelter_foundation")) {
            return ActionResult.failed("foundation_edge_return_failed");
        }
        return result;
    }

    private void beginExit(AIPlayerEntity bot, String failure) {
        if (failure != null) {
            noteExitFailure(failure);
        }
        phase = Phase.OPEN_EXIT;
        phaseStartedElapsed = elapsed;
        if (exitStartedElapsed < 0) {
            exitStartedElapsed = elapsed;
        }
        elevatedForRoofSupport = false;
        exitMiningTarget = null;
        cancelHoldEating(bot, "shelter_hold_finished");
        exitMiner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    private void beginForcedPressureExit(AIPlayerEntity bot) {
        noteExitFailure("shelter_exit_pressure_timeout");
        forcePressureExit = true;
        pressuredEgress.clear();

        Direction currentDirection = directionTo(egressFeet);
        boolean keepCurrent = currentDirection != null
                && !rejectedEgress.contains(currentDirection)
                && isRecoverableEgress(bot, egressFeet);
        if (!keepCurrent) {
            exitMiner.cancel(bot);
            exitMiningTarget = null;
            egressFeet = findForcedEgress(bot);
        }
        phase = Phase.OPEN_EXIT;
        phaseStartedElapsed = elapsed;
        elevatedForRoofSupport = false;
        cancelHoldEating(bot, "shelter_pressure_timeout");
        bot.getActionPack().stopAll();
        BotLog.action(bot, "shelter_pressure_exit_forced",
                "reason", pendingFailure,
                "egress", egressFeet,
                "kept_current", keepCurrent,
                "exit_age", exitAge());
    }

    private void returnToPressureHold(AIPlayerEntity bot, String reason) {
        phase = Phase.HOLD;
        phaseStartedElapsed = elapsed;
        exitMiningTarget = null;
        exitMiner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, reason,
                "pressured", pressuredEgress.size(),
                "exit_age", exitAge());
    }

    private void cancelHoldEating(AIPlayerEntity bot, String reason) {
        if (holdEatTask != null) {
            holdEatTask.cancel(bot, reason);
            holdEatTask = null;
        }
    }

    private void noteExitFailure(String reason) {
        if (pendingFailure == null) {
            pendingFailure = reason;
        }
    }

    private void rejectCurrentEgress(AIPlayerEntity bot, String reason) {
        noteExitFailure(reason);
        Direction direction = directionTo(egressFeet);
        if (direction != null) {
            rejectedEgress.add(direction);
        }
        exitMiner.cancel(bot);
        exitMiningTarget = null;
        egressFeet = null;
    }

    private void deferForcedEgress(AIPlayerEntity bot, String reason) {
        Direction direction = directionTo(egressFeet);
        if (direction != null && direction == lastDeferredForcedDirection) {
            // The exact same forced candidate came back unusable two ticks in a row with nothing
            // in the world able to change that (e.g. its landing support is gone for good).
            // Further retries cannot converge, so fail closed instead of spinning until the
            // GameTest/production watchdog timeout.
            failShelter(bot, reason);
            return;
        }
        lastDeferredForcedDirection = direction;
        exitMiner.cancel(bot);
        exitMiningTarget = null;
        egressFeet = null;
        BotLog.action(bot, "shelter_forced_egress_deferred",
                "reason", reason,
                "exit_age", exitAge());
    }

    private int phaseAge() {
        return Math.max(0, elapsed - phaseStartedElapsed);
    }

    private int exitAge() {
        return exitStartedElapsed < 0 ? 0 : Math.max(0, elapsed - exitStartedElapsed);
    }

    private boolean handleEnvironmentalOwnershipConflict(AIPlayerEntity bot) {
        if (!NavSafetyNet.INSTANCE.isWaterRescueActive(bot) && !hasBodyFluid(bot)) {
            return false;
        }
        // During an incomplete build, a verified two-high open side proves there is no sealed
        // enclosure debt to repay. Release immediately so NavSafety can own the next movement tick.
        if (phase == Phase.BUILD && hasOpenEnvelopeSide(bot)) {
            BotLog.action(bot, "shelter_environmental_release",
                    "phase", phase,
                    "anchor", shelterFeet,
                    "open_side", true);
            failShelter(bot, ENVIRONMENTAL_ESCAPE_REQUIRED);
            return true;
        }

        // A sealed HOLD (or a BUILD with every side closed) must reopen an owned doorway before it
        // can publish failure. If another exit reason was already pending, water ownership wins the
        // terminal type while preserving the prior reason in the log.
        if (!ENVIRONMENTAL_ESCAPE_REQUIRED.equals(pendingFailure)) {
            BotLog.action(bot, "shelter_environmental_exit_required",
                    "phase", phase,
                    "anchor", shelterFeet,
                    "previous_reason", pendingFailure == null ? "" : pendingFailure);
            pendingFailure = ENVIRONMENTAL_ESCAPE_REQUIRED;
        }
        if (phase == Phase.BUILD || phase == Phase.HOLD) {
            beginExit(bot, null);
        }
        return false;
    }

    private static boolean hasBodyFluid(AIPlayerEntity bot) {
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        return bot.isUnderWater()
                || !world.getFluidState(feet).isEmpty()
                || !world.getFluidState(feet.above()).isEmpty();
    }

    private void completeShelter(AIPlayerEntity bot) {
        complete();
        recordTerminal(bot, TaskState.COMPLETED, "");
    }

    private void failShelter(AIPlayerEntity bot, String reason) {
        fail(reason);
        recordTerminal(bot, TaskState.FAILED, reason);
    }

    private void recordTerminal(AIPlayerEntity bot, TaskState outcome, String reason) {
        if (terminalRecorded) {
            return;
        }
        terminalRecorded = true;
        BlockPos anchor = shelterFeet == null ? bot.blockPosition() : shelterFeet;
        DangerWatcher.INSTANCE.noteShelterTerminal(bot, anchor, outcome, reason);
    }

    private static void addFoundationIfNeeded(AIPlayerEntity bot,
                                              List<BlockPos> targets,
                                              BlockPos sideFeet) {
        if (!isSealed(bot, sideFeet) && !isSealed(bot, sideFeet.below())) {
            targets.add(sideFeet.below().immutable());
        }
    }

    private static Optional<ShelterPlan> planShelter(AIPlayerEntity bot, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos roof = head.above();
        // The eight side cells are only diagonal to the center roof. In strict survival they can
        // never be clicked as a face-adjacent placement support for that roof. Build one permanent
        // top-rim block above the north wall first; once the north head wall exists, the rim can be
        // placed on it and the center roof can then be placed against the rim.
        BlockPos roofSupport = roof.north().immutable();
        BlockPos roofSupportBase = head.north().immutable();
        BlockPos egressFeet = selectPlannedEgress(bot, feet);
        if (egressFeet == null) {
            return Optional.empty();
        }
        List<BlockPos> targets = new ArrayList<>(MAX_PLACEMENT_BLOCKS);
        // Only an open side whose landing support is missing needs a foundation. Put the planned
        // exit support first so no envelope block can be placed until the task has physically
        // proved that it owns a supported way back out.
        addFoundationIfNeeded(bot, targets, egressFeet);
        for (Direction direction : HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            if (!side.equals(egressFeet)) {
                addFoundationIfNeeded(bot, targets, side);
            }
        }
        // Build the envelope from the ground up. Keeping the center roof last prevents an earlier
        // failed target from leaving the player under a roof that can no longer be supported.
        for (Direction direction : HORIZONTAL) {
            targets.add(feet.relative(direction).immutable());
            targets.add(head.relative(direction).immutable());
        }
        if (!isSealed(bot, roof)) {
            targets.add(roofSupport);
            targets.add(roof.immutable());
        }
        return Optional.of(new ShelterPlan(
                egressFeet.immutable(),
                roofSupport,
                roofSupportBase,
                List.copyOf(targets)));
    }

    private static int requiredShelterBlocks(AIPlayerEntity bot, ShelterPlan plan) {
        int required = 0;
        for (BlockPos target : plan.targets()) {
            if (!isSealed(bot, target)) {
                required++;
            }
        }
        return required;
    }

    private static BlockPos selectPlannedEgress(AIPlayerEntity bot, BlockPos feet) {
        var world = bot.level();
        // Prefer a landing that already exists. Only extend a foundation when no naturally
        // supported two-block doorway is available.
        for (Direction direction : HORIZONTAL) {
            BlockPos candidate = feet.relative(direction);
            if (!isDryReplaceable(world.getBlockState(candidate))
                    || !isDryReplaceable(world.getBlockState(candidate.above()))) {
                continue;
            }
            BlockPos support = candidate.below();
            if (isSafeSupport(bot, support)) {
                return candidate.immutable();
            }
        }
        for (Direction direction : HORIZONTAL) {
            BlockPos candidate = feet.relative(direction);
            if (isDryReplaceable(world.getBlockState(candidate))
                    && isDryReplaceable(world.getBlockState(candidate.above()))
                    && isDryReplaceable(world.getBlockState(candidate.below()))) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private BlockPos findRecoverableEgress(AIPlayerEntity bot) {
        return findRecoverableEgress(bot, false);
    }

    private BlockPos findRecoverableEgressIgnoringPressure(AIPlayerEntity bot) {
        return findRecoverableEgress(bot, true);
    }

    private BlockPos findRecoverableEgress(AIPlayerEntity bot, boolean ignorePressure) {
        for (Direction direction : HORIZONTAL) {
            if (rejectedEgress.contains(direction)
                    || !ignorePressure && pressuredEgress.contains(direction)) {
                continue;
            }
            BlockPos candidate = shelterFeet.relative(direction);
            if (isRecoverableEgress(bot, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private BlockPos findOpenableEgress(AIPlayerEntity bot) {
        return findOpenableEgress(bot, false);
    }

    private BlockPos findOpenableEgressIgnoringPressure(AIPlayerEntity bot) {
        return findOpenableEgress(bot, true);
    }

    private BlockPos findOpenableEgress(AIPlayerEntity bot, boolean ignorePressure) {
        for (Direction direction : HORIZONTAL) {
            if (rejectedEgress.contains(direction)
                    || !ignorePressure && pressuredEgress.contains(direction)) {
                continue;
            }
            BlockPos candidate = shelterFeet.relative(direction);
            if (isOpenableEgress(bot, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private BlockPos findForcedEgress(AIPlayerEntity bot) {
        BlockPos supported = findRecoverableEgressIgnoringPressure(bot);
        return supported == null ? findOpenableEgressIgnoringPressure(bot) : supported;
    }

    private BlockPos findOwnedOpenableEgressIgnoringPressure(AIPlayerEntity bot) {
        for (Direction direction : HORIZONTAL) {
            if (rejectedEgress.contains(direction)) {
                continue;
            }
            BlockPos candidate = shelterFeet.relative(direction);
            if (isOpenableEgress(bot, candidate)
                    && (ownsCurrentPlacement(bot, candidate)
                    || ownsCurrentPlacement(bot, candidate.above()))) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean isRecoverableEgress(AIPlayerEntity bot, BlockPos candidate) {
        return candidate != null
                && isSafeSupport(bot, candidate.below())
                && isOpenableEgress(bot, candidate);
    }

    private boolean isOpenableEgress(AIPlayerEntity bot, BlockPos candidate) {
        return candidate != null
                && (isOpenCell(bot, candidate) || ownsCurrentPlacement(bot, candidate))
                && (isOpenCell(bot, candidate.above()) || ownsCurrentPlacement(bot, candidate.above()));
    }

    private BlockPos firstExitObstruction(AIPlayerEntity bot, BlockPos candidate) {
        if (!isOpenCell(bot, candidate.above())) {
            return candidate.above().immutable();
        }
        if (!isOpenCell(bot, candidate)) {
            return candidate.immutable();
        }
        return null;
    }

    private void rememberPlacement(AIPlayerEntity bot, BlockPos target) {
        ownedPlacements.put(target.immutable(), bot.level().getBlockState(target));
    }

    private boolean ownsCurrentPlacement(AIPlayerEntity bot, BlockPos target) {
        BlockState owned = ownedPlacements.get(target);
        return owned != null && owned.equals(bot.level().getBlockState(target));
    }

    private boolean isEnvelopeSealed(AIPlayerEntity bot) {
        if (!isSealed(bot, shelterFeet.above(2))) {
            return false;
        }
        for (Direction direction : HORIZONTAL) {
            if (!isSealed(bot, shelterFeet.relative(direction))
                    || !isSealed(bot, shelterFeet.above().relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasOpenEnvelopeSide(AIPlayerEntity bot) {
        for (Direction direction : HORIZONTAL) {
            BlockPos side = shelterFeet.relative(direction);
            if (isOpenCell(bot, side) && isOpenCell(bot, side.above())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPassableEnvelopeSide(AIPlayerEntity bot) {
        for (Direction direction : HORIZONTAL) {
            BlockPos side = shelterFeet.relative(direction);
            if (hasNoCollision(bot, side) && hasNoCollision(bot, side.above())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNoCollision(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.level();
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isOpenCell(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.level();
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isSafeSupport(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.level();
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isDryReplaceable(BlockState state) {
        return state.getFluidState().isEmpty() && state.canBeReplaced();
    }

    private Direction directionTo(BlockPos target) {
        if (target == null) {
            return null;
        }
        int dx = target.getX() - shelterFeet.getX();
        int dz = target.getZ() - shelterFeet.getZ();
        for (Direction direction : HORIZONTAL) {
            if (direction.getStepX() == dx && direction.getStepZ() == dz) {
                return direction;
            }
        }
        return null;
    }

    // The following are thin delegators to ShelterCleanupRegistry (shelterdig-refactor-1): kept
    // here, with identical signatures, purely so FollowTask/ShelterCleanupTask/DangerWatcher/
    // RuntimeLifecycleCoordinator callers need no change.
    static Optional<ExitDebt> pendingExitDebt(AIPlayerEntity bot) {
        return ShelterCleanupRegistry.pendingExitDebt(bot);
    }

    static void clearExitDebt(AIPlayerEntity bot, ExitDebt debt) {
        ShelterCleanupRegistry.clearExitDebt(bot, debt);
    }

    static void promoteExitDebtForCleanup(AIPlayerEntity bot, ExitDebt debt) {
        ShelterCleanupRegistry.promoteExitDebtForCleanup(bot, debt);
    }

    static boolean hasPendingCleanup(AIPlayerEntity bot) {
        return ShelterCleanupRegistry.hasPendingCleanup(bot);
    }

    static Optional<ShelterCleanupDebt> claimPendingCleanup(AIPlayerEntity bot) {
        return ShelterCleanupRegistry.claimPendingCleanup(bot);
    }

    static boolean renewCleanupClaim(AIPlayerEntity bot, ShelterCleanupDebt debt) {
        return ShelterCleanupRegistry.renewCleanupClaim(bot, debt);
    }

    static Optional<BlockPos> nextCleanupBlock(AIPlayerEntity bot,
                                               ShelterCleanupDebt debt,
                                               Set<BlockPos> excluded) {
        return ShelterCleanupRegistry.nextCleanupBlock(bot, debt, excluded);
    }

    static boolean ownsCleanupBlock(AIPlayerEntity bot,
                                    ShelterCleanupDebt debt,
                                    BlockPos position) {
        return ShelterCleanupRegistry.ownsCleanupBlock(bot, debt, position);
    }

    static void settleCleanupBlock(AIPlayerEntity bot, ShelterCleanupDebt debt, BlockPos position) {
        ShelterCleanupRegistry.settleCleanupBlock(bot, debt, position);
    }

    static void releaseCleanupClaim(AIPlayerEntity bot, ShelterCleanupDebt debt) {
        ShelterCleanupRegistry.releaseCleanupClaim(bot, debt);
    }

    public static void forgetCleanupDebtsOwnedBy(AIPlayerEntity bot) {
        ShelterCleanupRegistry.forgetCleanupDebtsOwnedBy(bot);
    }

    private void registerOwnedCleanupDebt(AIPlayerEntity bot) {
        if (cleanupDebtRegistered || shelterFeet == null) {
            return;
        }
        cleanupDebtRegistered = true;
        ShelterCleanupRegistry.registerCleanupDebt(bot, shelterFeet, ownedPlacements);
    }

    /**
     * Captures any cancellation-time enclosure that has no physically passable side yet, even if
     * the roof is still unfinished.  A BUILD cancellation can otherwise land after the four
     * two-high side walls are complete but before the roof transaction, leaving the bot just as
     * boxed in as a fully completed shell.  The debt remains limited to a verified two-cell
     * doorway made from this task's exact placements.
     *
     * @return whether FollowTask must physically repay a doorway before cleanup can be allowed
     */
    private boolean preserveOwnedExitDebt(AIPlayerEntity bot) {
        if (shelterFeet == null
                || !bot.blockPosition().equals(shelterFeet)
                || hasPassableEnvelopeSide(bot)) {
            return false;
        }
        Map<BlockPos, BlockState> currentOwned = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : ownedPlacements.entrySet()) {
            BlockPos position = entry.getKey();
            BlockState state = entry.getValue();
            if (state.equals(bot.level().getBlockState(position))) {
                currentOwned.put(position.immutable(), state);
            }
        }
        List<BlockPos> candidates = orderedEgressCandidates();
        boolean canReopenDoor = candidates.stream().anyMatch(candidate ->
                currentOwned.containsKey(candidate) && currentOwned.containsKey(candidate.above()));
        if (!canReopenDoor) {
            return false;
        }
        ExitDebt debt = new ExitDebt(
                bot.level().dimension().identifier().toString(),
                shelterFeet,
                candidates,
                currentOwned);
        ShelterCleanupRegistry.recordExitDebt(bot, debt);
        BotLog.action(bot, "shelter_exit_debt_handed_off",
                "anchor", shelterFeet,
                "owned", currentOwned.size(),
                "egress", egressFeet);
        return true;
    }

    private List<BlockPos> orderedEgressCandidates() {
        List<BlockPos> candidates = new ArrayList<>(HORIZONTAL.length);
        if (egressFeet != null) {
            candidates.add(egressFeet.immutable());
        }
        for (Direction direction : HORIZONTAL) {
            BlockPos candidate = shelterFeet.relative(direction).immutable();
            if (!candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        exitMiner.cancel(bot);
        cancelHoldEating(bot, "shelter_aborted");
        if (elevatedForRoofSupport) {
            returnToShelterFeet(bot);
        }
        boolean exitDebtHandedOff = preserveOwnedExitDebt(bot);
        // A cancelled partial shell still deserves cleanup, but only after the bot is already
        // outside its anchor or a collision-free side proves that a worker cannot entomb it by
        // removing the remaining owned blocks. A sealed/side-trapped shell instead stays out of
        // the cleanup registry until FollowTask has physically repaid its ExitDebt.
        if (!exitDebtHandedOff
                && (shelterFeet == null
                || !bot.blockPosition().equals(shelterFeet)
                || hasPassableEnvelopeSide(bot))) {
            registerOwnedCleanupDebt(bot);
        }
        bot.getActionPack().stopAll();
    }

    /**
     * A fully healed bot deliberately reopens its own door even if the original hostile is still
     * visible.  The normal danger scheduler immediately gives that healthy bot a defensive combat
     * owner after it steps out; resealing forever would turn recovery into a dirt prison.
     */
    private void beginRecoveredExit(AIPlayerEntity bot) {
        beginSafeExit(bot, "shelter_recovery_complete", "shelter_recovery_complete_exit");
    }

    /**
     * Point 6's 50%-health exception: healing has genuinely stalled (no food left, still below
     * max HP) but the bot is healthy enough to fight rather than sit sealed in on a rescue that
     * may never come. Physically identical to a full recovery exit -- forcing the door the same
     * way and for the same reason -- only the log reason and rescue messaging differ.
     */
    private void beginOutOfFoodExit(AIPlayerEntity bot) {
        beginSafeExit(bot, "shelter_out_of_food_exit", "shelter_out_of_food_exit");
    }

    private void beginSafeExit(AIPlayerEntity bot, String cancelReason, String logAction) {
        announceRescueResolvedIfNeeded(bot);
        forcePressureExit = true;
        phase = Phase.OPEN_EXIT;
        phaseStartedElapsed = elapsed;
        if (exitStartedElapsed < 0) {
            exitStartedElapsed = elapsed;
        }
        elevatedForRoofSupport = false;
        exitMiningTarget = null;
        cancelHoldEating(bot, cancelReason);
        exitMiner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, logAction,
                "health", bot.getHealth(),
                "max_health", bot.getMaxHealth(),
                "food", bot.getFoodData().getFoodLevel());
    }

    /**
     * Point 5's third chat message: once a bot has cried for help this episode, it also tells the
     * player when that need has passed -- either the 50%-and-out-of-food exception above fired,
     * or enough food eventually arrived to finish healing normally. This is deliberately distinct
     * from (and sent later than) the "got the food, continuing to heal" message in
     * {@link #tickFoodRescueState}: that one confirms the food arrived, this one confirms the
     * whole episode is over. Never fires for the ordinary case where the bot never ran short of
     * food in the first place, and never repeats even if a pressure reseal sends this shelter
     * back through HOLD before it physically steps outside.
     */
    private void announceRescueResolvedIfNeeded(AIPlayerEntity bot) {
        if (!criedForHelp || rescueResolvedAnnounced) {
            return;
        }
        rescueResolvedAnnounced = true;
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot",
                "I'm safe now and no longer need rescuing.");
        BotLog.action(bot, "shelter_rescue_resolved",
                "health", bot.getHealth(), "food", bot.getFoodData().getFoodLevel());
    }

    /** Immutable proof that a cancelled shelter may reopen only its own two-cell doorway. */
    static final class ExitDebt {
        private final String dimension;
        private final BlockPos anchor;
        private final List<BlockPos> egressCandidates;
        // Package-private (not private): ShelterCleanupRegistry.promoteExitDebtForCleanup reads
        // this directly to hand the exact-state placements off to a cleanup debt.
        final Map<BlockPos, BlockState> ownedPlacements;

        // Package-private (not private): only ShelterCleanupRegistry.recordExitDebt stores an
        // ExitDebt now, but EmergencyShelterTask.preserveOwnedExitDebt still builds it here since
        // constructing it needs no state beyond these plain arguments.
        ExitDebt(String dimension,
                         BlockPos anchor,
                         List<BlockPos> egressCandidates,
                         Map<BlockPos, BlockState> ownedPlacements) {
            this.dimension = dimension;
            this.anchor = anchor.immutable();
            this.egressCandidates = List.copyOf(egressCandidates);
            this.ownedPlacements = Map.copyOf(ownedPlacements);
        }

        BlockPos anchor() {
            return anchor;
        }

        List<BlockPos> egressCandidates() {
            return egressCandidates;
        }

        boolean matchesDimension(AIPlayerEntity bot) {
            return dimension.equals(bot.level().dimension().identifier().toString());
        }

        boolean ownsCurrentPlacement(AIPlayerEntity bot, BlockPos position) {
            if (!matchesDimension(bot)) {
                return false;
            }
            BlockState owned = ownedPlacements.get(position);
            return owned != null && owned.equals(bot.level().getBlockState(position));
        }
    }

    /**
     * Mutable only while held under ShelterCleanupRegistry's PENDING_CLEANUPS; every entry is a
     * recorded block state from a real placement.  `owner` is audit metadata, while any bot may
     * hold a short cleanup lease once no hostile pressure remains.
     */
    static final class ShelterCleanupDebt {
        private final UUID id;
        // Package-private (not private): ShelterCleanupRegistry.forgetCleanupDebtsOwnedBy reads
        // this directly to prune every debt a despawning bot owns.
        final UUID owner;
        private final String dimension;
        private final BlockPos anchor;
        // Package-private (not private): ShelterCleanupRegistry's claim/prune/settle methods read
        // and mutate this directly, the same way they already call discardChangedBlocks() etc.
        final Map<BlockPos, BlockState> remaining;
        private final int registrationTick;
        private UUID claimant;
        private int claimTick;

        // Package-private (not private): only ShelterCleanupRegistry.registerCleanupDebt
        // constructs a ShelterCleanupDebt now.
        ShelterCleanupDebt(UUID id,
                                   UUID owner,
                                   String dimension,
                                   BlockPos anchor,
                                   Map<BlockPos, BlockState> placements,
                                   int registrationTick) {
            this.id = id;
            this.owner = owner;
            this.dimension = dimension;
            this.anchor = anchor.immutable();
            this.remaining = new LinkedHashMap<>(placements);
            this.registrationTick = registrationTick;
        }

        UUID id() {
            return id;
        }

        BlockPos anchor() {
            return anchor;
        }

        boolean matchesDimension(AIPlayerEntity bot) {
            return dimension.equals(bot.level().dimension().identifier().toString());
        }

        boolean isWithinReasonableRange(AIPlayerEntity bot) {
            return anchor.distSqr(bot.blockPosition())
                    <= ShelterCleanupRegistry.CLEANUP_MAX_DISTANCE
                    * ShelterCleanupRegistry.CLEANUP_MAX_DISTANCE;
        }

        /** True once this debt is definitely older than any single GameTest can run, meaning the
         *  test that created it has already ended and this is a leaked cross-test artifact. Never
         *  true outside the GameTest harness -- see {@link ShelterCleanupRegistry#AGE_PRUNING_ENABLED}. */
        boolean isStale(int now) {
            return ShelterCleanupRegistry.AGE_PRUNING_ENABLED
                    && now - registrationTick > ShelterCleanupRegistry.CLEANUP_MAX_AGE_TICKS;
        }

        boolean claimAvailableTo(UUID botId, int now) {
            return claimant == null || claimant.equals(botId)
                    || now - claimTick > ShelterCleanupRegistry.CLEANUP_CLAIM_LEASE_TICKS;
        }

        boolean claimedBy(UUID botId) {
            return claimant != null && claimant.equals(botId);
        }

        void claim(UUID botId, int now) {
            claimant = botId;
            claimTick = now;
        }

        void release(UUID botId) {
            if (claimedBy(botId)) {
                claimant = null;
                claimTick = 0;
            }
        }

        void discardChangedBlocks(AIPlayerEntity bot) {
            remaining.entrySet().removeIf(entry ->
                    ObservableWorldQuery.canObserveBlock(bot, entry.getKey())
                            && !entry.getValue().equals(
                            bot.level().getBlockState(entry.getKey())));
        }

        boolean ownsCurrentPlacement(AIPlayerEntity bot, BlockPos position) {
            BlockState expected = remaining.get(position);
            return expected != null && expected.equals(bot.level().getBlockState(position));
        }
    }

    private static boolean isSealed(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.level();
        var state = world.getBlockState(pos);
        return !state.canBeReplaced()
                && !state.getCollisionShape(world, pos).isEmpty();
    }

    private static OptionalInt findShelterBlockSlot(AIPlayerEntity bot) {
        return MaterialPalette.pickEmergencyShelterBlockSlot(bot);
    }

    private static int countShelterBlocks(AIPlayerEntity bot) {
        return MaterialPalette.countEmergencyShelterBlocks(bot);
    }

    public static boolean hasShelterBlock(AIPlayerEntity bot) {
        return findShelterBlockSlot(bot).isPresent();
    }

    /**
     * Admission for a retreat-first emergency.  A flat dry shell needs eight walls and two roof
     * cells; uneven terrain can need foundations too and is still rechecked at the eventual anchor.
     * This intentionally does not require the *current* pose to be dry/standable because water
     * recovery and the retreat leg happen before the final exact plan is constructed.
     */
    static boolean hasMaterialsForEmergencyRetreat(AIPlayerEntity bot) {
        return countShelterBlocks(bot) >= 10;
    }

    /**
     * Admission probe shared with the danger scheduler. It uses the exact same physical plan and
     * inventory palette as {@link #onStart(AIPlayerEntity)}, so a one-block inventory cannot pause
     * live work for a shelter that is guaranteed to fail before its first placement.
     */
    static boolean hasMaterialsForCurrentPose(AIPlayerEntity bot) {
        if (!canStartAtCurrentPose(bot)) {
            return false;
        }
        Optional<ShelterPlan> plan = planShelter(bot, bot.blockPosition());
        return plan.isPresent()
                && countShelterBlocks(bot) >= requiredShelterBlocks(bot, plan.orElseThrow());
    }
}
