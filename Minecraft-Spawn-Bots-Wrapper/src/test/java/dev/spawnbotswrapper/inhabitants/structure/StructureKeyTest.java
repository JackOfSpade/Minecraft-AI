package dev.spawnbotswrapper.inhabitants.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructureKeyTest {

    @Test
    void roundTripsThroughItsStringForm() {
        StructureKey k = new StructureKey("minecraft:the_nether", "minecraft:fortress", -12, 34);
        assertEquals("minecraft:the_nether|minecraft:fortress|-12,34", k.asString());
        assertEquals(k, StructureKey.parse(k.asString()));
    }

    @Test
    void modIdsWithPathsRoundTrip() {
        StructureKey k = new StructureKey("somemod:custom_dim", "somemod:ruins/big_tower", 0, -1);
        assertEquals(k, StructureKey.parse(k.asString()));
    }

    @Test
    void malformedKeysParseToNull() {
        assertNull(StructureKey.parse(null));
        assertNull(StructureKey.parse(""));
        assertNull(StructureKey.parse("a|b"));
        assertNull(StructureKey.parse("a|b|c"));
        assertNull(StructureKey.parse("a|b|1,x"));
        assertNull(StructureKey.parse("a|b|1,2,3"));
    }

    @Test
    void stableHashDependsOnEveryComponent() {
        StructureKey base = new StructureKey("minecraft:overworld", "minecraft:village_plains", 5, 6);
        assertEquals(base.stableHash(), new StructureKey("minecraft:overworld", "minecraft:village_plains", 5, 6).stableHash());
        assertNotEquals(base.stableHash(), new StructureKey("minecraft:the_nether", "minecraft:village_plains", 5, 6).stableHash());
        assertNotEquals(base.stableHash(), new StructureKey("minecraft:overworld", "minecraft:village_desert", 5, 6).stableHash());
        assertNotEquals(base.stableHash(), new StructureKey("minecraft:overworld", "minecraft:village_plains", 6, 5).stableHash());
        assertNotEquals(base.stableHash(), new StructureKey("minecraft:overworld", "minecraft:village_plains", 5, 7).stableHash());
    }
}
