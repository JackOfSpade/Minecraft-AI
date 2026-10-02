package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripMineStrictSurvivalBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void retirementFailsClosedInEveryProfile() {
        assertEquals(StripMineTask.RETIRED_REJECTION,
                StripMineTask.profileRejectionReason(OperatingProfile.STRICT_SURVIVAL).orElseThrow());
        assertEquals(StripMineTask.RETIRED_REJECTION,
                StripMineTask.profileRejectionReason(null).orElseThrow());
        assertEquals(StripMineTask.RETIRED_REJECTION,
                StripMineTask.profileRejectionReason(OperatingProfile.OPERATOR).orElseThrow());
    }

    @Test
    void taskGateRunsBeforeAnyLegacyWorldInitialization() throws IOException {
        String source = read("task/StripMineTask.java");
        int onStart = source.indexOf("protected void onStart");
        int onTick = source.indexOf("protected void onTick", onStart);
        String start = source.substring(onStart, onTick);

        assertTrue(onStart >= 0 && onTick > onStart
                        && start.contains("profileRejectionReason(MinecraftAiConfig.get().profile())")
                        && start.contains("fail(reason)")
                        && start.contains("legacy_strip_mining_retired"),
                "retirement must fail before the legacy task can initialize from world state");
        assertFalse(start.contains("getBlockState") || start.contains("origin =") || start.contains("resolveDepotChest"),
                "the retired entry point must not perform a legacy world read");
    }

    @Test
    void longMiningGoalsDoNotDependOnLegacyStripMine() throws IOException {
        String executor = read("goal/GoalExecutor.java");
        String planner = read("goal/GoalPlanner.java");

        assertFalse(executor.contains("StripMineTask") || planner.contains("StripMineTask"),
                "GoalExecutor/GoalPlanner must keep 32 obsidian and 64 diamond on OreDig semantics");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
