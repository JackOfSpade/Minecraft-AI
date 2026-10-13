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

        assertTrue(source.contains("isFreshFulfillOutput(plan.goal, step.item())"));
        assertTrue(source.contains("GatherQuotaTask.collectAdditionalExact(step.item(), step.count())"));
        assertTrue(source.contains("fulfill.initialItemCounts().containsKey(item)"));
        assertTrue(source.contains("new CraftTask(step.item(), craftTargetCount("));
        assertTrue(source.contains("step.count()), freshOutput)"),
                "a declared fresh output must carry the same no-reuse requirement into CraftTask");
        assertTrue(source.contains("freshFulfillDelivery,"));
        assertTrue(source.contains("hasFreshFulfillDeliveryQuota(bot, plan, step)"));
        assertTrue(source.contains("GoalSnapshotCollector.inventoryCount(bot, step.item()) >= required"));
        assertTrue(source.contains("hasFreshFulfillPostDeliveryQuota(bot, plan, allocation)"));
        assertTrue(source.contains("completedAfterThisDelivery.add(allocation)"));
        assertTrue(source.contains("Ingredient gathers\n     * retain their normal family-aware behavior"),
                "only a declared fresh output may bypass family-aware gathering");

        String craftTask = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/CraftTask.java"));
        assertTrue(craftTask.contains("private final boolean freshOutputRequired;"));
        assertTrue(craftTask.contains("if (!freshOutputRequired && utilityAlreadyAvailable(bot))"),
                "a fresh player fulfillment must never let a nearby furnace, table, or held old copy skip production");
    }

    /**
     * The behaviour is covered by GoalFreshFulfillmentGameTests; this pins the wiring that those
     * registry-backed tests cannot reach without a promoted mission: every promotion path starts
     * a queued fresh request from the inventory held at that moment, while a persisted ACTIVE
     * mission is restored with the baseline it was written with.
     */
    @Test
    void everyQueuePromotionRebaselinesAndActiveRestoreDoesNot() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/goal/GoalExecutor.java"));

        assertTrue(source.contains("submit(bot, startingGoal(bot, next.goal), null, next.executionMode)"),
                "advanceQueue must start a queued fresh request from the current inventory");
        assertTrue(source.contains("hasActivePlan(bot) ? queued.get() : startingGoal(bot, queued.get())"),
                "a restored queue entry with nothing ahead of it is a promotion too");
        assertTrue(source.contains("submit(bot, restored.get(),\n"
                        + "                            restoreSeed(bot, restored.get(), activeRecord)"),
                "the restored ACTIVE mission must keep its persisted baseline");
        assertTrue(source.contains("freshFulfillDelivery && keepsDeliveryOffRoute(\n"
                        + "                                (Goal.Fulfill) plan.goal, plan.completedDeliveries)"),
                "only a fresh delivery that still owes a route-support block takes the no-dig, no-pillar approach");
    }
}
