package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorBatchCheckpointPolicyTest {
    private static final Path EXECUTOR = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/goal/GoalExecutor.java");

    @Test
    void waitsOnlyAfterAFullSafeBatchThatHasWorkRemaining() {
        int limit = GoalExecutor.DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT;

        assertFalse(GoalExecutor.shouldCheckpointAfterCompletedStep(0,
                limit - 1, 1, true));
        assertTrue(GoalExecutor.shouldCheckpointAfterCompletedStep(0,
                limit, 1, true));
        assertFalse(GoalExecutor.shouldCheckpointAfterCompletedStep(0,
                limit, 0, true));
        assertFalse(GoalExecutor.shouldCheckpointAfterCompletedStep(0,
                limit, 1, false));
        assertTrue(GoalExecutor.shouldCheckpointAfterCompletedStep(limit,
                limit * 2, 3, true));
    }

    @Test
    void batchCheckpointCodecIsStrictAndLegacySafe() {
        assertFalse(GoalCheckpointCodec.decodeBatchCheckpoint(Map.of()).orElseThrow().persisted());

        Map<String, String> valid = Map.of(
                "batch_checkpoint.schema", "1",
                "batch_checkpoint.awaiting_player", "true",
                "batch_checkpoint.completed_at_checkpoint", "10",
                "batch_checkpoint.step_limit", "10");
        GoalExecutor.GoalBatchCheckpoint decoded = GoalCheckpointCodec.decodeBatchCheckpoint(valid)
                .orElseThrow();
        assertTrue(decoded.persisted());
        assertEquals(10, decoded.completedAtCheckpoint());
        assertEquals(GoalExecutor.DEFAULT_AUTONOMOUS_BATCH_STEP_LIMIT,
                decoded.stepLimit());

        assertTrue(GoalCheckpointCodec.decodeBatchCheckpoint(Map.of(
                "batch_checkpoint.schema", "1",
                "batch_checkpoint.awaiting_player", "true",
                "batch_checkpoint.completed_at_checkpoint", "10",
                "batch_checkpoint.step_limit", "9")).isEmpty());
        assertTrue(GoalCheckpointCodec.decodeBatchCheckpoint(Map.of(
                "batch_checkpoint.schema", "1",
                "batch_checkpoint.awaiting_player", "true",
                "batch_checkpoint.completed_at_checkpoint", "10",
                "batch_checkpoint.step_limit", "10",
                "batch_checkpoint.unexpected", "x")).isEmpty());
    }

    @Test
    void fulfillmentConsentAndAmbiguousHandoffGuardsRemainWired() throws IOException {
        // Ordinary JUnit intentionally does not bootstrap Minecraft's item registry. The runtime
        // behavior is covered by the Fabric GameTest; pin this executor-only wiring as a source
        // contract so the non-bootstrapped suite remains reliable.
        String executor = Files.readString(EXECUTOR);

        assertTrue(executor.contains("return goal instanceof Goal.Fulfill;"));
        assertTrue(executor.contains("if (isBatchCheckpointExempt(plan.goal)) {"));
        assertTrue(executor.contains("step.kind() == GoalStep.Kind.GIVE_ITEM"));
        assertTrue(executor.contains("\"give_item_count_mismatch\".equals(reason)"));
        assertTrue(executor.contains("\"give_item_receipt_commit_failed\".equals(reason)"));
    }
}
