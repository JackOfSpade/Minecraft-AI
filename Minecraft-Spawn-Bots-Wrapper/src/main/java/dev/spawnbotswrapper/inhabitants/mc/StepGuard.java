package dev.spawnbotswrapper.inhabitants.mc;

import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Runs one unit of per-tick work so that a bug in it can never take the server tick down.
 * <p>
 * A step that fails every single tick would otherwise write twenty stack traces a second, so failures are
 * reported on the first and second occurrence and after that only every {@code reportEvery}-th, each with
 * the running count. Catches {@link RuntimeException} and {@link LinkageError} (a missing upstream class or
 * method after a PvP BOT update surfaces as the latter); genuine JVM errors such as running out of memory
 * still propagate.
 */
public final class StepGuard {
    private final BiConsumer<String, Throwable> sink;
    private final int reportEvery;
    private final Map<String, Long> failures = new ConcurrentHashMap<>();

    /**
     * @param sink        receives a one-line message and the cause for every failure that is reported
     * @param reportEvery after the second failure of a step, report every this-many-th one
     */
    public StepGuard(BiConsumer<String, Throwable> sink, int reportEvery) {
        this.sink = sink;
        this.reportEvery = Math.max(1, reportEvery);
    }

    /** A guard that reports through {@code log} at ERROR level, then every 1200th repeat (about a minute of ticks). */
    public static StepGuard logging(Logger log) {
        return new StepGuard((message, cause) -> log.error(message, cause), 1200);
    }

    /** Runs {@code body}; returns false (after reporting) if it threw. */
    public boolean run(String step, Runnable body) {
        try {
            body.run();
            return true;
        } catch (RuntimeException | LinkageError e) {
            report(step, e);
            return false;
        }
    }

    /** Counts a failure of {@code step} and reports it unless it is a repeat that is being throttled. */
    public void report(String step, Throwable cause) {
        long n = failures.merge(step, 1L, Long::sum);
        if (n <= 2 || n % reportEvery == 0) {
            String suffix = n == 1 ? "" : " (failure #" + n + ")";
            sink.accept("PvP BOT Inhabitants: step '" + step + "' failed" + suffix
                    + "; continuing without it this tick", cause);
        }
    }

    /** How many times {@code step} has failed so far. */
    public long failures(String step) {
        return failures.getOrDefault(step, 0L);
    }
}
