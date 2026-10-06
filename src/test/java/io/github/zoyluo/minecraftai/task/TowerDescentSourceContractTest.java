package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A pillar the bot built is taken down block by block before it does anything else ({@code TowerDescent}), and that
 * is the only place the bot may break the block under its own feet. The behaviour is proven in-world by the gather
 * and mine overhead GameTests; these pin the wiring that keeps a bot from ending, surveying or leaving on a tower.
 */
final class TowerDescentSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void gatherTakesItsPillarDownBeforeSurveyingPickingUpOrEnding() throws IOException {
        String gather = read("task/GatherQuotaTask.java");
        String onTick = methodBody(gather, "protected void onTick(AIPlayerEntity bot)");
        String descend = methodBody(gather, "private boolean descendTower(");
        String start = methodBody(gather, "private boolean startPillarApproach(");
        String settling = methodBody(gather, "private boolean settlingCollection()");

        int descent = onTick.indexOf("if (descendTower(bot))");
        int watchdog = onTick.indexOf("SELF_STUCK_LIMIT");
        int dispatch = onTick.indexOf("switch (phase)");
        assertTrue(descent > 0 && descent < watchdog && watchdog < dispatch,
                "the descent comes before the stuck watchdog (which would send a bot on a tower exploring) and the phase dispatch");
        assertTrue(onTick.contains("quotaReached() && phase != Phase.DONE"),
                "a stopAll() on every tick of DONE would cancel each break of the descent");
        assertTrue(start.contains("tower = TowerDescent.over(approach.goal(), approach.supports())"),
                "every pillar this task starts is remembered, to be taken down");
        assertTrue(descend.contains("phase != Phase.SURVEY && phase != Phase.PICKUP && phase != Phase.BOOTSTRAP_PICKUP"
                        + " && phase != Phase.DONE"),
                "a pillar still being climbed or mined from is in use; every place the bot leaves it from is not");
        assertTrue(settling.contains("tower != null"),
                "a timed collection must not end on top of a tower");
    }

    @Test
    void theLeafAFelledCanopyLogWouldRestOnIsBrokenBeforeTheBotLeavesItsPillar() throws IOException {
        String gather = read("task/GatherQuotaTask.java");
        String release = methodBody(gather, "private boolean releaseDropBelowBreak(");
        String descend = methodBody(gather, "private boolean descendTower(");

        assertTrue(release.contains("BlockTags.LEAVES") && release.contains("HarvestCore.canReach(bot, rest)")
                        && release.contains("pickupOrigin.below()") && release.contains("canObserveCell(bot, rest)"),
                "only a leaf the bot can see and reach, in the column under the cell it just broke, is ever broken to release the drop");
        assertTrue(descend.indexOf("releaseDropBelowBreak(bot)") > 0
                        && descend.indexOf("releaseDropBelowBreak(bot)") < descend.indexOf("tower.tick(bot)"),
                "the drop is released while the bot is still up on its pillar");
    }

    @Test
    void mineTakesItsPillarDownBeforeSearchingPickingUpOrEnding() throws IOException {
        String mine = read("task/MineTask.java");
        String onTick = methodBody(mine, "protected void onTick(AIPlayerEntity bot)");
        String descend = methodBody(mine, "private boolean descendTower(");
        String start = methodBody(mine, "private boolean startPillarApproach(");
        String pickup = methodBody(mine, "private void pickup(");

        assertTrue(onTick.contains("if (descendTower(bot))") && onTick.contains("case FINISHING -> complete();"),
                "the task ends only from FINISHING, which the descent holds back");
        assertTrue(start.contains("tower = TowerDescent.over(approach.goal(), approach.supports())"),
                "every pillar this task starts is remembered, to be taken down");
        assertTrue(descend.contains("phase == Phase.MOVING || phase == Phase.MINING"),
                "a pillar still being climbed or mined from is in use");
        assertTrue(!pickup.contains("complete();") && pickup.contains("Phase.FINISHING"),
                "a met quota must not end the task on top of a tower");
    }

    @Test
    void onlyTheTowerDescentMayBreakTheBotsOwnFooting() throws IOException {
        String pack = read("action/ActionPack.java");
        String own = methodBody(pack, "public ActionResult startOwnSupportMining(BlockPos pos)");

        int proof = own.indexOf("MiningController.currentObservedTarget(player, pos)");
        int read = own.indexOf("getBlockState(");
        assertTrue(proof > 0 && read > proof, "the observation proof comes before the first read of the cell");
        assertTrue(own.contains("pos.equals(player.blockPosition().below())") && own.contains("player.onGround()")
                        && own.contains("MaterialPalette.isPillarSupportItem(")
                        && own.contains("MiningSafety.SupportOccupancy.PLAYER"),
                "only the block under a bot that stands on it, only a throwaway pillar block, and never another player's footing");

        List<String> callers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            callers = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    String source = Files.readString(path);
                    return source.contains("startOwnSupportMining(") || source.contains("MiningController.ownSupport(");
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }).map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("ActionPack.java", "TowerDescent.java"), callers,
                "nothing else may ask for a break of the bot's own footing");
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}
