package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure prompt-assembly checks for {@link BrainCoordinator#systemPrompt}: the bot's own name must
 * always be stated, and the speaking player/bot's name must be stated too whenever one is known
 * (a fresh player instruction), but omitted for the automatic-wake path which has no single
 * speaking party.
 */
final class BrainCoordinatorSystemPromptTest {
    @Test
    void statesTheBotsOwnName() {
        String prompt = BrainCoordinator.systemPrompt("Moss", "");

        assertTrue(prompt.contains("named Moss"), "prompt must identify the bot by name");
    }

    @Test
    void statesTheSpeakingPartyWhenKnown() {
        String prompt = BrainCoordinator.systemPrompt("Moss", "JackNotInTheBox");

        assertTrue(prompt.contains("JackNotInTheBox"),
                "prompt must name who is currently speaking to the bot");
        assertTrue(prompt.contains("you are Moss and you speak and act only as Moss"),
                "prompt must pin the bot to speaking/acting only as itself");
    }

    @Test
    void omitsTheSpeakingPartySentenceWhenNoneIsKnown() {
        String withBlank = BrainCoordinator.systemPrompt("Moss", "");
        String withNull = BrainCoordinator.systemPrompt("Moss", null);

        assertFalse(withBlank.contains("currently speaking to you"));
        assertFalse(withNull.contains("currently speaking to you"));
    }

    @Test
    void differentSpeakersProduceDifferentPrompts() {
        String forJack = BrainCoordinator.systemPrompt("Moss", "JackNotInTheBox");
        String forOtherBot = BrainCoordinator.systemPrompt("Moss", "Ember");

        assertTrue(forJack.contains("JackNotInTheBox"));
        assertFalse(forJack.contains("speaking to you is Ember"));
        assertTrue(forOtherBot.contains("speaking to you is Ember"));
    }
}
