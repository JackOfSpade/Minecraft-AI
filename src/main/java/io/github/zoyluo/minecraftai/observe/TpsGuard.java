package io.github.zoyluo.minecraftai.observe;

import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

public final class TpsGuard {
    public static final TpsGuard INSTANCE = new TpsGuard();

    private static final int WARMUP_SAMPLES = 40;
    private static final int NORMAL_CONTINUATION_SECONDS = 3;
    private static final int DEGRADED_CONTINUATION_SECONDS = 8;
    private static final int NORMAL_SCAN_INTERVAL = 1;
    private static final int DEGRADED_SCAN_INTERVAL = 20;
    private static final int DEGRADED_DANGER_SCAN_INTERVAL = 5;
    private static final int NON_CRITICAL_TASK_INTERVAL = 5;

    /**
     * Harness switch (GameTest and verify lanes): the guard never measures wall-clock tick times, so a busy test server cannot flip
     * every bot in the JVM into degraded scanning halfway through a scenario. A scenario that needs the degraded behaviour asks for it
     * for its own bot with {@link #forceDegradedForTests}. Production never sets this.
     */
    private static volatile boolean harnessPinned;
    private static final Set<UUID> FORCED_DEGRADED = ConcurrentHashMap.newKeySet();

    private long lastSampleNanos;
    private double averageTickMs = 20.0D;
    private final TpsDegradationLatch latch = new TpsDegradationLatch();
    private boolean lastDegraded;
    private int sampleCount;

    private TpsGuard() {
    }

    public static void setHarnessPinned(boolean pinned) {
        harnessPinned = pinned;
    }

    public static boolean isForcedDegraded(UUID botId) {
        return FORCED_DEGRADED.contains(botId);
    }

    /** Test hook: treats one bot as running on a degraded server (or not), leaving every other bot alone. */
    public static void forceDegradedForTests(UUID botId, boolean degraded) {
        if (degraded) {
            FORCED_DEGRADED.add(botId);
        } else {
            FORCED_DEGRADED.remove(botId);
        }
    }

    public synchronized void tick(MinecraftServer server) {
        if (harnessPinned) {
            return;
        }
        long now = System.nanoTime();
        if (lastSampleNanos == 0L) {
            lastSampleNanos = now;
            return;
        }
        double elapsedMs = Math.max(0.0D, (now - lastSampleNanos) / 1_000_000.0D);
        lastSampleNanos = now;
        if (elapsedMs > 200.0D) {
            return;
        }
        sampleCount++;
        averageTickMs = averageTickMs * 0.95D + elapsedMs * 0.05D;
        if (sampleCount < WARMUP_SAMPLES) {
            return;
        }
        int dwell = latch.samplesInState();
        int relearnsBefore = latch.relearnCount();
        boolean flipped = latch.update(averageTickMs);
        if (latch.relearnCount() != relearnsBefore) {
            // Rare (at most once per RELEARN_SAMPLES): the degraded server settled at a steady level below
            // the enter level, so that level became the new baseline (see TpsDegradationLatch).
            BotLog.profile(null, "tps_guard_baseline_relearned",
                    "baseline_ms", String.format(java.util.Locale.ROOT, "%.2f", latch.baselineMs()),
                    "enter_level_ms", String.format(java.util.Locale.ROOT, "%.2f", latch.enterLevel()),
                    "exit_level_ms", String.format(java.util.Locale.ROOT, "%.2f", latch.exitLevel()));
        }
        if (flipped) {
            // Only real transitions are logged: the latch's dead band and minimum dwell keep this from
            // flapping around the threshold (it used to flip several times per second).
            lastDegraded = latch.degraded();
            BotLog.profile(null, "tps_guard_state",
                    "degraded", lastDegraded,
                    "avg_tick_ms", String.format(java.util.Locale.ROOT, "%.2f", averageTickMs),
                    "estimated_tps", String.format(java.util.Locale.ROOT, "%.2f", estimatedTps()),
                    "previous_state_ticks", dwell);
        }
    }

    public synchronized boolean degraded(MinecraftServer server) {
        return lastDegraded;
    }

    /** The degraded verdict for one bot: the server-wide one, or a scenario's forced one for that bot. */
    public synchronized boolean degraded(UUID botId) {
        return lastDegraded || FORCED_DEGRADED.contains(botId);
    }

    public synchronized int continuationDelaySeconds() {
        return lastDegraded ? DEGRADED_CONTINUATION_SECONDS : NORMAL_CONTINUATION_SECONDS;
    }

    public synchronized int scanInterval() {
        return lastDegraded ? DEGRADED_SCAN_INTERVAL : NORMAL_SCAN_INTERVAL;
    }

    public synchronized int dangerScanInterval() {
        return lastDegraded ? DEGRADED_DANGER_SCAN_INTERVAL : NORMAL_SCAN_INTERVAL;
    }

    public synchronized int scanInterval(UUID botId) {
        return degraded(botId) ? DEGRADED_SCAN_INTERVAL : NORMAL_SCAN_INTERVAL;
    }

    public synchronized int dangerScanInterval(UUID botId) {
        return degraded(botId) ? DEGRADED_DANGER_SCAN_INTERVAL : NORMAL_SCAN_INTERVAL;
    }

    public synchronized boolean shouldTickNonCriticalTask(MinecraftServer server, UUID botId) {
        return !degraded(botId) || server.getTickCount() % NON_CRITICAL_TASK_INTERVAL == 0;
    }

    public synchronized boolean shouldTickNonCriticalTask(MinecraftServer server) {
        return !lastDegraded || server.getTickCount() % NON_CRITICAL_TASK_INTERVAL == 0;
    }

    public synchronized Snapshot snapshot(MinecraftServer server) {
        return new Snapshot(averageTickMs, estimatedTps(), lastDegraded, continuationDelaySeconds(), scanInterval());
    }

    public synchronized void reset() {
        lastSampleNanos = 0L;
        averageTickMs = 20.0D;
        lastDegraded = false;
        latch.reset();
        sampleCount = 0;
    }

    private double estimatedTps() {
        return Math.min(20.0D, 1000.0D / Math.max(1.0D, averageTickMs));
    }

    public record Snapshot(double averageTickMs, double estimatedTps, boolean degraded, int continuationDelaySeconds, int scanInterval) {
    }
}
