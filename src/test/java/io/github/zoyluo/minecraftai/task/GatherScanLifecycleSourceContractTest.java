package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A budgeted scan must not outlive the phase that owns it, and the log flush hooks must stay wired. */
final class GatherScanLifecycleSourceContractTest {
    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + relative));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int next = source.indexOf("\n    private ", start + signature.length());
        return source.substring(start, next < 0 ? source.length() : next);
    }

    private static String methodBody2(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int next = source.indexOf("\n    public ", start + signature.length());
        return source.substring(start, next < 0 ? source.length() : next);
    }

    @Test
    void everyExitFromExploreDropsTheEnRouteScan() throws IOException {
        String source = read("task/GatherQuotaTask.java");
        // Central guard, run before every phase dispatch whatever code path changed the phase.
        int guard = source.indexOf("if (phase != Phase.EXPLORE) {");
        int dispatch = source.indexOf("switch (phase) {");
        assertTrue(guard > 0 && guard < dispatch, "onTick must drop exploreScan before dispatching outside EXPLORE");
        assertTrue(source.substring(guard, dispatch).contains("exploreScan = null;"));
        // Belt and braces: each explicit exit from exploreMove clears it as well.
        String explore = methodBody(source, "private void exploreMove(");
        Matcher exits = Pattern.compile("phase = Phase\\.SURVEY;").matcher(explore);
        int exitCount = 0;
        while (exits.find()) {
            exitCount++;
            String before = explore.substring(Math.max(0, exits.start() - 200), exits.start());
            assertTrue(before.contains("exploreScan = null;"),
                    "exit #" + exitCount + " from EXPLORE does not clear exploreScan");
        }
        assertTrue(exitCount >= 4, "expected the four EXPLORE exits, found " + exitCount);
    }

    @Test
    void surveyAndExploreScansAreClearedOnStartAndResume() throws IOException {
        String source = read("task/GatherQuotaTask.java");
        for (String hook : new String[] {"protected void onStart(", "protected void onResume("}) {
            String body = methodBody(source, hook);
            String head = body.substring(0, Math.min(body.length(), 900));
            assertTrue(head.contains("exploreScan = null;") && head.contains("surveyScan = null;")
                    && head.contains("prospectScan = null;"), hook + " must drop every in-flight scan");
        }
    }

    @Test
    void capabilityAuditRepeatsAreFlushedOnEveryLifecycleBoundaryAndPeriodically() throws IOException {
        String runtime = read("mode/CapabilityRuntime.java");
        assertTrue(methodBody(runtime, "public static void clearAll()").contains("AUDIT.drainAll("),
                "clearAll (world boundary, server stop, reload) must flush pending repeat counts first");
        assertTrue(methodBody(runtime, "public static void clear(").contains("AUDIT.drain("),
                "removing a bot must flush its pending repeat counts");
        assertTrue(runtime.contains("AUDIT.drainDue("), "a periodic sweep must report lone repeats");
        assertTrue(read("MinecraftAiMod.java").contains("CapabilityRuntime.flushDue(server.getTicks())"),
                "the sweep must be driven from the server tick");
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        assertTrue(methodBody2(lifecycle, "public void onServerStarted(").contains("clearWorldRuntime();")
                && methodBody2(lifecycle, "public void onServerStopping(").contains("clearWorldRuntime();"),
                "server start and stop must both clear the world runtime");
        assertTrue(lifecycle.contains("CapabilityRuntime.clearAll();"));
    }

}
