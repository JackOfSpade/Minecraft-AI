package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks gathering and hunting to one shared, physical water-recovery handoff. */
class SurfaceExpeditionWaterRecoverySourceContractTest {
    private static final Path TASKS = Path.of("src/main/java/io/github/zoyluo/minecraftai/task");

    @Test
    void surfaceTasksPauseExplorationUntilSharedRescueReturnsToDryGround() throws IOException {
        String safety = read("NavSafetyNet.java");
        String gather = read("GatherQuotaTask.java");
        String hunt = read("HuntTask.java");

        assertTrue(safety.contains("public void requestWaterRescue(AIPlayerEntity bot)"));
        assertTrue(safety.contains("public boolean isWaterRescueActive(AIPlayerEntity bot)"));
        // R5: real swim physics carries the bot by the forward and jump keys (BaritoneInputPhysicsProbeGameTests.waterAndSwimming and
        // NaturalSwimGameTests.legacyInputsSwimAndSurface measured about two blocks per second), so the rescue walks and swims
        // WalkedSteps and never calls a FakePlayerMotion teleport primitive.
        assertTrue(safety.contains("WalkedStep.Kind.SWIM"),
                "wet rescue cells are reached by an adjacent swim step (forward and jump keys)");
        assertTrue(safety.contains("WalkedStep.begin(bot, cell, kind, reason)")
                        && safety.contains("WalkedStepRules.walkKindFor("),
                "the final dry rescue cell is a walked landing (step up, level or drop)");
        for (String primitive : new String[]{"stepTo(", "stepToStandable(", "swimStepTo(", "jumpTo(", "returnToBlockCenter("}) {
            assertFalse(safety.contains("FakePlayerMotion." + primitive), "the water rescue must not call FakePlayerMotion." + primitive);
        }
        assertTrue(gather.contains("if (waitForDryGround(bot))"));
        assertTrue(hunt.contains("if (waitForDryGround(bot))"));
        assertTrue(gather.contains("NavSafetyNet.INSTANCE.requestWaterRescue(bot)"));
        assertTrue(hunt.contains("NavSafetyNet.INSTANCE.requestWaterRescue(bot)"));
    }

    private static String read(String file) throws IOException {
        return Files.readString(TASKS.resolve(file));
    }
}
