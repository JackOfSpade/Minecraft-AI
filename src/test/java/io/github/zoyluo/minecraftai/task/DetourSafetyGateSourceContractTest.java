package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of {@code task/DetourSafetyGate.java} (P1 contract D.3): it reads every SAFE-gate input
 * through the sources the contract names, touches no block state and contains no token banned by
 * {@code AssistObservationSourceContractTest}. Reads production sources as text, like the other source-contract
 * tests.
 */
class DetourSafetyGateSourceContractTest {
    private static final Path FILE = Path.of("src/main/java/io/github/zoyluo/minecraftai/task/DetourSafetyGate.java");

    private static String source() throws IOException {
        return Files.readString(FILE);
    }

    /** Source without comments, so a Javadoc mentioning a call is not mistaken for the call itself. */
    private static String code() throws IOException {
        return source().replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static String method(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = src.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return src.substring(start, end);
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    @Test
    void theFileExists() {
        assertTrue(Files.exists(FILE));
    }

    @Test
    void evaluateDelegatesToTheSafeGateWithTheSameInputsAndStage() throws IOException {
        String body = method(code(),
                "public static SafeReason evaluate(AIPlayerEntity bot, SafeGate.Stage stage, BlockPos pose, BlockPos ore) {");
        assertTrue(body.contains("SafeGate.evaluate(inputs(bot, stage, pose, ore), stage)"));
    }

    @Test
    void inputsGuardsEveryItemWithStageReads() throws IOException {
        String body = method(code(),
                "public static SafeGateInputs inputs(AIPlayerEntity bot, SafeGate.Stage stage, BlockPos pose, BlockPos ore) {");
        for (int item = 1; item <= 10; item++) {
            assertTrue(body.contains("stage.reads(" + item + ")"), "item " + item + " must be guarded by stage.reads(" + item + ")");
        }
    }

    @Test
    void everyInputComesThroughTheSourceOfD3() throws IOException {
        String body = method(code(),
                "public static SafeGateInputs inputs(AIPlayerEntity bot, SafeGate.Stage stage, BlockPos pose, BlockPos ore) {");
        // Item 1: mode/harness/forced through AssistGate, origin through TaskManager's active origin, audit session.
        assertTrue(body.contains("cfg.detourActive()"));
        assertTrue(body.contains("AssistGate.denyReason(cfg.mode(), cfg.harnessOff(), MiningAssistRuntime.isForced(uuid),"));
        assertTrue(body.contains("TaskManager.INSTANCE.activeOrigin(bot)"));
        assertTrue(body.contains("AssistGate.isRealOrigin("));
        assertTrue(body.contains("MiningEvidenceAudit.hasSession(uuid)"));
        // Item 2: the runtime's TPS verdict and the shared headroom, START asks canStart, TICK asks shouldAbort.
        assertTrue(body.contains("MiningAssistRuntime.tpsDegraded(bot)"));
        assertTrue(body.contains("MiningAssistRuntime.headroom().canStart(tpsDegraded)"));
        assertTrue(body.contains("MiningAssistRuntime.headroom().shouldAbort(tpsDegraded)"));
        assertTrue(body.contains("stage.isStart()"));
        // Item 3: bot vitals and the two MinecraftAiConfig sections.
        for (String token : List.of("bot.getHealth()", "combat.retreatHp()", "cfg.detour().startHpMargin()",
                "bot.hurtTime", "bot.isOnFire()", "bot.isInLava()", "bot.isUnderWater()",
                "bot.isInWater()", "bot.getFoodData().getFoodLevel()", "survival.hungerCriticalThreshold()")) {
            assertTrue(body.contains(token), "item 3 must read " + token);
        }
        // Item 4.
        assertTrue(body.contains("NavSafetyNet.INSTANCE.isWaterRescueActive(bot)"));
        assertTrue(body.contains("TaskManager.INSTANCE.pausedDepth(bot)"));
        assertTrue(body.contains("TaskManager.INSTANCE.isUserPaused(bot)"));
        assertTrue(body.contains("TaskOrigin::safety"));
        // Items 5 and 6.
        assertTrue(body.contains("DangerWatcher.INSTANCE.threatCooldownActive(bot, tick)"));
        assertTrue(body.contains("DangerWatcher.INSTANCE.shelterEpisodeActive(bot)"));
        assertTrue(body.contains("DangerWatcher.hasObservableHostilePressure(bot)"));
        // Item 7: the threat-box probe (ore passed through so a break-fresh exposure can be excluded, design
        // 4.7) and the remembered hazard field around bot, pose and ore.
        assertTrue(body.contains("DangerWatcher.observedLavaInThreatBox(bot, ore).isPresent()"));
        assertTrue(body.contains("cfg.detour().lavaClearRadius()"));
        assertTrue(body.contains("state.hazards().anyLavaWithin("));
        // Item 8: the biome refresh is gated by staleOrNever, never a bare subtraction.
        assertTrue(body.contains("MiningAssistState.staleOrNever(tick, state.biomeTick(), 20)"));
        assertTrue(body.contains("PoiDetector.refreshBiome(bot, state, world, tick)"));
        assertTrue(body.contains("cfg.safety().deepDarkVeto() && state.deepDark()"));
        assertFalse(Pattern.compile("tick\\s*-\\s*state\\.biomeTick\\(\\)\\s*>").matcher(body).find(),
                "never a bare subtraction against a NEVER-capable field");
        // Item 9: fails closed on a missing state and on a stale score.
        assertTrue(body.contains("b.poiEvidenceStale(true)"), "a missing state fails closed as POI_EVIDENCE");
        assertTrue(body.contains("cfg.poi().enabled()"));
        assertTrue(body.contains("MiningAssistState.staleOrNever(tick, state.poiScoreTick(), 3 * PoiScorer.EVAL_INTERVAL_TICKS)"));
        assertTrue(body.contains("SafeGate.poiWindowVeto(state.poiWindow())"));
        assertTrue(body.contains("candidate.hysteresis().satisfied(tick)"));
        assertTrue(body.contains("SafeGate.candidatePending(state.lastPoiBand(), anyCandidateSatisfied,"));
        assertTrue(body.contains("CAVERN_BLOCKS_DETOUR"));
        // P2: the no-detour zone now reads the real mandatory-repeat latch instead of a hard-coded false.
        assertTrue(body.contains(".inNoDetourZone(MandatoryLatch.inNoDetourZone(uuid, state.dimensionKey(), bot.blockPosition()))"),
                "the no-detour zone reads MandatoryLatch.inNoDetourZone");
        // Item 10.
        assertTrue(body.contains("state.hazards().anyTrapWithin("));
    }

    @Test
    void aMissingStateFailsClosedThroughPoiEvidenceOnly() throws IOException {
        String body = method(code(),
                "public static SafeGateInputs inputs(AIPlayerEntity bot, SafeGate.Stage stage, BlockPos pose, BlockPos ore) {");
        int item8 = body.indexOf("stage.reads(8)");
        int item9 = body.indexOf("stage.reads(9)");
        assertTrue(item8 >= 0 && item9 > item8);
        String item8Block = body.substring(item8, item9);
        String item9Block = body.substring(item9);
        assertTrue(item8Block.contains("b.deepDark(false)"), "item 8 answers all-clear when there is no state");
        assertTrue(item9Block.contains("b.poiEvidenceStale(true)"), "item 9 is the only backstop for a missing state");
    }

    @Test
    void noBlockOrFluidStateIsReadAndNoBannedTokenAppears() throws IOException {
        String code = code();
        for (String token : List.of("getBlockState(", "getFluidState(", "StructureManager", "structureManager",
                "getAllStarts", "startsForStructure", "findNearestMapStructure", "getStructureGeneratingAt", "maybeHas(", "getBlockEntity(", ".teleportTo(", "teleportTo(",
                "teleport(", "setPos(", "setPos(", "snapTo(", "getEntities(",
                "getEntities(", "getEntitiesOfClass(", "getMaxLocalRawBrightness(", "TaskManager.INSTANCE.assign(",
                "CapabilityRuntime", "PrivilegedCapability", "getChunk(", "getChunkAt(", "getChunkNow(")) {
            assertFalse(code.contains(token), "DetourSafetyGate must not contain " + token);
        }
    }

    @Test
    void theClassIsPublicAndTheCavernConstantIsDesignLiteralTrue() throws IOException {
        String source = source();
        assertTrue(source.contains("public final class DetourSafetyGate {"));
        assertTrue(source.contains("public static final boolean CAVERN_BLOCKS_DETOUR = true;"));
    }
}
