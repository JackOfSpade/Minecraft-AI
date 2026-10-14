package io.github.zoyluo.minecraftai.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Ensures ending server-driven input hands control back to keys that are already physically held. */
final class AutoSprintClientSourceContractTest {
    private static final Path CLIENT = Path.of(
            "src/client/java/io/github/zoyluo/minecraftai/client/AutoSprintClient.java");

    @Test
    void stoppingRepollsPhysicalBindingsInsteadOfClearingHeldKeys() throws IOException {
        String source = Files.readString(CLIENT);
        int deactivate = source.indexOf("private void deactivate(Minecraft client)");
        String body = source.substring(deactivate);

        assertTrue(body.contains("KeyMapping.setAll();"),
                "auto-sprint stop must restore the physical keyboard/mouse state immediately");
        assertTrue(body.contains("client.options.keySprint.isDown()"),
                "the local sprint state must agree with the restored physical binding");
        assertFalse(body.contains(".setDown(false)"),
                "a held physical movement key must never be stranded false when auto-sprint stops");
    }
}
