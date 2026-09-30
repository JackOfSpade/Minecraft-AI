package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ender-pearl sanitize step: PvP BOT's cobweb escape loop switches the held slot every tick while a bot
 * with a pearl (and no water bucket) stands in a web, which cancels its crossbow charge and attacks. Every
 * inhabitant the engine tracks is swept, right after a restore and then periodically.
 */
class PopulationEnginePearlSweepTest {

    private static StructureRecord populate(Rig rig, StructureSnapshot s) {
        rig.engine.submit(s);
        rig.runUntil(() -> rig.hasStatus(s.key(), dev.spawnbotswrapper.inhabitants.store.StructureStatus.POPULATED), 400);
        return rig.record(s.key());
    }

    private static long calls(Rig rig, String name) {
        return rig.bots.pearlStripCalls.stream().filter(n -> n.equals(name)).count();
    }

    @Test
    void everyInhabitantIsSweptSoonAfterItAppearsAndThenPeriodically() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.village(0, 0));
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 2);
        for (BotRecord b : r.bots) {
            assertTrue(calls(rig, b.name) >= 1, b.name + " is swept on its first roster visit");
        }
        long before = rig.bots.pearlStripCalls.size();
        rig.run(BotRoster.PEARL_SWEEP_TICKS * 5);
        long after = rig.bots.pearlStripCalls.size();
        assertTrue(after - before >= 3L * 4, "periodic: about once per sweep period per bot, got " + (after - before));
        assertTrue(after - before <= 3L * 7, "cheap: not every roster pass, got " + (after - before));
    }

    @Test
    void aRestoredInhabitantIsSweptAfterTheRestartEvenDuringTheSettlePeriod() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.village(0, 0));
        rig.cfg.processing.restoreSettleTicks = 1200;
        rig.bots.pearlStripCalls.clear();
        rig.restart(true);
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 2);
        for (BotRecord b : r.bots) {
            assertTrue(calls(rig, b.name) >= 1, b.name + " is swept as soon as it is restored");
        }
    }

    @Test
    void anOfflineInhabitantIsNotSweptAndAReturnIsSweptAgain() {
        Rig rig = new Rig();
        rig.cfg.processing.goneConfirmTicks = 100_000; // merely offline for a while (a relog), not gone for good
        StructureRecord r = populate(rig, Rig.village(0, 0));
        String name = r.bots.get(0).name;
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 2);
        rig.bots.kill(name);
        rig.run(BotRoster.SCAN_PERIOD_TICKS * 2);
        rig.bots.pearlStripCalls.clear();
        rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
        assertEquals(0, calls(rig, name), "nothing to sweep while it is not online");
        rig.bots.bringOnline(name);
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 2);
        assertTrue(calls(rig, name) >= 1);
    }

    @Test
    void pearlsFoundDoNotDisturbTheEngineAndAFailingSweepNeverEscapes() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.village(0, 0));
        for (BotRecord b : r.bots) {
            rig.bots.pearlsToStrip.put(FakeBots.key(b.name), 5);
        }
        rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
        rig.bots.throwStrip = true;
        rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
        assertEquals(List.of(), rig.bots.forgets, "sweeping never changes who is considered alive");
        assertEquals(0, rig.bots.restores.size());
    }

    // ------------------------------------------------------------------ the one-time migration of bots from before issued items were marked

    private static long migrations(Rig rig, String name) {
        return rig.bots.migrateCalls.stream().filter(n -> n.equals(name)).count();
    }

    @Test
    void everyInhabitantGetsExactlyOneMigrationPassAndItsRecordSaysSo() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.village(0, 0));
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 2);
        for (BotRecord b : r.bots) {
            assertEquals(1, migrations(rig, b.name), b.name + " is migrated on its first sweep");
            assertTrue(b.itemsMigrated, b.name + " is recorded as migrated");
        }
        rig.run(BotRoster.PEARL_SWEEP_TICKS * 6);
        for (BotRecord b : r.bots) {
            assertEquals(1, migrations(rig, b.name), b.name + ": never migrated a second time");
            assertTrue(calls(rig, b.name) >= 5, b.name + " keeps being swept for what the wrapper issued");
        }
    }

    @Test
    void aMigratedRecordIsNotMigratedAgainAfterARestartButAnOldRecordIs() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.village(0, 0));
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 2);
        BotRecord old = r.bots.get(0);
        old.itemsMigrated = false; // a record written before the marker existed
        rig.bots.migrateCalls.clear();
        rig.cfg.processing.restoreSettleTicks = 1200;
        rig.restart(true);
        rig.run(BotRoster.SCAN_PERIOD_TICKS * 2 + 2);
        StructureRecord after = rig.record(Rig.village(0, 0).key());
        for (BotRecord b : after.bots) {
            assertEquals(b.name.equals(old.name) ? 1 : 0, migrations(rig, b.name), b.name);
            assertTrue(b.itemsMigrated, b.name);
        }
    }

    @Test
    void whatTheMigrationRemovesIsLoggedOncePerBotAndAPassThatCouldNotRunIsRetried() {
        try (EngineLogCapture log = new EngineLogCapture(org.apache.logging.log4j.Level.INFO)) {
            Rig rig = new Rig();
            StructureRecord r = populate(rig, Rig.village(0, 0));
            String late = r.bots.get(0).name;
            for (BotRecord b : r.bots) {
                b.itemsMigrated = false;
                rig.bots.migrationResults.put(FakeBots.key(b.name),
                        new BotGateway.ItemSweep(4, List.of("minecraft:mending (netherite_chestplate)")));
            }
            rig.bots.migrateDeferredFor.add(FakeBots.key(late));
            rig.bots.migrateCalls.clear();
            rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
            assertFalse(late.isEmpty());
            assertTrue(migrations(rig, late) >= 2, "a pass that could not run is retried at every sweep");
            assertFalse(r.bots.get(0).itemsMigrated, "and is not recorded as done");
            assertEquals(1, log.count(org.apache.logging.log4j.Level.INFO, "Removed 4 ender pearl(s) from inhabitant " + r.bots.get(1).name + " "));
            assertEquals(1, log.count(org.apache.logging.log4j.Level.INFO,
                    "Removed disabled enchantment(s) from inhabitant " + r.bots.get(1).name + ":"));
            rig.bots.migrateDeferredFor.clear();
            rig.run(BotRoster.PEARL_SWEEP_TICKS * 2);
            assertTrue(r.bots.get(0).itemsMigrated, "it is migrated as soon as it can be");
            long done = migrations(rig, late);
            rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
            assertEquals(done, migrations(rig, late), "and then never again");
        }
    }

    @Test
    void aFailingMigrationNeverEscapesAndIsRetried() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.village(0, 0));
        for (BotRecord b : r.bots) {
            b.itemsMigrated = false;
        }
        rig.bots.throwMigrate = true;
        rig.run(BotRoster.PEARL_SWEEP_TICKS * 3);
        for (BotRecord b : r.bots) {
            assertFalse(b.itemsMigrated, b.name + " is not recorded as migrated after a failure");
        }
        assertEquals(List.of(), rig.bots.forgets, "the failure never changes who is considered alive");
        rig.bots.throwMigrate = false;
        rig.run(BotRoster.PEARL_SWEEP_TICKS * 2);
        for (BotRecord b : r.bots) {
            assertTrue(b.itemsMigrated, b.name);
        }
    }
}
