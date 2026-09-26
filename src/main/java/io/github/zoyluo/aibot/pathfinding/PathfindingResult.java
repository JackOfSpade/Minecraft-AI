package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;

import java.util.List;

public record PathfindingResult(
        List<Node> path,
        boolean success,
        FailureReason reason,
        int nodesExplored,
        long elapsedMs,
        BlockPos resolvedStart,
        BlockPos resolvedGoal
) {
    public static PathfindingResult success(List<Node> path, int nodesExplored, long elapsedMs) {
        List<Node> copy = List.copyOf(path);
        BlockPos resolvedStart = copy.isEmpty() ? null : copy.get(0).pos();
        BlockPos resolvedGoal = copy.isEmpty() ? null : copy.get(copy.size() - 1).pos();
        return new PathfindingResult(copy, true, FailureReason.NONE, nodesExplored, elapsedMs, resolvedStart, resolvedGoal);
    }

    public static PathfindingResult failure(FailureReason reason, int nodesExplored, long elapsedMs) {
        return new PathfindingResult(List.of(), false, reason, nodesExplored, elapsedMs, null, null);
    }
}
