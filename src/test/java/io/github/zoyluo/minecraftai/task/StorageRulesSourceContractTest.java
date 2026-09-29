package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks the storage fairness rules: no task ranks or picks containers by reading the contents of
 * unopened ones, deposits target storage blocks only, and the vanilla blocked-lid rule is honoured.
 */
final class StorageRulesSourceContractTest {
    @Test
    void tasksNeverPeekIntoUnopenedContainers() throws IOException {
        for (String file : new String[] {"ResupplyTask.java", "StockpileTask.java", "SmeltTask.java", "FarmTask.java"}) {
            String source = read("task/" + file);
            assertFalse(source.contains("ContainerSupport.containsItem"), file + " ranks containers by peeking at contents");
            assertFalse(source.contains("ContainerAction.resolve("), file + " must open containers through ContainerAction.open");
        }
        String collector = read("goal/GoalSnapshotCollector.java");
        assertFalse(collector.contains("ContainerAction.resolve("), "goal postconditions must not read closed containers");
        assertTrue(collector.contains(".containers()"), "goal postconditions read the bot's own ledger");
        String support = read("task/ContainerSupport.java");
        assertFalse(support.contains("containsItem"), "the remote content peek helper is gone");
    }

    @Test
    void openingWritesTheLedgerAndTransfersGoThroughIt() throws IOException {
        String action = read("action/ContainerAction.java");
        assertTrue(action.contains("public static Optional<Container> open("));
        assertTrue(action.contains("if (!inReachAndSight(bot, pos))"), "opening requires reach and line of sight");
        assertTrue(action.contains("note(bot, pos, container.get())"), "opening records the contents");
        assertTrue(action.contains("public static TransferResult deposit(")
                && action.contains("public static TransferResult withdraw("));
        for (String file : new String[] {"ContainerTask.java", "StockpileTask.java", "ResupplyTask.java", "FarmTask.java"}) {
            assertTrue(read("task/" + file).contains("ContainerAction.open("), file + " opens before transferring");
        }
    }

    @Test
    void depositTargetsAreStorageBlocksWithVanillaLidRuleAndNoIgnoreBlocked() throws IOException {
        String action = read("action/ContainerAction.java");
        assertTrue(action.contains(
                "block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock"));
        assertTrue(action.contains("ChestBlock.getContainer(chestBlock, state, level, pos, false)"));
        assertFalse(action.contains("pos, true)"), "ignoreBlocked=true would open a chest under a solid block");
        String targets = read("task/StorageTargets.java");
        assertTrue(targets.contains("Blocks.SPAWNER"), "chests near an observed spawner are skipped");
        int kind = targets.indexOf("ContainerAction.isStorageBlock(level.getBlockState(pos))");
        int rays = targets.indexOf("ContainerAction.canSee(bot, pos))\n                .forEach");
        assertTrue(kind >= 0 && rays > kind, "the block kind is tested before any line-of-sight ray");
        assertTrue(read("task/ContainerTask.java").contains("StorageTargets.clampRadius(radius)"),
                "find_container radius is clamped");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai").resolve(relative));
    }
}
