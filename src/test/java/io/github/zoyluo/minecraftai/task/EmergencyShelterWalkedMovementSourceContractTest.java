package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** R5: the emergency shelter never moves the bot itself; every pose change is a walked step or a real jump. */
final class EmergencyShelterWalkedMovementSourceContractTest {
    private static final Path SHELTER = Path.of("src/main/java/io/github/zoyluo/minecraftai/task/EmergencyShelterTask.java");

    @Test
    void shelterUsesWalkedStepsAndNoFakePlayerMotionTeleports() throws IOException {
        String source = Files.readString(SHELTER);
        assertFalse(source.contains("FakePlayerMotion"), "the shelter must not use the teleporting correction primitives");
        for (String reason : new String[]{"shelter_anchor_settle", "shelter_foundation", "shelter_foundation_return",
                "shelter_owned_egress", "shelter_anchor_return"}) {
            assertTrue(source.contains("\"" + reason + "\""), "walked step reason " + reason);
        }
        assertTrue(source.contains("WalkedStep.Kind.SNEAK_SHIFT"), "the foundation edge is a sneak shift");
        assertTrue(source.contains("WalkedStep.Kind.RECENTER"), "settling and the way back from the edge are re-centre steps");
        assertTrue(source.contains("pack.jumpOnce()"), "the roof support is placed from a real jump");
        assertFalse(source.contains(".teleportTo("), "no teleport of any kind");
    }
}
