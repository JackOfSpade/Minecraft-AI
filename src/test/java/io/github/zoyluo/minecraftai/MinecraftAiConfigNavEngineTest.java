package io.github.zoyluo.minecraftai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import org.junit.jupiter.api.Test;

/** {@code nav.engine}: which navigator answers ordinary walk requests. The shipped default is the legacy one. */
final class MinecraftAiConfigNavEngineTest {
    private static MinecraftAiConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        MinecraftAiConfig config = MinecraftAiConfig.parse(root, OperatingProfile.STRICT_SURVIVAL);
        assertNotNull(config, "config must parse");
        return config;
    }

    @Test
    void theShippedDefaultIsTheLegacyEngine() {
        MinecraftAiConfig defaults = MinecraftAiConfig.defaults();
        assertEquals("legacy", defaults.nav().engine());
        assertEquals(NavEngine.LEGACY, defaults.nav().engineChoice());
        assertEquals(NavEngine.LEGACY, parse("{}").nav().engineChoice(), "no nav section at all");
        assertEquals(NavEngine.LEGACY, parse("{\"nav\":{\"maxSafeFall\":5}}").nav().engineChoice(), "a nav section without an engine");
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
    void theValueIsCaseInsensitiveAndAnUnknownOneIsTheSafeDefault() {
        assertEquals(NavEngine.BARITONE, parse("{\"nav\":{\"engine\":\" Baritone \"}}").nav().engineChoice());
        assertEquals("baritone", parse("{\"nav\":{\"engine\":\"BARITONE\"}}").nav().engine(), "stored normalised");
        MinecraftAiConfig.Nav unknown = parse("{\"nav\":{\"engine\":\"astar9000\"}}").nav();
        assertEquals(NavEngine.LEGACY, unknown.engineChoice());
        assertEquals("legacy", unknown.engine());
        assertEquals(NavEngine.LEGACY, parse("{\"nav\":{\"engine\":\"\"}}").nav().engineChoice());
    }
}
