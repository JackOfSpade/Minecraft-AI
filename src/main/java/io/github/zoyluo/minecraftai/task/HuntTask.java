package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalPlanner;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * HUNT (tier-2 food self-sufficiency): actively hunts nearby edible animals and picks up raw
 * meat until the target amount of meat is reached.
 *
 * Background: CombatCore/CombatTask originally only fought **hostile mobs** (Monster); a
 * hungry bot had no ability to "actively go get meat" (EatTask only eats existing food and gives
 * up if there's no meat). This task fills that gap: find the nearest cow/pig/sheep/chicken/rabbit
 * -> approach -> kill -> pick up meat -> repeat until the quota is met.
 *
 * Reuses shared primitives: approach/attack goes through {@link CombatCore}, drops use
 * {@link HarvestCore} force-pickup (consistent with mining collection).
 * Self-contained state machine (G1, does not self-assign), runs entirely on the main thread (G2).
 * Ends once the quota is reached or no prey remains nearby, handing control back to the
 * orchestration layer (e.g. to continue on to cooking).
 */
public final class HuntTask extends AbstractTask implements CheckpointableTask {
    private enum Phase { RETURN_SURFACE, ACQUIRE, APPROACH, STRIKE, PICKUP, ROAM }
    public enum TransactionState { OPEN, CLOSED_COLLECTED, CLOSED_NO_RAW }
    private enum RoamResult { STARTED, RETRY, EXHAUSTED }
    enum SurfacePathStart { STARTED, RETRY, UNREACHABLE }
    enum SurfaceRouteProof { SAFE, RETRY, UNREACHABLE }

    private record AttackPoseSelection(
            BlockPos pose, BlockPos preyCell, SurfaceRouteProof proof) {
        private static AttackPoseSelection safe(BlockPos pose, BlockPos preyCell) {
            return new AttackPoseSelection(
                    pose.immutable(), preyCell.immutable(), SurfaceRouteProof.SAFE);
        }

        private static AttackPoseSelection failed(SurfaceRouteProof proof) {
            return new AttackPoseSelection(null, null, proof);
        }
    }

    private static final int SEARCH_RANGE = 64;        // Scan range for finding prey (animals are spread out -> expanded to 64 blocks, then walk over)
    // Prey sight range is aligned with the scan range: a real player within render distance
    // with line of sight can see an animal, far beyond the block-read interaction radius.
    // canObserveEntityWithin still keeps the raycast check (terrain can still occlude a herd);
    // only the distance cap is relaxed.
    private static final int PREY_SIGHT_RANGE = SEARCH_RANGE;
    private static final int MAX_ELAPSED = 3600;       // 3-minute hard timeout
    private static final int NO_PROGRESS_LIMIT = 400;  // Fails after 20s with no progress (no closer approach / no meat dropped)
    private static final int PICKUP_RECOVERY_LIMIT = 240; // Hard cap for physical recovery of a visible drop / in-progress pickup
    private static final int APPROACH_STUCK_TICKS = 30;
    private static final int MAX_PREY_ROAMS = 10;      // Max number of roam-to-a-new-tile attempts when no prey is found (search more tiles when the target amount is large)
    private static final int ROAM_DISTANCE = 32;       // Horizontal distance covered by each roam
    private static final int MAX_SURFACE_DESCENT = 16;
    private static final int SURFACE_RETURN_LIMIT = 400;
    private static final double MIN_ROAM_ADVANCE_SQUARED = 64.0D; // Must actually travel at least 8 blocks for a tile to count as explored
    private static final int WET_PREY_REJECTION_TICKS = 300; // After getting back on dry land, don't re-chase for 15s the same animal that just led the bot into water
    private static final int BLIND_PICKUP_SWEEP_DELAY = 20; // Even if no by-product was picked up, still run a bounded search, to cover single-drop prey like pigs
    private static final double PICKUP_DROP_ORIGIN_RADIUS_SQUARED = 16.0D;

    // Edible prey and their raw meat drops (get the raw meat first, before cooking).
    private static final Set<EntityType<?>> PREY = Set.of(
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT);
    private static final Set<Item> RAW_MEATS = Set.of(
            Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);
    private static final Set<Item> PREY_AUXILIARY_DROPS = Set.of(
            Items.LEATHER, Items.FEATHER, Items.RABBIT_HIDE, Items.RABBIT_FOOT,
            Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
            Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
            Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
            Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL);
    // Walk a small observed ring around the factual kill cell. A low ItemEntity at the player's
    // feet can fail LivingEntity.hasLineOfSight even though a sibling drop was physically collected there.
    private static final int[][] PICKUP_SWEEP_OFFSETS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2}
    };
    private static final int[][] ATTACK_POSE_OFFSETS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2},
            {2, 1}, {1, 2}, {-1, 2}, {-2, 1},
            {-2, -1}, {-1, -2}, {1, -2}, {2, -1}
    };
    private static final double ATTACK_POSE_RANGE =
            CombatCore.ATTACK_RANGE - 0.25D;

    private final int targetMeat;
    private final boolean requireFullQuota;
    private final HuntSearchCursor searchCursor;
    private final int maxElapsed; // Hard timeout scales with the target amount (about 24s extra per piece of meat), so hunting a large amount of meat isn't cut off by a fixed 3-minute limit
    private final RestoreMetadata restoredPickup;
    private final boolean invalidCheckpoint;
    private final boolean settlementOnly;
    private int meatBaseline;
    private int collected;
    private int lastProgressTick;
    private double bestApproachDistance = Double.MAX_VALUE;
    private int pickupGrace;
    private EntityType<?> targetPreyType;
    private Item targetExpectedRawMeat;
    private int targetKillStatBaseline;
    private int targetExpectedMeatBaseline;
    private int targetExpectedMeatPickupBaseline;
    private final Map<UUID, Integer> targetFreshRawDropUnits = new HashMap<>();
    private Item pickupExpectedRawMeat;
    private int pickupInventoryBaseline;
    private int pickupRawMeatStatBaseline;
    private long pickupStartedWorldTime;
    private final Map<UUID, Integer> pickupDropUnits = new HashMap<>();
    private String pickupDimension = "";
    private TransactionState pickupTransactionState;
    private boolean checkpointDirty;
    private int targetAuxiliaryBaseline;
    private long targetAuxiliaryPickupBaseline;
    private int pickupSweepCursor;
    private BlockPos pickupOrigin;
    private BlockPos pickupReturnAnchor;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private BlockPos attackPose;
    private BlockPos attackPreyCell;
    private BlockPos approachStuckPos; // Approach-stuck detection: the last recorded stand position
    private int approachStuckTick;     // The tick at which that stand position was recorded
    private int roamCount;             // Number of roam-to-new-tile attempts while searching for prey
    private BlockPos roamTarget;       // Roam landing point
    private BlockPos roamOrigin;       // The actual starting point of this roam; the budget is only settled against real displacement
    private int roamOrdinal;           // The ordinal that should be credited once this roam succeeds
    private boolean roamCredited;
    private int roamStartTick;         // The tick this roam started (gives pathfinding a startup grace period, to avoid an instant "arrived before it even left" false positive)
    private int nextRoamRetryTick;     // Backs off when every candidate path is temporarily rejected; avoids misreporting NO_START as prey exhaustion
    private int surfaceReturnStartTick;
    private final Map<UUID, Integer> wetPreyRejectedUntil = new HashMap<>();
    private final Map<UUID, Integer> unsafePreyRejectedUntil = new HashMap<>();

    public HuntTask(int targetMeat) {
        this(targetMeat, false);
    }

    public HuntTask(int targetMeat, boolean requireFullQuota) {
        this(targetMeat, requireFullQuota, HuntSearchCursor.initial());
    }

    public HuntTask(int targetMeat, boolean requireFullQuota, HuntSearchCursor searchCursor) {
        this(targetMeat, requireFullQuota, searchCursor, Map.of());
    }

    public HuntTask(int targetMeat, boolean requireFullQuota,
                    HuntSearchCursor searchCursor, Map<String, String> checkpoint) {
        this.targetMeat = Math.max(1, targetMeat);
        this.requireFullQuota = requireFullQuota;
        this.searchCursor = java.util.Objects.requireNonNull(searchCursor, "searchCursor");
        this.maxElapsed = Math.max(MAX_ELAPSED, this.targetMeat * 480);
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Optional<RestoreMetadata> restored = inspectCheckpoint(values);
        this.invalidCheckpoint = HuntPickupCheckpoint.checkpointStructurallyInvalid(
                values, restored);
        this.restoredPickup = invalidCheckpoint ? null : restored.orElse(null);
        this.settlementOnly = HuntPickupCheckpoint.settlementRestore(restoredPickup == null
                ? null : restoredPickup.transactionState());
    }

    @Override
    public String name() {
        return "hunt";
    }

    @Override
    public String describe() {
        return "Hunting meat " + collected + "/" + targetMeat + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetMeat);
    }

    @Override
    public boolean isWaiting() {
        // During the chase/pickup phase the bot may briefly stand still or get blocked by terrain;
        // this task has its own triple safety net (NO_PROGRESS / roam / timeout), so it should not be
        // handed to StuckWatcher's crude "abort if position hasn't changed in 200t" monitor, which
        // would kill it by mistake (observed: chasing a sheep against a wall got a false 200t abort).
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("hunt_pickup_invalid_checkpoint");
            checkpointDirty = true;
            return;
        }
        if (settlementOnly) {
            restorePickup(bot);
            return;
        }
        // A closed receipt carries no recoverable debt: the replan already re-derived this
        // step's quota from live inventory, so fall through and hunt fresh instead of
        // failing at tick 0 on a transaction that was already settled.
        CombatCore.equipMelee(bot);
        meatBaseline = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        targetPreyType = null;
        targetExpectedRawMeat = null;
        targetKillStatBaseline = 0;
        targetExpectedMeatBaseline = 0;
        targetExpectedMeatPickupBaseline = 0;
        pickupExpectedRawMeat = null;
        pickupInventoryBaseline = meatBaseline;
        pickupRawMeatStatBaseline = 0;
        pickupStartedWorldTime = 0L;
        pickupDropUnits.clear();
        pickupDimension = "";
        pickupTransactionState = null;
        checkpointDirty = false;
        targetAuxiliaryBaseline = HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS);
        targetAuxiliaryPickupBaseline = pickedUpAuxiliary(bot);
        pickupSweepCursor = 0;
        pickupOrigin = null;
        pickupReturnAnchor = null;
        roamCount = 0;
        clearRoamIntent();
        nextRoamRetryTick = 0;
        wetPreyRejectedUntil.clear();
        unsafePreyRejectedUntil.clear();
        clearTargetIntent();
        phase = Phase.ACQUIRE;
        initializeSurfaceAnchor(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // Meat-collected count: force-pickup drops at the bot's feet + absolute delta against a
        // fixed baseline (meat from a kill that lands in the inventory shortly after is also counted).
        HarvestCore.forcePickupNearbyAnyOf(bot, RAW_MEATS, 2.5D, 2.5D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, RAW_MEATS) - meatBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            roamCount = 0; // Hit meat = there's game in this area, reset the roam budget (otherwise, when hunting a large amount of meat, MAX_PREY_ROAMS would accumulate and run out early, finishing before the quota is met)
            BotLog.action(bot, "hunt_collected", "total", collected + "/" + targetMeat);
        }
        // An acknowledged kill owns a physical transaction. Goal quota, ordinary hunt timeout and
        // no-progress checks may not commit past that debt.
        if (phase == Phase.PICKUP) {
            pickup(bot);
            return;
        }
        if (elapsed > maxElapsed) {
            failAtMissionDeadline(bot);
            return;
        }
        // A roam that slips into water must not start and consume ten new paths while the bot is
        // still swimming (seed 3000 burned the whole prey budget in 21 ticks). Hand control to the
        // shared physical shore rescue and resume ACQUIRE only after dry ground is restored.
        if (waitForDryGround(bot)) {
            return;
        }

        if (enforceSurfaceEnvelope(bot)) {
            return;
        }
        if (collected >= targetMeat) {
            // Inventory success cannot erase a physical water/surface-return debt. Publish
            // completion only after the two gates above have restored an ordinary surface pose,
            // then retire every controller before the mission advances to cooking/mining.
            bot.getActionPack().stopAll();
            complete();
            return;
        }

        BlockPos feet = bot.blockPosition();
        String dimension = dimension(bot);
        EpisodeMemory.INSTANCE.recordTrail(bot.getUUID(), "hunt", feet);
        if (!searchCursor.markVisited(dimension, feet.getX(), feet.getZ())
                && searchCursor.isFull()
                && !searchCursor.contains(dimension, feet.getX(), feet.getZ())) {
            fail("hunt_search_capacity_exhausted sectors=" + searchCursor.visitedCount());
            return;
        }

        // Approach progress: a monotonic improvement of >=4 blocks in distance to the current prey,
        // relative to the best distance seen so far, also counts as progress. For a far-away target,
        // the movement from acquisition to kill can easily exceed NO_PROGRESS_LIMIT; crediting only
        // meat drops/roams would wrongly kill off an entire long approach still in progress (observed:
        // a 44-block approach at ~390t was judged "no progress" right as the attack started). Using the
        // best distance so far, rather than a tick-to-tick delta, means darting back and forth in
        // pursuit can't game the counter.
        if (phase == Phase.APPROACH && target != null && target.isAlive()) {
            double distance = bot.distanceTo(target);
            if (bestApproachDistance - distance >= 4.0D) {
                bestApproachDistance = distance;
                lastProgressTick = elapsed;
            }
        }

        // No-progress watchdog: no approach to prey / no meat dropped for a long time -> clean
        // failure, handed back to the orchestration layer (there may be no animals left nearby).
        if (phase != Phase.PICKUP
                && phase != Phase.RETURN_SURFACE
                && elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            fail("hunt_no_progress collected=" + collected);
            return;
        }

        switch (phase) {
            case RETURN_SURFACE -> returnToSurface(bot);
            case ACQUIRE -> acquire(bot);
            case APPROACH -> approach(bot);
            case STRIKE -> strike(bot);
            case PICKUP -> pickup(bot);
            case ROAM -> roamMove(bot);
        }
    }

    private void initializeSurfaceAnchor(AIPlayerEntity bot) {
        String dimension = dimension(bot);
        Optional<HuntSearchCursor.SurfaceAnchor> persisted =
                searchCursor.surfaceAnchor(dimension);
        if (persisted.isEmpty() && searchCursor.surfaceAnchor().isPresent()) {
            fail("hunt_dimension_changed expected="
                    + searchCursor.surfaceAnchor().orElseThrow().dimension()
                    + " actual=" + dimension);
            return;
        }
        if (persisted.isEmpty()) {
            BlockPos feet = bot.blockPosition();
            Standability.clearCache();
            if (!isFactualSurfaceAnchor(bot, feet)) {
                fail("hunt_surface_anchor_unavailable at=" + feet.toShortString());
                return;
            }
            searchCursor.setSurfaceAnchorIfAbsent(
                    dimension, feet.getX(), feet.getY(), feet.getZ());
        }
        if (outsideSurfaceEnvelope(bot)) {
            beginSurfaceReturn(bot);
        }
    }

    private static boolean isFactualSurfaceAnchor(AIPlayerEntity bot, BlockPos feet) {
        return Standability.isStandable(bot.level(), feet)
                && GoalPlanner.canAcquireSurfaceResources(bot);
    }

    private void failAtMissionDeadline(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        if (phase == Phase.PICKUP) {
            if (!pickupDropUnits.isEmpty()) {
                fail("hunt_drop_unrecovered origin="
                        + (pickupOrigin == null ? "unknown" : pickupOrigin.toShortString())
                        + " item=" + pickupExpectedRawMeat
                        + " deadline=max_elapsed");
            } else {
                fail("hunt_pickup_observation_timeout origin="
                        + (pickupOrigin == null ? "unknown" : pickupOrigin.toShortString())
                        + " item=" + pickupExpectedRawMeat);
            }
            return;
        }
        if (phase == Phase.RETURN_SURFACE) {
            HuntSearchCursor.SurfaceAnchor anchor =
                    searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
            fail("hunt_surface_return_timeout anchor="
                    + (anchor == null ? "unknown"
                    : new BlockPos(anchor.x(), anchor.y(), anchor.z()).toShortString()));
            return;
        }
        if (bot.isInWater() || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
            fail("hunt_water_rescue_timeout at=" + bot.blockPosition().toShortString());
            return;
        }
        fail("hunt_timeout collected=" + collected);
    }

    /**
     * A hunt is a surface expedition. Safety tasks may pause it and move the bot, but resuming
     * below the mission's original surface band must first settle the physical return debt.
     */
    private boolean enforceSurfaceEnvelope(AIPlayerEntity bot) {
        if (state != TaskState.RUNNING) {
            return true;
        }
        if (phase == Phase.RETURN_SURFACE) {
            returnToSurface(bot);
            return true;
        }
        if (!outsideSurfaceEnvelope(bot)) {
            return false;
        }
        beginSurfaceReturn(bot);
        return true;
    }

    private boolean outsideSurfaceEnvelope(AIPlayerEntity bot) {
        HuntSearchCursor.SurfaceAnchor anchor =
                searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
        if (anchor == null) {
            return true;
        }
        BlockPos current = bot.blockPosition();
        return current.getY() < anchor.y() - MAX_SURFACE_DESCENT;
    }

    private void beginSurfaceReturn(AIPlayerEntity bot) {
        HuntSearchCursor.SurfaceAnchor anchor =
                searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
        if (anchor == null) {
            fail("hunt_surface_anchor_missing");
            return;
        }
        bot.getActionPack().stopAll();
        clearTargetIntent();
        clearRoamIntent();
        phase = Phase.RETURN_SURFACE;
        surfaceReturnStartTick = elapsed;
        BlockPos destination = new BlockPos(anchor.x(), anchor.y(), anchor.z());
        int returnFloor = Math.min(
                bot.blockPosition().getY(), surfaceFloorY(anchor));
        SurfacePathStart start = HuntSurfaceRoutes.startExactSurfacePath(
                bot, destination, returnFloor, null);
        if (start == SurfacePathStart.UNREACHABLE) {
            fail("hunt_surface_return_unreachable anchor=" + destination.toShortString()
                    + " from=" + bot.blockPosition().toShortString());
            return;
        }
        if (start == SurfacePathStart.STARTED) {
            BotLog.action(bot, "hunt_surface_return_started",
                    "from", bot.blockPosition().toShortString(),
                    "to", destination.toShortString());
        }
    }

    private void returnToSurface(AIPlayerEntity bot) {
        HuntSearchCursor.SurfaceAnchor anchor =
                searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
        if (anchor == null) {
            fail("hunt_surface_anchor_missing");
            return;
        }
        BlockPos destination = new BlockPos(anchor.x(), anchor.y(), anchor.z());
        Standability.clearCache();
        if (bot.blockPosition().distSqr(destination) <= 4.0D
                && Standability.isStandable(bot.level(), bot.blockPosition())) {
            bot.getActionPack().stopAll();
            phase = Phase.ACQUIRE;
            lastProgressTick = elapsed;
            BotLog.action(bot, "hunt_surface_return_completed",
                    "at", bot.blockPosition().toShortString());
            return;
        }
        if (elapsed - surfaceReturnStartTick > SURFACE_RETURN_LIMIT) {
            bot.getActionPack().stopAll();
            fail("hunt_surface_return_timeout anchor=" + destination.toShortString());
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            int returnFloor = Math.min(
                    bot.blockPosition().getY(), surfaceFloorY(anchor));
            SurfacePathStart start = HuntSurfaceRoutes.startExactSurfacePath(
                    bot, destination, returnFloor, null);
            if (start == SurfacePathStart.UNREACHABLE) {
                fail("hunt_surface_return_unreachable anchor=" + destination.toShortString()
                        + " from=" + bot.blockPosition().toShortString());
            } else if (start == SurfacePathStart.STARTED) {
                BotLog.action(bot, "hunt_surface_return_started",
                        "from", bot.blockPosition().toShortString(),
                        "to", destination.toShortString());
            }
        }
    }

    private static String dimension(AIPlayerEntity bot) {
        return bot.level().dimension().identifier().toString();
    }

    private int surfaceFloorY(AIPlayerEntity bot) {
        return searchCursor.surfaceAnchor(dimension(bot))
                .map(HuntTask::surfaceFloorY)
                .orElse(Integer.MIN_VALUE);
    }

    private static int surfaceFloorY(HuntSearchCursor.SurfaceAnchor anchor) {
        return anchor.y() - MAX_SURFACE_DESCENT;
    }

    // Entering the approach phase: reset the stuck baseline/clear-intent state (otherwise the
    // previous target's baseline carries over and the new target gets falsely flagged as stuck
    // on its very first tick), then start pathfinding.
    private void beginApproach(AIPlayerEntity bot) {
        clearRoamIntent();
        phase = Phase.APPROACH;
        // Capture before combat. The target can die and its loot can enter inventory before the
        // following task tick notices !target.isAlive(); a baseline taken in beginPickup would
        // then mistake that real pickup for pre-existing food and wait forever.
        captureTargetTransactionEvidence(bot);
        approachStuckPos = null;
        approachStuckTick = elapsed;
        lastProgressTick = elapsed;
        bestApproachDistance = target == null
                ? Double.MAX_VALUE : bot.distanceTo(target);
        SurfacePathStart start = startSafePreyApproach(bot, target);
        if (start == SurfacePathStart.UNREACHABLE) {
            rejectUnsafePrey(bot, target, "no_round_trip");
            clearTargetIntent();
            phase = Phase.ACQUIRE;
        }
    }

    private SurfacePathStart startSafePreyApproach(
            AIPlayerEntity bot, LivingEntity prey) {
        BlockPos returnAnchor = bot.blockPosition().immutable();
        if (!isSafePreyPose(bot, prey)) {
            return SurfacePathStart.UNREACHABLE;
        }
        AttackPoseSelection selection = selectSafeAttackPose(bot, prey);
        if (selection.proof() == SurfaceRouteProof.RETRY) {
            return SurfacePathStart.RETRY;
        }
        if (selection.proof() != SurfaceRouteProof.SAFE) {
            return SurfacePathStart.UNREACHABLE;
        }
        attackPose = selection.pose();
        attackPreyCell = selection.preyCell();
        if (bot.blockPosition().equals(attackPose)) {
            return SurfacePathStart.STARTED;
        }
        return HuntSurfaceRoutes.startExactSurfacePath(
                bot, attackPose,
                HuntSurfaceRoutes.digBreakthroughFloor(bot.blockPosition(), attackPose, surfaceFloorY(bot)),
                returnAnchor, true);
    }

    private boolean isSafePreyPose(AIPlayerEntity bot, LivingEntity prey) {
        if (prey == null || !prey.isAlive()
                || !ObservableWorldQuery.canObserveEntityWithin(bot, prey, PREY_SIGHT_RANGE)) {
            return false;
        }
        BlockPos feet = prey.blockPosition();
        ServerLevel world = bot.level();
        // Prey-cell validation uses the same range as prey sight: if you can see the animal but not
        // the cell it's standing on, a far-away target would be rejected as no_round_trip on the very
        // first step, contradicting the sight/safety chain.
        if (feet.getY() < surfaceFloorY(bot)
                || !ObservableWorldQuery.canObserveCellWithin(bot, feet, PREY_SIGHT_RANGE)
                || !ObservableWorldQuery.canObserveCellWithin(bot, feet.above(), PREY_SIGHT_RANGE)
                || !ObservableWorldQuery.canObserveBlockWithin(bot, feet.below(), PREY_SIGHT_RANGE)) {
            return false;
        }
        Standability.clearCache();
        return Standability.isStandable(world, feet);
    }

    /**
     * Chooses a factual player stand near the current prey cell instead of pathing into a moving
     * entity's own block. The outbound leg may dig a near-level stair through terrain once
     * walking has no route, while the chosen pose and the factual kill/drop cell must both keep
     * ordinary no-dig/no-pillar return routes, so a prey walking onto a cliff cannot manufacture
     * a one-way pickup debt after the strike.
     */
    private AttackPoseSelection selectSafeAttackPose(
            AIPlayerEntity bot, LivingEntity prey) {
        BlockPos current = bot.blockPosition();
        BlockPos preyCell = prey.blockPosition();
        int floorY = surfaceFloorY(bot);
        List<BlockPos> candidates = new ArrayList<>();
        if (withinAttackPoseRange(current, prey)) {
            candidates.add(current.immutable());
        }
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] offset : ATTACK_POSE_OFFSETS) {
                BlockPos candidate = preyCell.offset(offset[0], dy, offset[1]);
                if (withinAttackPoseRange(candidate, prey)
                        && !candidates.contains(candidate)) {
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
                candidate -> candidate.distSqr(current)));

        boolean retryObserved = false;
        for (BlockPos candidate : candidates) {
            if (candidate.equals(preyCell)
                    || candidatePoseIntersectsPrey(bot, candidate, prey)
                    || !isObservableStandCandidate(bot, candidate, current, floorY)) {
                continue;
            }
            SurfaceRouteProof outbound = HuntSurfaceRoutes.provePreyApproachRoute(
                    bot, bot.level(), current, candidate, floorY, null);
            if (outbound == SurfaceRouteProof.RETRY) {
                retryObserved = true;
                continue;
            }
            if (outbound != SurfaceRouteProof.SAFE) {
                continue;
            }
            SurfaceRouteProof dropRecovery = HuntSurfaceRoutes.proveRoundTripSurfaceRoute(
                    bot.level(), candidate, preyCell, floorY);
            if (dropRecovery == SurfaceRouteProof.RETRY) {
                retryObserved = true;
                continue;
            }
            if (dropRecovery == SurfaceRouteProof.SAFE) {
                return AttackPoseSelection.safe(candidate, preyCell);
            }
        }
        return AttackPoseSelection.failed(retryObserved
                ? SurfaceRouteProof.RETRY : SurfaceRouteProof.UNREACHABLE);
    }

    private static boolean candidatePoseIntersectsPrey(
            AIPlayerEntity bot, BlockPos candidate, LivingEntity prey) {
        AABB candidateBody = bot.getDimensions(bot.getPose()).makeBoundingBox(
                candidate.getX() + 0.5D,
                candidate.getY(),
                candidate.getZ() + 0.5D);
        return candidateBody.intersects(prey.getBoundingBox());
    }

    private static boolean withinAttackPoseRange(
            BlockPos candidate, LivingEntity prey) {
        Vec3 pose = new Vec3(
                candidate.getX() + 0.5D,
                candidate.getY(),
                candidate.getZ() + 0.5D);
        return pose.distanceToSqr(prey.position())
                <= ATTACK_POSE_RANGE * ATTACK_POSE_RANGE;
    }

    private static boolean isObservableStandCandidate(
            AIPlayerEntity bot, BlockPos candidate, BlockPos current, int floorY) {
        if (candidate.getY() < floorY) {
            return false;
        }
        // Pose cells are grounded at prey-sight range like the prey itself; the base radius
        // here rejected every pose of a herd acquired 20-60 blocks out, and the distant-prey
        // GameTest then only passed when a roam happened to close the distance first.
        if (!candidate.equals(current)
                && (!ObservableWorldQuery.canObserveCellWithin(bot, candidate, PREY_SIGHT_RANGE)
                || !ObservableWorldQuery.canObserveCellWithin(bot, candidate.above(), PREY_SIGHT_RANGE)
                || !ObservableWorldQuery.canObserveBlockWithin(bot, candidate.below(), PREY_SIGHT_RANGE))) {
            return false;
        }
        Standability.clearCache();
        return Standability.isStandable(bot.level(), candidate);
    }

    private boolean attackPoseMatchesTarget(AIPlayerEntity bot, LivingEntity prey) {
        return attackPose != null
                && attackPreyCell != null
                && prey != null
                && attackPreyCell.equals(prey.blockPosition())
                && withinAttackPoseRange(attackPose, prey)
                && !candidatePoseIntersectsPrey(bot, attackPose, prey);
    }

    private void clearAttackIntent() {
        attackPose = null;
        attackPreyCell = null;
        approachStuckPos = null;
    }

    private void clearTargetIntent() {
        target = null;
        targetPreyType = null;
        targetExpectedRawMeat = null;
        targetKillStatBaseline = 0;
        targetExpectedMeatBaseline = 0;
        targetExpectedMeatPickupBaseline = 0;
        targetFreshRawDropUnits.clear();
        clearAttackIntent();
    }

    private void captureTargetTransactionEvidence(AIPlayerEntity bot) {
        targetFreshRawDropUnits.clear();
        if (target == null) {
            targetPreyType = null;
            targetExpectedRawMeat = null;
            return;
        }
        targetPreyType = target.getType();
        targetExpectedRawMeat = expectedRawMeat(targetPreyType);
        if (targetExpectedRawMeat == null) {
            return;
        }
        targetKillStatBaseline =
                bot.getStats().getValue(Stats.ENTITY_KILLED, targetPreyType);
        targetExpectedMeatBaseline =
                HarvestCore.countInventoryItems(bot, Set.of(targetExpectedRawMeat));
        targetExpectedMeatPickupBaseline =
                bot.getStats().getValue(Stats.ITEM_PICKED_UP, targetExpectedRawMeat);
        targetAuxiliaryBaseline =
                HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS);
        targetAuxiliaryPickupBaseline = pickedUpAuxiliary(bot);
    }

    private void rejectUnsafePrey(
            AIPlayerEntity bot, LivingEntity prey, String reason) {
        if (prey == null) {
            return;
        }
        int rejectedUntil = elapsed + WET_PREY_REJECTION_TICKS;
        unsafePreyRejectedUntil.put(prey.getUUID(), rejectedUntil);
        EpisodeMemory.INSTANCE.exclude(
                bot.getUUID(), prey.blockPosition(),
                bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        BotLog.action(bot, "hunt_unsafe_prey_rejected",
                "prey", prey.getUUID(),
                "at", prey.blockPosition().toShortString(),
                "reason", reason,
                "until", rejectedUntil);
        clearAttackIntent();
    }

    private void acquire(AIPlayerEntity bot) {
        target = nearestPrey(bot);
        if (target != null) {
            beginApproach(bot);
            return;
        }
        // No prey nearby (64 blocks) -> roam to a new tile first to find more, trying to reach the
        // full target (animals are spread out / far away), rather than "stop as soon as we get some"
        // (observed: hunting 10 pieces of meat, once nearby prey ran out after a few kills it would
        // complete without reaching the quota).
        RoamResult roam = roamForPrey(bot);
        if (roam != RoamResult.EXHAUSTED) {
            return;
        }
        // Still nothing found after exhausting roams: an ordinary foraging pass can settle for
        // whatever was collected; long-term mining readiness must reach the full quota.
        if (collected > 0 && !requireFullQuota) {
            complete();
            return;
        }
        fail((collected > 0 ? "insufficient_prey" : "no_prey_found")
                + " collected=" + collected + "/" + targetMeat + " roams=" + roamCount);
    }

    private boolean waitForDryGround(AIPlayerEntity bot) {
        boolean active = NavSafetyNet.INSTANCE.isWaterRescueActive(bot);
        if (!bot.isInWater() && !active) {
            return false;
        }
        bot.getActionPack().stopAll();
        boolean preservePhysicalDebt =
                phase == Phase.PICKUP || phase == Phase.RETURN_SURFACE;
        if (roamTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), roamTarget,
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        }
        if (target != null && target.isAlive()) {
            int rejectedUntil = elapsed + WET_PREY_REJECTION_TICKS;
            wetPreyRejectedUntil.put(target.getUUID(), rejectedUntil);
            BotLog.action(bot, "hunt_wet_prey_rejected",
                    "prey", target.getUUID(),
                    "until", rejectedUntil,
                    "at", target.blockPosition().toShortString());
        }
        if (!preservePhysicalDebt) {
            clearRoamIntent();
            clearTargetIntent();
            phase = Phase.ACQUIRE;
            lastProgressTick = elapsed;
        }
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        return true;
    }

    // No prey found -> walk to open surface ground ROAM_DISTANCE away to search a new tile; at most
    // MAX_PREY_ROAMS times.
    private RoamResult roamForPrey(AIPlayerEntity bot) {
        if (elapsed < nextRoamRetryTick) {
            return RoamResult.RETRY;
        }
        int nextRoam = roamCount + 1;
        if (nextRoam > MAX_PREY_ROAMS) {
            return RoamResult.EXHAUSTED;
        }
        if (searchCursor.isFull()) {
            fail("hunt_search_capacity_exhausted sectors=" + searchCursor.visitedCount());
            return RoamResult.RETRY;
        }
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        String dimension = dimension(bot);
        long claimedOrdinal;
        try {
            claimedOrdinal = searchCursor.claimNextOrdinal();
        } catch (IllegalStateException exhausted) {
            fail("hunt_search_ordinal_exhausted");
            return RoamResult.RETRY;
        }
        int attemptSerial = (int) Math.floorMod(claimedOrdinal, Integer.MAX_VALUE);
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int start = Math.floorMod(attemptSerial + nextRoam, dirs.length);
        // Distance is adaptive: if pathfinding is rejected in all 8 directions at full distance
        // (mountaintop / cliff / surrounded by water), halve it and retry -- there's usually somewhere
        // walkable nearby, so move there first and expand again next round (same approach as
        // GatherQuotaTask.roamToNewArea; fixes the "reject all 8 and give up immediately" quick death).
        for (int dist = ROAM_DISTANCE; dist >= ROAM_DISTANCE / 4; dist /= 2) {
            for (int i = 0; i < dirs.length; i++) {
                int[] d = dirs[(start + i) % dirs.length];
                BlockPos column = HuntSurfaceRoutes.rotatedRoamColumn(feet, d[0], d[1], dist, attemptSerial);
                BlockPos ground = findGround(world, column.getX(), column.getZ());
                if (ground == null
                        || ground.getY() < surfaceFloorY(bot)
                        || searchCursor.contains(
                        dimension, ground.getX(), ground.getZ())
                        || EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), ground, bot.level().getServer().getTickCount())
                        || EpisodeMemory.INSTANCE.nearTrail(
                                bot.getUUID(), "hunt", ground, 10.0D)) {
                    continue;
                }
                // Surface exploration is a sequence of waypoints, not a one-way cave descent.
                // Before accepting a lower waypoint, prove that a no-dig/no-pillar route can walk
                // back to the current surface. This rejects seed-3000's chained safe drops into a
                // Y=53 pocket whose only reverse path consumed pillar blocks and stranded the bot.
                if (!HuntSurfaceRoutes.hasRoundTripSurfaceRoute(
                        world, feet, ground, surfaceFloorY(bot))) {
                    EpisodeMemory.INSTANCE.exclude(bot.getUUID(), ground,
                            bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
                    BotLog.action(bot, "hunt_roam_one_way_rejected",
                            "from", feet.toShortString(), "to", ground.toShortString());
                    continue;
                }
                bot.getActionPack().stopAll();
                // Pathfinding rejected (target unreachable / not loaded) -> try the next direction.
                // Previously ROAM was entered without checking the result, so the very next tick
                // isPathExecutorIdle would be true -> instantly fall back to ACQUIRE -> roam again...
                // firing 3 roams in the same second and instantly burning the whole roam budget while
                // the bot never moved (observed: hunt spun idle for 642t on barren terrain and failed).
                SurfacePathStart pathStart = HuntSurfaceRoutes.startExactSurfacePath(
                        bot, ground, surfaceFloorY(bot), feet);
                if (pathStart == SurfacePathStart.RETRY) {
                    nextRoamRetryTick = elapsed + 5;
                    phase = Phase.ACQUIRE;
                    return RoamResult.RETRY;
                }
                if (pathStart == SurfacePathStart.UNREACHABLE) {
                    continue;
                }
                roamTarget = ground;
                roamOrigin = feet.immutable();
                roamStartTick = elapsed;
                roamOrdinal = nextRoam;
                roamCredited = false;
                phase = Phase.ROAM;
                BotLog.action(bot, "hunt_roam",
                        "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                        "n", nextRoam, "dist", dist,
                        "ordinal", claimedOrdinal);
                return RoamResult.STARTED;
            }
        }
        // A full rejection must change the next candidate geometry. Merely sleeping and retrying
        // the same 8 directions at the same 3 radii strands the bot forever on seed-3000's ridge:
        // nextRoam stays 1 because no movement was committed, so every replan repeats the exact
        // same 24 cells until hunt_no_progress. Rotate the sampling fan deterministically while
        // preserving the roam credit; successful physical movement still owns roamCount.
        nextRoamRetryTick = elapsed + 20;
        phase = Phase.ACQUIRE;
        BotLog.action(bot, "hunt_roam_retry",
                "n", nextRoam,
                "ordinal", claimedOrdinal,
                "from", feet.toShortString());
        return RoamResult.RETRY;
    }

    // Keep scanning for prey while roaming: switch to hunting as soon as one is found; on reaching
    // the landing point / getting stuck, return to ACQUIRE and rescan.
    private void roamMove(AIPlayerEntity bot) {
        LivingEntity prey = nearestPrey(bot);
        if (prey != null) {
            target = prey;
            // ROAM owns a different route contract and return anchor. Retire it before installing
            // the prey approach so same-goal cooldown cannot leave the old executor in control.
            bot.getActionPack().stopAll();
            beginApproach(bot);
            return;
        }
        double advance = roamOrigin == null
                ? 0.0D : bot.blockPosition().distSqr(roamOrigin);
        if (!roamCredited && advance >= MIN_ROAM_ADVANCE_SQUARED) {
            roamCount = Math.max(roamCount, roamOrdinal);
            roamCredited = true;
            lastProgressTick = elapsed;
            BotLog.action(bot, "hunt_roam_committed",
                    "n", roamCount, "advance", (int) Math.sqrt(advance));
        }
        boolean arrived = roamTarget == null
                || bot.blockPosition().distSqr(roamTarget) <= 9.0D;
        // 20t startup grace period: after startPathTo, the asynchronous A* computation needs a few
        // ticks, during which the executor is still idle; judging "can't move" immediately would
        // cause an instant fallback (making roam pointless). Idle after the grace period means it
        // genuinely can't get there; the 200t cap prevents walking for too long.
        boolean gaveUp = (elapsed - roamStartTick > 20 && bot.getActionPack().isPathExecutorIdle())
                || elapsed - roamStartTick > 200;
        if (arrived || gaveUp) {
            if (!roamCredited || gaveUp) {
                excludeRoamTarget(bot);
            }
            if (gaveUp) {
                bot.getActionPack().stopAll();
            }
            clearRoamIntent();
            phase = Phase.ACQUIRE;
        }
    }

    private void excludeRoamTarget(AIPlayerEntity bot) {
        if (roamTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUUID(), roamTarget,
                    bot.level().getServer().getTickCount(), EpisodeMemory.TTL_UNREACHABLE);
        }
    }

    private void clearRoamIntent() {
        roamTarget = null;
        roamOrigin = null;
        roamOrdinal = 0;
        roamCredited = false;
    }

    // In the (x,z) column, scan top-down for the first open-air standable spot (a surface landing point).
    private static BlockPos findGround(ServerLevel world, int x, int z) {
        // Use the heightmap to read that column's surface directly, so this holds at any altitude.
        // The old hard cap of y=110 meant a bot standing on terrain/hills above y=110 could never
        // find a landing point -> roaming was completely broken -> hunt_stuck_no_escape even with
        // prey nearby (observed: at y=111 with a chicken 13 blocks away, it still failed).
        // Canopy penetration: the old MOTION_BLOCKING top surface lands on the tree canopy in
        // forests (tall spruce can be 20+ blocks, so a fixed descent count is a losing bet), and the
        // ground under the canopy never sees sky -> every sampled point comes back null -> every
        // roam gets rejected, a quick death (observed: spawning in a spruce forest).
        // The fix: the MOTION_BLOCKING_NO_LEAVES heightmap natively skips leaves, so its top surface
        // is terrain/trunk; then descend a few more blocks to reach the ground.
        int surfaceY = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = surfaceY; y >= surfaceY - 24 && y > world.getMinY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (Standability.isStandable(world, p)) {
                return p;
            }
        }
        return null;
    }

    private void approach(AIPlayerEntity bot) {
        if (resolveUnavailableTarget(bot)) {
            return;
        }
        if (!isSafePreyPose(bot, target)) {
            rejectUnsafePrey(bot, target, "pose_left_surface_envelope");
            clearTargetIntent();
            bot.getActionPack().stopAll();
            phase = Phase.ACQUIRE;
            return;
        }
        CombatCore.lookAt(bot, target);
        if (!attackPoseMatchesTarget(bot, target)) {
            bot.getActionPack().stopAll();
            SurfacePathStart retarget = startSafePreyApproach(bot, target);
            if (retarget == SurfacePathStart.UNREACHABLE) {
                rejectUnsafePrey(bot, target, "moving_pose_unreachable");
                clearTargetIntent();
                phase = Phase.ACQUIRE;
            } else if (retarget == SurfacePathStart.STARTED
                    && readyToStrikeFromProvenPose(bot)) {
                bot.getActionPack().stopAll();
                phase = Phase.STRIKE;
            }
            return;
        }
        if (readyToStrikeFromProvenPose(bot)) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        BlockPos at = bot.blockPosition();
        BlockPos activeGoal = bot.getActionPack().activePathGoal();
        boolean staleGoal = activeGoal != null && !activeGoal.equals(attackPose);
        if (staleGoal) {
            bot.getActionPack().stopAll();
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            SurfacePathStart start = startSafePreyApproach(bot, target);
            if (start == SurfacePathStart.UNREACHABLE) {
                rejectUnsafePrey(bot, target, "surface_route_lost");
                clearTargetIntent();
                phase = Phase.ACQUIRE;
                return;
            }
            if (start == SurfacePathStart.RETRY) {
                // NO_START/throttling/search-budget exhaustion is transient. Leave the factual
                // prey pending and let the task-wide no-progress deadline bound retries instead
                // of poisoning the animal as unsafe after 30 fast, stationary ticks.
                return;
            }
            if (readyToStrikeFromProvenPose(bot)) {
                bot.getActionPack().stopAll();
                phase = Phase.STRIKE;
                return;
            }
            // A new exact path has just been accepted. Its executor can legitimately spend a few
            // ticks turning before changing block coordinates, so start the stuck budget here.
            approachStuckPos = at;
            approachStuckTick = elapsed;
            return;
        }
        if (at.equals(approachStuckPos)) {
            if (elapsed - approachStuckTick > APPROACH_STUCK_TICKS) {
                BotLog.action(bot, "hunt_approach_stuck", "pos", at.toShortString(),
                        "dist", (int) bot.distanceTo(target));
                rejectUnsafePrey(bot, target, "surface_path_stuck");
                clearTargetIntent();
                bot.getActionPack().stopAll();
                phase = Phase.ACQUIRE;
            }
            return;
        }
        approachStuckPos = at;
        approachStuckTick = elapsed;
    }

    private boolean readyToStrikeFromProvenPose(AIPlayerEntity bot) {
        return attackPoseMatchesTarget(bot, target)
                && (bot.blockPosition().equals(attackPose)
                // PathExecutor can physically overshoot an adjacent pose into the factual prey
                // cell. selectSafeAttackPose already proved this exact cell reversible for drop
                // recovery, so stop there instead of letting a live controller drift farther.
                || bot.blockPosition().equals(attackPreyCell))
                && CombatCore.inMeleeRange(bot, target)
                && CombatCore.hasLineOfSight(bot, target);
    }

    private void strike(AIPlayerEntity bot) {
        if (resolveUnavailableTarget(bot)) {
            return;
        }
        if (!isSafePreyPose(bot, target)) {
            rejectUnsafePrey(bot, target, "unsafe_strike_pose");
            clearTargetIntent();
            bot.getActionPack().stopAll();
            phase = Phase.ACQUIRE;
            return;
        }
        CombatCore.lookAt(bot, target);
        if (!readyToStrikeFromProvenPose(bot)) {
            // The prey moved out of the already-proven kill/drop envelope. Re-select a factual
            // attack pose instead of swinging from an unverified cliff or following its entity
            // block as a moving exact endpoint.
            beginApproach(bot);
            return;
        }
        CombatCore.equipMelee(bot); // Make sure the best weapon is equipped before striking (observed: hunting with held=dirt, hitting meat with dirt only deals 1 damage and is extremely slow); equipFromSlot is idempotent and won't flicker
        // Refresh at the exact attack boundary. A long approach may cross unrelated meat or kill
        // statistics; only evidence produced after the swing that can own this target may settle
        // the transaction.
        captureTargetTransactionEvidence(bot);
        float healthBeforeSwing = target == null ? Float.MAX_VALUE : target.getHealth();
        boolean struck = CombatCore.strikeIfReady(bot, target);
        // Vanilla death, kill statistics, and loot spawning are synchronous with the fatal attack.
        // Capture the fresh entity identity in this same task tick, but preserve the existing
        // phase transition timing: resolveUnavailableTarget opens PICKUP on the following tick.
        if (struck && target != null
                && (target.getHealth() <= 0.0F
                || target.getRemovalReason()
                == net.minecraft.world.entity.Entity.RemovalReason.KILLED)) {
            captureFreshTargetRawDropIds(bot);
        }
        // Only refresh lastProgressTick when a swing actually deals damage. Real damage means the
        // target's health monotonically decreases and a sustained combo will eventually kill it;
        // whiffing in a loop (observed: 126 hits without a kill) and being out of range both leave it
        // unrefreshed, so NO_PROGRESS_LIMIT cleanly closes it out instead of dragging on to maxElapsed;
        // a healthy combo right after a long approach also no longer gets falsely killed by the 400t
        // window (collected=0 with meat still on the ground).
        if (struck && target != null && target.getHealth() < healthBeforeSwing) {
            lastProgressTick = elapsed;
        }
    }

    /**
     * Distinguishes a factual kill from a stale entity reference.
     *
     * <p>{@link LivingEntity#isAlive()} is also false after chunk unload. Treating every removed
     * reference as a corpse creates a pickup debt for loot that never existed, while the same
     * animal can reappear alive when its chunk loads again. Only zero health or Minecraft's
     * explicit KILLED removal reason may open the atomic loot transaction.</p>
     */
    private boolean resolveUnavailableTarget(AIPlayerEntity bot) {
        if (target != null && (target.getHealth() <= 0.0F
                || target.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.KILLED)) {
            boolean creditedKill = targetPreyType != null
                    && targetExpectedRawMeat != null
                    && bot.getStats().getValue(Stats.ENTITY_KILLED, targetPreyType)
                    > targetKillStatBaseline;
            if (creditedKill) {
                beginPickup(bot);
            } else {
                UUID lostId = target.getUUID();
                BlockPos lostAt = target.blockPosition().immutable();
                bot.getActionPack().stopAll();
                clearTargetIntent();
                lastProgressTick = elapsed;
                phase = Phase.ACQUIRE;
                BotLog.action(bot, "hunt_target_death_uncredited",
                        "prey", lostId,
                        "at", lostAt.toShortString());
            }
            return true;
        }
        if (target != null && target.isAlive()) {
            return false;
        }

        UUID lostId = target == null ? null : target.getUUID();
        Object removal = target == null ? "missing" : target.getRemovalReason();
        bot.getActionPack().stopAll();
        clearTargetIntent();
        lastProgressTick = elapsed;
        phase = Phase.ACQUIRE;
        BotLog.action(bot, "hunt_target_reacquire", "prey", lostId, "removal", removal);
        return true;
    }

    private void beginPickup(AIPlayerEntity bot) {
        Map<UUID, Integer> creditedDropUnits = Map.copyOf(targetFreshRawDropUnits);
        pickupOrigin = target == null ? bot.blockPosition().immutable()
                : target.blockPosition().immutable();
        pickupReturnAnchor = bot.blockPosition().immutable();
        pickupExpectedRawMeat = targetExpectedRawMeat;
        pickupInventoryBaseline = targetExpectedMeatBaseline;
        pickupRawMeatStatBaseline = targetExpectedMeatPickupBaseline;
        pickupStartedWorldTime = bot.level().getGameTime();
        pickupDimension = dimension(bot);
        pickupDropUnits.clear();
        pickupDropUnits.putAll(creditedDropUnits);
        pickupTransactionState = TransactionState.OPEN;
        // Bind a factual drop identity at the kill site before yielding the first PICKUP tick.
        // Once bound, that same entity may drift, fall, or be moved beyond the origin envelope
        // without being mistaken for unrelated old meat. Unbound entities must still satisfy the
        // fresh-time and kill-origin checks in nearestTransactionRawDrop.
        nearestTransactionRawDrop(bot);
        clearTargetIntent();
        bot.getActionPack().stopAll();
        pickupGrace = 0;
        pickupSweepCursor = 0;
        lastProgressTick = elapsed;
        phase = Phase.PICKUP;
        checkpointDirty = true;
    }

    private void captureFreshTargetRawDropIds(AIPlayerEntity bot) {
        if (target == null || targetExpectedRawMeat == null) {
            return;
        }
        BlockPos killOrigin = target.blockPosition().immutable();
        List<ItemEntity> freshDrops = bot.level().getEntitiesOfClass(
                ItemEntity.class,
                bot.getBoundingBox().inflate(16.0D),
                entity -> entity.isAlive()
                        && !entity.getItem().isEmpty()
                        && entity.getItem().is(targetExpectedRawMeat)
                        && entity.getAge() >= 0
                        && entity.getAge() <= 3
                        && entity.position().distanceToSqr(Vec3.atCenterOf(killOrigin))
                        <= PICKUP_DROP_ORIGIN_RADIUS_SQUARED
                        && ObservableWorldQuery.canObserveEntity(bot, entity));
        for (ItemEntity drop : freshDrops) {
            int units = drop.getItem().getCount();
            if (units > 0 && HuntPickupCheckpoint.bindDropUnits(
                    targetFreshRawDropUnits, drop.getUUID(), units)) {
                BotLog.action(bot, "hunt_kill_drop_identity_bound",
                        "drop", drop.getUUID(),
                        "item", targetExpectedRawMeat,
                        "origin", killOrigin.toShortString(),
                        "age", drop.getAge(),
                        "units", units);
            }
        }
    }

    private void pickup(AIPlayerEntity bot) {
        if (!pickupDimension.equals(dimension(bot))) {
            checkpointDirty = true;
            fail("hunt_pickup_dimension_mismatch:expected=" + pickupDimension
                    + ":actual=" + dimension(bot));
            return;
        }
        long age = pickupAge(bot);
        if (age < 0L) {
            checkpointDirty = true;
            fail("hunt_pickup_time_rollback:started=" + pickupStartedWorldTime
                    + ":current=" + bot.level().getGameTime());
            return;
        }
        pickupGrace = (int) Math.min(Integer.MAX_VALUE, age);
        Optional<ItemEntity> nearestDrop = nearestTransactionRawDrop(bot, age);
        if (state == TaskState.FAILED) {
            return;
        }
        boolean observedDrop = nearestDrop.isPresent();
        boolean pickupMovementActive = !bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle();
        if (nearestDrop.isPresent() && !pickupMovementActive) {
            ItemEntity drop = nearestDrop.orElseThrow();
            // A delayed ItemEntity remains a factual unresolved debt, but cannot make physical
            // pickup progress yet. Let remembered-cell/auxiliary sweep recover sibling loot
            // instead of allowing this entity to monopolize the controller indefinitely.
            if (!drop.hasPickUpDelay()) {
                BlockPos stand = safeObservedDropStand(bot, drop);
                if (stand != null) {
                    pickupMovementActive = approachPickupStand(
                            bot, stand, drop.position());
                } else if (pickupGrace == 3 || pickupGrace % 40 == 0) {
                    BotLog.action(bot, "hunt_drop_one_way_rejected",
                            "drop", drop.blockPosition().toShortString(),
                            "anchor", pickupReturnAnchor == null
                                    ? "unknown" : pickupReturnAnchor.toShortString());
                }
            }
        }
        int currentMeat = pickupExpectedRawMeat == null ? 0
                : HarvestCore.countInventoryItems(bot, Set.of(pickupExpectedRawMeat));
        int currentPickupStat = pickupExpectedRawMeat == null ? 0
                : bot.getStats().getValue(Stats.ITEM_PICKED_UP, pickupExpectedRawMeat);
        int requiredUnits = Math.max(1,
                HuntPickupCheckpoint.boundDropUnitCount(pickupDropUnits));
        boolean collectionConfirmed = pickupExpectedRawMeat != null
                && HuntPickupCheckpoint.collectionCoversBoundUnits(
                pickupInventoryBaseline, currentMeat,
                pickupRawMeatStatBaseline, currentPickupStat, requiredUnits);
        boolean auxiliaryPickupObserved = HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS)
                > targetAuxiliaryBaseline
                || pickedUpAuxiliary(bot) > targetAuxiliaryPickupBaseline;
        if (!collectionConfirmed && !pickupMovementActive && pickupGrace >= 3) {
            // The kill coordinate is factual. Return there first, but do not camp forever when a
            // low sibling ItemEntity is hidden from hasLineOfSight at the player's feet. Picking leather,
            // wool, feather or hide is observable proof that this kill's loot transaction began;
            // walk an observed, dry ring so the missed meat becomes visible/collidable. The fixed
            // delay covers prey without auxiliary drops (notably pigs) without hidden scans.
            if ((auxiliaryPickupObserved || pickupGrace >= BLIND_PICKUP_SWEEP_DELAY)
                    && startNextPickupSweepStep(bot)) {
                pickupMovementActive = true;
            } else if (pickupOrigin != null && safePickupCellRoute(bot, pickupOrigin)) {
                pickupMovementActive = approachKnownPickupCell(bot, pickupOrigin);
            }
        }
        // PICKUP is an atomic inventory transaction, not a one-tick visibility hint. A dead
        // animal's ItemEntity may become visible only after this task's tick; leaving early lets a
        // roam path overwrite the only physical pickup route. Hold the debt until inventory proves
        // success, and after that until every observed drop/controller has settled.
        if (pickupGrace < PICKUP_RECOVERY_LIMIT
                && (!collectionConfirmed || observedDrop || pickupMovementActive)) {
            return;
        }
        boolean unresolvedObservedRawDebt =
                !pickupDropUnits.isEmpty() && (!collectionConfirmed || observedDrop);
        if (unresolvedObservedRawDebt) {
            bot.getActionPack().stopAll();
            checkpointDirty = true;
            fail("hunt_drop_unrecovered origin="
                    + (pickupOrigin == null ? "unknown" : pickupOrigin.toShortString())
                    + " item=" + pickupExpectedRawMeat
                    + " baseline=" + pickupInventoryBaseline + " current=" + currentMeat);
            return;
        }
        if (!collectionConfirmed) {
            // A credited vanilla kill can legally yield no raw item (rabbit loot variance), and a
            // Fire Aspect weapon converts the expected raw item to cooked food. If no fresh raw
            // ItemEntity from this kill was ever observed, the bounded observation/sweep window is
            // absence evidence, not an unrecoverable physical debt.
            BotLog.action(bot, "hunt_kill_without_raw_drop",
                    "origin", pickupOrigin == null ? "unknown" : pickupOrigin.toShortString(),
                    "item", pickupExpectedRawMeat,
                    "waited", pickupGrace);
            finishPickupTransaction(bot, currentMeat, TransactionState.CLOSED_NO_RAW);
            return;
        }
        finishPickupTransaction(bot, currentMeat, TransactionState.CLOSED_COLLECTED);
    }

    private void finishPickupTransaction(
            AIPlayerEntity bot, int currentMeat, TransactionState closedState) {
        bot.getActionPack().stopAll();
        pickupTransactionState = closedState;
        checkpointDirty = true;
        lastProgressTick = elapsed;
        if (settlementOnly) {
            complete();
            return;
        }
        phase = Phase.ACQUIRE; // Once pickup is done, go find the next one (if the quota is met, onTick's top-of-tick check will complete)
    }

    private Optional<ItemEntity> nearestTransactionRawDrop(AIPlayerEntity bot) {
        long age = pickupAge(bot);
        return age < 0L ? Optional.empty() : nearestTransactionRawDrop(bot, age);
    }

    private Optional<ItemEntity> nearestTransactionRawDrop(AIPlayerEntity bot, long transactionAge) {
        if (pickupExpectedRawMeat == null || pickupOrigin == null) {
            return Optional.empty();
        }
        List<ItemEntity> observed = bot.level().getEntitiesOfClass(
                ItemEntity.class,
                bot.getBoundingBox().inflate(16.0D),
                entity -> entity.isAlive()
                        && !entity.getItem().isEmpty()
                        && entity.getItem().is(pickupExpectedRawMeat)
                        && ObservableWorldQuery.canObserveEntity(bot, entity));
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : observed) {
            boolean bound = pickupDropUnits.containsKey(drop.getUUID());
            if (!bound && isFreshTransactionDrop(drop, transactionAge)) {
                int units = drop.getItem().getCount();
                if (units > 0 && HuntPickupCheckpoint.bindDropUnits(
                        pickupDropUnits, drop.getUUID(), units)) {
                    checkpointDirty = true;
                    bound = true;
                    BotLog.action(bot, "hunt_pickup_drop_bound",
                            "drop", drop.getUUID(),
                            "item", pickupExpectedRawMeat,
                            "origin", pickupOrigin.toShortString(),
                            "age", drop.getAge(),
                            "units", units);
                } else if (units > 0) {
                    checkpointDirty = true;
                    fail("hunt_pickup_checkpoint_capacity:entries="
                            + pickupDropUnits.size() + ":units="
                            + HuntPickupCheckpoint.boundDropUnitCount(pickupDropUnits));
                    return Optional.empty();
                }
            }
            if (!bound) {
                continue;
            }
            double distance = drop.distanceToSqr(bot);
            if (distance < nearestDistance) {
                nearest = drop;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private boolean isFreshTransactionDrop(ItemEntity drop, long transactionAge) {
        if (!HuntPickupCheckpoint.canBindFreshDropAtAge(transactionAge)) {
            return false;
        }
        int itemAge = drop.getAge();
        if (itemAge < 0 || itemAge > transactionAge + 3) {
            return false;
        }
        return drop.position().distanceToSqr(Vec3.atCenterOf(pickupOrigin))
                <= PICKUP_DROP_ORIGIN_RADIUS_SQUARED;
    }

    private long pickupAge(AIPlayerEntity bot) {
        return pickupAgeAt(pickupStartedWorldTime, bot.level().getGameTime());
    }

    static long pickupAgeAt(long startedWorldTime, long currentWorldTime) {
        return startedWorldTime < 0L || currentWorldTime < startedWorldTime
                ? -1L : currentWorldTime - startedWorldTime;
    }

    private BlockPos safeObservedDropStand(AIPlayerEntity bot, ItemEntity drop) {
        if (!HarvestCore.isDropPhysicallySupported(bot, drop)) {
            return null;
        }
        Standability.clearCache();
        BlockPos stand = HarvestCore.pickupStandPos(bot, drop.blockPosition());
        if (stand == null || !isObservablePickupStand(bot, stand)) {
            return null;
        }
        return safePickupCellRoute(bot, stand) ? stand : null;
    }

    private static boolean isObservablePickupStand(AIPlayerEntity bot, BlockPos stand) {
        if (stand.equals(bot.blockPosition())) {
            return true;
        }
        return ObservableWorldQuery.canObserveCell(bot, stand)
                && ObservableWorldQuery.canObserveCell(bot, stand.above())
                && ObservableWorldQuery.canObserveBlock(bot, stand.below());
    }

    private boolean safePickupCellRoute(AIPlayerEntity bot, BlockPos destination) {
        if (destination == null || pickupReturnAnchor == null
                || destination.getY() < surfaceFloorY(bot)) {
            return false;
        }
        ServerLevel world = bot.level();
        Standability.clearCache();
        if (!Standability.isStandable(world, destination)) {
            return false;
        }
        BlockPos current = bot.blockPosition();
        if (!HuntSurfaceRoutes.hasExactSurfaceRoute(world, current, destination, surfaceFloorY(bot))) {
            return false;
        }
        return HuntSurfaceRoutes.hasExactSurfaceRoute(
                world, destination, pickupReturnAnchor, surfaceFloorY(bot));
    }

    private boolean approachKnownPickupCell(
            AIPlayerEntity bot, BlockPos itemPos) {
        Standability.clearCache();
        BlockPos stand = HarvestCore.pickupStandPos(bot, itemPos);
        return stand != null && safePickupCellRoute(bot, stand)
                && approachPickupStand(bot, stand, null);
    }

    private boolean approachPickupStand(
            AIPlayerEntity bot, BlockPos stand, Vec3 observedDropPosition) {
        if (pickupReturnAnchor == null || stand == null) {
            return false;
        }
        if (bot.blockPosition().equals(stand)) {
            if (observedDropPosition == null) {
                bot.getActionPack().stopMovement();
            } else {
                io.github.zoyluo.minecraftai.mode.FakePlayerMotion.nudgeWithinBlockToward(
                        bot, stand, observedDropPosition, "physical_drop_pickup");
            }
            return true;
        }
        return HuntSurfaceRoutes.startExactSurfacePath(
                bot, stand, surfaceFloorY(bot), pickupReturnAnchor)
                == SurfacePathStart.STARTED;
    }

    // Only hunts adult vanilla animals known to drop raw meat. The old "any Animal"
    // compatibility would treat bees, frogs, and other non-food creatures as prey, wasting the 200t
    // pickup budget and possibly starting unwanted fights; modded meat sources should be added via
    // explicit config.
    private static boolean isHuntable(LivingEntity entity) {
        return PREY.contains(entity.getType())
                && entity instanceof net.minecraft.world.entity.animal.Animal animal
                && !animal.isBaby();
    }

    private LivingEntity nearestPrey(AIPlayerEntity bot) {
        wetPreyRejectedUntil.entrySet().removeIf(entry -> entry.getValue() <= elapsed);
        unsafePreyRejectedUntil.entrySet().removeIf(entry -> entry.getValue() <= elapsed);
        AABB box = bot.getBoundingBox().inflate(SEARCH_RANGE);
        return bot.level()
                .getEntitiesOfClass(LivingEntity.class, box,
                        entity -> entity.isAlive() && entity != bot && isHuntable(entity))
                .stream()
                .filter(entity -> ObservableWorldQuery.canObserveEntityWithin(
                        bot, entity, PREY_SIGHT_RANGE))
                .filter(entity -> !wetPreyRejectedUntil.containsKey(entity.getUUID()))
                .filter(entity -> !unsafePreyRejectedUntil.containsKey(entity.getUUID()))
                .filter(entity -> !EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), entity.blockPosition(), bot.level().getServer().getTickCount()))
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
    }

    boolean isWetPreyTemporarilyRejected(UUID preyId) {
        return preyId != null && wetPreyRejectedUntil.getOrDefault(preyId, -1) > elapsed;
    }

    private boolean startNextPickupSweepStep(AIPlayerEntity bot) {
        if (pickupOrigin == null) {
            return false;
        }
        ServerLevel world = bot.level();
        for (int checked = 0; checked < PICKUP_SWEEP_OFFSETS.length; checked++) {
            int[] offset = PICKUP_SWEEP_OFFSETS[
                    Math.floorMod(pickupSweepCursor++, PICKUP_SWEEP_OFFSETS.length)];
            BlockPos candidate = pickupOrigin.offset(offset[0], 0, offset[1]);
            Standability.clearCache();
            if (candidate.equals(bot.blockPosition())
                    || !ObservableWorldQuery.canObserveCell(bot, candidate)
                    || !ObservableWorldQuery.canObserveCell(bot, candidate.above())
                    || !ObservableWorldQuery.canObserveBlock(bot, candidate.below())
                    || !Standability.isStandable(world, candidate)
                    || !safePickupCellRoute(bot, candidate)) {
                continue;
            }
            SurfacePathStart start = HuntSurfaceRoutes.startExactSurfacePath(
                    bot, candidate, surfaceFloorY(bot), pickupReturnAnchor);
            if (start != SurfacePathStart.STARTED) {
                continue;
            }
            BotLog.action(bot, "hunt_pickup_observation_sweep",
                    "origin", pickupOrigin.toShortString(),
                    "to", candidate.toShortString(),
                    "step", pickupSweepCursor);
            return true;
        }
        return false;
    }

    private static long pickedUpAuxiliary(AIPlayerEntity bot) {
        long count = 0L;
        for (Item item : PREY_AUXILIARY_DROPS) {
            count += bot.getStats().getValue(Stats.ITEM_PICKED_UP, item);
        }
        return count;
    }

    private static Item expectedRawMeat(EntityType<?> preyType) {
        if (preyType == EntityType.COW) {
            return Items.BEEF;
        }
        if (preyType == EntityType.PIG) {
            return Items.PORKCHOP;
        }
        if (preyType == EntityType.SHEEP) {
            return Items.MUTTON;
        }
        if (preyType == EntityType.CHICKEN) {
            return Items.CHICKEN;
        }
        if (preyType == EntityType.RABBIT) {
            return Items.RABBIT;
        }
        return null;
    }

    private void restorePickup(AIPlayerEntity bot) {
        RestoreMetadata restored = restoredPickup;
        if (restored == null || restored.transactionState() != TransactionState.OPEN) {
            fail("hunt_pickup_invalid_checkpoint");
            checkpointDirty = true;
            return;
        }
        String liveDimension = dimension(bot);
        if (!restored.dimension().equals(liveDimension)) {
            fail("hunt_pickup_dimension_mismatch:expected=" + restored.dimension()
                    + ":actual=" + liveDimension);
            checkpointDirty = true;
            return;
        }
        long now = bot.level().getGameTime();
        if (now < restored.pickupStartedWorldTime()) {
            fail("hunt_pickup_time_rollback:started=" + restored.pickupStartedWorldTime()
                    + ":current=" + now);
            checkpointDirty = true;
            return;
        }
        CombatCore.equipMelee(bot);
        meatBaseline = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = (int) Math.min(Integer.MAX_VALUE,
                now - restored.pickupStartedWorldTime());
        pickupExpectedRawMeat = restored.expectedRawItem();
        pickupInventoryBaseline = restored.inventoryBaseline();
        pickupRawMeatStatBaseline = restored.pickupStatBaseline();
        targetAuxiliaryBaseline = restored.auxInventoryBaseline();
        targetAuxiliaryPickupBaseline = restored.auxPickupStatBaseline();
        pickupStartedWorldTime = restored.pickupStartedWorldTime();
        pickupDropUnits.clear();
        pickupDropUnits.putAll(restored.boundDropUnits());
        pickupDimension = restored.dimension();
        pickupTransactionState = TransactionState.OPEN;
        pickupOrigin = restored.pickupOrigin();
        pickupReturnAnchor = restored.pickupReturnAnchor();
        pickupSweepCursor = 0;
        clearRoamIntent();
        wetPreyRejectedUntil.clear();
        unsafePreyRejectedUntil.clear();
        clearTargetIntent();
        phase = Phase.PICKUP;
        checkpointDirty = false;
        bot.getActionPack().stopAll();
    }

    public boolean consumeCheckpointDirty() {
        boolean dirty = checkpointDirty;
        checkpointDirty = false;
        return dirty;
    }

    public boolean isSettlementOnly() {
        return settlementOnly;
    }

    public TransactionState transactionState() {
        return pickupTransactionState;
    }

    // Structural encode/decode (field set, canonical formatting, bound-drop-unit ledger,
    // dimension/item identifier validation) all live in HuntPickupCheckpoint, the strict,
    // Minecraft-bootstrap-independent codec that GoalExecutor also decodes these checkpoints
    // through. HuntTask only adapts between its own Item-typed fields and the codec's
    // bootstrap-free String/Position representation at the two edges below.
    @Override
    public Map<String, String> checkpoint() {
        if (invalidCheckpoint || pickupTransactionState == null
                || pickupExpectedRawMeat == null || pickupOrigin == null
                || pickupReturnAnchor == null || pickupDimension.isBlank()) {
            return Map.of();
        }
        HuntPickupCheckpoint.Metadata metadata = new HuntPickupCheckpoint.Metadata(
                HuntPickupCheckpoint.State.valueOf(pickupTransactionState.name()),
                targetMeat, requireFullQuota, pickupDimension,
                BuiltInRegistries.ITEM.getKey(pickupExpectedRawMeat).toString(),
                toCheckpointPosition(pickupOrigin), toCheckpointPosition(pickupReturnAnchor),
                pickupInventoryBaseline, pickupRawMeatStatBaseline,
                targetAuxiliaryBaseline, targetAuxiliaryPickupBaseline,
                pickupStartedWorldTime, pickupDropUnits);
        return HuntPickupCheckpoint.encode(metadata);
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(
            Map<String, String> checkpoint) {
        return HuntPickupCheckpoint.inspect(checkpoint).flatMap(HuntTask::toRestoreMetadata);
    }

    // The codec's Metadata carries the raw item id as a String (so it can validate it against a
    // hardcoded raw-meat id set without a live registry); HuntTask does have registry access, so
    // it resolves that id to the actual Item here and rejects anything that doesn't resolve to a
    // known raw meat, matching the pre-delegation registry-backed validation exactly (the codec's
    // RAW_MEAT_IDS string set and this class's RAW_MEATS item set name the same five vanilla
    // items, so this can only reject an already-invalid checkpoint, never a valid one).
    private static Optional<RestoreMetadata> toRestoreMetadata(
            HuntPickupCheckpoint.Metadata metadata) {
        Item expectedRaw = BuiltInRegistries.ITEM.getOptional(
                Identifier.parse(metadata.expectedRawItemId())).orElse(null);
        if (expectedRaw == null || !RAW_MEATS.contains(expectedRaw)) {
            return Optional.empty();
        }
        return Optional.of(new RestoreMetadata(
                TransactionState.valueOf(metadata.transactionState().name()),
                metadata.targetCount(), metadata.requireFullQuota(), metadata.dimension(),
                expectedRaw,
                toBlockPos(metadata.pickupOrigin()), toBlockPos(metadata.pickupReturnAnchor()),
                metadata.inventoryBaseline(), metadata.pickupStatBaseline(),
                metadata.auxInventoryBaseline(), metadata.auxPickupStatBaseline(),
                metadata.pickupStartedWorldTime(), metadata.boundDropUnits()));
    }

    public record RestoreMetadata(
            TransactionState transactionState,
            int targetCount,
            boolean requireFullQuota,
            String dimension,
            Item expectedRawItem,
            BlockPos pickupOrigin,
            BlockPos pickupReturnAnchor,
            int inventoryBaseline,
            int pickupStatBaseline,
            int auxInventoryBaseline,
            long auxPickupStatBaseline,
            long pickupStartedWorldTime,
            Map<UUID, Integer> boundDropUnits) {
        public RestoreMetadata {
            pickupOrigin = pickupOrigin.immutable();
            pickupReturnAnchor = pickupReturnAnchor.immutable();
            boundDropUnits = Map.copyOf(boundDropUnits);
        }

        public int boundUnits() {
            return HuntPickupCheckpoint.boundDropUnitCount(boundDropUnits);
        }

        public boolean open() {
            return transactionState == TransactionState.OPEN;
        }
    }

    private static HuntPickupCheckpoint.Position toCheckpointPosition(BlockPos pos) {
        return new HuntPickupCheckpoint.Position(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos toBlockPos(HuntPickupCheckpoint.Position pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    /** Factual net raw-food inventory used by GoalExecutor's HUNT-specific replan watermark. */
    public static int rawMeatCount(AIPlayerEntity bot) {
        return HarvestCore.countInventoryItems(bot, RAW_MEATS);
    }

    /** Whether huntable animals are nearby -- used by the hunger chain to decide whether it's worth
     * dispatching a hunt task, avoiding a guaranteed failure when dispatched with no animals around. */
    public static boolean hasPreyNearby(AIPlayerEntity bot) {
        return !bot.level()
                .getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(SEARCH_RANGE),
                        entity -> entity.isAlive() && entity != bot && isHuntable(entity))
                .stream()
                .filter(entity -> ObservableWorldQuery.canObserveEntityWithin(
                        bot, entity, PREY_SIGHT_RANGE))
                .toList()
                .isEmpty();
    }
}
