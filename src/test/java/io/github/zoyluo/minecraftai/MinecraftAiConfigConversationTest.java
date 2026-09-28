package io.github.zoyluo.minecraftai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftAiConfigConversationTest {
    @Test
    void defaultsAreEnabledWithATenMinuteCooldown() {
        MinecraftAiConfig.Conversation defaults = MinecraftAiConfig.defaults().conversation();
        assertTrue(Boolean.TRUE.equals(defaults.enabled()));
        assertEquals(12000, defaults.cooldownTicks());
    }

    @Test
    void aZeroOrNegativeFieldFallsBackToTheDefaultRatherThanStayingInvalid() {
        MinecraftAiConfig.Conversation defaults = MinecraftAiConfig.defaults().conversation();
        MinecraftAiConfig.Conversation configured = new MinecraftAiConfig.Conversation(
                null, 0, -1, 0.0D, 0, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0);

        MinecraftAiConfig.Conversation resolved = configured.withDefaults(defaults);
        assertEquals(defaults.enabled(), resolved.enabled());
        assertEquals(defaults.cooldownTicks(), resolved.cooldownTicks());
        assertEquals(defaults.checkIntervalTicks(), resolved.checkIntervalTicks());
        assertEquals(defaults.startChancePerCheck(), resolved.startChancePerCheck());
        assertEquals(defaults.minParticipants(), resolved.minParticipants());
        assertEquals(defaults.maxParticipants(), resolved.maxParticipants());
        assertEquals(defaults.readingWordsPerMinute(), resolved.readingWordsPerMinute());
        assertEquals(defaults.thinkingSecondsPerWord(), resolved.thinkingSecondsPerWord());
        assertEquals(defaults.minReplyDelaySeconds(), resolved.minReplyDelaySeconds());
        assertEquals(defaults.maxReplyDelaySeconds(), resolved.maxReplyDelaySeconds());
        assertEquals(defaults.maxTokens(), resolved.maxTokens());
    }

    @Test
    void aConfiguredStartChanceAboveOneIsClampedNotRejected() {
        MinecraftAiConfig.Conversation defaults = MinecraftAiConfig.defaults().conversation();
        MinecraftAiConfig.Conversation configured = new MinecraftAiConfig.Conversation(
                true, 12000, 200, 5.0D, 1, 4, 200.0D, 0.15D, 2.0D, 25.0D, 100);

        assertEquals(1.0D, configured.withDefaults(defaults).startChancePerCheck());
    }
}
