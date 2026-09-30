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

    @Test
    void thePerceptionBlockDefaultsToTheSharedModel(@TempDir Path dir) {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        InhabitantsConfig.AggroPerception p = again.config().aggro.perception;
        assertTrue(p.enabled);
        assertEquals(60.0, p.frontHalfAngleDeg);
        assertEquals(100.0, p.peripheralHalfAngleDeg);
        assertEquals(0.5, p.peripheralFactor);
        assertEquals(0.5, p.sneakFactor);
        assertEquals(4.0, p.hearWalk);
        assertEquals(8.0, p.hearSprint);
        assertEquals(12.0, p.hearCombat);
        assertEquals(10, p.combatNoiseTicks);
        assertTrue(ConfigIO.toJson(again.config()).contains("\"perception\""));
    }

    @Test
    void thePerceptionBlockCanBeSwitchedOffAndTuned(@TempDir Path dir) throws IOException {
        InhabitantsConfig.AggroPerception p = load(dir,
                "{ \"perception\": { \"enabled\": false, \"sneakFactor\": 0.25, \"hearWalk\": 6 } }").perception;
        assertFalse(p.enabled);
        assertEquals(0.25, p.sneakFactor);
        assertEquals(6.0, p.hearWalk);
        assertEquals(60.0, p.frontHalfAngleDeg, "keys left out keep their defaults");
    }

    @Test
    void thePerceptionBoundsAreKeptCoherent(@TempDir Path dir) throws IOException {
        InhabitantsConfig.AggroPerception p = load(dir, "{ \"perception\": { \"frontHalfAngleDeg\": 250, "
                + "\"peripheralHalfAngleDeg\": 30, \"peripheralFactor\": 3, \"sneakFactor\": -1, "
                + "\"hearWalk\": -2, \"hearSprint\": 999, \"hearCombat\": -1, \"combatNoiseTicks\": -5 } }").perception;
        assertEquals(180.0, p.frontHalfAngleDeg);
        assertEquals(180.0, p.peripheralHalfAngleDeg, "the field is never narrower than the front cone");
        assertEquals(1.0, p.peripheralFactor);
        assertEquals(0.0, p.sneakFactor);
        assertEquals(0.0, p.hearWalk);
        assertEquals(64.0, p.hearSprint);
        assertEquals(0.0, p.hearCombat);
        assertEquals(0, p.combatNoiseTicks);
        InhabitantsConfig.AggroPerception q = load(dir,
                "{ \"perception\": { \"frontHalfAngleDeg\": 80, \"peripheralHalfAngleDeg\": 50 } }").perception;
        assertEquals(80.0, q.frontHalfAngleDeg);
        assertEquals(80.0, q.peripheralHalfAngleDeg);
    }

    @Test
    void aMissingPerceptionBlockIsFilledIn(@TempDir Path dir) throws IOException {
        InhabitantsConfig.Aggro a = load(dir, "{ \"perception\": null }");
        assertTrue(a.perception != null && a.perception.enabled);
    }
}
