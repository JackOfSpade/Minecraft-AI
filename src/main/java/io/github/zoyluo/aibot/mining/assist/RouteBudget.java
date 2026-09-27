package io.github.zoyluo.aibot.mining.assist;

/**
 * Server-wide budget for the expensive part of a detour: starting a route (mining-assist design 4.3, C1).
 * {@code startSurfacePathTo(goal, minY, anchor)} runs a walk search of up to 50 ms and, for a contract-bound
 * route, a second return-proof search of up to 50 ms, so a failing start can eat two thirds of a tick. Cheap
 * starts should be common and one failing double search should block further starts for about a second.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li>A bucket of milliseconds: capacity {@code route.bucketMs} (100), refill {@value #REFILL_MS_PER_TICK} ms
 *       per server tick, lazily on the next call. It starts full.</li>
 *   <li>A start needs at least {@value #MIN_START_MS} ms in the bucket ({@link #canStart}) and deducts the
 *       measured cost afterwards ({@link #noteStart}). The balance may go negative down to
 *       {@code -capacity}, so a failing 100 ms double search taken at 30 ms leaves -70 ms and blocks starts for
 *       (30 + 70) / 5 = 20 ticks.</li>
 *   <li>At most one start per server tick, across all bots (a token): after {@link #noteStart} at tick T,
 *       {@code canStart(T, ...)} is false.</li>
 *   <li>{@code deterministic}: only the one-start-per-tick token applies; the bucket is neither consulted nor
 *       deducted (design 2.1: no millisecond budget in the harness).</li>
 *   <li>Drop-chase starts use the same bucket as approach starts (design 4.9). The mandatory return start does NOT ask
 *       {@link #canStart}: the return is the safety path and must not be starved by other bots (P1 contract, review
 *       log). It only calls {@link #noteStart}, so its cost is charged, the balance may go negative (floor
 *       {@code -capacity}) and the tick token is taken, which slows optional starts of the other bots instead.</li>
 * </ul>
 *
 * <p>One process-wide instance, {@link #shared()}; the class is also constructible for tests. Server thread only.
 * A tick that moves backwards (world reload) resets the refill clock without refilling.</p>
 */
public final class RouteBudget {
    /** Milliseconds added to the bucket per server tick. */
    public static final int REFILL_MS_PER_TICK = 5;
    /** A start needs at least this many milliseconds in the bucket. */
    public static final int MIN_START_MS = 30;

    /** Sentinel meaning "no server tick observed yet" for both clocks (a fresh bucket, or one just {@link #reset}). */
    private static final int UNSET = Integer.MIN_VALUE;

    private static final RouteBudget SHARED = new RouteBudget(100);

    private int capacityMs;
    private double balanceMs;
    private int lastRefillTick;
    private int lastStartTick;

    public RouteBudget(int capacityMs) {
        this.capacityMs = capacityMs;
        this.balanceMs = capacityMs;
        this.lastRefillTick = UNSET;
        this.lastStartTick = UNSET;
    }

    /** The process-wide instance the host uses. Its capacity is set from {@code route.bucketMs} by {@link #reconfigure}. */
    public static RouteBudget shared() {
        return SHARED;
    }

    /** Sets the capacity (clamped to at least {@value #MIN_START_MS}) and refills the bucket. */
    public void reconfigure(int capacityMs) {
        this.capacityMs = Math.max(capacityMs, MIN_START_MS);
        this.balanceMs = this.capacityMs;
    }

    /**
     * Whether a route start is allowed at {@code serverTick}: no start yet in this very tick, and, unless
     * {@code deterministic}, at least {@value #MIN_START_MS} ms in the bucket after refilling to this tick. Does
     * not consume anything (the caller may peek before choosing a candidate).
     */
    public boolean canStart(int serverTick, boolean deterministic) {
        if (serverTick == lastStartTick) {
            return false;
        }
        if (deterministic) {
            return true;
        }
        refill(serverTick);
        return balanceMs >= MIN_START_MS;
    }

    /**
     * Records that a start happened at {@code serverTick} and cost {@code costNanos}: takes the tick's token
     * and, unless {@code deterministic}, deducts {@code ceil(costNanos / 1_000_000)} ms from the bucket
     * (floor {@code -capacity}). A negative cost counts as 0.
     */
    public void noteStart(int serverTick, long costNanos, boolean deterministic) {
        lastStartTick = serverTick;
        if (deterministic) {
            return;
        }
        refill(serverTick);
        long cost = Math.max(costNanos, 0L);
        long ms = (cost + 999_999L) / 1_000_000L;
        balanceMs -= ms;
        if (balanceMs < -capacityMs) {
            balanceMs = -capacityMs;
        }
    }

    /** The balance in milliseconds after refilling to {@code serverTick} (diagnostics and tests). */
    public double availableMs(int serverTick) {
        refill(serverTick);
        return balanceMs;
    }

    /** Full bucket, no token taken (world unload, tests). */
    public void reset() {
        balanceMs = capacityMs;
        lastRefillTick = UNSET;
        lastStartTick = UNSET;
    }

    /**
     * Applies refill up to {@code serverTick}: {@value #REFILL_MS_PER_TICK} ms per elapsed tick since the last
     * refill, capped at {@code capacityMs}. A tick that moves backwards (or repeats) resets the clock without
     * refilling, per the class contract.
     */
    private void refill(int serverTick) {
        if (lastRefillTick == UNSET) {
            lastRefillTick = serverTick;
            return;
        }
        long elapsed = (long) serverTick - (long) lastRefillTick;
        if (elapsed < 0) {
            lastRefillTick = serverTick;
            return;
        }
        if (elapsed > 0) {
            balanceMs = Math.min(capacityMs, balanceMs + elapsed * REFILL_MS_PER_TICK);
            lastRefillTick = serverTick;
        }
    }
}
