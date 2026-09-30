package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a crash between "the bot was taken out" and "the next periodic save" does to the books: nothing that reads as a
 * death (review fixes b and e). Removal is durable BEFORE the bot is emptied (one write-ahead journal append), the record is
 * marked {@code removing}, and after a restart such a record is finished, not concluded dead; a bot of such a record that
 * rejoins late is emptied and removed again (the snapshot wins, no second copy).
 */
class PopulationEngineRemovalCrashTest {
    private static final String WORLD = Rig.OVERWORLD;

    private static Rig rig() {
        Rig rig = new Rig();
        rig.cfg.processing.maxLiveBots = 3;
        rig.cfg.dormancy.distanceBlocks = 1000.0;
        rig.cfg.allocation.intervalTicks = 5;
        rig.cfg.allocation.graceTicks = 0;
        rig.cfg.allocation.dwellTicks = 0;
        rig.bots.relevanceRadius = 192.0;
        return rig;
    }

    private static void playerAt(Rig rig, double x, double y, double z) {
        rig.bots.players.clear();
        rig.bots.players.add(new BotGateway.PlayerPos(WORLD, x, y, z));
    }

    private static BotSnapshot snapshotOf(float health) {
        BotSnapshot snap = new BotSnapshot();
        snap.health = health;
        snap.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:stone\",\"count\":1}"));
        return snap;
    }

    /** Runs a structure of three bots, takes the player far away and returns the store as it was durable when the FIRST bot left. */
    private static InMemoryStorage crashAtTheFirstRemoval(Rig rig, StructureSnapshot s, List<String> removed) {
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        InMemoryStorage[] crashed = new InMemoryStorage[1];
        rig.bots.beforeRemove = n -> {
            removed.add(n);
            if (crashed[0] == null) {
                crashed[0] = rig.store.crashCopy(); // the process dies right here: only what is durable now survives
            }
        };
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertNotNull(crashed[0]);
        return crashed[0];
    }

    private static void restartOn(Rig rig, InMemoryStorage crashed) {
        rig.bots.players.clear(); // nobody near: nothing is refilled while the books are being checked
        rig.store = crashed;
        rig.engine = rig.newEngine(rig.store);
    }

    @Test
    void anUnseenBotDeletedJustBeforeACrashIsAVacantSlotNotADeath() {
        Rig rig = rig();
        rig.cfg.processing.goneConfirmTicks = 100;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        List<String> removed = new ArrayList<>();
        InMemoryStorage crashed = crashAtTheFirstRemoval(rig, s, removed);
        assertEquals(3, removed.size(), "all three unseen bots left");
        // what the disk holds: the bots still SPAWNED, but marked as being removed
        for (BotRecord b : crashed.find(s.key()).orElseThrow().bots) {
            assertEquals(BotState.SPAWNED, b.state);
            assertTrue(b.removing, "the removal is durable before the bot is emptied: " + b.name);
        }
        // the bots were removed after that (the crash came before any periodic save): they are offline after the restart
        for (String n : removed) {
            rig.bots.kill(n);
        }
        restartOn(rig, crashed);
        rig.run(400);
        assertEquals(0, rig.record(s.key()).deadCount(), "a removal that a crash interrupted is never a death");
        assertTrue(rig.record(s.key()).bots.isEmpty(), "the three slots are vacant again: " + rig.record(s.key()).bots);
        assertEquals(3, rig.record(s.key()).vacantSlots());
    }

    @Test
    void aRemovalWhoseOutcomeTheGatewayCannotTellIsNeverReadAsADeath() {
        // isOnline throws right after the bots were removed: the old code undid the mark ("still there") and journalled a live
        // state, so once the gateway answered again the roster found the bots gone and spent their slots as deaths.
        Rig rig = rig();
        rig.cfg.processing.goneConfirmTicks = 100;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        rig.bots.beforeRemove = n -> rig.bots.throwIsOnline = true;
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertEquals(3, rig.bots.removes.size(), "the three unseen bots were removed");
        rig.bots.throwIsOnline = false;
        rig.run(400);
        assertEquals(0, rig.record(s.key()).deadCount(), "a removal whose outcome was unknown is never a death");
        assertTrue(rig.record(s.key()).bots.isEmpty(), "three vacant slots: " + rig.record(s.key()).bots);
        assertEquals(3, rig.record(s.key()).vacantSlots());
    }

    @Test
    void withoutTheMarkTheSameCrashWouldHaveBeenReadAsADeath() {
        // the contrast: a bot that is gone WITHOUT the mark is concluded dead after the long confirmation (unchanged rule)
        Rig rig = rig();
        rig.cfg.processing.goneConfirmTicks = 100;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        String name = rig.record(s.key()).bots.get(0).name;
        rig.bots.kill(name);
        rig.run(300);
        assertEquals(1, rig.record(s.key()).deadCount());
    }

    @Test
    void anUnseenBotThatIsStillOnlineAfterTheCrashIsRemovedAndItsSlotIsVacant() {
        Rig rig = rig();
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        List<String> removed = new ArrayList<>();
        InMemoryStorage crashed = crashAtTheFirstRemoval(rig, s, removed);
        for (String n : removed) {
            rig.bots.bringOnline(n); // the crash came before the entities left: they are all still there after the restart
        }
        rig.bots.removes.clear();
        restartOn(rig, crashed);
        rig.run(60);
        for (String n : removed) {
            assertTrue(rig.bots.removes.contains(n), n + " is emptied and removed once more");
            assertFalse(rig.bots.online.contains(FakeBots.key(n)));
        }
        assertEquals(0, rig.record(s.key()).deadCount());
        assertTrue(rig.record(s.key()).bots.isEmpty());
    }

    @Test
    void aSeenBotThatRejoinsAfterTheRestartHasNoSecondCopyTheSnapshotWins() {
        Rig rig = rig();
        rig.cfg.processing.restoreSettleTicks = 50;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord bot = rig.record(s.key()).bots.get(0);
        String name = bot.name;
        BotSnapshot state = snapshotOf(5.0f);
        rig.bots.liveState.put(FakeBots.key(name), state);
        rig.bots.visibleToHuman.add(FakeBots.key(name));
        rig.run(30);
        assertTrue(bot.seen);

        List<String> removed = new ArrayList<>();
        InMemoryStorage[] crashed = new InMemoryStorage[1];
        rig.bots.beforeRemove = n -> {
            removed.add(n);
            if (n.equals(name) && crashed[0] == null) {
                crashed[0] = rig.store.crashCopy();
            }
        };
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertNotNull(crashed[0]);
        for (String n : removed) {
            rig.bots.kill(n);
        }
        // restart: the bot is not back yet when the settle period ends, so the interrupted removal is finished without it ...
        restartOn(rig, crashed[0]);
        rig.bots.removes.clear();
        rig.run(80);
        BotRecord after = rig.record(s.key()).bots.stream().filter(b -> b.name.equals(name)).findFirst().orElseThrow();
        assertEquals(BotState.DORMANT, after.state);
        assertFalse(after.removing, "finished: the record may wake");
        assertEquals(state, after.snapshot);

        // ... and then PvP BOT brings it back late, with whatever its player data held
        rig.bots.bringOnline(name);
        rig.run(40);
        assertTrue(rig.bots.removes.contains(name), "the late rejoiner is emptied and removed again");
        assertFalse(rig.bots.online.contains(FakeBots.key(name)));
        assertEquals(BotState.DORMANT, after.state, "still asleep, its snapshot is the only copy");
        assertEquals(state, after.snapshot);
        assertEquals(0, rig.record(s.key()).deadCount(), "and never a death");
    }

    @Test
    void anUnseenBotThatRejoinsAfterTheRestartIsRemovedAndItsRecordDropped() {
        Rig rig = rig();
        rig.cfg.processing.restoreSettleTicks = 50;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        List<String> removed = new ArrayList<>();
        InMemoryStorage crashed = crashAtTheFirstRemoval(rig, s, removed);
        for (String n : removed) {
            rig.bots.kill(n);
        }
        restartOn(rig, crashed);
        rig.bots.removes.clear();
        rig.run(80); // settle over: they are offline, the records wait as vacant slots
        assertEquals(0, rig.record(s.key()).deadCount());
        String late = removed.get(0);
        rig.bots.bringOnline(late);
        rig.run(40);
        assertTrue(rig.bots.removes.contains(late), "the late rejoiner is removed");
        assertFalse(rig.bots.online.contains(FakeBots.key(late)));
        assertTrue(rig.record(s.key()).bots.stream().noneMatch(b -> late.equals(b.name)), "and its record is gone");
        assertEquals(0, rig.record(s.key()).deadCount());
    }

    @Test
    void aRemovalThatCouldNotBeRecordedRemovesNothingAndNoBotIsLostOrDuplicated() {
        Rig rig = rig();
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        rig.store.failSaves = true; // neither the journal nor a full save can be written
        int journalsBefore = rig.store.journals;
        playerAt(rig, 900, 70, 0);
        rig.run(60);
        assertTrue(rig.store.journals > journalsBefore, "the journal was tried");
        assertTrue(rig.bots.removes.isEmpty(), "nothing is emptied before its record is durable");
        assertEquals(3, Rig.count(rig.record(s.key()), BotState.SPAWNED));
        for (BotRecord b : rig.record(s.key()).bots) {
            assertFalse(b.removing);
        }
    }
}
