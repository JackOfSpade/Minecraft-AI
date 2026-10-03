package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the no-false-promise boundary without requiring a bootstrapped Minecraft registry. */
final class CapabilityTruthfulnessContractTest {
    private static String read(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + file));
    }

    @Test
    void unsupportedSpecializedActionsHaveAnExplicitHonestTerminalTool() throws IOException {
        String registry = read("brain/ToolRegistry.java");

        assertTrue(registry.contains("register(\"report_unsupported\""));
        assertTrue(registry.contains("I can't do that yet: I don't have a tool for"));
        assertTrue(registry.contains("I did not start the action"));
        assertTrue(read("brain/BrainCoordinator.java")
                .contains("controlled elytra flight with firework rockets"));
    }

    @Test
    void freeTextAndPlanOnlyPromisesAreNotPublishedAsWork() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");

        assertTrue(coordinator.contains("bot_text_reply\", \"published\", \"false\""));
        assertTrue(coordinator.contains("containsExecutablePlan"));
        assertTrue(coordinator.contains("a plan may be announced only with an applicable work-start tool"));
        assertTrue(coordinator.contains("Capability truthfulness is mandatory"));
    }
}
