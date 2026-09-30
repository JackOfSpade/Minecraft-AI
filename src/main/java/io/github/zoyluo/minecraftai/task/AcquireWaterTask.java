package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.BucketAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InCellWalk;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Strict-survival water acquisition for an obsidian expedition.
 *
 * <p>The task first walks back to the mission's surface anchor, then explores a bounded square
 * spiral. Water is considered only after its cell is visible, and the bucket is filled through
 * {@link BucketAction}, which invokes the vanilla item interaction. Search position and budget are
 * checkpointed so restart cannot reset an exhausted expedition or silently jump to hidden water.</p>
 */
public final class AcquireWaterTask extends AbstractTask implements CheckpointableTask {
    private static final int LEGACY_CHECKPOINT_SCHEMA = 2;
    private static final int PREVIOUS_CHECKPOINT_SCHEMA = 3;
    private static final int CHECKPOINT_SCHEMA = 4;
    private static final int LEGACY_MAX_ELAPSED = 12_000;
    private static final int MAX_ELAPSED = 30_000;
    private static final int SCAN_INTERVAL = 10;
    private static final int PATH_RETRY_INTERVAL = 20;
    private static final int ASCENT_BLOCKED_LOG_INTERVAL = 100;
    private static final int MAX_ASCENT_RELOCATIONS_PER_LEVEL = 32;
    private static final int PATH_STALL_LIMIT = 400;
    private static final int APPROACH_LIMIT = 600;
    // A Y16 iron trip can finish 80+ Manhattan blocks from the mission origin.  A physical
    // two-high stair costs roughly 30-40 ticks per vertical level, so 1,800 ticks expired exactly
    // as the seed-3000 bot reached Y66.  Keep this bounded but large enough for the ascent plus the
    // remaining surface traverse.
    private static final int RETURN_LIMIT = 6_000;
    private static final int MAX_SURFACE_OVERSHOOT = 32;
    private static final int SEARCH_STEP = 12;
    private static final int LEGACY_MAX_WAYPOINTS = 100;
    // Seven complete square-spiral rings.  The former 100-point cap stopped halfway through ring
    // five, so one direction received materially less physical coverage than the others.
    private static final int MAX_WAYPOINTS = 224;
    private static final int MAX_UNREACHABLE_WAYPOINTS = 3;
    private static final int BLOCKED_SECTOR_RUNWAY_RADIUS = 3;
    private static final int BLOCKED_SECTOR_PROOF_MAX_NODES = 128;
    private static final double UNREACHABLE_RELOCATION_SQUARED = 16.0D;
    private static final int MAX_REJECTED_SOURCES = 64;
    private static final int MAX_RESTORABLE_PATH_ATTEMPTS = 100;
    private static final double ARRIVE_SQUARED = 4.0D;
    private static final double INTERACTION_REACH_EPSILON = 0.01D;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    /** The sneak-bridge in flight: the bot leans over the edge (SHIFTING), places, then walks back to the middle (RETURNING). */
    private enum EdgeStage {
        SHIFTING,
        RETURNING
    }

    private static final class EdgePlacement {
        final BlockPos anchor;
        final Direction direction;
        final BlockPos foundation;
        final String item;
        EdgeStage stage = EdgeStage.SHIFTING;
        String placeFailure;
        // The step of the current stage: its own state says when it ended, whatever else the action pack runs meanwhile.
        WalkedStep step;

        EdgePlacement(BlockPos anchor, Direction direction, BlockPos foundation, String item, WalkedStep step) {
            this.step = step;
            this.anchor = anchor.immutable();
            this.direction = direction;
            this.foundation = foundation.immutable();
            this.item = item;
        }
    }

    private enum Phase {
        RETURN_SURFACE,
        SEARCH,
        APPROACH,
        DONE
    }

    private final BlockPos requestedSurfaceAnchor;
    private final SearchCheckpoint restored;
    private final boolean invalidCheckpoint;
    private final Set<BlockPos> rejectedSources = new LinkedHashSet<>();
    private final BlockMiner returnMiner = new BlockMiner();
    private CraftTask ascentToolCraft;

    private BlockPos surfaceAnchor;
    private BlockPos searchOrigin;
    private boolean surfaceExitReached;
    private Phase phase = Phase.RETURN_SURFACE;
    private BlockPos searchWaypoint;
    private BlockPos waterSource;
    private BlockPos waterStand;
    private int directionIndex;
    private int legLength = 1;
    private int stepInLeg;
    private int repeatedLegs;
    private int gridX;
    private int gridZ;
    private int issuedWaypoints;
    private int reachedWaypoints;
    private int waypointLimit = MAX_WAYPOINTS;
    private int elapsedLimit = MAX_ELAPSED;
    private int pathAttempts;
    private int elapsedOffset;
    private int phaseStartedBudget;
    private int waypointStartedBudget;
    private int consecutiveUnreachableWaypoints;
    private BlockPos unreachableAnchor;
    private int lastScanBudget = -SCAN_INTERVAL;
    private int lastPathAttemptBudget = -PATH_RETRY_INTERVAL;
    private BlockPos ascentTarget;
    private EdgePlacement edge;
    private WalkedStep ascentSettle;
    private BlockPos ascentCommittedFrom;
    private boolean ascentPathStarted;
    private int ascentPathStartedBudget;
    private BlockPos ascentRelocationTarget;
    private BlockPos ascentRelocationOrigin;
    private boolean ascentRelocationPathStarted;
    private BlockPos ascentRelocationPrevious;
    private int ascentRelocationStartedBudget;
    private int ascentRelocationLevel = Integer.MIN_VALUE;
    private int ascentRelocationsAtLevel;
    private final Set<BlockPos> failedAscentSupports = new LinkedHashSet<>();
    private final Set<BlockPos> failedAscentRelocations = new LinkedHashSet<>();
    private BlockPos lastAscentBlockedPos;
    private String lastAscentBlockedReasons = "";
    private int lastAscentBlockedLogBudget = -ASCENT_BLOCKED_LOG_INTERVAL;
    private BlockPos pausedAscentPosition;

    public AcquireWaterTask(BlockPos surfaceAnchor) {
        this(surfaceAnchor, Map.of());
    }

    public AcquireWaterTask(BlockPos surfaceAnchor, Map<String, String> checkpoint) {
        this.requestedSurfaceAnchor = surfaceAnchor == null ? BlockPos.ZERO : surfaceAnchor.immutable();
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        this.restored = SearchCheckpoint.decode(values).orElse(null);
        this.invalidCheckpoint = !values.isEmpty()
                && (restored == null || !restored.surfaceAnchor().equals(this.requestedSurfaceAnchor));
    }

    @Override
    public String name() {
        return "acquire_water";
    }

    @Override
    public String describe() {
        return "AcquireWater phase=" + phase
                + " observed=" + reachedWaypoints
                + " attempted=" + issuedWaypoints + "/" + waypointLimit;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case RETURN_SURFACE -> 0.05D;
            case SEARCH -> Math.min(0.75D,
                    0.1D + (double) reachedWaypoints / waypointLimit * 0.65D);
            case APPROACH -> 0.85D;
            case DONE -> 1.0D;
        };
    }

    @Override
    public boolean isWaiting() {
        // This task owns its bounded path/search watchdogs; generic stuck recovery must not replace it.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("acquire_water_invalid_checkpoint");
            return;
        }
        if (restored == null) {
            surfaceAnchor = requestedSurfaceAnchor;
            searchOrigin = surfaceAnchor;
            phase = Phase.RETURN_SURFACE;
            phaseStartedBudget = 0;
        } else {
            surfaceAnchor = restored.surfaceAnchor();
            searchOrigin = restored.searchOrigin();
            surfaceExitReached = restored.surfaceExitReached();
            phase = restored.phase();
            searchWaypoint = restored.searchWaypoint();
            waterSource = restored.waterSource();
            waterStand = restored.waterStand();
            directionIndex = restored.directionIndex();
            legLength = restored.legLength();
            stepInLeg = restored.stepInLeg();
            repeatedLegs = restored.repeatedLegs();
            gridX = restored.gridX();
            gridZ = restored.gridZ();
            issuedWaypoints = restored.issuedWaypoints();
            reachedWaypoints = restored.reachedWaypoints();
            waypointLimit = restored.waypointLimit();
            elapsedLimit = restored.elapsedLimit();
            pathAttempts = restored.pathAttempts();
            elapsedOffset = restored.budgetUsed();
            phaseStartedBudget = restored.phaseStartedBudget();
            waypointStartedBudget = restored.waypointStartedBudget();
            consecutiveUnreachableWaypoints = restored.consecutiveUnreachableWaypoints();
            unreachableAnchor = restored.unreachableAnchor();
            rejectedSources.addAll(restored.rejectedSources());
            BotLog.task(bot, "acquire_water_restored",
                    "phase", phase,
                    "observed", reachedWaypoints,
                    "attempted", issuedWaypoints,
                    "budget", elapsedOffset,
                    "waypoint_limit", waypointLimit,
                    "budget_limit", elapsedLimit,
                    "anchor", surfaceAnchor.toShortString());
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0) {
            finish(bot);
            return;
        }
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            fail("acquire_water_missing_bucket");
            return;
        }
        if (phase == Phase.DONE) {
            fail("acquire_water_checkpoint_done_without_water");
            return;
        }
        // Each checkpoint carries the authority ceiling under which it was issued.  A schema-2/3
        // terminal therefore keeps its historical 100/12,000 boundary after migration instead of
        // gaining a fresh expedition merely because the current binary has wider limits.
        if (totalBudget() >= elapsedLimit) {
            fail(timeoutReason());
            return;
        }
        if (phase == Phase.SEARCH
                && searchWaypoint == null
                && issuedWaypoints >= waypointLimit) {
            fail(searchExhaustedReason());
            return;
        }
        if (phase == Phase.SEARCH
                && consecutiveUnreachableWaypoints >= MAX_UNREACHABLE_WAYPOINTS) {
            if (!recoverBlockedSearchSector(bot, "restore")) {
                fail(noReachableSurfaceRouteReason(bot.blockPosition()));
            }
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        clearReturnSurfaceWork(bot, "acquire_water_aborted");
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (ascentToolCraft != null && ascentToolCraft.state() == TaskState.RUNNING) {
            ascentToolCraft.pause(bot);
        }
        returnMiner.cancel(bot);
        BlockPos current = bot.blockPosition().immutable();
        settleOrReleasePausedAscentMotion(bot, current);
        pausedAscentPosition = current;
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        BlockPos current = bot.blockPosition();
        boolean safetyDisplaced = pausedAscentPosition != null
                && !pausedAscentPosition.equals(current);

        // A safety task owns movement while this task is paused. Never resume an old path from its
        // pre-safety origin: release every transient motion intent and select again from the factual
        // current cell on the next tick. Relocation failures are local geometry observations, so a
        // safety displacement also invalidates that temporary ledger without refunding any durable
        // task budget or weakening the fluid checks used by the new selection.
        ascentTarget = null;
        ascentPathStarted = false;
        edge = null;
        ascentSettle = null;
        ascentRelocationTarget = null;
        ascentRelocationOrigin = null;
        ascentRelocationPathStarted = false;
        if (safetyDisplaced) {
            ascentRelocationPrevious = null;
            failedAscentRelocations.clear();
            // Ordinary path failures and their retry cooldown are observations about the old
            // position. A safety task can move the bot onto a different connected surface cell;
            // retaining either value would make the first factual retry wait for stale evidence,
            // or even inherit the old RETURN_SURFACE three-attempt terminal gate.
            pathAttempts = 0;
            lastPathAttemptBudget = totalBudget() - PATH_RETRY_INTERVAL;
            noteAscentProgress();
        }
        pausedAscentPosition = null;

        if (ascentToolCraft != null && ascentToolCraft.state() == TaskState.PAUSED) {
            ascentToolCraft.resume(bot);
        }
    }

    /**
     * Settles a movement that physically reached its target before the safety pause, and releases
     * any unfinished motion without recording a false path failure. The safety task may move the
     * bot immediately after this callback, so all path executor state must be closed here.
     */
    private void settleOrReleasePausedAscentMotion(AIPlayerEntity bot, BlockPos current) {
        if (ascentTarget != null && current.equals(ascentTarget)) {
            pathAttempts = 0;
            noteAscentProgress();
        }
        ascentTarget = null;
        ascentPathStarted = false;
        edge = null;
        ascentSettle = null;

        if (ascentRelocationTarget == null) {
            return;
        }
        BlockPos reached = ascentRelocationTarget;
        if (current.equals(reached)) {
            ascentRelocationPrevious = ascentRelocationOrigin;
            ascentRelocationsAtLevel++;
            failedAscentSupports.clear();
            failedAscentRelocations.clear();
            pathAttempts = 0;
            noteAscentProgress();
            BotLog.action(bot, "acquire_water_ascent_relocated",
                    "to", reached.toShortString(),
                    "used", ascentRelocationsAtLevel,
                    "budget", MAX_ASCENT_RELOCATIONS_PER_LEVEL,
                    "boundary", "pause");
        }
        ascentRelocationTarget = null;
        ascentRelocationOrigin = null;
        ascentRelocationPathStarted = false;
    }

    /**
     * Releases every transient owner of RETURN_SURFACE work before that phase is abandoned.
     * Durable search budget/cursor state is intentionally untouched; this is only process-local
     * mining, crafting, relocation, and movement state that must never leak into SEARCH or DONE.
     */
    private void clearReturnSurfaceWork(AIPlayerEntity bot, String reason) {
        returnMiner.cancel(bot);
        if (ascentToolCraft != null) {
            ascentToolCraft.cancel(bot, reason);
            ascentToolCraft = null;
        }
        ascentTarget = null;
        ascentPathStarted = false;
        edge = null;
        ascentSettle = null;
        ascentRelocationTarget = null;
        ascentRelocationOrigin = null;
        ascentRelocationPathStarted = false;
        pausedAscentPosition = null;
        bot.getActionPack().stopAll();
    }

    /** Package-private invariant seam for live transition tests. */
    boolean hasReturnSurfaceWorkForTesting() {
        return returnMiner.target() != null
                || ascentToolCraft != null
                || ascentTarget != null
                || ascentPathStarted
                || ascentRelocationTarget != null
                || ascentRelocationOrigin != null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (totalBudget() >= elapsedLimit) {
            fail(timeoutReason());
            return;
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0) {
            finish(bot);
            return;
        }
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            fail("acquire_water_bucket_lost");
            return;
        }
        switch (phase) {
            case RETURN_SURFACE -> returnToSurface(bot);
            case SEARCH -> search(bot);
            case APPROACH -> approach(bot);
            case DONE -> complete();
        }
    }

    private void returnToSurface(AIPlayerEntity bot) {
        // A cave opening can change after a support/shelter placement and flood the current cell
        // on the next vanilla fluid tick. Stop the ascent immediately and let the global physical
        // water rescue own movement until a dry landing is proved; continuing to inspect/place
        // supports from inside water makes every Fluid.ANY perception ray self-occlude.
        BlockPos wetFeet = bot.blockPosition();
        boolean touchingWater = bot.isInWater()
                || bot.level().getFluidState(wetFeet).is(FluidTags.WATER)
                || bot.level().getFluidState(wetFeet.above()).is(FluidTags.WATER);
        if (touchingWater || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
            returnMiner.cancel(bot);
            ascentTarget = null;
            edge = null;
            ascentPathStarted = false;
            bot.getActionPack().stopAll();
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            return;
        }
        // The sneak-bridge in flight (lean, place, walk back) has the bot to itself: nothing else may start a route or a break.
        if (edge != null) {
            tickEdgePlacement(bot);
            return;
        }
        // A strict return stair can expose a real aquifer before it reaches the remembered
        // surface.  When that source is already visible and reachable from the current dry
        // footing, walking past it (or waiting forever below its fluid ceiling) is unnecessary.
        // Keep this as an immediate vanilla interaction rather than publishing APPROACH state:
        // failure leaves every RETURN_SURFACE budget/ledger untouched, while success is durable in
        // the inventory and can therefore finish synchronously without a checkpoint schema bump.
        if (fillReachableReturnWater(bot)) {
            return;
        }
        // The old surface anchor is not a mandatory doorway. A deep staircase may emerge tens of
        // blocks away from it. SEARCH may begin only from one unified, physically reusable exit:
        // dry footing plus an observable same-level/uphill edge. The remembered anchor can attest
        // that a nearby cell is on the surface even under a leaf canopy; an unanchored exit still
        // needs open sky. Publishing the latch, origin, and phase together keeps every schema-3
        // SEARCH/APPROACH checkpoint inside the same durable invariant.
        BlockPos reusableSearchOrigin = reusableSurfaceSearchOrigin(bot);
        if (reusableSearchOrigin != null) {
            beginSurfaceSearch(bot, reusableSearchOrigin);
            return;
        }
        if (phaseAge() > RETURN_LIMIT) {
            fail("acquire_water_surface_return_unreachable anchor=" + surfaceAnchor.toShortString());
            return;
        }
        // A single A* from a deep mine to the old surface origin expands a large three-dimensional
        // dig graph and repeatedly hits TIMEOUT/SEARCH_LIMIT.  When the vertical gap is substantial,
        // carve a normal two-block-high diagonal staircase instead: every block is mined through the
        // vanilla mining controller and every rise is one validated adjacent jump.  Once close to
        // surface level the ordinary pathfinder can handle the remaining horizontal approach.
        boolean needsDrySurfaceExit = bot.blockPosition().getY() < surfaceAnchor.getY()
                || !hasReusableSurfaceEgress(bot, bot.level(), bot.blockPosition(), true)
                && bot.blockPosition().getY() < surfaceAnchor.getY() + MAX_SURFACE_OVERSHOOT;
        if (needsDrySurfaceExit && ascendOneStair(bot)) {
            return;
        }
        retryPath(bot, surfaceAnchor, false);
    }

    private boolean fillReachableReturnWater(AIPlayerEntity bot) {
        if (totalBudget() - lastScanBudget < SCAN_INTERVAL) {
            return false;
        }
        lastScanBudget = totalBudget();
        BlockPos source = nearestReachableObservablePlainWaterSource(bot);
        if (source == null) {
            return false;
        }

        ActionResult result = BucketAction.fillWaterSource(bot, source);
        if (result.isFailed()) {
            // A visibility or interaction failure is a fact about this pose, not a durable source
            // rejection.  The ordinary bounded return controller remains authoritative and may
            // expose the same source from a different dry stand later.
            BotLog.action(bot, "acquire_water_return_source_rejected",
                    "source", source.toShortString(), "reason", result.reason());
            return false;
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) <= 0) {
            fail("acquire_water_fill_without_water_bucket");
            return true;
        }
        BotLog.action(bot, "acquire_water_completed",
                "source", source.toShortString(), "phase", Phase.RETURN_SURFACE);
        finish(bot);
        return true;
    }

    private static BlockPos nearestReachableObservablePlainWaterSource(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        double reach = bot.blockInteractionRange();
        int range = Math.min(
                Math.max(1, (int) Math.ceil(reach)),
                Math.min(16, Math.max(1, MinecraftAiConfig.get().perception().radius())));
        Vec3 eye = bot.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = origin.getX() - range; x <= origin.getX() + range; x++) {
            for (int y = origin.getY() - range; y <= origin.getY() + range; y++) {
                for (int z = origin.getZ() - range; z <= origin.getZ() + range; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    double distance = eye.distanceToSqr(candidate.getCenter());
                    if (distance >= bestDistance || distance > reach * reach
                            || !ObservableWorldQuery.canObserveCell(bot, candidate)) {
                        continue;
                    }
                    // Read both block and fluid only after the strict line-of-sight gate. Requiring
                    // the independent WATER block excludes waterlogged solids/plants, while the
                    // still-fluid check excludes flowing water exposed by an unsafe cave opening.
                    var state = world.getBlockState(candidate);
                    var fluid = state.getFluidState();
                    if (state.is(Blocks.WATER)
                            && state.getCollisionShape(world, candidate).isEmpty()
                            && fluid.is(FluidTags.WATER)
                            && fluid.isSource()) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos reusableSurfaceSearchOrigin(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos current = bot.blockPosition();
        boolean nearKnownSurface = near(current, surfaceAnchor)
                && current.getY() >= surfaceAnchor.getY();
        // The reusable exit is the dry current footing plus the observable outward edge checked
        // below.  Requiring the current column itself to see sky creates a one-block-overhang
        // deadlock: the edge already proves open surface, so the ascent controller stops climbing,
        // but this method used to refuse the same evidence and never published SEARCH.  The Y gate
        // still prevents a sky-lit ravine below the remembered surface from minting an exit.
        if (!nearKnownSurface && current.getY() < surfaceAnchor.getY()) {
            return null;
        }
        Standability.clearCache();
        if (!world.getFluidState(current).isEmpty()
                || !world.getFluidState(current.above()).isEmpty()
                || !Standability.isStandable(world, current)
                || !hasReusableSurfaceEgress(bot, world, current, !nearKnownSurface)) {
            return null;
        }
        return current.immutable();
    }

    private void beginSurfaceSearch(AIPlayerEntity bot, BlockPos origin) {
        clearReturnSurfaceWork(bot, "acquire_water_surface_exit_reached");
        // Keep the durable surface proof and the phase transition adjacent: no checkpoint can
        // observe SEARCH with the old false latch or the pre-exit origin.
        surfaceExitReached = true;
        searchOrigin = origin.immutable();
        // RETURN_SURFACE retries belong to the old anchor and cannot attest that a waypoint from
        // this newly proved local exit is unreachable.  Rebase both the retry count and its
        // cooldown atomically with the surface latch.
        pathAttempts = 0;
        lastPathAttemptBudget = totalBudget() - PATH_RETRY_INTERVAL;
        enter(Phase.SEARCH);
        BotLog.action(bot, "acquire_water_surface_exit_reached",
                "at", origin.toShortString(),
                "anchor", surfaceAnchor.toShortString());
    }

    private static boolean hasReusableSurfaceEgress(AIPlayerEntity bot,
                                                     ServerLevel world,
                                                     BlockPos current,
                                                     boolean requireSkyVisibility) {
        for (Direction direction : HORIZONTAL) {
            BlockPos sameLevel = current.relative(direction);
            if (observableStandCell(bot, sameLevel)
                    && (!requireSkyVisibility || world.canSeeSky(sameLevel))
                    && Standability.isStandable(world, sameLevel)) {
                return true;
            }

            // Match the surface-only A* JUMP_UP envelope without starting or cancelling a path.
            // A downhill-only edge back into the mined stair deliberately does not count: SEARCH
            // needs a reusable outward route, not merely a way to re-enter the mine.
            BlockPos uphill = sameLevel.above();
            if (!observableStandCell(bot, uphill)
                    || requireSkyVisibility && !world.canSeeSky(uphill)
                    || !Standability.isStandable(world, uphill)) {
                continue;
            }
            var frontState = world.getBlockState(sameLevel);
            if (frontState.getCollisionShape(world, sameLevel).isEmpty()
                    || frontState.getCollisionShape(world, sameLevel)
                    .max(Direction.Axis.Y) > 1.0D
                    || !world.getBlockState(current.above()).getCollisionShape(world, current.above()).isEmpty()
                    || !world.getBlockState(current.above(2)).getCollisionShape(world, current.above(2)).isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Advances one safe diagonal-up stair cell.  Returns {@code true} when this tick is owned by
     * the ascent (mining, jumping, or deliberately rejecting a dangerous candidate); {@code false}
     * lets the caller fall back to ordinary pathfinding.
     */
    private boolean ascendOneStair(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos current = bot.blockPosition();
        if (edge != null) {
            tickEdgePlacement(bot);
            return true;
        }
        prepareAscentLevel(current);
        if (ascentToolCraft != null) {
            tickAscentToolCraft(bot);
            return true;
        }
        if (settleOnStandableCell(bot, world, current)) {
            return true;
        }
        if (tickAscentRelocation(bot, current)) {
            return true;
        }
        if (ascentTarget != null && current.equals(ascentTarget)) {
            returnMiner.cancel(bot);
            ascentTarget = null;
            ascentPathStarted = false;
            pathAttempts = 0;
            noteAscentProgress();
            return true;
        }
        // A subtask that ran mid-ascent (tool crafting, then physically walking over to reclaim
        // its crafting table) can leave the bot one cell away from the stance this step committed
        // from, even though the committed target still blocks the same stair and stays within
        // ordinary reach. Re-deriving everything from that drifted `current` would fail the
        // adjacency check below and abandon a live commitment for an easier direction instead of
        // finishing it. Keep evaluating the target from its already-vetted stance as long as the
        // bot can still physically reach both that stance and the obstruction itself; a real
        // safety displacement clears ascentTarget outright (see onResume) well before this runs,
        // so this never resurrects a target across an unrelated relocation.
        AscentCandidate driftCandidate = null;
        if (ascentTarget != null && !isAdjacentUp(current, ascentTarget)
                && ascentCommittedFrom != null
                && isAdjacentUp(ascentCommittedFrom, ascentTarget)
                && current.getY() == ascentCommittedFrom.getY()
                && HarvestCore.canReach(bot, ascentCommittedFrom)) {
            AscentCandidate inspected =
                    inspectAscentCandidate(bot, world, ascentCommittedFrom, ascentTarget);
            if (inspected.accepted() && inspected.obstruction() != null
                    && HarvestCore.canReach(bot, inspected.obstruction())) {
                driftCandidate = inspected;
            }
        }
        boolean keepDriftedTarget = driftCandidate != null;

        if (!keepDriftedTarget
                && (ascentTarget == null || !isAdjacentUp(current, ascentTarget)
                    || !inspectAscentCandidate(bot, world, current, ascentTarget).accepted())) {
            returnMiner.cancel(bot);
            ascentPathStarted = false;
            AscentChoice choice = selectAscentTarget(bot, world, current);
            ascentTarget = choice.target();
            ascentCommittedFrom = ascentTarget == null ? null : current.immutable();
            if (ascentTarget == null) {
                if (beginAscentRelocation(bot, world, current, choice.rejections())) {
                    return true;
                }
                maybeLogAscentBlocked(bot, current, choice.rejections());
                return false;
            }
            bot.getActionPack().stopAll();
            BotLog.action(bot, "acquire_water_ascent_step",
                    "from", current.toShortString(), "to", ascentTarget.toShortString());
        }

        AscentCandidate candidate = keepDriftedTarget
                ? driftCandidate
                : inspectAscentCandidate(bot, world, current, ascentTarget);
        if (candidate.foundationMissing()) {
            placeAscentFoundation(bot, current, ascentTarget.below().below());
            return true;
        }
        if (candidate.supportMissing()) {
            placeAscentSupport(bot, current, ascentTarget.below());
            return true;
        }

        BlockPos obstruction = candidate.obstruction();
        if (obstruction != null) {
            ascentPathStarted = false;
            if (!observableCellOrBlock(bot, obstruction)) {
                returnMiner.cancel(bot);
                ascentTarget = null;
                return true;
            }
            var obstructionState = world.getBlockState(obstruction);
            if (obstructionState.requiresCorrectToolForDrops()) {
                int requiredTier = ToolTier.requiredPickaxeTier(obstructionState.getBlock());
                // A low-tier obstruction is tunnel overhead, not expedition loot.  Keep the finite
                // iron/diamond picks for diamond and obsidian: if no renewable healthy stone pick
                // remains, replenish one locally even when a higher-tier pick could technically
                // harvest this block.
                if (requiredTier <= ToolTier.STONE && !hasHealthyStonePickaxe(bot)) {
                    beginAscentToolCraft(bot, obstruction);
                    return true;
                }
                ToolSelector.Selection selection =
                        ToolSelector.equipMiningChannelTool(bot, obstructionState);
                if (selection.slot() < 0 || selection.stack().isEmpty()) {
                    returnMiner.cancel(bot);
                    if (requiredTier > ToolTier.STONE) {
                        fail("acquire_water_ascent_need_tool:"
                                + ToolTier.requiredPickaxeItemId(obstructionState.getBlock()));
                        return true;
                    }
                    beginAscentToolCraft(bot, obstruction);
                    return true;
                }
            }
            if (returnMiner.target() == null || !returnMiner.target().equals(obstruction)) {
                // The physical return tunnel is part of the mining channel: never spend a scarce
                // future iron/diamond pick on ordinary rock, and never fall back to punching a
                // tool-required ore with a crafting table after all stone picks reach one durability.
                returnMiner.begin(bot, obstruction, true);
            }
            BlockMiner.Status status = returnMiner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                BotLog.action(bot, "acquire_water_ascent_mine_failed",
                        "target", obstruction.toShortString(), "reason", returnMiner.failureReason());
                ascentTarget = null;
            }
            return true;
        }

        returnMiner.cancel(bot);
        if (!Standability.isStandable(world, ascentTarget)) {
            BotLog.action(bot, "acquire_water_ascent_path_failed",
                    "from", current.toShortString(), "to", ascentTarget.toShortString(),
                    "reason", "unsafe_landing");
            ascentTarget = null;
            ascentPathStarted = false;
            return true;
        }
        if (!ascentPathStarted) {
            ActionResult result = bot.getActionPack().startSurfacePathTo(ascentTarget);
            if (result.isFailed()) {
                BotLog.action(bot, "acquire_water_ascent_path_failed",
                        "from", current.toShortString(), "to", ascentTarget.toShortString(),
                        "reason", result.reason());
                ascentTarget = null;
                return true;
            }
            ascentPathStarted = true;
            ascentPathStartedBudget = totalBudget();
            return true;
        }
        if (bot.getActionPack().isPathExecutorIdle()
                && totalBudget() - ascentPathStartedBudget > PATH_RETRY_INTERVAL) {
            BotLog.action(bot, "acquire_water_ascent_path_failed",
                    "from", current.toShortString(), "to", ascentTarget.toShortString(),
                    "reason", "ended_before_target");
            ascentTarget = null;
            ascentPathStarted = false;
        }
        return true;
    }

    /**
     * A bot whose body overhangs the edge of its floor (a restart or a cancelled hop left it half over the next cell) stands in a cell
     * that is not standable although it is supported by a neighbour. Every stair candidate and every route search treats the bot's
     * cell as its start, so nothing chosen from there is sound; a route search from such a start only plans the walk onto the
     * neighbouring cell and, when the search then fails, the plan is dropped and the same target is chosen again for the whole budget.
     * The bot first walks (a walked step, inputs only) onto the standable cell it is leaning on. Returns true while that step runs.
     */
    private boolean settleOnStandableCell(AIPlayerEntity bot, ServerLevel world, BlockPos current) {
        if (ascentSettle != null) {
            if (!ascentSettle.ended()) {
                return true;
            }
            ascentSettle = null;
            return false;
        }
        if (Standability.isStandableFresh(world, current) || !WalkedStep.supported(bot)) {
            return false;
        }
        WalkedStep step = bot.getActionPack().adjacentStandableStep("acquire_water_ascent_settle");
        if (step == null) {
            return false;
        }
        returnMiner.cancel(bot);
        bot.getActionPack().stopAll();
        bot.getActionPack().runStep(step);
        ascentSettle = step;
        ascentPathStarted = false;
        ascentTarget = null;
        return true;
    }

    private void prepareAscentLevel(BlockPos current) {
        if (ascentRelocationLevel == current.getY()) {
            return;
        }
        ascentRelocationLevel = current.getY();
        ascentRelocationsAtLevel = 0;
        ascentRelocationPrevious = null;
        failedAscentSupports.clear();
        failedAscentRelocations.clear();
        noteAscentProgress();
    }

    private void noteAscentProgress() {
        lastAscentBlockedPos = null;
        lastAscentBlockedReasons = "";
        lastAscentBlockedLogBudget = totalBudget() - ASCENT_BLOCKED_LOG_INTERVAL;
    }

    private void placeAscentSupport(AIPlayerEntity bot, BlockPos current, BlockPos support) {
        var slot = MaterialPalette.pickPathSupportBlockSlot(bot);
        if (slot.isEmpty()) {
            failedAscentSupports.add(support.immutable());
            ascentTarget = null;
            return;
        }
        String item = String.valueOf(bot.getInventory().getNonEquipmentItems().get(slot.getAsInt()).getItem());
        if (InventoryAction.equipFromSlot(bot, slot.getAsInt()) < 0) {
            failedAscentSupports.add(support.immutable());
            ascentTarget = null;
            BotLog.action(bot, "acquire_water_ascent_support_failed",
                    "pos", support.toShortString(), "reason", "equip_failed");
            return;
        }
        bot.getActionPack().stopAll();
        // A real jump-arc landing can leave the server-side onGround bit stale for one tick even
        // though the new cell has a collision-verified support (the same clientless fake-player
        // quirk placeAscentFoundation already accounts for below). Publish the equivalent movement
        // packet fact before this precise placement; never do this for a genuinely unsupported pose.
        Standability.clearCache();
        if (!bot.onGround() && Standability.isStandable(bot.level(), current)) {
            bot.setOnGround(true);
        }
        ActionResult result = BuildAction.placeBlockAt(bot, support);
        if (result.isFailed()) {
            failedAscentSupports.add(support.immutable());
            ascentTarget = null;
            BotLog.action(bot, "acquire_water_ascent_support_failed",
                    "pos", support.toShortString(), "reason", result.reason());
            return;
        }
        pathAttempts = 0;
        noteAscentProgress();
        BotLog.action(bot, "acquire_water_ascent_support_placed",
                "pos", support.toShortString(), "item", item);
    }

    /**
     * Extends the current pillar by one ordinary sneak-bridged foundation block. An open cave can
     * require more than one rise: after the first support is climbed, every neighbouring landing
     * has both an empty support and an empty base. A real player first bridges at foot level, then
     * places the landing support on top. Keeping those as two verified vanilla placements avoids
     * either reading a hidden block or using the non-survival direct-placement fallback.
     */
    private void placeAscentFoundation(AIPlayerEntity bot,
                                       BlockPos current,
                                       BlockPos foundation) {
        int dx = foundation.getX() - current.getX();
        int dz = foundation.getZ() - current.getZ();
        Direction direction = null;
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (candidate.getStepX() == dx && candidate.getStepZ() == dz) {
                direction = candidate;
                break;
            }
        }
        BlockPos support = foundation.above();
        var slot = MaterialPalette.pickPathSupportBlockSlot(bot);
        if (direction == null || slot.isEmpty()) {
            failedAscentSupports.add(support.immutable());
            ascentTarget = null;
            return;
        }
        int sourceSlot = slot.getAsInt();
        String item = String.valueOf(bot.getInventory().getNonEquipmentItems().get(sourceSlot).getItem());
        if (InventoryAction.equipFromSlot(bot, sourceSlot) < 0) {
            failedAscentSupports.add(support.immutable());
            ascentTarget = null;
            return;
        }
        bot.getActionPack().stopAll();
        // A clientless path jump can leave the server-side onGround bit stale for one tick even
        // though the new cell has a collision-verified support. Publish the equivalent movement
        // packet fact before the bounded sneak shift; never do this for an unsupported pose.
        Standability.clearCache();
        if (!bot.onGround() && Standability.isStandable(bot.level(), current)) {
            bot.setOnGround(true);
        }
        // A real player sneak-bridges by leaning out over the edge (sneaking will not walk off it), placing against the side
        // face of the support, and stepping back: three separate moments, so the bot walks the lean and the return with its
        // movement keys and the placement happens between them (tickEdgePlacement).
        if (!WalkedStep.supported(bot)) {
            // Still settling out of the last hop: the lean starts from solid footing, so ask again next tick.
            return;
        }
        WalkedStep lean = InCellWalk.beginEdgeShift(bot, current, direction, "acquire_water_ascent_foundation");
        if (lean == null) {
            failedAscentSupports.add(support.immutable());
            ascentTarget = null;
            BotLog.action(bot, "acquire_water_ascent_foundation_failed",
                    "pos", foundation.toShortString(), "reason", "foundation_edge_unreachable");
            return;
        }
        edge = new EdgePlacement(current, direction, foundation, item, lean);
    }

    /** Carries the sneak-bridge on: lean over the edge, place the foundation block, walk back to the middle of the cell. */
    private void tickEdgePlacement(AIPlayerEntity bot) {
        var pack = bot.getActionPack();
        EdgePlacement current = edge;
        if (!current.step.ended()) {
            return;
        }
        WalkedStep.Result result = current.step.outcome();
        BlockPos support = current.foundation.above();
        if (current.stage == EdgeStage.SHIFTING) {
            if (result == null || !result.succeeded()) {
                edge = null;
                pack.stopMovement();
                if (result != null && "not_supported".equals(result.reason())) {
                    // The bot left its footing between the request and the first tick (a hop still settling): not a verdict on the
                    // support, so the ascent looks at the cell again.
                    return;
                }
                failedAscentSupports.add(support.immutable());
                ascentTarget = null;
                BotLog.action(bot, "acquire_water_ascent_foundation_failed",
                        "pos", current.foundation.toShortString(), "reason", "foundation_edge_unreachable");
                return;
            }
            ActionResult placed = BuildAction.placeBlock(
                    bot, current.anchor.below(), current.direction, InteractionHand.MAIN_HAND);
            current.placeFailure = placed.isFailed() ? placed.reason() : null;
            current.step = InCellWalk.beginEdgeReturn(bot, current.anchor, "acquire_water_ascent_foundation");
            current.stage = EdgeStage.RETURNING;
            return;
        }
        edge = null;
        pack.stopMovement();
        boolean returned = result != null && result.succeeded();
        if (current.placeFailure != null || !returned) {
            failedAscentSupports.add(support.immutable());
            ascentTarget = null;
            BotLog.action(bot, "acquire_water_ascent_foundation_failed",
                    "pos", current.foundation.toShortString(),
                    "reason", current.placeFailure != null ? current.placeFailure : "foundation_edge_return_failed");
            return;
        }
        pathAttempts = 0;
        noteAscentProgress();
        BotLog.action(bot, "acquire_water_ascent_foundation_placed",
                "pos", current.foundation.toShortString(), "item", current.item);
    }

    private boolean tickAscentRelocation(AIPlayerEntity bot, BlockPos current) {
        if (ascentRelocationTarget == null) {
            return false;
        }
        if (current.equals(ascentRelocationTarget)) {
            bot.getActionPack().stopAll();
            ascentRelocationPrevious = ascentRelocationOrigin;
            BlockPos reached = ascentRelocationTarget;
            ascentRelocationTarget = null;
            ascentRelocationOrigin = null;
            ascentRelocationPathStarted = false;
            ascentRelocationsAtLevel++;
            failedAscentSupports.clear();
            failedAscentRelocations.clear();
            pathAttempts = 0;
            noteAscentProgress();
            BotLog.action(bot, "acquire_water_ascent_relocated",
                    "to", reached.toShortString(),
                    "used", ascentRelocationsAtLevel,
                    "budget", MAX_ASCENT_RELOCATIONS_PER_LEVEL);
            return true;
        }

        // A water pocket can become visible only after the shared jump-arc ceiling is mined. In
        // solid rock that correctly rejects every upward candidate, but there may be no already
        // open same-level cell for the old relocation controller to use. A real player escapes by
        // cutting a short dry side pocket first. Mine its head before its feet, re-check observable
        // fluid evidence after every block, and do not start movement until the exposed support is
        // physically proven standable. The partially cut pocket is factual world state, so pause or
        // restart can safely rediscover it without checkpointing a speculative route.
        if (!ascentRelocationPathStarted) {
            CarvedRelocationCandidate candidate = inspectCarvedAscentRelocation(
                    bot, bot.level(), current, ascentRelocationTarget);
            if (!candidate.accepted()) {
                failAscentRelocation(bot, current, candidate.reason());
                return true;
            }
            BlockPos obstruction = candidate.obstruction();
            if (obstruction != null) {
                mineAscentRelocationObstruction(bot, current, obstruction);
                return true;
            }
            ActionResult result = bot.getActionPack().startSurfacePathTo(ascentRelocationTarget);
            if (result.isFailed()) {
                if (!"pathfinding_throttled".equals(result.reason())) {
                    failAscentRelocation(bot, current, result.reason());
                }
                return true;
            }
            ascentRelocationPathStarted = true;
            ascentRelocationStartedBudget = totalBudget();
            return true;
        }

        if (bot.getActionPack().isPathExecutorIdle()
                && totalBudget() - ascentRelocationStartedBudget > PATH_RETRY_INTERVAL) {
            failAscentRelocation(bot, current, "ended_before_target");
        }
        return true;
    }

    private void mineAscentRelocationObstruction(AIPlayerEntity bot,
                                                 BlockPos current,
                                                 BlockPos obstruction) {
        if (!observableCellOrBlock(bot, obstruction)) {
            failAscentRelocation(bot, current, "obstruction_unobservable");
            return;
        }
        var obstructionState = bot.level().getBlockState(obstruction);
        if (obstructionState.requiresCorrectToolForDrops()) {
            int requiredTier = ToolTier.requiredPickaxeTier(obstructionState.getBlock());
            if (requiredTier <= ToolTier.STONE && !hasHealthyStonePickaxe(bot)) {
                beginAscentToolCraft(bot, obstruction);
                return;
            }
            ToolSelector.Selection selection =
                    ToolSelector.equipMiningChannelTool(bot, obstructionState);
            if (selection.slot() < 0 || selection.stack().isEmpty()) {
                failAscentRelocation(bot, current, "tool_unavailable");
                return;
            }
        }
        if (returnMiner.target() == null || !returnMiner.target().equals(obstruction)) {
            returnMiner.begin(bot, obstruction, true);
        }
        BlockMiner.Status status = returnMiner.tick(bot);
        if (status == BlockMiner.Status.FAILED) {
            failAscentRelocation(bot, current,
                    "mine_failed:" + returnMiner.failureReason());
        }
    }

    private void failAscentRelocation(AIPlayerEntity bot,
                                      BlockPos current,
                                      String reason) {
        returnMiner.cancel(bot);
        BlockPos failed = ascentRelocationTarget;
        if (failed != null) {
            failedAscentRelocations.add(failed.immutable());
        }
        BotLog.action(bot, "acquire_water_ascent_relocation_failed",
                "from", ascentRelocationOrigin == null
                        ? current.toShortString() : ascentRelocationOrigin.toShortString(),
                "to", failed == null ? "none" : failed.toShortString(),
                "reason", reason);
        ascentRelocationTarget = null;
        ascentRelocationOrigin = null;
        ascentRelocationPathStarted = false;
    }

    private boolean beginAscentRelocation(AIPlayerEntity bot,
                                          ServerLevel world,
                                          BlockPos current,
                                          String ascentRejections) {
        if (ascentRelocationsAtLevel >= MAX_ASCENT_RELOCATIONS_PER_LEVEL
                || !bot.getActionPack().isPathExecutorIdle()) {
            return false;
        }
        List<Direction> directions = orderedAscentDirections(current);
        // First exhaust every factual forward/side option. Returning to the immediately previous
        // relocation before trying a new dry pocket creates a two-cell oscillation when both cells
        // share the same water hazard.
        for (Direction direction : directions) {
            BlockPos target = current.relative(direction);
            if (target.equals(ascentRelocationPrevious)
                    || failedAscentRelocations.contains(target)) {
                continue;
            }
            if (startOpenAscentRelocation(
                    bot, world, current, target, ascentRejections)) {
                return true;
            }
        }
        for (Direction direction : directions) {
            BlockPos target = current.relative(direction);
            if (target.equals(ascentRelocationPrevious)
                    || failedAscentRelocations.contains(target)) {
                continue;
            }
            CarvedRelocationCandidate candidate = inspectCarvedAscentRelocation(
                    bot, world, current, target);
            if (candidate.accepted() && candidate.obstruction() != null) {
                beginCarvedAscentRelocation(
                        bot, current, target, candidate.obstruction(), ascentRejections);
                return true;
            }
        }
        // An already proven dry previous cell remains a bounded last resort. Never carve back into
        // it: if its geometry changed, the same observable/standable predicate must prove it again.
        if (ascentRelocationPrevious != null
                && !failedAscentRelocations.contains(ascentRelocationPrevious)
                && startOpenAscentRelocation(bot, world, current,
                ascentRelocationPrevious, ascentRejections)) {
            return true;
        }
        return false;
    }

    private boolean startOpenAscentRelocation(AIPlayerEntity bot,
                                              ServerLevel world,
                                              BlockPos current,
                                              BlockPos target,
                                              String ascentRejections) {
        if (!safeObservableRelocation(bot, world, target)) {
            return false;
        }
        ActionResult result = bot.getActionPack().startSurfacePathTo(target);
        if (!result.isFailed()) {
            ascentRelocationOrigin = current.immutable();
            ascentRelocationTarget = target.immutable();
            ascentRelocationPathStarted = true;
            ascentRelocationStartedBudget = totalBudget();
            BotLog.action(bot, "acquire_water_ascent_relocation",
                    "from", current.toShortString(), "to", target.toShortString(),
                    "ascent_rejections", ascentRejections);
            return true;
        }
        if (!"pathfinding_throttled".equals(result.reason())) {
            failedAscentRelocations.add(target.immutable());
            BotLog.action(bot, "acquire_water_ascent_relocation_failed",
                    "from", current.toShortString(), "to", target.toShortString(),
                    "reason", result.reason());
        }
        return false;
    }

    private void beginCarvedAscentRelocation(AIPlayerEntity bot,
                                             BlockPos current,
                                             BlockPos target,
                                             BlockPos obstruction,
                                             String ascentRejections) {
        ascentRelocationOrigin = current.immutable();
        ascentRelocationTarget = target.immutable();
        ascentRelocationPathStarted = false;
        ascentRelocationStartedBudget = totalBudget();
        BotLog.action(bot, "acquire_water_ascent_relocation_carve",
                "from", current.toShortString(),
                "to", target.toShortString(),
                "obstruction", obstruction.toShortString(),
                "ascent_rejections", ascentRejections);
    }

    private static CarvedRelocationCandidate inspectCarvedAscentRelocation(
            AIPlayerEntity bot,
            ServerLevel world,
            BlockPos current,
            BlockPos target) {
        if (target == null || target.getY() != current.getY()
                || Math.abs(target.getX() - current.getX())
                + Math.abs(target.getZ() - current.getZ()) != 1) {
            return CarvedRelocationCandidate.rejected("not_adjacent_same_level");
        }
        // Head first keeps a solid foot barrier in place while the side pocket is being exposed.
        for (BlockPos cell : new BlockPos[]{target.above(), target}) {
            if (!observableCellOrBlock(bot, cell)) {
                return CarvedRelocationCandidate.rejected(
                        "carve_unobservable@" + cell.toShortString());
            }
            var state = world.getBlockState(cell);
            if (!state.getFluidState().isEmpty()) {
                return CarvedRelocationCandidate.rejected(
                        "carve_fluid@" + cell.toShortString());
            }
            if (Standability.isDangerous(state)) {
                return CarvedRelocationCandidate.rejected(
                        "carve_dangerous@" + cell.toShortString());
            }
            if (hasObservableAdjacentFluid(bot, world, cell)) {
                return CarvedRelocationCandidate.rejected(
                        "carve_adjacent_fluid@" + cell.toShortString());
            }
            if (!state.getCollisionShape(world, cell).isEmpty()
                    && (state.getDestroySpeed(world, cell) < 0.0F
                    || world.getBlockEntity(cell) != null)) {
                return CarvedRelocationCandidate.rejected(
                        "carve_unbreakable@" + cell.toShortString());
            }
            if (!state.getCollisionShape(world, cell).isEmpty()
                    && state.requiresCorrectToolForDrops()
                    && ToolTier.requiredPickaxeTier(state.getBlock()) > ToolTier.STONE) {
                return CarvedRelocationCandidate.rejected(
                        "carve_reserved_tool@" + cell.toShortString());
            }
            if (!state.getCollisionShape(world, cell).isEmpty()) {
                return CarvedRelocationCandidate.accepted(cell.immutable());
            }
        }
        if (!safeObservableRelocation(bot, world, target)) {
            return CarvedRelocationCandidate.rejected("carve_landing_unproven");
        }
        return CarvedRelocationCandidate.accepted(null);
    }

    private record CarvedRelocationCandidate(boolean accepted,
                                               String reason,
                                               BlockPos obstruction) {
        private static CarvedRelocationCandidate accepted(BlockPos obstruction) {
            return new CarvedRelocationCandidate(true, "safe", obstruction);
        }

        private static CarvedRelocationCandidate rejected(String reason) {
            return new CarvedRelocationCandidate(false, reason, null);
        }
    }

    private static boolean safeObservableRelocation(AIPlayerEntity bot,
                                                     ServerLevel world,
                                                     BlockPos target) {
        return observableStandCell(bot, target)
                && Standability.isStandable(world, target)
                && !hasObservableAdjacentFluid(bot, world, target)
                && !hasObservableAdjacentFluid(bot, world, target.above());
    }

    private void maybeLogAscentBlocked(AIPlayerEntity bot, BlockPos current, String reasons) {
        boolean changed = !current.equals(lastAscentBlockedPos)
                || !reasons.equals(lastAscentBlockedReasons);
        if (!changed && totalBudget() - lastAscentBlockedLogBudget < ASCENT_BLOCKED_LOG_INTERVAL) {
            return;
        }
        lastAscentBlockedPos = current.immutable();
        lastAscentBlockedReasons = reasons;
        lastAscentBlockedLogBudget = totalBudget();
        BotLog.action(bot, "acquire_water_ascent_blocked",
                "at", current.toShortString(), "reasons", reasons);
    }

    private static boolean hasHealthyStonePickaxe(AIPlayerEntity bot) {
        for (var stack : bot.getInventory().getNonEquipmentItems()) {
            if (ToolTier.pickaxeTier(stack) == ToolTier.STONE) {
                return true;
            }
        }
        if (ToolTier.pickaxeTier(bot.getItemBySlot(EquipmentSlot.OFFHAND)) == ToolTier.STONE) {
            return true;
        }
        return false;
    }

    private void beginAscentToolCraft(AIPlayerEntity bot, BlockPos obstruction) {
        returnMiner.cancel(bot);
        int desired = InventoryAction.countItem(bot, Items.STONE_PICKAXE) + 1;
        ascentToolCraft = new CraftTask(Items.STONE_PICKAXE, desired);
        ascentToolCraft.start(bot);
        BotLog.action(bot, "acquire_water_ascent_tool_replenish",
                "target", obstruction.toShortString(),
                "item", Items.STONE_PICKAXE,
                "desired", desired);
    }

    private void tickAscentToolCraft(AIPlayerEntity bot) {
        CraftTask child = ascentToolCraft;
        child.tick(bot);
        if (child.state() == TaskState.COMPLETED) {
            ascentToolCraft = null;
            BotLog.action(bot, "acquire_water_ascent_tool_ready",
                    "item", Items.STONE_PICKAXE,
                    "count", InventoryAction.countItem(bot, Items.STONE_PICKAXE));
            return;
        }
        if (child.state() == TaskState.FAILED || child.state() == TaskState.CANCELLED) {
            String reason = child.failureReason();
            ascentToolCraft = null;
            fail("acquire_water_ascent_tool_unavailable:minecraft:stone_pickaxe:" + reason);
        }
    }

    private AscentChoice selectAscentTarget(AIPlayerEntity bot,
                                            ServerLevel world,
                                            BlockPos current) {
        List<Direction> directions = orderedAscentDirections(current);
        List<String> rejections = new ArrayList<>();
        BlockPos supportFallback = null;
        for (Direction direction : directions) {
            BlockPos candidate = current.relative(direction).above();
            AscentCandidate inspected = inspectAscentCandidate(bot, world, current, candidate);
            if (!inspected.accepted()) {
                rejections.add(direction.getSerializedName() + ":" + inspected.reason());
                continue;
            }
            if (!inspected.supportMissing()) {
                return new AscentChoice(candidate.immutable(), String.join(",", rejections));
            }
            if (failedAscentSupports.contains(candidate.below())) {
                rejections.add(direction.getSerializedName() + ":support_place_failed");
            } else if (supportFallback == null
                    && MaterialPalette.pickPathSupportBlockSlot(bot).isPresent()) {
                supportFallback = candidate.immutable();
            } else {
                rejections.add(direction.getSerializedName() + ":support_missing");
            }
        }
        if (supportFallback != null) {
            return new AscentChoice(supportFallback, String.join(",", rejections));
        }
        return new AscentChoice(null, String.join(",", rejections));
    }

    private List<Direction> orderedAscentDirections(BlockPos current) {
        List<Direction> directions = new ArrayList<>(List.of(HORIZONTAL));
        directions.sort(Comparator.comparingDouble(direction -> {
            BlockPos candidate = current.relative(direction).above();
            double dx = candidate.getX() - surfaceAnchor.getX();
            double dz = candidate.getZ() - surfaceAnchor.getZ();
            return dx * dx + dz * dz;
        }));
        return directions;
    }

    private static AscentCandidate inspectAscentCandidate(AIPlayerEntity bot,
                                                           ServerLevel world,
                                                           BlockPos current,
                                                           BlockPos target) {
        if (!isAdjacentUp(current, target)) {
            return AscentCandidate.rejected("not_adjacent_up");
        }

        // Inspect and remove the visible jump-arc obstruction before asking for the landing
        // support. In a natural two-block-high tunnel the solid block at {@code target} hides the
        // top/side faces of {@code target.down()}; requiring the support first therefore rejected
        // every ordinary stone stair as support_unobservable and left RETURN_SURFACE motionless.
        // Mining only the first observable obstruction exposes the support on the next tick, where
        // the normal support/fluid checks below still fail closed before any movement or placement.
        for (BlockPos cell : new BlockPos[]{current.above(2), target, target.above()}) {
            if (!observableCellOrBlock(bot, cell)) {
                return AscentCandidate.rejected("arc_unobservable@" + cell.toShortString());
            }
            var state = world.getBlockState(cell);
            if (!state.getFluidState().isEmpty()) {
                return AscentCandidate.rejected("arc_fluid@" + cell.toShortString());
            }
            if (Standability.isDangerous(state)) {
                return AscentCandidate.rejected("arc_dangerous@" + cell.toShortString());
            }
            if (!state.getCollisionShape(world, cell).isEmpty()
                    && state.getDestroySpeed(world, cell) < 0.0F) {
                return AscentCandidate.rejected("arc_unbreakable@" + cell.toShortString());
            }
            if (!state.getCollisionShape(world, cell).isEmpty()
                    && world.getBlockEntity(cell) != null) {
                return AscentCandidate.rejected("arc_block_entity@" + cell.toShortString());
            }
            if (hasObservableAdjacentFluid(bot, world, cell)) {
                return AscentCandidate.rejected("arc_adjacent_fluid@" + cell.toShortString());
            }
            if (!state.getCollisionShape(world, cell).isEmpty()) {
                return new AscentCandidate(true, false, false,
                        "visible_obstruction", cell.immutable());
            }
        }

        BlockPos support = target.below();
        if (!observableCellOrBlock(bot, support)) {
            return AscentCandidate.rejected("support_unobservable");
        }
        var supportState = world.getBlockState(support);
        if (!supportState.getFluidState().isEmpty()) {
            return AscentCandidate.rejected("support_fluid");
        }
        if (Standability.isDangerous(supportState)) {
            return AscentCandidate.rejected("support_dangerous");
        }
        boolean supportMissing = supportState.getCollisionShape(world, support).isEmpty();
        boolean foundationMissing = false;
        if (supportMissing) {
            BlockPos base = support.below();
            if (!supportState.canBeReplaced()) {
                return AscentCandidate.rejected("support_not_replaceable");
            }
            if (!observableCellOrBlock(bot, base)) {
                return AscentCandidate.rejected("support_unobservable");
            }
            var baseState = world.getBlockState(base);
            if (!baseState.getFluidState().isEmpty()
                    || Standability.isDangerous(baseState)) {
                return AscentCandidate.rejected("support_base_unsafe");
            }
            if (baseState.getCollisionShape(world, base).isEmpty()) {
                if (!baseState.canBeReplaced()) {
                    return AscentCandidate.rejected("support_base_unsafe");
                }
                foundationMissing = true;
            }
        }
        return new AscentCandidate(true, supportMissing, foundationMissing,
                supportMissing ? "support_missing" : "safe", null);
    }

    private record AscentChoice(BlockPos target, String rejections) {
    }

    private record AscentCandidate(boolean accepted,
                                   boolean supportMissing,
                                   boolean foundationMissing,
                                   String reason,
                                   BlockPos obstruction) {
        private static AscentCandidate rejected(String reason) {
            return new AscentCandidate(false, false, false, reason, null);
        }
    }

    private static boolean isAdjacentUp(BlockPos from, BlockPos to) {
        return from != null && to != null
                && to.getY() - from.getY() == 1
                && Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()) == 1;
    }

    private static boolean hasObservableAdjacentFluid(AIPlayerEntity bot,
                                                      ServerLevel world,
                                                      BlockPos cell) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = cell.relative(direction);
            if (observableCellOrBlock(bot, adjacent)
                    && !world.getFluidState(adjacent).isEmpty()) {
                return true;
            }
        }
        return observableCellOrBlock(bot, cell)
                && !world.getFluidState(cell).isEmpty();
    }

    private static boolean observableCellOrBlock(AIPlayerEntity bot, BlockPos pos) {
        return ObservableWorldQuery.canObserveCell(bot, pos)
                || ObservableWorldQuery.canObserveBlock(bot, pos);
    }

    private void search(AIPlayerEntity bot) {
        if (totalBudget() - lastScanBudget >= SCAN_INTERVAL) {
            lastScanBudget = totalBudget();
            if (lockObservableWater(bot)) {
                return;
            }
        }
        if (searchWaypoint != null && bot.blockPosition().equals(searchWaypoint)) {
            bot.getActionPack().stopAll();
            searchWaypoint = null;
            pathAttempts = 0;
            waypointStartedBudget = totalBudget();
            consecutiveUnreachableWaypoints = 0;
            unreachableAnchor = null;
            reachedWaypoints = Math.min(issuedWaypoints, reachedWaypoints + 1);
            // A waypoint is an observation post, not merely a loose navigation hint. Scan before
            // advancing the spiral so an elevated rim or ridge is actually used after the bot
            // reaches it; the periodic scan alone can otherwise fire just before arrival and let
            // the bot immediately walk away from the newly visible source.
            lastScanBudget = totalBudget();
            if (lockObservableWater(bot)) {
                return;
            }
        }
        if (searchWaypoint == null) {
            if (issuedWaypoints >= waypointLimit) {
                fail(searchExhaustedReason());
                return;
            }
            searchWaypoint = advanceSearchCursor();
            issuedWaypoints++;
            waypointStartedBudget = totalBudget();
            BotLog.action(bot, "acquire_water_search_waypoint",
                    "index", issuedWaypoints,
                    "observed", reachedWaypoints,
                    "pos", searchWaypoint.toShortString());
        }
        // This is an absolute waypoint deadline. Moving between two cells or receiving a fresh
        // PathExecutor replan is not search progress and therefore cannot extend the deadline.
        if (totalBudget() - waypointStartedBudget >= PATH_STALL_LIMIT) {
            skipSearchWaypoint(bot, "path_stalled");
            return;
        }
        retryPath(bot, searchWaypoint, true, true);
    }

    private boolean lockObservableWater(AIPlayerEntity bot) {
        for (int attempt = 0; attempt < 12; attempt++) {
            BlockPos source = nearestObservableWaterSource(bot);
            if (source == null) {
                return false;
            }
            BlockPos stand = findObservableWaterStand(bot, source);
            if (stand == null) {
                reject(source);
                continue;
            }
            bot.getActionPack().stopAll();
            waterSource = source;
            waterStand = stand;
            searchWaypoint = null;
            pathAttempts = 0;
            waypointStartedBudget = totalBudget();
            consecutiveUnreachableWaypoints = 0;
            unreachableAnchor = null;
            BotLog.action(bot, "acquire_water_source_found",
                    "source", source.toShortString(), "stand", stand.toShortString());
            enter(Phase.APPROACH);
            return true;
        }
        return false;
    }

    private void approach(AIPlayerEntity bot) {
        if (waterSource == null || waterStand == null) {
            fail("acquire_water_checkpoint_missing_target");
            return;
        }
        if (ObservableWorldQuery.canObserveCell(bot, waterSource)) {
            var fluid = bot.level().getFluidState(waterSource);
            if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) {
                rejectAndResumeSearch(bot, "source_changed");
                return;
            }
        }
        // The selected stand is reach-safe only from its exact feet cell. The generic surface
        // "near" threshold also accepts cells two blocks away and used to stop a live descent
        // before the bot was actually close enough to perform the vanilla bucket interaction.
        if (!bot.blockPosition().equals(waterStand)) {
            if (phaseAge() > APPROACH_LIMIT) {
                rejectAndResumeSearch(bot, "stand_unreachable");
                return;
            }
            retryPath(bot, waterStand, false);
            return;
        }
        if (!bot.onGround()) {
            // A real jump/fall arc's block position can match the target one tick before the bot
            // has actually landed there (the genuine multi-tick physics fix, unlike the old instant
            // teleport that always settled onGround the same instant its position changed).
            // Interrupting that landing and judging bucket-interaction reach from a still-airborne
            // eye height wrongly rejected a perfectly reachable source; let the landing finish
            // first, bounded by the same APPROACH_LIMIT watchdog as an unreachable stand.
            if (phaseAge() > APPROACH_LIMIT) {
                rejectAndResumeSearch(bot, "stand_unreachable");
            }
            return;
        }
        bot.getActionPack().stopAll();
        ActionResult result = BucketAction.fillWaterSource(bot, waterSource);
        if (result.isFailed()) {
            rejectAndResumeSearch(bot, result.reason());
            return;
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) <= 0) {
            fail("acquire_water_fill_without_water_bucket");
            return;
        }
        BotLog.action(bot, "acquire_water_completed", "source", waterSource.toShortString());
        finish(bot);
    }

    private void retryPath(AIPlayerEntity bot, BlockPos target, boolean updateResolvedSearchGoal) {
        retryPath(bot, target, updateResolvedSearchGoal, false);
    }

    private void retryPath(AIPlayerEntity bot, BlockPos target, boolean updateResolvedSearchGoal,
                           boolean surfaceOnly) {
        // A deep return cannot become reachable by asking the same cached old-anchor A* question
        // hundreds of times. The local stair/support/relocation controller owns physical progress;
        // keep only a small ordinary-path escape hatch for a cave that already connects upward.
        if (phase == Phase.RETURN_SURFACE && pathAttempts >= 3) {
            return;
        }
        if (!bot.getActionPack().isPathExecutorIdle()
                || totalBudget() - lastPathAttemptBudget < PATH_RETRY_INTERVAL) {
            return;
        }
        lastPathAttemptBudget = totalBudget();
        ActionResult result = surfaceOnly
                ? bot.getActionPack().startSurfacePathTo(target)
                : bot.getActionPack().startPathTo(target);
        if (result.isFailed()) {
            if (!"pathfinding_throttled".equals(result.reason())) {
                pathAttempts = Math.min(MAX_RESTORABLE_PATH_ATTEMPTS, pathAttempts + 1);
            }
            BotLog.action(bot, "acquire_water_path_retry",
                    "phase", phase, "target", target.toShortString(), "reason", result.reason());
            if (phase == Phase.SEARCH && pathAttempts >= 3) {
                if (result.reason().contains("GOAL_UNREACHABLE")
                        && noteUnreachableWaypoint(bot)) {
                    return;
                }
                skipSearchWaypoint(bot, result.reason().contains("GOAL_UNREACHABLE")
                        ? "goal_unreachable" : "path_start_failed");
            }
            return;
        }
        pathAttempts = 0;
        if (updateResolvedSearchGoal && bot.getActionPack().activePathGoal() != null) {
            // Keep waypointStartedBudget unchanged: endpoint snapping is part of the same issued
            // observation attempt and must not grant a new 400-tick window.
            searchWaypoint = bot.getActionPack().activePathGoal().immutable();
        }
    }

    private void skipSearchWaypoint(AIPlayerEntity bot, String reason) {
        bot.getActionPack().stopAll();
        BotLog.action(bot, "acquire_water_search_skip",
                "pos", searchWaypoint == null ? "none" : searchWaypoint.toShortString(),
                "reason", reason,
                "attempted", issuedWaypoints,
                "observed", reachedWaypoints);
        searchWaypoint = null;
        pathAttempts = 0;
        waypointStartedBudget = totalBudget();
        if (!"goal_unreachable".equals(reason)) {
            consecutiveUnreachableWaypoints = 0;
            unreachableAnchor = null;
        }
    }

    /** Returns true after owning the current waypoint boundary (recovered or terminal). */
    private boolean noteUnreachableWaypoint(AIPlayerEntity bot) {
        BlockPos current = bot.blockPosition().immutable();
        if (unreachableAnchor == null
                || horizontalSquared(current, unreachableAnchor) > UNREACHABLE_RELOCATION_SQUARED) {
            unreachableAnchor = current;
            consecutiveUnreachableWaypoints = 1;
        } else {
            consecutiveUnreachableWaypoints = Math.min(
                    MAX_UNREACHABLE_WAYPOINTS, consecutiveUnreachableWaypoints + 1);
        }
        if (consecutiveUnreachableWaypoints < MAX_UNREACHABLE_WAYPOINTS) {
            return false;
        }
        // Three goals on one square-spiral edge can all sit beyond the same ridge, ravine, or
        // shelter-altered contour even though the bot still has a factual surface route in another
        // direction.  That is a rejected search sector, not proof that the surface expedition is
        // sealed.  Keep the checkpoint-owned waypoint/time bounds, clear only this local ledger,
        // and let the cursor turn the corner.  A genuinely enclosed bot still fails below because
        // recovery requires a bounded, observable three-step runway, not merely another cell in
        // the same room or tunnel.
        if (recoverBlockedSearchSector(bot, "live")) {
            return true;
        }
        bot.getActionPack().stopAll();
        fail(noReachableSurfaceRouteReason(current));
        return true;
    }

    private boolean recoverBlockedSearchSector(AIPlayerEntity bot, String boundary) {
        ServerLevel world = bot.level();
        BlockPos current = bot.blockPosition();
        Standability.clearCache();
        if (!world.getFluidState(current).isEmpty()
                || !world.getFluidState(current.above()).isEmpty()
                || !Standability.isStandable(world, current)
                // SEARCH can be paused by shelter/combat and resumed somewhere other than the
                // originally attested exit.  The durable latch alone must not turn one indoor
                // neighbor into authority to erase a terminal unreachable ledger.  A short,
                // observable runway proves an alternate local route without requiring hidden
                // column reads under a tree canopy or overhang.
                || !hasObservableSurfaceRunway(bot, world, current)) {
            return false;
        }
        BotLog.action(bot, "acquire_water_blocked_sector_skipped",
                "boundary", boundary,
                "at", current.toShortString(),
                "consecutive", consecutiveUnreachableWaypoints,
                "attempted", issuedWaypoints,
                "observed", reachedWaypoints);
        bot.getActionPack().stopAll();
        searchWaypoint = null;
        pathAttempts = 0;
        waypointStartedBudget = totalBudget();
        lastPathAttemptBudget = totalBudget() - PATH_RETRY_INTERVAL;
        consecutiveUnreachableWaypoints = 0;
        unreachableAnchor = null;
        return true;
    }

    private static boolean hasObservableSurfaceRunway(AIPlayerEntity bot,
                                                       ServerLevel world,
                                                       BlockPos origin) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        open.add(origin.immutable());
        visited.add(origin.immutable());
        int inspected = 0;
        while (!open.isEmpty() && inspected < BLOCKED_SECTOR_PROOF_MAX_NODES) {
            BlockPos current = open.removeFirst();
            inspected++;
            int dx = Math.abs(current.getX() - origin.getX());
            int dz = Math.abs(current.getZ() - origin.getZ());
            if (Math.max(dx, dz) >= BLOCKED_SECTOR_RUNWAY_RADIUS) {
                return true;
            }
            for (Direction direction : HORIZONTAL) {
                BlockPos sameLevel = current.relative(direction);
                if (observableDryStandCell(bot, world, sameLevel)
                        && visited.add(sameLevel.immutable())) {
                    open.addLast(sameLevel.immutable());
                }

                BlockPos uphill = sameLevel.above();
                if (!observableDryStandCell(bot, world, uphill)
                        || !ObservableWorldQuery.canObserveCell(bot, current.above())
                        || !ObservableWorldQuery.canObserveCell(bot, current.above(2))) {
                    continue;
                }
                // observableDryStandCell(uphill) gates the front/support block before this read.
                var frontState = world.getBlockState(sameLevel);
                if (frontState.getCollisionShape(world, sameLevel).isEmpty()
                        || frontState.getCollisionShape(world, sameLevel)
                        .max(Direction.Axis.Y) > 1.0D
                        || !world.getBlockState(current.above())
                        .getCollisionShape(world, current.above()).isEmpty()
                        || !world.getBlockState(current.above(2))
                        .getCollisionShape(world, current.above(2)).isEmpty()) {
                    continue;
                }
                if (visited.add(uphill.immutable())) {
                    open.addLast(uphill.immutable());
                }
            }
        }
        return false;
    }

    private static boolean observableDryStandCell(AIPlayerEntity bot,
                                                   ServerLevel world,
                                                   BlockPos candidate) {
        if (!observableStandCell(bot, candidate)) {
            return false;
        }
        return world.getFluidState(candidate).isEmpty()
                && world.getFluidState(candidate.above()).isEmpty()
                && Standability.isStandable(world, candidate);
    }

    private BlockPos advanceSearchCursor() {
        int dx;
        int dz;
        switch (directionIndex) {
            case 0 -> { dx = 1; dz = 0; }
            case 1 -> { dx = 0; dz = 1; }
            case 2 -> { dx = -1; dz = 0; }
            default -> { dx = 0; dz = -1; }
        }
        gridX += dx;
        gridZ += dz;
        stepInLeg++;
        if (stepInLeg >= legLength) {
            stepInLeg = 0;
            directionIndex = (directionIndex + 1) & 3;
            repeatedLegs++;
            if (repeatedLegs >= 2) {
                repeatedLegs = 0;
                legLength++;
            }
        }
        return searchOrigin.offset(gridX * SEARCH_STEP, 0, gridZ * SEARCH_STEP).immutable();
    }

    private BlockPos nearestObservableWaterSource(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        int range = Math.min(16, Math.max(1, MinecraftAiConfig.get().perception().radius()));
        int minY = Math.max(world.getMinY(), origin.getY() - range);
        int maxY = Math.min(world.getMinY() + world.getHeight() - 1, origin.getY() + range);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = origin.getX() - range; x <= origin.getX() + range; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = origin.getZ() - range; z <= origin.getZ() + range; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    double distance = origin.distSqr(candidate);
                    if (distance >= bestDistance || distance > (double) range * range
                            || rejectedSources.contains(candidate)
                            || !ObservableWorldQuery.canObserveCell(bot, candidate)) {
                        continue;
                    }
                    // The fluid read is intentionally after the strict line-of-sight gate above.
                    var fluid = world.getFluidState(candidate);
                    if (fluid.is(FluidTags.WATER) && fluid.isSource()) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos findObservableWaterStand(AIPlayerEntity bot, BlockPos source) {
        ServerLevel world = bot.level();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        double reach = Math.max(0.0D,
                bot.blockInteractionRange() - INTERACTION_REACH_EPSILON);
        double reachSquared = reach * reach;
        for (int dy = -2; dy <= 3; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int horizontalSquared = dx * dx + dz * dz;
                    if (horizontalSquared < 1 || horizontalSquared > 16) {
                        continue;
                    }
                    BlockPos candidate = source.offset(dx, dy, dz);
                    if (!observableStandCell(bot, candidate)) {
                        continue;
                    }
                    // World reads happen only after feet/head/support were all observable.
                    if (!Standability.isStandable(world, candidate)
                            || !world.getFluidState(candidate).isEmpty()
                            || !world.getFluidState(candidate.above()).isEmpty()) {
                        continue;
                    }
                    Vec3 eye = Vec3.atBottomCenterOf(candidate).add(0.0D, 1.62D, 0.0D);
                    if (eye.distanceToSqr(source.getCenter()) > reachSquared) {
                        continue;
                    }
                    // Being within reach and able to see the STAND cell itself says nothing about
                    // whether the water block's face would actually be visible from there -- an
                    // intervening wall (e.g. a walled-well's curb) can sit directly between a
                    // nearby, perfectly standable cell and the source. BucketAction's own real fill
                    // attempt later performs exactly this SOURCE_ONLY ray from the bot's true eye;
                    // simulate it here from the CANDIDATE's would-be eye so a stand this task itself
                    // offers is one the real interaction can actually honor, instead of discovering
                    // the obstruction only after physically walking there and being rejected.
                    if (!hasVisibleWaterFace(bot, eye, source)) {
                        continue;
                    }
                    double distance = bot.blockPosition().distSqr(candidate);
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Mirrors BucketAction.fillWaterSource's real SOURCE_ONLY ray, but from a hypothetical eye
     * position instead of the bot's actual one -- lets a candidate stand be pre-validated before
     * ever walking there.
     */
    private static boolean hasVisibleWaterFace(AIPlayerEntity bot, Vec3 eye, BlockPos source) {
        var hit = bot.level().clip(new ClipContext(
                eye,
                source.getCenter(),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY,
                bot));
        return hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(source);
    }

    private static boolean observableStandCell(AIPlayerEntity bot, BlockPos candidate) {
        return ObservableWorldQuery.canObserveCell(bot, candidate)
                && ObservableWorldQuery.canObserveCell(bot, candidate.above())
                && ObservableWorldQuery.canObserveCollider(bot, candidate.below());
    }

    private void rejectAndResumeSearch(AIPlayerEntity bot, String reason) {
        if (waterSource != null) {
            reject(waterSource);
        }
        BotLog.action(bot, "acquire_water_source_rejected",
                "source", waterSource == null ? "none" : waterSource.toShortString(),
                "reason", reason);
        bot.getActionPack().stopAll();
        waterSource = null;
        waterStand = null;
        pathAttempts = 0;
        enter(Phase.SEARCH);
    }

    private void reject(BlockPos source) {
        if (rejectedSources.size() >= MAX_REJECTED_SOURCES) {
            fail("acquire_water_too_many_rejected_sources");
            return;
        }
        rejectedSources.add(source.immutable());
    }

    private void enter(Phase next) {
        phase = next;
        phaseStartedBudget = totalBudget();
        if (next == Phase.SEARCH) {
            waypointStartedBudget = totalBudget();
        }
    }

    private int phaseAge() {
        return totalBudget() - phaseStartedBudget;
    }

    private int totalBudget() {
        return elapsedOffset + elapsed;
    }

    private String timeoutReason() {
        return "acquire_water_timeout attempted=" + issuedWaypoints
                + " observed=" + reachedWaypoints;
    }

    private String searchExhaustedReason() {
        return "acquire_water_search_exhausted attempted=" + issuedWaypoints
                + " observed=" + reachedWaypoints;
    }

    private String noReachableSurfaceRouteReason(BlockPos current) {
        BlockPos at = unreachableAnchor == null ? current : unreachableAnchor;
        return "acquire_water_no_reachable_surface_route at=" + at.toShortString()
                + " consecutive=" + consecutiveUnreachableWaypoints;
    }

    private static double horizontalSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return (double) dx * dx + (double) dz * dz;
    }

    private void finish(AIPlayerEntity bot) {
        clearReturnSurfaceWork(bot, "acquire_water_completed");
        phase = Phase.DONE;
        complete();
    }

    private static boolean near(BlockPos first, BlockPos second) {
        return first != null && second != null && first.distSqr(second) <= ARRIVE_SQUARED;
    }

    @Override
    public Map<String, String> checkpoint() {
        // A rejected restore cannot attest any successor state. Returning invented defaults here
        // would replace the only durable copy of the malformed or exhausted input in GoalExecutor.
        if (invalidCheckpoint) {
            return Map.of();
        }
        BlockPos anchor = surfaceAnchor == null ? requestedSurfaceAnchor : surfaceAnchor;
        int durableBudget = Math.min(totalBudget(), elapsedLimit);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", String.valueOf(CHECKPOINT_SCHEMA));
        values.put("phase", phase.name());
        values.put("surface_anchor", encode(anchor));
        values.put("search_origin", encode(searchOrigin == null ? anchor : searchOrigin));
        values.put("surface_exit", String.valueOf(surfaceExitReached));
        values.put("direction", String.valueOf(directionIndex));
        values.put("leg_length", String.valueOf(legLength));
        values.put("step_in_leg", String.valueOf(stepInLeg));
        values.put("repeated_legs", String.valueOf(repeatedLegs));
        values.put("grid_x", String.valueOf(gridX));
        values.put("grid_z", String.valueOf(gridZ));
        values.put("issued", String.valueOf(issuedWaypoints));
        values.put("reached", String.valueOf(reachedWaypoints));
        values.put("waypoint_limit", String.valueOf(waypointLimit));
        values.put("budget_limit", String.valueOf(elapsedLimit));
        values.put("path_attempts", String.valueOf(pathAttempts));
        values.put("budget_used", String.valueOf(durableBudget));
        values.put("phase_started", String.valueOf(
                Math.min(Math.max(0, phaseStartedBudget), durableBudget)));
        values.put("waypoint_started_budget", String.valueOf(
                Math.min(Math.max(0, waypointStartedBudget), durableBudget)));
        values.put("consecutive_unreachable", String.valueOf(consecutiveUnreachableWaypoints));
        putPos(values, "waypoint", searchWaypoint);
        putPos(values, "water_source", waterSource);
        putPos(values, "water_stand", waterStand);
        putPos(values, "unreachable_anchor", unreachableAnchor);
        if (!rejectedSources.isEmpty()) {
            values.put("rejected", rejectedSources.stream()
                    .map(AcquireWaterTask::encode)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(";")));
        }
        return Map.copyOf(values);
    }

    private static void putPos(Map<String, String> values, String key, BlockPos pos) {
        if (pos != null) {
            values.put(key, encode(pos));
        }
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<BlockPos> decodePos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private record SearchCheckpoint(Phase phase,
                                    BlockPos surfaceAnchor,
                                    BlockPos searchOrigin,
                                    boolean surfaceExitReached,
                                    BlockPos searchWaypoint,
                                    BlockPos waterSource,
                                    BlockPos waterStand,
                                    int directionIndex,
                                    int legLength,
                                    int stepInLeg,
                                    int repeatedLegs,
                                    int gridX,
                                    int gridZ,
                                    int issuedWaypoints,
                                    int reachedWaypoints,
                                    int waypointLimit,
                                    int elapsedLimit,
                                    int pathAttempts,
                                    int budgetUsed,
                                    int phaseStartedBudget,
                                    int waypointStartedBudget,
                                    int consecutiveUnreachableWaypoints,
                                    BlockPos unreachableAnchor,
                                    Set<BlockPos> rejectedSources) {
        private SearchCheckpoint {
            surfaceAnchor = surfaceAnchor.immutable();
            searchOrigin = searchOrigin.immutable();
            searchWaypoint = immutable(searchWaypoint);
            waterSource = immutable(waterSource);
            waterStand = immutable(waterStand);
            unreachableAnchor = immutable(unreachableAnchor);
            rejectedSources = Set.copyOf(rejectedSources);
        }

        private static Optional<SearchCheckpoint> decode(Map<String, String> values) {
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            try {
                int schema = Integer.parseInt(values.getOrDefault("schema", "-1"));
                boolean schemaTwo = schema == LEGACY_CHECKPOINT_SCHEMA;
                boolean previous = schema == PREVIOUS_CHECKPOINT_SCHEMA;
                if (!schemaTwo && !previous && schema != CHECKPOINT_SCHEMA) {
                    return Optional.empty();
                }
                Phase phase = Phase.valueOf(values.getOrDefault("phase", ""));
                BlockPos anchor = decodePos(values.get("surface_anchor")).orElse(null);
                BlockPos searchOrigin = decodePos(values.get("search_origin")).orElse(null);
                String surfaceExitValue = values.get("surface_exit");
                if (!"true".equals(surfaceExitValue) && !"false".equals(surfaceExitValue)) {
                    return Optional.empty();
                }
                boolean surfaceExit = Boolean.parseBoolean(surfaceExitValue);
                // Schema 2 could enter SEARCH beside the remembered anchor without proving a
                // reusable surface egress. That phase is an artifact of the historical bypass,
                // not evidence that can mint a true latch. Both schema generations therefore use
                // the same fail-closed phase/latch shape below; only truthful legacy checkpoints
                // may receive the cursor/budget migrations that follow.
                int direction = Integer.parseInt(values.getOrDefault("direction", "-1"));
                int legLength = Integer.parseInt(values.getOrDefault("leg_length", "0"));
                int stepInLeg = Integer.parseInt(values.getOrDefault("step_in_leg", "-1"));
                int repeated = Integer.parseInt(values.getOrDefault("repeated_legs", "-1"));
                int gridX = Integer.parseInt(values.getOrDefault("grid_x", String.valueOf(Integer.MIN_VALUE)));
                int gridZ = Integer.parseInt(values.getOrDefault("grid_z", String.valueOf(Integer.MIN_VALUE)));
                int issued = Integer.parseInt(values.getOrDefault(
                        schemaTwo ? "visited" : "issued", "-1"));
                // Schema 2 counted waypoint issuance as "visited" and therefore cannot attest a
                // physical observation. Preserve the cursor but start its truthful reached count
                // at zero during the one-time migration.
                int reached = schemaTwo ? 0
                        : Integer.parseInt(values.getOrDefault("reached", "-1"));
                int waypointLimit = schemaTwo || previous
                        ? LEGACY_MAX_WAYPOINTS
                        : Integer.parseInt(values.getOrDefault("waypoint_limit", "-1"));
                int elapsedLimit = schemaTwo || previous
                        ? LEGACY_MAX_ELAPSED
                        : Integer.parseInt(values.getOrDefault("budget_limit", "-1"));
                int attempts = Integer.parseInt(values.getOrDefault("path_attempts", "-1"));
                int encodedBudget = Integer.parseInt(values.getOrDefault("budget_used", "-1"));
                // Schema 2 failed at LEGACY_MAX_ELAPSED+1 and then rejected its own terminal
                // checkpoint. Accept that one historical edge and clamp it into its own authority
                // domain before publishing schema 4.
                int budget = schemaTwo && encodedBudget == LEGACY_MAX_ELAPSED + 1
                        ? LEGACY_MAX_ELAPSED : encodedBudget;
                int phaseStarted = Integer.parseInt(values.getOrDefault("phase_started", "-1"));
                int waypointStarted = schemaTwo ? budget : Integer.parseInt(
                        values.getOrDefault("waypoint_started_budget", "-1"));
                int consecutiveUnreachable = schemaTwo ? 0 : Integer.parseInt(
                        values.getOrDefault("consecutive_unreachable", "-1"));
                boolean hasWaypoint = values.containsKey("waypoint");
                // Parse the persisted value so a malformed marker still fails closed, but never
                // restore its coordinates.  A live surface path may resolve the nominal post to a
                // nearby standable cell, and that process-local result is not durable authority.
                // The cursor has already advanced when an issued waypoint is checkpointed, so the
                // only restartable target is reconstructed below from searchOrigin + grid.
                decodeOptional(values, "waypoint");
                BlockPos source = decodeOptional(values, "water_source");
                BlockPos stand = decodeOptional(values, "water_stand");
                BlockPos unreachableAnchor = schemaTwo ? null
                        : decodeOptional(values, "unreachable_anchor");
                Set<BlockPos> rejected = decodePositions(values.get("rejected"));
                boolean targetShape = phase != Phase.APPROACH || source != null && stand != null;
                boolean surfacePhaseShape = surfaceExit
                        || (phase != Phase.SEARCH && phase != Phase.APPROACH);
                boolean limitPair = (waypointLimit == LEGACY_MAX_WAYPOINTS
                        && elapsedLimit == LEGACY_MAX_ELAPSED)
                        || (waypointLimit == MAX_WAYPOINTS && elapsedLimit == MAX_ELAPSED);
                boolean issuedInBounds = issued >= 0 && issued <= waypointLimit;
                SearchCursor expectedCursor = issuedInBounds
                        ? SearchCursor.afterIssued(issued) : null;
                boolean cursorShape = expectedCursor != null
                        && expectedCursor.matches(direction, legLength, stepInLeg, repeated,
                        gridX, gridZ);
                boolean waypointShape = !hasWaypoint
                        || phase == Phase.SEARCH && issued > 0 && reached < issued;
                // A consecutive ledger counts issued-but-unobserved waypoints.  Three is the
                // terminal boundary produced by noteUnreachableWaypoint: the current waypoint and
                // its third failed path attempt must still be present because recovery/failure owns
                // that boundary atomically.  Reject invented ledgers before onStart can clear them.
                boolean unreachableShape = consecutiveUnreachable == 0
                        ? unreachableAnchor == null
                        : phase == Phase.SEARCH
                        && unreachableAnchor != null
                        && consecutiveUnreachable <= issued - reached
                        && (consecutiveUnreachable < MAX_UNREACHABLE_WAYPOINTS
                        || hasWaypoint && attempts >= 3);
                if (anchor == null || searchOrigin == null
                        || direction < 0 || direction > 3 || legLength < 1
                        || stepInLeg < 0 || stepInLeg >= legLength
                        || repeated < 0 || repeated > 1
                        || !limitPair
                        // Cast before abs: Math.abs(Integer.MIN_VALUE) is still negative.
                        || Math.abs((long) gridX) > waypointLimit
                        || Math.abs((long) gridZ) > waypointLimit
                        || !issuedInBounds || !cursorShape || !waypointShape
                        || reached < 0 || reached > issued
                        || attempts < 0 || attempts > MAX_RESTORABLE_PATH_ATTEMPTS
                        || budget < 0 || budget > elapsedLimit
                        || phaseStarted < 0 || phaseStarted > budget
                        || waypointStarted < 0 || waypointStarted > budget
                        || consecutiveUnreachable < 0
                        || consecutiveUnreachable > MAX_UNREACHABLE_WAYPOINTS
                        || rejected.size() > MAX_REJECTED_SOURCES
                        || !targetShape || !surfacePhaseShape || !unreachableShape) {
                    return Optional.empty();
                }
                BlockPos waypoint = hasWaypoint
                        ? expectedCursor.nominalWaypoint(searchOrigin) : null;
                return Optional.of(new SearchCheckpoint(phase, anchor, searchOrigin, surfaceExit,
                        waypoint, source, stand, direction, legLength, stepInLeg, repeated, gridX,
                        gridZ, issued, reached, waypointLimit, elapsedLimit, attempts, budget,
                        phaseStarted, waypointStarted,
                        consecutiveUnreachable, unreachableAnchor, rejected));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        private static BlockPos decodeOptional(Map<String, String> values, String key) {
            if (!values.containsKey(key)) {
                return null;
            }
            return decodePos(values.get(key)).orElseThrow();
        }

        private static Set<BlockPos> decodePositions(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return Set.of();
            }
            Set<BlockPos> result = new LinkedHashSet<>();
            for (String part : encoded.split(";", -1)) {
                result.add(decodePos(part).orElseThrow());
            }
            return result;
        }

        private static BlockPos immutable(BlockPos pos) {
            return pos == null ? null : pos.immutable();
        }
    }

    /** Deterministic post-issuance square-spiral cursor used to validate restart authority. */
    private record SearchCursor(int directionIndex,
                                int legLength,
                                int stepInLeg,
                                int repeatedLegs,
                                int gridX,
                                int gridZ) {
        private static SearchCursor afterIssued(int issued) {
            int direction = 0;
            int length = 1;
            int step = 0;
            int repeated = 0;
            int x = 0;
            int z = 0;
            for (int index = 0; index < issued; index++) {
                switch (direction) {
                    case 0 -> x++;
                    case 1 -> z++;
                    case 2 -> x--;
                    default -> z--;
                }
                step++;
                if (step >= length) {
                    step = 0;
                    direction = (direction + 1) & 3;
                    repeated++;
                    if (repeated >= 2) {
                        repeated = 0;
                        length++;
                    }
                }
            }
            return new SearchCursor(direction, length, step, repeated, x, z);
        }

        private boolean matches(int direction,
                                int length,
                                int step,
                                int repeated,
                                int x,
                                int z) {
            return directionIndex == direction
                    && legLength == length
                    && stepInLeg == step
                    && repeatedLegs == repeated
                    && gridX == x
                    && gridZ == z;
        }

        private BlockPos nominalWaypoint(BlockPos origin) {
            int x = Math.addExact(origin.getX(), Math.multiplyExact(gridX, SEARCH_STEP));
            int z = Math.addExact(origin.getZ(), Math.multiplyExact(gridZ, SEARCH_STEP));
            return new BlockPos(x, origin.getY(), z).immutable();
        }
    }
}
