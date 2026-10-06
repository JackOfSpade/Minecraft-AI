package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures a direct no-count task still tells the player about an empty full window. */
final class TimedCollectionReportingSourceContractTest {
    @Test
    void directTaskDeadlineMissGetsAnUnconditionalPlayerFacingReport() throws IOException {
        String manager = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/TaskManager.java"));

        assertTrue(manager.contains("reportDirectTimedCollectionMiss(player, origin, task);"));
        assertTrue(manager.contains("GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE.equals(task.failureReason())"));
        assertTrue(manager.contains("I explored for ten minutes but found none of the requested resource."));
        assertTrue(manager.contains("GoalExecutor.INSTANCE.hasActivePlan(bot)"),
                "goals publish their own precise terminal result rather than duplicating a task report");
    }
}
