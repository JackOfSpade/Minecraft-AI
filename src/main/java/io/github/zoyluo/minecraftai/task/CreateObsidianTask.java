package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.BucketAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Creates and mines obsidian through vanilla survival interactions only.
 *
 * <p>The task acquires water with an empty bucket when necessary, pours it from a safe work pose,
 * waits for Minecraft's fluid tick to turn an observable lava source into obsidian, recovers the
 * reusable water source, and finally mines the block with a diamond-tier pickaxe. It never writes
 * fluid/block state or edits bucket inventory directly.</p>
 */
public final class CreateObsidianTask extends AbstractTask implements CheckpointableTask {
    static final int SERVICE_INTERVAL = 8;
    private static final int BASE_MAX_ELAPSED = 24000;
    private static final int TICKS_PER_TARGET = 2400;
    private static final int NO_PROGRESS_LIMIT = 800;
    private static final int PROSPECT_RANGE = 48;
    private static final int APPROACH_LIMIT = 260;
    private static final int FORMATION_LIMIT = 120;
    private static final int RECOVERY_LIMIT = 100;
    private static final int WATER_SPREAD_TICKS = 4;
    private static final int PROTECTION_SPREAD_TICKS = 20;
    private static final int FLOW_DRAIN_TICKS = 60;
    private static final int PICKUP_LIMIT = 600;
    static final int PICKUP_GRACE_TICKS = 30;
    private static final int PICKUP_MICROSTEP_RANGE = 3;
    private static final int SEARCH_BASE_LEG = 12;
    private static final int SEARCH_SCAN_STRIDE = 4;
    private static final int SEARCH_RETURN_LIMIT = 600;
    private static final int REJECT_TTL = 600;
    private static final int WATER_FLOW_REACH = 7;
    private static final double REACH_MARGIN_SQUARED = 20.16D; // 4.49 blocks

    enum Phase {
        FIND_WATER,
        APPROACH_WATER,
        SCAN,
        RETURN_TO_SCAN_FACE,
        RETURN_TO_SEARCH_FACE,
        SEARCH,
        APPROACH_LAVA_VIEW,
        WAIT_WATER_SPREAD,
        APPROACH_LAVA,
        POUR,
        WAIT_FORM,
        RECOVER_WATER,
        WAIT_DRAIN,
        APPROACH_OBSIDIAN,
        PROTECT_OBSIDIAN,
        MINE,
        PROTECT_PICKUP,
        PICKUP,
        RETURN_TO_RIM,
        SERVICE_BOUNDARY,
        DONE
    }

    /** Validated mission-level facts needed to replay an interrupted obsidian transaction. */
    public record RestoreMetadata(int targetCount,
                                  boolean transactionOpen,
                                  int servicedCollected,
                                  int pendingServiceBoundary) {
    }

    record PourPlan(BlockPos support, Direction face) {
        PourPlan {
            support = support.immutable();
        }

        BlockPos destination() {
            return support.relative(face);
        }
    }

    private record ObservedSearchSolid(BlockPos pos, BlockState state) {
        private ObservedSearchSolid {
            pos = pos.immutable();
        }
    }

    /** Exact observed local movement admitted while closing collision range to this task's own drop. */
    private record PickupStepAdmission(BlockPos origin, BlockPos destination, WalkedStep.Kind kind) {
        private PickupStepAdmission {
            origin = origin.immutable();
            destination = destination.immutable();
        }
    }

    /** Exact observed recovery move that gets the bot out of its own temporary water cell. */
    private record RecoveryStepAdmission(BlockPos origin, BlockPos destination, WalkedStep.Kind kind) {
        private RecoveryStepAdmission {
            origin = origin.immutable();
            destination = destination.immutable();
        }
    }

    private final int targetCount;
    private final int maxElapsed;
    private final ObsidianCheckpoint restoredCheckpoint;
    private final boolean invalidCheckpoint;
    private final BlockMiner miner = new BlockMiner();
    private final ObsidianTargetMemory rejectedLava = new ObsidianTargetMemory();
    private final ObsidianTargetMemory rejectedObsidian = new ObsidianTargetMemory();
    private final Set<BlockPos> rejectedPourDestinations = new HashSet<>();

    private int invBaseline;
    private int collected;
    private int lastProgressTick;
    private int phaseStartedTick;
    private int pickupGrace;
    private int pickupInventoryBaseline = -1;
    private int pickupGainTick = -1;
    private int waterBucketBaseline = -1;
    private int budgetOffset;
    /** Highest eight-block boundary whose service transaction has committed. */
    private int servicedCollected;
    /** Non-zero only while GoalExecutor must run OBSIDIAN_8 service before resuming this task. */
    private int pendingServiceBoundary;
    private int lastPathAttemptTick = -20;
    private Phase phase = Phase.FIND_WATER;
    private ObsidianSearchCursor searchCursor;
    /** Exact physical pose whose local topology the next SCAN is authorized to inspect. */
    private BlockPos scanResumeFace;
    private BlockPos lastScannedFace;
    private int lastScannedEpoch = -1;
    private BlockPos lastSearchMotionPos;
    private BlockPos waterTarget;
    private BlockPos waterSource;
    private BlockPos lavaClue;
    // One flow-control pour's causal record: how long its water needs to reach the clue, and
    // which clue must answer for a cycle that converts nothing. Transient by design — a restart
    // resumes through RECOVER_WATER, which repays the bucket before any new pour.
    private int pourSpreadWaitTicks = WATER_SPREAD_TICKS;
    private BlockPos lastPourClue;
    private BlockPos lavaTarget;
    private BlockPos obsidian;
    private BlockPos pickupPos;
    /** Last block cell of this transaction's drop while it was physically observable. */
    private BlockPos pickupLastSeenPos;
    private BlockPos returnRim;
    /** Last work pose from which this remembered obsidian/lava cell was reached safely. */
    private BlockPos obsidianStandHint;
    // The reason of the walked step this task started in the water recovery ("surface" or "rim"); judged when the step has ended.
    private String recoveryWalk;
    /** Exact ActionPack admission for {@link #recoveryWalk}; never infer our outcome from a later owner. */
    private ActionPack.StepLease recoveryWalkLease;
    /** Exact guarded admission for a local, observed pickup collision step. */
    private ActionPack.StepLease pickupStepLease;
    private BlockPos standPos;
    private PourPlan pourPlan;
    private BlockPos activeBreakPos;
    private int activeBreakInventoryBaseline = -1;
    /** True only after this exact obsidian target was washed and the source was recovered. */
    private boolean protectionPrepared;
    /** Non-null means every action in this checkpoint is bound to one strict evidence session. */
    private UUID auditSessionToken;

    public CreateObsidianTask(int targetCount) {
        this(targetCount, Map.of());
    }

    public CreateObsidianTask(int targetCount, Map<String, String> checkpoint) {
        this.targetCount = Math.max(1, targetCount);
        this.maxElapsed = maxElapsedForTarget(this.targetCount);
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        this.restoredCheckpoint = ObsidianCheckpoint.decode(values, this.targetCount, this.maxElapsed)
                .orElse(null);
        this.invalidCheckpoint = !values.isEmpty() && restoredCheckpoint == null;
    }

    /**
     * Validates a persisted task checkpoint without binding it to a newly replanned remaining count.
     * The original target is part of the transaction identity and must survive inventory changes.
     */
    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return Optional.empty();
        }
        try {
            int target = Integer.parseInt(checkpoint.getOrDefault("target_count", ""));
            if (target < 1) {
                return Optional.empty();
            }
            return ObsidianCheckpoint.decode(checkpoint, target, maxElapsedForTarget(target))
                    .map(value -> new RestoreMetadata(
                            target,
                            value.waterSource() != null
                                    || value.pickupPos() != null
                                    || value.activeBreakPos() != null,
                            value.servicedCollected(),
                            value.pendingServiceBoundary()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static int maxElapsedForTarget(int targetCount) {
        long scaled = (long) Math.max(1, targetCount) * TICKS_PER_TARGET;
        return (int) Math.min(Integer.MAX_VALUE, Math.max((long) BASE_MAX_ELAPSED, scaled));
    }

    @Override
    public String name() {
        return "create_obsidian";
    }

    @Override
    public String describe() {
        return "CreateObsidian " + collected + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("create_obsidian_invalid_checkpoint");
            return;
        }
        // An exhausted checkpoint is terminal evidence, not a corrupt restore. Hydrate its exact
        // durable state and fail before any inventory/world precondition can turn the restart into
        // a different failure or manufacture a fresh hard-budget window.
        if (restoredCheckpoint != null && restoredCheckpoint.budgetUsed() >= maxElapsed) {
            restoreCheckpoint(restoredCheckpoint);
            fail(timeoutReason());
            return;
        }
        UUID restoredAuditToken = restoredCheckpoint == null
                ? null : restoredCheckpoint.auditSessionToken();
        if (restoredAuditToken != null && !MiningEvidenceAudit.sessionMatches(
                bot, MiningEvidenceAudit.Target.OBSIDIAN, restoredAuditToken)) {
            fail("create_obsidian_audit_session_missing_or_mismatched");
            return;
        }
        auditSessionToken = restoredAuditToken != null
                ? restoredAuditToken
                : MiningEvidenceAudit.sessionToken(bot, MiningEvidenceAudit.Target.OBSIDIAN)
                .orElse(null);
        int inventoryNow = HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN));
        rejectedLava.clear();
        rejectedObsidian.clear();
        rejectedPourDestinations.clear();
        clearWorkTargets();
        lastSearchMotionPos = bot.blockPosition().immutable();
        if (restoredCheckpoint == null) {
            invBaseline = inventoryNow;
            collected = 0;
            lastProgressTick = 0;
            phaseStartedTick = 0;
            pickupGrace = 0;
            budgetOffset = 0;
            servicedCollected = 0;
            pendingServiceBoundary = 0;
            searchCursor = ObsidianSearchCursor.initial(bot.blockPosition(), SEARCH_BASE_LEG);
            enterScan(bot);
            return;
        }

        restoreCheckpoint(restoredCheckpoint);
        int restoredInventoryDelta = Math.max(0, inventoryNow - invBaseline);
        var auditedPickups = MiningEvidenceAudit.auditedObsidianPickupCredits(bot);
        int restoredCollected = auditedPickups.isPresent()
                ? auditedPickups.getAsInt() : restoredInventoryDelta;
        if (restoredCollected < restoredCheckpoint.collected()) {
            fail(auditedPickups.isPresent()
                    ? "create_obsidian_audit_checkpoint_ahead audited=" + restoredCollected
                    + " checkpoint=" + restoredCheckpoint.collected()
                    : "create_obsidian_inventory_checkpoint_regressed");
            return;
        }
        if (restoredInventoryDelta < restoredCollected) {
            fail("create_obsidian_inventory_checkpoint_regressed");
            return;
        }
        // Under the strict verifier, unrelated/pre-existing obsidian may make the raw inventory
        // delta larger than the exact conversion -> break -> pickup ledger. Never let a service
        // replacement or checkpoint restart promote that unrelated quantity into mission credit.
        collected = restoredCollected;
        if (activeBreakPos != null
                && !bot.level().getBlockState(activeBreakPos).is(Blocks.OBSIDIAN)) {
            promoteActiveBreakToPickup(bot);
            if (waterSource != null) {
                // The break may have committed immediately before the restart. Keep the live
                // source for a fresh full post-break spread window; recovering it here could expose
                // side lava that only became reachable once the obsidian block turned to AIR.
                enter(Phase.PROTECT_PICKUP);
            } else if (waterBucketBaseline >= 0) {
                enter(Phase.RECOVER_WATER);
            } else if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0) {
                // The physical break committed before its checkpoint cursor advanced. Rebuild the
                // same post-break lava protection transaction instead of chasing an unprotected
                // drop directly from the stale MINE phase.
                enter(Phase.PROTECT_PICKUP);
            } else {
                fail("create_obsidian_pickup_protection_missing_water");
                return;
            }
        }
        BotLog.task(bot, "create_obsidian_checkpoint_restored",
                "phase", phase,
                "collected", collected + "/" + targetCount,
                "budget", totalBudget(),
                "water", waterSource == null ? "none" : waterSource.toShortString(),
                "pickup", pickupPos == null ? "none" : pickupPos.toShortString(),
                "active_break", activeBreakPos == null ? "none" : activeBreakPos.toShortString(),
                "serviced", servicedCollected,
                "service_pending", pendingServiceBoundary);

        // SERVICE_BOUNDARY is a committed, world-safe yield point. Replaying it must not touch the
        // pool or inventory; GoalExecutor observes the same durable signal and runs service once.
        if (phase == Phase.SERVICE_BOUNDARY) {
            complete();
            return;
        }
        if (phase == Phase.PROTECT_PICKUP) {
            // Both an original protection checkpoint and an AIR-promoted active break own this
            // phase. onTick must place/retain the source before goal satisfaction is considered.
            return;
        }

        // A placed source is a real inventory obligation. It outranks the saved phase and every
        // pickup/mining continuation: first repay the bucket, then WAIT_DRAIN dispatches the
        // preserved pickup or obsidian ledger.
        if (restoredCheckpoint.resumePhase() == Phase.RECOVER_WATER) {
            if (phase != Phase.RECOVER_WATER) {
                enter(Phase.RECOVER_WATER);
            }
            return;
        }
        if (phase == Phase.RETURN_TO_RIM && returnRim != null
                && bot.blockPosition().equals(returnRim)) {
            enterScan(bot);
            return;
        }
        if (phase == Phase.RETURN_TO_SCAN_FACE
                || (phase == Phase.SCAN && !bot.blockPosition().equals(scanResumeFace))) {
            BotLog.task(bot, "create_obsidian_scan_restored",
                    "face", scanResumeFace.toShortString(),
                    "from", bot.blockPosition().toShortString(),
                    "produced", searchCursor.produced());
            phase = Phase.RETURN_TO_SCAN_FACE;
            return;
        }
        if (phase == Phase.RETURN_TO_SEARCH_FACE
                || (phase == Phase.SEARCH && !bot.blockPosition().equals(searchCursor.face()))) {
            BotLog.task(bot, "create_obsidian_search_restored",
                    "face", searchCursor.face().toShortString(),
                    "leg", searchCursor.legIndex(),
                    "remaining", searchCursor.stepsLeft(),
                    "produced", searchCursor.produced());
            phase = Phase.RETURN_TO_SEARCH_FACE;
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        cancelPickupStep(bot);
        releaseRecoveryWalk(bot);
        bot.getActionPack().stopAll();
        // A replacement may retain already committed conversions, but it must never inherit an
        // open placement candidate. Reconcile factual obsidian at the interruption boundary and
        // close the generation before any fresh task can scan the same cave.
        MiningEvidenceAudit.reconcileAndCloseWaterPlacement(bot);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (auditSessionToken != null && !MiningEvidenceAudit.sessionMatches(
                bot, MiningEvidenceAudit.Target.OBSIDIAN, auditSessionToken)) {
            fail("create_obsidian_audit_session_missing_or_mismatched");
            return;
        }
        if (totalBudget() >= maxElapsed) {
            fail(timeoutReason());
            return;
        }
        ServerLevel world = bot.level();

        int inventoryTotal = Math.max(0,
                HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN)) - invBaseline);
        var auditedPickups = MiningEvidenceAudit.auditedObsidianPickupCredits(bot);
        if (auditedPickups.isPresent() && auditedPickups.getAsInt() < collected) {
            fail("create_obsidian_audit_checkpoint_ahead audited=" + auditedPickups.getAsInt()
                    + " checkpoint=" + collected);
            return;
        }
        int total = auditedPickups.isPresent() ? auditedPickups.getAsInt() : inventoryTotal;
        if (total > collected) {
            collected = total;
            lastProgressTick = totalBudget();
            BotLog.action(bot, "create_obsidian_collected", "total", collected + "/" + targetCount);
            io.github.zoyluo.minecraftai.brain.BotReporter.INSTANCE.onGoalMessage(bot,
                    "Collected obsidian: " + collected + "/" + targetCount + ".");
        }
        // Transaction safety phases outrank goal satisfaction. A drop can enter inventory while
        // its protection source is still neutralizing exposed lava, and a restart can resume while
        // residual flow is draining. Neither event may shorten the persisted 20/60 tick windows.
        if (phase == Phase.PROTECT_PICKUP && pickupPos != null) {
            protectPickup(bot);
            return;
        }
        if (phase == Phase.RECOVER_WATER
                && (waterSource != null || waterBucketBaseline >= 0)) {
            recoverWater(bot);
            return;
        }
        if (phase == Phase.WAIT_DRAIN) {
            waitForDrain(bot);
            return;
        }
        if (!needsMorePools(targetCount, collected)) {
            if (waterSource != null || waterBucketBaseline >= 0) {
                if (phase != Phase.RECOVER_WATER) {
                    enter(Phase.RECOVER_WATER);
                }
                recoverWater(bot);
                return;
            }
            if (pickupPos != null) {
                if (phase != Phase.PICKUP) {
                    enter(Phase.PICKUP);
                }
                pickup(bot);
                return;
            }
            // A physical pickup completes the item ledger before it completes the safety ledger.
            // Repay the durable dry-rim return debt before declaring the final batch done, just as
            // every earlier batch does before an inter-batch service boundary.
            if (returnRim != null) {
                if (phase != Phase.RETURN_TO_RIM) {
                    enter(Phase.RETURN_TO_RIM);
                }
                returnToRim(bot);
                return;
            }
            pendingServiceBoundary = 0;
            finish(bot);
            return;
        }
        if (pendingServiceBoundary == 0) {
            pendingServiceBoundary = nextServiceBoundary(
                    servicedCollected, collected, targetCount);
            if (pendingServiceBoundary > 0) {
                BotLog.task(bot, "create_obsidian_service_boundary_latched",
                        "boundary", pendingServiceBoundary,
                        "phase", phase,
                        "collected", collected + "/" + targetCount);
            }
        }
        if (pendingServiceBoundary > 0) {
            if (canYieldForService(bot)) {
                requestServiceBoundary(bot, pendingServiceBoundary);
                return;
            }
            // Once the boundary is crossed, do not begin another scan/transaction. A stale action
            // is stopped first; an impossible dry work pose fails typed instead of silently
            // servicing from water or an unsupported cell.
            if (phase == Phase.SCAN) {
                miner.cancel(bot);
                bot.getActionPack().stopAll();
                BlockPos current = bot.blockPosition();
                if (!bot.level().getFluidState(current).isEmpty()
                        || !bot.level().getFluidState(current.above()).isEmpty()
                        || !Standability.isStandable(bot.level(), current)) {
                    fail("create_obsidian_service_boundary_unsafe_pose:"
                            + current.toShortString());
                }
                return;
            }
        }
        if (totalBudget() - lastProgressTick > NO_PROGRESS_LIMIT) {
            BotLog.action(bot, "create_obsidian_stall", "phase", phase,
                    "collected", collected + "/" + targetCount);
            miner.cancel(bot);
            fail("create_obsidian_no_progress collected=" + collected + " phase=" + phase);
            return;
        }

        switch (phase) {
            case FIND_WATER -> findWater(bot);
            case APPROACH_WATER -> approachWater(bot);
            case SCAN -> scan(bot);
            case RETURN_TO_SCAN_FACE -> returnToScanFace(bot);
            case RETURN_TO_SEARCH_FACE -> returnToSearchFace(bot);
            case SEARCH -> search(bot);
            case APPROACH_LAVA_VIEW -> approachLavaView(bot);
            case WAIT_WATER_SPREAD -> waitForWaterSpread();
            case APPROACH_LAVA -> approachLava(bot);
            case POUR -> pour(bot);
            case WAIT_FORM -> waitForFormation(bot);
            case RECOVER_WATER -> recoverWater(bot);
            case WAIT_DRAIN -> waitForDrain(bot);
            case APPROACH_OBSIDIAN -> approachObsidian(bot);
            case PROTECT_OBSIDIAN -> protectObsidian(bot);
            case MINE -> mine(bot);
            case PROTECT_PICKUP -> protectPickup(bot);
            case PICKUP -> pickup(bot);
            case RETURN_TO_RIM -> returnToRim(bot);
            case SERVICE_BOUNDARY -> { }
            case DONE -> { }
        }
    }

    private void findWater(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0) {
            enterScan(bot);
            return;
        }
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            fail("create_obsidian_missing_bucket");
            return;
        }
        BlockPos found = nearestObservableFluid(bot,
                fluid -> fluid.is(FluidTags.WATER) && fluid.isSource(), null);
        if (found == null) {
            fail("create_obsidian_no_water_source collected=" + collected);
            return;
        }
        BlockPos stand = findWorkStand(bot, found, null, false);
        if (stand == null) {
            fail("create_obsidian_no_safe_water_stand pos=" + found.toShortString());
            return;
        }
        waterTarget = found.immutable();
        standPos = stand;
        BotLog.action(bot, "create_obsidian_water_found", "pos", waterTarget.toShortString());
        enter(Phase.APPROACH_WATER);
    }

    private void approachWater(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        if (waterTarget == null || !isStillWater(world, waterTarget)) {
            waterTarget = null;
            enter(Phase.FIND_WATER);
            return;
        }
        if (!atWorkPose(bot, standPos, waterTarget, null, false)) {
            if (phaseAge() > APPROACH_LIMIT) {
                fail("create_obsidian_water_unreachable pos=" + waterTarget.toShortString());
                return;
            }
            pathToWorkPose(bot, standPos, "create_obsidian_water_path_failed");
            return;
        }
        bot.getActionPack().stopAll();
        ActionResult result = BucketAction.fillWaterSource(bot, waterTarget);
        if (result.isFailed()) {
            fail("create_obsidian_fill_water_failed:" + result.reason());
            return;
        }
        noteTopologyProgress();
        waterTarget = null;
        standPos = null;
        enterScan(bot);
    }

    private void scan(AIPlayerEntity bot) {
        BlockPos scanFace = bot.blockPosition().immutable();
        if (scanFace.equals(lastScannedFace)
                && lastScannedEpoch == searchCursor.topologyEpoch()) {
            beginSearch(bot, "already_scanned_work_face");
            return;
        }
        lastScannedFace = scanFace;
        lastScannedEpoch = searchCursor.topologyEpoch();
        searchCursor = searchCursor.markScanned();
        // Water may convert several touching lava sources at once. Mine every observable, safe
        // obsidian block before asking for another pour.
        Set<BlockPos> deferred = new HashSet<>();
        for (int attempt = 0; attempt < 12; attempt++) {
            BlockPos found = nearestObservableCell(bot,
                    state -> state.is(Blocks.OBSIDIAN),
                    pos -> !obsidianRejected(pos) && !deferred.contains(pos));
            if (found == null) {
                break;
            }
            if (!MiningEvidenceAudit.isObsidianBreakAuthorized(bot, found)) {
                rejectObsidian(found);
                BotLog.action(bot, "create_obsidian_unverified_obsidian_deferred",
                        "pos", found.toShortString());
                continue;
            }
            if (hasObservableNearbyLava(bot, found, 2)) {
                deferred.add(found.immutable());
                continue;
            }
            BlockPos stand = isCurrentDirectMinePose(bot, found)
                    ? bot.blockPosition().immutable()
                    : findWorkStand(bot, found, null, true);
            if (stand == null) {
                rejectObsidian(found);
                continue;
            }
            obsidian = found.immutable();
            standPos = stand;
            obsidianStandHint = stand;
            lastProgressTick = totalBudget();
            BotLog.action(bot, "create_obsidian_obsidian_found",
                    "pos", obsidian.toShortString(),
                    "stand", standPos.toShortString(),
                    "reuse_current", standPos.equals(bot.blockPosition()));
            enter(Phase.APPROACH_OBSIDIAN);
            return;
        }

        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) <= 0) {
            enter(Phase.FIND_WATER);
            return;
        }

        for (int attempt = 0; attempt < 12; attempt++) {
            BlockPos found = nearestObservableFluid(bot,
                    fluid -> fluid.is(FluidTags.LAVA) && fluid.isSource(),
                    pos -> !lavaRejected(pos));
            if (found == null) {
                break;
            }
            rejectedPourDestinations.clear();
            PourPlan plan = findPourPlan(bot, found, rejectedPourDestinations);
            BlockPos stand = plan == null ? null : findWorkStand(bot, found, plan, true);
            if (plan == null || stand == null) {
                rejectLava(found);
                continue;
            }
            lavaTarget = found.immutable();
            pourPlan = plan;
            standPos = stand;
            lastProgressTick = totalBudget();
            BotLog.action(bot, "create_obsidian_lava_found",
                    "pos", lavaTarget.toShortString(),
                    "stand", standPos.toShortString(),
                    "water", pourPlan.destination().toShortString());
            if (atPourPose(bot, standPos, pourPlan, true)) {
                bot.getActionPack().stopAll();
                enter(Phase.POUR);
                pour(bot);
                return;
            }
            enter(Phase.APPROACH_LAVA);
            return;
        }

        // A spreading source is frequently hidden by its own nearer flow. Neutralize that visible
        // edge from outside the danger radius; the water either exposes or converts the source,
        // after which the normal scan can continue without a hidden read.
        BlockPos clue = nearestObservableFluid(bot,
                fluid -> fluid.is(FluidTags.LAVA), pos -> !lavaRejected(pos));
        if (clue == null) {
            beginSearch(bot, "no_observable_lava");
            return;
        }
        rejectedPourDestinations.clear();
        PourPlan controlPlan = findPourPlan(bot, clue, rejectedPourDestinations);
        BlockPos controlStand = controlPlan == null ? null : findWorkStand(bot, clue, controlPlan, true);
        if (controlPlan == null || controlStand == null) {
            rejectLava(clue);
            beginSearch(bot, "unsafe_visible_lava");
            return;
        }
        lavaClue = clue.immutable();
        pourPlan = controlPlan;
        standPos = controlStand;
        BotLog.action(bot, "create_obsidian_flow_control",
                "clue", lavaClue.toShortString(),
                "stand", standPos.toShortString(),
                "water", pourPlan.destination().toShortString());
        if (atPourPose(bot, standPos, pourPlan, true)) {
            pourFlowControl(bot);
            return;
        }
        enter(Phase.APPROACH_LAVA_VIEW);
    }

    /** Stops when the current player view contains no usable lava; it never excavates to find one. */
    private void beginSearch(AIPlayerEntity bot, String reason) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, "create_obsidian_observed_lava_required", "reason", reason);
        fail("create_obsidian_no_observed_lava");
    }

    private void returnToScanFace(AIPlayerEntity bot) {
        BlockPos current = bot.blockPosition().immutable();
        if (!current.equals(lastSearchMotionPos)) {
            lastSearchMotionPos = current;
            lastProgressTick = totalBudget();
        }
        if (scanResumeFace == null) {
            fail("create_obsidian_scan_resume_missing");
            return;
        }
        if (current.equals(scanResumeFace)) {
            bot.getActionPack().stopAll();
            lastProgressTick = totalBudget();
            enter(Phase.SCAN);
            return;
        }
        if (phaseAge() > SEARCH_RETURN_LIMIT) {
            fail("create_obsidian_scan_resume_unreachable:" + scanResumeFace.toShortString());
            return;
        }
        pathToWorkPose(bot, scanResumeFace, "create_obsidian_scan_resume_path_failed");
    }

    private void returnToSearchFace(AIPlayerEntity bot) {
        if (RetiredNavigationTask.legacyExcavationDisabled()) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            BotLog.action(bot, "create_obsidian_observed_lava_required", "reason", "legacy_search_checkpoint");
            fail("create_obsidian_no_observed_lava");
            return;
        }

        BlockPos current = bot.blockPosition().immutable();
        if (!current.equals(lastSearchMotionPos)) {
            lastSearchMotionPos = current;
            lastProgressTick = totalBudget();
        }
        if (current.equals(searchCursor.face())) {
            bot.getActionPack().stopAll();
            lastProgressTick = totalBudget();
            enter(Phase.SEARCH);
            return;
        }
        if (phaseAge() > SEARCH_RETURN_LIMIT) {
            fail("create_obsidian_restore_face_unreachable:" + searchCursor.face().toShortString());
            return;
        }
        pathToWorkPose(bot, searchCursor.face(), "create_obsidian_restore_face_path_failed");
    }

    private void search(AIPlayerEntity bot) {
        if (RetiredNavigationTask.legacyExcavationDisabled()) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            BotLog.action(bot, "create_obsidian_observed_lava_required", "reason", "legacy_search_checkpoint");
            fail("create_obsidian_no_observed_lava");
            return;
        }

        ServerLevel world = bot.level();
        BlockPos current = bot.blockPosition().immutable();

        // Only actual forward displacement consumes the durable cursor. Excavating a wall does
        // not pretend that the work face moved.
        BlockPos previousFace = searchCursor.face();
        ObsidianSearchCursor advanced = searchCursor.advanceTo(current);
        if (!advanced.equals(searchCursor)) {
            searchCursor = advanced;
            lastSearchMotionPos = current;
            lastProgressTick = totalBudget();
            bot.getActionPack().stopAll();
            BotLog.action(bot, "create_obsidian_search_face",
                    "face", current.toShortString(),
                    "leg", searchCursor.legIndex(),
                    "remaining", searchCursor.stepsLeft());
            if (!lightSearchTrail(bot, previousFace)) {
                return;
            }
            if (searchCursor.facesSinceScan() >= SEARCH_SCAN_STRIDE) {
                enterScan(bot);
            }
            return;
        }

        if (!current.equals(searchCursor.face())) {
            // Steering should be one-cell and axis-aligned. If collision leaves us off that line,
            // return to the last factual face rather than fabricating cursor progress.
            enter(Phase.RETURN_TO_SEARCH_FACE);
            return;
        }

        // Reconcile any already-completed physical movement above before this readiness gate. A
        // crash or depleted torch stack must never leave checkpoint.work_face behind the bot's
        // factual pose. A factual four-direction closure is terminal even in darkness; only an
        // open search face needs a usable lighting resource before another wall is opened.
        if (searchCursor.blockedAllDirections()) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            fail("create_obsidian_search_enclosed face=" + current.toShortString()
                    + " mask=" + searchCursor.blockedDirections());
            return;
        }
        if (!searchLightingReady(bot)) {
            return;
        }

        if (searchCursor.directionIndex() < 0 || searchCursor.stepsLeft() <= 0) {
            searchCursor = searchCursor.beginNextLeg();
            BotLog.action(bot, "create_obsidian_search_leg",
                    "leg", searchCursor.legIndex(),
                    "dir", searchCursor.direction(),
                    "length", searchCursor.legLength(),
                    "face", searchCursor.face().toShortString());
            return;
        }

        BlockPos next = searchCursor.nextFace();
        BlockPos head = next.above();
        boolean feetObserved = ObservableWorldQuery.canObserveCell(bot, next);
        boolean headObserved = ObservableWorldQuery.canObserveCell(bot, head);
        if (!feetObserved && !headObserved) {
            return;
        }

        // A solid head-level wall can geometrically occlude the feet cell from the bot's eyes.
        // Process either independently observed solid before requiring the full two-block cell;
        // otherwise a visible gold/redstone blocker stalls until the no-progress budget fails.
        BlockState feetState = feetObserved ? world.getBlockState(next) : null;
        BlockState headState = headObserved ? world.getBlockState(head) : null;
        if ((feetObserved && (!feetState.getFluidState().isEmpty()
                || feetState.is(Blocks.OBSIDIAN)))
                || (headObserved && (!headState.getFluidState().isEmpty()
                || headState.is(Blocks.OBSIDIAN)))) {
            // Do not walk into a pool. This exposed cell is a newly observed topology boundary;
            // survey it from the dry face, then continue the next spiral leg if the pool is spent.
            searchCursor = searchCursor.skipBlockedLeg();
            lastScannedFace = null; // force the newly observed boundary through SCAN
            enterScan(bot);
            return;
        }
        if (feetObserved && headObserved && feetState.isAir() && headState.isAir()
                && (hasObservableAdjacentFluid(bot, next)
                || hasObservableAdjacentFluid(bot, head))) {
            // A side pocket can be exposed by the just-finished wall block. Survey it before the
            // physical step so the bot never walks into a newly flowing lava edge.
            searchCursor = searchCursor.skipBlockedLeg();
            lastScannedFace = null;
            enterScan(bot);
            return;
        }

        ObservedSearchSolid solid = firstObservedSearchSolid(
                next, feetState, feetObserved, head, headState, headObserved);
        if (solid != null) {
            BlockPos solidPos = solid.pos();
            BlockState state = solid.state();
            if (state.getDestroySpeed(world, solidPos) < 0.0F) {
                searchCursor = searchCursor.skipBlockedLeg();
                BotLog.action(bot, "create_obsidian_search_leg_blocked",
                        "pos", solidPos.toShortString(), "block", state.getBlock());
                return;
            }
            if (Math.max(ToolTier.STONE, ToolTier.requiredPickaxeTier(state.getBlock()))
                    > ToolTier.STONE) {
                searchCursor = searchCursor.skipBlockedLeg();
                BotLog.action(bot, "create_obsidian_search_preserve_mission_tool",
                        "pos", solidPos.toShortString(), "block", state.getBlock());
                return;
            }
            if (!equipOrdinaryMiningTool(bot, state)) {
                fail("create_obsidian_missing_tunneling_pickaxe");
                return;
            }
            BlockMiner.Status status = miner.target() != null && miner.target().equals(solidPos)
                    ? miner.tick(bot)
                    : beginSearchMine(bot, solidPos);
            if (status == BlockMiner.Status.DONE) {
                noteTopologyProgress();
                BotLog.action(bot, "create_obsidian_search_opened", "pos", solidPos.toShortString());
            } else if (status == BlockMiner.Status.FAILED) {
                searchCursor = searchCursor.skipBlockedLeg();
                BotLog.action(bot, "create_obsidian_search_mine_failed",
                        "pos", solidPos.toShortString(), "reason", miner.failureReason());
            }
            return;
        }

        if (!feetObserved || !headObserved) {
            return; // never infer that an unobserved half of the tunnel cell is open
        }

        // The tunnel cell is open. Read its support only after the face below is observable; an
        // unsupported cave edge is surveyed but never stepped into blindly.
        BlockPos support = next.below();
        if (!ObservableWorldQuery.canObserveBlock(bot, support)
                || !Standability.isStandable(world, next)
                || !world.getFluidState(next).isEmpty()) {
            searchCursor = searchCursor.skipBlockedLeg();
            lastScannedFace = null;
            enterScan(bot);
            return;
        }
        miner.cancel(bot);
        if (bot.getActionPack().isWalkToIdle()) {
            bot.getActionPack().startWalkTo(Vec3.atBottomCenterOf(next));
        }
    }

    /**
     * Keeps the physical branch corridor spawn-safe. Existing torch light is durable checkpoint
     * state in the world, so restart idempotence does not need an invented counter: place only
     * when the just-reached face is below the configured block-light threshold.
     */
    private boolean lightSearchTrail(AIPlayerEntity bot, BlockPos previousFace) {
        ServerLevel world = bot.level();
        if (combinedSearchLight(world, bot.blockPosition()) >= searchLightThreshold()) {
            return true;
        }
        if (!hasSearchLightingResource(bot)) {
            return false;
        }
        return placeSearchTorch(bot, previousFace);
    }

    private boolean searchLightingReady(AIPlayerEntity bot) {
        if (combinedSearchLight(bot.level(), bot.blockPosition()) >= searchLightThreshold()) {
            return true;
        }
        if (!hasSearchLightingResource(bot)) {
            return false;
        }
        // A standing torch has no collision box, so vanilla permits the same floor placement used
        // by DescendToYTask/OreDigTask at the bot's current feet. This lights an initial/restored
        // dark face before SEARCH opens another wall.
        return placeSearchTorch(bot, bot.blockPosition());
    }

    private boolean hasSearchLightingResource(AIPlayerEntity bot) {
        if (!MinecraftAiConfig.get().mining().placeTorches()) {
            fail("create_obsidian_search_lighting_disabled");
            return false;
        }
        if (InventoryAction.countItem(bot, Items.TORCH) <= 0) {
            fail("create_obsidian_search_missing_torch");
            return false;
        }
        return true;
    }

    private boolean placeSearchTorch(AIPlayerEntity bot, BlockPos target) {
        ServerLevel world = bot.level();
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (torchSlot < 0) {
            fail("create_obsidian_search_missing_torch");
            return false;
        }
        if (target == null
                || !ObservableWorldQuery.canObserveCell(bot, target)
                || !ObservableWorldQuery.canObserveBlock(bot, target.below())
                || !world.getBlockState(target).isAir()
                || world.getBlockState(target.below()).getCollisionShape(
                world, target.below()).isEmpty()
                || !world.getFluidState(target).isEmpty()
                || !world.getFluidState(target.below()).isEmpty()) {
            fail("create_obsidian_search_no_torch_mount:"
                    + (target == null ? "none" : target.toShortString()));
            return false;
        }
        if (InventoryAction.equipFromSlot(bot, torchSlot) < 0) {
            fail("create_obsidian_search_cannot_equip_torch");
            return false;
        }
        ActionResult placed = BuildAction.placeBlockAt(bot, target);
        if (placed.isInProgress()) {
            // A reactive shield may own the hand briefly.  Preserve the search
            // state and do not let that intentional wait consume its watchdog.
            lastProgressTick = totalBudget();
            return false;
        }
        if (placed.isFailed()) {
            fail("create_obsidian_search_torch_failed:" + placed.reason());
            return false;
        }
        lastProgressTick = totalBudget();
        BotLog.action(bot, "create_obsidian_search_torch",
                "pos", target.toShortString(),
                "face", bot.blockPosition().toShortString(),
                "remaining", InventoryAction.countItem(bot, Items.TORCH));
        return true;
    }

    private static int searchLightThreshold() {
        return Math.max(1, Math.min(14, MinecraftAiConfig.get().night().torchLightThreshold()));
    }

    private static int combinedSearchLight(ServerLevel world, BlockPos pos) {
        return Math.max(world.getBrightness(LightLayer.BLOCK, pos),
                world.getBrightness(LightLayer.SKY, pos));
    }

    private BlockMiner.Status beginSearchMine(AIPlayerEntity bot, BlockPos pos) {
        bot.getActionPack().stopMovement();
        miner.begin(bot, pos, true);
        return miner.tick(bot);
    }

    private static ObservedSearchSolid firstObservedSearchSolid(BlockPos feet,
                                                                BlockState feetState,
                                                                boolean feetObserved,
                                                                BlockPos head,
                                                                BlockState headState,
                                                                boolean headObserved) {
        if (feetObserved && !feetState.isAir()) {
            return new ObservedSearchSolid(feet, feetState);
        }
        if (headObserved && !headState.isAir()) {
            return new ObservedSearchSolid(head, headState);
        }
        return null;
    }

    /**
     * The exploration channel consumes only stone picks; iron and diamond remain mission assets.
     * For a block that needs no tool at all (dirt/gravel/sand, ...) a wood/stone pickaxe is still
     * preferred when one is on hand, but {@link BlockState#requiresCorrectToolForDrops()} being false means bare
     * hands are always a legal fallback -- see the no-candidate branch below, which never hard-fails
     * for such a block (mirrors {@code BlockMiner}/{@code ToolSelector.equipMiningChannelTool}'s own
     * {@code requiresCorrectToolForDrops()}-gated policy).
     */
    private static boolean equipOrdinaryMiningTool(AIPlayerEntity bot, BlockState state) {
        int required = state.requiresCorrectToolForDrops()
                ? Math.max(ToolTier.STONE, ToolTier.requiredPickaxeTier(state.getBlock()))
                : ToolTier.WOOD;
        int bestSlot = -1;
        int bestOffhandSlot = -1;
        int bestTier = Integer.MAX_VALUE;
        int bestRemaining = -1;
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            var stack = bot.getInventory().getNonEquipmentItems().get(slot);
            int tier = ToolTier.pickaxeTier(stack);
            if (tier < required || tier > ToolTier.STONE
                    || (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state))
                    || (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1)) {
                continue;
            }
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (tier < bestTier || (tier == bestTier && remaining > bestRemaining)) {
                bestTier = tier;
                bestRemaining = remaining;
                bestSlot = slot;
                bestOffhandSlot = -1;
            }
        }
        var offHandStack = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        int offHandTier = ToolTier.pickaxeTier(offHandStack);
        if (offHandTier >= required && offHandTier <= ToolTier.STONE
                && (!state.requiresCorrectToolForDrops() || offHandStack.isCorrectToolForDrops(state))
                && (!offHandStack.isDamageableItem() || offHandStack.getDamageValue() < offHandStack.getMaxDamage() - 1)) {
            int remaining = offHandStack.isDamageableItem()
                    ? offHandStack.getMaxDamage() - offHandStack.getDamageValue() : Integer.MAX_VALUE;
            if (offHandTier < bestTier || (offHandTier == bestTier && remaining > bestRemaining)) {
                bestTier = offHandTier;
                bestRemaining = remaining;
                bestSlot = -1;
                bestOffhandSlot = 0;
            }
        }
        if (bestSlot < 0 && bestOffhandSlot < 0) {
            if (state.requiresCorrectToolForDrops()) {
                return false;
            }
            // No wood/stone pickaxe is available, but this block needs no tool at all: failing the
            // mission over dirt/gravel/sand is exactly the bug this method must not reintroduce
            // (obsidianwater-001). Select an empty hotbar slot as an executable bare hand when one
            // is free -- the same "empty hotbar slot is a valid tool choice" pattern
            // ToolSelector.equipBestTool uses -- so the equipped item matches what will actually
            // mine the block. The loop above never considered a tier above STONE, so this can never
            // newly select the mission's reserved iron/diamond pickaxe; if every hotbar slot is
            // occupied, proceed with whatever is already equipped rather than aborting.
            for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
                if (Inventory.isHotbarSlot(slot)
                        && bot.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                    InventoryAction.selectHotbar(bot, slot);
                    break;
                }
            }
            return true;
        }
        if (bestOffhandSlot >= 0) {
            bestSlot = InventoryAction.promoteOffhandSlot(bot, bestOffhandSlot).orElse(-1);
            if (bestSlot < 0) {
                return false;
            }
        }
        InventoryAction.equipFromSlot(bot, bestSlot);
        return true;
    }

    private void approachLavaView(AIPlayerEntity bot) {
        if (lavaClue == null
                || !ObservableWorldQuery.canObserveCell(bot, lavaClue)
                || !bot.level().getFluidState(lavaClue).is(FluidTags.LAVA)) {
            lavaClue = null;
            enterScan(bot);
            return;
        }
        if (!isPourPlanUsable(bot, pourPlan)) {
            rejectedPourDestinations.add(pourPlan.destination());
            pourPlan = findPourPlan(bot, lavaClue, rejectedPourDestinations);
            standPos = pourPlan == null ? null : findWorkStand(bot, lavaClue, pourPlan, true);
            if (pourPlan == null || standPos == null) {
                rejectLava(lavaClue);
                resetForNextScan(bot);
                return;
            }
        }
        if (!isSafeStand(bot, standPos, true)) {
            standPos = findWorkStand(bot, lavaClue, pourPlan, true);
            if (standPos == null) {
                rejectLava(lavaClue);
                resetForNextScan(bot);
                return;
            }
        }
        if (!atPourPose(bot, standPos, pourPlan, true)) {
            if (phaseAge() % 40 == 0) {
                BotLog.action(bot, "create_obsidian_lava_approach_wait",
                        "bot", bot.blockPosition().toShortString(),
                        "stand", standPos == null ? "none" : standPos.toShortString(),
                        "water", pourPlan == null ? "none" : pourPlan.destination().toShortString());
            }
            if (phaseAge() > APPROACH_LIMIT) {
                rejectLava(lavaClue);
                resetForNextScan(bot);
                return;
            }
            pathToWorkPose(bot, standPos, "create_obsidian_flow_control_path_failed");
            return;
        }
        bot.getActionPack().stopAll();
        pourFlowControl(bot);
    }

    private void pourFlowControl(AIPlayerEntity bot) {
        int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        Set<BlockPos> observableLava = observableLavaForWaterPlacement(
                bot, pourPlan.destination(), lavaClue);
        ActionResult result = BucketAction.placeWater(bot, pourPlan.support(), pourPlan.face());
        if (result.isFailed()) {
            rejectedPourDestinations.add(pourPlan.destination());
            PourPlan alternate = findPourPlan(bot, lavaClue, rejectedPourDestinations);
            if (alternate != null && atPourPose(bot, standPos, alternate, true)) {
                pourPlan = alternate;
                return;
            }
            fail("create_obsidian_flow_control_failed:" + result.reason());
            return;
        }
        waterSource = pourPlan.destination().immutable();
        waterBucketBaseline = waterBefore;
        // On flat-pool terrain the pour destination can be up to 4 blocks from the clue, and
        // vanilla water only advances one block per 5 ticks: a fixed 4-tick wait would mean the
        // water never reaches the lava, the world shows zero change on recovery, and the same
        // clue gets replayed forever (an F4 livelock). Scale the wait by the actual distance, and
        // remember which clue this pour targeted so a drain that converts nothing can reject it.
        int flowDistance = Math.max(
                Math.abs(waterSource.getX() - lavaClue.getX()),
                Math.abs(waterSource.getZ() - lavaClue.getZ()));
        pourSpreadWaitTicks = Math.max(WATER_SPREAD_TICKS, flowDistance * 5 + 4);
        lastPourClue = lavaClue.immutable();
        MiningEvidenceAudit.recordWaterPlacement(bot, observableLava);
        noteTopologyProgress();
        enter(Phase.WAIT_WATER_SPREAD);
    }

    private void waitForWaterSpread() {
        int requiredTicks = obsidian == null ? pourSpreadWaitTicks : PROTECTION_SPREAD_TICKS;
        if (phaseAge() >= requiredTicks) {
            lavaClue = null;
            pourPlan = null;
            if (obsidian != null) {
                // Let the source neutralize the target's immediate lava ring, then reclaim it
                // before mining. A live flow pushes the clientless player off its dry work pose,
                // which repeatedly cancels BlockMiner and can strand one bucket per retry.
                protectionPrepared = true;
            }
            enter(Phase.RECOVER_WATER);
        }
    }

    private void approachLava(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        if (lavaTarget == null || !isStillLava(world, lavaTarget)) {
            resetForNextScan(bot);
            return;
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) <= 0) {
            resetForNextScan(bot);
            return;
        }
        if (!isPourPlanUsable(bot, pourPlan)) {
            rejectedPourDestinations.add(pourPlan.destination());
            pourPlan = findPourPlan(bot, lavaTarget, rejectedPourDestinations);
            standPos = pourPlan == null ? null : findWorkStand(bot, lavaTarget, pourPlan, true);
            if (pourPlan == null || standPos == null) {
                rejectLava(lavaTarget);
                resetForNextScan(bot);
                return;
            }
        }
        if (!isSafeStand(bot, standPos, true)) {
            standPos = findWorkStand(bot, lavaTarget, pourPlan, true);
            if (standPos == null) {
                rejectLava(lavaTarget);
                resetForNextScan(bot);
                return;
            }
        }
        if (!atPourPose(bot, standPos, pourPlan, true)) {
            if (phaseAge() > APPROACH_LIMIT) {
                rejectLava(lavaTarget);
                resetForNextScan(bot);
                return;
            }
            pathToWorkPose(bot, standPos, "create_obsidian_lava_path_failed");
            return;
        }
        bot.getActionPack().stopAll();
        enter(Phase.POUR);
    }

    private void pour(AIPlayerEntity bot) {
        if (lavaTarget == null || !isStillLava(bot.level(), lavaTarget)) {
            resetForNextScan(bot);
            return;
        }
        int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        Set<BlockPos> observableLava = observableLavaForWaterPlacement(
                bot, pourPlan.destination(), lavaTarget);
        ActionResult result = BucketAction.placeWater(bot, pourPlan.support(), pourPlan.face());
        if (result.isFailed()) {
            rejectedPourDestinations.add(pourPlan.destination());
            PourPlan alternate = findPourPlan(bot, lavaTarget, rejectedPourDestinations);
            if (alternate != null && atPourPose(bot, standPos, alternate, true)) {
                pourPlan = alternate;
                return;
            }
            BotLog.action(bot, "create_obsidian_pour_failed",
                    "lava", lavaTarget.toShortString(), "reason", result.reason());
            rejectLava(lavaTarget);
            resetForNextScan(bot);
            return;
        }
        waterSource = pourPlan.destination().immutable();
        waterBucketBaseline = waterBefore;
        noteTopologyProgress();
        BotLog.action(bot, "create_obsidian_water_placed",
                "water", waterSource.toShortString(), "lava", lavaTarget.toShortString());
        MiningEvidenceAudit.recordWaterPlacement(bot, observableLava);
        enter(Phase.WAIT_FORM);
    }

    private void waitForFormation(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        if (lavaTarget != null && world.getBlockState(lavaTarget).is(Blocks.OBSIDIAN)) {
            obsidian = lavaTarget;
            obsidianStandHint = standPos;
            noteTopologyProgress();
            BotLog.action(bot, "create_obsidian_formed", "pos", obsidian.toShortString());
            MiningEvidenceAudit.recordLavaToObsidian(bot, obsidian);
            if (!MiningEvidenceAudit.isObsidianBreakAuthorized(bot, obsidian)) {
                fail("create_obsidian_conversion_audit_rejected:" + obsidian.toShortString());
                return;
            }
            lavaTarget = null;
            // Give scheduled fluid collision a brief settling window, then reclaim the source
            // before its flow pushes the bot off the safe rim. Reusing one real bucket is more
            // reliable than depending on an opportunistic full-pool fan-out.
            enter(Phase.WAIT_WATER_SPREAD);
            return;
        }
        // Fluid collision and scheduled fluid ticks may temporarily replace the source state before
        // OBSIDIAN appears. Only the deadline decides failure; do not collapse that transition to a
        // one-tick check.
        if (phaseAge() <= FORMATION_LIMIT && lavaTarget != null) {
            return;
        }
        if (lavaTarget != null) {
            rejectLava(lavaTarget);
            BotLog.action(bot, "create_obsidian_formation_failed",
                    "pos", lavaTarget.toShortString(), "waited", phaseAge());
        }
        lavaTarget = null;
        obsidian = null;
        enter(Phase.RECOVER_WATER);
    }

    private void recoverWater(AIPlayerEntity bot) {
        if (pickupPos != null) {
            rememberVisiblePendingObsidianDrop(bot);
        }
        if (recoveryWalk != null) {
            // A swim stroke or a step out of the pool is in flight: it has the bot to itself. When it has ended the recovery is derived
            // again from where the bot stands (a refused or failed step keeps the old failure reasons).
            var pack = bot.getActionPack();
            ActionPack.StepLease lease = recoveryWalkLease;
            if (pack.stepInFlightFor(lease)) {
                return;
            }
            // A later safety successor is not this task's surface/rim result. Forget only the
            // local admission and leave that owner uninterrupted until its own next tick.
            if (!pack.stepIdle()) {
                releaseRecoveryWalk(bot);
                return;
            }
            String walk = recoveryWalk;
            WalkedStep.Result walked = pack.stepResultFor(lease);
            releaseRecoveryWalk(bot);
            if (walked != null && walked.failed()) {
                fail("surface".equals(walk)
                        ? "create_obsidian_water_recovery_surface_blocked"
                        : "create_obsidian_water_recovery_rim_blocked");
                return;
            }
        }
        if (waterSource != null && isStillWater(bot.level(), waterSource)) {
            if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
                fail("create_obsidian_bucket_lost_after_pour");
                return;
            }
            BlockPos recoveryFeet = bot.blockPosition().immutable();
            boolean needsSurface = recoveryFeet.getY() < waterSource.getY();
            if (!needsSurface && recoveryFeet.getY() == waterSource.getY()
                    && SwimRoute.canObserveColumn(bot, recoveryFeet)) {
                needsSurface = !bot.level().getFluidState(recoveryFeet).isEmpty() && bot.isUnderWater();
            }
            if (needsSurface) {
                // (A body standing in a shallow flowing sheet at the source's level already has its head in the air: a swim stroke
                // cannot lift it a whole cell there, and it has no reason to.)
                // Fake players have no client buoyancy. After collecting in the protected hole,
                // make one collision-validated adjacent rise so the eye is above the flowing
                // sheet and the retained source face becomes ray-visible again.
                // A swim stroke (the jump key in water), not a hop: the step ends when the body is in the cell above.
                beginRecoveryStep(bot, recoveryFeet.above(), WalkedStep.Kind.SWIM,
                        "obsidian_surface", "surface");
                return;
            }
            if (obsidianStandHint != null
                    && canObserveRecoveryTransitEnvelope(bot, obsidianStandHint)
                    && bot.level().getFluidState(obsidianStandHint).isEmpty()
                    && bot.level().getFluidState(obsidianStandHint.above()).isEmpty()
                    && !bot.blockPosition().equals(obsidianStandHint)
                    && isAdjacentMicroStep(bot.blockPosition(), obsidianStandHint)) {
                // Leave the water column while it still provides the legitimate upward movement,
                // then recover from the dry remembered rim. Removing the source while suspended
                // over the hole drops the fake player straight back to the pool floor.
                beginRecoveryStep(bot, obsidianStandHint, WalkedStep.Kind.FLAT,
                        "obsidian_return_rim", "rim");
                return;
            }
            if (bot.getEyePosition().distanceToSqr(waterSource.getCenter()) <= REACH_MARGIN_SQUARED) {
                bot.getActionPack().stopAll();
                ActionResult result = BucketAction.fillWaterSource(bot, waterSource);
                if (result.isSuccess()) {
                    if (waterBucketBaseline >= 0
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) < waterBucketBaseline) {
                        fail("create_obsidian_water_recovery_inventory_mismatch");
                        return;
                    }
                    noteTopologyProgress();
                    waterSource = null;
                    waterBucketBaseline = -1;
                    enter(Phase.WAIT_DRAIN);
                    return;
                }
                if (phaseAge() % 20 == 0) {
                    BotLog.action(bot, "create_obsidian_water_recovery_retry",
                            "water", waterSource.toShortString(),
                            "bot", bot.blockPosition().toShortString(),
                            "reason", result.reason());
                }
            }
            if (waterSource != null) {
                BlockPos recoveryStand = findWorkStand(bot, waterSource, null, false);
                if (recoveryStand != null) {
                    standPos = recoveryStand;
                    pathToWorkPose(bot, recoveryStand, "create_obsidian_water_recovery_path_failed");
                }
            }
        } else if (waterBucketBaseline < 0
                || InventoryAction.countItem(bot, Items.WATER_BUCKET) >= waterBucketBaseline) {
            waterSource = null;
            waterBucketBaseline = -1;
            enter(Phase.WAIT_DRAIN);
            return;
        }
        if (phaseAge() > RECOVERY_LIMIT) {
            BotLog.action(bot, "create_obsidian_water_recovery_timeout",
                    "water", waterSource == null ? "none" : waterSource.toShortString());
            if (waterSource != null && isStillWater(bot.level(), waterSource)) {
                fail("create_obsidian_water_recovery_failed pos=" + waterSource.toShortString());
            } else if (waterBucketBaseline >= 0
                    && InventoryAction.countItem(bot, Items.WATER_BUCKET) < waterBucketBaseline) {
                fail("create_obsidian_water_source_lost pos="
                        + (waterSource == null ? "none" : waterSource.toShortString()));
            } else {
                enter(Phase.WAIT_DRAIN);
            }
        }
    }

    /**
     * Starts a one-cell, observed recovery move from this task's own temporary water placement.
     * The action is intentionally local, but it still proves every cell that the vanilla movement
     * validator may inspect and retains that proof for every later tick.
     */
    private boolean beginRecoveryStep(AIPlayerEntity bot, BlockPos cell, WalkedStep.Kind kind,
                                      String reason, String recoveryKind) {
        if (!canObserveRecoveryTransitEnvelope(bot, cell)
                || !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, cell, kind)
                || WalkedStep.refusal(bot, cell, kind) != null) {
            BotLog.action(bot, "create_obsidian_recovery_step_refused",
                    "kind", recoveryKind, "target", cell.toShortString(), "reason", "unobserved_or_unsafe");
            return false;
        }
        RecoveryStepAdmission admission = new RecoveryStepAdmission(bot.blockPosition(), cell, kind);
        ActionPack.StepLease lease = bot.getActionPack().runStep(WalkedStep.begin(bot, cell, kind, reason),
                (guardBot, guardedStep) -> canContinueObservedRecoveryStep(
                        guardBot, guardedStep, admission));
        if (lease == null) {
            return false;
        }
        recoveryWalk = recoveryKind;
        recoveryWalkLease = lease;
        return true;
    }

    /** State-free proof for the current, destination, and support cells of a recovery step. */
    private static boolean canObserveRecoveryTransitEnvelope(AIPlayerEntity bot, BlockPos cell) {
        return SwimRoute.canObserveColumn(bot, bot.blockPosition())
                && SwimRoute.canObserveColumn(bot, cell)
                && ObservableWorldQuery.canObserveCellThroughFluids(bot, cell.below());
    }

    /** Re-proves this exact recovery move before WalkedStep may perform another terrain read. */
    private static boolean canContinueObservedRecoveryStep(AIPlayerEntity bot, WalkedStep step,
                                                            RecoveryStepAdmission admission) {
        return step.kind() == admission.kind()
                && step.cell().equals(admission.destination())
                && withinRecoveryStepContinuationEnvelope(bot.blockPosition(), admission, step.ticks())
                && canObserveRecoveryTransitEnvelope(bot, admission.destination())
                && SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, admission.destination(), step.kind());
    }

    /** No-world-read source/destination envelope matching WalkedStep's first-tick normalization. */
    private static boolean withinRecoveryStepContinuationEnvelope(BlockPos feet,
                                                                  RecoveryStepAdmission admission,
                                                                  int activeStepTicks) {
        BlockPos origin = admission.origin();
        BlockPos destination = admission.destination();
        if (between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getY(), origin.getY(), destination.getY())
                && between(feet.getZ(), origin.getZ(), destination.getZ())) {
            return true;
        }
        return (admission.kind() == WalkedStep.Kind.FLAT
                || admission.kind() == WalkedStep.Kind.STEP_UP
                || admission.kind() == WalkedStep.Kind.STEP_DOWN)
                && activeStepTicks >= 0 && activeStepTicks <= 1
                && feet.getX() == origin.getX()
                && feet.getZ() == origin.getZ()
                && Math.abs(feet.getY() - origin.getY()) == 1;
    }

    private void waitForDrain(AIPlayerEntity bot) {
        if (pickupPos != null) {
            // Flow continues moving entities after the source is reclaimed. Preserve the last
            // player-observable cell throughout the complete drain window so an edge fall can be
            // recovered without a hidden entity scan once PICKUP resumes.
            rememberVisiblePendingObsidianDrop(bot);
        }
        if (phaseAge() < FLOW_DRAIN_TICKS) {
            return;
        }
        // The complete spread-and-drain window is the causal lifetime of one real water
        // placement. Reconcile observable conversions once, then discard every unconverted
        // candidate before any later protection pour or natural fluid update can reuse it.
        MiningEvidenceAudit.reconcileAndCloseWaterPlacement(bot);
        waterSource = null;
        if (pickupPos != null) {
            // The block is already broken. Let the drained item settle, then resume the durable
            // physical pickup ledger instead of clearing it as if the pool cycle had completed.
            lastPourClue = null;
            enter(Phase.PICKUP);
            return;
        }
        if (obsidian != null) {
            // The formation position and its work pose are already observed facts. Recovery may
            // have walked us beyond current line-of-sight; return to that known pose first, then
            // re-observe the block. Rejecting it from here would turn temporary occlusion into a
            // permanent false negative.
            lastPourClue = null;
            standPos = obsidianStandHint;
            enter(Phase.APPROACH_OBSIDIAN);
            return;
        }
        if (lastPourClue != null) {
            // The full spread-and-drain lifetime of one real pour produced no conversion and no
            // pickup at this clue. Replaying the identical pour resets the stall detector every
            // cycle and can burn the whole 153,600-tick task budget (F4); rotate the barren clue
            // out through the bounded reject ledger instead. Rejection only acts on an observed
            // still-lava cell — occlusion must not turn into a false negative.
            if (bot.level().getFluidState(lastPourClue).is(FluidTags.LAVA)
                    && ObservableWorldQuery.canObserveCell(bot, lastPourClue)) {
                rejectLava(lastPourClue);
                BotLog.action(bot, "create_obsidian_barren_pour_rejected",
                        "clue", lastPourClue.toShortString());
            }
            lastPourClue = null;
        }
        if (obsidianStandHint != null && !bot.blockPosition().equals(obsidianStandHint)) {
            // Source recovery can leave the fake player on the one-block-deep pool floor. Return
            // through normal path execution to the already observed dry rim before surveying the
            // remaining pool; scanning from the hole turns temporary occlusion into false no_lava.
            if (phaseAge() == FLOW_DRAIN_TICKS) {
                BotLog.action(bot, "create_obsidian_return_rim",
                        "from", bot.blockPosition().toShortString(),
                        "to", obsidianStandHint.toShortString());
            }
            if (phaseAge() > FLOW_DRAIN_TICKS + APPROACH_LIMIT) {
                fail("create_obsidian_return_rim_failed");
                return;
            }
            pathToWorkPose(bot, obsidianStandHint, "create_obsidian_return_rim_failed");
            return;
        }
        resetForNextScan(bot);
    }

    private void approachObsidian(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        if (obsidian == null) {
            resetForNextScan(bot);
            return;
        }
        // A remembered work pose can be followed without reading the now-occluded target. Only
        // after arriving do we use a fresh visibility ray and inspect the current block state.
        if (standPos != null && bot.blockPosition().distSqr(standPos) > 1.0D) {
            if (phaseAge() > APPROACH_LIMIT) {
                rejectObsidian(obsidian);
                obsidian = null;
                resetForNextScan(bot);
                return;
            }
            pathToWorkPose(bot, standPos, "create_obsidian_return_to_view_failed");
            return;
        }
        if (waterSource != null && atKnownProtectedMinePose(bot, standPos, obsidian)) {
            // This exact target and work pose were observed when vanilla fluid formed the block.
            // The retained source now sits between the eye ray and obsidian, so a Fluid.ANY
            // visibility check would reject the very protection a player deliberately keeps.
            // Interaction reach + unchanged safe pose bound this exception to that remembered cell.
            bot.getActionPack().stopAll();
            BotLog.action(bot, "create_obsidian_protected_mine_pose",
                    "pos", obsidian.toShortString(), "water", waterSource.toShortString());
            if (!requireObsidianMiningTool(bot)) {
                return;
            }
            if (!prepareActiveBreak(bot, obsidian)) {
                return;
            }
            enter(Phase.MINE);
            return;
        }
        if (!ObservableWorldQuery.canObserveCell(bot, obsidian)) {
            BlockPos refreshedStand = findWorkStand(bot, obsidian, null, true);
            if (refreshedStand == null || refreshedStand.equals(standPos)) {
                rejectObsidian(obsidian);
                obsidian = null;
                resetForNextScan(bot);
                return;
            }
            standPos = refreshedStand;
            obsidianStandHint = refreshedStand;
            enter(Phase.APPROACH_OBSIDIAN);
            return;
        }
        if (!isSafeStand(bot, standPos, true)) {
            BlockPos refreshedStand = findWorkStand(bot, obsidian, null, true);
            if (refreshedStand == null || refreshedStand.equals(standPos)) {
                rejectObsidian(obsidian);
                obsidian = null;
                resetForNextScan(bot);
                return;
            }
            standPos = refreshedStand;
            obsidianStandHint = refreshedStand;
            enter(Phase.APPROACH_OBSIDIAN);
            return;
        }
        if (!world.getBlockState(obsidian).is(Blocks.OBSIDIAN)) {
            resetForNextScan(bot);
            return;
        }
        if (waterSource == null && !protectionPrepared) {
            if (InventoryAction.countItem(bot, Items.WATER_BUCKET) <= 0) {
                obsidian = null;
                enter(Phase.FIND_WATER);
                return;
            }
            rejectedPourDestinations.clear();
            pourPlan = findPourPlan(bot, obsidian, rejectedPourDestinations);
            BlockPos protectionStand = pourPlan == null
                    ? null : findWorkStand(bot, obsidian, pourPlan, true);
            if (pourPlan == null || protectionStand == null) {
                rejectObsidian(obsidian);
                obsidian = null;
                resetForNextScan(bot);
                return;
            }
            standPos = protectionStand;
            enter(Phase.PROTECT_OBSIDIAN);
            return;
        }
        if (!protectionPrepared && hasObservableNearbyLava(bot, obsidian, 2)) {
            obsidian = null;
            resetForNextScan(bot);
            return;
        }
        if (!atWorkPose(bot, standPos, obsidian, null, true)) {
            if (phaseAge() > APPROACH_LIMIT) {
                rejectObsidian(obsidian);
                obsidian = null;
                resetForNextScan(bot);
                return;
            }
            pathToWorkPose(bot, standPos, "create_obsidian_mine_path_failed");
            return;
        }
        bot.getActionPack().stopAll();
        if (!requireObsidianMiningTool(bot)) {
            return;
        }
        if (!prepareActiveBreak(bot, obsidian)) {
            return;
        }
        enter(Phase.MINE);
    }

    private void protectObsidian(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        if (obsidian == null || !world.getBlockState(obsidian).is(Blocks.OBSIDIAN)) {
            resetForNextScan(bot);
            return;
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) <= 0) {
            obsidian = null;
            enter(Phase.FIND_WATER);
            return;
        }
        if (!isPourPlanUsable(bot, pourPlan)) {
            if (pourPlan != null) {
                rejectedPourDestinations.add(pourPlan.destination());
            }
            pourPlan = findPourPlan(bot, obsidian, rejectedPourDestinations);
            standPos = pourPlan == null ? null : findWorkStand(bot, obsidian, pourPlan, true);
        }
        if (pourPlan == null || standPos == null) {
            rejectObsidian(obsidian);
            obsidian = null;
            resetForNextScan(bot);
            return;
        }
        if (!atPourPose(bot, standPos, pourPlan, true)) {
            if (phaseAge() > APPROACH_LIMIT) {
                rejectObsidian(obsidian);
                obsidian = null;
                resetForNextScan(bot);
                return;
            }
            pathToWorkPose(bot, standPos, "create_obsidian_protection_path_failed");
            return;
        }
        bot.getActionPack().stopAll();
        int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        Set<BlockPos> observableLava = observableLavaForWaterPlacement(
                bot, pourPlan.destination(), null);
        ActionResult placed = BucketAction.placeWater(bot, pourPlan.support(), pourPlan.face());
        if (placed.isFailed()) {
            rejectedPourDestinations.add(pourPlan.destination());
            pourPlan = null;
            if (phaseAge() > APPROACH_LIMIT) {
                fail("create_obsidian_protection_failed:" + placed.reason());
            }
            return;
        }
        waterSource = pourPlan.destination().immutable();
        waterBucketBaseline = waterBefore;
        MiningEvidenceAudit.recordWaterPlacement(bot, observableLava);
        // Keep the stand selected against this exact pour geometry. The pre-protection mining
        // hint may be directly above the target and will be flooded by the new adjacent source.
        obsidianStandHint = standPos.immutable();
        protectionPrepared = false;
        noteTopologyProgress();
        BotLog.action(bot, "create_obsidian_protection_placed",
                "obsidian", obsidian.toShortString(),
                "water", waterSource.toShortString());
        enter(Phase.WAIT_WATER_SPREAD);
    }

    private void mine(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        if (obsidian == null) {
            resetForNextScan(bot);
            return;
        }
        if (!prepareActiveBreak(bot, obsidian)) {
            return;
        }
        BlockMiner.Status status;
        if (miner.target() != null && miner.target().equals(obsidian)) {
            // Let BlockMiner observe AIR and report DONE after its controller breaks the block.
            // Checking for OBSIDIAN first would skip the drop-collection phase on every success.
            if (world.getBlockState(obsidian).isAir()) {
                status = miner.tick(bot);
            } else {
                boolean stableMinePose = waterSource != null
                        ? atKnownProtectedMinePose(bot, standPos, obsidian)
                        : atWorkPose(bot, standPos, obsidian, null, true);
                if (!stableMinePose
                        || (waterSource == null && !protectionPrepared && totalBudget() % 10 == 0
                        && hasObservableNearbyLava(bot, obsidian, 2))) {
                    miner.cancel(bot);
                    enter(Phase.APPROACH_OBSIDIAN);
                    return;
                }
                if (world.getBlockState(obsidian).is(Blocks.OBSIDIAN)
                        && !requireObsidianMiningTool(bot)) {
                    return;
                }
                bot.getActionPack().stopMovement();
                status = miner.tick(bot);
            }
        } else {
            if (!world.getBlockState(obsidian).is(Blocks.OBSIDIAN)) {
                resetForNextScan(bot);
                return;
            }
            if (waterSource == null && !protectionPrepared
                    && hasObservableNearbyLava(bot, obsidian, 2)) {
                miner.cancel(bot);
                obsidian = null;
                resetForNextScan(bot);
                return;
            }
            // A raw-2 pick is one valid remaining break under the service contract. Require the
            // tool only when starting that physical break; after it becomes raw-1, the next tick
            // must still settle AIR -> pickup -> water recovery instead of failing before the
            // durable transaction ledger is advanced.
            if (!requireObsidianMiningTool(bot)) {
                return;
            }
            status = beginMine(bot, obsidian);
        }
        if (status == BlockMiner.Status.DONE) {
            noteTopologyProgress();
            pickupPos = activeBreakPos;
            pickupLastSeenPos = pickupPos;
            pickupInventoryBaseline = activeBreakInventoryBaseline;
            pickupGainTick = -1;
            returnRim = durableReturnRim(pickupPos);
            BotLog.action(bot, "create_obsidian_pickup_pending",
                    "pos", pickupPos == null ? "none" : pickupPos.toShortString(),
                    "inventory", pickupInventoryBaseline,
                    "rim", returnRim.toShortString());
            if (!MiningEvidenceAudit.recordObsidianBreak(bot, pickupPos)) {
                clearActiveBreak();
                fail("create_obsidian_break_audit_rejected:" + pickupPos.toShortString());
                return;
            }
            clearActiveBreak();
            obsidian = null;
            protectionPrepared = false;
            // The wash source was already recovered so BlockMiner could work from a dry pose.
            // Put a fresh real source into the opened cell immediately: hidden side lava can only
            // become observable after the block is gone. PROTECT_PICKUP keeps it long enough to
            // neutralize that ring, then recovers and drains the flow before physical collection;
            // leaving a live source throughout PICKUP can wash the drop out of the bounded cave.
            if (waterSource != null && InventoryAction.countItem(bot, Items.BUCKET) > 0) {
                enter(Phase.RECOVER_WATER);
            } else if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0) {
                enter(Phase.PROTECT_PICKUP);
                protectPickup(bot);
            } else {
                fail("create_obsidian_pickup_protection_missing_water");
            }
        } else if (status == BlockMiner.Status.FAILED) {
            BotLog.action(bot, "create_obsidian_mine_failed", "reason", miner.failureReason());
            if (obsidian != null) {
                rejectObsidian(obsidian);
            }
            resetForNextScan(bot);
        }
    }

    private boolean requireObsidianMiningTool(AIPlayerEntity bot) {
        if (ToolTier.canHarvestWithInventory(bot, Blocks.OBSIDIAN.defaultBlockState())) {
            return true;
        }
        fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(Blocks.OBSIDIAN));
        return false;
    }

    private boolean prepareActiveBreak(AIPlayerEntity bot, BlockPos target) {
        if (target == null) {
            fail("create_obsidian_active_break_missing_target");
            return false;
        }
        if (!MiningEvidenceAudit.isObsidianBreakAuthorized(bot, target)) {
            fail("create_obsidian_break_not_conversion_backed:" + target.toShortString());
            return false;
        }
        if (activeBreakPos == null) {
            activeBreakPos = target.immutable();
            activeBreakInventoryBaseline = HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN));
            return true;
        }
        if (!activeBreakPos.equals(target) || activeBreakInventoryBaseline < 0) {
            fail("create_obsidian_active_break_ledger_mismatch");
            return false;
        }
        return true;
    }

    private void promoteActiveBreakToPickup(AIPlayerEntity bot) {
        if (activeBreakPos == null || activeBreakInventoryBaseline < 0 || pickupPos != null) {
            return;
        }
        pickupPos = activeBreakPos;
        pickupLastSeenPos = pickupPos;
        pickupInventoryBaseline = activeBreakInventoryBaseline;
        pickupGainTick = -1;
        returnRim = returnRim == null ? durableReturnRim(pickupPos) : returnRim;
        obsidian = null;
        protectionPrepared = false;
        if (!MiningEvidenceAudit.recordObsidianBreak(bot, pickupPos)) {
            clearActiveBreak();
            fail("create_obsidian_break_audit_rejected:" + pickupPos.toShortString());
            return;
        }
        clearActiveBreak();
    }

    private void clearActiveBreak() {
        activeBreakPos = null;
        activeBreakInventoryBaseline = -1;
    }

    private void protectPickup(AIPlayerEntity bot) {
        if (pickupPos == null || pickupInventoryBaseline < 0) {
            fail("create_obsidian_pickup_protection_missing_ledger");
            return;
        }
        if (waterSource != null) {
            rememberVisiblePendingObsidianDrop(bot);
            // Match the pre-mine protection contract: give scheduled fluid collisions enough time
            // to neutralize newly exposed side lava, then reclaim the reusable source. WAIT_DRAIN
            // already preserves pickupPos and resumes the same durable ledger once the flow can no
            // longer wash the ItemEntity away from the break cell.
            if (phaseAge() >= PROTECTION_SPREAD_TICKS) {
                enter(Phase.RECOVER_WATER);
                recoverWater(bot);
            }
            return;
        }
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) <= 0) {
            fail("create_obsidian_pickup_protection_missing_water");
            return;
        }
        ActionResult last = ActionResult.failed("no_visible_support_face");
        BlockPos[] destinations = {pickupPos, pickupPos.above()};
        for (BlockPos destination : destinations) {
            if (destination.equals(bot.blockPosition())) {
                continue;
            }
            for (Direction supportDirection : Direction.values()) {
                BlockPos support = destination.relative(supportDirection);
                PourPlan candidate = new PourPlan(support, supportDirection.getOpposite());
                if (!isPourPlanUsable(bot, candidate)
                        || !atPourPose(bot, bot.blockPosition(), candidate, false)) {
                    continue;
                }
                int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
                Set<BlockPos> observableLava = observableLavaForWaterPlacement(
                        bot, destination, null);
                last = BucketAction.placeWater(bot, candidate.support(), candidate.face());
                if (last.isSuccess()) {
                    waterSource = destination.immutable();
                    waterBucketBaseline = waterBefore;
                    MiningEvidenceAudit.recordWaterPlacement(bot, observableLava);
                    noteTopologyProgress();
                    BotLog.action(bot, "create_obsidian_pickup_protected",
                            "drop", pickupPos.toShortString(),
                            "water", waterSource.toShortString());
                    return;
                }
            }
        }
        // Protection is opportunistic when no vanilla-clickable support face exists. The durable
        // pickup ledger still drives an immediate physical approach; failing the whole mission
        // here abandons a live drop that is often already within collision range.
        BotLog.action(bot, "create_obsidian_pickup_protection_unavailable",
                "drop", pickupPos.toShortString(), "reason", last.reason());
        enter(Phase.PICKUP);
    }

    private void pickup(AIPlayerEntity bot) {
        if (awaitPickupStep(bot)) {
            return;
        }
        int inventoryNow = HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN));
        if (inventoryNow > pickupInventoryBaseline && pickupGainTick < 0) {
            pickupGainTick = totalBudget();
        }
        Optional<ItemEntity> visibleDrop = nearestPendingObsidianDrop(bot);
        if (pickupGainTick >= 0 && totalBudget() - pickupGainTick >= 5 && visibleDrop.isEmpty()) {
            // Exact pickup micro-steps may have overtaken an older approach path. Cancel it before
            // assigning the rim return, otherwise stale nodes are interpreted from the new pose.
            bot.getActionPack().stopAll();
            BotLog.action(bot, "create_obsidian_pickup_confirmed",
                    "pos", pickupPos == null ? "none" : pickupPos.toShortString(),
                    "gained", inventoryNow - pickupInventoryBaseline);
            if (!MiningEvidenceAudit.recordObsidianPickup(
                    bot, pickupPos, inventoryNow - pickupInventoryBaseline)) {
                fail("create_obsidian_pickup_audit_rejected:" + pickupPos.toShortString());
                return;
            }
            pickupPos = null;
            pickupLastSeenPos = null;
            pickupInventoryBaseline = -1;
            pickupGainTick = -1;
            if (waterSource != null && InventoryAction.countItem(bot, Items.BUCKET) > 0) {
                enter(Phase.RECOVER_WATER);
            } else {
                enter(Phase.RETURN_TO_RIM);
            }
            return;
        }
        if (pickupPos == null || pickupInventoryBaseline < 0) {
            // A prior tick's approachDropPhysically/chaseDropAnyOf-style nudge can leave sneaking
            // held; every exit from this method must clear it so a paused task beneath this one
            // never deadlocks on ActionPack.hasActiveActions().
            bot.getActionPack().stopAll();
            fail("create_obsidian_pickup_checkpoint_missing");
            return;
        }

        // The exact break cell is durable task memory. Drive the player into collision range even
        // if the ItemEntity is temporarily occluded; strict mode never mutates inventory directly.
        if (visibleDrop.isPresent()) {
            // Follow the entity's exact sub-block position. Water can keep a fresh drop moving
            // during vanilla's pickup delay; hopping between its successive block coordinates
            // overshoots the collision box and may chase it forever. HarvestCore keeps the bot on
            // a factual stand and performs bounded in-cell nudges toward the observed entity.
            ItemEntity observed = visibleDrop.orElseThrow();
            pickupLastSeenPos = observed.blockPosition().immutable();
            HarvestCore.approachDropPhysically(bot, observed);
        } else {
            boolean pursuingLastSeen = pickupLastSeenPos != null
                    && !pickupLastSeenPos.equals(pickupPos)
                    && HarvestCore.approachKnownPickupCell(bot, pickupLastSeenPos);
            if (!pursuingLastSeen) {
                BlockPos pickupPose = pickupPoseForMinedObsidian(bot, pickupPos);
                if (!bot.blockPosition().equals(pickupPos)
                        && stepTowardPickupCell(bot, pickupPos)) {
                    // The durable break cell is the exact fallback pickup destination. A* may
                    // legitimately snap an occluded or water-filled endpoint to the nearest
                    // standable cell; bounded microsteps close the remaining gap.
                } else if (pickupPose != null && !bot.blockPosition().equals(pickupPose)) {
                    pathToPickupPose(bot, pickupPose, "create_obsidian_pickup_path_failed");
                }
            }
        }
        if (phaseAge() > PICKUP_LIMIT) {
            bot.getActionPack().stopAll();
            fail("create_obsidian_drop_not_collected pos=" + pickupPos.toShortString()
                    + " last_seen=" + (pickupLastSeenPos == null
                    ? "none" : pickupLastSeenPos.toShortString())
                    + " collected=" + collected);
        }
    }

    private void rememberVisiblePendingObsidianDrop(AIPlayerEntity bot) {
        nearestPendingObsidianDrop(bot).ifPresent(drop ->
                pickupLastSeenPos = drop.blockPosition().immutable());
    }

    /**
     * Binds pickup pursuit to this transaction's durable break cell. Choosing the obsidian entity
     * nearest to the player can attach the ledger to an unrelated nearby drop, especially when
     * several mining tasks are active in the same cave. Water may move the owned drop after the
     * break, so search a bounded box around the recorded cell and rank by distance to that cell.
     */
    private Optional<ItemEntity> nearestPendingObsidianDrop(AIPlayerEntity bot) {
        if (pickupPos == null) {
            return Optional.empty();
        }
        Vec3 transactionOrigin = pickupPos.getCenter();
        AABB search = new AABB(pickupPos).inflate(8.0D, 4.0D, 8.0D);
        return bot.level().getEntitiesOfClass(
                        ItemEntity.class,
                        search,
                        entity -> entity.isAlive()
                                && entity.getItem().is(Items.OBSIDIAN)
                                && ObservableWorldQuery.canObserveEntity(bot, entity))
                .stream()
                .min(Comparator
                        .comparingDouble((ItemEntity entity) ->
                                entity.position().distanceToSqr(transactionOrigin))
                        .thenComparingDouble(entity -> entity.distanceTo(bot)));
    }

    private void returnToRim(AIPlayerEntity bot) {
        BlockPos target = returnRim == null ? searchCursor.face() : returnRim;
        if (bot.blockPosition().equals(target)) {
            bot.getActionPack().stopAll();
            returnRim = null;
            lastProgressTick = totalBudget();
            resetForNextScan(bot);
            return;
        }
        if (phaseAge() > SEARCH_RETURN_LIMIT) {
            fail("create_obsidian_return_rim_failed:" + target.toShortString());
            return;
        }
        pathToWorkPose(bot, target, "create_obsidian_return_rim_path_failed");
    }

    private static BlockPos pickupPoseForMinedObsidian(AIPlayerEntity bot, BlockPos minedPos) {
        BlockPos below = minedPos.below();
        BlockPos[] candidates = {
                minedPos,
                minedPos.north(), minedPos.east(), minedPos.south(), minedPos.west(),
                below,
                below.north(), below.east(), below.south(), below.west()
        };
        for (BlockPos candidate : candidates) {
            // SwimRoute proves feet, head and support before it performs its standability read.
            // A pickup pose may be remembered, but it is never a license to inspect unseen ground.
            if (SwimRoute.observedCell(bot, bot.level(), candidate, false) == SwimRoute.Cell.DRY) {
                return candidate.immutable();
            }
        }
        // The break cell itself is factual task memory. Path execution still validates collision
        // before moving, so temporary LOS loss must not turn one mined block into a lost drop.
        return minedPos.immutable();
    }

    private BlockPos durableReturnRim(BlockPos minedPos) {
        BlockPos lostSupport = minedPos == null ? null : minedPos.above();
        if (obsidianStandHint != null && !obsidianStandHint.equals(lostSupport)) {
            return obsidianStandHint.immutable();
        }
        if (standPos != null && !standPos.equals(lostSupport)) {
            return standPos.immutable();
        }
        // The search face is a persisted dry waypoint reached before interacting with this pool;
        // unlike a pose directly above the target, its support survives the target break.
        return searchCursor.face();
    }

    /**
     * Closes the last few cells to a factual ItemEntity/break position with exact adjacent moves.
     *
     * <p>Pathfinding endpoints must be standable, while a protected physical drop may sit in a
     * water-filled collision cell. When A* snaps that endpoint to the current safe cell, repeatedly
     * asking for the same path makes no physical progress. This adapter is deliberately bounded to
     * three horizontal cells and validates every transit/final cell before starting one walked
     * step (real movement keys, see {@link WalkedStep}; the bot arrives a few ticks later, and the
     * caller asks again while the step is in flight, which answers true without starting another).</p>
     */
    boolean stepTowardPickupCell(AIPlayerEntity bot, BlockPos target) {
        if (awaitPickupStep(bot)) {
            return true;
        }
        if (!bot.getActionPack().stepIdle()) {
            return true;
        }
        BlockPos from = bot.blockPosition();
        int deltaX = target.getX() - from.getX();
        int deltaY = target.getY() - from.getY();
        int deltaZ = target.getZ() - from.getZ();
        int absX = Math.abs(deltaX);
        int absY = Math.abs(deltaY);
        int absZ = Math.abs(deltaZ);
        if (absY > 1 || Math.max(absX, absZ) > PICKUP_MICROSTEP_RANGE
                || absX == 0 && absY == 0 && absZ == 0) {
            return false;
        }

        int stepX = Integer.compare(deltaX, 0);
        int stepZ = Integer.compare(deltaZ, 0);
        int changedAxes = (absX == 0 ? 0 : 1) + (absY == 0 ? 0 : 1) + (absZ == 0 ? 0 : 1);
        boolean adjacent = absX <= 1 && absY <= 1 && absZ <= 1 && changedAxes <= 2;
        if (adjacent && isSafePickupCollisionCell(bot, target)) {
            bot.getActionPack().stopAll();
            return beginPickupStep(bot, target, "obsidian_pickup_collision_step");
        }

        // Align one horizontal axis at the current elevation. The intermediate must be a genuine
        // safe stand; only the final collision cell may be water-filled/non-standable.
        BlockPos first = absX >= absZ && stepX != 0
                ? from.offset(stepX, 0, 0)
                : from.offset(0, 0, stepZ);
        BlockPos second = first.getX() == from.getX() && stepX != 0
                ? from.offset(stepX, 0, 0)
                : stepZ != 0 ? from.offset(0, 0, stepZ) : null;
        BlockPos[] candidates = second == null || second.equals(first)
                ? new BlockPos[]{first}
                : new BlockPos[]{first, second};
        for (BlockPos candidate : candidates) {
            // This combines ray-proven feet/head/support with the only permitted standability
            // read.  Do not use a raw terrain query to line up with a remembered pickup cell.
            if (SwimRoute.observedCell(bot, bot.level(), candidate, false) != SwimRoute.Cell.DRY) {
                continue;
            }
            bot.getActionPack().stopAll();
            if (beginPickupStep(bot, candidate, "obsidian_pickup_collision_align")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts the walked step onto one adjacent pickup cell: a swim stroke into a water-filled cell, else a walk, hop or drop onto the
     * standable one. False when the step is not legal (no adjacent kind covers it, or the landing is refused).
     */
    private boolean beginPickupStep(AIPlayerEntity bot, BlockPos cell, String reason) {
        if (!canObservePickupTransitEnvelope(bot, cell)) {
            return false;
        }
        ServerLevel world = bot.level();
        BlockPos from = bot.blockPosition();
        boolean wet = world.getFluidState(cell).is(FluidTags.WATER) || world.getFluidState(cell.above()).is(FluidTags.WATER);
        WalkedStep.Kind kind = wet ? WalkedStep.Kind.SWIM : WalkedStepRules.walkKindFor(cell.getY() - from.getY());
        if (kind == null
                || !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, cell, kind)
                || WalkedStep.refusal(bot, cell, kind) != null) {
            return false;
        }
        PickupStepAdmission admission = new PickupStepAdmission(from, cell, kind);
        ActionPack.StepLease lease = bot.getActionPack().runStep(WalkedStep.begin(bot, cell, kind, reason),
                (guardBot, guardedStep) -> canContinueObservedPickupStep(
                        guardBot, guardedStep, admission));
        if (lease == null) {
            return false;
        }
        pickupStepLease = lease;
        return true;
    }

    private static boolean isSafePickupCollisionCell(AIPlayerEntity bot, BlockPos target) {
        if (!canObservePickupTransitEnvelope(bot, target)) {
            return false;
        }
        ServerLevel world = bot.level();
        BlockState feet = world.getBlockState(target);
        BlockState head = world.getBlockState(target.above());
        BlockState below = world.getBlockState(target.below());
        boolean supportedOrSwimming = !below.getCollisionShape(world, target.below()).isEmpty()
                || feet.getFluidState().is(FluidTags.WATER);
        return feet.getCollisionShape(world, target).isEmpty()
                && head.getCollisionShape(world, target.above()).isEmpty()
                && supportedOrSwimming
                && !Standability.isDangerous(feet)
                && !Standability.isDangerous(head)
                && !Standability.isDangerous(below);
    }

    /**
     * Proves every world cell that pickup collision admission and a later {@link WalkedStep} may
     * inspect.  The water-transparent rays deliberately model a player looking through a visible
     * pickup pool; they do not turn an occluded remembered break position into terrain knowledge.
     */
    private static boolean canObservePickupTransitEnvelope(AIPlayerEntity bot, BlockPos cell) {
        return SwimRoute.canObserveColumn(bot, cell)
                && ObservableWorldQuery.canObserveCellThroughFluids(bot, cell.below());
    }

    /** Re-proves the exact one-cell pickup transit before WalkedStep performs another terrain read. */
    private static boolean canContinueObservedPickupStep(AIPlayerEntity bot, WalkedStep step,
                                                          PickupStepAdmission admission) {
        return step.kind() == admission.kind()
                && step.cell().equals(admission.destination())
                && withinPickupStepContinuationEnvelope(bot.blockPosition(), admission, step.ticks())
                && canObservePickupTransitEnvelope(bot, admission.destination())
                && SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, admission.destination(), step.kind());
    }

    /** No-world-read envelope matching WalkedStep's one-tick source-settling normalization. */
    private static boolean withinPickupStepContinuationEnvelope(BlockPos feet, PickupStepAdmission admission,
                                                                int activeStepTicks) {
        BlockPos origin = admission.origin();
        BlockPos destination = admission.destination();
        if (between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getY(), origin.getY(), destination.getY())
                && between(feet.getZ(), origin.getZ(), destination.getZ())) {
            return true;
        }
        return (admission.kind() == WalkedStep.Kind.FLAT
                || admission.kind() == WalkedStep.Kind.STEP_UP
                || admission.kind() == WalkedStep.Kind.STEP_DOWN)
                && activeStepTicks >= 0 && activeStepTicks <= 1
                && feet.getX() == origin.getX()
                && feet.getZ() == origin.getZ()
                && Math.abs(feet.getY() - origin.getY()) == 1;
    }

    private static boolean between(int value, int first, int second) {
        return value >= Math.min(first, second) && value <= Math.max(first, second);
    }

    /** Reconciles only this task's guarded collision move; a successor controller is left alone. */
    private boolean awaitPickupStep(AIPlayerEntity bot) {
        ActionPack.StepLease lease = pickupStepLease;
        if (lease == null) {
            return false;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.stepInFlightFor(lease)) {
            return true;
        }
        if (!pack.stepIdle()) {
            pickupStepLease = null;
            return true;
        }
        WalkedStep.Result result = pack.stepResultFor(lease);
        pack.releaseStepLease(lease);
        pickupStepLease = null;
        if (result != null && result.failed()) {
            BotLog.action(bot, "create_obsidian_pickup_step_refused", "reason", result.reason());
        }
        return false;
    }

    /** Cancels/releases only this task's strict pickup move before an abort hands control away. */
    private void cancelPickupStep(AIPlayerEntity bot) {
        if (pickupStepLease != null) {
            bot.getActionPack().cancelStep(pickupStepLease);
            pickupStepLease = null;
        }
    }

    private BlockMiner.Status beginMine(AIPlayerEntity bot, BlockPos pos) {
        bot.getActionPack().stopMovement();
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    private void finish(AIPlayerEntity bot) {
        miner.cancel(bot);
        HarvestCore.sweepPickupAnyOf(bot, Set.of(Items.OBSIDIAN), 16);
        if (pickupGrace++ >= PICKUP_GRACE_TICKS
                || HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN)) - invBaseline >= targetCount) {
            enter(Phase.DONE);
            complete();
        }
    }

    /** Returns the first crossed, unserviced eight-block boundary below the final target. */
    static int nextServiceBoundary(int serviced, int collected, int target) {
        int safeServiced = Math.max(0, serviced);
        int safeCollected = Math.max(0, collected);
        int safeTarget = Math.max(1, target);
        if (safeCollected >= safeTarget) {
            return 0;
        }
        for (long boundary = SERVICE_INTERVAL; boundary < safeTarget; boundary += SERVICE_INTERVAL) {
            if (boundary > safeServiced && boundary <= safeCollected) {
                return (int) boundary;
            }
        }
        return 0;
    }

    /**
     * A service yield is legal only after the complete per-block transaction has returned to the
     * dry search face. SCAN plus an empty ledger is deliberately stricter than merely owning the
     * drop: it excludes live water, pickup, active-break and rim-return debts.
     */
    private boolean canYieldForService(AIPlayerEntity bot) {
        BlockPos current = bot.blockPosition();
        ServerLevel world = bot.level();
        return phase == Phase.SCAN
                && waterTarget == null
                && waterSource == null
                && lavaClue == null
                && lavaTarget == null
                && obsidian == null
                && pickupPos == null
                && pickupLastSeenPos == null
                && returnRim == null
                && standPos == null
                && obsidianStandHint == null
                && pourPlan == null
                && activeBreakPos == null
                && waterBucketBaseline < 0
                && pickupInventoryBaseline < 0
                && pickupGainTick < 0
                && activeBreakInventoryBaseline < 0
                && !protectionPrepared
                && miner.isDone()
                && !bot.getActionPack().hasActiveActions()
                && world.getFluidState(current).isEmpty()
                && world.getFluidState(current.above()).isEmpty()
                && Standability.isStandable(world, current);
    }

    private void requestServiceBoundary(AIPlayerEntity bot, int boundary) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        pendingServiceBoundary = boundary;
        enter(Phase.SERVICE_BOUNDARY);
        BotLog.task(bot, "create_obsidian_service_boundary",
                "boundary", boundary,
                "collected", collected + "/" + targetCount,
                "budget", totalBudget());
        complete();
    }

    /**
     * Commits the service acknowledgement into the dedicated obsidian transaction namespace.
     * Reapplying it to an already acknowledged checkpoint is intentionally idempotent.
     */
    public static Optional<Map<String, String>> acknowledgeServiceBoundary(
            Map<String, String> checkpoint) {
        Optional<RestoreMetadata> metadata = inspectCheckpoint(checkpoint);
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        int target = metadata.orElseThrow().targetCount();
        Optional<ObsidianCheckpoint> decoded = ObsidianCheckpoint.decode(
                checkpoint, target, maxElapsedForTarget(target));
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        ObsidianCheckpoint value = decoded.orElseThrow();
        if (value.pendingServiceBoundary() == 0) {
            return Optional.of(value.encode());
        }
        if (value.phase() != Phase.SERVICE_BOUNDARY) {
            return Optional.empty();
        }
        return Optional.of(value.acknowledgeService().encode());
    }

    private void restoreCheckpoint(ObsidianCheckpoint checkpoint) {
        phase = checkpoint.phase();
        searchCursor = checkpoint.searchCursor();
        scanResumeFace = checkpoint.scanResumeFace();
        invBaseline = checkpoint.inventoryBaseline();
        collected = checkpoint.collected();
        budgetOffset = checkpoint.budgetUsed();
        phaseStartedTick = checkpoint.phaseStartedBudget();
        lastProgressTick = checkpoint.lastProgressBudget();
        pickupGrace = checkpoint.pickupGrace();
        waterTarget = checkpoint.waterTarget();
        waterSource = checkpoint.waterSource();
        lavaClue = checkpoint.lavaClue();
        lavaTarget = checkpoint.lavaTarget();
        obsidian = checkpoint.obsidian();
        pickupPos = checkpoint.pickupPos();
        pickupLastSeenPos = checkpoint.pickupLastSeenPos();
        pickupInventoryBaseline = checkpoint.pickupInventoryBaseline();
        pickupGainTick = checkpoint.pickupGainBudget();
        returnRim = checkpoint.returnRim();
        obsidianStandHint = checkpoint.obsidianStandHint();
        standPos = checkpoint.standPos();
        pourPlan = checkpoint.pourPlan();
        waterBucketBaseline = checkpoint.waterBucketBaseline();
        protectionPrepared = checkpoint.protectionPrepared();
        activeBreakPos = checkpoint.activeBreakPos();
        activeBreakInventoryBaseline = checkpoint.activeBreakInventoryBaseline();
        servicedCollected = checkpoint.servicedCollected();
        pendingServiceBoundary = checkpoint.pendingServiceBoundary();
        auditSessionToken = checkpoint.auditSessionToken();
    }

    @Override
    public Map<String, String> checkpoint() {
        // An invalid restore has no trustworthy successor state. Returning an invented origin/zero
        // baseline here would overwrite the only forensic copy of the rejected checkpoint.
        if (invalidCheckpoint) {
            return Map.of();
        }
        ObsidianSearchCursor cursor = searchCursor == null
                ? (restoredCheckpoint == null
                ? ObsidianSearchCursor.initial(BlockPos.ZERO, SEARCH_BASE_LEG)
                : restoredCheckpoint.searchCursor())
                : searchCursor;
        int produced = Math.max(cursor.produced(), Math.max(0, collected));
        // AbstractTask increments elapsed before onTick. Keep terminal evidence inside the codec's
        // closed budget domain even if a caller captures an older MAX+1 task, and clamp every
        // timestamp that is validated relative to that durable budget.
        int durableBudget = Math.min(Math.max(0, totalBudget()), maxElapsed);
        int durablePhaseStarted = Math.min(Math.max(0, phaseStartedTick), durableBudget);
        int durableLastProgress = Math.min(Math.max(0, lastProgressTick), durableBudget);
        int durablePickupGain = pickupGainTick < 0
                ? -1 : Math.min(pickupGainTick, durableBudget);
        ObsidianCheckpoint live = new ObsidianCheckpoint(
                targetCount,
                phase,
                cursor.withProduced(produced),
                scanResumeFace == null ? cursor.face() : scanResumeFace,
                Math.max(0, invBaseline),
                Math.max(0, collected),
                Math.max(0, servicedCollected),
                Math.max(0, pendingServiceBoundary),
                durableBudget,
                durablePhaseStarted,
                durableLastProgress,
                pickupGrace,
                waterTarget,
                waterSource,
                lavaClue,
                lavaTarget,
                obsidian,
                pickupPos,
                pickupLastSeenPos,
                pickupInventoryBaseline,
                durablePickupGain,
                returnRim,
                obsidianStandHint,
                standPos,
                pourPlan,
                waterBucketBaseline,
                protectionPrepared,
                activeBreakPos,
                activeBreakInventoryBaseline,
                auditSessionToken);
        Map<String, String> encoded = live.encode();
        return ObsidianCheckpoint.decode(encoded, targetCount, maxElapsed).isPresent()
                ? encoded : Map.of();
    }

    static boolean needsMorePools(int target, int collected) {
        return Math.max(0, collected) < Math.max(1, target);
    }

    private void noteTopologyProgress() {
        lastProgressTick = totalBudget();
        if (searchCursor != null) {
            searchCursor = searchCursor.topologyChanged();
        }
    }

    private int topologyEpoch() {
        return searchCursor == null ? 0 : searchCursor.topologyEpoch();
    }

    private void rejectLava(BlockPos pos) {
        rejectedLava.reject(pos, topologyEpoch(), totalBudget(), REJECT_TTL);
        lastScannedFace = null;
    }

    private boolean lavaRejected(BlockPos pos) {
        return rejectedLava.isRejected(pos, topologyEpoch(), totalBudget());
    }

    private void rejectObsidian(BlockPos pos) {
        rejectedObsidian.reject(pos, topologyEpoch(), totalBudget(), REJECT_TTL);
        lastScannedFace = null;
    }

    private boolean obsidianRejected(BlockPos pos) {
        return rejectedObsidian.isRejected(pos, topologyEpoch(), totalBudget());
    }

    private void resetForNextScan(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        releaseRecoveryWalk(bot);
        if (waterSource != null) {
            // A placed source is a transactional resource obligation. Do not forget its position
            // merely because the target/pose became invalid; recover the real bucket first.
            if (obsidian == null && activeBreakPos != null) {
                obsidian = activeBreakPos;
            }
            BotLog.action(bot, "create_obsidian_recover_before_reset",
                    "water", waterSource.toShortString(),
                    "phase", phase,
                    "obsidian", obsidian == null ? "none" : obsidian.toShortString());
            enter(Phase.RECOVER_WATER);
            return;
        }
        if (waterBucketBaseline >= 0
                && InventoryAction.countItem(bot, Items.WATER_BUCKET) < waterBucketBaseline) {
            fail("create_obsidian_water_source_lost_before_reset");
            return;
        }
        clearWorkTargets();
        enterScan(bot);
    }

    private void clearWorkTargets() {
        clearRecoveryWalk();
        waterTarget = null;
        waterSource = null;
        lavaClue = null;
        lavaTarget = null;
        obsidian = null;
        pickupPos = null;
        pickupLastSeenPos = null;
        pickupInventoryBaseline = -1;
        pickupGainTick = -1;
        waterBucketBaseline = -1;
        returnRim = null;
        obsidianStandHint = null;
        standPos = null;
        pourPlan = null;
        protectionPrepared = false;
        clearActiveBreak();
        rejectedPourDestinations.clear();
    }

    /** Releases or cancels only this task's guarded recovery lease before dropping its state. */
    private void releaseRecoveryWalk(AIPlayerEntity bot) {
        ActionPack.StepLease lease = recoveryWalkLease;
        if (lease != null) {
            ActionPack pack = bot.getActionPack();
            if (pack.stepInFlightFor(lease)) {
                pack.cancelStep(lease);
            } else {
                pack.releaseStepLease(lease);
            }
        }
        clearRecoveryWalk();
    }

    /** Clears only the remembered admission after it has been released or before task start. */
    private void clearRecoveryWalk() {
        recoveryWalk = null;
        recoveryWalkLease = null;
    }

    private void pathToWorkPose(AIPlayerEntity bot, BlockPos target, String failure) {
        if (target == null) {
            fail(failure + ":no_stand");
            return;
        }
        double blockDistance = bot.blockPosition().distSqr(target);
        // WalkToController intentionally solves horizontal steering only. Sending it a stand one
        // block below makes it report arrival while the bot remains balanced on the upper ledge,
        // producing an endless walk_complete loop. Vertical final steps stay with the path
        // executor, which can actually step/jump between levels.
        if (bot.blockPosition().getY() == target.getY()
                && blockDistance > 0.0D && blockDistance <= 2.25D) {
            if (bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(Vec3.atBottomCenterOf(target));
            }
            return;
        }
        if (!bot.getActionPack().isWalkToIdle()) {
            return;
        }
        if (elapsed - lastPathAttemptTick >= 20 && bot.getActionPack().isPathExecutorIdle()) {
            lastPathAttemptTick = elapsed;
            ActionResult path = bot.getActionPack().startPathTo(target);
            if (path.isFailed()) {
                // The executor intentionally throttles rapid replans. Phase-specific approach
                // deadlines distinguish a genuine unreachable pose from this transient response.
                BotLog.action(bot, "create_obsidian_path_retry",
                        "phase", phase, "reason", path.reason(), "target", target.toShortString());
            }
        }
    }

    /** Pickup-only pathing never accepts a snapped endpoint that resolves back to the current cell. */
    private void pathToPickupPose(AIPlayerEntity bot, BlockPos target, String failure) {
        if (target == null) {
            // A just-mined ItemEntity can still be airborne.  Keep the physical pickup ledger
            // pending until it settles; the phase deadline remains the fail-closed boundary.
            BotLog.action(bot, "create_obsidian_pickup_no_progress_endpoint",
                    "reason", "no_stand", "failure", failure);
            return;
        }
        BlockPos current = bot.blockPosition();
        if (current.equals(target) || SwimRoute.observedCell(bot, bot.level(), target, false)
                != SwimRoute.Cell.DRY) {
            BotLog.action(bot, "create_obsidian_pickup_no_progress_endpoint",
                    "reason", current.equals(target) ? "current_cell" : "unobserved_or_non_standable",
                    "target", target.toShortString());
            return;
        }
        if (!bot.getActionPack().isWalkToIdle()) {
            bot.getActionPack().stopAll();
        }
        if (elapsed - lastPathAttemptTick < 20 || !bot.getActionPack().isPathExecutorIdle()) {
            return;
        }
        lastPathAttemptTick = elapsed;
        ActionResult path = bot.getActionPack().startSurfacePathTo(target);
        if (path.isFailed()) {
            BotLog.action(bot, "create_obsidian_path_retry",
                    "phase", phase, "reason", path.reason(), "target", target.toShortString());
            return;
        }
        BlockPos resolved = bot.getActionPack().activePathGoal();
        if (resolved == null || resolved.equals(current)) {
            bot.getActionPack().stopAll();
            BotLog.action(bot, "create_obsidian_pickup_no_progress_endpoint",
                    "reason", resolved == null ? "missing_resolved_goal" : "snapped_to_current",
                    "target", target.toShortString());
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseStartedTick = totalBudget();
    }

    private void enterScan(AIPlayerEntity bot) {
        scanResumeFace = bot.blockPosition().immutable();
        enter(Phase.SCAN);
    }

    /**
     * Lets the global safety layer distinguish controlled pool work from accidental exposure.
     * This domain task owns nearby-lava geometry through pickup/return; actual contact, fire or
     * low health are never owned and remain eligible for immediate safety preemption.
     */
    boolean controlsNearbyLava(AIPlayerEntity bot, BlockPos observedLava) {
        if (observedLava == null
                || phase == Phase.DONE
                || phase == Phase.SERVICE_BOUNDARY
                || phase == Phase.FIND_WATER
                || bot.isInLava()
                || bot.isOnFire()
                || bot.getHealth() < 10.0F) {
            return false;
        }
        BlockPos current = bot.blockPosition();
        return !bot.level().getFluidState(current).is(FluidTags.LAVA)
                && !bot.level().getFluidState(current.above()).is(FluidTags.LAVA);
    }

    private int phaseAge() {
        return totalBudget() - phaseStartedTick;
    }

    private int totalBudget() {
        return budgetOffset + elapsed;
    }

    private String timeoutReason() {
        return "create_obsidian_timeout collected=" + collected;
    }

    private static boolean atWorkPose(AIPlayerEntity bot,
                                      BlockPos expectedStand,
                                      BlockPos target,
                                      PourPlan plan,
                                      boolean avoidLava) {
        if (expectedStand == null || bot.blockPosition().distSqr(expectedStand) > 1.0D) {
            return false;
        }
        if (!Standability.isStandable(bot.level(), bot.blockPosition())
                || !bot.level().getFluidState(bot.blockPosition()).isEmpty()) {
            return false;
        }
        if (avoidLava && hasObservableAdjacentLava(bot, bot.blockPosition())) {
            return false;
        }
        if (!isDirectlyUsable(bot, target, true)) {
            return false;
        }
        return plan == null || isPourPlanUsable(bot, plan);
    }

    private static boolean atPourPose(AIPlayerEntity bot,
                                      BlockPos expectedStand,
                                      PourPlan plan,
                                      boolean avoidLava) {
        if (expectedStand == null || plan == null
                || bot.blockPosition().distSqr(expectedStand) > 1.0D
                || !Standability.isStandable(bot.level(), bot.blockPosition())
                || !bot.level().getFluidState(bot.blockPosition()).isEmpty()) {
            return false;
        }
        if (avoidLava && hasObservableAdjacentLava(bot, bot.blockPosition())) {
            return false;
        }
        Vec3 face = plan.support().getCenter().add(
                plan.face().getStepX() * 0.5D,
                plan.face().getStepY() * 0.5D,
                plan.face().getStepZ() * 0.5D);
        return bot.getEyePosition().distanceToSqr(face) <= REACH_MARGIN_SQUARED
                && isPourPlanUsable(bot, plan);
    }

    private static boolean atKnownProtectedMinePose(AIPlayerEntity bot,
                                                    BlockPos expectedStand,
                                                    BlockPos target) {
        if (expectedStand == null || target == null
                || bot.blockPosition().distSqr(expectedStand) > 1.0D
                || !Standability.isStandable(bot.level(), bot.blockPosition())
                || !bot.level().getFluidState(bot.blockPosition()).isEmpty()
                || hasObservableAdjacentLava(bot, bot.blockPosition())) {
            return false;
        }
        return bot.getEyePosition().distanceToSqr(target.getCenter()) <= REACH_MARGIN_SQUARED
                && bot.isWithinBlockInteractionRange(target, 0.0D);
    }

    private static boolean isCurrentDirectMinePose(AIPlayerEntity bot, BlockPos target) {
        BlockPos current = bot.blockPosition();
        return Standability.isStandable(bot.level(), current)
                && bot.level().getFluidState(current).isEmpty()
                && !hasObservableAdjacentLava(bot, current)
                && isDirectlyUsable(bot, target, true);
    }

    private static boolean isAdjacentMicroStep(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        return dx <= 1 && dy <= 1 && dz <= 1 && changedAxes >= 1 && changedAxes <= 2;
    }

    private static boolean isDirectlyUsable(AIPlayerEntity bot, BlockPos target, boolean cell) {
        if (target == null) {
            return false;
        }
        boolean observable = cell
                ? ObservableWorldQuery.canObserveCell(bot, target)
                : ObservableWorldQuery.canObserveBlock(bot, target);
        return observable
                && bot.getEyePosition().distanceToSqr(target.getCenter()) <= REACH_MARGIN_SQUARED
                && bot.isWithinBlockInteractionRange(target, 0.0D);
    }

    private static BlockPos findWorkStand(AIPlayerEntity bot,
                                          BlockPos target,
                                          PourPlan plan,
                                          boolean avoidLava) {
        ServerLevel world = bot.level();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 3; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int horizontalSquared = dx * dx + dz * dz;
                    BlockPos candidate = target.offset(dx, dy, dz);
                    // A dry ledge above the pool is safe even when horizontally adjacent. The
                    // two-block ring is only required at/below the water source level.
                    boolean elevatedPourStand = plan != null
                            && candidate.getY() > plan.destination().getY();
                    int minimumHorizontalSquared = avoidLava && plan != null && !elevatedPourStand
                            ? 4 : 1;
                    if (horizontalSquared < minimumHorizontalSquared || horizontalSquared > 16) {
                        continue;
                    }
                    if (!observableStandCell(bot, candidate)
                            || !Standability.isStandable(world, candidate)
                            || !world.getFluidState(candidate).isEmpty()
                            || !world.getFluidState(candidate.above()).isEmpty()) {
                        continue;
                    }
                    if (avoidLava && hasObservableAdjacentLava(bot, candidate)) {
                        continue;
                    }
                    Vec3 eye = Vec3.atBottomCenterOf(candidate).add(0.0D, 1.62D, 0.0D);
                    if (eye.distanceToSqr(target.getCenter()) > REACH_MARGIN_SQUARED) {
                        continue;
                    }
                    if (plan != null) {
                        Vec3 supportFace = plan.support().getCenter().add(
                                plan.face().getStepX() * 0.5D,
                                plan.face().getStepY() * 0.5D,
                                plan.face().getStepZ() * 0.5D);
                        int waterDx = candidate.getX() - plan.destination().getX();
                        int waterDz = candidate.getZ() - plan.destination().getZ();
                        boolean sourceCanOccupyFeet = candidate.getY() <= plan.destination().getY()
                                && waterDx * waterDx + waterDz * waterDz <= 2;
                        if (eye.distanceToSqr(supportFace) > REACH_MARGIN_SQUARED
                                || sourceCanOccupyFeet) {
                            continue;
                        }
                    }
                    double distance = bot.blockPosition().distSqr(candidate);
                    if (avoidLava && plan == null) {
                        // Mining from maximum reach strands the physical drop four blocks away in
                        // strict mode. Prefer the nearest safe work ring, then minimize travel.
                        distance += candidate.distSqr(target) * 1000.0D;
                    }
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean observableStandCell(AIPlayerEntity bot, BlockPos candidate) {
        return ObservableWorldQuery.canObserveCell(bot, candidate)
                && ObservableWorldQuery.canObserveCell(bot, candidate.above())
                && ObservableWorldQuery.canObserveBlock(bot, candidate.below());
    }

    private static boolean isSafeStand(AIPlayerEntity bot, BlockPos candidate, boolean avoidLava) {
        if (candidate == null
                || !observableStandCell(bot, candidate)
                || !Standability.isStandable(bot.level(), candidate)
                || !bot.level().getFluidState(candidate).isEmpty()
                || !bot.level().getFluidState(candidate.above()).isEmpty()) {
            return false;
        }
        return !avoidLava || !hasObservableAdjacentLava(bot, candidate);
    }

    private static PourPlan findPourPlan(AIPlayerEntity bot,
                                         BlockPos lava,
                                         Set<BlockPos> rejectedDestinations) {
        // For an already formed obsidian cell, the safest vanilla pattern is to place the source
        // directly on its top face. When the block breaks, water immediately occupies the opened
        // cell and protects both the drop and any newly exposed lava below. A lava target naturally
        // fails this candidate because fluid is not a solid clickable support.
        PourPlan topProtection = new PourPlan(lava, Direction.UP);
        if (!rejectedDestinations.contains(topProtection.destination())
                && isPourPlanUsable(bot, topProtection)) {
            return topProtection;
        }

        // Preferred geometry: source directly above lava, supported by an exposed wall face.
        BlockPos direct = lava.above();
        if (!rejectedDestinations.contains(direct) && canReceiveWater(bot, direct)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos support = direct.relative(direction);
                Direction face = direction.getOpposite();
                PourPlan plan = new PourPlan(support, face);
                if (isPourPlanUsable(bot, plan)) {
                    return plan;
                }
            }
        }

        // Open pool geometry: place the source on top of a solid rim block adjacent to the lava.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos support = lava.relative(direction);
            PourPlan plan = new PourPlan(support, Direction.UP);
            if (!rejectedDestinations.contains(plan.destination())
                    && isPourPlanUsable(bot, plan)) {
                return plan;
            }
        }

        // Flat pool geometry: pour on a dry floor tile and let vanilla water flow into the source.
        for (int distance = 1; distance <= 4; distance++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos support = lava.relative(direction, distance).below();
                PourPlan plan = new PourPlan(support, Direction.UP);
                if (!rejectedDestinations.contains(plan.destination())
                        && isPourPlanUsable(bot, plan)) {
                    return plan;
                }
            }
        }
        return null;
    }

    private static boolean isPourPlanUsable(AIPlayerEntity bot, PourPlan plan) {
        if (plan == null || !ObservableWorldQuery.canObserveCell(bot, plan.destination())) {
            return false;
        }
        if (!ObservableWorldQuery.canObserveBlock(bot, plan.support())) {
            return false;
        }
        BlockState support = bot.level().getBlockState(plan.support());
        return !support.isAir()
                && support.getFluidState().isEmpty()
                && !support.getCollisionShape(bot.level(), plan.support()).isEmpty()
                && canReceiveWater(bot, plan.destination());
    }

    private static boolean canReceiveWater(AIPlayerEntity bot, BlockPos destination) {
        if (!ObservableWorldQuery.canObserveCell(bot, destination)) {
            return false;
        }
        BlockState state = bot.level().getBlockState(destination);
        return state.getFluidState().isEmpty()
                && (state.isAir() || state.canBeReplaced(Fluids.WATER));
    }

    private static boolean hasObservableAdjacentLava(AIPlayerEntity bot, BlockPos pos) {
        ServerLevel world = bot.level();
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);
            if (ObservableWorldQuery.canObserveCell(bot, adjacent)
                    && world.getFluidState(adjacent).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasObservableAdjacentFluid(AIPlayerEntity bot, BlockPos pos) {
        ServerLevel world = bot.level();
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);
            if (ObservableWorldQuery.canObserveCell(bot, adjacent)
                    && !world.getFluidState(adjacent).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasObservableNearbyLava(AIPlayerEntity bot, BlockPos pos, int radius) {
        ServerLevel world = bot.level();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = pos.offset(dx, dy, dz);
                    if (ObservableWorldQuery.canObserveCell(bot, candidate)
                            && world.getFluidState(candidate).is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Captures exact still-lava facts before a real water placement mutates the world. Only cells
     * within vanilla water's bounded horizontal spread and the bot's strict line of sight are
     * armed; observing arbitrary obsidian after the pour cannot create provenance retroactively.
     */
    private static Set<BlockPos> observableLavaForWaterPlacement(AIPlayerEntity bot,
                                                                  BlockPos waterDestination,
                                                                  BlockPos requiredLava) {
        ServerLevel world = bot.level();
        Set<BlockPos> result = new LinkedHashSet<>();
        if (waterDestination == null || !canPrePlacementWaterOccupy(bot, waterDestination)) {
            return Set.of();
        }
        record WaterReach(BlockPos pos, int horizontalSteps, int drops) {
        }
        ArrayDeque<WaterReach> frontier = new ArrayDeque<>();
        Map<BlockPos, Integer> bestCost = new LinkedHashMap<>();
        BlockPos origin = waterDestination.immutable();
        frontier.add(new WaterReach(origin, 0, 0));
        bestCost.put(origin, 0);
        while (!frontier.isEmpty()) {
            WaterReach reached = frontier.removeFirst();
            BlockPos cell = reached.pos();
            for (Direction direction : Direction.values()) {
                BlockPos touching = cell.relative(direction);
                if (ObservableWorldQuery.canObserveCell(bot, touching)
                        && isStillLava(world, touching)) {
                    result.add(touching.immutable());
                }
            }

            BlockPos below = cell.below();
            boolean falls = reached.drops() < 1
                    && canPrePlacementWaterOccupy(bot, below);
            if (falls) {
                int encodedCost = reached.horizontalSteps() + WATER_FLOW_REACH + 1;
                if (bestCost.getOrDefault(below, Integer.MAX_VALUE) > encodedCost) {
                    bestCost.put(below.immutable(), encodedCost);
                    frontier.addLast(new WaterReach(
                            below.immutable(), reached.horizontalSteps(), reached.drops() + 1));
                }
                // Vanilla prioritizes a downward opening. Conservatively do not authorize cells
                // beyond that hole as if the same sheet also crossed it horizontally.
                continue;
            }
            if (reached.horizontalSteps() >= WATER_FLOW_REACH) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(direction);
                int nextCost = reached.horizontalSteps() + 1;
                if (!canPrePlacementWaterOccupy(bot, next)
                        || bestCost.getOrDefault(next, Integer.MAX_VALUE) <= nextCost) {
                    continue;
                }
                bestCost.put(next.immutable(), nextCost);
                frontier.addLast(new WaterReach(next.immutable(), nextCost, reached.drops()));
            }
        }
        // The production target is not a privileged exception. If the pre-placement observable
        // flow graph cannot touch it, WAIT_FORM will fail its exact audit gate even if unrelated
        // water happens to change that coordinate during the same time window.
        if (requiredLava != null && !result.contains(requiredLava)) {
            BotLog.action(bot, "create_obsidian_audit_target_not_water_reachable",
                    "water", waterDestination.toShortString(),
                    "lava", requiredLava.toShortString());
        }
        return Set.copyOf(result);
    }

    private static boolean canPrePlacementWaterOccupy(AIPlayerEntity bot, BlockPos pos) {
        if (!ObservableWorldQuery.canObserveCell(bot, pos)) {
            return false;
        }
        BlockState state = bot.level().getBlockState(pos);
        return state.getFluidState().is(FluidTags.WATER)
                || state.getFluidState().isEmpty()
                && (state.isAir() || state.canBeReplaced(Fluids.WATER));
    }

    private static boolean isStillLava(ServerLevel world, BlockPos pos) {
        var fluid = world.getFluidState(pos);
        return fluid.is(FluidTags.LAVA) && fluid.isSource();
    }

    private static boolean isStillWater(ServerLevel world, BlockPos pos) {
        var fluid = world.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && fluid.isSource();
    }

    /**
     * Strict-survival fluid scan. Fluids do not expose a stable solid collision face, so a
     * block-face query can miss a lava or water surface even at the pool rim. Every candidate is
     * first gated by an unobstructed cell ray; its fluid state is read only after that visibility
     * boundary succeeds.
     */
    private static BlockPos nearestObservableFluid(AIPlayerEntity bot,
                                                   Predicate<net.minecraft.world.level.material.FluidState> match,
                                                   Predicate<BlockPos> posFilter) {
        return nearestObservableCell(bot, state -> match.test(state.getFluidState()), posFilter);
    }

    private static BlockPos nearestObservableCell(AIPlayerEntity bot,
                                                  Predicate<BlockState> match,
                                                  Predicate<BlockPos> posFilter) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        int range = Math.min(PROSPECT_RANGE, Math.max(1, MinecraftAiConfig.get().perception().radius()));
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
                            || (posFilter != null && !posFilter.test(candidate))
                            || !ObservableWorldQuery.canObserveCell(bot, candidate)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(candidate);
                    if (match.test(state)) {
                        bestDistance = distance;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }
}
