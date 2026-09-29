package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

    public record Placement(Optional<AbstractBoat> boat, String reason) {
        public boolean success() {
            return boat.isPresent();
        }
    }

    /** Places the currently held boat item into the selected water cell through vanilla item use. */
    public static Placement placeBoatInWater(AIPlayerEntity player, BlockPos water) {
        ServerLevel world = player.level();
        if (!world.getFluidState(water).is(net.minecraft.tags.FluidTags.WATER)) {
            return new Placement(Optional.empty(), "target_not_water");
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BoatItem)) {
            return new Placement(Optional.empty(), "held_item_not_boat");
        }
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(water))
                > player.blockInteractionRange() * player.blockInteractionRange()) {
            return new Placement(Optional.empty(), "water_out_of_reach");
        }

        Set<UUID> existing = nearbyBoats(world, water).stream()
                .map(AbstractBoat::getUUID)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        LookAction.lookAt(player, Vec3.atCenterOf(water));
        ActionResult used = InteractAction.useItemInAir(player, InteractionHand.MAIN_HAND);
        if (used.isFailed()) {
            return new Placement(Optional.empty(), "boat_use_failed:" + used.reason());
        }

        Optional<AbstractBoat> placed = nearbyBoats(world, water).stream()
                .filter(boat -> !existing.contains(boat.getUUID()))
                .min(Comparator.comparingDouble(boat -> boat.distanceToSqr(Vec3.atCenterOf(water))));
        if (placed.isEmpty()) {
            return new Placement(Optional.empty(), "boat_not_created");
        }
        AbstractBoat boat = placed.get();
        BotLog.action(player, "boat_placed",
                "water", water.toShortString(),
                "boat_id", boat.getUUID());
        return new Placement(Optional.of(boat), "");
    }

    /** Uses the normal boat interaction path; successful interaction is verified by the caller. */
    public static ActionResult boardBoat(AIPlayerEntity player, AbstractBoat boat) {
        if (boat == null || !boat.isAlive()) {
            return ActionResult.failed("boat_unavailable");
        }
        if (player.getVehicle() == boat) {
            return ActionResult.SUCCESS;
        }
        if (player.distanceToSqr(boat) > BOARD_REACH * BOARD_REACH) {
            return ActionResult.failed("boat_out_of_reach");
        }
        LookAction.lookAt(player, boat.position().add(0.0D, boat.getBbHeight() * 0.5D, 0.0D));
        ActionResult result = InteractAction.useItemOnEntity(player, boat, InteractionHand.MAIN_HAND);
        if (result.isSuccess()) {
            BotLog.action(player, "boat_board_requested", "boat_id", boat.getUUID());
        }
        return result;
    }

    /** Clears all vanilla paddle inputs before a wait, dismount, or task transition. */
    public static void stopBoat(AbstractBoat boat) {
        if (boat != null && boat.isAlive()) {
            boat.setInput(false, false, false, false);
        }
    }

    private static java.util.List<AbstractBoat> nearbyBoats(ServerLevel world, BlockPos water) {
        return world.getEntitiesOfClass(AbstractBoat.class,
                new AABB(water).inflate(PLACE_SCAN_RADIUS, 2.0D, PLACE_SCAN_RADIUS),
                boat -> boat.isAlive());
    }
}
