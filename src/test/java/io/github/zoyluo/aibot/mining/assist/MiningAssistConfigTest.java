package io.github.zoyluo.aibot.mining.assist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Advisor;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.CavernKeylessPolicy;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Detour;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Edits;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Explore;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.ModeSource;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.NoticeRecipients;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Poi;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Route;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Safety;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Sense;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.Tick;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig.UnavailablePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningAssistConfigTest {
    private static final Function<String, String> NO_ENV = key -> null;

    private static Function<String, String> env(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map::get;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** Parses {@code {"miningAssist": <body>}} with no env, shipped default ALL and no harness switch. */
    private static MiningAssistConfig config(String miningAssistBody) {
        return MiningAssistConfig.parse(json("{\"miningAssist\":" + miningAssistBody + "}"), NO_ENV, AssistMode.ALL,
                false);
    }

    private static MiningAssistConfig defaults() {
        return MiningAssistConfig.parse(null, NO_ENV, AssistMode.ALL, false);
    }

    private static boolean warned(MiningAssistConfig config, String fragment) {
        return config.warnings().stream().anyMatch(w -> w.contains(fragment));
    }

    // ---- defaults ------------------------------------------------------------------------------

    @Test
    void defaultsMatchTheDesignTable() {
        MiningAssistConfig c = defaults();

        assertEquals(AssistMode.ALL, c.mode());
        assertEquals(ModeSource.DEFAULT, c.modeSource());
        assertFalse(c.harnessOff());
        assertTrue(c.warnings().isEmpty());

        assertEquals(new Sense(40, 640, true, false), c.sense());
        assertEquals(new Tick(38.0D, 48.0D), c.tick());
        assertEquals(new Route(100), c.route());
        assertEquals(new Detour(true, 25, 1.2D, 12, 6, 2, 300, 200, 24, 3, 4, 4, 90), c.detour());
        assertEquals(new Safety(true), c.safety());
        assertEquals(new Advisor(true, "", 8, 512, 6, 400, 3, 6000), c.advisor());
        assertEquals(new Edits(true), c.edits());
        assertEquals(new Explore(false, false, 28), c.explore());

        Poi poi = c.poi();
        assertTrue(poi.enabled());
        assertEquals(0.40D, poi.possibleScore());
        assertEquals(0.80D, poi.structureCertainScore());
        assertEquals(0.175D, poi.cavernOpenFractionLow());
        assertEquals(0.554D, poi.cavernOpenFractionHigh());
        assertEquals(0.379D, poi.cavernOpenFractionSpan(), 1e-12);
        assertTrue(poi.habitationDowngrade());
        assertTrue(poi.useOwnBiome());
        assertEquals(40, poi.dedupeRadius());
        assertEquals(3, poi.maxHoldsPerMission());
        assertEquals(160, poi.holdDeadlineTicks());
        assertTrue(poi.announceHold());
        assertEquals(NoticeRecipients.AUTHORIZED, poi.noticeRecipients());
        assertEquals(UnavailablePolicy.STOP_IF_STRUCTURE, poi.unavailablePolicy());
        assertEquals(CavernKeylessPolicy.NOTIFY_ONLY, poi.cavernKeylessPolicy());
        assertEquals(List.of("minecraft:overworld"), poi.cavernDimensions());
    }

    @Test
    void derivedDefaultsAndConstants() {
        MiningAssistConfig c = defaults();

        assertEquals(4, c.detour().minFreeSlotsRareBatch());
        assertEquals(60, Detour.LEASE_PER_BREAK_TICKS);
        assertEquals(600, Detour.LEASE_CAP_TICKS);
        assertEquals(3200, Detour.MIN_INTERVAL_CAP_TICKS);
        assertTrue(c.poi().cavernEnabledIn("minecraft:overworld"));
        assertFalse(c.poi().cavernEnabledIn("minecraft:the_nether"));
        assertFalse(c.poi().cavernEnabledIn(null));
        assertEquals("authorized", NoticeRecipients.AUTHORIZED.wireName());
        assertEquals("stop_if_structure", UnavailablePolicy.STOP_IF_STRUCTURE.wireName());
        assertEquals("notify_only", CavernKeylessPolicy.NOTIFY_ONLY.wireName());
    }

    @Test
    void defaultsFactoryEqualsParsingNothing() {
        assertEquals(defaults(), MiningAssistConfig.defaults(AssistMode.ALL, false));
        assertEquals(MiningAssistConfig.parse(new JsonObject(), NO_ENV, AssistMode.SENSE, true),
                MiningAssistConfig.defaults(AssistMode.SENSE, true));
    }

    @Test
    void shadowLogDefaultsToTrueOnlyInSenseMode() {
        for (AssistMode shipped : AssistMode.values()) {
            MiningAssistConfig c = MiningAssistConfig.parse(null, NO_ENV, shipped, false);
            assertEquals(shipped == AssistMode.SENSE, c.sense().shadowLog(), "shipped " + shipped);
        }
        // The resolved mode decides, so an env override to sense turns it on.
        assertTrue(MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, "sense"), AssistMode.ALL, false)
                .sense().shadowLog());
        // An explicit key wins either way.
        assertFalse(MiningAssistConfig.parse(json("{\"miningAssist\":{\"sense\":{\"shadowLog\":false}}}"), NO_ENV,
                AssistMode.SENSE, false).sense().shadowLog());
        assertTrue(config("{\"sense\":{\"shadowLog\":true}}").sense().shadowLog());
    }

    // ---- missing / malformed sections ----------------------------------------------------------

    @Test
    void missingMiningAssistKeyGivesDefaults() {
        JsonObject root = json("{\"profile\":\"local\",\"brain\":{\"model\":\"x\"},\"perception\":{\"radius\":16}}");

        MiningAssistConfig c = MiningAssistConfig.parse(root, NO_ENV, AssistMode.SENSE, false);

        assertEquals(MiningAssistConfig.parse(null, NO_ENV, AssistMode.SENSE, false), c);
        assertEquals(AssistMode.SENSE, c.mode());
        assertEquals(ModeSource.DEFAULT, c.modeSource());
        assertTrue(c.warnings().isEmpty());
    }

    @Test
    void emptyMiningAssistObjectGivesDefaultsWithoutWarnings() {
        MiningAssistConfig c = config("{}");

        assertEquals(defaults(), c);
        assertTrue(c.warnings().isEmpty());
    }

    @Test
    void miningAssistThatIsNotAnObjectIsIgnored() {
        for (String body : new String[] {"5", "\"all\"", "[]", "true"}) {
            MiningAssistConfig c = config(body);

            assertEquals(AssistMode.ALL, c.mode(), body);
            assertEquals(ModeSource.DEFAULT, c.modeSource(), body);
            assertEquals(defaults().detour(), c.detour(), body);
            assertTrue(warned(c, "miningAssist"), body);
        }
    }

    @Test
    void jsonNullMiningAssistIsTreatedAsAbsent() {
        MiningAssistConfig c = config("null");

        assertEquals(defaults(), c);
    }

    @Test
    void aSectionThatIsNotAnObjectFallsBackToItsDefaults() {
        MiningAssistConfig c = config("{\"detour\":5,\"poi\":\"x\",\"sense\":[1]}");

        assertEquals(defaults().detour(), c.detour());
        assertEquals(defaults().poi(), c.poi());
        assertEquals(defaults().sense(), c.sense());
        assertTrue(warned(c, "miningAssist.detour"));
        assertTrue(warned(c, "miningAssist.poi"));
    }

    // ---- mode precedence -----------------------------------------------------------------------

    @Test
    void shippedDefaultIsUsedWhenNeitherEnvNorFileSpecifiesAMode() {
        for (AssistMode shipped : AssistMode.values()) {
            MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{}}"), NO_ENV, shipped, false);

            assertEquals(shipped, c.mode());
            assertEquals(ModeSource.DEFAULT, c.modeSource());
        }
    }

    @Test
    void fileModeBeatsTheShippedDefault() {
        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\"poi\"}}"), NO_ENV,
                AssistMode.SENSE, false);

        assertEquals(AssistMode.POI, c.mode());
        assertEquals(ModeSource.FILE, c.modeSource());
    }

    @Test
    void envModeBeatsFileMode() {
        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\"all\"}}"),
                env(MiningAssistConfig.ENV_MODE, "detour"), AssistMode.SENSE, false);

        assertEquals(AssistMode.DETOUR, c.mode());
        assertEquals(ModeSource.ENV, c.modeSource());
    }

    @Test
    void envModeBeatsTheShippedDefaultWithNoFile() {
        MiningAssistConfig c = MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, "off"), AssistMode.ALL,
                false);

        assertEquals(AssistMode.OFF, c.mode());
        assertEquals(ModeSource.ENV, c.modeSource());
    }

    @Test
    void everyModeNameIsAcceptedFromEnvAndFile() {
        for (AssistMode expected : AssistMode.values()) {
            String name = expected.name().toLowerCase(Locale.ROOT);
            AssistMode other = expected == AssistMode.OFF ? AssistMode.ALL : AssistMode.OFF;

            assertEquals(expected, MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, name), other, false)
                    .mode(), "env " + name);
            assertEquals(expected, MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\"" + name + "\"}}"),
                    NO_ENV, other, false).mode(), "file " + name);
        }
    }

    @Test
    void envModeIsCaseInsensitiveAndTrimmed() {
        assertEquals(AssistMode.DETOUR, MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, "  DeTouR \n"),
                AssistMode.OFF, false).mode());
        assertEquals(AssistMode.POI, MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\" POI \"}}"), NO_ENV,
                AssistMode.OFF, false).mode());
    }

    @Test
    void blankEnvValueCountsAsUnspecified() {
        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\"poi\"}}"),
                env(MiningAssistConfig.ENV_MODE, "   "), AssistMode.SENSE, false);

        assertEquals(AssistMode.POI, c.mode());
        assertEquals(ModeSource.FILE, c.modeSource());
        assertTrue(c.warnings().isEmpty());
        assertEquals(AssistMode.SENSE, MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, ""),
                AssistMode.SENSE, false).mode());
    }

    @Test
    void anExplicitButUnknownEnvModeFailsClosedToOff() {
        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\"all\"}}"),
                env(MiningAssistConfig.ENV_MODE, "detor"), AssistMode.ALL, false);

        assertEquals(AssistMode.OFF, c.mode());
        assertEquals(ModeSource.ENV, c.modeSource());
        assertTrue(warned(c, MiningAssistConfig.ENV_MODE));
    }

    @Test
    void anExplicitButUnknownFileModeFailsClosedToOff() {
        MiningAssistConfig c = config("{\"mode\":\"turbo\"}");

        assertEquals(AssistMode.OFF, c.mode());
        assertEquals(ModeSource.FILE, c.modeSource());
        assertTrue(warned(c, "miningAssist.mode"));
    }

    @Test
    void aWrongTypedFileModeIsIgnored() {
        for (String value : new String[] {"5", "true", "[\"all\"]", "{\"x\":1}"}) {
            MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":" + value + "}}"), NO_ENV,
                    AssistMode.DETOUR, false);

            assertEquals(AssistMode.DETOUR, c.mode(), value);
            assertEquals(ModeSource.DEFAULT, c.modeSource(), value);
            assertTrue(warned(c, "miningAssist.mode"), value);
        }
        assertEquals(ModeSource.DEFAULT, config("{\"mode\":null}").modeSource());
        assertEquals(ModeSource.DEFAULT, config("{\"mode\":\"  \"}").modeSource());
    }

    @Test
    void aNullShippedDefaultMeansOff() {
        assertEquals(AssistMode.OFF, MiningAssistConfig.parse(null, NO_ENV, null, false).mode());
    }

    @Test
    void aNullEnvFunctionIsTolerated() {
        MiningAssistConfig c = MiningAssistConfig.parse(null, null, AssistMode.POI, false);

        assertEquals(AssistMode.POI, c.mode());
        assertFalse(c.deterministic(false));
    }

    @Test
    void onlyTheDocumentedEnvKeysAreRead() {
        List<String> asked = new ArrayList<>();
        MiningAssistConfig.parse(null, key -> {
            asked.add(key);
            return null;
        }, AssistMode.ALL, false);

        assertEquals(List.of("AIBOT_MINING_ASSIST", "AIBOT_MINING_ASSIST_DETERMINISTIC"), asked);
    }

    // ---- harness default-off -------------------------------------------------------------------

    @Test
    void harnessOffAppliesWhenNeitherEnvNorFileSpecifiesAMode() {
        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{}}"), NO_ENV, AssistMode.SENSE, true);

        assertTrue(c.harnessOff());
        assertEquals(AssistMode.SENSE, c.mode(), "the harness flag is separate from the mode");
        assertFalse(c.enabledFor(false, true, false, false));
        assertTrue(c.enabledFor(true, true, false, false), "forceEnable opts a bot back in");
    }

    @Test
    void envBeatsTheHarnessDefault() {
        MiningAssistConfig c = MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, "detour"),
                AssistMode.SENSE, true);

        assertFalse(c.harnessOff());
        assertEquals(AssistMode.DETOUR, c.mode());
        assertTrue(c.enabledFor(false, true, false, false));
    }

    @Test
    void envOffAlsoBeatsTheHarnessDefaultAndStaysOff() {
        MiningAssistConfig c = MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, "off"),
                AssistMode.ALL, true);

        assertFalse(c.harnessOff());
        assertEquals(AssistMode.OFF, c.mode());
        assertFalse(c.enabledFor(true, true, false, false), "mode off wins even when forced");
    }

    @Test
    void fileModeBeatsTheHarnessDefault() {
        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\"poi\"}}"), NO_ENV,
                AssistMode.SENSE, true);

        assertFalse(c.harnessOff());
        assertEquals(AssistMode.POI, c.mode());
    }

    @Test
    void harnessOffIsInactiveWhenTheHarnessDidNotAskForIt() {
        assertFalse(MiningAssistConfig.parse(null, NO_ENV, AssistMode.ALL, false).harnessOff());
    }

    @Test
    void aBlankEnvAndAWrongTypedFileModeDoNotCountAsSpecifyingAMode() {
        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":7}}"),
                env(MiningAssistConfig.ENV_MODE, " "), AssistMode.SENSE, true);

        assertTrue(c.harnessOff());
    }

    // ---- partial keys, wrong types, unknown keys -----------------------------------------------

    @Test
    void partialKeysOverrideOnlyWhatIsGiven() {
        MiningAssistConfig c = config("{\"detour\":{\"minValue\":30,\"maxRadius\":8},"
                + "\"poi\":{\"dedupeRadius\":60,\"announceHold\":false},"
                + "\"advisor\":{\"model\":\"cheap-model\"}}");

        MiningAssistConfig d = defaults();
        assertEquals(new Detour(true, 30, 1.2D, 8, 6, 2, 300, 200, 24, 3, 4, 4, 90), c.detour());
        assertEquals(60, c.poi().dedupeRadius());
        assertFalse(c.poi().announceHold());
        assertEquals(d.poi().possibleScore(), c.poi().possibleScore());
        assertEquals(d.poi().noticeRecipients(), c.poi().noticeRecipients());
        assertEquals("cheap-model", c.advisor().model());
        assertEquals(d.advisor().maxTokens(), c.advisor().maxTokens());
        assertEquals(d.sense(), c.sense());
        assertEquals(d.tick(), c.tick());
        assertEquals(d.route(), c.route());
        assertEquals(d.safety(), c.safety());
        assertEquals(d.edits(), c.edits());
        assertEquals(d.explore(), c.explore());
        assertTrue(c.warnings().isEmpty());
    }

    @Test
    void everyKeyCanBeSetInOneDocument() {
        MiningAssistConfig c = config("{\"mode\":\"detour\","
                + "\"sense\":{\"raysPerTick\":20,\"globalRaysPerTick\":320,\"adaptiveThrottle\":false,\"shadowLog\":true},"
                + "\"tick\":{\"startWorkMs\":30,\"abortWorkMs\":40},"
                + "\"route\":{\"bucketMs\":150},"
                + "\"detour\":{\"enabled\":false,\"minValue\":40,\"minScore\":2.5,\"maxRadius\":10,\"maxUp\":5,"
                + "\"maxDown\":1,\"leaseTicks\":240,\"minIntervalTicks\":400,\"maxPerMission\":10,\"minFreeSlots\":5,"
                + "\"startHpMargin\":6,\"lavaClearRadius\":6,\"announceMinValue\":100},"
                + "\"safety\":{\"deepDarkVeto\":false},"
                + "\"poi\":{\"enabled\":false,\"possibleScore\":0.5,\"structureCertainScore\":0.9,"
                + "\"cavernOpenFraction\":[0.2,0.5],\"habitationDowngrade\":false,\"useOwnBiome\":false,"
                + "\"dedupeRadius\":50,\"maxHoldsPerMission\":2,\"holdDeadlineTicks\":200,\"announceHold\":false,"
                + "\"noticeRecipients\":\"broadcast\",\"unavailablePolicy\":\"stop_if_possible\","
                + "\"cavernKeylessPolicy\":\"stop_if_possible\",\"cavernDimensions\":[\"minecraft:the_nether\"]},"
                + "\"advisor\":{\"enabled\":false,\"model\":\"m\",\"timeoutSeconds\":5,\"maxTokens\":1024,"
                + "\"maxConsultsPerMission\":3,\"minIntervalTicks\":800,\"breakerFailures\":5,\"breakerOpenTicks\":1200},"
                + "\"edits\":{\"sidecar\":false},"
                + "\"explore\":{\"legChooser\":true,\"frontier\":true,\"frontierMaxRadius\":40}}");

        assertEquals(AssistMode.DETOUR, c.mode());
        assertEquals(new Sense(20, 320, false, true), c.sense());
        assertEquals(new Tick(30.0D, 40.0D), c.tick());
        assertEquals(new Route(150), c.route());
        assertEquals(new Detour(false, 40, 2.5D, 10, 5, 1, 240, 400, 10, 5, 6, 6, 100), c.detour());
        assertEquals(new Safety(false), c.safety());
        assertEquals(new Poi(false, 0.5D, 0.9D, 0.2D, 0.5D, false, false, 50, 2, 200, false,
                NoticeRecipients.BROADCAST, UnavailablePolicy.STOP_IF_POSSIBLE, CavernKeylessPolicy.STOP_IF_POSSIBLE,
                List.of("minecraft:the_nether")), c.poi());
        assertEquals(new Advisor(false, "m", 5, 1024, 3, 800, 5, 1200), c.advisor());
        assertEquals(new Edits(false), c.edits());
        assertEquals(new Explore(true, true, 40), c.explore());
        assertTrue(c.warnings().isEmpty());
    }

    @Test
    void unknownKeysAtEveryLevelAreIgnored() {
        JsonObject root = json("{\"unrelated\":1,\"miningAssist\":{\"future\":true,\"sense\":{\"rays\":5,"
                + "\"raysPerTick\":40},\"poi\":{\"newKnob\":[1,2,3]},\"detour\":{},\"zzz\":{\"a\":1}}}");

        MiningAssistConfig c = MiningAssistConfig.parse(root, NO_ENV, AssistMode.ALL, false);

        assertEquals(defaults(), c);
        assertTrue(c.warnings().isEmpty());
    }

    @Test
    void wrongTypedValuesFallBackToDefaultsAndWarn() {
        MiningAssistConfig c = config("{"
                + "\"sense\":{\"raysPerTick\":\"40\",\"adaptiveThrottle\":\"yes\",\"shadowLog\":1},"
                + "\"tick\":{\"startWorkMs\":true,\"abortWorkMs\":[1]},"
                + "\"route\":{\"bucketMs\":{\"a\":1}},"
                + "\"detour\":{\"enabled\":\"true\",\"minValue\":\"25\",\"minScore\":\"1.2\",\"maxRadius\":false},"
                + "\"safety\":{\"deepDarkVeto\":0},"
                + "\"poi\":{\"enabled\":null,\"possibleScore\":\"high\",\"dedupeRadius\":[],\"announceHold\":\"no\","
                + "\"noticeRecipients\":5,\"unavailablePolicy\":true,\"cavernKeylessPolicy\":{}},"
                + "\"advisor\":{\"model\":42,\"maxTokens\":\"512\",\"enabled\":1},"
                + "\"edits\":{\"sidecar\":\"true\"},"
                + "\"explore\":{\"frontier\":\"true\",\"frontierMaxRadius\":\"28\"}}");

        MiningAssistConfig d = defaults();
        assertEquals(d.sense(), c.sense());
        assertEquals(d.tick(), c.tick());
        assertEquals(d.route(), c.route());
        assertEquals(d.detour(), c.detour());
        assertEquals(d.safety(), c.safety());
        assertEquals(d.poi(), c.poi());
        assertEquals(d.advisor(), c.advisor());
        assertEquals(d.edits(), c.edits());
        assertEquals(d.explore(), c.explore());
        assertTrue(warned(c, "miningAssist.sense.raysPerTick"));
        assertTrue(warned(c, "miningAssist.poi.noticeRecipients"));
        assertFalse(warned(c, "miningAssist.poi.enabled"), "JSON null is simply absent");
    }

    @Test
    void integerKeysRejectFractionalNumbers() {
        MiningAssistConfig c = config("{\"detour\":{\"minValue\":25.5,\"maxRadius\":12.0,\"maxUp\":1e1}}");

        assertEquals(25, c.detour().minValue(), "fractional value ignored, default kept");
        assertEquals(12, c.detour().maxRadius(), "12.0 is an integer");
        assertEquals(10, c.detour().maxUp(), "1e1 is an integer");
        assertTrue(warned(c, "miningAssist.detour.minValue"));
    }

    @Test
    void decimalKeysAcceptIntegersAndFractions() {
        assertEquals(2.0D, config("{\"detour\":{\"minScore\":2}}").detour().minScore());
        assertEquals(1.75D, config("{\"detour\":{\"minScore\":1.75}}").detour().minScore());
        assertEquals(37.5D, config("{\"tick\":{\"startWorkMs\":37.5}}").tick().startWorkMs());
    }

    // ---- clamping ------------------------------------------------------------------------------

    private interface IntGetter {
        int get(MiningAssistConfig config);
    }

    private interface DoubleGetter {
        double get(MiningAssistConfig config);
    }

    private static void assertIntBounds(String section, String key, int min, int max, IntGetter getter) {
        String path = "miningAssist." + section + "." + key;
        assertEquals(min, getter.get(config("{\"" + section + "\":{\"" + key + "\":" + (min - 1) + "}}")), path + " below");
        assertEquals(min, getter.get(config("{\"" + section + "\":{\"" + key + "\":-1000000000000}}")), path + " far below");
        assertEquals(max, getter.get(config("{\"" + section + "\":{\"" + key + "\":" + (max + 1) + "}}")), path + " above");
        assertEquals(max, getter.get(config("{\"" + section + "\":{\"" + key + "\":1e30}}")), path + " far above");
        MiningAssistConfig atMin = config("{\"" + section + "\":{\"" + key + "\":" + min + "}}");
        MiningAssistConfig atMax = config("{\"" + section + "\":{\"" + key + "\":" + max + "}}");
        assertEquals(min, getter.get(atMin), path + " at min");
        assertEquals(max, getter.get(atMax), path + " at max");
        assertTrue(atMin.warnings().isEmpty() && atMax.warnings().isEmpty(), path + " boundary values are not warnings");
        assertTrue(warned(config("{\"" + section + "\":{\"" + key + "\":" + (max + 1) + "}}"), path), path + " warns");
    }

    private static void assertDoubleBounds(String section, String key, double min, double max, DoubleGetter getter) {
        String path = "miningAssist." + section + "." + key;
        assertEquals(min, getter.get(config("{\"" + section + "\":{\"" + key + "\":" + (min - 1) + "}}")), path + " below");
        assertEquals(max, getter.get(config("{\"" + section + "\":{\"" + key + "\":" + (max + 1000) + "}}")), path + " above");
        assertEquals(min, getter.get(config("{\"" + section + "\":{\"" + key + "\":" + min + "}}")), path + " at min");
        assertEquals(max, getter.get(config("{\"" + section + "\":{\"" + key + "\":" + max + "}}")), path + " at max");
        assertTrue(warned(config("{\"" + section + "\":{\"" + key + "\":" + (max + 1000) + "}}"), path), path + " warns");
    }

    @Test
    void senseValuesAreClamped() {
        assertIntBounds("sense", "raysPerTick", 1, 256, c -> c.sense().raysPerTick());
        assertIntBounds("sense", "globalRaysPerTick", 1, 4096, c -> c.sense().globalRaysPerTick());
    }

    @Test
    void tickAndRouteValuesAreClamped() {
        assertDoubleBounds("tick", "startWorkMs", 1.0D, 100.0D, c -> c.tick().startWorkMs());
        assertIntBounds("route", "bucketMs", 30, 1000, c -> c.route().bucketMs());
    }

    @Test
    void abortThresholdIsNeverBelowTheStartThreshold() {
        MiningAssistConfig raised = config("{\"tick\":{\"startWorkMs\":60}}");
        assertEquals(60.0D, raised.tick().startWorkMs());
        assertEquals(60.0D, raised.tick().abortWorkMs(), "default abort 48 was raised to the start value");
        assertTrue(warned(raised, "tick.abortWorkMs"));

        MiningAssistConfig both = config("{\"tick\":{\"startWorkMs\":70,\"abortWorkMs\":50}}");
        assertEquals(70.0D, both.tick().abortWorkMs());

        MiningAssistConfig equal = config("{\"tick\":{\"startWorkMs\":45,\"abortWorkMs\":45}}");
        assertEquals(45.0D, equal.tick().abortWorkMs());
        assertTrue(equal.warnings().isEmpty());

        MiningAssistConfig ok = config("{\"tick\":{\"startWorkMs\":10,\"abortWorkMs\":90}}");
        assertEquals(90.0D, ok.tick().abortWorkMs());
    }

    @Test
    void abortThresholdIsNeverBelowTheFixedRearmLevel() {
        assertEquals(40.0D, Tick.MIN_ABORT_WORK_MS);
        assertDoubleBounds("tick", "abortWorkMs", 40.0D, 100.0D, c -> c.tick().abortWorkMs());

        MiningAssistConfig low = config("{\"tick\":{\"startWorkMs\":20,\"abortWorkMs\":30}}");
        assertEquals(20.0D, low.tick().startWorkMs());
        assertEquals(40.0D, low.tick().abortWorkMs(), "a lower abort would re-arm while still over budget");
        assertTrue(warned(low, "miningAssist.tick.abortWorkMs"));

        MiningAssistConfig exactlyAtTheFloor = config("{\"tick\":{\"abortWorkMs\":40}}");
        assertEquals(40.0D, exactlyAtTheFloor.tick().abortWorkMs());
        assertTrue(exactlyAtTheFloor.warnings().isEmpty());
    }

    @Test
    void everyTickPairTheConfigCanProduceIsAcceptedByTickHeadroom() {
        String[] samples = {"-1e400", "-1e9", "-1", "0", "0.5", "1", "20", "37.9", "38", "39.99", "40", "41", "47.9",
                "48", "60", "99.9", "100", "101", "1e9", "1e400", "\"x\"", "null"};
        for (String start : samples) {
            for (String abort : samples) {
                String body = "{\"tick\":{\"startWorkMs\":" + start + ",\"abortWorkMs\":" + abort + "}}";
                Tick tick = config(body).tick();

                // Thresholds.of throws on an invalid pair; sanitized() would silently swap in the defaults.
                TickHeadroom.Thresholds thresholds = TickHeadroom.Thresholds.of(tick.startWorkMs(), tick.abortWorkMs());

                assertEquals(tick.startWorkMs(), thresholds.startWorkMs(), body);
                assertEquals(tick.abortWorkMs(), thresholds.abortWorkMs(), body);
                assertTrue(tick.startWorkMs() >= 1.0D && tick.abortWorkMs() <= 100.0D, body);
            }
        }
    }

    @Test
    void detourValuesAreClamped() {
        assertIntBounds("detour", "minValue", 1, 1000, c -> c.detour().minValue());
        assertDoubleBounds("detour", "minScore", 0.1D, 100.0D, c -> c.detour().minScore());
        assertIntBounds("detour", "maxRadius", 1, 32, c -> c.detour().maxRadius());
        assertIntBounds("detour", "maxUp", 0, 16, c -> c.detour().maxUp());
        assertIntBounds("detour", "maxDown", 0, 16, c -> c.detour().maxDown());
        assertIntBounds("detour", "leaseTicks", 60, 600, c -> c.detour().leaseTicks());
        assertIntBounds("detour", "minIntervalTicks", 20, 3200, c -> c.detour().minIntervalTicks());
        assertIntBounds("detour", "maxPerMission", 0, 200, c -> c.detour().maxPerMission());
        assertIntBounds("detour", "minFreeSlots", 1, 32, c -> c.detour().minFreeSlots());
        assertIntBounds("detour", "startHpMargin", 0, 20, c -> c.detour().startHpMargin());
        assertIntBounds("detour", "lavaClearRadius", 1, 16, c -> c.detour().lavaClearRadius());
        assertIntBounds("detour", "announceMinValue", 0, 1000, c -> c.detour().announceMinValue());
    }

    @Test
    void leaseNeverExceedsTheCompileTimeCap() {
        assertEquals(Detour.LEASE_CAP_TICKS, config("{\"detour\":{\"leaseTicks\":100000}}").detour().leaseTicks());
        assertEquals(Detour.MIN_INTERVAL_CAP_TICKS,
                config("{\"detour\":{\"minIntervalTicks\":100000}}").detour().minIntervalTicks());
    }

    @Test
    void poiValuesAreClamped() {
        assertDoubleBounds("poi", "possibleScore", 0.05D, 1.0D, c -> c.poi().possibleScore());
        assertIntBounds("poi", "dedupeRadius", 1, 256, c -> c.poi().dedupeRadius());
        assertIntBounds("poi", "maxHoldsPerMission", 0, 32, c -> c.poi().maxHoldsPerMission());
        assertIntBounds("poi", "holdDeadlineTicks", 20, 1200, c -> c.poi().holdDeadlineTicks());
        assertEquals(1.0D, config("{\"poi\":{\"structureCertainScore\":5}}").poi().structureCertainScore());
    }

    @Test
    void structureCertainScoreIsNeverBelowThePossibleScore() {
        MiningAssistConfig c = config("{\"poi\":{\"possibleScore\":0.9,\"structureCertainScore\":0.3}}");

        assertEquals(0.9D, c.poi().possibleScore());
        assertEquals(0.9D, c.poi().structureCertainScore());
        assertTrue(warned(c, "poi.structureCertainScore"));

        MiningAssistConfig floorHit = config("{\"poi\":{\"possibleScore\":0.2,\"structureCertainScore\":0}}");
        assertEquals(0.2D, floorHit.poi().structureCertainScore(), "clamped to 0.05 then raised to possible");
    }

    @Test
    void advisorValuesAreClamped() {
        assertIntBounds("advisor", "timeoutSeconds", 1, 10, c -> c.advisor().timeoutSeconds());
        assertIntBounds("advisor", "maxTokens", 64, 16384, c -> c.advisor().maxTokens());
        assertIntBounds("advisor", "maxConsultsPerMission", 0, 64, c -> c.advisor().maxConsultsPerMission());
        assertIntBounds("advisor", "minIntervalTicks", 0, 12000, c -> c.advisor().minIntervalTicks());
        assertIntBounds("advisor", "breakerFailures", 1, 20, c -> c.advisor().breakerFailures());
        assertIntBounds("advisor", "breakerOpenTicks", 200, 72000, c -> c.advisor().breakerOpenTicks());
    }

    @Test
    void exploreRadiusIsClamped() {
        assertIntBounds("explore", "frontierMaxRadius", 8, 64, c -> c.explore().frontierMaxRadius());
    }

    @Test
    void theAdvisorModelIsTrimmedAndBounded() {
        assertEquals("deepseek-chat", config("{\"advisor\":{\"model\":\"  deepseek-chat \"}}").advisor().model());
        assertEquals("", config("{\"advisor\":{\"model\":\"   \"}}").advisor().model());
        String longName = "m".repeat(300);
        MiningAssistConfig c = config("{\"advisor\":{\"model\":\"" + longName + "\"}}");
        assertEquals(128, c.advisor().model().length());
        assertTrue(warned(c, "miningAssist.advisor.model"));
    }

    // ---- poi.cavernOpenFraction ----------------------------------------------------------------

    @Test
    void cavernOpenFractionTakesALowHighPair() {
        Poi poi = config("{\"poi\":{\"cavernOpenFraction\":[0.1,0.6]}}").poi();

        assertEquals(0.1D, poi.cavernOpenFractionLow());
        assertEquals(0.6D, poi.cavernOpenFractionHigh());
        assertEquals(0.5D, poi.cavernOpenFractionSpan(), 1e-12);
    }

    @Test
    void cavernOpenFractionClampsEachEndToUnitRange() {
        Poi poi = config("{\"poi\":{\"cavernOpenFraction\":[-0.5,3]}}").poi();

        assertEquals(0.0D, poi.cavernOpenFractionLow());
        assertEquals(1.0D, poi.cavernOpenFractionHigh());
    }

    @Test
    void invalidCavernOpenFractionKeepsTheDefaultPair() {
        String[] invalid = {"[0.5,0.5]", "[0.6,0.2]", "[0.3]", "[0.1,0.2,0.3]", "[]", "0.5", "\"a\"", "[\"a\",\"b\"]",
                "{\"low\":0.1,\"high\":0.5}", "[2,3]"};
        for (String value : invalid) {
            MiningAssistConfig c = config("{\"poi\":{\"cavernOpenFraction\":" + value + "}}");

            assertEquals(0.175D, c.poi().cavernOpenFractionLow(), value);
            assertEquals(0.554D, c.poi().cavernOpenFractionHigh(), value);
            assertTrue(warned(c, "cavernOpenFraction"), value);
        }
    }

    // ---- poi.cavernDimensions ------------------------------------------------------------------

    @Test
    void cavernDimensionsCanBeReplaced() {
        Poi poi = config("{\"poi\":{\"cavernDimensions\":[\"minecraft:overworld\",\"aether:the_aether\"]}}").poi();

        assertEquals(List.of("minecraft:overworld", "aether:the_aether"), poi.cavernDimensions());
        assertTrue(poi.cavernEnabledIn("aether:the_aether"));
        assertFalse(poi.cavernEnabledIn("minecraft:the_end"));
    }

    @Test
    void cavernDimensionsAreNormalisedAndDeduplicated() {
        Poi poi = config("{\"poi\":{\"cavernDimensions\":[\" Minecraft:Overworld \",\"overworld\",\"minecraft:the_nether\","
                + "\"MINECRAFT:THE_NETHER\"]}}").poi();

        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), poi.cavernDimensions());
    }

    @Test
    void malformedCavernDimensionEntriesAreDropped() {
        MiningAssistConfig c = config("{\"poi\":{\"cavernDimensions\":[\"minecraft:overworld\",5,\"\",\"bad key\","
                + "\"a:b:c\",null,\"x:\"]}}");

        assertEquals(List.of("minecraft:overworld"), c.poi().cavernDimensions());
        assertTrue(warned(c, "cavernDimensions"));
    }

    @Test
    void anExplicitEmptyCavernDimensionsListDisablesTheChannelEverywhere() {
        MiningAssistConfig c = config("{\"poi\":{\"cavernDimensions\":[]}}");

        assertEquals(List.of(), c.poi().cavernDimensions());
        assertFalse(c.poi().cavernEnabledIn("minecraft:overworld"));
        assertTrue(c.warnings().isEmpty());
    }

    @Test
    void cavernDimensionsWithNoValidEntryOrTheWrongTypeKeepTheDefault() {
        for (String value : new String[] {"[5,true]", "[\"bad key\"]", "\"minecraft:overworld\"", "7", "{}"}) {
            MiningAssistConfig c = config("{\"poi\":{\"cavernDimensions\":" + value + "}}");

            assertEquals(List.of("minecraft:overworld"), c.poi().cavernDimensions(), value);
            assertTrue(warned(c, "cavernDimensions"), value);
        }
    }

    @Test
    void cavernDimensionsAreCapped() {
        StringBuilder list = new StringBuilder("[");
        for (int i = 0; i < MiningAssistConfig.MAX_CAVERN_DIMENSIONS + 10; i++) {
            list.append(i == 0 ? "" : ",").append("\"ns:dim_").append(i).append('"');
        }
        list.append(']');

        Poi poi = config("{\"poi\":{\"cavernDimensions\":" + list + "}}").poi();

        assertEquals(MiningAssistConfig.MAX_CAVERN_DIMENSIONS, poi.cavernDimensions().size());
        assertEquals("ns:dim_0", poi.cavernDimensions().get(0));
    }

    // ---- enums ---------------------------------------------------------------------------------

    @Test
    void enumKeysAreCaseInsensitiveAndTrimmed() {
        Poi poi = config("{\"poi\":{\"noticeRecipients\":\" BROADCAST \",\"unavailablePolicy\":\"Notify_Only\","
                + "\"cavernKeylessPolicy\":\"STOP_IF_POSSIBLE\"}}").poi();

        assertEquals(NoticeRecipients.BROADCAST, poi.noticeRecipients());
        assertEquals(UnavailablePolicy.NOTIFY_ONLY, poi.unavailablePolicy());
        assertEquals(CavernKeylessPolicy.STOP_IF_POSSIBLE, poi.cavernKeylessPolicy());
    }

    @Test
    void everyDocumentedEnumValueParses() {
        assertEquals(NoticeRecipients.AUTHORIZED, NoticeRecipients.parse("authorized").orElseThrow());
        assertEquals(NoticeRecipients.BROADCAST, NoticeRecipients.parse("broadcast").orElseThrow());
        assertEquals(UnavailablePolicy.STOP_IF_STRUCTURE, UnavailablePolicy.parse("stop_if_structure").orElseThrow());
        assertEquals(UnavailablePolicy.NOTIFY_ONLY, UnavailablePolicy.parse("notify_only").orElseThrow());
        assertEquals(UnavailablePolicy.STOP_IF_POSSIBLE, UnavailablePolicy.parse("stop_if_possible").orElseThrow());
        assertEquals(CavernKeylessPolicy.NOTIFY_ONLY, CavernKeylessPolicy.parse("notify_only").orElseThrow());
        assertTrue(NoticeRecipients.parse(null).isEmpty());
        assertTrue(NoticeRecipients.parse("everyone").isEmpty());
        assertTrue(UnavailablePolicy.parse("").isEmpty());
        assertEquals(3, UnavailablePolicy.values().length);
        assertEquals(2, NoticeRecipients.values().length);
    }

    @Test
    void unknownEnumValuesKeepTheDefaultAndWarn() {
        MiningAssistConfig c = config("{\"poi\":{\"noticeRecipients\":\"everyone\",\"unavailablePolicy\":\"maybe\","
                + "\"cavernKeylessPolicy\":\"\"}}");

        assertEquals(NoticeRecipients.AUTHORIZED, c.poi().noticeRecipients());
        assertEquals(UnavailablePolicy.STOP_IF_STRUCTURE, c.poi().unavailablePolicy());
        assertEquals(CavernKeylessPolicy.NOTIFY_ONLY, c.poi().cavernKeylessPolicy());
        assertTrue(warned(c, "noticeRecipients"));
        assertTrue(warned(c, "unavailablePolicy"));
        assertTrue(warned(c, "cavernKeylessPolicy"));
    }

    // ---- deterministic derivation --------------------------------------------------------------

    @Test
    void deterministicIsTrueForHarnessDefaultForceOrEnvOne() {
        assertFalse(MiningAssistConfig.deterministic(false, false, NO_ENV));
        assertTrue(MiningAssistConfig.deterministic(true, false, NO_ENV), "harness default active");
        assertTrue(MiningAssistConfig.deterministic(false, true, NO_ENV), "bot force-enabled");
        assertTrue(MiningAssistConfig.deterministic(false, false, env(MiningAssistConfig.ENV_DETERMINISTIC, "1")),
                "env flag");
        assertTrue(MiningAssistConfig.deterministic(true, true, env(MiningAssistConfig.ENV_DETERMINISTIC, "1")));
    }

    @Test
    void deterministicEnvValueMustBeExactlyOne() {
        for (String value : new String[] {"0", "", " ", "true", "yes", "2", "11", "one", "-1", "1.0", "on"}) {
            assertFalse(MiningAssistConfig.deterministic(false, false, env(MiningAssistConfig.ENV_DETERMINISTIC, value)),
                    "value '" + value + "'");
        }
        assertTrue(MiningAssistConfig.deterministic(false, false, env(MiningAssistConfig.ENV_DETERMINISTIC, " 1 ")));
        assertFalse(MiningAssistConfig.deterministic(false, false, null), "null env function is tolerated");
        assertFalse(MiningAssistConfig.deterministic(false, false, env(MiningAssistConfig.ENV_MODE, "1")),
                "only the deterministic key counts");
    }

    @Test
    void deterministicTruthTable() {
        for (int bits = 0; bits < 8; bits++) {
            boolean harness = (bits & 1) != 0;
            boolean forced = (bits & 2) != 0;
            boolean envOn = (bits & 4) != 0;
            Function<String, String> environment = envOn ? env(MiningAssistConfig.ENV_DETERMINISTIC, "1") : NO_ENV;

            assertEquals(harness || forced || envOn, MiningAssistConfig.deterministic(harness, forced, environment),
                    "harness=" + harness + " forced=" + forced + " env=" + envOn);
        }
    }

    @Test
    void theInstanceDeterministicFlagUsesTheResolvedHarnessStateAndTheEnvAtParseTime() {
        MiningAssistConfig plain = MiningAssistConfig.parse(null, NO_ENV, AssistMode.ALL, false);
        assertFalse(plain.deterministic(false));
        assertTrue(plain.deterministic(true), "forced bot");

        MiningAssistConfig harness = MiningAssistConfig.parse(null, NO_ENV, AssistMode.ALL, true);
        assertTrue(harness.harnessOff());
        assertTrue(harness.deterministic(false), "harness default active");

        MiningAssistConfig withEnv = MiningAssistConfig.parse(null,
                env(MiningAssistConfig.ENV_DETERMINISTIC, "1"), AssistMode.ALL, false);
        assertTrue(withEnv.deterministic(false));
    }

    @Test
    void anExplicitModeDeactivatesTheHarnessDefaultAndSoItsDeterminism() {
        MiningAssistConfig c = MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE, "all"), AssistMode.SENSE,
                true);

        assertFalse(c.harnessOff());
        assertFalse(c.deterministic(false), "env beats the harness default, so it is not deterministic either");
        assertTrue(c.deterministic(true));
    }

    @Test
    void adaptiveThrottleIsOffWhenDeterministic() {
        MiningAssistConfig plain = defaults();
        assertTrue(plain.adaptiveThrottleActive(false));
        assertFalse(plain.adaptiveThrottleActive(true));

        MiningAssistConfig harness = MiningAssistConfig.parse(null, NO_ENV, AssistMode.ALL, true);
        assertFalse(harness.adaptiveThrottleActive(false));

        MiningAssistConfig off = config("{\"sense\":{\"adaptiveThrottle\":false}}");
        assertFalse(off.adaptiveThrottleActive(false), "the key itself still applies");
    }

    // ---- derived activity helpers --------------------------------------------------------------

    @Test
    void featureHelpersFollowTheModeAndTheirEnableKeys() {
        for (AssistMode mode : AssistMode.values()) {
            MiningAssistConfig c = MiningAssistConfig.parse(null, env(MiningAssistConfig.ENV_MODE,
                    mode.name().toLowerCase(Locale.ROOT)), AssistMode.OFF, false);

            assertEquals(mode != AssistMode.OFF, c.senseActive(), "sense " + mode);
            assertEquals(mode == AssistMode.DETOUR || mode == AssistMode.ALL, c.detourActive(), "detour " + mode);
            assertEquals(mode == AssistMode.POI || mode == AssistMode.ALL, c.poiActive(), "poi " + mode);
            assertEquals(c.poiActive(), c.advisorActive(true), "advisor " + mode);
            assertFalse(c.advisorActive(false), "advisor needs a key " + mode);
        }
    }

    @Test
    void enableKeysSwitchTheirFeatureOffEvenInAllMode() {
        MiningAssistConfig c = config("{\"detour\":{\"enabled\":false},\"poi\":{\"enabled\":false}}");

        assertEquals(AssistMode.ALL, c.mode());
        assertTrue(c.senseActive());
        assertFalse(c.detourActive());
        assertFalse(c.poiActive());
        assertFalse(c.advisorActive(true), "no consults without R3");

        assertFalse(config("{\"advisor\":{\"enabled\":false}}").advisorActive(true));
        assertTrue(config("{}").advisorActive(true));
    }

    @Test
    void enabledForCombinesTheResolvedModeWithTheCallersLiveInputs() {
        MiningAssistConfig c = defaults();

        assertTrue(c.enabledFor(false, true, false, false));
        assertFalse(c.enabledFor(false, false, false, false), "origin");
        assertFalse(c.enabledFor(false, true, true, false), "audit session");
        assertFalse(c.enabledFor(false, true, false, true), "tps degraded");
        assertFalse(config("{\"mode\":\"off\"}").enabledFor(true, true, false, false));
    }

    // ---- immutability and determinism ----------------------------------------------------------

    @Test
    void theConfigExposesNoMutableState() {
        MiningAssistConfig c = defaults();

        assertThrows(UnsupportedOperationException.class, () -> c.poi().cavernDimensions().add("x:y"));
        assertThrows(UnsupportedOperationException.class, () -> c.warnings().add("x"));

        List<String> source = new ArrayList<>(List.of("minecraft:overworld"));
        Poi poi = new Poi(true, 0.4D, 0.8D, 0.175D, 0.554D, true, true, 40, 3, 160, true,
                NoticeRecipients.AUTHORIZED, UnavailablePolicy.STOP_IF_STRUCTURE, CavernKeylessPolicy.NOTIFY_ONLY, source);
        source.add("minecraft:the_end");
        assertEquals(List.of("minecraft:overworld"), poi.cavernDimensions(), "the record copies its list");
    }

    @Test
    void parsingTheSameInputTwiceGivesEqualConfigs() {
        JsonObject root = json("{\"miningAssist\":{\"mode\":\"poi\",\"detour\":{\"minValue\":999999},"
                + "\"poi\":{\"cavernDimensions\":[\"minecraft:the_nether\"]}}}");

        MiningAssistConfig first = MiningAssistConfig.parse(root, env(MiningAssistConfig.ENV_DETERMINISTIC, "1"),
                AssistMode.SENSE, true);
        MiningAssistConfig second = MiningAssistConfig.parse(root, env(MiningAssistConfig.ENV_DETERMINISTIC, "1"),
                AssistMode.SENSE, true);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
        assertFalse(first.equals(MiningAssistConfig.parse(root, NO_ENV, AssistMode.SENSE, true)),
                "the env-derived deterministic flag is part of the value");
        assertTrue(first.toString().contains("mode=POI"));
    }

    @Test
    void parsingDoesNotMutateTheInputDocument() {
        JsonObject root = json("{\"miningAssist\":{\"detour\":{\"minValue\":999999},\"poi\":{\"cavernDimensions\":[\"X\"]}}}");
        String before = root.toString();

        MiningAssistConfig.parse(root, NO_ENV, AssistMode.ALL, false);

        assertEquals(before, root.toString());
    }

    // ---- robustness and property tests ------------------------------------------------------

    @Test
    void aFailingEnvLookupNeverEscapesParse() {
        Function<String, String> exploding = key -> {
            throw new IllegalStateException("no environment access");
        };

        MiningAssistConfig c = MiningAssistConfig.parse(json("{\"miningAssist\":{\"mode\":\"poi\"}}"), exploding,
                AssistMode.SENSE, false);

        assertEquals(AssistMode.POI, c.mode(), "an unreadable env counts as unset, so the file mode applies");
        assertEquals(ModeSource.FILE, c.modeSource());
        assertFalse(c.deterministic(false));
        assertTrue(warned(c, MiningAssistConfig.ENV_MODE));
        assertTrue(warned(c, MiningAssistConfig.ENV_DETERMINISTIC));
        assertFalse(MiningAssistConfig.deterministic(false, false, exploding));
        assertTrue(MiningAssistConfig.deterministic(true, false, exploding), "the other inputs still count");
    }

    @Test
    void literalsBeyondTheDoubleRangeClampInsteadOfBeingIgnored() {
        MiningAssistConfig c = config("{\"detour\":{\"minValue\":1e400,\"maxRadius\":-1e400,\"minScore\":1e400,"
                + "\"maxUp\":-1e400},\"tick\":{\"startWorkMs\":1e400,\"abortWorkMs\":-1e400},"
                + "\"poi\":{\"cavernOpenFraction\":[-1e400,1e400]}}");

        assertEquals(1000, c.detour().minValue());
        assertEquals(1, c.detour().maxRadius());
        assertEquals(100.0D, c.detour().minScore());
        assertEquals(0, c.detour().maxUp());
        assertEquals(100.0D, c.tick().startWorkMs());
        assertEquals(100.0D, c.tick().abortWorkMs());
        assertEquals(0.0D, c.poi().cavernOpenFractionLow());
        assertEquals(1.0D, c.poi().cavernOpenFractionHigh());
        assertTrue(warned(c, "miningAssist.detour.minValue"));
    }

    @Test
    void cavernOpenFractionAcceptsTheDesignTableRangeString() {
        Poi poi = config("{\"poi\":{\"cavernOpenFraction\":\"0.2..0.6\"}}").poi();
        assertEquals(0.2D, poi.cavernOpenFractionLow());
        assertEquals(0.6D, poi.cavernOpenFractionHigh());

        Poi spaced = config("{\"poi\":{\"cavernOpenFraction\":\" 0.175 .. 0.554 \"}}").poi();
        assertEquals(0.175D, spaced.cavernOpenFractionLow());
        assertEquals(0.554D, spaced.cavernOpenFractionHigh());

        Poi whole = config("{\"poi\":{\"cavernOpenFraction\":\"0..1\"}}").poi();
        assertEquals(0.0D, whole.cavernOpenFractionLow());
        assertEquals(1.0D, whole.cavernOpenFractionHigh());

        for (String bad : new String[] {"\"0.6..0.2\"", "\"0.5..0.5\"", "\"0.2-0.6\"", "\"a..b\"", "\"0.2..\"", "\"..0.6\"",
                "\"NaN..1\"", "\"0.2..0.6..0.9\"", "\"\"", "\"0x1..1\"", "\"1e-1..0.6\""}) {
            MiningAssistConfig c = config("{\"poi\":{\"cavernOpenFraction\":" + bad + "}}");

            assertEquals(0.175D, c.poi().cavernOpenFractionLow(), bad);
            assertEquals(0.554D, c.poi().cavernOpenFractionHigh(), bad);
            assertTrue(warned(c, "cavernOpenFraction"), bad);
        }
    }

    @Test
    void clampingACavernOpenFractionEndIsReported() {
        MiningAssistConfig c = config("{\"poi\":{\"cavernOpenFraction\":[-0.5,0.6]}}");

        assertEquals(0.0D, c.poi().cavernOpenFractionLow());
        assertEquals(0.6D, c.poi().cavernOpenFractionHigh());
        assertTrue(warned(c, "clamped miningAssist.poi.cavernOpenFraction"));
        assertTrue(config("{\"poi\":{\"cavernOpenFraction\":[0.1,0.9]}}").warnings().isEmpty());
        assertTrue(config("{\"poi\":{\"cavernOpenFraction\":[0,1]}}").warnings().isEmpty(), "0 and 1 are in range");
    }

    @Test
    void dimensionsBeyondTheCapAreReportedNotSilentlyLost() {
        StringBuilder list = new StringBuilder("[");
        for (int i = 0; i < MiningAssistConfig.MAX_CAVERN_DIMENSIONS + 1; i++) {
            list.append(i == 0 ? "" : ",").append("\"ns:dim_").append(i).append('"');
        }
        list.append(']');

        MiningAssistConfig over = config("{\"poi\":{\"cavernDimensions\":" + list + "}}");
        assertEquals(MiningAssistConfig.MAX_CAVERN_DIMENSIONS, over.poi().cavernDimensions().size());
        assertTrue(warned(over, "cavernDimensions keeps only the first 16"));

        StringBuilder exact = new StringBuilder("[");
        for (int i = 0; i < MiningAssistConfig.MAX_CAVERN_DIMENSIONS; i++) {
            exact.append(i == 0 ? "" : ",").append("\"ns:dim_").append(i).append('"');
        }
        exact.append(']');
        assertTrue(config("{\"poi\":{\"cavernDimensions\":" + exact + "}}").warnings().isEmpty(),
                "exactly at the cap is fine");
    }

    @Test
    void aJunkFilledFileCannotFloodTheWarningList() {
        StringBuilder junk = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) {
            junk.append(i == 0 ? "" : ",").append("[").append(i).append("]");
        }
        junk.append(']');

        MiningAssistConfig c = config("{\"poi\":{\"cavernDimensions\":" + junk + "},\"advisor\":{\"model\":"
                + "\"" + "m".repeat(5000) + "\"}}");

        assertEquals(List.of("minecraft:overworld"), c.poi().cavernDimensions());
        assertTrue(c.warnings().size() <= 12, "size " + c.warnings().size());
        assertTrue(c.warnings().stream().allMatch(w -> w.length() <= 245));
        assertTrue(warned(c, "4992 more malformed poi.cavernDimensions entries"));
        assertTrue(warned(c, "miningAssist.advisor.model"));

        StringBuilder many = new StringBuilder("{");
        String[] intKeys = {"minValue", "maxRadius", "maxUp", "maxDown", "leaseTicks", "minIntervalTicks", "maxPerMission",
                "minFreeSlots", "startHpMargin", "lavaClearRadius", "announceMinValue"};
        for (int i = 0; i < intKeys.length; i++) {
            many.append(i == 0 ? "" : ",").append("\"").append(intKeys[i]).append("\":\"junk\"");
        }
        many.append('}');
        assertEquals(intKeys.length, config("{\"detour\":" + many + "}").warnings().size(), "a few notes are all kept");
    }

    // ---- oracle-based fuzz ---------------------------------------------------------------------

    private record IntKey(String section, String key, int min, int max, IntGetter getter) {
        String path() {
            return section + "." + key;
        }
    }

    private static final List<IntKey> INT_KEYS = List.of(
            new IntKey("sense", "raysPerTick", 1, 256, c -> c.sense().raysPerTick()),
            new IntKey("sense", "globalRaysPerTick", 1, 4096, c -> c.sense().globalRaysPerTick()),
            new IntKey("route", "bucketMs", 30, 1000, c -> c.route().bucketMs()),
            new IntKey("detour", "minValue", 1, 1000, c -> c.detour().minValue()),
            new IntKey("detour", "maxRadius", 1, 32, c -> c.detour().maxRadius()),
            new IntKey("detour", "maxUp", 0, 16, c -> c.detour().maxUp()),
            new IntKey("detour", "maxDown", 0, 16, c -> c.detour().maxDown()),
            new IntKey("detour", "leaseTicks", 60, 600, c -> c.detour().leaseTicks()),
            new IntKey("detour", "minIntervalTicks", 20, 3200, c -> c.detour().minIntervalTicks()),
            new IntKey("detour", "maxPerMission", 0, 200, c -> c.detour().maxPerMission()),
            new IntKey("detour", "minFreeSlots", 1, 32, c -> c.detour().minFreeSlots()),
            new IntKey("detour", "startHpMargin", 0, 20, c -> c.detour().startHpMargin()),
            new IntKey("detour", "lavaClearRadius", 1, 16, c -> c.detour().lavaClearRadius()),
            new IntKey("detour", "announceMinValue", 0, 1000, c -> c.detour().announceMinValue()),
            new IntKey("poi", "dedupeRadius", 1, 256, c -> c.poi().dedupeRadius()),
            new IntKey("poi", "maxHoldsPerMission", 0, 32, c -> c.poi().maxHoldsPerMission()),
            new IntKey("poi", "holdDeadlineTicks", 20, 1200, c -> c.poi().holdDeadlineTicks()),
            new IntKey("advisor", "timeoutSeconds", 1, 10, c -> c.advisor().timeoutSeconds()),
            new IntKey("advisor", "maxTokens", 64, 16384, c -> c.advisor().maxTokens()),
            new IntKey("advisor", "maxConsultsPerMission", 0, 64, c -> c.advisor().maxConsultsPerMission()),
            new IntKey("advisor", "minIntervalTicks", 0, 12000, c -> c.advisor().minIntervalTicks()),
            new IntKey("advisor", "breakerFailures", 1, 20, c -> c.advisor().breakerFailures()),
            new IntKey("advisor", "breakerOpenTicks", 200, 72000, c -> c.advisor().breakerOpenTicks()),
            new IntKey("explore", "frontierMaxRadius", 8, 64, c -> c.explore().frontierMaxRadius()));

    private static JsonElement randomValue(SplittableRandom rnd) {
        switch (rnd.nextInt(14)) {
            case 0:
                return new JsonPrimitive(rnd.nextInt(-20, 400));
            case 1:
                return new JsonPrimitive(rnd.nextLong(-2_000_000_000_000L, 2_000_000_000_000L));
            case 2:
                return new JsonPrimitive(rnd.nextDouble(-50.0D, 200.0D));
            case 3:
                return new JsonPrimitive((double) rnd.nextInt(-20, 400));
            case 4:
                return new JsonPrimitive(rnd.nextBoolean());
            case 5:
                return new JsonPrimitive(new String[] {"", "x", "true", "40", "all", "  "}[rnd.nextInt(6)]);
            case 6:
                return JsonNull.INSTANCE;
            case 7:
                return new JsonArray();
            case 8:
                return new JsonObject();
            case 9:
                return JsonParser.parseString("1e400");
            case 10:
                return JsonParser.parseString("-1e400");
            case 11:
                return JsonParser.parseString("12345678901234567890123");
            case 12:
                return new JsonPrimitive(rnd.nextInt(0, 100) / 4.0D);
            default:
                return new JsonPrimitive(rnd.nextInt(-5, 100));
        }
    }

    /** What an integer key should resolve to for a raw file value: the spec, restated independently. */
    private static int expectedInt(JsonElement raw, int fallback, int min, int max) {
        if (raw == null || raw.isJsonNull() || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        double value = raw.getAsDouble();
        if (Double.isNaN(value) || (!Double.isInfinite(value) && value != Math.rint(value))) {
            return fallback;
        }
        return value < min ? min : value > max ? max : (int) value;
    }

    @Test
    void everyIntegerKeyFollowsTheClampOracleForRandomFileValues() {
        SplittableRandom rnd = new SplittableRandom(0x5EEDL);
        MiningAssistConfig defaults = defaults();

        for (int round = 0; round < 400; round++) {
            JsonObject body = new JsonObject();
            for (IntKey key : INT_KEYS) {
                if (rnd.nextInt(4) == 0) {
                    continue;
                }
                JsonObject section = body.has(key.section()) ? body.getAsJsonObject(key.section()) : new JsonObject();
                section.add(key.key(), randomValue(rnd));
                body.add(key.section(), section);
            }
            JsonObject root = new JsonObject();
            root.add("miningAssist", body);

            MiningAssistConfig parsed = MiningAssistConfig.parse(root, NO_ENV, AssistMode.ALL, false);

            for (IntKey key : INT_KEYS) {
                JsonElement raw = body.has(key.section()) ? body.getAsJsonObject(key.section()).get(key.key()) : null;
                int expected = expectedInt(raw, key.getter().get(defaults), key.min(), key.max());
                assertEquals(expected, key.getter().get(parsed), key.path() + " from " + raw);
                assertTrue(key.getter().get(parsed) >= key.min() && key.getter().get(parsed) <= key.max(), key.path());
            }
        }
    }

    @Test
    void randomJunkNeverBreaksTheCrossFieldInvariants() {
        SplittableRandom rnd = new SplittableRandom(0xC0FFEEL);
        String[] sections = {"sense", "tick", "route", "detour", "safety", "poi", "advisor", "edits", "explore"};
        String[] keys = {"mode", "raysPerTick", "startWorkMs", "abortWorkMs", "possibleScore", "structureCertainScore",
                "cavernOpenFraction", "cavernDimensions", "noticeRecipients", "unavailablePolicy", "cavernKeylessPolicy",
                "model", "minScore", "enabled", "shadowLog", "unknownKnob"};

        for (int round = 0; round < 600; round++) {
            JsonObject body = new JsonObject();
            if (rnd.nextInt(3) == 0) {
                body.add("mode", randomValue(rnd));
            }
            for (String sectionName : sections) {
                if (rnd.nextInt(3) == 0) {
                    body.add(sectionName, randomValue(rnd));
                    continue;
                }
                JsonObject section = new JsonObject();
                for (String key : keys) {
                    if (rnd.nextInt(3) == 0) {
                        section.add(key, randomValue(rnd));
                    }
                }
                if (rnd.nextInt(4) == 0) {
                    JsonArray fraction = new JsonArray();
                    fraction.add(randomValue(rnd));
                    fraction.add(randomValue(rnd));
                    section.add("cavernOpenFraction", fraction);
                }
                body.add(sectionName, section);
            }
            JsonObject root = new JsonObject();
            root.add("miningAssist", body);

            MiningAssistConfig c = MiningAssistConfig.parse(root, NO_ENV, AssistMode.SENSE, rnd.nextBoolean());

            assertTrue(c.tick().startWorkMs() >= 1.0D && c.tick().startWorkMs() <= 100.0D, body.toString());
            assertTrue(c.tick().abortWorkMs() >= c.tick().startWorkMs(), body.toString());
            assertTrue(c.tick().abortWorkMs() >= Tick.MIN_ABORT_WORK_MS && c.tick().abortWorkMs() <= 100.0D,
                    body.toString());
            assertTrue(c.poi().possibleScore() >= 0.05D && c.poi().possibleScore() <= 1.0D, body.toString());
            assertTrue(c.poi().structureCertainScore() >= c.poi().possibleScore()
                    && c.poi().structureCertainScore() <= 1.0D, body.toString());
            assertTrue(c.poi().cavernOpenFractionLow() >= 0.0D
                    && c.poi().cavernOpenFractionLow() < c.poi().cavernOpenFractionHigh()
                    && c.poi().cavernOpenFractionHigh() <= 1.0D, body.toString());
            assertTrue(c.detour().minScore() >= 0.1D && c.detour().minScore() <= 100.0D, body.toString());
            assertTrue(c.poi().cavernDimensions().size() <= MiningAssistConfig.MAX_CAVERN_DIMENSIONS);
            assertTrue(c.advisor().model().length() <= 128);
            assertTrue(c.warnings().size() <= 64, "at most one note per key plus a few array notes");
            assertEquals(c, MiningAssistConfig.parse(root, NO_ENV, AssistMode.SENSE, c.harnessOff()),
                    "parsing is a pure function of its input");
        }
    }
}
