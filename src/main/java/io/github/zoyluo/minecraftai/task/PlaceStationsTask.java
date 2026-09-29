package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * PLACE_STATIONS (Phase 2 infrastructure goal): places the crafting table / furnace / chest from
 * the inventory onto empty ground around the bot, forming a fixed production+storage station.
 *
 * Reuses {@link BuildAction#placeBlockAt} (the same placement primitive as the emergency shelter):
 * switch the held item, then place it in a placeable empty spot near the bot's feet (solid block
 * underneath, clear space above).
 * Whichever item is missing from the inventory is skipped (GoalPlanner already back-plans to
 * gather all three beforehand). Self-contained state machine (G1), runs entirely on the main
 * thread (G2).
 */
public final class PlaceStationsTask extends AbstractTask {
    private static final int MAX_ELAPSED = 600;
    private static final int STATION_RADIUS = 8;
    private static final List<Item> STATIONS = List.of(Items.CRAFTING_TABLE, Items.FURNACE, Items.CHEST);

    private final List<Item> pending = new ArrayList<>();
    private final Set<BlockPos> used = new HashSet<>();
    private final Set<BlockPos> placedPositions = new HashSet<>();
    private int placed;
    private int ready;

    @Override
    public String name() {
        return "place_stations";
    }

    @Override
    public String describe() {
        return "Setting up stations ready=" + ready + "/" + STATIONS.size()
                + " placed=" + placed;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return STATIONS.isEmpty() ? 0.0D : Math.min(0.95D, (double) ready / STATIONS.size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        pending.clear();
        ready = 0;
        for (Item station : STATIONS) {
            if (stationAlreadyNearby(bot, station)) {
                ready++;
            } else {
                pending.add(station);
            }
        }
        used.clear();
        placedPositions.clear();
        placed = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            if (placed > 0) {
                complete();
            } else {
                fail("place_stations_timeout");
            }
            return;
        }
        if (pending.isEmpty()) {
            complete();
            return;
        }
        Item station = pending.get(0);
        int slot = findSlot(bot, station);
        if (slot < 0) {
            BotLog.action(bot, "place_stations_missing_item", "item", BuiltInRegistries.ITEM.getKey(station));
            pending.remove(0); // This item is no longer in the inventory -> skip it
            return;
        }
        BlockPos spot = findFreeSpot(bot);
        if (spot == null) {
            fail("no_place_spot");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, slot) < 0) {
            BotLog.action(bot, "place_stations_equip_failed", "item", BuiltInRegistries.ITEM.getKey(station));
            pending.remove(0);
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, spot);
        used.add(spot);
        if (result.isSuccess()) {
            placed++;
            ready++;
            placedPositions.add(spot.immutable());
            pending.remove(0);
        } else {
            BotLog.action(bot, "place_stations_place_failed", "item", BuiltInRegistries.ITEM.getKey(station),
                    "pos", spot.toShortString(), "reason", result.reason());
        }
    }

    // A placeable empty spot 1-2 blocks around the bot that hasn't been used yet, with clear space above and a solid block underneath.
    private BlockPos findFreeSpot(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        for (int r = 1; r <= 2; r++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos p = feet.relative(direction, r);
                if (used.contains(p)) {
                    continue;
                }
                if (world.getBlockState(p).isAir()
                        && world.getBlockState(p.above()).isAir()
                        && !world.getBlockState(p.below()).getCollisionShape(world, p.below()).isEmpty()) {
                    return p;
                }
            }
        }
        return null;
    }

    private static int findSlot(AIPlayerEntity bot, Item item) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (inventory.getNonEquipmentItems().get(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Preserve stations a player or another bot has already established.  The table/furnace
     * lookups use the same visible-local definition as the crafting/smelting tasks; chest uses
     * the workstation goal's radius and accepts trapped chests as the same storage capability.
     */
    private static boolean stationAlreadyNearby(AIPlayerEntity bot, Item station) {
        if (station == Items.CRAFTING_TABLE) {
            return WorkshopLocator.hasNearbyCraftingTable(bot);
        }
        if (station == Items.FURNACE) {
            return WorkshopLocator.hasNearbyFurnace(bot);
        }
        if (station != Items.CHEST) {
            return false;
        }
        BlockPos origin = bot.blockPosition();
        return BlockPos.betweenClosedStream(origin.offset(-STATION_RADIUS, -3, -STATION_RADIUS),
                        origin.offset(STATION_RADIUS, 4, STATION_RADIUS))
                .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                .anyMatch(pos -> bot.level().getBlockState(pos).is(Blocks.CHEST)
                        || bot.level().getBlockState(pos).is(Blocks.TRAPPED_CHEST));
    }

    public Set<BlockPos> placedPositions() {
        return Set.copyOf(placedPositions);
    }
}
