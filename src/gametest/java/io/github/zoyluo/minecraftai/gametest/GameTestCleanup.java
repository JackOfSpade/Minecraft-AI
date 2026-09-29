package io.github.zoyluo.minecraftai.gametest;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * field is named {@code testInfo}).
 */
public final class GameTestCleanup {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-gametest-cleanup");
    private static final Field TEST_INFO = testInfoField();

    private GameTestCleanup() {
    }

    /**
     * Runs {@code cleanup} exactly once when the test behind {@code helper} has finished (passed, failed or
     * timed out). It never completes the test itself. Call it from the test method (not from inside a
     * running tick callback) so the listener is registered before the first tick. A cleanup that throws is
     * logged and swallowed so it cannot take down the test runner's tick loop.
     */
    public static void whenFinished(GameTestHelper helper, Runnable cleanup) {
        AtomicBoolean ran = new AtomicBoolean(false);
        Runnable once = () -> {
            if (!ran.compareAndSet(false, true)) {
                return;
            }
            try {
                cleanup.run();
            } catch (RuntimeException | Error failure) {
                LOG.error("GameTest cleanup failed", failure);
            }
        };
        infoOf(helper).addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                once.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                once.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
            }
        });
    }

    private static GameTestInfo infoOf(GameTestHelper helper) {
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
