package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of the process-level wiring of the mining assist, phase P0 (mining-assist design 8.1):
 * config load and tick-headroom measurement in {@code AIBotMod}, sidecar and cleanup ordering in
 * {@code RuntimeLifecycleCoordinator}, the harness default in the GameTest mod, and the promise that
 * {@code OreDigTask} is not touched in P0.
 */
class MiningAssistWiringSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");
    private static final Path HARNESS = Path.of(
            "src/gametest/java/io/github/zoyluo/aibot/gametest/AIBotHarnessTestMod.java");

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /** The text of one method or lambda body region between two markers. */
    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, from + " must exist");
        int end = source.indexOf(to, start + from.length());
        assertTrue(end > start, to + " must follow " + from);
        return source.substring(start, end);
    }

    // ---- AIBotMod ---------------------------------------------------------------------------------------

    @Test
    void theAssistConfigIsLoadedRightAfterTheMainConfigAndItsLogsAreStarted() throws IOException {
        String mod = read(MAIN.resolve("AIBotMod.java"));
        int mainConfig = mod.indexOf("AIBotConfig config = AIBotConfig.load();");
        int logStart = mod.indexOf("BotLogWriter.INSTANCE.start(config);");
        int configLogged = mod.indexOf("BotLog.config(\"config_loaded\"");
        int assistLoad = mod.indexOf("MiningAssistRuntime.load(FabricLoader.getInstance().getConfigDir().resolve(\"aibot.json\"));");
        int brain = mod.indexOf("BrainCoordinator.INSTANCE.configure(config);");
        assertTrue(mainConfig > 0 && logStart > mainConfig && configLogged > logStart,
                "the existing start-up order is unchanged");
        assertTrue(assistLoad > configLogged, "loaded after AIBotConfig.load() and once logging is up");
        assertTrue(assistLoad < brain, "and before any bot subsystem is configured");
        assertEquals(1, count(mod, "MiningAssistRuntime.load("));
    }

    @Test
    void theTickStartIsRecordedAndTheHeadroomEndIsTheLastStatementOfTheEndLambda() throws IOException {
        String mod = read(MAIN.resolve("AIBotMod.java"));
        int start = mod.indexOf("ServerTickEvents.START_SERVER_TICK.register(server -> MiningAssistRuntime.beginTick());");
        int end = mod.indexOf("ServerTickEvents.END_SERVER_TICK.register(server -> {");
        assertTrue(start > 0 && end > start, "START is registered before END");
        String lambda = between(mod, "ServerTickEvents.END_SERVER_TICK.register(server -> {", "\n        });");
        String endCall = "MiningAssistRuntime.endTick(server.getTicks());";
        int tps = lambda.indexOf("TpsGuard.INSTANCE.tick(server);");
        int diagnostics = lambda.indexOf("DiagnosticLogger.INSTANCE.tick(server);");
        int snapshot = lambda.indexOf("BotEdits.snapshotIfDue(server.getTicks(), assistSidecar);");
        int last = lambda.lastIndexOf(endCall);
        assertTrue(tps > 0 && diagnostics > tps, "the existing END statements keep their order");
        assertTrue(snapshot > diagnostics && last > snapshot, "sidecar snapshot, then the headroom end");
        assertEquals(1, count(lambda, endCall));
        assertTrue(lambda.substring(last + endCall.length()).isBlank(),
                "the headroom end is the very last statement, so it measures the whole tick's work");
    }

    // ---- RuntimeLifecycleCoordinator -------------------------------------------------------------------

    @Test
    void theSidecarIsLoadedAfterTheWorldRuntimeIsClearedAndFlushedSynchronouslyOnStop() throws IOException {
        String lifecycle = read(MAIN.resolve("runtime/RuntimeLifecycleCoordinator.java"));
        String started = between(lifecycle, "public void onServerStarted(", "public void onServerStopping(");
        assertTrue(started.indexOf("clearWorldRuntime();") > 0
                && started.indexOf("BotEdits.loadFromDisk(BotEdits.defaultSidecarPath());")
                > started.indexOf("clearWorldRuntime();"), "clear first, then load the persisted ledger");
        assertTrue(started.indexOf("BotEdits.loadFromDisk(") < started.indexOf("BotPersistence.INSTANCE.loadAndRespawn(server)"),
                "the ledger is in place before any bot resumes placing blocks");

        String stopping = between(lifecycle, "public void onServerStopping(", "public void resetBot(");
        int bots = stopping.indexOf("AIPlayerManager.INSTANCE.onServerStopping(server);");
        int flush = stopping.indexOf("BotEdits.flushSync(BotEdits.defaultSidecarPath());");
        int clear = stopping.indexOf("clearWorldRuntime();");
        assertTrue(bots > 0 && flush > bots && clear > flush,
                "flushed after the bots stopped placing and before the runtime is cleared");
    }

    @Test
    void perBotAndWorldAssistStateIsClearedWhereTheOtherRuntimeStateIs() throws IOException {
        String lifecycle = read(MAIN.resolve("runtime/RuntimeLifecycleCoordinator.java"));
        String transientBody = between(lifecycle, "private static void clearTransient(", "private static void forgetBot(");
        assertTrue(transientBody.contains("MiningAssistRuntime.clearBot(bot);"));
        String forget = between(lifecycle, "private static void forgetBot(", "private static void clearWorldRuntime()");
        assertTrue(forget.contains("MiningAssistRuntime.clearForced(bot.getUuid());"),
                "a bot that is gone for good must not keep the harness opt-in alive");
        String world = lifecycle.substring(lifecycle.indexOf("private static void clearWorldRuntime()"));
        assertTrue(world.contains("MiningAssistRuntime.clearWorldRuntime();"));
    }

    // ---- harness -----------------------------------------------------------------------------------------

    @Test
    void theHarnessDefaultsTheAssistOffAsItsFirstStatement() throws IOException {
        String harness = read(HARNESS);
        int init = harness.indexOf("public void onInitialize() {");
        int off = harness.indexOf("MiningAssistRuntime.setHarnessDefaultOff(true);");
        int commands = harness.indexOf("CommandRegistrationCallback.EVENT.register(");
        assertTrue(init > 0 && off > init && commands > off, "before anything can start a bot");
        assertFalse(harness.contains("setHarnessDefaultOff(false)"));
    }

    // ---- P0 leaves the mining tasks alone ------------------------------------------------------------------

    @Test
    void theOtherMiningTasksDoNotReferenceTheAssistInPhaseOne() throws IOException {
        for (String task : new String[] {"DigDownTask", "DescendToYTask", "MineTask",
                "MineValuablesTask", "StripMineTask"}) {
            String source = read(MAIN.resolve("task/" + task + ".java"));
            assertFalse(source.contains("mining.assist"), task + " must not import the assist in P0");
            assertFalse(source.contains("MiningAssist"), task + " must not call the assist in P0");
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
