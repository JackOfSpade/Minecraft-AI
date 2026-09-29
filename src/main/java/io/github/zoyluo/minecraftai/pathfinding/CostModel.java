package io.github.zoyluo.minecraftai.pathfinding;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

public final class CostModel {
    private CostModel() {
    }

    public static double stepCost(MoveType type, int fallHeight) {
        return switch (type) {
            case WALK -> 1.0D;
            // Diagonal ≈ √2: cheaper than two orthogonal steps (2.0), so A* prefers the shortcut, reducing zig-zagging and reaching the goal faster.
            case DIAGONAL -> 1.41D;
            case JUMP_UP -> 1.5D;
            case DROP_DOWN -> {
                if (fallHeight > MinecraftAiConfig.get().nav().maxSafeFall()) {
                    yield 1000.0D;
                }
                yield 0.5D + 0.3D * fallHeight;
            }
            case DIG_THROUGH -> 8.0D;
            // Pillaring up: high cost (consumes a block + slow); A* only picks this when the terrain can't otherwise be traversed.
            case PILLAR_UP -> 6.0D;
            // Bridging horizontally across a gap: same cost tier as PILLAR_UP -- both consume a block; only chosen when there is no natural foothold.
            case BRIDGE -> 6.0D;
        };
    }

    public static double stepCost(Node current, NeighborCandidate neighbor, ServerLevel world) {
        double cost = stepCost(neighbor.moveType(), neighbor.fallHeight());
        cost += turnPenalty(current, neighbor.pos());
        if (world.getFluidState(neighbor.pos()).is(FluidTags.WATER)) {
            cost *= 1.5D;
        }
        return cost;
    }

    public static double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dy = Math.abs(from.getY() - to.getY());
        int dz = Math.abs(from.getZ() - to.getZ());
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return straight + 1.41D * diagonal + 1.5D * dy;
    }

    private static double turnPenalty(Node current, BlockPos next) {
        Node parent = current.parent();
        if (parent == null) {
            return 0.0D;
        }
        Direction previous = horizontalDirection(parent.pos(), current.pos());
        Direction incoming = horizontalDirection(current.pos(), next);
        if (previous == null || incoming == null || previous == incoming) {
            return 0.0D;
        }
        return previous.getOpposite() == incoming ? 0.4D : 0.15D;
    }

    private static Direction horizontalDirection(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);
        if (dx != 0 && dz == 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0 && dx == 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }
}
