package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.baritone.BaritoneNavigator;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavigationMeasurement;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.navigation.NavRouteRules;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.pathfinding.FailureReason;
import io.github.zoyluo.minecraftai.pathfinding.PathExecutor;
import io.github.zoyluo.minecraftai.pathfinding.PathfindingResult;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class ActionPack {
    /** Failure reason of a request identical to the previous one still inside its cooldown. */
    public static final String PATHFINDING_THROTTLED = "pathfinding_throttled";
    /** A strict guarded step has not yet been reconciled by its exact owner. */
    public static final String GUARDED_STEP_FENCE = "guarded_step_fence";
    private static final int PATHFIND_SUCCESS_COOLDOWN_TICKS = 5;
    private static final int PATHFIND_FAILURE_COOLDOWN_TICKS = 20;
    // NAV-OPT two-phase pathfinding budget: pure walking only searches air cells (small search
    // space, so give it a generous allowance); the dig-through cap is smaller, to contain the 3D
    // volume search blowing up when trapped/underground.
    private static final int WALK_MAX_NODES = 10_000;
    private static final int DIG_MAX_NODES = 4_000;
    // Large budget dedicated to the approach primitive: approaching ore enclosed in stone
    // necessarily requires digging, so it goes straight to DIG with an enlarged budget (the
    // digging neighbor branching factor is small, so 24k nodes covers ~40 blocks of direct
    // through-mountain travel; the small-budget DIG in ordinary startPathTo is only a walking
    // fallback, and its semantics are unchanged).
    private static final int DIG_APPROACH_MAX_NODES = 24_000;
    private static final long PATHFIND_MAX_MILLIS = 50L;

    /**
     * Opaque proof that identifies one exact {@link #runStep(WalkedStep, WalkedStep.ContinuationGuard)}
     * admission. A continuation-guarded step keeps its fence until its owner releases this lease,
     * so another controller cannot swap in an unguarded step between the guard's last proof and
     * the owner's next reconciliation tick.
     */
    public static final class StepLease {
        private StepLease() {
        }
    }

    private final AIPlayerEntity player;

    private float forward;
    private float strafing;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    private int jumpTicks;

    private WalkToController walkTo;
    private MiningController mining;
    /** Vanilla client destroyDelay: ticks a finished multi-tick break makes the next one wait (see {@link #tickMining}). */
    static final int DESTROY_DELAY_TICKS = 5;
    /** Game time before which the running mining controller does nothing (the post-break delay); 0 when none. */
    private long nextBreakAt;
    private PathExecutor pathExecutor;
    /** The input-driven step this pack runs (see {@link WalkedStep}); it has the bot to itself while it is in flight. */
    private WalkedStep step;
    /** Lease of {@link #step}, cleared as soon as the active step ends or is cancelled. */
    private StepLease activeStepLease;
    /** Exact lease whose natural completion produced {@link #lastStepResult}, if any. */
    private StepLease completedStepLease;
    /**
     * Continuation-guard fence retained across a generic cancellation or a natural completion
     * until the original owner explicitly consumes/releases it. See {@link StepLease}.
     */
    private StepLease guardedStepLease;
    /**
     * A live NavSafetyNet suffocation step is a guarded step with one extra property: ordinary
     * controller shutdown must not zero its already-issued physical inputs between entity ticks.
     * Its opaque lease is still released only through the normal exact-owner APIs, or replaced by
     * a later {@link #preemptGuardedStepForEmergency()}.
     */
    private StepLease emergencyStepLease;
    /** True only while the guarded step itself is issuing its already-proved vanilla inputs. */
    private boolean tickingGuardedStep;
    private WalkedStep.Result lastStepResult;
    private PathRequestIdentity lastPathRequest;
    private PathRequestIdentity activePathRequest;
    private BlockPos activePathGoal;
    private int nextPathfindTick;
    private final SnapRepeatGuard physicalSnapGuard = new SnapRepeatGuard();
    // The route Baritone executes for this pack (engine=baritone), and how the previous one ended. Never set with the legacy
    // engine; nothing below touches a Baritone class while it is null.
    private NavRoute route;
    private NavOutcome lastRouteOutcome;

    // Pace (see PacePolicy): leases and ceilings requested by tasks, the per-bot policy memory and quiet-zone cache. None of this
    // counts as an active action and none of it claims the bot from Baritone.
    private final PacePolicy.Lease[] tickLeases = new PacePolicy.Lease[PaceOwner.values().length];
    private PacePolicy.Lease routeLease;
    private Gait capGait;
    private long capUntil;
    private String capReason = "";
    private long controllerInputTick = Long.MIN_VALUE;
    private final PacePolicy.State paceState = new PacePolicy.State();
    private final QuietZone quietZone = new QuietZone();
    // What the enforcer wrote on the last controller-driven tick (the walkers scale their progress limits with the input scale).
    private float lastInputScale = 1.0F;
    private Gait lastPaceGait = Gait.SPRINT;
    private DeadlineCredit deadlineCredit = new DeadlineCredit(0);
    private double lastClockWeight = 1.0D;

    public ActionPack(AIPlayerEntity player) {
        this.player = player;
    }

    public AIPlayerEntity player() {
        return player;
    }

    /**
     * Single-writer hand-over with Baritone (see {@code BaritoneDriver}): while Baritone drives the bot, this executor is idle; an
     * order that would make it act (start a walk, a path, mining, hold an input) first takes the bot back, which stops
     * Baritone and releases the inputs it wrote. Releasing (stop, zero, false) never takes the bot.
     */
    private void claim(String why) {
        releaseBaritone(why);
    }

    /**
     * A continuation guard is a pre-terrain-read safety proof, not merely task bookkeeping. Until
     * its opaque lease is reconciled, no unrelated controller may begin planning or take a
     * Baritone handoff in the gap left by a generic cancellation.
     */
    private boolean controllerStartBlocked() {
        return guardedStepLease != null;
    }

    /**
     * Read-only admission boundary for ordinary task controllers. A returned {@code true} means
     * a guarded physical-step owner has retained the handoff fence, so a nullable helper result
     * must be treated as an unstarted retry rather than as a terrain refusal.
     */
    public boolean stepAdmissionBlocked() {
        return controllerStartBlocked();
    }

    /**
     * Read-only admission boundary for the Baritone integration. A guarded physical step has
     * already proved its next terrain revalidation, so a Baritone process must not start (or
     * keep applying its direct bridge inputs) until that exact step owner reconciles its lease.
     * The only priority override is {@link #preemptGuardedStepForEmergency()}, used by
     * {@code NavSafetyNet} before an actual lava or suffocation escape.
     */
    public boolean baritoneControlBlocked() {
        return controllerStartBlocked();
    }

    /** Zero/false releases remain safe; nonzero inputs belong only to the guarded step's own tick. */
    private boolean nonzeroInputBlocked() {
        return guardedStepLease != null && !tickingGuardedStep;
    }

    /**
     * Unlike an ordinary guarded step, a live emergency successor cannot be stopped by a generic
     * pause/stop call between its ActionPack update and the next vanilla physics tick. Its exact
     * NavSafetyNet owner and a later emergency preemption deliberately bypass this boundary.
     */
    private boolean emergencyInputBlocked() {
        return step != null && activeStepLease != null && activeStepLease == emergencyStepLease
                && !tickingGuardedStep;
    }

    /**
     * Whatever Baritone is doing for this bot stops: the route this pack started (recorded as cancelled) or, for a caller that
     * drives Baritone directly, its goal and path. Nothing Baritone-related is touched while Baritone has never been initialised.
     */
    private void releaseBaritone(String why) {
        if (route != null) {
            cancelBaritoneRoute(why);
        } else {
            NavEngineSelector.hook("baritone_preempt", () -> BaritoneRegistry.INSTANCE.preempt(player, why));
        }
    }

    /**
     * Baritone takes the bot over: everything this executor was doing is dropped without a trace of it left in the inputs, so
     * the two never write at once. Called by {@code BaritoneDriver} on the first tick it drives the bot.
     */
    public void yieldToBaritone() {
        if (controllerStartBlocked()) {
            return;
        }
        dropPathExecutor(); // keeps the route lease (requested after the route was started)
        cancelStep();
        stopMining();
        this.walkTo = null;
        stopMovement();
    }

    public void setForward(float value) {
        if (emergencyInputBlocked()) {
            return;
        }
        if (value != 0.0F) {
            if (nonzeroInputBlocked()) {
                return;
            }
            claim("set_forward");
        }
        this.forward = clampInput(value);
    }

    public void setStrafing(float value) {
        if (emergencyInputBlocked()) {
            return;
        }
        if (value != 0.0F) {
            if (nonzeroInputBlocked()) {
                return;
            }
            claim("set_strafing");
        }
        this.strafing = clampInput(value);
    }

    /**
     * Sneak and sprint are pace requests, not movement: while a Baritone route (or a direct Baritone caller) drives this bot they only
     * record the flag (the input bridge reads it as the task's wish), they do not take the bot over and so cannot cancel the route
     * the caller has just started. Held keys, walks, paths and mining still claim.
     */
    private boolean baritoneOwnsBot() {
        return route != null
                || NavEngineSelector.query("baritone_busy", () -> BaritoneRegistry.INSTANCE.isBusy(player), false);
    }

    public void setSneaking(boolean sneaking) {
        if (emergencyInputBlocked()) {
            return;
        }
        if (sneaking && nonzeroInputBlocked()) {
            return;
        }
        if (sneaking && !baritoneOwnsBot()) {
            claim("set_sneaking");
        }
        this.sneaking = sneaking;
        player.setShiftKeyDown(sneaking);
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }

    public void setSprinting(boolean sprinting) {
        if (emergencyInputBlocked()) {
            return;
        }
        if (sprinting && nonzeroInputBlocked()) {
            return;
        }
        if (sprinting && !baritoneOwnsBot()) {
            claim("set_sprinting");
        }
        this.sprinting = sprinting;
        player.setSprinting(sprinting);
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }

    public void setJumping(boolean jumping) {
        if (emergencyInputBlocked()) {
            return;
        }
        if (jumping) {
            if (nonzeroInputBlocked()) {
                return;
            }
            claim("set_jumping");
        }
        this.jumping = jumping;
    }

    public void jumpOnce() {
        if (emergencyInputBlocked()) {
            return;
        }
        if (nonzeroInputBlocked()) {
            return;
        }
        claim("jump_once");
        this.jumpTicks = 2;
    }

    // ==================== Pace (see PacePolicy) ====================
    // Requests about HOW the bot moves, not movement: none of them claims the bot from Baritone and none counts in hasActiveActions.

    /**
     * A TICK lease: {@code gait} is wanted for the next {@value PacePolicy.Lease#TICK_LEASE_TICKS} game ticks (renew it every time the
     * task ticks; it survives the TaskManager's 1-in-5 throttle). The highest-priority valid lease decides the gait of controller-driven
     * travel; see {@link PacePolicy} for what still overrules it.
     */
    public void requestPace(Gait gait, PaceOwner owner) {
        long now = player.level().getGameTime();
        this.tickLeases[owner.ordinal()] = PacePolicy.Lease.tick(gait, owner, now);
    }

    /**
     * A ROUTE lease: {@code gait} for the whole route that is running now. Ended only by {@link #stopAll}, {@link #stopNavigation},
     * the start of a new path/walk/route and the settling (complete or failed) of the route; {@link #stopMovement} does not end it
     * (the walkers call that at every sub-target). Request it AFTER starting the route: starting one ends the previous lease.
     */
    public void requestRoutePace(Gait gait, PaceOwner owner) {
        this.routeLease = PacePolicy.Lease.route(gait, owner);
    }

    /**
     * A per-tick hard ceiling: the gait is at most {@code max} on this tick and the next, whoever asks for more (the legacy executor
     * caps to a walk on jump, drop, pillar, bridge and dig nodes; raw-input drivers use it for their steps). Ceilings combine to the
     * lowest.
     */
    public void capPace(Gait max, String reason) {
        long now = player.level().getGameTime();
        if (capGait != null && capUntil > now) {
            capGait = Gait.min(capGait, max);
        } else {
            capGait = max;
        }
        capReason = reason == null ? "" : reason;
        capUntil = now + 2;
    }

    /**
     * A raw-input driver (it writes {@code setForward}/{@code setStrafing} itself, like the walked combat step) calls this every
     * tick it drives, so the legacy enforcer applies the pace and the vanilla rules to its keys this tick.
     */
    public void markControllerInput() {
        this.controllerInputTick = player.level().getGameTime();
    }

    /** The gait of the winning valid lease (tick or route), or null. */
    public Gait leasedGait() {
        PacePolicy.Lease lease = leaseAt(player.level().getGameTime());
        return lease == null ? null : lease.gait();
    }

    /** The owner of the winning valid lease, or null. */
    public PaceOwner leasedOwner() {
        PacePolicy.Lease lease = leaseAt(player.level().getGameTime());
        return lease == null ? null : lease.owner();
    }

    /** The task asked for sprinting ({@link #setSprinting}). */
    public boolean sprintRequested() {
        return sprinting;
    }

    /** The task asked for sneaking ({@link #setSneaking}). */
    public boolean sneakRequested() {
        return sneaking;
    }

    /** Drops every lease and ceiling and forgets the hysteresis of the policy. */
    public void clearPace() {
        java.util.Arrays.fill(tickLeases, null);
        routeLease = null;
        capGait = null;
        capUntil = 0L;
        capReason = "";
        controllerInputTick = Long.MIN_VALUE;
        paceState.reset();
        quietZone.invalidate();
        lastInputScale = 1.0F;
        lastClockWeight = 1.0D;
    }

    /** The winning valid lease at {@code now} (package-visible: the policy reads it). */
    PacePolicy.Lease leaseAt(long now) {
        PacePolicy.Lease best = routeLease != null && routeLease.validAt(now) ? routeLease : null;
        for (PacePolicy.Lease lease : tickLeases) {
            best = PacePolicy.Lease.best(lease, best, now);
        }
        return best;
    }

    /** The active ceiling at {@code now}, or null. */
    Gait capAt(long now) {
        return capGait != null && now < capUntil ? capGait : null;
    }

    /** Why the current ceiling was set (for logs and tests). */
    public String capReason() {
        return capReason;
    }

    PacePolicy.State paceState() {
        return paceState;
    }

    QuietZone quietZone() {
        return quietZone;
    }

    /** The gait the enforcer applied on its last controller-driven tick (SPRINT before the first). */
    public Gait lastPaceGait() {
        return lastPaceGait;
    }

    /** The input scale the enforcer applied on its last controller-driven tick (sneak 0.3, item use 0.2, both multiply). */
    public float lastInputScale() {
        return lastInputScale;
    }

    /**
     * How much of a tick the controllers count for their own time limits: the slower the bot deliberately moves, the less "late"
     * it is. Sneaking counts 1/4.4 and walking 1/1.3 of a tick against a sprint's 1, and using an item scales it further (input 0.2).
     */
    public double paceClockWeight() {
        return lastClockWeight;
    }

    /**
     * What an enforcer (legacy or Baritone bridge) really applied this tick: the gait the bot moves at, the input scale and whether the
     * item-use slowdown is part of it. The walkers scale their limits by it, and a route's deadline moves out for a slow tick.
     */
    public void noteEnforced(Gait actual, float inputScale, boolean itemSlowdown) {
        this.lastInputScale = inputScale;
        this.lastClockWeight = actual.clockWeight() * (itemSlowdown ? PaceRules.USE_ITEM_SCALE : 1.0F);
        notePaceTick(actual);
    }

    /**
     * Records that a route ran a tick at {@code gait} (Baritone bridge and legacy enforcer): the route's deadline moves out by the
     * part of the tick a slow gait does not count, so a bot that deliberately sneaks past a sculk sensor is not timed out for it. The
     * total credit of a route is capped ({@link DeadlineCredit}): a long SNEAK lease still meets its deadline when the bot is stuck.
     */
    public void notePaceTick(Gait gait) {
        this.lastPaceGait = gait;
        NavRoute current = route;
        if (current == null || gait == Gait.SPRINT) {
            return;
        }
        int whole = deadlineCredit.note(gait);
        if (whole > 0) {
            current.setDeadlineTick(current.deadlineTick() + whole);
        }
    }

    /**
     * A Baritone request that did not start a route (rejected, or threw) with no Baritone route of this pack behind it: the legacy route
     * that {@link #yieldToBaritone} dropped just before the admission (it keeps the route lease so a route that DOES start owns it)
     * has nothing left to own the lease, so a clockless ROUTE lease of the old route would stay in force for a bot that is idle. The
     * legacy route is over either way, so its lease is too. (Only this pack's own lease: a tick lease of a task is untouched.)
     */
    private void dropStaleRouteLease() {
        if (pathExecutor == null && walkTo == null && step == null) {
            clearRouteLease();
        }
    }

    /** The route lease ends with the route it was requested for. */
    private void clearRouteLease() {
        this.routeLease = null;
    }

    /** Horizontal distance to the goal of the path, walk or route that is running, or NaN when there is none. */
    private double goalDistance() {
        double gx;
        double gz;
        if (walkTo != null) {
            gx = walkTo.target().x;
            gz = walkTo.target().z;
        } else {
            BlockPos goal = activePathGoal();
            if (goal == null) {
                return Double.NaN;
            }
            gx = goal.getX() + 0.5D;
            gz = goal.getZ() + 0.5D;
        }
        double dx = gx - player.getX();
        double dz = gz - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Legacy enforcer: the pace policy and the vanilla rules applied to the keys of controller-driven movement (see
     * {@link PacePolicy}). Runs in {@link #onUpdate} after the controllers have written their keys.
     */
    private void enforcePace(MinecraftAiConfig.Pace config) {
        Gait gait = PacePolicy.resolve(player, goalDistance(), true);
        WalkToController walker = walkTo != null ? walkTo : pathExecutor != null ? pathExecutor.activeWalker() : null;
        boolean edge = (pathExecutor != null && pathExecutor.onEdgeDescentNode())
                || (step != null && step.descends()) || player.onClimbable();
        boolean effectiveSneak = sneaking || (gait == Gait.SNEAK && !edge);
        boolean geometry = walker == null || walker.geometryAllowsSprint();
        boolean effectiveSprint = gait == Gait.SPRINT && geometry && PaceRules.sprintAllowed(player, forward, effectiveSneak);
        player.setShiftKeyDown(effectiveSneak);
        player.setSprinting(effectiveSprint);
        float scale = PaceRules.inputScale(effectiveSneak, player.isUsingItem());
        player.zza = forward * scale;
        player.xxa = strafing * scale;
        noteEnforced(effectiveSneak ? Gait.SNEAK : effectiveSprint ? Gait.SPRINT : Gait.WALK, scale,
                player.isUsingItem());
    }

    private boolean controllerDriven() {
        // A raw-input driver marks its tick from a task tick, which may run just before or just after this bot's own tick.
        return pathExecutor != null || walkTo != null || step != null
                || (controllerInputTick != Long.MIN_VALUE && player.level().getGameTime() - controllerInputTick <= 1L);
    }

    public ActionResult startWalkTo(Vec3 target) {
        return startWalkTo(target, 0.6D);
    }

    /** Starts a direct walk with a caller-defined horizontal arrival tolerance. */
    public ActionResult startWalkTo(Vec3 target, double arrivalThreshold) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        claim("walk_to");
        logEngine("walk_to", BlockPos.containing(target), NavEngine.LEGACY, "straight_line_walk");
        clearActivePathExecutor();
        clearRouteLease();
        this.walkTo = new WalkToController(target, arrivalThreshold);
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    // Unified entry point for the approach primitive: dig-aware pathfinding (large-budget DIG
    // straight to the goal; the goal may be a solid cell that is "dug open, then stood on" -- see
    // the dig-endpoint exemption in AStarPathfinder.resolveEndpoint). Use this for approaching ore
    // enclosed in stone / going straight through a mountain; ordinary walking still uses
    // startPathTo (WALK first, then small-budget DIG).
    public ActionResult startDigPathTo(BlockPos goal) {
        return startDigPathTo(goal, 0);
    }

    /**
     * Starts a digging approach while preserving a caller-scoped mining-stone reserve.
     * The same reserve gates initial pillar planning, physical pillar execution and replanning.
     */
    public ActionResult startDigPathTo(BlockPos goal, int protectedStoneLikeReserve) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        claim("dig_path_to");
        logEngine("dig_path_to", goal, NavEngine.LEGACY, "dig_approach");
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int now = player.level().getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        boolean canPillar = PathExecutor.hasPlaceableBlock(player, reserve);
        PathRequestIdentity request = new PathRequestIdentity(
                immutableGoal, canPillar, true, reserve,
                PathExecutor.RouteContract.unrestricted());
        if (preparePathRequest(request, now)) {
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        if (!snapPlayerToNearestStandable("path_start_invalid")) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        WalkedStep startStep = takeStartStep();
        PathfindingResult result = new AStarPathfinder(player, player.level(), startCell(), goal,
                DIG_APPROACH_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true, 10.0D).findPath();
        if (!result.success()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathRequest = request;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        activePathRequest = request;
        this.pathExecutor = new PathExecutor(
                result.path(), resolvedGoal, canPillar, true, reserve);
        this.pathExecutor.prefixStep(startStep);
        commitPlannedPhysicalSnap(startStep);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startPathTo(BlockPos goal) {
        return startPathTo(goal, 0);
    }

    /**
     * Starts an ordinary route while preserving a caller-scoped mining-stone reserve.
     * Non-reserve path supports remain eligible; protected stone is exposed only above the
     * requested floor.
     */
    public ActionResult startPathTo(BlockPos goal, int protectedStoneLikeReserve) {
        int reserve = Math.max(0, protectedStoneLikeReserve);
        return startPathTo(
                goal, PathExecutor.hasPlaceableBlock(player, reserve), true, reserve);
    }

    /**
     * Starts a surface-exploration path without digging or disposable pillar shortcuts.
     * Hunt/Gather roaming must be able to keep moving after it reaches a waypoint; a path that
     * spends the last few dirt blocks pillaring out of a depression is not a reusable surface route.
     */
    public ActionResult startSurfacePathTo(BlockPos goal) {
        return startPathTo(goal, false, false, 0);
    }

    /**
     * Starts a no-dig/no-pillar surface route that must remain at or above {@code minimumY}.
     * This overload does not require a round-trip proof.
     */
    public ActionResult startSurfacePathTo(BlockPos goal, int minimumY) {
        return startSurfacePathTo(goal, minimumY, null);
    }

    /**
     * Starts a contract-bound surface route. A non-null {@code returnAnchor} additionally requires
     * an exact no-dig/no-pillar route from the requested goal back to that anchor under the same
     * Y floor and search budget.
     */
    public ActionResult startSurfacePathTo(
            BlockPos goal, int minimumY, BlockPos returnAnchor) {
        return startPathTo(goal, false, false, 0,
                PathExecutor.RouteContract.constrainedSurface(minimumY, returnAnchor));
    }

    /**
     * Contract-bound surface route whose outbound leg may dig through obstacles once the
     * walk-only phase has no solution (a hunter digs a stair through a hill instead of
     * rejecting the whole herd). The dig fallback stays above the same Y floor, and the
     * return proof from the goal remains strictly walk-only: a dug stair is itself
     * walk-only returnable, while a goal whose return needs fresh digging is still rejected.
     */
    public ActionResult startSurfaceDigFallbackPathTo(
            BlockPos goal, int minimumY, BlockPos returnAnchor) {
        return startPathTo(goal, false, true, 0,
                PathExecutor.RouteContract.constrainedSurface(minimumY, returnAnchor));
    }

    private ActionResult startPathTo(BlockPos goal, boolean canPillar,
                                     boolean allowDigFallback,
                                     int protectedStoneLikeReserve) {
        return startPathTo(goal, canPillar, allowDigFallback, protectedStoneLikeReserve,
                PathExecutor.RouteContract.unrestricted());
    }

    private ActionResult startPathTo(BlockPos goal, boolean canPillar,
                                     boolean allowDigFallback,
                                     int protectedStoneLikeReserve,
                                     PathExecutor.RouteContract routeContract) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        // Engine seam: with nav.engine=baritone an ordinary walk (not a contract route) is Baritone's. A null answer means
        // "not routed" (legacy engine, contract route, or Baritone failed to initialise) and the legacy code below carries on.
        ActionResult routed = routeOnBaritone("path_to", goal, canPillar, allowDigFallback, protectedStoneLikeReserve, routeContract);
        if (routed != null) {
            return routed;
        }
        claim("path_to");
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int now = player.level().getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        PathRequestIdentity request = new PathRequestIdentity(
                immutableGoal, canPillar, allowDigFallback, reserve, routeContract);
        if (preparePathRequest(request, now)) {
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        if (routeContract.constrained()
                && player.blockPosition().getY() < routeContract.minimumY()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("path_contract_failed: start_below_minimum_y");
        }
        boolean startReady = routeContract.constrained()
                ? recenterPlayerInCurrentStandableCell("path_start_invalid")
                : snapPlayerToNearestStandable("path_start_invalid");
        if (!startReady) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        ServerLevel world = player.level();
        WalkedStep startStep = routeContract.constrained() ? null : takeStartStep();
        BlockPos from = routeContract.constrained() ? player.blockPosition() : startCell();
        // NAV-OPT two-phase pathfinding: try pure walking first (no digging allowed, search
        // space = air cells, so it converges fast and won't be blown out to SEARCH_LIMIT by
        // dig-through neighbors); only if pure walking has no solution do we allow the dig-through
        // fallback (tunneling/breaking obstacles), with a smaller dig budget to bound the 3D
        // volume search blowing up when trapped/underground.
        AStarPathfinder walkFinder =
                new AStarPathfinder(
                        player, world, from, goal, WALK_MAX_NODES, PATHFIND_MAX_MILLIS,
                        canPillar, false);
        PathfindingResult result = routeContract.constrained()
                ? walkFinder.findPathUncachedAtOrAbove(routeContract.minimumY())
                : walkFinder.findPath();
        boolean dugOutbound = false;
        if (!result.success() && allowDigFallback) {
            AStarPathfinder digFinder = new AStarPathfinder(
                    player, world, from, goal, DIG_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true);
            PathfindingResult dig = routeContract.constrained()
                    ? digFinder.findPathUncachedAtOrAbove(routeContract.minimumY())
                    : digFinder.findPath();
            if (dig.success()) {
                result = dig;
                dugOutbound = true;
            }
        }
        if (!result.success()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        PathfindingResult returnProof = null;
        if (routeContract.requiresReturnProof()) {
            if (dugOutbound && PathExecutor.isReversibleStair(result)) {
                // The pre-dig world cannot prove the walk-only return yet; the dug stair is
                // itself that return, so derive the proof from the reversed outbound nodes.
                java.util.List<io.github.zoyluo.minecraftai.pathfinding.Node> reversed =
                        new java.util.ArrayList<>(result.path());
                java.util.Collections.reverse(reversed);
                returnProof = new PathfindingResult(
                        reversed, true, FailureReason.NONE,
                        result.nodesExplored(), result.elapsedMs(),
                        result.resolvedGoal(), result.resolvedStart());
            } else {
                returnProof = new AStarPathfinder(
                        player, world, immutableGoal, routeContract.returnAnchor(),
                        WALK_MAX_NODES, PATHFIND_MAX_MILLIS, false, false)
                        .findPathUncachedAtOrAbove(routeContract.minimumY());
            }
        }
        PathExecutor.RouteValidation validation = PathExecutor.validateRouteContract(
                result, immutableGoal, routeContract, returnProof);
        if (!validation.accepted()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("path_contract_failed: " + validation.reason());
        }
        lastPathRequest = request;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        activePathRequest = request;
        this.pathExecutor = routeContract.constrained()
                ? new PathExecutor(
                result.path(), resolvedGoal, canPillar, allowDigFallback, reserve, routeContract)
                : new PathExecutor(
                result.path(), resolvedGoal, canPillar, allowDigFallback, reserve);
        this.pathExecutor.prefixStep(startStep);
        commitPlannedPhysicalSnap(startStep);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public BlockPos activePathGoal() {
        if (route != null) {
            settleRoute();
            if (route != null) {
                // RUN_AWAY's target is the threat/source that Baritone is fleeing, not an
                // arrival goal. Exposing it here makes a caller treat the bot as though it is
                // navigating *toward* the threat. Baritone normally has no resolved endpoint
                // for this shape, in which case there simply is no active path goal to report.
                if (route.shape() == NavRoute.Shape.RUN_AWAY) {
                    return route.resolvedGoal();
                }
                return route.resolvedGoal() != null ? route.resolvedGoal() : route.target();
            }
        }
        return activePathGoal;
    }

    // ==================== Navigator seam (nav.engine = baritone) ====================

    /** Failure reason of {@link #startApproachTo} when the Baritone engine is not the one answering requests. */
    public static final String ENGINE_NOT_BARITONE = "engine_not_baritone";

    /**
     * Routes an ordinary walk to Baritone. Returns null when the request is not Baritone's (legacy engine, a contract-bound
     * route, Baritone unavailable) and the caller runs the legacy navigator; otherwise the same answer the legacy code would give:
     * {@code IN_PROGRESS}, {@code failed("pathfinding_failed: GOAL_UNREACHABLE")} or {@code failed(PATHFINDING_THROTTLED)}.
     *
     * <p>Routing table (docs/NAVIGATION_BARITONE_PLAN.md): ordinary walks and surface-only walks are Baritone's (surface-only:
     * no breaking, no placing); contract routes, dig approaches, straight-line walks and one-cell safety moves stay legacy.</p>
     */
    private ActionResult routeOnBaritone(String kind, BlockPos goal, boolean canPillar, boolean allowDigFallback,
                                         int protectedStoneLikeReserve, PathExecutor.RouteContract routeContract) {
        if (routeContract.constrained()) {
            logEngine(kind, goal, NavEngine.LEGACY, "contract_route");
            return null;
        }
        if (!NavEngineSelector.baritoneSelectedFor(player.getUUID())) {
            logEngine(kind, goal, NavEngine.LEGACY, NavEngineSelector.baritoneFailed() ? "baritone_unavailable" : "engine_legacy");
            return null;
        }
        int reserve = Math.max(0, protectedStoneLikeReserve);
        // A caller that keeps a stone reserve must not have it spent on pillars/bridges by a planner that cannot see the reserve.
        NavRoute.Options options = NavRouteRules.optionsFor(allowDigFallback, canPillar, reserve, player.isInWater());
        NavRoute request = new NavRoute(NavRoute.Shape.BLOCK, goal, 0, options, kind, serverTick());
        PathRequestIdentity identity = new PathRequestIdentity(goal, canPillar, allowDigFallback, reserve, routeContract);
        return NavEngineSelector.attempt(player.getUUID(), kind, () -> startBaritoneRoute(request, identity, true), () -> null);
    }

    /**
     * Baritone-only: walk until within {@code radius} blocks of {@code target} (a {@code GoalNear}: no stand-off cell, no goal
     * snapping); what follow and approach use. Not throttled (the caller has its own schedule).
     *
     * @param refresh    re-target the route that is already running without another admission search on the server thread
     * @param allowBreak whether breaking through an obstacle is allowed as a last resort when there is no way around
     * @return {@link #ENGINE_NOT_BARITONE} failure when the engine is not (or no longer) Baritone: the caller uses its legacy walk
     */
    public ActionResult startApproachTo(BlockPos target, int radius, boolean refresh, boolean allowBreak) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        if (!NavEngineSelector.baritoneSelectedFor(player.getUUID())) {
            return ActionResult.failed(ENGINE_NOT_BARITONE);
        }
        NavRoute.Options options = new NavRoute.Options(allowBreak, false, player.isInWater());
        NavRoute request = new NavRoute(NavRoute.Shape.NEAR, target, radius, options, "approach", serverTick());
        boolean admit = !(refresh && route != null);
        return NavEngineSelector.attempt(player.getUUID(), "approach", () -> startBaritoneRoute(request, null, admit),
                () -> ActionResult.failed(ENGINE_NOT_BARITONE));
    }

    /**
     * Baritone-only: a walk that may cross water (the route is leased against the drowning safety net for as long as Baritone
     * drives it). No breaking, no placing.
     */
    public ActionResult startSwimRouteTo(BlockPos goal) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        if (!NavEngineSelector.baritoneSelectedFor(player.getUUID())) {
            return ActionResult.failed(ENGINE_NOT_BARITONE);
        }
        NavRoute request = new NavRoute(NavRoute.Shape.BLOCK, goal, 0, NavRoute.Options.SWIM, "swim_route", serverTick());
        return NavEngineSelector.attempt(player.getUUID(), "swim_route", () -> startBaritoneRoute(request, null, true),
                () -> ActionResult.failed(ENGINE_NOT_BARITONE));
    }

    /**
     * Baritone-only: flee. Walks until at least {@code distance} blocks (horizontally) from {@code source}, using Baritone's own
     * run-away goal (no hand-made flee target). Walk-only (no breaking, no placing) and dry, like every surface escape.
     *
     * @return {@link #ENGINE_NOT_BARITONE} failure when the engine is not (or no longer) Baritone, or the admission failure: the
     *         caller then projects its own escape goal and uses the ordinary surface path
     */
    public ActionResult startRunAwayFrom(BlockPos source, int distance) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        if (!NavEngineSelector.baritoneSelectedFor(player.getUUID())) {
            return ActionResult.failed(ENGINE_NOT_BARITONE);
        }
        NavRoute request = new NavRoute(NavRoute.Shape.RUN_AWAY, source, distance, NavRoute.Options.WALK_ONLY, "run_away", serverTick());
        return NavEngineSelector.attempt(player.getUUID(), "run_away", () -> startBaritoneRoute(request, null, true),
                () -> ActionResult.failed(ENGINE_NOT_BARITONE));
    }

    private ActionResult startBaritoneRoute(NavRoute request, PathRequestIdentity identity, boolean admit) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        int now = serverTick();
        if (identity != null && identity.equals(lastPathRequest) && now < nextPathfindTick) {
            logEngine(request.label(), request.target(), NavEngine.BARITONE, "throttled");
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        // Single writer: whatever the legacy executor was doing is dropped before Baritone is asked to move the bot.
        if (pathExecutor != null || walkTo != null || mining != null || forward != 0.0F || strafing != 0.0F
                || step != null || sneaking || sprinting || jumping || jumpTicks > 0) {
            yieldToBaritone();
        }
        // A route that already ended is recorded as it ended, not as "replaced".
        settleRoute();
        NavRoute previous = route;
        BaritoneNavigator.Admission admission;
        try {
            admission = BaritoneNavigator.start(player, request, admit);
        } catch (Throwable failure) {
            if (previous != null) {
                cancelBaritoneRoute("start_failed");
            } else {
                dropStaleRouteLease();
            }
            throw failure;
        }
        if (!admission.accepted()) {
            if (previous != null) {
                cancelBaritoneRoute("rejected_request");
            } else {
                dropStaleRouteLease();
            }
            if (identity != null) {
                lastPathRequest = identity;
                nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            }
            logEngine(request.label(), request.target(), NavEngine.BARITONE, "rejected: " + admission.failure());
            return ActionResult.failed(admission.failure());
        }
        double dx = request.target().getX() + 0.5D - player.getX();
        double dz = request.target().getZ() + 0.5D - player.getZ();
        // A flight is as long as its radius, not as far as the source it runs from.
        double travel = request.shape() == NavRoute.Shape.RUN_AWAY ? request.radius() : Math.sqrt(dx * dx + dz * dz);
        int deadlineBudget = NavRouteRules.deadlineTicks(travel);
        request.setDeadlineTick(now + deadlineBudget);
        if (previous != null && admit) {
            // A newer request took the bot over: the route it replaced is over and says so. (A deliberate re-goal refresh of the
            // same follow route, admit=false, is the same route and is not an ending.) The water bookkeeping belongs to the new one.
            finishRoute(NavOutcome.Status.CANCELLED, NavRouteRules.REPLACED, false);
        }
        route = request;
        // The slow-pace credit of a deadline is capped by the budget that deadline first allowed (a fresh deadline, a fresh credit).
        deadlineCredit = DeadlineCredit.forBudget(deadlineBudget);
        if (previous == null || admit) {
            // A new route (not a re-goal refresh of the follow route) ends the lease of the one before.
            clearRouteLease();
        }
        if (identity != null) {
            lastPathRequest = identity;
            nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        }
        logEngine(request.label(), request.target(), NavEngine.BARITONE,
                previous == null ? "started" : "regoal" + (admit ? "" : "_no_admission"));
        return ActionResult.IN_PROGRESS;
    }

    /** How the last Baritone route of this pack ended, or null if none has yet. */
    public NavOutcome lastRouteOutcome() {
        return lastRouteOutcome;
    }

    /** Whether a Baritone route of this pack is running (settles it first: a route that ended is not running). */
    public boolean hasBaritoneRoute() {
        settleRoute();
        return route != null;
    }

    /**
     * Ends this pack's Baritone route on the caller's order: Baritone lets go of the bot (goal, path, search, inputs, the block
     * being broken) and the route is recorded as cancelled, unless it had already ended by itself.
     *
     * @return true if a route was cancelled
     */
    public boolean cancelBaritoneRoute(String why) {
        settleRoute();
        if (route == null) {
            return false;
        }
        NavEngineSelector.hook("baritone_cancel", () -> BaritoneNavigator.cancel(player, why));
        finishRoute(NavOutcome.Status.CANCELLED, "cancelled: " + why, true);
        return true;
    }

    /**
     * The per-tick check of a Baritone route: arrived, ended short (failed), timed out, or a dry route that got wet. Called by
     * {@link #onUpdate} for a bot that is not driven and by {@code BaritoneDriver} at the end of a driven tick.
     */
    public void onBaritoneTick() {
        settleRoute();
    }

    private void settleRoute() {
        NavRoute current = route;
        if (current == null) {
            return;
        }
        if (!NavEngineSelector.baritoneActive()) {
            // Baritone was given up on while the route ran: there is nothing left to ask, and the bot is the legacy navigator's.
            finishRoute(NavOutcome.Status.FAILED, NavRouteRules.BARITONE_UNAVAILABLE, false);
            return;
        }
        NavRoute.Progress progress;
        boolean searchFailed = false;
        try {
            progress = BaritoneNavigator.progress(player, current);
            if (progress == NavRoute.Progress.ENDED_SHORT) {
                searchFailed = BaritoneNavigator.searchFailed(player);
            }
        } catch (Throwable failure) {
            // Reached from ~40 callers every tick, outside NavEngineSelector.attempt: whatever Baritone throws here ends the route
            // (a linkage-type failure also retires Baritone) and the callers just see an idle pack.
            // This may be called by the Baritone driver's own post-physics path, where the
            // driver otherwise completes normally. Record the real failure at this central
            // progress seam so a Baritone-labelled P3 capture cannot silently survive it.
            NavigationMeasurement.noteBaritoneFallback(player);
            boolean retired = NavEngineSelector.handleFailure("baritone_progress", failure);
            if (!retired) {
                NavEngineSelector.hook("baritone_cancel", () -> BaritoneNavigator.cancel(player, "progress_failed"));
            }
            finishRoute(NavOutcome.Status.FAILED, retired ? NavRouteRules.BARITONE_UNAVAILABLE : NavRouteRules.BARITONE_ERROR, !retired);
            return;
        }
        boolean dryRouteWet = !current.options().allowWater() && player.isInWater();
        boolean pastDeadline = serverTick() > current.deadlineTick();
        NavRouteRules.Verdict verdict = NavRouteRules.verdict(progress, dryRouteWet, pastDeadline, searchFailed);
        if (!verdict.ended()) {
            return;
        }
        if (progress == NavRoute.Progress.RUNNING || progress == NavRoute.Progress.POLICY_REFUSED) {
            // A dry route that got wet (a follower waits on its bank; the drowning safety net owns the bot from here), one that ran
            // out of time, or one the strict-survival rules keep vetoing: Baritone lets go of the bot before the route is recorded.
            NavEngineSelector.hook("baritone_cancel", () -> BaritoneNavigator.cancel(player, verdict.reason()));
        }
        finishRoute(verdict.status(), verdict.reason(), true);
    }

    /** @param releaseWater whether the route's water bookkeeping ends with it (false when a replacement has just registered its own) */
    private void finishRoute(NavOutcome.Status status, String reason, boolean releaseWater) {
        NavRoute finished = route;
        if (finished == null) {
            return;
        }
        route = null;
        deadlineCredit = new DeadlineCredit(0);
        if (releaseWater) {
            // A replacement route (releaseWater=false) has registered itself; only a route that really ended takes its lease with it.
            clearRouteLease();
        }
        BlockPos goal = finished.resolvedGoal() != null ? finished.resolvedGoal() : finished.target();
        NavOutcome outcome = new NavOutcome(status, reason, finished.label(), goal, serverTick() - finished.startTick());
        lastRouteOutcome = outcome;
        if (releaseWater) {
            NavEngineSelector.hook("baritone_release_route", () -> BaritoneNavigator.releaseRoute(player.getUUID()));
        }
        switch (status) {
            case SUCCESS -> BotLog.path(player, outcome.event(), "engine", "baritone", "ticks", outcome.ticks());
            case FAILED -> BotLog.warn(LogCategory.ERROR, player, outcome.event(),
                    "engine", "baritone", "reason", reason, "goal", LogFields.pos(goal), "ticks", outcome.ticks());
            default -> BotLog.path(player, outcome.event(), "engine", "baritone", "reason", reason,
                    "goal", LogFields.pos(goal), "ticks", outcome.ticks());
        }
    }

    private void logEngine(String kind, BlockPos goal, NavEngine engine, String why) {
        BotLog.path(player, "nav_engine", "engine", engine.configValue(), "configured", NavEngineSelector.configuredFor(player.getUUID()).configValue(),
                "kind", kind, "goal", LogFields.pos(goal), "why", why);
    }

    private int serverTick() {
        return player.level().getServer().getTickCount();
    }

    /**
     * Whether a constrained route may start from the current cell. Constrained routes never relocate the bot before their contract is
     * proven, and nothing here moves it at all: the cell must already be standable. A body that overlaps a neighbouring column
     * is not repaired by a move, it simply walks off (the first leg of the route leaves it, the safety net shoves a suffocating body
     * out with real inputs).
     */
    public boolean recenterPlayerInCurrentStandableCell(String reason) {
        if (controllerStartBlocked()) {
            return false;
        }
        Standability.clearCache();
        return Standability.isStandable(player.level(), player.blockPosition());
    }

    /**
     * What a route search starts from and how the bot gets there: {@code from} is the cell the search starts at, {@code prefix} the walk
     * that takes the bot from where it stands onto it (null when it already stands there).
     */
    private record StartPlan(BlockPos from, WalkedStep prefix, BlockPos snapOrigin) {
    }

    private StartPlan startPlan;
    // A physical start snap is deliberately not entered in physicalSnapGuard while it is only a
    // search plan. A failed A* search leaves the bot exactly where it was, so consuming the
    // once-per-origin retry window there used to lock an unmoved bot out for ten seconds.
    private WalkedStep unstartedPhysicalSnap;
    private BlockPos unstartedPhysicalSnapOrigin;

    /**
     * Makes the bot's current position a valid start for a route search, WITHOUT moving it: a standable cell is the start as it is (a
     * body that overlaps a wall column just walks off it); a cell that is not standable is left by a real, input-driven step onto an
     * adjacent standable cell (same level first, then one down, then one up; at most once per origin cell per
     * {@link SnapRepeatGuard#WINDOW_TICKS}), which the route executor performs before its first node. The caller takes the plan with
     * {@link #startCell()} / {@link #takeStartStep()}. Nothing here teleports the bot in any profile: no privileged long-distance
     * relocation exists for a path start any more.
     *
     * @return false when there is no legal start (the caller's search fails with NO_START)
     */
    public boolean snapPlayerToNearestStandable(String reason) {
        if (controllerStartBlocked()) {
            return false;
        }
        this.startPlan = null;
        discardUnstartedPhysicalSnap();
        ServerLevel world = player.level();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        if (Standability.isStandable(world, current)) {
            this.startPlan = new StartPlan(current.immutable(), null, null);
            return true;
        }
        if (physicalSnapSuppressed(current, reason)) {
            // A suppressed snap is a refusal to step out of a cell the bot was just walked back into: whatever walked it
            // back in would just be undone again (see SnapRepeatGuard). The caller's search simply fails this time.
            return false;
        }
        StartPlan planned = planAdjacentStep(world, current, reason);
        if (planned == null) {
            BotLog.warn(LogCategory.PATH, player, "path_start_no_adjacent_step", "reason", reason, "from", LogFields.pos(current));
            return false;
        }
        this.startPlan = planned;
        return true;
    }

    /**
     * An input-driven step onto an adjacent standable cell for a bot that stands in a cell it cannot use (the suffocation escape),
     * chosen by the same rules and under the same once-per-origin-cell guard as the start of a route; null when there is none
     * or the guard refuses. The caller runs it with {@link #runStep}.
     */
    public WalkedStep adjacentStandableStep(String reason) {
        if (controllerStartBlocked()) {
            return null;
        }
        discardUnstartedPhysicalSnap();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        if (physicalSnapSuppressed(current, reason)) {
            return null;
        }
        StartPlan planned = planAdjacentStep(player.level(), current, reason);
        rememberUnstartedPhysicalSnap(planned);
        return planned == null ? null : planned.prefix();
    }

    /** The cell a search for the route must start from after a successful {@link #snapPlayerToNearestStandable}. */
    public BlockPos startCell() {
        return startPlan != null ? startPlan.from() : player.blockPosition();
    }

    /** The walk onto {@link #startCell()} that the route must begin with (null when the bot already stands on it); hands it over once. */
    public WalkedStep takeStartStep() {
        StartPlan plan = startPlan;
        if (plan == null) {
            return null;
        }
        startPlan = new StartPlan(plan.from(), null, null);
        rememberUnstartedPhysicalSnap(plan);
        return plan.prefix();
    }

    private void rememberUnstartedPhysicalSnap(StartPlan plan) {
        unstartedPhysicalSnap = plan == null ? null : plan.prefix();
        unstartedPhysicalSnapOrigin = plan == null ? null : plan.snapOrigin();
    }

    private void discardUnstartedPhysicalSnap() {
        unstartedPhysicalSnap = null;
        unstartedPhysicalSnapOrigin = null;
    }

    /**
     * Commits the once-per-origin physical-snap guard when a controller actually accepts the
     * planned walked step. Kept public for {@link PathExecutor}, which can install the same kind
     * of prefix during a runtime replan. Planning, searching, and a refused prefix leave no guard
     * entry, so a bot that never moved can retry immediately.
     */
    public void commitPlannedPhysicalSnap(WalkedStep acceptedStep) {
        if (acceptedStep == null || acceptedStep != unstartedPhysicalSnap
                || unstartedPhysicalSnapOrigin == null) {
            return;
        }
        physicalSnapGuard.record(unstartedPhysicalSnapOrigin, serverTick());
        discardUnstartedPhysicalSnap();
    }

    /**
     * True when a second physical step out of the same cell inside the guard window is being refused:
     * whatever walked the bot back in would just be undone again (see SnapRepeatGuard). One step
     * per stall.
     */
    private boolean physicalSnapSuppressed(BlockPos current, String reason) {
        int nowTick = player.level().getServer().getTickCount();
        if (physicalSnapGuard.allows(current, nowTick)) {
            return false;
        }
        BotLog.path(player, "path_start_physical_snap_suppressed",
                "reason", reason, "from", LogFields.pos(current));
        return true;
    }

    private StartPlan planAdjacentStep(ServerLevel world, BlockPos current, String reason) {
        // Same-level steps first, then a one-block drop (up to the drop a walk survives), finally a vanilla-style hop. A vertical
        // move includes one horizontal axis at most; a corner hop is never legitimate.
        int[][] horizontalOffsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] offset : horizontalOffsets) {
                int dx = offset[0];
                int dz = offset[1];
                if (dy != 0 && Math.abs(dx) + Math.abs(dz) > 1) {
                    continue;
                }
                BlockPos candidate = current.offset(dx, dy, dz);
                if (!Standability.isStandable(world, candidate)) {
                    continue;
                }
                WalkedStep.Kind kind = dy > 0 ? WalkedStep.Kind.STEP_UP
                        : dy < 0 ? WalkedStep.Kind.STEP_DOWN : WalkedStep.Kind.FLAT;
                if (WalkedStep.refusal(player, candidate, kind) != null) {
                    continue;
                }
                BotLog.path(player, "path_start_walked_step_planned",
                        "reason", reason,
                        "from", LogFields.pos(current),
                        "to", LogFields.pos(candidate),
                        "kind", kind);
                return new StartPlan(candidate.immutable(),
                        WalkedStep.begin(player, candidate, kind, "path_start:" + reason),
                        current.immutable());
            }
        }
        return null;
    }

    /**
     * A step that walks the bot down into the just-mined {@code cell}, to be run
     * with {@link #runStep}. A cell diagonally below-adjacent is a {@link WalkedStep.Kind#STEP_DOWN} (walk off the edge and let gravity
     * land it); the cell directly below is a {@link WalkedStep.Kind#DROP} (no key: gravity lands it). Nothing moves the bot: the descent
     * has happened only when the step has verified the landing (the next tick or later, never in the tick that starts it). Null when
     * the cell is not below the bot or the landing is refused (not standable, a hazard, a block or an entity in the way).
     */
    public WalkedStep beginDescend(BlockPos cell, String reason) {
        if (controllerStartBlocked()) {
            return null;
        }
        BlockPos here = player.blockPosition();
        if (cell.getY() >= here.getY()) {
            return null;
        }
        WalkedStep.Kind kind = cell.getX() == here.getX() && cell.getZ() == here.getZ()
                ? WalkedStep.Kind.DROP : WalkedStep.Kind.STEP_DOWN;
        String refused = WalkedStep.refusal(player, cell, kind);
        if (refused != null) {
            BotLog.action(player, "descend_step_refused", "reason", reason, "from", LogFields.pos(here),
                    "to", LogFields.pos(cell), "why", refused);
            return null;
        }
        return WalkedStep.begin(player, cell, kind, reason);
    }

    public ActionResult startMining(BlockPos pos, Direction face) {
        if (controllerStartBlocked()) {
            return ActionResult.failed(GUARDED_STEP_FENCE);
        }
        claim("mining");
        this.mining = new MiningController(pos, face);
        clearActivePathExecutor();
        this.forward = 0.0F;
        this.strafing = 0.0F;
        return ActionResult.IN_PROGRESS;
    }

    public void stopMining() {
        if (this.mining != null) {
            this.mining.abort(player);
            this.mining = null;
        }
    }

    public void stopMovement() {
        if (emergencyInputBlocked()) {
            return;
        }
        setSneaking(false);
        setSprinting(false);
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        player.setJumping(false);
    }

    /**
     * Cancels the active path executor and direct walk and releases the movement keys.  Unlike
     * {@link #stopMovement()} (keys only -- a live executor re-presses forward on its very next
     * tick and keeps walking a stale route), this really stops navigation, but leaves mining and
     * item use alone.
     */
    public void stopNavigation() {
        if (emergencyInputBlocked()) {
            return;
        }
        if (route != null) {
            cancelBaritoneRoute("stop_navigation");
        }
        cancelStep();
        clearActivePathExecutor();
        this.walkTo = null;
        clearRouteLease();
        stopMovement();
    }

    public void stopAll() {
        if (emergencyInputBlocked()) {
            return;
        }
        releaseBaritone("stop_all");
        cancelStep();
        clearActivePathExecutor();
        stopMining();
        this.walkTo = null;
        clearRouteLease();
        stopMovement();
        // Cancel, never release: releasing a drawn bow fires it, and stopAll is an interruption, not a shot. A shield the reactive
        // owner holds against a noticed threat is not the task's to drop (the owner lowers it itself when the threat is over).
        if (!io.github.zoyluo.minecraftai.task.ShieldGuard.holdsShield(player)) {
            player.stopUsingItem();
        }
    }

    public boolean hasActiveActions() {
        return NavEngineSelector.query("baritone_busy", () -> BaritoneRegistry.INSTANCE.isBusy(player), false)
                || pathExecutor != null
                || step != null
                || walkTo != null
                || mining != null
                || forward != 0.0F
                || strafing != 0.0F
                || sneaking
                || sprinting
                || jumping
                || jumpTicks > 0
                || player.isUsingItem();
    }

    /**
     * Opt-in P3 diagnostic seam: which navigator actually owns a controller after this pack's
     * update. A pending/active Baritone route is Baritone even when the scheduler admitted it on
     * the preceding non-driven tick; a legacy path executor, direct walk, mining controller,
     * walked step, or raw movement is a real legacy fallback. This is never consulted by normal
     * navigation code.
     */
    public NavEngine navigationOwnerForMeasurement() {
        if (route != null || NavEngineSelector.query("baritone_busy", () -> BaritoneRegistry.INSTANCE.isBusy(player), false)) {
            return NavEngine.BARITONE;
        }
        if (pathExecutor != null || walkTo != null || mining != null || step != null
                || forward != 0.0F || strafing != 0.0F || jumping || jumpTicks > 0) {
            return NavEngine.LEGACY;
        }
        return null;
    }

    public boolean isPathExecutorIdle() {
        settleRoute();
        return pathExecutor == null && route == null;
    }

    public boolean isWalkToIdle() {
        return walkTo == null;
    }

    public boolean isMiningIdle() {
        return mining == null;
    }

    public void onUpdate() {
        settleRoute();
        // A step in flight has the bot to itself: whatever route, walk or break another owner left running waits (it was stopped by
        // the owner that started the step, and a controller a task starts meanwhile must not fight the step for the keys).
        if (!tickStep()) {
            tickPathExecutor();
            tickWalkTo();
            tickMining();
        }

        MinecraftAiConfig.Pace pace = MinecraftAiConfig.get().behaviour().paceOrDefaults();
        if (pace.paceEnabled() && controllerDriven()) {
            enforcePace(pace);
        } else {
            // Raw keys (a task's own setForward/setSneaking/setSprinting, the walked combat step) go through exactly as written.
            float velocity = sneaking ? PaceRules.SNEAK_SCALE : 1.0F;
            player.zza = forward * velocity;
            player.xxa = strafing * velocity;
            lastInputScale = 1.0F;
            lastClockWeight = 1.0D;
        }
        boolean jumpNow = jumping || jumpTicks > 0;
        player.setJumping(jumpNow);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    /**
     * Runs {@code next} as this pack's controller: it is ticked once per game tick in {@link #onUpdate} (so the bot counts as
     * controller-driven and the pace enforcer applies the pace and the vanilla rules to its keys) until it succeeds or fails.
     * Returns an opaque admission lease, or {@code null} when another guarded owner retains the handoff fence. A caller must treat
     * {@code null} as not started; stateful callers reconcile only their own admitted lease with
     * {@link #stepInFlightFor(StepLease)} and {@link #stepResultFor(StepLease)}, never the global {@link #stepResult()}.
     * Takes the bot over from a route, a walk and a break in progress.
     */
    public StepLease runStep(WalkedStep next) {
        return runStep(next, null);
    }

    /**
     * Runs a physical step with an optional owner-supplied continuation proof. The proof is held
     * by {@link WalkedStep} itself and is invoked before that step's next terrain revalidation,
     * which is earlier than task ownership reconciliation at END_SERVER_TICK.
     */
    public StepLease runStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard) {
        return startStep(next, continuationGuard, false);
    }

    /**
     * Starts NavSafetyNet's own physical suffocation successor. It retains the usual guarded
     * lease fence, plus a narrow immunity to generic stop/cancel/input-zeroing calls while it is
     * live. Returns {@code null} when an earlier guarded owner retains the handoff fence; callers
     * must then publish no local emergency state. Its exact owner must reconcile an admitted lease with {@link #cancelStep(StepLease)} or
     * {@link #releaseStepLease(StepLease)}; a later actual emergency may still preempt it.
     */
    public StepLease runEmergencyStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard) {
        return startStep(next, continuationGuard, true);
    }

    private StepLease startStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard,
                                boolean emergencyStep) {
        // A strict owner has proved this step's continuation before raw terrain validation. Do
        // not let a different controller replace it with an unguarded (or merely different)
        // step before that owner gets a chance to observe the handoff. The owner alone has the
        // opaque lease needed to release or cancel this fence deliberately.
        if (guardedStepLease != null) {
            return null;
        }
        claim("run_step");
        cancelStep();
        clearActivePathExecutor();
        stopMining();
        this.walkTo = null;
        next.setContinuationGuard(continuationGuard);
        this.step = next;
        StepLease lease = new StepLease();
        this.activeStepLease = lease;
        if (continuationGuard != null || emergencyStep) {
            this.guardedStepLease = lease;
        }
        if (emergencyStep) {
            this.emergencyStepLease = lease;
        }
        this.lastStepResult = null;
        this.completedStepLease = null;
        commitPlannedPhysicalSnap(next);
        return lease;
    }

    /** True when no step is in flight. */
    public boolean stepIdle() {
        return step == null;
    }

    /**
     * Ticks elapsed on the currently owned physical step, or {@code -1} when the pack is idle.
     * Owners use the zero/one-tick settling window only to preserve {@link WalkedStep}'s narrow
     * first-tick source normalization; it is not a general-purpose position override.
     */
    public int activeStepTicks() {
        return step == null ? -1 : step.ticks();
    }

    /** Kind of the active physical step, or {@code null} while idle. */
    public WalkedStep.Kind activeStepKind() {
        return step == null ? null : step.kind();
    }

    /**
     * True only when this pack is still running the exact walked step identified by its owner.
     * Callers that share pickup/navigation ownership use this instead of treating an unrelated
     * step as their own progress.
     */
    public boolean stepInFlightFor(String reason, BlockPos cell, WalkedStep.Kind kind) {
        return step != null
                && step.kind() == kind
                && step.cell().equals(cell)
                && step.reason().equals(reason);
    }

    /** True only while the exact lease's walked step is still active. */
    public boolean stepInFlightFor(StepLease lease) {
        return lease != null && step != null && activeStepLease == lease;
    }

    /**
     * Returns the result only when this exact lease completed naturally. A replacement or a
     * generic cancellation must never be mistaken for the old owner's outcome.
     */
    public WalkedStep.Result stepResultFor(StepLease lease) {
        return lease != null && completedStepLease == lease ? lastStepResult : null;
    }

    /**
     * Releases a completed/cancelled guarded-step fence after its owner has reconciled it.
     * Active steps require {@link #cancelStep(StepLease)} instead, so an owner cannot accidentally
     * make a still-running strict step replaceable.
     */
    public boolean releaseStepLease(StepLease lease) {
        if (lease == null || guardedStepLease != lease || activeStepLease == lease) {
            return false;
        }
        guardedStepLease = null;
        if (emergencyStepLease == lease) {
            emergencyStepLease = null;
        }
        if (completedStepLease == lease) {
            completedStepLease = null;
        }
        return true;
    }

    /** How the last step this pack ran ended (null while it is in flight or before the first). */
    public WalkedStep.Result stepResult() {
        return lastStepResult;
    }

    /** Abandons the step in flight (its keys are released; no result is recorded). */
    public void cancelStep() {
        if (emergencyInputBlocked()) {
            return;
        }
        cancelStepUnchecked();
    }

    /**
     * Internal cancellation path for an exact owner or a later actual emergency. Public generic
     * cancellation deliberately cannot interrupt a live {@link #runEmergencyStep} successor.
     */
    private void cancelStepUnchecked() {
        if (step != null) {
            step.cancel();
            step = null;
        }
        activeStepLease = null;
    }

    /**
     * Cancels and releases only the strict step owned by {@code lease}. This is the intentional
     * handoff path; ordinary {@link #cancelStep()} deliberately leaves the guarded fence in place.
     */
    public boolean cancelStep(StepLease lease) {
        if (lease == null || guardedStepLease != lease) {
            return false;
        }
        if (activeStepLease == lease) {
            // WalkedStep.end() releases its keys through ActionPack. Clear the emergency marker
            // first so this exact NavSafetyNet lifecycle cancellation can perform that cleanup.
            if (emergencyStepLease == lease) {
                emergencyStepLease = null;
            }
            cancelStepUnchecked();
        }
        guardedStepLease = null;
        if (emergencyStepLease == lease) {
            emergencyStepLease = null;
        }
        if (completedStepLease == lease) {
            completedStepLease = null;
        }
        return true;
    }

    /**
     * Explicit priority handoff for an immediate physical safety reflex (lava or being buried).
     * Ordinary controllers must use an exact {@link StepLease}; only NavSafetyNet calls this before
     * it writes its real emergency inputs, so a stale guarded follow/rescue step cannot suppress a
     * higher-priority escape indefinitely.
     */
    public boolean preemptGuardedStepForEmergency() {
        StepLease lease = guardedStepLease;
        if (lease == null) {
            return false;
        }
        // A later lava/burial emergency intentionally outranks a live emergency successor. Its
        // marker must be cleared before WalkedStep.end() can zero the old physical keys.
        if (emergencyStepLease == lease) {
            emergencyStepLease = null;
        }
        cancelStep();
        guardedStepLease = null;
        if (emergencyStepLease == lease) {
            emergencyStepLease = null;
        }
        if (completedStepLease == lease) {
            completedStepLease = null;
        }
        return true;
    }

    /** Ticks the step; true while it is still in flight (the other controllers do not run this tick). */
    private boolean tickStep() {
        if (step == null) {
            return false;
        }
        boolean guarded = activeStepLease != null && activeStepLease == guardedStepLease;
        tickingGuardedStep = guarded;
        WalkedStep.Result result;
        try {
            result = step.tick();
        } finally {
            tickingGuardedStep = false;
        }
        if (result.inProgress()) {
            return true;
        }
        lastStepResult = result;
        completedStepLease = activeStepLease;
        step = null;
        activeStepLease = null;
        return false;
    }

    private void tickWalkTo() {
        if (walkTo == null) {
            return;
        }

        ActionResult result = walkTo.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "walk_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "walk_failed", "reason", result.reason());
        }
        walkTo = null;
        clearRouteLease();
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickPathExecutor() {
        if (pathExecutor == null) {
            return;
        }

        ActionResult result = pathExecutor.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
        }
        pathExecutor = null;
        activePathGoal = null;
        activePathRequest = null;
        clearRouteLease();
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    /**
     * Ticks one break controller under vanilla's destroyDelay: after a break that took more than one tick a client waits five ticks
     * (continueDestroyBlock returns early while the counter runs) before it starts on the next block. A bot that chained breaks back
     * to back would dig faster than any player, so the next controller does nothing (IN_PROGRESS) until that delay is over. An
     * instant break (hardness 0, or a tool that mines the block in the first tick) sets no delay, as in vanilla. The ONE place
     * the delay lives: {@link #tickMining} and the route executor's dig-through sub-miners both tick through here.
     */
    public ActionResult tickBreak(MiningController controller) {
        if (io.github.zoyluo.minecraftai.task.ShieldGuard.usingShield(player)) {
            // A player cannot break a block while the use key is down (Minecraft.continueAttack needs no item in use): with the shield
            // up against a noticed threat the break waits, its progress kept.
            return ActionResult.IN_PROGRESS;
        }
        if (player.level().getGameTime() < nextBreakAt) {
            return ActionResult.IN_PROGRESS;
        }
        ActionResult result = controller.tick(this);
        if (result.isSuccess() && controller.elapsedTicks() > 1) {
            nextBreakAt = player.level().getGameTime() + DESTROY_DELAY_TICKS + 1L;
        }
        return result;
    }

    private void tickMining() {
        if (mining == null) {
            return;
        }
        ActionResult result = tickBreak(mining);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            // Auditable break record (see docs/LOGGING.md "Auditing a gather"): block is captured
            // BEFORE the break by MiningController, so this reports what was actually destroyed
            // even though the world cell is air by now.
            BlockState brokenState = mining.brokenBlockState();
            ItemStack tool = player.getMainHandItem();
            BotLog.action(player, "mine_complete",
                    "block", brokenState == null ? "unknown" : BuiltInRegistries.BLOCK.getKey(brokenState.getBlock()).toString(),
                    "pos", LogFields.pos(mining.pos()),
                    "tool", tool.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(tool.getItem()).toString(),
                    "ticks", mining.elapsedTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "mine_failed", "reason", result.reason());
        }
        mining = null;
    }

    /**
     * Returns true when an identical request is still inside its cooldown.
     * A different active identity is stopped before cooldown evaluation so an old, weaker route
     * can never keep moving merely because the replacement happens to share the same goal.
     */
    private boolean preparePathRequest(PathRequestIdentity request, int now) {
        if (pathExecutor != null && !request.equals(activePathRequest)) {
            clearActivePathExecutor();
        }
        if (request.equals(lastPathRequest) && now < nextPathfindTick) {
            return true;
        }
        // An explicit restart after cooldown owns the controller from this point onward.
        clearActivePathExecutor();
        return false;
    }

    /**
     * A test seam: forgets the last path request and its cooldown, so that a fixture that has just cancelled a route (the bot's first
     * reaction to a threat it had only just noticed) can make the same request again in the same tick instead of being throttled as a
     * repeat. Production never calls it.
     */
    public void forgetPathThrottleForTests() {
        lastPathRequest = null;
        nextPathfindTick = 0;
    }

    /** Drops the legacy path executor and its request AND the route lease that went with it. */
    private void clearActivePathExecutor() {
        dropPathExecutor();
        clearRouteLease();
    }

    /**
     * Drops the legacy path executor and its request but keeps the route lease: a lease is requested after a route is started, and
     * Baritone taking the bot over ({@link #yieldToBaritone}) is the first tick of the route it belongs to, not its end.
     */
    private void dropPathExecutor() {
        if (pathExecutor != null) {
            pathExecutor.abort(this);
            pathExecutor = null;
        }
        activePathGoal = null;
        activePathRequest = null;
    }

    private record PathRequestIdentity(
            BlockPos goal,
            boolean canPillar,
            boolean allowDig,
            int protectedStoneLikeReserve,
            PathExecutor.RouteContract routeContract) {
        private PathRequestIdentity {
            goal = goal.immutable();
            protectedStoneLikeReserve = Math.max(0, protectedStoneLikeReserve);
            routeContract = java.util.Objects.requireNonNull(routeContract, "routeContract");
        }
    }

    private static float clampInput(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
}
