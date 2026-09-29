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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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
        CapabilityTally.INSTANCE.record(bot.getUuid(), PrivilegedCapability.HIDDEN_BLOCK_SCAN, scanDecision.allowed());
        BlockPos origin = bot.getBlockPos();
        return firstWalkReachable(bot, origin,
                BlockPos.stream(origin.add(-horizontalRadius, -down, -horizontalRadius), origin.add(horizontalRadius, up, horizontalRadius))
                        .filter(pos -> scanDecision.allowed() || withinObservationReach(bot, pos))
                        .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                        .filter(pos -> bot.getEntityWorld().getBlockState(pos).isOf(targetBlock))
                        .map(BlockPos::toImmutable)
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
     * Finds a reachable target while optionally accepting a visible cell when the target has no
     * collision shape.  Short grass, ferns, flowers, and similar thin blocks cannot be hit by a
     * COLLIDER raycast, so {@code canObserveBlock} alone makes every visible plant look hidden.
     * The cell fallback remains line-of-sight bounded and is opt-in for callers that can target
     * such blocks.
     */
    public static TargetChoice nearestReachableBlock(AIPlayerEntity bot, Set<Block> targetBlocks,
                                                     int horizontalRadius, int down, int up,
                                                     Predicate<BlockPos> posFilter,
                                                     boolean allowObservableCellFallback) {
        CapabilityDecision scanDecision = CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "harvest_nearest_blocks");
        CapabilityTally.INSTANCE.record(bot.getUuid(), PrivilegedCapability.HIDDEN_BLOCK_SCAN, scanDecision.allowed());
        BlockPos origin = bot.getBlockPos();
        return firstWalkReachable(bot, origin,
                BlockPos.stream(origin.add(-horizontalRadius, -down, -horizontalRadius), origin.add(horizontalRadius, up, horizontalRadius))
                        .filter(pos -> scanDecision.allowed() || withinObservationReach(bot, pos))
                        .filter(pos -> canObserveHarvestTarget(
                                bot, pos, allowObservableCellFallback))
                        .filter(pos -> targetBlocks.contains(bot.getEntityWorld().getBlockState(pos).getBlock()))
                        .filter(pos -> posFilter == null || posFilter.test(pos))
                        .map(BlockPos::toImmutable)
                        .map(pos -> targetChoice(bot, pos))
                        .filter(choice -> choice != null));
    }

    public static void startMining(AIPlayerEntity bot, BlockPos targetPos) {
        ToolSelector.equipBestTool(bot, bot.getEntityWorld().getBlockState(targetPos));
        MiningAction.startMining(bot, targetPos, Direction.getFacing(bot.getEyePos().subtract(targetPos.toCenterPos())));
    }

    public static Optional<ItemEntity> nearestDrop(AIPlayerEntity bot, Item item, double radius) {
        return nearestDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static Optional<ItemEntity> nearestDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        return bot.getEntityWorld()
                .getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(radius),
                        entity -> !entity.getStack().isEmpty() && matches(entity.getStack(), items)
                                && ObservableWorldQuery.canObserveEntity(bot, entity))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
    }

    public static boolean forcePickupNearby(AIPlayerEntity bot, Item item, double maxH, double maxV) {
        return forcePickupNearbyAnyOf(bot, item == null ? null : Set.of(item), maxH, maxV);
    }

    public static boolean forcePickupNearbyAnyOf(AIPlayerEntity bot, Set<Item> items, double maxH, double maxV) {
        CapabilityDecision pickupDecision = CapabilityRuntime.decide(bot, PrivilegedCapability.FORCED_PICKUP, "harvest_force_pickup");
        CapabilityTally.INSTANCE.record(bot.getUuid(), PrivilegedCapability.FORCED_PICKUP, pickupDecision.allowed());
        if (!pickupDecision.allowed()) {
            return false;
        }
        Box box = bot.getBoundingBox().expand(maxH, maxV, maxH);
        List<ItemEntity> drops = bot.getEntityWorld().getEntitiesByClass(ItemEntity.class, box,
                entity -> !entity.getStack().isEmpty()
                        && matches(entity.getStack(), items)
                        && ObservableWorldQuery.canObserveEntity(bot, entity)
                        && canForcePickup(bot, entity, maxH, maxV));
        boolean picked = false;
        for (ItemEntity drop : drops) {
            ItemStack remaining = drop.getStack().copy();
            int before = remaining.getCount();
            ActionResult result = InventoryAction.giveItem(bot, remaining);
            int inserted = before - remaining.getCount();
            if (inserted <= 0) {
                continue;
            }
            picked = true;
            BotLog.action(bot, "pickup_forced",
                    "item", drop.getStack().getItem(),
                    "count", inserted,
                    "result", result.isSuccess() ? "all" : result.reason());
            if (remaining.isEmpty()) {
                drop.discard();
            } else {
                drop.setStack(remaining);
            }
        }
        return picked;
    }

    public static boolean forcePickupNearby(AIPlayerEntity bot, Item item) {
        MinecraftAiConfig.Pickup pickup = MinecraftAiConfig.get().pickup();
        return forcePickupNearby(bot, item, pickup.forceRadiusH(), pickup.forceRadiusV());
    }

    public static boolean forcePickupNearbyAnyOf(AIPlayerEntity bot, Set<Item> items) {
        MinecraftAiConfig.Pickup pickup = MinecraftAiConfig.get().pickup();
        return forcePickupNearbyAnyOf(bot, items, pickup.forceRadiusH(), pickup.forceRadiusV());
    }

    public static void chaseDrop(AIPlayerEntity bot, Item item, double radius) {
        chaseDropAnyOf(bot, item == null ? null : Set.of(item), radius);
    }

    public static void chaseDropAnyOf(AIPlayerEntity bot, Set<Item> items, double radius) {
        if (forcePickupNearbyAnyOf(bot, items)) {
            bot.getActionPack().stopMovement();
            return;
        }
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                approachDropPhysically(bot, drop);
            }
        });
    }

    public static int sweepPickup(AIPlayerEntity bot, Item item, double radius, int maxTargets) {
        return sweepPickupAnyOf(bot, item == null ? null : Set.of(item), radius, maxTargets);
    }

    public static int sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, double radius, int maxTargets) {
        int picked = 0;
        for (int i = 0; i < maxTargets; i++) {
            if (!forcePickupNearbyAnyOf(bot, items)) {
                break;
            }
            picked++;
        }
        if (picked > 0) {
            return picked;
        }
        nearestDropAnyOf(bot, items, radius).ifPresent(drop -> {
            if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
                approachDropPhysically(bot, drop);
            }
        });
        return 0;
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
        return approachKnownPickupCell(bot, drop.getBlockPos(), drop.getEntityPos(), false);
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
        BlockPos shaftBase = observableDryPickupShaftBase(bot, drop.getBlockPos());
        if (shaftBase == null) {
            return false;
        }
        BlockPos current = bot.getBlockPos();
        if (current.equals(shaftBase)) {
            bot.getActionPack().stopMovement();
            return true;
        }
        int vertical = shaftBase.getY() - current.getY();
        int horizontal = Math.abs(shaftBase.getX() - current.getX())
                + Math.abs(shaftBase.getZ() - current.getZ());
        if (vertical == -1 && horizontal == 0
                && Standability.isStandable(bot.getEntityWorld(), shaftBase)) {
            bot.getActionPack().descendInto(shaftBase);
            return true;
        }
        return startExactPickupPath(bot, shaftBase);
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
        if (drop.isOnGround() || drop.isTouchingWater()) {
            return true;
        }
        Box bounds = drop.getBoundingBox();
        Box supportProbe = new Box(
                bounds.minX,
                bounds.minY - PICKUP_SUPPORT_PROBE_DEPTH,
                bounds.minZ,
                bounds.maxX,
                bounds.minY,
                bounds.maxZ);
        return bot.getEntityWorld().findSupportingBlockPos(drop, supportProbe).isPresent();
    }

    /**
     * Returns to a previously observed break/kill cell through ordinary collision movement.
     * This is the fail-closed fallback for a durable pickup ledger when the ItemEntity itself is
     * temporarily behind terrain: the coordinate came from a visible interaction, not a hidden
     * entity scan, and no digging or pillaring is allowed while recovering it.
     */
    public static boolean approachKnownPickupCell(AIPlayerEntity bot, BlockPos itemPos) {
        return approachKnownPickupCell(bot, itemPos, itemPos.toCenterPos(), true);
    }

    private static boolean approachKnownPickupCell(AIPlayerEntity bot,
                                                    BlockPos itemPos,
                                                    net.minecraft.util.math.Vec3d target,
                                                    boolean requireExactRoute) {
        BlockPos current = bot.getBlockPos();
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
                && Standability.isStandable(bot.getEntityWorld(), stand)) {
            // Server-side fake players receive no client gravity. Enter the adjacent open cell
            // explicitly; ActionPack validates this as a single physical fake-client step.
            bot.getActionPack().descendInto(stand);
            return true;
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

    public static int sweepPickup(AIPlayerEntity bot, Item item, int maxTargets) {
        return sweepPickup(bot, item, MinecraftAiConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static int sweepPickupAnyOf(AIPlayerEntity bot, Set<Item> items, int maxTargets) {
        return sweepPickupAnyOf(bot, items, MinecraftAiConfig.get().pickup().sweepRadius(), maxTargets);
    }

    public static int totalInventoryCount(AIPlayerEntity bot) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().getMainStacks()) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        ItemStack offHandStack = bot.getEquippedStack(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty()) {
            count += offHandStack.getCount();
        }
        return count;
    }

    public static int countInventoryItems(AIPlayerEntity bot, Set<Item> items) {
        int count = 0;
        for (ItemStack stack : bot.getInventory().getMainStacks()) {
            if (!stack.isEmpty() && matches(stack, items)) {
                count += stack.getCount();
            }
        }
        ItemStack offHandStack = bot.getEquippedStack(EquipmentSlot.OFFHAND);
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
        for (ItemStack stack : bot.getInventory().getMainStacks()) {
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Returns a real standable pickup pose, or {@code null} while a drop is still unsupported. */
    public static BlockPos pickupStandPos(AIPlayerEntity bot, BlockPos itemPos) {
        BlockPos current = bot.getBlockPos();
        // A grounded drop on the bot's current level must be collected by entering its exact
        // cell. Choosing the already occupied neighbouring cell merely causes a bounded nudge;
        // that is useful for an elevated drop on a pedestal, but it cannot close an ordinary
        // one-block horizontal gap reliably under vanilla pickup collision.
        if (itemPos.getY() == current.getY()
                && Standability.isStandable(bot.getEntityWorld(), itemPos)) {
            return itemPos.toImmutable();
        }
        BlockPos below = itemPos.down();
        if (itemPos.getY() == current.getY() + 1
                && Standability.isStandable(bot.getEntityWorld(), below)) {
            // A launch-drifted drop one block above and one block sideways can be visible while
            // the nearest generic candidate is the current lower-ring cell. Nudging inside that
            // cell never crosses the horizontal block boundary, so the item can survive until the
            // recovery deadline. Walk to the factual stand directly beneath it; the player's
            // ordinary collision box then overlaps the elevated ItemEntity without a jump/pillar.
            return below.toImmutable();
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
            if (!Standability.isStandable(bot.getEntityWorld(), candidate)) {
                continue;
            }
            double distance = candidate.getSquaredDistance(current);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (shaftBase != null) {
            double distance = shaftBase.getSquaredDistance(current);
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
        var world = bot.getEntityWorld();
        for (int depth = 0; depth <= PICKUP_DRY_SHAFT_DEPTH; depth++) {
            BlockPos cell = itemPos.down(depth);
            if (!ObservableWorldQuery.canObserveCell(bot, cell)
                    || !world.getFluidState(cell).isEmpty()
                    || !world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                return null;
            }
            if (depth >= 2 && Standability.isStandable(world, cell)) {
                return cell.toImmutable();
            }
        }
        return null;
    }

    public static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePos().distanceTo(target.toCenterPos()) <= 4.5D;
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
        return bot.getEyePos().squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= reach * reach;
    }

    private static boolean canObserveHarvestTarget(AIPlayerEntity bot,
                                                    BlockPos pos,
                                                    boolean allowObservableCellFallback) {
        if (ObservableWorldQuery.canObserveBlock(bot, pos)) {
            return true;
        }
        // A non-colliding plant has no block hit for canObserveBlock's collider ray.  Reading the
        // block state is still safe only after this separate ordinary line-of-sight cell check.
        return allowObservableCellFallback && ObservableWorldQuery.canObserveCell(bot, pos);
    }

    // NAV-OPT (layer 0B): candidates sorted near-to-far, returns the first one the bot can **actually
    // walk to on foot**; only the nearest REACH_VERIFY_LIMIT candidates are verified (small-budget
    // pure-walk A*), balancing accuracy against performance. The old logic only checked "an empty
    // cell is adjacent to the target" without verifying the bot could actually walk there, causing
    // GOTO to repeatedly fail and get stuck.
    private static TargetChoice firstWalkReachable(AIPlayerEntity bot, BlockPos origin, java.util.stream.Stream<TargetChoice> candidates) {
        return candidates
                .sorted(Comparator.comparingDouble(choice -> choice.pos().getSquaredDistance(origin)))
                .limit(REACH_VERIFY_LIMIT)
                .filter(choice -> isWalkReachable(bot, choice))
                .findFirst()
                .orElse(null);
    }

    public static boolean isWalkReachable(AIPlayerEntity bot, TargetChoice choice) {
        BlockPos stand = choice.stand();
        if (stand == null || bot.getBlockPos().equals(stand)) {
            return true; // Within reach, mine directly / already at the stand position, no pathfinding needed
        }
        return new AStarPathfinder(bot, bot.getEntityWorld(), bot.getBlockPos(), stand,
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
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction);
            if (Standability.isStandable(bot.getEntityWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canForcePickup(AIPlayerEntity bot, ItemEntity drop, double maxH, double maxV) {
        if (drop.cannotPickup()) {
            return false;
        }
        double dx = drop.getX() - bot.getX();
        double dz = drop.getZ() - bot.getZ();
        return dx * dx + dz * dz <= maxH * maxH && Math.abs(drop.getY() - bot.getY()) <= maxV;
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
