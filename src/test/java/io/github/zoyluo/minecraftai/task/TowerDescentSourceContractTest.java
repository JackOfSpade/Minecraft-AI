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
                        && descend.indexOf("releaseDropBelowBreak(bot)") < descend.indexOf("dropWatch.hold(bot)")
                        && descend.indexOf("dropWatch.hold(bot)") < descend.indexOf("tower.tick(bot)"),
                "the drop is released, and its item waited for or looked for, while the bot is still up on its pillar");
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
    void mineCountsTheItemOfItsBreakBeforeItsTowerComesBackAsItems() throws IOException {
        String descend = methodBody(read("task/MineTask.java"), "private boolean descendTower(");

        int counted = descend.indexOf("collectedDrops(bot) > 0");
        int waits = descend.indexOf("dropWatch.hold(bot)");
        int breaks = descend.indexOf("tower.tick(bot)");
        assertTrue(counted > 0 && counted < waits && waits < breaks,
                "the pickup is counted first, then the bot stays for its item, and only then does the tower start coming down");
        assertTrue(descend.contains("inventoryCountBeforeMining += Math.min(tower.returnedOf(targetDrops)"),
                "what the tower gives back is no progress of a request for the same kind of block");
    }

    @Test
    void aTowerIsHeldByItsTaskAndTakenDownWithoutOneWhenTheTaskEndsFirst() throws IOException {
        for (String task : List.of("task/GatherQuotaTask.java", "task/MineTask.java")) {
            String source = read(task);
            assertTrue(methodBody(source, "private boolean startPillarApproach(").contains("TowerCustody.INSTANCE.hold(bot, this, tower)"),
                    task + " answers for the tower it builds until it has taken it down");
            assertTrue(methodBody(source, "private boolean descendTower(").contains("TowerCustody.INSTANCE.release(bot, tower)"),
                    task + " hands the tower back once it is down");
        }
        String tick = methodBody(read("task/TaskManager.java"), "public void tickAll(MinecraftServer server)");
        assertTrue(tick.indexOf("TowerCustody.INSTANCE.tickOrphans()") > 0
                        && tick.indexOf("TowerCustody.INSTANCE.tickOrphans()") < tick.indexOf("for (Map.Entry<UUID, Task> entry")
                        && tick.contains("descending.contains(uuid)"),
                "an orphaned tower comes down before the bot's active task is ticked");
        assertTrue(read("task/StuckWatcher.java").contains("TowerCustody.INSTANCE.isDescending(bot)"),
                "a task held back for a tower is not stuck");
    }

    @Test
    void oreDigBuildsItsPillarWithThePlannerOfTheOthersAndTakesItDownBeforeItWalksOff() throws IOException {
        String ore = read("task/OreDigTask.java");
        String start = methodBody(ore, "private boolean startOrePillar(");
        String route = methodBody(ore, "private boolean beginOrePillarRoute(");
        String descend = methodBody(ore, "private boolean descendOreTower(");
        String inUse = methodBody(ore, "private boolean towerInUse(");
        String onTick = methodBody(ore, "protected void onTick(AIPlayerEntity bot)");

        assertTrue(start.contains("HarvestCore.pillarApproachFor(bot, ore, targetOres,")
                        && start.contains("isRecoverableBreakPose(goal, ore)"),
                "an ore pillar is the planner of the gather and mine tasks, asked to end on a pose the ore's drop is recovered from");
        assertTrue(!start.contains("maxPillarSupports") && !ore.contains("OrePillarTower"),
                "a tower of any height can be left: the descent takes it down, so no height is invented as a cap");
        assertTrue(route.contains("TowerDescent.over(goal, goal.getY() - floor.getY())")
                        && route.contains("tower.standsOnTower(bot) ? tower.base()")
                        && route.contains("TowerCustody.INSTANCE.hold(bot, this, tower)"),
                "every pillar route is remembered, to be taken down, from the floor the first of a stack of pillars rose from");
        assertTrue(descend.contains("TowerCustody.INSTANCE.release(bot, tower)") && descend.contains("tower.tick(bot)"),
                "the task hands the tower back once it is down");
        assertTrue(inUse.contains("orePillarGoal != null") && inUse.contains("miner.target() != null")
                        && inUse.contains("canBreakTargetFromHere(bot, owner)") && inUse.contains("owner.equals(towerOre)"),
                "a pillar being built or mined from is in use; the ore next in line that cannot be mined from its top is not");

        int descent = onTick.indexOf("if (descendOreTower(bot))");
        assertTrue(descent > 0
                        && descent > onTick.indexOf("recoverPendingTargetDrop(bot)")
                        && descent < onTick.indexOf("maybePlaceAutomaticTorchAtSafeBoundary(bot, world, false)")
                        && descent < onTick.indexOf("tickVisibleOreSighting(bot)")
                        && onTick.indexOf("failForNoProgress(bot, world)", descent) > descent
                        && descent < onTick.indexOf("waitForFinalCountVeinDrops(bot)"),
                "the descent comes after the drop of the last break is settled and before anything that walks the bot off its tower or ends the task");
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
