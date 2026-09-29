package io.github.zoyluo.minecraftai.baritone;

import baritone.api.utils.HostEnvironment;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place Baritone's background work runs (path searches, block rescans, region loads): a small, fixed set of
 * <b>daemon</b> threads shared by every bot, installed through {@link HostEnvironment#setExecutor} (patch 0012).
 *
 * <p>Why bounded and shared: upstream keeps an unbounded pool of non-daemon threads, i.e. one thread per concurrent search;
 * with N bots that is N CPU-bound A* searches fighting the server thread for cores. Here at most {@link #workers()} searches
 * run at once and the rest wait in a FIFO queue. The queue cannot grow without bound: {@code PathingBehavior} admits one
 * search per Baritone instance (one instance per bot), {@link BaritonePlanner} admits one per instance, and a
 * {@code MineProcess}/{@code FarmProcess} rescan is issued at most once per update interval.</p>
 *
 * <p>The clock of a search ({@code primaryTimeoutMS}) starts when a worker picks it up, not when it is queued, so queueing
 * delays a plan but never shortens its search. {@link #queueDepth()} and {@link #maxQueueWaitMillis()} make the delay visible.
 * The pool is never shut down (daemon threads, idle ones time out after 30 s), so a server restart in the same JVM
 * (GameTest, integrated server) needs no re-installation.</p>
 */
public final class BaritoneExecutor {
    private static final int WORKERS = workerCount();

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong MAX_WAIT_NANOS = new AtomicLong();
    private static volatile ThreadPoolExecutor pool;

    private BaritoneExecutor() {
    }

    /** Number of worker threads: half the cores, at least 2 and at most 4 (override with -Dminecraftai.baritone.workers=N). */
    public static int workers() {
        return WORKERS;
    }

    /** Creates the pool (once) and makes Baritone use it. Idempotent. */
    public static synchronized void install() {
        if (pool != null) {
            return;
        }
        ThreadPoolExecutor created = new ThreadPoolExecutor(WORKERS, WORKERS, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "minecraftai-baritone-" + THREAD_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        created.allowCoreThreadTimeOut(true);
        pool = created;
        HostEnvironment.setExecutor(new Instrumented(created));
    }

    /** Tasks waiting for a worker right now. */
    public static int queueDepth() {
        ThreadPoolExecutor current = pool;
        return current == null ? 0 : current.getQueue().size();
    }

    /** Tasks running on a worker right now. */
    public static int activeWorkers() {
        ThreadPoolExecutor current = pool;
        return current == null ? 0 : current.getActiveCount();
    }

    /** Longest time any task has waited in the queue since start-up (or the last {@link #resetStats}). */
    public static long maxQueueWaitMillis() {
        return TimeUnit.NANOSECONDS.toMillis(MAX_WAIT_NANOS.get());
    }

    public static long submitted() {
        return SUBMITTED.get();
    }

    public static long completed() {
        return COMPLETED.get();
    }

    public static void resetStats() {
        MAX_WAIT_NANOS.set(0L);
    }

    private static int workerCount() {
        Integer configured = Integer.getInteger("minecraftai.baritone.workers");
        if (configured != null && configured > 0) {
            return configured;
        }
        return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    /** Measures queue wait and turns an escaped exception into a log line instead of a silent, dead worker task. */
    private record Instrumented(Executor delegate) implements Executor {
        @Override
        public void execute(Runnable task) {
            long queuedAt = System.nanoTime();
            SUBMITTED.incrementAndGet();
            delegate.execute(() -> {
                MAX_WAIT_NANOS.accumulateAndGet(System.nanoTime() - queuedAt, Math::max);
                try {
                    task.run();
                } catch (Throwable t) {
                    BotLog.config("baritone_worker_task_failed", "error", String.valueOf(t));
                } finally {
                    COMPLETED.incrementAndGet();
                }
            });
        }
    }
}
