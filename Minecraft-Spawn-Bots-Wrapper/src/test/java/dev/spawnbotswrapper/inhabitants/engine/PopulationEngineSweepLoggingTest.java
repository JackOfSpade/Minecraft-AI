package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an operator sees from the inventory sweeps (ender pearls, disabled enchantments): the first removal per bot is
 * one INFO line, later ones are debug only, and one bot that throws never stops the sweep of the others.
 */
class PopulationEngineSweepLoggingTest {
    private static final String PEARL_LINE = "Removed 5 ender pearl(s) from inhabitant ";
    private static final String ENCHANT_LINE = "Removed disabled enchantment(s) from inhabitant ";

    private static StructureRecord populate(Rig rig, StructureSnapshot s) {
        rig.engine.submit(s);
        rig.runUntil(() -> rig.hasStatus(s.key(), StructureStatus.POPULATED), 400);
        return rig.record(s.key());
    }

    @Test
    void theFirstRemovalPerBotIsOneInfoLineAndLaterOnesAreNotInfo() {
        try (EngineLogCapture log = new EngineLogCapture(Level.DEBUG)) {
            Rig rig = new Rig();
            StructureRecord r = populate(rig, Rig.village(0, 0));
            for (BotRecord b : r.bots) {
                rig.bots.pearlsToStrip.put(FakeBots.key(b.name), 5);
                rig.bots.enchantmentsToStrip.put(FakeBots.key(b.name), List.of("minecraft:piercing (crossbow)"));
            }
            rig.run(BotRoster.PEARL_SWEEP_TICKS * 6);
            assertFalse(r.bots.isEmpty());
            for (BotRecord b : r.bots) {
                assertEquals(1, log.count(Level.INFO, PEARL_LINE + b.name + " "), b.name + " pearls: INFO once");
                assertEquals(1, log.count(Level.INFO, ENCHANT_LINE + b.name + ":"), b.name + " enchantments: INFO once");
                assertTrue(log.count(Level.DEBUG, "more ender pearl(s) from inhabitant " + b.name) >= 2,
                        "later pearl removals are logged at debug");
                assertTrue(log.count(Level.DEBUG, "Removed more disabled enchantment(s) from inhabitant " + b.name) >= 2,
                        "later enchantment removals are logged at debug");
            }
            assertTrue(log.matching(ENCHANT_LINE).get(0).message().contains("minecraft:piercing (crossbow)"));
        }
    }

    @Test
    void nothingRemovedMeansNothingLogged() {
        try (EngineLogCapture log = new EngineLogCapture(Level.DEBUG)) {
            Rig rig = new Rig();
            populate(rig, Rig.village(0, 0));
            rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
            assertTrue(log.matching("Removed ").isEmpty(), log.matching("Removed ").toString());
        }
    }

    @Test
    void oneBadBotDoesNotStopTheSweepOfTheOthers() {
        try (EngineLogCapture log = new EngineLogCapture(Level.INFO)) {
            Rig rig = new Rig();
            StructureRecord r = populate(rig, Rig.village(0, 0));
            assertTrue(r.bots.size() >= 2, "the village has several inhabitants");
            String bad = r.bots.get(0).name;
            rig.bots.throwStripFor.add(FakeBots.key(bad));
            for (BotRecord b : r.bots) {
                rig.bots.pearlsToStrip.put(FakeBots.key(b.name), 5);
                rig.bots.enchantmentsToStrip.put(FakeBots.key(b.name), List.of("minecraft:piercing (crossbow)"));
            }
            rig.bots.pearlStripCalls.clear();
            rig.bots.enchantmentStripCalls.clear();
            rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
            for (BotRecord b : r.bots) {
                assertTrue(rig.bots.pearlStripCalls.contains(b.name), b.name + " is still swept for pearls");
                assertTrue(rig.bots.enchantmentStripCalls.contains(b.name), b.name + " is still swept for enchantments");
                if (!b.name.equals(bad)) {
                    assertEquals(1, log.count(Level.INFO, PEARL_LINE + b.name + " "), b.name);
                    assertEquals(1, log.count(Level.INFO, ENCHANT_LINE + b.name + ":"), b.name);
                }
            }
            assertEquals(0, log.count(Level.INFO, PEARL_LINE + bad + " "), "the bad bot's failure is not a removal");
            assertEquals(List.of(), rig.bots.forgets, "a failing sweep never changes who is considered alive");
        }
    }

    @Test
    void aFailingEnchantmentSweepDoesNotSkipThePearlSweepOfTheSameBot() {
        try (EngineLogCapture log = new EngineLogCapture(Level.INFO)) {
            Rig rig = new Rig();
            StructureRecord r = populate(rig, Rig.village(0, 0));
            rig.bots.throwStripEnchantments = true;
            for (BotRecord b : r.bots) {
                rig.bots.pearlsToStrip.put(FakeBots.key(b.name), 5);
            }
            rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
            for (BotRecord b : r.bots) {
                assertEquals(1, log.count(Level.INFO, PEARL_LINE + b.name + " "), b.name);
            }
        }
    }
}
