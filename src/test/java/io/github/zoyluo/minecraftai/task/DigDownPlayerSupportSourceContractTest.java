package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** Pins the narrow reroute required when a real player occupies a selected dig-down stair body. */
class DigDownPlayerSupportSourceContractTest {
    private static final Path DIG_DOWN = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/DigDownTask.java");

    @Test
    void selectedStairBodyIsLimitedToTheCurrentDiagonalClearanceCells() {
        BlockPos feet = new BlockPos(10, 64, 10);
        BlockPos ahead = feet.relative(Direction.NORTH);

        assertTrue(DigDownTask.isSelectedStairBody(feet, 0, ahead));
        assertTrue(DigDownTask.isSelectedStairBody(feet, 0, ahead.above()));
        assertTrue(DigDownTask.isSelectedStairBody(feet, 0, ahead.below()));
        assertFalse(DigDownTask.isSelectedStairBody(feet, 0, feet.relative(Direction.EAST)));
        assertFalse(DigDownTask.isSelectedStairBody(feet, 0, null));
    }

    @Test
    void playerSupportedStairMineIsRejectedAndReroutedBeforeARepeat() throws IOException {
        String source = Files.readString(DIG_DOWN);
        String tick = methodBody(source, "protected void onTick(");

        int capturedTarget = tick.indexOf("BlockPos minedTarget = miner.target()");
        int minerTick = tick.indexOf("BlockMiner.Status status = miner.tick(bot)", capturedTarget);
        int typedRefusal = tick.indexOf("MiningSafety.PLAYER_SUPPORT.equals(miner.failureReason())", minerTick);
        int reroute = tick.indexOf("routeAroundPlayerSupportedStairBody(bot, world, feet, minedTarget)", typedRefusal);
        int nextStairSelection = tick.indexOf("BlockPos solid = TerrainProbe.firstSolid", reroute);
        assertTrue(capturedTarget >= 0 && minerTick > capturedTarget && typedRefusal > minerTick
                        && reroute > typedRefusal && nextStairSelection > reroute,
                "the typed failure must use the captured stair target to reroute before that target can be selected again");

        String rerouteBody = methodBody(source, "private boolean routeAroundPlayerSupportedStairBody(");
        int selected = rerouteBody.indexOf("isSelectedStairBody(feet, stairDirIndex, failedTarget)");
        int reject = rerouteBody.indexOf("rejectLandingDirection(feet, stairDirIndex)", selected);
        int rotate = rerouteBody.indexOf("rotateStair(bot, world, feet)", reject);
        int horizontal = rerouteBody.indexOf("digHorizontal(bot, world, feet)", rotate);
        assertTrue(selected >= 0 && reject > selected && rotate > reject && horizontal > rotate,
                "a player-supported stair body must be rejected, then rotate or use horizontal fallback");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, () -> "missing method " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, () -> "missing method body " + signature);
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
