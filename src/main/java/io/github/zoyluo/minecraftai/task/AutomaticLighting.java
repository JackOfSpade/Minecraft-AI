package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import net.minecraft.core.BlockPos;
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
    private static final int VERTICAL_SCAN_RADIUS = 1;

    enum Placement {
        /** No torch was needed, available, or safely reachable this tick. */
        NONE,
        /** A shield or another temporary use-owner must finish before retrying. */
        IN_PROGRESS,
        /** Vanilla confirmed that a torch was placed at the selected darkest cell. */
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
        return isPotentialHostileSpawnDark(world, pos) && !SurfaceCheck.isOnSurface(world, pos);
    }

    /**
     * Mining/exploration lighting has its own switch.  {@code night.autoLight} belongs to the
     * idle danger-watcher reflexes and must not silently turn off torches in an active mine.
     */
    static boolean miningTorchAutomationEnabled() {
        return Boolean.TRUE.equals(MinecraftAiConfig.get().mining().placeTorches());
    }

    /**
     * Places one torch for an actively mining/exploring bot without taking over its route or
     * moving it away from an ore drop.  "Reachable" here has the strict real-player meaning:
     * {@link BuildAction#canAcceptPlacementAt} has ray-proven a support face inside vanilla
     * interaction range.  It never starts a path, digs, or pillars merely to light an area.
     *
     * <p>The scan is deliberately small because this runs inside active tasks.  It only considers
     * observable floor cells that the bot can place on right now, excludes every open-sky/canopy
     * cell, and picks lowest raw light (nearest as a stable tie-break).  This makes the automatic
     * action useful without violating a mining task's pickup and movement ownership.</p>
     */
    static Placement tryPlaceDarkestReachable(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.TORCH) <= 0) {
            return Placement.NONE;
        }
        Level world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (!needsUndergroundTorch(world, feet)) {
            return Placement.NONE;
        }
        BlockPos target = darkestReachableFloor(bot);
        if (target == null) {
            return Placement.NONE;
        }
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (torchSlot < 0 || InventoryAction.equipFromSlot(bot, torchSlot) < 0) {
            return Placement.FAILED;
        }
        ActionResult placed = BuildAction.placeBlockAt(bot, target);
        if (placed.isInProgress()) {
            return Placement.IN_PROGRESS;
        }
        if (placed.isSuccess()) {
            BotLog.action(bot, "auto_torch",
                    "pos", target.toShortString(),
                    "raw_light", rawBrightness(world, target),
                    "block_light", world.getBrightness(LightLayer.BLOCK, target));
            return Placement.PLACED;
        }
        return Placement.FAILED;
    }

    /** Package-visible for focused tests and the automatic area-light task's safety reasoning. */
    static BlockPos darkestReachableFloor(AIPlayerEntity bot) {
        Level world = bot.level();
        BlockPos feet = bot.blockPosition();
        int radius = Math.max(1, (int) Math.ceil(bot.blockInteractionRange()));
        BlockPos best = null;
        int bestRaw = Integer.MAX_VALUE;
        int bestBlock = Integer.MAX_VALUE;
        long bestDistance = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -VERTICAL_SCAN_RADIUS; dy <= VERTICAL_SCAN_RADIUS; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = feet.offset(dx, dy, dz).immutable();
                    if (!isReachableDarkFloor(bot, candidate)) {
                        continue;
                    }
                    int raw = rawBrightness(world, candidate);
                    int block = world.getBrightness(LightLayer.BLOCK, candidate);
                    long xDistance = candidate.getX() - feet.getX();
                    long yDistance = candidate.getY() - feet.getY();
                    long zDistance = candidate.getZ() - feet.getZ();
                    long distance = xDistance * xDistance + yDistance * yDistance + zDistance * zDistance;
                    if (raw < bestRaw
                            || raw == bestRaw && block < bestBlock
                            || raw == bestRaw && block == bestBlock && distance < bestDistance) {
                        best = candidate;
                        bestRaw = raw;
                        bestBlock = block;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isReachableDarkFloor(AIPlayerEntity bot, BlockPos pos) {
        Level world = bot.level();
        return ObservableWorldQuery.canObserveCell(bot, pos)
                && ObservableWorldQuery.canObserveCollider(bot, pos.below())
                && world.getBlockState(pos).isAir()
                && !world.getBlockState(pos.below()).getCollisionShape(world, pos.below()).isEmpty()
                && isPotentialHostileSpawnDark(world, pos)
                && !SurfaceCheck.isOnSurface(world, pos)
                && BuildAction.canAcceptPlacementAt(bot, pos);
    }
}
