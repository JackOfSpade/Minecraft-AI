package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuleResolverTest {

    private static InhabitantsConfig bare() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.structures = new LinkedHashMap<>();
        c.tags = new LinkedHashMap<>();
        return c;
    }

    @Test
    void defaultsApplyWhenNothingMatches() {
        InhabitantsConfig c = bare();
        c.defaults = new InhabitantsConfig.Rule(0.65, 1, 4);
        EffectiveRule r = RuleResolver.resolve(c, "minecraft:desert_pyramid", Set.of());
        assertEquals(0.65, r.occupiedChance());
        assertEquals(1, r.minBots());
        assertEquals(4, r.maxBots());
        assertEquals("default", r.occupiedChanceFrom());
    }

    @Test
    void exactIdBeatsTagBeatsNamespaceBeatsDefault() {
        InhabitantsConfig c = bare();
        c.defaults = new InhabitantsConfig.Rule(0.10, 1, 1);
        c.structures.put("somemod:*", new InhabitantsConfig.RuleOverride(0.20, 2, 2));
        c.tags.put("#minecraft:village", new InhabitantsConfig.RuleOverride(0.30, 3, 3));
        c.structures.put("minecraft:village_plains", new InhabitantsConfig.RuleOverride(0.40, 4, 4));

        EffectiveRule exact = RuleResolver.resolve(c, "minecraft:village_plains", Set.of("minecraft:village"));
        assertEquals(0.40, exact.occupiedChance());
        assertEquals("structure minecraft:village_plains", exact.occupiedChanceFrom());

        EffectiveRule viaTag = RuleResolver.resolve(c, "minecraft:village_desert", Set.of("minecraft:village"));
        assertEquals(0.30, viaTag.occupiedChance());
        assertEquals("tag #minecraft:village", viaTag.occupiedChanceFrom());

        EffectiveRule viaNamespace = RuleResolver.resolve(c, "somemod:tower", Set.of());
        assertEquals(0.20, viaNamespace.occupiedChance());
        assertEquals("namespace somemod:*", viaNamespace.occupiedChanceFrom());

        EffectiveRule dflt = RuleResolver.resolve(c, "othermod:hut", Set.of());
        assertEquals(0.10, dflt.occupiedChance());
    }

    @Test
    void overridesInheritPerField() {
        InhabitantsConfig c = bare();
        c.defaults = new InhabitantsConfig.Rule(0.50, 1, 4);
        c.tags.put("#minecraft:village", new InhabitantsConfig.RuleOverride(0.9, null, null));
        c.structures.put("minecraft:village_plains", new InhabitantsConfig.RuleOverride(null, 2, null));

        EffectiveRule r = RuleResolver.resolve(c, "minecraft:village_plains", Set.of("minecraft:village"));
        assertEquals(0.9, r.occupiedChance());        // from the tag
        assertEquals(2, r.minBots());                 // from the exact id
        assertEquals(4, r.maxBots());                 // from default
        assertEquals("tag #minecraft:village", r.occupiedChanceFrom());
        assertEquals("structure minecraft:village_plains", r.minBotsFrom());
        assertEquals("default", r.maxBotsFrom());
    }

    @Test
    void earlierTagWinsOverLaterTag() {
        InhabitantsConfig c = bare();
        c.tags.put("#a:first", new InhabitantsConfig.RuleOverride(0.1, null, null));
        c.tags.put("#a:second", new InhabitantsConfig.RuleOverride(0.9, null, null));
        EffectiveRule r = RuleResolver.resolve(c, "x:y", Set.of("a:first", "a:second"));
        assertEquals(0.1, r.occupiedChance());
    }

    @Test
    void resultIsClampedAndOrdered() {
        InhabitantsConfig c = bare();
        c.defaults = new InhabitantsConfig.Rule(7.0, 0, 0); // unvalidated on purpose
        EffectiveRule r = RuleResolver.resolve(c, "a:b", Set.of());
        assertEquals(1.0, r.occupiedChance());
        assertEquals(1, r.minBots());
        assertEquals(1, r.maxBots());

        c.defaults = new InhabitantsConfig.Rule(0.5, 9, 3);
        r = RuleResolver.resolve(c, "a:b", Set.of());
        assertTrue(r.maxBots() >= r.minBots());

        c.defaults = new InhabitantsConfig.Rule(0.5, 1, 9999);
        assertEquals(EffectiveRule.MAX_BOTS_PER_STRUCTURE, RuleResolver.resolve(c, "a:b", Set.of()).maxBots());
    }

    @Test
    void eligibilityHonoursIncludeExcludeAndExcludeWins() {
        InhabitantsConfig c = bare();
        c.include = List.of("minecraft:*", "#somemod:special");
        c.exclude = List.of("minecraft:buried_treasure", "#minecraft:ocean_ruin");

        assertTrue(RuleResolver.isEligible(c, "minecraft:village_plains", Set.of()));
        assertFalse(RuleResolver.isEligible(c, "minecraft:buried_treasure", Set.of()));
        assertFalse(RuleResolver.isEligible(c, "minecraft:ocean_ruin_warm", Set.of("minecraft:ocean_ruin")));
        assertFalse(RuleResolver.isEligible(c, "othermod:tower", Set.of()));
        assertTrue(RuleResolver.isEligible(c, "othermod:tower", Set.of("somemod:special")));

        // exclude beats include even when both match
        c.include = List.of("*");
        c.exclude = List.of("minecraft:mansion");
        assertFalse(RuleResolver.isEligible(c, "minecraft:mansion", Set.of()));
    }

    @Test
    void emptyIncludeMeansEverything() {
        InhabitantsConfig c = bare();
        c.include = List.of();
        c.exclude = List.of();
        assertTrue(RuleResolver.isEligible(c, "anything:goes", Set.of()));
    }

    @Test
    void dimensionFiltering() {
        InhabitantsConfig c = bare();
        c.dimensions.include = List.of("*");
        c.dimensions.exclude = List.of("minecraft:the_end");
        assertTrue(RuleResolver.isDimensionEligible(c, "minecraft:overworld"));
        assertFalse(RuleResolver.isDimensionEligible(c, "minecraft:the_end"));
        assertTrue(RuleResolver.isDimensionEligible(c, "somemod:dim"));
    }

    @Test
    void bareIdsAndCaseAreNormalised() {
        assertEquals("minecraft:village_plains", IdMatcher.normalize("  Village_Plains "));
        assertEquals("#minecraft:village", IdMatcher.normalize("#village"));
        assertEquals("*", IdMatcher.normalize(" * "));
        assertNull(IdMatcher.normalize("   "));
        assertTrue(IdMatcher.matches("VILLAGE_PLAINS", "minecraft:village_plains", Set.of()));
        assertTrue(IdMatcher.matches("SomeMod:*", "somemod:x/y", Set.of()));
        assertFalse(IdMatcher.matches("somemod:*", "othermod:x", Set.of()));
    }
}
