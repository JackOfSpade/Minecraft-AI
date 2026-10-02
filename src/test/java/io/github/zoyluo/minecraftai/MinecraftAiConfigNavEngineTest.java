package io.github.zoyluo.minecraftai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import org.junit.jupiter.api.Test;

/** {@code nav.engine}: ordinary walk requests are always answered by Baritone. */
final class MinecraftAiConfigNavEngineTest {
    private static MinecraftAiConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        MinecraftAiConfig config = MinecraftAiConfig.parse(root, OperatingProfile.STRICT_SURVIVAL);
        assertNotNull(config, "config must parse");
        return config;
    }

    @Test
    void theShippedDefaultIsBaritone() {
        MinecraftAiConfig defaults = MinecraftAiConfig.defaults();
        assertEquals("baritone", defaults.nav().engine());
        assertEquals(NavEngine.BARITONE, defaults.nav().engineChoice());
        assertEquals(NavEngine.BARITONE, parse("{}").nav().engineChoice(), "no nav section at all");
        assertEquals(NavEngine.BARITONE, parse("{\"nav\":{\"maxSafeFall\":5}}").nav().engineChoice(), "a nav section without an engine");
    }

    @Test
    void theEngineCanBeSelectedAndKeepsTheOtherNavDefaults() {
        MinecraftAiConfig.Nav nav = parse("{\"nav\":{\"engine\":\"baritone\"}}").nav();
        MinecraftAiConfig.Nav defaults = MinecraftAiConfig.defaults().nav();
        assertEquals(NavEngine.BARITONE, nav.engineChoice());
        assertEquals("baritone", nav.engine());
        assertEquals(defaults.maxSafeFall(), nav.maxSafeFall());
        assertEquals(defaults.jumpReach(), nav.jumpReach());
    }

    @Test
    void oldAndUnknownValuesMigrateToTheOnlyRuntimeEngine() {
        assertEquals(NavEngine.BARITONE, parse("{\"nav\":{\"engine\":\" Baritone \"}}").nav().engineChoice());
        assertEquals("baritone", parse("{\"nav\":{\"engine\":\"BARITONE\"}}").nav().engine(), "stored normalised");
        assertEquals(NavEngine.BARITONE, parse("{\"nav\":{\"engine\":\"legacy\"}}").nav().engineChoice());
        assertEquals("baritone", parse("{\"nav\":{\"engine\":\"legacy\"}}").nav().engine(), "old config is canonicalised");
        MinecraftAiConfig.Nav unknown = parse("{\"nav\":{\"engine\":\"astar9000\"}}").nav();
        assertEquals(NavEngine.BARITONE, unknown.engineChoice());
        assertEquals("baritone", unknown.engine());
        assertEquals(NavEngine.BARITONE, parse("{\"nav\":{\"engine\":\"\"}}").nav().engineChoice());
    }
}
