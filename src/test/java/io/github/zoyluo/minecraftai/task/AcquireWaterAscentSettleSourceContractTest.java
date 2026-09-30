package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcquireWaterAscentSettleSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void anOverhangingBotWalksOntoItsStandableNeighbourBeforeAnyStairTargetIsChosen() throws IOException {
        String task = Files.readString(MAIN.resolve("task/AcquireWaterTask.java"));
        int ascend = task.indexOf("private boolean ascendOneStair");
        int select = task.indexOf("selectAscentTarget(bot, world, current)", ascend);
        int settle = task.indexOf("settleOnStandableCell(bot, world, current)", ascend);

        assertTrue(ascend >= 0 && select > ascend && settle > ascend, "anchors moved: update this pin");
        assertTrue(settle < select, "the bot must stand on a standable cell before a stair target is derived from it");
        assertTrue(task.contains("adjacentStandableStep(\"acquire_water_ascent_settle\")"),
                "the settle is an input-driven walked step");
        assertFalse(task.contains("FakePlayerMotion"), "the settle never moves the bot itself");
    }
}
