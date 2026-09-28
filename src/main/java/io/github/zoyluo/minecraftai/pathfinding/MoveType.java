package io.github.zoyluo.minecraftai.pathfinding;

public enum MoveType {
    WALK,
    DIAGONAL,
    JUMP_UP,
    DROP_DOWN,
    DIG_THROUGH,
    PILLAR_UP,
    /** Horizontal bridging: places a support block one step ahead to cross an open-air gap. */
    BRIDGE
}
