package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.gametest.GameTestTimeLock;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;

/**
 * Runs a GameTest body that needs the world clock to itself ({@link GameTestTimeLock}) and releases the lock
 * (and runs the cleanup) exactly when the body finishes or fails.
 *
 * <p>{@code TestContext.addFinalTask} is NOT an "after the test" hook: it schedules its task at tick 0 and then
 * completes the test, dropping every later {@code runAtEveryTick} tick. A body that must run for many ticks and
 * still clean up therefore does its own bookkeeping here instead. Like {@link
 * ShelterGameTestFixtures#runLocked} this registers the test's one and only {@code runAtEveryTick}.
 */
final class TimeLockedRun {
    private TimeLockedRun() {
    }

    /**
     * @param maxHeldTicks fail (and release the lock) when the body has not finished this many ticks after the
     *                     lock was acquired, so a stuck scenario cannot starve the other lock users
     * @param body         called once per tick while the lock is held; returns true when the scenario is done
     * @param cleanup      always run once, whether the body finished or threw
     */
    static void run(TestContext context, int maxHeldTicks, BooleanSupplier body, Runnable cleanup) {
        boolean[] held = {false};
        boolean[] over = {false};
        int[] heldTicks = {0};
        context.runAtEveryTick(() -> {
            if (over[0]) {
                return;
            }
            if (!held[0]) {
                if (!GameTestTimeLock.tryAcquire()) {
                    return;
                }
                held[0] = true;
            }
            try {
                if (++heldTicks[0] > maxHeldTicks) {
                    context.throwGameTestException(Text.of("scenario did not finish within " + maxHeldTicks
                            + " ticks of holding the time lock"));
                }
                if (!body.getAsBoolean()) {
                    return;
                }
                over[0] = true;
                finish(cleanup);
                context.complete();
            } catch (RuntimeException | Error failure) {
                over[0] = true;
                finish(cleanup);
                throw failure;
            }
        });
    }

    private static void finish(Runnable cleanup) {
        try {
            cleanup.run();
        } finally {
            GameTestTimeLock.release();
        }
    }
}
