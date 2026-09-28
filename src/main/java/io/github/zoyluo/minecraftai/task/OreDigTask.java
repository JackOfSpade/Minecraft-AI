package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.action.WalkToController;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.mining.MiningMissionBudget;
import io.github.zoyluo.minecraftai.mining.MiningEvidenceAudit;
import io.github.zoyluo.minecraftai.mining.OreProspector;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mining.assist.CoverageGrid;
import io.github.zoyluo.minecraftai.mining.assist.DetourControl;
import io.github.zoyluo.minecraftai.mining.assist.DetourPhase;
import io.github.zoyluo.minecraftai.mining.assist.DetourPolicy;
import io.github.zoyluo.minecraftai.mining.assist.HazardField;
import io.github.zoyluo.minecraftai.mining.assist.InventoryHeadroom;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistLog;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRegistry;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.MissionAssistLedger;
import io.github.zoyluo.minecraftai.mining.assist.ObservedGraphSearch;
import io.github.zoyluo.minecraftai.mining.assist.ObservedOccupancy;
import io.github.zoyluo.minecraftai.mining.assist.ObservedReach;
import io.github.zoyluo.minecraftai.mining.assist.OreClaims;
import io.github.zoyluo.minecraftai.mining.assist.FrontierPlanner;
import io.github.zoyluo.minecraftai.mining.assist.RouteBudget;
import io.github.zoyluo.minecraftai.mining.assist.SafeGate;
import io.github.zoyluo.minecraftai.mining.assist.SafeReason;
import io.github.zoyluo.minecraftai.mining.assist.SenseBudget;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger;
import io.github.zoyluo.minecraftai.mining.assist.SweepEngine;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.memory.EpisodeLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OREDIG (field-tested #10): reliable ore mining, replacing the OreSeekTask that GoalExecutor's
 * MINE_ORE step originally used.
 *
 * OreSeek's "scan -> A* approach -> corridor fallback" approach logic kept getting stuck
 * (#6/#8/#10) on ore wrapped in stone.
 * This task instead uses a mode already verified to **never deadlock**: **controlled direct-dig
 * tunneling + a shared {@link BlockMiner}**, never pathfinding/walking:
 *  - SCAN: use full server-side data to find the nearest target ore (rate-limited); if none is
 *    found, dig down one block to change layer and scan again;
 *  - DIG: each tick only mine "the next cell toward the ore" (horizontal or one down); BlockMiner
 *    drives the shaping block by block,
 *    and the bot naturally follows; once the ore enters reach -> mine it directly, and clear out
 *    adjacent same-vein ore along with it;
 *  - a no-progress watchdog runs throughout: if no block is broken before timeout, fail cleanly
 *    and hand off to GoalExecutor.
 *
 * Self-contained state machine (Iron Rule G1), no internal assign; runs entirely on the main thread (G2).
 */
public final class OreDigTask extends AbstractTask implements CheckpointableTask {
    private enum PickupEgressResult {
        CLEAR,
        WORKING,
        UNSAFE,
        TARGET_ORE
    }

    /** One blind-branch tick may either prove the body envelope clear or seal one exact cell. */
    private enum BranchFluidSealResult {
        CLEAR,
        SEALED,
        BLOCKED
    }

    /** The caller owns the safety semantics; targetOre being null is not an intent signal. */
    private enum TunnelIntent {
        BLIND_BRANCH,
        TARGET_APPROACH
    }

    /** Distinguishes an ordinary mineable wall from a one-block raised landing we must not mine. */
    private enum RaisedBoundaryLanding {
        NOT_APPLICABLE,
        UNSAFE,
        READY
    }

    /** Exact location, when present, for one tri-state adjacent fluid observation. */
    private record AdjacentFluidObservation(OreScan.Observation state,
                                            BlockPos position) {
        AdjacentFluidObservation {
            position = position == null ? null : position.toImmutable();
        }
    }

    private static final int CHECKPOINT_SCHEMA = 4;
    private static final int MISSION_CHECKPOINT_SCHEMA = 3;
    private static final int RESOURCE_EPOCH_CHECKPOINT_SCHEMA = 2;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 1;
    private static final int MAX_ELAPSED_BASE = MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS;
    private static final int MAX_CHECKPOINT_TARGET_COUNT = 4096;
    private static final int MAX_CURSOR_LEGS = 4096;
    private static final int STRIP_AFTER_SKIPS = 3;     // this many consecutive "locked ore out of reach, skipped" events -> force one strip-mine advance step (breaks an in-place deadlock)
    private static final int NO_PROGRESS_LIMIT = 200;   // 10s with no block broken -> fail
    private static final int RESTORE_FACE_LIMIT = 1200;
    // Closed batches carry geometry only: a farther handoff is a new physical work region.
    private static final long MAX_COMMITTED_CURSOR_HANDOFF_DISTANCE_SQUARED = 16L * 16L;
    private static final int SCAN_INTERVAL = 10;
    private static final int SCAN_RADIUS = 24;
    private static final int PROSPECT_RANGE = 64;       // prospecting (wide-range locate of the nearest ore) radius -- kicks in when nothing is found nearby
    private static final int PROSPECT_INTERVAL = 40;    // prospecting is relatively expensive (scans section by section), once every 2s
    private static final int VERTICAL_SCAN = 10;
    // 4.5^2: matches BlockMiner's own internal check (at 5.5, edge-of-range mining was rejected by
    // the miner -> FAILED -> ore wrongly blacklisted; geo_wall testing showed it got abandoned
    // within 2s of locking on). The premise behind the old 5.1 dead zone no longer holds -- the
    // approach target is now the cell directly under the ore, so pathfinding actually walks right
    // up against it.
    private static final double REACH_SQUARED = 20.25D;
    private static final int MIN_Y = -60;
    private static final int VEIN_CAP = 64;
    private static final int PICKUP_GRACE_TICKS = 30;
    private static final int TARGET_DROP_RECOVERY_LIMIT = 200;
    private static final int TARGET_DROP_LAST_SEEN_RANGE = 16;
    // Staying in place within the recovery window (a same-cell nudge or a silent pathfinding
    // failure both count) beyond this threshold -> escalate to an observation sweep: walk to an
    // observable stance near the last-seen position so an occluded or knocked-away drop can
    // re-enter view or its hitbox.
    private static final int PICKUP_STALL_SWEEP_TICKS = 30;
    private static final int[][] PICKUP_SWEEP_OFFSETS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2}
    };
    private static final int MIN_TARGET_BREAK_DY = -1;
    private static final int MAX_TARGET_BREAK_DY = 2;
    private static final int BONUS_CAP = 8;            // R3 opportunistic-ore per-task cap: free pickups are good, but not at the cost of the main job
    private static final int APPROACH_LIMIT = 80;       // P0: if locked ore still hasn't been approached after this many ticks -> judge it unreachable, give up and switch ore/dig down
    static final int STRIP_SEGMENT = 48;        // Coverage efficiency: the scan is an omniscient 24-block sphere, so a tunnel's value is the ground it covers by moving; a long straight segment reduces turning and overlapping scans
    private static final int HOSTILE_BARRICADE_RETREAT = 4;
    static final Direction[] STRIP_DIRS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final Set<Block> targetOres;
    private final Set<Item> targetDrops;
    private final int targetCount;
    private final BlockMiner miner = new BlockMiner();
    // Exclusions are folded into EpisodeMemory (working memory, goal-scoped lifecycle + TTL
    // revival): the old instance Set was lost after a replan and needed a one-time "amnesty" fix;
    // a short TTL exclusion (30s) is finer-grained semantics -- it naturally revives on expiry, no
    // amnesty needed.
    private final Deque<BlockPos> veinQueue = new ArrayDeque<>();
    /**
     * A finite observation ledger, not a cached safety verdict. Each value was a strictly visible,
     * standable side pose when its high ore key was observed. Later occlusion may hide that pose
     * while a lower member is mined or its physical drop is recovered; retaining the factual
     * coordinate lets the task walk back without digging through unknown terrain. Live observed
     * hazards and invalid standability still revoke the entry before any new route is issued.
     */
    private final Map<BlockPos, BlockPos> rememberedHighWorkPoses = new HashMap<>();

    private int invBaseline;
    private int collected;
    private int lastProgressBudget;
    private int lastScanTick = -SCAN_INTERVAL;
    private int lastProspectTick = -100;
    private int pickupGrace;
    private BlockPos targetOre;
    private double lastTargetDist = Double.MAX_VALUE; // P0: the historical closest squared distance to the locked ore (monitors whether we're actually approaching)
    private int targetApproachTick;
    private int stripDirIndex = -1;   // Optimization 1: current horizontal digging direction for finding ore at this layer (index into STRIP_DIRS), -1 = not started
    private int stripStepsLeft;       // Optimization 1: cells remaining in the current tunnel segment
    private int stripLegIndex;        // Which leg of the square spiral we're on; expands every two legs, avoiding returning to the origin every four legs
    private int stripLegLength = STRIP_SEGMENT;
    private BlockPos stripProgressPos;
    // P4 (design 5.3, off by default): L1 LegChooser's own coverage memory, mission-scoped like
    // veinQueue above -- never shared with MiningAssistState, never persisted.
    private final CoverageGrid stripCoverage = new CoverageGrid();
    // P4 (design 5.3): the LegChooser adapter that measures the world into LegChooser's four
    // signals. Constructed once targetOres is known (see the full constructor below).
    private final OreDigLegChooserAdapter legChooserAdapter;
    /**
     * Exact rear cell owned by the current committed one-block blind-strip edge, or the crossed
     * rear of a factual square-spiral corner until its successor first moves. This is a durable
     * branch identity, not a claim that no pause/rejoin movement happened later and not a cached
     * safety verdict: every recovery re-observes the body column and support before moving. It
     * survives both the multi-tick BlockMiner window and a checkpoint restart.
     */
    private BlockPos controlledStripRear;
    private BlockPos boundaryRerouteOrigin;
    private BlockPos cursorOrigin;
    private BlockPos lastFace;
    private int completedBatches;
    private int consecutiveSkips;     // Count of consecutive "locked ore out of reach, skipped" events (reset to zero on a successful mine); above the threshold, force a strip-mine advance to break the deadlock
    private BlockPos lastSkipPos;     // The bot's cell the last time ore was abandoned: repeatedly abandoning ore in place (unchanged position) does not feed the watchdog, letting it trip and break the thrash
    private final int maxElapsed;     // Hard timeout: scales with a large quota (a full suit of iron armor = 26 iron), a small quota uses the baseline
    private BlockPos bonusOre;        // R3 opportunistic ore: non-target ore within reach, mined in passing (single block, does not chase the vein)
    private int bonusMined;           // Opportunistic-mining budget counter (prevents it from overshadowing the main job)
    private int lastBonusScanTick = -100;
    private int lastReLockTick = -100;  // Rate limit for re-scanning mid-approach to switch to a closer ore
    private final MiningCursor restoredCursor;
    private final OreDigCheckpoint restoredCheckpoint;
    private final boolean invalidCheckpoint;
    private final int budgetTargetCount;
    private final int deliveredAtStart;
    private final int rareMissionTarget;
    private final boolean rareExpeditionBatch;
    /** Parent-goal policy, reconstructed from the durable mission on every process restore. */
    private final int protectedStoneLikeReserve;
    private final BlockPos restoredPendingPickupPos;
    private final BlockPos restoredPendingPickupLastSeenPos;
    private final int restoredPendingPickupInventory;
    private final BlockPos restoredActiveTargetBreakPos;
    private final int restoredActiveTargetBreakInventory;
    private final String oreFingerprint;
    private boolean restoringFace;
    private int restoreFaceStarted;
    private BlockPos pendingPickupPos;
    private BlockPos pendingPickupLastSeenPos;
    private int pendingPickupInventory;
    private int pendingPickupStarted = -1;
    private int pendingPickupGainTick = -1;
    // Transient recovery-stall state; deliberately not checkpointed. A restored ledger restarts
    // the stall clock, which only delays the first sweep escalation by one threshold window.
    private BlockPos pendingPickupStallAnchor;
    private int pendingPickupStallTicks;
    private int pendingPickupSweepCursor;
    // Bounded perception-recovery window for a delivered batch whose last open break is not
    // observable from the restore pose. Transient: a restart merely restarts the bounded walk.
    private int deliveredBatchObservationTicks;
    private BlockPos activeTargetBreakPos;
    private int activeTargetBreakInventory = -1;
    private int budgetOffset;
    private int torchPlacements;
    private int resourceEpoch;
    private boolean inventoryServiceUsed;
    private LavaReroute lavaReroute;
    private OreDigDetourEngine detour;
    private boolean detourInterruptedFlag;
    private BlockPos blockedBodyRecoveryTarget;
    private PendingBlindAdvance pendingBlindAdvance;
    private BlockPos rememberedHighWorkPoseRouteOwner;
    private int rememberedHighWorkPoseRouteStartedBudget = -1;

    /**
     * Runtime-only ownership for the one direct walker started by a blind branch. The action
     * controller itself is not checkpointed, so neither is this authorization: a process restart
     * conservatively restores the pre-move cursor face instead of trusting a vanished walker.
     */
    private record PendingBlindAdvance(BlockPos rear,
                                       BlockPos destination,
                                       Direction direction) {
        PendingBlindAdvance {
            rear = rear.toImmutable();
            destination = destination.toImmutable();
        }
    }

    private enum PendingBlindAdvancePhase {
        NONE,
        IN_FLIGHT_AT_REAR,
        RETRY_AT_REAR,
        ARRIVED
    }

    private record PendingBlindAdvanceInspection(PendingBlindAdvancePhase phase,
                                                 BlockPos rear) {
        PendingBlindAdvanceInspection {
            rear = rear == null ? null : rear.toImmutable();
        }
    }

    private record LavaReroute(BlockPos source,
                               BlockPos origin,
                               int direction,
                               int startedBudget,
                               long startingDistanceSquared) {
        LavaReroute {
            source = source.toImmutable();
            origin = origin.toImmutable();
        }
    }

    public OreDigTask(Set<Block> targetOres, int targetCount) {
        this(targetOres, targetCount, 0,
                MiningBudget.EMERGENCY_STONE_LIKE, Map.of());
    }

    public OreDigTask(Set<Block> targetOres, int targetCount, Map<String, String> checkpoint) {
        this(targetOres, targetCount, 0,
                MiningBudget.EMERGENCY_STONE_LIKE, checkpoint);
    }

    public OreDigTask(Set<Block> targetOres,
                      int targetCount,
                      int expectedRareMissionTarget,
                      Map<String, String> checkpoint) {
        this(targetOres, targetCount, expectedRareMissionTarget,
                MiningBudget.EMERGENCY_STONE_LIKE, checkpoint);
    }

    public OreDigTask(Set<Block> targetOres,
                      int targetCount,
                      int expectedRareMissionTarget,
                      int expectedFluidSealStoneLikeReserve,
                      Map<String, String> checkpoint) {
        this.targetOres = targetOres == null || targetOres.isEmpty()
                ? OreScan.COMMON_ORES
                : OreScan.expandOreFamilies(targetOres);
        this.legChooserAdapter = new OreDigLegChooserAdapter(this.targetOres, this.stripCoverage);
        this.targetDrops = HarvestCore.expectedDropsFor(this.targetOres);
        this.oreFingerprint = oreFingerprint(this.targetOres);
        if (expectedRareMissionTarget != 0
                && expectedRareMissionTarget < MiningBudget.EXPEDITION_THRESHOLD) {
            throw new IllegalArgumentException(
                    "invalid_rare_mission_target:" + expectedRareMissionTarget);
        }
        if (expectedFluidSealStoneLikeReserve < MiningBudget.EMERGENCY_STONE_LIKE) {
            throw new IllegalArgumentException("invalid_fluid_seal_stone_reserve:"
                    + expectedFluidSealStoneLikeReserve);
        }
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        this.restoredCheckpoint = OreDigCheckpoint.decode(
                values, this.targetOres, expectedRareMissionTarget).orElse(null);
        boolean incompatibleOpenStep = restoredCheckpoint != null
                && restoredCheckpoint.batchOpen()
                && targetCount != restoredCheckpoint.targetCount()
                && targetCount != (restoredCheckpoint.targetCount()
                        - restoredCheckpoint.delivered());
        this.invalidCheckpoint = !values.isEmpty()
                && (restoredCheckpoint == null || incompatibleOpenStep);
        this.restoredCursor = restoredCheckpoint == null ? null : restoredCheckpoint.cursor();
        this.restoredPendingPickupPos = restoredCheckpoint == null
                ? null : restoredCheckpoint.pendingPickupPos();
        this.restoredPendingPickupLastSeenPos = restoredCheckpoint == null
                ? null : restoredCheckpoint.pendingPickupLastSeenPos();
        this.restoredPendingPickupInventory = restoredCheckpoint == null
                ? -1 : restoredCheckpoint.pendingPickupInventory();
        this.restoredActiveTargetBreakPos = restoredCheckpoint == null
                ? null : restoredCheckpoint.activeBreakPos();
        this.restoredActiveTargetBreakInventory = restoredCheckpoint == null
                ? -1 : restoredCheckpoint.activeBreakInventory();
        this.budgetTargetCount = restoredCheckpoint != null && restoredCheckpoint.batchOpen()
                ? restoredCheckpoint.targetCount() : Math.max(1, targetCount);
        this.deliveredAtStart = restoredCheckpoint != null && restoredCheckpoint.batchOpen()
                ? restoredCheckpoint.delivered() : 0;
        // targetCount is the work still owed by this logical batch, not the original step label.
        // A process restart may replay the original GoalStep(8), while a factual replan emits only
        // the remainder (for example 4).  The durable delivered ledger is authoritative in both
        // cases and prevents either path from mining the already delivered output again.
        this.targetCount = Math.max(0, this.budgetTargetCount - this.deliveredAtStart);
        this.rareMissionTarget = restoredCheckpoint == null
                ? expectedRareMissionTarget : restoredCheckpoint.rareMissionTarget();
        this.rareExpeditionBatch = isRareExpeditionBatch(
                this.targetOres, this.rareMissionTarget);
        this.torchPlacements = restoredCheckpoint == null
                ? 0 : restoredCheckpoint.torchPlacements();
        this.resourceEpoch = restoredCheckpoint == null
                ? 0 : restoredCheckpoint.resourceEpoch();
        this.protectedStoneLikeReserve = protectedStoneLikeReserveForPolicy(
                expectedFluidSealStoneLikeReserve, rareExpeditionBatch, resourceEpoch);
        this.inventoryServiceUsed = restoredCheckpoint != null
                && restoredCheckpoint.inventoryServiceUsed();
        this.maxElapsed = maxElapsedForTarget(
                this.targetOres, this.budgetTargetCount,
                this.rareMissionTarget, this.resourceEpoch);
    }

    static int protectedStoneLikeReserveForPolicy(int parentMissionReserve,
                                                   boolean rareExpeditionBatch,
                                                   int resourceEpoch) {
        int rareEpochReserve = rareExpeditionBatch && resourceEpoch == 0
                ? MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE
                : MiningBudget.EMERGENCY_STONE_LIKE;
        return Math.max(parentMissionReserve, rareEpochReserve);
    }

    @Override
    public String name() {
        return "mine_ore";
    }

    @Override
    public String describe() {
        return "OreDig " + collected + "/" + targetCount
                + (restoringFace ? " (returning to saved face)" : "")
                + " branch=" + stripLegIndex + ":" + stripStepsLeft
                + (targetOre == null ? " (scanning)" : " ->" + targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ());
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (targetCount == 0) {
            return 0.95D;
        }
        return Math.min(0.95D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // During mining/digging-down the bot basically stands still and mines, treated as waiting
        // so StuckWatcher doesn't misjudge it (StuckWatcher was exactly the culprit behind #10's
        // repeated aborts); this task's own NO_PROGRESS_LIMIT watchdog handles deadlock protection
        // instead.
        return true;
    }

    // Thin wrapper over EpisodeMemory: excludes ore that is "unreachable/mined out" (TTL 30s auto-revival), goal-scoped lifecycle survives across replans.
    private void excludeOre(AIPlayerEntity bot, BlockPos pos) {
        EpisodeMemory.INSTANCE.exclude(bot.getUuid(), pos, bot.getEntityWorld().getServer().getTicks(), EpisodeMemory.TTL_SHORT);
        forgetRememberedHighWorkPose(pos);
    }

    private boolean oreExcluded(AIPlayerEntity bot, BlockPos pos) {
        return EpisodeMemory.INSTANCE.isExcluded(bot.getUuid(), pos, bot.getEntityWorld().getServer().getTicks());
    }

    /**
     * A visible lava source beside the active branch is a reroute signal, not a request to run an
     * underground EvadeTask into solid rock.  Physical target-break and pickup ledgers retain
     * safety ownership and therefore decline this fast path.
     */
    boolean avoidObservedLava(AIPlayerEntity bot, BlockPos lavaPos) {
        if (assistDetourActive()) {
            return detourClaimLava(bot, lavaPos);
        }
        if (lavaPos == null || bot.isInLava() || bot.isOnFire()
                || bot.getHealth() <= 8.0F || restoringFace
                || pendingPickupPos != null || activeTargetBreakPos != null
                || !veinQueue.isEmpty() || bonusOre != null
                || blockedBodyRecoveryTarget != null
                || hasImmediateLava(bot)) {
            return false;
        }
        BlockPos here = bot.getBlockPos();
        if (stripDirIndex < 0 || stripDirIndex >= STRIP_DIRS.length || targetOre != null) {
            return false;
        }
        // The valid steps-left=0 transition still belongs to OreDig: stripMine will publish the
        // next durable leg on its next tick. Likewise, once a prior/direct boundary decision has
        // turned the active branch away from a non-immediate visible source, assigning a generic
        // underground EvadeTask would discard that factual safe direction. Current-forward cells
        // retain their own water/lava preflight every task tick.
        if (stripStepsLeft <= 0
                || !isAheadOfDirection(here, lavaPos, STRIP_DIRS[stripDirIndex])) {
            return true;
        }
        if (lavaReroute != null && sameLavaCluster(lavaReroute.source(), lavaPos)) {
            long distance = squaredBlockDistance(here, lavaReroute.source());
            if (distance > lavaReroute.startingDistanceSquared()) {
                return true;
            }
            if (totalBudget() - lavaReroute.startedBudget() <= 40) {
                return true;
            }
            // The task consumed its bounded chance to leave this lava-facing branch without
            // increasing physical distance. Restore generic safety handling; do not refresh the
            // mining no-progress clock with another logical rotation.
            lavaReroute = null;
        }
        int rejectedDirection = stripDirIndex;
        int remainingSteps = stripStepsLeft;
        if (!rerouteBlindBranchAtObservedBoundary(
                bot, bot.getEntityWorld(), "lava", lavaPos)) {
            // The branch cannot leave this observed boundary without walking old territory or
            // opening an unproven body column. Keep ownership here and let the typed task failure
            // drive the normal mission replan instead of assigning an underground EvadeTask.
            return true;
        }
        lavaReroute = new LavaReroute(
                lavaPos, here, rejectedDirection, totalBudget(), squaredBlockDistance(here, lavaPos));
        bonusOre = null;
        BotLog.danger(bot, "ore_dig_lava_direction_rejected",
                "at", here.toShortString(),
                "lava", lavaPos.toShortString(),
                "direction", STRIP_DIRS[rejectedDirection].asString(),
                "reroute", STRIP_DIRS[stripDirIndex].asString(),
                "steps_left", remainingSteps,
                "escape_window", 40);
        return true;
    }

    /**
     * Converts a hostile-opened branch into a factual rear-and-gate recovery plan. The gate is
     * selected inside the already controlled one-wide tunnel, not at the irregular cave mouth, so
     * two vanilla placements can permanently separate the mission from the mobs. Physical target
     * break/pickup debts deliberately reject this shortcut and remain owned by the normal safety
     * fallback.
     */
    Optional<MiningBarricadeTask> prepareHostileBarricade(AIPlayerEntity bot, BlockPos hostilePos) {
        if (assistDetourActive()) {
            return Optional.empty();
        }
        if (stripDirIndex < 0 || stripDirIndex >= STRIP_DIRS.length
                || restoringFace || pendingPickupPos != null || activeTargetBreakPos != null) {
            BotLog.danger(bot, "ore_dig_hostile_barricade_rejected",
                    "reason", "state_not_safe",
                    "direction", stripDirIndex,
                    "restoring", restoringFace,
                    "pickup_debt", pendingPickupPos != null,
                    "break_debt", activeTargetBreakPos != null);
            return Optional.empty();
        }
        BlockPos here = bot.getBlockPos();
        // Once an ore approach has carried the bot away from the controlled strip face, direction
        // alone no longer proves which cells are the rear tunnel. Fall back to the general shelter.
        if (stripProgressPos == null || squaredBlockDistance(here, stripProgressPos) > 16L) {
            BotLog.danger(bot, "ore_dig_hostile_barricade_rejected",
                    "reason", "outside_controlled_face",
                    "at", here.toShortString(),
                    "face", stripProgressPos == null ? "missing" : stripProgressPos.toShortString());
            return Optional.empty();
        }
        Direction forward = STRIP_DIRS[stripDirIndex];
        // A normal factual corner publishes the perpendicular successor before it moves. Its
        // geometric reverse is fresh work, not the tunnel just crossed (north -> east owns south,
        // not west). Use only the exact persisted rear proof; a boundary reroute without one must
        // fall back to the general safety path instead of treating any standable cave as owned.
        Direction rear = forward.getOpposite();
        boolean factualCorner = boundaryRerouteOrigin != null;
        if (factualCorner) {
            if (!ownsFactualCornerRear(here, forward)) {
                BotLog.danger(bot, "ore_dig_hostile_barricade_rejected",
                        "reason", "boundary_rear_unowned",
                        "at", here.toShortString(),
                        "direction", forward.asString());
                return Optional.empty();
            }
            rear = forward.rotateYClockwise();
        }
        Direction gateFacing = rear.getOpposite();
        // Retreat only when the observed hostile is beyond the planned gate, never when it is in
        // the rear tunnel or beside the bot. Otherwise this safety shortcut could move toward the
        // threat and seal an unrelated wall; the general shelter/combat path retains ownership.
        if (hostilePos == null || !isAheadOfDirection(here, hostilePos, gateFacing)) {
            BotLog.danger(bot, "ore_dig_hostile_barricade_rejected",
                    "reason", "hostile_not_beyond_gate",
                    "at", here.toShortString(),
                    "hostile", hostilePos == null ? "unknown" : hostilePos.toShortString(),
                    "gate_facing", gateFacing.asString());
            return Optional.empty();
        }
        BlockPos retreat = null;
        BlockPos gate = null;
        Standability.clearCache();
        for (int distance = HOSTILE_BARRICADE_RETREAT; distance >= 1; distance--) {
            BlockPos candidateRetreat = here.offset(rear, distance);
            BlockPos candidateGate = candidateRetreat.offset(gateFacing);
            // The durable corner pair proves only the crossed adjacent cell. Re-observe every
            // deeper body/floor and every gate face before reading its survival geometry; if the
            // longest plan is occluded, the loop naturally tries a shorter observable retreat.
            boolean observableCornerPlan = !factualCorner
                    || isObservableRearCorridor(bot, here, rear, distance)
                    && isObservableNarrowMiningGate(bot, candidateGate, gateFacing);
            if (observableCornerPlan
                    && isControlledRearCorridor(bot, here, rear, distance)
                    && isNarrowMiningGate(bot, candidateGate, gateFacing)) {
                retreat = candidateRetreat.toImmutable();
                gate = candidateGate.toImmutable();
                break;
            }
        }
        if (retreat == null || gate == null) {
            BotLog.danger(bot, "ore_dig_hostile_barricade_rejected",
                    "reason", "no_narrow_rear_gate",
                    "at", here.toShortString(),
                    "direction", forward.asString());
            return Optional.empty();
        }
        // isNarrowMiningGate proved that both the feet and head gate cells are open. Reject the
        // plan before mutating OreDig's target queues/cursor unless the safety task can seal both;
        // otherwise one remaining block would repeatedly create a failing barricade while leaving
        // this exact mining instance paused behind an already-committed retreat.
        if (!MiningBarricadeTask.hasMaterialsForOpenGate(bot)) {
            BotLog.danger(bot, "ore_dig_hostile_barricade_rejected",
                    "reason", "insufficient_gate_blocks",
                    "available", io.github.zoyluo.minecraftai.action.MaterialPalette
                            .countShelterBlocks(bot));
            return Optional.empty();
        }

        miner.cancel(bot);
        bot.getActionPack().stopAll();
        if (targetOre != null) {
            excludeOre(bot, targetOre);
        }
        targetOre = null;
        bonusOre = null;
        veinQueue.clear();
        rememberedHighWorkPoses.clear();
        lavaReroute = null;
        clearStripMovementOwnership();
        // Closing the hostile-facing leg also consumes any same-origin boundary turn. Keeping the
        // marker beside steps_left=0 would publish an undecodable mixed cursor on pause/restart.
        boundaryRerouteOrigin = null;
        stripStepsLeft = 0;
        stripProgressPos = retreat;
        // A restart during the safety task must return to the known rear, not to the cave-facing
        // pose that triggered it. The remaining cursor/budget and all finite ledgers stay intact.
        lastFace = retreat;
        noteProgress();
        BotLog.danger(bot, "ore_dig_hostile_branch_closed",
                "at", here.toShortString(),
                "hostile", hostilePos == null ? "unknown" : hostilePos.toShortString(),
                "retreat", retreat.toShortString(),
                "gate", gate.toShortString(),
                "direction", forward.asString());
        return Optional.of(new MiningBarricadeTask(retreat, gate));
    }

    private static boolean isControlledRearCorridor(AIPlayerEntity bot,
                                                     BlockPos origin,
                                                     Direction rear,
                                                     int distance) {
        for (int step = 1; step <= distance; step++) {
            BlockPos candidate = origin.offset(rear, step);
            if (!Standability.isStandable(bot.getEntityWorld(), candidate)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isObservableRearCorridor(AIPlayerEntity bot,
                                                     BlockPos origin,
                                                     Direction rear,
                                                     int distance) {
        for (int step = 1; step <= distance; step++) {
            BlockPos candidate = origin.offset(rear, step);
            if (!ObservableWorldQuery.canObserveCell(bot, candidate)
                    || !ObservableWorldQuery.canObserveCell(bot, candidate.up())
                    || !ObservableWorldQuery.canObserveBlock(bot, candidate.down())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isObservableNarrowMiningGate(AIPlayerEntity bot,
                                                         BlockPos gate,
                                                         Direction forward) {
        if (!ObservableWorldQuery.canObserveCell(bot, gate)
                || !ObservableWorldQuery.canObserveCell(bot, gate.up())
                || !ObservableWorldQuery.canObserveBlock(bot, gate.down())
                || !ObservableWorldQuery.canObserveBlock(bot, gate.up(2))) {
            return false;
        }
        Direction side = forward.rotateYClockwise();
        for (Direction wall : new Direction[]{side, side.getOpposite()}) {
            if (!ObservableWorldQuery.canObserveBlock(bot, gate.offset(wall))
                    || !ObservableWorldQuery.canObserveBlock(bot, gate.up().offset(wall))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNarrowMiningGate(AIPlayerEntity bot,
                                               BlockPos gate,
                                               Direction forward) {
        ServerWorld world = bot.getEntityWorld();
        if (!isDryOpen(world, gate) || !isDryOpen(world, gate.up())
                || !isSolidSafe(world, gate.down()) || !isSolidSafe(world, gate.up(2))) {
            return false;
        }
        Direction side = forward.rotateYClockwise();
        for (Direction wall : new Direction[]{side, side.getOpposite()}) {
            if (!isSolidSafe(world, gate.offset(wall))
                    || !isSolidSafe(world, gate.up().offset(wall))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDryOpen(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isSolidSafe(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean sameLavaCluster(BlockPos first, BlockPos second) {
        return squaredBlockDistance(first, second) <= 16L;
    }

    private static long squaredBlockDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isAheadOfDirection(BlockPos origin, BlockPos target, Direction direction) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        return dx * direction.getOffsetX() + dz * direction.getOffsetZ() > 0;
    }

    private static boolean hasImmediateLava(AIPlayerEntity bot) {
        BlockPos feet = bot.getBlockPos();
        for (BlockPos center : new BlockPos[]{feet, feet.up()}) {
            if (OreScan.observeDangerFluid(bot, center)
                    == OreScan.Observation.OBSERVED_PRESENT
                    && bot.getEntityWorld().getFluidState(center).isIn(FluidTags.LAVA)) {
                return true;
            }
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = center.offset(direction);
                if (OreScan.observeDangerFluid(bot, adjacent)
                        == OreScan.Observation.OBSERVED_PRESENT
                        && bot.getEntityWorld().getFluidState(adjacent).isIn(FluidTags.LAVA)) {
                    return true;
                }
            }
        }
        return OreScan.observeDangerFluid(bot, feet.down())
                == OreScan.Observation.OBSERVED_PRESENT
                && bot.getEntityWorld().getFluidState(feet.down()).isIn(FluidTags.LAVA);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("ore_dig_invalid_checkpoint");
            return;
        }
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        budgetOffset = restoredCheckpoint != null && restoredCheckpoint.batchOpen()
                ? restoredCheckpoint.budgetUsed() : 0;
        lastProgressBudget = restoredCheckpoint != null && restoredCheckpoint.batchOpen()
                ? restoredCheckpoint.lastProgressBudget() : 0;
        pickupGrace = 0;
        targetOre = null;
        rememberedHighWorkPoses.clear();
        rememberedHighWorkPoseRouteOwner = null;
        rememberedHighWorkPoseRouteStartedBudget = -1;
        if (restoredCheckpoint != null && restoredCheckpoint.batchOpen()) {
            rememberedHighWorkPoses.putAll(restoredCheckpoint.rememberedHighWorkPoses());
        }
        MiningCursor cursor = restoredCursor == null
                ? MiningCursor.initial(bot.getBlockPos(), STRIP_SEGMENT)
                : restoredCursor;
        boolean rebaseCommittedCursor = restoredCheckpoint != null
                && !restoredCheckpoint.batchOpen()
                && squaredBlockDistance(bot.getBlockPos(), cursor.face())
                > MAX_COMMITTED_CURSOR_HANDOFF_DISTANCE_SQUARED;
        if (rebaseCommittedCursor) {
            BlockPos abandonedFace = cursor.face();
            cursor = new MiningCursor(
                    MiningCursor.CURRENT_SCHEMA,
                    bot.getBlockPos(),
                    bot.getBlockPos(),
                    -1,
                    0,
                    0,
                    STRIP_SEGMENT,
                    cursor.completedBatches());
            BotLog.task(bot, "ore_dig_committed_cursor_rebased",
                    "old_face", abandonedFace.toShortString(),
                    "new_face", cursor.face().toShortString(),
                    "distance_squared", squaredBlockDistance(
                            abandonedFace, cursor.face()));
        }
        cursorOrigin = cursor.origin();
        lastFace = cursor.face();
        stripProgressPos = cursor.face();
        controlledStripRear = restoredCheckpoint != null && restoredCheckpoint.batchOpen()
                ? restoredCheckpoint.controlledStripRear() : null;
        boundaryRerouteOrigin = restoredCheckpoint != null && restoredCheckpoint.batchOpen()
                ? restoredCheckpoint.boundaryRerouteOrigin() : null;
        completedBatches = cursor.completedBatches();
        if (restoredPendingPickupPos != null && restoredPendingPickupInventory >= 0) {
            pendingPickupPos = restoredPendingPickupPos;
            pendingPickupLastSeenPos = restoredPendingPickupLastSeenPos;
            pendingPickupInventory = restoredPendingPickupInventory;
            pendingPickupStarted = restoredCheckpoint.pendingPickupStartedBudget();
            pendingPickupGainTick = restoredCheckpoint.pendingPickupGainBudget();
        }
        if (restoredActiveTargetBreakPos != null && restoredActiveTargetBreakInventory >= 0) {
            OreScan.Observation restoredTarget = OreScan.observeOre(
                    bot, restoredActiveTargetBreakPos, targetOres);
            if (restoredTarget != OreScan.Observation.OBSERVED_GONE) {
                // A restart loses BlockMiner's controller, not ownership of the finite block.
                // UNKNOWN keeps the exact active target staged until ordinary perception can prove
                // either the intact ore or its factual disappearance.
                activeTargetBreakPos = restoredActiveTargetBreakPos;
                activeTargetBreakInventory = restoredActiveTargetBreakInventory;
                targetOre = restoredActiveTargetBreakPos;
            } else if (pendingPickupPos == null) {
                pendingPickupPos = restoredActiveTargetBreakPos;
                pendingPickupLastSeenPos = restoredActiveTargetBreakPos;
                pendingPickupInventory = restoredActiveTargetBreakInventory;
                pendingPickupStarted = totalBudget();
                pendingPickupGainTick = -1;
            }
        }
        if (restoredCursor != null && !rebaseCommittedCursor) {
            stripDirIndex = cursor.directionIndex();
            stripLegIndex = cursor.legIndex();
            stripStepsLeft = cursor.stepsLeft();
            stripLegLength = cursor.legLength();
            restoringFace = !bot.getBlockPos().equals(cursor.face());
            restoreFaceStarted = elapsed;
            BotLog.task(bot, "ore_dig_cursor_restored",
                    "face", cursor.face().toShortString(),
                    "leg", stripLegIndex,
                    "remaining", stripStepsLeft,
                    "batches", completedBatches);
        }
        // R6 entry landmark: auto-mark the dig site (goto_place mine_entry walks back in one step; can also answer when a player asks "where's the mine?").
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUuid())
                .markPlace("mine_entry", bot.getEntityWorld(), bot.getBlockPos());
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        detourInterrupted(bot);
        publishInterruptionCursor(bot, true);
        clearPendingBlindAdvance();
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (lastFace == null) {
            return;
        }
        // A safety task may move the bot after pause admission.  The saved face is either the
        // exact recoverable rear of an unpublished branch step or the last physical work cell
        // published at pause time.  Rejoin it before any scan can reinterpret the safety task's
        // destination as a new mining cursor.
        restoringFace = !bot.getBlockPos().equals(lastFace);
        restoreFaceStarted = elapsed;
        detourResumed(restoringFace);
        if (restoringFace) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        boolean detourWasLive = detourInterrupted(bot);
        publishInterruptionCursor(bot, false);
        clearPendingBlindAdvance();
        markMineFace(bot);
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        reapplyAnchor(bot, detourWasLive);
    }

    /**
     * Publishes the exact process boundary before pause/cancel can hand movement ownership to a
     * safety task.  A recoverable adjacent branch advance deliberately keeps its pre-move rear and
     * cursor so resume performs the same conservative rollback as a process restart.  If that rear
     * is no longer safe, the factual forward movement is instead committed atomically now; merely
     * changing {@code lastFace} would create a post-move face with pre-move remaining steps.
     */
    private void publishInterruptionCursor(AIPlayerEntity bot,
                                           boolean preserveExistingFace) {
        BlockPos feet = bot.getBlockPos();
        PendingBlindAdvanceInspection pending = inspectPendingBlindAdvance(bot, feet);
        BlockPos exactRear = pending.phase() == PendingBlindAdvancePhase.ARRIVED
                ? pending.rear() : null;
        if (!restoringFace && exactRear != null) {
            if (!canRetainUnpublishedBlindAdvance(
                    bot, bot.getEntityWorld(), feet, exactRear)) {
                Direction direction = STRIP_DIRS[stripDirIndex];
                BlockPos factualRear = publishStripProgress(bot, direction);
                if (stripStepsLeft <= 0) {
                    publishCompletedStripSuccessor(bot, direction, factualRear);
                }
            }
            return;
        }
        // pauseFor may be called after prepareHostileBarricade has already moved lastFace to a
        // deeper verified retreat. More generally, the previously published face is the only
        // conservative owner when an arbitrary action advances between task ticks. Preserve it
        // across live pause; abort/cancel still publishes the current physical face as before.
        if (!restoringFace && (!preserveExistingFace || lastFace == null)) {
            lastFace = feet.toImmutable();
            if (boundaryRerouteOrigin != null
                    && !feet.equals(boundaryRerouteOrigin)) {
                boundaryRerouteOrigin = null;
            }
        }
    }

    // R6/R7 work-face landmark: where the task ends = the starting point for next time it resumes digging. The ore type is remembered alongside it, so resume_mining doesn't need to ask.
    private void markMineFace(AIPlayerEntity bot) {
        var mem = io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUuid());
        mem.markPlace("mine_face", bot.getEntityWorld(), bot.getBlockPos());
        String ores = targetOres.stream()
                .map(b -> net.minecraft.registry.Registries.BLOCK.getId(b).toString())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        mem.remember("mine_face_ores", ores);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos feet = bot.getBlockPos();
        // Falling sand/gravel is updated after the task tick that opened a tunnel cell.  The cell
        // can therefore look clear long enough for the fake player to enter, then become occupied
        // before the next mining decision.  Resolve the current body collision before restoring a
        // cursor, scanning or opening another channel block.  NavSafetyNet is intentionally only a
        // global fallback; OreDig owns the factual previous face needed for a strict physical exit.
        if (recoverBlockedBody(bot, world, feet)) {
            clearStripMovementOwnership();
            return;
        }
        // Quota delivery wins over timeout/face restoration: a crash in the final pickup-grace
        // tick must never turn a fully delivered batch back into mining work.
        if (targetCount == 0) {
            finishAlreadyDeliveredBatch(bot, world);
            return;
        }
        if (totalBudget() > maxElapsed) {
            fail("ore_dig_timeout collected=" + collected);
            return;
        }
        if (restoringFace) {
            if (detourResumeReturn(bot)) {
                return;
            }
            returnToSavedFace(bot);
            return;
        }
        // WalkTo can reach the next branch cell between two scan-paced strip decisions. Treat that
        // exact movement as an unpublished transaction: while its factual rear remains observable
        // and safe, checkpoint face/progress/steps/boundary marker all retain the pre-move state.
        // If the rear changed underneath us, publish the current lip instead of promising an
        // unsafe restart destination. A successful consume below commits every cursor field in one
        // task tick before any vein/bonus/target owner may be created.
        PendingBlindAdvanceInspection pendingAdvance = inspectPendingBlindAdvance(bot, feet);
        BlockPos unpublishedRear = pendingAdvance.phase() == PendingBlindAdvancePhase.ARRIVED
                ? pendingAdvance.rear() : null;
        boolean recoverableUnpublishedAdvance = unpublishedRear != null
                && canRetainUnpublishedBlindAdvance(bot, world, feet, unpublishedRear);
        boolean committedUnrecoverableAdvance = unpublishedRear != null
                && !recoverableUnpublishedAdvance;
        if (committedUnrecoverableAdvance) {
            // The move is factual even though its rear changed underneath it. Publish face,
            // progress, remaining steps and boundary marker as one transaction before any
            // capacity/no-progress failure or process snapshot can observe a mixed cursor.
            Direction direction = STRIP_DIRS[stripDirIndex];
            BlockPos factualRear = publishStripProgress(bot, direction);
            if (stripStepsLeft <= 0) {
                publishCompletedStripSuccessor(bot, direction, factualRear);
                return;
            }
        } else if (unpublishedRear == null) {
            // Target approaches, rich-zone paths and other non-strip owners may move far away
            // from the last blind-branch projection. Re-anchor the projection to the same factual
            // face already persisted by the checkpoint; otherwise the next strip tick interprets
            // arbitrary target/path displacement as planar branch progress and can consume an
            // entire leg without a factual rear (seed 3000: north target path became an east
            // marker-free open-drop boundary). This is bookkeeping only, not task progress.
            BlockPos factualFace = bot.getBlockPos().toImmutable();
            if (stripProgressPos == null || !stripProgressPos.equals(factualFace)) {
                stripProgressPos = factualFace;
                clearStripMovementOwnership();
            }
            lastFace = factualFace;
            if (boundaryRerouteOrigin != null
                    && !feet.equals(boundaryRerouteOrigin)) {
                boundaryRerouteOrigin = null;
            }
        }

        // Underwater work doesn't wait until 5 seconds of oxygen remain before failing and
        // replanning. Let the shared safety net physically return to a dry work face first, while
        // preserving the current OreDig/branch cursor; otherwise a replan would generate surface
        // prerequisites like "chop trees" based on the ore-floor position.
        if (bot.isSubmergedInWater() || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
            clearStripMovementOwnership();
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            noteProgress();
            return;
        }

        // Tool gate: if the target ore can't be mined (no qualifying pickaxe), fail immediately and hand off to GoalExecutor to backward-chain acquiring a pickaxe.
        if (!canHarvestAnyTarget(bot)) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetOres));
            return;
        }

        // Collection count: a fixed-baseline absolute increment (drops from ore just broken that then land in the inventory get counted in).
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 3.0D, 3.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        boolean targetInventoryAdvanced = total > collected;
        if (total > collected) {
            collected = total;
            noteProgress();
            BotLog.action(bot, "ore_dig_collected", "total", collected + "/" + targetCount);
            io.github.zoyluo.minecraftai.brain.BotReporter.INSTANCE.onGoalMessage(bot,
                    "Collected " + Registries.ITEM.getId(targetDrops.iterator().next())
                            .getPath().replace('_', ' ') + ": " + collected + "/" + targetCount + ".");
        }
        // Clear the durable physical-drop debt before either opening another ore or declaring the
        // final quota complete.  Completing immediately on the last inventory increment left a
        // pending_pickup_pos in the checkpoint and one active path tick after task completion.
        if (pendingPickupPos != null || activeTargetBreakPos != null) {
            clearStripMovementOwnership();
        }
        if (recoverPendingTargetDrop(bot)) {
            return;
        }
        if (collected >= targetCount) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline >= targetCount) {
                markMineFace(bot);
                complete();
            }
            return;
        }

        // Capacity is evaluated only after factual inventory gain, pending-drop settlement and the
        // completed-quota branch above. This lets the final target item fill the last slot and still
        // commit successfully. OreDig never creates an open-world junk pile at its work face: every
        // unfinished batch stops before opening another block and lets GoalExecutor route the exact
        // cursor through one bounded sealed MiningService transaction.
        if (HarvestCore.isInventoryFull(bot)) {
            fail("ore_dig_inventory_service_required");
            return;
        }

        // No-progress watchdog: if no block is broken within NO_PROGRESS_LIMIT -> fail cleanly.
        // Dump internal state before failing, so headless tests can diagnose exactly which stage
        // "found ore but no progress" got stuck at (lost lock / approach failure / can't mine).
        if (totalBudget() - lastProgressBudget > NO_PROGRESS_LIMIT) {
            // (The old one-time "amnesty" has been replaced by EpisodeMemory's short TTL exclusion: 30s auto-revival, finer-grained than a blanket amnesty.)
            BotLog.action(bot, "ore_dig_stall_dump",
                    "target", targetOre == null ? "none"
                            : targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ(),
                    "dist", targetOre == null ? "-"
                            : String.format("%.1f", Math.sqrt(bot.getEyePos().squaredDistanceTo(targetOre.toCenterPos()))),
                    "miner", miner.target() == null ? "idle"
                            : miner.target().getX() + "," + miner.target().getY() + "," + miner.target().getZ(),
                    "ignored", EpisodeMemory.INSTANCE.excludedCount(bot.getUuid()),
                    "vein_queue", veinQueue.size(),
                    "strip_left", stripStepsLeft);
            // Automatic terrain snapshot (the key to diagnosing real_diamond seed777's deep-approach
            // jitter): dump the geometry between bot and ore as a compact per-Y-layer ASCII map
            // (#solid/.air/O=ore/~=fluid/B=bot/T=target-ore) into the log (test logs can grep it
            // back out). Blind changes to deep approach have regressed geo_deep before -- we must
            // freeze the exact geometry into a deterministic repro before refining further. Zero
            // behavior change here.
            dumpStallRegion(bot, world);
            miner.cancel(bot);
            fail("ore_dig_no_progress collected=" + collected);
            return;
        }

        // Do not let a scan discover a competing owner while a blind-branch move is only partially
        // published. The direct walker may report the destination BlockPos one server update before
        // it clears its controller; wait for that owner to settle, then consume the exact step and
        // run its forward-boundary preflight immediately instead of waiting for SCAN_INTERVAL.
        if (pendingAdvance.phase() == PendingBlindAdvancePhase.IN_FLIGHT_AT_REAR) {
            return;
        }
        if (pendingAdvance.phase() == PendingBlindAdvancePhase.RETRY_AT_REAR) {
            stripMine(bot, world);
            return;
        }
        if (recoverableUnpublishedAdvance || committedUnrecoverableAdvance) {
            if (!bot.getActionPack().isWalkToIdle()) {
                return;
            }
            stripMine(bot, world);
            return;
        }

        // Mining assist R1 (design 4.14): a live or startable opportunistic detour owns this tick.
        if (tickOpportunistic(bot, world)) {
            return;
        }

        // 1) First drain the adjacent-vein queue (after mining one ore block, clear out same-vein neighbors along with it).
        if (targetOre == null && advanceVein(bot, world)) {
            clearStripMovementOwnership();
            return;
        }

        // R3 opportunistic ore (real-player muscle memory): non-target ore appearing within reach
        // while en route/digging -- coal is a fuel essential, iron is tool currency, and passing up
        // a free pickup would be foolish. The approach to a locked target ore is exactly where
        // opportunistic pickups are most frequent (geo_bonus's first validation: previously this
        // only scanned in the "no lock" branch, so while digging with iron locked in the whole time,
        // opportunistic pickup never triggered). The one time it should NOT trigger: while the miner
        // is already mid-bite on the target ore (switching targets halfway through would wipe its
        // progress). Constraints: single block only, no vein chasing, budget-capped, does not count
        // toward the target count.
        // A single BlockMiner owns target ore, channel rock and bonus ore. Never discover a new
        // bonus while that miner is working on any other block: beginMine would switch targets and
        // reset a slower channel block every scan interval. Dense copper beside an iron branch was
        // a deterministic starvation case in seed 3000.
        boolean minerBusy = miner.target() != null || hasStagedBlindFootWork(bot);
        if (bonusOre == null && !minerBusy && bonusMined < BONUS_CAP
                && bot.getEntityWorld().getServer().getTicks() - lastBonusScanTick >= SCAN_INTERVAL
                && !HarvestCore.isInventoryFull(bot)) {
            lastBonusScanTick = bot.getEntityWorld().getServer().getTicks();
            bonusOre = scanBonusOre(bot, world);
            if (bonusOre != null) {
                clearStripMovementOwnership();
            }
        }
        if (bonusOre != null) {
            clearStripMovementOwnership();
            boolean activeBonus = miner.target() != null && miner.target().equals(bonusOre);
            if (activeBonus) {
                // The owned BlockMiner transaction is authoritative after the physical break.
                // Its former block can become occluded by the newly exposed rear wall, so an
                // ordinary ore observation may now be UNKNOWN even though tick() can factually
                // settle DONE from the already-committed target coordinate.
                BlockMiner.Status st = miner.tick(bot);
                targetApproachTick = elapsed;
                if (st == BlockMiner.Status.DONE) {
                    bonusMined++;
                    noteProgress();
                    HarvestCore.forcePickupNearbyAnyOf(bot, null, 7.0D, 4.0D);
                    BotLog.action(bot, "ore_dig_bonus", "pos", bonusOre.toShortString(),
                            "total", bonusMined + "/" + BONUS_CAP);
                    bonusOre = null;
                } else if (st == BlockMiner.Status.FAILED
                        && !failMissingMiningChannelTool(bot)) {
                    excludeOre(bot, bonusOre);
                    bonusOre = null;
                }
                return;
            }
            OreScan.Observation bonusState = OreScan.observeAnyOre(bot, bonusOre);
            if (bonusState == OreScan.Observation.UNKNOWN) {
                // Temporary occlusion is not evidence that an opportunistic ore vanished. Keep the
                // exact owner and do not let another scan replace its BlockMiner transaction.
                return;
            }
            boolean bonusStillPresent = bonusState == OreScan.Observation.OBSERVED_PRESENT;
            if (!bonusStillPresent || !withinReach(bot, bonusOre)) {
                bonusOre = null; // Mined by another executor or we walked away: let it go, don't backtrack for an opportunistic-ore pickup
            } else {
                // Tick an active bonus even after its block became air. BlockMiner owns the DONE
                // transition; clearing bonusOre first loses bonusMined/noteProgress and leaves the
                // cap permanently at zero, so every new copper block can preempt the main tunnel.
                // Active ownership also wins over a transient reach change: the air/DONE check is
                // the first operation inside BlockMiner.tick and must not be cancelled by stale
                // movement after the physical break.
                BlockMiner.Status st = beginMine(bot, bonusOre);
                targetApproachTick = elapsed; // An opportunistic swing doesn't count as approach stalling; don't let APPROACH_LIMIT wrongly kill the target ore
                if (st == BlockMiner.Status.DONE) {
                    bonusMined++;
                    noteProgress();
                    HarvestCore.forcePickupNearbyAnyOf(bot, null, 7.0D, 4.0D); // Pick up everything: the drop isn't in targetDrops
                    BotLog.action(bot, "ore_dig_bonus", "pos", bonusOre.toShortString(),
                            "total", bonusMined + "/" + BONUS_CAP);
                    bonusOre = null;
                } else if (st == BlockMiner.Status.FAILED
                        && !failMissingMiningChannelTool(bot)) {
                    excludeOre(bot, bonusOre);
                    bonusOre = null;
                }
                return;
            }
        }

        // 2) There is a currently locked ore: if reachable, mine it (queue same-vein neighbors after mining); if not reachable, dig one tunnel cell toward it.
        if (targetOre != null) {
            clearStripMovementOwnership();
            boolean miningTarget = miner.target() != null && miner.target().equals(targetOre);
            if (miningTarget) {
                // Re-prove the physical drop catch before every owned target tick. Once the block
                // breaks, its coordinate may cease to be ordinarily observable; BlockMiner still
                // owns that exact committed coordinate and must settle it before a fresh tri-state
                // scan is allowed to reinterpret the now-occluded cell.
                if (!passesTargetDropCommitGate(bot, world, targetOre)) {
                    return;
                }
                BlockMiner.Status st = miner.tick(bot);
                if (st == BlockMiner.Status.DONE) {
                    finishTargetBreak(bot, targetOre, activeTargetBreakInventory);
                    targetOre = null;
                    noteProgress();
                    consecutiveSkips = 0;
                } else if (st == BlockMiner.Status.FAILED
                        && !failMissingMiningChannelTool(bot)) {
                    clearActiveTargetBreak(targetOre);
                    excludeOre(bot, targetOre);
                    targetOre = null;
                }
                return;
            }
            OreScan.Observation targetState = OreScan.observeOre(bot, targetOre, targetOres);
            if (targetState == OreScan.Observation.UNKNOWN) {
                // Do not convert an occluded target into a completed break or a pickup debt. The
                // same exact owner is retried after ordinary movement exposes the cell again. An
                // already-issued approach may continue toward its remembered factual coordinate;
                // an active break simply pauses without reading through its new occluder. When no
                // movement survives a restart, only an already-observed side pose may be issued;
                // the generic tunnelling fallback could otherwise mine the unknown target itself.
                if (bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()) {
                    if (miner.target() != null) {
                        // An intermediate channel break is an older factual transaction. Settle it
                        // before issuing any new movement toward the still-unknown finite owner.
                        settleOwnedTunnelMine(bot, targetOre,
                                TunnelIntent.TARGET_APPROACH, miner.target());
                    } else {
                        BlockPos observedWorkPose = approachGoalFor(bot, world, targetOre);
                        if (observedWorkPose != null) {
                            rememberObservedHighWorkPose(bot, targetOre, observedWorkPose);
                            bot.getActionPack().startDigPathTo(
                                    observedWorkPose, protectedStoneLikeReserve);
                        } else if (!tryRememberedHighWorkPoseRoute(bot, world, targetOre)) {
                            continueUnknownOwnerApproach(
                                    bot, world, targetOre, TunnelIntent.TARGET_APPROACH);
                        }
                    }
                }
                return;
            }
            if (targetState == OreScan.Observation.OBSERVED_GONE) {
                // The ore is gone -- most often the pathfinding executor mined it in passing while
                // approaching, because the head-position cell happened to equal the ore (the
                // approach target is by design the cell directly under the ore). The drop is
                // already on the ground: open a dwell window and pick up with a wide radius
                // (geo_wall testing showed mine_complete is triggered by the executor and skips the
                // DONE branch, so without a dwell-and-pickup step here the collected count stays at
                // 0 for a mined-for-nothing block). Same-vein queueing proceeds as usual.
                int inventoryNow = HarvestCore.countInventoryItems(bot, targetDrops);
                int breakBaseline = activeTargetBreakPos != null && activeTargetBreakPos.equals(targetOre)
                        ? activeTargetBreakInventory
                        : Math.max(0, inventoryNow - (targetInventoryAdvanced ? 1 : 0));
                finishTargetBreak(bot, targetOre, breakBaseline);
                targetOre = null;
                return;
            }
            if (activeTargetBreakPos != null
                    && activeTargetBreakPos.equals(targetOre)
                    && !passesTargetDropCommitGate(bot, world, targetOre)) {
                // A restored active_break_pos has no live BlockMiner target yet. Re-run the same
                // pose gate before approach logic so an intact block authorized by an older build
                // cannot resume from a now-forbidden high-shaft pose.
                return;
            }
            // Re-lock onto a closer ore mid-approach (root cause confirmed for real_diamond
            // seed777): the bot locks onto a diamond 35 blocks away, then digs past a closer
            // same-vein ore block (dist 3-4) along the way but stubbornly keeps chasing the far
            // lock, and ends up jittering to death in the far ore's local geometry over the last 8
            // blocks. (This couldn't be reproduced in any of the three static synthetic scenarios --
            // because in those, targetOre starts null and the bot simply picks the nearest ore from
            // the start.) Re-scan every SCAN_INTERVAL: if a significantly closer (<0.6x current
            // distance), unexcluded target ore is found, switch to it -- mine the nearer one and
            // naturally avoid the far ore's jitter dead zone. Never interrupt ore currently being
            // mined (miningNow); the 0.6 threshold prevents flip-flopping between ore at similar
            // distances.
            boolean miningNow = miningTarget;
            int nowRe = bot.getEntityWorld().getServer().getTicks();
            if (!miningNow && nowRe - lastReLockTick >= SCAN_INTERVAL) {
                lastReLockTick = nowRe;
                BlockPos nearer = nearestOre(bot, world);
                if (nearer != null && !nearer.equals(targetOre)
                        && bot.getEyePos().squaredDistanceTo(nearer.toCenterPos())
                           < bot.getEyePos().squaredDistanceTo(targetOre.toCenterPos()) * 0.36D) {
                    BotLog.action(bot, "ore_dig_relock_nearer",
                            "from", targetOre.toShortString(), "to", nearer.toShortString());
                    targetOre = nearer.toImmutable();
                    lastTargetDist = Double.MAX_VALUE;
                    targetApproachTick = elapsed;
                    return;
                }
            }
            // P0: approach monitoring -- if digging toward the ore for a while still hasn't gotten
            // closer (e.g. unreachable diagonally-below position) -> abandon that ore instead of
            // spinning in place (testing found this was the root cause of a bot at Y=48 repeatedly
            // locking onto a diagonally-below diamond, dist stuck, no_progress after 11 minutes).
            double dist2 = bot.getEyePos().squaredDistanceTo(targetOre.toCenterPos());
            if (dist2 < lastTargetDist - 0.25D) {
                lastTargetDist = dist2;
                targetApproachTick = elapsed;
                // Approach also counts as progress: for a far ore, DIG-approach mines one cell at a
                // time, and a 16-cell tunnel takes ~190t; a no_progress (200t) that only recognizes
                // "ore actually mined" would wrongly kill a normal long approach halfway through
                // (geo_rich testing showed a run with dist 16 stalling out at 201t).
                noteProgress();
            } else if (elapsed - targetApproachTick > APPROACH_LIMIT) {
                excludeOre(bot, targetOre);
                consecutiveSkips++; // Accumulate unreachable skips; too many in a row -> the scan branch below forces a strip-mine advance, breaking the "lock far ore in place, skip" deadlock
                BotLog.action(bot, "ore_dig_unreachable_skip",
                        "pos", targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ(),
                        "skips", consecutiveSkips);
                targetOre = null;
                lastTargetDist = Double.MAX_VALUE;
                // Proactively switching targets is decision-driven progress: naturally embedded ore
                // may need several blocks excluded in a row before a reachable one or the rich-zone
                // fallback comes up, and that legitimate rotation shouldn't be killed by mistake.
                // But under a [large quota (a full suit of armor, >=16)], repeatedly re-locking onto
                // the same unreachable ore in the same spot (position unchanged), if the watchdog
                // were fed unconditionally, would thrash for 100s+ and never trip, with strip/replan
                // never taking over (real_armor testing: found324/collected9/bot motionless for
                // 106s). So under a large quota, only an actual change of territory (bot moved)
                // counts as progress; a small quota (sparse diamond, targetCount<16) keeps the old
                // unconditional feed, zero regression.
                if (targetCount < 16 || !bot.getBlockPos().equals(lastSkipPos)) {
                    noteProgress();
                    lastSkipPos = bot.getBlockPos().toImmutable();
                }
                return;
            }
            // Mining lock: only start mining once horizontally close to the ore block. Vanilla
            // behavior breaks the block right at the edge of reach, and the drop can bounce
            // randomly into a base corner where it's neither visible nor has an observable recovery
            // stance (seed3000: dx=1,dz=2). Once mining on this ore block has started, keep mining
            // it to completion regardless of whether it's still within reach right now -- a bot
            // standing on stairs shifting slightly can make dist oscillate back and forth around the
            // reach edge (4.5); the old logic would mine while in reach -> step out of reach to dig
            // a tunnel cell -> come back into reach and restart mining, resetting mining progress
            // every time and never finishing (testing showed mine_start cycling through 5
            // coordinates once a second with a stone pickaxe's 2.5s mining time on ore producing 300t
            // of zero output). If the bot actually walks far away, BlockMiner's own failure
            // determination provides the fallback (FAILED -> ignored).
            if (miningTarget || canBreakTargetFromHere(bot, targetOre)) {
                // Never remove the block carrying the bot. Fake-player motion can settle one tick
                // into the freshly opened cell; surrounding solids then deal suffocation damage.
                // Step onto an observed, standable neighbour and mine the ore from the side.
                if (!miningTarget && isCurrentSupport(bot, targetOre)) {
                    if (!moveOffSupport(bot, targetOre)) {
                        BotLog.action(bot, "ore_dig_support_unsafe", "ore", targetOre.toShortString());
                        excludeOre(bot, targetOre);
                        targetOre = null;
                    }
                    return;
                }
                if (!miningTarget) {
                    PickupEgressResult egress = tickPickupEgressClearance(
                            bot, world, targetOre);
                    if (egress == PickupEgressResult.WORKING) {
                        return;
                    }
                    if (egress == PickupEgressResult.UNSAFE) {
                        BotLog.action(bot, "ore_dig_pickup_egress_unsafe",
                                "ore", targetOre.toShortString());
                        excludeOre(bot, targetOre);
                        targetOre = null;
                        return;
                    }
                    if (egress == PickupEgressResult.TARGET_ORE) {
                        BlockPos original = targetOre;
                        targetOre = lowerOreTransitionHead(bot, original);
                        BotLog.action(bot, "ore_dig_pickup_egress_retarget",
                                "from", original.toShortString(),
                                "to", targetOre.toShortString());
                        return;
                    }
                }
                // P0 seal lava before mining (standard real-player technique): an ore's neighbor
                // face is touching lava, and the instant the ore is mined the lava floods in --
                // burning the bot and burning the drop. Separate sealing stage: the lava cell may be
                // one block farther than the ore, out of reach the moment reach is entered -> keep
                // closing in (walk toward the cell directly under the ore); once in reach, replace
                // the lava source with a low-value block (one tick, one cell); only abandon the ore
                // safely if there's no block left to seal with (life is worth more than ore).
                if (!miningTarget) {
                    AdjacentFluidObservation adjacentFluid = adjacentDangerFluidOf(bot, targetOre);
                    if (adjacentFluid.state() == OreScan.Observation.UNKNOWN) {
                        // UNKNOWN changes no target action: an occluded stone neighbour and an
                        // occluded fluid neighbour are indistinguishable. A newly exposed fluid is
                        // rechecked and sealed before the following target-mining settlement.
                    }
                    if (adjacentFluid.state() == OreScan.Observation.OBSERVED_PRESENT) {
                        BlockPos lava = adjacentFluid.position();
                        var blockSlot = MaterialPalette.pickSacrificialBlockSlot(
                                bot, protectedStoneLikeReserve);
                        if (blockSlot.isEmpty()) {
                            BotLog.action(bot, "ore_dig_fluid_unsealable",
                                    "ore", targetOre.toShortString(),
                                    "protected_stone", protectedStoneLikeReserve);
                            excludeOre(bot, targetOre);
                            targetOre = null;
                            return;
                        }
                        // The observable-fluid API already proves one exact inset face inside the
                        // real interaction range. Do not reject that legal edge ray by comparing
                        // block centers; BuildAction repeats the final vanilla placement proof.
                        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
                        ActionResult sealResult = BuildAction.placeBlockAt(bot, lava);
                        if (!sealResult.isFailed()) {
                            BotLog.action(bot, "ore_dig_fluid_seal", "sealed", lava.toShortString());
                            noteProgress(); // Sealing also counts as progress
                        } else {
                            BotLog.action(bot, "ore_dig_seal_fail",
                                    "lava", lava.toShortString(), "reason", sealResult.reason());
                        }
                        if (sealResult.isFailed()
                                && bot.getActionPack().isPathExecutorIdle()) {
                            bot.getActionPack().startDigPathTo(
                                    targetOre.down(), protectedStoneLikeReserve); // Close in until sealing is reachable
                        }
                        return;
                    }
                }
                if (!passesTargetDropCommitGate(bot, world, targetOre)) {
                    return;
                }
                if (!miningTarget) {
                    queueVeinAround(bot, world, targetOre);
                }
                BlockMiner.Status st = miningTarget
                        ? miner.tick(bot)
                        : beginTargetMine(bot, targetOre);
                if (st == BlockMiner.Status.DONE) {
                    // Drop pickup radius keeps pace with reach: pathfinding approach stops mining at
                    // the reach edge (5.5), the drop lands at the ore's position, beyond the passive
                    // per-tick 3-block pickup radius (the old close-quarters direct-dig only exposed
                    // this at 1-2 blocks away); as soon as it's mined, do a directed wide-radius
                    // pickup sweep, otherwise collected never increases and the bot gets pulled away
                    // by the next target having mined for nothing (geo_wall testing: 0/1 after
                    // mine_complete).
                    finishTargetBreak(bot, targetOre, activeTargetBreakInventory);
                    targetOre = null;
                    noteProgress();
                    consecutiveSkips = 0; // Successfully mined -> reset the skip count (this area is reachable, no need to force strip-mining)
                } else if (st == BlockMiner.Status.FAILED
                        && !failMissingMiningChannelTool(bot)) {
                    clearActiveTargetBreak(targetOre);
                    excludeOre(bot, targetOre);
                    targetOre = null;
                }
                return;
            }
            // Not at a safe mining position -> fall back to the unified approach primitive:
            // dig-aware pathfinding straight to a position adjacent to the ore (A* DIG with a large
            // budget, with a destination exemption allowing a solid cell that becomes standable once
            // dug). When no observable safe side stance exists or pathfinding is rejected,
            // digTowardStep only opens one cell of controlled tunnel, then retries the side stance
            // next tick; APPROACH_LIMIT still handles circuit-breaking for ore that truly can't be
            // reached.
            approachTargetOre(bot, world, targetOre);
            return;
        }

        // 3) No locked ore: scan for the nearest target ore (rate-limited).
        int now = bot.getEntityWorld().getServer().getTicks();
        if (now - lastScanTick < SCAN_INTERVAL) {
            return;
        }
        lastScanTick = now;
        // Break the "lock far ore in place, skip" deadlock: if STRIP_AFTER_SKIPS consecutive locked
        // ores in a row have all been unreachable and skipped -> stop locking on (it's most likely
        // another unreachable far ore); force one strip-mine advance step instead (dig a tunnel
        // forward, bringing ore closer into reach + exposing new ore faces). Reset the count after
        // advancing, resume normal scanning next round; nearby ore is now reachable and gets locked
        // and mined (real_armor's actual fix: 372 skips in place mining only 9 -> stable mining
        // after strip-mine advances).
        if (consecutiveSkips >= STRIP_AFTER_SKIPS) {
            consecutiveSkips = 0;
            stripMine(bot, world);
            return;
        }
        BlockPos found = nearestOre(bot, world);
        if (found != null) {
            clearStripMovementOwnership();
            targetOre = found;
            lastTargetDist = Double.MAX_VALUE;  // P0: newly locked ore, reset approach monitoring
            targetApproachTick = elapsed;
            // Episodic memory: resource discoveries flow in -> distilled into resource points
            // (deduplicated within 8 blocks), so next time "is there iron nearby" can ask the
            // knowledge base first instead of digging blind.
            io.github.zoyluo.minecraftai.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.minecraftai.memory.EpisodeLog.Type.RESOURCE_FOUND, found,
                    net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(found).getBlock()).toString());
            BotLog.action(bot, "ore_dig_found",
                    "pos", found.getX() + "," + found.getY() + "," + found.getZ(),
                    "dist", (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(found)),
                    "collected", collected + "/" + targetCount);
            return;
        }
        // No ore nearby (24 blocks) -> wide-range prospecting (64 blocks, ported from the player
        // magic mod's HelmetOreLocator scan) to locate the nearest ore vein; once locked on, the
        // digTowardStep above digs a directed tunnel to it. More efficient than blind strip-mining
        // -- can find diamonds dozens of blocks away, no longer "give up if nothing nearby."
        BlockPos prospected = prospect(bot, world);
        if (prospected != null) {
            clearStripMovementOwnership();
            targetOre = prospected;
            lastTargetDist = Double.MAX_VALUE;
            targetApproachTick = elapsed;
            BotLog.action(bot, "ore_dig_prospected",
                    "pos", prospected.getX() + "," + prospected.getY() + "," + prospected.getZ(),
                    "dist", (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(prospected)));
            return;
        }
        // No prospecting hit either -> first ask the knowledge base for a rich zone (a place we've
        // consistently mined ore before, within 128 blocks, with >=3 points clustered within 24
        // blocks): the cluster center is a "resource-point coordinate," not an ore cell -- using it
        // as targetOre would get instantly wiped by the "ore is gone" branch into an infinite loop
        // (testing showed it re-triggering every second, spinning in place). The correct semantics
        // are: navigate to the rich zone, and once close, the short-range scan naturally takes over;
        // if there's still no ore once there, the memory is stale, so invalidate that resource point
        // and move to the next strategy.
        for (Block oreBlock : targetOres) {
            String oreId = net.minecraft.registry.Registries.BLOCK.getId(oreBlock).toString();
            var rich = io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE
                    .richZoneNear(bot.getUuid(), oreId, bot.getBlockPos(), 128, 3, 24);
            if (rich.isPresent() && !oreExcluded(bot, rich.get())) {
                BlockPos zone = rich.get();
                if (bot.getBlockPos().isWithinDistance(zone, 16)) {
                    io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE
                            .invalidateResource(bot.getUuid(), zone);
                    BotLog.action(bot, "ore_dig_rich_zone_stale", "at", zone.toShortString());
                } else if (bot.getActionPack().isPathExecutorIdle()) {
                    clearStripMovementOwnership();
                    // Prefer walk (startPathTo's two-phase approach): a rich zone is often 100+
                    // blocks away, and a single-phase large-budget DIG pathfind at 50ms would always
                    // TIMEOUT -> resending a failed pathfind every cooldown period, storming in place
                    // until timeout (testing showed 2 sends per second with zero movement).
                    bot.getActionPack().startPathTo(zone, protectedStoneLikeReserve);
                    BotLog.action(bot, "ore_dig_rich_zone", "to", zone.toShortString());
                    noteProgress(); // Setting out also counts as progress
                }
                return;
            }
        }
        // P5 (design 5.4, off by default): cave-frontier excursion trigger, checked once the scan ladder
        // has exhausted nearestOre, prospect and the rich zone -- exactly where a spiral strip leg would
        // otherwise start (design 8.2 hook 14). explorationTick starts a detour of kind FRONTIER through
        // the SAME engine as the P1 opportunistic detour (tickOpportunistic, hook 9, already ticks it on
        // every later tick); this call only ever fires the START.
        if (explorationTick(bot, world)) {
            return;
        }
        // P4 (design 5.3, off by default): note the ground under the bot as covered before the strip
        // itself runs, so LegChooser's freshFraction sees the trail this exact tick leaves behind.
        markStripCoverage(bot);
        // Horizontal strip-mine digging exposes new ore faces.
        stripMine(bot, world);
    }

    /** Two static reads and a return while L1 is off (design 5.3); see {@link #tickOpportunistic} for the idiom. */
    private void markStripCoverage(AIPlayerEntity bot) {
        if (!MiningAssistRuntime.senseConfigured() || !MiningAssistRuntime.config().explore().legChooser()) {
            return;
        }
        stripCoverage.mark(bot.getBlockPos());
    }

    private void finishAlreadyDeliveredBatch(AIPlayerEntity bot, ServerWorld world) {
        if (bot.isSubmergedInWater() || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            NavSafetyNet.INSTANCE.requestWaterRescue(bot);
            return;
        }
        if (activeTargetBreakPos != null) {
            BlockPos active = activeTargetBreakPos;
            miner.cancel(bot);
            OreScan.Observation activeState = OreScan.observeOre(bot, active, targetOres);
            if (activeState == OreScan.Observation.UNKNOWN) {
                // Delivered inventory does not authorize guessing whether the last open break
                // committed. But waiting blind is unbounded after a restart whose restore pose
                // cannot see the cell at all — this fast path also runs before the hard timeout,
                // so nothing else would ever end it. Walk the observable ring to regain
                // perception; past the bounded window prefer the under-claiming exact-once
                // outcome (leave the ore in the world, never count it).
                deliveredBatchObservationTicks++;
                if (deliveredBatchObservationTicks > RESTORE_FACE_LIMIT) {
                    BotLog.action(bot, "ore_dig_delivered_batch_break_unobservable",
                            "pos", active.toShortString(),
                            "waited", deliveredBatchObservationTicks);
                    clearActiveTargetBreak(active);
                    return;
                }
                if (deliveredBatchObservationTicks % 20 == 0
                        && bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()) {
                    startObservationSweepStep(bot, active);
                }
                return;
            }
            if (activeState == OreScan.Observation.OBSERVED_PRESENT) {
                // The block was never committed. Leaving it in the world is the only exact-once
                // outcome now that this batch's delivered quota is already complete.
                clearActiveTargetBreak(active);
            } else if (pendingPickupPos == null) {
                finishTargetBreak(bot, active, activeTargetBreakInventory);
            }
        }
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 3.0D, 3.0D);
        if (recoverPendingTargetDrop(bot)) {
            return;
        }
        markMineFace(bot);
        complete();
    }

    /**
     * Physically leaves, or survival-mines, a block that reoccupied OreDig's current feet/head.
     * A falling-block retreat preserves the unfinished branch budget and immediately republishes
     * the factual safe face as a bounded reroute origin. Closing the leg here would advance the
     * square cursor before that observation and can point it straight back at an earlier boundary.
     * Other body intrusions retain the conservative close-leg behavior. Target/pickup ledgers are
     * never discarded here.
     */
    private boolean recoverBlockedBody(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        BlockPos blocked = firstBodyCollision(world, feet);
        if (blocked == null) {
            if (blockedBodyRecoveryTarget == null) {
                return false;
            }
            // Yield one full task tick after the obstruction disappears.  Another gravity block may
            // still be scheduled to enter the same cell; normal branch motion resumes only after it
            // also passes this preflight.
            miner.cancel(bot);
            blockedBodyRecoveryTarget = null;
            noteProgress();
            return true;
        }

        var obstruction = world.getBlockState(blocked);
        BlockPos retreat = findBlockedBodyRetreat(bot, world, feet);
        if (retreat != null) {
            // Capture ownership before stopAll clears the distinction between an exact blind
            // branch walk and a long-range rich-zone PathExecutor. targetOre == null alone is not
            // an intent signal: pickup, queued-vein, bonus and restore owners all use that shape.
            boolean blindBranchCollision = ownsActiveBlindBranchCollision(bot, feet, retreat);
            boolean preserveRestoreTarget = restoringFace;
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            boolean moved = io.github.zoyluo.minecraftai.mode.FakePlayerMotion.stepToStandable(
                    bot, retreat, "ore_dig_blocked_body_retreat");
            if (moved && bot.getBlockPos().equals(retreat)) {
                blockedBodyRecoveryTarget = null;
                boolean fallingBranchCollapse = blindBranchCollision
                        && obstruction.getBlock() instanceof FallingBlock;
                if (blindBranchCollision) {
                    if (!fallingBranchCollapse) {
                        stripStepsLeft = 0;
                    }
                    lavaReroute = null;
                }
                if (preserveRestoreTarget) {
                    // lastFace is the durable restoration destination, not merely the previous
                    // physical cell. A safety sidestep must not erase it before returnToSavedFace.
                    noteProgress();
                } else {
                    publishSynchronousMove(feet, retreat);
                }
                BotLog.danger(bot, "ore_dig_blocked_body_retreat",
                        "from", feet.toShortString(),
                        "to", retreat.toShortString(),
                        "blocked", blocked.toShortString(),
                        "block", Registries.BLOCK.getId(obstruction.getBlock()));
                if (fallingBranchCollapse) {
                    // The bot physically returned to a proven safe face. Marking that exact face as
                    // the cascade origin admits only finite visible side work or one observed dry
                    // escape step; it never grants an arbitrary walk through old territory.
                    boundaryRerouteOrigin = retreat.toImmutable();
                    rerouteBlindBranchAtObservedBoundary(
                            bot, world, "gravity", blocked);
                }
                return true;
            }
        }

        // No factual adjacent landing remains (for example after a restart).  Stop every movement
        // producer and break the visible body obstruction with normal survival tool selection.
        bot.getActionPack().stopAll();
        if (!blocked.equals(blockedBodyRecoveryTarget)
                || miner.target() == null || !miner.target().equals(blocked)) {
            miner.begin(bot, blocked);
            blockedBodyRecoveryTarget = blocked.toImmutable();
            BotLog.danger(bot, "ore_dig_blocked_body_clear",
                    "at", feet.toShortString(),
                    "blocked", blocked.toShortString(),
                    "block", Registries.BLOCK.getId(obstruction.getBlock()));
        }
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.FAILED) {
            blockedBodyRecoveryTarget = null;
            fail("ore_dig_blocked_body_clear_failed at=" + feet.toShortString()
                    + " blocked=" + blocked.toShortString()
                    + " reason=" + miner.failureReason());
        }
        noteProgress();
        return true;
    }

    /**
     * Proves that the occupied body cell belongs to the active finite blind branch before any
     * branch cursor is mutated. The live form is one exact forward cell from the last factual
     * face. The restart form is allowed only at a persisted boundary marker and may retreat one
     * exact cell through the known rear corridor. Every other task owner gets physical safety
     * recovery without branch reroute or terminal classification.
     */
    private boolean ownsActiveBlindBranchCollision(AIPlayerEntity bot,
                                                    BlockPos feet,
                                                    BlockPos retreat) {
        if (assistDetourActive()) {
            return false;
        }
        if (restoringFace || targetCount <= 0 || collected >= targetCount
                || targetOre != null || pendingPickupPos != null
                || activeTargetBreakPos != null || !veinQueue.isEmpty()
                || bonusOre != null || stripDirIndex < 0
                || stripDirIndex >= STRIP_DIRS.length || stripStepsLeft <= 0
                || !bot.getActionPack().isPathExecutorIdle()
                || bot.isSubmergedInWater()
                || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)
                || lastFace == null || stripProgressPos == null) {
            return false;
        }
        Direction direction = STRIP_DIRS[stripDirIndex];
        boolean liveForwardCell = stripProgressPos.equals(lastFace)
                && feet.equals(lastFace.offset(direction))
                && retreat.equals(lastFace);
        boolean resumedBoundaryCell = boundaryRerouteOrigin != null
                && boundaryRerouteOrigin.equals(feet)
                && stripProgressPos.equals(feet)
                && lastFace.equals(feet)
                && retreat.equals(feet.offset(direction.getOpposite()));
        return liveForwardCell || resumedBoundaryCell;
    }

    private BlockPos findBlockedBodyRetreat(AIPlayerEntity bot,
                                            ServerWorld world,
                                            BlockPos feet) {
        // A gravity block can put the eye inside collision geometry, making every ordinary rear
        // ray fail. The durable lastFace is still one exact factual cell even when pickup/target
        // ownership means this is not a blind-branch transaction. It may bypass only line of
        // sight; stepToStandable still performs collision, support, fluid and entity checks, and
        // recoverBlockedBody separately decides whether the branch cursor may be mutated.
        if (lastFace != null && lastFace.getY() == feet.getY()
                && Math.abs(lastFace.getX() - feet.getX())
                + Math.abs(lastFace.getZ() - feet.getZ()) == 1) {
            return lastFace.toImmutable();
        }
        // A resumed boundary can own the geometric rear while lastFace is the occupied face.
        // Keep this narrower branch-only identity for that checkpoint shape.
        if (lastFace != null && ownsActiveBlindBranchCollision(bot, feet, lastFace)) {
            return lastFace.toImmutable();
        }
        if (stripDirIndex >= 0 && stripDirIndex < STRIP_DIRS.length) {
            BlockPos ownedRear = feet.offset(STRIP_DIRS[stripDirIndex].getOpposite());
            if (ownsActiveBlindBranchCollision(bot, feet, ownedRear)) {
                return ownedRear.toImmutable();
            }
        }
        if (isAdjacentDryLanding(bot, world, feet, lastFace)) {
            return lastFace.toImmutable();
        }
        if (stripDirIndex >= 0 && stripDirIndex < STRIP_DIRS.length) {
            BlockPos rear = feet.offset(STRIP_DIRS[stripDirIndex].getOpposite());
            if (isAdjacentDryLanding(bot, world, feet, rear)) {
                return rear.toImmutable();
            }
        }
        for (Direction direction : STRIP_DIRS) {
            BlockPos candidate = feet.offset(direction);
            if (isAdjacentDryLanding(bot, world, feet, candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static boolean isAdjacentDryLanding(AIPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos feet,
                                                BlockPos candidate) {
        if (candidate == null || candidate.getY() != feet.getY()) {
            return false;
        }
        int dx = Math.abs(candidate.getX() - feet.getX());
        int dz = Math.abs(candidate.getZ() - feet.getZ());
        if (Math.max(dx, dz) != 1) {
            return false;
        }
        if (!canObserveWorldState(bot, candidate)
                || !canObserveWorldState(bot, candidate.up())
                || !ObservableWorldQuery.canObserveBlock(bot, candidate.down())) {
            return false;
        }
        Standability.clearCache();
        return world.getFluidState(candidate).isEmpty()
                && world.getFluidState(candidate.up()).isEmpty()
                && Standability.isStandable(world, candidate);
    }

    private static BlockPos firstBodyCollision(ServerWorld world, BlockPos feet) {
        BlockPos head = feet.up();
        if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
            return head.toImmutable();
        }
        if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
            return feet.toImmutable();
        }
        return null;
    }

    // Horizontal strip-mine: dig a straight tunnel in the current direction, exposing new ore faces
    // (each cell advanced, the next round's nearestOre will pick up new ore on both sides of the
    // tunnel); if a whole segment (STRIP_SEGMENT) is dug with no ore found -> drop down one layer +
    // switch horizontal direction and continue. Much faster at finding ore than the old
    // "only change layer vertically" approach. Serves both as the fallback for "nothing nearby at
    // all" and as the deadlock-breaker for "found ore but it's all unreachable (skipped
    // STRIP_AFTER_SKIPS times in a row)" -- advancing into new territory also brings previously
    // unreachable ore into reach (real_armor testing: without forced strip-mining, it would lock
    // onto far ore and skip 372 times in place, mining only 9).
    private void stripMine(AIPlayerEntity bot, ServerWorld world) {
        Direction activeDirection = stripDirIndex < 0 ? null : STRIP_DIRS[stripDirIndex];
        BlockPos factualRear = publishStripProgress(bot, activeDirection);
        if (stripStepsLeft <= 0) {
            publishCompletedStripSuccessor(bot, activeDirection, factualRear);
            return;
        }
        // Long rare expeditions own a bounded lighting resource epoch. Once its 40 successful
        // placements are spent, or its carried reserve is empty at a dark placement boundary, the
        // task must stop before extending an unlit branch. GoalExecutor may service exactly one new
        // epoch without replacing this task's durable branch/hard-budget checkpoint.
        boolean darkLightingBoundary = stripStepsLeft % 10 == 0
                && world.getLightLevel(net.minecraft.world.LightType.BLOCK, bot.getBlockPos()) < 8;
        if (darkLightingBoundary) {
            var torchSlot = io.github.zoyluo.minecraftai.action.InventoryAction.findItem(
                    bot, net.minecraft.item.Items.TORCH);
            if (rareExpeditionBatch
                    && (torchPlacements >= MiningBudget.RARE_BATCH_TORCH_LIMIT || torchSlot.isEmpty())) {
                miner.cancel(bot);
                bot.getActionPack().stopAll();
                fail(resourceEpochFailureReason(torchPlacements, resourceEpoch));
                return;
            }
            if (torchSlot.isPresent()) {
                io.github.zoyluo.minecraftai.action.InventoryAction.equipFromSlot(bot, torchSlot.getAsInt());
                ActionResult placement = io.github.zoyluo.minecraftai.action.BuildAction.placeBlockAt(
                        bot, bot.getBlockPos());
                if (!placement.isFailed()) {
                    if (rareExpeditionBatch) {
                        torchPlacements++;
                    }
                    BotLog.action(bot, "ore_dig_torch", "pos", bot.getBlockPos().toShortString());
                } else if (rareExpeditionBatch) {
                    fail("ore_dig_torch_placement_failed:" + placement.reason());
                    return;
                }
            }
        }
        // A full inventory can move the active channel pick to offhand while promoting a torch.
        // Rebind from the real BlockMiner target before its next tick; the old selected slot may
        // now contain the torch and is not a valid restoration identity.
        restoreActiveChannelTool(bot, world, miner);
        Direction dir = STRIP_DIRS[stripDirIndex];
        digTowardStep(bot, world, bot.getBlockPos().offset(dir, 2),
                TunnelIntent.BLIND_BRANCH, factualRear); // Reuses the digging primitive: mine the foot cell + head cell -> walk in
    }

    /**
     * Closes a finished strip edge in the same transaction that published its final physical step.
     * The final step may arrive through the scan-paced strip loop, pending-walk preflight, or a
     * pause boundary; all three must preserve the same exact corner identity. At the successor
     * face, the geometric reverse is fresh territory rather than the crossed old edge (for
     * north -> east, the factual rear is south while west remains untried). A one-step same-origin
     * escape satisfies the same invariant. Only zero-movement closures lack a factual rear and
     * publish a marker-free successor.
     */
    private void publishCompletedStripSuccessor(AIPlayerEntity bot,
                                                Direction completedDirection,
                                                BlockPos factualRear) {
        boolean factualCorner = completedDirection != null
                && factualRear != null
                && factualRear.equals(
                bot.getBlockPos().offset(completedDirection.getOpposite()));
        publishStripSuccessor(bot, factualRear, factualCorner);
    }

    /**
     * Publishes the next square-spiral edge at the current factual face. A physical final step,
     * including a gravity-interrupted edge, carries its exact crossed rear into the perpendicular
     * successor. That one-cell proof lets an immediately blocked successor distinguish the old
     * tunnel from its still-fresh geometric reverse without granting movement through either.
     */
    private void publishStripSuccessor(AIPlayerEntity bot,
                                       BlockPos factualRear,
                                       boolean preserveFactualCorner) {
        clearStripMovementOwnership();
        boundaryRerouteOrigin = null;
        // P4 (design 5.3, off by default): the two static reads cost nothing while L1 is off, matching
        // the tickOpportunistic idiom above.
        boolean legChooserActive = MiningAssistRuntime.senseConfigured()
                && MiningAssistRuntime.config().explore().legChooser();
        if (stripDirIndex < 0) {
            stripDirIndex = legChooserActive ? legChooserAdapter.chooseInitialDirection(bot) : 0;
            stripLegIndex = 0;
            stripLegLength = STRIP_SEGMENT;
        } else {
            int clockwiseDir = (stripDirIndex + 1) % STRIP_DIRS.length;
            stripLegIndex++;
            // Square spiral: N48,E48,S96,W96,N144.... The old implementation forced a layer drop
            // after every leg, which at the diamond peak layer Y=-59 would immediately hit
            // MIN_Y=-60; the expanding spiral stays on the best layer and never returns to the
            // origin every four legs.
            int defaultLegLength = stripLegIndex % 2 == 0
                    ? Math.min(STRIP_SEGMENT * 8, stripLegLength + STRIP_SEGMENT)
                    : stripLegLength;
            if (legChooserActive) {
                OreDigLegChooserAdapter.StripTurnChoice turn =
                        legChooserAdapter.chooseTurn(bot, stripDirIndex, defaultLegLength);
                stripDirIndex = turn.dirIndex();
                stripLegLength = turn.legLength();
                if (turn.dirIndex() != clockwiseDir) {
                    // L1 never claims the old-leg proof for a turn it invented itself; the general
                    // shelter path re-verifies everything instead (design 5.3).
                    preserveFactualCorner = false;
                }
            } else {
                stripDirIndex = clockwiseDir;
                stripLegLength = defaultLegLength;
            }
        }
        stripStepsLeft = stripLegLength;
        stripProgressPos = bot.getBlockPos().toImmutable();
        if (preserveFactualCorner && factualRear != null) {
            boundaryRerouteOrigin = stripProgressPos;
            controlledStripRear = factualRear.toImmutable();
        }
        BotLog.action(bot, "ore_dig_branch_leg",
                "leg", stripLegIndex,
                "dir", STRIP_DIRS[stripDirIndex],
                "length", stripLegLength,
                "factual_corner", preserveFactualCorner);
    }

    /**
     * Commits factual movement along the active strip edge as one checkpoint transaction. The
     * exact rear of a one-block advance remains owned until the next factual move or branch-owner
     * change because clearing a solid feet/head column can take multiple ticks before its missing
     * support becomes observable. This stores only the crossed cell identity; recovery still
     * re-observes its complete survival geometry immediately before movement.
     */
    private BlockPos publishStripProgress(AIPlayerEntity bot, Direction activeDirection) {
        if (activeDirection == null || stripProgressPos == null) {
            clearStripMovementOwnership();
            return null;
        }
        BlockPos previousProgress = stripProgressPos;
        int moved = directionalProgress(previousProgress, bot.getBlockPos(), activeDirection);
        if (moved <= 0) {
            // The successor has not crossed its first cell yet. Preserve the perpendicular rear
            // proof for an immediate hostile pause; ordinary branch recovery deliberately accepts
            // only current-direction reverse and therefore must not reinterpret this identity.
            if (ownsFactualCornerRear(bot.getBlockPos(), activeDirection)) {
                return null;
            }
            return controlledStripRearFor(bot, activeDirection);
        }
        BlockPos factualRear = moved == 1
                && bot.getBlockPos().equals(previousProgress.offset(activeDirection))
                ? previousProgress.toImmutable() : null;
        clearPendingBlindAdvance();
        controlledStripRear = factualRear;
        stripStepsLeft = Math.max(0, stripStepsLeft - moved);
        BlockPos factualFace = bot.getBlockPos().toImmutable();
        stripProgressPos = factualFace;
        lastFace = factualFace;
        // A boundary turn remains part of the pre-move cursor until the physical advance commits.
        // Clear it alongside face/progress/steps so every observable checkpoint is internally
        // equivalent to uninterrupted execution.
        boundaryRerouteOrigin = null;
        // Expanding the dug face is itself valid progress. In strict mode, ore behind a wall isn't
        // visible, and digging hundreds of blocks in a row is a normal part of the search process;
        // if the watchdog were only fed when ore lands in the inventory, a second batch would be
        // wrongly killed by the 200-tick no-progress check while genuinely moving forward.
        noteProgress();
        return factualRear;
    }

    /**
     * Returns the retained rear only while the exact blind-strip owner still controls this face.
     * Mining the next feet/head blocks is allowed; every movement owner, target ledger, rescue,
     * direction change or geometric mismatch invalidates the proof before it can be used.
     */
    private BlockPos controlledStripRearFor(AIPlayerEntity bot, Direction activeDirection) {
        if (controlledStripRear == null) {
            return null;
        }
        BlockPos face = bot.getBlockPos();
        BlockPos next = activeDirection == null ? null : face.offset(activeDirection);
        BlockPos activeMine = miner.target();
        boolean branchMine = activeMine == null || next != null
                && (activeMine.equals(next) || activeMine.equals(next.up()));
        boolean valid = activeDirection != null
                && stripDirIndex >= 0 && stripDirIndex < STRIP_DIRS.length
                && STRIP_DIRS[stripDirIndex] == activeDirection
                && stripStepsLeft > 0
                && stripProgressPos != null && stripProgressPos.equals(face)
                && lastFace != null && lastFace.equals(face)
                && controlledStripRear.equals(face.offset(activeDirection.getOpposite()))
                && boundaryRerouteOrigin == null
                && !restoringFace && targetOre == null && pendingPickupPos == null
                && activeTargetBreakPos == null && veinQueue.isEmpty() && bonusOre == null
                && blockedBodyRecoveryTarget == null && branchMine
                && bot.getActionPack().isPathExecutorIdle()
                && bot.getActionPack().isWalkToIdle()
                && !bot.isSubmergedInWater()
                && !NavSafetyNet.INSTANCE.isWaterRescueActive(bot);
        if (!valid) {
            clearControlledStripRear();
            return null;
        }
        return controlledStripRear.toImmutable();
    }

    private void clearControlledStripRear() {
        controlledStripRear = null;
    }

    private void clearPendingBlindAdvance() {
        pendingBlindAdvance = null;
    }

    private void clearStripMovementOwnership() {
        clearControlledStripRear();
        clearPendingBlindAdvance();
    }

    /** Exact persisted geometry produced only by an unmarked normal leg's final physical step. */
    private boolean ownsFactualCornerRear(BlockPos face, Direction successor) {
        return face != null && successor != null
                && boundaryRerouteOrigin != null && boundaryRerouteOrigin.equals(face)
                && stripProgressPos != null && stripProgressPos.equals(face)
                && lastFace != null && lastFace.equals(face)
                && stripStepsLeft == stripLegLength
                && controlledStripRear != null
                && controlledStripRear.equals(face.offset(successor.rotateYClockwise()));
    }

    /**
     * Detects the sole transient shape in which the bot has physically reached the next blind
     * branch cell but the scan-paced cursor has not consumed that movement yet. The saved face is
     * intentionally kept at the exact rear during this window; every non-branch movement and
     * competing owner continues to publish the current position normally.
     */
    private PendingBlindAdvanceInspection inspectPendingBlindAdvance(AIPlayerEntity bot,
                                                                     BlockPos feet) {
        PendingBlindAdvance pending = pendingBlindAdvance;
        if (pending == null) {
            return new PendingBlindAdvanceInspection(PendingBlindAdvancePhase.NONE, null);
        }
        if (feet == null || restoringFace
                || stripDirIndex < 0 || stripDirIndex >= STRIP_DIRS.length
                || stripStepsLeft <= 0 || lastFace == null || stripProgressPos == null
                || targetOre != null || pendingPickupPos != null
                || activeTargetBreakPos != null || !veinQueue.isEmpty()
                || bonusOre != null
                || blockedBodyRecoveryTarget != null || miner.target() != null
                || !bot.getActionPack().isPathExecutorIdle()
                || bot.isSubmergedInWater()
                || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
            clearPendingBlindAdvance();
            return new PendingBlindAdvanceInspection(PendingBlindAdvancePhase.NONE, null);
        }
        Direction direction = STRIP_DIRS[stripDirIndex];
        boolean exactOwner = pending.direction() == direction
                && pending.rear().equals(lastFace)
                && pending.rear().equals(stripProgressPos)
                && pending.destination().equals(pending.rear().offset(direction));
        if (!exactOwner) {
            clearPendingBlindAdvance();
            return new PendingBlindAdvanceInspection(PendingBlindAdvancePhase.NONE, null);
        }
        if (feet.equals(pending.rear())) {
            if (!bot.getActionPack().isWalkToIdle()) {
                return new PendingBlindAdvanceInspection(
                        PendingBlindAdvancePhase.IN_FLIGHT_AT_REAR, pending.rear());
            }
            clearPendingBlindAdvance();
            return new PendingBlindAdvanceInspection(
                    PendingBlindAdvancePhase.RETRY_AT_REAR, pending.rear());
        }
        if (feet.equals(pending.destination())) {
            return new PendingBlindAdvanceInspection(
                    PendingBlindAdvancePhase.ARRIVED, pending.rear());
        }
        clearPendingBlindAdvance();
        return new PendingBlindAdvanceInspection(PendingBlindAdvancePhase.NONE, null);
    }

    private boolean canRetainUnpublishedBlindAdvance(AIPlayerEntity bot,
                                                       ServerWorld world,
                                                       BlockPos feet,
                                                       BlockPos exactRear) {
        if (exactRear == null || stripDirIndex < 0 || stripDirIndex >= STRIP_DIRS.length) {
            return false;
        }
        Direction rearDirection = STRIP_DIRS[stripDirIndex].getOpposite();
        return exactRear.equals(feet.offset(rearDirection))
                && isObservedSafeOpenEscapeCorridor(bot, world, feet, rearDirection);
    }

    static void restoreActiveChannelTool(AIPlayerEntity bot,
                                         ServerWorld world,
                                         BlockMiner activeMiner) {
        if (activeMiner.target() != null) {
            ToolSelector.equipMiningChannelTool(
                    bot, world.getBlockState(activeMiner.target()));
        }
    }

    private static int directionalProgress(BlockPos from, BlockPos to, Direction direction) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        return Math.max(0, dx * direction.getOffsetX() + dz * direction.getOffsetZ());
    }

    @Override
    public Map<String, String> checkpoint() {
        if (invalidCheckpoint || cursorOrigin == null) {
            return Map.of();
        }
        BlockPos origin = cursorOrigin == null
                ? (lastFace == null ? BlockPos.ORIGIN : lastFace)
                : cursorOrigin;
        BlockPos face = detourPublishedFace(lastFace == null ? origin : lastFace);
        boolean committed = state == TaskState.COMPLETED;
        BlockPos durableControlledRear = committed
                ? null : checkpointControlledStripRear(face);
        Map<BlockPos, BlockPos> durableRememberedHighWorkPoses = committed
                ? Map.of() : checkpointRememberedHighWorkPoses(face);
        int batches = completedBatches + (committed ? 1 : 0);
        // The timeout branch observes max+1 because AbstractTask increments elapsed before onTick.
        // Persist the exhausted boundary itself so GoalExecutor cannot turn a hard timeout into a
        // fresh budget merely because the terminal checkpoint would otherwise fail validation.
        int durableBudget = committed ? 0 : Math.min(totalBudget(), maxElapsed);
        // A running/paused/cancelled checkpoint is an exact process-restart boundary and must keep
        // its already-spent stall window. A failed attempt that physically delivered at least one
        // target item publishes a successor-attempt boundary instead: only the transient
        // no-progress clock is rebased. The hard budget, branch cursor and pickup/break ledgers
        // below remain durable, so retry cannot manufacture time or erase physical obligations.
        boolean partialDeliverySuccessor = state == TaskState.FAILED && collected > 0;
        int durableLastProgress = committed ? 0
                : partialDeliverySuccessor ? durableBudget : lastProgressBudget;
        int durableDelivered = committed ? 0
                : Math.min(budgetTargetCount, deliveredAtStart + collected);
        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                origin,
                face,
                stripDirIndex,
                stripLegIndex,
                stripStepsLeft,
                stripLegLength,
                batches);
        OreDigCheckpoint live = new OreDigCheckpoint(
                CHECKPOINT_SCHEMA,
                budgetTargetCount,
                !committed,
                durableDelivered,
                rareMissionTarget,
                !committed && inventoryServiceUsed,
                MiningBudget.RARE_BATCH_TORCH_LIMIT,
                committed ? 0 : torchPlacements,
                committed ? 0 : resourceEpoch,
                cursor,
                oreFingerprint,
                durableBudget,
                durableLastProgress,
                durableControlledRear,
                committed ? null : boundaryRerouteOrigin,
                committed ? null : pendingPickupPos,
                committed ? null : pendingPickupLastSeenPos,
                committed || pendingPickupPos == null ? -1 : pendingPickupInventory,
                committed || pendingPickupPos == null ? -1 : pendingPickupStarted,
                committed || pendingPickupPos == null ? -1 : pendingPickupGainTick,
                committed ? null : activeTargetBreakPos,
                committed || activeTargetBreakPos == null ? -1 : activeTargetBreakInventory,
                durableRememberedHighWorkPoses);
        Map<String, String> encoded = live.encode();
        return OreDigCheckpoint.decode(encoded, targetOres).isPresent() ? encoded : Map.of();
    }

    /** Keeps only the structurally exact branch-owned rear identity; no safety is cached. */
    private BlockPos checkpointControlledStripRear(BlockPos face) {
        if (controlledStripRear == null || face == null
                || assistDetourActive()
                || stripDirIndex < 0 || stripDirIndex >= STRIP_DIRS.length
                || stripStepsLeft <= 0
                || targetOre != null || pendingPickupPos != null
                || activeTargetBreakPos != null || !veinQueue.isEmpty()
                || bonusOre != null || blockedBodyRecoveryTarget != null) {
            return null;
        }
        Direction forward = STRIP_DIRS[stripDirIndex];
        boolean ordinaryRear = boundaryRerouteOrigin == null
                && controlledStripRear.equals(face.offset(forward.getOpposite()));
        boolean factualCornerRear = ownsFactualCornerRear(face, forward);
        if (!ordinaryRear && !factualCornerRear) {
            return null;
        }
        return controlledStripRear.toImmutable();
    }

    /** Keeps only nearby structurally exact facts, so stale observations cannot poison a restart. */
    private Map<BlockPos, BlockPos> checkpointRememberedHighWorkPoses(BlockPos face) {
        if (face == null || rememberedHighWorkPoses.isEmpty()) {
            return Map.of();
        }
        Map<BlockPos, BlockPos> durable = new java.util.LinkedHashMap<>();
        rememberedHighWorkPoses.entrySet().stream()
                .sorted(java.util.Comparator.comparing(
                        entry -> encodeCheckpointPos(entry.getKey())))
                .filter(entry -> isExactHighWorkPose(entry.getKey(), entry.getValue()))
                .filter(entry -> isRememberedHighWorkPoseNearFace(face, entry.getKey()))
                .limit(VEIN_CAP)
                .forEach(entry -> durable.put(
                        entry.getKey().toImmutable(), entry.getValue().toImmutable()));
        return Map.copyOf(durable);
    }

    private static boolean isRememberedHighWorkPoseNearFace(BlockPos face, BlockPos ore) {
        return face != null && ore != null
                && Math.abs((long) ore.getX() - face.getX()) <= SCAN_RADIUS * 2L
                && Math.abs((long) ore.getY() - face.getY()) <= VERTICAL_SCAN * 2L
                && Math.abs((long) ore.getZ() - face.getZ()) <= SCAN_RADIUS * 2L;
    }

    /** Stable family fingerprint prevents a coal/iron replan from consuming a diamond cursor. */
    public static String oreFingerprint(Set<Block> ores) {
        Set<Block> expanded = ores == null || ores.isEmpty()
                ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(ores);
        return expanded.stream()
                .map(Registries.BLOCK::getId)
                .map(Object::toString)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Strictly inspects a persisted OreDig checkpoint without trusting its family fingerprint.
     * The fingerprint is first resolved through the block registry, then the full task codec
     * verifies that every persisted field is present, bounded, and internally consistent.
     */
    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> checkpoint) {
        return inspectCheckpoint(checkpoint, null);
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> checkpoint,
                                                              int expectedRareMissionTarget) {
        return inspectCheckpoint(checkpoint, Integer.valueOf(expectedRareMissionTarget));
    }

    private static Optional<RestoreMetadata> inspectCheckpoint(
            Map<String, String> checkpoint,
            Integer expectedRareMissionTarget) {
        return checkpointOres(checkpoint)
                .flatMap(ores -> OreDigCheckpoint.decode(
                                checkpoint, ores, expectedRareMissionTarget)
                        .map(restored -> new RestoreMetadata(
                                ores,
                                restored.targetCount(),
                                restored.delivered(),
                                restored.rareMissionTarget(),
                                restored.batchOpen(),
                                restored.cursor(),
                                restored.budgetUsed(),
                                restored.torchLimit(),
                                restored.torchPlacements(),
                                restored.resourceEpoch(),
                                restored.inventoryServiceUsed())));
    }

    public record RestoreMetadata(Set<Block> ores,
                                  int targetCount,
                                  int delivered,
                                  int rareMissionTarget,
                                  boolean batchOpen,
                                  MiningCursor cursor,
                                  int budgetUsed,
                                  int torchLimit,
                                  int torchPlacements,
                                  int resourceEpoch,
                                  boolean inventoryServiceUsed) {
        public RestoreMetadata {
            ores = ores == null ? Set.of() : Set.copyOf(ores);
        }

        public int remainingCount() {
            return Math.max(0, targetCount - delivered);
        }

        /** Both persisted GoalStep(8) and replanned remainder GoalStep(4) name the same open batch. */
        public boolean acceptsStepTarget(int stepTarget) {
            return stepTarget == targetCount || stepTarget == remainingCount();
        }
    }

    private static Optional<Set<Block>> checkpointOres(Map<String, String> checkpoint) {
        if (checkpoint == null) {
            return Optional.empty();
        }
        String fingerprint = checkpoint.get("ore_fingerprint");
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        Set<Block> ores = new java.util.LinkedHashSet<>();
        for (String encoded : fingerprint.split(",", -1)) {
            if (encoded.isBlank()) {
                return Optional.empty();
            }
            try {
                Block block = Registries.BLOCK.getOptionalValue(Identifier.of(encoded)).orElse(null);
                if (block == null) {
                    return Optional.empty();
                }
                ores.add(block);
            } catch (RuntimeException invalidIdentifier) {
                return Optional.empty();
            }
        }
        return ores.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(ores));
    }

    /**
     * Advances one bounded resource epoch — the per-batch retry or a mission-margin epoch —
     * without changing any physical or time cursor. This codec only proves the successor epoch
     * stays inside the mission-derived capacity; the margin ledger itself (whether the mission may
     * still pay for an epoch beyond the per-batch retry) is owned by GoalExecutor's checkpoint.
     */
    public static Optional<Map<String, String>> advanceResourceEpoch(Map<String, String> checkpoint) {
        return checkpointOres(checkpoint).flatMap(ores ->
                OreDigCheckpoint.decode(checkpoint, ores)
                        .filter(restored -> restored.batchOpen()
                                && isRareExpeditionBatch(ores, restored.rareMissionTarget())
                                && restored.resourceEpoch() + 1
                                < rareMissionResourceEpochCapacity(
                                restored.rareMissionTarget()))
                        .map(restored -> restored.withTorchPlacements(0)
                                .withResourceEpoch(restored.resourceEpoch() + 1)
                                .encode()));
    }

    /** Debits the one per-batch sealed inventory service without changing any OreDig cursor. */
    public static Optional<Map<String, String>> debitInventoryService(
            Map<String, String> checkpoint) {
        return checkpointOres(checkpoint).flatMap(ores ->
                OreDigCheckpoint.decode(checkpoint, ores)
                        .filter(restored -> restored.batchOpen()
                                && isRareExpeditionBatch(ores, restored.rareMissionTarget())
                                && !restored.inventoryServiceUsed())
                        .map(restored -> restored.withInventoryServiceUsed(true).encode()));
    }

    /** Debits the first ordinary/small-rare capacity hand-off owned by this exact open batch. */
    public static Optional<Map<String, String>> debitCapacityHandoff(
            Map<String, String> checkpoint) {
        return debitCapacityHandoff(checkpoint, -1);
    }

    /**
     * Advances a capacity hand-off only after the same open batch has physically delivered a new
     * target item. The previous delivery watermark is owned by GoalExecutor's capacity-parent
     * transaction; keeping it outside this schema preserves the exact OreDig cursor and physical
     * pickup/break ledgers. Channel-tool resupply deliberately does not use this overload and
     * remains a single per-batch repair.
     */
    public static Optional<Map<String, String>> debitCapacityHandoff(
            Map<String, String> checkpoint,
            int previousServicedDelivered) {
        if (previousServicedDelivered < -1) {
            return Optional.empty();
        }
        if (previousServicedDelivered < 0) {
            return debitOrdinaryAuxiliaryService(checkpoint);
        }
        return checkpointOres(checkpoint).flatMap(ores ->
                OreDigCheckpoint.decode(checkpoint, ores, 0)
                        .filter(restored -> restored.batchOpen()
                                && restored.rareMissionTarget() == 0
                                && restored.inventoryServiceUsed()
                                && restored.delivered() > previousServicedDelivered)
                        // The debit watermark is persisted by GoalExecutor. Returning the exact
                        // validated bytes here prevents a repeat service from refreshing any task
                        // budget, cursor or physical obligation.
                        .map(restored -> Map.copyOf(checkpoint)));
    }

    /**
     * Advances a capacity hand-off after either a new target delivery or factual branch movement.
     * GoalExecutor owns the persisted service-count cap and the previous work-face watermark; this
     * codec boundary only proves that the exact open ordinary batch still owns the same debited
     * cursor and that one of those two monotonic witnesses advanced.
     */
    public static Optional<Map<String, String>> debitCapacityHandoff(
            Map<String, String> checkpoint,
            int previousServicedDelivered,
            BlockPos previousServicedFace) {
        if (previousServicedDelivered < -1) {
            return Optional.empty();
        }
        if (previousServicedDelivered < 0) {
            return previousServicedFace == null
                    ? debitOrdinaryAuxiliaryService(checkpoint) : Optional.empty();
        }
        if (previousServicedFace == null) {
            return Optional.empty();
        }
        return checkpointOres(checkpoint).flatMap(ores ->
                OreDigCheckpoint.decode(checkpoint, ores, 0)
                        .filter(restored -> restored.batchOpen()
                                && restored.rareMissionTarget() == 0
                                && restored.inventoryServiceUsed()
                                && (restored.delivered() > previousServicedDelivered
                                || !restored.cursor().face().equals(previousServicedFace)))
                        // GoalExecutor persists both watermarks and the bounded service count in
                        // the same mission snapshot. Preserve every OreDig byte here so the repeat
                        // cannot refresh its hard clock, cursor, or physical pickup obligation.
                        .map(restored -> Map.copyOf(checkpoint)));
    }

    /**
     * Debits the one ordinary-batch channel-tool resupply without changing any physical cursor.
     * Capacity hand-off and channel repair intentionally share one bit: an open batch gets at most
     * one auxiliary supply transaction of either kind across retries and process restarts.
     */
    public static Optional<Map<String, String>> debitChannelToolResupply(
            Map<String, String> checkpoint) {
        return debitOrdinaryAuxiliaryService(checkpoint);
    }

    private static Optional<Map<String, String>> debitOrdinaryAuxiliaryService(
            Map<String, String> checkpoint) {
        return checkpointOres(checkpoint).flatMap(ores ->
                OreDigCheckpoint.decode(checkpoint, ores, 0)
                        .filter(restored -> restored.batchOpen()
                                && restored.rareMissionTarget() == 0
                                && !restored.inventoryServiceUsed())
                        .map(restored -> restored.withInventoryServiceUsed(true).encode()));
    }

    public static String resourceEpochFailureReason(int placements, int epoch) {
        return "ore_dig_torch_epoch_exhausted:placed=" + placements + ":epoch=" + epoch;
    }

    private static boolean isRareExpeditionBatch(Set<Block> ores, int rareMissionTarget) {
        if (rareMissionTarget < MiningBudget.EXPEDITION_THRESHOLD) {
            return false;
        }
        return isRareOreFamily(ores);
    }

    /** Exclusive epoch bound for one batch: the per-batch pair plus the mission margin pool. */
    private static int rareMissionResourceEpochCapacity(int rareMissionTarget) {
        return MiningBudget.rareMissionResourceEpochCapacity(
                MiningBudget.rareMissionBatchCount(rareMissionTarget));
    }

    private static boolean isRareOreFamily(Set<Block> ores) {
        Set<Block> expanded = ores == null || ores.isEmpty()
                ? Set.of() : OreScan.expandOreFamilies(ores);
        return expanded.contains(Blocks.DIAMOND_ORE)
                || expanded.contains(Blocks.DEEPSLATE_DIAMOND_ORE)
                || expanded.contains(Blocks.EMERALD_ORE)
                || expanded.contains(Blocks.DEEPSLATE_EMERALD_ORE);
    }

    private static int maxElapsedForTarget(Set<Block> ores,
                                           int targetCount,
                                           int rareMissionTarget,
                                           int resourceEpoch) {
        if (isRareExpeditionBatch(ores, rareMissionTarget)) {
            return MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(
                    resourceEpoch, rareMissionResourceEpochCapacity(rareMissionTarget));
        }
        Set<Block> expanded = ores == null || ores.isEmpty()
                ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(ores);
        boolean rare = expanded.contains(Blocks.DIAMOND_ORE)
                || expanded.contains(Blocks.DEEPSLATE_DIAMOND_ORE)
                || expanded.contains(Blocks.EMERALD_ORE)
                || expanded.contains(Blocks.DEEPSLATE_EMERALD_ORE);
        int perTarget = rare ? 3000 : 700;
        return Math.max(MAX_ELAPSED_BASE, Math.max(1, targetCount) * perTarget);
    }

    private int totalBudget() {
        return budgetOffset + elapsed;
    }

    private void noteProgress() {
        lastProgressBudget = totalBudget();
    }

    private static String encodeCheckpointPos(BlockPos pos) {
        return BlockPosText.encodePos(pos);
    }

    private static Optional<BlockPos> decodeCheckpointPos(String value) {
        return BlockPosText.decodePos(value);
    }

    private static String encodeRememberedHighWorkPoses(Map<BlockPos, BlockPos> poses) {
        return poses.entrySet().stream()
                .sorted(java.util.Comparator.comparing(
                        entry -> encodeCheckpointPos(entry.getKey())))
                .map(entry -> encodeCheckpointPos(entry.getKey())
                        + "@" + encodeCheckpointPos(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Optional<Map<BlockPos, BlockPos>> decodeRememberedHighWorkPoses(String value) {
        if (value == null) {
            return Optional.of(Map.of());
        }
        if (value.isBlank()) {
            return Optional.empty();
        }
        String[] encodedEntries = value.split(";", -1);
        if (encodedEntries.length > VEIN_CAP) {
            return Optional.empty();
        }
        Map<BlockPos, BlockPos> decoded = new java.util.LinkedHashMap<>();
        for (String encodedEntry : encodedEntries) {
            String[] pair = encodedEntry.split("@", -1);
            if (pair.length != 2) {
                return Optional.empty();
            }
            BlockPos ore = decodeCheckpointPos(pair[0]).orElse(null);
            BlockPos pose = decodeCheckpointPos(pair[1]).orElse(null);
            if (ore == null || pose == null || decoded.put(ore, pose) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(Map.copyOf(decoded));
    }

    record OreDigCheckpoint(int taskSchema,
                            int targetCount,
                            boolean batchOpen,
                            int delivered,
                            int rareMissionTarget,
                            boolean inventoryServiceUsed,
                            int torchLimit,
                            int torchPlacements,
                            int resourceEpoch,
                            MiningCursor cursor,
                            String oreFingerprint,
                            int budgetUsed,
                            int lastProgressBudget,
                            BlockPos controlledStripRear,
                            BlockPos boundaryRerouteOrigin,
                            BlockPos pendingPickupPos,
                            BlockPos pendingPickupLastSeenPos,
                            int pendingPickupInventory,
                            int pendingPickupStartedBudget,
                            int pendingPickupGainBudget,
                            BlockPos activeBreakPos,
                            int activeBreakInventory,
                            Map<BlockPos, BlockPos> rememberedHighWorkPoses) {
        private static final Set<String> LEGACY_REQUIRED_KEYS = Set.of(
                "task_schema", "target_count", "batch_open", "budget_used",
                "last_progress_budget", "schema", "origin", "face", "direction", "leg",
                "steps_left", "leg_length", "batches", "ore_fingerprint",
                "pending_pickup_inventory", "pending_pickup_started_budget",
                "pickup_gain_budget", "active_break_inventory");
        private static final Set<String> LEGACY_ALLOWED_KEYS = Set.of(
                "task_schema", "target_count", "batch_open", "budget_used",
                "last_progress_budget", "schema", "origin", "face", "direction", "leg",
                "steps_left", "leg_length", "batches", "ore_fingerprint",
                "pending_pickup_pos", "pending_pickup_inventory",
                "pending_pickup_started_budget", "pickup_gain_budget",
                "active_break_pos", "active_break_inventory");
        private static final Set<String> RESOURCE_REQUIRED_KEYS =
                withResourceKeys(LEGACY_REQUIRED_KEYS);
        private static final Set<String> RESOURCE_ALLOWED_KEYS =
                withResourceKeys(LEGACY_ALLOWED_KEYS);
        private static final Set<String> MISSION_REQUIRED_KEYS =
                withMissionKeys(RESOURCE_REQUIRED_KEYS);
        private static final Set<String> MISSION_ALLOWED_KEYS =
                withPickupLastSeenKey(withMissionKeys(RESOURCE_ALLOWED_KEYS));
        private static final Set<String> REQUIRED_KEYS =
                withDeliveredKey(MISSION_REQUIRED_KEYS);
        private static final Set<String> ALLOWED_KEYS =
                withRememberedHighWorkPosesKey(
                        withControlledStripRearKey(
                                withBoundaryRerouteKey(withDeliveredKey(MISSION_ALLOWED_KEYS))));

        OreDigCheckpoint {
            controlledStripRear = controlledStripRear == null
                    ? null : controlledStripRear.toImmutable();
            boundaryRerouteOrigin = boundaryRerouteOrigin == null
                    ? null : boundaryRerouteOrigin.toImmutable();
            pendingPickupPos = pendingPickupPos == null ? null : pendingPickupPos.toImmutable();
            pendingPickupLastSeenPos = pendingPickupLastSeenPos == null
                    ? null : pendingPickupLastSeenPos.toImmutable();
            activeBreakPos = activeBreakPos == null ? null : activeBreakPos.toImmutable();
            if (rememberedHighWorkPoses == null || rememberedHighWorkPoses.isEmpty()) {
                rememberedHighWorkPoses = Map.of();
            } else {
                Map<BlockPos, BlockPos> immutable = new java.util.LinkedHashMap<>();
                for (Map.Entry<BlockPos, BlockPos> entry : rememberedHighWorkPoses.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        throw new IllegalArgumentException("null_remembered_high_work_pose");
                    }
                    immutable.put(entry.getKey().toImmutable(), entry.getValue().toImmutable());
                }
                rememberedHighWorkPoses = Map.copyOf(immutable);
            }
        }

        /** Compatibility constructor for deterministic fixtures created before the optional key. */
        OreDigCheckpoint(int taskSchema,
                         int targetCount,
                         boolean batchOpen,
                         int delivered,
                         int rareMissionTarget,
                         boolean inventoryServiceUsed,
                         int torchLimit,
                         int torchPlacements,
                         int resourceEpoch,
                         MiningCursor cursor,
                         String oreFingerprint,
                         int budgetUsed,
                         int lastProgressBudget,
                         BlockPos controlledStripRear,
                         BlockPos boundaryRerouteOrigin,
                         BlockPos pendingPickupPos,
                         BlockPos pendingPickupLastSeenPos,
                         int pendingPickupInventory,
                         int pendingPickupStartedBudget,
                         int pendingPickupGainBudget,
                         BlockPos activeBreakPos,
                         int activeBreakInventory) {
            this(taskSchema, targetCount, batchOpen, delivered, rareMissionTarget,
                    inventoryServiceUsed, torchLimit, torchPlacements, resourceEpoch, cursor,
                    oreFingerprint, budgetUsed, lastProgressBudget, controlledStripRear,
                    boundaryRerouteOrigin, pendingPickupPos, pendingPickupLastSeenPos,
                    pendingPickupInventory, pendingPickupStartedBudget, pendingPickupGainBudget,
                    activeBreakPos, activeBreakInventory, Map.of());
        }

        /** Derives a copy with only {@link #resourceEpoch} replaced; every other field is kept. */
        private OreDigCheckpoint withResourceEpoch(int resourceEpoch) {
            return new OreDigCheckpoint(taskSchema, targetCount, batchOpen, delivered,
                    rareMissionTarget, inventoryServiceUsed, torchLimit, torchPlacements,
                    resourceEpoch, cursor, oreFingerprint, budgetUsed, lastProgressBudget,
                    controlledStripRear, boundaryRerouteOrigin, pendingPickupPos,
                    pendingPickupLastSeenPos, pendingPickupInventory, pendingPickupStartedBudget,
                    pendingPickupGainBudget, activeBreakPos, activeBreakInventory,
                    rememberedHighWorkPoses);
        }

        /** Derives a copy with only {@link #torchPlacements} replaced; every other field is kept. */
        private OreDigCheckpoint withTorchPlacements(int torchPlacements) {
            return new OreDigCheckpoint(taskSchema, targetCount, batchOpen, delivered,
                    rareMissionTarget, inventoryServiceUsed, torchLimit, torchPlacements,
                    resourceEpoch, cursor, oreFingerprint, budgetUsed, lastProgressBudget,
                    controlledStripRear, boundaryRerouteOrigin, pendingPickupPos,
                    pendingPickupLastSeenPos, pendingPickupInventory, pendingPickupStartedBudget,
                    pendingPickupGainBudget, activeBreakPos, activeBreakInventory,
                    rememberedHighWorkPoses);
        }

        /**
         * Derives a copy with only {@link #inventoryServiceUsed} replaced; every other field is
         * kept.
         */
        private OreDigCheckpoint withInventoryServiceUsed(boolean inventoryServiceUsed) {
            return new OreDigCheckpoint(taskSchema, targetCount, batchOpen, delivered,
                    rareMissionTarget, inventoryServiceUsed, torchLimit, torchPlacements,
                    resourceEpoch, cursor, oreFingerprint, budgetUsed, lastProgressBudget,
                    controlledStripRear, boundaryRerouteOrigin, pendingPickupPos,
                    pendingPickupLastSeenPos, pendingPickupInventory, pendingPickupStartedBudget,
                    pendingPickupGainBudget, activeBreakPos, activeBreakInventory,
                    rememberedHighWorkPoses);
        }

        Map<String, String> encode() {
            Map<String, String> values = new java.util.LinkedHashMap<>(cursor.encode());
            values.put("task_schema", String.valueOf(taskSchema));
            values.put("target_count", String.valueOf(targetCount));
            values.put("batch_open", String.valueOf(batchOpen));
            values.put("delivered", String.valueOf(delivered));
            values.put("rare_mission_target", String.valueOf(rareMissionTarget));
            values.put("inventory_service_used", String.valueOf(inventoryServiceUsed));
            values.put("torch_limit", String.valueOf(torchLimit));
            values.put("torch_placements", String.valueOf(torchPlacements));
            values.put("resource_epoch", String.valueOf(resourceEpoch));
            values.put("budget_used", String.valueOf(budgetUsed));
            values.put("last_progress_budget", String.valueOf(lastProgressBudget));
            values.put("ore_fingerprint", oreFingerprint);
            values.put("pending_pickup_inventory", String.valueOf(pendingPickupInventory));
            values.put("pending_pickup_started_budget", String.valueOf(pendingPickupStartedBudget));
            values.put("pickup_gain_budget", String.valueOf(pendingPickupGainBudget));
            values.put("active_break_inventory", String.valueOf(activeBreakInventory));
            if (controlledStripRear != null) {
                values.put("controlled_strip_rear",
                        encodeCheckpointPos(controlledStripRear));
            }
            if (boundaryRerouteOrigin != null) {
                values.put("boundary_reroute_origin",
                        encodeCheckpointPos(boundaryRerouteOrigin));
            }
            if (pendingPickupPos != null) {
                values.put("pending_pickup_pos", encodeCheckpointPos(pendingPickupPos));
            }
            if (pendingPickupLastSeenPos != null
                    && !pendingPickupLastSeenPos.equals(pendingPickupPos)) {
                values.put("pending_pickup_last_seen_pos",
                        encodeCheckpointPos(pendingPickupLastSeenPos));
            }
            if (activeBreakPos != null) {
                values.put("active_break_pos", encodeCheckpointPos(activeBreakPos));
            }
            if (!rememberedHighWorkPoses.isEmpty()) {
                values.put("remembered_high_work_poses",
                        encodeRememberedHighWorkPoses(rememberedHighWorkPoses));
            }
            return Map.copyOf(values);
        }

        static Optional<OreDigCheckpoint> decode(Map<String, String> values, Set<Block> ores) {
            return decode(values, ores, null);
        }

        static Optional<OreDigCheckpoint> decode(Map<String, String> values,
                                                 Set<Block> ores,
                                                 Integer expectedRareMissionTarget) {
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            try {
                int taskSchema = requiredInt(values, "task_schema");
                Set<String> requiredKeys = switch (taskSchema) {
                    case LEGACY_CHECKPOINT_SCHEMA -> LEGACY_REQUIRED_KEYS;
                    case RESOURCE_EPOCH_CHECKPOINT_SCHEMA -> RESOURCE_REQUIRED_KEYS;
                    case MISSION_CHECKPOINT_SCHEMA -> MISSION_REQUIRED_KEYS;
                    case CHECKPOINT_SCHEMA -> REQUIRED_KEYS;
                    default -> Set.of();
                };
                Set<String> allowedKeys = switch (taskSchema) {
                    case LEGACY_CHECKPOINT_SCHEMA -> LEGACY_ALLOWED_KEYS;
                    case RESOURCE_EPOCH_CHECKPOINT_SCHEMA -> RESOURCE_ALLOWED_KEYS;
                    case MISSION_CHECKPOINT_SCHEMA -> MISSION_ALLOWED_KEYS;
                    case CHECKPOINT_SCHEMA -> ALLOWED_KEYS;
                    default -> Set.of();
                };
                if (!values.keySet().containsAll(requiredKeys)
                        || !allowedKeys.containsAll(values.keySet())) {
                    return Optional.empty();
                }
                int targetCount = requiredInt(values, "target_count");
                boolean batchOpen = strictBoolean(values, "batch_open");
                int delivered = taskSchema == CHECKPOINT_SCHEMA
                        ? requiredInt(values, "delivered") : 0;
                int rareMissionTarget;
                boolean inventoryServiceUsed;
                if (taskSchema >= MISSION_CHECKPOINT_SCHEMA) {
                    rareMissionTarget = requiredInt(values, "rare_mission_target");
                    inventoryServiceUsed = strictBoolean(values, "inventory_service_used");
                } else {
                    if (expectedRareMissionTarget == null) {
                        return Optional.empty();
                    }
                    rareMissionTarget = expectedRareMissionTarget;
                    inventoryServiceUsed = false;
                }
                int torchLimit = MiningBudget.RARE_BATCH_TORCH_LIMIT;
                int torchPlacements = taskSchema == LEGACY_CHECKPOINT_SCHEMA
                        ? (batchOpen && rareMissionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                        ? MiningBudget.RARE_BATCH_TORCH_LIMIT : 0)
                        : requiredInt(values, "torch_placements");
                int resourceEpoch = taskSchema == LEGACY_CHECKPOINT_SCHEMA
                        ? 0 : requiredInt(values, "resource_epoch");
                if (taskSchema != LEGACY_CHECKPOINT_SCHEMA) {
                    torchLimit = requiredInt(values, "torch_limit");
                }
                int budget = requiredInt(values, "budget_used");
                int lastProgress = requiredInt(values, "last_progress_budget");
                String fingerprint = required(values, "ore_fingerprint");
                int cursorSchema = requiredInt(values, "schema");
                BlockPos origin = decodeCheckpointPos(required(values, "origin")).orElse(null);
                BlockPos face = decodeCheckpointPos(required(values, "face")).orElse(null);
                int direction = requiredInt(values, "direction");
                int leg = requiredInt(values, "leg");
                int stepsLeft = requiredInt(values, "steps_left");
                int legLength = requiredInt(values, "leg_length");
                int batches = requiredInt(values, "batches");
                BlockPos controlledStripRear = taskSchema == CHECKPOINT_SCHEMA
                        ? optionalPos(values, "controlled_strip_rear") : null;
                BlockPos boundaryRerouteOrigin = taskSchema == CHECKPOINT_SCHEMA
                        ? optionalPos(values, "boundary_reroute_origin") : null;
                BlockPos pending = optionalPos(values, "pending_pickup_pos");
                BlockPos pendingLastSeen = taskSchema >= MISSION_CHECKPOINT_SCHEMA
                        ? optionalPos(values, "pending_pickup_last_seen_pos") : null;
                if (pending != null && pendingLastSeen == null) {
                    // Optional schema-3 extension: older checkpoints resume from the durable
                    // break cell until a visible moving ItemEntity publishes a newer position.
                    pendingLastSeen = pending;
                }
                int pendingInventory = requiredInt(values, "pending_pickup_inventory");
                int pendingStarted = requiredInt(values, "pending_pickup_started_budget");
                int pendingGain = requiredInt(values, "pickup_gain_budget");
                BlockPos activeBreak = optionalPos(values, "active_break_pos");
                int activeBreakInventory = requiredInt(values, "active_break_inventory");
                Map<BlockPos, BlockPos> rememberedHighWorkPoses =
                        taskSchema == CHECKPOINT_SCHEMA
                                ? decodeRememberedHighWorkPoses(
                                values.get("remembered_high_work_poses")).orElseThrow()
                                : Map.of();

                int maxBudget = maxElapsedForTarget(
                        ores, targetCount, rareMissionTarget, resourceEpoch);
                boolean cursorShape = cursorSchema == MiningCursor.CURRENT_SCHEMA
                        && origin != null && face != null
                        && direction >= -1 && direction < STRIP_DIRS.length
                        && leg >= 0 && leg <= MAX_CURSOR_LEGS
                        && legLength >= STRIP_SEGMENT && legLength <= STRIP_SEGMENT * 8
                        && legLength % STRIP_SEGMENT == 0
                        && stepsLeft >= 0 && stepsLeft <= legLength
                        && batches >= 0 && batches <= MAX_CHECKPOINT_TARGET_COUNT;
                boolean pendingPair = (pending == null)
                        ? pendingInventory == -1 && pendingStarted == -1 && pendingGain == -1
                        : pendingInventory >= 0 && pendingInventory <= 4096
                        && pendingStarted >= 0 && pendingStarted <= budget
                        && (pendingGain == -1
                        || pendingGain >= pendingStarted && pendingGain <= budget);
                boolean pendingLastSeenPair = (pending == null) == (pendingLastSeen == null)
                        && (pendingLastSeen == null
                        || Math.abs((long) pendingLastSeen.getX() - pending.getX())
                        <= TARGET_DROP_LAST_SEEN_RANGE
                        && Math.abs((long) pendingLastSeen.getY() - pending.getY())
                        <= TARGET_DROP_LAST_SEEN_RANGE
                        && Math.abs((long) pendingLastSeen.getZ() - pending.getZ())
                        <= TARGET_DROP_LAST_SEEN_RANGE);
                boolean activePair = (activeBreak == null)
                        ? activeBreakInventory == -1
                        : activeBreakInventory >= 0 && activeBreakInventory <= 4096;
                boolean rememberedHighWorkPoseShape = rememberedHighWorkPoses.size() <= VEIN_CAP
                        && (batchOpen || rememberedHighWorkPoses.isEmpty())
                        && rememberedHighWorkPoses.entrySet().stream().allMatch(entry ->
                        isExactHighWorkPose(entry.getKey(), entry.getValue())
                                && isRememberedHighWorkPoseNearFace(face, entry.getKey()));
                boolean boundaryRerouteShape = boundaryRerouteOrigin == null
                        || batchOpen && direction >= 0 && stepsLeft > 0
                        && boundaryRerouteOrigin.equals(face);
                boolean controlledStripRearShape = controlledStripRear == null
                        || batchOpen && direction >= 0 && stepsLeft > 0
                        && (boundaryRerouteOrigin == null
                        && controlledStripRear.equals(
                        face.offset(STRIP_DIRS[direction].getOpposite()))
                        || boundaryRerouteOrigin != null
                        && boundaryRerouteOrigin.equals(face)
                        && stepsLeft == legLength
                        && controlledStripRear.equals(
                        face.offset(STRIP_DIRS[direction].rotateYClockwise())))
                        && pending == null && activeBreak == null;
                boolean committedShape = batchOpen
                        || budget == 0 && lastProgress == 0
                        && pending == null && activeBreak == null;
                boolean deliveredShape = batchOpen
                        ? delivered >= 0 && delivered <= targetCount
                        : delivered == 0;
                boolean rareMissionShape = rareMissionTarget == 0
                        || rareMissionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                        && rareMissionTarget <= MAX_CHECKPOINT_TARGET_COUNT
                        && isRareOreFamily(ores);
                boolean rareExpedition = isRareExpeditionBatch(ores, rareMissionTarget);
                // Margin epochs raise the per-batch bound only by the mission-derived pool; the
                // non-rare branch below still pins ordinary batches to epoch zero.
                int epochCapacity = rareExpedition
                        ? rareMissionResourceEpochCapacity(rareMissionTarget)
                        : MiningBudget.RARE_RESOURCE_EPOCHS_PER_BATCH;
                if (taskSchema != CHECKPOINT_SCHEMA
                        && taskSchema != MISSION_CHECKPOINT_SCHEMA
                        && taskSchema != RESOURCE_EPOCH_CHECKPOINT_SCHEMA
                        && taskSchema != LEGACY_CHECKPOINT_SCHEMA
                        || targetCount < 1 || targetCount > MAX_CHECKPOINT_TARGET_COUNT
                        || !rareMissionShape
                        || expectedRareMissionTarget != null
                        && rareMissionTarget != expectedRareMissionTarget
                        || torchLimit != MiningBudget.RARE_BATCH_TORCH_LIMIT
                        || torchPlacements < 0 || torchPlacements > torchLimit
                        || resourceEpoch < 0 || resourceEpoch >= epochCapacity
                        || !OreDigTask.oreFingerprint(ores).equals(fingerprint)
                        || budget < 0 || budget > maxBudget
                        || lastProgress < 0 || lastProgress > budget
                        || !cursorShape || !pendingPair || !pendingLastSeenPair || !activePair
                        || !rememberedHighWorkPoseShape
                        || !boundaryRerouteShape || !controlledStripRearShape
                        || pending != null && activeBreak != null
                        || !committedShape
                        || !deliveredShape
                        // Schemas 1-3 never recorded how much an open batch already delivered to
                        // inventory. Assuming zero would duplicate coal/iron as well as rare output.
                        || taskSchema < CHECKPOINT_SCHEMA && batchOpen
                        || !rareExpedition && (torchPlacements != 0 || resourceEpoch != 0)
                        || !batchOpen && (torchPlacements != 0 || resourceEpoch != 0
                        || inventoryServiceUsed)) {
                    return Optional.empty();
                }
                MiningCursor cursor = new MiningCursor(cursorSchema, origin, face, direction, leg,
                        stepsLeft, legLength, batches);
                return Optional.of(new OreDigCheckpoint(CHECKPOINT_SCHEMA, targetCount, batchOpen,
                        delivered, rareMissionTarget, inventoryServiceUsed,
                        torchLimit, torchPlacements, resourceEpoch, cursor,
                        fingerprint, budget, lastProgress, controlledStripRear,
                        boundaryRerouteOrigin,
                        pending, pendingLastSeen,
                        pendingInventory,
                        pendingStarted, pendingGain, activeBreak, activeBreakInventory,
                        rememberedHighWorkPoses));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        private static Set<String> withResourceKeys(Set<String> base) {
            Set<String> keys = new java.util.HashSet<>(base);
            keys.add("torch_limit");
            keys.add("torch_placements");
            keys.add("resource_epoch");
            return Set.copyOf(keys);
        }

        private static Set<String> withMissionKeys(Set<String> base) {
            Set<String> keys = new java.util.HashSet<>(base);
            keys.add("rare_mission_target");
            keys.add("inventory_service_used");
            return Set.copyOf(keys);
        }

        private static Set<String> withPickupLastSeenKey(Set<String> base) {
            Set<String> keys = new java.util.HashSet<>(base);
            keys.add("pending_pickup_last_seen_pos");
            return Set.copyOf(keys);
        }

        private static Set<String> withDeliveredKey(Set<String> base) {
            Set<String> keys = new java.util.HashSet<>(base);
            keys.add("delivered");
            return Set.copyOf(keys);
        }

        private static Set<String> withBoundaryRerouteKey(Set<String> base) {
            Set<String> keys = new java.util.HashSet<>(base);
            keys.add("boundary_reroute_origin");
            return Set.copyOf(keys);
        }

        private static Set<String> withControlledStripRearKey(Set<String> base) {
            Set<String> keys = new java.util.HashSet<>(base);
            keys.add("controlled_strip_rear");
            return Set.copyOf(keys);
        }

        private static Set<String> withRememberedHighWorkPosesKey(Set<String> base) {
            Set<String> keys = new java.util.HashSet<>(base);
            keys.add("remembered_high_work_poses");
            return Set.copyOf(keys);
        }

        private static BlockPos optionalPos(Map<String, String> values, String key) {
            if (!values.containsKey(key)) {
                return null;
            }
            return decodeCheckpointPos(values.get(key)).orElseThrow();
        }

        private static int requiredInt(Map<String, String> values, String key) {
            return Integer.parseInt(required(values, key));
        }

        private static boolean strictBoolean(Map<String, String> values, String key) {
            return switch (required(values, key)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("invalid_boolean:" + key);
            };
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing_checkpoint_key:" + key);
            }
            return value;
        }
    }

    private void returnToSavedFace(AIPlayerEntity bot) {
        if (lastFace == null || bot.getBlockPos().equals(lastFace)) {
            bot.getActionPack().stopAll();
            restoringFace = false;
            stripProgressPos = bot.getBlockPos().toImmutable();
            lastFace = bot.getBlockPos().toImmutable();
            noteProgress();
            BotLog.task(bot, "ore_dig_face_restored", "face", lastFace.toShortString());
            return;
        }
        if (elapsed - restoreFaceStarted > RESTORE_FACE_LIMIT) {
            fail("ore_dig_restore_face_unreachable:" + lastFace.toShortString());
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult path = bot.getActionPack().startPathTo(
                    lastFace, protectedStoneLikeReserve);
            if (path.isFailed()) {
                path = bot.getActionPack().startDigPathTo(
                        lastFace, protectedStoneLikeReserve);
            }
            if (path.isFailed()) {
                BotLog.action(bot, "ore_dig_restore_face_retry",
                        "face", lastFace.toShortString(), "reason", path.reason());
            }
        }
    }

    // ── Ore vein: clear out the same-vein ore adjacent to the currently locked ore ──
    private boolean advanceVein(AIPlayerEntity bot, ServerWorld world) {
        if (veinQueue.isEmpty()) {
            return false;
        }
        BlockPos v = veinQueue.peekFirst();
        if (v == null) {
            veinQueue.pollFirst();
            return true;
        }
        boolean miningVein = miner.target() != null && miner.target().equals(v);
        if (miningVein) {
            // Preserve the same exact-once ordering as the primary target: the durable miner owns
            // the committed coordinate even when the broken cell's new sightline classifies it as
            // UNKNOWN. The drop-catch authorization still has to hold on every active tick.
            if (!passesTargetDropCommitGate(bot, world, v)) {
                return true;
            }
            BlockMiner.Status st = miner.tick(bot);
            if (st == BlockMiner.Status.DONE) {
                finishTargetBreak(bot, v, activeTargetBreakInventory);
                veinQueue.pollFirst();
                noteProgress();
            } else if (st == BlockMiner.Status.FAILED
                    && !failMissingMiningChannelTool(bot)) {
                clearActiveTargetBreak(v);
                forgetRememberedHighWorkPose(v);
                veinQueue.pollFirst();
            }
            return true;
        }
        OreScan.Observation veinState = OreScan.observeOre(bot, v, targetOres);
        if (veinState == OreScan.Observation.UNKNOWN) {
            // A queued vein is finite remembered work. Occlusion cannot dequeue it or promote its
            // active break into a target-drop debt.
            if (bot.getActionPack().isPathExecutorIdle()
                    && bot.getActionPack().isWalkToIdle()) {
                if (miner.target() != null) {
                    settleOwnedTunnelMine(bot, v,
                            TunnelIntent.TARGET_APPROACH, miner.target());
                } else if (!tryRememberedHighWorkPoseRoute(bot, world, v)) {
                    continueUnknownOwnerApproach(
                            bot, world, v, TunnelIntent.TARGET_APPROACH);
                }
            }
            return true;
        }
        if (veinState == OreScan.Observation.OBSERVED_GONE) {
            if (v != null && activeTargetBreakPos != null && activeTargetBreakPos.equals(v)) {
                finishTargetBreak(bot, v, activeTargetBreakInventory);
            } else {
                forgetRememberedHighWorkPose(v);
            }
            veinQueue.pollFirst();
            return true;
        }
        if (oreExcluded(bot, v)) {
            if (miner.target() != null && miner.target().equals(v)) {
                miner.cancel(bot);
            }
            clearActiveTargetBreak(v);
            forgetRememberedHighWorkPose(v);
            veinQueue.pollFirst();
            return true;
        }
        BlockPos veinFeet = bot.getBlockPos();
        if (v.getX() == veinFeet.getX() && v.getZ() == veinFeet.getZ()
                && v.getY() - veinFeet.getY() > MAX_TARGET_BREAK_DY
                && !withinReach(bot, v)) {
            // A queued vertical vein can extend one block beyond vanilla reach after every lower
            // member has been recovered. There is no legal upward body step or drop shaft left to
            // create; release this finite queue owner so ordinary scanning/strip work can resume.
            abandonTargetApproach(bot, v, "overhead_beyond_reach", v);
            return true;
        }
        if (!miningVein && !canBreakTargetFromHere(bot, v)) {
            // Vein ores carry the same physical-drop debt as the primary target.  Do not exploit
            // the full interaction reach here: a dx/dz=2 break can launch its drop behind a low
            // pedestal edge where neither the entity nor a return pose remains observable.
            approachTargetOre(bot, world, v);
            return true;
        }
        if (!miningVein && isCurrentSupport(bot, v)) {
            if (!moveOffSupport(bot, v)) {
                BotLog.action(bot, "ore_dig_support_unsafe", "ore", v.toShortString());
                excludeOre(bot, v);
                veinQueue.pollFirst();
            }
            return true;
        }
        if (!miningVein) {
            PickupEgressResult egress = tickPickupEgressClearance(bot, world, v);
            if (egress == PickupEgressResult.WORKING) {
                return true;
            }
            if (egress == PickupEgressResult.UNSAFE) {
                BotLog.action(bot, "ore_dig_pickup_egress_unsafe", "ore", v.toShortString());
                excludeOre(bot, v);
                veinQueue.pollFirst();
                return true;
            }
            if (egress == PickupEgressResult.TARGET_ORE) {
                BlockPos egressOre = lowerOreTransitionHead(bot, v);
                if (egressOre != null && !veinQueue.contains(egressOre)) {
                    veinQueue.addFirst(egressOre.toImmutable());
                }
                return true;
            }
        }
        if (!passesTargetDropCommitGate(bot, world, v)) {
            return true;
        }
        if (!miningVein) {
            queueVeinAround(bot, world, v);
        }
        BlockMiner.Status st = miningVein
                ? miner.tick(bot)
                : beginTargetMine(bot, v);
        if (st == BlockMiner.Status.DONE) {
            finishTargetBreak(bot, v, activeTargetBreakInventory);
            veinQueue.pollFirst();
            noteProgress();
        } else if (st == BlockMiner.Status.FAILED
                && !failMissingMiningChannelTool(bot)) {
            clearActiveTargetBreak(v);
            forgetRememberedHighWorkPose(v);
            veinQueue.pollFirst();
        }
        return true;
    }

    /**
     * Moves toward one observed target ore without allowing PathExecutor to use that ore itself as
     * a DIG_THROUGH endpoint. Both primary targets and queued vein members use this exact policy.
     */
    private void approachTargetOre(AIPlayerEntity bot, ServerWorld world, BlockPos ore) {
        if (!bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle()) {
            return;
        }
        BlockPos workPose = approachGoalFor(bot, world, ore);
        if (workPose == null) {
            if (tryRememberedHighWorkPoseRoute(bot, world, ore)) {
                return;
            }
            BlockPos feet = bot.getBlockPos();
            if (ore.getX() == feet.getX()
                    && ore.getZ() == feet.getZ()
                    && ore.getY() - feet.getY() > MAX_TARGET_BREAK_DY) {
                // A vertical air column is not a sealed item funnel. Vanilla block drops start at
                // a random X/Z offset and retain horizontal velocity, so a high drop can leave the
                // shaft and settle on an unreachable ledge before entering player collision range.
                // Keep the ore intact unless this task previously observed and durably retained a
                // standable high side pose before the lower shaft/pickup occluded it.
                abandonTargetApproach(
                        bot, ore, "overhead_drop_catch_unproven", ore.down());
                return;
            }
            // No observed standable side exists yet. Open one controlled cell at a time; never use
            // the target ore itself as a path cell because one DIG_THROUGH step may remove both
            // foot/head ores before either physical drop is entered into the durable ledger.
            digTowardStep(bot, world, ore, TunnelIntent.TARGET_APPROACH);
            return;
        }
        rememberObservedHighWorkPose(bot, ore, workPose);
        ActionResult approach = bot.getActionPack().startDigPathTo(
                workPose, protectedStoneLikeReserve);
        if (approach.isFailed()) {
            if (!"pathfinding_throttled".equals(approach.reason())) {
                BotLog.action(bot, "ore_dig_approach_rejected", "why", approach.reason(),
                        "target", ore.toShortString());
            }
            // Deep solid terrain can exhaust the bounded A* search. Controlled one-cell tunnelling
            // also fills a harmless pathfinding-cooldown tick, keeping distance progress physical
            // without weakening the close-break invariant.
            digTowardStep(bot, world, ore, TunnelIntent.TARGET_APPROACH);
        }
    }

    /**
     * Uses only a previously observed coordinate and a non-destructive route. A missing path never
     * falls through to controlled digging: the pose may now be occluded, so no current observation
     * authorizes changing terrain between the bot and that historical fact.
     */
    private boolean tryRememberedHighWorkPoseRoute(AIPlayerEntity bot,
                                                   ServerWorld world,
                                                   BlockPos ore) {
        BlockPos workPose = rememberedHighWorkPose(bot, world, ore);
        if (workPose == null) {
            return false;
        }
        if (bot.getBlockPos().equals(workPose)) {
            return true;
        }
        if (!ore.equals(rememberedHighWorkPoseRouteOwner)) {
            rememberedHighWorkPoseRouteOwner = ore.toImmutable();
            rememberedHighWorkPoseRouteStartedBudget = totalBudget();
        }
        // The lease belongs to this finite ore owner, not to one particular planner attempt.
        // A surface path can be accepted every tick yet repeatedly end without reaching the exact
        // historical pose (dynamic obstruction, collision, or executor reset). Checking only
        // failed plans therefore grants an infinite retry loop. Expire the absolute owner lease
        // before issuing every new route, including throttled and successfully planned retries.
        if (rememberedHighWorkPoseRouteStartedBudget >= 0
                && totalBudget() - rememberedHighWorkPoseRouteStartedBudget
                > APPROACH_LIMIT) {
            abandonTargetApproach(
                    bot, ore, "remembered_work_pose_unreachable", workPose);
            return true;
        }
        ActionResult route = bot.getActionPack().startSurfacePathTo(workPose);
        if (!route.isFailed()
                && !workPose.equals(bot.getActionPack().activePathGoal())) {
            BlockPos resolved = bot.getActionPack().activePathGoal();
            bot.getActionPack().stopAll();
            forgetRememberedHighWorkPose(ore);
            abandonTargetApproach(
                    bot, ore, "remembered_work_pose_not_exact", resolved);
        } else if (route.isFailed() && !"pathfinding_throttled".equals(route.reason())) {
            BotLog.action(bot, "ore_dig_remembered_work_pose_retry",
                    "ore", ore.toShortString(),
                    "pose", workPose.toShortString(),
                    "reason", route.reason());
        }
        return true;
    }

    /**
     * Makes bounded progress toward a remembered finite owner without inspecting that owner.
     * Only an intermediate body column may be opened; when the next head/foot cell is the unknown
     * ore itself, the task waits for ordinary perception instead of treating it as channel rock.
     */
    private void continueUnknownOwnerApproach(AIPlayerEntity bot,
                                              ServerWorld world,
                                              BlockPos owner,
                                              TunnelIntent intent) {
        if (owner == null || !bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle()) {
            return;
        }
        BlockPos next = stepToward(bot.getBlockPos(), owner);
        if (next == null || next.equals(owner) || next.up().equals(owner)) {
            return;
        }
        if (miner.target() != null) {
            return;
        }
        digTowardStep(bot, world, owner, intent);
    }

    private void beginPendingTargetDrop(AIPlayerEntity bot, BlockPos minedPos, int inventoryBeforeBreak) {
        if (pendingPickupPos != null) {
            return;
        }
        pendingPickupPos = minedPos.toImmutable();
        pendingPickupLastSeenPos = pendingPickupPos;
        pendingPickupInventory = Math.max(0, inventoryBeforeBreak);
        pendingPickupStarted = totalBudget();
        pendingPickupGainTick = -1;
        BotLog.action(bot, "ore_dig_pickup_pending",
                "pos", pendingPickupPos.toShortString(),
                "inventory", pendingPickupInventory);
    }

    private BlockMiner.Status beginTargetMine(AIPlayerEntity bot, BlockPos pos) {
        MiningEvidenceAudit.observeDiamondOreBeforeBreak(
                bot, pos, bot.getEntityWorld().getBlockState(pos).getBlock());
        if (activeTargetBreakPos == null || !activeTargetBreakPos.equals(pos)) {
            activeTargetBreakPos = pos.toImmutable();
            activeTargetBreakInventory = HarvestCore.countInventoryItems(bot, targetDrops);
        }
        return beginMine(bot, pos);
    }

    /**
     * Commits a finite same-level/lower target only after its drop column has a physical catch or
     * one support block can be spent without crossing the parent mission's protected reserve.
     *
     * <p>The cell below an intact ore can be occluded even though the ore itself is visible. Such a
     * hidden floor is not accepted as safety evidence: the task must retain one real support until
     * the break exposes that cell. Rejecting the commit uses the existing target-abandon path, so
     * the ore remains intact, no active/pending break debt is published, and the bounded search may
     * resupply, change pose, or select another observed target.</p>
     */
    private boolean passesTargetDropCommitGate(AIPlayerEntity bot,
                                               ServerWorld world,
                                               BlockPos ore) {
        if (!hasRecoverableTargetBreakPose(bot, ore)) {
            BotLog.action(bot, "ore_dig_drop_pose_commit_blocked",
                    "ore", ore.toShortString(),
                    "feet", bot.getBlockPos().toShortString());
            // This also fences old checkpoints whose active_break_pos was published under the
            // former high-shaft shortcut. The ore is still intact, so cancelling the partial miner
            // and releasing its finite owner is exact-once and cannot lose a physical drop debt.
            miner.cancel(bot);
            clearActiveTargetBreak(ore);
            abandonTargetApproach(bot, ore, "drop_pose_unrecoverable", bot.getBlockPos());
            return false;
        }
        if (!needsTargetDropSupport(bot, ore)) {
            return true;
        }
        BlockPos support = ore.down();
        if (hasReliableObservedDropCatch(bot, world, support)) {
            return true;
        }
        if (MaterialPalette.pickPathSupportBlockSlot(
                bot, protectedStoneLikeReserve).isPresent()) {
            return true;
        }
        BotLog.action(bot, "ore_dig_drop_support_commit_blocked",
                "ore", ore.toShortString(),
                "support", support.toShortString(),
                "protected_stone", protectedStoneLikeReserve);
        // The finite block is still intact, so a partially progressed BlockMiner carries no
        // physical authority once its reserved catch disappears during a pause/restart. Cancel it
        // and erase only the uncommitted active ledger before changing target.
        miner.cancel(bot);
        clearActiveTargetBreak(ore);
        abandonTargetApproach(bot, ore, "drop_support_required", support);
        return false;
    }

    /** Accepts only a visible, dry, stable collision surface as an already-owned drop catch. */
    private static boolean hasReliableObservedDropCatch(AIPlayerEntity bot,
                                                        ServerWorld world,
                                                        BlockPos support) {
        if (!ObservableWorldQuery.canObserveBlock(bot, support)) {
            return false;
        }
        var state = world.getBlockState(support);
        return state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof FallingBlock)
                && !Standability.isDangerous(state)
                && !state.getCollisionShape(world, support).isEmpty();
    }

    private void finishTargetBreak(AIPlayerEntity bot, BlockPos pos, int inventoryBeforeBreak) {
        miner.cancel(bot);
        forgetRememberedHighWorkPose(pos);
        // Removing a lower vein member can expose a high side staircase for only the short physical
        // pickup window. Record every newly visible target pose now, before chasing the randomized
        // ItemEntity can settle the bot under the remaining ore and occlude that staircase again.
        nearestOre(bot, bot.getEntityWorld());
        stabilizeBrokenTargetDrop(bot, pos);
        MiningEvidenceAudit.recordDiamondOreBreak(bot, pos);
        int safeBaseline = inventoryBeforeBreak >= 0
                ? inventoryBeforeBreak
                : HarvestCore.countInventoryItems(bot, targetDrops);
        beginPendingTargetDrop(bot, pos, safeBaseline);
        clearActiveTargetBreak(pos);
    }

    /**
     * On the first task settlement after the ore no longer occludes the shaft, place a real support
     * before the newly spawned ItemEntity can leave the adjacent break column. Same-level and
     * one-lower cardinal breaks both need this catch: swept lower-ore headroom proves the bot can
     * enter the future pickup cell, but it does not prove that the occluded floor below that cell is
     * solid. Higher targets first move to a real high work pose before entering this same bounded
     * break envelope. Failure leaves the ordinary durable pickup debt in charge.
     */
    private void stabilizeBrokenTargetDrop(AIPlayerEntity bot, BlockPos ore) {
        if (!needsTargetDropSupport(bot, ore)) {
            return;
        }
        ServerWorld world = bot.getEntityWorld();
        BlockPos support = ore.down();
        if (!ObservableWorldQuery.canObserveCell(bot, support)) {
            BotLog.action(bot, "ore_dig_drop_support_unavailable",
                    "ore", ore.toShortString(),
                    "support", support.toShortString(),
                    "reason", "unobservable_after_break");
            return;
        }
        var state = world.getBlockState(support);
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            BotLog.action(bot, "ore_dig_drop_support_unavailable",
                    "ore", ore.toShortString(),
                    "support", support.toShortString(),
                    "reason", !state.getFluidState().isEmpty() ? "fluid" : "dangerous");
            return;
        }
        if (!state.getCollisionShape(world, support).isEmpty()) {
            return;
        }
        if (!state.isAir()) {
            BotLog.action(bot, "ore_dig_drop_support_unavailable",
                    "ore", ore.toShortString(),
                    "support", support.toShortString(),
                    "reason", "not_empty");
            return;
        }

        var blockSlot = io.github.zoyluo.minecraftai.action.MaterialPalette
                .pickPathSupportBlockSlot(bot, protectedStoneLikeReserve);
        if (blockSlot.isEmpty()
                || io.github.zoyluo.minecraftai.action.InventoryAction.equipFromSlot(
                bot, blockSlot.getAsInt()) < 0) {
            BotLog.action(bot, "ore_dig_drop_support_unavailable",
                    "ore", ore.toShortString(),
                    "support", support.toShortString(),
                    "reason", blockSlot.isEmpty() ? "no_material" : "equip_rejected");
            return;
        }
        ActionResult placed = io.github.zoyluo.minecraftai.action.BuildAction.placeBlockAt(bot, support);
        if (placed.isFailed()) {
            BotLog.action(bot, "ore_dig_drop_support_unavailable",
                    "ore", ore.toShortString(),
                    "support", support.toShortString(),
                    "reason", "place_rejected:" + placed.reason());
            return;
        }
        var placedState = world.getBlockState(support);
        if (!placedState.getFluidState().isEmpty()
                || Standability.isDangerous(placedState)
                || placedState.getCollisionShape(world, support).isEmpty()) {
            BotLog.action(bot, "ore_dig_drop_support_unavailable",
                    "ore", ore.toShortString(),
                    "support", support.toShortString(),
                    "reason", "placed_without_support");
            return;
        }
        noteProgress();
        BotLog.action(bot, "ore_dig_drop_support_placed",
                "ore", ore.toShortString(),
                "support", support.toShortString(),
                "block", Registries.BLOCK.getId(placedState.getBlock()));
    }

    private void clearActiveTargetBreak(BlockPos pos) {
        if (activeTargetBreakPos == null || (pos != null && !activeTargetBreakPos.equals(pos))) {
            return;
        }
        activeTargetBreakPos = null;
        activeTargetBreakInventory = -1;
    }

    /** Returns true while target mining must remain paused for physical drop recovery. */
    private boolean recoverPendingTargetDrop(AIPlayerEntity bot) {
        if (pendingPickupPos == null) {
            return false;
        }
        int inventoryNow = HarvestCore.countInventoryItems(bot, targetDrops);
        if (inventoryNow > pendingPickupInventory && pendingPickupGainTick < 0) {
            pendingPickupGainTick = totalBudget();
            // The target item entered through vanilla collision pickup. Any route that was
            // chasing the launch position is now stale; leaving it active can carry the miner
            // away after the drop has already disappeared.
            bot.getActionPack().stopAll();
        }
        int age = Math.max(0, totalBudget() - pendingPickupStarted);
        if (age > TARGET_DROP_RECOVERY_LIMIT) {
            bot.getActionPack().stopAll();
            fail("ore_dig_drop_unrecovered:" + pendingPickupPos.toShortString()
                    + ":last_seen=" + (pendingPickupLastSeenPos == null
                    ? "none" : pendingPickupLastSeenPos.toShortString()));
            return true;
        }

        // ItemEntity appears one or more ticks after the break. Chase an observable entity first;
        // when terrain occludes it, return to the exact mined cell so vanilla collision pickup can
        // occur without reading a hidden entity or mutating inventory.
        Optional<net.minecraft.entity.ItemEntity> visibleDrop =
                HarvestCore.nearestDropAnyOf(bot, targetDrops, 16.0D);
        visibleDrop.ifPresent(drop ->
                pendingPickupLastSeenPos = drop.getBlockPos().toImmutable());
        if (pendingPickupGainTick < 0
                && age >= 3
                && bot.getActionPack().isPathExecutorIdle()
                && bot.getActionPack().isWalkToIdle()) {
            if (visibleDrop.isPresent()) {
                // Vanilla drops inherit a small random launch velocity and can settle outside the
                // mined block. Follow the currently observed entity instead of camping the stale
                // break cell. The shared pickup approach distinguishes same-level walking from a
                // vertical path/descent, which matters when a staircase ore drops directly below.
                net.minecraft.entity.ItemEntity drop = visibleDrop.orElseThrow();
                boolean pursuingDrop = false;
                boolean physicallySupported = HarvestCore.isDropPhysicallySupported(bot, drop);
                if (physicallySupported) {
                    pursuingDrop = HarvestCore.approachDropPhysically(bot, drop);
                } else if (isPendingDropInsideRecoverableFallNeighborhood(drop.getBlockPos())) {
                    // A just-broken stacked vein member can remain airborne in its own visible
                    // shaft. Vanilla launch drift may move it one horizontal cell, but movement is
                    // admitted only when the shared helper proves that exact observed column dry,
                    // supported and reachable without digging or pillaring.
                    pursuingDrop = HarvestCore.approachObservedAirborneDropColumn(bot, drop);
                }
                if (physicallySupported
                        && !pursuingDrop
                        && !drop.getBlockPos().equals(pendingPickupPos)) {
                    // A visible entity can rest on a factual but disconnected ledge. A failed exact
                    // route is not movement ownership; fall back to the durable break cell instead
                    // of suppressing it with a false-positive boolean. Unsupported entities outside
                    // the exact fall column must keep waiting for a real landing; their durable
                    // break coordinate is not proof that an elevated route is currently safe.
                    HarvestCore.approachKnownPickupCell(bot, pendingPickupPos);
                }
            } else {
                // The break cell is durable factual knowledge even when the ItemEntity itself is
                // hidden behind the new tunnel corner. Reuse the shared exact, no-dig/no-pillar
                // route directly; requiring a newly observable fallback pose recreates the same
                // blind-corner deadlock that this ledger exists to recover.
                boolean pursuingLastSeen = pendingPickupLastSeenPos != null
                        && HarvestCore.approachKnownPickupCell(bot, pendingPickupLastSeenPos);
                if (!pursuingLastSeen && (pendingPickupLastSeenPos == null
                        || !pendingPickupLastSeenPos.equals(pendingPickupPos))) {
                    HarvestCore.approachKnownPickupCell(bot, pendingPickupPos);
                }
            }
            // A same-cell nudge toward a stale coordinate and a silently failed exact path start
            // both report "pursuing" while the bot physically camps one block. Count that stall
            // and escalate to an observation sweep before the recovery deadline burns out; a
            // launch-drifted drop is routinely collectable from the very next standable cell.
            updatePickupRecoveryStall(bot);
        }
        // Confirmation requires more than one counter increment: allow merged ItemEntities and
        // pickup delay to settle, then observe no remaining target drop. Do not force a return to
        // the original mined cell after inventory gain: vanilla launch velocity can make a real
        // collision pickup happen from a diagonal/lower stand, and the vanished entity no longer
        // has a factual coordinate to chase. This closes the old 64-broken/32-collected failure
        // mode without reading hidden entities or accepting a privileged inventory mutation.
        io.github.zoyluo.minecraftai.pathfinding.Standability.clearCache();
        // ServerPlayerEntity's onGround bit normally comes from client movement packets and is
        // therefore not durable for a clientless fake player. The collision-checked support/head
        // envelope is the authoritative settled-pose invariant here: it rejects the transient
        // upper cell from an elevated pickup while accepting a physically supported shaft floor.
        boolean settledOnStandablePose = io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(
                bot.getEntityWorld(), bot.getBlockPos());
        if (pendingPickupGainTick >= 0
                && totalBudget() - pendingPickupGainTick >= 5
                && settledOnStandablePose
                && visibleDrop.isEmpty()) {
            BotLog.action(bot, "ore_dig_pickup_confirmed",
                    "pos", pendingPickupPos.toShortString(),
                    "gained", inventoryNow - pendingPickupInventory);
            MiningEvidenceAudit.recordDiamondNativePickup(
                    bot, pendingPickupPos, targetDrops, inventoryNow - pendingPickupInventory);
            pendingPickupPos = null;
            pendingPickupLastSeenPos = null;
            pendingPickupInventory = 0;
            pendingPickupStarted = -1;
            pendingPickupGainTick = -1;
            resetPickupRecoveryStall();
            return false;
        }
        return true;
    }

    private void resetPickupRecoveryStall() {
        pendingPickupStallAnchor = null;
        pendingPickupStallTicks = 0;
    }

    /**
     * Detects a physically idle recovery loop. Movement ownership (an active path or walk
     * controller) and real cell changes reset the clock; everything else — same-cell nudges at a
     * stale coordinate, path starts that fail inside their cooldown, an unreachable stand — counts
     * toward one bounded stall window before the sweep escalation runs.
     */
    private void updatePickupRecoveryStall(AIPlayerEntity bot) {
        if (!bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle()) {
            resetPickupRecoveryStall();
            return;
        }
        BlockPos current = bot.getBlockPos().toImmutable();
        if (!current.equals(pendingPickupStallAnchor)) {
            pendingPickupStallAnchor = current;
            pendingPickupStallTicks = 0;
            return;
        }
        pendingPickupStallTicks++;
        if (pendingPickupStallTicks < PICKUP_STALL_SWEEP_TICKS) {
            return;
        }
        if (startPickupObservationSweepStep(bot)) {
            pendingPickupStallTicks = 0;
            return;
        }
        if (pendingPickupStallTicks % 20 == 0) {
            BotLog.action(bot, "ore_dig_pickup_recovery_stalled",
                    "camped", current.toShortString(),
                    "pending", pendingPickupPos == null
                            ? "none" : pendingPickupPos.toShortString(),
                    "last_seen", pendingPickupLastSeenPos == null
                            ? "none" : pendingPickupLastSeenPos.toShortString(),
                    "stalled_ticks", pendingPickupStallTicks);
        }
    }

    /**
     * Walks to the next observable, standable ring cell around the drop's last factual
     * coordinate. This mirrors Hunt's pickup observation sweep: ordinary exact no-dig/no-pillar
     * movement that changes the viewpoint so an occluded or launch-drifted ItemEntity becomes
     * visible — or simply collides with the player's pickup box from the neighbouring cell.
     */
    private boolean startPickupObservationSweepStep(AIPlayerEntity bot) {
        return startObservationSweepStep(bot, pendingPickupLastSeenPos != null
                ? pendingPickupLastSeenPos : pendingPickupPos);
    }

    private boolean startObservationSweepStep(AIPlayerEntity bot, BlockPos anchor) {
        if (anchor == null) {
            return false;
        }
        ServerWorld world = bot.getEntityWorld();
        int standY = bot.getBlockPos().getY();
        for (int checked = 0; checked < PICKUP_SWEEP_OFFSETS.length; checked++) {
            int[] offset = PICKUP_SWEEP_OFFSETS[
                    Math.floorMod(pendingPickupSweepCursor++, PICKUP_SWEEP_OFFSETS.length)];
            BlockPos candidate = new BlockPos(
                    anchor.getX() + offset[0], standY, anchor.getZ() + offset[1]);
            Standability.clearCache();
            if (candidate.equals(bot.getBlockPos())
                    || !ObservableWorldQuery.canObserveCell(bot, candidate)
                    || !ObservableWorldQuery.canObserveCell(bot, candidate.up())
                    || !ObservableWorldQuery.canObserveBlock(bot, candidate.down())
                    || !Standability.isStandable(world, candidate)) {
                continue;
            }
            if (!HarvestCore.startExactPickupPath(bot, candidate)) {
                continue;
            }
            BotLog.action(bot, "ore_dig_pickup_observation_sweep",
                    "anchor", anchor.toShortString(),
                    "to", candidate.toShortString(),
                    "step", pendingPickupSweepCursor);
            return true;
        }
        return false;
    }

    private boolean isPendingDropInsideRecoverableFallNeighborhood(BlockPos dropPos) {
        if (pendingPickupPos == null || dropPos == null) {
            return false;
        }
        int horizontal = Math.max(
                Math.abs(dropPos.getX() - pendingPickupPos.getX()),
                Math.abs(dropPos.getZ() - pendingPickupPos.getZ()));
        int downward = pendingPickupPos.getY() - dropPos.getY();
        // A restored ledger from an older build can still contain a high overhead break. Admit only
        // vanilla's immediate one-cell launch neighbourhood below that factual break; the shared
        // airborne-column helper must separately prove a visible dry shaft and exact surface route.
        return horizontal <= 1 && downward >= 0
                && downward <= TARGET_DROP_LAST_SEEN_RANGE;
    }

    /** Returns the swept head cell for an adjacent lower ore, or null for every other geometry. */
    private static BlockPos lowerOreTransitionHead(AIPlayerEntity bot, BlockPos ore) {
        if (bot == null || ore == null) {
            return null;
        }
        BlockPos current = bot.getBlockPos();
        int vertical = ore.getY() - current.getY();
        int horizontal = Math.abs(ore.getX() - current.getX())
                + Math.abs(ore.getZ() - current.getZ());
        return vertical == -1 && horizontal == 1 ? ore.up(2).toImmutable() : null;
    }

    /**
     * Clears the one extra head cell required to walk/fall into a lower ore's future pickup cell.
     * This happens before the finite target is broken, through the ordinary BlockMiner channel;
     * an unsafe or unobservable obstruction rejects the ore instead of creating unrecoverable
     * pickup debt.
     */
    private PickupEgressResult tickPickupEgressClearance(AIPlayerEntity bot,
                                                          ServerWorld world,
                                                          BlockPos ore) {
        BlockPos transitionHead = lowerOreTransitionHead(bot, ore);
        if (transitionHead == null) {
            return PickupEgressResult.CLEAR;
        }
        boolean active = miner.target() != null && miner.target().equals(transitionHead);
        if (active) {
            // The opened head cell can become UNKNOWN when the ray continues into a farther wall.
            // Settle the already-owned clearance transaction before asking for a fresh observation.
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.DONE) {
                noteProgress();
            } else if (status == BlockMiner.Status.FAILED) {
                return failMissingMiningChannelTool(bot)
                        ? PickupEgressResult.WORKING : PickupEgressResult.UNSAFE;
            }
            return PickupEgressResult.WORKING;
        }
        OreScan.Observation openState = observePickupEgressClearance(
                bot, world, transitionHead);
        if (openState == OreScan.Observation.UNKNOWN) {
            // Preserve the lower target and any active head-clear transaction. Hidden headroom is
            // neither clear nor an unsafe obstruction until the bot can actually observe it.
            return PickupEgressResult.WORKING;
        }
        var state = world.getBlockState(transitionHead);
        if (openState == OreScan.Observation.OBSERVED_PRESENT) {
            return PickupEgressResult.CLEAR;
        }
        if (!state.getFluidState().isEmpty()
                || state.getHardness(world, transitionHead) < 0.0F
                || !ObservableWorldQuery.canObserveBlock(bot, transitionHead)) {
            return PickupEgressResult.UNSAFE;
        }
        if (OreScan.isOre(state, targetOres)) {
            return PickupEgressResult.TARGET_ORE;
        }
        BlockMiner.Status status = beginMine(bot, transitionHead);
        if (status == BlockMiner.Status.DONE) {
            noteProgress();
        } else if (status == BlockMiner.Status.FAILED) {
            return failMissingMiningChannelTool(bot)
                    ? PickupEgressResult.WORKING : PickupEgressResult.UNSAFE;
        }
        return PickupEgressResult.WORKING;
    }

    static OreScan.Observation observePickupEgressClearance(AIPlayerEntity bot,
                                                             ServerWorld world,
                                                             BlockPos transitionHead) {
        return OreScan.observe(bot, transitionHead,
                state -> state.getCollisionShape(world, transitionHead).isEmpty());
    }

    private void queueVeinAround(AIPlayerEntity bot, ServerWorld world, BlockPos around) {
        for (BlockPos p : OreScan.veinFrom(bot, around, targetOres, VEIN_CAP)) {
            if (!p.equals(around)
                    && !veinQueue.contains(p)
                    && !oreExcluded(bot, p)
                    && io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, p)) {
                rememberObservedHighWorkPose(bot, world, p);
                veinQueue.addLast(p.toImmutable());
            }
        }
    }

    // ── Dig one tunnel cell toward the target (only the cell within reach, driven by BlockMiner) ──
    private void digTowardStep(AIPlayerEntity bot,
                               ServerWorld world,
                               BlockPos goal,
                               TunnelIntent intent) {
        digTowardStep(bot, world, goal, intent, null);
    }

    private void digTowardStep(AIPlayerEntity bot,
                               ServerWorld world,
                               BlockPos goal,
                               TunnelIntent intent,
                               BlockPos factualRear) {
        BlockPos feet = bot.getBlockPos();
        // P0 (fixes zero-displacement spinning on deep diagonally-below ore): the target is deep
        // underfoot (>=2 lower) and already horizontally close (<=2) -> the same-layer horizontal
        // approach can never reach it, so switch to a safe-stair descent by one layer
        // (digDownOneLayer comes with its own water/lava avoidance and headroom clearing, the same
        // reliable primitive used for dive-mining shafts). Testing on real_armor: bot stands at Y47
        // locked onto ore at Y40, stepToward only ever walks east horizontally and never descends ->
        // dist never shrinks -> skip -> thrash for 100s+.
        int dyToGoal = goal.getY() - feet.getY();
        if (dyToGoal <= -2) {
            clearStripMovementOwnership();
            // The target is clearly below (>=2 lower): the same-layer horizontal approach can never
            // reach it (real_armor testing: bot stuck fast at Y47 locked onto ore at Y40,
            // skip265/collected9). Descend via a safe stair (digDownOneLayer avoids water/lava, clears
            // headroom), and bias the stair direction [toward the ore's horizontal bearing] -- this
            // forms a "diagonal descent straight at the ore" staircase: it both drops Y and closes in
            // horizontally, so once it reaches the ore's layer the same-layer logic naturally takes
            // over. Broader than the old "only descend if horizontal distance <=2" rule.
            int dx = goal.getX() - feet.getX();
            int dz = goal.getZ() - feet.getZ();
            if (Math.abs(dx) >= Math.abs(dz)) {
                stripDirIndex = dx >= 0 ? 1 : 3; // STRIP_DIRS{N,E,S,W}: EAST=1 / WEST=3
            } else {
                stripDirIndex = dz >= 0 ? 2 : 0; // SOUTH=2 / NORTH=0
            }
            if (!digDownOneLayer(bot, world)) {
                abandonTargetApproach(bot, goal, "no_safe_stair_landing", goal);
            }
            return;
        }
        BlockPos step = stepToward(feet, goal);
        if (step == null) {
            if (intent == TunnelIntent.TARGET_APPROACH) {
                abandonTargetApproach(bot, goal, "no_step", goal);
            } else {
                excludeOre(bot, goal);
            }
            return;
        }
        BlockPos head = step.up();

        // An issued channel break remains factual even if the newly opened air cell no longer has
        // a direct ray. Settle that exact owner before asking ordinary perception to classify it.
        BlockPos activeChannel = miner.target();
        if (step.equals(activeChannel) || head.equals(activeChannel)) {
            settleOwnedTunnelMine(bot, goal, intent, activeChannel);
            return;
        }

        // Head-first structural staging must not ignore a fluid already visible in the other body
        // cell. This probe reveals only the dedicated danger predicate: hidden stone and hidden
        // fluid both remain UNKNOWN and produce the same staged action, while an observed source
        // is rejected before either obstruction is mined.
        for (BlockPos bodyCell : new BlockPos[]{head, step}) {
            if (OreScan.observeDangerFluid(bot, bodyCell)
                    == OreScan.Observation.OBSERVED_PRESENT) {
                rejectObservedTunnelBoundary(bot, world, goal, intent,
                        observedFluidReason(world.getFluidState(bodyCell)),
                        bodyCell, factualRear);
                return;
            }
        }

        // Stage the body column from head to foot. An observed safe upper obstruction can be mined
        // while the lower cell is still UNKNOWN; opening it creates the real sightline used on the
        // following tick. No world state is read for either cell before its own observation gate.
        if (!canObserveWorldState(bot, head)) {
            miner.cancel(bot);
            return;
        }
        var headState = world.getBlockState(head);
        if (headState.getBlock() instanceof FallingBlock) {
            rejectObservedTunnelBoundary(
                    bot, world, goal, intent, "gravity", head, factualRear);
            return;
        }
        if (!headState.getFluidState().isEmpty()) {
            rejectObservedTunnelBoundary(bot, world, goal, intent,
                    observedFluidReason(headState.getFluidState()), head, factualRear);
            return;
        }
        if (!preflightBlindBodyEnvelope(bot, world, step, intent, factualRear)) {
            return;
        }
        if (!headState.isAir()) {
            mineObservedTunnelObstruction(bot, world, goal, intent, head, factualRear);
            return;
        }

        if (!canObserveWorldState(bot, step)) {
            miner.cancel(bot);
            return;
        }
        var feetState = world.getBlockState(step);
        if (feetState.getBlock() instanceof FallingBlock) {
            rejectObservedTunnelBoundary(
                    bot, world, goal, intent, "gravity", step, factualRear);
            return;
        }
        if (!feetState.getFluidState().isEmpty()) {
            rejectObservedTunnelBoundary(bot, world, goal, intent,
                    observedFluidReason(feetState.getFluidState()), step, factualRear);
            return;
        }
        if (!feetState.isAir()) {
            mineObservedTunnelObstruction(bot, world, goal, intent, step, factualRear);
            return;
        }

        // Movement is the only phase that requires both body cells to be factually open and dry.
        // Natural caves frequently change floor height by exactly one block. Treat that
        // observed, supported lower cell as a real stair instead of collapsing every height
        // change into the same open-drop failure used for multi-block shafts. The move stays
        // fail-closed: the complete landing body/floor must be visible, dry, hazard-free and
        // standable, and FakePlayerMotion rechecks collision/entity occupancy at commit time.
        if (intent == TunnelIntent.TARGET_APPROACH
                && descendAcrossObservedOneBlockDrop(bot, world, step, goal)) {
            return;
        }
        if (intent == TunnelIntent.TARGET_APPROACH
                && hasObservedAdjacentDangerFluid(bot, step, head)) {
            abandonTargetApproach(bot, goal, "adjacent_fluid", step);
            miner.cancel(bot);
            return;
        }
        if (!canObserveWorldState(bot, step.down())) {
            // A hidden floor never authorizes movement or an open-drop classification. An observed
            // AIR floor, however, is a factual cell observation and must reach Standability below
            // so the branch can rotate instead of waiting forever on a block-face-only query.
            miner.cancel(bot);
            return;
        }
        // A blind branch is allowed to advance only through a factual same-level floor. The
        // exact walker will otherwise interpret an already-open shaft/cave as a route and can
        // drop several blocks before DangerWatcher gets another scan (seed 3000: Y48 -> Y40
        // beside three hostiles). Rotate the optional strip leg at the lip; target-ore pickup
        // approaches retain their separately bounded lower-transition contract.
        if (intent == TunnelIntent.BLIND_BRANCH) {
            Standability.clearCache();
            if (!Standability.isStandable(world, step)) {
                rerouteBlindBranchAtObservedBoundary(
                        bot, world, "open_drop", step, factualRear);
                return;
            }
        } else {
            Standability.clearCache();
            if (!Standability.isStandable(world, step)) {
                abandonTargetApproach(bot, goal, "open_drop", step);
                return;
            }
        }
        // This cell is already dug through (air) -> walk into it to occupy the cell, advancing the
        // bot to the tunnel front, then continue digging toward the ore.
        // Must actively walk: this task never pathfinds, so horizontal advancement relies entirely
        // on this step, otherwise the bot just stands still until the watchdog fails it.
        miner.cancel(bot);
        // A default 0.6-block arrival radius can report success just before the player crosses
        // the BlockPos boundary, especially while moving east/south. Recreating that walker
        // every tick produced hundreds of walk_complete events without advancing the tunnel
        // face. Reuse the executor's exact node threshold so the next task tick observes the
        // factual destination block before it may break a finite ore.
        ActionResult walk = bot.getActionPack().startWalkTo(
                step.toCenterPos(), WalkToController.PATH_NODE_ARRIVAL_THRESHOLD);
        if (intent == TunnelIntent.BLIND_BRANCH && !walk.isFailed()) {
            pendingBlindAdvance = new PendingBlindAdvance(
                    feet, step, STRIP_DIRS[stripDirIndex]);
        } else {
            clearPendingBlindAdvance();
        }
        // Reaching a new cell also counts as progress (avoids the watchdog wrongly killing the task during the few ticks of "dug through, now walking there").
        if (bot.getBlockPos().equals(step)) {
            noteProgress();
        }
        return;
    }

    private boolean preflightBlindBodyEnvelope(AIPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos step,
                                                TunnelIntent intent,
                                                BlockPos factualRear) {
        if (intent != TunnelIntent.BLIND_BRANCH) {
            return true;
        }
        BranchFluidSealResult fluid = sealOneObservableLateralBranchFluid(
                bot, world, step, "body_envelope");
        if (fluid == BranchFluidSealResult.CLEAR) {
            return true;
        }
        miner.cancel(bot);
        if (fluid == BranchFluidSealResult.BLOCKED) {
            rerouteBlindBranchAtObservedBoundary(
                    bot, world, "lava", step, factualRear);
        }
        return false;
    }

    private void mineObservedTunnelObstruction(AIPlayerEntity bot,
                                               ServerWorld world,
                                               BlockPos goal,
                                               TunnelIntent intent,
                                               BlockPos obstruction,
                                               BlockPos factualRear) {
        if (intent == TunnelIntent.TARGET_APPROACH
                && OreScan.adjacentHazard(bot, obstruction)
                == OreScan.Observation.OBSERVED_PRESENT) {
            abandonTargetApproach(bot, goal, "adjacent_fluid", obstruction);
            miner.cancel(bot);
            return;
        }
        // A newly exposed target ore may become the next blind body block between scan intervals.
        // Never treat it as disposable channel rock: acquire ordinary target ownership, or rotate
        // around an explicitly excluded target whose drop-catch commit was rejected.
        if (intent == TunnelIntent.BLIND_BRANCH
                && preserveTargetOreAtBlindBoundary(
                bot, world, obstruction, factualRear)) {
            return;
        }
        if (intent == TunnelIntent.BLIND_BRANCH
                && rerouteBlindBranchAroundHigherTierOre(
                bot, world, obstruction, factualRear)) {
            return;
        }
        settleOwnedTunnelMine(bot, goal, intent, obstruction);
    }

    private void settleOwnedTunnelMine(AIPlayerEntity bot,
                                       BlockPos goal,
                                       TunnelIntent intent,
                                       BlockPos obstruction) {
        BlockMiner.Status status = miner.target() != null
                && miner.target().equals(obstruction)
                ? miner.tick(bot)
                : beginMine(bot, obstruction);
        if (status == BlockMiner.Status.DONE) {
            noteProgress();
        } else if (status == BlockMiner.Status.FAILED
                && !failMissingMiningChannelTool(bot)) {
            if (intent == TunnelIntent.TARGET_APPROACH) {
                abandonTargetApproach(bot, goal, "channel_failed", obstruction);
            } else {
                excludeOre(bot, goal);
            }
        }
    }

    private void rejectObservedTunnelBoundary(AIPlayerEntity bot,
                                              ServerWorld world,
                                              BlockPos goal,
                                              TunnelIntent intent,
                                              String reason,
                                              BlockPos blocked,
                                              BlockPos factualRear) {
        miner.cancel(bot);
        if (intent == TunnelIntent.TARGET_APPROACH) {
            abandonTargetApproach(bot, goal, reason, blocked);
        } else {
            rerouteBlindBranchAtObservedBoundary(
                    bot, world, reason, blocked, factualRear);
        }
    }

    private static String observedFluidReason(net.minecraft.fluid.FluidState fluid) {
        return fluid.isIn(FluidTags.WATER) ? "water" : "lava";
    }

    private static boolean hasObservedAdjacentDangerFluid(AIPlayerEntity bot,
                                                           BlockPos... centers) {
        for (BlockPos center : centers) {
            if (OreScan.adjacentHazard(bot, center)
                    == OreScan.Observation.OBSERVED_PRESENT) {
                return true;
            }
        }
        return false;
    }

    private boolean preserveTargetOreAtBlindBoundary(AIPlayerEntity bot,
                                                      ServerWorld world,
                                                      BlockPos obstruction,
                                                      BlockPos factualRear) {
        OreScan.Observation obstructionState = OreScan.observeOre(
                bot, obstruction, targetOres);
        if (obstructionState == OreScan.Observation.UNKNOWN) {
            // Preserve the unopened branch wall and retry after its face is observable.
            miner.cancel(bot);
            return true;
        }
        if (obstructionState == OreScan.Observation.OBSERVED_GONE) {
            return false;
        }
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        if (oreExcluded(bot, obstruction)) {
            rerouteBlindBranchAtObservedBoundary(
                    bot, world, "protected_target", obstruction, factualRear);
            return true;
        }
        clearStripMovementOwnership();
        targetOre = obstruction.toImmutable();
        lastTargetDist = Double.MAX_VALUE;
        targetApproachTick = elapsed;
        BotLog.action(bot, "ore_dig_branch_target_acquired",
                "ore", obstruction.toShortString(),
                "direction", STRIP_DIRS[stripDirIndex].asString(),
                "steps_left", stripStepsLeft);
        return true;
    }

    /**
     * Commits one diagonal downward player step through a fully observed natural cave ledge.
     * Unsupported shafts, fluids, hazards, hidden floors and occupied landings remain owned by
     * the ordinary open-drop rejection path. This is deliberately target-only: blind exploration
     * still rotates at every open ledge, while a finite visible ore may use one proven natural
     * stair and continue its bounded approach from the lower level.
     */
    private boolean descendAcrossObservedOneBlockDrop(AIPlayerEntity bot,
                                                       ServerWorld world,
                                                       BlockPos step,
                                                       BlockPos goal) {
        BlockPos origin = bot.getBlockPos();
        if (step == null || goal == null || goal.getY() >= origin.getY()
                || step.getY() != origin.getY()
                || Math.abs(step.getX() - origin.getX())
                + Math.abs(step.getZ() - origin.getZ()) != 1) {
            return false;
        }
        BlockPos landing = step.down();
        BlockPos floor = landing.down();
        if (landing.getSquaredDistance(goal) >= origin.getSquaredDistance(goal)) {
            return false;
        }
        if (!ObservableWorldQuery.canObserveCell(bot, step)
                || !ObservableWorldQuery.canObserveCell(bot, step.up())
                || !ObservableWorldQuery.canObserveCell(bot, landing)
                || !ObservableWorldQuery.canObserveBlock(bot, floor)) {
            return false;
        }
        var floorState = world.getBlockState(floor);
        if (!world.getFluidState(step).isEmpty()
                || !world.getFluidState(landing).isEmpty()
                || !world.getFluidState(floor).isEmpty()
                || floorState.getBlock() instanceof FallingBlock
                || world.getBlockEntity(floor) != null
                || Standability.isDangerous(floorState)
                || floorState.getCollisionShape(world, floor)
                .getMax(Direction.Axis.Y) < 1.0D
                || !isObservedAdjacentFluidSafe(bot, world, step, landing)) {
            return false;
        }
        Standability.clearCache();
        if (!Standability.isStandable(world, landing)
                || !bot.getActionPack().descendInto(landing)
                || !bot.getBlockPos().equals(landing)) {
            return false;
        }
        publishSynchronousMove(origin, landing);
        BotLog.action(bot, "ore_dig_observed_lower_step",
                "intent", "target_approach",
                "from", origin.toShortString(),
                "to", landing.toShortString(),
                "steps_left", stripStepsLeft);
        return true;
    }

    /**
     * Rejects both a visible adjacent fluid and an unknown adjacent cell. Reading a hidden neighbour
     * merely to discover that it contains water/lava would still be privileged sensing, even when
     * the result is used only to avoid danger.
     */
    static boolean isObservedAdjacentFluidSafe(AIPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos... centers) {
        Set<BlockPos> checked = new HashSet<>();
        for (BlockPos center : centers) {
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = center.offset(direction).toImmutable();
                if (!checked.add(adjacent)) {
                    continue;
                }
                if (!ObservableWorldQuery.canObserveCell(bot, adjacent)
                        && !ObservableWorldQuery.canObserveBlock(bot, adjacent)
                        && !ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, adjacent)) {
                    return false;
                }
                var fluid = world.getFluidState(adjacent);
                if (fluid.isIn(FluidTags.LAVA) || fluid.isIn(FluidTags.WATER)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Releases exactly one known ore-approach owner without mutating the durable strip cursor.
     * A queued vein member intentionally has targetOre == null, so ownership must be resolved
     * explicitly instead of falling through to blind-branch reroute state.
     */
    private void abandonTargetApproach(AIPlayerEntity bot,
                                       BlockPos goal,
                                       String reason,
                                       BlockPos blocked) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        clearActiveTargetBreak(goal);
        excludeOre(bot, goal);
        String owner = "unknown";
        if (goal.equals(targetOre)) {
            targetOre = null;
            lastTargetDist = Double.MAX_VALUE;
            owner = "primary";
        } else if (goal.equals(veinQueue.peekFirst())) {
            veinQueue.pollFirst();
            owner = "vein";
        }
        BotLog.action(bot, "ore_dig_target_approach_abandoned",
                "owner", owner,
                "reason", reason,
                "ore", goal.toShortString(),
                "blocked", blocked == null ? "none" : blocked.toShortString());
    }

    // Staircase-style diagonal layer descent (human-like + safe): never dig straight down underfoot
    // -- there may be water/lava below, and one pickaxe swing through it means drowning or burning
    // in lava. Dig "the next step down" diagonally ahead in the current digging direction (ahead =
    // head cell, next = foot cell); if water/lava is hit, switch to a different diagonal direction,
    // descending to the new plane like digging a staircase (consistent with the staircase logic in
    // DescendToYTask / DigDownTask).
    private boolean digDownOneLayer(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        if (feet.down().getY() <= MIN_Y) {
            fail("ore_dig_reached_min_y collected=" + collected);
            return true;
        }
        Direction dir = safeStairDir(bot, world, feet);
        if (dir == null) {
            // This is only a failure of the approach route to the current target ore. Let the caller
            // exclude this one finite target and keep searching -- a cave edge or a four-way fluid
            // boundary must not be escalated into a permanent failure of the entire OreDig task.
            return false;
        }
        BlockPos ahead = feet.offset(dir);   // Next step's head cell (x+d, y)
        BlockPos next = ahead.down();         // Next step's foot cell (x+d, y-1)
        // Clear three body cells: ahead (front head cell, mine first if visible) + ahead.up() (headroom
        // above the front) + next (foot cell). Digging ahead.up() too keeps the dive shaft 2 cells
        // tall along the diagonal -- clearing only next+ahead would leave a player descending the
        // stairs bumping their head on the solid ceiling ahead, making the passage effectively only
        // 1 block tall and impassable.
        BlockPos solid = TerrainProbe.firstSolid(world, ahead, ahead.up(), next);
        if (solid != null) {
            BlockMiner.Status st = miner.target() != null && miner.target().equals(solid)
                    ? miner.tick(bot)
                    : beginMine(bot, solid);
            if (st == BlockMiner.Status.DONE) {
                noteProgress();
            } else if (st == BlockMiner.Status.FAILED) {
                failMissingMiningChannelTool(bot);
            }
            return true;
        }
        // Body clearance confirmed -> step diagonally down onto the next stair (a 1-block micro-move, not the map-crossing teleport that roam uses).
        boolean moved = bot.getActionPack().descendInto(next);
        if (moved && bot.getBlockPos().equals(next)) {
            publishSynchronousMove(feet, next);
            return true;
        }
        return false;
    }

    /**
     * Atomically publishes an already-completed fake-client move into OreDig's durable cursor.
     * Unlike ordinary walking, these moves finish inside the current task tick, so waiting for the
     * next onTick preflight can expose a stale face or preserve the one-origin reverse exception in
     * an immediate checkpoint.  A factual move always consumes that exception, including a retreat
     * which happens to land on an older branch face.
     */
    private void publishSynchronousMove(BlockPos from, BlockPos destination) {
        if (from == null || destination == null || from.equals(destination)) {
            return;
        }
        clearStripMovementOwnership();
        BlockPos factualFace = destination.toImmutable();
        boundaryRerouteOrigin = null;
        lastFace = factualFace;
        stripProgressPos = factualFace;
        noteProgress();
    }

    // Pick a diagonal-down stair direction that is "not adjacent to water/lava": prefer the current
    // digging direction (naturally continues the tunnel), otherwise search in STRIP_DIRS order;
    // returns null if all four diagonal-down directions are blocked by water/lava.
    private Direction safeStairDir(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        int base = stripDirIndex < 0 ? 0 : stripDirIndex;
        for (int i = 0; i < STRIP_DIRS.length; i++) {
            Direction dir = STRIP_DIRS[(base + i) % STRIP_DIRS.length];
            BlockPos ahead = feet.offset(dir);
            BlockPos next = ahead.down();
            BlockPos support = next.down();
            if (!canObserveWorldState(bot, ahead)
                    || !canObserveWorldState(bot, ahead.up())
                    || !canObserveWorldState(bot, next)
                    || !ObservableWorldQuery.canObserveBlock(bot, support)) {
                continue;
            }
            var supportState = world.getBlockState(support);
            boolean supported = supportState.getFluidState().isEmpty()
                    && !Standability.isDangerous(supportState)
                    && supportState.getCollisionShape(world, support)
                    .getMax(Direction.Axis.Y) > 0.0D;
            if (supported
                    && !isLava(world, next) && !isLava(world, support)
                    && !isLava(world, ahead) && !isLava(world, ahead.up())
                    && !isWater(world, next) && !isWater(world, support)
                    && !isWater(world, ahead) && !isWater(world, ahead.up())) {
                return dir;
            }
        }
        return null;
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    private static boolean canObserveWorldState(AIPlayerEntity bot, BlockPos pos) {
        return ObservableWorldQuery.canObserveCell(bot, pos)
                || ObservableWorldQuery.canObserveBlock(bot, pos);
    }

    private void rememberObservedHighWorkPose(AIPlayerEntity bot,
                                              ServerWorld world,
                                              BlockPos ore) {
        if (ore == null || ore.getY() - bot.getBlockPos().getY() <= MAX_TARGET_BREAK_DY) {
            return;
        }
        BlockPos observed = approachGoalFor(bot, world, ore);
        if (observed != null) {
            rememberObservedHighWorkPose(bot, ore, observed);
        }
    }

    private void rememberObservedHighWorkPose(AIPlayerEntity bot,
                                              BlockPos ore,
                                              BlockPos observedPose) {
        if (ore == null || observedPose == null
                || ore.getY() - bot.getBlockPos().getY() <= MAX_TARGET_BREAK_DY
                || !isExactHighWorkPose(ore, observedPose)) {
            return;
        }
        BlockPos immutableOre = ore.toImmutable();
        BlockPos immutablePose = observedPose.toImmutable();
        BlockPos previous = rememberedHighWorkPoses.put(immutableOre, immutablePose);
        trimRememberedHighWorkPoseLedger(bot);
        if (rememberedHighWorkPoses.containsKey(immutableOre)
                && !immutablePose.equals(previous)) {
            BotLog.action(bot, "ore_dig_high_work_pose_observed",
                    "ore", immutableOre.toShortString(),
                    "pose", immutablePose.toShortString());
        }
    }

    /**
     * Keeps the finite observation ledger useful after it reaches capacity.
     *
     * <p>Current route/target/queue owners are transaction debt and cannot be evicted. Among the
     * remaining facts, discard the farthest ore from the bot; coordinate ordering makes equal
     * distances deterministic across HashMap iteration and process restarts.</p>
     */
    private void trimRememberedHighWorkPoseLedger(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        java.util.Comparator<BlockPos> farthestFirst = java.util.Comparator
                .comparingLong((BlockPos pos) -> squaredBlockDistance(origin, pos))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ);
        while (rememberedHighWorkPoses.size() > VEIN_CAP) {
            BlockPos evicted = rememberedHighWorkPoses.keySet().stream()
                    .filter(pos -> !isRememberedHighWorkPosePinned(pos))
                    .max(farthestFirst)
                    .orElse(null);
            if (evicted == null) {
                // At most three owners can be pinned while VEIN_CAP is 64. Keep this guard so a
                // future ownership expansion fails closed rather than spinning in this loop.
                return;
            }
            BlockPos evictedPose = rememberedHighWorkPoses.get(evicted);
            forgetRememberedHighWorkPose(evicted);
            BotLog.action(bot, "ore_dig_high_work_pose_evicted",
                    "ore", evicted.toShortString(),
                    "pose", evictedPose == null ? "none" : evictedPose.toShortString(),
                    "distance_squared", squaredBlockDistance(origin, evicted));
        }
    }

    private boolean isRememberedHighWorkPosePinned(BlockPos ore) {
        return ore != null && (ore.equals(rememberedHighWorkPoseRouteOwner)
                || ore.equals(targetOre)
                || ore.equals(veinQueue.peekFirst()));
    }

    /**
     * Revalidates only facts that are presently observable. UNKNOWN does not become either a fresh
     * safety proof or an invalidation; the no-dig surface path and ordinary movement safety remain
     * responsible while the old pose is occluded.
     */
    private BlockPos rememberedHighWorkPose(AIPlayerEntity bot,
                                            ServerWorld world,
                                            BlockPos ore) {
        BlockPos remembered = rememberedHighWorkPoses.get(ore);
        if (remembered == null) {
            return null;
        }
        if (!isExactHighWorkPose(ore, remembered)) {
            forgetRememberedHighWorkPose(ore);
            return null;
        }
        OreScan.Observation hazard = OreScan.adjacentHazard(bot, remembered);
        if (hazard == OreScan.Observation.OBSERVED_PRESENT) {
            forgetRememberedHighWorkPose(ore);
            BotLog.action(bot, "ore_dig_high_work_pose_revoked",
                    "ore", ore.toShortString(),
                    "pose", remembered.toShortString(),
                    "reason", "observed_hazard");
            return null;
        }
        boolean envelopeObserved = ObservableWorldQuery.canObserveCell(bot, remembered)
                && ObservableWorldQuery.canObserveCell(bot, remembered.up())
                && ObservableWorldQuery.canObserveBlock(bot, remembered.down());
        if (envelopeObserved) {
            Standability.clearCache();
        }
        if (envelopeObserved && !Standability.isStandable(world, remembered)) {
            forgetRememberedHighWorkPose(ore);
            BotLog.action(bot, "ore_dig_high_work_pose_revoked",
                    "ore", ore.toShortString(),
                    "pose", remembered.toShortString(),
                    "reason", "observed_unstandable");
            return null;
        }
        return remembered;
    }

    private void forgetRememberedHighWorkPose(BlockPos ore) {
        if (ore != null) {
            rememberedHighWorkPoses.remove(ore);
            if (ore.equals(rememberedHighWorkPoseRouteOwner)) {
                rememberedHighWorkPoseRouteOwner = null;
                rememberedHighWorkPoseRouteStartedBudget = -1;
            }
        }
    }

    private static boolean isExactHighWorkPose(BlockPos ore, BlockPos pose) {
        if (ore == null || pose == null || pose.getY() != ore.getY() - 1) {
            return false;
        }
        long dx = Math.abs((long) pose.getX() - ore.getX());
        long dz = Math.abs((long) pose.getZ() - ore.getZ());
        return dx + dz == 1L;
    }

    // Approach landing spot: only pick a horizontally-adjacent safe stance next to the target ore,
    // never hand the ore block itself or the cell directly under it to DIG_THROUGH.
    // The latter could break both the foot-cell and head-cell ore blocks in a single path step,
    // bypassing the per-block drop accounting.
    private static BlockPos approachGoalFor(AIPlayerEntity bot, ServerWorld world, BlockPos ore) {
        AdjacentFluidObservation fluid = adjacentDangerFluidOf(bot, ore);
        BlockPos lava = fluid.position();
        Direction preferred = null;
        if (lava != null) {
            int dx = Integer.compare(ore.getX(), lava.getX());
            int dz = Integer.compare(ore.getZ(), lava.getZ());
            preferred = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST)
                    : dz != 0 ? (dz > 0 ? Direction.SOUTH : Direction.NORTH)
                    : Direction.NORTH;
        }
        Direction[] order = preferred == null
                ? new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}
                : new Direction[]{preferred, preferred.rotateYClockwise(), preferred.rotateYCounterclockwise(), preferred.getOpposite()};
        for (Direction direction : order) {
            BlockPos candidate = ore.down().offset(direction);
            if (!ObservableWorldQuery.canObserveCell(bot, candidate)
                    || !ObservableWorldQuery.canObserveCell(bot, candidate.up())
                    || !ObservableWorldQuery.canObserveBlock(bot, candidate.down())) {
                continue;
            }
            if (io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(world, candidate)
                    && OreScan.adjacentHazard(bot, candidate)
                    == OreScan.Observation.OBSERVED_GONE) {
                return candidate;
            }
        }
        return null;
    }

    /** Package-private deterministic geometry probe for strict-survival GameTests. */
    static BlockPos inspectApproachGoalFor(AIPlayerEntity bot,
                                           ServerWorld world,
                                           BlockPos ore) {
        return approachGoalFor(bot, world, ore);
    }

    // Hazardous fluids (lava | water) handled uniformly: water doesn't burn, but the instant it
    // floods in when the ore is mined it can push the bot and the drop away and flood the tunnel,
    // repeatedly causing approach monitoring to time out and abandon the ore (common with deep
    // water-adjacent ore). The sealing semantics are identical to lava -- place a block to replace
    // the source.
    private static AdjacentFluidObservation adjacentDangerFluidOf(AIPlayerEntity bot,
                                                                   BlockPos pos) {
        boolean unknown = false;
        for (Direction d : Direction.values()) {
            BlockPos side = pos.offset(d);
            OreScan.Observation observation = OreScan.observeDangerFluid(bot, side);
            if (observation == OreScan.Observation.OBSERVED_PRESENT) {
                return new AdjacentFluidObservation(observation, side);
            }
            unknown |= observation == OreScan.Observation.UNKNOWN;
        }
        return new AdjacentFluidObservation(unknown
                ? OreScan.Observation.UNKNOWN
                : OreScan.Observation.OBSERVED_GONE, null);
    }

    // Stall-site terrain snapshot: dump the bounding box formed by the bot and the target ore
    // (expanded 3 blocks in each direction) as ASCII, per Y layer, into the log.
    // Characters: B=bot foot cell, T=target ore, O=other ore, ~=fluid, #=solid (mineable),
    // X=solid (unmineable/bedrock), .=air.
    // Purpose: freeze the real terrain behind cases like "A* returns a path, the executor breaks
    // blocks, but dist doesn't shrink" into a deterministic repro scenario, to refine approach
    // jitter.
    private void dumpStallRegion(AIPlayerEntity bot, ServerWorld world) {
        CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "ore_dig_stall_dump");
        BlockPos b = bot.getBlockPos();
        BlockPos t = targetOre != null ? targetOre : b;
        int minX = Math.min(b.getX(), t.getX()) - 3, maxX = Math.max(b.getX(), t.getX()) + 3;
        int minY = Math.min(b.getY(), t.getY()) - 2, maxY = Math.max(b.getY(), t.getY()) + 3;
        int minZ = Math.min(b.getZ(), t.getZ()) - 3, maxZ = Math.max(b.getZ(), t.getZ()) + 3;
        BotLog.action(bot, "ore_dig_region_head",
                "bot", b.toShortString(), "target", t.toShortString(),
                "box", (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1));
        for (int y = maxY; y >= minY; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    char c;
                    if (p.equals(b)) c = 'B';
                    else if (p.equals(t)) c = 'T';
                    else if (!ObservableWorldQuery.canObserveBlock(bot, p)) c = '?';
                    else {
                        var st = world.getBlockState(p);
                        if (!st.getFluidState().isEmpty()) c = '~';
                        else if (OreScan.isOreBlock(st.getBlock())) c = 'O';
                        else if (st.getCollisionShape(world, p).isEmpty()) c = '.';
                        else c = st.getHardness(world, p) < 0 ? 'X' : '#';
                    }
                    row.append(c);
                }
                row.append('|'); // Separates x rows (each segment is a fixed z span)
            }
            BotLog.action(bot, "ore_dig_region_y", "y", y, "row", row.toString());
        }
    }

    private BlockMiner.Status beginMine(AIPlayerEntity bot, BlockPos pos) {
        bot.getActionPack().stopMovement(); // Mutual exclusion: mining the ore block itself stops approach pathfinding (the executor's DIG_THROUGH and BlockMiner don't compete for control)
        miner.begin(bot, pos, true);
        return miner.tick(bot);
    }

    /** Channel rock may never consume the finite iron/diamond mission tool. */
    private boolean failMissingMiningChannelTool(AIPlayerEntity bot) {
        String prefix = "missing_mining_channel_tool:";
        String reason = miner.failureReason();
        if (reason == null || !reason.startsWith(prefix)) {
            return false;
        }
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        String required = reason.substring(prefix.length());
        if (required.isBlank()) {
            required = "minecraft:stone_pickaxe";
        }
        if (rareExpeditionBatch) {
            // Long rare missions own a cursor-bound MiningService resource epoch.  Leave a healthy
            // target pick in hand so the generic background watcher cannot consume unbudgeted sticks
            // by crafting one ad-hoc stone pick before GoalExecutor schedules that service.
            targetOres.stream()
                    .map(Block::getDefaultState)
                    .filter(state -> ToolTier.canHarvestWithInventory(bot, state))
                    .findFirst()
                    .ifPresent(state -> ToolSelector.equipBestTool(bot, state));
            BotLog.task(bot, "ore_dig_channel_service_handoff",
                    "required", required,
                    "mission_target", rareMissionTarget,
                    "resource_epoch", resourceEpoch);
        }
        fail("need_mining_channel_tool:" + required);
        return true;
    }

    /**
     * Rotates only an uncommitted strip leg. Target breaks, pickup debt, vein work and bonus work
     * retain their existing typed-failure ownership and are never erased by this shortcut.
     */
    private boolean rerouteBlindBranchAroundHigherTierOre(AIPlayerEntity bot,
                                                           ServerWorld world,
                                                           BlockPos obstruction,
                                                           BlockPos factualRear) {
        if (stripDirIndex < 0 || targetOre != null || pendingPickupPos != null
                || activeTargetBreakPos != null || bonusOre != null || !veinQueue.isEmpty()) {
            return false;
        }
        var state = world.getBlockState(obstruction);
        if (!OreScan.isOreBlock(state.getBlock()) || OreScan.isOre(state, targetOres)) {
            return false;
        }
        int usableTier = ToolTier.bestPickaxeTier(bot);
        int blockedTier = ToolTier.requiredPickaxeTier(state.getBlock());
        if (usableTier < ToolTier.STONE || blockedTier <= usableTier) {
            return false;
        }
        rerouteBlindBranchAtObservedBoundary(
                bot, world, "tool_obstruction", obstruction, factualRear);
        BotLog.action(bot, "ore_dig_tool_obstruction_reroute",
                "at", obstruction.toShortString(),
                "block", Registries.BLOCK.getId(state.getBlock()),
                "required", ToolTier.requiredPickaxeItemId(state.getBlock()),
                "usable_tier", usableTier,
                "direction", STRIP_DIRS[stripDirIndex].asString());
        return true;
    }

    /**
     * Turns an interrupted blind leg into a local L-shaped detour without claiming that the
     * unfinished spiral edge completed. The observed forward direction is rejected first and the
     * two perpendicular directions are checked in deterministic order. Geometric reverse is
     * normally the controlled rear corridor and remains excluded even if a stale/forged fixture has
     * filled that corridor back with rock. It becomes eligible only after a successful reroute and
     * before any factual movement from that exact durable origin: a newly selected side branch can
     * expose a second fluid boundary while its reverse is still an untried fresh column. The marker,
     * direction and remaining edge budget change atomically across checkpoint restore, while the
     * first factual move clears the exception and prevents old-corridor ping-pong. If a selected
     * detour exposes another boundary before that first move, fresh solid work still wins; only
     * when every fresh candidate is unsafe may the task take one strictly observed step through a
     * dry open corridor. Physical rear, raised and one-step corridor escapes publish their normal
     * successor atomically at the factual landing, so a restart or immediate second boundary cannot
     * lose the finite turn proof and misclassify a safe face as trapped.
     */
    private boolean rerouteBlindBranchAtObservedBoundary(AIPlayerEntity bot,
                                                          ServerWorld world,
                                                          String reason,
                                                          BlockPos blocked) {
        return rerouteBlindBranchAtObservedBoundary(bot, world, reason, blocked, null);
    }

    private boolean rerouteBlindBranchAtObservedBoundary(AIPlayerEntity bot,
                                                          ServerWorld world,
                                                          String reason,
                                                          BlockPos blocked,
                                                          BlockPos factualRear) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        BlockPos origin = bot.getBlockPos();
        if (stripDirIndex < 0 || stripDirIndex >= STRIP_DIRS.length || stripStepsLeft <= 0) {
            fail("ore_dig_branch_boundary_trapped:" + reason + ":" + blocked.toShortString());
            return false;
        }
        int rejectedDirection = stripDirIndex;
        int clockwise = (rejectedDirection + 1) % STRIP_DIRS.length;
        int counterClockwise = (rejectedDirection + STRIP_DIRS.length - 1)
                % STRIP_DIRS.length;
        int reverse = (rejectedDirection + 2) % STRIP_DIRS.length;
        boolean sameOriginCascade = origin.equals(boundaryRerouteOrigin);
        if (!sameOriginCascade) {
            boundaryRerouteOrigin = null;
        }
        int[] candidates = sameOriginCascade
                ? new int[]{clockwise, counterClockwise, reverse}
                : new int[]{clockwise, counterClockwise};
        for (int candidate : candidates) {
            Direction direction = STRIP_DIRS[candidate];
            if ("open_drop".equals(reason)) {
                RaisedBoundaryLanding raised = inspectRaisedBoundaryLanding(
                        bot, world, origin, direction);
                if (raised != RaisedBoundaryLanding.NOT_APPLICABLE) {
                    BlockPos landing = origin.offset(direction).up();
                    if (raised == RaisedBoundaryLanding.READY
                            && FakePlayerMotion.jumpTo(
                            bot, landing, "ore_dig_open_drop_raised_landing")) {
                        clearStripMovementOwnership();
                        stripDirIndex = candidate;
                        publishSynchronousMove(origin, landing);
                        int closedDirection = stripDirIndex;
                        int closedLeg = stripLegIndex;
                        // This vertical escape is not progress through the selected horizontal
                        // edge and cannot own a horizontal factual rear. Close that edge and publish
                        // its normal successor in the same durable transaction, retaining only the
                        // exact-landing marker for a possible immediate second boundary.
                        publishStripSuccessor(bot, null, false);
                        boundaryRerouteOrigin = landing.toImmutable();
                        BotLog.action(bot, "ore_dig_branch_boundary_raised_landing",
                                "at", origin.toShortString(),
                                "blocked", blocked.toShortString(),
                                "from", STRIP_DIRS[rejectedDirection].asString(),
                                "via", STRIP_DIRS[closedDirection].asString(),
                                "closed_leg", closedLeg,
                                "landing", landing.toShortString(),
                                "successor", STRIP_DIRS[stripDirIndex].asString(),
                                "steps_left", stripStepsLeft);
                        return true;
                    }
                    // A one-block ledge is structurally a possible raised landing even when its
                    // observation, clearance or occupancy check fails. Never fall through and mine
                    // the block that may be the only support for the previous safe staircase.
                    continue;
                }
            }
            if (!isFreshSafeLateralBranch(bot, world, origin, direction)) {
                continue;
            }
            clearStripMovementOwnership();
            stripDirIndex = candidate;
            stripProgressPos = origin.toImmutable();
            boundaryRerouteOrigin = origin.toImmutable();
            BotLog.action(bot, "ore_dig_branch_boundary_reroute",
                    "at", origin.toShortString(),
                    "blocked", blocked.toShortString(),
                    "reason", reason,
                    "from", STRIP_DIRS[rejectedDirection].asString(),
                    "to", direction.asString(),
                    "steps_left", stripStepsLeft);
            return true;
        }
        // A branch which physically advanced into this exact lip owns one factual rear cell even
        // though the first move cleared boundaryRerouteOrigin. If neither side offers fresh work,
        // leave the unsafe edge by that single observed step and close the interrupted leg in the
        // same transaction. This retreat can never turn into a reverse-length walk through old
        // tunnels, and the exact landing marker survives an immediate successor boundary.
        Direction rearDirection = STRIP_DIRS[reverse];
        boolean progressedOpenDrop = "open_drop".equals(reason)
                && !sameOriginCascade
                && factualRear != null
                && factualRear.equals(origin.offset(rearDirection));
        if (progressedOpenDrop
                && isObservedSafeOpenEscapeCorridor(
                bot, world, origin, rearDirection)
                && FakePlayerMotion.stepToStandable(
                bot, factualRear, "ore_dig_open_drop_rear_retreat")) {
            publishSynchronousMove(origin, factualRear);
            int closedDirection = stripDirIndex;
            int closedLeg = stripLegIndex;
            // The retreat is a complete physical turn at a new safe face. Publish the normal
            // spiral successor in the same checkpoint transaction and retain only its same-origin
            // boundary marker. If that successor is immediately another observed drop, all three
            // genuinely available columns may be classified instead of losing this turn between
            // ticks and reporting a false trap (seed 3000, iron search at 48,16,-165).
            publishStripSuccessor(bot, null, false);
            boundaryRerouteOrigin = bot.getBlockPos().toImmutable();
            BotLog.action(bot, "ore_dig_branch_boundary_rear_retreat",
                    "at", origin.toShortString(),
                    "blocked", blocked.toShortString(),
                    "from", STRIP_DIRS[closedDirection].asString(),
                    "closed_leg", closedLeg,
                    "to", factualRear.toShortString(),
                    "successor", STRIP_DIRS[stripDirIndex].asString(),
                    "steps_left", stripStepsLeft);
            return true;
        }
        // This special closure is allowed only after the bot has already committed a real one-block
        // advance along this edge (seed 3000's descent exit did exactly that).  The retained rear
        // proves this is an interrupted in-flight leg rather than an initial all-air cursor fixture.
        // Close the current edge and publish its bounded successor atomically while preserving
        // the factual rear. Do not count this logical closure as progress:
        // publishStripProgress already recorded the physical move, while a fully surrounded bot
        // must still reach the ordinary no-progress fuse.
        // Gravity and higher-tier ore are both finite, dry forward walls at an otherwise safe
        // factual face. If the branch already crossed exactly one observable rear cell, close the
        // interrupted edge and publish its normal successor atomically. This preserves the ore and
        // avoids declaring a stone-tier miner trapped when old air corridors leave no fresh side
        // branch; a zero-movement cursor has no factual rear and retains the fail-closed outcome.
        boolean progressedClosableBoundary = ("gravity".equals(reason)
                || "tool_obstruction".equals(reason))
                && !sameOriginCascade
                && factualRear != null
                && factualRear.equals(origin.offset(rearDirection))
                && isObservedSafeOpenEscapeCorridor(
                bot, world, origin, rearDirection);
        if (progressedClosableBoundary) {
            int closedDirection = stripDirIndex;
            BotLog.action(bot, "ore_dig_branch_boundary_leg_closed",
                    "at", origin.toShortString(),
                    "blocked", blocked.toShortString(),
                    "reason", reason,
                    "direction", STRIP_DIRS[closedDirection].asString(),
                    "factual_rear", factualRear.toShortString(),
                    "steps_left", 0);
            // Closing and publishing the perpendicular successor are one cursor transaction. If
            // the successor is also blocked at this same face, its geometric reverse is fresh
            // territory while the two perpendicular columns are the old rear and rejected edge.
            // Dropping the crossed-rear marker here made that finite escape look fully trapped.
            publishStripSuccessor(bot, factualRear, true);
            return true;
        }
        if (sameOriginCascade) {
            for (int candidate : candidates) {
                Direction direction = STRIP_DIRS[candidate];
                if (!isObservedSafeOpenEscapeCorridor(bot, world, origin, direction)) {
                    continue;
                }
                clearStripMovementOwnership();
                stripDirIndex = candidate;
                stripStepsLeft = 1;
                stripProgressPos = origin.toImmutable();
                boundaryRerouteOrigin = origin.toImmutable();
                BotLog.action(bot, "ore_dig_branch_boundary_backtrack",
                        "at", origin.toShortString(),
                        "blocked", blocked.toShortString(),
                        "reason", reason,
                        "from", STRIP_DIRS[rejectedDirection].asString(),
                        "to", direction.asString(),
                        "escape_steps", stripStepsLeft);
                return true;
            }
        }
        fail("ore_dig_branch_boundary_trapped:" + reason + ":" + blocked.toShortString());
        return false;
    }

    private BranchFluidSealResult sealOneObservableLateralBranchFluid(
            AIPlayerEntity bot,
            ServerWorld world,
            BlockPos branchCell,
            String reason) {
        BlockPos fluid = null;
        Set<BlockPos> checked = new HashSet<>();
        for (BlockPos center : new BlockPos[]{branchCell, branchCell.up()}) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos candidate = center.offset(direction).toImmutable();
                if (!checked.add(candidate)
                        || candidate.equals(branchCell) || candidate.equals(branchCell.up())
                        || candidate.equals(bot.getBlockPos())
                        || candidate.equals(bot.getBlockPos().up())) {
                    continue;
                }
                // The explicit fluid probe includes inset face rays and is itself capped by the
                // real interaction range. Do not prefilter by center distance: an edge-visible
                // source can be placeable even when its center is just outside that sphere.
                OreScan.Observation observation = OreScan.observeDangerFluid(bot, candidate);
                if (observation == OreScan.Observation.OBSERVED_PRESENT) {
                    fluid = candidate;
                    break;
                }
            }
            if (fluid != null) {
                break;
            }
        }
        if (fluid == null) {
            return BranchFluidSealResult.CLEAR;
        }
        String fluidName = observedFluidReason(world.getFluidState(fluid));
        var blockSlot = MaterialPalette.pickSacrificialBlockSlot(
                bot, protectedStoneLikeReserve);
        if (blockSlot.isEmpty()) {
            BotLog.action(bot, "ore_dig_branch_fluid_unsealable",
                    "at", branchCell.toShortString(),
                    "fluid", fluid.toShortString(),
                    "reason", reason,
                    "protected_stone", protectedStoneLikeReserve);
            return BranchFluidSealResult.BLOCKED;
        }
        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
        ActionResult seal = BuildAction.placeBlockAt(bot, fluid);
        if (seal.isFailed()) {
            BotLog.action(bot, "ore_dig_branch_fluid_seal_failed",
                    "at", branchCell.toShortString(),
                    "fluid", fluid.toShortString(),
                    "reason", reason,
                    "placement", seal.reason());
            return BranchFluidSealResult.BLOCKED;
        }
        noteProgress();
        BotLog.action(bot, "ore_dig_branch_fluid_sealed",
                "at", branchCell.toShortString(),
                "fluid", fluid.toShortString(),
                "reason", reason,
                "direction", STRIP_DIRS[stripDirIndex].asString(),
                "steps_left", stripStepsLeft);
        BrainCoordinator.INSTANCE.sendBotReply(bot,
                "Sealed off exposed " + fluidName + " while mining -- routing around it.");
        return BranchFluidSealResult.SEALED;
    }

    /**
     * Classifies a visible one-block ledge beside an observed open drop. The shape check is kept
     * separate from the safety checks so a blocked/occupied landing cannot fall through to the
     * ordinary wall-mining path and destroy its own staircase support.
     */
    private RaisedBoundaryLanding inspectRaisedBoundaryLanding(AIPlayerEntity bot,
                                                                 ServerWorld world,
                                                                 BlockPos origin,
                                                                 Direction direction) {
        BlockPos support = origin.offset(direction);
        BlockPos landing = support.up();
        BlockPos head = landing.up();
        BlockPos takeoffHeadSweep = origin.up(2);
        OreScan.Observation landingClearance = OreScan.observe(
                bot, landing,
                state -> state.getCollisionShape(world, landing).isEmpty());
        if (landingClearance == OreScan.Observation.OBSERVED_GONE) {
            // An observed solid landing is an ordinary head-first side wall, not a raised ledge.
            // Classify that shape before asking for the jump-only head/sweep envelope; otherwise
            // the wall can occlude its lower cell and mask valid fresh branch work as UNSAFE.
            return RaisedBoundaryLanding.NOT_APPLICABLE;
        }
        if (landingClearance == OreScan.Observation.UNKNOWN
                || !canObserveWorldState(bot, support)
                || !canObserveWorldState(bot, head)
                || !canObserveWorldState(bot, takeoffHeadSweep)) {
            // UNKNOWN is structurally unsafe, not NOT_APPLICABLE: falling through to fresh-wall
            // classification could mine the only support of an occluded raised escape.
            return RaisedBoundaryLanding.UNSAFE;
        }
        var supportState = world.getBlockState(support);
        var landingState = world.getBlockState(landing);
        var headState = world.getBlockState(head);
        boolean solidSupport = !supportState.getCollisionShape(world, support).isEmpty();
        boolean openLanding = landingState.getCollisionShape(world, landing).isEmpty();
        if (!solidSupport || !openLanding) {
            return RaisedBoundaryLanding.NOT_APPLICABLE;
        }
        var sweepState = world.getBlockState(takeoffHeadSweep);
        boolean fullHeightSupport = supportState.getCollisionShape(world, support)
                .getMax(Direction.Axis.Y) >= 1.0D;
        if (!fullHeightSupport
                || supportState.getBlock() instanceof FallingBlock
                || !supportState.getFluidState().isEmpty()
                || !landingState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()
                || !sweepState.getFluidState().isEmpty()
                || !headState.getCollisionShape(world, head).isEmpty()
                || !sweepState.getCollisionShape(world, takeoffHeadSweep).isEmpty()
                || Standability.isDangerous(supportState)
                || Standability.isDangerous(landingState)
                || Standability.isDangerous(headState)
                || Standability.isDangerous(sweepState)
                || OreScan.adjacentHazard(bot, support)
                == OreScan.Observation.OBSERVED_PRESENT
                || OreScan.adjacentHazard(bot, landing)
                == OreScan.Observation.OBSERVED_PRESENT
                || OreScan.adjacentHazard(bot, head)
                == OreScan.Observation.OBSERVED_PRESENT) {
            return RaisedBoundaryLanding.UNSAFE;
        }
        Standability.clearCache();
        return Standability.isStandable(world, landing)
                ? RaisedBoundaryLanding.READY
                : RaisedBoundaryLanding.UNSAFE;
    }

    /**
     * A zero-movement cascade may use a visibly safe old corridor for exactly one physical step.
     * This is deliberately separate from fresh-branch eligibility: ordinary strip work must not
     * rotate into old air, and a corridor with an unobserved floor or adjacent hazard remains
     * fail-closed.
     */
    private boolean isObservedSafeOpenEscapeCorridor(AIPlayerEntity bot,
                                                       ServerWorld world,
                                                       BlockPos origin,
                                                       Direction direction) {
        BlockPos feet = origin.offset(direction);
        BlockPos head = feet.up();
        BlockPos floor = feet.down();
        if (!canObserveWorldState(bot, feet)
                || !canObserveWorldState(bot, head)
                || !ObservableWorldQuery.canObserveBlock(bot, floor)) {
            return false;
        }
        var feetState = world.getBlockState(feet);
        var headState = world.getBlockState(head);
        if (!feetState.getCollisionShape(world, feet).isEmpty()
                || !headState.getCollisionShape(world, head).isEmpty()
                || !feetState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()
                || OreScan.adjacentHazard(bot, feet)
                == OreScan.Observation.OBSERVED_PRESENT
                || OreScan.adjacentHazard(bot, head)
                == OreScan.Observation.OBSERVED_PRESENT) {
            return false;
        }
        return isAdjacentDryLanding(bot, world, origin, feet);
    }

    /**
     * Requires factual new work in a visible adjacent body column. An all-air column is an old
     * corridor and is deliberately rejected. A visible solid feet block may temporarily hide its
     * floor; that sealed wall is safe to open because normal strip movement rechecks Standability
     * after the feet/head blocks are gone and before entering the cell.
     */
    private boolean isFreshSafeLateralBranch(AIPlayerEntity bot,
                                             ServerWorld world,
                                             BlockPos origin,
                                             Direction direction) {
        BlockPos feet = origin.offset(direction);
        BlockPos head = feet.up();
        // Open the upper obstruction first. A safe observed head wall is finite work even when it
        // still occludes the lower cell; after opening it, the next tick factually re-observes the
        // foot cell before mining or movement. UNKNOWN therefore never becomes a content read or
        // a claim that this candidate is an old corridor.
        if (!canObserveWorldState(bot, head)) {
            return false;
        }
        var headState = world.getBlockState(head);
        boolean solidHead = !headState.getCollisionShape(world, head).isEmpty();
        if (!headState.getFluidState().isEmpty()
                || headState.getBlock() instanceof FallingBlock
                || OreScan.adjacentHazard(bot, head)
                == OreScan.Observation.OBSERVED_PRESENT
                || !isSafeBranchBlock(bot, world, head, headState, solidHead)) {
            return false;
        }
        if (solidHead) {
            return true;
        }
        if (!canObserveWorldState(bot, feet)) {
            return false;
        }
        var feetState = world.getBlockState(feet);
        boolean solidFeet = !feetState.getCollisionShape(world, feet).isEmpty();
        if (!feetState.getFluidState().isEmpty()
                || feetState.getBlock() instanceof FallingBlock
                || OreScan.adjacentHazard(bot, feet)
                == OreScan.Observation.OBSERVED_PRESENT
                || !isSafeBranchBlock(bot, world, feet, feetState, solidFeet)) {
            return false;
        }
        if (solidFeet) {
            return true;
        }
        return false;
    }

    private static boolean isSafeBranchBlock(AIPlayerEntity bot,
                                             ServerWorld world,
                                             BlockPos pos,
                                             net.minecraft.block.BlockState state,
                                             boolean solid) {
        if (!solid) {
            return state.isAir();
        }
        if (state.getHardness(world, pos) < 0.0F || world.getBlockEntity(pos) != null) {
            return false;
        }
        return !state.isToolRequired() || ToolTier.canHarvestWithInventory(bot, state);
    }

    /**
     * Keeps a head-first blind body column under channel ownership until its lower block is also
     * factually open. Without this derived owner, the one tick after the head miner reports DONE
     * has no active BlockMiner and opportunistic bonus work can preempt the still-solid foot block.
     */
    private boolean hasStagedBlindFootWork(AIPlayerEntity bot) {
        if (stripDirIndex < 0 || stripDirIndex >= STRIP_DIRS.length || stripStepsLeft <= 0
                || targetOre != null || pendingPickupPos != null
                || activeTargetBreakPos != null || !veinQueue.isEmpty()) {
            return false;
        }
        BlockPos next = bot.getBlockPos().offset(STRIP_DIRS[stripDirIndex]);
        OreScan.Observation headOpen = OreScan.observeAir(bot, next.up());
        if (headOpen != OreScan.Observation.OBSERVED_PRESENT) {
            return false;
        }
        return OreScan.observeAir(bot, next)
                != OreScan.Observation.OBSERVED_PRESENT;
    }

    // R3 opportunistic-ore scan: search a bot-centered +-2 (reach range) box for any ore that is
    // "non-target, mineable, and not adjacent to a hazardous fluid."
    // The opportunistic gain must not lower the work face's standing elevation. Ore underfoot or
    // lower would keep hollowing out the support, leaving the restored branch cursor at the old
    // elevation while the bot falls into the ore vein; so the entire lower half-space is excluded
    // fail-closed. The range is still deliberately kept small and reuses the SCAN_INTERVAL cadence;
    // the existing same-layer/above-layer opportunistic-ore contract is unchanged.
    private BlockPos scanBonusOre(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        for (BlockPos p : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 3, 2))) {
            if (p.getY() < feet.getY()) {
                continue;
            }
            if (!ObservableWorldQuery.canObserveBlock(bot, p)) {
                continue;
            }
            Block b = world.getBlockState(p).getBlock();
            if (!OreScan.isOreBlock(b) || targetOres.contains(b)) {
                continue;
            }
            BlockPos pos = p.toImmutable();
            if (oreExcluded(bot, pos) || !withinReach(bot, pos)) {
                continue;
            }
            if (OreClaims.heldByOther(bot, pos)) {
                continue;
            }
            if (!ToolTier.canHarvestWithInventory(bot, world.getBlockState(pos))) {
                continue; // Skip anything unmineable as opportunistic (e.g. mining diamond and passing emerald with only a stone pickaxe: don't swing for nothing)
            }
            if (adjacentDangerFluidOf(bot, pos).state()
                    == OreScan.Observation.OBSERVED_PRESENT) {
                continue; // Ore observed adjacent to lava/water is skipped as opportunistic; UNKNOWN must never be silently read as either safe or dangerous
            }
            return pos;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Mining assist P5 (design 5.4, off by default: explore.frontier): the cave-frontier excursion trigger.
    // ObservedGraphSearch and FrontierPlanner are pure kernels (mining/assist/); everything here just measures
    // the world into their inputs and, on acceptance, starts a detour of kind FRONTIER through the SAME engine
    // instance the P1 opportunistic detour uses (design 5.4: "Kind FRONTIER runs in the same engine").
    // ---------------------------------------------------------------------------------------------------------

    private static final int FRONTIER_UNPRODUCTIVE_COOLDOWN_TICKS = 600;
    private static final double FRONTIER_MIN_OPEN_VOLUME = 1200.0D;
    private static final int FRONTIER_MIN_HP = 16;
    private static final int FRONTIER_CANDIDATE_GRID_SPACING = 6;   // design 5.4's "6x12 grid", horizontal only (contract §1.5)
    private static final int FRONTIER_MIN_FREE_RUN = 9;             // design 5.4's "free length >= 9"
    private static final double FRONTIER_Y_BAND_HALF_WIDTH = 16.0D; // contract §1.6

    /**
     * P5 trigger (design 5.4). Returns true when a frontier excursion was just STARTED this tick (caller must
     * return without running stripMine). False leaves stripMine to run exactly as today.
     */
    private boolean explorationTick(AIPlayerEntity bot, ServerWorld world) {
        if (!MiningAssistRuntime.senseConfigured() || !MiningAssistRuntime.config().explore().frontier()) {
            return false;
        }
        if (assistDetourActive()) {
            return false; // defensive: hook 9 already owns any active detour before control reaches here
        }
        int now = bot.getEntityWorld().getServer().getTicks();
        if (now < frontierCooldownUntilServerTick) {
            return false;
        }
        MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUuid());
        if (state == null) {
            return false;
        }
        ObservedOccupancy occ = state.occupancyIfPresent();
        if (occ == null) {
            return false;
        }
        double radius = SenseBudget.sweepRadius(MinecraftAiConfig.get().perception().radius());
        Vec3d eye = bot.getEyePos();
        double openVolume = state.ring().volume(now, SweepEngine.eyeCell(eye.x, eye.y, eye.z), radius);
        if (openVolume < FRONTIER_MIN_OPEN_VOLUME) {
            return false;
        }
        if (bot.getHealth() < FRONTIER_MIN_HP) {
            return false;
        }
        if (io.github.zoyluo.minecraftai.coordination.PoiCoordinator.INSTANCE.awaitingContinue(bot)) {
            return false;
        }
        if (detourHost == null) {
            detourHost = new DetourHostImpl();
        }
        detourHost.bind(bot, world, state);
        if (detourHost.ledger().detoursDisabled()) {
            // A return that already had to rebase the cursor (design 4.3 DISABLED) ends every further excursion
            // this mission, not only ordinary ore detours: a cave excursion is the riskier walk of the two.
            return false;
        }
        if (detourHost.safety(SafeGate.Stage.START, null, null) != SafeReason.OK) {
            return false;
        }

        FrontierEnvironment env = new FrontierEnvironment(occ, state.hazards());
        BlockPos feet = bot.getBlockPos();
        List<BlockPos> candidateStands = frontierCandidateStands(occ, feet, env);
        if (candidateStands.isEmpty()) {
            return false;
        }
        java.util.Map<BlockPos, Double> costs = ObservedGraphSearch.search(feet, env);
        int bestY = io.github.zoyluo.minecraftai.mining.MiningChain.bestY(targetOres);
        List<FrontierPlanner.Candidate> scored = new ArrayList<>();
        // Keyed by the Candidate record itself (field-equality hashCode/equals, verified): recovers the
        // winning BlockPos in O(1) with no risk of two candidates colliding on cost+distance alone (a
        // real bug in an earlier draft's cost/distance rescan, since two stands CAN share
        // (observedPathCost, distanceFromBot) while differing in unknownFrac/sightings/yBand -- exactly
        // the fields that made one of them the actual winner).
        java.util.Map<FrontierPlanner.Candidate, BlockPos> standByCandidate = new java.util.LinkedHashMap<>();
        for (BlockPos stand : candidateStands) {
            Double cost = costs.get(stand);
            if (cost == null) {
                continue; // not reached by the observed graph -- excluded, never a ranking input
            }
            double dist = Math.sqrt(feet.getSquaredDistance(stand));
            double unknownFrac = frontierUnknownFraction(occ, stand);
            int sightings6 = frontierSightingsWithin(state, stand, 6);
            double yBand = Math.max(0.0D, 1.0D - Math.abs(stand.getY() - bestY) / FRONTIER_Y_BAND_HALF_WIDTH);
            FrontierPlanner.Candidate c = new FrontierPlanner.Candidate(unknownFrac, sightings6, yBand, cost, dist);
            scored.add(c);
            standByCandidate.put(c, stand);
        }
        double minUtility = MiningAssistRuntime.config().explore().frontierMinUtility();
        FrontierPlanner.Candidate best = FrontierPlanner.bestAccepted(scored, minUtility);
        if (best == null) {
            return false;
        }
        BlockPos target = standByCandidate.get(best);
        if (target == null) {
            return false; // defensive; cannot happen given best came from scored
        }
        List<BlockPos> route = ObservedGraphSearch.path(feet, target, env);
        if (route == null) {
            return false;
        }
        List<BlockPos> waypointList = cutIntoWaypoints(route, FRONTIER_CANDIDATE_GRID_SPACING);
        if (waypointList.size() > OreDigDetourEngine.FRONTIER_MAX_WAYPOINTS) {
            // Contract §1.1: this candidate's real observed-path length exceeds the hop budget even
            // though it passed on straight-line distance alone. Reject rather than partially execute,
            // but log it -- see §1.1 for why this is expected to happen often near the far end of the
            // accepted band, and why the bench must measure the resulting accept rate directly.
            detourHost.log("ore_dig_frontier_rejected_hop_cap", "waypoints", waypointList.size(),
                    "route_len", route.size() - 1, "distance", (int) best.distanceFromBot());
            return false;
        }

        if (detour == null) {
            detour = new OreDigDetourEngine();
        }
        detourState = state;
        detour.startFrontier(detourHost, new OreDigDetourEngine.FrontierSelection(waypointList));
        MiningAssistState fresh = MiningAssistRegistry.getIfPresent(bot.getUuid());
        if (fresh != null) {
            fresh.publishDetour(this, detour.phase(), detourHost.serverTick(), detourHost);
        }
        return true;
    }

    /** Design 5.4's stand test, in the same order as approachGoalFor: solid floor, air feet+head, standable,
     *  no adjacent hazard -- using this file's OWN hazard-clearance convention (contract §1.7), the same one
     *  DetourHostImpl.poseFor already uses for detour poses, not ObservedGraphSearch's narrower internal
     *  constants. */
    private static boolean frontierStandable(ObservedOccupancy occ, BlockPos pos, HazardField hazards) {
        if (occ.get(pos.down()) != ObservedOccupancy.SOLID) return false;
        if (occ.get(pos) != ObservedOccupancy.AIR) return false;
        if (occ.get(pos.up()) != ObservedOccupancy.AIR) return false;
        int lavaClearRadius = MiningAssistRuntime.config().detour().lavaClearRadius();
        if (hazards.anyLavaWithin(pos, lavaClearRadius)) return false;
        if (hazards.anyTrapWithin(pos, 3)) return false;
        return true;
    }

    /**
     * Candidate stands (contract §1.5 -- occupancy-grid substitute for design 5.4's raw ray clustering):
     * scan a horizontal grid at FRONTIER_CANDIDATE_GRID_SPACING around the bot, within the accepted
     * [MIN_DISTANCE, MAX_DISTANCE] band, at a handful of Y offsets around the bot's own Y; keep a grid
     * point only if there is a standable cell in that column AND the true line-of-sight run of
     * observed-AIR cells from the bot toward it is at least FRONTIER_MIN_FREE_RUN blocks (the "free
     * length >= 9" substitute), walked with RayGrid's DDA rather than an ad hoc interpolation so a
     * diagonal grid point is never skipped or double-visited.
     */
    private static List<BlockPos> frontierCandidateStands(ObservedOccupancy occ, BlockPos feet, FrontierEnvironment env) {
        List<BlockPos> result = new ArrayList<>();
        int maxDist = (int) FrontierPlanner.MAX_DISTANCE;
        for (int dx = -maxDist; dx <= maxDist; dx += FRONTIER_CANDIDATE_GRID_SPACING) {
            for (int dz = -maxDist; dz <= maxDist; dz += FRONTIER_CANDIDATE_GRID_SPACING) {
                double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (horiz < FrontierPlanner.MIN_DISTANCE || horiz > FrontierPlanner.MAX_DISTANCE) continue;
                if (!frontierFreeRunOk(occ, feet, dx, dz)) continue;
                for (int dy = -8; dy <= 8; dy += 4) {
                    BlockPos candidate = feet.add(dx, dy, dz);
                    if (frontierStandable(occ, candidate, env.hazards)) {
                        result.add(candidate);
                    }
                }
            }
        }
        return result;
    }

    /**
     * True line-of-sight free-run check using {@link io.github.zoyluo.minecraftai.mining.assist.RayGrid#traverse}
     * (Amanatides-Woo DDA, already used elsewhere in this package for exactly this "walk cells along a
     * line" job) instead of a rounding-based interpolation -- a prior draft's ad hoc
     * {@code Math.round(dx * (i / (float) steps))} loop can skip or double-visit a cell near a diagonal
     * line; this cannot.
     */
    private static boolean frontierFreeRunOk(ObservedOccupancy occ, BlockPos feet, int dx, int dz) {
        double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (horiz < 1.0e-6) {
            return false;
        }
        int[] openRun = {0};
        boolean[] blocked = {false};
        io.github.zoyluo.minecraftai.mining.assist.RayGrid.traverse(
                feet.getX() + 0.5D, feet.getY() + 0.5D, feet.getZ() + 0.5D,
                dx / horiz, 0.0D, dz / horiz, horiz,
                (x, y, z) -> {
                    if (x == feet.getX() && z == feet.getZ()) {
                        return true; // origin cell itself isn't part of the run away from the bot
                    }
                    if (occ.get(x, feet.getY(), z) == ObservedOccupancy.AIR) {
                        openRun[0]++;
                        return true;
                    }
                    blocked[0] = true;
                    return false;
                });
        return !blocked[0] && openRun[0] >= FRONTIER_MIN_FREE_RUN;
    }

    /** UNKNOWN fraction of the 7x7x7 window around {@code stand} (design 5.4). */
    private static double frontierUnknownFraction(ObservedOccupancy occ, BlockPos stand) {
        int unknown = 0;
        int total = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    total++;
                    if (occ.get(stand.getX() + dx, stand.getY() + dy, stand.getZ() + dz) == ObservedOccupancy.UNKNOWN) {
                        unknown++;
                    }
                }
            }
        }
        return total == 0 ? 0.0D : (double) unknown / total;
    }

    private static int frontierSightingsWithin(MiningAssistState state, BlockPos stand, int radius) {
        int count = 0;
        for (SightingLedger.Sighting s : state.sightings().snapshotSortedByValueDesc()) {
            if (Math.sqrt(stand.getSquaredDistance(s.pos())) <= radius) count++;
        }
        return count;
    }

    /** Cuts an observed route into waypoints at most {@code spacing} cells apart, always including the final cell. */
    private static List<BlockPos> cutIntoWaypoints(List<BlockPos> route, int spacing) {
        List<BlockPos> waypoints = new ArrayList<>();
        for (int i = spacing; i < route.size(); i += spacing) {
            waypoints.add(route.get(i));
        }
        BlockPos last = route.get(route.size() - 1);
        if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equals(last)) {
            waypoints.add(last);
        }
        return waypoints;
    }

    /** ObservedGraphSearch.Environment adapter over already-observed P0 state (design 5.4; no new sensing). */
    private static final class FrontierEnvironment implements ObservedGraphSearch.Environment {
        final ObservedOccupancy occ;
        final HazardField hazards;

        FrontierEnvironment(ObservedOccupancy occ, HazardField hazards) {
            this.occ = occ;
            this.hazards = hazards;
        }

        @Override public boolean isStandable(BlockPos pos) {
            return frontierStandable(occ, pos, hazards);
        }

        @Override public boolean isForbidden(BlockPos pos) {
            return hazards.anyLavaWithin(pos, ObservedGraphSearch.LAVA_CLEARANCE) || hazards.anyTrapWithin(pos, 0);
        }

        @Override public boolean isAdjacentToWater(BlockPos pos) {
            for (Direction d : Direction.values()) {
                if (hazards.anyWithin(HazardField.Kind.WATER, pos.offset(d), 0)) return true;
            }
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Mining assist R1 (design 4.14): the opportunistic valuables detour. Everything below is additive (P1
    // contract section E). The engine and its host never dig, never teleport and never bypass the exact-once
    // re-proof discipline the rest of this file already follows (I1-I3); see task/DetourHost.java for the
    // honesty rules the inner class below must keep.
    // ---------------------------------------------------------------------------------------------------------

    private OreDigTask.DetourHostImpl detourHost;        // declared here, created lazily
    private MiningAssistState detourState;               // the state the running detour trusts (M39); null when idle
    private BlockPos detourInterruptedFace;
    private int detourResumeStart = -1;
    private int detourResumeAttempts;
    private int detourResumeLastAttempt = -1_000_000;
    private int frontierCooldownUntilServerTick = -1;   // design 5.4: 600 after an unproductive excursion

    /** Whether the detour engine currently owns any part of this task's behaviour. */
    private boolean assistDetourActive() {
        return detour != null && detour.isActive();
    }

    /**
     * Ticks the opportunistic detour, if any, before the vein/bonus/target/scan ladder (hook 9). Returns true
     * when the detour owned this tick (OreDig's caller must return at once). The two static reads below and the
     * exception fence run OUTSIDE the assist-off fast path, so an idle bot with the mode off (or SENSE) pays one
     * volatile read and a return (M30, design 8.2 hook 9/13).
     */
    private boolean tickOpportunistic(AIPlayerEntity bot, ServerWorld world) {
        boolean live = detour != null && detour.isActive();
        if (!live && (!MiningAssistRuntime.senseConfigured() || !MiningAssistRuntime.config().detourActive())) {
            return false;                                     // mode off / SENSE: two static reads, no behaviour change
        }
        try {
            if (live) {
                MiningAssistState fresh = MiningAssistRegistry.getIfPresent(bot.getUuid());
                if (fresh != detourState) {
                    // The coordinator's exception fence dropped (and possibly rebuilt) the bot's state: hazards,
                    // exclusions and POI facts of the running detour are gone or belong to a different instance.
                    // A detour must never keep walking on knowledge it no longer has (I15). Ask for an abort; the
                    // engine keeps binding the OLD (now orphaned) detourState object for the rest of this detour,
                    // so no host method sees null and the RETURN that follows still releases claims and settles
                    // the ledger normally. requestAbort is a no-op once RETURN has already begun (idempotent).
                    detour.requestAbort("safety_state_lost");
                }
            } else {
                detourState = MiningAssistRegistry.getIfPresent(bot.getUuid());
                if (detourState == null) {
                    return false;                                 // the sensor never ran: nothing nominated
                }
                if (detour == null) {
                    detour = new OreDigDetourEngine();
                }
                if (detourHost == null) {
                    detourHost = new DetourHostImpl();
                }
            }
            detourHost.bind(bot, world, detourState);
            OreDigDetourEngine.Result result = detour.tick(detourHost);
            switch (result.kind()) {
                case IDLE -> {
                    return false;
                }
                case FINISHED -> {
                    if (detour.wasFrontierUnproductive()) {
                        frontierCooldownUntilServerTick = detourHost.serverTick() + FRONTIER_UNPRODUCTIVE_COOLDOWN_TICKS;
                    }
                    MiningAssistState fresh = MiningAssistRegistry.getIfPresent(bot.getUuid());
                    if (fresh != null) {
                        fresh.clearDetour();
                    }
                    detourState = null;
                    return true;
                }
                default -> {
                    MiningAssistState fresh = MiningAssistRegistry.getIfPresent(bot.getUuid());
                    if (fresh != null) {
                        fresh.publishDetour(this, detour.phase(), detourHost.serverTick(), detourHost);
                    }
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // Best-effort unwind: never let a defect anywhere in the seven writers' code (or a stub nobody
            // finished) reach TaskManager.tickAll (I8, M41). Order matters: stop movement/mining before
            // anything that might itself throw.
            try {
                bot.getActionPack().stopAll();
                miner.cancel(bot);
            } catch (RuntimeException ignored) {
                // already unwinding; the action pack or miner is in an unknown state either way
            }
            if (detour != null) {
                try {
                    detour.interrupt(detourHost, "exception");
                } catch (RuntimeException ignored) {
                }
            }
            MiningAssistState fresh = MiningAssistRegistry.getIfPresent(bot.getUuid());
            if (fresh != null) {
                if (fresh.detourControl() != null) {
                    fresh.detourControl().abandoned(MiningAssistRuntime.serverTick(bot));
                }
                fresh.clearDetour();
            }
            detourState = null;
            BotLog.error(bot, "ore_dig_detour_engine_exception", e,
                    "phase", detour == null ? "?" : detour.phase());
            return false;
        }
    }

    /** Hook 11: the checkpoint face is the live anchor face while a detour is active, else the given face. */
    private BlockPos detourPublishedFace(BlockPos face) {
        return detour != null && detour.isActive() ? detour.anchorFace() : face;
    }

    /**
     * Hook 3: a lava sighting claimed by a live detour (design 4.14, M40). The original immediate-danger guard
     * of {@code avoidObservedLava} is not dropped here: lava in or touching the bot's own cell still falls
     * through to the generic Evade/pause path. In RETURN a fresh abort would do nothing useful (the detour is
     * already walking home). If that RETURN is the detour's own answer to lava (its safety gate aborted for
     * item 7 a tick before this scan saw the same still-visible lava), the sighting is claimed: letting
     * DangerWatcher fall through to its generic Evade+pause would grow a pause frame over a task that is already
     * self-aborting for exactly this hazard, the race M40's self-abort contract exists to prevent. A RETURN for
     * any other reason is left to the generic path, whose interrupt/resume brings the bot home once safe.
     */
    private boolean detourClaimLava(AIPlayerEntity bot, BlockPos lavaPos) {
        if (lavaPos == null || bot.isInLava() || bot.isOnFire()
                || bot.getHealth() <= 8.0F || hasImmediateLava(bot)) {
            return false;
        }
        if (detour.phase() == DetourPhase.RETURN) {
            return isLavaAbort(detour.abortReason());
        }
        bot.getActionPack().stopAll();
        miner.cancel(bot);
        detour.requestAbort(SafeReason.LAVA_THREAT_BOX.abortReason());
        BotLog.danger(bot, "ore_dig_detour_lava_claimed",
                "lava", lavaPos.toShortString(), "phase", detour.phase());
        return true;
    }

    /** Whether a detour abort reason is one of the two lava items of the safety gate (design 4.12 item 7). */
    private static boolean isLavaAbort(String abortReason) {
        return SafeReason.LAVA_THREAT_BOX.abortReason().equals(abortReason)
                || SafeReason.HAZARD_LAVA.abortReason().equals(abortReason);
    }

    /**
     * Hooks 5/6: interrupts a live detour without a return (design 4.10). Returns whether THIS call interrupted
     * a live engine, so a stale flag from an earlier pause can never make a later, unrelated abort rewrite
     * {@code lastFace} (M40).
     */
    private boolean detourInterrupted(AIPlayerEntity bot) {
        if (detour == null || !detour.isActive()) {
            return false;
        }
        if (detourHost == null) {
            detourHost = new DetourHostImpl();
        }
        detourHost.bind(bot, bot.getEntityWorld(), detourState);
        DetourHost.Anchor anchor = detour.interrupt(detourHost, "paused");
        if (anchor == null) {
            return false;
        }
        lastFace = anchor.face();
        detourInterruptedFace = anchor.face();
        detourInterruptedFlag = true;
        detourResumeStart = -1;
        detourResumeAttempts = 0;
        MiningAssistState fresh = MiningAssistRegistry.getIfPresent(bot.getUuid());
        if (fresh != null) {
            fresh.clearDetour();
        }
        detourState = null;
        return true;
    }

    /**
     * Hook 6 (last statement of {@code onAbort}): re-marks {@code mine_face} at the anchor when THIS abort
     * interrupted a live detour, so the persisted "resume mining" place is the anchor, never the excursion cell
     * {@code markMineFace} just recorded at the live (aborted) feet (M40).
     */
    private void reapplyAnchor(AIPlayerEntity bot, boolean detourWasLive) {
        if (!detourWasLive) {
            return;
        }
        if (detourInterruptedFace != null) {
            lastFace = detourInterruptedFace;
            markMineFace(bot);
        }
    }

    /**
     * Hook 7 (replaces the old one-line guard): rebases the target monitors once per interruption and clears
     * the sticky flag as soon as there is nothing left to walk back to (a pause with zero transit), instead of
     * leaving it set for the rest of the task (M40).
     */
    private void detourResumed(boolean restoringFace) {
        if (!detourInterruptedFlag) {
            return;
        }
        detourRebaseTargetMonitors();
        if (!restoringFace) {
            detourInterruptedFlag = false;
            detourInterruptedFace = null;
        }
    }

    /**
     * Named with the {@code detour} prefix (not the design's bare {@code rebaseTargetMonitors}) so it never
     * shares a simple name with the interface method {@code DetourHostImpl.rebaseTargetMonitors()} that
     * delegates to it (an unqualified same-name delegation would call itself, JLS 15.12.1).
     */
    private void detourRebaseTargetMonitors() {
        targetApproachTick = elapsed;
        if (targetOre != null) {
            lastTargetDist = Double.MAX_VALUE;
        }
    }

    /**
     * Hook 8: the walk-only resume of an interrupted detour, tried before the existing {@code returnToSavedFace}
     * machinery. While it returns true, {@code returnToSavedFace} is not called, so {@code RESTORE_FACE_LIMIT}
     * still bounds everything.
     */
    private boolean detourResumeReturn(AIPlayerEntity bot) {
        if (!detourInterruptedFlag || lastFace == null) {
            return false;
        }
        if (detourResumeStart < 0) {
            detourResumeStart = elapsed;
        }
        boolean pathIdle = bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle();
        if (bot.getBlockPos().equals(lastFace)
                || elapsed - detourResumeStart > 300
                || (detourResumeAttempts >= 3 && pathIdle)) {          // count a finished attempt, not a started one
            detourInterruptedFlag = false;
            detourInterruptedFace = null;
            return false;                                             // returnToSavedFace takes over (arrival bookkeeping too)
        }
        if (pathIdle && elapsed - detourResumeLastAttempt >= 20) {
            int minY = Math.max(MIN_Y + 1, Math.min(bot.getBlockPos().getY(), lastFace.getY()) - 1);
            ActionResult route = bot.getActionPack().startSurfacePathTo(lastFace, minY);
            detourResumeLastAttempt = elapsed;
            if (!"pathfinding_throttled".equals(route.reason())) {
                detourResumeAttempts++;
            }
            BotLog.task(bot, "ore_dig_detour_resume_return",
                    "to", lastFace.toShortString(),
                    "result", route.isFailed() ? route.reason() : "ok",
                    "attempts", detourResumeAttempts);
        }
        return true;
    }

    /**
     * The return-failure rebase (design 4.10): the current feet cell becomes the face and the strip progress
     * cursor; keeps {@code cursorOrigin} and {@code completedBatches}. Only the host calls this (it passes
     * {@code bot.getBlockPos()}).
     */
    private void detourRebaseCursor(BlockPos here) {
        lastFace = here;
        stripProgressPos = here;
        stripDirIndex = -1;
        stripLegIndex = 0;
        stripStepsLeft = 0;
        stripLegLength = STRIP_SEGMENT;
        boundaryRerouteOrigin = null;
        clearStripMovementOwnership();
    }

    /**
     * {@code Registries.BLOCK.get} of {@code id} ({@code minecraft} namespace for a bare path). An id missing
     * from the registry is reported as absent (never resolved to {@code Blocks.AIR}, for which a predicate
     * testing "is this block" would wrongly answer PRESENT over real air): every caller treats a null return as
     * "nothing to observe" (design 9, review round finding, see {@code DetourHost#observeBlockIs}).
     */
    private static Block blockOf(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        int colon = trimmed.indexOf(':');
        Identifier identifier = colon >= 0
                ? Identifier.of(trimmed.substring(0, colon), trimmed.substring(colon + 1))
                : Identifier.of("minecraft", trimmed);
        return Registries.BLOCK.getOptionalValue(identifier).orElse(null);
    }

    /**
     * Production {@link DetourHost} and {@link DetourControl}: the engine's and the coordinator's only window
     * into this task and the world. Reaches OreDig's privates because it is a non-static member class; every
     * delegation to an outer member of the same simple name is qualified (class comment of
     * {@code task/DetourHost.java}). Every block read here goes through {@code OreScan}/{@code
     * ObservableWorldQuery}/{@code Standability} exactly like the rest of this file (I1-I3): no teleport, no
     * hidden scan, no forced pickup outside the existing capability-gated {@code HarvestCore} call.
     */
    private final class DetourHostImpl implements DetourHost, DetourControl {
        private AIPlayerEntity bot;
        private ServerWorld world;
        private MiningAssistState state;
        private String routeFailure = "";
        private final Map<String, Boolean> targetIdCache = new HashMap<>();
        /**
         * The block state the last successful {@link #observeBlockIs} of that EXACT cell saw, per cell (not just
         * the last one: {@link #toolVerdict} is asked for the selector's ranked candidate and separately for the
         * engine's current member in the same tick, which are not always the same cell). Bounded: cleared
         * wholesale past a small cap rather than tracked per detour, since a detour never touches more than a
         * handful of cells.
         */
        private final Map<BlockPos, BlockState> observedStates = new HashMap<>();
        /** The mission ledger key last resolved while an origin was present (design C.1 {@code ledger()}). */
        private String lastLedgerKey;

        void bind(AIPlayerEntity bot, ServerWorld world, MiningAssistState state) {
            this.bot = bot;
            this.world = world;
            this.state = state;
        }

        // ---- DetourControl --------------------------------------------------------------------------------

        @Override
        public void abortNow(String reason) {
            if (bot != null) {
                bot.getActionPack().stopAll();
                miner.cancel(bot);
            }
            if (detour != null) {
                detour.requestAbort(reason);
            }
        }

        @Override
        public void abandoned(int serverTick) {
            if (detour != null) {
                detour.abandon(detourHost);
            }
            detourState = null;
        }

        // ---- time, identity, position, configuration -------------------------------------------------------

        @Override
        public int now() {
            return elapsed;
        }

        @Override
        public int serverTick() {
            return MiningAssistRuntime.serverTick(bot);
        }

        @Override
        public int staggerSeed() {
            return Math.floorMod(bot.getUuid().hashCode(), 10);
        }

        @Override
        public int taskMaxElapsedTicks() {
            return maxElapsed;
        }

        @Override
        public BlockPos feet() {
            return bot.getBlockPos();
        }

        @Override
        public Vec3d eyePos() {
            return bot.getEyePos();
        }

        @Override
        public int minStandY() {
            return MIN_Y + 1;
        }

        @Override
        public boolean feetStandable() {
            BlockPos feetPos = bot.getBlockPos();
            if (!ObservableWorldQuery.canObserveCell(bot, feetPos)
                    || !ObservableWorldQuery.canObserveCell(bot, feetPos.up())
                    || !ObservableWorldQuery.canObserveBlock(bot, feetPos.down())) {
                return false;
            }
            Standability.clearCache();
            return Standability.isStandable(world, feetPos);
        }

        @Override
        public int lavaBandTopY() {
            return DetourPolicy.lavaBandTopY(BotEdits.dimensionKey(world));
        }

        @Override
        public MiningAssistConfig.Detour config() {
            return MiningAssistRuntime.config().detour();
        }

        @Override
        public boolean deterministic() {
            return MiningAssistRuntime.deterministic(bot.getUuid());
        }

        // ---- owners -----------------------------------------------------------------------------------------

        @Override
        public boolean ownersIdle() {
            return !restoringFace && miner.target() == null && pendingPickupPos == null
                    && activeTargetBreakPos == null && bonusOre == null && veinQueue.isEmpty()
                    && blockedBodyRecoveryTarget == null && !hasStagedBlindFootWork(bot)
                    && lavaReroute == null && boundaryRerouteOrigin == null
                    && pendingBlindAdvance == null
                    && bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle();
        }

        @Override
        public DetourPolicy.TargetLock targetLock() {
            if (targetOre == null) {
                return DetourPolicy.TargetLock.NONE;
            }
            return bot.getBlockPos().isWithinDistance(targetOre, DetourPolicy.TARGET_NEAR_BLOCKS)
                    ? DetourPolicy.TargetLock.NEAR : DetourPolicy.TargetLock.LOCKED;
        }

        @Override
        public boolean isTargetOre(String blockId) {
            Boolean cached = targetIdCache.get(blockId);
            if (cached != null) {
                return cached;
            }
            Block block = blockOf(blockId);
            boolean result = block != null
                    && (targetOres.contains(block)
                            || !Collections.disjoint(HarvestCore.expectedDropsFor(block), targetDrops));
            targetIdCache.put(blockId, result);
            return result;
        }

        @Override
        public boolean bonusOwns(BlockPos pos, String blockId) {
            if (bonusMined >= BONUS_CAP || blockId == null || !blockId.endsWith("_ore")) {
                return false;
            }
            BlockPos feetPos = bot.getBlockPos();
            if (Math.abs(pos.getX() - feetPos.getX()) > 2 || Math.abs(pos.getZ() - feetPos.getZ()) > 2) {
                return false;
            }
            if (pos.getY() < feetPos.getY() || pos.getY() > feetPos.getY() + 3) {
                return false;
            }
            return withinReach(bot, pos);
        }

        // ---- nominations, exclusions, claims, ledgers --------------------------------------------------------

        @Override
        public List<SightingLedger.Sighting> sightings() {
            return state == null ? List.of() : state.sightings().snapshotSortedByValueDesc();
        }

        @Override
        public void forgetSighting(BlockPos pos) {
            if (state != null) {
                state.sightings().markGone(pos);
            }
        }

        @Override
        public MissionAssistLedger.Entry ledger() {
            Optional<TaskOrigin> origin = TaskManager.INSTANCE.activeOrigin(bot);
            if (origin.isPresent()) {
                lastLedgerKey = MissionAssistLedger.keyFor(
                        bot.getUuid(), origin.get().missionId(), origin.get().jobId());
            } else if (lastLedgerKey == null) {
                lastLedgerKey = MissionAssistLedger.keyFor(bot.getUuid(), null, null);
            }
            return MissionAssistLedger.get(lastLedgerKey, serverTick());
        }

        @Override
        public boolean excluded(BlockPos pos) {
            return (state != null && state.exclusions().isExcluded(pos, serverTick())) || oreExcluded(bot, pos);
        }

        @Override
        public void exclude(BlockPos pos, int ttlServerTicks) {
            if (state != null) {
                state.exclusions().exclude(pos, serverTick(), ttlServerTicks);
            }
        }

        @Override
        public boolean shouldLogSkip(BlockPos pos) {
            return state != null && state.exclusions().shouldLogSkip(pos, serverTick());
        }

        @Override
        public boolean claimedByOther(BlockPos pos) {
            return OreClaims.heldByOther(bot, pos);
        }

        @Override
        public boolean tryClaim(BlockPos pos) {
            return OreClaims.tryClaim(bot, pos);
        }

        @Override
        public void renewClaims() {
            OreClaims.renewAll(bot);
        }

        @Override
        public void releaseClaims() {
            OreClaims.releaseAll(bot.getUuid());
        }

        // ---- observation --------------------------------------------------------------------------------------

        @Override
        public Seen observeBlockIs(BlockPos pos, String blockId) {
            Block block = blockOf(blockId);
            if (block == null) {
                return Seen.GONE;
            }
            BlockPos immutable = pos.toImmutable();
            OreScan.Observation observation = OreScan.observe(bot, pos, blockState -> {
                rememberObservedState(immutable, blockState);
                return blockState.isOf(block);
            });
            return switch (observation) {
                case OBSERVED_PRESENT -> Seen.PRESENT;
                case OBSERVED_GONE -> Seen.GONE;
                case UNKNOWN -> Seen.UNKNOWN;
            };
        }

        private void rememberObservedState(BlockPos pos, BlockState blockState) {
            if (observedStates.size() > 16) {
                observedStates.clear();
            }
            observedStates.put(pos, blockState);
        }

        @Override
        public FluidProbe probeFluidAround(BlockPos cell) {
            BlockPos present = null;
            boolean unknown = false;
            for (Direction d : new Direction[]{
                    Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                BlockPos n = cell.offset(d);
                OreScan.Observation observation = OreScan.observeDangerFluid(bot, n);
                if (observation == OreScan.Observation.OBSERVED_PRESENT) {
                    if (present == null) {
                        present = n.toImmutable();
                    }
                } else if (observation == OreScan.Observation.UNKNOWN) {
                    unknown = true;
                }
            }
            return new FluidProbe(present, unknown);
        }

        @Override
        public ObservedReach.Result observedReach(BlockPos from, BlockPos to) {
            return ObservedReach.search(state == null ? null : state.occupancyIfPresent(), from, to);
        }

        @Override
        public List<BlockPos> veinAt(BlockPos seed, String blockId, int cap) {
            Block block = blockOf(blockId);
            if (block == null) {
                return List.of();
            }
            return OreScan.veinFrom(bot, seed, OreScan.oreFamily(block), cap);
        }

        @Override
        public List<BlockPos> neighbours26Same(BlockPos around, String blockId) {
            Block block = blockOf(blockId);
            if (block == null) {
                return List.of();
            }
            List<BlockPos> result = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = around.add(dx, dy, dz);
                        if (OreScan.observe(bot, n, blockState -> blockState.isOf(block))
                                == OreScan.Observation.OBSERVED_PRESENT) {
                            result.add(n.toImmutable());
                        }
                    }
                }
            }
            return result;
        }

        // ---- pose and geometry ---------------------------------------------------------------------------------

        @Override
        public Pose poseFor(BlockPos ore, Anchor anchor, Set<BlockPos> forbiddenStands) {
            Standability.clearCache();
            if (DetourPolicy.inAnchorFloorPatch(ore, anchor.face())) {
                return null;
            }
            MiningAssistConfig.Detour cfg = config();
            BlockPos feetPos = bot.getBlockPos();
            List<BlockPos> candidates = new ArrayList<>();
            if (hasRecoverableTargetBreakPose(bot, ore)
                    && !OreDigTask.isCurrentSupport(bot, ore)
                    && (!needsTargetDropSupport(bot, ore) || hasReliableObservedDropCatch(bot, world, ore.down()))) {
                candidates.add(feetPos);
            }
            BlockPos approach = approachGoalFor(bot, world, ore);
            if (approach != null) {
                candidates.add(approach);
            }
            for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                BlockPos stand = ore.offset(d);
                if (!ObservableWorldQuery.canObserveCell(bot, stand)
                        || !ObservableWorldQuery.canObserveCell(bot, stand.up())
                        || !ObservableWorldQuery.canObserveBlock(bot, stand.down())) {
                    continue;
                }
                if (!Standability.isStandable(world, stand)) {
                    continue;
                }
                if (OreScan.adjacentHazard(bot, stand) != OreScan.Observation.OBSERVED_GONE) {
                    continue;
                }
                if (!hasReliableObservedDropCatch(bot, world, ore.down())) {
                    continue;
                }
                candidates.add(stand.toImmutable());
            }
            for (BlockPos stand : candidates) {
                if (forbiddenStands.contains(stand) || excluded(stand)) {
                    continue;
                }
                if (!DetourPolicy.standWithinLimits(stand, anchor.face(), minStandY(), cfg)) {
                    continue;
                }
                if (DetourPolicy.inAnchorFloorPatch(stand, anchor.face())) {
                    continue;
                }
                if (state != null) {
                    if (state.hazards().anyLavaWithin(stand, cfg.lavaClearRadius())) {
                        continue;
                    }
                    if (state.hazards().anyTrapWithin(stand, 3) || state.hazards().anyTrapWithin(ore, 3)) {
                        continue;
                    }
                }
                return new Pose(stand, stand.equals(feetPos));
            }
            return null;
        }

        @Override
        public boolean inBreakEnvelope(BlockPos ore) {
            return hasRecoverableTargetBreakPose(bot, ore);
        }

        @Override
        public boolean isCurrentSupport(BlockPos ore) {
            return OreDigTask.isCurrentSupport(bot, ore);
        }

        @Override
        public GeometryVerdict breakGeometry(BlockPos ore) {
            BlockPos feetPos = bot.getBlockPos();
            int dy = ore.getY() - feetPos.getY();
            if (dy >= 1) {
                BlockPos above = ore.up();
                if (!ObservableWorldQuery.canObserveBlock(bot, above)) {
                    return GeometryVerdict.ABOVE_UNOBSERVED;
                }
                if (world.getBlockState(above).getBlock() instanceof FallingBlock) {
                    return GeometryVerdict.ABOVE_FALLING;
                }
            }
            if (needsTargetDropSupport(bot, ore) && !hasReliableObservedDropCatch(bot, world, ore.down())) {
                return GeometryVerdict.CATCH_MISSING;
            }
            return GeometryVerdict.OK;
        }

        // ---- resource gates -------------------------------------------------------------------------------------

        @Override
        public ToolVerdict toolVerdict(BlockPos ore, int plannedMembers) {
            BlockState st = observedStates.get(ore);
            if (st == null) {
                if (!ObservableWorldQuery.canObserveCell(bot, ore) && !ObservableWorldQuery.canObserveBlock(bot, ore)) {
                    return ToolVerdict.NO_TOOL;
                }
                st = world.getBlockState(ore);
                rememberObservedState(ore.toImmutable(), st);
            }
            if (!ToolTier.canHarvestWithInventory(bot, st)) {
                return ToolVerdict.NO_TOOL;
            }
            ToolSelector.Selection selection = ToolSelector.equipMiningChannelTool(bot, st);
            if (selection.slot() < 0 || (st.isToolRequired() && selection.stack().isEmpty())) {
                return ToolVerdict.NO_TOOL;
            }
            ItemStack stack = selection.stack();
            int maxDamage = stack.getMaxDamage();
            int remaining = maxDamage - stack.getDamage();
            return InventoryHeadroom.durabilityOk(remaining, maxDamage, plannedMembers)
                    ? ToolVerdict.OK : ToolVerdict.WEAR;
        }

        @Override
        public boolean capacityOk(BlockPos ore, String blockId) {
            int reserve = rareExpeditionBatch ? config().minFreeSlotsRareBatch() : config().minFreeSlots();
            List<ItemStack> main = bot.getInventory().getMainStacks();
            int empty = 0;
            for (ItemStack stack : main) {
                if (stack.isEmpty()) {
                    empty++;
                }
            }
            InventoryHeadroom.Estimate estimate = InventoryHeadroom.estimate(blockId);
            if (!estimate.itemKnown()) {
                return InventoryHeadroom.unknownDropOk(empty, reserve);
            }
            Block block = blockOf(blockId);
            Set<Item> drops = block == null ? Set.of() : HarvestCore.expectedDropsFor(block);
            Item dropItem = drops.isEmpty() ? null : drops.iterator().next();
            int room = 0;
            if (dropItem != null) {
                for (ItemStack stack : main) {
                    if (!stack.isEmpty() && stack.getItem() == dropItem) {
                        room += stack.getMaxCount() - stack.getCount();
                    }
                }
            }
            return InventoryHeadroom.capacityOk(empty, room, estimate.maxItems(), reserve);
        }

        @Override
        public boolean sealMaterialOk() {
            var slot = MaterialPalette.pickSacrificialBlockSlot(bot, protectedStoneLikeReserve);
            if (slot.isEmpty()) {
                return false;
            }
            ItemStack stack = bot.getInventory().getMainStacks().get(slot.getAsInt());
            Item item = stack.getItem();
            boolean protectedStoneLike = item == Items.COBBLESTONE
                    || item == Items.COBBLED_DEEPSLATE || item == Items.BLACKSTONE;
            return protectedStoneLike
                    ? stack.getCount() - protectedStoneLikeReserve >= 2
                    : stack.getCount() >= 2;
        }

        // ---- movement -----------------------------------------------------------------------------------------

        @Override
        public boolean routeStartAllowed() {
            return RouteBudget.shared().canStart(serverTick(), deterministic());
        }

        @Override
        public RouteResult startRoute(BlockPos stand, int minY, BlockPos returnAnchorOrNull) {
            return route(stand, minY, returnAnchorOrNull, false);
        }

        @Override
        public RouteResult startReturnRoute(BlockPos face, int minY) {
            return route(face, minY, null, true);
        }

        private RouteResult route(BlockPos stand, int minY, BlockPos anchorOrNull, boolean isReturn) {
            int tick = serverTick();
            if (!isReturn && !RouteBudget.shared().canStart(tick, deterministic())) {
                return RouteResult.BUDGET;
            }
            long t0 = System.nanoTime();
            ActionResult result = (isReturn || anchorOrNull == null)
                    ? bot.getActionPack().startSurfacePathTo(stand, minY)
                    : bot.getActionPack().startSurfacePathTo(stand, minY, anchorOrNull);
            if (result.isFailed() && "pathfinding_throttled".equals(result.reason())) {
                return RouteResult.THROTTLED;                 // no search ran, no ms spent: checked before any charge
            }
            RouteBudget.shared().noteStart(tick, System.nanoTime() - t0, deterministic());
            if (result.isFailed()) {
                routeFailure = result.reason();
                return RouteResult.FAILED;
            }
            if (!stand.equals(bot.getActionPack().activePathGoal())) {
                bot.getActionPack().stopAll();
                routeFailure = "resolved_goal_mismatch";
                return RouteResult.FAILED;
            }
            return RouteResult.OK;
        }

        @Override
        public String routeFailureReason() {
            return routeFailure;
        }

        @Override
        public boolean pathIdle() {
            return bot.getActionPack().isPathExecutorIdle();
        }

        @Override
        public boolean walkIdle() {
            return bot.getActionPack().isWalkToIdle();
        }

        @Override
        public void stopAll() {
            bot.getActionPack().stopAll();
        }

        @Override
        public void cancelMining() {
            miner.cancel(bot);
        }

        // ---- mining, sealing, drops -----------------------------------------------------------------------------

        @Override
        public MineStep mineStep(BlockPos pos) {
            BlockMiner.Status status = beginMine(bot, pos);
            return switch (status) {
                case DONE -> MineStep.DONE;
                case MINING -> MineStep.MINING;
                case FAILED -> MineStep.failed(miner.failureReason());
                case IDLE -> MineStep.failed("miner_idle");
            };
        }

        @Override
        public SealResult sealOneFluidNeighbour(BlockPos fluidCell) {
            var slot = MaterialPalette.pickSacrificialBlockSlot(bot, protectedStoneLikeReserve);
            if (slot.isEmpty()) {
                return SealResult.NO_BLOCK;
            }
            InventoryAction.equipFromSlot(bot, slot.getAsInt());
            ActionResult result = BuildAction.placeBlockAt(bot, fluidCell);
            return result.isFailed() ? SealResult.FAILED : SealResult.SEALED;
        }

        @Override
        public int inventoryTotal() {
            return HarvestCore.totalInventoryCount(bot);
        }

        @Override
        public void tryForcedPickup() {
            HarvestCore.forcePickupNearbyAnyOf(bot, null, 3.0D, 3.0D);
        }

        @Override
        public DropView observeDrop(BlockPos breakCell) {
            Standability.clearCache();
            Vec3d centre = breakCell.toCenterPos();
            List<ItemEntity> nearby = bot.getEntityWorld().getEntitiesByClass(ItemEntity.class,
                    bot.getBoundingBox().expand(8.0D),
                    e -> !e.getStack().isEmpty() && ObservableWorldQuery.canObserveEntity(bot, e));
            ItemEntity best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (ItemEntity candidate : nearby) {
                double distSq = candidate.getEntityPos().squaredDistanceTo(centre);
                if (distSq > 3.5D * 3.5D) {
                    continue;
                }
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = candidate;
                }
            }
            if (best == null) {
                return DropView.NONE;
            }
            BlockPos stand = best.getBlockPos();
            boolean legal = ObservableWorldQuery.canObserveCell(bot, stand)
                    && ObservableWorldQuery.canObserveCell(bot, stand.up())
                    && ObservableWorldQuery.canObserveBlock(bot, stand.down())
                    && Standability.isStandable(world, stand)
                    && OreScan.adjacentHazard(bot, stand) == OreScan.Observation.OBSERVED_GONE;
            return new DropView(true, legal ? stand.toImmutable() : null);
        }

        // ---- safety and progress --------------------------------------------------------------------------------

        @Override
        public SafeReason safety(SafeGate.Stage stage, BlockPos pose, BlockPos ore) {
            return DetourSafetyGate.evaluate(bot, stage, pose, ore);
        }

        @Override
        public void noteProgress() {
            OreDigTask.this.noteProgress();
        }

        // ---- anchor and cursor ----------------------------------------------------------------------------------

        @Override
        public Anchor captureAnchor() {
            return new Anchor(bot.getBlockPos(), stripDirIndex, stripLegIndex, stripStepsLeft, stripLegLength);
        }

        @Override
        public void clearStripOwnership() {
            clearStripMovementOwnership();
        }

        @Override
        public boolean restoreAnchorNumbers(Anchor anchor) {
            boolean changed = stripDirIndex != anchor.stripDirIndex()
                    || stripLegIndex != anchor.stripLegIndex()
                    || stripStepsLeft != anchor.stripStepsLeft()
                    || stripLegLength != anchor.stripLegLength();
            stripDirIndex = anchor.stripDirIndex();
            stripLegIndex = anchor.stripLegIndex();
            stripStepsLeft = anchor.stripStepsLeft();
            stripLegLength = anchor.stripLegLength();
            return changed;
        }

        @Override
        public void rebaseTargetMonitors() {
            OreDigTask.this.detourRebaseTargetMonitors();
        }

        @Override
        public void rebaseCursorHere() {
            OreDigTask.this.detourRebaseCursor(bot.getBlockPos());
        }

        // ---- logging and side effects ---------------------------------------------------------------------------

        @Override
        public void log(String event, Object... kv) {
            MiningAssistLog.noteDetourEvent(state, event);
            BotLog.task(bot, event, kv);
        }

        @Override
        public void warn(String event, Object... kv) {
            MiningAssistLog.noteDetourEvent(state, event);
            BotLog.warn(LogCategory.TASK, bot, event, kv);
        }

        @Override
        public void announce(String blockId) {
            BrainCoordinator.INSTANCE.sendBotReply(bot, DetourPolicy.announceText(blockId));
        }

        @Override
        public void recordFind(BlockPos pos, String blockId) {
            Block block = blockOf(blockId);
            if (block == null) {
                return;
            }
            EpisodeLog.INSTANCE.record(bot, EpisodeLog.Type.RESOURCE_FOUND, pos,
                    Registries.BLOCK.getId(block).toString());
        }

        @Override
        public void panoramaBurst() {
            state.requestBreakthrough(serverTick());
        }
    }

    // Prospecting: when no ore is found nearby, locate the nearest target ore over the wider
    // PROSPECT_RANGE (only scans loaded chunks); rate-limited to protect TPS.
    private BlockPos prospect(AIPlayerEntity bot, ServerWorld world) {
        int now = bot.getEntityWorld().getServer().getTicks();
        if (now - lastProspectTick < PROSPECT_INTERVAL) {
            return null;
        }
        lastProspectTick = now;
        // Blacklist filter: without posFilter, ore just excluded by unreachable_skip would get
        // picked right back up by prospect unchanged --
        // skip -> prospect -> same ore -> skip, an infinite loop until no_progress (geo_rich batch
        // testing showed this happening five times in a row at 637,47,-11).
        return OreProspector.nearest(bot, PROSPECT_RANGE,
                state -> OreScan.isOre(state, targetOres),
                p -> !oreExcluded(bot, p));
    }

    private BlockPos nearestOre(AIPlayerEntity bot, ServerWorld world) {
        BlockPos origin = bot.getBlockPos();
        BlockPos min = origin.add(-SCAN_RADIUS, -VERTICAL_SCAN, -SCAN_RADIUS);
        BlockPos max = origin.add(SCAN_RADIUS, VERTICAL_SCAN, SCAN_RADIUS);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (oreExcluded(bot, pos)
                    || !io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, pos)
                    || !OreScan.isOre(world.getBlockState(pos), targetOres)) {
                continue;
            }
            rememberObservedHighWorkPose(bot, world, pos);
            double dist = origin.getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private boolean canHarvestAnyTarget(AIPlayerEntity bot) {
        for (Block ore : targetOres) {
            if (ToolTier.canHarvestWithInventory(bot, ore.getDefaultState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean withinReach(AIPlayerEntity bot, BlockPos pos) {
        return bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) <= REACH_SQUARED;
    }

    private static boolean canBreakTargetFromHere(AIPlayerEntity bot, BlockPos pos) {
        // The shared physical-pickup fallback can route to the mined cell or its lower ring. A
        // lateral target two blocks above the bot is still inside vanilla eye reach, but the drop
        // can remain on the raised ledge while both recovery levels are unreachable. Finish the
        // exact raised work-pose approach first. No ore above this envelope is opened from below:
        // an open column does not contain vanilla drop launch drift.
        return hasRecoverableTargetBreakPose(bot, pos)
                && bot.getActionPack().isPathExecutorIdle()
                && bot.getActionPack().isWalkToIdle();
    }

    /** Geometry from which a natural target drop remains inside an ordinary recovery envelope. */
    private static boolean hasRecoverableTargetBreakPose(AIPlayerEntity bot, BlockPos pos) {
        BlockPos feet = bot.getBlockPos();
        int vertical = pos.getY() - feet.getY();
        int horizontalManhattan = Math.abs(feet.getX() - pos.getX())
                + Math.abs(feet.getZ() - pos.getZ());
        return vertical >= MIN_TARGET_BREAK_DY
                && vertical <= MAX_TARGET_BREAK_DY
                && horizontalManhattan <= 1
                && (vertical < MAX_TARGET_BREAK_DY || horizontalManhattan == 0)
                && withinReach(bot, pos);
    }

    /**
     * Same-level and one-lower cardinal targets can drop straight through an adjacent open shaft
     * while leaving the bot on the rim. Higher targets first move to a real high work pose; the
     * remaining vertical geometries have dedicated descent recovery and do not depend on spare blocks.
     */
    private static boolean needsTargetDropSupport(AIPlayerEntity bot, BlockPos ore) {
        BlockPos feet = bot.getBlockPos();
        int vertical = ore.getY() - feet.getY();
        int horizontalManhattan = Math.abs(feet.getX() - ore.getX())
                + Math.abs(feet.getZ() - ore.getZ());
        return horizontalManhattan == 1 && (vertical == 0 || vertical == -1);
    }

    private static boolean targetBreakEnvelope(BlockPos feet, BlockPos pos) {
        int vertical = pos.getY() - feet.getY();
        int horizontalChebyshev = Math.max(
                Math.abs(feet.getX() - pos.getX()),
                Math.abs(feet.getZ() - pos.getZ()));
        int horizontalManhattan = Math.abs(feet.getX() - pos.getX())
                + Math.abs(feet.getZ() - pos.getZ());
        return vertical >= MIN_TARGET_BREAK_DY
                && vertical <= MAX_TARGET_BREAK_DY
                && horizontalChebyshev <= 1
                && horizontalManhattan <= 1;
    }

    private static boolean isCurrentSupport(AIPlayerEntity bot, BlockPos pos) {
        return pos != null && pos.equals(bot.getBlockPos().down());
    }

    /**
     * Moves to a nearby observed stand before mining the current support block. Returns false only
     * when every visible candidate is definitively unusable; a running/throttled path is retried.
     */
    private boolean moveOffSupport(AIPlayerEntity bot, BlockPos support) {
        if (!bot.getActionPack().isPathExecutorIdle()) {
            return true;
        }
        BlockPos feet = bot.getBlockPos();
        boolean throttled = false;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int dy : new int[]{0, -1, 1}) {
                BlockPos candidate = feet.offset(direction).up(dy);
                // Leaving upward can put the old support two blocks below the new feet. The
                // ordinary approach controller then descends straight back onto that support,
                // creating an endless leave-support/descend-into loop. Only start a relocation
                // that remains inside the exact finite-drop work envelope; otherwise blacklist
                // this awkward ore and continue the bounded search for a recoverable one.
                if (!targetBreakEnvelope(candidate, support)
                        || !ObservableWorldQuery.canObserveCell(bot, candidate)
                        || !ObservableWorldQuery.canObserveCell(bot, candidate.up())
                        || !ObservableWorldQuery.canObserveBlock(bot, candidate.down())
                        || !io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(
                                bot.getEntityWorld(), candidate)) {
                    continue;
                }
                ActionResult result = bot.getActionPack().startPathTo(
                        candidate, protectedStoneLikeReserve);
                if (!result.isFailed()) {
                    BotLog.action(bot, "ore_dig_leave_support",
                            "ore", support.toShortString(), "to", candidate.toShortString());
                    noteProgress();
                    return true;
                }
                throttled |= "pathfinding_throttled".equals(result.reason());
            }
        }
        return throttled;
    }

    // The next cell toward the target: vertical takes priority (dig down if the target is lower), otherwise the larger horizontal component (avoids cutting diagonally through a wall corner).
    private static BlockPos stepToward(BlockPos from, BlockPos target) {
        int dy = target.getY() - from.getY();
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        if (dy < 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            return from.down();
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return from.offset(dx > 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST);
        }
        if (dz != 0) {
            return from.offset(dz > 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH);
        }
        if (dy < 0) {
            return from.down();
        }
        if (dy > 0) {
            return from.up();
        }
        return null;
    }
}
