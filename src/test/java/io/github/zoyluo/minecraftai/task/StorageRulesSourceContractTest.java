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
 * Assertions compare whitespace-squashed source so a reformat does not break them.
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
        String action = squash(read("action/ContainerAction.java"));
        assertTrue(action.contains("publicstaticOptional<Container>open("));
        assertTrue(action.contains("if(!inReachAndSight(bot,pos))"), "opening requires reach and line of sight");
        assertTrue(action.contains("note(bot,pos,container.get())"), "opening records the contents");
        assertTrue(action.contains("publicstaticTransferResultdeposit(")
                && action.contains("publicstaticTransferResultwithdraw("));
        for (String file : new String[] {"ContainerTask.java", "StockpileTask.java", "ResupplyTask.java", "FarmTask.java"}) {
            assertTrue(read("task/" + file).contains("ContainerAction.open("), file + " opens before transferring");
        }
    }

    @Test
    void depositTargetsAreStorageBlocksWithVanillaLidRuleAndNoIgnoreBlocked() throws IOException {
        String action = squash(read("action/ContainerAction.java"));
        assertTrue(action.contains(
                "blockinstanceofChestBlock||blockinstanceofBarrelBlock||blockinstanceofShulkerBoxBlock"));
        assertTrue(action.contains("ChestBlock.getContainer(chestBlock,state,level,pos,false)"));
        assertFalse(action.contains("pos,true)"), "ignoreBlocked=true would open a chest under a solid block");
        String targets = squash(read("task/StorageTargets.java"));
        assertTrue(targets.contains("Blocks.SPAWNER"), "chests near an observed spawner are skipped");
        int kind = targets.indexOf("ContainerAction.isStorageBlock(level.getBlockState(pos))");
        int ray = targets.indexOf("ContainerAction.canSee(bot,pos))");
        int spawnerScan = targets.indexOf("nearObservedSpawner(bot,cell)");
        assertTrue(kind >= 0 && ray > kind, "the block kind is tested before any line-of-sight ray");
        assertTrue(spawnerScan > ray, "the cheap ray runs before the ~2200-state spawner scan");
        assertTrue(squash(read("task/ContainerTask.java")).contains("StorageTargets.clampRadius(radius)"),
                "find_container radius is clamped");
    }

    @Test
    void fullIsADemotionNotAnExclusionAndRememberedPositionsAreBounded() throws IOException {
        String rawTargets = read("task/StorageTargets.java");
        assertFalse(rawTargets.contains("skipKnownFull"), "a full ledger entry must never exclude a container for good");
        String targets = squash(rawTargets);
        assertTrue(targets.contains("demoteKnownFull") && targets.contains("entry.knownFull(now)"),
                "known-full containers are demoted and the flag fades with age");
        assertTrue(targets.contains("LEDGER_MAX_DISTANCE") && targets.contains("hasChunkAt(entry.pos())"),
                "a remembered position is a candidate only when loaded and reasonably near");
        String action = squash(read("action/ContainerAction.java"));
        assertTrue(action.contains("if(!level.hasChunkAt(pos)){returnpos.immutable();"),
                "canonicalPos must not force-load a remote chunk");
        assertTrue(action.contains("if(!bot.level().hasChunkAt(pos)){returnfalse;"),
                "canSee must not ray-cast into an unloaded chunk");
        String task = squash(read("task/ContainerTask.java"));
        assertTrue(task.contains("anyContainerAllowed()") && task.contains("returnexplicitTarget()&&!junkOnly;"),
                "a junk stow keeps the storage-kind rules even when handed a position");
        assertTrue(task.contains("!junkOnly||!trustedCandidate")
                        && task.contains("named_container_unusable:"),
                "a player-named junk target is reported unusable, never silently replaced by another chest");
        assertTrue(squash(read("task/StorageJanitor.java")).contains("ContainerTask.depositJunkTrusted(target.get())"),
                "only the janitor's own pick is a trusted candidate that may fall back to automatic ones");
        String box = squash(read("goal/GoalSnapshotCollector.java"));
        assertTrue(box.contains("Math.abs(pos.getX()-origin.getX())<=CONTAINER_RADIUS")
                        && box.contains("Math.abs(pos.getZ()-origin.getZ())<=CONTAINER_RADIUS")
                        && box.contains("Math.abs(pos.getY()-origin.getY())<=CONTAINER_HEIGHT"),
                "the goal snapshot keeps the original |dx|,|dz| <= 16 and |dy| <= 6 container box");
        assertTrue(task.contains("StorageTargets.ledgerCandidateOk(bot,entry)"), "ledger candidates are bounded");
    }

    private static String squash(String source) {
        return source.replaceAll("\\s+", "");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai").resolve(relative));
    }
}
