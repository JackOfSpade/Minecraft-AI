package io.github.zoyluo.minecraftai.gametest;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The correct "after the test" hook for GameTest fixtures.
 *
 * <p>{@code GameTestHelper.succeedIf} is not one: it runs its task on the FIRST tick and then completes the
 * test successfully, so a test that registers its cleanup there passes at tick 1 without any of its later
 * assertions ever running (and its cleanup runs at tick 1 as well, before the scenario has done anything).
 * {@code GameTestHelper} exposes no completion hook, but {@link GameTestInfo#addListener} does: the
 * runner notifies every listener exactly once when the test is done, whether it succeeded, failed or ran out
 * of ticks. The {@link GameTestInfo} is only reachable through {@code GameTestHelper}'s private field, so it
 * is read reflectively (this source set only ever runs in the Mojang-mapped dev/test environment, where the
 * field is named {@code testInfo}); {@code GameTestCleanupSelfTests} fails loudly if a version bump breaks
 * that lookup or the once-only listener contract.
 *
 * <p>Ordering: the runner registers its own listener (which starts the NEXT test batch and clears every
 * force-loaded chunk) when the batch starts, before the test body registers ours. A cleanup therefore runs AFTER
 * the runner has already spawned the next batch's structures. Cleanup code must not undo shared state the
 * runner may have just re-established (see {@link GameTestChunkForcing}).
 */
public final class GameTestCleanup {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-gametest-cleanup");
    private static final Field TEST_INFO = testInfoField();

    private GameTestCleanup() {
    }

    /**
     * Runs {@code cleanup} exactly once when the test behind {@code helper} has finished (passed, failed or
     * timed out). It never completes the test itself. Call it from the test method (not from inside a
     * running tick callback) so the listener is registered before the first tick. A cleanup that throws a
     * RuntimeException or AssertionError (other Errors propagate) is
     * logged and swallowed so it cannot take down the test runner's tick loop.
     */
    public static void whenFinished(GameTestHelper helper, Runnable cleanup) {
        whenFinished(helper, (info, runner) -> cleanup.run());
    }

    /** Like {@link #whenFinished(GameTestHelper, Runnable)}, for a cleanup that needs the runner's view. */
    public static void whenFinished(GameTestHelper helper, BiConsumer<GameTestInfo, GameTestRunner> cleanup) {
        infoOf(helper).addListener(onceListener(cleanup));
    }

    /**
     * The listener behind {@code whenFinished}: runs {@code cleanup} on the first pass/fail notification and
     * never again, and swallows (logs) anything the cleanup throws.
     */
    static GameTestListener onceListener(BiConsumer<GameTestInfo, GameTestRunner> cleanup) {
        AtomicBoolean ran = new AtomicBoolean(false);
        return new GameTestListener() {
            private void once(GameTestInfo info, GameTestRunner runner) {
                if (!ran.compareAndSet(false, true)) {
                    return;
                }
                try {
                    cleanup.accept(info, runner);
                } catch (RuntimeException | AssertionError failure) {
                    // Other Errors (OutOfMemoryError, LinkageError, ...) are not a failed cleanup: they propagate.
                    LOG.error("GameTest cleanup failed", failure);
                }
            }

            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                once(info, runner);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                once(info, runner);
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
            }
        };
    }

    /** The framework's info object behind {@code helper} (reflective; see the class docs). */
    static GameTestInfo infoOf(GameTestHelper helper) {
        try {
            return (GameTestInfo) TEST_INFO.get(helper);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("cannot read GameTestHelper.testInfo", impossible);
        }
    }

    private static Field testInfoField() {
        try {
            Field field = GameTestHelper.class.getDeclaredField("testInfo");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException missing) {
            throw new ExceptionInInitializerError(missing);
        }
    }
}
