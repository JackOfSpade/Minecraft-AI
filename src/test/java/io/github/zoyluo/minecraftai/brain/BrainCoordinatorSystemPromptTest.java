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

    @Test
    void distinguishesNewResourceCollectionFromAnExistingInventoryHandoff() {
        String prompt = BrainCoordinator.systemPrompt("Moss", "JackNotInTheBox");

        assertTrue(prompt.contains("Direct give_item is only for handing over what you already carry"));
        assertTrue(prompt.contains("give_item and achieve_goal are not offered"));
        assertTrue(prompt.contains("gather_then_give with the number the player stated"));
        assertTrue(prompt.contains("item=\"logs\" for an unspecified plural \"logs\""));
        assertTrue(prompt.contains("fulfill_items is for production, never for handing over carried stock"));
        assertTrue(prompt.contains("hand over a bundle with one give_item call per item"));
    }

    @Test
    void thePromptSteersWhereTheRoutingLeavesRoom() {
        String prompt = BrainCoordinator.systemPrompt("Moss", "JackNotInTheBox");

        // No number stated: gather_then_give needs one, so the plain collection tools stay and the prompt says so.
        assertTrue(prompt.contains("If the player gives no number, collect with gather, mine_ore or harvest_crop without count"));
        // A crafted result cannot go through gather_then_give (it would hand over the raw resource).
        assertTrue(prompt.contains("gather_then_give would hand over the logs, not the table"));
        // The prompt must not call for tools exactly where the routing hides them.
        assertTrue(prompt.contains("For \"make an iron pickaxe\" or \"get iron ingots\", call achieve_goal"));
        RegistryBootstrap.ensure();
        for (String phrase : new String[] {"make an iron pickaxe", "get iron ingots"}) {
            ToolRouting routing = new ToolRouting();
            routing.beginInstruction(RequestIntent.parse(phrase));
            assertFalse(routing.withheldTools().contains("achieve_goal"), phrase);
        }
    }
}
