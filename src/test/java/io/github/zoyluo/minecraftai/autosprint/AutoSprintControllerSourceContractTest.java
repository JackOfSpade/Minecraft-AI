package io.github.zoyluo.minecraftai.autosprint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the server-to-client handoff of Baritone's resolved, hunger-aware sprint decision. */
final class AutoSprintControllerSourceContractTest {
    private static final Path CONTROLLER = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/autosprint/AutoSprintController.java");

    @Test
    void steeringUsesBaritonesResolvedSprintEventRatherThanItsTemporaryKey() throws IOException {
        String source = Files.readString(CONTROLLER);
        assertTrue(source.contains("SprintStateEvent sprint = new SprintStateEvent()"));
        assertTrue(source.contains("onPlayerSprintState(sprint)"));
        assertTrue(source.contains("Boolean.TRUE.equals(sprint.getState())"));

        int frame = source.indexOf("private static void sendSteeringFrame");
        int destroy = source.indexOf("private static void destroy", frame);
        String steering = source.substring(frame, destroy);
        assertFalse(steering.contains("keys.isInputForcedDown(Input.SPRINT)"),
                "PathExecutor clears its temporary sprint key before the client frame is sent");
    }
}
