package io.github.zoyluo.minecraftai.pathfinding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the factual startup warm-up and its unscaled production-budget probe. */
class RuntimePathfinderWarmupTest {
    @Test
    void onlyAnActualSearchCountsAsWarmAndTheProbeUsesTheRealFiftyMillisecondClock() {
        PathfindingResult noEndpoint = PathfindingResult.failure(FailureReason.NO_START, 0, 0L);
        PathfindingResult oneNode = PathfindingResult.failure(FailureReason.TIMEOUT, 1, 50L);
        PathfindingResult searched = PathfindingResult.failure(FailureReason.GOAL_UNREACHABLE, 2, 50L);

        assertFalse(RuntimePathfinderWarmup.didRealWork(noEndpoint));
        assertFalse(RuntimePathfinderWarmup.didRealWork(oneNode));
        assertTrue(RuntimePathfinderWarmup.didRealWork(searched));
        assertTrue(RuntimePathfinderWarmup.passedRealTimeProbe(searched,
                TimeUnit.MILLISECONDS.toNanos(AStarPathfinder.DEFAULT_MAX_MILLIS)));
        assertFalse(RuntimePathfinderWarmup.passedRealTimeProbe(searched,
                TimeUnit.MILLISECONDS.toNanos(AStarPathfinder.DEFAULT_MAX_MILLIS + 1L)));
        assertFalse(RuntimePathfinderWarmup.passedRealTimeProbe(oneNode, 0L));
    }

    @Test
    void startupRunsBeforeRestoredWorkAndTheHarnessCannotScaleItsPerformanceProbe() throws IOException {
        Path root = Path.of("src/main/java/io/github/zoyluo/minecraftai");
        String mod = Files.readString(root.resolve("MinecraftAiMod.java"));
        String warmup = Files.readString(root.resolve("pathfinding/RuntimePathfinderWarmup.java"));
        String pathfinder = Files.readString(root.resolve("pathfinding/AStarPathfinder.java"));
        String natural = Files.readString(Path.of("src/gametest/java/io/github/zoyluo/minecraftai/action/NaturalMovementGameTests.java"));

        int started = mod.indexOf("ServerLifecycleEvents.SERVER_STARTED.register(server -> {");
        int warm = mod.indexOf("RuntimePathfinderWarmup.warmAtServerStart(server);", started);
        int restore = mod.indexOf("RuntimeLifecycleCoordinator.INSTANCE.onServerStarted(server, config);", started);
        assertTrue(started >= 0 && warm > started && restore > warm,
                "the cold search is paid before restored work can submit the first route");
        assertTrue(mod.contains("RuntimePathfinderWarmup.clear(server);"), "a later integrated-server lifecycle gets a fresh probe");
        assertTrue(warmup.contains("System.nanoTime()")
                        && warmup.contains("AStarPathfinder.withRealTimeBudget")
                        && warmup.contains("AStarPathfinder.DEFAULT_MAX_MILLIS")
                        && warmup.contains("Standability.findNearestStandable")
                        && warmup.contains("Standability.isStandableFresh"),
                "the probe measures a factual spawn route with a monotonic, unscaled clock");
        assertTrue(pathfinder.contains("boolean applyHarnessTimeScale")
                        && pathfinder.contains("withRealTimeBudget")
                        && pathfinder.contains("applyHarnessTimeScale ? harnessTimeScale : 1L"),
                "only the explicit startup probe bypasses the GameTest multiplier; ordinary searches retain harness behavior");
        int checked = natural.indexOf("didRealPathfindingWork(walk)");
        int marked = natural.indexOf("pathfinderWarm = true;", checked);
        assertTrue(checked >= 0 && marked > checked
                        && natural.contains("findWarmRoute(level, where)")
                        && natural.contains("hasStandableLane(level, start, direction)")
                        && natural.contains("warm-up fixture has no clear standable route"),
                "the fixture must discover a real clear lane and do real work before it says the JVM is warm");
    }
}
