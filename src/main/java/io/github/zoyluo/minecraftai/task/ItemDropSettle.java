package io.github.zoyluo.minecraftai.task;

/**
 * How long the drop of a block that was just broken needs to come to rest, by vanilla's own physics
 * ({@code Block.popResource} and {@code ItemEntity.tick}), so that waiting for a drop nobody can see
 * ends when it has had time to land and not at some arbitrary count of ticks.
 */
final class ItemDropSettle {
    /** {@code ItemEntity#getDefaultGravity}: blocks per tick squared, applied before the item moves. */
    private static final double GRAVITY = 0.04D;
    /** {@code ItemEntity#tick}: the vertical velocity is multiplied by this after every move. */
    private static final double VERTICAL_DRAG = 0.98D;
    /** {@code Block.popResource} lets a drop leave its block with this upward velocity. */
    private static final double POP_VELOCITY = 0.2D;
    /** {@code ItemEntity#setDefaultPickUpDelay}: a dropped item cannot be picked up before this many ticks. */
    static final int PICKUP_DELAY_TICKS = 10;

    private ItemDropSettle() {
    }

    /**
     * Ticks until a drop that popped out of a block {@code heightBlocks} above the floor it falls to has
     * landed and can be picked up: the longer of its fall and vanilla's pickup delay. A drop that lands on
     * something higher than that floor (leaves, a ledge) lands sooner, so this is the latest it can settle
     * on a floor the bot stands on.
     */
    static int ticksToSettle(double heightBlocks) {
        double velocity = POP_VELOCITY;
        double height = Double.isFinite(heightBlocks) ? Math.max(0.0D, heightBlocks) : 0.0D;
        int ticks = 0;
        // The item rises first, then falls: it has landed once it is back down on the floor.
        do {
            velocity -= GRAVITY;
            height += velocity;
            velocity *= VERTICAL_DRAG;
            ticks++;
        } while (height > 0.0D);
        return Math.max(PICKUP_DELAY_TICKS, ticks);
    }
}
