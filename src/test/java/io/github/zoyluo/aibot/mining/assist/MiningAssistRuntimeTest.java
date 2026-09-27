package io.github.zoyluo.aibot.mining.assist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningAssistRuntimeTest {
    private static final Function<String, String> NO_ENV = key -> null;
    private static final UUID BOT = new UUID(11L, 22L);

    @BeforeEach
    @AfterEach
    void reset() {
        MiningAssistRuntime.resetForTests();
    }

    private static Function<String, String> env(String key, String value) {
        return name -> key.equals(name) ? value : null;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    // ---- shipped default -------------------------------------------------------------------------

    @Test
    void phaseZeroShipsSenseInShadow() {
        assertEquals(AssistMode.SENSE, MiningAssistRuntime.SHIPPED_DEFAULT_MODE);
        MiningAssistRuntime.install(null, NO_ENV);
        assertEquals(AssistMode.SENSE, MiningAssistRuntime.mode());
        assertTrue(MiningAssistRuntime.senseConfigured());
        assertFalse(MiningAssistRuntime.config().detourActive(), "P0 never detours");
        assertFalse(MiningAssistRuntime.config().poiActive(), "P0 never acts on POI");
        assertFalse(MiningAssistRuntime.config().harnessOff());
    }

    @Test
    void offModeIsASingleStaticCheckThatNeverTouchesTheBot() {
        MiningAssistRuntime.install(json("{\"miningAssist\":{\"mode\":\"off\"}}"), NO_ENV);
        assertFalse(MiningAssistRuntime.senseConfigured());
        // Passing null proves the gate returns before it reads anything from the bot.
        assertFalse(MiningAssistRuntime.enabledFor(null, 100));
        MiningAssistHooks.onBotBreak(null, new net.minecraft.util.math.BlockPos(1, 2, 3));
    }

    // ---- harness default -------------------------------------------------------------------------

    @Test
    void harnessDefaultKeepsTheModeButTurnsTheHarnessOffFlagOn() {
        MiningAssistRuntime.setHarnessDefaultOff(true);
        MiningAssistRuntime.install(null, NO_ENV);
        assertTrue(MiningAssistRuntime.harnessDefaultOff());
        assertTrue(MiningAssistRuntime.config().harnessOff());
        assertEquals(AssistMode.SENSE, MiningAssistRuntime.mode());
        assertTrue(MiningAssistRuntime.deterministic(BOT), "the harness default makes runs deterministic");
    }

    @Test
    void harnessDefaultIsOrderIndependent() {
        MiningAssistRuntime.install(null, NO_ENV);
        assertFalse(MiningAssistRuntime.config().harnessOff());
        MiningAssistRuntime.setHarnessDefaultOff(true);
        assertTrue(MiningAssistRuntime.config().harnessOff(), "set after the load must re-parse");
        MiningAssistRuntime.setHarnessDefaultOff(false);
        assertFalse(MiningAssistRuntime.config().harnessOff());
    }

    @Test
    void anExplicitEnvOrFileModeBeatsTheHarnessDefault() {
        MiningAssistRuntime.setHarnessDefaultOff(true);
        MiningAssistRuntime.install(null, env(MiningAssistConfig.ENV_MODE, "poi"));
        assertEquals(AssistMode.POI, MiningAssistRuntime.mode());
        assertFalse(MiningAssistRuntime.config().harnessOff());

        MiningAssistRuntime.install(json("{\"miningAssist\":{\"mode\":\"detour\"}}"), NO_ENV);
        assertEquals(AssistMode.DETOUR, MiningAssistRuntime.mode());
        assertFalse(MiningAssistRuntime.config().harnessOff());
    }

    @Test
    void harnessOffMakesTheGateRefuseUnlessTheBotIsForced() {
        MiningAssistRuntime.setHarnessDefaultOff(true);
        MiningAssistRuntime.install(null, NO_ENV);
        MiningAssistConfig config = MiningAssistRuntime.config();
        assertFalse(config.enabledFor(false, true, false, false));
        MiningAssistRuntime.forceEnable(BOT);
        assertTrue(config.enabledFor(MiningAssistRuntime.isForced(BOT), true, false, false));
    }

    @Test
    void harnessDefaultSilencesEveryHookUntilABotIsForced() {
        MiningAssistRuntime.setHarnessDefaultOff(true);
        MiningAssistRuntime.install(null, NO_ENV);
        assertFalse(MiningAssistRuntime.senseConfigured(),
                "harness default off and nobody opted in: hooks and coordinator are one static read");
        assertFalse(MiningAssistRuntime.enabledFor(null, 1), "the gate returns before it touches the bot");

        MiningAssistRuntime.forceEnable(BOT);
        assertTrue(MiningAssistRuntime.senseConfigured(), "a forced bot switches the hooks on");
        MiningAssistRuntime.clearForced(BOT);
        assertFalse(MiningAssistRuntime.senseConfigured());

        MiningAssistRuntime.forceEnable(BOT);
        MiningAssistRuntime.clearWorldRuntime();
        assertFalse(MiningAssistRuntime.senseConfigured(), "the world runtime drops every forced bot");
    }

    @Test
    void anExplicitModeKeepsTheHooksOnEvenInTheHarness() {
        MiningAssistRuntime.setHarnessDefaultOff(true);
        MiningAssistRuntime.install(null, env(MiningAssistConfig.ENV_MODE, "sense"));
        assertTrue(MiningAssistRuntime.senseConfigured());
        MiningAssistRuntime.install(null, env(MiningAssistConfig.ENV_MODE, "off"));
        assertFalse(MiningAssistRuntime.senseConfigured());
    }

    @Test
    void theHooksFollowTheHarnessDefaultInEitherCallOrder() {
        MiningAssistRuntime.install(null, NO_ENV);
        assertTrue(MiningAssistRuntime.senseConfigured());
        MiningAssistRuntime.setHarnessDefaultOff(true);
        assertFalse(MiningAssistRuntime.senseConfigured(), "harness default set after the config load");
        MiningAssistRuntime.setHarnessDefaultOff(false);
        assertTrue(MiningAssistRuntime.senseConfigured());
    }

    // ---- forced, deterministic, headroom ---------------------------------------------------------

    @Test
    void forceEnableIsPerBotAndMakesTheRunDeterministic() {
        MiningAssistRuntime.install(null, NO_ENV);
        UUID other = new UUID(33L, 44L);
        assertFalse(MiningAssistRuntime.isForced(BOT));
        assertFalse(MiningAssistRuntime.deterministic(BOT));
        assertFalse(MiningAssistRuntime.headroom().isDeterministic());

        MiningAssistRuntime.forceEnable(BOT);
        assertTrue(MiningAssistRuntime.isForced(BOT));
        assertFalse(MiningAssistRuntime.isForced(other));
        assertTrue(MiningAssistRuntime.deterministic(BOT));
        assertFalse(MiningAssistRuntime.deterministic(other));
        assertTrue(MiningAssistRuntime.headroom().isDeterministic(), "no time-based throttling while forced");
        assertTrue(MiningAssistRuntime.headroom().canStart(false));
        assertFalse(MiningAssistRuntime.headroom().halveRays());

        MiningAssistRuntime.clearForced(BOT);
        assertFalse(MiningAssistRuntime.isForced(BOT));
        assertFalse(MiningAssistRuntime.headroom().isDeterministic());
    }

    @Test
    void theEnvFlagMakesEveryBotDeterministic() {
        MiningAssistRuntime.install(null, env(MiningAssistConfig.ENV_DETERMINISTIC, "1"));
        assertTrue(MiningAssistRuntime.deterministic(BOT));
        assertTrue(MiningAssistRuntime.headroom().isDeterministic());
    }

    @Test
    void headroomThresholdsComeFromTheConfig() {
        MiningAssistRuntime.install(json("{\"miningAssist\":{\"tick\":{\"startWorkMs\":30,\"abortWorkMs\":45}}}"), NO_ENV);
        TickHeadroom.Thresholds thresholds = MiningAssistRuntime.headroom().thresholds();
        assertEquals(30.0D, thresholds.startWorkMs(), 1.0e-9D);
        assertEquals(45.0D, thresholds.abortWorkMs(), 1.0e-9D);
    }

    @Test
    void headroomKeepsItsInstanceUntilTheConfigOrForcedSetChanges() {
        MiningAssistRuntime.install(null, NO_ENV);
        TickHeadroom first = MiningAssistRuntime.headroom();
        assertSame(first, MiningAssistRuntime.headroom());
        first.record(60.0D);
        assertTrue(first.workEma() > 0.0D);
        MiningAssistRuntime.forceEnable(BOT);
        assertTrue(MiningAssistRuntime.headroom() != first);
    }

    @Test
    void tickWorkIsIgnoredDuringWarmupAndFeedsTheLiveHeadroomAfterwards() {
        MiningAssistRuntime.install(null, NO_ENV);
        MiningAssistRuntime.recordTickWork(900.0D, MiningAssistRuntime.HEADROOM_WARMUP_TICKS - 1);
        assertEquals(0.0D, MiningAssistRuntime.headroom().workEma(), 1.0e-12D);
        MiningAssistRuntime.recordTickWork(30.0D, MiningAssistRuntime.HEADROOM_WARMUP_TICKS);
        assertEquals(30.0D, MiningAssistRuntime.headroom().workEma(), 1.0e-9D);
        TickHeadroom before = MiningAssistRuntime.headroom();
        MiningAssistRuntime.forceEnable(BOT);
        MiningAssistRuntime.recordTickWork(30.0D, 500);
        assertEquals(30.0D, MiningAssistRuntime.headroom().workEma(), 1.0e-9D,
                "after a rebuild the call reaches the new instance, not the stale one");
        assertEquals(30.0D, before.workEma(), 1.0e-9D);
    }

    @Test
    void endTickFeedsTheMeasuredWorkOnlyAfterATickStartWasSeen() throws InterruptedException {
        MiningAssistRuntime.install(null, NO_ENV);
        MiningAssistRuntime.endTick(MiningAssistRuntime.HEADROOM_WARMUP_TICKS + 1);
        assertEquals(0.0D, MiningAssistRuntime.headroom().workEma(), 1.0e-12D, "no start seen: nothing recorded");

        MiningAssistRuntime.beginTick();
        Thread.sleep(3L);
        MiningAssistRuntime.endTick(MiningAssistRuntime.HEADROOM_WARMUP_TICKS - 1);
        assertEquals(0.0D, MiningAssistRuntime.headroom().workEma(), 1.0e-12D, "warm-up ticks are ignored");

        MiningAssistRuntime.endTick(MiningAssistRuntime.HEADROOM_WARMUP_TICKS + 1);
        assertTrue(MiningAssistRuntime.headroom().workEma() >= 2.0D, "the sleep is inside the measured work");
    }

    @Test
    void oneHugeTickSampleIsClampedSoASpikeCannotLatchTheHalvingThrottle() {
        MiningAssistRuntime.install(null, NO_ENV);
        for (int i = 0; i < 20; i++) {
            MiningAssistRuntime.recordTickWork(10.0D, 500 + i);
        }
        assertFalse(MiningAssistRuntime.headroom().halveRays());
        // One autosave, GC pause or chunk-generation spike: 5 seconds in a single tick.
        MiningAssistRuntime.recordTickWork(5000.0D, 600);
        assertFalse(MiningAssistRuntime.headroom().halveRays(), "one spike is not sustained load");
        assertTrue(MiningAssistRuntime.headroom().workEma() <= 10.0D + MiningAssistRuntime.MAX_HEADROOM_SAMPLE_MS,
                "the sample is clamped before it reaches the average");
    }

    @Test
    void sustainedOverloadStillLatchesTheHalvingThrottle() {
        MiningAssistRuntime.install(null, NO_ENV);
        for (int i = 0; i < 60; i++) {
            MiningAssistRuntime.recordTickWork(5000.0D, 500 + i);
        }
        assertTrue(MiningAssistRuntime.headroom().halveRays(), "a clamped sample is still an overloaded tick");
    }

    @Test
    void beginAndEndTickDoNothingWhileTheSwitchIsOff() throws InterruptedException {
        MiningAssistRuntime.install(json("{\"miningAssist\":{\"mode\":\"off\"}}"), NO_ENV);
        MiningAssistRuntime.beginTick();
        Thread.sleep(3L);
        MiningAssistRuntime.endTick(MiningAssistRuntime.HEADROOM_WARMUP_TICKS + 1);
        assertEquals(0.0D, MiningAssistRuntime.headroom().workEma(), 1.0e-12D);

        // The switch comes on later: a start that was ignored while off must not be measured against.
        MiningAssistRuntime.install(null, NO_ENV);
        MiningAssistRuntime.endTick(MiningAssistRuntime.HEADROOM_WARMUP_TICKS + 2);
        assertEquals(0.0D, MiningAssistRuntime.headroom().workEma(), 1.0e-12D);
    }

    @Test
    void theSingleArgumentGateReturnsBeforeItReadsAnythingFromTheBotWhileOff() {
        MiningAssistRuntime.install(json("{\"miningAssist\":{\"mode\":\"off\"}}"), NO_ENV);
        assertFalse(MiningAssistRuntime.enabledFor(null), "the bot has no server here: it must not be asked for one");
    }

    // ---- gate wiring (the I4 inputs, with fakes) ---------------------------------------------------

    @Test
    void onlyTheFiveRealOriginKindsOpenTheGateAndAnyOtherOrMissingOriginDoesNot() {
        MiningAssistRuntime.install(null, NO_ENV);
        MiningAssistConfig config = MiningAssistRuntime.config();
        for (TaskOrigin.Kind kind : TaskOrigin.Kind.values()) {
            String deny = MiningAssistRuntime.resolveDeny(config, false, Optional.of(kind.name()), false, false);
            boolean real = kind == TaskOrigin.Kind.MISSION || kind == TaskOrigin.Kind.PLAYER_COMMAND
                    || kind == TaskOrigin.Kind.PLAYER_PANEL || kind == TaskOrigin.Kind.LLM_TOOL
                    || kind == TaskOrigin.Kind.JOB;
            if (real) {
                assertNull(deny, kind.name());
            } else {
                assertEquals(AssistGate.DENY_ORIGIN, deny, kind.name());
            }
        }
        assertEquals(AssistGate.DENY_ORIGIN,
                MiningAssistRuntime.resolveDeny(config, false, Optional.empty(), false, false),
                "a bot with no active origin fails closed");
        assertEquals(AssistGate.DENY_ORIGIN,
                MiningAssistRuntime.resolveDeny(config, false, Optional.of("SOMETHING_NEW"), false, false),
                "an origin kind added later is denied until it is listed");
    }

    @Test
    void anAuditSessionAndDegradedTpsEachCloseTheGateForARealOrigin() {
        MiningAssistRuntime.install(null, NO_ENV);
        MiningAssistConfig config = MiningAssistRuntime.config();
        Optional<String> mission = Optional.of("MISSION");
        assertNull(MiningAssistRuntime.resolveDeny(config, false, mission, false, false));
        assertEquals(AssistGate.DENY_AUDIT, MiningAssistRuntime.resolveDeny(config, false, mission, true, false));
        assertEquals(AssistGate.DENY_TPS, MiningAssistRuntime.resolveDeny(config, false, mission, false, true));
        assertEquals(AssistGate.DENY_AUDIT, MiningAssistRuntime.resolveDeny(config, false, mission, true, true),
                "audit is checked before TPS");
    }

    @Test
    void modeOffAndTheHarnessDefaultStillDecideFirstInTheResolver() {
        MiningAssistRuntime.install(json("{\"miningAssist\":{\"mode\":\"off\"}}"), NO_ENV);
        assertEquals(AssistGate.DENY_MODE_OFF,
                MiningAssistRuntime.resolveDeny(MiningAssistRuntime.config(), true, Optional.of("MISSION"), false, false),
                "forcing never overrides mode off");

        MiningAssistRuntime.setHarnessDefaultOff(true);
        MiningAssistRuntime.install(null, NO_ENV);
        MiningAssistConfig harnessOff = MiningAssistRuntime.config();
        assertEquals(AssistGate.DENY_HARNESS_OFF,
                MiningAssistRuntime.resolveDeny(harnessOff, false, Optional.of("MISSION"), false, false));
        assertNull(MiningAssistRuntime.resolveDeny(harnessOff, true, Optional.of("MISSION"), false, false),
                "a forced bot passes the harness default only");
        assertEquals(AssistGate.DENY_ORIGIN,
                MiningAssistRuntime.resolveDeny(harnessOff, true, Optional.of("VERIFY"), false, false),
                "forcing does not make a verify origin real");
    }

    @Test
    void aCachedOpenVerdictIsRecheckedAgainstTheLiveOriginAndAuditSession() {
        assertTrue(MiningAssistRuntime.stillOpen(Optional.of("MISSION"), false));
        assertFalse(MiningAssistRuntime.stillOpen(Optional.of("MISSION"), true),
                "an audit session that begins inside the cache window closes the gate at once");
        assertFalse(MiningAssistRuntime.stillOpen(Optional.of("VERIFY"), false),
                "so does an origin that stopped being real");
        assertFalse(MiningAssistRuntime.stillOpen(Optional.empty(), false));
    }

    @Test
    void theFailureGateIsClearedWithTheWorldRuntime() {
        MiningAssistRuntime.failures().recordFailure(BOT, 5);
        assertTrue(MiningAssistRuntime.failures().coolingDown(BOT, 6));
        MiningAssistRuntime.clearWorldRuntime();
        assertFalse(MiningAssistRuntime.failures().coolingDown(BOT, 6));
        assertEquals(0, MiningAssistRuntime.failures().size());
    }

    @Test
    void adaptiveThrottleFollowsTheConfigAndTheDeterministicRule() {
        MiningAssistRuntime.install(null, NO_ENV);
        assertTrue(MiningAssistRuntime.config().adaptiveThrottleActive(false));
        assertFalse(MiningAssistRuntime.config().adaptiveThrottleActive(true));
        MiningAssistRuntime.install(json("{\"miningAssist\":{\"sense\":{\"adaptiveThrottle\":false}}}"), NO_ENV);
        assertFalse(MiningAssistRuntime.config().adaptiveThrottleActive(false));
    }

    // ---- test switches and lifecycle -------------------------------------------------------------

    @Test
    void testTpsOverrideIsStoredAndClearedWithTheWorldRuntime() {
        assertNull(MiningAssistRuntime.testTpsDegraded());
        MiningAssistRuntime.setTestTpsDegraded(true);
        assertEquals(Boolean.TRUE, MiningAssistRuntime.testTpsDegraded());
        MiningAssistRuntime.setTestTpsDegraded(null);
        assertNull(MiningAssistRuntime.testTpsDegraded());

        MiningAssistRuntime.setTestTpsDegraded(false);
        MiningAssistRuntime.forceEnable(BOT);
        MiningAssistRegistry.getOrCreate(BOT);
        MiningAssistRuntime.clearWorldRuntime();
        assertNull(MiningAssistRuntime.testTpsDegraded());
        assertFalse(MiningAssistRuntime.isForced(BOT));
        assertEquals(0, MiningAssistRegistry.size());
    }

    // ---- P1 (F.4, M5): the detour's own server-wide statics are lifecycle-cleared too ------------

    @Test
    void clearWorldRuntimeAlsoClearsTheDetourStatics() {
        MiningAssistRuntime.install(null, NO_ENV);
        assertTrue(OreClaims.tryClaim("minecraft:overworld", BOT, new BlockPos(1, 2, 3).asLong(), 0));
        assertEquals(1, OreClaims.size());
        MissionAssistLedger.get("adhoc:" + BOT, 0).noteStart(0);
        assertEquals(1, MissionAssistLedger.size());
        RouteBudget.shared().noteStart(0, 50_000_000L, false);

        MiningAssistRuntime.clearWorldRuntime();

        assertEquals(0, OreClaims.size(), "a detour's soft claims must not outlive the world");
        assertEquals(0, MissionAssistLedger.size(), "per-mission bookkeeping is server-wide, not per-bot");
        assertEquals(100.0D, RouteBudget.shared().availableMs(0), 1.0e-9D, "reset gives a full bucket back");
    }

    @Test
    void installReconfiguresTheSharedRouteBudgetFromTheLiveConfig() {
        MiningAssistRuntime.install(json("{\"miningAssist\":{\"route\":{\"bucketMs\":40}}}"), NO_ENV);
        assertEquals(40.0D, RouteBudget.shared().availableMs(0), 1.0e-9D,
                "the shared route budget's capacity tracks the live config, not just its own default");
    }

    // ---- file loading ----------------------------------------------------------------------------

    @Test
    void loadReadsTheMiningAssistSectionOfTheConfigFile(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv(MiningAssistConfig.ENV_MODE) == null);
        Path file = dir.resolve("aibot.json");
        Files.writeString(file, "{\"profile\":\"strict\",\"miningAssist\":{\"mode\":\"detour\",\"sense\":{\"raysPerTick\":24}}}");
        MiningAssistConfig loaded = MiningAssistRuntime.load(file);
        assertEquals(AssistMode.DETOUR, loaded.mode());
        assertEquals(24, loaded.sense().raysPerTick());
        assertSame(loaded, MiningAssistRuntime.config());
    }

    @Test
    void loadFailsOpenToTheShippedDefaultOnMissingOrBrokenFiles(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv(MiningAssistConfig.ENV_MODE) == null);
        assertEquals(AssistMode.SENSE, MiningAssistRuntime.load(dir.resolve("missing.json")).mode());
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");
        assertEquals(AssistMode.SENSE, MiningAssistRuntime.load(broken).mode());
        Path notObject = dir.resolve("array.json");
        Files.writeString(notObject, "[1,2,3]");
        assertEquals(AssistMode.SENSE, MiningAssistRuntime.load(notObject).mode());
        assertEquals(AssistMode.SENSE, MiningAssistRuntime.load(null).mode());
    }
}
