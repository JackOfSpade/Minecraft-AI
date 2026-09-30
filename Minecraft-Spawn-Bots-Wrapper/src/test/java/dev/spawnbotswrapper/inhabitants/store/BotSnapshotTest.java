package dev.spawnbotswrapper.inhabitants.store;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static dev.spawnbotswrapper.inhabitants.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The saved state of an inhabitant: plain data, compared by value, written with its record and read back tolerantly. */
class BotSnapshotTest {

    private static BotSnapshot sample() {
        BotSnapshot s = new BotSnapshot();
        s.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:bow\",\"count\":1,\"components\":{\"minecraft:damage\":31}}"));
        s.stacks.add(new BotSnapshot.Entry(9, "{\"id\":\"minecraft:arrow\",\"count\":37}"));
        s.stacks.add(new BotSnapshot.Entry(38, "{\"id\":\"minecraft:iron_chestplate\",\"count\":1,\"components\":{\"minecraft:damage\":120}}"));
        s.selectedSlot = 2;
        s.health = 13.5f;
        s.foodLevel = 14;
        s.saturation = 1.25f;
        s.exhaustion = 2.5f;
        s.effects.add("{\"id\":\"minecraft:regeneration\",\"duration\":100,\"amplifier\":1}");
        s.xpLevel = 7;
        s.xpProgress = 0.4f;
        s.xpTotal = 91;
        s.fireTicks = -1;
        s.airSupply = 300;
        return s;
    }

    @Test
    void twoSnapshotsWithTheSameContentAreEqual() {
        assertEquals(sample(), sample());
        assertEquals(sample().hashCode(), sample().hashCode());
    }

    @Test
    void anyDifferenceInWhatTheBotCarriesOrHowItIsDoingMakesThemDiffer() {
        BotSnapshot base = sample();
        BotSnapshot fewerArrows = sample();
        fewerArrows.stacks.set(1, new BotSnapshot.Entry(9, "{\"id\":\"minecraft:arrow\",\"count\":36}"));
        assertNotEquals(base, fewerArrows);
        BotSnapshot hurt = sample();
        hurt.health = 13.0f;
        assertNotEquals(base, hurt);
        BotSnapshot hungry = sample();
        hungry.foodLevel = 13;
        assertNotEquals(base, hungry);
        BotSnapshot otherSlot = sample();
        otherSlot.selectedSlot = 3;
        assertNotEquals(base, otherSlot);
    }

    @Test
    void aSnapshotIsWorthWritingWhenAnythingThatMattersChanged() {
        BotSnapshot before = sample();
        assertTrue(BotSnapshot.worthPersisting(null, before), "the first one always");
        assertFalse(BotSnapshot.worthPersisting(before, sample()), "identical: nothing to write");
        assertFalse(BotSnapshot.worthPersisting(before, null));

        BotSnapshot health = sample();
        health.health = 5.0f;
        assertTrue(BotSnapshot.worthPersisting(before, health));
        BotSnapshot arrows = sample();
        arrows.stacks.set(1, new BotSnapshot.Entry(9, "{\"id\":\"minecraft:arrow\",\"count\":36}"));
        assertTrue(BotSnapshot.worthPersisting(before, arrows));
        BotSnapshot effect = sample();
        effect.effects.clear();
        assertTrue(BotSnapshot.worthPersisting(before, effect));
        BotSnapshot food = sample();
        food.foodLevel = 13;
        assertTrue(BotSnapshot.worthPersisting(before, food));
    }

    @Test
    void fireAirAndTheExhaustionAccumulatorAloneNeverCauseAWrite() {
        BotSnapshot before = sample();
        BotSnapshot drifted = sample();
        drifted.fireTicks = 40;
        drifted.airSupply = 120;
        drifted.exhaustion = 3.9f;
        assertNotEquals(before, drifted, "they are part of the snapshot ...");
        assertFalse(BotSnapshot.worthPersisting(before, drifted), "... but they move constantly and never cause a write");
    }

    @Test
    void normalisedRepairsNullsAndDropsUnusableEntries() {
        BotSnapshot s = new BotSnapshot();
        s.stacks = null;
        s.effects = null;
        s.normalised();
        assertNotNull(s.stacks);
        assertNotNull(s.effects);

        BotSnapshot damaged = sample();
        damaged.stacks.add(null);
        damaged.stacks.add(new BotSnapshot.Entry(3, null));
        damaged.stacks.add(new BotSnapshot.Entry(-4, "{}"));
        damaged.stacks.add(new BotSnapshot.Entry(5, ""));
        damaged.effects.add("");
        damaged.effects.add(null);
        assertEquals(sample(), damaged.normalised());
    }

    @Test
    void stackAtFindsTheSlotAndEmptySlotsAreNull() {
        BotSnapshot s = sample();
        assertTrue(s.stackAt(9).contains("arrow"));
        assertNull(s.stackAt(10));
    }

    @Test
    void aSnapshotIsUsableWhenItHasAPulseOrAStack() {
        assertTrue(sample().usable());
        BotSnapshot naked = new BotSnapshot();
        naked.health = 3.0f;
        assertTrue(naked.usable());
        assertFalse(new BotSnapshot().usable(), "no stack and no health: nothing to come back to");
    }

    // ------------------------------------------------------------------ persistence

    @Test
    void aSnapshotSurvivesTheStoreFileExactly(@TempDir Path dir) throws IOException {
        StructureRecord record = populated("Zed_1", "Amy_2");
        record.bots.get(0).snapshot = sample();
        Path file = dir.resolve("populations.json");
        PopulationFile.write(file, StructureRecord.CURRENT_DATA_VERSION, Map.of(key(VILLAGE, 1, 1), record), Map.of());

        PopulationFile.ReadResult read = PopulationFile.read(file);
        assertEquals(PopulationFile.Outcome.OK, read.outcome(), read.detail());
        StructureRecord back = read.parsed().structures().get(key(VILLAGE, 1, 1));
        assertRecordEquals(record, back);
        assertEquals(sample(), back.bots.get(0).snapshot, "every stack, its damage and every vital came back bit for bit");
        assertNull(back.bots.get(1).snapshot, "a bot without one stays without one");
    }

    @Test
    void aRecordWithoutASnapshotFieldStillLoadsAndHasNone() {
        String old = "{\"dataVersion\":3,\"structures\":{\"minecraft:overworld|minecraft:village_plains|1,2\":"
                + "{\"status\":\"POPULATED\",\"bots\":[{\"index\":0,\"name\":\"Old_1\",\"state\":\"SPAWNED\"}]}}}";
        PopulationFile.ReadResult r = PopulationFile.parseText(old);
        assertEquals(PopulationFile.Outcome.OK, r.outcome(), r.detail());
        StructureKey key = r.parsed().structures().keySet().iterator().next();
        assertNull(r.parsed().structures().get(key).bots.get(0).snapshot);
    }

    @Test
    void aDamagedSnapshotIsRepairedOnLoadInsteadOfFailingTheWholeFile() {
        String damaged = "{\"dataVersion\":3,\"structures\":{\"minecraft:overworld|minecraft:village_plains|1,2\":"
                + "{\"status\":\"POPULATED\",\"bots\":[{\"index\":0,\"name\":\"Hurt_1\",\"state\":\"SPAWNED\","
                + "\"snapshot\":{\"health\":6.0,\"stacks\":[{\"slot\":0},{\"slot\":2,\"stack\":\"{}\"}]}}]}}}";
        PopulationFile.ReadResult r = PopulationFile.parseText(damaged);
        assertEquals(PopulationFile.Outcome.OK, r.outcome(), r.detail());
        BotSnapshot s = r.parsed().structures().values().iterator().next().bots.get(0).snapshot;
        assertNotNull(s);
        assertEquals(6.0f, s.health);
        assertEquals(1, s.stacks.size(), "the entry without a stack is dropped, the other kept");
        assertEquals(2, s.stacks.get(0).slot);
        assertTrue(s.effects.isEmpty());
    }
}
