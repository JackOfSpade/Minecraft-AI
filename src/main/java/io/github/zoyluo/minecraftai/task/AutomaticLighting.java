package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/**
 * Vanilla-backed predicates for the bot's automatic underground lighting reflex.
 *
 * <p>This deliberately does not use the configurable comfort-light value.  A player deciding
 * whether a hostile can naturally spawn uses the dimension's own monster-light settings, so the
 * bot does too.  The raw-light check uses the largest value accepted by the dimension's random
 * test: if the current cell is at or below it, a natural hostile spawn is possible there on a
 * favorable roll.  Lighting that cell is therefore useful; anything brighter is left alone.</p>
 */
final class AutomaticLighting {

    /** A steady one-second cadence keeps the background check bounded and replayable. */
    private static final int PASSIVE_CHECK_INTERVAL_TICKS = 20;

    enum Placement {
        /** No torch was needed, available, or safely reachable this tick. */
        NONE,
        /** A shield or another temporary use-owner must finish before retrying. */
        IN_PROGRESS,
        /** Vanilla confirmed that a torch was placed at the bot's current floor cell. */
        PLACED,
        /** The candidate was valid when chosen, but vanilla declined the placement. */
        FAILED
    }

    private AutomaticLighting() {
    }

    /**
     * True when the dimension's native monster-spawn test could accept this cell for a hostile.
     * This is intentionally a possibility test, rather than sampling randomness: automatic
     * lighting must remove a real spawn risk before the next spawn attempt gets its lucky roll.
     */
    static boolean isPotentialHostileSpawnDark(Level world, BlockPos pos) {
        return world.getBrightness(LightLayer.BLOCK, pos)
                <= world.dimensionType().monsterSpawnBlockLightLimit()
                && rawBrightness(world, pos)
                <= world.dimensionType().monsterSpawnLightTest().getMaxValue();
    }

    /** The same ambient-darkness-aware raw light vanilla passes to natural-spawn checks. */
    static int rawBrightness(Level world, BlockPos pos) {
        return world.getMaxLocalRawBrightness(pos, world.getSkyDarken());
    }

    /**
     * Automatic torches are an underground safety action, never surface decoration.  A real roof
     * is required even if {@link Level#canSeeSky(BlockPos)} is fooled by leaves or another canopy.
     */
    static boolean needsUndergroundTorch(Level world, BlockPos pos) {
        // The native light read is constant-time while the roof test deliberately walks a
        // vertical column.  Bright locations are overwhelmingly common on the surface, so reject
        // them first without changing the underground-only policy.
        if (!isPotentialHostileSpawnDark(world, pos)) {
            return false;
        }
        // The Nether has no sky: its bedrock ceiling would classify every open lava sea and
        // cavern as underground. Light only genuinely enclosed passages there, where a torch
        // reduces an actual spawn risk without decorating open terrain or wasting the reserve.
        // The End has no comparable roof, so its open islands naturally take the regular
        // surface path below; enclosed End-city rooms and caves still have a real local roof.
        return world.dimension() == Level.NETHER
                ? isEnclosedNetherSpace(world, pos)
                : !SurfaceCheck.isOnSurface(world, pos);
    }

    /** A Nether cave/corridor has a nearby solid ceiling and at least one solid side wall. */
    private static boolean isEnclosedNetherSpace(Level world, BlockPos pos) {
        boolean ceiling = false;
        for (int dy = 1; dy <= 6; dy++) {
            if (!world.getBlockState(pos.above(dy)).getCollisionShape(world, pos.above(dy)).isEmpty()) {
                ceiling = true;
                break;
            }
        }
        if (!ceiling) {
            return false;
        }
        return !world.getBlockState(pos.north()).getCollisionShape(world, pos.north()).isEmpty()
                || !world.getBlockState(pos.south()).getCollisionShape(world, pos.south()).isEmpty()
                || !world.getBlockState(pos.east()).getCollisionShape(world, pos.east()).isEmpty()
                || !world.getBlockState(pos.west()).getCollisionShape(world, pos.west()).isEmpty();
    }

    /**
     * Mining/exploration lighting has its own switch.  {@code night.autoLight} belongs to the
     * idle danger-watcher reflexes and must not silently turn off torches in an active mine.
     */
    static boolean miningTorchAutomationEnabled() {
        return Boolean.TRUE.equals(MinecraftAiConfig.get().mining().placeTorches());
    }

    /**
     * Places one torch in the bot's own floor cell for an actively mining/exploring bot. It never
     * scans neighbouring cells, starts a path, digs, or pillars. The exact target still needs a
     * ray-proven vanilla support face through {@link BuildAction#canAcceptPlacementAt}; when that
     * proof fails, the automation simply defers instead of changing the task's movement.
     */
    static Placement tryPlaceTorchAtFeet(AIPlayerEntity bot) {
        // Choosing a hotbar slot is itself a player action.  In particular it can interrupt a
        // bow/food use before BuildAction gets the chance to report that the hand is busy, so
        // defer before touching inventory rather than only relying on BuildAction's shield guard.
        if (bot.isUsingItem()) {
            return Placement.IN_PROGRESS;
        }
        if (InventoryAction.countItem(bot, Items.TORCH) <= 0) {
            return Placement.NONE;
        }
        Level world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (!needsUndergroundTorch(world, feet)) {
            return Placement.NONE;
        }
        if (!world.getBlockState(feet).isAir() || !BuildAction.canAcceptPlacementAt(bot, feet)) {
            return Placement.NONE;
        }
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (torchSlot < 0 || InventoryAction.equipFromSlot(bot, torchSlot) < 0) {
            return Placement.FAILED;
        }
        ActionResult placed = BuildAction.placeBlockAt(bot, feet);
        if (placed.isInProgress()) {
            return Placement.IN_PROGRESS;
        }
        if (placed.isSuccess()) {
            BotLog.action(bot, "auto_torch",
                    "pos", feet.toShortString(),
                    "raw_light", rawBrightness(world, feet),
                    "block_light", world.getBrightness(LightLayer.BLOCK, feet));
            return Placement.PLACED;
        }
        return Placement.FAILED;
    }

    /**
     * Opportunistically lights an already-reachable underground floor while another task keeps
     * its route. This never starts a route, cancels one, digs, pillars, or pauses the task. It
     * also refuses to take over an in-flight local walk, step, mining action, inventory screen,
     * or held-item use. The selected hotbar slot and look direction are restored before the
     * task/control layer sees the next tick.
     *
     * <p>Unlike {@link #tryPlaceTorchAtFeet(AIPlayerEntity)}, the passive form restores an
     * exact full-hotbar swap before it returns. It can therefore use a carried main-inventory
     * torch without leaving a task's selected tool displaced; offhand promotion remains excluded
     * because that changes a second equipment owner.</p>
     */
    static Placement tryPlaceTorchAtFeetPassively(AIPlayerEntity bot, int serverTick) {
        if (Math.floorMod(serverTick, PASSIVE_CHECK_INTERVAL_TICKS) != 0) {
            return Placement.NONE;
        }
        return tryPlaceTorchAtFeetPassively(bot);
    }

    /** Performs the same non-interrupting probe immediately for a detected dark-trap safety event. */
    static Placement tryPlaceTorchAtFeetPassively(AIPlayerEntity bot) {
        if (!MinecraftAiConfig.get().night().autoLight()
                || !canLightWithoutInterrupting(bot)
                || InventoryAction.countItem(bot, Items.TORCH) <= 0) {
            return Placement.NONE;
        }
        Level world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (!needsUndergroundTorch(world, feet)) {
            return Placement.NONE;
        }
        if (!world.getBlockState(feet).isAir() || !BuildAction.canAcceptPlacementAt(bot, feet)) {
            return Placement.NONE;
        }
        int torchSlot = passiveTorchSourceSlot(bot);
        if (torchSlot < 0) {
            return Placement.NONE;
        }

        Inventory inventory = bot.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        float yaw = bot.getYRot();
        float pitch = bot.getXRot();
        boolean restoreInventorySwap = !Inventory.isHotbarSlot(torchSlot);
        int equippedSlot = -1;
        try {
            equippedSlot = InventoryAction.equipFromSlot(bot, torchSlot);
            if (equippedSlot < 0) {
                return Placement.FAILED;
            }
            ActionResult placed = BuildAction.placeBlockAt(bot, feet);
            if (placed.isInProgress()) {
                return Placement.IN_PROGRESS;
            }
            if (!placed.isSuccess()) {
                return Placement.FAILED;
            }
            BotLog.action(bot, "passive_auto_torch",
                    "pos", feet.toShortString(),
                    "raw_light", rawBrightness(world, feet),
                    "block_light", world.getBrightness(LightLayer.BLOCK, feet));
            return Placement.PLACED;
        } finally {
            // BuildAction must turn to the support face to use vanilla interaction. Give the
            // route and its task their exact inventory, slot, and aim back before their next tick.
            // equipFromSlot exchanges a main-inventory source with a hotbar destination; reversing
            // that exchange puts the remaining torch stack back in its source after vanilla has
            // consumed one, even when the hotbar had no empty slot.
            if (restoreInventorySwap && equippedSlot >= 0) {
                var sourceStack = inventory.getNonEquipmentItems().get(torchSlot);
                var equippedStack = inventory.getNonEquipmentItems().get(equippedSlot);
                inventory.getNonEquipmentItems().set(torchSlot, equippedStack);
                inventory.getNonEquipmentItems().set(equippedSlot, sourceStack);
            }
            inventory.setSelectedSlot(selectedSlot);
            inventory.setChanged();
            LookAction.setYawPitch(bot, yaw, pitch);
        }
    }

    private static boolean canLightWithoutInterrupting(AIPlayerEntity bot) {
        if (bot.isUsingItem()
                || ShieldGuard.holdsShield(bot)
                || BotInventoryScreenHandler.isScreenOpen(bot)) {
            return false;
        }
        var actions = bot.getActionPack();
        if (!actions.isWalkToIdle() || !actions.isMiningIdle() || !actions.stepIdle()) {
            return false;
        }
        // A Baritone route is explicitly allowed: the placement is in interaction range and does
        // not stop or replace it. A completely idle action pack is allowed too, so a quiet task
        // boundary can light its own cave without spawning a separate light_area task.
        return !actions.isPathExecutorIdle() || !actions.hasActiveActions();
    }

    /** Returns a non-disruptive torch source for passive lighting, or {@code -1} if none exists. */
    private static int passiveTorchSourceSlot(AIPlayerEntity bot) {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot <= 8; slot++) {
            if (inventory.getNonEquipmentItems().get(slot).is(Items.TORCH)) {
                return slot;
            }
        }
        // A main-inventory source is safe even with a full hotbar: the caller reverses the exact
        // equipFromSlot swap in its finally block, so the task's selected tool returns unchanged.
        for (int slot = 9; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (inventory.getNonEquipmentItems().get(slot).is(Items.TORCH)) {
                return slot;
            }
        }
        return -1;
    }

}
