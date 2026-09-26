package io.github.zoyluo.aibot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AIBotConfigBrainCapTest {
    @Test
    void defaultsToInitialCallPlusTwoRetries() {
        assertEquals(3, AIBotConfig.defaults().brain().maxTurnsPerRequest());
    }

    @Test
    void configuredPositiveCapIsNotRaisedToTheDefault() {
        AIBotConfig.Brain defaults = AIBotConfig.defaults().brain();
        AIBotConfig.Brain configured = new AIBotConfig.Brain(
                defaults.maxHistoryMessages(),
                defaults.maxToolCallsPerTurn(),
                2,
                defaults.exposeLowLevelTools(),
                defaults.enableMemoryTools(),
                defaults.enableCoordinationTools(),
                defaults.maxTaskRetries(),
                defaults.verboseReports());

        assertEquals(2, configured.withDefaults(defaults).maxTurnsPerRequest());
    }
}
