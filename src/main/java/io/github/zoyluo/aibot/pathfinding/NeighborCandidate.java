package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;

record NeighborCandidate(BlockPos pos, MoveType moveType, int fallHeight) {
    NeighborCandidate {
        pos = pos.toImmutable();
    }
}
