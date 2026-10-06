package io.github.zoyluo.minecraftai.goal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Prevents a restart from silently converting a bounded collection session into a fresh window. */
final class TimedCollectionPersistenceSourceContractTest {
    @Test
    void durationStepsPersistAndRestoreOnlyTheirAccountingReceipt() throws IOException {
        String executor = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/goal/GoalExecutor.java"));
        String ore = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/OreDigTask.java"));
        String farm = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/FarmTask.java"));

        assertTrue(executor.contains("plan.takeTaskCheckpoint(GoalStep.Kind.MINE_ORE_FOR_DURATION)")
                        && executor.contains("plan.takeTaskCheckpoint(GoalStep.Kind.FARM_FOR_DURATION)"),
                "the durable task receipt must be handed back to the matching duration task");
        assertTrue(ore.contains("new TimedCollectionCheckpoint(collectionDurationTicks,")
                        && ore.contains("elapsed = restoredTimedCollection.elapsedTicks();"));
        assertTrue(farm.contains("new TimedCollectionCheckpoint(collectionDurationTicks,")
                        && farm.contains("elapsed = restoredTimedCollection.elapsedTicks();"));
    }
}
