package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.persist.MissionSpec.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorQueuedGoalTest {
    @Test
    void replacingAQueuedGoalConsumesItsPayloadWithoutDiscardingOtherPolicies() {
        var before = new GoalExecutor.QueuedGoal(new Goal.Food(2), ExecutionMode.ADAPTIVE);
        var replacement = new GoalExecutor.QueuedGoal(new Goal.Food(4), ExecutionMode.STANDARD);
        var after = new GoalExecutor.QueuedGoal(new Goal.Food(8), ExecutionMode.ADAPTIVE);
        var queue = new ConcurrentLinkedDeque<>(List.of(before, replacement, after));

        assertTrue(GoalExecutor.removeFirstQueuedGoal(queue, new Goal.Food(4)));

        assertEquals(List.of(before, after), List.copyOf(queue));
        assertEquals(ExecutionMode.ADAPTIVE, queue.getFirst().executionMode());
        assertEquals(ExecutionMode.ADAPTIVE, queue.getLast().executionMode());
        assertFalse(GoalExecutor.removeFirstQueuedGoal(queue, new Goal.Food(4)),
                "the replacement must not remain queued for a second execution");
    }

    @Test
    void removalFindsTheFirstMatchingGoalRegardlessOfItsExecutionPolicy() {
        var first = new GoalExecutor.QueuedGoal(new Goal.Food(4), ExecutionMode.ADAPTIVE);
        var second = new GoalExecutor.QueuedGoal(new Goal.Food(4), ExecutionMode.STANDARD);
        var queue = new ConcurrentLinkedDeque<>(List.of(first, second));

        assertTrue(GoalExecutor.removeFirstQueuedGoal(queue, new Goal.Food(4)));

        assertEquals(List.of(second), List.copyOf(queue));
    }

    @Test
    void anUnmatchedOrEmptyQueueKeepsEveryExplicitRequest() {
        var request = new GoalExecutor.QueuedGoal(new Goal.Food(4), ExecutionMode.ADAPTIVE);
        var queue = new ConcurrentLinkedDeque<>(List.of(request));

        assertFalse(GoalExecutor.removeFirstQueuedGoal(queue, new Goal.Food(3)));
        assertEquals(List.of(request), List.copyOf(queue));
        assertThrows(NullPointerException.class, () -> GoalExecutor.removeFirstQueuedGoal(queue, null),
                "the production ConcurrentLinkedDeque rejects null removal lookups");
        queue.clear();
        assertFalse(GoalExecutor.removeFirstQueuedGoal(queue, new Goal.Food(4)));
    }

    @Test
    void wrapperEqualityRemainsSymmetricAndIncludesItsExecutionPolicy() {
        Goal goal = new Goal.Food(4);
        var standard = new GoalExecutor.QueuedGoal(goal, null);
        var adaptive = new GoalExecutor.QueuedGoal(goal, ExecutionMode.ADAPTIVE);

        assertEquals(new GoalExecutor.QueuedGoal(new Goal.Food(4), ExecutionMode.STANDARD), standard);
        assertFalse(standard.equals(adaptive));
        assertFalse(standard.equals(goal));
        assertFalse(goal.equals(standard));
    }
}
