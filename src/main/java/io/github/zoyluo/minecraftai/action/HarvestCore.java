package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.CapabilityTally;
import io.github.zoyluo.minecraftai.mode.CapabilityDecision;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class HarvestCore {
    // NAV-OPT (layer 0B): reachability that's actually reachable -- verify only the nearest N candidates,
    // using a small-budget pure-walk A* run, balancing accuracy against performance.
    private static final int REACH_VERIFY_LIMIT = 8;
    private static final int REACH_MAX_NODES = 3_000;
    private static final long REACH_MAX_MILLIS = 30L;
    /** Vanilla item bounces can leave a drop just above a block edge without setting onGround. */
    private static final double PICKUP_SUPPORT_PROBE_DEPTH = 0.26D;
    /** Maximum observed, collision-free fall column that pickup recovery may wait beneath. */
    private static final int PICKUP_DRY_SHAFT_DEPTH = 6;

    private HarvestCore() {
    }

    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Block targetBlock, int horizontalRadius, int down, int up) {
        CapabilityDecision scanDecision = CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "harvest_nearest_block");
        CapabilityTally.INSTANCE.record(bot.getUUID(), PrivilegedCapability.HIDDEN_BLOCK_SCAN, scanDecision.allowed());
        BlockPos origin = bot.blockPosition();
        return firstWalkReachable(bot, origin,
                BlockPos.betweenClosedStream(origin.offset(-horizontalRadius, -down, -horizontalRadius), origin.offset(horizontalRadius, up, horizontalRadius))
                        .filter(pos -> scanDecision.allowed() || withinObservationReach(bot, pos))
                        .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                        .filter(pos -> bot.level().getBlockState(pos).is(targetBlock))
                        .map(BlockPos::immutable)
                        .map(pos -> targetChoice(bot, pos))
                        .filter(choice -> choice != null));
    }

    // MINE-DIG/Fix C: finds the nearest reachable block within a set of candidate blocks (e.g. "any log"),
    // for GatherQuotaTask to gather across tree species.
    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Set<Block> targetBlocks, int horizontalRadius, int down, int up) {
        return nearestReachableBlock(bot, targetBlocks, horizontalRadius, down, up, null);
    }

    // EXPLORE/unreachable-blacklist: position-filtered variant -- candidates rejected by posFilter are
    // skipped outright (e.g. targets working memory has "repeatedly failed to reach"), null means no
    // filtering. Used by GatherQuotaTask.survey to filter out blacklisted targets so it no longer
    // re-locks onto the same unreachable tree in an infinite loop.
    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Set<Block> targetBlocks, int horizontalRadius, int down, int up,
                                                     Predicate<BlockPos> posFilter) {
        return nearestReachableBlock(bot, targetBlocks, horizontalRadius, down, up,
                posFilter, false);
    }

    /**
     * Finds a reachable target while optionally accepting a visible cell as a fallback. {@code canObserveBlock}
     * is shape-aware (a block with no collision shape is aimed at through its selection outline), so a plainly
     * visible plant normally passes it; the cell fallback ({@code canObserveCell}, an unobstructed view into the
     * cell) only catches the remainder, such as a thin plant whose outline rays are all blocked by a neighbour
     * although the cell itself is in view. It remains line-of-sight bounded and is opt-in for callers that can
     * target such blocks.
     */
    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Set<Block> targetBlocks,
                                                     int horizontalRadius, int down, int up,
                                                     Predicate<BlockPos> posFilter,
                                                     boolean allowObservableCellFallback) {
        NearestScan scan = beginNearestScan(bot, targetBlocks, horizontalRadius, down, up, posFilter, allowObservableCellFallback);
        scan.step(Long.MAX_VALUE);
        return scan.result();
    }

    /**
     * Starts the same search as {@link #nearestReachableBlock(AIPlayerEntity, Set, int, int, int, Predicate, boolean)}
     * (which is just this plus one unbounded {@link NearestScan#step}) as a resumable scan. The capability decision
     * is taken once here. A caller on the server thread advances it a couple of milliseconds per tick, so a radius-48
     * survey (about 170,000 positions, 90-165 ms cold in the strict profile) never lands as one server tick.
     */
    public static NearestScan beginNearestScan(AIPlayerEntity bot, Set<Block> targetBlocks,
                                               int horizontalRadius, int down, int up,
                                               Predicate<BlockPos> posFilter,
                                               boolean allowObservableCellFallback) {
        CapabilityDecision scanDecision = CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "harvest_nearest_blocks");
        CapabilityTally.INSTANCE.record(bot.getUUID(), PrivilegedCapability.HIDDEN_BLOCK_SCAN, scanDecision.allowed());
        return new NearestScan(bot, targetBlocks, horizontalRadius, down, up, posFilter,
                allowObservableCellFallback, scanDecision.allowed());
    }

    /**
     * Resumable nearest-reachable-block search. Phase 1 walks the cube and collects candidate positions that pass
     * the unchanged observability rules (the capability-denied sphere pre-filter, then the observation check, and
     * only then the block-state read, exactly as the old stream did). Phase 2 orders them near to far and verifies
     * the nearest {@link #REACH_VERIFY_LIMIT} usable ones with the walk-reachability A*; one A* (up to
     * {@link #REACH_MAX_MILLIS} ms) is the most any single {@link #step} spends there, and it always ends the step.
     * The result equals the old synchronous stream: the same filters, the same x-fastest/z-slowest tie order, the
     * same 8-candidate verification limit (counting only positions that produced a usable standing choice).
     */
    public static final class NearestScan {
        private static final int CLOCK_CHECK_MASK = 63; // read the clock every 64 positions

        private final AIPlayerEntity bot;
        private final Set<Block> targetBlocks;
        private final Predicate<BlockPos> posFilter;
        private final boolean allowObservableCellFallback;
        private final boolean hiddenScanAllowed;
        private final BlockPos origin;
        private final int startTick;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final long total;
        private long index;

        private final java.util.ArrayList<BlockPos> candidates = new java.util.ArrayList<>();
        private boolean enumerated;
        private int verifyIndex;
        private int verified;
        private TargetChoice result;
        private boolean done;
        private int steps;
        private long maxStepNanos;
        private long totalNanos;

        private NearestScan(AIPlayerEntity bot, Set<Block> targetBlocks, int horizontalRadius, int down, int up,
                            Predicate<BlockPos> posFilter, boolean allowObservableCellFallback, boolean hiddenScanAllowed) {
            this.bot = bot;
            this.targetBlocks = targetBlocks;
            this.posFilter = posFilter;
            this.allowObservableCellFallback = allowObservableCellFallback;
            this.hiddenScanAllowed = hiddenScanAllowed;
            this.origin = bot.blockPosition();
            this.startTick = bot.level().getServer() == null ? 0 : bot.level().getServer().getTickCount();
            this.minX = origin.getX() - horizontalRadius;
            this.minY = origin.getY() - down;
            this.minZ = origin.getZ() - horizontalRadius;
            this.sizeX = 2 * horizontalRadius + 1;
            this.sizeY = down + up + 1;
            long sizeZ = 2L * horizontalRadius + 1L;
            this.total = (long) sizeX * sizeY * sizeZ;
        }

        public int startTick() {
            return startTick;
        }

        public BlockPos origin() {
            return origin;
        }

        public boolean isDone() {
            return done;
        }

        /** The nearest reachable target, or null; meaningful once {@link #isDone}. */
        public TargetChoice result() {
            return result;
        }

        public int steps() {
            return steps;
        }

        public long maxStepNanos() {
            return maxStepNanos;
        }

        public long totalNanos() {
            return totalNanos;
        }

        /**
         * Advances for about {@code budgetNanos} (one candidate batch or one A* may overshoot it).
         *
         * @return true once the search is finished
         */
        public boolean step(long budgetNanos) {
            if (done) {
                return true;
            }
            long start = System.nanoTime();
            long deadline = budgetNanos >= Long.MAX_VALUE - start ? Long.MAX_VALUE : start + budgetNanos;
            steps++;
            try {
                if (!enumerated) {
                    enumerate(deadline);
                    if (!enumerated) {
                        return false;
                    }
                    candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
                }
                verify(deadline);
                return done;
            } finally {
                long spent = System.nanoTime() - start;
                maxStepNanos = Math.max(maxStepNanos, spent);
                totalNanos += spent;
            }
        }

        private void enumerate(long deadline) {
            var world = bot.level();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int visited = 0;
            while (index < total) {
                long i = index++;
                int x = (int) (i % sizeX);
                long rest = i / sizeX;
                int y = (int) (rest % sizeY);
                int z = (int) (rest / sizeY);
                cursor.set(minX + x, minY + y, minZ + z);
                if ((hiddenScanAllowed || withinObservationReach(bot, cursor))
                        && canObserveHarvestTarget(bot, cursor, allowObservableCellFallback)
                        && targetBlocks.contains(world.getBlockState(cursor).getBlock())
                        && (posFilter == null || posFilter.test(cursor))) {
                    candidates.add(cursor.immutable());
                }
                if ((++visited & CLOCK_CHECK_MASK) == 0 && System.nanoTime() >= deadline) {
                    return;
                }
            }
            enumerated = true;
        }

        private void verify(long deadline) {
            while (!done) {
                if (verified >= REACH_VERIFY_LIMIT || verifyIndex >= candidates.size()) {
                    done = true;
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    return; // out of budget: the next tick continues with the next candidate
                }
                TargetChoice choice = targetChoice(bot, candidates.get(verifyIndex++));
                if (choice == null) {
                    continue;
                }
                verified++;
                if (isWalkReachable(bot, choice)) { // the expensive unit: one A*, up to REACH_MAX_MILLIS
                    result = choice;
                    done = true;
                    return;
                }
            }
        }
    }

    public static void startMining(AIPlayerEntity bot, BlockPos targetPos) {
        ToolSelector.equipBestTool(bot, bot.level().getBlockState(targetPos));
        MiningAction.startMining(bot, targetPos, Direction.getApproximateNearest(bot.getEyePosition().subtract(targetPos.getCenter())));
    }

    public static Optional<ItemEntity> nearestDrop(AIPlayerEntity bot, Item item, double radius) {
        return nearestDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static Optional<ItemEntity> nearestDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        return bot.level()
                .getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(radius),
                        entity -> !entity.getItem().isEmpty() && matches(entity.getItem(), items)
                                && ObservableWorldQuery.canObserveEntity(bot, entity))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
    }

    public static void chaseDrop(AIPlayerEntity bot, Item item, double radius) {
        chaseDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static void chaseDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                approachDropPhysically(bot, drop);
            }
        });
    }

    /**
     * One tick of a plain walk-over pickup for drops lying in open ground (a harvested crop field): a direct walk
     * (collision-driven, the same controller
     * a player-like walk uses) toward the nearest observed, settled drop of {@code items} within
     * {@code radius} whose straight approach is a {@link #isSafeWalkCorridor safe corridor}. Unlike
     * {@link #approachDropPhysically}, it assumes no standable-cell geometry: a bot standing on farmland
     * (15/16 high) or a drop resting inside a partial block's cell has no clean integer stand cell for the
     * route planner, but a short straight walk over the field reaches it. The straight walk has no route
     * planning, so a drop across a cliff, lava or water is skipped (left for the durable pickup logic)
     * instead of walked at. Returns whether a reachable drop is still on the ground within range.
     */
    public static boolean walkOverDrops(AIPlayerEntity bot, Set<Item> items, double radius) {
        List<ItemEntity> drops = bot.level()
                .getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(radius),
                        entity -> !entity.getItem().isEmpty() && matches(entity.getItem(), items)
                                && ObservableWorldQuery.canObserveEntity(bot, entity));
        drops.sort(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
        boolean settling = false;
        for (ItemEntity target : drops) {
            if (!isDropPhysicallySupported(bot, target)) {
                settling = true; // a fresh drop is still in the air: keep waiting for it, do not walk at it yet
                continue;
            }
            if (!isSafeWalkCorridor(bot, target.position())) {
                continue; // skipped for good: nothing to wait for
            }
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(target.position(), 0.5D);
            }
            return true;
        }
        if (!drops.isEmpty() && !settling) {
            bot.getActionPack().stopMovement();
        }
        return settling;
    }

    /** Spacing of the corridor samples: the bot's box is 0.6 wide, so half a block never skips a cell. */
    private static final double CORRIDOR_STEP = 0.5D;
    /** Largest fall a straight walk may take on the way (vanilla's no-damage fall). */
    private static final double CORRIDOR_MAX_FALL = 3.0D;
    /** Highest ledge the straight walk climbs without a jump (vanilla step height is 0.6). */
    private static final double CORRIDOR_STEP_UP = 0.6D;

    /** Points along the straight segment {@code from}..{@code to}, both ends included, at most {@code step} apart. */
    static List<Vec3> corridorSamples(Vec3 from, Vec3 to, double step) {
        double length = from.distanceTo(to);
        int segments = Math.max(1, (int) Math.ceil(length / step));
        List<Vec3> samples = new java.util.ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            samples.add(from.lerp(to, (double) i / segments));
        }
        return samples;
    }

    /**
     * Cheap safety check for a straight, unplanned walk from the bot to {@code to}: at every sample along
     * the segment the bot's box must be free of collision, must not overlap fire/lava/other hazards or any
     * fluid (feet, head or the cell under the feet), and must have a floor within a harmless fall whose fall
     * column (every cell down to that floor) is free of fire, lava, other hazards and fluid too. A cliff edge,
     * a lava pool, a pit with lava at its bottom or a pond between the bot and the drop fails the corridor.
     */
    public static boolean isSafeWalkCorridor(AIPlayerEntity bot, Vec3 to) {
        var world = bot.level();
        Vec3 from = bot.position();
        AABB base = bot.getBoundingBox();
        for (Vec3 sample : corridorSamples(from, to, CORRIDOR_STEP)) {
            // Level with the bot: the walk is collision-driven at the bot's own height (a drop a hair lower on a
            // 15/16 farmland row is still reached; interpolating the height would scrape the floor it stands on).
            AABB box = base.move(sample.x - from.x, 0.0D, sample.z - from.z).deflate(0.01D, 0.0D, 0.01D);
            AABB body = box.inflate(0.0D, -0.01D, 0.0D);
            // Blocked unless the obstacle is something a walking player steps onto (farmland next to a path,
            // a slab, a carpet): free once the box is lifted by the step height.
            if (!world.noCollision(bot, body) && !world.noCollision(bot, body.move(0.0D, CORRIDOR_STEP_UP, 0.0D))) {
                return false;
            }
            int minX = net.minecraft.util.Mth.floor(box.minX);
            int maxX = net.minecraft.util.Mth.floor(box.maxX);
            int minZ = net.minecraft.util.Mth.floor(box.minZ);
            int maxZ = net.minecraft.util.Mth.floor(box.maxZ);
            int feetY = net.minecraft.util.Mth.floor(box.minY + 0.01D);
            int headY = net.minecraft.util.Mth.floor(box.maxY - 0.01D);
            int belowY = net.minecraft.util.Mth.floor(box.minY - 0.01D);
            for (BlockPos cell : BlockPos.betweenClosed(minX, Math.min(belowY, feetY), minZ, maxX, headY, maxZ)) {
                var state = world.getBlockState(cell);
                if (Standability.isDangerous(state) || !state.getFluidState().isEmpty()) {
                    return false;
                }
            }
            if (world.noCollision(bot, box.expandTowards(0.0D, -CORRIDOR_MAX_FALL - 0.01D, 0.0D))) {
                return false; // no floor within a harmless fall
            }
            // The fall column: a gap the bot would drop into is only as safe as everything it drops through down
            // to the floor it lands on (lava or water in a pit is not a floor, a cactus or magma block is not one
            // either). The scan stops at the first layer that has a collision shape, the floor the bot stands on
            // or lands on, so a lava pool under a bridge it walks on does not matter.
            int floorLimit = belowY - (int) Math.ceil(CORRIDOR_MAX_FALL) - 1;
            for (int y = belowY; y >= floorLimit; y--) {
                boolean floor = false;
                for (BlockPos cell : BlockPos.betweenClosed(minX, y, minZ, maxX, y, maxZ)) {
                    var state = world.getBlockState(cell);
                    if (Standability.isDangerous(state) || !state.getFluidState().isEmpty()) {
                        return false;
                    }
                    if (!state.getCollisionShape(world, cell).isEmpty()) {
                        floor = true;
                    }
                }
                if (floor) {
                    break;
                }
            }
        }
        return true;
    }

    public static void sweepPickup(AIPlayerEntity bot, Item item, double radius, int maxTargets) {
        sweepPickupAnyOf(bot, item == null ? null : Set.of(item), radius, maxTargets);
    }

    public static void sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, double radius, int maxTargets) {
        // A player collects what lies about by walking onto it: one drop at a time, through the vanilla pickup
        // range and delay. Nothing is transferred from a distance.
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                approachDropPhysically(bot, drop);
            }
        });
    }

    /**
     * Moves through ordinary player collision toward one observed drop without confusing a
     * vertical separation for horizontal arrival. {@link WalkToController} intentionally steers
     * only on X/Z, so handing it an item directly below the bot reports success without entering
     * the drop's cell. Deep staircase mining hits that geometry frequently.
     */
    public static boolean approachDropPhysically(AIPlayerEntity bot, ItemEntity drop) {
        if (!isDropPhysicallySupported(bot, drop)) {
            return false;
        }
        return approachKnownPickupCell(bot, drop.blockPosition(), drop.position(), false);
    }

    /**
     * Waits beneath an observed airborne drop only when its current column is visibly dry and has
     * a real standable floor. The route is exact surface movement: it cannot dig or spend a pillar,
     * and an unsupported/occluded column retains the caller's durable pickup debt instead.
     */
    public static boolean approachObservedAirborneDropColumn(AIPlayerEntity bot, ItemEntity drop) {
        if (bot == null || drop == null || !drop.isAlive()) {
            return false;
        }
        BlockPos shaftBase = observableDryPickupShaftBase(bot, drop.blockPosition());
        if (shaftBase == null) {
            return false;
        }
        BlockPos current = bot.blockPosition();
        if (current.equals(shaftBase)) {
            bot.getActionPack().stopMovement();
            return true;
        }
        int vertical = shaftBase.getY() - current.getY();
        int horizontal = Math.abs(shaftBase.getX() - current.getX())
                + Math.abs(shaftBase.getZ() - current.getZ());
        if (vertical == -1 && horizontal == 0
                && Standability.isStandable(bot.level(), shaftBase)
                && walkDownInto(bot, shaftBase)) {
            return true;
        }
        return startExactPickupPath(bot, shaftBase);
    }

    /**
     * Drops the bot into the open cell directly below it as a walked step ({@link ActionPack#beginDescend}); a step already in
     * flight is left alone. False when the world refuses the step (the caller falls back to an exact route).
     */
    private static boolean walkDownInto(AIPlayerEntity bot, BlockPos cell) {
        ActionPack pack = bot.getActionPack();
        if (!pack.stepIdle()) {
            return true;
        }
        WalkedStep step = pack.beginDescend(cell, "physical_drop_pickup");
        if (step == null) {
            return false;
        }
        pack.runStep(step);
        return true;
    }

    /**
     * Accepts an observed drop only after ordinary collision can support it. ItemEntity's
     * {@code onGround} flag is not authoritative at a block edge: vanilla bounce resolution can
     * leave the item a fraction of a block above a real support while its velocity is already
     * settled. A thin downward collision probe admits that recoverable state without chasing a
     * truly airborne coordinate or manufacturing a pillar route.
     */
    public static boolean isDropPhysicallySupported(AIPlayerEntity bot, ItemEntity drop) {
        if (bot == null || drop == null || !drop.isAlive()) {
            return false;
        }
        if (drop.onGround() || drop.isInWater()) {
            return true;
        }
        AABB bounds = drop.getBoundingBox();
        AABB supportProbe = new AABB(
                bounds.minX,
                bounds.minY - PICKUP_SUPPORT_PROBE_DEPTH,
                bounds.minZ,
                bounds.maxX,
                bounds.minY,
                bounds.maxZ);
        return bot.level().findSupportingBlock(drop, supportProbe).isPresent();
    }

    /**
     * Returns to a previously observed break/kill cell through ordinary collision movement.
     * This is the fail-closed fallback for a durable pickup ledger when the ItemEntity itself is
     * temporarily behind terrain: the coordinate came from a visible interaction, not a hidden
     * entity scan, and no digging or pillaring is allowed while recovering it.
     */
    public static boolean approachKnownPickupCell(AIPlayerEntity bot, BlockPos itemPos) {
        return approachKnownPickupCell(bot, itemPos, itemPos.getCenter(), true);
    }

    private static boolean approachKnownPickupCell(AIPlayerEntity bot,
                                                    BlockPos itemPos,
                                                    net.minecraft.world.phys.Vec3 target,
                                                    boolean requireExactRoute) {
        BlockPos current = bot.blockPosition();
        Standability.clearCache();
        BlockPos stand = pickupStandPos(bot, itemPos);
        // A freshly broken item keeps its launch velocity for several ticks.  Its current block
        // can therefore be an unsupported air cell.  Asking A* to "reach" that cell makes endpoint
        // resolution choose an unrelated standable block above it and, when filler blocks are in
        // inventory, can even pillar past the falling item.  Wait for the entity to settle instead;
        // the durable pickup ledger owns the retry/deadline.
        if (stand == null) {
            return false;
        }
        int vertical = stand.getY() - current.getY();
        int horizontal = Math.abs(stand.getX() - current.getX())
                + Math.abs(stand.getZ() - current.getZ());

        if (vertical == -1 && horizontal == 0
                && Standability.isStandable(bot.level(), stand)) {
            // The drop is a walked step: no key is needed, gravity lands the bot in the open cell below it once it is
            // centred over it. A refused step (something in the way) falls through to the exact route below.
            if (walkDownInto(bot, stand)) {
                return true;
            }
        }
        if (vertical != 0) {
            // Pickup navigation may use an existing jump/drop route, but it must never dig or
            // pillar toward a transient entity position.  If no ordinary route exists, leave the
            // durable pickup pending and retry/fail with its typed recovery deadline.
            return startExactPickupPath(bot, stand);
        }
        if (current.equals(stand)) {
            // A lower-ring pose is intentionally adjacent to an elevated drop. Move 0.15 blocks
            // toward it while staying inside this supported cell; steering at the entity itself
            // would walk into its pedestal and trigger an unintended jump onto the upper cell.
            // A visible drop can also land at the far edge of the same block: block-coordinate
            // arrival alone does not guarantee a bounding-box collision, so keep nudging toward
            // the observed entity pose. Remembered cells have no factual entity pose and stop at
            // their centre instead.
            if (!stand.equals(itemPos) || !requireExactRoute) {
                io.github.zoyluo.minecraftai.mode.FakePlayerMotion.nudgeWithinBlockToward(
                        bot, stand, target, "physical_drop_pickup");
            } else {
                bot.getActionPack().stopMovement();
            }
            return true;
        }
        // A direct walker steers toward the centre in one straight line. At a diagonal mining
        // corner that line collides with the two solid cardinal walls even though an ordinary
        // two-step L route exists. Exact surface A* retains every required waypoint and its
        // endpoint validation still forbids digging or pillaring during pickup recovery.
        return startExactPickupPath(bot, stand);
    }

    /**
     * Starts an exact no-dig/no-pillar surface route to {@code stand}, rejecting endpoint
     * snapping. Shared by pickup chases and observation sweeps: recovery movement must reach the
     * requested cell itself, or report failure so the caller's ledger keeps owning the retry.
     */
    public static boolean startExactPickupPath(AIPlayerEntity bot, BlockPos stand) {
        ActionResult result = bot.getActionPack().startSurfacePathTo(stand);
        if (result.isFailed()) {
            return false;
        }
        BlockPos resolved = bot.getActionPack().activePathGoal();
        if (resolved == null || !resolved.equals(stand)) {
            bot.getActionPack().stopAll();
            BotLog.action(bot, "pickup_path_endpoint_rejected",
                    "requested", stand.toShortString(),
                    "resolved", resolved == null ? "none" : resolved.toShortString());
            return false;
        }
        return true;
    }

    public static void sweepPickup(AIPlayerEntity bot, Item item, int maxTargets) {
        sweepPickup(bot, item, MinecraftAiConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static void sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, int maxTargets) {
        sweepPickupAnyOf(bot, items, MinecraftAiConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static int totalInventoryCount(AIPlayerEntity bot) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        ItemStack offHandStack = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty()) {
            count += offHandStack.getCount();
        }
        return count;
    }

    public static int countInventoryItems(AIPlayerEntity bot, Set<Item> items) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && matches(stack, items)) {
                count += stack.getCount();
            }
        }
        ItemStack offHandStack = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty() && matches(offHandStack, items)) {
            count += offHandStack.getCount();
        }
        return count;
    }

    public static Set<Item> expectedDropsFor(Set<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Set.of();
        }
        java.util.LinkedHashSet<Item> result = new java.util.LinkedHashSet<>();
        for (Block block : blocks) {
            result.addAll(expectedDropsFor(block));
        }
        return Set.copyOf(result);
    }

    public static Set<Item> expectedDropsFor(Block block) {
        if (block == Blocks.STONE) {
            return Set.of(Items.COBBLESTONE);
        }
        if (block == Blocks.DEEPSLATE) {
            return Set.of(Items.COBBLED_DEEPSLATE);
        }
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            return Set.of(Items.COAL);
        }
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            return Set.of(Items.RAW_IRON);
        }
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
            return Set.of(Items.RAW_COPPER);
        }
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) {
            return Set.of(Items.RAW_GOLD);
        }
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return Set.of(Items.REDSTONE);
        }
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return Set.of(Items.LAPIS_LAZULI);
        }
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            return Set.of(Items.DIAMOND);
        }
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return Set.of(Items.EMERALD);
        }
        Item item = block.asItem();
        return item == Items.AIR ? Set.of() : Set.of(item);
    }

    public static boolean isInventoryFull(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Returns a real standable pickup pose, or {@code null} while a drop is still unsupported. */
    public static BlockPos pickupStandPos(AIPlayerEntity bot, BlockPos itemPos) {
        BlockPos current = bot.blockPosition();
        // A grounded drop on the bot's current level must be collected by entering its exact
        // cell. Choosing the already occupied neighbouring cell merely causes a bounded nudge;
        // that is useful for an elevated drop on a pedestal, but it cannot close an ordinary
        // one-block horizontal gap reliably under vanilla pickup collision.
        if (itemPos.getY() == current.getY()
                && Standability.isStandable(bot.level(), itemPos)) {
            return itemPos.immutable();
        }
        BlockPos below = itemPos.below();
        if (itemPos.getY() == current.getY() + 1
                && Standability.isStandable(bot.level(), below)) {
            // A launch-drifted drop one block above and one block sideways can be visible while
            // the nearest generic candidate is the current lower-ring cell. Nudging inside that
            // cell never crosses the horizontal block boundary, so the item can survive until the
            // recovery deadline. Walk to the factual stand directly beneath it; the player's
            // ordinary collision box then overlaps the elevated ItemEntity without a jump/pillar.
            return below.immutable();
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos shaftBase = observableDryPickupShaftBase(bot, itemPos);
        BlockPos[] candidates = {
                itemPos,
                itemPos.north(),
                itemPos.south(),
                itemPos.east(),
                itemPos.west(),
                below,
                below.north(),
                below.south(),
                below.east(),
                below.west()
        };
        for (BlockPos candidate : candidates) {
            if (!Standability.isStandable(bot.level(), candidate)) {
                continue;
            }
            double distance = candidate.distSqr(current);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (shaftBase != null) {
            double distance = shaftBase.distSqr(current);
            if (distance < bestDistance) {
                best = shaftBase;
            }
        }
        return best;
    }

    /**
     * Finds the factual floor beneath an observed airborne drop without scanning through terrain.
     * Stacked overhead ores can suspend a newly spawned ItemEntity several cells above the mining
     * floor long enough to exhaust a recovery ledger. Waiting below its visible, dry fall column
     * keeps pickup physical and uses only ordinary no-dig/no-pillar surface movement.
     */
    private static BlockPos observableDryPickupShaftBase(AIPlayerEntity bot, BlockPos itemPos) {
        if (bot == null || itemPos == null) {
            return null;
        }
        var world = bot.level();
        for (int depth = 0; depth <= PICKUP_DRY_SHAFT_DEPTH; depth++) {
            BlockPos cell = itemPos.below(depth);
            if (!ObservableWorldQuery.canObserveCell(bot, cell)
                    || !world.getFluidState(cell).isEmpty()
                    || !world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                return null;
            }
            if (depth >= 2 && Standability.isStandable(world, cell)) {
                return cell.immutable();
            }
        }
        return null;
    }

    public static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePosition().distanceTo(target.getCenter()) <= 4.5D;
    }

    public static boolean canDirectMine(AIPlayerEntity bot, BlockPos target) {
        // Allows mining the block underfoot/below (as long as it's within reach) -- the core of
        // digging straight down through stone from the surface.
        return canReach(bot, target);
    }

    /**
     * Cheap exact pre-filter for the strict (capability denied) scans: a block whose centre is farther from the eye
     * than the perception radius plus the block's half diagonal (0.87) has no face endpoint within the radius, so
     * neither the face-ray nor the cell check can ever accept it. Skipping it avoids one capability decision and up
     * to seven raycast setups per position (a radius-48 scan visits about 170,000 positions and cost 100-150 ms).
     * Never applied when the hidden-scan capability is allowed, where every position is observable by definition.
     */
    private static boolean withinObservationReach(AIPlayerEntity bot, BlockPos pos) {
        double reach = Math.max(1, io.github.zoyluo.minecraftai.MinecraftAiConfig.get().perception().radius()) + 0.87D;
        return bot.getEyePosition().distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= reach * reach;
    }

    private static boolean canObserveHarvestTarget(AIPlayerEntity bot,
                                                    BlockPos pos,
                                                    boolean allowObservableCellFallback) {
        if (ObservableWorldQuery.canObserveBlock(bot, pos)) {
            return true;
        }
        // canObserveBlock aims at the block's own shape (its outline for a plant). What it can still miss, a thin
        // plant whose outline rays a neighbour blocks although the cell is in view, is accepted only after this
        // separate ordinary line-of-sight cell check, before the block state is read.
        return allowObservableCellFallback && ObservableWorldQuery.canObserveCell(bot, pos);
    }

    // NAV-OPT (layer 0B): candidates sorted near-to-far, returns the first one the bot can **actually
    // walk to on foot**; only the nearest REACH_VERIFY_LIMIT candidates are verified (small-budget
    // pure-walk A*), balancing accuracy against performance. The old logic only checked "an empty
    // cell is adjacent to the target" without verifying the bot could actually walk there, causing
    // GOTO to repeatedly fail and get stuck.
    private static TargetChoice firstWalkReachable(AIPlayerEntity bot, BlockPos origin, java.util.stream.Stream<TargetChoice> candidates) {
        return candidates
                .sorted(Comparator.comparingDouble(choice -> choice.pos().distSqr(origin)))
                .limit(REACH_VERIFY_LIMIT)
                .filter(choice -> isWalkReachable(bot, choice))
                .findFirst()
                .orElse(null);
    }

    public static boolean isWalkReachable(AIPlayerEntity bot, TargetChoice choice) {
        BlockPos stand = choice.stand();
        if (stand == null || bot.blockPosition().equals(stand)) {
            return true; // Within reach, mine directly / already at the stand position, no pathfinding needed
        }
        return new AStarPathfinder(bot, bot.level(), bot.blockPosition(), stand,
                REACH_MAX_NODES, REACH_MAX_MILLIS, false, false).findPath().success();
    }

    public static TargetChoice targetChoice(AIPlayerEntity bot, BlockPos target) {
        // No longer rejects a block for being lower than the bot itself: if it's within reach, mine it
        // directly (mine underfoot -> fall -> keep mining downward, so drops land right next to the
        // bot's feet for easy pickup).
        if (canDirectMine(bot, target)) {
            return new TargetChoice(target, null, true);
        }
        BlockPos stand = adjacentStandPos(bot, target);
        if (stand == null) {
            return null;
        }
        return new TargetChoice(target, stand, false);
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction);
            if (Standability.isStandable(bot.level(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean matches(ItemStack stack, Set<Item> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        return items.contains(stack.getItem());
    }

    public record TargetChoice(BlockPos pos, BlockPos stand, boolean direct) {
    }
}
