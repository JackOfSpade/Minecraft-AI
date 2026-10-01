package io.github.zoyluo.minecraftai.navigation;

import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationMeasurementTest {
    private String oldMode;
    private long oldScale;

    @BeforeEach
    void captureGlobals() {
        oldMode = System.getProperty(NavigationMeasurement.MODE_PROPERTY);
        oldScale = AStarPathfinder.harnessTimeScaleForDiagnostics();
        NavigationMeasurement.clearForTests();
    }

    @AfterEach
    void restoreGlobals() {
        NavigationMeasurement.clearForTests();
        AStarPathfinder.setHarnessTimeScale(oldScale);
        if (oldMode == null) {
            System.clearProperty(NavigationMeasurement.MODE_PROPERTY);
        } else {
            System.setProperty(NavigationMeasurement.MODE_PROPERTY, oldMode);
        }
    }

    @Test
    void disabledModeCreatesNoCapture() {
        System.clearProperty(NavigationMeasurement.MODE_PROPERTY);
        assertNull(NavigationMeasurement.startScaleOneGameTest("wall", NavEngine.LEGACY, List.of(UUID.randomUUID())));
    }

    @Test
    void scaleOneCapturePersistsCompleteMatchedEvidence() {
        System.setProperty(NavigationMeasurement.MODE_PROPERTY, NavigationMeasurement.SCALE_ONE_GAMETEST);
        AStarPathfinder.setHarnessTimeScale(1L);
        UUID bot = UUID.randomUUID();
        NavigationMeasurement.Run run = NavigationMeasurement.startScaleOneGameTest("wall", NavEngine.LEGACY, List.of(bot));
        assertNotNull(run);

        NavigationMeasurement.noteEffectiveEngine(bot, NavEngine.LEGACY);
        NavigationMeasurement.recordBotTick(bot, 1_000_000L);
        NavigationMeasurement.recordBotTick(bot, 2_000_000L);
        NavigationMeasurement.recordBotTick(bot, 10_000_000L);
        NavigationMeasurement.endServerTick(System.nanoTime() - 1_000_000L);
        NavigationMeasurement.recordPlanner(bot, NavEngine.LEGACY, "walk", 4_000_000L, 3L, 17, 6, "SUCCESS");

        NavigationMeasurement.Snapshot snapshot = NavigationMeasurement.finish(run,
                new NavigationMeasurement.Outcome(true, 42, 0.0D, 0, 0, 0, 0, "-"));
        assertEquals(NavigationMeasurement.ENVIRONMENT, snapshot.environment());
        assertEquals(1L, snapshot.pathfinderBudgetScale());
        assertTrue(snapshot.engineIsolated());
        assertEquals(3, snapshot.engineTick().count());
        assertEquals(13.0D / 3.0D, snapshot.engineTick().avgMs(), 0.0001D);
        assertEquals(10.0D, snapshot.engineTick().p95Ms(), 0.0001D);
        assertEquals(1, snapshot.serverTick().count());
        assertEquals(1, snapshot.planner().count());
        assertEquals(4.0D, snapshot.planner().avgMs(), 0.0001D);
        assertEquals(3L, snapshot.planners().getFirst().reportedMs());
        assertTrue(snapshot.hasRequiredEvidence());
        assertEquals("", snapshot.evidenceProblem());
    }

    @Test
    void scaledLegacyHarnessCannotCreateAnEvidenceRow() {
        System.setProperty(NavigationMeasurement.MODE_PROPERTY, NavigationMeasurement.SCALE_ONE_GAMETEST);
        AStarPathfinder.setHarnessTimeScale(40L);
        assertThrows(IllegalStateException.class,
                () -> NavigationMeasurement.startScaleOneGameTest("wall", NavEngine.LEGACY, List.of(UUID.randomUUID())));
    }

    @Test
    void writerMismatchInvalidatesOtherwiseCompleteEvidence() {
        System.setProperty(NavigationMeasurement.MODE_PROPERTY, NavigationMeasurement.SCALE_ONE_GAMETEST);
        AStarPathfinder.setHarnessTimeScale(1L);
        UUID bot = UUID.randomUUID();
        NavigationMeasurement.Run run = NavigationMeasurement.startScaleOneGameTest("wall", NavEngine.LEGACY, List.of(bot));
        NavigationMeasurement.noteEffectiveEngine(bot, NavEngine.BARITONE);
        NavigationMeasurement.recordBotTick(bot, 1_000_000L);
        NavigationMeasurement.endServerTick(System.nanoTime() - 1_000_000L);
        NavigationMeasurement.recordPlanner(bot, NavEngine.LEGACY, "walk", 1_000_000L, 1L, 1, 1, "SUCCESS");

        NavigationMeasurement.Snapshot snapshot = NavigationMeasurement.finish(run,
                new NavigationMeasurement.Outcome(true, 1, 0.0D, 0, 0, 0, 0, "-"));
        assertFalse(snapshot.engineIsolated());
        assertFalse(snapshot.hasRequiredEvidence());
        assertTrue(snapshot.evidenceProblem().contains("engine_isolation_lost"));
    }

    @Test
    void baritoneAdmissionOnlyCaptureIsValidAndFallbackIsRejected() {
        System.setProperty(NavigationMeasurement.MODE_PROPERTY, NavigationMeasurement.SCALE_ONE_GAMETEST);
        AStarPathfinder.setHarnessTimeScale(1L);
        UUID bot = UUID.randomUUID();
        NavigationMeasurement.Run missingDriver = NavigationMeasurement.startScaleOneGameTest(
                "wall", NavEngine.BARITONE, List.of(bot));
        NavigationMeasurement.noteEffectiveEngine(bot, NavEngine.BARITONE);
        // The scheduler can admit a Baritone route from its legacy branch before the first
        // driver tick. That is provenance, but it cannot prove a Baritone-labelled run drove.
        NavigationMeasurement.noteDriver(bot, false, true);
        NavigationMeasurement.recordBotTick(bot, 1_000_000L);
        NavigationMeasurement.endServerTick(System.nanoTime() - 1_000_000L);
        NavigationMeasurement.recordPlanner(bot, NavEngine.BARITONE, "admission", 1_000_000L, 1L, 1, 1, "SUCCESS");

        NavigationMeasurement.Snapshot admissionOnly = NavigationMeasurement.finish(missingDriver,
                new NavigationMeasurement.Outcome(true, 1, 0.0D, 0, 0, 0, 0, "-"));
        assertEquals(0, admissionOnly.driver().baritoneDriverTicks());
        assertEquals(1, admissionOnly.driver().legacyActionPackTicks());
        // A sealed/no-route course can truthfully consist of Baritone inline admissions plus a
        // holding scheduler tick. Its no-driver count is explicit in the artifact; it is not a
        // legacy fallback while the matching Baritone planner row is present.
        assertTrue(admissionOnly.hasRequiredEvidence());
        assertEquals("", admissionOnly.evidenceProblem());

        NavigationMeasurement.Run fallback = NavigationMeasurement.startScaleOneGameTest(
                "wall", NavEngine.BARITONE, List.of(bot));
        NavigationMeasurement.noteEffectiveEngine(bot, NavEngine.BARITONE);
        NavigationMeasurement.noteDriver(bot, true, false);
        NavigationMeasurement.noteBaritoneFallback(bot);
        NavigationMeasurement.recordBotTick(bot, 1_000_000L);
        NavigationMeasurement.endServerTick(System.nanoTime() - 1_000_000L);
        NavigationMeasurement.recordPlanner(bot, NavEngine.BARITONE, "admission", 1_000_000L, 1L, 1, 1, "SUCCESS");

        NavigationMeasurement.Snapshot mislabeled = NavigationMeasurement.finish(fallback,
                new NavigationMeasurement.Outcome(true, 1, 0.0D, 0, 0, 0, 0, "-"));
        assertEquals(1, mislabeled.driver().baritoneDriverTicks());
        assertEquals(1, mislabeled.driver().baritoneFallbacks());
        assertFalse(mislabeled.engineIsolated());
        assertFalse(mislabeled.hasRequiredEvidence());
        assertTrue(mislabeled.evidenceProblem().contains("baritone_fallbacks=1"));
    }

    @Test
    void legacyControllerAfterABaritoneRouteInvalidatesTheRow() {
        System.setProperty(NavigationMeasurement.MODE_PROPERTY, NavigationMeasurement.SCALE_ONE_GAMETEST);
        AStarPathfinder.setHarnessTimeScale(1L);
        UUID bot = UUID.randomUUID();
        NavigationMeasurement.Run run = NavigationMeasurement.startScaleOneGameTest(
                "wall", NavEngine.BARITONE, List.of(bot));
        NavigationMeasurement.noteEffectiveEngine(bot, NavEngine.BARITONE);
        NavigationMeasurement.noteDriver(bot, true, false);
        // Models an ended Baritone route followed by MoveTask's legacy direct-dig/path controller:
        // no selector exception or new A* invocation is needed for this to be a mislabeled row.
        // The controller may finish in the same ActionPack update; observing its pre-update
        // owner is enough to invalidate the otherwise Baritone-labelled row.
        NavigationMeasurement.noteDriver(bot, false, true, NavEngine.LEGACY, null);
        NavigationMeasurement.recordBotTick(bot, 1_000_000L);
        NavigationMeasurement.endServerTick(System.nanoTime() - 1_000_000L);
        NavigationMeasurement.recordPlanner(bot, NavEngine.BARITONE, "admission", 1_000_000L, 1L, 1, 1, "SUCCESS");

        NavigationMeasurement.Snapshot snapshot = NavigationMeasurement.finish(run,
                new NavigationMeasurement.Outcome(true, 1, 0.0D, 0, 0, 0, 0, "-"));
        assertEquals(1, snapshot.driver().baritoneDriverTicks());
        assertEquals(1, snapshot.driver().baritoneFallbacks());
        assertFalse(snapshot.hasRequiredEvidence());
        assertTrue(snapshot.evidenceProblem().contains("baritone_fallbacks=1"));
    }

    @Test
    void directFailureAndSameTickDriverFallbackAreCountedOnce() {
        System.setProperty(NavigationMeasurement.MODE_PROPERTY, NavigationMeasurement.SCALE_ONE_GAMETEST);
        AStarPathfinder.setHarnessTimeScale(1L);
        UUID bot = UUID.randomUUID();
        NavigationMeasurement.Run run = NavigationMeasurement.startScaleOneGameTest(
                "wall", NavEngine.BARITONE, List.of(bot));
        NavigationMeasurement.beginBotTick(bot);
        NavigationMeasurement.noteEffectiveEngine(bot, NavEngine.BARITONE);
        // Models BaritoneDriver.tickFailed (or an outer AI hook catch) followed by the same
        // tick's finally-based driver record. It must invalidate the row without inflating the
        // artifact's actual fallback count to two.
        NavigationMeasurement.noteBaritoneFallback(bot);
        NavigationMeasurement.noteDriver(bot, true, true, NavEngine.LEGACY, null);

        NavigationMeasurement.Snapshot snapshot = NavigationMeasurement.finish(run,
                new NavigationMeasurement.Outcome(true, 1, 0.0D, 0, 0, 0, 0, "-"));
        assertEquals(1, snapshot.driver().baritoneFallbacks());
        assertFalse(snapshot.hasRequiredEvidence());
    }
}
