package io.github.zoyluo.minecraftai.gametest;

import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

/**
 * Self-tests of {@link GameTestCleanup} and {@link GameTestChunkForcing}, so that a Minecraft / Fabric / mapping
 * bump that changes how the framework notifies its listeners (or renames the private {@code testInfo} field the
 * cleanup reads reflectively) breaks loudly here instead of silently skipping the cleanups of every other test.
 *
 * <p>The framework offers no clean expected-failure mechanism (an optional test that fails on purpose would just
 * be a permanently red line, and nothing could assert that it failed), and a test cannot observe its own
 * completion cleanup. So the "the cleanup really ran once the test was done" claim is verified by the NEXT
 * self-test: the two completion scenarios below are identical, every batch runs alone and in sequence, and each
 * scenario first checks what the previous one left behind (its cleanup must have run exactly once and its
 * force-loaded chunk must be gone). When only one of them is selected, that cross-check is simply skipped; the
 * registration and once-only listener checks still run. The real completion path of a failing test is covered
 * by the listener contract test, which drives the listener's pass and fail notifications directly.
 */
public final class GameTestCleanupSelfTests {
    private static final AtomicInteger STARTED = new AtomicInteger();
    private static final AtomicInteger CLEANED = new AtomicInteger();
    /** Far from any test structure, so only the fixture's own force-loading can be why it is loaded. */
    private static final int FAR_CHUNK = 40;
    private static boolean farChunkForcedByEarlierScenario;

    @GameTest(environment = "minecraftai-gametest:game_test_cleanup_self_tests_completion_scenario_a_cleans_up_exactly_once", maxTicks = 60)
    public void completionScenarioACleansUpExactlyOnce(GameTestHelper context) {
        completionScenario(context);
    }

    @GameTest(environment = "minecraftai-gametest:game_test_cleanup_self_tests_completion_scenario_b_cleans_up_exactly_once", maxTicks = 60)
    public void completionScenarioBCleansUpExactlyOnce(GameTestHelper context) {
        completionScenario(context);
    }

    private static void completionScenario(GameTestHelper context) {
        // Whatever self-test ran before this one must have been cleaned up exactly once by now.
        require(context, STARTED.get() == CLEANED.get(),
                "the previous self-test's cleanup did not run exactly once: started=" + STARTED.get()
                        + " cleaned=" + CLEANED.get());
        if (farChunkForcedByEarlierScenario) {
            require(context, !context.getLevel().getForceLoadedChunks()
                            .contains(ChunkPos.asLong(FAR_CHUNK, FAR_CHUNK)),
                    "a chunk force-loaded by the previous self-test outlived its test (leaked force-loading)");
        }
        STARTED.incrementAndGet();
        GameTestInfo info = GameTestCleanup.infoOf(context);
        require(context, info != null, "GameTestCleanup could not read the framework's test info");
        long listenersBefore = info.getListeners().count();
        int[] cleanups = {0};
        GameTestCleanup.whenFinished(context, () -> {
            cleanups[0]++;
            CLEANED.incrementAndGet();
        });
        require(context, info.getListeners().count() == listenersBefore + 1,
                "whenFinished must register exactly one completion listener");
        GameTestChunkForcing.forceForTest(context, FAR_CHUNK, FAR_CHUNK, FAR_CHUNK, FAR_CHUNK);
        require(context, context.getLevel().getForceLoadedChunks().contains(ChunkPos.asLong(FAR_CHUNK, FAR_CHUNK)),
                "forceForTest did not force-load its chunk");
        farChunkForcedByEarlierScenario = true;
        context.runAtTickTime(3L, () -> require(context, cleanups[0] == 0,
                "the cleanup ran while the test was still running"));
        context.runAtTickTime(5L, () -> {
            require(context, cleanups[0] == 0, "the cleanup ran before the test completed");
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:game_test_cleanup_self_tests_once_listener_runs_the_cleanup_once_for_pass_and_fail_and_swallows_errors", maxTicks = 5)
    public void onceListenerRunsTheCleanupOnceForPassAndFailAndSwallowsErrors(GameTestHelper context) {
        int[] runs = {0};
        GameTestListener passFirst = GameTestCleanup.onceListener((info, runner) -> runs[0]++);
        passFirst.testPassed(null, null);
        passFirst.testFailed(null, null);
        passFirst.testPassed(null, null);
        require(context, runs[0] == 1, "a passing test's cleanup must run exactly once, ran " + runs[0]);

        GameTestListener failFirst = GameTestCleanup.onceListener((info, runner) -> runs[0]++);
        failFirst.testFailed(null, null);
        failFirst.testPassed(null, null);
        failFirst.testFailed(null, null);
        require(context, runs[0] == 2, "a failing or timed-out test's cleanup must run exactly once");

        int[] throwing = {0};
        GameTestListener boom = GameTestCleanup.onceListener((info, runner) -> {
            throwing[0]++;
            throw new IllegalStateException("cleanup failure must not escape into the runner's tick loop");
        });
        boom.testFailed(null, null);
        boom.testFailed(null, null);
        require(context, throwing[0] == 1, "a throwing cleanup must be swallowed and not retried");
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
