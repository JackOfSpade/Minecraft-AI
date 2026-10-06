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
    void acquisitionRequestsCannotOfferDirectInventoryHandoff() {
        List<ToolDefinition> tools = List.of(
                tool("say"), tool("gather"), tool("gather_then_give"), tool("give_item"),
                tool("achieve_goal"), tool("fulfill_items"), tool("inventory"), tool("assign_task"));

        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction("moss get 32 logs"));
        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction("get us 32 logs"));
        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction("get me 32 logs, then hand them over"));
        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction("mine some coal"));
        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction("mine 32 diamonds"));
        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction("gather and hand me 32 logs"),
                "an acquire-and-deliver request must use gather_then_give, not a pre-existing inventory stack");
        assertFalse(BrainCoordinator.suppressDirectGiveItemForInstruction("give me 32 logs"));
        assertFalse(BrainCoordinator.suppressDirectGiveItemForInstruction("can you hand me your logs?"));
        assertFalse(BrainCoordinator.suppressDirectGiveItemForInstruction("give me mine back"),
                "the possessive pronoun must not be mistaken for a mining request");
        assertFalse(BrainCoordinator.suppressDirectGiveItemForInstruction("give me mine now"));
        assertFalse(BrainCoordinator.suppressDirectGiveItemForInstruction("get over here and give me 32 logs"),
                "a movement instruction followed by an explicit handoff must keep give_item available");
        assertFalse(BrainCoordinator.suppressDirectGiveItemForInstruction("get to me, then hand me your logs"));
        assertFalse(BrainCoordinator.suppressDirectGiveItemForInstruction("get out and give me your logs"));
        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction(
                        "get 32 logs, then get over here and hand them to me"),
                "a later movement-form get must not erase an earlier resource-acquisition get");
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction("get 32 logs"));
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction("get us 32 logs"));
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction("get a stack of logs"));
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction("gather logs"));
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction("mine 32 diamonds"));
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction("acquire some oak logs"));
        assertFalse(BrainCoordinator.requiresFreshResourceQuotaForInstruction("get an iron pickaxe"));
        assertFalse(BrainCoordinator.requiresFreshResourceQuotaForInstruction("get 32 iron pickaxes"));
        assertFalse(BrainCoordinator.requiresFreshResourceQuotaForInstruction("obtain an iron pickaxe"));
        assertFalse(BrainCoordinator.requiresFreshResourceQuotaForInstruction("acquire a diamond sword"));
        assertFalse(BrainCoordinator.requiresFreshResourceQuotaForInstruction("collect an iron pickaxe"));
        assertFalse(BrainCoordinator.requiresFreshResourceQuotaForInstruction("gather a diamond sword"));
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction(
                "gather 32 logs, then collect an iron pickaxe"),
                "a raw subrequest must retain its fresh quota guard even beside a crafted outcome");
        String compoundCraftedHandoff = "gather 32 logs, craft an iron pickaxe, then give it to me";
        assertTrue(BrainCoordinator.requiresFreshResourceQuotaForInstruction(compoundCraftedHandoff));
        assertTrue(BrainCoordinator.hasCraftedItemOutcome(compoundCraftedHandoff));
        assertTrue(BrainCoordinator.suppressDirectGiveItemForInstruction(compoundCraftedHandoff),
                "a crafted outcome cannot erase an earlier fresh raw-resource quota");
        assertFalse(BrainCoordinator.requiresFreshResourceHandoffForInstruction(compoundCraftedHandoff),
                "a raw-resource wrapper cannot replace a compound craft-and-handoff workflow");
        assertTrue(BrainCoordinator.requiresFreshQuotaHandoffForInstruction(compoundCraftedHandoff),
                "the compound route must still hide split gather/direct-give escapes");
        assertFalse(BrainCoordinator.hasCraftedItemOutcome(
                        "gather 32 logs with an iron pickaxe, then hand them to me"),
                "mentioning a carried tool must not disable the fresh raw-resource handoff guard");
        assertTrue(BrainCoordinator.requiresFreshResourceHandoffForInstruction("gather and hand me 32 logs"));
        assertTrue(BrainCoordinator.requiresFreshResourceHandoffForInstruction(
                "get 32 logs, then hand them to me"));
        assertTrue(BrainCoordinator.requiresFreshResourceHandoffForInstruction(
                "gather 32 logs, then give the logs to me"));
        assertTrue(BrainCoordinator.requiresFreshResourceHandoffForInstruction(
                "gather 32 logs and hand over 32 logs to me"));
        assertTrue(BrainCoordinator.requiresFreshResourceHandoffForInstruction(
                "gather 32 logs and bring them to me"));
        assertFalse(BrainCoordinator.requiresFreshResourceHandoffForInstruction("gather 32 logs"));
        assertFalse(BrainCoordinator.requiresFreshResourceHandoffForInstruction(
                "gather 32 logs and give me a status update"));

        List<ToolDefinition> restricted = BrainCoordinator.toolsForCall(tools, false, true, true);
        assertEquals(List.of("say", "gather", "gather_then_give", "fulfill_items", "inventory", "assign_task"),
                restricted.stream().map(ToolDefinition::name).toList());

        List<ToolDefinition> continuation = BrainCoordinator.toolsForCall(tools, true, true, true);
        assertEquals(List.of("gather", "gather_then_give", "fulfill_items", "inventory", "assign_task"),
                continuation.stream().map(ToolDefinition::name).toList());

        List<ToolDefinition> atomicHandoff = BrainCoordinator.toolsForCall(tools, false, true, true, true);
        assertEquals(List.of("say", "gather_then_give", "fulfill_items", "inventory"),
                atomicHandoff.stream().map(ToolDefinition::name).toList());

        List<ToolDefinition> compoundCraftTools = BrainCoordinator.toolsForCall(tools, false,
                BrainCoordinator.suppressDirectGiveItemForInstruction(compoundCraftedHandoff),
                BrainCoordinator.requiresFreshResourceQuotaForInstruction(compoundCraftedHandoff),
                BrainCoordinator.requiresFreshQuotaHandoffForInstruction(compoundCraftedHandoff));
        assertEquals(List.of("say", "gather_then_give", "fulfill_items", "inventory"),
                compoundCraftTools.stream().map(ToolDefinition::name).toList(),
                "a compound crafted delivery must select fresh fulfillment, never split gather/direct give");
    }

    @Test
    void strategyCheckpointOffersOnlyServerBoundContinuationChoices() {
        List<ToolDefinition> tools = List.of(
                tool("continue_goal_step"), tool("replan_goal_from_current_state"), tool("stop_goal_mission"),
                tool("move_to"), tool("mine_block"), tool("fulfill_items"));

        assertEquals(List.of("continue_goal_step", "replan_goal_from_current_state", "stop_goal_mission"),
                BrainCoordinator.toolsForStrategyCheckpoint(tools).stream()
                        .map(ToolDefinition::name).toList());
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
