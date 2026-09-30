package io.github.zoyluo.minecraftai.log;

import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-bot tally of privileged-capability decisions, so a task can report at the end of its run
 * how many were allowed or denied without changing what {@code CapabilityRuntime} actually
 * decides. {@code CapabilityRuntime.decide} already logs {@code capability_decision} for every
 * individual decision (throttled); this only accumulates counts a caller can read back later,
 * e.g. for the {@code gather_summary} {@code capability_denials} field.
 */
public final class CapabilityTally {
    public static final CapabilityTally INSTANCE = new CapabilityTally();

    private final ConcurrentHashMap<UUID, Counts> counts = new ConcurrentHashMap<>();

    private CapabilityTally() {
    }

    /** Records one decision. Call this alongside {@code CapabilityRuntime.decide}, not instead of it. */
    public void record(UUID botId, PrivilegedCapability capability, boolean allowed) {
        if (botId == null) {
            return;
        }
        Counts c = counts.computeIfAbsent(botId, id -> new Counts());
        if (!allowed) {
            c.denied.incrementAndGet();
        }
    }

    /** Zeroes the tally for a bot; a task calls this when it starts so it only sees its own decisions. */
    public void reset(UUID botId) {
        if (botId != null) {
            counts.put(botId, new Counts());
        }
    }

    /** Reads the counts accumulated since the last {@link #reset}, without clearing them. */
    public Snapshot snapshot(UUID botId) {
        Counts c = counts.get(botId);
        return c == null ? new Snapshot(0) : new Snapshot(c.denied.get());
    }

    public void clear(UUID botId) {
        if (botId != null) {
            counts.remove(botId);
        }
    }

    public void clearAll() {
        counts.clear();
    }

    /** {@code denied}: privileged decisions that were denied. */
    public record Snapshot(int denied) {
    }

    private static final class Counts {
        private final AtomicInteger denied = new AtomicInteger();
    }
}
