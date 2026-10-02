package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.craft.CraftingHelper;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared, local-only discovery helpers for the boat task family. */
final class BoatSupport {
    static final int LOCAL_WATER_SEARCH_RADIUS = 16;
    static final double BOARD_REACH = 4.5D;
    // Lakes and rivers sit at or below the bank the bot stands on, so look further down than up.
    private static final int LAUNCH_SEARCH_DOWN = 6;
    private static final int LAUNCH_SEARCH_UP = 3;
    private static final int LAUNCH_SHORE_RADIUS = 3;
    // Eye-to-water distance from the shore CELL CENTRE. The bot stops within 1.5 blocks of that
    // centre and the boat item is placed only within the 4.5-block block-interaction range, so leave
    // a real margin instead of picking spots that are reachable only from the exact centre.
    private static final double LAUNCH_REACH = 3.4D;

    /**
     * Keep recipes species-specific.  A boat should be craftable from the logs/planks the bot
     * actually carries; asking it to make an oak boat from birch logs would be a needless failure.
     */
    private static final List<Item> CRAFTABLE_BOATS = List.of(
            Items.OAK_BOAT,
            Items.SPRUCE_BOAT,
            Items.BIRCH_BOAT,
            Items.JUNGLE_BOAT,
            Items.ACACIA_BOAT,
            Items.DARK_OAK_BOAT,
            Items.MANGROVE_BOAT,
            Items.CHERRY_BOAT,
            Items.PALE_OAK_BOAT);

    private static final double[][] WATER_SURFACE_SAMPLES = {
            {0.0D, 0.0D}, {-0.3D, 0.0D}, {0.3D, 0.0D}, {0.0D, -0.3D}, {0.0D, 0.3D}
    };
    /** State-free ray endpoints: a successful fluid hit proves the actual liquid height. */
    private static final double[] WATER_SURFACE_HEIGHTS = {0.15D, 0.35D, 0.55D, 0.75D, 0.86D};

    private BoatSupport() {
    }

    record LaunchSite(BlockPos water, BlockPos shore) {
    }

    static boolean isBoatItem(Item item) {
        return item instanceof BoatItem;
    }

    static OptionalInt boatSlot(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            ItemStack stack = bot.getInventory().getNonEquipmentItems().get(slot);
            if (!stack.isEmpty() && isBoatItem(stack.getItem())) {
                return OptionalInt.of(slot);
            }
        }
        ItemStack offHandStack = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty() && isBoatItem(offHandStack.getItem())) {
            return io.github.zoyluo.minecraftai.action.InventoryAction.promoteOffhandSlot(bot, 0);
        }
        return OptionalInt.empty();
    }

    static Optional<Item> craftableBoat(AIPlayerEntity bot) {
        return CRAFTABLE_BOATS.stream()
                .filter(boat -> CraftingHelper.plan(bot, boat, 1).success())
                .findFirst();
    }

    /**
     * Finds a visible local water cell with a dry, supported shore cell from which it can be used.
     *
     * <p>Three things the original test got wrong, all of which made a bot standing a few blocks
     * from a lake report {@code no_nearby_water_shore}: (1) the shore may be level with the water
     * cell or one block above it (the ordinary flush vanilla bank puts the bot one cell above the
     * water block that touches its floor block); (2) the launch water must hold a whole boat (see
     * {@link #holdsBoat}), so it is a cell or two out from the bank, not the one beside it; (3) a
     * calm lake is judged visible by rays at its surface ({@link #canObserveWater}), not by the
     * block-face centres, none of which a lake seen from a bank ever satisfies.</p>
     */
    static Optional<LaunchSite> findLaunchSite(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        Standability.clearCache();
        // Ray-prove the liquid before any hull, water, or shore state is read. A loaded chunk is
        // not evidence that the player can see a launch site.
        return BlockPos.betweenClosedStream(
                        origin.offset(-LOCAL_WATER_SEARCH_RADIUS, -LAUNCH_SEARCH_DOWN, -LOCAL_WATER_SEARCH_RADIUS),
                        origin.offset(LOCAL_WATER_SEARCH_RADIUS, LAUNCH_SEARCH_UP, LOCAL_WATER_SEARCH_RADIUS))
                .map(BlockPos::immutable)
                .filter(water -> canObserveWater(bot, water))
                .filter(water -> isOpenWater(bot, world, water))
                .map(water -> launchSite(bot, world, water))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(site -> site.shore().distSqr(origin)))
                .findFirst();
    }

    /**
     * A dry, standable cell close to the nearest visible open water, for walking toward a lake or
     * river the bot can see but is not yet beside (its launch-site test needs the water next to a
     * shore, and near water is only visible from up close).  Empty when no open water is visible.
     */
    static Optional<BlockPos> findWaterApproach(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        Standability.clearCache();
        Optional<BlockPos> water = BlockPos.betweenClosedStream(
                        origin.offset(-LOCAL_WATER_SEARCH_RADIUS, -LAUNCH_SEARCH_DOWN, -LOCAL_WATER_SEARCH_RADIUS),
                        origin.offset(LOCAL_WATER_SEARCH_RADIUS, LAUNCH_SEARCH_UP, LOCAL_WATER_SEARCH_RADIUS))
                .map(BlockPos::immutable)
                .filter(cell -> canObserveWater(bot, cell))
                .filter(cell -> isOpenWater(bot, world, cell))
                .sorted(Comparator.comparingDouble(cell -> cell.distSqr(origin)))
                .findFirst();
        return water.flatMap(cell -> nearestObservedStandable(bot, world, cell, 6, 4, 4));
    }

    /**
     * Line-of-sight test for a water cell.  {@link ObservableWorldQuery#canObserveBlock} is shape-aware, but a
     * fluid has neither a collision shape nor a selection outline to aim at, so it is aimed at as a full cell
     * with a collider ray, which never strikes water (and the face centres of that cell sit outside the
     * 0.89-high surface or on hidden side faces): a calm lake seen from a bank never passes it. Here the rays
     * aim at points just under the water surface, which is exactly what a player looking at the lake sees.
     */
    static boolean canObserveWater(AIPlayerEntity bot, BlockPos water) {
        ServerLevel world = bot.level();
        double reach = LOCAL_WATER_SEARCH_RADIUS;
        for (double height : WATER_SURFACE_HEIGHTS) {
            for (double[] offset : WATER_SURFACE_SAMPLES) {
                Vec3 target = new Vec3(water.getX() + 0.5D + offset[0],
                        water.getY() + height, water.getZ() + 0.5D + offset[1]);
                if (bot.getEyePosition().distanceToSqr(target) > reach * reach) {
                    continue;
                }
                BlockHitResult hit = world.clip(new ClipContext(bot.getEyePosition(), target,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(water)) {
                    return true;
                }
            }
        }
        return false;
    }

    static Optional<BlockPos> nearestBoardingShore(AIPlayerEntity bot, AbstractBoat boat) {
        if (boat == null || !boat.isAlive()) {
            return Optional.empty();
        }
        if (!ObservableWorldQuery.canObserveEntity(bot, boat)) {
            return Optional.empty();
        }
        ServerLevel world = bot.level();
        BlockPos center = boat.blockPosition();
        Standability.clearCache();
        return BlockPos.betweenClosedStream(center.offset(-2, -1, -2), center.offset(2, 1, 2))
                .map(BlockPos::immutable)
                .filter(pos -> observedStandable(bot, world, pos))
                .filter(pos -> pos.distSqr(boat.blockPosition()) <= BOARD_REACH * BOARD_REACH)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())));
    }

    /** A real dry landing next to the boat, used before automatic land-follow dismounts. */
    static Optional<BlockPos> nearbySafeDismountShore(AIPlayerEntity bot, AbstractBoat boat) {
        if (boat == null || !boat.isAlive()) {
            return Optional.empty();
        }
        if (!ObservableWorldQuery.canObserveEntity(bot, boat)) {
            return Optional.empty();
        }
        ServerLevel world = bot.level();
        BlockPos center = boat.blockPosition();
        Standability.clearCache();
        return BlockPos.betweenClosedStream(center.offset(-3, -1, -3), center.offset(3, 1, 3))
                .map(BlockPos::immutable)
                .filter(pos -> observedStandable(bot, world, pos))
                .filter(pos -> world.getFluidState(pos).isEmpty() && world.getFluidState(pos.above()).isEmpty())
                .filter(pos -> pos.distSqr(center) <= BOARD_REACH * BOARD_REACH)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())));
    }

    static Optional<AbstractBoat> nearbyEmptyBoat(AIPlayerEntity bot) {
        return nearbyEmptyBoat(bot, Set.of());
    }

    /**
     * Nearest visible boat with nobody in it. An occupied boat is never returned, which is also
     * what keeps a follower from taking the boat its own target is riding.
     */
    static Optional<AbstractBoat> nearbyEmptyBoat(AIPlayerEntity bot, Set<UUID> excluded) {
        return bot.level().getEntitiesOfClass(
                        AbstractBoat.class,
                        bot.getBoundingBox().inflate(LOCAL_WATER_SEARCH_RADIUS),
                        boat -> boat.isAlive()
                                && !excluded.contains(boat.getUUID())
                                && boat.getPassengers().isEmpty()
                                && ObservableWorldQuery.canObserveEntity(bot, boat))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr));
    }

    static Optional<AbstractBoat> boatById(AIPlayerEntity bot, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        Entity entity = bot.level().getEntity(id);
        return entity instanceof AbstractBoat boat && boat.isAlive()
                ? Optional.of(boat) : Optional.empty();
    }

    static Optional<AbstractBoat> mountedBoat(AIPlayerEntity bot) {
        return bot.getVehicle() instanceof AbstractBoat boat && boat.isAlive()
                ? Optional.of(boat) : Optional.empty();
    }

    static boolean isWater(ServerLevel world, BlockPos pos) {
        return world.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * Steers {@code boat} toward the given world coordinates using its vanilla paddle-input API,
     * or stops it once within {@code stopDistance}. {@code turnOnlyAngle} is the yaw-error
     * threshold beyond which the boat turns in place instead of also paddling forward.
     *
     * @return true once the boat has stopped (already within {@code stopDistance}).
     */
    static boolean steerToward(AbstractBoat boat, double targetX, double targetZ,
            double stopDistance, double turnOnlyAngle) {
        double dx = targetX - boat.getX();
        double dz = targetZ - boat.getZ();
        double horizontalDistance = Math.hypot(dx, dz);
        if (horizontalDistance <= stopDistance) {
            BoatAction.stopBoat(boat);
            return true;
        }
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float turn = Mth.wrapDegrees(desiredYaw - boat.getYRot());
        boolean left = turn < -4.0F;
        boolean right = turn > 4.0F;
        boolean forward = Math.abs(turn) < turnOnlyAngle;
        boat.setInput(left, right, forward, false);
        return false;
    }

    /**
     * Drives the bot's mounted boat toward {@code (targetX, targetZ)} until it can dismount at a
     * genuine dry shore near the boat, then dismounts.
     *
     * @return true while the bot is still aboard and needs another boat tick before land follow.
     */
    static boolean leaveBoatForLand(AIPlayerEntity bot, double targetX, double targetZ,
            double stopDistance, double turnOnlyAngle) {
        AbstractBoat boat = mountedBoat(bot).orElse(null);
        if (boat == null) {
            return false;
        }
        if (nearbySafeDismountShore(bot, boat).isPresent()) {
            BoatAction.stopBoat(boat);
            bot.removeVehicle();
            return bot.getVehicle() instanceof AbstractBoat;
        }
        steerToward(boat, targetX, targetZ, stopDistance, turnOnlyAngle);
        return true;
    }

    private static boolean isOpenWater(AIPlayerEntity bot, ServerLevel world, BlockPos water) {
        if (!canObserveWater(bot, water)
                || !ObservableWorldQuery.canObserveCell(bot, water.above())
                || !isWater(world, water)
                || !world.getFluidState(water.above()).isEmpty()
                || !world.getBlockState(water.above()).getCollisionShape(world, water.above()).isEmpty()) {
            return false;
        }
        int connectedWater = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = water.relative(direction);
            if (canObserveWater(bot, neighbor) && isWater(world, neighbor)) {
                connectedWater++;
            }
        }
        return connectedWater >= 1;
    }

    /**
     * A boat item is placed where the bot's ray meets the water surface, so the spot has to hold a
     * whole boat (1.375 wide, 0.56 high, sitting on the surface): no solid block in the 3x3 cells
     * around it at surface level, none in the 3x3 above.  The water cell right beside a flush bank
     * fails this (the hull would overlap the bank block: vanilla answers FAIL), so the launch
     * water is picked a cell or two out from the shore.
     */
    private static boolean holdsBoat(AIPlayerEntity bot, ServerLevel world, BlockPos water) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = water.offset(dx, dy, dz);
                    if (!ObservableWorldQuery.canObserveCell(bot, pos)) {
                        return false;
                    }
                    if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Nearest standable shore cell (level with or one above the water) that can reach {@code water}. */
    private static Optional<LaunchSite> launchSite(AIPlayerEntity bot, ServerLevel world,
                                                    BlockPos water) {
        if (!holdsBoat(bot, world, water)) {
            return Optional.empty();
        }
        for (int radius = 1; radius <= LAUNCH_SHORE_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos shore = water.offset(dx, dy, dz);
                        double eyeToWater = Math.sqrt(dx * dx + dz * dz
                                + Math.pow(dy + 1.62D - 0.5D, 2));
                        if (eyeToWater <= LAUNCH_REACH
                                && observedStandable(bot, world, shore)) {
                            return Optional.of(new LaunchSite(water, shore));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** A dry standing envelope may be inspected only after the player-eye ray has proved it. */
    private static boolean observedStandable(AIPlayerEntity bot, ServerLevel world, BlockPos pos) {
        return ObservableWorldQuery.canObserveCell(bot, pos)
                && ObservableWorldQuery.canObserveCell(bot, pos.above())
                && ObservableWorldQuery.canObserveBlockCellFace(bot, pos.below())
                && Standability.isStandableFresh(world, pos);
    }

    private static Optional<BlockPos> nearestObservedStandable(AIPlayerEntity bot,
                                                                ServerLevel world,
                                                                BlockPos origin,
                                                                int horizontalRadius,
                                                                int verticalDown,
                                                                int verticalUp) {
        return BlockPos.betweenClosedStream(
                        origin.offset(-horizontalRadius, -verticalDown, -horizontalRadius),
                        origin.offset(horizontalRadius, verticalUp, horizontalRadius))
                .map(BlockPos::immutable)
                .filter(pos -> observedStandable(bot, world, pos))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
    }
}
