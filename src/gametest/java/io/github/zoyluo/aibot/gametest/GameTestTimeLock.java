package io.github.zoyluo.aibot.gametest;

import net.minecraft.test.TestContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GameTest scenarios run heavily concurrently, sharing one persistent test-server world.
 * {@code ServerWorld.setTimeOfDay} is world-global, so any scenario that depends on a stable
 * day/night value for its whole duration must not run at the same time as another such
 * scenario, or they overwrite each other's clock every tick. There is no per-test world
 * instance and no batch-level exclusivity to rely on for this, so scenarios that need a
 * stable time of day opt into this in-process mutual-exclusion lock instead: only one such
 * scenario runs its real body at a time, while every other (non-time-sensitive) scenario is
 * unaffected and keeps running fully concurrently.
 */
public final class GameTestTimeLock {
    private static final AtomicBoolean HELD = new AtomicBoolean(false);

    private GameTestTimeLock() {
    }

    /**
     * Runs {@code body} once this scenario has exclusive claim on world time-of-day, and
     * releases that claim when the scenario ends (success, failure, or timeout) via
     * {@link TestContext#addFinalTask}. Waiting for the claim does not run any of
     * {@code body}'s logic yet, so give the scenario's {@code maxTicks} enough headroom to
     * cover both a worst-case queueing wait behind every other time-sensitive scenario and
     * its own real duration.
     */
    public static void runExclusive(TestContext context, Runnable body) {
        boolean[] acquired = {false};
        context.addFinalTask(() -> {
            if (acquired[0]) {
                HELD.set(false);
            }
        });
        context.runAtEveryTick(() -> {
            if (acquired[0]) {
                return;
            }
            if (!HELD.compareAndSet(false, true)) {
                return;
            }
            acquired[0] = true;
            body.run();
        });
    }
}
