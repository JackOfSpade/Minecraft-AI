package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lag governor / critical-hit / combat-log configuration: defaults, the legacy keys of an installed config, repairs. */
class ConfigGovernorTest {

    /** The installed config of this pack: legacy tpsThrottle keys, its own startupCommands. It must load and take the new defaults. */
    @Test
    void anInstalledConfigWithTheLegacyThrottleKeysLoadsWarnsAndGetsTheNewDefaults(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, """
                {
                  "processing": { "restoreSettleTicks": 1200, "maxLiveBots": 64 },
                  "startupCommands": [ "pvpbot settings auto-target true", "pvpbot settings view-distance 16" ],
                  "tpsThrottle": { "enabled": true, "healthyMillis": 52.6, "degradedMillis": 55.6,
                                   "checkIntervalTicks": 100, "despawnBatchSize": 3 }
                }
                """, StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNull(r.fatalError());
        InhabitantsConfig.TpsThrottle t = r.config().tpsThrottle;
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("healthyMillis") && w.contains("no longer used")),
                r.warnings().toString());
        assertNull(t.healthyMillis);
        assertNull(t.degradedMillis);
        assertEquals(65.0, t.degradedFloorMillis);
        assertEquals(1.25, t.degradedFactor);
        assertEquals(58.0, t.recoveredFloorMillis);
        assertEquals(1.10, t.recoveredFactor);
        assertEquals(600, t.sustainTicks);
        assertEquals(1200, t.minDwellTicks);
        assertEquals(3, t.despawnBatchSize, "the user's own keys still apply");
        assertEquals(3, r.config().criticalFallTicks);
        assertEquals(List.of("pvpbot settings crit-fall-ticks 3", "pvpbot settings auto-target true",
                        "pvpbot settings view-distance 16"), StartupCommands.plan(r.config()),
                "the managed crit setting is applied even though the installed startupCommands never mention it");
        assertTrue(r.config().combatLog.enabled);
    }

    @Test
    void defaultsRoundTripTheNewGovernorAndCombatLogKeys(@TempDir Path dir) {
        Path f = dir.resolve("cfg.json");
        ConfigIO.load(f);
        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertTrue(again.warnings().isEmpty(), again.warnings().toString());
        assertEquals(65.0, again.config().tpsThrottle.degradedFloorMillis);
        assertEquals(60.0, again.config().tpsThrottle.baselineMaxMillis);
        assertEquals(6000, again.config().tpsThrottle.baselineWindowTicks);
        assertEquals(100, again.config().combatLog.coalesceTicks);
        assertEquals(30, again.config().combatLog.maxLinesPerMinute);
        assertEquals(3, again.config().criticalFallTicks);
        assertFalse(ConfigIO.toJson(again.config()).contains("healthyMillis"), "the legacy keys are not written into a new file");
    }

    @Test
    void governorLevelsAreRepairedWhenTheyWouldLeaveNoHysteresis(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"tpsThrottle\": { \"degradedFloorMillis\": 60, \"recoveredFloorMillis\": 70, \"sustainTicks\": -5 } }",
                StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        InhabitantsConfig.TpsThrottle t = r.config().tpsThrottle;
        assertTrue(t.recoveredFloorMillis < t.degradedFloorMillis,
                "exit " + t.recoveredFloorMillis + " enter " + t.degradedFloorMillis);
        assertEquals(0, t.sustainTicks);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("hysteresis")), r.warnings().toString());
    }

    @Test
    void criticalFallTicksAndTheCombatLogAreClamped(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"criticalFallTicks\": 99, \"combatLog\": { \"coalesceTicks\": 0, \"maxLinesPerMinute\": -3 } }",
                StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertEquals(10, r.config().criticalFallTicks);
        assertEquals(1, r.config().combatLog.coalesceTicks);
        assertEquals(1, r.config().combatLog.maxLinesPerMinute);
    }
}
