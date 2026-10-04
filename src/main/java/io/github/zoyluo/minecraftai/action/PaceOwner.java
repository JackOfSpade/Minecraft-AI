package io.github.zoyluo.minecraftai.action;

/**
 * Who asks for a pace (see {@link ActionPack#requestPace}). When two leases are valid at the same time the one with the higher
 * priority wins: an evade sprint beats a task's own walk pace, which beats the follow pace.
 */
public enum PaceOwner {
    FOLLOW(30),
    TASK(40),
    EVADE(60);

    private final int priority;

    PaceOwner(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
