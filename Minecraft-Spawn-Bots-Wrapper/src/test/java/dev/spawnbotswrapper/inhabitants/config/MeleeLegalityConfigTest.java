package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code combat.meleeLegality} block: on by default, the only knob is the switch. */
class MeleeLegalityConfigTest {

    @Test
    void itIsOnByDefaultAndRoundTrips(@TempDir Path dir) {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        assertTrue(again.config().combat.meleeLegality.enabled);
        String json = ConfigIO.toJson(again.config());
        assertTrue(json.contains("\"meleeLegality\""), json);
    }

    @Test
    void itCanBeSwitchedOff(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"combat\": { \"meleeLegality\": { \"enabled\": false } } }", StandardCharsets.UTF_8);
        assertFalse(ConfigIO.load(f).config().combat.meleeLegality.enabled);
    }

    @Test
    void anAbsentOrNullBlockFallsBackToTheDefault(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"combat\": { \"meleeLegality\": null } }", StandardCharsets.UTF_8);
        InhabitantsConfig.Combat c = ConfigIO.load(f).config().combat;
        assertNotNull(c.meleeLegality);
        assertTrue(c.meleeLegality.enabled);
        Files.writeString(f, "{ \"combat\": null }", StandardCharsets.UTF_8);
        assertTrue(ConfigIO.load(f).config().combat.meleeLegality.enabled);
    }
}
