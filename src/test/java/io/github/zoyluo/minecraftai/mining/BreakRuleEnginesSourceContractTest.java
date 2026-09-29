package io.github.zoyluo.minecraftai.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins how the two engines share the natural-terrain break rule and the vanilla destroy delay (source contract; the behaviour is
 * proven by {@code OreDigStructureBreakGameTests}, {@code BaritoneEngineToolGameTests} and the dig/route GameTests).
 */
class BreakRuleEnginesSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void oreDigSetsTheNaturalOnlyModeSeparatelyAndExemptsTheBodyClearOnPurpose() throws IOException {
        String oreDig = read("task/OreDigTask.java");
        int natural = oreDig.indexOf("miner.naturalTerrainOnly(true);");
        int begin = oreDig.indexOf("miner.begin(bot, pos, true);");
        assertTrue(natural >= 0 && begin > natural, "the natural-only mode is set before the 3-argument begin");
        assertEquals(1, count(oreDig, "miner.naturalTerrainOnly(true)"));
        int exempt = oreDig.indexOf("miner.naturalTerrainOnly(false);");
        int clear = oreDig.indexOf("miner.begin(bot, blocked);");
        assertTrue(exempt >= 0 && clear > exempt && oreDig.substring(exempt, clear).length() < 50, "the body clear is exempt, explicitly");
        assertTrue(oreDig.contains("\"natural_only\", false"), "the exemption is in the log line of the body clear");
        assertTrue(oreDig.contains("EXEMPT from natural-only, on purpose"));
    }

    @Test
    void routeDigThroughTicksItsSubMinersThroughTheSharedDestroyDelayAndChecksTheRuleAtExecution() throws IOException {
        String executor = read("pathfinding/PathExecutor.java");
        assertTrue(executor.contains("pack.tickBreak(subMiner)") && !executor.contains("subMiner.tick(pack)"),
                "a DIG_THROUGH sub-miner must tick through ActionPack.tickBreak (the destroy delay)");
        assertEquals(2, count(executor, "digRefusal(pack, "), "feet and head cell are both checked before a controller is created");
        assertTrue(executor.contains("BreakRule.legacyDenialOf("));
        String pack = read("action/ActionPack.java");
        assertTrue(pack.contains("public ActionResult tickBreak(MiningController controller)"));
        assertEquals(1, count(pack, "nextBreakAt = "), "the delay is armed in one place");
        assertTrue(pack.contains("ActionResult result = tickBreak(mining);"), "the action pack's own mining uses the same helper");
    }

    @Test
    void theCostModelSnapshotIsLazyForTheGameThreadAndImmediateForAnotherThread() throws IOException {
        String patch = Files.readString(Path.of("tools/baritone/patches/0016-tool-policy-host-hook.patch"));
        assertTrue(patch.contains("new ToolSet(player, true, forUseOnAnotherThread)"),
                "the context tells the tool set whether another thread will query it");
        assertTrue(patch.contains("if (hostPolicy != null && snapshotNow)"), "a set for another thread snapshots when it is created");
        assertTrue(patch.contains("if (!hostSnapshotTaken)"), "a set for the game thread snapshots on its first break-time query");
    }

    @Test
    void theRuleNamesTheIceDecisionAndTheVeinBlocks() throws IOException {
        String rule = read("mining/BreakRule.java");
        assertTrue(rule.contains("Blocks.RAW_IRON_BLOCK") && rule.contains("Blocks.RAW_COPPER_BLOCK"));
        assertTrue(rule.contains("\"ice_releases_water\""));
        assertTrue(!rule.contains("state.is(BlockTags.ICE)"), "plain ice is not natural terrain for a digger (packed and blue ice are)");
        assertTrue(rule.contains("block == Blocks.PACKED_ICE") && rule.contains("block == Blocks.BLUE_ICE"));
    }
}
