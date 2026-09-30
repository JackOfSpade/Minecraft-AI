package dev.spawnbotswrapper.inhabitants.config;

import dev.spawnbotswrapper.inhabitants.util.RangedDistances;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code pvpbotSettings} and {@code rangedPacing} blocks: shipped values, partial blocks, validation. */
class PvpbotSettingsConfigTest {

    private static ConfigIO.LoadResult load(Path dir, String json) throws IOException {
        Path file = dir.resolve("pvpbot_inhabitants.json");
        Files.writeString(file, json);
        return ConfigIO.load(file);
    }

    @Test
    void theShippedDefaultsAreTheModMaximumCeilingAndTheArcherRanges() {
        InhabitantsConfig.PvpbotSettings s = new InhabitantsConfig().pvpbotSettings;
        assertEquals(128.0, s.maxTargetDistance, "a ceiling only, the mod maximum: line of sight decides");
        assertEquals(8.0, s.rangedMinRange);
        assertEquals(12.0, s.rangedOptimalRange);
        assertEquals(16.0, s.rangedMaxRange);
        assertEquals(Boolean.FALSE, s.autoEquipWeapon);
        assertEquals(Boolean.FALSE, s.autoTargetEnabled, "the aggro controller acquires, PvP BOT does not");
        assertEquals(List.of(), ConfigValidator.validate(new InhabitantsConfig()), "the shipped values are valid");
    }

    @Test
    void theShippedPacingIsFourTicksOfAimSettleAndTwentySixBetweenShots() {
        InhabitantsConfig.RangedPacing p = new InhabitantsConfig().rangedPacing;
        assertTrue(p.enabled);
        assertEquals(4, p.aimSettleTicks);
        assertEquals(26, p.crossbowMinShotIntervalTicks);
    }

    @Test
    void aFileWithoutTheBlockGetsTheShippedValues(@TempDir Path dir) throws IOException {
        InhabitantsConfig c = load(dir, "{ \"enabled\": true }").config();
        assertEquals(128.0, c.pvpbotSettings.maxTargetDistance);
        assertEquals(Boolean.FALSE, c.pvpbotSettings.autoTargetEnabled);
        assertEquals(Boolean.FALSE, c.pvpbotSettings.autoEquipWeapon);
    }

    @Test
    void aPartialBlockManagesOnlyWhatItNames(@TempDir Path dir) throws IOException {
        InhabitantsConfig c = load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 16 } }").config();
        assertEquals(16.0, c.pvpbotSettings.maxTargetDistance);
        assertNull(c.pvpbotSettings.rangedMinRange);
        assertNull(c.pvpbotSettings.rangedOptimalRange);
        assertNull(c.pvpbotSettings.rangedMaxRange);
        assertNull(c.pvpbotSettings.autoEquipWeapon, "an absent key inside a present block leaves PvP BOT's value alone");
        assertNull(c.pvpbotSettings.autoTargetEnabled);
    }

    @Test
    void anExplicitNullLeavesThatSettingAloneAndANullBlockManagesNothing(@TempDir Path dir) throws IOException {
        InhabitantsConfig c = load(dir, "{ \"pvpbotSettings\": { \"autoEquipWeapon\": null, \"maxTargetDistance\": 12 } }").config();
        assertNull(c.pvpbotSettings.autoEquipWeapon);
        assertEquals(12.0, c.pvpbotSettings.maxTargetDistance);

        ConfigIO.LoadResult r = load(dir, "{ \"pvpbotSettings\": null }");
        assertNotNull(r.config().pvpbotSettings);
        assertTrue(r.config().pvpbotSettings.isEmpty());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("pvpbotSettings")), r.warnings().toString());
    }

    @Test
    void theTargetDistanceIsClampedToFourToOneHundredTwentyEight(@TempDir Path dir) throws IOException {
        ConfigIO.LoadResult low = load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 1 } }");
        assertEquals(4.0, low.config().pvpbotSettings.maxTargetDistance);
        assertTrue(low.warnings().stream().anyMatch(w -> w.contains("maxTargetDistance") && w.contains("4.0..128.0")));
        ConfigIO.LoadResult high = load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 200 } }");
        assertEquals(128.0, high.config().pvpbotSettings.maxTargetDistance);
    }

    @Test
    void disorderedRangesAreDroppedTogetherWithAWarning(@TempDir Path dir) throws IOException {
        ConfigIO.LoadResult r = load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 10, \"rangedMinRange\": 9, "
                + "\"rangedOptimalRange\": 8, \"rangedMaxRange\": 10 } }");
        InhabitantsConfig.PvpbotSettings s = r.config().pvpbotSettings;
        assertNull(s.rangedMinRange);
        assertNull(s.rangedOptimalRange);
        assertNull(s.rangedMaxRange);
        assertEquals(10.0, s.maxTargetDistance, "the other settings are unaffected");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("must be below") && w.contains("not managed")),
                r.warnings().toString());
    }

    @Test
    void aRangedMaximumBeyondTheTargetDistanceIsDropped(@TempDir Path dir) throws IOException {
        ConfigIO.LoadResult r = load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 10, \"rangedMinRange\": 6, "
                + "\"rangedOptimalRange\": 8, \"rangedMaxRange\": 20 } }");
        assertNull(r.config().pvpbotSettings.rangedMaxRange);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("must not exceed maxTargetDistance")), r.warnings().toString());
    }

    @Test
    void aPartiallyConfiguredRangeSetIsLeftForTheApplyStepToJudge(@TempDir Path dir) throws IOException {
        ConfigIO.LoadResult r = load(dir, "{ \"pvpbotSettings\": { \"rangedMinRange\": 6 } }");
        assertEquals(6.0, r.config().pvpbotSettings.rangedMinRange);
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void pacingValuesAreClampedAndABrokenBlockFallsBackToTheDefaults(@TempDir Path dir) throws IOException {
        InhabitantsConfig.RangedPacing p = load(dir,
                "{ \"rangedPacing\": { \"aimSettleTicks\": 500, \"crossbowMinShotIntervalTicks\": 0 } }").config().rangedPacing;
        assertEquals(40, p.aimSettleTicks);
        assertEquals(1, p.crossbowMinShotIntervalTicks);
        ConfigIO.LoadResult nulled = load(dir, "{ \"rangedPacing\": null }");
        assertEquals(26, nulled.config().rangedPacing.crossbowMinShotIntervalTicks);
        assertTrue(nulled.warnings().stream().anyMatch(w -> w.contains("rangedPacing")));
    }

    @Test
    void theWrittenDefaultFileContainsBothBlocksWithTheirKeys() {
        String json = ConfigIO.toJson(new InhabitantsConfig());
        for (String key : List.of("\"pvpbotSettings\"", "\"maxTargetDistance\": 128.0", "\"rangedMinRange\": 8.0",
                "\"rangedOptimalRange\": 12.0", "\"rangedMaxRange\": 16.0", "\"autoEquipWeapon\": false", "\"autoTargetEnabled\": false",
                "\"rangedPacing\"", "\"aimSettleTicks\": 4", "\"crossbowMinShotIntervalTicks\": 26")) {
            assertTrue(json.contains(key), key + " missing from:\n" + json);
        }
    }

    @Test
    void theOrderingRuleAcceptsAnEqualOptimalAndMaximumAndRejectsEverythingElse() {
        assertNull(RangedDistances.problem(6.0, 8.0, 10.0, 10.0));
        assertNull(RangedDistances.problem(6.0, 10.0, 10.0, 10.0), "optimal may equal max");
        assertNull(RangedDistances.problem(null, null, null, 10.0));
        assertNotNull(RangedDistances.problem(8.0, 8.0, 10.0, 10.0), "min must be strictly below optimal");
        assertNotNull(RangedDistances.problem(6.0, 11.0, 10.0, 12.0), "optimal above max");
        assertNotNull(RangedDistances.problem(6.0, 8.0, 10.0, 9.0), "max above the target distance");
        assertNotNull(RangedDistances.problem(-1.0, null, null, null));
        assertEquals("10", RangedDistances.text(10.0));
        assertEquals("6.5", RangedDistances.text(6.5));
    }
}
