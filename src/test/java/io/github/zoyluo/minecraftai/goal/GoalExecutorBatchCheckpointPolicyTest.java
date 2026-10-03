package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorBatchCheckpointPolicyTest {
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
    void explicitlyAuthorizedFulfillmentBundleDoesNotUseOrdinaryTenStepConsentGate() {
        Goal.Fulfill fulfillment = new Goal.Fulfill(List.of(
                new Goal.Allocation(Items.STICK, 2, ""),
                new Goal.Allocation(Items.STONE_AXE, 1, "Alex")));

        assertTrue(GoalExecutor.isBatchCheckpointExempt(fulfillment));
        assertFalse(GoalExecutor.isBatchCheckpointExempt(
                new Goal.HaveItem(Items.STICK, 2)));
    }

    @Test
    void ambiguousPostDropHandoffFailuresNeverReplanIntoADuplicateDelivery() {
        GoalStep handoff = GoalStep.give(Items.STONE_AXE, 1, "Alex");

        assertTrue(GoalExecutor.isUnreceiptedPhysicalDeliveryFailure(
                handoff, "give_item_count_mismatch"));
        assertTrue(GoalExecutor.isUnreceiptedPhysicalDeliveryFailure(
                handoff, "give_item_receipt_commit_failed"));
        assertFalse(GoalExecutor.isUnreceiptedPhysicalDeliveryFailure(
                handoff, "give_item_player_not_found"));
        assertFalse(GoalExecutor.isUnreceiptedPhysicalDeliveryFailure(
                GoalStep.craft(Items.STONE_AXE, 1), "give_item_count_mismatch"));
    }
}
