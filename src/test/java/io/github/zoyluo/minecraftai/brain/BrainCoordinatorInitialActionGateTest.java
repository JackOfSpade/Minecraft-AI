package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrainCoordinatorInitialActionGateTest {
    @Test
    void acceptsAnAnswerAfterExplicitReadOnlyInspection() {
        assertTrue(BrainCoordinator.isAnswerOnlyReply(List.of(
                new ChatToolCall("inspect", "find_container", "{}"),
                new ChatToolCall("answer", "say",
                        "{\"message\":\"There is a chest nearby.\",\"purpose\":\"answer\"}"))));
    }

    @Test
    void doesNotTreatAStateChangingToolAsPartOfAnAnswer() {
        assertFalse(BrainCoordinator.isAnswerOnlyReply(List.of(
                new ChatToolCall("answer", "say",
                        "{\"message\":\"I will remember that.\",\"purpose\":\"answer\"}"),
                new ChatToolCall("remember", "remember",
                        "{\"key\":\"base\",\"value\":\"here\"}"))));
    }

    @Test
    void blocksTheFirstActionWhenThereIsNoPlan() {
        BrainCoordinator.InitialActionGate gate = BrainCoordinator.initialActionGate(List.of(
                new ChatToolCall("clear", "clear_grass", "{\"count\":3}")), false);

        assertTrue(gate.blockedActionCalls());
        assertFalse(gate.reorderedPlan());
        assertEquals("clear_grass", gate.orderedCalls().get(0).name());
    }

    @Test
    void movesAPlanAheadOfAnOutOfOrderFirstAction() {
        BrainCoordinator.InitialActionGate gate = BrainCoordinator.initialActionGate(List.of(
                new ChatToolCall("clear", "clear_grass", "{\"count\":3}"),
                new ChatToolCall("plan", "say",
                        "{\"message\":\"I will clear three grass plants.\",\"purpose\":\"plan\"}")), false);

        assertFalse(gate.blockedActionCalls());
        assertTrue(gate.reorderedPlan());
        assertEquals("say", gate.orderedCalls().get(0).name());
        assertEquals("clear_grass", gate.orderedCalls().get(1).name());
    }

    @Test
    void retainsAnAlreadyOrderedPlanAndAction() {
        BrainCoordinator.InitialActionGate gate = BrainCoordinator.initialActionGate(List.of(
                new ChatToolCall("plan", "say",
                        "{\"message\":\"I will clear three grass plants.\",\"purpose\":\"plan\"}"),
                new ChatToolCall("clear", "clear_grass", "{\"count\":3}")), false);

        assertFalse(gate.blockedActionCalls());
        assertFalse(gate.reorderedPlan());
        assertEquals("plan", gate.orderedCalls().get(0).id());
    }

    @Test
    void onlyAConcreteWorkToolCanMarkTheInitialRequestAsStarted() {
        assertTrue(BrainCoordinator.isWorkStartTool("clear_grass"));
        assertTrue(BrainCoordinator.isWorkStartTool("break_blocks"));
        assertTrue(BrainCoordinator.isWorkStartTool("launch_boat"));
        assertTrue(BrainCoordinator.isWorkStartTool("boat_follow"));
        assertTrue(BrainCoordinator.isWorkStartTool("assign_task"));
        assertFalse(BrainCoordinator.isWorkStartTool("equip_best_tool"));
        assertFalse(BrainCoordinator.isWorkStartTool("remember"));
    }

    @Test
    void containsValidPlanFindsAPlanRegardlessOfOrderOrOutcome() {
        assertTrue(BrainCoordinator.containsValidPlan(List.of(
                new ChatToolCall("plan", "say",
                        "{\"message\":\"I will clear three grass plants.\",\"purpose\":\"plan\"}"),
                new ChatToolCall("clear", "clear_grass", "{\"count\":3}"))));
        assertTrue(BrainCoordinator.containsValidPlan(List.of(
                new ChatToolCall("assign", "assign_task", "{\"task_type\":\"gather\",\"params\":{}}"),
                new ChatToolCall("plan", "say",
                        "{\"message\":\"I will gather wood.\",\"purpose\":\"plan\"}"))));
    }

    @Test
    void containsValidPlanIsFalseWithoutAPlanPurposeSay() {
        assertFalse(BrainCoordinator.containsValidPlan(List.of(
                new ChatToolCall("clear", "clear_grass", "{\"count\":3}"))));
        assertFalse(BrainCoordinator.containsValidPlan(List.of(
                new ChatToolCall("answer", "say",
                        "{\"message\":\"There is a chest nearby.\",\"purpose\":\"answer\"}"))));
        assertFalse(BrainCoordinator.containsValidPlan(List.of()));
        assertFalse(BrainCoordinator.containsValidPlan(null));
    }
}
