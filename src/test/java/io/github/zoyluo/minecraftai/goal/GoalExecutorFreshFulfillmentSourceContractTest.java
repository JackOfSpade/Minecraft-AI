package io.github.zoyluo.minecraftai.goal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps exact final-output gathering distinct from ordinary family-aware ingredient gathering. */
class GoalExecutorFreshFulfillmentSourceContractTest {
    @Test
    void freshFulfillFinalGatherUsesExactAdditionalQuotaOnlyForDeclaredOutputs() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/goal/GoalExecutor.java"));

        assertTrue(source.contains("isFreshFulfillOutputGather(plan.goal, step.item())"));
        assertTrue(source.contains("GatherQuotaTask.collectAdditionalExact(step.item(), step.count())"));
        assertTrue(source.contains("fulfill.initialItemCounts().containsKey(item)"));
        assertTrue(source.contains("freshFulfillDelivery,"));
        assertTrue(source.contains("hasFreshFulfillDeliveryQuota(bot, plan, step)"));
        assertTrue(source.contains("GoalSnapshotCollector.inventoryCount(bot, step.item()) >= required"));
        assertTrue(source.contains("hasFreshFulfillPostDeliveryQuota(bot, plan, allocation)"));
        assertTrue(source.contains("completedAfterThisDelivery.add(allocation)"));
        assertTrue(source.contains("Ingredient gathers retain\n     * their normal family-aware behavior"),
                "only a declared fresh output may bypass family-aware gathering");
    }
}
