package io.github.zoyluo.aibot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.mode.OperatingProfile;
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
 * {@code AIBOT_LLM_API_KEY} (or the legacy {@code DEEPSEEK_API_KEY}).
 */
class AIBotConfigLlmSectionTest {

    private static AIBotConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        AIBotConfig config = AIBotConfig.parse(root, OperatingProfile.STRICT_SURVIVAL);
        assertTrue(config != null, "config must parse");
        return config;
    }

    @Test
    void theLlmSectionIsRead() {
        AIBotConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k1\",\"baseUrl\":\"https://example.test/v1\","
                + "\"model\":\"some-model\"}}").llm();
        assertEquals("k1", llm.apiKey());
        assertEquals("https://example.test/v1", llm.baseUrl());
        assertEquals("some-model", llm.model());
    }

    @Test
    void theLegacyDeepseekSectionIsStillRead() {
        AIBotConfig.Llm llm = parse("{\"deepseek\":{\"apiKey\":\"old-key\",\"model\":\"old-model\"}}").llm();
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
        AIBotConfig.Llm llm = parse("{\"llm\":{\"apiKey\":\"k\"}}").llm();
        AIBotConfig.Llm defaults = AIBotConfig.defaults().llm();
        assertEquals(defaults.baseUrl(), llm.baseUrl());
        assertEquals(defaults.model(), llm.model());
        assertEquals(defaults.maxTokens(), llm.maxTokens());
    }

    @Test
    void anAbsentSectionYieldsTheDefaults() {
        assertEquals(AIBotConfig.defaults().llm(), parse("{}").llm());
    }

    @Test
    void theWrittenTemplateUsesTheGenericSectionName() {
        String json = new Gson().toJson(AIBotConfig.defaults());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(root.has("llm"), "new configs are written with the llm section");
        assertFalse(root.has("deepseek"), "the provider-specific name is no longer written");
    }

    @Test
    void theShippedDefaultsStillTargetDeepseeksEndpoint() {
        // A rename of the fields must not silently change which provider a fresh install talks to.
        assertEquals("https://api.deepseek.com", AIBotConfig.defaults().llm().baseUrl());
        assertEquals("deepseek-v4-flash", AIBotConfig.defaults().llm().model());
    }

    @Test
    void theGenericEnvironmentKeyBeatsTheLegacyOne() {
        Map<String, String> env = new HashMap<>();
        env.put("DEEPSEEK_API_KEY", "legacy");
        assertEquals("legacy", AIBotConfig.apiKeyFromEnv(env::get),
                "the legacy variable still works on its own");
        env.put("AIBOT_LLM_API_KEY", "generic");
        assertEquals("generic", AIBotConfig.apiKeyFromEnv(env::get));
    }

    @Test
    void blankEnvironmentValuesAreIgnored() {
        Map<String, String> env = new HashMap<>();
        env.put("AIBOT_LLM_API_KEY", "   ");
        env.put("DEEPSEEK_API_KEY", "");
        assertNull(AIBotConfig.apiKeyFromEnv(env::get));
        env.put("DEEPSEEK_API_KEY", "fallback");
        assertEquals("fallback", AIBotConfig.apiKeyFromEnv(env::get),
                "a blank generic variable falls through to the legacy one");
    }

    @Test
    void environmentVariableNamesAreThePublishedOnes() {
        assertEquals("AIBOT_LLM_API_KEY", AIBotConfig.ENV_API_KEY);
        assertEquals("DEEPSEEK_API_KEY", AIBotConfig.LEGACY_ENV_API_KEY);
    }
}
