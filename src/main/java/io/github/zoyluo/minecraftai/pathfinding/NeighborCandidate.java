package io.github.zoyluo.minecraftai.pathfinding;

import net.minecraft.core.BlockPos;

record NeighborCandidate(BlockPos pos, MoveType moveType, int fallHeight) {
    NeighborCandidate {
        pos = pos.immutable();
    }
}
