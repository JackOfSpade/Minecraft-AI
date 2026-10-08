package dev.spawnbotswrapper.inhabitants.config;

import dev.spawnbotswrapper.inhabitants.util.RangedDistances;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code pvpbotSettings} block (and the retired {@code rangedPacing} keys): shipped values, partial blocks (an absent key takes
 * its shipped value, an explicit null leaves PvP BOT alone), validation.
 */
class PvpbotSettingsConfigTest {

    private static ConfigIO.LoadResult load(Path dir, String json) throws IOException {
        Path file = dir.resolve("pvpbot_inhabitants.json");
        Files.writeString(file, json);
        return ConfigIO.load(file);
    }

    @Test
    void theShippedDefaultsAreTheEngageLimitAndTheArcherRanges() {
        InhabitantsConfig.PvpbotSettings s = new InhabitantsConfig().pvpbotSettings;
        assertEquals(32.0, s.maxTargetDistance, "PvP BOT only has to cover the 32 block engage limit");
        assertEquals(8.0, s.rangedMinRange);
        assertEquals(12.0, s.rangedOptimalRange);
        assertEquals(16.0, s.rangedMaxRange);
        assertEquals(Boolean.FALSE, s.autoEquipWeapon);
        assertEquals(Boolean.FALSE, s.autoTargetEnabled, "the aggro controller acquires, PvP BOT does not");
        assertEquals(20, s.bowMinDrawTime, "vanilla full power, not PvP BOT's artificial 40 tick wait");
        assertEquals(Boolean.FALSE, s.autoTotemEnabled, "the offhand is the addon's offhand policy, not PvP BOT's auto-totem");
        assertEquals(Boolean.FALSE, s.totemPriority, "a bot blocks with the shield already in its offhand");
        assertEquals(List.of(), ConfigValidator.validate(new InhabitantsConfig()), "the shipped values are valid");
    }

    @Test
    void theBowDrawTimeIsClampedToFiveToOneHundred(@TempDir Path dir) throws IOException {
        assertEquals(5, load(dir, "{ \"pvpbotSettings\": { \"bowMinDrawTime\": 1 } }").config().pvpbotSettings.bowMinDrawTime);
        assertEquals(100, load(dir, "{ \"pvpbotSettings\": { \"bowMinDrawTime\": 900 } }").config().pvpbotSettings.bowMinDrawTime);
        assertEquals(21, load(dir, "{ \"pvpbotSettings\": { \"bowMinDrawTime\": 21 } }").config().pvpbotSettings.bowMinDrawTime);
        assertEquals(20, load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 20 } }").config().pvpbotSettings.bowMinDrawTime,
                "an absent key inside a present block takes the shipped value");
        assertNull(load(dir, "{ \"pvpbotSettings\": { \"bowMinDrawTime\": null } }").config().pvpbotSettings.bowMinDrawTime,
                "an explicit null leaves PvP BOT's value alone");
    }

    @Test
    void aFileWithoutTheBlockGetsTheShippedValues(@TempDir Path dir) throws IOException {
        InhabitantsConfig c = load(dir, "{ \"enabled\": true }").config();
        assertEquals(32.0, c.pvpbotSettings.maxTargetDistance);
        assertEquals(Boolean.FALSE, c.pvpbotSettings.autoTargetEnabled);
        assertEquals(Boolean.FALSE, c.pvpbotSettings.autoEquipWeapon);
        assertEquals(Boolean.FALSE, c.pvpbotSettings.autoTotemEnabled, "no block: the offhand rule's auto-totem switch is managed off");
        assertEquals(Boolean.FALSE, c.pvpbotSettings.totemPriority, "no block: totem priority is managed off");
        assertEquals(List.of(), load(dir, "{ \"enabled\": true }").notes(), "nothing is backfilled, the shipped block is in place");
    }

    /**
     * Premise since the offhand rule: a partial block used to manage only what it names (an absent key meant "leave PvP BOT alone"),
     * which left an existing install's offhand rule inert, because its file predates autoTotemEnabled and totemPriority. Now an absent
     * key takes its shipped value; only an explicit null leaves PvP BOT's value alone (see the next test).
     */
    @Test
    void aPartialBlockManagesEveryKeyWithItsShippedValueUnlessThatKeyIsExplicitlyNull(@TempDir Path dir) throws IOException {
        ConfigIO.LoadResult r = load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 16 } }");
        InhabitantsConfig.PvpbotSettings c = r.config().pvpbotSettings;
        assertEquals(16.0, c.maxTargetDistance, "what the block names is kept");
        InhabitantsConfig.PvpbotSettings shipped = InhabitantsConfig.PvpbotSettings.shipped();
        assertEquals(shipped.rangedMinRange, c.rangedMinRange);
        assertEquals(shipped.rangedOptimalRange, c.rangedOptimalRange);
        assertEquals(shipped.rangedMaxRange, c.rangedMaxRange);
        assertEquals(Boolean.FALSE, c.autoEquipWeapon);
        assertEquals(Boolean.FALSE, c.autoTargetEnabled);
        assertEquals(Boolean.FALSE, c.rangedRetreatOnClose);
        assertEquals(2.5, c.meleeRange);
        assertEquals(20, c.bowMinDrawTime);
        assertEquals(Boolean.FALSE, c.autoTotemEnabled, "an existing block without the offhand keys still manages auto-totem off");
        assertEquals(Boolean.FALSE, c.totemPriority, "and totem priority off");
        assertEquals(1, r.notes().size(), r.notes().toString());
        assertTrue(r.notes().get(0).contains("autoTotemEnabled") && r.notes().get(0).contains("totemPriority")
                && !r.notes().get(0).contains("maxTargetDistance"), r.notes().toString());
        assertEquals(List.of(), r.warnings(), "16 covers the shipped archer ranges, nothing is wrong");
        // A block that already names every key changes nothing and says nothing.
        ConfigIO.LoadResult full = load(dir, ConfigIO.toJson(new InhabitantsConfig()));
        assertEquals(List.of(), full.notes());
    }

    @Test
    void anExplicitNullLeavesThatSettingAloneAndANullBlockManagesNothing(@TempDir Path dir) throws IOException {
        InhabitantsConfig c = load(dir, "{ \"pvpbotSettings\": { \"autoEquipWeapon\": null, \"maxTargetDistance\": 12 } }").config();
        assertNull(c.pvpbotSettings.autoEquipWeapon);
        assertEquals(12.0, c.pvpbotSettings.maxTargetDistance);
        // The offhand keys: explicit null = unmanaged (PvP BOT keeps its own auto-totem and totem priority), absent = managed false.
        InhabitantsConfig optOut = load(dir, "{ \"pvpbotSettings\": { \"autoTotemEnabled\": null, \"totemPriority\": null } }").config();
        assertNull(optOut.pvpbotSettings.autoTotemEnabled);
        assertNull(optOut.pvpbotSettings.totemPriority);
        assertEquals(32.0, optOut.pvpbotSettings.maxTargetDistance, "the other absent keys still take their shipped value");
        // An explicit true is honoured (the admin gives the offhand back to PvP BOT).
        InhabitantsConfig back = load(dir, "{ \"pvpbotSettings\": { \"autoTotemEnabled\": true, \"totemPriority\": true } }").config();
        assertEquals(Boolean.TRUE, back.pvpbotSettings.autoTotemEnabled);
        assertEquals(Boolean.TRUE, back.pvpbotSettings.totemPriority);
        // An empty block is a present block: every key takes its shipped value.
        assertEquals(Boolean.FALSE, load(dir, "{ \"pvpbotSettings\": { } }").config().pvpbotSettings.autoTotemEnabled);

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
        // The absent optimal and max ranges take their shipped 12 and 16 (see the partial-block test), so 6 / 12 / 16 is a valid set.
        ConfigIO.LoadResult r = load(dir, "{ \"pvpbotSettings\": { \"rangedMinRange\": 6 } }");
        assertEquals(6.0, r.config().pvpbotSettings.rangedMinRange);
        assertEquals(12.0, r.config().pvpbotSettings.rangedOptimalRange);
        assertEquals(16.0, r.config().pvpbotSettings.rangedMaxRange);
        assertEquals(List.of(), r.warnings());
        // A minimum that does not fit the shipped optimum is judged like any other disordered set: dropped together with a warning.
        ConfigIO.LoadResult bad = load(dir, "{ \"pvpbotSettings\": { \"rangedMinRange\": 13 } }");
        assertNull(bad.config().pvpbotSettings.rangedMinRange);
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("must be below")), bad.warnings().toString());
    }

    @Test
    void theRetiredPacingKeysAreIgnoredWithOneInfoNoteAndNeverFailTheLoad(@TempDir Path dir) throws IOException {
        ConfigIO.LoadResult r = load(dir,
                "{ \"rangedPacing\": { \"enabled\": true, \"aimSettleTicks\": 4, \"crossbowMinShotIntervalTicks\": 26 } }");
        assertNull(r.fatalError());
        assertEquals(List.of(), r.warnings(), "not a problem, just information");
        assertEquals(1, r.notes().size(), r.notes().toString());
        assertTrue(r.notes().get(0).contains("rangedPacing.aimSettleTicks")
                && r.notes().get(0).contains("rangedPacing.crossbowMinShotIntervalTicks"), r.notes().toString());
        assertFalse(ConfigIO.toJson(r.config()).contains("rangedPacing"), "and they are not written back");
        assertEquals(List.of(), load(dir, "{ \"enabled\": true }").notes(), "nothing to say without them");
    }

    @Test
    void theWrittenDefaultFileContainsTheBlockWithItsKeys() {
        String json = ConfigIO.toJson(new InhabitantsConfig());
        for (String key : List.of("\"pvpbotSettings\"", "\"maxTargetDistance\": 32.0", "\"rangedMinRange\": 8.0",
                "\"rangedOptimalRange\": 12.0", "\"rangedMaxRange\": 16.0", "\"autoEquipWeapon\": false", "\"autoTargetEnabled\": false",
                "\"bowMinDrawTime\": 20", "\"autoTotemEnabled\": false", "\"totemPriority\": false")) {
            assertTrue(json.contains(key), key + " missing from:\n" + json);
        }
        assertFalse(json.contains("rangedPacing"), json);
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

    @Test
    void theShippedDefaultTurnsPvpBotsArcherRetreatOffSoABowCarrierUsesItsMeleeWeaponUpClose(@TempDir Path dir) throws IOException {
        assertEquals(Boolean.FALSE, new InhabitantsConfig().pvpbotSettings.rangedRetreatOnClose);
        assertEquals(Boolean.FALSE, load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 16 } }").config().pvpbotSettings.rangedRetreatOnClose,
                "an absent key inside a present block takes the shipped value");
        assertNull(load(dir, "{ \"pvpbotSettings\": { \"rangedRetreatOnClose\": null } }").config().pvpbotSettings.rangedRetreatOnClose,
                "an explicit null leaves PvP BOT's value alone");
        assertEquals(Boolean.TRUE, load(dir, "{ \"pvpbotSettings\": { \"rangedRetreatOnClose\": true } }")
                .config().pvpbotSettings.rangedRetreatOnClose);
        assertTrue(ConfigIO.toJson(new InhabitantsConfig()).contains("\"rangedRetreatOnClose\": false"));
    }

    @Test
    void theShippedMeleeRangeIsTwoAndAHalfSoTheSwordComesOutAtFiveBlocks(@TempDir Path dir) throws IOException {
        assertEquals(2.5, new InhabitantsConfig().pvpbotSettings.meleeRange);
        assertEquals(2.5, load(dir, "{ \"pvpbotSettings\": { \"maxTargetDistance\": 16 } }").config().pvpbotSettings.meleeRange,
                "an absent key inside a present block takes the shipped value");
        assertNull(load(dir, "{ \"pvpbotSettings\": { \"meleeRange\": null } }").config().pvpbotSettings.meleeRange,
                "an explicit null leaves PvP BOT's value alone");
        assertEquals(3.0, load(dir, "{ \"pvpbotSettings\": { \"meleeRange\": 3 } }").config().pvpbotSettings.meleeRange);
        assertTrue(ConfigIO.toJson(new InhabitantsConfig()).contains("\"meleeRange\": 2.5"));
    }
}
