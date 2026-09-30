package io.github.zoyluo.minecraftai.gametest;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GameTest scenarios run heavily concurrently, sharing one persistent test-server world.
 * {@code ServerLevel.setDayTime} is world-global, so any scenario that depends on a stable
 * day/night value for its whole duration must not run at the same time as another such
 * scenario, or they overwrite each other's clock every tick. There is no per-test world
 * instance and no batch-level exclusivity to rely on for this, so scenarios that need a
 * stable time of day opt into this in-process mutual-exclusion lock instead: only one such
 * scenario runs its real body at a time, while every other (non-time-sensitive) scenario is
 * unaffected and keeps running fully concurrently.
 *
 * <p>Registering a fresh {@code GameTestHelper#failIfEver} callback from inside one that is
 * already executing corrupts vanilla's internal per-test tracking (a live bug in 1.21.5's
 * GameTest rewrite: {@code GameTestInfo#tickInternal} iterates its own tracking map while
 * invoking each test's tick callbacks, and mutating that state admits a new one mid-iteration
 * crashes the whole test server). So a lock user must poll {@link #tryAcquire()} and run its
 * one-time setup plus its real per-tick body from inside the SAME, single, top-level
 * {@code failIfEver} callback it already registers -- never register a second one once
 * the lock is held.</p>
 *
 * <p>Since {@link GameTestIsolation} runs every test in a batch of its own, no two scenarios run at the same time any more and the
 * lock is always free when asked; {@link GameTestSweeper} puts the ambient clock back between tests. The lock stays the contract of
 * the scenarios that own the clock, for any run that batches tests together again.</p>
 */
public final class GameTestTimeLock {
    private static final AtomicBoolean HELD = new AtomicBoolean(false);

    private GameTestTimeLock() {
    }

    /** Returns true if the caller now holds the lock (either just now, or already). */
    public static boolean tryAcquire() {
        return HELD.compareAndSet(false, true);
    }

    /** Releases the lock. Only call this if a prior {@link #tryAcquire()} returned true. */
    public static void release() {
        HELD.set(false);
    }
}
