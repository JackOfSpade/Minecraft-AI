package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an inhabitant carries and how it is doing survives despawning and restarting: the engine saves the live state
 * into the bot's record (first dressing, every {@link BotRoster#SNAPSHOT_TICKS} while it changes, dormancy, shutdown) and
 * gives it back instead of dressing the bot from its profile again. The bot side is scripted by {@link FakeBots}; what
 * the real game does with a snapshot is covered by the McBotGateway/BotSnapshots cases and the real-server GameTests.
 */
class PopulationEngineSnapshotTest {

    /** A state with a recognisable number of arrows and health: what a bot that fired {@code 64 - arrows} arrows looks like. */
    private static BotSnapshot state(int arrows, float health) {
        BotSnapshot s = new BotSnapshot();
        s.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:bow\",\"count\":1}"));
        s.stacks.add(new BotSnapshot.Entry(1, "{\"id\":\"minecraft:arrow\",\"count\":" + arrows + "}"));
        s.selectedSlot = 0;
        s.health = health;
        s.foodLevel = 14;
        s.saturation = 2.0f;
        return s;
    }

    private static StructureRecord populate(Rig rig, StructureSnapshot s) {
        rig.engine.submit(s);
        rig.runUntil(() -> rig.hasStatus(s.key(), StructureStatus.POPULATED), 400);
        return rig.record(s.key());
    }

    /** Far from every player, and SEEN by one before (only a seen bot is kept asleep; an unseen one is deleted). */
    private static void makeFar(Rig rig, List<BotRecord> bots) {
        for (BotRecord b : bots) {
            b.seen = true;
            rig.bots.distanceToPlayer.put(FakeBots.key(b.name), 500.0);
        }
    }

    // ------------------------------------------------------------------ the first dressing

    @Test
    void theFirstSnapshotIsTakenRightAfterTheDressing() {
        Rig rig = new Rig();
        rig.bots.snapshotSource = name -> state(64, 20.0f);
        StructureRecord r = populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
        for (BotRecord b : r.bots) {
            assertEquals(state(64, 20.0f), b.snapshot, b.name + " must have been snapshotted when it was dressed");
        }
    }

    @Test
    void aBotWhoseStateCannotBeReadIsStillDressedAndSimplyHasNoSnapshotYet() {
        Rig rig = new Rig();
        rig.bots.throwSnapshot = true;
        StructureRecord r = populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
        assertEquals(3, Rig.count(r, BotState.SPAWNED));
        for (BotRecord b : r.bots) {
            assertNull(b.snapshot);
        }
    }

    // ------------------------------------------------------------------ dormancy

    private static Rig dormancyRig(StructureSnapshot s) {
        Rig rig = new Rig();
        rig.cfg.dormancy.distanceBlocks = 100.0;
        rig.cfg.dormancy.delayTicks = 20;
        rig.cfg.dormancy.scanIntervalTicks = 5;
        return rig;
    }

    @Test
    void goingDormantSavesTheLiveStateAndWakingRestoresThatInsteadOfDressingTheProfileAgain() {
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        Rig rig = dormancyRig(s);
        rig.bots.snapshotSource = name -> state(64, 20.0f);
        StructureRecord r = populate(rig, s);
        BotRecord target = r.bots.get(0);
        BotSnapshot dressed = target.snapshot;
        assertNotNull(dressed);
        int dressings = (int) rig.bots.applied.stream().filter(a -> a.name().equalsIgnoreCase(target.name)).count();
        assertEquals(1, dressings);

        // The bot fires 27 arrows and is hurt: this is what its live state is now.
        rig.bots.snapshotSource = name -> state(37, 13.0f);
        makeFar(rig, r.bots);
        rig.runUntil(() -> target.state == BotState.DORMANT, 80);
        assertEquals(state(37, 13.0f), target.snapshot, "the state at the moment it went dormant is in its record");

        rig.engine.submit(s);
        rig.run(5);
        assertEquals(BotState.SPAWNED, target.state);
        List<FakeBots.Woke> wakes = rig.bots.wakes.stream().filter(w -> w.name().equalsIgnoreCase(target.name)).toList();
        assertEquals(1, wakes.size(), "it wakes from its snapshot");
        assertEquals(state(37, 13.0f), wakes.get(0).snapshot());
        assertSame(target.profile, wakes.get(0).profile(), "the profile still supplies its behaviour");
        assertEquals(dressings, rig.bots.applied.stream().filter(a -> a.name().equalsIgnoreCase(target.name)).count(),
                "and is NOT dressed from the profile a second time (that would refill the quiver and heal it)");
    }

    @Test
    void aRecordFromBeforeSnapshotsWakesFromItsProfileOnceAndIsSavedFromThenOn() {
        try (EngineLogCapture log = new EngineLogCapture(Level.INFO)) {
            StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
            Rig rig = dormancyRig(s);
            StructureRecord r = populate(rig, s); // the fake cannot read any state: like a record that predates snapshots
            BotRecord target = r.bots.get(0);
            assertNull(target.snapshot);
            makeFar(rig, r.bots);
            rig.runUntil(() -> target.state == BotState.DORMANT, 80);
            assertNull(target.snapshot);

            rig.bots.snapshotSource = name -> state(50, 18.0f);
            rig.engine.submit(s);
            rig.run(5);
            assertEquals(BotState.SPAWNED, target.state);
            assertTrue(rig.bots.wakes.isEmpty(), "nothing to wake from");
            assertEquals(2, rig.bots.applied.stream().filter(a -> a.name().equalsIgnoreCase(target.name)).count(),
                    "dressed from its profile, as before");
            assertEquals(state(50, 18.0f), target.snapshot, "and saved from that moment on");
            assertEquals(1, log.count(Level.INFO, "Inhabitant " + target.name + " has no saved state"));
        }
    }

    @Test
    void aBotThatIsNotRestoredYetIsNotSnapshottedWhenItGoesDormant() {
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        Rig rig = dormancyRig(s);
        rig.bots.snapshotSource = name -> state(40, 9.0f);
        StructureRecord r = populate(rig, s);
        BotSnapshot saved = r.bots.get(0).snapshot;
        rig.restart(true);
        StructureRecord after = rig.record(s.key());
        // After a start the bots are online but PvP BOT has not listed them: they are not restored, so what they carry is
        // a healed fake player, not their state, and must not overwrite the saved one.
        for (BotRecord b : after.bots) {
            rig.bots.unmanaged.add(FakeBots.key(b.name));
        }
        rig.bots.snapshotSource = name -> state(64, 20.0f);
        makeFar(rig, after.bots);
        rig.run(200);
        for (BotRecord b : after.bots) {
            assertEquals(state(40, 9.0f), b.snapshot, b.name + ": the unrestored live state must not replace the saved one");
        }
        assertEquals(state(40, 9.0f), saved);
    }

    // ------------------------------------------------------------------ the live cadence

    @Test
    void theSnapshotIsRefreshedOnlyWhenSomethingChangedAndNotMoreOftenThanTheCadence() {
        Rig rig = new Rig();
        rig.bots.snapshotSource = name -> state(64, 20.0f);
        StructureRecord r = populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
        rig.run(BotRoster.SNAPSHOT_TICKS * 2);
        int callsWhenIdle = rig.bots.snapshotCalls.size();
        BotSnapshot before = r.bots.get(0).snapshot;

        rig.bots.snapshotSource = name -> state(60, 17.0f);
        rig.run(BotRoster.SNAPSHOT_TICKS + 5);
        assertEquals(state(60, 17.0f), r.bots.get(0).snapshot, "a changed state is written");
        assertTrue(rig.bots.snapshotCalls.size() > callsWhenIdle);
        assertFalse(before.equals(r.bots.get(0).snapshot));

        int before2 = rig.bots.snapshotCalls.size();
        rig.run(BotRoster.SNAPSHOT_TICKS - 1);
        int perWindow = rig.bots.snapshotCalls.size() - before2;
        assertTrue(perWindow <= 3, "each bot is read at most once per " + BotRoster.SNAPSHOT_TICKS + " ticks: " + perWindow);
    }

    @Test
    void anUnchangedStateDoesNotDirtyTheStore() {
        Rig rig = new Rig();
        rig.bots.snapshotSource = name -> state(64, 20.0f);
        populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
        rig.run(BotRoster.SNAPSHOT_TICKS * 3);
        int writes = rig.store.writes;
        rig.run(BotRoster.SNAPSHOT_TICKS * 5);
        assertEquals(writes, rig.store.writes, "nothing changed, so nothing is written");
    }

    @Test
    void shuttingDownSavesTheLiveStateOfEveryOnlineBotEvenIfTheCadenceHasNotComeAround() {
        Rig rig = new Rig();
        rig.bots.snapshotSource = name -> state(64, 20.0f);
        StructureRecord r = populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
        rig.bots.snapshotSource = name -> state(12, 4.0f);
        rig.engine.shutdown();
        for (BotRecord b : r.bots) {
            assertEquals(state(12, 4.0f), b.snapshot, b.name);
        }
        assertEquals(state(12, 4.0f), rig.store.persisted(r.bots.isEmpty() ? null : keyOf(rig)).orElseThrow().bots.get(0).snapshot,
                "and it reached the disk");
    }

    private static dev.spawnbotswrapper.inhabitants.structure.StructureKey keyOf(Rig rig) {
        return rig.store.all().keySet().iterator().next();
    }

    // ------------------------------------------------------------------ restart

    @Test
    void afterARestartTheSavedStateIsHandedToTheGatewayWithTheRestore() {
        Rig rig = new Rig();
        rig.bots.snapshotSource = name -> state(33, 11.0f);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        populate(rig, s);
        rig.restart(true);
        rig.run(300);
        assertEquals(3, rig.bots.restores.size());
        for (BotSnapshot given : rig.bots.restoreSnapshots) {
            assertEquals(state(33, 11.0f), given, "every restore carries the bot's own saved state");
        }
    }

    @Test
    void aBotThatIsNotRestoredYetNeverHasItsSavedStateOverwrittenByTheHealedFakePlayer() {
        Rig rig = new Rig();
        rig.bots.snapshotSource = name -> state(33, 11.0f);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        populate(rig, s);
        rig.restart(true);
        StructureRecord r = rig.record(s.key());
        String late = r.bots.get(0).name;
        rig.bots.unmanaged.add(late.toLowerCase(Locale.ROOT));
        rig.bots.snapshotSource = name -> state(64, 20.0f); // what a freshly created fake player looks like
        rig.run(BotRoster.SNAPSHOT_TICKS * 3);
        assertEquals(state(33, 11.0f), r.bots.get(0).snapshot, "still the saved state: it has not been restored");
        rig.bots.unmanaged.clear();
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 5);
        assertEquals(state(33, 11.0f), rig.bots.restoreSnapshots.get(rig.bots.restoreSnapshots.size() - 1),
                "and the restore gets the saved state, not the healed one");
    }

    // ------------------------------------------------------------------ survival mode and vanilla stats

    @Test
    void everyOnlineBotIsCheckedForSurvivalAndVanillaAttributesOnTheCadence() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
        rig.bots.enforceCalls.clear();
        rig.run(BotRoster.VANILLA_SWEEP_TICKS * 3);
        for (BotRecord b : r.bots) {
            long calls = rig.bots.enforceCalls.stream().filter(n -> n.equals(b.name)).count();
            assertTrue(calls >= 2 && calls <= 4, b.name + ": " + calls);
        }
    }

    @Test
    void theFirstFixPerBotIsOneWarnAndLaterOnesAreNotWarnings() {
        try (EngineLogCapture log = new EngineLogCapture(Level.DEBUG)) {
            Rig rig = new Rig();
            StructureRecord r = populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
            for (BotRecord b : r.bots) {
                rig.bots.vanillaFixes.put(FakeBots.key(b.name), new BotGateway.StateFixes("creative",
                        List.of("instabuild", "invulnerable"), List.of("minecraft:max_health +12.00 (add_value)")));
            }
            rig.run(BotRoster.VANILLA_SWEEP_TICKS * 5);
            for (BotRecord b : r.bots) {
                assertEquals(1, log.count(Level.WARN, "Inhabitant " + b.name + " was not an ordinary survival player"), b.name);
                assertEquals(1, log.count(Level.INFO, "Removed attribute modifiers that older versions of this addon gave inhabitant "
                        + b.name), b.name);
            }
            assertTrue(log.matching("game mode creative, abilities [instabuild, invulnerable]").size() >= 3);
        }
    }

    @Test
    void aBotThatIsAlreadyAnOrdinarySurvivalPlayerCausesNoLogLine() {
        try (EngineLogCapture log = new EngineLogCapture(Level.DEBUG)) {
            Rig rig = new Rig();
            populate(rig, Rig.structure("minecraft:pillager_outpost", 0, 0));
            rig.run(BotRoster.VANILLA_SWEEP_TICKS * 3);
            assertTrue(log.matching("survival").isEmpty(), log.matching("survival").toString());
            assertTrue(log.matching("attribute modifiers").isEmpty());
        }
    }
}
