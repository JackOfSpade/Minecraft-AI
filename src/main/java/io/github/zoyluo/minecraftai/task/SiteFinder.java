package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Optional;
import java.util.OptionalInt;

public final class SiteFinder {
    private static final double MAX_SCORE = 2.0D;

    private SiteFinder() {
    }

    public static Optional<BlockPos> findSite(AIPlayerEntity bot, int footprintX, int footprintZ, int searchRadius) {
        return findSite(bot, footprintX, footprintZ, searchRadius, false);
    }

    // lenient=true (paired with BuildTask's flatten step): naturally undulating terrain rarely has
    // ready-made flat ground, so relax the search and pick the flattest available point
    // (height difference <=5, not standing in water/void, within +-8 of the spawn surface); the
    // FLATTEN phase then digs down high spots and fills in low ones to level it.
    // lenient=false stays strict (on a flat canvas / when no flattening is done, don't pick sloped
    // ground carelessly - zero regressions).
    public static Optional<BlockPos> findSite(AIPlayerEntity bot, int footprintX, int footprintZ, int searchRadius, boolean lenient) {
        if (footprintX <= 0 || footprintZ <= 0 || searchRadius < 0) {
            return Optional.empty();
        }
        boolean hiddenScanAllowed = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "site_finder").allowed();
        if (!hiddenScanAllowed) {
            return findObservableSite(bot, footprintX, footprintZ, searchRadius, lenient);
        }
        ServerWorld world = bot.getEntityWorld();
        BlockPos origin = bot.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int ySpread = lenient ? 8 : 4;
        int maxRange = lenient ? 5 : 2;
        double scoreCap = lenient ? Double.MAX_VALUE : MAX_SCORE;
        int originSurface = standableY(world, origin.getX(), origin.getZ(), origin.getY()).orElse(origin.getY());
        for (int x = origin.getX() - searchRadius; x <= origin.getX() + searchRadius - footprintX + 1; x++) {
            for (int z = origin.getZ() - searchRadius; z <= origin.getZ() + searchRadius - footprintZ + 1; z++) {
                OptionalInt maybeY = standableY(world, x, z, originSurface);
                if (maybeY.isEmpty()) {
                    continue;
                }
                int y = maybeY.getAsInt();
                BlockPos anchor = new BlockPos(x, y, z);
                if (!isObservableFootprint(bot, anchor, footprintX, footprintZ)) {
                    continue;
                }
                if (Math.abs(y - originSurface) > ySpread) {
                    continue;
                }
                double score = flatnessScore(world, anchor, footprintX, footprintZ, maxRange);
                if (score > scoreCap) {
                    continue;
                }
                if (!hasUsableStand(world, anchor, footprintX, footprintZ)) {
                    continue;
                }
                double distancePenalty = anchor.getSquaredDistance(origin) / 256.0D;
                double total = score + distancePenalty;
                if (total < bestScore) {
                    bestScore = total;
                    best = anchor.toImmutable();
                }
            }
        }
        if (best == null) {
            // Diagnose the rejection reasons behind no_flat_site: run the same flatnessScore-style
            // check on every standable candidate footprint and tally the first rejection reason for
            // each -- okFlat = would actually be acceptable (flat and clean; if >0, best=null has
            // some other cause) / obstruct = feet or head block is non-air (clearable obstructions
            // like grass/flowers/snow -- flatten's CLEAR stage clears these anyway, so it's safe to
            // relax) / fluid = ground or feet block has water (a water body, harder to handle) /
            // groundAir = floating with nothing underneath. Use this to decide which direction to fix.
            int okFlat = 0, obstruct = 0, fluid = 0, groundAir = 0, tooSteep = 0;
            for (int x = origin.getX() - searchRadius; x <= origin.getX() + searchRadius - footprintX + 1; x++) {
                for (int z = origin.getZ() - searchRadius; z <= origin.getZ() + searchRadius - footprintZ + 1; z++) {
                    OptionalInt my = standableY(world, x, z, originSurface);
                    if (my.isEmpty() || Math.abs(my.getAsInt() - originSurface) > ySpread) {
                        continue;
                    }
                    BlockPos anchor = new BlockPos(x, my.getAsInt(), z);
                    if (!isObservableFootprint(bot, anchor, footprintX, footprintZ)) {
                        continue;
                    }
                    switch (footprintReject(world, anchor, footprintX, footprintZ, maxRange)) {
                        case 0 -> okFlat++;
                        case 1 -> obstruct++;
                        case 2 -> fluid++;
                        case 3 -> groundAir++;
                        case 4 -> tooSteep++;
                        default -> { }
                    }
                }
            }
            BotLog.action(bot, "no_flat_site_diag",
                    "okFlat", okFlat, "obstruct", obstruct, "fluid", fluid,
                    "groundAir", groundAir, "tooSteep", tooSteep,
                    "maxRange", maxRange, "ySpread", ySpread, "searchRadius", searchRadius);
        }
        return Optional.ofNullable(best);
    }

    /** Strict-survival site selection: visibility is checked before every terrain-state read. */
    private static Optional<BlockPos> findObservableSite(AIPlayerEntity bot,
                                                         int footprintX,
                                                         int footprintZ,
                                                         int searchRadius,
                                                         boolean lenient) {
        BlockPos origin = bot.getBlockPos();
        int ySpread = lenient ? 8 : 4;
        int maxRange = lenient ? 5 : 2;
        double scoreCap = lenient ? Double.MAX_VALUE : MAX_SCORE;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int observableSurfaces = 0;
        int acceptableFootprints = 0;
        int usableApproaches = 0;
        for (int x = origin.getX() - searchRadius; x <= origin.getX() + searchRadius - footprintX + 1; x++) {
            for (int z = origin.getZ() - searchRadius; z <= origin.getZ() + searchRadius - footprintZ + 1; z++) {
                OptionalInt maybeY = observableStandableY(bot, x, z, origin.getY(), ySpread);
                if (maybeY.isEmpty()) {
                    continue;
                }
                observableSurfaces++;
                BlockPos anchor = new BlockPos(x, maybeY.getAsInt(), z);
                double score = observableFlatnessScore(bot, anchor, footprintX, footprintZ, maxRange);
                if (score > scoreCap) {
                    continue;
                }
                acceptableFootprints++;
                if (!hasObservableUsableStand(bot, anchor, footprintX, footprintZ)) {
                    continue;
                }
                usableApproaches++;
                double total = score + anchor.getSquaredDistance(origin) / 256.0D;
                if (total < bestScore) {
                    bestScore = total;
                    best = anchor.toImmutable();
                }
            }
        }
        if (best == null) {
            BotLog.action(bot, "no_flat_site_diag",
                    "mode", "observable_only",
                    "observableSurfaces", observableSurfaces,
                    "acceptableFootprints", acceptableFootprints,
                    "usableApproaches", usableApproaches,
                    "footprint", footprintX + "x" + footprintZ,
                    "maxRange", maxRange,
                    "ySpread", ySpread,
                    "searchRadius", searchRadius);
        }
        return Optional.ofNullable(best);
    }

    private static double observableFlatnessScore(AIPlayerEntity bot,
                                                  BlockPos anchor,
                                                  int footprintX,
                                                  int footprintZ,
                                                  int maxRange) {
        int[][] surfaces = new int[footprintX][footprintZ];
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        double sum = 0.0D;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                OptionalInt surface = observableStandableY(
                        bot, anchor.getX() + dx, anchor.getZ() + dz, anchor.getY(), maxRange);
                if (surface.isEmpty()) {
                    return Double.MAX_VALUE;
                }
                int y = surface.getAsInt();
                surfaces[dx][dz] = y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                sum += y;
            }
        }
        if (maxY - minY > maxRange) {
            return Double.MAX_VALUE;
        }
        int count = footprintX * footprintZ;
        double mean = sum / count;
        double variance = 0.0D;
        for (int[] row : surfaces) {
            for (int y : row) {
                double delta = y - mean;
                variance += delta * delta;
            }
        }
        return variance / count + (maxY - minY);
    }

    private static boolean hasObservableUsableStand(AIPlayerEntity bot,
                                                    BlockPos anchor,
                                                    int footprintX,
                                                    int footprintZ) {
        for (int dx = -1; dx <= footprintX; dx++) {
            if (observableStandableY(bot, anchor.getX() + dx, anchor.getZ() - 1, anchor.getY(), 4).isPresent()
                    || observableStandableY(bot, anchor.getX() + dx,
                    anchor.getZ() + footprintZ, anchor.getY(), 4).isPresent()) {
                return true;
            }
        }
        for (int dz = 0; dz < footprintZ; dz++) {
            if (observableStandableY(bot, anchor.getX() - 1, anchor.getZ() + dz, anchor.getY(), 4).isPresent()
                    || observableStandableY(bot, anchor.getX() + footprintX,
                    anchor.getZ() + dz, anchor.getY(), 4).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static OptionalInt observableStandableY(AIPlayerEntity bot,
                                                    int x,
                                                    int z,
                                                    int preferredY,
                                                    int verticalRange) {
        ServerWorld world = bot.getEntityWorld();
        int range = Math.max(0, verticalRange);
        for (int delta = 0; delta <= range; delta++) {
            int high = preferredY + delta;
            if (isObservableStandable(bot, world, x, high, z)) {
                return OptionalInt.of(high);
            }
            int low = preferredY - delta;
            if (delta > 0 && isObservableStandable(bot, world, x, low, z)) {
                return OptionalInt.of(low);
            }
        }
        return OptionalInt.empty();
    }

    private static boolean isObservableStandable(AIPlayerEntity bot,
                                                 ServerWorld world,
                                                 int x,
                                                 int y,
                                                 int z) {
        if (y <= world.getBottomY() || y >= world.getBottomY() + world.getHeight() - 1) {
            return false;
        }
        BlockPos feet = new BlockPos(x, y, z);
        return ObservableWorldQuery.canObserveBlock(bot, feet.down())
                && ObservableWorldQuery.canObserveCell(bot, feet)
                && ObservableWorldQuery.canObserveCell(bot, feet.up())
                && Standability.isStandable(world, feet);
    }

    private static boolean isObservableFootprint(AIPlayerEntity bot,
                                                 BlockPos anchor,
                                                 int footprintX,
                                                 int footprintZ) {
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                BlockPos ground = anchor.add(dx, -1, dz);
                if (!ObservableWorldQuery.canObserveBlock(bot, ground)) {
                    return false;
                }
            }
        }
        return true;
    }

    // Diagnostic helper: runs the same column-by-column check as flatnessScore over the footprint and
    // returns the [first] rejection code encountered --
    // 0 = clean and acceptable / 1 = feet or head block is non-air (clearable obstruction) /
    // 2 = ground or feet block has fluid (water) / 3 = ground block is air (floating) /
    // 4 = height difference > maxRange / -1 = not standable.
    private static int footprintReject(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ, int maxRange) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                OptionalInt my = standableY(world, x, z, anchor.getY());
                if (my.isEmpty()) {
                    return -1;
                }
                int surfaceY = my.getAsInt();
                BlockPos feet = new BlockPos(x, surfaceY, z);
                if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                    return 1; // solid obstruction with a collision box (once relaxed, collision-less vegetation like grass no longer counts as an obstruction)
                }
                BlockPos ground = feet.down();
                if (!world.getFluidState(ground).isEmpty() || !world.getFluidState(feet).isEmpty()) {
                    return 2;
                }
                if (world.getBlockState(ground).isAir()) {
                    return 3;
                }
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
            }
        }
        return (maxY - minY) > maxRange ? 4 : 0;
    }

    public static double flatnessScore(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ) {
        return flatnessScore(world, anchor, footprintX, footprintZ, 2);
    }

    public static double flatnessScore(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ, int maxRange) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        double sum = 0.0D;
        int count = 0;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                OptionalInt maybeY = standableY(world, x, z, anchor.getY());
                if (maybeY.isEmpty()) {
                    return Double.MAX_VALUE;
                }
                int surfaceY = maybeY.getAsInt();
                BlockPos feet = new BlockPos(x, surfaceY, z);
                // Relaxed: accept when the feet/head block is "clearable vegetation" (grass/flowers/
                // ferns/snow layers -- blocks with no collision box) -- the bot can stand right
                // through them (same empty-collision-box test as isStandable), and flatten's CLEAR
                // stage will clear them out anyway. The original "must be strict air" rule was
                // misjudging large amounts of flat grassland as no_flat_site (observed on seeds
                // 8888/31337/7777777: obstruct=700+, terrain spread=0 [flat], fluid=0 [dry]).
                // Solid obstructions (with a collision box) are still rejected (the bot can't stand
                // through them without pre-clearing); water is rejected by the fluid check below
                // (water-surface sites are not accepted).
                if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                    return Double.MAX_VALUE;
                }
                BlockPos ground = feet.down();
                FluidState groundFluid = world.getFluidState(ground);
                FluidState feetFluid = world.getFluidState(feet);
                if (!groundFluid.isEmpty() || !feetFluid.isEmpty() || world.getBlockState(ground).isAir()) {
                    return Double.MAX_VALUE;
                }
                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                sum += surfaceY;
                count++;
            }
        }
        if (count == 0 || maxY - minY > maxRange) {
            return Double.MAX_VALUE;
        }
        double mean = sum / count;
        double variance = 0.0D;
        for (int dx = 0; dx < footprintX; dx++) {
            for (int dz = 0; dz < footprintZ; dz++) {
                int surfaceY = standableY(world, anchor.getX() + dx, anchor.getZ() + dz, anchor.getY()).orElse(anchor.getY());
                double delta = surfaceY - mean;
                variance += delta * delta;
            }
        }
        return variance / count + (maxY - minY);
    }

    private static boolean hasUsableStand(ServerWorld world, BlockPos anchor, int footprintX, int footprintZ) {
        Standability.clearCache();
        for (int dx = -1; dx <= footprintX; dx++) {
            if (standableSurface(world, anchor.getX() + dx, anchor.getZ() - 1)
                    || standableSurface(world, anchor.getX() + dx, anchor.getZ() + footprintZ)) {
                return true;
            }
        }
        for (int dz = 0; dz < footprintZ; dz++) {
            if (standableSurface(world, anchor.getX() - 1, anchor.getZ() + dz)
                    || standableSurface(world, anchor.getX() + footprintX, anchor.getZ() + dz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean standableSurface(ServerWorld world, int x, int z) {
        return standableY(world, x, z, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)).isPresent();
    }

    private static OptionalInt standableY(ServerWorld world, int x, int z, int preferredY) {
        int heightmapY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        OptionalInt direct = firstStandable(world, x, z, preferredY, heightmapY, heightmapY + 1, heightmapY - 1);
        if (direct.isPresent()) {
            return direct;
        }
        int minY = Math.max(world.getBottomY() + 1, Math.min(preferredY, heightmapY) - 4);
        int maxY = Math.min(world.getBottomY() + world.getHeight() - 2, Math.max(preferredY, heightmapY) + 4);
        for (int y = minY; y <= maxY; y++) {
            if (Standability.isStandable(world, new BlockPos(x, y, z))) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt firstStandable(ServerWorld world, int x, int z, int... ys) {
        for (int y : ys) {
            if (y > world.getBottomY() && y < world.getBottomY() + world.getHeight() - 1
                    && Standability.isStandable(world, new BlockPos(x, y, z))) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }
}
