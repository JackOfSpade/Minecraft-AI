package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Route-level contracts for a shared refusal caused by a real player standing on a block. */
class PlayerSupportRoutePolicyTest {
    private static final Path ORE_DIG = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/OreDigTask.java");
    private static final Path DESCEND = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/DescendToYTask.java");

    @Test
    void typedPlayerSupportRefusalIsRecognizedWithoutBroadMatching() {
        assertTrue(OreDigTask.isPlayerSupportRefusal("player_support"));
        assertFalse(OreDigTask.isPlayerSupportRefusal("self_support"));
        assertFalse(OreDigTask.isPlayerSupportRefusal("mine_timeout"));
        assertFalse(OreDigTask.isPlayerSupportRefusal(null));
    }

    @Test
    void oreDigSkipsOrRoutesAroundPlayerSupportedChannelAndStairBlocks() throws IOException {
        String source = Files.readString(ORE_DIG);

        String tunnelSettlement = methodBody(source, "private void settleOwnedTunnelMine(");
        int failure = tunnelSettlement.indexOf("String failure = miner.failureReason()");
        int typedReason = tunnelSettlement.indexOf("isPlayerSupportRefusal(failure)", failure);
        int abandon = tunnelSettlement.indexOf("abandonTargetApproach(bot, goal,", typedReason);
        int bypassLog = tunnelSettlement.indexOf("ore_dig_player_support_bypass", typedReason);
        assertTrue(failure >= 0 && typedReason > failure && abandon > typedReason
                        && bypassLog > typedReason,
                "a channel block supporting a player must be released/rerouted, not retried as a generic mine failure");

        String stairChooser = methodBody(source, "private Direction safeStairDir(");
        int diggable = stairChooser.indexOf("isDiggableBody(world, ahead, ahead.above(), next)");
        int playerGuard = stairChooser.indexOf(
                "!hasPlayerSupportedBodyBlock(bot, ahead, ahead.above(), next)", diggable);
        int result = stairChooser.indexOf("return dir", playerGuard);
        assertTrue(diggable >= 0 && playerGuard > diggable && result > playerGuard,
                "a lower-ore stair must choose another observed direction before mining a player-supported body block");

        String descend = methodBody(source, "private boolean digDownOneLayer(");
        int raceReason = descend.indexOf("String reason = miner.failureReason()");
        int raceGuard = descend.indexOf("isPlayerSupportRefusal(reason)", raceReason);
        int falseReturn = descend.indexOf("return false", raceGuard);
        int toolFailure = descend.indexOf("failMissingMiningChannelTool(bot)", raceGuard);
        assertTrue(raceReason >= 0 && raceGuard > raceReason && falseReturn > raceGuard
                        && toolFailure > falseReturn,
                "a player entering the selected stair after proof must release that route before any retry/tool handling");
    }

    @Test
    void descendRejectsAFailedStairBeforeItCanBecomeTerminal() throws IOException {
        String source = Files.readString(DESCEND);
        String recovery = methodBody(source, "private void recoverFailedMiningAttempt(");
        int reject = recovery.indexOf("rejectLandingDirection(feet, stairDirIndex)");
        int rotate = recovery.indexOf("rotateStair(bot, world, feet)", reject);
        int lateral = recovery.indexOf("tryLateralDetour(bot, world, feet)", rotate);
        int terminal = recovery.indexOf("fail(\"descend_mine_failed", lateral);
        assertTrue(reject >= 0 && rotate > reject && lateral > rotate && terminal > lateral,
                "a refused descent mine must first reject the edge and try other stair/detour routes");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, "missing method body " + signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("unterminated method " + signature);
    }
}
