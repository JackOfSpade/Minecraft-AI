package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BoatAction;
import io.github.zoyluo.minecraftai.craft.CraftingHelper;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Shared, local-only discovery helpers for the boat task family. */
final class BoatSupport {
    static final int LOCAL_WATER_SEARCH_RADIUS = 16;
    static final double BOARD_REACH = 4.5D;
    // Lakes and rivers sit at or below the bank the bot stands on, so look further down than up.
    private static final int LAUNCH_SEARCH_DOWN = 6;
    private static final int LAUNCH_SEARCH_UP = 3;
    private static final int LAUNCH_SHORE_RADIUS = 3;
    // Comfortably inside the 4.5-block block-interaction range used when the boat item is placed.
    private static final double LAUNCH_REACH = 4.0D;

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

    private BoatSupport() {
    }

    record LaunchSite(BlockPos water, BlockPos shore) {
    }

    static boolean isBoatItem(Item item) {
        return item instanceof BoatItem;
    }

    static OptionalInt boatSlot(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().getMainStacks().size(); slot++) {
            ItemStack stack = bot.getInventory().getMainStacks().get(slot);
            if (!stack.isEmpty() && isBoatItem(stack.getItem())) {
                return OptionalInt.of(slot);
            }
        }
        ItemStack offHandStack = bot.getEquippedStack(EquipmentSlot.OFFHAND);
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
     * <p>The shore is either level with the water cell or one block above it. The second shape is
     * the ordinary vanilla bank (a beach or grass block whose top is flush with the water's
     * surface: the bot stands one cell above the water block that touches its floor block); the
     * old same-level-only test matched almost no real terrain, which is why a bot standing a few
     * blocks from a lake reported {@code no_nearby_water_shore}.</p>
     */
    static Optional<LaunchSite> findLaunchSite(AIPlayerEntity bot) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos origin = bot.getBlockPos();
        Standability.clearCache();
        // Cheap block-state tests first; the (raycast) visibility test only runs for the few
        // cells that are already real open-water launch candidates.
        return BlockPos.stream(
                        origin.add(-LOCAL_WATER_SEARCH_RADIUS, -LAUNCH_SEARCH_DOWN, -LOCAL_WATER_SEARCH_RADIUS),
                        origin.add(LOCAL_WATER_SEARCH_RADIUS, LAUNCH_SEARCH_UP, LOCAL_WATER_SEARCH_RADIUS))
                .map(BlockPos::toImmutable)
                .filter(water -> isOpenWater(world, water))
                .map(water -> launchSite(world, water))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(site -> site.shore().getSquaredDistance(origin)))
                .filter(site -> canObserveWater(bot, site.water()))
                .findFirst();
    }

    /**
     * A dry, standable cell close to the nearest visible open water, for walking toward a lake or
     * river the bot can see but is not yet beside (its launch-site test needs the water next to a
     * shore, and near water is only visible from up close).  Empty when no open water is visible.
     */
    static Optional<BlockPos> findWaterApproach(AIPlayerEntity bot) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos origin = bot.getBlockPos();
        Standability.clearCache();
        Optional<BlockPos> water = BlockPos.stream(
                        origin.add(-LOCAL_WATER_SEARCH_RADIUS, -LAUNCH_SEARCH_DOWN, -LOCAL_WATER_SEARCH_RADIUS),
                        origin.add(LOCAL_WATER_SEARCH_RADIUS, LAUNCH_SEARCH_UP, LOCAL_WATER_SEARCH_RADIUS))
                .map(BlockPos::toImmutable)
                .filter(cell -> isOpenWater(world, cell))
                .sorted(Comparator.comparingDouble(cell -> cell.getSquaredDistance(origin)))
                .filter(cell -> canObserveWater(bot, cell))
                .findFirst();
        return water.flatMap(cell -> Standability.findNearestStandable(world, cell, 6, 4, 4));
    }

    /**
     * Line-of-sight test for a water cell.  {@link ObservableWorldQuery#canObserveBlock} aims at
     * the block-face centres, all of which sit outside the (0.89-high) water surface or on hidden
     * side faces, so a calm lake seen from a bank never passes it; here the rays aim at points
     * just under the water surface, which is exactly what a player looking at the lake sees.
     */
    static boolean canObserveWater(AIPlayerEntity bot, BlockPos water) {
        if (ObservableWorldQuery.canObserveBlock(bot, water)) {
            return true;
        }
        ServerWorld world = bot.getEntityWorld();
        double surface = water.getY() + world.getFluidState(water).getHeight(world, water) - 0.05D;
        double reach = LOCAL_WATER_SEARCH_RADIUS;
        for (double[] offset : WATER_SURFACE_SAMPLES) {
            Vec3d target = new Vec3d(water.getX() + 0.5D + offset[0], surface, water.getZ() + 0.5D + offset[1]);
            if (bot.getEyePos().squaredDistanceTo(target) > reach * reach) {
                continue;
            }
            BlockHitResult hit = world.raycast(new RaycastContext(bot.getEyePos(), target,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, bot));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(water)) {
                return true;
            }
        }
        return false;
    }

    static Optional<BlockPos> nearestBoardingShore(AIPlayerEntity bot, AbstractBoatEntity boat) {
        if (boat == null || !boat.isAlive()) {
            return Optional.empty();
        }
        ServerWorld world = bot.getEntityWorld();
        BlockPos center = boat.getBlockPos();
        Standability.clearCache();
        return BlockPos.stream(center.add(-2, -1, -2), center.add(2, 1, 2))
                .map(BlockPos::toImmutable)
                .filter(pos -> Standability.isStandable(world, pos))
                .filter(pos -> pos.getSquaredDistance(boat.getBlockPos()) <= BOARD_REACH * BOARD_REACH)
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos())));
    }

    /** A real dry landing next to the boat, used before automatic land-follow dismounts. */
    static Optional<BlockPos> nearbySafeDismountShore(AIPlayerEntity bot, AbstractBoatEntity boat) {
        if (boat == null || !boat.isAlive()) {
            return Optional.empty();
        }
        ServerWorld world = bot.getEntityWorld();
        BlockPos center = boat.getBlockPos();
        Standability.clearCache();
        return BlockPos.stream(center.add(-3, -1, -3), center.add(3, 1, 3))
                .map(BlockPos::toImmutable)
                .filter(pos -> Standability.isStandable(world, pos))
                .filter(pos -> world.getFluidState(pos).isEmpty() && world.getFluidState(pos.up()).isEmpty())
                .filter(pos -> pos.getSquaredDistance(center) <= BOARD_REACH * BOARD_REACH)
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos())));
    }

    static Optional<AbstractBoatEntity> nearbyEmptyBoat(AIPlayerEntity bot) {
        return nearbyEmptyBoat(bot, Set.of());
    }

    /**
     * Nearest visible boat with nobody in it. An occupied boat is never returned, which is also
     * what keeps a follower from taking the boat its own target is riding.
     */
    static Optional<AbstractBoatEntity> nearbyEmptyBoat(AIPlayerEntity bot, Set<UUID> excluded) {
        return bot.getEntityWorld().getEntitiesByClass(
                        AbstractBoatEntity.class,
                        bot.getBoundingBox().expand(LOCAL_WATER_SEARCH_RADIUS),
                        boat -> boat.isAlive()
                                && !excluded.contains(boat.getUuid())
                                && boat.getPassengerList().isEmpty()
                                && ObservableWorldQuery.canObserveEntity(bot, boat))
                .stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo));
    }

    static Optional<AbstractBoatEntity> boatById(AIPlayerEntity bot, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        Entity entity = bot.getEntityWorld().getEntity(id);
        return entity instanceof AbstractBoatEntity boat && boat.isAlive()
                ? Optional.of(boat) : Optional.empty();
    }

    static Optional<AbstractBoatEntity> mountedBoat(AIPlayerEntity bot) {
        return bot.getVehicle() instanceof AbstractBoatEntity boat && boat.isAlive()
                ? Optional.of(boat) : Optional.empty();
    }

    static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getFluidState(pos).isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    /**
     * Steers {@code boat} toward the given world coordinates using its vanilla paddle-input API,
     * or stops it once within {@code stopDistance}. {@code turnOnlyAngle} is the yaw-error
     * threshold beyond which the boat turns in place instead of also paddling forward.
     *
     * @return true once the boat has stopped (already within {@code stopDistance}).
     */
    static boolean steerToward(AbstractBoatEntity boat, double targetX, double targetZ,
            double stopDistance, double turnOnlyAngle) {
        double dx = targetX - boat.getX();
        double dz = targetZ - boat.getZ();
        double horizontalDistance = Math.hypot(dx, dz);
        if (horizontalDistance <= stopDistance) {
            BoatAction.stopBoat(boat);
            return true;
        }
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float turn = MathHelper.wrapDegrees(desiredYaw - boat.getYaw());
        boolean left = turn < -4.0F;
        boolean right = turn > 4.0F;
        boolean forward = Math.abs(turn) < turnOnlyAngle;
        boat.setInputs(left, right, forward, false);
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
        AbstractBoatEntity boat = mountedBoat(bot).orElse(null);
        if (boat == null) {
            return false;
        }
        if (nearbySafeDismountShore(bot, boat).isPresent()) {
            BoatAction.stopBoat(boat);
            bot.dismountVehicle();
            return bot.getVehicle() instanceof AbstractBoatEntity;
        }
        steerToward(boat, targetX, targetZ, stopDistance, turnOnlyAngle);
        return true;
    }

    private static boolean isOpenWater(ServerWorld world, BlockPos water) {
        if (!isWater(world, water)
                || !world.getFluidState(water.up()).isEmpty()
                || !world.getBlockState(water.up()).getCollisionShape(world, water.up()).isEmpty()) {
            return false;
        }
        int connectedWater = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (isWater(world, water.offset(direction))) {
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
    private static boolean holdsBoat(ServerWorld world, BlockPos water) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = water.add(dx, dy, dz);
                    if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Nearest standable shore cell (level with or one above the water) that can reach {@code water}. */
    private static Optional<LaunchSite> launchSite(ServerWorld world, BlockPos water) {
        if (!holdsBoat(world, water)) {
            return Optional.empty();
        }
        for (int radius = 1; radius <= LAUNCH_SHORE_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos shore = water.add(dx, dy, dz);
                        double eyeToWater = Math.sqrt(dx * dx + dz * dz
                                + Math.pow(dy + 1.62D - 0.5D, 2));
                        if (eyeToWater <= LAUNCH_REACH && Standability.isStandable(world, shore)) {
                            return Optional.of(new LaunchSite(water, shore));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }
}
