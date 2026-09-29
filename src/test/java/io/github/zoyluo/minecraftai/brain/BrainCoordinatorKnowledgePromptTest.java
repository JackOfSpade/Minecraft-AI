package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model denied copper tools and the Lunge enchantment from stale memory and repeated it after
 * correction. The prompt must pin the exact game version, tell the model to verify with the
 * read-only lookup tool instead of memory, and route leaf/vein/come-here requests to the right tools.
 */
final class BrainCoordinatorKnowledgePromptTest {
    private static final String PROMPT = BrainCoordinator.systemPrompt("Moss", "Jack");

    @Test
    void statesTheExactMinecraftVersionAndDistrustsMemory() {
        assertTrue(PROMPT.contains("Minecraft Java Edition 1.21.11 exactly"));
        assertTrue(PROMPT.contains("out of date"));
        assertTrue(PROMPT.contains("call lookup_recipe"));
        assertTrue(PROMPT.contains("Lunge"), "the recurring stale-knowledge example must be named");
    }

    @Test
    void routesLeavesToBreakBlocksAndVeinsToMineOreVeinMode() {
        assertTrue(PROMPT.contains("call break_blocks with block=leaves"));
        assertTrue(PROMPT.contains("mine_ore with mode=vein"));
    }

    @Test
    void neverAnswersAnActionRequestWithSayAlone() {
        assertTrue(PROMPT.contains("Never answer an action request with say alone"));
        assertTrue(PROMPT.contains("\"come here\""));
    }
}
