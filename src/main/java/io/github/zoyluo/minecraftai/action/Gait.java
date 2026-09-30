package io.github.zoyluo.minecraftai.action;

/**
 * How a bot moves on the ground, slowest first (the order is the order of the constants, so {@code compareTo} ranks them):
 * sneaking (1.3 blocks/s, silent, will not walk off an edge), walking (4.3 blocks/s) and sprinting (5.6 blocks/s). The pace policy
 * ({@link PacePolicy}) picks one for every tick of controller-driven travel and both enforcers (the legacy
 * {@code ActionPack.onUpdate} and the Baritone {@code BotInputBridge}) write it into the bot's movement fields.
 */
public enum Gait {
    SNEAK,
    WALK,
    SPRINT;

    /** The slower of the two. */
    public static Gait min(Gait a, Gait b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** The faster of the two. */
    public static Gait max(Gait a, Gait b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * How much of a tick a tick at this gait counts against a route's clocks (deadlines, no-progress limits): a route that is
     * deliberately slower than the sprint its clocks were written for is not "late" for it. Sneaking covers 1/4.4 of the ground a
     * sprint does, walking 1/1.3 (the ratio of the vanilla speeds).
     */
    public double clockWeight() {
        return switch (this) {
            case SPRINT -> 1.0D;
            case WALK -> 1.0D / 1.3D;
            case SNEAK -> 1.0D / 4.4D;
        };
    }
}
