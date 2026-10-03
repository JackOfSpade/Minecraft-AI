package io.github.zoyluo.minecraftai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftAiConfigBrainCapTest {
    @Test
    void defaultsToAConfigurableMultiTurnPlanningBudget() {
        assertEquals(12, MinecraftAiConfig.defaults().brain().maxTurnsPerRequest());
    }

    @Test
    void configuredPositiveCapIsNotRaisedToTheDefault() {
        MinecraftAiConfig.Brain defaults = MinecraftAiConfig.defaults().brain();
        MinecraftAiConfig.Brain configured = new MinecraftAiConfig.Brain(
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
