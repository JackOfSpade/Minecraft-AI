package io.github.zoyluo.minecraftai.task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small durable accounting state for a time-boxed collection session.
 *
 * <p>Unlike an OreDig cursor, this carries no position, target, or in-flight action authority.
 * A restart must observe the world again before it moves or mines.  It does retain the elapsed
 * collection clock and the inventory baseline so a server restart cannot mint a fresh ten-minute
 * window (or forget yield obtained before the restart).</p>
 */
public record TimedCollectionCheckpoint(int durationTicks,
                                        int elapsedTicks,
                                        int inventoryBaseline,
                                        int collected) {
    private static final String SCHEMA = "1";
    private static final Map<String, String> EMPTY = Map.of();

    public TimedCollectionCheckpoint {
        if (durationTicks <= 0 || elapsedTicks < 0 || elapsedTicks > durationTicks
                || inventoryBaseline < 0 || collected < 0) {
            throw new IllegalArgumentException("invalid_timed_collection_checkpoint");
        }
    }

    public Map<String, String> encode() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", SCHEMA);
        values.put("duration_ticks", String.valueOf(durationTicks));
        values.put("elapsed_ticks", String.valueOf(elapsedTicks));
        values.put("inventory_baseline", String.valueOf(inventoryBaseline));
        values.put("collected", String.valueOf(collected));
        return Map.copyOf(values);
    }

    /** Missing state is handled by the caller as a legacy fresh session; present state is exact. */
    public static Optional<TimedCollectionCheckpoint> decode(Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? EMPTY : checkpoint;
        if (!values.keySet().equals(SetHolder.KEYS)) {
            return Optional.empty();
        }
        try {
            if (!SCHEMA.equals(values.get("schema"))) {
                return Optional.empty();
            }
            int duration = canonicalPositive(values.get("duration_ticks"));
            int elapsed = canonicalNonNegative(values.get("elapsed_ticks"));
            int baseline = canonicalNonNegative(values.get("inventory_baseline"));
            int collected = canonicalNonNegative(values.get("collected"));
            return elapsed > duration ? Optional.empty()
                    : Optional.of(new TimedCollectionCheckpoint(duration, elapsed, baseline, collected));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static int canonicalPositive(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0 || !String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non_canonical_positive_int");
        }
        return parsed;
    }

    private static int canonicalNonNegative(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0 || !String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non_canonical_non_negative_int");
        }
        return parsed;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> KEYS = java.util.Set.of(
                "schema", "duration_ticks", "elapsed_ticks", "inventory_baseline", "collected");
    }
}
