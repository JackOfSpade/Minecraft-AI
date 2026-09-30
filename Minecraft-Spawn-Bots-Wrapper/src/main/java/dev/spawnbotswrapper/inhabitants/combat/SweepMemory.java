package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.MeleeGeometry.Box;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The last legally hit primary victim of each attacker, kept for the tick it was hit in only, so that a vanilla
 * sweeping-edge victim of the same swing (see {@link MeleeGeometry#isSweepVictim}) can be let through. One record per
 * attacker; a record older than the tick asked about is dropped when it is read, and the map is capped
 * ({@link #MAX_ATTACKERS}: stale records first, then everything) so it cannot grow without bound. Server thread only.
 */
public final class SweepMemory {
    /** Most attackers remembered at once. */
    public static final int MAX_ATTACKERS = 256;

    private record Primary(long tick, UUID victim, Box box) {
    }

    private final Map<UUID, Primary> primaries = new HashMap<>();

    /** Records that the attacker legally hit this victim (with this box) in tick {@code now}. */
    public void remember(long now, UUID attacker, UUID victim, Box box) {
        if (primaries.size() >= MAX_ATTACKERS && !primaries.containsKey(attacker)) {
            primaries.values().removeIf(p -> p.tick != now);
            if (primaries.size() >= MAX_ATTACKERS) {
                primaries.clear();
            }
        }
        primaries.put(attacker, new Primary(now, victim, box));
    }

    /**
     * The box of the victim the attacker legally hit in tick {@code now}, provided it is not {@code victim} itself; null
     * when there is none. A record of another tick is dropped.
     */
    public Box otherPrimaryThisTick(long now, UUID attacker, UUID victim) {
        Primary primary = primaries.get(attacker);
        if (primary == null) {
            return null;
        }
        if (primary.tick != now) {
            primaries.remove(attacker);
            return null;
        }
        return primary.victim.equals(victim) ? null : primary.box;
    }

    public int size() {
        return primaries.size();
    }

    public void clear() {
        primaries.clear();
    }
}
