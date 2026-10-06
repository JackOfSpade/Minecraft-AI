package io.github.zoyluo.minecraftai.action;

import java.util.OptionalInt;
import net.minecraft.world.phys.AABB;

/**
 * Where a standing player collects a dropped item: vanilla's own pickup box ({@code Player#aiStep}), the body's
 * box grown by a block to each side and half a block up and down. An item that rests on a ledge beside a pillar
 * is picked up once the pillar is high enough for that box to meet it, with no step taken off the pillar; this
 * is the geometry that says how high.
 */
public final class PickupReach {
    /** The growth of the body's box on each horizontal axis in {@code Player#aiStep}. */
    static final double SIDE = 1.0D;
    /** The growth of the body's box up and down in {@code Player#aiStep}. */
    static final double VERTICAL = 0.5D;

    /** An item dropped by a block cannot be collected for this many ticks ({@code ItemEntity#setDefaultPickUpDelay}). */
    static final int PICKUP_DELAY_TICKS = 10;
    /** The upward speed an item is popped with, and the pull and drag on it every tick ({@code ItemEntity}). */
    private static final double POP_SPEED = 0.2D;
    private static final double GRAVITY = 0.04D;
    private static final double DRAG = 0.98D;

    private PickupReach() {
    }

    /**
     * The ticks after which an item dropped by a block {@code height} blocks above the surface it lands on has
     * landed and may be collected there: its fall under vanilla's gravity and drag from the kick it is popped with,
     * or the pickup delay if that is longer. An item not seen or collected by then is not on its way down.
     */
    public static int settledTicks(double height) {
        double y = 0.0D;
        double speed = POP_SPEED;
        int ticks = 0;
        while (y > -height) {
            speed -= GRAVITY;
            y += speed;
            speed *= DRAG;
            ticks++;
        }
        return Math.max(ticks, PICKUP_DELAY_TICKS);
    }

    /** The box an item must meet for a player whose body occupies {@code body} to collect it. */
    static AABB pickupBox(AABB body) {
        return body.inflate(SIDE, VERTICAL, SIDE);
    }

    /**
     * The lowest level from {@code fromFeetY} to {@code toFeetY} (inclusive) at which a body standing upright
     * in the cell column centred on {@code x}, {@code z} collects {@code item}, if any.
     */
    static OptionalInt lowestMeetingLevel(double x, double z, int fromFeetY, int toFeetY,
                                          double width, double height, AABB item) {
        double half = width / 2.0D;
        for (int feetY = fromFeetY; feetY <= toFeetY; feetY++) {
            AABB body = new AABB(x - half, feetY, z - half, x + half, feetY + height, z + half);
            if (pickupBox(body).intersects(item)) {
                return OptionalInt.of(feetY);
            }
            if (pickupBox(body).minY > item.maxY) {
                break; // every higher level is farther above the item
            }
        }
        return OptionalInt.empty();
    }
}
