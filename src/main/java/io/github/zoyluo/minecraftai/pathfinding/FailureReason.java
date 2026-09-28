package io.github.zoyluo.minecraftai.pathfinding;

public enum FailureReason {
    NONE,
    NO_START,
    GOAL_UNREACHABLE,
    SEARCH_LIMIT,
    TIMEOUT,
    GOAL_NOT_STANDABLE
}
