package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigIOTest {

    @Test
    void missingFileIsCreatedWithDefaultsThatRoundTrip(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertTrue(r.created());
        assertNull(r.fatalError());
        assertTrue(Files.exists(f));

        ConfigIO.LoadResult again = ConfigIO.load(f);
        assertFalse(again.created());
        assertNull(again.fatalError());
        assertEquals(0.65, again.config().defaults.occupiedChance);
        assertEquals(0.85, again.config().structures.get("minecraft:pillager_outpost").occupiedChance);
        assertEquals(2, again.config().structures.get("minecraft:pillager_outpost").minBots);
    }

    @Test
    void userExampleFromTheBriefParses(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, """
                {
                  "default": { "occupiedChance": 0.65, "minBots": 1, "maxBots": 4 },
                  "structures": {
                    "minecraft:pillager_outpost": { "occupiedChance": 0.85, "minBots": 2, "maxBots": 6 }
                  }
                }
                """, StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNull(r.fatalError());
        assertEquals(1, r.config().structures.size());
        assertEquals(6, r.config().structures.get("minecraft:pillager_outpost").maxBots);
        // sections not present keep their defaults
        assertTrue(r.config().enabled);
        assertEquals(8, r.config().profiles.coverageBuckets);
    }

    @Test
    void commentsAndTrailingCommasAreTolerated(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, """
                {
                  // turn it off for now
                  "enabled": false,
                  "default": { "occupiedChance": 0.5, "minBots": 1, "maxBots": 2, },
                }
                """, StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNull(r.fatalError());
        assertFalse(r.config().enabled);
        assertEquals(0.5, r.config().defaults.occupiedChance);
    }

    @Test
    void brokenFileYieldsDefaultsAndIsNeverOverwritten(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        String broken = "{ this is not json ";
        Files.writeString(f, broken, StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertNotNull(r.fatalError());
        assertNotNull(r.config());
        assertEquals(broken, Files.readString(f, StandardCharsets.UTF_8), "a broken file must be left alone");
    }

    @Test
    void emptyFileIsAFatalErrorNotACrash(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "", StandardCharsets.UTF_8);
        assertNotNull(ConfigIO.load(f).fatalError());
    }

    @Test
    void outOfRangeValuesAreRepairedWithWarnings(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, """
                {
                  "default": { "occupiedChance": 3.5, "minBots": 0, "maxBots": -4 },
                  "processing": { "maxStructuresPerTick": 0, "maxLiveBots": -5 },
                  "profiles": { "coverageBuckets": 1000 },
                  "spawning": { "backend": "nonsense", "namePrefix": "bad prefix!!" },
                  "connection": { "joinHoldTicks": 999999 },
                  "commandPermissionLevel": 9
                }
                """, StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        InhabitantsConfig c = r.config();
        assertEquals(1.0, c.defaults.occupiedChance);
        assertEquals(1, c.defaults.minBots);
        assertTrue(c.defaults.maxBots >= c.defaults.minBots);
        assertEquals(1, c.processing.maxStructuresPerTick);
        assertEquals(0, c.processing.maxLiveBots);
        assertEquals(64, c.profiles.coverageBuckets);
        assertEquals("AUTO", c.spawning.backend);
        assertEquals("badprefi", c.spawning.namePrefix); // stripped to "badprefix", then cut to 8 characters
        assertEquals(72000, c.connection.joinHoldTicks);
        assertEquals(4, c.commandPermissionLevel);
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void aPrefixLeftAtEightCharsWithATrailingUnderscoreIsFixedAndWarnedAbout(@TempDir Path dir) throws IOException {
        // Regression: this prefix is already valid charset and length (8), so the OLD validator accepted it
        // silently as "clean" - while engine.NameGenerator's own separate copy of this rule trimmed the
        // trailing underscore anyway, so the prefix actually stamped on every bot name ("abcd_e") silently
        // differed from the one the config reported as accepted ("abcd_e__"). Both must now agree.
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"spawning\": { \"namePrefix\": \"abcd_e__\" } }", StandardCharsets.UTF_8);
        ConfigIO.LoadResult r = ConfigIO.load(f);
        assertEquals("abcd_e", r.config().spawning.namePrefix);
        assertFalse(r.warnings().isEmpty(), "silently changing the effective prefix must be reported");
    }

    @Test
    void misplacedTagAndStructureKeysAreMovedNotIgnored(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, """
                {
                  "structures": { "#minecraft:village": { "occupiedChance": 0.9 } },
                  "tags": { "minecraft:mineshaft": { "occupiedChance": 0.2 } }
                }
                """, StandardCharsets.UTF_8);
        InhabitantsConfig c = ConfigIO.load(f).config();
        assertTrue(c.tags.containsKey("#minecraft:village"));
        assertTrue(c.tags.containsKey("#minecraft:mineshaft"));
        assertTrue(c.structures.isEmpty());
        EffectiveRule r = RuleResolver.resolve(c, "minecraft:village_taiga", java.util.Set.of("minecraft:village"));
        assertEquals(0.9, r.occupiedChance());
    }

    @Test
    void nullOverridesDoNotBreakResolution(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cfg.json");
        Files.writeString(f, "{ \"structures\": { \"minecraft:igloo\": null } }", StandardCharsets.UTF_8);
        InhabitantsConfig c = ConfigIO.load(f).config();
        EffectiveRule r = RuleResolver.resolve(c, "minecraft:igloo", java.util.Set.of());
        assertEquals("default", r.occupiedChanceFrom());
    }

    @Test
    void saveIsAtomicAndLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("sub/dir/cfg.json");
        ConfigIO.save(f, new InhabitantsConfig());
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(f.resolveSibling("cfg.json.tmp")));
    }
}
