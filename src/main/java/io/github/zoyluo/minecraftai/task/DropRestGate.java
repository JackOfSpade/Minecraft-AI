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
 *   <li>showed a tiny velocity on two observations of the same entity at most
 *       {@link #MAX_SAMPLE_GAP_TICKS} ticks apart -- an apex reading is a single-tick event, so two
 *       close slow readings mean it stopped moving. Two slow readings far apart say nothing about the
 *       ticks between them (the item may have been falling in between), so they never combine.</li>
 * </ul>
 * Server-thread only, one instance per observer; remembers only the previous observation. The
 * observer calls {@link #reset()} whenever no item is being tracked any more.
 */
final class DropRestGate {
    /** Squared speed (blocks/tick)^2 below which an item is "not moving". */
    static final double SLOW_SPEED_SQ = 1.0E-4D;

    /** Two slow observations further apart than this many ticks are not "successive". */
    static final int MAX_SAMPLE_GAP_TICKS = 3;

    private UUID lastId;
    private long lastTick = Long.MIN_VALUE;
    private boolean lastSlow;
    private boolean lastResult;

    boolean atRest(UUID itemId, long tick, boolean onGround, boolean touchingWater, double speedSq) {
        if (itemId.equals(lastId) && tick == lastTick) {
            return lastResult; // a second observation in the same tick is not a new sample
        }
        boolean slow = speedSq < SLOW_SPEED_SQ;
        long gap = tick - lastTick; // MIN_VALUE start: overflows to a value that is never within the bound
        boolean slowBefore = itemId.equals(lastId) && lastSlow && gap > 0 && gap <= MAX_SAMPLE_GAP_TICKS;
        boolean result = onGround || (slow && touchingWater) || (slow && slowBefore);
        lastId = itemId;
        lastTick = tick;
        lastSlow = slow;
        lastResult = result;
        return result;
    }

    /** Forgets the tracked item (it vanished, was picked up, or the settle ended). */
    void reset() {
        lastId = null;
        lastTick = Long.MIN_VALUE;
        lastSlow = false;
        lastResult = false;
    }
}
