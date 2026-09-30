package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DisabledEnchantmentsTest {

    @Test
    void idsAreAcceptedWithOrWithoutNamespaceAndInAnyCase() {
        assertEquals("minecraft:piercing", DisabledEnchantments.normalize("piercing"));
        assertEquals("minecraft:piercing", DisabledEnchantments.normalize("minecraft:piercing"));
        assertEquals("minecraft:piercing", DisabledEnchantments.normalize("  Minecraft:Piercing "));
        assertEquals("othermod:frost_bite", DisabledEnchantments.normalize("othermod:frost_bite"));
        assertNull(DisabledEnchantments.normalize(null));
        assertNull(DisabledEnchantments.normalize("   "));
        assertNull(DisabledEnchantments.normalize("two:colons:here"));
        assertNull(DisabledEnchantments.normalize("has space"));
        assertNull(DisabledEnchantments.normalize("minecraft:"));
    }

    @Test
    void parseSkipsGarbageDeduplicatesAndToleratesNull() {
        assertEquals(Set.of("minecraft:piercing", "minecraft:mending"),
                DisabledEnchantments.parse(Arrays.asList("piercing", "MINECRAFT:PIERCING", "???", null, "mending")));
        assertEquals(Set.of(), DisabledEnchantments.parse(null));
        assertEquals(Set.of(), DisabledEnchantments.parse(List.of()));
    }

    @Test
    void cleanWarnsOncePerBadEntryAndKeepsTheRest() {
        List<String> warnings = new ArrayList<>();
        List<String> cleaned = DisabledEnchantments.clean(
                List.of("piercing", "minecraft:piercng", "bad id", "othermod:thing", "minecraft:piercing"), "x", warnings);
        assertEquals(List.of("minecraft:piercing", "othermod:thing"), cleaned);
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("piercng"), warnings.toString());
        assertTrue(warnings.get(1).contains("bad id"), warnings.toString());
    }

    @Test
    void defaultConfigDisablesPiercingAndMendingWithoutWarnings() {
        assertEquals(List.of("minecraft:piercing", "minecraft:mending"), DisabledEnchantments.DEFAULT);
        InhabitantsConfig c = new InhabitantsConfig();
        List<String> warnings = ConfigValidator.validate(c);
        assertEquals(List.of("minecraft:piercing", "minecraft:mending"), c.profiles.disabledEnchantments);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("disabledEnchantments")), warnings.toString());
    }

    @Test
    void anEmptyListStaysEmptyAndNullFallsBackToTheDefault() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.profiles.disabledEnchantments = new ArrayList<>();
        assertTrue(ConfigValidator.validate(c).stream().noneMatch(w -> w.contains("disabledEnchantments")));
        assertEquals(List.of(), c.profiles.disabledEnchantments);

        c.profiles.disabledEnchantments = null;
        List<String> warnings = ConfigValidator.validate(c);
        assertEquals(List.of("minecraft:piercing", "minecraft:mending"), c.profiles.disabledEnchantments);
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void validateNormalizesTheListAndWarnsOnceForAnUnknownId() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.profiles.disabledEnchantments = new ArrayList<>(List.of("Piercing", "minecraft:sharpnes"));
        List<String> warnings = ConfigValidator.validate(c);
        assertEquals(List.of("minecraft:piercing"), c.profiles.disabledEnchantments);
        assertEquals(1, warnings.stream().filter(w -> w.contains("sharpnes")).count(), warnings.toString());
        // Validating the repaired config again is quiet.
        assertTrue(ConfigValidator.validate(c).isEmpty());
    }

    @Test
    void anOldConfigFileWithoutTheKeyGetsTheDefaultAndAWrittenListIsHonoured(@TempDir Path dir) throws IOException {
        Path old = dir.resolve("old.json");
        Files.writeString(old, "{ \"profiles\": { \"randomize\": true } }", StandardCharsets.UTF_8);
        ConfigIO.LoadResult loaded = ConfigIO.load(old);
        assertNull(loaded.fatalError());
        assertEquals(List.of("minecraft:piercing", "minecraft:mending"), loaded.config().profiles.disabledEnchantments);

        // A config that explicitly lists only piercing keeps that choice: Mending is allowed again.
        Path piercingOnly = dir.resolve("piercing_only.json");
        Files.writeString(piercingOnly, "{ \"profiles\": { \"disabledEnchantments\": [\"minecraft:piercing\"] } }",
                StandardCharsets.UTF_8);
        assertEquals(List.of("minecraft:piercing"), ConfigIO.load(piercingOnly).config().profiles.disabledEnchantments);

        Path custom = dir.resolve("custom.json");
        Files.writeString(custom, "{ \"profiles\": { \"disabledEnchantments\": [\"multishot\", \"minecraft:mending\"] } }",
                StandardCharsets.UTF_8);
        assertEquals(List.of("minecraft:multishot", "minecraft:mending"),
                ConfigIO.load(custom).config().profiles.disabledEnchantments);

        Path none = dir.resolve("none.json");
        Files.writeString(none, "{ \"profiles\": { \"disabledEnchantments\": [] } }", StandardCharsets.UTF_8);
        assertEquals(List.of(), ConfigIO.load(none).config().profiles.disabledEnchantments);
    }
}
