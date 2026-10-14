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

    @Test
    void aWorldTransferOrSameDimensionRespawnStopsBeforeAnyStaleSteeringFrameCanBeSent() throws IOException {
        String controller = Files.readString(CONTROLLER);
        int before = controller.indexOf("public void beforeWorldTick");
        int after = controller.indexOf("public void afterWorldTick", before);
        int disconnect = controller.indexOf("public void onDisconnect", after);
        String beforeTick = controller.substring(before, after);
        String afterTick = controller.substring(after, disconnect);

        assertTrue(beforeTick.indexOf("discardIfInactive(entry)") < beforeTick.indexOf("entry.level != level"));
        assertTrue(afterTick.indexOf("discardIfInactive(entry)") < afterTick.indexOf("entry.level != level"),
                "the end-of-world callback must not send a frame after the player has changed worlds");
        int worldChange = controller.indexOf("public void onWorldChange(ServerPlayer player)");
        int inactiveCheck = controller.indexOf("private boolean discardIfInactive", worldChange);
        assertTrue(worldChange >= 0 && inactiveCheck > worldChange,
                "a world-transfer callback must retain a dedicated input-cleanup implementation");
        String worldChangeBody = controller.substring(worldChange, inactiveCheck);
        assertTrue(worldChangeBody.contains("destroy(entry, false);")
                        && worldChangeBody.contains("sendStop(player);"),
                "a world-transfer callback must restore input through its destination player, which can replace the removed player on respawn");
        assertTrue(controller.contains("entries.get(player.getUUID()) != entry"),
                "a retired snapshot entry must not send one final steering frame after it is removed");

        String mod = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/MinecraftAiMod.java"));
        assertTrue(mod.contains("ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register")
                        && mod.contains("AutoSprintController.INSTANCE.onWorldChange(player)"),
                "portal transfers must notify the autosprint controller directly");
        assertTrue(mod.contains("ServerPlayerEvents.AFTER_RESPAWN.register")
                        && mod.contains("AutoSprintController.INSTANCE.onWorldChange(newPlayer)"),
                "same-dimension respawn replaces ServerPlayer without firing a world-change event, so it must also stop autosprint");
    }
}
