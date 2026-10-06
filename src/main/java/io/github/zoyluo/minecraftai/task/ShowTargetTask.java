package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.PaceOwner;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Physically points an owner to a known location without mining, interacting, or using any
 * hidden-world path. The owner leg may use the verified-owner route, because it is scoped to
 * chunks the server has already loaded. The target leg is otherwise ordinary observed navigation:
 * unseen targets get only short directional Baritone hops until the target is visible again.
 */
public final class ShowTargetTask extends AbstractTask {
    /** The requested player rendezvous distance. */
    static final int OWNER_RADIUS = 5;
    /** The requested final distance from the thing being shown. */
    static final int TARGET_RADIUS = 3;
    /** A directional route is a local, observed Baritone hop rather than a path through unseen terrain. */
    static final int HOP_DISTANCE = 12;
    /** A location from find is at most 64 blocks away; this still leaves room for a moving owner. */
    static final int MAX_TARGET_LEGS = 24;
    /** Bound the whole demonstration so a bad route cannot become a permanent task. */
    static final int MAX_SHOW_TICKS = 3_600;
    /** Exactly five distinct visual gestures, never an attack/mining input. */
    static final int SWING_COUNT = 5;
    /** Four game ticks makes each swing a discrete tap instead of holding an input. */
    static final int SWING_INTERVAL_TICKS = 4;
    private static final int RETRY_DELAY_TICKS = 5;
    /** A blocked visible surface step gets a few re-proofs, but cannot turn into endless movement. */
    private static final int MAX_SURFACE_STEP_FAILURES = 12;
    /** Surface presentation only takes level, observed neighboring cells; it never admits a vertical dive. */
    private static final int[][] SURFACE_STEPS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    /** Vanilla water momentum may carry a surface stroke one cell past its centre before it turns back. */
    private static final int SURFACE_STEP_SETTLING_MARGIN = 1;

    private enum Phase {
        REACH_OWNER,
        REACH_TARGET,
        POINT
    }

    /** Immutable provenance for one guarded surface step. */
    private record SurfaceStepAdmission(BlockPos origin, BlockPos destination, int surfaceY, WalkedStep.Kind kind) {
    }

    private final BlockPos target;
    private final String label;
    private Phase phase;
    private UUID ownerUuid;
    private NavOutcome handledOutcome;
    private int showStartedTick;
    private int nextRouteTick;
    private int targetLegs;
    private int swings;
    private int nextSwingTick;
    /** A water surface directly above the known target; it is a presentation stance, never a dive goal. */
    private BlockPos surfacePresentation;
    /** Exact ownership of the one observed, top-water stroke this task currently drives. */
    private ActionPack.StepLease surfaceStepLease;
    private SurfaceStepAdmission surfaceStepAdmission;
    /** True only while the stationary pointing phase is actively holding a proved water surface. */
    private boolean surfaceHoldActive;
    private int surfaceStepFailures;
    private int nextSurfaceStepTick;

    public ShowTargetTask(BlockPos target, String label) {
        if (target == null) {
            throw new IllegalArgumentException("missing_show_target");
        }
        this.target = target.immutable();
        String cleaned = label == null ? "" : label.trim();
        this.label = cleaned.isBlank() ? "the target" : cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    @Override
    public String name() {
        return "show_location";
    }

    @Override
    public String describe() {
        return "show target=" + target.toShortString() + " label=" + label
                + " phase=" + (phase == null ? "pending" : phase.name().toLowerCase())
                + " hops=" + targetLegs + "/" + MAX_TARGET_LEGS;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase == null ? Phase.REACH_OWNER : phase) {
            case REACH_OWNER -> 0.15D;
            case REACH_TARGET -> Math.min(0.85D, 0.35D + targetLegs / (double) MAX_TARGET_LEGS * 0.45D);
            case POINT -> Math.min(0.98D, 0.90D + swings / (double) SWING_COUNT * 0.08D);
        };
    }

    /** This task owns bounded navigation and its deliberate stationary pointing stage. */
    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        ServerPlayer owner = resolveOwner(bot);
        if (owner == null) {
            cannotShow(bot, "owner_unavailable", "I can't show you that location because I can't find you in this dimension.");
            return;
        }
        ownerUuid = owner.getUUID();
        phase = Phase.REACH_OWNER;
        showStartedTick = bot.level().getServer().getTickCount();
        nextRouteTick = 0;
        targetLegs = 0;
        swings = 0;
        nextSwingTick = 0;
        surfacePresentation = null;
        surfaceStepLease = null;
        surfaceStepAdmission = null;
        surfaceHoldActive = false;
        NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
        surfaceStepFailures = 0;
        nextSurfaceStepTick = 0;
        handledOutcome = null;
        BrainCoordinator.INSTANCE.sendBotReply(bot, "I'll show you where " + label + " is.");
        BotLog.action(bot, "show_location_started", "target", target.toShortString(), "label", label,
                "owner", owner.getGameProfile().name());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        int now = bot.level().getServer().getTickCount();
        if (now - showStartedTick >= MAX_SHOW_TICKS) {
            cannotShow(bot, "time_limit", "I couldn't reach that location in time to show it to you.");
            return;
        }
        switch (phase) {
            case REACH_OWNER -> reachOwner(bot, now);
            case REACH_TARGET -> reachTarget(bot, now);
            case POINT -> pointAtTarget(bot, now);
        }
    }

    /** First leg: a verified owner's live coordinate can be used even if the bot cannot see them. */
    private void reachOwner(AIPlayerEntity bot, int now) {
        ServerPlayer owner = resolveOwner(bot);
        if (owner == null) {
            cannotShow(bot, "owner_unavailable", "I can't show you that location because I can't find you in this dimension.");
            return;
        }
        if (bot.distanceTo(owner) <= OWNER_RADIUS) {
            // Stop the owner route before issuing the final-target Baritone request in this same tick.
            bot.getActionPack().stopNavigation();
            phase = Phase.REACH_TARGET;
            nextRouteTick = now;
            BotLog.action(bot, "show_location_owner_reached", "target", target.toShortString());
            reachTarget(bot, now);
            return;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.hasBaritoneRoute()) {
            return;
        }
        noteEndedRoute(bot);
        if (now < nextRouteTick) {
            return;
        }
        ActionResult started = pack.startOwnerFollowTo(owner.getUUID(), owner.blockPosition(), OWNER_RADIUS, false);
        if (started.isFailed()) {
            nextRouteTick = now + RETRY_DELAY_TICKS;
            BotLog.action(bot, "show_location_owner_route_refused", "reason", started.reason());
            return;
        }
        requestSprintRoute(bot);
        nextRouteTick = now + RETRY_DELAY_TICKS;
        BotLog.path(bot, "show_location_owner_route", "target",
                io.github.zoyluo.minecraftai.log.LogFields.pos(owner.blockPosition()), "radius", OWNER_RADIUS,
                "policy", "owner_loaded_chunks_only");
    }

    /** Second leg: only give Baritone the exact target after a live proof; otherwise hop locally toward it. */
    private void reachTarget(AIPlayerEntity bot, int now) {
        ActionPack pack = bot.getActionPack();
        boolean targetVisible = ObservableWorldQuery.canObserveCell(bot, target);
        BlockPos observedSurface = observedSurfaceWaterAtTargetColumn(bot);
        if (observedSurface != null) {
            surfacePresentation = observedSurface;
        }
        BlockPos presentation = surfacePresentation;
        if (presentation != null && withinSurfacePresentation(bot, presentation)) {
            releaseSurfaceStep(bot, false);
            pack.stopNavigation();
            phase = Phase.POINT;
            nextSwingTick = now;
            BotLog.action(bot, "show_location_surface_reached", "target", target.toShortString(),
                    "surface", presentation.toShortString(), "hops", targetLegs);
            pointAtTarget(bot, now);
            return;
        }
        if (presentation != null && driveSurfacePresentation(bot, presentation, now)) {
            return;
        }
        // A known target under visible water is presented from its surface projection.  Do not
        // let the ordinary three-dimensional GoalNear branch turn that deliberate presentation
        // into a dive merely because the bot happens to be close to the target's bottom cell.
        if (presentation == null && withinTargetRadius(bot)) {
            if (!targetVisible) {
                cannotShow(bot, "target_not_visible", "I reached the last known spot, but I can't see it clearly enough to point it out.");
                return;
            }
            pack.stopNavigation();
            phase = Phase.POINT;
            nextSwingTick = now;
            BotLog.action(bot, "show_location_target_reached", "target", target.toShortString(), "hops", targetLegs);
            pointAtTarget(bot, now);
            return;
        }
        if (pack.hasBaritoneRoute()) {
            return;
        }
        noteEndedRoute(bot);
        if (now < nextRouteTick) {
            return;
        }
        if (targetLegs >= MAX_TARGET_LEGS) {
            cannotShow(bot, "target_route_limit", "I couldn't safely reach that location to point it out.");
            return;
        }
        ActionResult started = startTargetRoute(bot, targetVisible, presentation);
        nextRouteTick = now + RETRY_DELAY_TICKS;
        if (started.isFailed()) {
            targetLegs++;
            BotLog.action(bot, "show_location_target_route_refused", "reason", started.reason(),
                    "target", target.toShortString(), "visible", targetVisible, "leg", targetLegs);
        }
    }

    /**
     * A visible target gets the requested GoalNear(3). A target that vanished from view after
     * the owner rendezvous receives only a short observed directional hop; the next tick can
     * re-prove it and use GoalNear(3).
     */
    private ActionResult startTargetRoute(AIPlayerEntity bot, boolean targetVisible, BlockPos surfaceWater) {
        ActionPack pack = bot.getActionPack();
        // reachTarget owns a visible surface presentation with guarded one-cell surface strokes.
        // Keep this defensive refusal here so no future caller can accidentally fall through to
        // the ordinary three-dimensional GoalNear route for a submerged target.
        if (surfaceWater != null) {
            return ActionResult.failed("surface_presentation_step_required");
        }
        if (targetVisible) {
            ActionResult direct = pack.startApproachTo(target, TARGET_RADIUS, false, false);
            if (!direct.isFailed()) {
                targetLegs++;
                requestSprintRoute(bot);
                BotLog.path(bot, "show_location_target_route", "target",
                        io.github.zoyluo.minecraftai.log.LogFields.pos(target), "radius", TARGET_RADIUS,
                        "policy", "observed_no_break");
                return direct;
            }
            if (!isObservationAdmissionFailure(direct.reason())) {
                return direct;
            }
        }
        ActionResult pursuit = pack.startDirectionalPursuitTo(target, HOP_DISTANCE, false, false);
        if (!pursuit.isFailed()) {
            targetLegs++;
            requestSprintRoute(bot);
            BotLog.path(bot, "show_location_target_hop", "target",
                    io.github.zoyluo.minecraftai.log.LogFields.pos(target), "hop", HOP_DISTANCE,
                    "policy", "observed_no_break");
        }
        return pursuit;
    }

    /** Stop moving, face the actual visible location, then make exactly five harmless animation-only taps. */
    private void pointAtTarget(AIPlayerEntity bot, int now) {
        if (surfacePresentation != null && bot.blockPosition().getY() < surfacePresentation.getY()) {
            releaseSurfaceHold(bot);
            cannotShow(bot, "surface_below_water_level",
                    "I won't dive to show that location, but it's below the water surface here.");
            return;
        }
        boolean presentingFromSurface = surfacePresentation != null
                && withinSurfacePresentation(bot, surfacePresentation);
        if (presentingFromSurface) {
            BlockPos feet = bot.blockPosition();
            SwimRoute.Cell stance = SwimRoute.observedCell(bot, bot.level(), feet, false);
            boolean bobbingAboveSurface = feet.getY() == surfacePresentation.getY() + 1
                    && SwimRoute.observedCell(bot, bot.level(), feet.below(), false)
                    == SwimRoute.Cell.WATER_WITH_AIR_ABOVE;
            if (stance == SwimRoute.Cell.WATER_WITH_AIR_ABOVE) {
                // Do not complete a presentation from water if the high-air, observed hold could
                // not be renewed. That yields cleanly to NavSafetyNet instead of resuming a dive.
                if (!maintainSurfaceHold(bot, surfacePresentation)) {
                    return;
                }
            } else if (stance == SwimRoute.Cell.DRY || bobbingAboveSurface) {
                releaseSurfaceHold(bot);
                if (stance == SwimRoute.Cell.DRY) {
                    NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
                }
            } else {
                releaseSurfaceHold(bot);
                NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
                cannotShow(bot, "surface_stance_lost",
                        "I can't safely stay at the water surface to show that location.");
                return;
            }
        } else {
            releaseSurfaceHold(bot);
        }
        if (!presentingFromSurface && !ObservableWorldQuery.canObserveCell(bot, target)) {
            cannotShow(bot, "target_lost_before_point", "I lost sight of that spot before I could point it out.");
            return;
        }
        if (now < nextSwingTick || bot.isUsingItem()) {
            return;
        }
        LookAction.lookAt(bot, Vec3.atCenterOf(target));
        // swing() is a visual arm animation only. It does not call attack(), mine, use an item,
        // or hold an input, so this cannot break or interact with the target block.
        bot.swing(InteractionHand.MAIN_HAND);
        bot.resetLastActionTime();
        swings++;
        nextSwingTick = now + SWING_INTERVAL_TICKS;
        if (swings >= SWING_COUNT) {
            BotLog.action(bot, "show_location_pointed", "target", target.toShortString(), "swings", SWING_COUNT);
            BrainCoordinator.INSTANCE.sendBotReply(bot, presentingFromSurface ? "It's below here." : "There it is.");
            releaseSurfaceHold(bot);
            NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            complete();
        }
    }

    /** Consume each completed route outcome once for useful log diagnosis, without mistaking an old outcome for a new one. */
    private void noteEndedRoute(AIPlayerEntity bot) {
        NavOutcome outcome = bot.getActionPack().lastRouteOutcome();
        if (outcome == null || outcome == handledOutcome) {
            return;
        }
        handledOutcome = outcome;
        if (!outcome.success()) {
            BotLog.action(bot, "show_location_route_ended", "phase", phase.name().toLowerCase(),
                    "status", outcome.status(), "reason", outcome.reason(), "label", outcome.label());
        }
    }

    private boolean withinTargetRadius(AIPlayerEntity bot) {
        // GoalNear evaluates the bot and target in block-cell coordinates. Keep this completion
        // check identical so a route which is already within its radius cannot complete forever
        // while the task still waits for the entity's feet to reach the block centre.
        return bot.blockPosition().distSqr(target) <= (double) TARGET_RADIUS * TARGET_RADIUS;
    }

    /**
     * Finds an actually visible, breathable water surface in the target's X/Z column.  The target
     * coordinate is already known to this task; this bounded scan only chooses a safe place to
     * stand above it.  Every state read is preceded by its own fluid-transparent sight proof, so
     * a deep-ocean presentation never becomes a heightmap or hidden-world scan.
     */
    private BlockPos observedSurfaceWaterAtTargetColumn(AIPlayerEntity bot) {
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        int minimumY = Math.max(bot.level().getMinY(), bot.blockPosition().getY() - radius);
        int maximumY = Math.min(bot.level().getMaxY() - 1, bot.blockPosition().getY() + radius);
        for (int y = maximumY; y >= minimumY; y--) {
            BlockPos water = new BlockPos(target.getX(), y, target.getZ());
            // A surface below the target cannot describe an underwater presentation of it.
            if (water.getY() <= target.getY()) {
                continue;
            }
            BlockPos air = water.above();
            if (!ObservableWorldQuery.canObserveCellThroughFluids(bot, water)
                    || !ObservableWorldQuery.canObserveCellThroughFluids(bot, air)) {
                continue;
            }
            BlockState waterState = bot.level().getBlockState(water);
            BlockState airState = bot.level().getBlockState(air);
            if (!waterState.getFluidState().is(FluidTags.WATER)) {
                continue;
            }
            // Do not surface under another fluid or a roof. Lava is never a surface shortcut.
            if (!airState.getFluidState().isEmpty()
                    || !airState.getCollisionShape(bot.level(), air).isEmpty()) {
                continue;
            }
            return water.immutable();
        }
        return null;
    }

    /** At the water level (or an adjacent dry bank), horizontally close enough to point down. */
    private static boolean withinSurfacePresentation(AIPlayerEntity bot, BlockPos surfaceWater) {
        BlockPos feet = bot.blockPosition();
        int dx = feet.getX() - surfaceWater.getX();
        int dz = feet.getZ() - surfaceWater.getZ();
        return dx * dx + dz * dz <= TARGET_RADIUS * TARGET_RADIUS
                && feet.getY() >= surfaceWater.getY()
                && feet.getY() <= surfaceWater.getY() + 1;
    }

    /**
     * Drives a water presentation without asking Baritone to model an ocean floor. Each move is
     * one currently observed cell at the top-water Y (or an equally high dry bank), so a deep
     * target remains below the bot rather than becoming a dive route.
     */
    private boolean driveSurfacePresentation(AIPlayerEntity bot, BlockPos surfaceWater, int now) {
        ActionPack pack = bot.getActionPack();
        if (surfaceStepLease != null) {
            if (pack.stepInFlightFor(surfaceStepLease)) {
                // A water-surface lease is renewed only while this exact guarded stroke remains
                // observed, breathable and high-air. A refusal gives NavSafetyNet the next tick.
                if (surfaceStepAdmission != null && surfaceStepAdmission.kind() == WalkedStep.Kind.SWIM
                        && !NavSafetyNet.INSTANCE.renewObservedSurfacePresentationWater(bot,
                        surfaceStepAdmission.destination())) {
                    releaseSurfaceStep(bot, true);
                }
                return true;
            }
            // A safety reflex may have preempted this lease with its own active physical step.
            // It owns the tick until it finishes; never treat its state as a failed surface move.
            if (!pack.stepIdle()) {
                surfaceStepLease = null;
                surfaceStepAdmission = null;
                NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
                return true;
            }
            SurfaceStepAdmission admission = surfaceStepAdmission;
            WalkedStep.Result result = pack.stepResultFor(surfaceStepLease);
            pack.releaseStepLease(surfaceStepLease);
            surfaceStepLease = null;
            surfaceStepAdmission = null;
            if (admission == null || admission.kind() != WalkedStep.Kind.SWIM
                    || result == null || result.failed()) {
                NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            }
            if (result == null || result.failed()) {
                surfaceStepFailures++;
                nextSurfaceStepTick = now + RETRY_DELAY_TICKS;
                BotLog.action(bot, "show_location_surface_step_ended",
                        "status", result == null ? "cancelled" : result.status(),
                        "reason", result == null ? "" : result.reason(),
                        "failures", surfaceStepFailures);
            } else {
                surfaceStepFailures = 0;
                nextSurfaceStepTick = now;
            }
            return true;
        }
        if (pack.hasBaritoneRoute()) {
            // A surface discovery can happen between directional hops. Stop that old route before
            // its next input tick; its three-dimensional target is no longer safe authority.
            pack.stopNavigation();
            NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            return true;
        }
        // A surface presentation must never write a route from lava. NavSafetyNet owns escape
        // inputs while this physical condition persists; wait for it rather than competing.
        if (bot.isInLava() || bot.level().getFluidState(bot.blockPosition()).is(FluidTags.LAVA)) {
            NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            return true;
        }
        if (bot.blockPosition().getY() < surfaceWater.getY()) {
            NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            cannotShow(bot, "surface_below_water_level",
                    "I won't dive to show that location, but it's below the water surface here.");
            return true;
        }
        // Do not trade a dry ledge for an unobserved vertical move just to reach water. A nearby
        // ledge already within the presentation radius is handled above; otherwise fail closed.
        if (bot.blockPosition().getY() != surfaceWater.getY()) {
            surfaceStepFailures++;
            nextSurfaceStepTick = now + RETRY_DELAY_TICKS;
            if (surfaceStepFailures >= MAX_SURFACE_STEP_FAILURES) {
                NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
                cannotShow(bot, "surface_level_unreachable",
                        "I found the water above that location, but I can't safely reach its surface.");
            }
            return true;
        }
        if (now < nextSurfaceStepTick) {
            return true;
        }
        if (startSurfacePresentationStep(bot, surfaceWater)) {
            return true;
        }
        surfaceStepFailures++;
        nextSurfaceStepTick = now + RETRY_DELAY_TICKS;
        if (surfaceStepFailures >= MAX_SURFACE_STEP_FAILURES) {
            cannotShow(bot, "surface_route_blocked",
                    "I found the water above that location, but I can't safely reach its surface.");
        } else {
            BotLog.action(bot, "show_location_surface_step_refused", "surface", surfaceWater.toShortString(),
                    "failures", surfaceStepFailures, "policy", "observed_surface_swim_no_dive");
        }
        return true;
    }

    /** Starts the best directly advancing, observed surface stroke or dry step; it never selects a lower cell. */
    private boolean startSurfacePresentationStep(AIPlayerEntity bot, BlockPos surfaceWater) {
        BlockPos feet = bot.blockPosition();
        int surfaceY = surfaceWater.getY();
        double currentDistance = horizontalDistanceSquared(feet, surfaceWater);
        for (int[] direction : SURFACE_STEPS) {
            BlockPos candidate = new BlockPos(feet.getX() + direction[0], surfaceY, feet.getZ() + direction[1]);
            if (horizontalDistanceSquared(candidate, surfaceWater) >= currentDistance) {
                continue;
            }
            // This helper proves feet and head before it reads either state. Keeping the caller
            // permanently in the observed mode prevents a surface presentation from becoming a
            // hidden ocean-floor search under another profile.
            SwimRoute.Cell observed = SwimRoute.observedCell(bot, bot.level(), candidate, false);
            if (observed == null) {
                continue;
            }
            WalkedStep.Kind kind;
            if (observed == SwimRoute.Cell.WATER_WITH_AIR_ABOVE) {
                kind = WalkedStep.Kind.SWIM;
            } else if (observed == SwimRoute.Cell.DRY) {
                kind = WalkedStep.Kind.FLAT;
            } else {
                // Water with water above it is submerged. Do not use it even when it would move
                // horizontally closer: a surface presentation must retain breathable headroom.
                continue;
            }
            if (kind == null || !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, candidate, kind)
                    || WalkedStep.refusal(bot, candidate, kind) != null) {
                continue;
            }
            if (kind == WalkedStep.Kind.SWIM) {
                if (!NavSafetyNet.INSTANCE.renewObservedSurfacePresentationWater(bot, candidate)) {
                    return false;
                }
            } else {
                // Do not leave a previous water-stroke exemption live while this task is
                // deliberately walking a dry shore cell.
                NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            }
            SurfaceStepAdmission admission = new SurfaceStepAdmission(feet.immutable(), candidate.immutable(),
                    surfaceY, kind);
            // The first shore-to-water stroke is level and its candidate is already proven to
            // have air above it.  Enter it without a ground jump; after water contact the step
            // resumes the usual SWIM depth hold, which prevents the entry arc from sinking below
            // this task's surface-only boundary.
            boolean drySurfaceEntry = kind == WalkedStep.Kind.SWIM
                    && SwimRoute.observedCell(bot, bot.level(), feet, false) == SwimRoute.Cell.DRY;
            WalkedStep surfaceStep = drySurfaceEntry
                    ? WalkedStep.beginObservedSurfaceEntry(bot, candidate, "show_location_surface")
                    : WalkedStep.begin(bot, candidate, kind, "show_location_surface");
            ActionPack.StepLease lease = bot.getActionPack().runStep(
                    surfaceStep,
                    (guardBot, guardedStep) -> canContinueSurfacePresentationStep(guardBot, guardedStep, admission));
            if (lease != null) {
                surfaceStepLease = lease;
                surfaceStepAdmission = admission;
                BotLog.action(bot, "show_location_surface_step", "from", feet.toShortString(),
                        "to", candidate.toShortString(), "kind", kind, "policy", "observed_surface_swim_no_dive");
                return true;
            }
            if (kind == WalkedStep.Kind.SWIM) {
                NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            }
            return false;
        }
        return false;
    }

    /** Re-proves each active step before its next input tick and rejects any physics sink below the surface. */
    private static boolean canContinueSurfacePresentationStep(AIPlayerEntity bot, WalkedStep step,
                                                              SurfaceStepAdmission admission) {
        BlockPos feet = bot.blockPosition();
        if (!step.cell().equals(admission.destination()) || step.kind() != admission.kind()) {
            return denySurfaceStep(bot, step, admission, "provenance");
        }
        if (feet.getY() < admission.surfaceY()) {
            return denySurfaceStep(bot, step, admission, "below_surface");
        }
        if (feet.getY() > admission.surfaceY() + 1) {
            return denySurfaceStep(bot, step, admission, "above_surface");
        }
        if (!withinSurfaceStepContinuationEnvelope(feet, admission.origin(), admission.destination())) {
            return denySurfaceStep(bot, step, admission, "outside_settling_envelope");
        }
        if (bot.isInLava() || bot.level().getFluidState(feet).is(FluidTags.LAVA)) {
            return denySurfaceStep(bot, step, admission, "lava");
        }
        if (!isSafeSurfaceStance(bot, feet, admission.surfaceY())) {
            return denySurfaceStep(bot, step, admission, "current_stance_changed");
        }
        SwimRoute.Cell observed = SwimRoute.observedCell(bot, bot.level(), step.cell(), false);
        if (observed == null || (admission.kind() == WalkedStep.Kind.SWIM
                ? observed != SwimRoute.Cell.WATER_WITH_AIR_ABOVE
                : observed != SwimRoute.Cell.DRY)) {
            return denySurfaceStep(bot, step, admission,
                    observed == null ? "destination_unobserved" : "destination_changed");
        }
        // WalkedStep itself validates the live terrain after this guard. Do not call refusal()
        // here: it correctly rejects a zero-offset move, but this guard runs just before
        // WalkedStep sees that a successful step has arrived in its destination cell.
        if (!SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, step.cell(), step.kind())) {
            return denySurfaceStep(bot, step, admission, "refusal_envelope");
        }
        return true;
    }

    private static boolean denySurfaceStep(AIPlayerEntity bot, WalkedStep step,
                                           SurfaceStepAdmission admission, String reason) {
        BotLog.action(bot, "show_location_surface_guard_denied", "reason", reason,
                "feet", bot.blockPosition().toShortString(), "from", admission.origin().toShortString(),
                "to", admission.destination().toShortString(), "kind", step.kind());
        return false;
    }

    /**
     * Keeps a physical stroke close to its originally admitted edge while allowing the one-cell
     * momentum overshoot that vanilla swimming produces before WalkedStep turns back to centre.
     */
    private static boolean withinSurfaceStepContinuationEnvelope(BlockPos feet, BlockPos origin, BlockPos destination) {
        return betweenWithMargin(feet.getX(), origin.getX(), destination.getX(), SURFACE_STEP_SETTLING_MARGIN)
                && betweenWithMargin(feet.getZ(), origin.getZ(), destination.getZ(), SURFACE_STEP_SETTLING_MARGIN);
    }

    private static boolean betweenWithMargin(int value, int first, int second, int margin) {
        return value >= Math.min(first, second) - margin && value <= Math.max(first, second) + margin;
    }

    /** Every physical location in the settling envelope must still be dry or breathable top water. */
    private static boolean isSafeSurfaceStance(AIPlayerEntity bot, BlockPos feet, int surfaceY) {
        SwimRoute.Cell current = SwimRoute.observedCell(bot, bot.level(), feet, false);
        if (current == SwimRoute.Cell.DRY || current == SwimRoute.Cell.WATER_WITH_AIR_ABOVE) {
            return true;
        }
        // A held swim stroke may crest into the air cell just above the proven water surface.
        // On the first shore-to-water stroke, that air cell can still be over the dry bank; it
        // is equally safe because it is above the surface, inside the admitted one-cell envelope,
        // and the destination remains re-proven as breathable top water.
        if (feet.getY() != surfaceY + 1) {
            return false;
        }
        SwimRoute.Cell below = SwimRoute.observedCell(bot, bot.level(), feet.below(), false);
        return below == SwimRoute.Cell.DRY || below == SwimRoute.Cell.WATER_WITH_AIR_ABOVE;
    }

    /** Maintains buoyancy while the bot points from a visible, breathable top-water cell. */
    private boolean maintainSurfaceHold(AIPlayerEntity bot, BlockPos surfaceWater) {
        BlockPos feet = bot.blockPosition();
        if (feet.getY() < surfaceWater.getY() || bot.isInLava()
                || bot.level().getFluidState(feet).is(FluidTags.LAVA)) {
            releaseSurfaceHold(bot);
            NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
            return false;
        }
        if (feet.getY() != surfaceWater.getY()
                || SwimRoute.observedCell(bot, bot.level(), feet, false)
                != SwimRoute.Cell.WATER_WITH_AIR_ABOVE
                || !NavSafetyNet.INSTANCE.renewObservedSurfacePresentationWater(bot, feet)) {
            releaseSurfaceHold(bot);
            return false;
        }
        ActionPack pack = bot.getActionPack();
        pack.setForward(0.0F);
        pack.setStrafing(0.0F);
        pack.setJumping(bot.getY() < surfaceWater.getY() + WalkedStepRules.SWIM_HOLD_DEPTH);
        surfaceHoldActive = true;
        return true;
    }

    /** Releases only this task's stationary water-surface input. */
    private void releaseSurfaceHold(AIPlayerEntity bot) {
        if (!surfaceHoldActive) {
            return;
        }
        ActionPack pack = bot.getActionPack();
        pack.setForward(0.0F);
        pack.setStrafing(0.0F);
        pack.setJumping(false);
        surfaceHoldActive = false;
    }

    private static double horizontalDistanceSquared(BlockPos from, BlockPos to) {
        long dx = (long) from.getX() - to.getX();
        long dz = (long) from.getZ() - to.getZ();
        return (double) dx * dx + (double) dz * dz;
    }

    private ServerPlayer resolveOwner(AIPlayerEntity bot) {
        UUID expected = ownerUuid;
        if (expected == null) {
            expected = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        }
        UUID ownerId = expected;
        if (ownerId == null || AIPlayerManager.INSTANCE.ownerOf(bot).filter(ownerId::equals).isEmpty()) {
            return null;
        }
        ServerPlayer owner = bot.level().getServer().getPlayerList().getPlayer(ownerId);
        return owner != null && owner.level() == bot.level() ? owner : null;
    }

    /** Route leases give Baritone permission to sprint while preserving its collision/hunger safety checks. */
    private static void requestSprintRoute(AIPlayerEntity bot) {
        if (MinecraftAiConfig.get().behaviour().paceOrDefaults().paceEnabled()) {
            bot.getActionPack().requestRoutePace(Gait.SPRINT, PaceOwner.TASK);
        } else {
            // This mirrors the legacy no-pace-policy behaviour. The Baritone input bridge still
            // decides whether sprinting is safe on the current movement tick.
            bot.getActionPack().setSprinting(true);
        }
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

    private void cannotShow(AIPlayerEntity bot, String reason, String message) {
        releaseSurfaceHold(bot);
        releaseSurfaceStep(bot, true);
        NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
        bot.getActionPack().stopNavigation();
        BotLog.action(bot, "show_location_failed", "target", target.toShortString(), "reason", reason);
        BrainCoordinator.INSTANCE.sendBotReply(bot, message);
        fail("show_location:" + reason);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        releaseSurfaceHold(bot);
        releaseSurfaceStep(bot, true);
        NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
        super.onPause(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        releaseSurfaceHold(bot);
        releaseSurfaceStep(bot, true);
        NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
        super.onAbort(bot);
    }

    /** Releases this task's exact guarded stroke before any generic cancellation can leave its fence behind. */
    private void releaseSurfaceStep(AIPlayerEntity bot, boolean cancel) {
        ActionPack.StepLease lease = surfaceStepLease;
        if (lease == null) {
            return;
        }
        ActionPack pack = bot.getActionPack();
        if (cancel || pack.stepInFlightFor(lease)) {
            pack.cancelStep(lease);
        } else {
            pack.releaseStepLease(lease);
        }
        surfaceStepLease = null;
        surfaceStepAdmission = null;
        if (cancel) {
            NavSafetyNet.INSTANCE.clearObservedSurfacePresentationWater(bot);
        }
    }
}
