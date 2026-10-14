package io.github.zoyluo.minecraftai.pathfinding;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds manual-teleport landing cells that do not put the arriving player inside a loaded
 * monster's acquisition range. A standable block alone is not a safe landing after a death:
 * landing beside the monsters that caused it simply begins another fight immediately.
 */
public final class TeleportSafety {
    /** Enough room for the largest normal vanilla hostile acquisition range plus a small buffer. */
    private static final double HOSTILE_QUERY_PADDING = 128.0D;
    private static final double MIN_HOSTILE_CLEARANCE = 16.0D;
    private static final double HOSTILE_RANGE_BUFFER = 2.0D;

    private TeleportSafety() {
    }

    /**
     * Finds the closest standable landing around {@code origin} that lies outside every nearby
     * loaded monster's follow range. The search is deliberately horizontal-first: a panel
     * teleport should choose a nearby surface/cave neighbour, not a remote floor in the same
     * column.
     */
    public static Optional<BlockPos> findNearestHostileSafeStandable(ServerLevel world,
                                                                       BlockPos origin,
                                                                       int horizontalRadius,
                                                                       int verticalDown,
                                                                       int verticalUp) {
        int radiusLimit = Math.max(0, horizontalRadius);
        AABB searchArea = new AABB(origin).inflate(radiusLimit + HOSTILE_QUERY_PADDING);
        List<Monster> hostiles = world.getEntitiesOfClass(Monster.class, searchArea,
                hostile -> hostile.isAlive() && !hostile.isRemoved());

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int radius = 0; radius <= radiusLimit; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Optional<BlockPos> standable = Standability.findNearestStandable(
                            world, origin.offset(dx, 0, dz), 0, verticalDown, verticalUp);
                    if (standable.isEmpty() || !clearOfHostiles(standable.get(), hostiles)) {
                        continue;
                    }
                    double distance = standable.get().distSqr(origin);
                    if (distance < bestDistance) {
                        best = standable.get().immutable();
                        bestDistance = distance;
                    }
                }
            }
            // Every unvisited horizontal ring is at least radius + 1 blocks from the origin.
            // Once the best full 3D distance is smaller, no later ring can improve it.
            double nextRingMinimumDistance = (double) (radius + 1) * (radius + 1);
            if (best != null && bestDistance < nextRingMinimumDistance) {
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean clearOfHostiles(BlockPos landing, List<Monster> hostiles) {
        Vec3 landingCenter = Vec3.atBottomCenterOf(landing);
        for (Monster hostile : hostiles) {
            double clearance = Math.max(MIN_HOSTILE_CLEARANCE,
                    hostile.getAttributeValue(Attributes.FOLLOW_RANGE) + HOSTILE_RANGE_BUFFER);
            if (hostile.distanceToSqr(landingCenter) <= clearance * clearance) {
                return false;
            }
        }
        return true;
    }
}
