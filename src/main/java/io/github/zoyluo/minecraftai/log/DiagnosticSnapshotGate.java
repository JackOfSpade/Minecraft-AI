package io.github.zoyluo.minecraftai.log;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Change detection and backoff for the periodic {@code diag_snapshot} line. Pure (the caller passes the server
 * tick), so the policy is unit-testable.
 *
 * <p>The snapshot used to be written for every bot every 2 s unconditionally: 57% of a two-hour session log,
 * most of it identical lines from an idle bot. The caller still evaluates the gate every
 * {@link DiagnosticLogger}-cadence tick (40), and a snapshot is now written only when:</p>
 * <ul>
 *   <li>it is the first one for the bot ({@code first});</li>
 *   <li>the <b>state</b> changed since the last written snapshot (task, task state/phase, goal/step, air, game
 *       mode, lava/submerged), at most once per {@link #MIN_STATE_TICKS} (2 s);</li>
 *   <li>the bot <b>moved</b> at least {@link #MOVE_DISTANCE_BLOCKS} blocks, or its <b>vitals/loadout</b>
 *       changed (health, food, held item, inventory), at most once per {@link #MIN_SOFT_TICKS} (5 s);</li>
 *   <li>otherwise a <b>heartbeat</b> every {@link #HEARTBEAT_TICKS} (30 s), so a quiet bot still leaves a trail.</li>
 * </ul>
 * The reason and the number of snapshots skipped since the previous one are reported on the written line, so
 * nothing about the suppression is silent. Discrete danger events ({@code diag_health_drop}, {@code diag_bot_died},
 * {@code diag_falling}, ...) are independent of this gate and still fire on the tick they happen.
 */
public final class DiagnosticSnapshotGate {
    public static final int MIN_STATE_TICKS = 40;
    public static final int MIN_SOFT_TICKS = 100;
    public static final int HEARTBEAT_TICKS = 600;
    public static final int MOVE_DISTANCE_BLOCKS = 3;

    /**
     * One observation. {@code state} is a preformatted string of everything that should force a snapshot when it
     * changes; {@code loadout} is the same for changes that may be reported more slowly.
     */
    public record Reading(int x, int y, int z, String state, String loadout) {
    }

    public record Decision(boolean emit, String reason, int suppressedSinceLast) {
        static Decision skip() {
            return new Decision(false, "", 0);
        }
    }

    private static final class Last {
        Reading reading;
        int tick;
        int suppressed;
    }

    private final Map<UUID, Last> last = new ConcurrentHashMap<>();

    public Decision decide(UUID botId, Reading now, int tick) {
        Last previous = last.get(botId);
        if (previous == null) {
            record(botId, now, tick, 0);
            return new Decision(true, "first", 0);
        }
        int since = tick - previous.tick;
        if (since < 0) {
            // The server tick counter went backwards (world reload): start over rather than stall for good.
            record(botId, now, tick, 0);
            return new Decision(true, "first", 0);
        }
        boolean stateChanged = !previous.reading.state().equals(now.state());
        boolean moved = squaredDistance(previous.reading, now)
                >= (long) MOVE_DISTANCE_BLOCKS * MOVE_DISTANCE_BLOCKS;
        boolean loadoutChanged = !previous.reading.loadout().equals(now.loadout());

        String reason = null;
        if (stateChanged && since >= MIN_STATE_TICKS) {
            reason = "state" + (moved ? "+moved" : "") + (loadoutChanged ? "+loadout" : "");
        } else if ((moved || loadoutChanged) && since >= MIN_SOFT_TICKS) {
            reason = (moved ? "moved" : "") + (moved && loadoutChanged ? "+" : "") + (loadoutChanged ? "loadout" : "");
        } else if (since >= HEARTBEAT_TICKS) {
            reason = "heartbeat";
        }
        if (reason == null) {
            previous.suppressed++;
            return Decision.skip();
        }
        int suppressed = previous.suppressed;
        record(botId, now, tick, 0);
        return new Decision(true, reason, suppressed);
    }

    public void clear(UUID botId) {
        last.remove(botId);
    }

    public void clearAll() {
        last.clear();
    }

    private void record(UUID botId, Reading reading, int tick, int suppressed) {
        Last entry = new Last();
        entry.reading = reading;
        entry.tick = tick;
        entry.suppressed = suppressed;
        last.put(botId, entry);
    }

    private static long squaredDistance(Reading a, Reading b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
