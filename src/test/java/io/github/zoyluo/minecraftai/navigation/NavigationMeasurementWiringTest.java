package io.github.zoyluo.minecraftai.navigation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the P3 fixture wiring so a future cleanup cannot turn scaled GameTest data into a real-time claim. */
class NavigationMeasurementWiringTest {
    @Test
    void scaleOneEngineIsolationAndRawArtifactsStayWiredEndToEnd() throws IOException {
        Path root = Path.of("src");
        String harness = Files.readString(root.resolve("gametest/java/io/github/zoyluo/minecraftai/gametest/MinecraftAiHarnessTestMod.java"));
        String course = Files.readString(root.resolve("gametest/java/io/github/zoyluo/minecraftai/task/NavigationCourseRun.java"));
        String entity = Files.readString(root.resolve("main/java/io/github/zoyluo/minecraftai/entity/AIPlayerEntity.java"));
        String serverTick = Files.readString(root.resolve("main/java/io/github/zoyluo/minecraftai/mixin/ServerTickProbeMixin.java"));
        String actionPack = Files.readString(root.resolve("main/java/io/github/zoyluo/minecraftai/action/ActionPack.java"));
        String pathfinder = Files.readString(root.resolve("main/java/io/github/zoyluo/minecraftai/pathfinding/AStarPathfinder.java"));
        String selector = Files.readString(root.resolve("main/java/io/github/zoyluo/minecraftai/navigation/NavEngineSelector.java"));
        String baritone = Files.readString(root.resolve("main/java/io/github/zoyluo/minecraftai/baritone/BaritoneNavigator.java"));
        String driver = Files.readString(root.resolve("main/java/io/github/zoyluo/minecraftai/baritone/BaritoneDriver.java"));
        String runner = Files.readString(Path.of("scripts/dev/nav_measurement.sh"));
        String commonRunner = Files.readString(Path.of("scripts/dev/gametest.sh"));
        String unitRunner = Files.readString(Path.of("scripts/dev/unittest.sh"));
        String staticCheck = Files.readString(Path.of("scripts/ci_static_check.sh"));

        assertTrue(harness.contains("NavigationMeasurement.scaleOneGameTestRequested()")
                        && harness.contains("? 1L : 40L"),
                "only the explicit P3 fixture may restore the legacy budget from the normal 40x harness scale");
        assertTrue(course.contains("startScaleOneGameTest")
                        && course.contains("noteEffectiveEngine")
                        && course.contains("hasRequiredEvidence"),
                "the course must open a scale-one session, continuously prove per-bot engine ownership, and reject incomplete rows");
        assertTrue(course.contains("measurements.tsv") && course.contains("planner.tsv")
                        && course.contains("NAVMEASURE") && course.contains("NAVPLAN"),
                "course outcome, timing summary, and raw planner samples must remain persisted artifacts");
        assertTrue(entity.contains("NavigationMeasurement.beginBotTick")
                        && entity.contains("NavigationMeasurement.endBotTick")
                        && entity.contains("NavigationMeasurement.noteDriver")
                        && entity.contains("NavEngine ownerBeforeUpdate = null;")
                        && entity.contains("ownerBeforeUpdate = this.actionPack.navigationOwnerForMeasurement();")
                        && entity.contains("NavEngine ownerAfterUpdate = this.actionPack.navigationOwnerForMeasurement();")
                        && entity.contains("try {\n                    this.actionPack.onUpdate();\n                } finally {")
                        && entity.contains("noteDriver(this, false, true, ownerBeforeUpdate, ownerAfterUpdate)"),
                "the metric must bracket the full AIPlayerEntity tick and preserve a one-tick legacy controller on both sides of ActionPack.update, including an update exception");
        assertTrue(serverTick.contains("NavigationMeasurement.beginServerTick")
                        && serverTick.contains("@At(\"RETURN\")"),
                "the metric must include the complete MinecraftServer tick");
        assertTrue(pathfinder.contains("findPathInternal") && pathfinder.contains("NavigationMeasurement.recordPlanner")
                        && pathfinder.contains("capturePlanner ? System.nanoTime()")
                        && !actionPack.contains("recordLegacyPlan"),
                "the central A* seam must capture initial routes, executor replans, and return proofs without duplicate caller probes");
        assertTrue(selector.contains("NavigationMeasurement.noteBaritoneFallback")
                        && driver.contains("NavigationMeasurement.noteBaritoneFallback(bot)")
                        && actionPack.contains("NavigationMeasurement.noteBaritoneFallback(player)")
                        && entity.contains("NavigationMeasurement.noteBaritoneFallback(this)")
                        && entity.indexOf("NavigationMeasurement.noteBaritoneFallback(this)")
                                != entity.lastIndexOf("NavigationMeasurement.noteBaritoneFallback(this)")
                        && entity.contains("try {\n                    baritoneCompleted = baritoneAfterPhysics();\n                } finally {")
                        && entity.contains("baritoneCompleted") && actionPack.contains("navigationOwnerForMeasurement"),
                "a Baritone-labelled row must record actual driver behavior and invalidate selector, both outer-driver catches, driver-fault, progress-fault, or legacy-controller fallback even when its update throws");
        assertTrue(baritone.contains("NavigationMeasurement.recordPlanner") && baritone.contains("admission"),
                "Baritone's inline admission must be captured with the same artifact schema");
        assertTrue(runner.contains("-Dminecraftai.nav.measurement=scale1")
                        && runner.contains("gametest_unpaced")
                        && runner.contains("NAVMEASURE") && runner.contains("NAVPLAN")
                        && runner.contains("resolve_java_command") && runner.contains("$java_home/bin/java")
                        && runner.contains("self_test_java_home_fallback") && runner.contains("\"${JAVA_COMMAND[@]}\" -version"),
                "the evidence runner must force scale one, preserve raw rows, declare unpaced provenance, and use JAVA_HOME when Git Bash lacks PATH Java");
        assertTrue(runner.contains("runner-status.txt") && runner.contains("$1 == \"PASS\"")
                        && runner.contains("$6 == run_id") && runner.contains("NAVCOURSE")
                        && runner.contains("snapshot_current_artifacts") && runner.contains("self_test_snapshot_current_artifacts"),
                "the runner must reject a failed filter and join its planner rows to one matching course result");
        int cleanPreflight = runner.indexOf("status --porcelain=v1");
        int createOutput = runner.indexOf("mkdir -p \"$OUT\"");
        assertTrue(cleanPreflight >= 0 && cleanPreflight < createOutput,
                "the clean-tree preflight must happen before an output directory can dirty an in-repository capture");
        assertTrue(commonRunner.contains("GT_JAVA_OPTS")
                        && commonRunner.contains("# mkdir locks even on Linux CI")
                        && commonRunner.contains("HAVE_FLOCK=0")
                        && commonRunner.indexOf("# mkdir locks even on Linux CI") < commonRunner.indexOf("mkdir -p build"),
                "the shared serial GameTest runner must forward the explicit measurement property and self-test its mkdir lock path before acquisition");
        assertTrue(unitRunner.contains("terminate_unit_run")
                        && unitRunner.contains("wait_for_unit_pid_gone")
                        && unitRunner.contains("trap 'on_unit_signal TERM' TERM"),
                "the portable unit runner must reap a tracked Gradle tree before its signal path releases the shared lock");
        assertTrue(staticCheck.contains("bash scripts/dev/gametest.sh --self-test")
                        && staticCheck.contains("bash scripts/dev/unittest.sh --self-test"),
                "CI static validation must exercise both portable runners, not only parse them");
    }
}
