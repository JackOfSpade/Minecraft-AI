package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision logic for the live "say(plan) loop" bug: the model answered a command with only
 * say(purpose=plan), each continuation re-offered the same ~60 tools including say, and after three
 * calls the command was silently dropped. The fix withholds say from the call that follows a
 * plan-only round, drops a repeated say(plan) as a fault, and always tells the player when the
 * request never started.
 */
final class BrainCoordinatorSayPlanLoopTest {
    private static ChatToolCall say(String purpose) {
        return new ChatToolCall("s", "say", "{\"message\":\"I will do it.\",\"purpose\":\"" + purpose + "\"}");
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, "d", new JsonObject(), null);
    }

    @Test
    void aPlanOnlyRoundThatStartedNothingWithholdsSayFromTheNextCall() {
        assertTrue(BrainCoordinator.shouldWithholdSay(true, false, false, true));
    }

    @Test
    void sayIsKeptWhenTheRoundDidNotAnnounceAPlan() {
        // Could be a question answered without say(answer): the model must still be able to say it.
        assertFalse(BrainCoordinator.shouldWithholdSay(true, false, false, false));
    }

    @Test
    void sayIsKeptWhenActionCallsWereBlockedForMissingPlan() {
        // The model did call an action; it now needs say to announce the plan first.
        assertFalse(BrainCoordinator.shouldWithholdSay(true, true, false, true));
    }

    @Test
    void sayIsKeptOnceTheRequestStartedOrWhenTheActionRequirementIsMet() {
        assertFalse(BrainCoordinator.shouldWithholdSay(true, false, true, true));
        assertFalse(BrainCoordinator.shouldWithholdSay(false, false, false, true));
    }

    @Test
    void toolsForCallRemovesOnlySayWhenWithheld() {
        List<ToolDefinition> tools = List.of(tool("say"), tool("follow"), tool("hold"), tool("inventory"));

        List<ToolDefinition> withheld = BrainCoordinator.toolsForCall(tools, true);

        assertEquals(List.of("follow", "hold", "inventory"), withheld.stream().map(ToolDefinition::name).toList());
        assertSame(tools, BrainCoordinator.toolsForCall(tools, false),
                "when say is not withheld the offered set must be untouched");
    }

    @Test
    void aRepeatedPlanIsAFaultOnlyWhileThePlanIsAnnouncedAndUnstarted() {
        assertTrue(BrainCoordinator.isRepeatedPlanSay(say("plan"), true));
        assertFalse(BrainCoordinator.isRepeatedPlanSay(say("plan"), false));
        assertFalse(BrainCoordinator.isRepeatedPlanSay(say("answer"), true));
        assertFalse(BrainCoordinator.isRepeatedPlanSay(say("status"), true));
        assertFalse(BrainCoordinator.isRepeatedPlanSay(
                new ChatToolCall("f", "follow", "{}"), true));
        // A plan with no message is not a valid plan, so it is not treated as a repeat either.
        assertFalse(BrainCoordinator.isRepeatedPlanSay(
                new ChatToolCall("s", "say", "{\"purpose\":\"plan\"}"), true));
    }

    @Test
    void theCouldNotStartMessageEchoesTheCommandBrieflyAndNeverStaysSilent() {
        assertEquals("Sorry, I could not start \"come here\". Could you say it another way?",
                BrainCoordinator.couldNotStartMessage("come here"));
        assertEquals("Sorry, I could not start that. Could you say it another way?",
                BrainCoordinator.couldNotStartMessage(""));
        assertEquals("Sorry, I could not start that. Could you say it another way?",
                BrainCoordinator.couldNotStartMessage(null));
        String longMessage = BrainCoordinator.couldNotStartMessage("x".repeat(500));
        assertTrue(longMessage.length() < 120, "the echo must stay short: " + longMessage);
        assertTrue(longMessage.contains("..."));
        assertFalse(BrainCoordinator.couldNotStartMessage("break\n32\nleaves").contains("\n"));
    }

    @Test
    void theFailureFallbackNamesTheTaskAndTheReason() {
        assertEquals("Sorry, my following stopped: stuck.",
                BrainCoordinator.failureFallbackMessage("follow", "stuck"));
        assertEquals("Sorry, my task stopped: unknown.",
                BrainCoordinator.failureFallbackMessage(null, "unknown"));
    }

    @Test
    void lookupRecipeIsAReadOnlyToolAndSayIsNamedConsistently() {
        assertEquals("say", BrainCoordinator.SAY_TOOL_NAME);
        // A pure lookup may accompany an answer, exactly like inventory.
        assertTrue(BrainCoordinator.isAnswerOnlyReply(List.of(
                new ChatToolCall("l", "lookup_recipe", "{\"name\":\"copper pickaxe\"}"),
                say("answer"))));
    }
}
