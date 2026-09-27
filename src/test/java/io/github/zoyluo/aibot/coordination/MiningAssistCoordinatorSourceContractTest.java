package io.github.zoyluo.aibot.coordination;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of {@code MiningAssistCoordinator} for phase P0 (sense in shadow): it never assigns or
 * pauses tasks, never chats or calls a model, never throws out of the tick, and senses only for the five
 * mining task classes. Reads the production source as text, like the other source-contract tests.
 */
class MiningAssistCoordinatorSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/coordination/MiningAssistCoordinator.java");

    private static String source() throws IOException {
        return Files.readString(SOURCE);
    }

    /** The text of one method, from its signature to its closing brace at method indentation. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return source.substring(start, end);
    }

    // ---- P0 acts on nothing -------------------------------------------------------------------------

    @Test
    void neverAssignsPausesOrCancelsTasksAndNeverDrivesTheBot() throws IOException {
        String source = source();
        for (String token : List.of(
                "TaskManager.INSTANCE.assign",
                ".assign(",
                "pauseUserIntent",
                "resumeUserIntent",
                "cancelIntentTasks",
                "resetToIdle",
                "IntentController",
                "getActionPack",
                "interactionManager",
                ".teleport(")) {
            assertFalse(source.contains(token), "P0 is shadow only, found " + token);
        }
        // The only TaskManager use is the read-only lookup of the active task.
        Matcher matcher = Pattern.compile("TaskManager\\.INSTANCE\\.(\\w+)").matcher(source);
        List<String> uses = new ArrayList<>();
        while (matcher.find()) {
            uses.add(matcher.group(1));
        }
        assertFalse(uses.isEmpty());
        assertTrue(uses.stream().allMatch("getActive"::equals), "only getActive is allowed: " + uses);
    }

    @Test
    void neverChatsCallsAModelOrTouchesTheGoalLayer() throws IOException {
        String source = source();
        for (String token : List.of(
                "sendMessage(",
                "io.github.zoyluo.aibot.brain",
                "BrainCoordinator",
                "PoiAdvisor",
                "GoalExecutor",
                "BotReporter",
                "java.net.http",
                "CompletableFuture")) {
            assertFalse(source.contains(token), "no chat, no LLM, no goal layer in P0, found " + token);
        }
    }

    @Test
    void readsNoBlockFluidOrBiomeFromTheWorldItself() throws IOException {
        String source = source();
        for (String token : List.of("getBlockState(", "getFluidState(", "getChunk(", "getWorldChunk(",
                "getBiome(", "getBlockEntity(", "CapabilityRuntime", "PrivilegedCapability", "raycast(")) {
            assertFalse(source.contains(token), "the sensor adapters own every world read, found " + token);
        }
        assertEquals(1, count(source, "isSkyVisible("), "the one own-cell read is the underground test (design 2.3 3e)");
        assertTrue(source.contains("!bot.getServerWorld().isSkyVisible(bot.getBlockPos())"));
    }

    // ---- the exception fence ------------------------------------------------------------------------

    @Test
    void tickBotChecksTheStaticSwitchFirstAndCannotThrow() throws IOException {
        String source = source();
        String body = method(source, "public void tickBot(MinecraftServer server, AIPlayerEntity bot, boolean handled) {");
        int switchCheck = body.indexOf("if (!MiningAssistRuntime.senseConfigured()) {");
        int tryBlock = body.indexOf("try {");
        int run = body.indexOf("run(bot, tick, handled);");
        int catchBlock = body.indexOf("catch (RuntimeException exception) {");
        int fail = body.indexOf("fail(bot, tick, exception);");
        assertTrue(switchCheck > 0, "mode off must cost one static read");
        assertTrue(body.indexOf("server.getTicks()") > switchCheck, "nothing is read from the server before the switch");
        assertTrue(tryBlock > switchCheck && run > tryBlock && catchBlock > run && fail > catchBlock,
                "the whole pass runs inside the try and a failure goes to the fence");
        assertTrue(body.contains("return;"), "the off path returns immediately");
        assertEquals(1, count(body, "try {"));
        assertFalse(body.contains("throw "), "tickBot never throws");
    }

    @Test
    void theFenceLogsThroughBotLogErrorAtMostOncePerBotPerMinuteAndDropsTheStateAndPausesSensing() throws IOException {
        String source = source();
        String fail = method(source, "private static void fail(AIPlayerEntity bot, int tick, RuntimeException exception) {");
        int clear = fail.indexOf("MiningAssistRegistry.clear(bot);");
        int gate = fail.indexOf("MiningAssistRuntime.failures().recordFailure(bot.getUuid(), tick)");
        int log = fail.indexOf("BotLog.error(bot, \"assist_tick_failed\", exception,");
        assertTrue(clear >= 0 && gate > clear && log > gate, "throttle decides, then the log line is written");
        assertFalse(fail.contains("MiningAssistRuntime.clearBot("),
                "clearBot would also erase the failure record and defeat the throttle and the cooldown");
        assertFalse(fail.contains("throw "), "the fence itself never rethrows");
        String run = method(source, "private static void run(AIPlayerEntity bot, int tick, boolean handled) {");
        assertTrue(run.contains("MiningAssistRuntime.failures().coolingDown(botId, tick)"),
                "a failing bot pauses instead of failing on every tick");
    }

    // ---- what it senses for -----------------------------------------------------------------------------

    @Test
    void sensesOnlyForTheFiveMiningClassesViaInstanceof() throws IOException {
        String source = source();
        String method = method(source, "private static boolean isSensedTask(Task task) {");
        List<String> classes = List.of("OreDigTask", "DigDownTask", "DescendToYTask", "MineTask", "MineValuablesTask");
        Matcher matcher = Pattern.compile("task instanceof (\\w+)").matcher(method);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        assertEquals(classes, found, "exactly the five classes of design 6.1, in this order");
        assertFalse(method.contains("StripMineTask"), "StripMineTask is legacy and strict-rejected");
        assertFalse(source.contains("import io.github.zoyluo.aibot.task.StripMineTask;"));
        for (String name : classes) {
            assertTrue(source.contains("import io.github.zoyluo.aibot.task." + name + ";"), name);
        }
        assertEquals(5, count(source, " instanceof "), "no other type test anywhere in the coordinator");
    }

    @Test
    void theChecksRunThroughTheTestedPlanWithHandledFirstAndSensingOnlyOnSense() throws IOException {
        String source = source();
        String run = method(source, "private static void run(AIPlayerEntity bot, int tick, boolean handled) {");
        int decide = run.indexOf("SensePlan.decide(handled,");
        int task = run.indexOf("isSensedTask(TaskManager.INSTANCE.getActive(bot).orElse(null))");
        int gate = run.indexOf("MiningAssistRuntime.enabledFor(bot, tick)");
        int sky = run.indexOf("!bot.getServerWorld().isSkyVisible(bot.getBlockPos())");
        assertTrue(decide > 0 && task > decide && gate > task && sky > gate,
                "handled, then the task class, then the gate, then the sky read");
        assertTrue(run.contains("if (verdict.senses()) {"));
        assertEquals(1, count(source, "sense(bot, tick);"), "the sensor has one entry point");
    }

    // ---- the sensing pass ---------------------------------------------------------------------------------

    @Test
    void theSensingPassDrainsBreaksThenSweepsThenScoresOnceAndSummarisesLast() throws IOException {
        String source = source();
        String sense = method(source, "private static void sense(AIPlayerEntity bot, int tick) {");
        int status = sense.indexOf("state.status().sensed(tick)");
        int maintain = sense.indexOf("state.maintain(tick);");
        int drain = sense.indexOf("BreakPeek.drain(bot, state, tick, BreakPeek.MAX_BREAKS_PER_TICK);");
        int sweep = sense.indexOf("ViewSweeper.step(bot, state, config.sense().raysPerTick(), tick);");
        int due = sense.indexOf("PoiDetector.due(state, tick)");
        int evaluate = sense.indexOf("PoiDetector.evaluate(bot, state, world, tick)");
        int summary = sense.indexOf("MiningAssistLog.summaryIfDue(bot, state, tick);");
        assertTrue(status > 0 && maintain > status && drain > maintain && sweep > drain
                        && due > sweep && evaluate > due && summary > evaluate,
                "status, expiry, break peek (at most 4 breaks), sweep, shadow POI, cost summary");
        assertEquals(1, count(source, "PoiDetector.evaluate("), "one heavy operation per bot per tick");
        assertTrue(sense.contains("config.poi().enabled() && PoiDetector.due(state, tick)"));
        assertTrue(sense.contains("MiningAssistLog.poiBand(bot, state, PoiDetector.evaluate("),
                "the evaluation feeds nothing but the (rate limited) band log");
    }

    @Test
    void theRareFindSnapshotIsTakenOnlyWhenSomethingWorthAnnouncingWasAdded() throws IOException {
        String sense = method(source(), "private static void sense(AIPlayerEntity bot, int tick) {");
        int zero = sense.indexOf("state.counters().newSightingMaxValue = 0;");
        int drain = sense.indexOf("BreakPeek.drain(");
        int sweep = sense.indexOf("ViewSweeper.step(");
        int gate = sense.indexOf("if (state.counters().newSightingMaxValue >= config.detour().announceMinValue()) {");
        int log = sense.indexOf("MiningAssistLog.sightings(bot, state, tick, config.detour().announceMinValue());");
        assertTrue(zero > 0 && drain > zero && sweep > drain && gate > sweep && log > gate,
                "zeroed before the pass, checked after it, and only then is the ledger snapshotted for the log");
    }

    @Test
    void theRecoveryOfAFailedPassIsNotLoggedAsANewSession() throws IOException {
        String sense = method(source(), "private static void sense(AIPlayerEntity bot, int tick) {");
        int status = sense.indexOf("state.status().sensed(tick) == SenseStatus.Change.ENABLED");
        int suppress = sense.indexOf("!MiningAssistRuntime.failures().takeReenableSuppression(bot.getUuid())");
        int log = sense.indexOf("MiningAssistLog.senseEnabled(");
        assertTrue(status > 0 && suppress > status && log > suppress,
                "the enabled line is withheld once after a failure, not once per cooldown");
    }

    @Test
    void aBotThatStopsMiningReleasesItsStateOnlyThroughTheRuntimeAfterAFinalSummary() throws IOException {
        String source = source();
        String method = method(source, "private static void notSensing(AIPlayerEntity bot, int tick, SensePlan.Verdict verdict) {");
        int idle = method.indexOf("status.idleFor(tick, SenseStatus.IDLE_RELEASE_TICKS)");
        int summary = method.indexOf("MiningAssistLog.summaryFinal(bot, state, tick);");
        int released = method.indexOf("MiningAssistLog.stateReleased(");
        int clear = method.indexOf("MiningAssistRuntime.clearBot(bot);");
        assertTrue(idle > 0 && summary > idle && released > summary && clear > released);
        assertTrue(method.contains("status.notSensed(tick)"));
    }

    @Test
    void theCoordinatorIsInTheGuardedSetOfTheObservationContractAndExists() throws IOException {
        assertTrue(Files.exists(SOURCE));
        String contract = Files.readString(Path.of(
                "src/test/java/io/github/zoyluo/aibot/mining/assist/AssistObservationSourceContractTest.java"));
        assertTrue(contract.contains("\"coordination/MiningAssistCoordinator.java\""),
                "the banned-token scan must cover the coordinator");
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
