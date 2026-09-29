package io.github.zoyluo.minecraftai.task;

import java.util.UUID;

/**
 * Decides whether a dropped item entity has really come to rest, for the ore-dig detour's drop
 * settle (design 4.9). A bare "velocity is tiny" test is not enough: an item popped out of a broken
 * block is momentarily near-stationary at the apex of its arc (~tick 5) while still airborne, and
 * would be reported settled far too early. An item counts as at rest when it
 * <ul>
 *   <li>is on the ground, or</li>
 *   <li>is touching water with a tiny velocity (floating items never report on-ground), or</li>
 *   <li>showed a tiny velocity on two successive observations (different ticks, same entity) -- an
 *       apex reading is a single-tick event, so two slow readings in a row mean it stopped moving.</li>
 * </ul>
 * Server-thread only, one instance per observer; remembers only the previous observation.
 */
final class DropRestGate {
    /** Squared speed (blocks/tick)^2 below which an item is "not moving". */
    static final double SLOW_SPEED_SQ = 1.0E-4D;

    private UUID lastId;
    private long lastTick = Long.MIN_VALUE;
    private boolean lastSlow;
    private boolean lastResult;

    boolean atRest(UUID itemId, long tick, boolean onGround, boolean touchingWater, double speedSq) {
        if (itemId.equals(lastId) && tick == lastTick) {
            return lastResult; // a second observation in the same tick is not a new sample
        }
        boolean slow = speedSq < SLOW_SPEED_SQ;
        boolean slowBefore = itemId.equals(lastId) && lastSlow;
        boolean result = onGround || (slow && touchingWater) || (slow && slowBefore);
        lastId = itemId;
        lastTick = tick;
        lastSlow = slow;
        lastResult = result;
        return result;
    }

    void reset() {
        lastId = null;
        lastTick = Long.MIN_VALUE;
        lastSlow = false;
        lastResult = false;
    }
}
