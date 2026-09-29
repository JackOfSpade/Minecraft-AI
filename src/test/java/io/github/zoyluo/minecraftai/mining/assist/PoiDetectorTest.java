package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.BOT;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.OVERWORLD;
import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.facts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure parts of the PoiDetector adapter: scheduling, labels and log fields. {@link #refreshBiome} and
 * {@link #evaluate} themselves need a real {@code AIPlayerEntity}/{@code ServerLevel}, which the pure JUnit lane
 * cannot construct (review round, P1 contract G.5): those two methods are pinned here as source-contract checks
 * on the comment-stripped production text instead of exercised live. The live behaviour (a real biome read,
 * {@code deepDark}/{@code poiStructureScore} filled) is covered by the GameTest lane and by
 * {@code MiningAssistStateTest}'s accessor tests.
 */
class PoiDetectorTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");
    private static final Path ASSIST = MAIN.resolve("mining/assist");

    /** Source without comments, so a Javadoc mentioning a call is not mistaken for the call itself. */
    private static String code(Path path) throws IOException {
        return Files.readString(path).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    /** One method of a comment-free source: from its signature to its closing brace at method indentation. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return source.substring(start, end);
    }

    // ---- P1 (F.3, M18): refreshBiome moved the one own-cell biome read out of evaluate --------------------

    @Test
    void refreshBiomeReadsTheFeetBiomeAndNotesWhenItWasReadSourceContract() throws IOException {
        String detector = code(ASSIST.resolve("PoiDetector.java"));
        String refreshBiome = method(detector,
                "public static void refreshBiome(AIPlayerEntity bot, MiningAssistState state, ServerLevel world, int serverTick) {");
        assertTrue(refreshBiome.contains("world.getBiome(feet)"), "the own-cell biome read moved here, unchanged");
        assertTrue(refreshBiome.contains("BlockPos feet = bot.blockPosition();"));
        assertTrue(refreshBiome.contains("state.noteBiomeRead(serverTick);"),
                "so DetourSafetyGate item 8 can tell whether the fact is fresh");
    }

    @Test
    void evaluateCallsRefreshBiomeAndRecordsThePoiScoreSourceContract() throws IOException {
        String detector = code(ASSIST.resolve("PoiDetector.java"));
        String evaluate = method(detector,
                "public static Result evaluate(AIPlayerEntity bot, MiningAssistState state, ServerLevel world, int serverTick) {");
        assertTrue(evaluate.contains("refreshBiome(bot, state, world, serverTick);"),
                "evaluate no longer reads the biome itself");
        assertTrue(evaluate.contains("state.notePoiScore(serverTick, score.s());"),
                "design 4.4 item 9: the SAFE gate reads this score, so it must be timestamped when it is written");
    }

    @Test
    void exactlyOneBiomeReadRemainsInTheWholeAssistPackage() throws IOException {
        int biomeReads = 0;
        try (var stream = Files.walk(ASSIST)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                biomeReads += count(code(file), ".getBiome(");
            }
        }
        assertEquals(1, biomeReads, "refreshBiome must be the only place in the package that reads the world's biome");
    }

    // ---- pure scheduling, labels, log fields --------------------------------------------------------------
    @Test
    void firstCallArmsAStaggerOfUpToFifteenTicksThenEvaluatesEveryTwentyTicks() {
        MiningAssistState state = new MiningAssistState(BOT);
        int stagger = BOT.hashCode() & 15;
        assertFalse(PoiDetector.due(state, 1000), "the first call only arms the stagger");
        assertEquals(1000 + stagger, state.nextPoiEvalTick());
        if (stagger > 0) {
            assertFalse(PoiDetector.due(state, 1000 + stagger - 1));
        }
        assertTrue(PoiDetector.due(state, 1000 + stagger));

        state.setNextPoiEvalTick(1000 + stagger + PoiScorer.EVAL_INTERVAL_TICKS);
        assertFalse(PoiDetector.due(state, 1000 + stagger + PoiScorer.EVAL_INTERVAL_TICKS - 1));
        assertTrue(PoiDetector.due(state, 1000 + stagger + PoiScorer.EVAL_INTERVAL_TICKS));
    }

    @Test
    void differentBotsGetDifferentStaggersSoTheyDoNotAllEvaluateTogether() {
        boolean differs = false;
        int first = -1;
        for (int i = 0; i < 32; i++) {
            MiningAssistState state = new MiningAssistState(new UUID(i * 7919L, i * 104729L));
            PoiDetector.due(state, 0);
            int offset = state.nextPoiEvalTick();
            assertTrue(offset >= 0 && offset <= 15);
            if (first < 0) {
                first = offset;
            } else if (offset != first) {
                differs = true;
            }
        }
        assertTrue(differs);
    }

    @Test
    void aTickThatMovedFarBackwardsIsDueAgainInsteadOfWaitingForever() {
        MiningAssistState state = new MiningAssistState(BOT);
        state.setNextPoiEvalTick(90_000);
        assertTrue(PoiDetector.due(state, 100));
    }

    @Test
    void labelsFollowTheBand() {
        PoiSignals empty = PoiSignals.empty();
        assertEquals("", PoiDetector.labelFor(PoiScorer.Band.NONE, empty));
        assertEquals("cavern", PoiDetector.labelFor(PoiScorer.Band.CAVERN_ONLY, empty));
        assertEquals("warden_risk", PoiDetector.labelFor(PoiScorer.Band.MANDATORY, empty));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, PoiDetector.labelFor(PoiScorer.Band.POSSIBLE, empty));

        PoiEvidenceWindow window = new PoiEvidenceWindow();
        for (int i = 0; i < 6; i++) {
            BlockFacts rail = facts("rail");
            window.observe(new BlockPos(i, 64, 0), rail.bucket(), rail.poiFlags(), 1, false);
        }
        PoiSignals mineshaft = PoiAssembler.assemble(window, 0.5D, 64.0D, 0.5D, null, null, 16.0D, OVERWORLD,
                List.of(OVERWORLD));
        assertEquals(PoiLabeler.MINESHAFT, PoiDetector.labelFor(PoiScorer.Band.STRUCTURE_CERTAIN, mineshaft));
    }

    @Test
    void logFieldsAreEvenKeyValuePairsWithoutASingleNullValue() {
        PoiScorer.PoiScore score = PoiScorer.evaluate(PoiSignals.empty());
        PoiDetector.Result result = new PoiDetector.Result(true, PoiScorer.Band.NONE, score, "", false, true,
                new BlockPos(1, 2, 3), 4, 0, 0, "minecraft:lush_caves", false);
        Object[] fields = result.logFields();
        assertEquals(0, fields.length % 2);
        for (int i = 0; i < fields.length; i += 2) {
            assertTrue(fields[i] instanceof String, "key at " + i);
            assertTrue(fields[i + 1] != null, "value of " + fields[i]);
        }
        assertTrue(List.of(fields).contains("band"));
        assertTrue(List.of(fields).contains("1,2,3"));
    }

    @Test
    void theNotRunResultIsInertAndLogsSafely() {
        PoiDetector.Result notRun = PoiDetector.Result.NOT_RUN;
        assertFalse(notRun.evaluated());
        assertFalse(notRun.confirmed());
        assertFalse(notRun.bandChanged());
        assertEquals(0, notRun.logFields().length % 2);
    }
}
