package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Vanilla-facing boat interactions for clientless AI players.
 *
 * <p>Boat placement deliberately goes through {@link InteractAction#useItemInAir}; it does not
 * spawn an entity or remove an item directly.  That keeps item consumption, collision checks, and
 * the resulting boat entity on the ordinary server-side player path.</p>
 */
public final class BoatAction {
    private static final double PLACE_SCAN_RADIUS = 4.0D;
    private static final double BOARD_REACH = 4.5D;

    private BoatAction() {
    }

    public record Placement(Optional<AbstractBoatEntity> boat, String reason) {
        public boolean success() {
            return boat.isPresent();
        }
    }

    /** Places the currently held boat item into the selected water cell through vanilla item use. */
    public static Placement placeBoatInWater(AIPlayerEntity player, BlockPos water) {
        ServerWorld world = player.getEntityWorld();
        if (!world.getFluidState(water).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
            return new Placement(Optional.empty(), "target_not_water");
        }
        ItemStack stack = player.getMainHandStack();
        if (!(stack.getItem() instanceof BoatItem)) {
            return new Placement(Optional.empty(), "held_item_not_boat");
        }
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(water))
                > player.getBlockInteractionRange() * player.getBlockInteractionRange()) {
            return new Placement(Optional.empty(), "water_out_of_reach");
        }

        Set<UUID> existing = nearbyBoats(world, water).stream()
                .map(AbstractBoatEntity::getUuid)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        LookAction.lookAt(player, Vec3d.ofCenter(water));
        ActionResult used = InteractAction.useItemInAir(player, Hand.MAIN_HAND);
        if (used.isFailed()) {
            return new Placement(Optional.empty(), "boat_use_failed:" + used.reason());
        }

        Optional<AbstractBoatEntity> placed = nearbyBoats(world, water).stream()
                .filter(boat -> !existing.contains(boat.getUuid()))
                .min(Comparator.comparingDouble(boat -> boat.squaredDistanceTo(Vec3d.ofCenter(water))));
        if (placed.isEmpty()) {
            return new Placement(Optional.empty(), "boat_not_created");
        }
        AbstractBoatEntity boat = placed.get();
        BotLog.action(player, "boat_placed",
                "water", water.toShortString(),
                "boat_id", boat.getUuid());
        return new Placement(Optional.of(boat), "");
    }

    /** Uses the normal boat interaction path; successful interaction is verified by the caller. */
    public static ActionResult boardBoat(AIPlayerEntity player, AbstractBoatEntity boat) {
        if (boat == null || !boat.isAlive()) {
            return ActionResult.failed("boat_unavailable");
        }
        if (player.getVehicle() == boat) {
            return ActionResult.SUCCESS;
        }
        if (player.squaredDistanceTo(boat) > BOARD_REACH * BOARD_REACH) {
            return ActionResult.failed("boat_out_of_reach");
        }
        LookAction.lookAt(player, boat.getEntityPos().add(0.0D, boat.getHeight() * 0.5D, 0.0D));
        ActionResult result = InteractAction.useItemOnEntity(player, boat, Hand.MAIN_HAND);
        if (result.isSuccess()) {
            BotLog.action(player, "boat_board_requested", "boat_id", boat.getUuid());
        }
        return result;
    }

    /** Clears all vanilla paddle inputs before a wait, dismount, or task transition. */
    public static void stopBoat(AbstractBoatEntity boat) {
        if (boat != null && boat.isAlive()) {
            boat.setInputs(false, false, false, false);
        }
    }

    private static java.util.List<AbstractBoatEntity> nearbyBoats(ServerWorld world, BlockPos water) {
        return world.getEntitiesByClass(AbstractBoatEntity.class,
                new Box(water).expand(PLACE_SCAN_RADIUS, 2.0D, PLACE_SCAN_RADIUS),
                boat -> boat.isAlive());
    }
}
