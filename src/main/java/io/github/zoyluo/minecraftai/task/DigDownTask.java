package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * DIGDOWN: stands in place and digs a vertical shaft, collecting N target blocks (e.g. cobblestone).
 * Designed specifically for GoalExecutor's MINE step.
 *
 * It does not target a specific block; the first attempt at the current shaft entry digs normally,
 * and only an entry already observed to have failed switches columns via survival pathfinding:
 *  - If the block underfoot is already the target block (stone) -> mine it, count it toward output;
 *  - If the block underfoot is dirt/grass/sand etc. (a non-target block) -> mine through it too
 *    (a surface bot must dig through the topsoil layer before reaching stone), which is exactly
 *    the fix for the case observed in test #9, "standing on grass with no adjacent stone instantly
 *    fails as no_reachable";
 *  - Mine one cell, let the bot naturally fall one cell, mine the next cell, and so on downward
 *    until enough is collected.
 *
 * Safety: before digging each cell, check the two cells directly below for lava/void; on hitting a
 * fluid or reaching the bedrock layer, fail and hand off to GoalExecutor.
 * Digging goes through the shared primitive {@link BlockMiner} (only starts when idle, never
 * re-issues mid-break and resets progress, always uses the correct face).
 *
 * Self-contained state machine (iron rule G1); no internal assign; entirely on the main thread (G2).
 */
public final class DigDownTask extends AbstractTask implements CheckpointableTask {
    private static final int CHECKPOINT_SCHEMA = 4;
    private static final int RETURN_OUTCOME_CHECKPOINT_SCHEMA = 3;
    private static final int WATER_SEAL_CHECKPOINT_SCHEMA = 2;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 1;
    private static final int MAX_ELAPSED_BASE = 2400;   // small quotas still keep a 2-minute hard timeout
    private static final int MAX_ELAPSED_CAP = 24000;   // large quotas must still be bounded (20 minutes)
    private static final int MAX_ELAPSED_PER_BLOCK = 80;
    private static final int NO_PROGRESS_LIMIT = 200;   // no progress (no block broken) within 10s = fail
    private static final int PICKUP_GRACE_TICKS = 30;   // after collecting enough, wait a bit longer to let drops land in inventory
    private static final int MIN_Y = -60;               // don't dig through below bedrock
    private static final int RETURN_LIMIT = 600;        // ordinary return trip hard cap (30s)
    private static final int RETURN_SAFETY_LIMIT = 2400; // bounded reconnect cap after a safety-task displacement (2min)
    private static final int RETURN_STALL_LIMIT = 600;  // fail-closed if 30s pass with zero approach to the factual waypoint
    private static final int MAX_DESCENT = 24;          // descent depth cap (cells): exceeding it without collecting enough is usually drops not picked up -- sweep and recheck once; never dig endlessly deeper and get surrounded by mobs
    private static final int MAX_TRAIL_POINTS = 512;
    private static final int RETURN_PATH_RETRY = 20;
    private static final int ENTRY_RELOCATION_LIMIT = 600;
    private static final int[] ENTRY_RELOCATION_DISTANCES = {4, 8, 14};
    private static final int[][] ENTRY_RELOCATION_DIRECTIONS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1}
    };
    // A factual staircase can lose supports to falling terrain and require vanilla pillar repairs
    // on the way out. Stone bootstrap steps therefore reserve at most one support per descended
    // layer; completion is still decided from the net inventory delta after exact return.

    enum Phase {
        DESCEND,
        RETURN,
        DONE
    }

    private enum TrailUpdate {
        UNCHANGED,
        APPENDED,
        DISCONNECTED,
        LIMIT
    }

    /** Why a walked step runs: the diagonal stair down, the horizontal advance, or one step of the return up the recorded trail. */
    private enum StepPurpose {
        STAIR,
        HORIZONTAL,
        RETURN
    }

    /** Ticks a bot that lost a step in the air is given to land before its pose is recorded anyway (a fall is a few ticks). */
    private static final int UNSETTLED_LIMIT = 100;

    /**
     * A failed descent is not allowed to strand the bot below its exact entry point.  Persist the
     * terminal outcome while the same factual trail is being unwound; only after that debt is
     * settled does the task expose FAILED to GoalExecutor and permit a remaining-quota replan.
     */
    enum ReturnOutcome {
        COMPLETE,
        TIMEOUT,
        NEED_BETTER_TOOL,
        NO_PROGRESS,
        TRAIL_LIMIT,
        WALLED,
        SAFETY_INTERRUPTED,
        NET_DELIVERY_SHORTFALL
    }

    private final Block targetBlock;
    private final Set<Item> targetDrops;
    private final int targetCount;
    private final int maxWorkBudget;
    private final DigDownCheckpoint restored;
    private final boolean invalidCheckpoint;
    private final BlockMiner miner = new BlockMiner();
    private static final Direction[] HDIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private int hdirIndex;    // current direction of horizontal digging after hitting bedrock
    private int stairDirIndex; // current horizontal direction of the diagonal-down staircase (index into HDIRS)
    private boolean horizontalMode; // once switched to horizontal digging after reaching the descent cap/hitting bedrock, this switch is permanent, routed through the shared primitive + watchdog (fixes MAX_DESCENT self-resetting every tick)
    private BlockPos rejectedLandingOrigin;
    private int rejectedLandingDirections;

    private int invBaseline;
    private int collected;
    private int pickupGrace;
    private BlockPos startPos;       // the shaft entry position before digging (surface); once enough is collected, climb back here to complete, so as not to get stuck at the bottom of the shaft
    private int targetY;             // the lowest Y actually reached; the checkpoint uses it to verify the trail hasn't been truncated/forged
    private Phase phase = Phase.DESCEND;
    private int returnStartTick;
    private int workBudgetOffset;
    private int workBudgetAtReturn;
    private int returnBudgetOffset;
    private int lastProgressBudget;
    private final List<BlockPos> descentTrail = new ArrayList<>();
    private int returnTrailIndex;
    private int lastReturnPathAttemptBudget;
    private boolean returnPathFallback;
    private int lastReturnProgressBudget;
    private int returnProgressWaypointIndex;
    private long returnBestDistanceSquared;
    private boolean returnSafetyRecovery;
    private ReturnOutcome returnOutcome = ReturnOutcome.COMPLETE;

    /** What the walked step in flight is for (see {@link #launchStep}); null while none is. Never persisted: a checkpoint holds the trail, not a step. */
    private StepPurpose stepPurpose;
    /** Exact ActionPack admission for {@link #step}; foreign step results never settle this trail. */
    private ActionPack.StepLease stepLease;
    private WalkedStep step;
    private BlockPos stepOrigin;
    private int stepDirIndex;
    /** Set when a step was abandoned or failed: the bot may be in the air between two trail cells, so no pose is recorded until it stands on something. */
    private boolean poseUnsettled;
    private int unsettledTicks;
    private final List<BlockPos> entryRelocationCandidates = new ArrayList<>();
    private int entryRelocationIndex;
    private int entryRelocationAttempts;
    private int entryRelocationStartElapsed;
    private BlockPos entryRelocationTarget;
    private String entryRelocationLastFailure = "none";

    public DigDownTask(Block targetBlock, int targetCount) {
        this(targetBlock, targetCount, Map.of());
    }

    public DigDownTask(Block targetBlock, int targetCount, Map<String, String> checkpoint) {
        this.targetBlock = targetBlock;
        this.targetCount = Math.max(1, targetCount);
        this.maxWorkBudget = maxWorkBudgetForTarget(
                BuiltInRegistries.BLOCK.getKey(targetBlock).toString(), this.targetCount);
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        this.restored = DigDownCheckpoint.decode(values).orElse(null);
        this.invalidCheckpoint = !values.isEmpty()
                && (restored == null
                || !restored.targetBlockId().equals(BuiltInRegistries.BLOCK.getKey(targetBlock).toString())
                || restored.targetCount() != this.targetCount);
        Set<Item> drops = new HashSet<>(HarvestCore.expectedDropsFor(Set.of(targetBlock)));
        if (targetBlock == Blocks.STONE) {
            // The deep layer (Y<0) is all deepslate (mining it drops cobbled_deepslate), and ancient
            // ruins are blackstone -- both count as "stone material", otherwise the deep layer could
            // never gather enough cobblestone to make a furnace (the observed root cause of the
            // Y=-59 infinite loop).
            drops.add(Items.COBBLED_DEEPSLATE);
            drops.add(Items.BLACKSTONE);
        }
        this.targetDrops = drops;
    }

    @Override
    public String name() {
        return "dig_down";
    }

    @Override
    public String describe() {
        return "DigDown " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(targetBlock).getPath()
                + " " + collected + "/" + targetCount;
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
        // During descent the bot digs in place with its position essentially unchanged; treat it as
        // waiting so StuckWatcher doesn't misjudge it, leaving this task's own NO_PROGRESS_LIMIT
        // watchdog responsible for stall protection.
        return true;
    }

    /** Mining-assist design 6.1: the POI coordinator's per-task-class notice variant needs to know whether this
     * task is currently descending, as opposed to climbing back out (RETURN) or finished (DONE). */
    public boolean isDescending() {
        return phase == Phase.DESCEND;
    }

    /**
     * A nearby observed lava cell closes this descent entry; the safe response is to repay the
     * task's factual staircase debt, not to hand a sealed underground pose to generic Evade.
     * Contact/fire/low-health/water states deliberately decline ownership so the global emergency
     * layer keeps priority. Repeated scans while RETURN is active are idempotent and never reset its
     * cursor or aggregate budget.
     */
    boolean claimObservedLavaReturn(AIPlayerEntity bot, BlockPos lavaPos) {
        if (lavaPos == null
                || startPos == null
                || descentTrail.isEmpty()
                || phase == Phase.DONE
                || state != TaskState.RUNNING && state != TaskState.PAUSED
                || bot.isInLava()
                || bot.isOnFire()
                || bot.isUnderWater()
                || bot.hurtTime > 0
                || bot.getHealth() <= 8.0F
                || !bot.level().getFluidState(lavaPos).is(FluidTags.LAVA)
                || !ObservableWorldQuery.canObserveBlock(bot, lavaPos)) {
            return false;
        }
        BlockPos current = bot.blockPosition();
        if (!bot.level().getFluidState(current).isEmpty()
                || !bot.level().getFluidState(current.above()).isEmpty()) {
            return false;
        }

        boolean transitionedToLavaReturn = false;
        if (phase == Phase.DESCEND) {
            abandonStep(bot);
            TrailUpdate update = rememberPose(bot);
            beginReturn(bot, update == TrailUpdate.LIMIT
                    ? ReturnOutcome.TRAIL_LIMIT : ReturnOutcome.WALLED);
            transitionedToLavaReturn = returnOutcome == ReturnOutcome.WALLED;
        } else if (returnOutcome == ReturnOutcome.COMPLETE
                || returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED) {
            // onPause() already published the exact cursor and safety hard-clock lease. Upgrade only
            // the terminal cause; beginReturn() here would forge a fresh cursor/budget window.
            returnOutcome = ReturnOutcome.WALLED;
            transitionedToLavaReturn = true;
        }

        // WALLED is the existing durable "this entry is factually closed" outcome. Record the same
        // episode exclusion even when a stronger pre-existing return failure keeps its own reason.
        EpisodeMemory.INSTANCE.exclude(bot.getUUID(), startPos,
                bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        if (transitionedToLavaReturn) {
            BotLog.danger(bot, "dig_down_observed_lava_return",
                    "at", current.toShortString(),
                    "lava", lavaPos.toShortString(),
                    "entry", startPos.toShortString(),
                    "state", state,
                    "return_index", returnTrailIndex,
                    "return_budget", returnBudget());
        }
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("dig_down_invalid_checkpoint");
            return;
        }
        if (restored != null) {
            // A DESCEND cursor is only factual at the last recorded stair cell. Replanning may
            // insert a surface gather before this MINE step; that task legitimately moves the bot
            // and makes the old local staircase unusable. Restart fresh at the current supported
            // pose instead of restoring an already-exhausted no-progress clock against stale
            // coordinates. RETURN debt is never discarded here.
            BlockPos restoredPose = restored.trail().isEmpty()
                    ? null : restored.trail().getLast();
            if (restored.resumePhase() != Phase.DESCEND
                    || restoredPose == null
                    || bot.blockPosition().equals(restoredPose)) {
                restoreCheckpoint(bot);
                return;
            }
            BotLog.task(bot, "dig_down_descend_checkpoint_discarded",
                    "saved", restoredPose.toShortString(),
                    "current", bot.blockPosition().toShortString(),
                    "reason", "pose_moved_before_resume");
        }
        // strict_survival does not allow pre-reading underground fluids. The first attempt at an
        // entry digs directly; only an entry that has already failed with WALLED and been written to
        // EpisodeMemory within the same goal triggers a physical column switch on replan. Before
        // relocation completes, no inventory baseline, trail, or checkpoint is established, so a
        // restart can safely reselect.
        int now = bot.level().getServer().getTickCount();
        if (EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), bot.blockPosition(), now)) {
            prepareEntryRelocation(bot);
            return;
        }
        initializeFreshDescent(bot);
    }

    private void prepareEntryRelocation(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        startPos = null;
        descentTrail.clear();
        entryRelocationCandidates.clear();
        entryRelocationIndex = 0;
        entryRelocationAttempts = 0;
        entryRelocationStartElapsed = elapsed;
        entryRelocationTarget = null;
        entryRelocationLastFailure = "none";

        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        int now = bot.level().getServer().getTickCount();
        for (int dist : ENTRY_RELOCATION_DISTANCES) {
            for (int[] direction : ENTRY_RELOCATION_DIRECTIONS) {
                int x = feet.getX() + direction[0] * dist;
                int z = feet.getZ() + direction[1] * dist;
                // Flat and gently rolling terrain is the common case. Try the factual current
                // surface level first; a distant heightmap can resolve to a roof/tree/overhang
                // that is technically the top block but has no reversible route from this entry.
                addEntryRelocationCandidate(bot, world, new BlockPos(x, feet.getY(), z), now);
                int topY = world.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (topY != feet.getY()) {
                    addEntryRelocationCandidate(bot, world, new BlockPos(x, topY, z), now);
                }
            }
        }
        startNextEntryRelocation(bot);
    }

    private void addEntryRelocationCandidate(AIPlayerEntity bot,
                                             ServerLevel world,
                                             BlockPos candidate,
                                             int now) {
        BlockPos immutable = candidate.immutable();
        if (Standability.isStandable(world, immutable)
                && !EpisodeMemory.INSTANCE.isExcluded(bot.getUUID(), immutable, now)
                && !entryRelocationCandidates.contains(immutable)) {
            entryRelocationCandidates.add(immutable);
        }
    }

    private boolean startNextEntryRelocation(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        while (entryRelocationIndex < entryRelocationCandidates.size()) {
            BlockPos candidate = entryRelocationCandidates.get(entryRelocationIndex++);
            entryRelocationAttempts++;
            if (!Standability.isStandable(world, candidate)
                    || EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUUID(), candidate, bot.level().getServer().getTickCount())) {
                entryRelocationLastFailure = "candidate_became_unavailable";
                continue;
            }
            ActionResult result = bot.getActionPack().startSurfacePathTo(candidate);
            if (result.isFailed()) {
                entryRelocationLastFailure = result.reason();
                continue;
            }
            BlockPos resolved = bot.getActionPack().activePathGoal();
            if (resolved == null || !resolved.equals(candidate)) {
                bot.getActionPack().stopAll();
                entryRelocationLastFailure = "endpoint_not_exact";
                continue;
            }
            entryRelocationTarget = candidate;
            BotLog.action(bot, "dig_down_entry_relocation_path",
                    "to", candidate.toShortString(),
                    "attempt", entryRelocationAttempts);
            return true;
        }
        bot.getActionPack().stopAll();
        fail("dig_down_no_reachable_entry_column:attempted=" + entryRelocationAttempts
                + ":last=" + entryRelocationLastFailure);
        return false;
    }

    private void tickEntryRelocation(AIPlayerEntity bot) {
        if (elapsed - entryRelocationStartElapsed > ENTRY_RELOCATION_LIMIT) {
            bot.getActionPack().stopAll();
            fail("dig_down_no_reachable_entry_column:attempted=" + entryRelocationAttempts
                    + ":last=relocation_timeout");
            return;
        }
        BlockPos current = bot.blockPosition();
        if (entryRelocationTarget != null && current.equals(entryRelocationTarget)) {
            if (Standability.isStandable(bot.level(), current)
                    && !EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUUID(), current, bot.level().getServer().getTickCount())) {
                bot.getActionPack().stopAll();
                BotLog.action(bot, "dig_down_entry_relocation_complete",
                        "to", current.toShortString(),
                        "attempt", entryRelocationAttempts);
                initializeFreshDescent(bot);
                return;
            }
            bot.getActionPack().stopAll();
            entryRelocationLastFailure = "arrival_became_unavailable";
            entryRelocationTarget = null;
        } else if (entryRelocationTarget != null && bot.getActionPack().isPathExecutorIdle()) {
            entryRelocationLastFailure = "path_ended_before_target";
            entryRelocationTarget = null;
        }
        if (entryRelocationTarget == null) {
            startNextEntryRelocation(bot);
        }
    }

    private void initializeFreshDescent(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        lastProgressBudget = 0;
        pickupGrace = 0;
        startPos = bot.blockPosition();   // remember the shaft entry, return here once enough is collected
        targetY = startPos.getY();
        phase = Phase.DESCEND;
        returnStartTick = 0;
        workBudgetOffset = -elapsed;
        workBudgetAtReturn = 0;
        returnBudgetOffset = 0;
        hdirIndex = 0;
        stairDirIndex = 0;
        horizontalMode = false;
        descentTrail.clear();
        descentTrail.add(startPos.immutable());
        returnTrailIndex = -1;
        lastReturnPathAttemptBudget = -RETURN_PATH_RETRY;
        returnPathFallback = false;
        lastReturnProgressBudget = 0;
        returnProgressWaypointIndex = -1;
        returnBestDistanceSquared = -1L;
        returnSafetyRecovery = false;
        returnOutcome = ReturnOutcome.COMPLETE;
        clearRejectedLandingDirections();
    }

    private void restoreCheckpoint(AIPlayerEntity bot) {
        invBaseline = restored.inventoryBaseline();
        collected = restored.collected();
        pickupGrace = restored.pickupGrace();
        startPos = restored.startPos();
        targetY = restored.targetY();
        phase = restored.resumePhase();
        boolean promotedToReturn = restored.phase() == Phase.DESCEND && phase == Phase.RETURN;
        hdirIndex = restored.horizontalDirection();
        stairDirIndex = restored.stairDirection();
        horizontalMode = restored.horizontalMode();
        workBudgetOffset = restored.workBudgetUsed();
        workBudgetAtReturn = restored.workBudgetUsed();
        returnBudgetOffset = promotedToReturn ? 0 : restored.returnBudgetUsed();
        returnStartTick = elapsed;
        lastProgressBudget = restored.lastProgressBudget();
        descentTrail.clear();
        descentTrail.addAll(restored.trail());
        returnTrailIndex = promotedToReturn
                ? Math.max(-1, descentTrail.size() - 2) : restored.returnTrailIndex();
        lastReturnPathAttemptBudget = promotedToReturn
                ? -RETURN_PATH_RETRY : restored.lastReturnPathAttemptBudget();
        returnPathFallback = !promotedToReturn && restored.returnPathFallback();
        lastReturnProgressBudget = promotedToReturn
                ? 0 : restored.lastReturnProgressBudget();
        returnProgressWaypointIndex = promotedToReturn
                ? returnTrailIndex : restored.returnProgressWaypointIndex();
        returnBestDistanceSquared = promotedToReturn
                ? -1L : restored.returnBestDistanceSquared();
        returnSafetyRecovery = !promotedToReturn && restored.returnSafetyRecovery();
        returnOutcome = restored.returnOutcome();
        if (phase == Phase.RETURN) {
            // A process snapshot may capture this task while it is paused, before the safety task
            // displaces the player and before onResume() can establish a new distance baseline.
            // Rebase only the physical distance geometry at the restored pose. The persisted hard
            // budget and last-progress budget remain untouched, so restarts cannot extend either
            // the 2400-tick safety cap or the current no-progress lease.
            returnProgressWaypointIndex = returnTrailIndex;
            BlockPos waypoint = pendingReturnWaypoint();
            returnBestDistanceSquared = waypoint == null
                    ? -1L : squaredDistance(bot.blockPosition(), waypoint);
        }
        rememberUnusableEntry(bot, returnOutcome);
        if (promotedToReturn) {
            clearRejectedLandingDirections();
        } else {
            rejectedLandingOrigin = restored.rejectedLandingOrigin();
            rejectedLandingDirections = restored.rejectedLandingDirections();
        }
        bot.getActionPack().stopAll();
        abandonStep(bot);
        poseUnsettled = true; // a restored bot may be mid-air: its pose is recorded only once it stands on something
        unsettledTicks = 0;
        BotLog.task(bot, "dig_down_restored",
                "phase", phase,
                "start", startPos.toShortString(),
                "target_y", targetY,
                "trail", descentTrail.size(),
                "return_index", returnTrailIndex,
                "return_budget", returnBudgetOffset);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        abandonStep(bot);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        abandonStep(bot);
        if (startPos == null) {
            return;
        }
        if (phase == Phase.DESCEND) {
            TrailUpdate update = rememberPose(bot);
            // A safety task is allowed to move the bot after pause().  Publish durable RETURN
            // debt now, while the last factual work cell is still known, so a paused snapshot or
            // a process restart can never reinterpret the displaced underground pose as a fresh
            // mine entrance.  The batch may be replanned only after the exact surface debt is paid.
            beginReturn(bot, update == TrailUpdate.LIMIT
                    ? ReturnOutcome.TRAIL_LIMIT : ReturnOutcome.SAFETY_INTERRUPTED);
            return;
        }
        if (phase == Phase.RETURN) {
            returnSafetyRecovery = true;
            // returnToSurface decrements the cursor immediately after a factual micro-step. If a
            // combat/resupply task now displaces the bot, first rejoin this exact recorded cell
            // instead of skipping it and opening a long diagonal gap to the preceding waypoint.
            BlockPos current = bot.blockPosition();
            for (int i = descentTrail.size() - 1; i >= 0; i--) {
                if (descentTrail.get(i).equals(current)) {
                    returnTrailIndex = Math.max(returnTrailIndex, i);
                    returnPathFallback = false;
                    lastReturnPathAttemptBudget = returnBudget() - RETURN_PATH_RETRY;
                    break;
                }
            }
            // Persist the recovery lease at pause admission, before the safety task can move the
            // player. A process snapshot may happen while this task is still paused and therefore
            // never call onResume(); the aggregate hard clock is unchanged either way.
            resetReturnProgressLease(bot.blockPosition());
        }
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (phase != Phase.RETURN) {
            return;
        }
        // A safety/background task may have moved the bot while this task was paused. Give that
        // new physical recovery pose one fresh stall lease, but never reset returnBudget(): the
        // 2400-tick safety hard cap remains monotonic across arbitrarily many interruptions.
        returnSafetyRecovery = true;
        returnPathFallback = false;
        lastReturnPathAttemptBudget = returnBudget() - RETURN_PATH_RETRY;
        resetReturnProgressLease(bot.blockPosition());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (startPos == null) {
            tickEntryRelocation(bot);
            return;
        }
        if (holdForStepOrLanding(bot)) {
            return; // a walked step is in flight (or its bot is still landing): the trail changes only when the landing is verified
        }
        if (phase == Phase.RETURN) {
            returnToSurface(bot);
            return;
        }
        if (phase == Phase.DONE) {
            complete();
            return;
        }
        TrailUpdate trailUpdate = rememberDescentStep(bot.blockPosition());
        if (trailUpdate == TrailUpdate.LIMIT) {
            failAfterExactReturn(bot, ReturnOutcome.TRAIL_LIMIT);
            return;
        }
        if (trailUpdate == TrailUpdate.DISCONNECTED) {
            failAfterExactReturn(bot, ReturnOutcome.SAFETY_INTERRUPTED);
            return;
        }
        ServerLevel world = bot.level();
        // pickupGrace>0 in DESCEND is a durable, already-observed horizontal-frontier settlement
        // debt. rotateStair() can fall back to digHorizontal() before horizontalMode is latched, so
        // the armed counter itself is the authority. It is not fresh mining work; service its
        // bounded physical pickup window before any watchdog or hard-budget failure. The counter is
        // checkpointed and therefore cannot be reset by restart.
        if (hasHorizontalFrontierSettleDebt()) {
            settleHorizontalFrontier(bot);
            return;
        }
        if (workBudget() >= maxWorkBudget) {
            // A final horizontal break can land exactly on the hard boundary. Observe the now-closed
            // geometry before timing out; otherwise the drop's vanilla pickup delay is preempted by
            // up to PICKUP_GRACE_TICKS and the same entry can be retried without settling its debt.
            if (horizontalMode && isObservedClosedHorizontalFrontier(bot, world, bot.blockPosition())) {
                armHorizontalFrontierSettle(bot);
                settleHorizontalFrontier(bot);
            } else {
                failAfterExactReturn(bot, ReturnOutcome.TIMEOUT);
            }
            return;
        }
        // Descent-depth fallback: exceeding MAX_DESCENT without collecting enough is usually because
        // drops weren't picked up in time (collected doesn't increase) -> do one wide-area sweep and
        // recheck; if it's still not enough, permanently switch to horizontal digging [once only]
        // (set the horizontalMode flag), and never keep digging endlessly deeper (the observed root
        // cause of getting surrounded and killed by spiders while digging endlessly down to y6).
        // Key point: this must never cancel+begin+return every tick -- the old logic did that and
        // bypassed the miner.tick below (never advancing) and the L207 no-progress watchdog (waiting
        // the full MAX_DESCENT hard timeout out for nothing), self-resetting to zero blocks broken
        // every tick (the root cause of the seed20260610 dig_down_timeout). Changed to set the flag
        // only once; afterward it flows normally through the shared primitive: miner.tick advances
        // block by block, and if the watchdog genuinely stalls, it fails cleanly at 200t and hands
        // back to replan.
        if (!horizontalMode && startPos != null && startPos.getY() - bot.blockPosition().getY() >= MAX_DESCENT) {
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 12.0D, 64);
            int got = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
            if (got >= grossCollectionTarget()) {
                collected = got;
                miner.cancel(bot);
                beginReturn(bot);
                return;
            }
            horizontalMode = true;        // permanently switch to horizontal digging; subsequent ticks go through L236's digHorizontal decision (via miner.tick + watchdog)
            noteWorkProgress();            // give horizontal digging a clean watchdog window to start counting from
            BotLog.action(bot, "dig_down_go_horizontal", "from", bot.blockPosition().toShortString(),
                    "collected", collected + "/" + targetCount);
        }

        // Sea-level/aquifer descent drowning prevention: seal lateral water around the foot and head
        // cells (see method comment). If a seal happens this tick, stop here; digging continues next tick.
        if (sealLateralWater(bot, world)) {
            return;
        }
        // Tool gate: if the target can't be mined (no qualifying pickaxe), fail directly and let GoalExecutor backtrack to resupply a pickaxe.
        if (!ToolTier.canHarvestWithInventory(bot, targetBlock.defaultBlockState())) {
            failAfterExactReturn(bot, ReturnOutcome.NEED_BETTER_TOOL);
            return;
        }

        // Collection counting: an absolute delta against a fixed baseline; drops from a just-broken
        // block landing in inventory afterward get counted in.
        // Vertical pickup radius widened: while descending, cobblestone from a just-broken block
        // lands on the upper stair step, out of reach at 2.5 cells -> collected never increases ->
        // endless descent leading to getting surrounded and killed by mobs.
        refreshCollected(bot);
        if (collected >= grossCollectionTarget()) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline
                    >= grossCollectionTarget()) {
                // Enough collected -> enter the return trip and climb back to the shaft entry before
                // completing (otherwise stuck at the bottom of the shaft; the next task, e.g. hunting
                // surface prey, would be unable to get out -> livelock).
                beginReturn(bot);
            }
            return;
        }

        // No-progress watchdog: no block broken within NO_PROGRESS_LIMIT -> fail cleanly, don't spin idle.
        if (workBudget() - lastProgressBudget > NO_PROGRESS_LIMIT) {
            miner.cancel(bot);
            // Forensic dump: mountainous/natural-terrain no_progress (observed as 424t with zero
            // blocks broken) can't be pinpointed from the failure reason alone -- log the foot
            // position, stair direction, the blocks of the next tier's three cells (head/foot/tread),
            // and whether the obstruction can be broken, to help diagnose the geometric stall point.
            BlockPos feetNow = bot.blockPosition();
            Direction dirNow = HDIRS[stairDirIndex];
            BlockPos aheadNow = feetNow.relative(dirNow);
            BlockPos nextNow = aheadNow.below();
            ServerLevel worldNow = bot.level();
            BotLog.action(bot, "dig_down_stall_dump",
                    "feet", feetNow.toShortString(),
                    "dir", dirNow.getSerializedName(),
                    "ahead", worldNow.getBlockState(aheadNow).getBlock().toString(),
                    "next", worldNow.getBlockState(nextNow).getBlock().toString(),
                    "below_next", worldNow.getBlockState(nextNow.below()).getBlock().toString(),
                    "under_feet", worldNow.getBlockState(feetNow.below()).getBlock().toString(),
                    "can_harvest", ToolTier.canHarvestWithInventory(bot, targetBlock.defaultBlockState()));
            failAfterExactReturn(bot, ReturnOutcome.NO_PROGRESS);
            return;
        }

        // Advance the current dig.
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return; // still mining, wait for it to break/time out
        }
        if (status == BlockMiner.Status.DONE) {
            noteWorkProgress(); // a block broken = progress (whether it's stone or topsoil)
        }
        // DONE / FAILED / IDLE -> decide the next cell (the column underfoot).

        BlockPos feet = bot.blockPosition();
        if (horizontalMode || feet.below().getY() <= MIN_Y) {
            // horizontalMode: permanently switches to horizontal digging once the MAX_DESCENT cap is
            // reached (see the one-time flag set above). Or, just above bedrock with no more room
            // downward -> switch to horizontal digging to keep collecting stone material (observed:
            // at Y=-59 the very first step's below was already <= MIN_Y, and the old logic would
            // directly fail with collected=0 -> MINE stone failure -> goal replan infinite loop).
            // Both paths reuse the unified digHorizontal: its begin() has a re-entry guard, advanced
            // via miner.tick above with the L207 watchdog as the stall-loss backstop.
            digHorizontal(bot, world, feet);
            return;
        }

        // Staircase-style diagonal descent (humanlike + safe): never mine straight down underfoot --
        // below could be water/lava, and one pickaxe swing through it means drowning/dying in lava.
        // Instead mine the "next stair step" (diagonally ahead-below: ahead = head cell, next = foot
        // cell); at depth these two cells are usually stone material, incidentally counted toward
        // collected; if the next step or its tread is water/lava, switch diagonal direction and go
        // around it, descending diagonally one step at a time like a staircase (consistent with
        // DescendToYTask's staircase logic).
        ensureRejectedLandingOrigin(feet);
        if ((rejectedLandingDirections & 1 << stairDirIndex) != 0) {
            if (rotateStair(bot, world, feet)) {
                return;
            }
            digHorizontal(bot, world, feet);
            return;
        }
        Direction dir = HDIRS[stairDirIndex];
        BlockPos ahead = feet.relative(dir);   // next tier's head cell (x+d, y)
        BlockPos next = ahead.below();         // next tier's standing cell (x+d, y-1)
        if (!isViableStairDirection(bot, feet, dir)) {
            // The tread's own support may have turned out, once observable, to be an already-open
            // cavity or natural cave rather than a hazard fluid (sealed elsewhere) or solid ground.
            // Wall it off before rerouting, same as a real player would rather than leaving a hole
            // into unknown open space behind them.
            if (trySealOpenCavityLanding(bot, world, feet, next, stairDirIndex)) {
                return;
            }
            if (rotateStair(bot, world, feet)) {
                return; // switched to a diagonal-down direction that neither touches a fluid nor forms a genuine landing surface
            }
            // All four diagonal-down directions are blocked by water/lava -> switch to horizontal
            // digging (this layer can still yield more stone material); if that truly fails too, judge failure there instead.
            digHorizontal(bot, world, feet);
            return;
        }
        // Clear the next tier's body space: ahead (the cell directly in front, always visible, mine
        // first) -> ahead.up() (headroom above the front cell) -> next (the tread below and in front).
        // (1) Ordering: on a slope/uphill, ahead is a solid wall blocking the line of sight to next;
        //     mining the occluded next first would ray-cast into the wall and be judged unreachable
        //     -> FAILED -> zero-blocks-broken stall (the main cause of seed20260610); mine ahead first
        //     to clear the occlusion.
        // (2) Additionally mining ahead.up(): clearing only next+ahead leaves just 2 cells per column
        //     (Y-1, Y), so when a player descends from the upper tier their head hits the solid
        //     ceiling at Y+1 in front, leaving only 1 walkable cell height on the way down the stair
        //     -- a normal player can't pass through. Clearing the headroom too -> the descending
        //     tunnel is 2 cells tall and passable. firstSolid3 skips fluids to prevent a mud/water collapse.
        BlockPos solid = TerrainProbe.firstSolid(world, ahead, ahead.above(), next);
        if (solid != null) {
            miner.begin(bot, solid);
            miner.tick(bot); // start mining this cell immediately, don't waste a tick
            return;
        }
        // Body space is clear -> walk diagonally down onto the next stair tier: a walked step (off the edge of the tier, gravity
        // lands it one block lower), exactly what a player does with the keys. The landing joins the trail when it is verified on a
        // later tick (settleStep); after descending, the just-broken block's drops land right within the pickup radius, which also
        // fixes the collected=0 problem.
        WalkedStep descent = bot.getActionPack().beginDescend(next, "dig_down_stair");
        if (descent != null) {
            launchStep(bot, descent, StepPurpose.STAIR, stairDirIndex);
            return;
        }
        if (bot.getActionPack().stepAdmissionBlocked()) {
            // beginDescend observed only the guarded handoff fence. No physical refusal occurred,
            // so retain this stair direction and retry after its exact owner reconciles.
            return;
        }
        // Terrain may change between viability check and movement (falling blocks/entities), and
        // ActionPack is the final collision authority. Never retry the same unsupported cell for
        // 200 ticks: rotate to a different safe staircase, then fall back to supported horizontal
        // mining if all four directions are gone.
        rejectLandingDirection(feet, stairDirIndex);
        if (!rotateStair(bot, world, feet)) {
            digHorizontal(bot, world, feet);
        }
    }

    private void beginReturn(AIPlayerEntity bot) {
        beginReturn(bot, ReturnOutcome.COMPLETE);
    }

    private void failAfterExactReturn(AIPlayerEntity bot, ReturnOutcome outcome) {
        rememberUnusableEntry(bot, outcome);
        if (startPos == null) {
            fail(returnFailureReason(bot, outcome));
            return;
        }
        if (hasReturned(startPos, bot.blockPosition())) {
            settleAfterExactReturn(bot, outcome);
            return;
        }
        beginReturn(bot, outcome);
    }

    private void rememberUnusableEntry(AIPlayerEntity bot, ReturnOutcome outcome) {
        if (!shouldExcludeEntry(outcome, horizontalMode) || startPos == null) {
            return;
        }
        EpisodeMemory.INSTANCE.exclude(bot.getUUID(), startPos,
                bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        BotLog.action(bot, "dig_down_entry_excluded",
                "entry", startPos.toShortString(),
                "reason", switch (outcome) {
                    case WALLED -> "observed_walled";
                    case SAFETY_INTERRUPTED -> "safety_interrupted";
                    case TIMEOUT -> "horizontal_work_timeout";
                    default -> "horizontal_no_progress";
                });
    }

    static boolean shouldExcludeEntry(ReturnOutcome outcome, boolean horizontalMode) {
        return outcome == ReturnOutcome.WALLED
                || outcome == ReturnOutcome.SAFETY_INTERRUPTED
                || horizontalMode && (outcome == ReturnOutcome.NO_PROGRESS
                || outcome == ReturnOutcome.TIMEOUT);
    }

    private void beginReturn(AIPlayerEntity bot, ReturnOutcome outcome) {
        // Settlement time is bounded by pickupGrace, not fresh work. Persist only the capped work
        // budget so RETURN checkpoints remain valid even when a drop settled after the hard edge.
        int exhaustedWorkBudget = Math.min(workBudget(), maxWorkBudget);
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        abandonStep(bot);
        clearRejectedLandingDirections();
        phase = Phase.RETURN;
        returnOutcome = outcome == null ? ReturnOutcome.COMPLETE : outcome;
        workBudgetAtReturn = exhaustedWorkBudget;
        returnBudgetOffset = 0;
        returnStartTick = elapsed;
        BlockPos current = bot.blockPosition();
        boolean currentRecorded = !descentTrail.isEmpty()
                && descentTrail.getLast().equals(current);
        // A safety task runs only after this task has been paused and may move the bot before the
        // first RETURN tick. Keep the current factual tail as the first waypoint in that case.
        // If no displacement happened, returnToSurface consumes the equal waypoint immediately.
        boolean mayBeDisplacedWhilePaused = returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED;
        returnTrailIndex = Math.max(-1, descentTrail.size()
                - (currentRecorded && !mayBeDisplacedWhilePaused ? 2 : 1));
        lastReturnPathAttemptBudget = -RETURN_PATH_RETRY;
        returnPathFallback = false;
        returnSafetyRecovery = returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED;
        resetReturnProgressLease(current);
        BotLog.action(bot, "dig_down_return_start", "from", bot.blockPosition().toShortString(),
                "to", startPos.toShortString(), "trail", descentTrail.size(),
                "outcome", returnOutcome);
    }

    /**
     * Hands a walked step to the pack. The bot is moved by its keys only: the step's landing is verified on a later tick (see
     * {@link #settleStep}) and the trail, the return cursor and the checkpoint change only then, never in the tick that starts the step.
     */
    private boolean launchStep(AIPlayerEntity bot, WalkedStep walked, StepPurpose purpose, int dirIndex) {
        ActionPack.StepLease lease = bot.getActionPack().runStep(walked);
        if (lease == null) {
            // A guarded owner still owns ActionPack. Do not publish a trail-step purpose/origin
            // that could later consume another controller's global result; retry this route.
            return false;
        }
        step = walked;
        stepLease = lease;
        stepPurpose = purpose;
        stepOrigin = bot.blockPosition().immutable();
        stepDirIndex = dirIndex;
        return true;
    }

    /**
     * Forgets a step the pack no longer runs (a pause, an abort, a hazard return or a restart cancelled it, keys released). The bot may
     * be between two trail cells, so nothing is recorded from its pose until it stands on something again; the trail INDEX is
     * re-derived from where it lands, the bot is never moved to fit the trail.
     */
    private void abandonStep(AIPlayerEntity bot) {
        if (bot.getActionPack().stepInFlightFor(stepLease)) {
            // An ordinary lease has no exact cancellation overload. This ownership check makes
            // the generic cancellation safe and leaves a higher-priority successor untouched.
            bot.getActionPack().cancelStep();
        }
        if (stepPurpose != null) {
            stepPurpose = null;
            poseUnsettled = true;
            unsettledTicks = 0;
        }
        clearStepFields();
    }

    /** Clears only this task's admission record; it never changes whichever controller owns the pack. */
    private void clearStepFields() {
        stepPurpose = null;
        stepLease = null;
        step = null;
        stepOrigin = null;
        stepDirIndex = -1;
    }

    /** True while the bot must be left alone: a step is in flight (or was just settled), or it is still falling after one was lost. */
    private boolean holdForStepOrLanding(AIPlayerEntity bot) {
        if (stepPurpose != null) {
            ActionPack pack = bot.getActionPack();
            ActionPack.StepLease lease = stepLease;
            if (pack.stepInFlightFor(lease)) {
                return true;
            }
            if (!pack.stepIdle()) {
                // A successor owns ActionPack now. Its result/position is not evidence about our
                // trail micro-step, so drop only stale local state and yield to it.
                clearStepFields();
                return true;
            }
            settleStep(bot, pack.stepResultFor(lease));
            return true;
        }
        if (poseUnsettled) {
            if (WalkedStep.supported(bot) || bot.isInWater() || ++unsettledTicks > UNSETTLED_LIMIT) {
                poseUnsettled = false;
            } else {
                return true;
            }
        }
        return false;
    }

    /** A step ended: the landing it verified is the only thing that changes the trail; a failed step is re-derived from the pose. */
    private void settleStep(AIPlayerEntity bot, WalkedStep.Result result) {
        StepPurpose purpose = stepPurpose;
        BlockPos origin = stepOrigin;
        int dirIndex = stepDirIndex;
        stepPurpose = null;
        stepLease = null;
        step = null;
        stepOrigin = null;
        stepDirIndex = -1;
        // The exact lease is the only source of a result. A successor's completion is never
        // evidence about this trail step.
        boolean landed = result != null && result.succeeded();
        if (!landed) {
            poseUnsettled = true;
            unsettledTicks = 0;
            BotLog.action(bot, "dig_down_step_failed", "purpose", purpose,
                    "from", origin.toShortString(), "at", bot.blockPosition().toShortString(),
                    "why", result == null ? "cancelled" : result.reason());
        }
        boolean stillAtOrigin = bot.blockPosition().equals(origin);
        switch (purpose) {
            case STAIR -> {
                if (landed) {
                    afterDescentLanding(bot, false);
                } else if (stillAtOrigin) {
                    rejectLandingDirection(origin, dirIndex);
                }
            }
            case HORIZONTAL -> {
                if (landed) {
                    afterDescentLanding(bot, true);
                } else if (stillAtOrigin) {
                    rejectLandingDirection(origin, dirIndex);
                    hdirIndex = (dirIndex + 1) % HDIRS.length;
                }
            }
            case RETURN -> {
                if (landed) {
                    returnTrailIndex--;
                    returnPathFallback = false;
                    resetReturnProgressLease(bot.blockPosition());
                } else {
                    returnPathFallback = true;
                    BotLog.action(bot, "dig_down_return_path_fallback",
                            "from", origin.toShortString(), "to", pendingReturnWaypoint().toShortString(),
                            "reason", "micro_step_failed", "why", result == null ? "cancelled" : result.reason());
                }
            }
        }
    }

    /** The verified landing of a stair or horizontal step joins the factual trail (and is checkpointed with it on this same tick). */
    private void afterDescentLanding(AIPlayerEntity bot, boolean horizontal) {
        TrailUpdate landingUpdate = rememberDescentStep(bot.blockPosition());
        if (landingUpdate == TrailUpdate.LIMIT) {
            failAfterExactReturn(bot, ReturnOutcome.TRAIL_LIMIT);
            return;
        }
        if (landingUpdate == TrailUpdate.DISCONNECTED) {
            failAfterExactReturn(bot, ReturnOutcome.SAFETY_INTERRUPTED);
            return;
        }
        clearRejectedLandingDirections();
        if (horizontal) {
            noteWorkProgress();
        }
    }

    /**
     * Records the bot's pose in the trail unless a step was lost and it has not landed yet (then nothing is recorded: it may be in the
     * air between two trail cells).
     */
    private TrailUpdate rememberPose(AIPlayerEntity bot) {
        if (poseUnsettled && !WalkedStep.supported(bot) && !bot.isInWater()) {
            return TrailUpdate.UNCHANGED;
        }
        poseUnsettled = false;
        return rememberDescentStep(bot.blockPosition());
    }

    /** The kind of walked step that covers a return-trail micro-step, or null when no walk does (up a pure vertical, two blocks up). */
    private static WalkedStep.Kind returnStepKind(BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        if (to.getX() == from.getX() && to.getZ() == from.getZ()) {
            return dy < 0 ? WalkedStep.Kind.DROP : null;
        }
        return WalkedStepRules.walkKindFor(dy);
    }

    private TrailUpdate rememberDescentStep(BlockPos current) {
        if (current == null || phase != Phase.DESCEND) {
            return TrailUpdate.UNCHANGED;
        }
        BlockPos immutable = current.immutable();
        BlockPos last = descentTrail.isEmpty() ? null : descentTrail.getLast();
        if (last == null) {
            descentTrail.add(immutable);
            targetY = Math.min(targetY, immutable.getY());
            return TrailUpdate.APPENDED;
        }
        if (last.equals(immutable)) {
            return TrailUpdate.UNCHANGED;
        }
        int dx = Math.abs(last.getX() - immutable.getX());
        int dy = Math.abs(last.getY() - immutable.getY());
        int dz = Math.abs(last.getZ() - immutable.getZ());
        int changedAxes = changedAxes(dx, dy, dz);
        if (dx <= 1 && dy <= 1 && dz <= 1 && changedAxes >= 1 && changedAxes <= 2) {
            if (descentTrail.size() >= MAX_TRAIL_POINTS) {
                return TrailUpdate.LIMIT;
            }
            descentTrail.add(immutable);
            targetY = Math.min(targetY, immutable.getY());
            return TrailUpdate.APPENDED;
        }
        // Never keep mining from a safety-displaced pose that is disconnected from the factual
        // return trail. Transition to exact RETURN debt; interpolation would forge physical facts.
        return TrailUpdate.DISCONNECTED;
    }

    private String returnFailureReason(AIPlayerEntity bot, ReturnOutcome outcome) {
        return switch (outcome) {
            case COMPLETE -> "dig_down_return_completed_without_failure";
            case TIMEOUT -> "dig_down_timeout collected=" + collected;
            case NEED_BETTER_TOOL -> "need_better_tool:"
                    + ToolTier.requiredPickaxeItemId(targetBlock);
            case NO_PROGRESS -> "dig_down_no_progress collected=" + collected;
            case TRAIL_LIMIT -> "dig_down_trail_limit";
            case WALLED -> "dig_down_walled collected=" + collected;
            case SAFETY_INTERRUPTED -> "dig_down_safety_interrupted collected=" + collected;
            case NET_DELIVERY_SHORTFALL -> "dig_down_net_delivery_shortfall:have="
                    + Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops)
                    - invBaseline) + ":required=" + targetCount;
        };
    }

    private int grossCollectionTarget() {
        return grossCollectionTarget(BuiltInRegistries.BLOCK.getKey(targetBlock).toString(), targetCount,
                startPos, targetY);
    }

    private static int grossCollectionTarget(String targetBlockId, int requested,
                                             BlockPos startPos, int targetY) {
        int safeRequested = Math.max(1, requested);
        int returnMaterialReserve = startPos == null ? 0
                : Math.min(MAX_DESCENT, Math.max(0, startPos.getY() - targetY));
        return "minecraft:stone".equals(targetBlockId)
                ? safeRequested + returnMaterialReserve
                : safeRequested;
    }

    /**
     * DigDown's no-progress watchdog remains the fast stuck detector. The independent hard budget
     * must therefore scale with useful quota instead of cutting a healthy large batch at the same
     * two-minute boundary as a three-block bootstrap. Stone also budgets its worst-case factual
     * return reserve, because those extra blocks are part of the bounded work required before a
     * safe exact return can begin.
     */
    static int maxWorkBudgetForTarget(String targetBlockId, int targetCount) {
        int safeTarget = Math.max(1, targetCount);
        long budgetedBlocks = (long) safeTarget
                + ("minecraft:stone".equals(targetBlockId) ? MAX_DESCENT : 0);
        long scaled = budgetedBlocks * MAX_ELAPSED_PER_BLOCK;
        return (int) Math.min(MAX_ELAPSED_CAP, Math.max(MAX_ELAPSED_BASE, scaled));
    }

    // Once enough is collected, prefer retracing the actually-walked stair steps cell by cell in
    // reverse; only a gap in the trail falls back to a short pathfind to the next waypoint.
    private void returnToSurface(AIPlayerEntity bot) {
        miner.cancel(bot);
        BlockPos at = bot.blockPosition();
        if (hasReturned(startPos, at)) {
            bot.getActionPack().stopAll();
            settleAfterExactReturn(bot, returnOutcome);
            return;
        }
        while (returnTrailIndex >= 0 && at.equals(descentTrail.get(returnTrailIndex))) {
            returnTrailIndex--;
            returnPathFallback = false;
            resetReturnProgressLease(at);
        }
        noteReturnDistanceProgress(at);
        int currentReturnBudget = returnBudget();
        if (currentReturnBudget > returnHardLimit()) {
            bot.getActionPack().stopAll();
            fail("dig_down_return_failed:hard_limit from=" + at.toShortString()
                    + " to=" + startPos.toShortString());
            return;
        }
        if (currentReturnBudget - lastReturnProgressBudget > RETURN_STALL_LIMIT) {
            bot.getActionPack().stopAll();
            fail("dig_down_return_failed:stalled from=" + at.toShortString()
                    + " to=" + startPos.toShortString());
            return;
        }
        // A recorded stair cell can become unsupported after the descent (for example, sand or
        // gravel above the tunnel falls when an adjacent block is mined). Retrying a path to that
        // stale waypoint can snap the goal back to the current/lower stand and report an empty
        // successful path forever. Skip only historical intermediate cells that are no longer
        // factual landings; index 0 is the exact start debt and must never be skipped.
        while (returnTrailIndex > 0
                && !isSafeReturnLanding(bot.level(), descentTrail.get(returnTrailIndex))) {
            BlockPos skipped = descentTrail.get(returnTrailIndex);
            // A factual stair can lose only its support when a later branch cuts underneath it.
            // Skipping that ascending cell may leave the next waypoint two blocks away and make
            // generic pathing drop back into the shaft (the water-sealed seed-3000 failure). Pay
            // the reserved return-material budget first: place one normal survival block under
            // the clear body cell, then retry the exact recorded micro-step on the next tick.
            if (tryRepairReturnSupport(bot, skipped)) {
                return;
            }
            bot.getActionPack().stopAll();
            BotLog.action(bot, "dig_down_return_waypoint_skipped",
                    "at", skipped.toShortString(), "reason", "unsafe_landing");
            returnTrailIndex--;
            returnPathFallback = false;
            lastReturnPathAttemptBudget = returnBudget() - RETURN_PATH_RETRY;
            resetReturnProgressLease(at);
        }
        BlockPos waypoint = returnTrailIndex >= 0
                ? descentTrail.get(returnTrailIndex) : startPos;
        if (at.equals(waypoint)) {
            returnTrailIndex--;
            returnPathFallback = false;
            resetReturnProgressLease(at);
            return;
        }

        // The exact entry cannot be skipped. A later mining branch may have removed startPos.down()
        // after that cell was recorded, just like an intermediate stair support. Repair it while
        // still standing on the adjacent factual waypoint; otherwise generic pathing can snap two
        // blocks down the open column and loop until RETURN_LIMIT.
        if (!isSafeReturnLanding(bot.level(), waypoint)
                && tryRepairReturnSupport(bot, waypoint)) {
            return;
        }

        if (!returnPathFallback
                && isValidReturnMicroStep(at, waypoint)
                && isSafeReturnLanding(bot.level(), waypoint)) {
            bot.getActionPack().stopAll();
            WalkedStep.Kind kind = returnStepKind(at, waypoint);
            String refused = kind == null ? "no_walk" : WalkedStep.refusal(bot, waypoint, kind);
            if (refused == null) {
                // One walked step back up (or down) the recorded trail; the cursor moves when its landing is verified (settleStep).
                launchStep(bot, WalkedStep.begin(bot, waypoint, kind, "dig_down_return_trail"), StepPurpose.RETURN, -1);
                return;
            } else {
                returnPathFallback = true;
                BotLog.action(bot, "dig_down_return_path_fallback",
                        "from", at.toShortString(), "to", waypoint.toShortString(),
                        "reason", "micro_step_failed", "why", refused);
            }
        } else {
            if (!returnPathFallback) {
                returnPathFallback = true;
                BotLog.action(bot, "dig_down_return_path_fallback",
                        "from", at.toShortString(), "to", waypoint.toShortString(),
                        "reason", isValidReturnMicroStep(at, waypoint)
                                ? "unsafe_landing" : "non_adjacent_or_three_axis");
            }
        }

        if (returnPathFallback && bot.getActionPack().isPathExecutorIdle()
                && returnBudget() - lastReturnPathAttemptBudget >= RETURN_PATH_RETRY) {
            lastReturnPathAttemptBudget = returnBudget();
            // The ordinary two-phase path may accept a WALK result whose endpoint was snapped
            // down from an unsupported exact entry. DIG approach preserves a dry, clear requested
            // endpoint and lets the existing PILLAR_UP executor pay the real multi-block climb.
            var result = bot.getActionPack().startDigPathTo(waypoint);
            if (result.isFailed()) {
                BotLog.action(bot, "dig_down_return_path_retry",
                        "target", waypoint.toShortString(), "reason", result.reason());
            }
        }
    }

    /**
     * Settles the batch only after the exact entry debt is paid. A work timeout describes why the
     * descent stopped, not whether the requested material was ultimately delivered: return repair
     * can consume the gross reserve while still leaving the full requested quota in inventory.
     * Conversely, inventory is never allowed to forgive an incomplete/failed return route.
     */
    private void settleAfterExactReturn(AIPlayerEntity bot, ReturnOutcome outcome) {
        returnOutcome = outcome == null ? ReturnOutcome.COMPLETE : outcome;
        // A safety interruption is factual evidence about this exact mine entry, but publishing it
        // when pause begins would spend its 1,200-tick episode TTL during a return that may itself
        // take up to 2,400 ticks. Refresh only after the exact startPos debt has been paid, while the
        // typed cause is still intact, so a fresh remaining-quota plan must physically change column.
        if (returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED) {
            rememberUnusableEntry(bot, returnOutcome);
        }
        int netDelivered = Math.max(0,
                HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (netDelivered >= targetCount) {
            collected = netDelivered;
            returnOutcome = ReturnOutcome.COMPLETE;
        } else if (returnOutcome == ReturnOutcome.COMPLETE) {
            collected = netDelivered;
            returnOutcome = ReturnOutcome.NET_DELIVERY_SHORTFALL;
        }
        BotLog.action(bot, "dig_down_return_done", "pos", bot.blockPosition().toShortString(),
                "surfaced", "true", "outcome", returnOutcome,
                "net_delivered", netDelivered + "/" + targetCount);
        phase = Phase.DONE;
        if (returnOutcome == ReturnOutcome.COMPLETE) {
            complete();
        } else {
            fail(returnFailureReason(bot, returnOutcome));
        }
    }

    private int returnHardLimit() {
        return returnSafetyRecovery || returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED
                ? RETURN_SAFETY_LIMIT : RETURN_LIMIT;
    }

    private BlockPos pendingReturnWaypoint() {
        return returnTrailIndex >= 0 && returnTrailIndex < descentTrail.size()
                ? descentTrail.get(returnTrailIndex) : startPos;
    }

    private void noteReturnDistanceProgress(BlockPos current) {
        BlockPos waypoint = pendingReturnWaypoint();
        if (current == null || waypoint == null) {
            return;
        }
        long distanceSquared = squaredDistance(current, waypoint);
        if (returnProgressWaypointIndex != returnTrailIndex
                || returnBestDistanceSquared < 0L
                || distanceSquared < returnBestDistanceSquared) {
            lastReturnProgressBudget = returnBudget();
            returnProgressWaypointIndex = returnTrailIndex;
            returnBestDistanceSquared = distanceSquared;
        }
    }

    private void resetReturnProgressLease(BlockPos current) {
        lastReturnProgressBudget = returnBudget();
        returnProgressWaypointIndex = returnTrailIndex;
        BlockPos waypoint = pendingReturnWaypoint();
        returnBestDistanceSquared = current == null || waypoint == null
                ? -1L : squaredDistance(current, waypoint);
    }

    private static long squaredDistance(BlockPos from, BlockPos to) {
        long dx = (long) from.getX() - to.getX();
        long dy = (long) from.getY() - to.getY();
        long dz = (long) from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean hasReturned(BlockPos start, BlockPos current) {
        return start != null && start.equals(current);
    }

    static boolean isValidReturnMicroStep(BlockPos from, BlockPos to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());
        int changedAxes = changedAxes(dx, dy, dz);
        return dx <= 1 && dy <= 1 && dz <= 1 && changedAxes >= 1 && changedAxes <= 2;
    }

    private static int changedAxes(int dx, int dy, int dz) {
        return (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
    }

    static boolean isSafeReturnLanding(ServerLevel world, BlockPos pos) {
        return world != null && pos != null
                && Standability.isStandable(world, pos)
                && world.getFluidState(pos).isEmpty()
                && world.getFluidState(pos.above()).isEmpty();
    }

    private boolean tryRepairReturnSupport(AIPlayerEntity bot, BlockPos landing) {
        ServerLevel world = bot.level();
        // A level intermediate gap can still be routed around and the established skip behavior
        // preserves the mission payload. The exact origin is different: it may never be skipped,
        // so repair its support at any relative height while it is still physically reachable.
        boolean exactOrigin = startPos != null && startPos.equals(landing);
        BlockPos current = bot.blockPosition();
        if ((!exactOrigin && landing.getY() <= current.getY())
                || !isValidReturnMicroStep(current, landing)
                || !bot.getActionPack().isPathExecutorIdle()
                || !isClearReturnBody(world, landing)) {
            return false;
        }
        BlockPos support = landing.below();
        // A support equal to the current feet cell needs jump-then-place pillar semantics. A raw
        // place attempt cannot succeed while the player occupies it; leave it to exact DIG pathing.
        if (support.equals(current)) {
            return false;
        }
        if (isSafeSupport(world, support)) {
            return false;
        }
        var supportState = world.getBlockState(support);
        if (!supportState.canBeReplaced()
                && !supportState.getCollisionShape(world, support).isEmpty()) {
            return false;
        }
        OptionalInt slot = MaterialPalette.pickPathSupportBlockSlot(bot);
        if (slot.isEmpty()) {
            return false;
        }
        bot.getActionPack().stopAll();
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult placed = BuildAction.placeBlockAt(bot, support);
        if (placed.isInProgress()) {
            // The reactive shield has the use key; keep the exact return waypoint and its
            // bounded lease alive until this physical support repair can be retried.
            resetReturnProgressLease(bot.blockPosition());
            return true;
        }
        if (placed.isFailed()) {
            return false;
        }
        returnPathFallback = false;
        resetReturnProgressLease(bot.blockPosition());
        BotLog.action(bot, "dig_down_return_support_repaired",
                "landing", landing.toShortString(),
                "support", support.toShortString());
        return true;
    }

    private static boolean isClearReturnBody(ServerLevel world, BlockPos landing) {
        if (world == null || landing == null) {
            return false;
        }
        for (BlockPos cell : new BlockPos[]{landing, landing.above()}) {
            var state = world.getBlockState(cell);
            if (!state.getFluidState().isEmpty()
                    || !state.getCollisionShape(world, cell).isEmpty()
                    || Standability.isDangerous(state)) {
                return false;
            }
        }
        return true;
    }

    private void noteWorkProgress() {
        lastProgressBudget = Math.min(workBudget(), maxWorkBudget);
    }

    private void refreshCollected(AIPlayerEntity bot) {
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (total <= collected) {
            return;
        }
        collected = total;
        noteWorkProgress();
        BotLog.action(bot, "dig_down_collected", "total", collected + "/" + targetCount);
    }

    private boolean hasHorizontalFrontierSettleDebt() {
        return phase == Phase.DESCEND && pickupGrace > 0
                && collected < grossCollectionTarget();
    }

    private void armHorizontalFrontierSettle(AIPlayerEntity bot) {
        if (pickupGrace > 0) {
            return;
        }
        pickupGrace = 1;
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, "dig_down_horizontal_settle_start",
                "at", bot.blockPosition().toShortString(),
                "work_budget", Math.min(workBudget(), maxWorkBudget),
                "collected", collected + "/" + grossCollectionTarget());
    }

    private void settleHorizontalFrontier(AIPlayerEntity bot) {
        miner.cancel(bot);
        refreshCollected(bot);
        if (collected >= grossCollectionTarget()) {
            beginReturn(bot);
            return;
        }
        if (pickupGrace >= PICKUP_GRACE_TICKS) {
            failAfterExactReturn(bot, ReturnOutcome.WALLED);
            return;
        }
        pickupGrace++;
    }

    /**
     * True only when every currently owned horizontal option is closed by factual geometry.
     * Strict survival cannot pre-read a direction's hidden floor/fluid to decide this; a
     * direction only counts as closed here once its own rejection bit is already set (an
     * observed fact from an earlier tick: a real hazard, an unsupported landing, or a failed
     * step), it has literally nothing left to mine and has already been walked, or -- for a
     * direction never actually stepped into -- its body is already touching an observed hazard
     * fluid or its landing is already observably (never behind unmined rock) unsupported. That
     * last case mirrors {@link #isViableStairDirection}'s unconditional touching-hazard gate: a
     * real player standing next to a visibly open, visibly unsupported drop already knows it is a
     * dead end without needing to physically step into it first to "discover" the same fact one
     * tick later. Never peeks through unmined rock to manufacture an extra "closed" verdict.
     */
    private boolean isObservedClosedHorizontalFrontier(AIPlayerEntity bot,
                                                        ServerLevel world,
                                                        BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        for (int directionIndex = 0; directionIndex < HDIRS.length; directionIndex++) {
            if ((rejectedLandingDirections & 1 << directionIndex) != 0) {
                continue;
            }
            BlockPos side = feet.relative(HDIRS[directionIndex]);
            if (TerrainProbe.firstNonAir(world, side, side.above()) != null) {
                return false;
            }
            if (descentTrail.contains(side)) {
                continue;
            }
            if (isObservedHazardFluid(bot, side) || isObservedHazardFluid(bot, side.above())
                    || !isAcceptableLanding(bot, world, side.below())) {
                continue;
            }
            return false;
        }
        return true;
    }

    private int workBudget() {
        return phase == Phase.DESCEND
                ? workBudgetOffset + elapsed
                : workBudgetAtReturn;
    }

    private int returnBudget() {
        return phase == Phase.RETURN
                ? returnBudgetOffset + Math.max(0, elapsed - returnStartTick)
                : returnBudgetOffset;
    }

    // Sea-level/aquifer descent drowning prevention: diagonal-down staircase digging gradually
    // drifts toward a neighboring ocean, and water floods in [laterally] at the foot/head cells,
    // drowning the bot (observed: spawning on a Y63 beach, dig_down drowned 12 times in a row ->
    // guard preempts -> surfaces -> replan re-digs the same column -> infinite loop; the stair logic
    // only checks water directly below/ahead [L260], it can't handle lateral inflow). Like a real
    // miner walling off water: every tick, seal the water around the foot and head cells, preventing
    // drowning at the root instead of letting the bot drown and relying on the survival layer as a
    // backstop. Dry terrain with no water -> return false immediately with zero placements, no
    // behavior change (geo_deep/shaft/cave don't regress).
    // Returns true = one cell was sealed this tick (caller yields, continues next tick; BlockMiner
    // digging automatically switches back to the pickaxe).
    private boolean sealLateralWater(AIPlayerEntity bot, ServerLevel world) {
        BlockPos feet = bot.blockPosition();
        // (1) Lateral: around the foot and head cells (water floods in horizontally as the staircase drifts toward the ocean).
        for (BlockPos level : new BlockPos[]{feet, feet.above()}) {
            for (Direction d : HDIRS) {
                if (trySealWater(bot, world, level.relative(d))) {
                    return true;
                }
            }
        }
        // (2) Cap the top: one cell above the head -- for water-heavy seeds, the root cause of still
        // drowning after sealing only laterally is water pouring down onto the head cell from
        // [above] the shaft (observed: sealed laterally 25 times, still drowned 12 times); sealing
        // the top too keeps the bot digging in a dry 1x2 air pocket, so oxygen is no longer drained
        // by water pouring in from above.
        return trySealWater(bot, world, feet.above(2));
    }

    // If this cell is water, seal it with a block (real-player wall-building/capping to block
    // water). When the main hand holds a pickaxe, interacting with a water cell is silently
    // swallowed by vanilla as PASS (the same lesson learned from OreDigTask's lava sealing) -> equip
    // the block before placing it. Returns true = one cell was sealed (caller yields and continues
    // next tick; BlockMiner automatically switches back to the pickaxe); no water/no block available
    // to seal -> false (hand off to downstream logic/the survival layer as a backstop -- staying
    // alive is worth more than this one cell, never get stuck over it).
    private boolean trySealWater(AIPlayerEntity bot, ServerLevel world, BlockPos pos) {
        boolean lava = isLava(world, pos);
        if (!lava && !isWater(world, pos)) {
            return false;
        }
        OptionalInt blockSlot = MaterialPalette.pickSacrificialBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
        ActionResult sealed = BuildAction.placeBlockAt(bot, pos);
        if (sealed.isInProgress()) {
            noteWorkProgress();
            return true;
        }
        if (sealed.isFailed()) {
            return false;
        }
        // A BlockMiner started before this tick may still own exactly the fluid cell that has now
        // become our safety wall. Cancel that stale break intent and reject its stair direction;
        // otherwise the next miner tick removes the seal, the fluid refills it and the task consumes
        // every portable block in an endless seal/mine loop.
        miner.cancel(bot);
        rejectSealedStairDirection(bot.blockPosition(), pos);
        String fluidName = lava ? "lava" : "water";
        BotLog.action(bot, "dig_down_seal_water", "fluid", fluidName, "at", pos.toShortString());
        BrainCoordinator.INSTANCE.sendBotReply(bot,
                "Sealed off exposed " + fluidName + " while digging -- routing around it.");
        noteWorkProgress(); // sealing = progress, don't let it get falsely killed by the NO_PROGRESS watchdog
        return true;
    }

    private void rejectSealedStairDirection(BlockPos feet, BlockPos sealed) {
        for (int i = 0; i < HDIRS.length; i++) {
            BlockPos side = feet.relative(HDIRS[i]);
            if (sealed.equals(side) || sealed.equals(side.above())) {
                rejectLandingDirection(feet, i);
                return;
            }
        }
    }

    private static boolean isWater(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    private static boolean isLava(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    /**
     * Walls off an unexpectedly opened void beneath the next stair tread: its own support turned
     * out, once observable, to be neither hazard fluid (sealed by trySealWater) nor solid ground
     * but genuine open space -- a pre-existing cavity or cave the tread just broke into. Never
     * called on a still-hidden support (isViableStairDirection would not have rejected the
     * direction for one). Rejects the direction on success so the next tick's rotateStair routes
     * around it rather than walking onto the freshly placed patch.
     */
    private boolean trySealOpenCavityLanding(AIPlayerEntity bot, ServerLevel world,
                                              BlockPos feet, BlockPos next, int directionIndex) {
        BlockPos hole = next.below();
        if (!ObservableWorldQuery.canObserveCell(bot, hole)) {
            return false;
        }
        var holeState = world.getBlockState(hole);
        if (!holeState.getFluidState().isEmpty()
                || !holeState.getCollisionShape(world, hole).isEmpty()) {
            return false; // fluid (sealed elsewhere) or already-solid: not an open cavity to wall off
        }
        OptionalInt blockSlot = MaterialPalette.pickSacrificialBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
        ActionResult sealed = BuildAction.placeBlockAt(bot, hole);
        if (sealed.isInProgress()) {
            noteWorkProgress();
            return true;
        }
        if (sealed.isFailed()) {
            return false;
        }
        var sealState = world.getBlockState(hole);
        if (sealState.isAir() || !sealState.getFluidState().isEmpty()) {
            return false;
        }
        miner.cancel(bot);
        rejectLandingDirection(feet, directionIndex);
        BotLog.action(bot, "dig_down_seal_open_cavity", "at", hole.toShortString());
        BrainCoordinator.INSTANCE.sendBotReply(bot,
                "Sealed off an open cavity found while digging -- routing around it.");
        noteWorkProgress();
        return true;
    }

    // Staircase diagonal-down: switch to the next direction that neither touches a fluid nor lacks a
    // genuine tread support. Where a natural slope meets a cliff, ahead/next may both be air; the
    // old logic only checked water/lava and would try to step onto the same unsupported next forever.
    private boolean rotateStair(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        for (int i = 0; i < HDIRS.length; i++) {
            stairDirIndex = (stairDirIndex + 1) % HDIRS.length;
            if ((rejectedLandingDirections & 1 << stairDirIndex) != 0) {
                continue;
            }
            if (isViableStairDirection(bot, feet, HDIRS[stairDirIndex])) {
                return true;
            }
        }
        return false;
    }

    private void rejectLandingDirection(BlockPos origin, int direction) {
        ensureRejectedLandingOrigin(origin);
        if (direction >= 0 && direction < HDIRS.length) {
            rejectedLandingDirections |= 1 << direction;
        }
    }

    private void ensureRejectedLandingOrigin(BlockPos origin) {
        if (rejectedLandingOrigin == null || !rejectedLandingOrigin.equals(origin)) {
            rejectedLandingOrigin = origin.immutable();
            rejectedLandingDirections = 0;
        }
    }

    private void clearRejectedLandingDirections() {
        rejectedLandingOrigin = null;
        rejectedLandingDirections = 0;
    }

    /**
     * Strict-survival stair viability. {@code ahead}/{@code ahead.up()} touch the bot's body at
     * foot and head height, so any fluid there is exactly as visible as a wall a real player is
     * standing next to — that fluid check runs unconditionally. {@code next}/{@code support} sit
     * behind that unmined wall and are unknowable until it is actually opened: they can only
     * reject this direction when they are ALREADY genuinely observable right now (natural open
     * terrain, a previously mined cavity, or a nearby exposed pocket — never a peek through solid
     * rock). Cells still hidden behind unmined rock report UNKNOWN here and never block progress;
     * mining ahead is what legitimately exposes them, and the walked step's per-tick landing check
     * reacts the instant that happens.
     */
    static boolean isViableStairDirection(AIPlayerEntity bot, BlockPos feet, Direction direction) {
        ServerLevel world = bot.level();
        BlockPos ahead = feet.relative(direction);
        BlockPos next = ahead.below();
        BlockPos support = next.below();
        if (isObservedHazardFluid(bot, ahead) || isObservedHazardFluid(bot, ahead.above())
                || !isClearableForStair(world, ahead) || !isClearableForStair(world, ahead.above())) {
            return false;
        }
        if (isObservedHazardFluid(bot, next) || isObservedHazardFluid(bot, support)) {
            return false;
        }
        return isAcceptableLanding(bot, world, support);
    }

    /**
     * Strict-survival fluid gate: true only when a lava/water hazard is already genuinely
     * observable at pos (touching the bot, an already-mined cavity, or naturally exposed
     * terrain). A cell still hidden behind unmined rock is UNKNOWN and never counts as a hazard.
     */
    private static boolean isObservedHazardFluid(AIPlayerEntity bot, BlockPos pos) {
        return OreScan.observeDangerFluid(bot, pos) == OreScan.Observation.OBSERVED_PRESENT;
    }

    /**
     * True when a landing/support cell is either not yet honestly knowable (still hidden behind
     * unmined rock, so it may not be treated as unsafe) or is genuinely observable and solid.
     */
    private static boolean isAcceptableLanding(AIPlayerEntity bot, ServerLevel world, BlockPos pos) {
        return !ObservableWorldQuery.canObserveCell(bot, pos) || isSafeSupport(world, pos);
    }

    private static boolean isSafeSupport(ServerLevel world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isClearableForStair(ServerLevel world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return state.getDestroySpeed(world, pos) >= 0.0F && world.getBlockEntity(pos) == null;
    }

    // After hitting bedrock (reaching the bottom), switch to horizontal digging: mine stone material
    // cell by cell along one direction, and once through, walk in and switch columns to continue.
    // The deep layer is all deepslate, so together with the constructor counting cobbled_deepslate
    // into targetDrops, enough stone material can be gathered at Y<0 to make a furnace, no more infinite loop.
    private void digHorizontal(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        for (int tries = 0; tries < HDIRS.length; tries++) {
            if ((rejectedLandingDirections & 1 << hdirIndex) != 0) {
                hdirIndex = (hdirIndex + 1) % HDIRS.length;
                continue;
            }
            Direction dir = HDIRS[hdirIndex];
            BlockPos side = feet.relative(dir);
            // side/side.up are adjacent to the body (the foot/head neighbor cells); their fluid
            // state is just as naturally visible as it would be to a real player standing next to a
            // wall, so it's checked unconditionally. side.down() (the tread) remains unknowable,
            // hidden behind solid rock, until side itself is mined open -- never pre-read here;
            // the walked step below rechecks it only at the exact moment a landing is actually
            // needed, which is precisely the honest, reactive "only see it once you break through" check.
            if (isObservedHazardFluid(bot, side) || isObservedHazardFluid(bot, side.above())) {
                hdirIndex = (hdirIndex + 1) % HDIRS.length; // water/lava is visibly touching the body in this direction, switch to another
                continue;
            }
            BlockPos solid = TerrainProbe.firstNonAir(world, side, side.above());
            if (solid != null) {
                if (miner.target() == null || !miner.target().equals(solid)) {
                    miner.begin(bot, solid); // advanced by the miner.tick at the top of onTick
                }
                return;
            }
            // An already-recorded empty cell is part of the exact return trail, not a new mining
            // frontier. After reaching the end of an old tunnel, if stepping back onto the trail were
            // allowed, direction rotation would make the bot ping-pong between the two ends of the
            // whole corridor, with no new blocks broken even though its position keeps changing. Only
            // "double-air movement" is restricted here; if a new mineable solid block appears in a
            // direction, the solid branch above still digs it normally.
            if (descentTrail.contains(side)) {
                rejectLandingDirection(feet, hdirIndex);
                hdirIndex = (hdirIndex + 1) % HDIRS.length;
                continue;
            }
            // side's foot and head cells are both empty -> walk in and switch columns, continuing to
            // dig horizontally. This is only an adjacent cell, so startWalkTo must not be restarted
            // every tick: an already-dug-through long corridor would sway back and forth as the
            // controller repeatedly changes target, eventually falsely triggering NO_PROGRESS with no
            // new block broken. Use one walked step instead (its keys, its own timeout), and write the
            // landing cell into the factual trail on the tick its landing is verified, guaranteeing an
            // exact return trip even after an interruption/restart.
            miner.cancel(bot);
            if (WalkedStep.refusal(bot, side, WalkedStep.Kind.FLAT) == null) {
                launchStep(bot, WalkedStep.begin(bot, side, WalkedStep.Kind.FLAT, "dig_down_horizontal"),
                        StepPurpose.HORIZONTAL, hdirIndex);
                return;
            }
            rejectLandingDirection(feet, hdirIndex);
            hdirIndex = (hdirIndex + 1) % HDIRS.length;
        }
        // A freshly mined horizontal frontier can close every onward direction immediately after
        // the bot steps into its drop cell. Persist an explicit bounded pickup debt before WALLED;
        // onTick services it ahead of the hard work budget, including after restart.
        armHorizontalFrontierSettle(bot);
    }

    @Override
    public Map<String, String> checkpoint() {
        if (invalidCheckpoint || startPos == null || phase == Phase.DONE || descentTrail.isEmpty()) {
            return Map.of();
        }
        DigDownCheckpoint live = new DigDownCheckpoint(
                CHECKPOINT_SCHEMA,
                BuiltInRegistries.BLOCK.getKey(targetBlock).toString(),
                targetCount,
                phase,
                returnOutcome,
                startPos,
                targetY,
                invBaseline,
                collected,
                phase == Phase.DESCEND ? Math.min(workBudget(), maxWorkBudget) : workBudgetAtReturn,
                lastProgressBudget,
                pickupGrace,
                hdirIndex,
                stairDirIndex,
                horizontalMode,
                rejectedLandingOrigin,
                rejectedLandingDirections,
                List.copyOf(descentTrail),
                returnTrailIndex,
                returnBudget(),
                lastReturnPathAttemptBudget,
                returnPathFallback,
                lastReturnProgressBudget,
                returnProgressWaypointIndex,
                returnBestDistanceSquared,
                returnSafetyRecovery);
        Map<String, String> encoded = live.encode();
        return DigDownCheckpoint.decode(encoded).isPresent() ? encoded : Map.of();
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> values) {
        return DigDownCheckpoint.decode(values).flatMap(checkpoint ->
                BuiltInRegistries.BLOCK.getOptional(Identifier.parse(checkpoint.targetBlockId()))
                        .map(block -> new RestoreMetadata(
                                block,
                                checkpoint.targetCount(),
                                checkpoint.resumePhase() == Phase.RETURN)));
    }

    public record RestoreMetadata(Block targetBlock, int targetCount, boolean returnDebt) {
    }

    record DigDownCheckpoint(int schema,
                             String targetBlockId,
                             int targetCount,
                             Phase phase,
                             ReturnOutcome returnOutcome,
                             BlockPos startPos,
                             int targetY,
                             int inventoryBaseline,
                             int collected,
                             int workBudgetUsed,
                             int lastProgressBudget,
                             int pickupGrace,
                             int horizontalDirection,
                             int stairDirection,
                             boolean horizontalMode,
                             BlockPos rejectedLandingOrigin,
                             int rejectedLandingDirections,
                             List<BlockPos> trail,
                             int returnTrailIndex,
                             int returnBudgetUsed,
                             int lastReturnPathAttemptBudget,
                             boolean returnPathFallback,
                             int lastReturnProgressBudget,
                             int returnProgressWaypointIndex,
                             long returnBestDistanceSquared,
                             boolean returnSafetyRecovery) {
        private static final Set<String> KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "rejected_landing_origin", "rejected_landing_directions",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback", "return_outcome", "return_last_progress_budget",
                "return_progress_waypoint_index", "return_best_distance_squared",
                "return_safety_recovery");
        private static final Set<String> RETURN_OUTCOME_KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "rejected_landing_origin", "rejected_landing_directions",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback", "return_outcome");
        private static final Set<String> WATER_SEAL_KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "rejected_landing_origin", "rejected_landing_directions",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback");
        private static final Set<String> LEGACY_KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback");

        DigDownCheckpoint {
            returnOutcome = returnOutcome == null ? ReturnOutcome.COMPLETE : returnOutcome;
            startPos = startPos == null ? null : startPos.immutable();
            rejectedLandingOrigin = rejectedLandingOrigin == null
                    ? null : rejectedLandingOrigin.immutable();
            trail = trail == null ? List.of() : trail.stream().map(BlockPos::immutable).toList();
        }

        DigDownCheckpoint(int schema,
                          String targetBlockId,
                          int targetCount,
                          Phase phase,
                          ReturnOutcome returnOutcome,
                          BlockPos startPos,
                          int targetY,
                          int inventoryBaseline,
                          int collected,
                          int workBudgetUsed,
                          int lastProgressBudget,
                          int pickupGrace,
                          int horizontalDirection,
                          int stairDirection,
                          boolean horizontalMode,
                          BlockPos rejectedLandingOrigin,
                          int rejectedLandingDirections,
                          List<BlockPos> trail,
                          int returnTrailIndex,
                          int returnBudgetUsed,
                          int lastReturnPathAttemptBudget,
                          boolean returnPathFallback) {
            this(schema, targetBlockId, targetCount, phase, returnOutcome, startPos, targetY,
                    inventoryBaseline, collected, workBudgetUsed, lastProgressBudget, pickupGrace,
                    horizontalDirection, stairDirection, horizontalMode, rejectedLandingOrigin,
                    rejectedLandingDirections, trail, returnTrailIndex, returnBudgetUsed,
                    lastReturnPathAttemptBudget, returnPathFallback,
                    phase == Phase.RETURN ? returnBudgetUsed : 0,
                    phase == Phase.RETURN ? returnTrailIndex : -1,
                    -1L,
                    phase == Phase.RETURN && returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED);
        }

        Phase resumePhase() {
            return phase == Phase.DESCEND
                    && collected >= grossCollectionTarget(
                    targetBlockId, targetCount, startPos, targetY)
                    ? Phase.RETURN : phase;
        }

        Map<String, String> encode() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("schema", String.valueOf(schema));
            values.put("target_block", targetBlockId);
            values.put("target_count", String.valueOf(targetCount));
            values.put("phase", phase.name());
            if (schema >= RETURN_OUTCOME_CHECKPOINT_SCHEMA) {
                values.put("return_outcome", returnOutcome.name());
            }
            values.put("start_pos", BlockPosText.encodePos(startPos));
            values.put("target_y", String.valueOf(targetY));
            values.put("inventory_baseline", String.valueOf(inventoryBaseline));
            values.put("collected", String.valueOf(collected));
            values.put("work_budget_used", String.valueOf(workBudgetUsed));
            values.put("last_progress_budget", String.valueOf(lastProgressBudget));
            values.put("pickup_grace", String.valueOf(pickupGrace));
            values.put("horizontal_direction", String.valueOf(horizontalDirection));
            values.put("stair_direction", String.valueOf(stairDirection));
            values.put("horizontal_mode", String.valueOf(horizontalMode));
            if (schema >= WATER_SEAL_CHECKPOINT_SCHEMA) {
                values.put("rejected_landing_origin", rejectedLandingOrigin == null
                        ? "none" : BlockPosText.encodePos(rejectedLandingOrigin));
                values.put("rejected_landing_directions", String.valueOf(rejectedLandingDirections));
            }
            values.put("trail", trail.stream()
                    .map(BlockPosText::encodePos)
                    .collect(java.util.stream.Collectors.joining(";")));
            values.put("return_index", String.valueOf(returnTrailIndex));
            values.put("return_budget_used", String.valueOf(returnBudgetUsed));
            values.put("last_return_path_budget", String.valueOf(lastReturnPathAttemptBudget));
            values.put("return_path_fallback", String.valueOf(returnPathFallback));
            if (schema >= CHECKPOINT_SCHEMA) {
                values.put("return_last_progress_budget", String.valueOf(lastReturnProgressBudget));
                values.put("return_progress_waypoint_index",
                        String.valueOf(returnProgressWaypointIndex));
                values.put("return_best_distance_squared",
                        String.valueOf(returnBestDistanceSquared));
                values.put("return_safety_recovery", String.valueOf(returnSafetyRecovery));
            }
            return Map.copyOf(values);
        }

        static Optional<DigDownCheckpoint> decode(Map<String, String> values) {
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            try {
                int schema = integer(values, "schema");
                if (schema == LEGACY_CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(LEGACY_KEYS)) {
                        return Optional.empty();
                    }
                } else if (schema == WATER_SEAL_CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(WATER_SEAL_KEYS)) {
                        return Optional.empty();
                    }
                } else if (schema == RETURN_OUTCOME_CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(RETURN_OUTCOME_KEYS)) {
                        return Optional.empty();
                    }
                } else if (schema == CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(KEYS)) {
                        return Optional.empty();
                    }
                } else {
                    return Optional.empty();
                }
                String blockId = Identifier.parse(required(values, "target_block")).toString();
                int targetCount = integer(values, "target_count");
                Phase phase = Phase.valueOf(required(values, "phase"));
                ReturnOutcome returnOutcome = schema < RETURN_OUTCOME_CHECKPOINT_SCHEMA
                        ? ReturnOutcome.COMPLETE
                        : ReturnOutcome.valueOf(required(values, "return_outcome"));
                BlockPos start = BlockPosText.decodePosStrictSplit(required(values, "start_pos")).orElse(null);
                int targetY = integer(values, "target_y");
                int baseline = integer(values, "inventory_baseline");
                int collected = integer(values, "collected");
                int workBudget = integer(values, "work_budget_used");
                int lastProgress = integer(values, "last_progress_budget");
                int pickupGrace = integer(values, "pickup_grace");
                int hdir = integer(values, "horizontal_direction");
                int stair = integer(values, "stair_direction");
                boolean horizontal = strictBoolean(values, "horizontal_mode");
                BlockPos rejectedOrigin = schema < WATER_SEAL_CHECKPOINT_SCHEMA
                        ? null : decodeOptionalPos(required(values, "rejected_landing_origin"));
                int rejectedDirections = schema < WATER_SEAL_CHECKPOINT_SCHEMA
                        ? 0 : integer(values, "rejected_landing_directions");
                List<BlockPos> trail = decodeTrail(required(values, "trail"));
                int returnIndex = integer(values, "return_index");
                int returnBudget = integer(values, "return_budget_used");
                int lastReturnPath = integer(values, "last_return_path_budget");
                boolean pathFallback = strictBoolean(values, "return_path_fallback");
                int returnLastProgress = schema < CHECKPOINT_SCHEMA
                        ? (phase == Phase.RETURN ? returnBudget : 0)
                        : integer(values, "return_last_progress_budget");
                int returnProgressIndex = schema < CHECKPOINT_SCHEMA
                        ? (phase == Phase.RETURN ? returnIndex : -1)
                        : integer(values, "return_progress_waypoint_index");
                long returnBestDistance = schema < CHECKPOINT_SCHEMA
                        ? -1L : longInteger(values, "return_best_distance_squared");
                boolean safetyRecovery = schema < CHECKPOINT_SCHEMA
                        ? phase == Phase.RETURN
                        && returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED
                        : strictBoolean(values, "return_safety_recovery");

                int minimumTrailY = trail.stream().mapToInt(BlockPos::getY).min().orElse(Integer.MAX_VALUE);
                boolean validTrail = start != null && !trail.isEmpty() && trail.getFirst().equals(start)
                        && trail.size() <= MAX_TRAIL_POINTS && minimumTrailY == targetY;
                for (int i = 1; validTrail && i < trail.size(); i++) {
                    validTrail = isValidReturnMicroStep(trail.get(i - 1), trail.get(i));
                }
                boolean phaseShape = switch (phase) {
                    case DESCEND -> returnIndex == -1 && returnBudget == 0 && !pathFallback
                            && returnOutcome == ReturnOutcome.COMPLETE
                            && returnLastProgress == 0 && returnProgressIndex == -1
                            && returnBestDistance == -1L && !safetyRecovery;
                    case RETURN -> returnIndex >= -1 && returnIndex < trail.size();
                    case DONE -> false;
                };
                boolean rejectedShape = rejectedDirections >= 0
                        && rejectedDirections < (1 << HDIRS.length)
                        && (rejectedDirections == 0 || rejectedOrigin != null)
                        && (rejectedOrigin == null || !trail.isEmpty()
                        && rejectedOrigin.equals(trail.getLast()))
                        && (phase != Phase.RETURN || rejectedOrigin == null);
                if (targetCount < 1 || targetCount > 4096
                        || baseline < 0 || baseline > 4096
                        || collected < 0 || collected > targetCount + 64
                        || workBudget < 0
                        || workBudget > maxWorkBudgetForTarget(blockId, targetCount)
                        || lastProgress < 0 || lastProgress > workBudget
                        || pickupGrace < 0 || pickupGrace > PICKUP_GRACE_TICKS + 1
                        || hdir < 0 || hdir >= HDIRS.length
                        || stair < 0 || stair >= HDIRS.length
                        || returnBudget < 0
                        || returnBudget > (schema >= CHECKPOINT_SCHEMA
                        && (safetyRecovery || returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED)
                        ? RETURN_SAFETY_LIMIT : RETURN_LIMIT)
                        || lastReturnPath < -RETURN_PATH_RETRY || lastReturnPath > returnBudget
                        || returnLastProgress < 0 || returnLastProgress > returnBudget
                        || returnProgressIndex < -1 || returnProgressIndex >= trail.size()
                        || returnBestDistance < -1L
                        || targetY > (start == null ? Integer.MIN_VALUE : start.getY())
                        || !validTrail || !phaseShape || !rejectedShape) {
                    return Optional.empty();
                }
                return Optional.of(new DigDownCheckpoint(schema, blockId, targetCount, phase,
                        returnOutcome,
                        start, targetY, baseline, collected, workBudget, lastProgress, pickupGrace,
                        hdir, stair, horizontal, rejectedOrigin, rejectedDirections,
                        trail, returnIndex, returnBudget,
                        lastReturnPath, pathFallback, returnLastProgress, returnProgressIndex,
                        returnBestDistance, safetyRecovery));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        private static int integer(Map<String, String> values, String key) {
            return Integer.parseInt(required(values, key));
        }

        private static long longInteger(Map<String, String> values, String key) {
            return Long.parseLong(required(values, key));
        }

        private static boolean strictBoolean(Map<String, String> values, String key) {
            return switch (required(values, key)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("invalid_boolean:" + key);
            };
        }

        private static BlockPos decodeOptionalPos(String value) {
            if ("none".equals(value)) {
                return null;
            }
            return BlockPosText.decodePosStrictSplit(value).orElseThrow();
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing_checkpoint_key:" + key);
            }
            return value;
        }

        private static List<BlockPos> decodeTrail(String encoded) {
            if (encoded.isBlank()) {
                return List.of();
            }
            List<BlockPos> result = new ArrayList<>();
            for (String part : encoded.split(";", -1)) {
                result.add(BlockPosText.decodePosStrictSplit(part).orElseThrow());
            }
            return List.copyOf(result);
        }
    }
}
