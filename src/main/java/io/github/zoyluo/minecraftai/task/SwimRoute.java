package io.github.zoyluo.minecraftai.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/**
 * Bounded breadth-first search over the water (and its dry landings) around a swimming bot.  It
 * reuses {@link NavSafetyNet}'s physical-movement predicates -- an adjacent cell whose feet and head
 * are collision-free and that is either a water cell or a dry standable landing -- so every cell of
 * a returned route is one the verified {@code FakePlayerMotion} step primitives can actually take.
 * Used by follow to leave the water toward a player who walked out, to climb toward the nearest
 * breathable cell when a straight-up ascent is blocked, and to get around an obstacle that a
 * greedy step toward a swimmer cannot.
 */
final class SwimRoute {
    enum Goal {
        /** Nearest cell where the bot's head is out of the water. */
        AIR,
        /** Best dry standable landing, preferring one about {@code standoff} blocks from the target. */
        EXIT,
        /** Reachable swim cell closest to the target (must improve on the start). */
        APPROACH
    }

    private static final int MAX_NODES = 4000;
    private static final int RADIUS_H = 20;
    private static final int RADIUS_V = 20;

    private SwimRoute() {
    }

    /** @return the cells to step through, in order, excluding {@code start}; empty when none. */
    static Optional<List<BlockPos>> search(ServerLevel world, BlockPos start, BlockPos target,
                                           Goal goal, double standoff) {
        BlockPos origin = start.immutable();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        HashSet<BlockPos> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin);
        depth.put(origin, 0);

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double startDistance = origin.distSqr(target);
        int nodes = 0;
        while (!queue.isEmpty() && nodes < MAX_NODES) {
            BlockPos current = queue.removeFirst();
            nodes++;
            int currentDepth = depth.get(current);
            for (BlockPos candidate : NavSafetyNet.waterEscapeNeighbors(current)) {
                if (Math.abs(candidate.getX() - origin.getX()) > RADIUS_H
                        || Math.abs(candidate.getZ() - origin.getZ()) > RADIUS_H
                        || Math.abs(candidate.getY() - origin.getY()) > RADIUS_V
                        || !visited.add(candidate)) {
                    continue;
                }
                if (!NavSafetyNet.passableWaterColumn(world, candidate)) {
                    continue;
                }
                BlockPos cell = candidate.immutable();
                parent.put(cell, current);
                depth.put(cell, currentDepth + 1);
                boolean dry = NavSafetyNet.isDryStandableCell(world, cell);
                switch (goal) {
                    case AIR -> {
                        if (dry || hasAirAbove(world, cell)) {
                            return Optional.of(pathTo(parent, origin, cell));
                        }
                    }
                    case EXIT -> {
                        if (dry) {
                            double score = (currentDepth + 1)
                                    + 1.5D * Math.abs(Math.sqrt(cell.distSqr(target)) - standoff);
                            if (score < bestScore) {
                                bestScore = score;
                                best = cell;
                            }
                            continue;
                        }
                    }
                    case APPROACH -> {
                        if (!dry) {
                            double score = cell.distSqr(target) * 1000.0D + currentDepth + 1;
                            if (cell.distSqr(target) + 0.01D < startDistance && score < bestScore) {
                                bestScore = score;
                                best = cell;
                            }
                        }
                    }
                }
                if (NavSafetyNet.isWaterSwimCell(world, cell)) {
                    queue.addLast(cell);
                }
            }
        }
        return best == null ? Optional.empty() : Optional.of(pathTo(parent, origin, best));
    }

    /** A water cell whose head cell is free of water: standing there the bot breathes. */
    static boolean hasAirAbove(ServerLevel world, BlockPos cell) {
        return world.getFluidState(cell).is(FluidTags.WATER)
                && !world.getFluidState(cell.above()).is(FluidTags.WATER);
    }

    private static List<BlockPos> pathTo(Map<BlockPos, BlockPos> parent, BlockPos origin, BlockPos goal) {
        ArrayList<BlockPos> path = new ArrayList<>();
        BlockPos cursor = goal;
        while (cursor != null && !cursor.equals(origin)) {
            path.add(cursor);
            cursor = parent.get(cursor);
        }
        Collections.reverse(path);
        return path;
    }
}
