package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InCellWalk;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningMissionBudget;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * DESCEND_TO_Y (deep-ore digging rework, P1): continuously digs a vertical shaft **down to a
 * specified Y level**, then hands back control -- purpose-built for "reach the ore layer before
 * mining diamond/redstone/other deep ores".
 *
 * Root cause (observed in testing): OreDigTask coupled "dig down" and "find ore" into one
 * rate-limited scan loop. When trying to dig from Y=48 down to the diamond layer (Y<16), it
 * repeatedly cycled "lock onto an out-of-reach diagonally-below ore -> tunnel horizontally ->
 * dist gets stuck -> no_progress", hanging for 11 minutes. This task pulls "descend to the ore
 * layer" out as its own responsibility: it uses the shared {@link BlockMiner} to continuously mine
 * straight down at its feet (not subject to any rate limit); when the bot has no passive gravity it
 * descends by walking (WalkedStep: the step off the stair edge, the gravity drop into the cell it just mined; the landing is published once it is verified); it hard-stops on lava and passes through water (same as
 * DigDownTask), digging all the way down to targetY. Once at the layer, GoalExecutor takes over
 * with MINE_ORE (ore is now close by on the horizontal plane).
 *
 * A self-contained state machine (G1, not self-assigning), entirely on the main thread (G2).
 */
public final class DescendToYTask extends AbstractTask implements CheckpointableTask {
    private static final int CHECKPOINT_SCHEMA = 5;
    private static final int LANDING_DRIFT_CHECKPOINT_SCHEMA = 4; // schema had budget_limit but not landing_drift_recoveries
    private static final int EDGE_CHECKPOINT_SCHEMA = 3;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 2;
    private static final int MAX_CHECKPOINTED_WATER_SEALS = 256;
    private static final int NO_PROGRESS_LIMIT = 200;  // fail if no block is broken for 10s (can't dig / stuck)
    private static final int MIN_Y = -60;
    // One obstructed layer can be the roof of a broad natural cave, not only a lava pool.
    // Keep the graph walk bounded, but leave enough factual movement budget to reach a nearby
    // supported rim after exploring a short dead branch (seed-3000 needs 21 unique edges).
    private static final int MAX_LATERAL = 32;
    // Landing drift (knockback/pushing knocks the bot to a third cell outside origin/target) is a
    // common external-force event, not a safety-invariant violation: simply replan the stair from
    // the bot's actual current stance as the new origin. Only when drift repeats beyond this cap
    // within a single descent does the task terminate under the original fail-closed semantics
    // (indicating persistent external interference or a physics anomaly).
    private static final int MAX_LANDING_DRIFT_RECOVERIES = 8;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final int targetY;
    private final BlockMiner miner = new BlockMiner();
    // A water seal is a task-owned safety wall, not ordinary tunnel obstruction.  Direction
    // rejection alone is insufficient because a later horizontal detour can approach the same
    // cell from another origin and otherwise feed it back to BlockMiner.
    private final Map<BlockPos, Block> ownedWaterSeals = new LinkedHashMap<>();
    // A lateral detour is a bounded graph walk around one obstructed layer. Keep the directed
    // edges already crossed so a necessary one-time backtrack remains possible, while A->B->A
    // cannot immediately consume the remaining budget by replaying A->B forever.
    private final Set<DetourEdge> traversedDetourEdges = new LinkedHashSet<>();
    private final RestoreMetadata restoredCheckpoint;
    private final boolean invalidCheckpoint;
    /**
     * The generic, durable DESCEND_TO_Y goal was retired because its old checkpoints can no
     * longer prove an observed navigation intent.  A fresh child created by
     * {@link MiningExplorationTask} is different: it has no restored cursor and is owned by the
     * active mining request that just exhausted its observed search.  Keep that narrow exception
     * in this task rather than reopening the public/goal-executor entry point.
     */
    private final boolean miningExplorationChild;
    /** Cave entrances already surveyed by this mining request; a fresh re-descent rotates past them. */
    private final Set<BlockPos> excludedOpenCavities;
    private boolean committed;
    /** True only when a fresh mining-exploration child intentionally stopped at a visible cave rim. */
    private boolean completedAtObservedOpenCavity;
    /** The actually standable, visible cave entry handed to the owning mining exploration. */
    private BlockPos completedOpenCavity;
    private int budgetOffset;
    private int budgetLimit;
    private int lastProgressTick;
    private int lateralDetours; // number of lateral detours already taken around lava/obstructions at the current height level
    private int landingDriftRecoveries; // number of landing-drift recoveries already performed within this descent
    private int stairDirIndex;  // the stair's current diagonal-descent horizontal direction (index into HORIZONTAL)
    private int detourHeadingIndex = -1; // detours keep heading; an immediate reversal is tried last
    // SafetyNet runs after task ticks.  Remember the most recent physical landing so that, when
    // water/unsupported-footing recovery returns the bot to its origin, the next task tick rotates
    // away instead of issuing the identical rejected edge forever.
    private BlockPos pendingLandingOrigin;
    private BlockPos pendingLandingTarget;
    private int pendingLandingDirection = -1;
    /** Why a walked step runs (see {@link #launchStep}). */
    private enum StepPurpose {
        /** The diagonal stair step down into the cell just mined. */
        STAIR,
        /** A same-level or one-up lateral hop (the flat landing, the climb-over, a detour around an obstruction). */
        LATERAL,
        /** The walk out of a body cell that was reoccupied after a landing, back onto the previous landing. */
        RETREAT,
        /** The one diagonal step before the descent starts, onto a cell with an observed safe stair. */
        ENTRY_RELOCATION,
        /** The walk from a leaned pose (over the edge of the support) back onto the cell that supports it. */
        LEAN_RECOVERY
    }

    /** The stages of the sneak-bridge that places one floor block: lean over the edge, place, walk back to the middle of the cell. */
    private enum EdgeStage {
        SHIFTING,
        /** The floor interaction has completed; wait to admit the physical recenter step. */
        RETURN_PENDING,
        RETURNING
    }

    private static final class EdgePlacement {
        final BlockPos origin;
        final BlockPos landing;
        final BlockPos support;
        final Direction direction;
        final String item;
        WalkedStep step;
        EdgeStage stage = EdgeStage.SHIFTING;
        String placeFailure;

        EdgePlacement(BlockPos origin, BlockPos landing, BlockPos support, Direction direction, String item, WalkedStep step) {
            this.origin = origin.immutable();
            this.landing = landing.immutable();
            this.support = support.immutable();
            this.direction = direction;
            this.item = item;
            this.step = step;
        }
    }

    /** Ticks a bot that lost a step in the air is given to land before its pose is used anyway (a fall is a few ticks). */
    private static final int UNSETTLED_LIMIT = 100;

    // The walked step in flight and what it is for. Never persisted: a checkpoint holds the settled landing history, not a step; a
    // pause, a restart or a hazard abort cancels the step and the stair is re-derived from bot.blockPosition().
    private StepPurpose stepPurpose;
    /** Exact ActionPack admission for the ordinary walked step; edge helpers retain their own object. */
    private ActionPack.StepLease stepLease;
    private WalkedStep step;
    private BlockPos stepOrigin;
    private BlockPos stepTarget;
    private int stepDirIndex = -1;
    private BlockPos stepBlocked;
    private String stepBlockedName;
    private String stepReason;
    private EdgePlacement edge;
    // Steps that failed in this task instance: never retried (the flat landing, detour and relocation choosers skip them).
    private final Set<DetourEdge> failedStepEdges = new HashSet<>();
    // Set when a step was abandoned or failed: the bot may be in the air between two cells, so nothing is decided from its pose
    // until it stands on something again.
    private boolean poseUnsettled;
    private int unsettledTicks;
    private BlockPos rejectedLandingOrigin;
    private int rejectedLandingDirections;
    // Falling sand/gravel is scheduled after the task tick that opened the stair. Keep the last
    // two factual, dry landings so a newly buried bot can take exactly one ordinary adjacent step
    // back instead of depending on strict-survival's forbidden emergency teleport.
    private BlockPos latestSafeLanding;
    private BlockPos previousSafeLanding;
    private BlockPos blockedBodyRecoveryTarget;
    // Not checkpointed: purely a same-instance, same-level memory of whether THIS task itself has
    // already started mining `ahead`/`ahead.up()` from the current `feet` -- i.e. whether a later
    // "ahead is open" observation reflects genuinely pre-existing terrain (a real cave rim, never
    // touched yet) or is merely an artifact of the bot's own in-progress excavation of this exact
    // stair tread. See the flat-landing shortcut below for why this distinction matters.
    private BlockPos selfCarvedAheadAt;
    private boolean started;    // whether the descend_started log has already been emitted
    private int lastTorchY = Integer.MAX_VALUE; // P1: the Y of the last placed torch (one torch every TORCH_EVERY blocks descended)
    private static final int TORCH_EVERY = 6;   // a torch's light radius comfortably covers a 6-block drop, preventing mob spawns

    public DescendToYTask(int targetY) {
        this(targetY, Map.of(), false, Set.of());
    }

    public DescendToYTask(int targetY, Map<String, String> checkpoint) {
        this(targetY, checkpoint, false, Set.of());
    }

    /**
     * Creates a non-durable, fresh descent owned by {@link MiningExplorationTask}.  Package
     * visibility is intentional: goal restoration and other packages must continue to use the
     * retired public DESCEND_TO_Y route rather than accidentally reviving an old checkpoint.
     */
    static DescendToYTask forMiningExploration(int targetY) {
        return forMiningExploration(targetY, Set.of());
    }

    /**
     * Creates a fresh exploration descent while excluding one cave entry already surveyed by the
     * same parent request. This prevents a restarted staircase from completing at its old rim
     * again instead of selecting another proven-safe downward direction.
     */
    static DescendToYTask forMiningExploration(int targetY, Set<BlockPos> excludedOpenCavities) {
        return new DescendToYTask(targetY, Map.of(), true, excludedOpenCavities);
    }

    /** Whether this fresh child completed because it exposed a dry, visible cave rather than its target layer. */
    boolean completedAtObservedOpenCavity() {
        return completedAtObservedOpenCavity;
    }

    /** The dry, observed stand cell inside the cave when {@link #completedAtObservedOpenCavity()} is true. */
    BlockPos completedOpenCavity() {
        return completedOpenCavity == null ? null : completedOpenCavity.immutable();
    }

    private DescendToYTask(int targetY, Map<String, String> checkpoint,
                           boolean miningExplorationChild, Set<BlockPos> excludedOpenCavities) {
        this.targetY = targetY;
        this.miningExplorationChild = miningExplorationChild;
        this.excludedOpenCavities = excludedOpenCavities == null ? Set.of() : Set.copyOf(excludedOpenCavities);
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Optional<RestoreMetadata> restored = inspectCheckpoint(values);
        this.invalidCheckpoint = !values.isEmpty()
                && (restored.isEmpty() || restored.orElseThrow().targetY() != targetY);
        this.restoredCheckpoint = invalidCheckpoint ? null : restored.orElse(null);
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return Optional.empty();
        }
        try {
            int schema = Integer.parseInt(required(checkpoint, "task_schema"));
            boolean legacyWithoutEdges = schema == LEGACY_CHECKPOINT_SCHEMA;
            boolean legacyBudget = legacyWithoutEdges || schema == EDGE_CHECKPOINT_SCHEMA;
            // landing_drift_recoveries was added after budget_limit: a schema-4 checkpoint (from
            // before this field existed) has budget_limit but not landing_drift_recoveries, and
            // must still restore exactly as before -- treat it as landingDriftRecoveries=0 rather
            // than rejecting the checkpoint (see LANDING_DRIFT_CHECKPOINT_SCHEMA).
            boolean legacyLandingDrift = legacyBudget || schema == LANDING_DRIFT_CHECKPOINT_SCHEMA;
            if (!legacyLandingDrift && schema != CHECKPOINT_SCHEMA) {
                return Optional.empty();
            }
            Set<String> expectedKeys = new LinkedHashSet<>(Set.of(
                    "task_schema",
                    "task_open",
                    "target_y",
                    "budget_used",
                    "last_progress_budget",
                    "stair_direction",
                    "lateral_detours",
                    "detour_heading",
                    "pending_landing_origin",
                    "pending_landing_target",
                    "pending_landing_direction",
                    "rejected_landing_origin",
                    "rejected_landing_directions",
                    "last_torch_y",
                    "owned_water_seals"));
            if (!legacyWithoutEdges) {
                expectedKeys.add("traversed_detour_edges");
            }
            if (!legacyBudget) {
                expectedKeys.add("budget_limit");
            }
            if (!legacyLandingDrift) {
                expectedKeys.add("landing_drift_recoveries");
            }
            if (!checkpoint.keySet().equals(expectedKeys)) {
                return Optional.empty();
            }
            boolean taskOpen = strictBoolean(required(checkpoint, "task_open"));
            int restoredTargetY = Integer.parseInt(required(checkpoint, "target_y"));
            int budgetUsed = Integer.parseInt(required(checkpoint, "budget_used"));
            int lastProgressBudget = Integer.parseInt(required(checkpoint, "last_progress_budget"));
            int budgetLimit = legacyBudget
                    ? -1 : Integer.parseInt(required(checkpoint, "budget_limit"));
            int landingDriftRecoveries = legacyLandingDrift
                    ? 0 : Integer.parseInt(required(checkpoint, "landing_drift_recoveries"));
            int stairDirection = Integer.parseInt(required(checkpoint, "stair_direction"));
            int lateralDetours = Integer.parseInt(required(checkpoint, "lateral_detours"));
            int detourHeading = Integer.parseInt(required(checkpoint, "detour_heading"));
            BlockPos pendingOrigin = decodeOptionalPos(required(checkpoint, "pending_landing_origin"));
            BlockPos pendingTarget = decodeOptionalPos(required(checkpoint, "pending_landing_target"));
            int pendingDirection = Integer.parseInt(required(checkpoint, "pending_landing_direction"));
            BlockPos rejectedOrigin = decodeOptionalPos(required(checkpoint, "rejected_landing_origin"));
            int rejectedDirections = Integer.parseInt(required(checkpoint, "rejected_landing_directions"));
            int lastTorchY = Integer.parseInt(required(checkpoint, "last_torch_y"));
            Set<DetourEdge> traversedEdges = legacyWithoutEdges
                    ? Set.of()
                    : decodeDetourEdges(required(checkpoint, "traversed_detour_edges"));
            String encoded = required(checkpoint, "owned_water_seals");
            Map<BlockPos, Block> seals = new LinkedHashMap<>();
            if (!encoded.isBlank()) {
                String[] entries = encoded.split(";", -1);
                if (entries.length > MAX_CHECKPOINTED_WATER_SEALS) {
                    return Optional.empty();
                }
                for (String entry : entries) {
                    String[] coordinates = entry.split(",", -1);
                    if (coordinates.length != 4) {
                        return Optional.empty();
                    }
                    BlockPos seal = new BlockPos(
                            Integer.parseInt(coordinates[0]),
                            Integer.parseInt(coordinates[1]),
                            Integer.parseInt(coordinates[2]));
                    Block sealBlock = BuiltInRegistries.BLOCK
                            .getOptional(Identifier.parse(coordinates[3])).orElse(null);
                    if (sealBlock == null || !MaterialPalette.isSacrificialBlock(sealBlock)
                            || sealBlock.defaultBlockState().isAir()
                            || !sealBlock.defaultBlockState().getFluidState().isEmpty()
                            || seals.putIfAbsent(seal, sealBlock) != null) {
                        return Optional.empty();
                    }
                }
            }
            boolean pendingShape = (pendingOrigin == null) == (pendingTarget == null)
                    && ((pendingOrigin == null && pendingDirection == -1)
                    || (pendingOrigin != null
                    && pendingDirection >= 0 && pendingDirection < HORIZONTAL.length
                    && pendingTarget.equals(pendingOrigin.relative(HORIZONTAL[pendingDirection]).below())));
            boolean rejectedShape = rejectedDirections >= 0
                    && rejectedDirections < (1 << HORIZONTAL.length)
                    && (rejectedDirections == 0 || rejectedOrigin != null);
            boolean valid = restoredTargetY > MIN_Y && restoredTargetY <= 320
                    && (legacyBudget
                    ? budgetUsed >= 0
                    && budgetUsed <= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS + 1
                    : budgetLimit >= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    && budgetLimit <= MiningMissionBudget.DESCEND_HARD_WINDOW_TICKS
                    && budgetUsed >= 0 && budgetUsed <= budgetLimit + 1)
                    && lastProgressBudget >= 0 && lastProgressBudget <= budgetUsed
                    && stairDirection >= 0 && stairDirection < HORIZONTAL.length
                    && lateralDetours >= 0 && lateralDetours <= MAX_LATERAL
                    && lateralDetours == traversedEdges.size()
                    && (!legacyWithoutEdges || lateralDetours == 0)
                    && detourHeading >= -1 && detourHeading < HORIZONTAL.length
                    && (traversedEdges.isEmpty()
                    || directionIndex(lastEdge(traversedEdges)) == detourHeading)
                    && pendingShape && rejectedShape
                    && (taskOpen || pendingOrigin == null)
                    && (lastTorchY == Integer.MAX_VALUE || lastTorchY >= -64 && lastTorchY <= 320)
                    && landingDriftRecoveries >= 0
                    && landingDriftRecoveries <= MAX_LANDING_DRIFT_RECOVERIES;
            if (!valid) {
                return Optional.empty();
            }
            return Optional.of(new RestoreMetadata(
                    restoredTargetY,
                    taskOpen,
                    budgetUsed,
                    lastProgressBudget,
                    budgetLimit,
                    stairDirection,
                    lateralDetours,
                    landingDriftRecoveries,
                    detourHeading,
                    pendingOrigin,
                    pendingTarget,
                    pendingDirection,
                    rejectedOrigin,
                    rejectedDirections,
                    lastTorchY,
                    seals,
                    traversedEdges));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public record RestoreMetadata(int targetY,
                                  boolean transactionOpen,
                                  int budgetUsed,
                                  int lastProgressBudget,
                                  int budgetLimit,
                                  int stairDirection,
                                  int lateralDetours,
                                  int landingDriftRecoveries,
                                  int detourHeading,
                                  BlockPos pendingLandingOrigin,
                                  BlockPos pendingLandingTarget,
                                  int pendingLandingDirection,
                                  BlockPos rejectedLandingOrigin,
                                  int rejectedLandingDirections,
                                  int lastTorchY,
                                  Map<BlockPos, Block> ownedWaterSeals,
                                  Set<DetourEdge> traversedDetourEdges) {
        public RestoreMetadata {
            pendingLandingOrigin = pendingLandingOrigin == null
                    ? null : pendingLandingOrigin.immutable();
            pendingLandingTarget = pendingLandingTarget == null
                    ? null : pendingLandingTarget.immutable();
            rejectedLandingOrigin = rejectedLandingOrigin == null
                    ? null : rejectedLandingOrigin.immutable();
            ownedWaterSeals = ownedWaterSeals == null ? Map.of() : Map.copyOf(ownedWaterSeals);
            traversedDetourEdges = traversedDetourEdges == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(traversedDetourEdges));
        }
    }

    public record DetourEdge(BlockPos origin, BlockPos target) {
        public DetourEdge {
            if (origin == null || target == null) {
                throw new IllegalArgumentException("detour edge positions are required");
            }
            origin = origin.immutable();
            target = target.immutable();
        }
    }

    @Override
    public String name() {
        return "descend";
    }

    @Override
    public String describe() {
        return "Descend to Y=" + targetY;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.5D;
    }

    @Override
    public boolean isWaiting() {
        // While digging down the bot stands and mines in place, so its position barely changes; report waiting so StuckWatcher doesn't misjudge it as stuck -- this task's own NO_PROGRESS_LIMIT watchdog is the real backstop.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        completedAtObservedOpenCavity = false;
        completedOpenCavity = null;
        if (!miningExplorationChild && RetiredNavigationTask.legacyExcavationDisabled()) {
            RetiredNavigationTask.refuse(bot, "descend_to_y");
            fail(RetiredNavigationTask.OBSERVED_TARGET_REQUIRED);
            return;
        }

        if (restoredCheckpoint == null) {
            budgetOffset = 0;
            budgetLimit = budgetLimitFor(bot.blockPosition().getY(), targetY);
            lastProgressTick = 0;
            lateralDetours = 0;
            landingDriftRecoveries = 0;
            detourHeadingIndex = -1;
            traversedDetourEdges.clear();
        } else {
            budgetOffset = restoredCheckpoint.budgetUsed();
            // Schema 2/3 had a fixed 4,800-tick clock. Promote a live legacy checkpoint once from
            // its factual restart height, but never resurrect an already exhausted old terminal.
            budgetLimit = restoredCheckpoint.budgetLimit()
                    >= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    ? restoredCheckpoint.budgetLimit()
                    : budgetOffset >= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    ? MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    : migratedLegacyBudgetLimit(
                            budgetOffset, bot.blockPosition().getY(), targetY);
            lastProgressTick = restoredCheckpoint.lastProgressBudget();
            stairDirIndex = restoredCheckpoint.stairDirection();
            lateralDetours = restoredCheckpoint.lateralDetours();
            landingDriftRecoveries = restoredCheckpoint.landingDriftRecoveries();
            detourHeadingIndex = restoredCheckpoint.detourHeading();
            pendingLandingOrigin = restoredCheckpoint.pendingLandingOrigin();
            pendingLandingTarget = restoredCheckpoint.pendingLandingTarget();
            pendingLandingDirection = restoredCheckpoint.pendingLandingDirection();
            rejectedLandingOrigin = restoredCheckpoint.rejectedLandingOrigin();
            rejectedLandingDirections = restoredCheckpoint.rejectedLandingDirections();
            lastTorchY = restoredCheckpoint.lastTorchY();
            ownedWaterSeals.putAll(restoredCheckpoint.ownedWaterSeals());
            traversedDetourEdges.addAll(restoredCheckpoint.traversedDetourEdges());
            started = budgetOffset > 0;
            committed = !restoredCheckpoint.transactionOpen();

            if (committed) {
                complete();
                return;
            }

            // NavSafetyNet's transient controller is not part of the Mission checkpoint. If the
            // server stopped after the stair step but before the landing was accepted, an entity back
            // at the origin cannot safely retry that same edge. Preserve the physical debt by
            // rejecting the direction; a bot already at the target is validated by onTick.
            BlockPos feet = bot.blockPosition();
            if (pendingLandingOrigin != null && feet.equals(pendingLandingOrigin)) {
                int interruptedDirection = pendingLandingDirection;
                pendingLandingOrigin = null;
                pendingLandingTarget = null;
                pendingLandingDirection = -1;
                rejectLandingDirection(feet, interruptedDirection);
            }
            // A restored bot may be mid-air (a step was in flight when the process stopped): nothing is decided from its pose until it
            // stands on something, then the stair is re-derived from where it is.
            if (!WalkedStep.supported(bot) && !bot.isInWater()) {
                poseUnsettled = true;
                unsettledTicks = 0;
            }
        }
        // Armor-up bonus: before descending into a dangerous deep layer, proactively equip the
        // best armor in the inventory (the diamond plan already stocked a helmet + chestplate in
        // the preamble). Most deep-descent deaths are survival deaths (lava/mobs/low health), and
        // iron armor directly reduces damage taken; equip it now rather than waiting for combat to
        // trigger it, since it also protects against passive damage. Armor is a non-tool, so it is best-first everywhere: the background
        // armor pass (autoEquipArmor) wears the same best piece, this call just does it now.
        io.github.zoyluo.minecraftai.action.EquipAction.equipBestArmor(bot);
        initializeSafeLandingHistory(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        blockedBodyRecoveryTarget = null;
        bot.getActionPack().stopAll();
        abandonStep(bot);
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        // A completed stair can be interrupted between the pack publishing its outcome and this
        // task's next tick. Settle that factual landing before abandoning the task-owned step.
        settleCompletedStepBeforeAbandon(bot);
        // A walked step still in flight is cancelled (keys released); the stair is re-derived from where the bot stands when it resumes.
        abandonStep(bot);
        // A safety task can interrupt in the same server tick that Descend physically reached a
        // dry landing. Accept that factual landing before shelter/combat is allowed to move the
        // bot away; otherwise the resumed task compares the new safety position with a stale
        // origin/target pair and reports descend_landing_pose_drift.
        settlePendingLandingAtCurrentPose(bot);
        // Safety work may seal the currently open stair. Do not retain an in-flight BlockMiner
        // cursor that would immediately reopen that wall when the descent resumes.
        miner.cancel(bot);
        blockedBodyRecoveryTarget = null;
        bot.getActionPack().stopAll();
    }

    void avoidCurrentDescentDirection(AIPlayerEntity bot, BlockPos threatPos) {
        // DangerWatcher calls this immediately before pauseFor(). Settle the landing first, then
        // record the threat rejection against the new factual origin. onPause() will subsequently
        // no-op and therefore preserve this newly recorded rejection.
        settleCompletedStepBeforeAbandon(bot);
        boolean stairStep = stepPurpose == StepPurpose.STAIR;
        BlockPos stairOrigin = stepOrigin;
        abandonStep(bot);
        settlePendingLandingAtCurrentPose(bot);
        // A stair step that was cut short leaves the bot between two cells: the rejection belongs to the cell the step began in.
        BlockPos origin = stairStep && stairOrigin != null ? stairOrigin : bot.blockPosition();
        miner.cancel(bot);
        rejectLandingDirection(origin, stairDirIndex);
        BotLog.danger(bot, "descend_threat_direction_rejected",
                "from", origin.toShortString(),
                "direction", HORIZONTAL[stairDirIndex].getSerializedName(),
                "threat", threatPos == null ? "unknown" : threatPos.toShortString());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("descend_invalid_checkpoint");
            return;
        }
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        // A task-owned walking/landing hold must never hide either terminal bound. In particular,
        // lava escape and overshoot handling are allowed to interrupt a fall instead of waiting up
        // to UNSETTLED_LIMIT ticks for unsupported footing.
        if (totalBudget() > budgetLimit) {
            failAfterStoppingOwnedWork(bot, "descend_timeout at_y=" + feet.getY());
            return;
        }
        if (feet.getY() < targetY) {
            failAfterStoppingOwnedWork(bot,
                    "descend_overshoot_unrecoverable target_y=" + targetY + " at_y=" + feet.getY());
            pendingLandingOrigin = null;
            pendingLandingTarget = null;
            pendingLandingDirection = -1;
            BotLog.danger(bot, "descend_overshoot_unrecoverable",
                    "target_y", targetY, "at_y", feet.getY(), "at", feet.toShortString());
            return;
        }
        if (holdForStep(bot, world)) {
            return; // a walked step is in flight (or its bot is still landing): the stair changes only when the landing is verified
        }
        feet = bot.blockPosition();
        // DangerWatcher schedules lava escape after task ticks. Do not start another mine/walk
        // controller in the same tick once the hold has released an in-lava pose.
        if (bot.isInLava()) {
            miner.cancel(bot);
            return;
        }
        // Vanilla falling-block updates run after task decisions. A stair that was clear when the
        // bot entered it can therefore become occupied before the next task tick. Resolve the
        // current collision first; continuing to mine the next stair leaves the bot suffocating,
        // and eating cannot remove the block around its head.
        if (recoverBlockedBody(bot, world, feet)) {
            return;
        }
        rememberSafeLanding(world, feet);
        if (handleRejectedLanding(bot, world, feet)) {
            return;
        }
        // A water expedition can hand Descend a dry shoreline stance whose four cardinal lower
        // landings are all water or unsupported even though an immediately adjacent diagonal
        // stance has a normal safe stair. Before the first mutation only, inspect that bounded
        // 3x3 neighborhood and take one ordinary physical step. This is deliberately not a
        // general path search: an already observed safe cardinal stair keeps priority, while an
        // occluded support is never read or promoted to safe. Both diagonal corner columns and
        // the complete destination/stair envelope must be observable and passable (the same
        // no-corner-clipping invariant as A*). If that positive proof is unavailable, retain the
        // existing fail-closed descent behavior.
        if (tryFreshEntryRelocation(bot, world, feet)) {
            return;
        }
        // An aquifer's water can flow sideways/downward into a freshly dug stair after an adjacent
        // stone block is broken. Checking only whether the next cell is already water is not
        // enough: at check time it is still stone, and only becomes flowing water once the break
        // completes. Same as DigDownTask: every tick, use an inventory block to seal off the water
        // around the foot/head cells one cell at a time, restoring a dry working bubble before
        // continuing the descent.
        if (sealLateralWater(bot, world)) {
            return;
        }
        // Reaching the target layer must also hand off a dry working face. The old logic completed
        // immediately even underwater at Y=16, and the subsequent OreDig would see no workable cell
        // and incorrectly replan to chop trees at the bottom of the mine after 200 ticks.
        if (feet.getY() == targetY) {
            // Entity water flags lag a server tick behind fluid placement/removal. Read the
            // authoritative cells as well and require actual footing before handing the face to
            // OreDig; otherwise a just-sealed flow can still be present for one fluid tick and
            // Descend reports success from inside it.
            Standability.clearCache();
            boolean wet = bot.isUnderWater()
                    || bot.isInWater()
                    || world.getFluidState(feet).is(FluidTags.WATER)
                    || world.getFluidState(feet.above()).is(FluidTags.WATER);
            if (wet || !Standability.isStandable(world, feet)) {
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                lastProgressTick = totalBudget();
                return;
            }
            miner.cancel(bot);
            committed = true;
            complete();
            return;
        }
        maybePlaceTorch(bot, world, feet); // P1: place torches at fixed intervals while descending so a deep shaft is no longer pitch black and mob-spawning (observed: descending to Y-58 stayed light=0 throughout and the bot was swarmed by skeletons)
        BlockPos below = feet.below();
        if (below.getY() <= MIN_Y) {
            fail("descend_reached_min_y");
            return;
        }
        // Stuck too long (can't dig / blocked) -> first try a lateral detour around it; only fail if all four sides are impassable.
        if (totalBudget() - lastProgressTick > NO_PROGRESS_LIMIT) {
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = totalBudget();
                return;
            }
            miner.cancel(bot);
            fail("descend_no_progress at_y=" + feet.getY());
            return;
        }

        // Advance the current mining operation.
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return;
        }
        if (status == BlockMiner.Status.DONE) {
            lastProgressTick = totalBudget();
        }

        // Stair-style diagonal descent (human-like + safe): dig "the next stair step" (diagonally
        // forward-down), exposing what's ahead first -- if the next step or its tread is water/lava,
        // switch direction and go around; never mine straight down through the feet (to avoid one
        // pickaxe swing breaking through into water/lava below). Descend diagonally one step at a
        // time, like digging a staircase.
        ensureRejectedLandingOrigin(feet);
        if ((rejectedLandingDirections & 1 << stairDirIndex) != 0) {
            if (rotateStair(bot, world, feet)) {
                return;
            }
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = totalBudget();
                return;
            }
            fail("descend_no_safe_landing at_y=" + (feet.getY() - 1));
            return;
        }
        Direction dir = HORIZONTAL[stairDirIndex];
        BlockPos ahead = feet.relative(dir);   // next step's head cell (x+d, y)
        BlockPos next = ahead.below();         // next step's standing cell (x+d, y-1)
        if (containsOwnedWaterSeal(world, ahead, ahead.above(), next)
                || !isViableDescentDirection(bot, world, feet, stairDirIndex)) {
            // Mining next's tread can reveal that its own support is an already-mined cavity or a
            // natural cave rather than solid ground. A mining-exploration child stops at a
            // genuinely visible dry cave mouth so its parent can survey/explore that real open
            // space; a generic descent still walls it off before rerouting rather than leaving a
            // hole into unknown space behind it.
            if (skipPreviouslySurveyedOpenCavity(bot, world, feet, next)) {
                return;
            }
            if (completeMiningExplorationAtObservedOpenCavity(bot, world, next)) {
                return;
            }
            if (trySealOpenCavityLanding(bot, world, feet, next, stairDirIndex)) {
                return;
            }
            rejectLandingDirection(feet, stairDirIndex);
            if (rotateStair(bot, world, feet)) {
                return; // switched to a diagonal-descent direction that doesn't touch water/lava
            }
            // All four diagonal-descent directions are blocked by water/lava -> fall back to a lateral detour (stuck-condition backstop).
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = totalBudget();
                return;
            }
            miner.cancel(bot);
            fail("descend_no_safe_landing at_y=" + next.getY());
            return;
        }
        // Clear the next step's body space: ahead (the forward head cell, visible, mined first) +
        // ahead.up() (forward-up, headroom clearance) + next (the foot cell). The key extra dig is
        // ahead.up() -- the old stair that only cleared next+ahead gave each column just 2 cells
        // (Y-1, Y); when the player walks down from the previous step, their head hits the solid
        // ceiling at the forward Y+1 cell, leaving only 1 walkable-height cell on the diagonal step
        // down, which a normal player (1.8 blocks tall) cannot fit through (observed: the bot
        // physically could not pass through the descent shaft). After adding ahead.up(), the descent
        // tunnel has a genuine 2-cell-high clearance along the diagonal and is passable. firstSolid3
        // skips fluids to avoid a lava/water collapse.
        BlockPos solid = TerrainProbe.firstSolid(world, ahead, ahead.above(), next);
        DetourEdge flatLandingEdge = new DetourEdge(feet, ahead);
        if (solid != null && solid.equals(next) && isObservedDryStandable(bot, world, ahead)
                && !feet.equals(selfCarvedAheadAt)
                && lateralDetours < MAX_LATERAL
                && !traversedDetourEdges.contains(flatLandingEdge)
                && !failedStepEdges.contains(flatLandingEdge)
                && WalkedStep.refusal(bot, ahead, WalkedStep.Kind.FLAT) == null) {
            // `ahead`/`ahead.up()` are already open and `ahead` itself is a fully observed, dry,
            // safely supported landing at the CURRENT height -- only the riser one level further
            // down (`next`) is solid. A real player standing here sees ordinary flat ground
            // immediately in front of them and just walks onto it; mining through that ground to
            // force a descent nothing asked for would only destroy the one solid floor this same
            // level may still need (a supported cave rim's own footing). Take the free flat step
            // instead of digging through it -- unless that exact same-level edge was already
            // traversed (and possibly backed out of) earlier in this detour, or the lateral budget
            // is already spent: this shortcut is itself a lateral, same-level hop and must be
            // recorded and bounded exactly like tryLateralDetour's own moves, or a restored
            // checkpoint could bounce forever between two cells / smuggle unbounded free detours.
            // `!feet.equals(selfCarvedAheadAt)` excludes the OTHER way `ahead`/`ahead.up()` can end
            // up open: this same task, from this same `feet`, having just mined them itself as the
            // first two of this level's own three-block stair tread. That is not a pre-existing cave
            // rim at all -- it is this task's own excavation, one step from finishing -- and a real
            // player mid-dig on their own planned staircase keeps mining the tread instead of
            // spontaneously wandering sideways just because the headroom happened to clear first.
            // Without this, an ordinary uniform straight staircase (every level: mine ahead, mine
            // ahead.up(), discover its own third block still solid) would take this "shortcut" at
            // literally every level, forever trading a level of real descent for a same-height
            // sideways hop until the lateral budget ran out -- never the honest reactive detour this
            // shortcut exists for.
            // A walked step onto the flat landing: the detour edge is recorded when the landing is verified (settleStep).
            miner.cancel(bot);
            launchStep(bot, WalkedStep.begin(bot, ahead, WalkedStep.Kind.FLAT, "descend_flat_landing"),
                    StepPurpose.LATERAL, feet, ahead, stairDirIndex, "descend_flat_landing");
            return;
        }
        BlockPos climbTarget = ahead.above();
        DetourEdge climbEdge = new DetourEdge(feet, climbTarget);
        if (solid != null && solid.equals(ahead)
                && !(canObservePosition(bot, next.below()) && hasSafeSupport(world, next))
                && !Standability.isDangerous(world.getBlockState(ahead))
                && isObservedDryPassableColumn(bot, world, ahead.above())
                && lateralDetours < MAX_LATERAL
                && !traversedDetourEdges.contains(climbEdge)
                && !failedStepEdges.contains(climbEdge)
                && WalkedStep.refusal(bot, climbTarget, WalkedStep.Kind.STEP_UP) == null) {
            // `ahead` is solid and would have to be mined to make any progress this direction at
            // all, but the landing one level further down (`next`) is not a CONFIRMED-safe
            // support -- isViableDescentDirection only allowed this attempt because that deeper
            // cell is still hidden behind `ahead` itself. `ahead` also ALREADY, visibly, right
            // now (no mining needed) supports a completely safe climb-over landing on its own top
            // face. Mining `ahead` merely to test the hidden landing would permanently destroy
            // that support even if the gamble fails -- unlike an ordinary staircase riser, this
            // block cannot be un-mined once the reactive check below rejects a bad landing. A real
            // player facing a chest-high block with clear headroom above it climbs over it instead
            // of digging through it; take that zero-risk step instead. This is itself a lateral
            // hop (dy == 1, exactly tryLateralDetour's own upper-retreat shape), so it shares that
            // same budget and no-replay bookkeeping.
            // A hop onto it (forward and jump). The detour edge and the rejection of the exact reverse stair from the new landing (so
            // a restart cannot descend back into the cell just escaped, mirroring the lateral detour's upper-retreat bookkeeping) are
            // recorded when the landing is verified (settleStep).
            miner.cancel(bot);
            launchStep(bot, WalkedStep.begin(bot, climbTarget, WalkedStep.Kind.STEP_UP, "descend_climb_over"),
                    StepPurpose.LATERAL, feet, climbTarget, stairDirIndex, "descend_climb_over");
            return;
        }
        if (solid != null) {
            // Tool gate (same as DigDownTask): fail immediately with a typed reason when no
            // qualifying pickaxe is available, letting GoalExecutor work backward to restock a
            // pickaxe; otherwise grinding away at deepslate bare-handed would burn the entire descent
            // window into an untyped descend_timeout.
            if (!ToolTier.canHarvestWithInventory(bot, world.getBlockState(solid))) {
                miner.cancel(bot);
                bot.getActionPack().stopAll();
                fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(
                        world.getBlockState(solid).getBlock()));
                return;
            }
            if (solid.equals(ahead) || solid.equals(ahead.above())) {
                // Remember that THIS task, from THIS `feet`, is the one clearing the headroom --
                // see the flat-landing shortcut's guard above.
                selfCarvedAheadAt = feet.immutable();
            }
            miner.begin(bot, solid);
            miner.tick(bot);
            markStarted(bot, feet);
            return;
        }
        // Body space is now clear -> walk diagonally down onto the next stair step (off the edge of the tread; gravity lands it).
        BlockPos origin = feet.immutable();
        WalkedStep descent = bot.getActionPack().beginDescend(next, "descend_stair");
        if (descent == null) {
            if (bot.getActionPack().stepAdmissionBlocked()) {
                // beginDescend also observes the guarded handoff boundary. No terrain verdict
                // was made, so leave this stair direction untouched and retry it next tick.
                return;
            }
            rejectLandingDirection(origin, stairDirIndex);
            rotateStair(bot, world, origin);
            BotLog.action(bot, "descend_landing_rejected",
                    "from", origin.toShortString(), "target", next.toShortString());
            markStarted(bot, feet);
            return;
        }
        // The bot walks off the edge of its tread and gravity lands it on the next one. The landing joins the pending-landing
        // record only when the step has verified it (settleStep), never in the tick that starts the step.
        launchStep(bot, descent, StepPurpose.STAIR, origin, next, stairDirIndex, "descend_stair");
    }

    /**
     * Clears or physically backs out of a body cell that was reoccupied after a verified landing.
     * This is deliberately task-local: Descend owns the adjacent factual stair history and can
     * reject the collapsed edge, while NavSafetyNet has no such history and its long-range
     * suffocation snap is correctly denied by strict_survival.
     */
    private boolean recoverBlockedBody(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        BlockPos blocked = firstBodyCollision(world, feet);
        if (blocked == null) {
            if (blockedBodyRecoveryTarget == null) {
                return false;
            }
            BlockPos recoveryTarget = blockedBodyRecoveryTarget;
            BlockState recoveryState = world.getBlockState(recoveryTarget);
            if (recoveryState.getCollisionShape(world, recoveryTarget).isEmpty()) {
                // The ordinary BlockMiner broke the obstruction (or it resumed falling). Cancel
                // its now-stale cursor and yield one tick so another falling block must also pass
                // this preflight before normal descent can resume.
                miner.cancel(bot);
                blockedBodyRecoveryTarget = null;
                lastProgressTick = totalBudget();
                return true;
            }
            // A collapsed same-level detour is first observed while it occupies the bot's body.
            // After the exact physical retreat, keep clearing that now-adjacent factual block
            // before general detour search is allowed to promote it into an upper landing. This
            // does not authorize a hidden support read: only the already observed body cell is
            // mined, and ordinary landing proof still runs after it becomes air.
            if (!isAdjacentSameLevel(feet, recoveryTarget)
                    || !canObservePosition(bot, recoveryTarget)
                    || recoveryState.getFluidState().is(FluidTags.LAVA)) {
                miner.cancel(bot);
                blockedBodyRecoveryTarget = null;
                return true;
            }
            if (miner.target() == null || !miner.target().equals(recoveryTarget)) {
                miner.begin(bot, recoveryTarget);
            }
            BlockMiner.Status recoveryStatus = miner.tick(bot);
            markStarted(bot, feet);
            lastProgressTick = totalBudget();
            if (recoveryStatus == BlockMiner.Status.FAILED) {
                blockedBodyRecoveryTarget = null;
                fail("descend_blocked_body_clear_failed at=" + feet.toShortString()
                        + " blocked=" + recoveryTarget.toShortString()
                        + " reason=" + miner.failureReason());
            }
            return true;
        }

        BlockState obstruction = world.getBlockState(blocked);
        BlockPos retreat = findRecentPhysicalRetreat(world, feet);
        WalkedStep.Kind retreatKind = retreat == null ? null : WalkedStepRules.walkKindFor(retreat.getY() - feet.getY());
        if (retreat != null && retreatKind != null && WalkedStep.refusal(bot, retreat, retreatKind) == null) {
            // The bot walks (or hops) back onto the landing it came from; the bookkeeping of the retreat happens when the landing is
            // verified (settleStep). A step that cannot start (the head room of a hop is the blocked cell itself) falls through to mining.
            miner.cancel(bot);
            if (launchStep(bot, WalkedStep.begin(bot, retreat, retreatKind, "descend_blocked_body_retreat"),
                    StepPurpose.RETREAT, feet, retreat, -1, "descend_blocked_body_retreat")) {
                // This metadata belongs to the admitted step only. A fenced admission must not
                // let a future foreign result look like this task's blocked-body retreat.
                stepBlocked = blocked.immutable();
                stepBlockedName = String.valueOf(BuiltInRegistries.BLOCK.getKey(obstruction.getBlock()));
            }
            return true;
        }

        // No still-valid adjacent landing remains (for example after a restart). Mine the visible
        // block occupying the bot's own body with the normal survival BlockMiner. Prefer the head
        // cell in firstBodyCollision so breathing is restored before any lower obstruction.
        if (!blocked.equals(blockedBodyRecoveryTarget)
                || miner.target() == null || !miner.target().equals(blocked)) {
            rejectCollapsedStairForMiningFallback(bot, feet);
            miner.begin(bot, blocked);
            blockedBodyRecoveryTarget = blocked.immutable();
            BotLog.danger(bot, "descend_blocked_body_clear",
                    "at", feet.toShortString(),
                    "blocked", blocked.toShortString(),
                    "block", BuiltInRegistries.BLOCK.getKey(obstruction.getBlock()));
        }
        BlockMiner.Status status = miner.tick(bot);
        markStarted(bot, feet);
        if (status == BlockMiner.Status.FAILED) {
            blockedBodyRecoveryTarget = null;
            fail("descend_blocked_body_clear_failed at=" + feet.toShortString()
                    + " blocked=" + blocked.toShortString()
                    + " reason=" + miner.failureReason());
        }
        // Treat every recovery tick as useful bounded work. The dedicated BlockMiner timeout still
        // rejects an unbreakable obstruction; the ordinary no-progress detour must not interrupt a
        // live escape attempt first.
        lastProgressTick = totalBudget();
        return true;
    }

    private void initializeSafeLandingHistory(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (!isDryStandable(world, feet)) {
            return;
        }
        latestSafeLanding = feet.immutable();
        if (pendingLandingOrigin != null && feet.equals(pendingLandingTarget)) {
            previousSafeLanding = pendingLandingOrigin.immutable();
        }
    }

    private void rememberSafeLanding(ServerLevel world, BlockPos feet) {
        if (!isDryStandable(world, feet)) {
            return;
        }
        BlockPos immutable = feet.immutable();
        if (latestSafeLanding == null) {
            latestSafeLanding = immutable;
        } else if (!latestSafeLanding.equals(immutable)) {
            previousSafeLanding = latestSafeLanding;
            latestSafeLanding = immutable;
        }
    }

    private BlockPos findRecentPhysicalRetreat(ServerLevel world, BlockPos feet) {
        BlockPos detourOrigin = lastDetourOriginFor(feet);
        for (BlockPos candidate : new BlockPos[]{
                detourOrigin, pendingLandingOrigin, previousSafeLanding, latestSafeLanding}) {
            if (candidate == null || candidate.equals(feet)
                    || !isPhysicalRetreatStep(feet, candidate)
                    || failedStepEdges.contains(new DetourEdge(feet, candidate))) {
                continue;
            }
            Standability.clearCache();
            if (isDryStandable(world, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private void rejectCollapsedEdge(BlockPos retreat, BlockPos buried) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            if (buried.equals(retreat.relative(HORIZONTAL[i]).below())) {
                stairDirIndex = i;
                rejectLandingDirection(retreat, i);
                return;
            }
        }
        clearRejectedLandingDirections();
    }

    /**
     * A falling block can bury a stair landing after the normal retreat has become impossible.
     * The mining fallback must not treat the just-collapsed direction as a fresh safe stair as
     * soon as the visible obstruction is cleared: preserve the factual old edge for the audit and
     * reject that direction from the current landing before ordinary stair planning resumes.
     */
    private void rejectCollapsedStairForMiningFallback(AIPlayerEntity bot, BlockPos feet) {
        if (pendingLandingOrigin == null || pendingLandingTarget == null
                || !feet.equals(pendingLandingTarget)) {
            return;
        }
        BlockPos origin = pendingLandingOrigin;
        int direction = pendingLandingDirection;
        rejectCollapsedEdge(origin, feet);
        pendingLandingOrigin = null;
        pendingLandingTarget = null;
        pendingLandingDirection = -1;
        // The old origin's rejection prevents a future factual return from replaying the buried
        // edge. The current rejection prevents an immediate re-dig down the same unstable line.
        rejectLandingDirection(feet, direction);
        BotLog.danger(bot, "descend_collapsed_stair_rejected",
                "from", origin.toShortString(), "at", feet.toShortString(),
                "direction", direction >= 0 && direction < HORIZONTAL.length
                        ? HORIZONTAL[direction].getSerializedName() : "unknown");
    }

    /**
     * A lateral edge is provisional until its target survives the following world update. Sand or
     * gravel can reoccupy that target after the task tick that issued the physical step. When the
     * bot can retreat along the exact last edge, roll that uncommitted traversal back instead of
     * permanently spending graph budget on a landing that never became stable.
     */
    private boolean rollbackCollapsedDetourEdge(AIPlayerEntity bot,
                                                BlockPos retreat,
                                                BlockPos buried) {
        if (traversedDetourEdges.isEmpty()) {
            return false;
        }
        DetourEdge edge = lastEdge(traversedDetourEdges);
        if (!edge.origin().equals(retreat) || !edge.target().equals(buried)) {
            return false;
        }
        if (!traversedDetourEdges.remove(edge)) {
            throw new IllegalStateException("last detour edge disappeared during rollback");
        }
        lateralDetours = traversedDetourEdges.size();
        detourHeadingIndex = traversedDetourEdges.isEmpty()
                ? -1 : directionIndex(lastEdge(traversedDetourEdges));
        BotLog.action(bot, "descend_detour_edge_rolled_back",
                "from", buried.toShortString(),
                "to", retreat.toShortString(),
                "used", lateralDetours,
                "budget", MAX_LATERAL);
        return true;
    }

    private BlockPos lastDetourOriginFor(BlockPos target) {
        if (traversedDetourEdges.isEmpty()) {
            return null;
        }
        DetourEdge edge = lastEdge(traversedDetourEdges);
        return edge.target().equals(target) ? edge.origin().immutable() : null;
    }

    private static boolean isPhysicalRetreatStep(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = to.getY() - from.getY();
        int dz = Math.abs(to.getZ() - from.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        return dx <= 1 && dz <= 1 && dy >= -1 && dy <= 1
                && changedAxes >= 1 && changedAxes <= 2
                && (dy == 0 || dx + dz <= 1);
    }

    private static boolean isAdjacentSameLevel(BlockPos origin, BlockPos target) {
        return origin.getY() == target.getY()
                && Math.abs(origin.getX() - target.getX())
                + Math.abs(origin.getZ() - target.getZ()) == 1;
    }

    private static boolean isDryStandable(ServerLevel world, BlockPos feet) {
        Standability.clearCache();
        return world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.above()).isEmpty()
                && Standability.isStandable(world, feet);
    }

    private static BlockPos firstBodyCollision(ServerLevel world, BlockPos feet) {
        BlockPos head = feet.above();
        if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
            return head.immutable();
        }
        if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
            return feet.immutable();
        }
        return null;
    }

    private boolean handleRejectedLanding(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        if (pendingLandingOrigin == null || pendingLandingTarget == null) {
            return false;
        }
        if (feet.equals(pendingLandingOrigin)) {
            BlockPos rejected = pendingLandingTarget;
            int rejectedDirection = pendingLandingDirection;
            pendingLandingOrigin = null;
            pendingLandingTarget = null;
            pendingLandingDirection = -1;
            miner.cancel(bot);
            rejectLandingDirection(feet, rejectedDirection);
            boolean rotated = rotateStair(bot, world, feet);
            lastProgressTick = totalBudget();
            BotLog.action(bot, "descend_wet_landing_rejected",
                    "from", feet.toShortString(),
                    "rejected", rejected.toShortString(),
                    "rotated", rotated);
            if (!rotated) {
                boolean detouring = lateralDetours < MAX_LATERAL
                        && tryLateralDetour(bot, world, feet);
                if (!detouring) {
                    fail("descend_no_safe_landing at_y=" + rejected.getY());
                }
            }
            // Yield once so the safety controller observes the dry origin and clears ownership.
            return true;
        }
        if (!feet.equals(pendingLandingOrigin) && !feet.equals(pendingLandingTarget)) {
            BlockPos origin = pendingLandingOrigin;
            BlockPos target = pendingLandingTarget;
            pendingLandingOrigin = null;
            pendingLandingTarget = null;
            pendingLandingDirection = -1;
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            if (landingDriftRecoveries < MAX_LANDING_DRIFT_RECOVERIES) {
                landingDriftRecoveries++;
                lastProgressTick = totalBudget();
                BotLog.danger(bot, "descend_landing_drift_recovered",
                        "origin", origin.toShortString(),
                        "target", target.toShortString(),
                        "at", feet.toShortString(),
                        "used", landingDriftRecoveries,
                        "budget", MAX_LANDING_DRIFT_RECOVERIES);
                // Yield one tick; the stair loop re-plans from the factual drifted pose.
                return true;
            }
            fail("descend_landing_pose_drift origin=" + origin.toShortString()
                    + " target=" + target.toShortString() + " at=" + feet.toShortString());
            return true;
        }
        if (feet.equals(pendingLandingTarget)) {
            if (!settlePendingLandingAtCurrentPose(bot)) {
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                return true;
            }
        }
        return false;
    }

    /**
     * Commits an unresolved physical landing only when the bot is still standing dry at the
     * recorded target. This is shared by the normal next-tick acknowledgement and by safety-task
     * interruption, whose shelter/combat work may legitimately relocate the bot before Descend
     * resumes.
     */
    private boolean settlePendingLandingAtCurrentPose(AIPlayerEntity bot) {
        if (pendingLandingTarget == null || !bot.blockPosition().equals(pendingLandingTarget)) {
            return false;
        }
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        Standability.clearCache();
        boolean wet = bot.isUnderWater()
                || bot.isInWater()
                || world.getFluidState(feet).is(FluidTags.WATER)
                || world.getFluidState(feet.above()).is(FluidTags.WATER);
        if (NavSafetyNet.INSTANCE.isWaterRescueActive(bot) || wet
                || !Standability.isStandable(world, feet)) {
            return false;
        }
        // MAX_LATERAL is the detour budget for a single obstruction layer, not a global budget for
        // the whole descent. Only landing below the lowest layer touched by this round of detours
        // counts as genuinely having gotten past the obstruction. Retreating up one level and then
        // landing back on that same lowest layer is not progress: if the budget were cleared here,
        // A->B->A->upper->A would regain a full budget every few ticks and loop forever. Likewise, a
        // wet landing that gets rejected back to the original position must not get a free budget
        // refund.
        boolean advancedBelowDetourFloor = traversedDetourEdges.isEmpty()
                || feet.getY() < lowestTraversedDetourY();
        if (advancedBelowDetourFloor) {
            lateralDetours = 0;
            detourHeadingIndex = pendingLandingDirection;
            traversedDetourEdges.clear();
        } else {
            BotLog.action(bot, "descend_detour_floor_revisited",
                    "at", feet.toShortString(),
                    "floor_y", lowestTraversedDetourY(),
                    "used", lateralDetours,
                    "budget", MAX_LATERAL);
        }
        pendingLandingOrigin = null;
        pendingLandingTarget = null;
        pendingLandingDirection = -1;
        clearRejectedLandingDirections();
        rememberSafeLanding(world, feet);
        lastProgressTick = totalBudget();
        return true;
    }

    private int lowestTraversedDetourY() {
        int lowest = Integer.MAX_VALUE;
        for (DetourEdge edge : traversedDetourEdges) {
            lowest = Math.min(lowest, Math.min(edge.origin().getY(), edge.target().getY()));
        }
        return lowest;
    }

    private void markStarted(AIPlayerEntity bot, BlockPos feet) {
        lastProgressTick = totalBudget();
        if (!started) {
            started = true;
            BotLog.action(bot, "descend_started", "target_y", targetY, "from_y", feet.getY());
        }
    }

    /**
     * {@link AbstractTask#fail(String)} only changes task state; it does not invoke
     * {@link #onAbort(AIPlayerEntity)}. Terminal bounds are intentionally checked before
     * {@link #holdForStep(AIPlayerEntity, ServerLevel)}, so release an active task-owned walked
     * step and every input it wrote before recording failure. Otherwise a removed task can leave
     * its forward/jump keys held through the next owner.
     */
    private void failAfterStoppingOwnedWork(AIPlayerEntity bot, String reason) {
        miner.cancel(bot);
        blockedBodyRecoveryTarget = null;
        bot.getActionPack().stopAll();
        abandonStep(bot);
        fail(reason);
    }

    /**
     * Hands a walked step to the pack. The bot is moved by its keys only: the landing is verified on a later tick (see
     * {@link #settleStep}) and the pending landing, the detour edges and the checkpoint change only then, never in the tick that
     * starts the step.
     */
    private boolean launchStep(AIPlayerEntity bot, WalkedStep walked, StepPurpose purpose,
                               BlockPos origin, BlockPos target, int dirIndex, String reason) {
        ActionPack.StepLease lease = bot.getActionPack().runStep(walked);
        if (lease == null) {
            // Another guarded owner remains responsible for the pack. Keep all local step state
            // unset; callers either hold this tick or re-evaluate the same physical route later.
            return false;
        }
        stepLease = lease;
        step = walked;
        stepPurpose = purpose;
        stepOrigin = origin.immutable();
        stepTarget = target.immutable();
        stepDirIndex = dirIndex;
        stepReason = reason;
        if (purpose == StepPurpose.STAIR) {
            markStarted(bot, origin);
        }
        return true;
    }

    private void clearStepFields() {
        stepPurpose = null;
        stepLease = null;
        step = null;
        stepOrigin = null;
        stepTarget = null;
        stepDirIndex = -1;
        stepBlocked = null;
        stepBlockedName = null;
        stepReason = null;
    }

    /**
     * Forgets a step (or a sneak-bridge) the pack no longer runs: a pause, an abort, a hazard or a restart cancelled it and its keys are
     * released. The bot may be between two cells, so nothing is decided from its pose until it stands on something again; the stair is
     * then re-derived from where it is (the bot is never moved to fit a recorded landing).
     */
    private void abandonStep(AIPlayerEntity bot) {
        if (stepPurpose == null && edge == null) {
            return;
        }
        // Only a step of this task is cancelled. Ordinary steps retain their exact lease; the
        // InCell helpers expose their own returned step object rather than ActionPack's lease.
        if (edge != null) {
            if (edge.step != null && !edge.step.ended()) {
                bot.getActionPack().cancelStep();
            }
        } else if (bot.getActionPack().stepInFlightFor(stepLease)) {
            bot.getActionPack().cancelStep();
        }
        clearStepFields();
        edge = null;
        poseUnsettled = true;
        unsettledTicks = 0;
    }

    /** Settles an already-ended ordinary step before an interruption forgets its task-local fields. */
    private void settleCompletedStepBeforeAbandon(AIPlayerEntity bot) {
        if (edge != null || stepPurpose == null) {
            return;
        }
        ActionPack pack = bot.getActionPack();
        ActionPack.StepLease lease = stepLease;
        if (pack.stepInFlightFor(lease)) {
            return;
        }
        if (!pack.stepIdle()) {
            // A successor owns the pack. Its state says nothing about this descent edge.
            clearStepFields();
            return;
        }
        settleStep(bot, bot.level(), pack.stepResultFor(lease));
    }

    /** True while the bot must be left alone: a step is in flight (or was just settled), or it is still falling after one was lost. */
    private boolean holdForStep(AIPlayerEntity bot, ServerLevel world) {
        if (edge != null || stepPurpose != null) {
            if (stepPurpose != StepPurpose.RETREAT && firstBodyCollision(world, bot.blockPosition()) != null) {
                // A block fell into the body cell under the step: the blocked-body recovery decides, not the step.
                abandonStep(bot);
                return false;
            }
            if (edge != null) {
                tickEdgePlacement(bot, world);
            } else {
                ActionPack pack = bot.getActionPack();
                ActionPack.StepLease lease = stepLease;
                if (pack.stepInFlightFor(lease)) {
                    return true;
                }
                if (!pack.stepIdle()) {
                    // A successor owns ActionPack. Drop only local state and let it complete.
                    clearStepFields();
                    return true;
                }
                settleStep(bot, world, pack.stepResultFor(lease));
            }
            return true;
        }
        if (poseUnsettled) {
            if (WalkedStep.supported(bot) || bot.isInWater()
                    || isUnsettledHazard(bot, world)
                    || ++unsettledTicks > UNSETTLED_LIMIT) {
                poseUnsettled = false;
            } else {
                return true;
            }
        }
        return tryLeanRecovery(bot, world);
    }

    /** Hazards own recovery; an old lost-step hold must not delay their first task tick. */
    private static boolean isUnsettledHazard(AIPlayerEntity bot, ServerLevel world) {
        BlockPos feet = bot.blockPosition();
        return bot.isInLava()
                || bot.onClimbable()
                || bot.isInPowderSnow
                || world.getBlockState(feet).is(Blocks.COBWEB)
                || world.getBlockState(feet.above()).is(Blocks.COBWEB);
    }

    /**
     * A step ended: the landing it verified is the only thing that changes the stair history; a failed step is re-derived from the
     * pose (its edge is never tried again by this task).
     */
    private void settleStep(AIPlayerEntity bot, ServerLevel world, WalkedStep.Result result) {
        StepPurpose purpose = stepPurpose;
        BlockPos origin = stepOrigin;
        BlockPos target = stepTarget;
        int dirIndex = stepDirIndex;
        String reason = stepReason;
        BlockPos blocked = stepBlocked;
        String blockedName = stepBlockedName;
        clearStepFields();
        BlockPos feet = bot.blockPosition();
        boolean landed = result != null && result.succeeded() && feet.equals(target);
        if (!landed) {
            poseUnsettled = true;
            unsettledTicks = 0;
            BotLog.action(bot, "descend_step_failed", "purpose", purpose, "reason", reason,
                    "from", origin.toShortString(), "at", feet.toShortString(),
                    "why", result == null ? "cancelled" : result.failed() ? result.reason() : "not_at_target");
            if (result == null) {
                return; // cancelled from outside (a safety task took the pack): the same step may be tried again
            }
            if (purpose == StepPurpose.STAIR) {
                if (feet.equals(origin)) {
                    rejectLandingDirection(origin, dirIndex);
                    rotateStair(bot, world, origin);
                    BotLog.action(bot, "descend_landing_rejected",
                            "from", origin.toShortString(), "target", target.toShortString());
                }
            } else {
                failedStepEdges.add(new DetourEdge(origin, target));
            }
            return;
        }
        lastProgressTick = totalBudget();
        switch (purpose) {
            case STAIR -> {
                pendingLandingOrigin = origin;
                pendingLandingTarget = target;
                pendingLandingDirection = dirIndex;
            }
            case LATERAL -> {
                traversedDetourEdges.add(new DetourEdge(origin, target));
                markStarted(bot, origin);
                lateralDetours++;
                detourHeadingIndex = dirIndex;
                boolean up = target.getY() > origin.getY();
                if (up) {
                    // The upper landing is a bounded retreat, not a fresh descent origin. Keep the exact reverse stair rejected across
                    // the next tick and checkpoint restart; otherwise Descend immediately drops into the lower cell it just escaped
                    // and clears the detour history when that landing settles.
                    rejectLandingDirection(target, (dirIndex + HORIZONTAL.length / 2) % HORIZONTAL.length);
                }
                if ("descend_lava_detour".equals(reason)) {
                    BotLog.action(bot, "descend_lava_detour",
                            "dir", HORIZONTAL[dirIndex].getSerializedName(),
                            "at_y", target.getY(),
                            "up", up ? 1 : 0,
                            "used", lateralDetours,
                            "budget", MAX_LATERAL);
                }
            }
            case RETREAT -> {
                markStarted(bot, origin);
                miner.cancel(bot);
                boolean rolledBackDetour = rollbackCollapsedDetourEdge(bot, target, origin);
                blockedBodyRecoveryTarget = rolledBackDetour && blocked != null ? blocked : null;
                if (!rolledBackDetour) {
                    rejectCollapsedEdge(target, origin);
                }
                pendingLandingOrigin = null;
                pendingLandingTarget = null;
                pendingLandingDirection = -1;
                latestSafeLanding = target;
                previousSafeLanding = null;
                BotLog.danger(bot, "descend_blocked_body_retreat",
                        "from", origin.toShortString(),
                        "to", target.toShortString(),
                        "blocked", blocked == null ? "unknown" : blocked.toShortString(),
                        "block", blockedName == null ? "unknown" : blockedName,
                        "detour_rolled_back", rolledBackDetour);
            }
            case ENTRY_RELOCATION -> {
                stairDirIndex = dirIndex;
                clearRejectedLandingDirections();
                markStarted(bot, origin);
                BotLog.action(bot, "descend_entry_relocated",
                        "from", origin.toShortString(),
                        "to", target.toShortString(),
                        "stair_direction", HORIZONTAL[dirIndex].getSerializedName());
            }
            case LEAN_RECOVERY -> BotLog.action(bot, "descend_lean_recovered",
                    "from", origin.toShortString(), "to", target.toShortString());
        }
    }

    /**
     * A bot that stands (supported) over the edge of its support with its own cell empty underneath (the sneak-bridge lean was cut
     * short by a pause or a restart) walks back onto the neighbouring cell whose floor holds it. Never moves the bot itself.
     */
    private boolean tryLeanRecovery(AIPlayerEntity bot, ServerLevel world) {
        BlockPos feet = bot.blockPosition();
        if (bot.isInWater() || !WalkedStep.supported(bot) || Math.abs(bot.getY() - feet.getY()) > 1.0E-3D) {
            return false;
        }
        BlockPos floor = feet.below();
        if (!world.getBlockState(floor).getCollisionShape(world, floor).isEmpty()
                || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        for (Direction direction : HORIZONTAL) {
            BlockPos neighbour = feet.relative(direction);
            BlockPos neighbourFloor = neighbour.below();
            if (world.getBlockState(neighbourFloor).getCollisionShape(world, neighbourFloor).isEmpty()
                    || !isDryStandable(world, neighbour)
                    || failedStepEdges.contains(new DetourEdge(feet, neighbour))
                    || WalkedStep.refusal(bot, neighbour, WalkedStep.Kind.FLAT) != null) {
                continue;
            }
            launchStep(bot, WalkedStep.begin(bot, neighbour, WalkedStep.Kind.FLAT, "descend_lean_recovery"),
                    StepPurpose.LEAN_RECOVERY, feet, neighbour, -1, "descend_lean_recovery");
            return true;
        }
        return false;
    }

    /**
     * Takes at most one same-level diagonal step before the descent transaction starts.
     *
     * <p>The relocation is intentionally derived from current observable geometry rather than a
     * remembered route or hidden scan. Once it succeeds {@link #started} is latched, so a restart
     * resumes from the factual destination and cannot refund or replay this preflight.</p>
     */
    private boolean tryFreshEntryRelocation(AIPlayerEntity bot,
                                            ServerLevel world,
                                            BlockPos feet) {
        if (started || feet.getY() <= targetY || !isDryStandable(world, feet)
                || hasObservedSafeStairDirection(bot, world, feet)) {
            return false;
        }
        Direction[][] diagonals = {
                {Direction.NORTH, Direction.EAST},
                {Direction.NORTH, Direction.WEST},
                {Direction.SOUTH, Direction.EAST},
                {Direction.SOUTH, Direction.WEST}
        };
        for (Direction[] pair : diagonals) {
            BlockPos firstCorner = feet.relative(pair[0]);
            BlockPos secondCorner = feet.relative(pair[1]);
            BlockPos candidate = firstCorner.relative(pair[1]);
            if (!isObservedDryPassableColumn(bot, world, firstCorner)
                    || !isObservedDryPassableColumn(bot, world, secondCorner)
                    || !isObservedDryStandable(bot, world, candidate)) {
                continue;
            }
            int safeDirection = observedSafeStairDirection(bot, world, candidate);
            if (safeDirection < 0) {
                continue;
            }
            if (failedStepEdges.contains(new DetourEdge(feet, candidate))
                    || WalkedStep.refusal(bot, candidate, WalkedStep.Kind.FLAT) != null) {
                continue;
            }
            // One walked diagonal step; the relocation is recorded (started latched, stair direction) when the landing is verified.
            miner.cancel(bot);
            launchStep(bot, WalkedStep.begin(bot, candidate, WalkedStep.Kind.FLAT, "descend_fresh_entry_relocation"),
                    StepPurpose.ENTRY_RELOCATION, feet, candidate, safeDirection, "descend_fresh_entry_relocation");
            return true;
        }
        return false;
    }

    private boolean hasObservedSafeStairDirection(AIPlayerEntity bot,
                                                  ServerLevel world,
                                                  BlockPos feet) {
        for (int direction = 0; direction < HORIZONTAL.length; direction++) {
            if (!canObserveStairEnvelope(bot, feet, direction)) {
                continue;
            }
            if (isSafeStairDirection(world, feet, direction)) {
                return true;
            }
        }
        return false;
    }

    private int observedSafeStairDirection(AIPlayerEntity bot,
                                           ServerLevel world,
                                           BlockPos feet) {
        for (int direction = 0; direction < HORIZONTAL.length; direction++) {
            if (!canObserveStairEnvelope(bot, feet, direction)) {
                continue;
            }
            if (isSafeStairDirection(world, feet, direction)) {
                return direction;
            }
        }
        return -1;
    }

    private static boolean canObserveStairEnvelope(AIPlayerEntity bot,
                                                   BlockPos feet,
                                                   int direction) {
        BlockPos ahead = feet.relative(HORIZONTAL[direction]);
        BlockPos landing = ahead.below();
        return canObservePosition(bot, ahead)
                && canObservePosition(bot, ahead.above())
                && canObservePosition(bot, landing)
                && canObservePosition(bot, landing.below());
    }

    private boolean isSafeStairDirection(ServerLevel world,
                                         BlockPos feet,
                                         int direction) {
        BlockPos ahead = feet.relative(HORIZONTAL[direction]);
        BlockPos landing = ahead.below();
        return !containsOwnedWaterSeal(world, ahead, ahead.above(), landing)
                && !isLava(world, landing)
                && !isLava(world, landing.below())
                && !isLava(world, ahead)
                && !isWater(world, landing)
                && !isWater(world, landing.below())
                && hasSafeSupport(world, landing);
    }

    private static boolean isObservedDryPassableColumn(AIPlayerEntity bot,
                                                       ServerLevel world,
                                                       BlockPos feet) {
        if (!canObservePosition(bot, feet) || !canObservePosition(bot, feet.above())) {
            return false;
        }
        return world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.above()).isEmpty()
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.above()).getCollisionShape(world, feet.above()).isEmpty();
    }

    private static boolean isObservedDryStandable(AIPlayerEntity bot,
                                                   ServerLevel world,
                                                   BlockPos feet) {
        return isObservedDryPassableColumn(bot, world, feet)
                && canObservePosition(bot, feet.below())
                && isDryStandable(world, feet);
    }

    private static boolean canObservePosition(AIPlayerEntity bot, BlockPos pos) {
        return ObservableWorldQuery.canObserveCell(bot, pos)
                || ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos);
    }

    // When the shaft is blocked by lava (or another obstruction), move laterally one cell to an
    // adjacent column that is "lava-free and diggable" to go around it and keep descending. Mine
    // the block leading to that side column (never mine a block touching lava, to prevent a
    // lava-collapse flood); once it's open, move over via an adjacent physical step/jump. If all
    // four sides are infeasible -> return false, letting the caller judge failure (the evasion
    // layer's "trapped -- evacuate" logic is the backstop).
    private boolean tryLateralDetour(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        // First look for a lava-free side column to detour through on the current level; when the
        // current level is sealed on all four sides by lava (a large lava lake -- observed at
        // at_y=50: the feet and all four side.down cells were lava -> no solution on the current
        // level -> the whole step fails), retreat up one level and detour above the lava lake's
        // surface -- this usually lets the bot climb out past the lake's rim and keep descending.
        int[] directionOrder = detourDirectionOrder();
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos base = feet.above(dy);
            for (int directionIndex : directionOrder) {
                Direction dir = HORIZONTAL[directionIndex];
                BlockPos side = base.relative(dir);
                BlockPos support = side.below();
                DetourEdge edge = new DetourEdge(feet, side);
                if (traversedDetourEdges.contains(edge) || failedStepEdges.contains(edge)) {
                    continue;
                }
                // Strict-survival detours may only inspect the exact body/support envelope the bot
                // can currently see.  In particular, never read a hidden floor merely because a
                // horizontal fallback is otherwise out of graph edges.
                if (!canObservePosition(bot, side)
                        || !canObservePosition(bot, side.above())
                        || !canObservePosition(bot, support)) {
                    continue;
                }
                // A seal in the body column is an obstruction Descend must never mine through.
                // A seal used only as the landing's solid floor is safe to stand on and is not a
                // placement target; rejecting it here would erase the bounded upper escape route.
                if (containsOwnedWaterSeal(world, side, side.above())) {
                    continue;
                }
                if (isLava(world, side) || isLava(world, side.above()) || isLava(world, support)) {
                    continue; // don't move laterally toward lava
                }
                // Verify the factual landing before clearing its body column. A solid side block
                // can hide an unsupported floor below it; mining that block first both discovers
                // the bad landing too late and may destroy the only support for the dry upper
                // landing that Descend must use to retreat around an aquifer.
                if (!hasSafeSupport(world, side)) {
                    // An upper retreat can require a two-block foundation transaction and remains
                    // fail-closed here.  The seed-3000 aquifer failure is the narrower same-level
                    // isolated-pillar case: place one real, visible floor block, then yield.  On
                    // the next tick (or after restart) the ordinary hasSafeSupport branch above is
                    // the receipt; no checkpoint-local authority can refund or duplicate the item.
                    if (dy == 0 && tryPlaceDetourSupport(
                            bot, world, feet, side, support, directionIndex)) {
                        return true;
                    }
                    continue;
                }
                BlockPos solid = TerrainProbe.firstSolid(world, side, side.above());
                if (solid != null) {
                    // Only a genuinely visible neighbouring lava source (through an already open
                    // gap elsewhere) may reject this block; unmined rock beyond it stays UNKNOWN.
                    if (hasObservedAdjacentLava(bot, world, solid)) {
                        continue; // the block to mine is adjacent to already-visible lava; mining it would cause a lava collapse -- switch direction
                    }
                    if (!ToolTier.canHarvestWithInventory(bot, world.getBlockState(solid))) {
                        miner.cancel(bot);
                        bot.getActionPack().stopAll();
                        fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(
                                world.getBlockState(solid).getBlock()));
                        return true;
                    }
                    if (miner.target() == null || !miner.target().equals(solid)) {
                        miner.begin(bot, solid);
                    }
                    miner.tick(bot);
                    markStarted(bot, feet);
                    return true; // currently mining the path to the side column (counts as progress this tick)
                }
                // The side column is now clear (foot and head cells both empty) -> move over via adjacent physical movement (possibly retreating up one level), then continue descending in the new column on the next tick.
                WalkedStep.Kind kind = dy == 0 ? WalkedStep.Kind.FLAT : WalkedStep.Kind.STEP_UP;
                if (WalkedStep.refusal(bot, side, kind) != null) {
                    continue;
                }
                // One walked step (a walk, or a hop up one level); the edge, the budget and the rejection of the exact reverse stair after
                // an upper retreat are recorded when the landing is verified (settleStep).
                miner.cancel(bot);
                launchStep(bot, WalkedStep.begin(bot, side, kind, "descend_lava_detour"),
                        StepPurpose.LATERAL, feet, side, directionIndex, "descend_lava_detour");
                return true;
            }
        }
        return false;
    }

    /**
     * Builds one same-level floor edge using an ordinary strict-survival block interaction.
     * Successful placement always yields; movement is forbidden until a later tick observes the
     * resulting collision shape through {@link #hasSafeSupport(ServerLevel, BlockPos)}.
     */
    private boolean tryPlaceDetourSupport(AIPlayerEntity bot,
                                          ServerLevel world,
                                          BlockPos origin,
                                          BlockPos landing,
                                          BlockPos support,
                                          int directionIndex) {
        if (!origin.equals(bot.blockPosition())
                || landing.getY() != origin.getY()
                || !landing.equals(origin.relative(HORIZONTAL[directionIndex]))
                || containsOwnedWaterSeal(world, landing, landing.above(), support)) {
            return false;
        }

        BlockState feetState = world.getBlockState(landing);
        BlockState headState = world.getBlockState(landing.above());
        BlockState supportState = world.getBlockState(support);
        if (!feetState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()
                || !supportState.getFluidState().isEmpty()
                || !feetState.getCollisionShape(world, landing).isEmpty()
                || !headState.getCollisionShape(world, landing.above()).isEmpty()
                || !supportState.getCollisionShape(world, support).isEmpty()
                || !supportState.canBeReplaced()
                || Standability.isDangerous(feetState)
                || Standability.isDangerous(headState)
                || Standability.isDangerous(supportState)
                || hasObservedAdjacentLava(bot, world, landing, landing.above(), support)) {
            return false;
        }

        OptionalInt blockSlot = MaterialPalette.pickPathSupportBlockSlot(
                bot, MiningBudget.EMERGENCY_STONE_LIKE);
        if (blockSlot.isEmpty()) {
            return false;
        }
        String item = String.valueOf(bot.getInventory().getNonEquipmentItems()
                .get(blockSlot.getAsInt()).getItem());
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            return false;
        }
        // The pillar's top face hides the target floor from its center. Reproduce an ordinary
        // sneak-bridge transaction: expose the support's side, click that exact visible face, and
        // always settle back on the original center before any receipt or movement decision.
        Standability.clearCache();
        if (!bot.onGround() && Standability.isStandable(world, origin)) {
            bot.setOnGround(true);
        }
        Direction direction = HORIZONTAL[directionIndex];
        // The lean is a walked step (sneak, forward toward a point over the edge of the support); the placement happens when it has
        // ended, and the walk back to the middle of the cell after it (tickEdgePlacement). The next task ticks are held until then.
        WalkedStep lean = InCellWalk.beginEdgeShift(bot, origin, direction, "descend_detour_support");
        if (lean == null) {
            if (bot.getActionPack().stepAdmissionBlocked()) {
                // Keep the observed detour candidate unpoisoned while its guarded owner finishes.
                return true;
            }
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", origin.toShortString(),
                    "landing", landing.toShortString(),
                    "support", support.toShortString(),
                    "reason", "support_edge_unreachable");
            return false;
        }
        edge = new EdgePlacement(origin, landing, support, direction, item, lean);
        return true;
    }

    /**
     * Carries the sneak-bridge on (always holds the tick): lean over the edge, place the floor block against the side face of the
     * support, walk back to the middle of the cell, then check the world receipt. A pause or restart in between cancels the step and
     * the pose is re-derived afterwards ({@link #holdForStep}).
     */
    private void tickEdgePlacement(AIPlayerEntity bot, ServerLevel world) {
        EdgePlacement current = edge;
        if (!current.step.ended()) {
            return;
        }
        ActionPack pack = bot.getActionPack();
        if (!pack.stepIdle()) {
            // InCellWalk owns the exact edge-step object but cannot expose the ActionPack lease.
            // A foreign successor may have cancelled that object; clear only this stale edge and
            // yield rather than stopping the successor's inputs or publishing its result.
            edge = null;
            return;
        }
        WalkedStep.Result result = current.step.outcome();
        DetourEdge detour = new DetourEdge(current.origin, current.landing);
        if (current.stage == EdgeStage.SHIFTING) {
            if (result == null || !result.succeeded()) {
                edge = null;
                bot.getActionPack().stopMovement();
                poseUnsettled = true;
                unsettledTicks = 0;
                if (result != null && !"not_supported".equals(result.reason())) {
                    failedStepEdges.add(detour);
                    BotLog.action(bot, "descend_detour_support_failed",
                            "origin", current.origin.toShortString(),
                            "landing", current.landing.toShortString(),
                            "support", current.support.toShortString(),
                            "reason", "support_edge_unreachable");
                }
                return;
            }
            ActionResult placed = BuildAction.placeBlock(
                    bot, current.origin.below(), current.direction, InteractionHand.MAIN_HAND);
            if (placed.isInProgress()) {
                // Keep the exact edge transaction and descent watchdog alive while a reactive
                // shield owns use; no edge state advances until the real placement succeeds.
                lastProgressTick = totalBudget();
                return;
            }
            current.placeFailure = placed.isFailed() ? placed.reason() : null;
            // The placement is already an interaction receipt. A guarded successor can temporarily
            // deny the walk back, so retain a pending state instead of assigning null to the edge
            // step or repeating the placement on every retry.
            current.stage = EdgeStage.RETURN_PENDING;
        }
        if (current.stage == EdgeStage.RETURN_PENDING) {
            WalkedStep returning = InCellWalk.beginEdgeReturn(bot, current.origin, "descend_detour_support");
            if (returning == null) {
                return;
            }
            current.step = returning;
            current.stage = EdgeStage.RETURNING;
            return;
        }
        edge = null;
        bot.getActionPack().stopMovement();
        if (result == null) {
            // Cancelled (a pause, a restart, another controller took the pack): the lean is walked back by holdForStep.
            poseUnsettled = true;
            unsettledTicks = 0;
            return;
        }
        if (!result.succeeded()) {
            fail("descend_detour_support_return_failed origin=" + current.origin.toShortString());
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", current.origin.toShortString(),
                    "landing", current.landing.toShortString(),
                    "support", current.support.toShortString(),
                    "reason", "support_edge_return_failed");
            return;
        }
        if (current.placeFailure != null) {
            failedStepEdges.add(detour);
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", current.origin.toShortString(),
                    "landing", current.landing.toShortString(),
                    "support", current.support.toShortString(),
                    "reason", current.placeFailure);
            return;
        }

        Standability.clearCache();
        BlockState receipt = world.getBlockState(current.support);
        if (!receipt.getFluidState().isEmpty()
                || receipt.getCollisionShape(world, current.support).isEmpty()
                || Standability.isDangerous(receipt)) {
            fail("descend_no_safe_landing support_receipt_invalid="
                    + current.support.toShortString());
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", current.origin.toShortString(),
                    "landing", current.landing.toShortString(),
                    "support", current.support.toShortString(),
                    "reason", "invalid_world_receipt");
            return;
        }

        markStarted(bot, current.origin);
        lastProgressTick = totalBudget();
        BotLog.action(bot, "descend_detour_support_placed",
                "origin", current.origin.toShortString(),
                "landing", current.landing.toShortString(),
                "support", current.support.toShortString(),
                "item", current.item,
                "stone_like_reserve", MiningBudget.EMERGENCY_STONE_LIKE);
    }

    /** Reads only visible neighbours; hidden cells never become implicit lava-scan authority. */
    private static boolean hasObservedAdjacentLava(AIPlayerEntity bot,
                                                    ServerLevel world,
                                                    BlockPos... positions) {
        for (BlockPos position : positions) {
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = position.relative(direction);
                if (canObservePosition(bot, adjacent) && isLava(world, adjacent)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Explores the edge of an obstruction along the current heading: straight ahead, turn right,
     * turn left, and only try reversing course last. A fixed NORTH/EAST/SOUTH/WEST enum order would
     * place the "south" reversal path ahead of the "west" new exit in a two-cell-wide passage,
     * causing the bot to shuttle back and forth north-south until the budget is exhausted.
     */
    private int[] detourDirectionOrder() {
        int forward = detourHeadingIndex >= 0 ? detourHeadingIndex : stairDirIndex;
        return new int[]{
                forward,
                (forward + 1) % HORIZONTAL.length,
                (forward + HORIZONTAL.length - 1) % HORIZONTAL.length,
                (forward + 2) % HORIZONTAL.length
        };
    }

    private static boolean isLava(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    private static boolean isWater(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    /** Seals one observable water/lava ingress cell and yields until the next tick. */
    private boolean sealLateralWater(AIPlayerEntity bot, ServerLevel world) {
        BlockPos feet = bot.blockPosition();
        for (BlockPos level : new BlockPos[]{feet, feet.above()}) {
            for (Direction direction : HORIZONTAL) {
                if (trySealWater(bot, world, level.relative(direction))) {
                    return true;
                }
            }
        }
        return trySealWater(bot, world, feet.above(2));
    }

    private boolean trySealWater(AIPlayerEntity bot, ServerLevel world, BlockPos pos) {
        boolean lava = isLava(world, pos);
        if (!lava && !isWater(world, pos)) {
            return false;
        }
        if (ownedWaterSeals.size() >= MAX_CHECKPOINTED_WATER_SEALS) {
            pruneStaleWaterSeals(world);
            if (ownedWaterSeals.size() >= MAX_CHECKPOINTED_WATER_SEALS) {
                miner.cancel(bot);
                bot.getActionPack().stopAll();
                fail("descend_water_seal_checkpoint_capacity");
                return true;
            }
        }
        OptionalInt blockSlot = MaterialPalette.pickSacrificialBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
        ActionResult sealed = BuildAction.placeBlockAt(bot, pos);
        if (sealed.isInProgress()) {
            lastProgressTick = totalBudget();
            return true;
        }
        if (sealed.isFailed()) {
            return false;
        }
        BlockState sealState = world.getBlockState(pos);
        if (sealState.isAir() || !sealState.getFluidState().isEmpty()) {
            return false;
        }
        ownedWaterSeals.put(pos.immutable(), sealState.getBlock());
        miner.cancel(bot);
        markStarted(bot, bot.blockPosition());
        rejectSealedStairDirection(bot.blockPosition(), pos);
        lastProgressTick = totalBudget();
        String fluidName = lava ? "lava" : "water";
        BotLog.action(bot, "descend_seal_water", "fluid", fluidName, "at", pos.toShortString());
        BrainCoordinator.INSTANCE.sendBotReply(bot,
                "Sealed off exposed " + fluidName + " while digging down -- routing around it.");
        return true;
    }

    /**
     * Walls off an unexpectedly opened void beneath the next stair tread: {@code next}'s own
     * support turned out, once observable, to be neither fluid (handled by trySealWater) nor solid
     * ground but genuine open space -- a pre-existing cavity or cave the tread just broke into.
     * Never called on a still-hidden support (isViableDescentDirection would not have rejected the
     * direction for one). Returns true only after an actual seal, so the caller can yield the tick
     * and let the already-issued rejectLandingDirection route around it next tick.
     */
    private boolean trySealOpenCavityLanding(AIPlayerEntity bot, ServerLevel world,
                                              BlockPos feet, BlockPos next, int directionIndex) {
        BlockPos hole = next.below();
        if (!canObservePosition(bot, hole)) {
            return false;
        }
        BlockState holeState = world.getBlockState(hole);
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
            lastProgressTick = totalBudget();
            return true;
        }
        if (sealed.isFailed()) {
            return false;
        }
        BlockState sealState = world.getBlockState(hole);
        if (sealState.isAir() || !sealState.getFluidState().isEmpty()) {
            return false;
        }
        miner.cancel(bot);
        rejectLandingDirection(feet, directionIndex);
        lastProgressTick = totalBudget();
        BotLog.action(bot, "descend_seal_open_cavity", "at", hole.toShortString());
        BrainCoordinator.INSTANCE.sendBotReply(bot,
                "Sealed off an open cavity found while digging down -- routing around it.");
        return true;
    }

    /**
     * A fresh mining-exploration staircase has found the open space it was meant to expose.
     * Stop at the current confirmed-safe tread instead of sealing and bypassing that cave; the
     * owning mine/gather task immediately resumes its ordinary observable survey from this rim.
     * Generic durable descents retain the conservative sealing behaviour above.
     */
    private boolean completeMiningExplorationAtObservedOpenCavity(AIPlayerEntity bot,
                                                                   ServerLevel world,
                                                                   BlockPos next) {
        if (!miningExplorationChild) {
            return false;
        }
        BlockPos hole = next.below();
        if (!canObservePosition(bot, hole)) {
            return false;
        }
        BlockState state = world.getBlockState(hole);
        if (!state.getFluidState().isEmpty() || !state.getCollisionShape(world, hole).isEmpty()) {
            return false;
        }
        // A dry void alone is not an entrance: the task must prove a full, currently visible
        // stand cell (solid floor plus empty feet/head) before the owner may route into it. A
        // vertical shaft with no visible floor stays in the ordinary seal-and-reroute flow.
        if (!isObservedDryStandable(bot, world, hole)) {
            return false;
        }
        miner.cancel(bot);
        committed = true;
        completedAtObservedOpenCavity = true;
        completedOpenCavity = hole.immutable();
        BotLog.action(bot, "mining_exploration_open_cavity",
                "at", hole.toShortString(), "target_y", targetY);
        complete();
        return true;
    }

    /**
     * A re-descent must not terminally rediscover the very cave it just surveyed. Leave that
     * entrance open, reject only this stair edge, and let the established safe rotation/detour
     * logic choose a different downward route. This is intentionally narrower than a global
     * cave blacklist: a later position may expose a different safe entrance to the same cavern.
     */
    private boolean skipPreviouslySurveyedOpenCavity(AIPlayerEntity bot, ServerLevel world,
                                                      BlockPos feet, BlockPos next) {
        if (!miningExplorationChild || excludedOpenCavities.isEmpty()) {
            return false;
        }
        BlockPos hole = next.below();
        if (!excludedOpenCavities.contains(hole) || !canObservePosition(bot, hole)) {
            return false;
        }
        BlockState state = world.getBlockState(hole);
        if (!state.getFluidState().isEmpty() || !state.getCollisionShape(world, hole).isEmpty()) {
            return false;
        }
        miner.cancel(bot);
        rejectLandingDirection(feet, stairDirIndex);
        BotLog.action(bot, "mining_exploration_skip_surveyed_cavity",
                "at", hole.toShortString(), "target_y", targetY);
        if (rotateStair(bot, world, feet)) {
            return true;
        }
        if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
            lastProgressTick = totalBudget();
            return true;
        }
        fail("descend_no_safe_landing at_y=" + next.getY());
        return true;
    }

    private void rejectSealedStairDirection(BlockPos feet, BlockPos sealed) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            BlockPos side = feet.relative(HORIZONTAL[i]);
            if (sealed.equals(side) || sealed.equals(side.above())) {
                rejectLandingDirection(feet, i);
                return;
            }
        }
    }

    // Diagonal stair descent: switch to the next diagonal-descent direction that "doesn't touch
    // water/lava"; if none of the four directions work, return false (a lateral detour is the
    // backstop). The order matches tryLateralDetour's detourDirectionOrder (turn right, turn left,
    // and only try reversing course last): once a rejected direction's deeper support becomes
    // unobservable it is treated as "unknown, so allowed", which can make the reverse cell look
    // "viable" too -- but that is only the origin just left. Walking straight into the two new side
    // directions is genuine exploration; reversing course should be the last option tried, otherwise
    // the bot would oscillate back and forth between two cells until the budget is exhausted.
    private boolean rotateStair(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        int forward = stairDirIndex;
        int[] candidates = {
                (forward + 1) % HORIZONTAL.length,
                (forward + HORIZONTAL.length - 1) % HORIZONTAL.length,
                (forward + 2) % HORIZONTAL.length
        };
        for (int candidate : candidates) {
            if ((rejectedLandingDirections & 1 << candidate) != 0) {
                continue;
            }
            BlockPos ahead = feet.relative(HORIZONTAL[candidate]);
            BlockPos next = ahead.below();
            if (!containsOwnedWaterSeal(world, ahead, ahead.above(), next)
                    && isViableDescentDirection(bot, world, feet, candidate)) {
                stairDirIndex = candidate;
                return true;
            }
        }
        return false;
    }

    /**
     * Strict-survival stair viability, mirroring DigDownTask's gated design. {@code ahead}/
     * {@code ahead.up()} touch the bot's body at foot and head height, so any fluid there is
     * exactly as visible as a wall a real player is standing next to — checked unconditionally.
     * {@code next}/{@code support} sit behind that unmined wall and are unknowable until it is
     * actually opened: a hazard there only rejects the direction when it is ALREADY genuinely
     * observable right now (natural open terrain, a previously mined cavity, or a nearby exposed
     * pocket — never a peek through solid rock). Cells still hidden behind unmined rock report
     * UNKNOWN and never block progress; mining ahead legitimately exposes them, and the
     * walked-step landing check (WalkedStep.refusal) below reacts the instant that
     * happens by rejecting the direction and rotating away.
     */
    private boolean isViableDescentDirection(AIPlayerEntity bot,
                                              ServerLevel world,
                                              BlockPos feet,
                                              int directionIndex) {
        BlockPos ahead = feet.relative(HORIZONTAL[directionIndex]);
        BlockPos next = ahead.below();
        BlockPos support = next.below();
        // An edge already recorded as traversed (in either direction) was explicitly explored and
        // backed out of by the lateral detour -- most tellingly when BOTH directions of the same
        // edge are present, a round trip that found nothing useful. The primary stair flow must
        // honor that same history instead of blindly re-selecting it as a "fresh" direction the
        // instant a hidden/allowed deeper cell makes it look newly viable; that would silently
        // undo the whole point of recording it.
        if (traversedDetourEdges.contains(new DetourEdge(feet, ahead))) {
            return false;
        }
        // `ahead`/`ahead.up()` touch the bot's body exactly like the fluid check below -- a
        // magma block, cactus, or other already-dangerous block sitting there is exactly as
        // visible as a wall a real player would recognize and route around rather than dig into,
        // whatever might be hidden behind it. This does not depend on what is behind it because
        // there is no confirmed benefit to ever risk touching it in the first place.
        if ((canObservePosition(bot, ahead) && Standability.isDangerous(world.getBlockState(ahead)))
                || (canObservePosition(bot, ahead.above())
                        && Standability.isDangerous(world.getBlockState(ahead.above())))) {
            return false;
        }
        if (isObservedHazardFluid(bot, ahead) || isObservedHazardFluid(bot, ahead.above())) {
            return false;
        }
        if (isObservedHazardFluid(bot, next) || isObservedHazardFluid(bot, support)) {
            return false;
        }
        return isAcceptableLanding(bot, world, next);
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
     * True when a landing's support is either not yet honestly knowable (still hidden behind
     * unmined rock, so it may not be treated as unsafe) or is genuinely observable and solid.
     */
    private static boolean isAcceptableLanding(AIPlayerEntity bot, ServerLevel world, BlockPos landing) {
        return !canObservePosition(bot, landing.below()) || hasSafeSupport(world, landing);
    }

    private boolean containsOwnedWaterSeal(ServerLevel world, BlockPos... positions) {
        for (BlockPos position : positions) {
            Block owned = ownedWaterSeals.get(position);
            if (owned == null) {
                continue;
            }
            BlockState live = world.getBlockState(position);
            // Air/fluid proves the old wall no longer exists; retaining that stale coordinate
            // would permanently blacklist a valid stair after a restart. A different solid block
            // remains protected: it may be a player repair and is never evidence that Descend owns
            // the right to mine through it.
            if (live.isAir() || !live.getFluidState().isEmpty()) {
                ownedWaterSeals.remove(position);
                continue;
            }
            if (live.is(owned)) {
                return true;
            }
            // A different solid is not task-owned, but it is still a deliberate wall. Keep the
            // coordinate protected so stale identity never becomes authority to mine a repair.
            return true;
        }
        return false;
    }

    private void pruneStaleWaterSeals(ServerLevel world) {
        ownedWaterSeals.entrySet().removeIf(entry -> {
            BlockState live = world.getBlockState(entry.getKey());
            return live.isAir() || !live.getFluidState().isEmpty();
        });
    }

    private void rejectLandingDirection(BlockPos origin, int direction) {
        ensureRejectedLandingOrigin(origin);
        if (direction >= 0 && direction < HORIZONTAL.length) {
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

    private static boolean hasSafeSupport(ServerLevel world, BlockPos landing) {
        BlockPos supportPos = landing.below();
        var support = world.getBlockState(supportPos);
        return support.getFluidState().isEmpty()
                && !support.getCollisionShape(world, supportPos).isEmpty()
                && !Standability.isDangerous(support);
    }

    // P1 descent lighting (standard real-player mining practice): every TORCH_EVERY blocks
    // descended, if light level < 8 and a torch is available, place one at the foot cell. This
    // fixes "the shaft stays light=0 for the whole descent, letting skeletons/zombies spawn in
    // and swarm the bot" (observed: real_diamond descending to Y-58 stayed light=0 throughout and
    // was swarmed by 5 skeletons). Lighting is a nice-to-have, not a prerequisite: lacking torches
    // never blocks the descent.
    private void maybePlaceTorch(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        if (lastTorchY != Integer.MAX_VALUE && lastTorchY - feet.getY() < TORCH_EVERY) {
            return;
        }
        if (world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet) >= 8) {
            lastTorchY = feet.getY(); // already bright enough -- advance the baseline too, to avoid re-checking every tick
            return;
        }
        var torchSlot = InventoryAction.findItem(bot, net.minecraft.world.item.Items.TORCH);
        if (torchSlot.isPresent()) {
            InventoryAction.equipFromSlot(bot, torchSlot.getAsInt());
            ActionResult placed = BuildAction.placeBlockAt(bot, feet);
            if (placed.isSuccess()) {
                lastTorchY = feet.getY();
                markStarted(bot, feet);
                BotLog.action(bot, "descend_torch", "pos", feet.toShortString());
            }
        }
        // Slot identity is not stable: equipping a non-hotbar/offhand torch in a full inventory
        // swaps the selected pick out of its old slot. Restore from the factual active block rather
        // than selecting the stale index; without an active break, the next miner.begin selects.
        restoreActiveMiningTool(bot, world, miner);
    }

    static void restoreActiveMiningTool(AIPlayerEntity bot,
                                        ServerLevel world,
                                        BlockMiner activeMiner) {
        if (activeMiner.target() != null) {
            ToolSelector.equipBestTool(bot, world.getBlockState(activeMiner.target()));
        }
    }

    @Override
    public Map<String, String> checkpoint() {
        // A nested mining-exploration descent deliberately has no durable identity.  Persisting
        // it as the old DESCEND_TO_Y schema would make a later mission restore indistinguishable
        // from a retired legacy task, so let its parent restart from ordinary observed search
        // instead.
        if (miningExplorationChild || invalidCheckpoint
                || ownedWaterSeals.size() > MAX_CHECKPOINTED_WATER_SEALS) {
            return Map.of();
        }
        ArrayList<Map.Entry<BlockPos, Block>> sorted = new ArrayList<>(ownedWaterSeals.entrySet());
        sorted.sort(Map.Entry.comparingByKey(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ)));
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<BlockPos, Block> entry : sorted) {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            BlockPos seal = entry.getKey();
            encoded.append(seal.getX()).append(',')
                    .append(seal.getY()).append(',')
                    .append(seal.getZ()).append(',')
                    .append(BuiltInRegistries.BLOCK.getKey(entry.getValue()));
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_schema", String.valueOf(CHECKPOINT_SCHEMA));
        values.put("task_open", String.valueOf(!committed));
        values.put("target_y", String.valueOf(targetY));
        values.put("budget_used", String.valueOf(totalBudget()));
        values.put("last_progress_budget", String.valueOf(lastProgressTick));
        values.put("budget_limit", String.valueOf(budgetLimit));
        values.put("stair_direction", String.valueOf(stairDirIndex));
        values.put("lateral_detours", String.valueOf(lateralDetours));
        values.put("landing_drift_recoveries", String.valueOf(landingDriftRecoveries));
        values.put("detour_heading", String.valueOf(detourHeadingIndex));
        values.put("pending_landing_origin", encodeOptionalPos(pendingLandingOrigin));
        values.put("pending_landing_target", encodeOptionalPos(pendingLandingTarget));
        values.put("pending_landing_direction", String.valueOf(pendingLandingDirection));
        values.put("rejected_landing_origin", encodeOptionalPos(rejectedLandingOrigin));
        values.put("rejected_landing_directions", String.valueOf(rejectedLandingDirections));
        values.put("last_torch_y", String.valueOf(lastTorchY));
        values.put("owned_water_seals", encoded.toString());
        values.put("traversed_detour_edges", encodeDetourEdges(traversedDetourEdges));
        Map<String, String> result = Map.copyOf(values);
        return inspectCheckpoint(result).isPresent() ? result : Map.of();
    }

    private int totalBudget() {
        return budgetOffset + elapsed;
    }

    /**
     * Three body-column breaks per stair level at conservative Deepslate/stone-pick speed, plus
     * movement and bounded safety-detour headroom. The persisted result is the task's monotonic
     * hard window; restart must never recompute a fresh clock from the remaining distance.
     */
    public static int budgetLimitFor(int fromY, int targetY) {
        return MiningMissionBudget.descendTaskWindowTicks(fromY, targetY);
    }

    private static int migratedLegacyBudgetLimit(int budgetUsed, int fromY, int targetY) {
        long migrated = (long) budgetUsed + budgetLimitFor(fromY, targetY);
        return (int) Math.min(MiningMissionBudget.DESCEND_HARD_WINDOW_TICKS,
                Math.max(MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS, migrated));
    }

    private static String encodeOptionalPos(BlockPos pos) {
        return BlockPosText.encodeOptionalPos(pos);
    }

    private static BlockPos decodeOptionalPos(String value) {
        return BlockPosText.decodeOptionalPos(value);
    }

    private static String encodeDetourEdges(Set<DetourEdge> edges) {
        StringBuilder encoded = new StringBuilder();
        for (DetourEdge edge : edges) {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(edge.origin().getX()).append(',')
                    .append(edge.origin().getY()).append(',')
                    .append(edge.origin().getZ()).append(',')
                    .append(edge.target().getX()).append(',')
                    .append(edge.target().getY()).append(',')
                    .append(edge.target().getZ());
        }
        return encoded.toString();
    }

    private static Set<DetourEdge> decodeDetourEdges(String encoded) {
        if (encoded.isBlank()) {
            return Set.of();
        }
        String[] entries = encoded.split(";", -1);
        if (entries.length > MAX_LATERAL) {
            throw new IllegalArgumentException("too many detour edges");
        }
        Set<DetourEdge> edges = new LinkedHashSet<>();
        for (String entry : entries) {
            String[] coordinates = entry.split(",", -1);
            if (coordinates.length != 6) {
                throw new IllegalArgumentException("invalid detour edge");
            }
            BlockPos origin = new BlockPos(
                    Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]));
            BlockPos target = new BlockPos(
                    Integer.parseInt(coordinates[3]),
                    Integer.parseInt(coordinates[4]),
                    Integer.parseInt(coordinates[5]));
            int horizontalDistance = Math.abs(origin.getX() - target.getX())
                    + Math.abs(origin.getZ() - target.getZ());
            int rise = target.getY() - origin.getY();
            if (horizontalDistance != 1 || rise < 0 || rise > 1
                    || origin.getY() <= MIN_Y || origin.getY() > 320
                    || target.getY() <= MIN_Y || target.getY() > 320
                    || !edges.add(new DetourEdge(origin, target))) {
                throw new IllegalArgumentException("invalid detour edge");
            }
        }
        return Collections.unmodifiableSet(edges);
    }

    private static DetourEdge lastEdge(Set<DetourEdge> edges) {
        DetourEdge last = null;
        for (DetourEdge edge : edges) {
            last = edge;
        }
        if (last == null) {
            throw new IllegalArgumentException("missing detour edge");
        }
        return last;
    }

    private static int directionIndex(DetourEdge edge) {
        int dx = edge.target().getX() - edge.origin().getX();
        int dz = edge.target().getZ() - edge.origin().getZ();
        for (int index = 0; index < HORIZONTAL.length; index++) {
            Direction direction = HORIZONTAL[index];
            if (direction.getStepX() == dx && direction.getStepZ() == dz) {
                return index;
            }
        }
        throw new IllegalArgumentException("invalid detour heading");
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("invalid_boolean");
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }
}
