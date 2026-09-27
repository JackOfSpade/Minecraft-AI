package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.TelemetryProbe.Telemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryProbeTest {

    private static Path write(Path configDir, String text) throws IOException {
        Path file = configDir.resolve(TelemetryProbe.RELATIVE_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void explicitOptOutIsRecognisedInEitherSpelling() {
        assertEquals(Telemetry.DISABLED, TelemetryProbe.interpret("{\"send_anonymous_statistics\": false}"));
        assertEquals(Telemetry.DISABLED, TelemetryProbe.interpret("{\"send_anonymous_statistics\": \"false\"}"));
    }

    @Test
    void explicitOptInIsEnabled() {
        assertEquals(Telemetry.ENABLED, TelemetryProbe.interpret("{\"send_anonymous_statistics\": true}"));
        assertEquals(Telemetry.ENABLED, TelemetryProbe.interpret("{\"send_anonymous_statistics\": \"true\"}"));
        assertTrue(Telemetry.ENABLED.sends());
    }

    @Test
    void everythingElseFailsOpenExactlyLikeUpstream() {
        // Upstream turns statistics off ONLY when the value stringifies to exactly "false".
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("{}"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("{\"other\": 1}"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("{\"send_anonymous_statistics\": \"False\"}"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("{\"send_anonymous_statistics\": 0}"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("{\"send_anonymous_statistics\": null}"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("{\"send_anonymous_statistics\": [false]}"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("this is not json"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("[1,2]"));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.interpret("{\"send_anonymous_statistics\": false"),
                "truncated file: unparsable, so on");
        assertTrue(Telemetry.ENABLED_BY_DEFAULT.sends());
    }

    @Test
    void anEmptyFileTurnsStatisticsOffBecauseUpstreamDoes() {
        assertEquals(Telemetry.DISABLED, TelemetryProbe.interpret(""));
        assertEquals(Telemetry.DISABLED, TelemetryProbe.interpret("   \n"));
        assertEquals(Telemetry.DISABLED, TelemetryProbe.interpret(null));
        assertFalse(Telemetry.DISABLED.sends());
    }

    @Test
    void aMissingFileMeansStatisticsAreOnByDefault(@TempDir Path configDir) {
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.read(configDir));
    }

    @Test
    void anUnknownConfigDirectoryIsUnknownAndNeverWarns() {
        assertEquals(Telemetry.UNKNOWN, TelemetryProbe.read(null));
        assertFalse(Telemetry.UNKNOWN.sends());
    }

    @Test
    void readsTheFileFromTheConfigDirectory(@TempDir Path configDir) throws IOException {
        write(configDir, "{\"send_anonymous_statistics\": false}");
        assertEquals(Telemetry.DISABLED, TelemetryProbe.read(configDir));
        write(configDir, "{\"send_anonymous_statistics\": true}");
        assertEquals(Telemetry.ENABLED, TelemetryProbe.read(configDir));
    }

    @Test
    void neverWritesOrCreatesAnything(@TempDir Path configDir) throws IOException {
        TelemetryProbe.read(configDir);
        try (var listing = Files.list(configDir)) {
            assertEquals(0, listing.count(), "the probe must not create pvpbot/ or the file");
        }
        Path file = write(configDir, "{\"send_anonymous_statistics\": true}");
        long before = Files.getLastModifiedTime(file).toMillis();
        String content = Files.readString(file);
        TelemetryProbe.read(configDir);
        assertEquals(content, Files.readString(file));
        assertEquals(before, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void aDirectoryWhereTheFileShouldBeIsNotACrash(@TempDir Path configDir) throws IOException {
        Files.createDirectories(configDir.resolve(TelemetryProbe.RELATIVE_PATH));
        assertEquals(Telemetry.ENABLED_BY_DEFAULT, TelemetryProbe.read(configDir));
    }
}
