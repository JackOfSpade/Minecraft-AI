package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.craft.CraftingHelper;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Shared, local-only discovery helpers for the boat task family. */
final class BoatSupport {
    static final int LOCAL_WATER_SEARCH_RADIUS = 16;
    static final double BOARD_REACH = 4.5D;

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

    private BoatSupport() {
    }

    record LaunchSite(BlockPos water, BlockPos shore) {
    }

    static boolean isBoatItem(Item item) {
        return item instanceof BoatItem;
    }

    static OptionalInt boatSlot(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            ItemStack stack = bot.getInventory().main.get(slot);
            if (!stack.isEmpty() && isBoatItem(stack.getItem())) {
                return OptionalInt.of(slot);
            }
        }
        for (int slot = 0; slot < bot.getInventory().offHand.size(); slot++) {
            ItemStack stack = bot.getInventory().offHand.get(slot);
            if (!stack.isEmpty() && isBoatItem(stack.getItem())) {
                return io.github.zoyluo.aibot.action.InventoryAction.promoteOffhandSlot(bot, slot);
            }
        }
        return OptionalInt.empty();
    }

    static Optional<Item> craftableBoat(AIPlayerEntity bot) {
        return CRAFTABLE_BOATS.stream()
                .filter(boat -> CraftingHelper.plan(bot, boat, 1).success())
                .findFirst();
    }

    /** Finds a visible local water cell with a dry, supported shore cell from which it can be used. */
    static Optional<LaunchSite> findLaunchSite(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        Standability.clearCache();
        return BlockPos.stream(
                        origin.add(-LOCAL_WATER_SEARCH_RADIUS, -3, -LOCAL_WATER_SEARCH_RADIUS),
                        origin.add(LOCAL_WATER_SEARCH_RADIUS, 3, LOCAL_WATER_SEARCH_RADIUS))
                .map(BlockPos::toImmutable)
                .filter(water -> ObservableWorldQuery.canObserveBlock(bot, water))
                .filter(water -> isOpenWater(world, water))
                .map(water -> launchSite(world, water))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(site -> site.shore().getSquaredDistance(origin)));
    }

    static Optional<BlockPos> nearestBoardingShore(AIPlayerEntity bot, AbstractBoatEntity boat) {
        if (boat == null || !boat.isAlive()) {
            return Optional.empty();
        }
        ServerWorld world = bot.getServerWorld();
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
        ServerWorld world = bot.getServerWorld();
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
        return bot.getServerWorld().getEntitiesByClass(
                        AbstractBoatEntity.class,
                        bot.getBoundingBox().expand(LOCAL_WATER_SEARCH_RADIUS),
                        boat -> boat.isAlive()
                                && boat.getPassengerList().isEmpty()
                                && ObservableWorldQuery.canObserveEntity(bot, boat))
                .stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo));
    }

    static Optional<AbstractBoatEntity> boatById(AIPlayerEntity bot, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        Entity entity = bot.getServerWorld().getEntity(id);
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

    private static boolean isOpenWater(ServerWorld world, BlockPos water) {
        if (!isWater(world, water)
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

    private static Optional<LaunchSite> launchSite(ServerWorld world, BlockPos water) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos shore = water.offset(direction);
            if (Standability.isStandable(world, shore)) {
                return Optional.of(new LaunchSite(water, shore));
            }
        }
        return Optional.empty();
    }
}
