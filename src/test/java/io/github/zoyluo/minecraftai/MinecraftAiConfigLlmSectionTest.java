package io.github.zoyluo.minecraftai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the provider-neutral names of the LLM settings: the JSON section is {@code llm}, the old
 * {@code deepseek} section is still accepted, and the API key can come from
 * {@code MINECRAFTAI_LLM_API_KEY} (or the legacy {@code DEEPSEEK_API_KEY}).
 */
class MinecraftAiConfigLlmSectionTest {

    private static MinecraftAiConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        MinecraftAiConfig config = MinecraftAiConfig.parse(root, OperatingProfile.STRICT_SURVIVAL);
        assertTrue(config != null, "config must parse");
        return config;
    }

    @Test
    void theLlmSectionIsRead() {
        MinecraftAiConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k1\",\"baseUrl\":\"https://example.test/v1\","
                + "\"model\":\"some-model\"}}").llm();
        assertEquals("k1", llm.apiKey());
        assertEquals("https://example.test/v1", llm.baseUrl());
        assertEquals("some-model", llm.model());
    }

    @Test
    void theLegacyDeepseekSectionIsStillRead() {
        MinecraftAiConfig.Llm llm = parse("{\"deepseek\":{\"apiKey\":\"old-key\",\"model\":\"old-model\"}}").llm();
        assertEquals("old-key", llm.apiKey());
        assertEquals("old-model", llm.model());
    }

    @Test
    void whenBothSectionsArePresentTheNewNameWinsInEitherOrder() {
        String llmFirst = "{\"llm\":{\"apiKey\":\"new\"},\"deepseek\":{\"apiKey\":\"old\"}}";
        String legacyFirst = "{\"deepseek\":{\"apiKey\":\"old\"},\"llm\":{\"apiKey\":\"new\"}}";
        assertEquals("new", parse(llmFirst).llm().apiKey());
        assertEquals("new", parse(legacyFirst).llm().apiKey());
    }

    @Test
    void missingValuesFallBackToTheShippedDefaults() {
        MinecraftAiConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k\"}}").llm();
        MinecraftAiConfig.Llm defaults = MinecraftAiConfig.defaults().llm();
        assertEquals(defaults.baseUrl(), llm.baseUrl());
        assertEquals(defaults.model(), llm.model());
        assertEquals(defaults.maxTokens(), llm.maxTokens());
    }

    @Test
    void anAbsentSectionYieldsTheDefaults() {
        assertEquals(MinecraftAiConfig.defaults().llm(), parse("{}").llm());
    }

    @Test
    void omittedTemperatureFallsBackToTheShippedDefaultRatherThanZero() {
        MinecraftAiConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k\"}}").llm();
        assertEquals(MinecraftAiConfig.defaults().llm().temperature(), llm.temperature());
    }

    @Test
    void anExplicitZeroTemperatureIsHonoured() {
        // 0.0 is a legitimate, fully-deterministic temperature and must not be treated as "omitted".
        MinecraftAiConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k\",\"temperature\":0.0}}").llm();
        assertEquals(0.0D, llm.temperature());
    }

    @Test
    void omittedRetryCountFallsBackToTheShippedDefaultRatherThanZero() {
        MinecraftAiConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k\"}}").llm();
        assertEquals(MinecraftAiConfig.defaults().llm().retryCount(), llm.retryCount());
    }

    @Test
    void anExplicitZeroRetryCountMeansNoRetriesAndIsHonoured() {
        MinecraftAiConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k\",\"retryCount\":0}}").llm();
        assertEquals(0, llm.retryCount());
    }

    @Test
    void aNegativeRetryCountIsStillClampedToZero() {
        MinecraftAiConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k\",\"retryCount\":-5}}").llm();
        assertEquals(0, llm.retryCount());
    }

    @Test
    void theWrittenTemplateUsesTheGenericSectionName() {
        String json = new Gson().toJson(MinecraftAiConfig.defaults());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(root.has("llm"), "new configs are written with the llm section");
        assertFalse(root.has("deepseek"), "the provider-specific name is no longer written");
    }

    @Test
    void theShippedDefaultsStillTargetDeepseeksEndpoint() {
        // A rename of the fields must not silently change which provider a fresh install talks to.
        assertEquals("https://api.deepseek.com", MinecraftAiConfig.defaults().llm().baseUrl());
        assertEquals("deepseek-v4-flash", MinecraftAiConfig.defaults().llm().model());
    }

    @Test
    void theGenericEnvironmentKeyBeatsTheLegacyOne() {
        Map<String, String> env = new HashMap<>();
        env.put("DEEPSEEK_API_KEY", "legacy");
        assertEquals("legacy", MinecraftAiConfig.apiKeyFromEnv(env::get),
                "the legacy variable still works on its own");
        env.put("MINECRAFTAI_LLM_API_KEY", "generic");
        assertEquals("generic", MinecraftAiConfig.apiKeyFromEnv(env::get));
    }

    @Test
    void blankEnvironmentValuesAreIgnored() {
        Map<String, String> env = new HashMap<>();
        env.put("MINECRAFTAI_LLM_API_KEY", "   ");
        env.put("DEEPSEEK_API_KEY", "");
        assertNull(MinecraftAiConfig.apiKeyFromEnv(env::get));
        env.put("DEEPSEEK_API_KEY", "fallback");
        assertEquals("fallback", MinecraftAiConfig.apiKeyFromEnv(env::get),
                "a blank generic variable falls through to the legacy one");
    }

    @Test
    void environmentVariableNamesAreThePublishedOnes() {
        assertEquals("MINECRAFTAI_LLM_API_KEY", MinecraftAiConfig.ENV_API_KEY);
        assertEquals("DEEPSEEK_API_KEY", MinecraftAiConfig.LEGACY_ENV_API_KEY);
    }
}
