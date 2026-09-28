package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpenAiCompatibleApiClientEndpointTest {
    @Test
    void deepSeekHostsAreRecognized() {
        assertTrue(OpenAiCompatibleApiClient.isDeepSeekHost("deepseek.com"));
        assertTrue(OpenAiCompatibleApiClient.isDeepSeekHost("api.deepseek.com"));
        assertTrue(OpenAiCompatibleApiClient.isDeepSeekHost("DeepSeek.com"));
    }

    @Test
    void nonDeepSeekHostsAreRejected() {
        assertFalse(OpenAiCompatibleApiClient.isDeepSeekHost("api.openai.com"));
        assertFalse(OpenAiCompatibleApiClient.isDeepSeekHost("openrouter.ai"));
        assertFalse(OpenAiCompatibleApiClient.isDeepSeekHost("localhost"));
        assertFalse(OpenAiCompatibleApiClient.isDeepSeekHost("generativelanguage.googleapis.com"));
        assertFalse(OpenAiCompatibleApiClient.isDeepSeekHost(null));
    }
}
