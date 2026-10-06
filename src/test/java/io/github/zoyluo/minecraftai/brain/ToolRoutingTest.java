package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zoyluo.minecraftai.task.GatherThenGiveTask;
import io.github.zoyluo.minecraftai.task.TaskState;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The per-conversation routing state: what a fresh instruction withholds and what lifts it again. */
final class ToolRoutingTest {
    private static final Set<String> COLLECT = Set.of("give_item", "achieve_goal");

    @BeforeAll
    static void bootstrap() {
        RegistryBootstrap.ensure();
    }

    private static ToolRouting routingFor(String instruction) {
        ToolRouting routing = new ToolRouting();
        routing.beginInstruction(RequestIntent.parse(instruction));
        return routing;
    }

    @Test
    void aNewInstructionStartsFromItsOwnWording() {
        ToolRouting routing = routingFor("get 32 logs");
        assertEquals(COLLECT, routing.withheldTools());

        routing.beginInstruction(RequestIntent.parse("give me 32 logs"));
        assertEquals(Set.of(), routing.withheldTools(),
                "the next message must not inherit the previous request's restriction");

        routing.beginInstruction(RequestIntent.parse("mine some coal"));
        assertEquals(COLLECT, routing.withheldTools());
    }

    @Test
    void aNewInstructionForgetsWhatTheLastOneStartedAndFinished() {
        ToolRouting routing = routingFor("gather 32 logs");
        routing.noteTaskStarted("gather");
        routing.observeTask("gather", TaskState.COMPLETED);
        assertEquals(Set.of(), routing.withheldTools());

        routing.beginInstruction(RequestIntent.parse("get 16 coal"));
        assertEquals(COLLECT, routing.withheldTools(), "the lift belongs to the instruction chain that earned it");
        routing.observeTask("gather", TaskState.COMPLETED);
        assertEquals(COLLECT, routing.withheldTools(), "a task of the previous chain is not this chain's collection");
    }

    @Test
    void anAutonomousGoalContinuationDoesNotInheritTheLastInstructionsRestriction() {
        ToolRouting routing = routingFor("get 32 logs");
        assertEquals(COLLECT, routing.withheldTools());

        routing.beginGoalContinuation();

        assertEquals(Set.of(), routing.withheldTools());
        assertNull(routing.blockedResult("give_item"));
        assertNull(routing.blockedResult("achieve_goal"));
    }

    @Test
    void theCollectionTheChainStartedEndsTheRestrictionWhenItCompletes() {
        ToolRouting routing = routingFor("gather 32 logs, then slide them over to me");
        assertEquals(COLLECT, routing.withheldTools(), "a handoff phrase the classifier missed");
        routing.noteTaskStarted("gather");

        routing.observeTask("gather", TaskState.RUNNING);
        assertEquals(COLLECT, routing.withheldTools());
        routing.observeTask("gather", TaskState.FAILED);
        assertEquals(COLLECT, routing.withheldTools(), "a failed collection never opens the carried stock");
        routing.observeTask("idle", TaskState.COMPLETED);
        assertEquals(COLLECT, routing.withheldTools(), "the idle placeholder is not a finished collection");
        routing.observeTask("follow", TaskState.COMPLETED);
        assertEquals(COLLECT, routing.withheldTools(), "an unrelated task finishing is not this collection");

        routing.observeTask("gather", TaskState.COMPLETED);
        assertEquals(Set.of(), routing.withheldTools());
        assertNull(routing.blockedResult("give_item"));
    }

    @Test
    void aTaskCompletionBeforeAnyTaskWasStartedByTheChainIsIgnored() {
        ToolRouting routing = routingFor("get 32 logs");

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(COLLECT, routing.withheldTools(),
                "the cached status of an earlier instruction's task must not unlock this one");
    }

    @Test
    void aCompletedGoalMissionEndsTheRestriction() {
        ToolRouting routing = routingFor("mine 10 iron and give them to me");
        assertTrue(routing.withheldTools().contains("mine_ore"));

        routing.observeGoalCompleted();

        assertEquals(Set.of(), routing.withheldTools());
    }

    @Test
    void aHandoffThatFailedAfterTheCollectionOpensGiveItemButNotAnotherCollection() {
        ToolRouting routing = routingFor("gather 32 logs and give them to me");
        routing.observeFailure("fresh_gather_failed:gather_timeout");
        assertTrue(routing.withheldTools().contains("give_item"), "the gather phase failed: nothing is carried");

        routing.observeFailure(GatherThenGiveTask.HANDOFF_FAILED_PREFIX + "give_item_player_not_found");

        assertEquals(Set.of("gather_then_give"), routing.withheldTools(),
                "the quota is carried: hand it over, do not gather a second one");
        assertNull(routing.blockedResult("give_item"));
        assertTrue(routing.blockedResult("gather_then_give").contains("give_item"));
    }

    @Test
    void aCompoundRequestUnlocksTheCraftAndHandoffStagesWhenItsCollectionCompletes() {
        ToolRouting routing = routingFor("gather 32 logs then craft a table and give it to me");
        assertEquals(Set.of("give_item", "achieve_goal", "gather_then_give", "fulfill_items"), routing.withheldTools());
        assertTrue(routing.blockedResult("gather_then_give").contains("gather (count)"));
        assertTrue(routing.blockedResult("fulfill_items").contains("gather (count)"));
        routing.noteTaskStarted("gather");

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(Set.of(), routing.withheldTools());
    }

    @Test
    void aPartialHandoffCollectsFirstBecauseGatherThenGiveHandsOverEverything() {
        ToolRouting routing = routingFor("gather 32 logs and give me 16");
        assertEquals(Set.of("give_item", "achieve_goal", "gather_then_give", "fulfill_items"), routing.withheldTools());
        assertNull(routing.blockedResult("gather"), "gather with the count is the new quota");
        String text = routing.blockedResult("gather_then_give");
        assertTrue(text.contains("part") && text.contains("gather (count)") && text.contains("give_item"), text);
        assertEquals(text, routing.blockedResult("fulfill_items"));
        routing.noteTaskStarted("gather");

        routing.observeTask("gather", TaskState.COMPLETED);

        assertEquals(Set.of(), routing.withheldTools(), "the part is handed over with give_item afterwards");
    }

    @Test
    void theBlockedTextsNameTheToolsThatCanServeTheRequest() {
        ToolRouting collect = routingFor("get 32 logs");
        String give = collect.blockedResult("give_item");
        assertTrue(give.contains("gather_then_give") && give.contains("fulfill_items"), give);
        assertTrue(give.startsWith("blocked:"));
        String goal = collect.blockedResult("achieve_goal");
        assertTrue(goal.contains("fulfill_items") && goal.contains("gather"), goal);
        assertNull(collect.blockedResult("gather"));
        assertNull(collect.blockedResult("gather_then_give"));

        ToolRouting handoff = routingFor("gather 32 logs and give them to me");
        String split = handoff.blockedResult("gather");
        assertTrue(split.contains("gather_then_give") && split.contains("fulfill_items"), split);
        assertNull(handoff.blockedResult("gather_then_give"));
        assertNull(handoff.blockedResult("fulfill_items"));
    }

    @Test
    void withholdingOnlyAppliesWhileThereIsSomethingToCollect() {
        for (String instruction : new String[] {"give me 32 logs", "hello", "make an iron pickaxe", ""}) {
            ToolRouting routing = routingFor(instruction);
            assertEquals(Set.of(), routing.withheldTools(), instruction);
            assertFalse(routing.withheldTools().contains("give_item"));
        }
    }
}
