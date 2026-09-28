package io.github.zoyluo.minecraftai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the V4 thinking contract. Reasoning output shares the {@code max_tokens} budget, so an
 * unset or silently-changed effort level directly costs the bot tool calls.
 */
class LlmThinkingConfigTest {
    private static MinecraftAiConfig.Llm llmConfig(Boolean thinking, String effort) {
        return new MinecraftAiConfig.Llm(
                "key", "https://api.deepseek.com", "deepseek-v4-flash",
                8192, 0.3D, 60, 3, 500, thinking, effort);
    }

    @Test
    void defaultsTargetTheCurrentFlashModelWithABudgetReasoningCanNotStarve() {
        MinecraftAiConfig.Llm defaults = MinecraftAiConfig.defaults().llm();

        assertEquals("deepseek-v4-flash", defaults.model());
        assertEquals(Boolean.TRUE, defaults.thinking());
        assertEquals("low", defaults.reasoningEffort());
        assertTrue(defaults.maxTokens() >= 8192,
                "reasoning shares max_tokens; 2048 truncates tool calls");
    }

    @Test
    void unsetOverridesFallBackToDefaultsInsteadOfSilentlyDisablingThinking() {
        MinecraftAiConfig.Llm defaults = llmConfig(Boolean.TRUE, "low");
        MinecraftAiConfig.Llm merged = llmConfig(null, null).withDefaults(defaults);

        assertEquals(Boolean.TRUE, merged.thinking());
        assertEquals("low", merged.reasoningEffort());
    }

    @Test
    void explicitOverridesWin() {
        MinecraftAiConfig.Llm defaults = llmConfig(Boolean.TRUE, "low");

        assertEquals(Boolean.FALSE, llmConfig(Boolean.FALSE, "low")
                .withDefaults(defaults).thinking());
        assertEquals("max", llmConfig(Boolean.TRUE, "max")
                .withDefaults(defaults).reasoningEffort());
    }

    @Test
    void unsupportedEffortFallsBackRatherThanReachingTheApi() {
        MinecraftAiConfig.Llm defaults = llmConfig(Boolean.TRUE, "low");

        assertEquals("low", llmConfig(Boolean.TRUE, "medium")
                .withDefaults(defaults).reasoningEffort());
        assertEquals("low", llmConfig(Boolean.TRUE, "")
                .withDefaults(defaults).reasoningEffort());
    }

    @Test
    void apiKeyRebindKeepsTheThinkingContract() {
        MinecraftAiConfig config = MinecraftAiConfig.defaults();
        MinecraftAiConfig.Llm rebound = config.llm().withDefaults(config.llm());

        assertNotNull(rebound.reasoningEffort());
        assertEquals(config.llm().thinking(), rebound.thinking());
        assertEquals(config.llm().reasoningEffort(), rebound.reasoningEffort());
    }
}
