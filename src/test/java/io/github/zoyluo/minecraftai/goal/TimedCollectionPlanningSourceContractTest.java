package io.github.zoyluo.minecraftai.goal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the no-count route time-bound end-to-end instead of faking a one-item quota. */
final class TimedCollectionPlanningSourceContractTest {
    @Test
    void plannerEmitsDurationStepsAndExecutorClosesThemAtTheTaskDeadline() throws IOException {
        String planner = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/goal/GoalPlanner.java"));
        String executor = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/goal/GoalExecutor.java"));

        assertTrue(planner.contains("mineOre.isTimedCollection()")
                        && planner.contains("ensureTimedMineOre")
                        && planner.contains("GoalStep.mineOreForDuration"));
        assertTrue(planner.contains("harvestCrop.isTimedCollection()")
                        && planner.contains("ensureTimedHarvestCrop")
                        && planner.contains("GoalStep.farmForDuration"));
        assertTrue(executor.contains("case MINE_ORE_FOR_DURATION -> Optional.of(OreDigTask.collectForDuration("));
        assertTrue(executor.contains("case FARM_FOR_DURATION -> Optional.of(FarmTask.collectForDuration("));
        assertTrue(executor.contains("isTimedCollectionStep(plan.current)"));
        assertTrue(executor.contains("GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE"));
        assertTrue(executor.contains("I explored for ten minutes but found no"));
    }
}
