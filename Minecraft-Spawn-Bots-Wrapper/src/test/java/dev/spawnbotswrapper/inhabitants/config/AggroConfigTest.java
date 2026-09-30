package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code aggro} block: defaults, round trip and the bounds that keep the rules coherent. */
class AggroConfigTest {

    @Test
    void defaultsAreTheAgreedRules(@TempDir Path dir) {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        InhabitantsConfig.Aggro a = again.config().aggro;
        assertTrue(a.enabled);
        assertEquals(10.0, a.acquireRange);
        assertTrue(a.requireLineOfSight);
        assertEquals(5, a.scanIntervalTicks);
        assertEquals(32.0, a.leashRange);
        assertEquals(200, a.loseSightTicks);
        assertTrue(a.returnToOrigin);
        assertEquals(1.5, a.returnArriveDistance);
        assertEquals(200, a.returnStuckTicks);
        assertEquals(1200, a.returnMaxTicks);
        String json = ConfigIO.toJson(again.config());
        assertTrue(json.contains("\"leashRange\""), json);
        assertFalse(json.contains("forgetAfterTicksOutOfSight"), "the earlier draft's key is gone");
    }

    private static InhabitantsConfig.Aggro load(Path dir, String aggroJson) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": " + aggroJson + " }", StandardCharsets.UTF_8);
        return ConfigIO.load(f).config().aggro;
    }

    @Test
    void theAcquireRangeIsClampedToTwoToSixtyFour(@TempDir Path dir) throws IOException {
        assertEquals(2.0, load(dir, "{ \"acquireRange\": 0.5 }").acquireRange);
        assertEquals(64.0, load(dir, "{ \"acquireRange\": 500 }").acquireRange);
        assertEquals(12.5, load(dir, "{ \"acquireRange\": 12.5 }").acquireRange);
    }

    @Test
    void theLeashIsNeverSmallerThanTheAcquireRangeNorAbove128(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro a = load(dir, "{ \"acquireRange\": 20, \"leashRange\": 5 }");
        assertEquals(20.0, a.acquireRange);
        assertEquals(20.0, a.leashRange, "the leash cannot be inside the range a bot notices players from");
        assertEquals(128.0, load(dir, "{ \"leashRange\": 1000 }").leashRange);
        assertEquals(40.0, load(dir, "{ \"leashRange\": 40 }").leashRange);
    }

    @Test
    void tickCountsMustBeAtLeastOne(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro a = load(dir, "{ \"scanIntervalTicks\": 0, \"loseSightTicks\": -4, "
                + "\"returnStuckTicks\": 0, \"returnMaxTicks\": 0 }");
        assertEquals(1, a.scanIntervalTicks);
        assertEquals(1, a.loseSightTicks);
        assertEquals(1, a.returnStuckTicks);
        assertEquals(1, a.returnMaxTicks);
    }

    @Test
    void theArriveDistanceIsKeptSane(@TempDir Path dir) throws IOException {
        assertEquals(0.5, load(dir, "{ \"returnArriveDistance\": 0 }").returnArriveDistance);
        assertEquals(16.0, load(dir, "{ \"returnArriveDistance\": 99 }").returnArriveDistance);
    }

    @Test
    void anOutOfRangeValueIsReportedAsAWarning(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"aggro\": { \"acquireRange\": 500 } }", StandardCharsets.UTF_8);
        assertTrue(ConfigIO.load(f).warnings().stream().anyMatch(w -> w.contains("aggro.acquireRange")));
    }
}
