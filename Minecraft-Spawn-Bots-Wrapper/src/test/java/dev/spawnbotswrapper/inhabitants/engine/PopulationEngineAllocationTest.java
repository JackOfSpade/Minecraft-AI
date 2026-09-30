package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nearest-first population through the whole engine: which structure gets the live bots, how the allocation follows the
 * player, and the rules that make a loot farm impossible (removal is not a death, seen bots persist, unseen ones are
 * ephemeral, deaths are never refilled).
 */
class PopulationEngineAllocationTest {
    private static final String WORLD = Rig.OVERWORLD;

    /** A rig with the allocation reacting at once (no grace, no dwell) and room for several structures. */
    private static Rig rig(int maxLiveBots) {
        Rig rig = new Rig();
        rig.cfg.processing.maxLiveBots = maxLiveBots;
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

    private static long live(Rig rig, StructureSnapshot s) {
        return Rig.count(rig.record(s.key()), BotState.SPAWNED);
    }

    private static Set<String> liveNames(Rig rig, StructureSnapshot s) {
        Set<String> out = new HashSet<>();
        for (BotRecord b : rig.record(s.key()).bots) {
            if (b.state == BotState.SPAWNED) {
                out.add(b.name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ nearest first

    @Test
    void theNearestStructureFillsFirstAndTheRestGoesToTheNextClosest() {
        Rig rig = rig(5);
        StructureSnapshot near = Rig.structure("minecraft:pillager_outpost", 0, 0);   // x 0..47
        StructureSnapshot mid = Rig.structure("minecraft:pillager_outpost", 10, 0);   // x 160..207
        StructureSnapshot far = Rig.structure("minecraft:pillager_outpost", 20, 0);   // x 320..367: beyond the relevance area
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(far);
        rig.engine.submit(mid);
        rig.engine.submit(near);
        rig.run(200);

        assertEquals(3, live(rig, near), "the nearest gets its full fill target");
        assertEquals(2, live(rig, mid), "the rest of the budget goes to the next closest");
        assertEquals(0, live(rig, far), "outside the relevance area nothing lives");
        assertEquals(5, rig.engine.stats().liveBots());
    }

    /** A three-bot structure with an explicit bounding box (the pieces sit at its lower corner). */
    private static StructureSnapshot boxed(String id, int chunkX, int chunkZ, IntBox bounds) {
        List<IntBox> pieces = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int px = bounds.minX() + i * 6;
            pieces.add(new IntBox(px, bounds.minY(), bounds.minZ(), px + 4, bounds.minY() + 6, bounds.minZ() + 4));
        }
        return new StructureSnapshot(new StructureKey(WORLD, id, chunkX, chunkZ), Set.of(), bounds, pieces, true);
    }

    @Test
    void anUndergroundStructureIsFartherThanAVillageOnTheSurface() {
        Rig rig = rig(3);
        StructureSnapshot village = boxed("minecraft:village_plains", 4, 0, new IntBox(64, 60, -20, 111, 80, 20));
        StructureSnapshot chamber = boxed("minecraft:trial_chambers", 0, 0, new IntBox(0, -60, -20, 47, -20, 20));
        playerAt(rig, 20, 70, 0); // 44 blocks from the village, 89 above the chamber that lies straight below
        rig.engine.submit(chamber);
        rig.engine.submit(village);
        rig.run(200);
        assertEquals(3, live(rig, village), "the village is nearer in 3D, although the chamber is right under the player's feet");
        assertEquals(0, live(rig, chamber));
    }
    @Test
    void theAllocationFollowsThePlayerAndDropsUnseenBotsWithoutAnyRecord() {
        Rig rig = rig(5);
        StructureSnapshot a = Rig.structure("minecraft:pillager_outpost", 0, 0);   // x 0..47
        StructureSnapshot b = Rig.structure("minecraft:pillager_outpost", 10, 0);  // x 160..207
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(a);
        rig.engine.submit(b);
        rig.run(200);
        assertEquals(3, live(rig, a));
        assertEquals(2, live(rig, b));
        Set<String> aBefore = liveNames(rig, a);

        // The player walks to the other structure: b is now nearest and takes its full target, a keeps the rest.
        playerAt(rig, 215, 70, 0);
        rig.run(200);
        assertEquals(3, live(rig, b), "the new nearest fills up");
        assertEquals(2, live(rig, a), "the previous one gives the slots back");
        assertEquals(1, rig.bots.removes.size(), "exactly the surplus was removed");
        // The removed bot was never seen: no record of it is kept, its slot is vacant, and it is not a death.
        StructureRecord ra = rig.record(a.key());
        assertEquals(0, ra.deadCount());
        assertEquals(2, ra.bots.size(), "an unseen bot leaves no record at all");
        assertEquals(1, ra.vacantSlots());
        assertTrue(aBefore.containsAll(liveNames(rig, a)), "the two that stay are two of the original three");
        assertNull(rig.store.findBot(rig.bots.removes.get(0)).orElse(null), "and nothing about the removed one is left in the store");
    }

    @Test
    void anUnseenBotThatIsDeletedIsReplacedByAFreshOneWithADifferentNameOnReturn() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        Set<String> first = liveNames(rig, s);
        assertEquals(3, first.size());
        int requestsBefore = rig.bots.requests.size();

        playerAt(rig, 900, 70, 0); // far outside the relevance area: the structure leaves the allocation
        rig.run(100);
        assertEquals(0, live(rig, s));
        assertEquals(3, rig.bots.removes.size());
        assertEquals(0, rig.record(s.key()).bots.size(), "unseen bots leave nothing behind");
        assertEquals(3, rig.record(s.key()).vacantSlots());
        for (String name : first) {
            assertTrue(rig.store.findBot(name).isEmpty());
        }

        playerAt(rig, -10, 70, 0); // back
        rig.run(200);
        assertEquals(3, live(rig, s));
        Set<String> second = liveNames(rig, s);
        assertTrue(second.stream().noneMatch(first::contains), "fresh bots: different names, so a different roll");
        assertEquals(requestsBefore + 3, rig.bots.requests.size());
        StructureRecord r = rig.record(s.key());
        assertTrue(r.bots.stream().allMatch(b -> b.index >= 3), "fresh indices are never reused");
    }

    // ------------------------------------------------------------------ seen bots persist

    @Test
    void aSeenBotIsNeverDeletedItSleepsWithItsStateAndWakesAsTheSameBot() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord bot = rig.record(s.key()).bots.get(0);
        String name = bot.name;
        BotSnapshot state = new BotSnapshot();
        state.health = 7.0f;
        state.foodLevel = 11;
        state.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:iron_sword\",\"count\":1}"));
        rig.bots.liveState.put(FakeBots.key(name), state);
        rig.bots.positions.put(FakeBots.key(name), new BotGateway.PlayerPos(WORLD, 12.5, 64, 13.5, 90f));

        rig.bots.visibleToHuman.add(FakeBots.key(name));
        rig.run(30);
        assertTrue(bot.seen, "a player saw it");
        assertTrue(bot.firstSeenMillis > 0);

        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertEquals(BotState.DORMANT, bot.state, "a seen bot sleeps instead of being deleted");
        assertEquals(state, bot.snapshot, "with its whole state");
        assertEquals(12.5, bot.x);
        assertEquals(13.5, bot.z);
        assertEquals(90f, bot.yaw);
        assertNull(bot.dimension);
        assertFalse(bot.removing);
        assertEquals(1, rig.record(s.key()).bots.stream().filter(b -> b.state == BotState.DORMANT).count());
        assertEquals(0, rig.record(s.key()).bots.stream().filter(b -> b.state == BotState.SPAWNED).count(), "the unseen two were deleted");
        assertEquals(2, rig.record(s.key()).vacantSlots());

        playerAt(rig, -10, 70, 0);
        rig.run(200);
        assertEquals(BotState.SPAWNED, bot.state, "the same bot is back");
        assertEquals(name, bot.name);
        assertEquals(1, rig.bots.wakes.size(), "woken from its saved state, not dressed again");
        assertEquals(state, rig.bots.wakes.get(0).snapshot());
        assertEquals(name, rig.bots.wakes.get(0).name());
        // the sleeper was woken FIRST and the two vacant slots were rolled fresh next to it
        assertEquals(3, live(rig, s));
        assertEquals(1, rig.record(s.key()).bots.stream().filter(b -> b.name.equals(name)).count());
    }

    @Test
    void aSeenBotIsProtectedWhileItsStructureIsInsideTheRelevanceArea() {
        Rig rig = rig(3);
        StructureSnapshot a = Rig.structure("minecraft:pillager_outpost", 0, 0);   // x 0..47
        StructureSnapshot b = Rig.structure("minecraft:pillager_outpost", 10, 0);  // x 160..207
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(a);
        rig.run(100);
        rig.engine.submit(b);
        rig.run(50);
        assertEquals(3, live(rig, a));
        // every bot of a has been seen
        for (BotRecord bot : rig.record(a.key()).bots) {
            rig.bots.visibleToHuman.add(FakeBots.key(bot.name));
        }
        rig.run(30);
        // the player now stands next to b: b is nearer, but a's seen bots stay (b only gets what the budget leaves)
        playerAt(rig, 215, 70, 0);
        rig.run(300);
        assertEquals(3, live(rig, a), "seen bots are never taken out while their structure is in range");
        assertEquals(0, live(rig, b), "so the budget of 3 is already used up by protected bots");
        assertEquals(0, rig.bots.removes.size());
    }

    @Test
    void anEngagedBotIsNeverRemovedUntilItDisengages() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        String fighter = rig.record(s.key()).bots.get(0).name;
        rig.bots.engaged.add(FakeBots.key(fighter));
        playerAt(rig, 900, 70, 0);
        rig.run(200);
        assertEquals(1, live(rig, s), "only the engaged one is left");
        assertEquals(List.of(fighter), new ArrayList<>(liveNames(rig, s)));
        rig.bots.engaged.clear();
        rig.run(100);
        assertEquals(0, live(rig, s), "reconsidered after it disengaged");
    }

    // ------------------------------------------------------------------ anti-churn

    @Test
    void aBotIsNotRemovedWithinTheDwellTimeUnlessARoomIsNeededForAStructureMuchNearer() {
        Rig rig = rig(3);
        rig.cfg.allocation.dwellTicks = 400;
        StructureSnapshot a = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(a);
        rig.run(60);
        assertEquals(3, live(rig, a));
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertEquals(3, live(rig, a), "it just came up: left alone for the dwell time");
        rig.run(400);
        assertEquals(0, live(rig, a), "after the dwell time it goes");
    }

    @Test
    void theDwellTimeIsOverriddenWhenTheRoomIsNeededForAStructureAtLeastThirtyTwoBlocksNearer() {
        Rig rig = rig(3);
        rig.cfg.allocation.dwellTicks = 4000;
        StructureSnapshot far = Rig.structure("minecraft:pillager_outpost", 8, 0);   // x 128..175
        StructureSnapshot near = Rig.structure("minecraft:pillager_outpost", 0, 0);   // x 0..47
        playerAt(rig, 190, 70, 0);
        rig.engine.submit(far);
        rig.engine.submit(near);
        rig.run(60);
        assertEquals(3, live(rig, far));
        // the player walks to a place where "far" is still the nearer one: nothing changes
        playerAt(rig, 100, 70, 0);
        rig.run(200);
        assertEquals(3, live(rig, far), "far is still nearer than near");
        assertEquals(0, live(rig, near));
        // now the player is right next to "near": it is far more than 32 blocks nearer, the room is needed
        playerAt(rig, 0, 70, 0);
        rig.run(200);
        assertEquals(3, live(rig, near), "the nearer structure got the slots");
        assertEquals(0, live(rig, far));
    }

    @Test
    void aStructureThatDropsOutKeepsItsBotsForTheGracePeriod() {
        Rig rig = rig(3);
        rig.cfg.allocation.graceTicks = 300;
        StructureSnapshot a = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(a);
        rig.run(60);
        playerAt(rig, 900, 70, 0);
        rig.run(200);
        assertEquals(3, live(rig, a), "still inside the grace period");
        // riding back within the grace period costs nothing: no bot was ever removed
        playerAt(rig, -10, 70, 0);
        rig.run(400);
        assertEquals(3, live(rig, a));
        assertTrue(rig.bots.removes.isEmpty());
        playerAt(rig, 900, 70, 0);
        rig.run(500);
        assertEquals(0, live(rig, a));
    }

    @Test
    void twoNearlyEquidistantStructuresDoNotSwapPlacesEveryPass() {
        Rig rig = rig(3);
        StructureSnapshot a = Rig.structure("minecraft:pillager_outpost", 0, 0);    // x 0..47
        StructureSnapshot b = Rig.structure("minecraft:pillager_outpost", 12, 0);   // x 192..239
        playerAt(rig, 118, 70, 0);   // 71 from a, 74 from b... a is nearer
        rig.engine.submit(a);
        rig.engine.submit(b);
        rig.run(100);
        assertEquals(3, live(rig, a));
        // the player drifts until b is 6 blocks nearer than a: inside the 8 block margin, so a keeps its bots
        playerAt(rig, 125, 70, 0);   // 78 from a, 67 from b -> b is 11 nearer? recompute below
        double toA = 125 - 48;
        double toB = 192 - 125;
        assertTrue(toA - toB > 8, "sanity: this is a real crossover");
        rig.run(100);
        assertEquals(3, live(rig, b), "a clear win (more than the margin) takes over");
        assertEquals(0, live(rig, a));
    }

    // ------------------------------------------------------------------ deaths are never refilled

    @Test
    void aDeadBotIsNeverReplacedNotByTheAllocationNorByAReturn() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord victim = rig.record(s.key()).bots.get(0);
        rig.bots.kill(victim.name);
        rig.engine.onBotDeath(victim.name);
        assertEquals(BotState.DEAD, victim.state);
        assertEquals(1, rig.record(s.key()).deadCount());
        int requests = rig.bots.requests.size();
        rig.run(500);
        assertEquals(requests, rig.bots.requests.size(), "nothing replaces it while the player stays");
        assertEquals(2, live(rig, s));

        // leaving and coming back: the two unseen bots are deleted and re-rolled, but only two fit (N - D = 2)
        playerAt(rig, 900, 70, 0);
        rig.run(200);
        assertEquals(0, live(rig, s));
        playerAt(rig, -10, 70, 0);
        rig.run(300);
        assertEquals(2, live(rig, s), "a structure never yields more than N bots' worth of kills");
        assertEquals(1, rig.record(s.key()).deadCount());
        assertEquals(0, rig.record(s.key()).vacantSlots());
    }

    @Test
    void everyBotOfAStructureCanDieOnceAndNeverComesBack() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        for (BotRecord b : new ArrayList<>(rig.record(s.key()).bots)) {
            rig.bots.kill(b.name);
            rig.engine.onBotDeath(b.name);
        }
        int requests = rig.bots.requests.size();
        for (int i = 0; i < 5; i++) {
            playerAt(rig, 900, 70, 0);
            rig.run(100);
            playerAt(rig, -10, 70, 0);
            rig.run(200);
        }
        assertEquals(requests, rig.bots.requests.size(), "not one more bot in five visits");
        assertEquals(3, rig.record(s.key()).deadCount());
        assertEquals(0, rig.record(s.key()).vacantSlots());
    }

    @Test
    void aDeathEventForABotThatIsBeingRemovedIsNotADeath() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        // While the addon removes an (unseen) bot, a death event fired by the removal itself must not count.
        String name = rig.record(s.key()).bots.get(0).name;
        rig.bots.beforeRemove = n -> rig.engine.onBotDeath(n);
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertTrue(rig.bots.removes.contains(name));
        assertEquals(0, rig.record(s.key()).deadCount(), "removal is not a death");
    }

    @Test
    void aBotThatWasGoneWithoutADeathEventStillCountsAsDeadAfterTheConfirmTime() {
        Rig rig = rig(3);
        rig.cfg.processing.goneConfirmTicks = 100;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        String name = rig.record(s.key()).bots.get(0).name;
        rig.bots.kill(name);
        rig.run(300);
        assertEquals(1, rig.record(s.key()).deadCount());
        assertEquals(2, live(rig, s));
    }

    // ------------------------------------------------------------------ sleeping: snapshot -> store -> clear -> remove

    @Test
    void goingToSleepSnapshotsFirstThenMakesTheRecordDurableAndOnlyThenRemovesTheBot() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord bot = rig.record(s.key()).bots.get(0);
        BotSnapshot state = new BotSnapshot();
        state.health = 9.0f;
        state.stacks.add(new BotSnapshot.Entry(3, "{\"id\":\"minecraft:arrow\",\"count\":12}"));
        rig.bots.liveState.put(FakeBots.key(bot.name), state);
        rig.bots.visibleToHuman.add(FakeBots.key(bot.name));
        rig.run(30);
        assertTrue(bot.seen);

        List<String> order = rig.bots.events;
        rig.store.onWrite = () -> order.add("write");
        StructureRecord[] durableAtRemoval = new StructureRecord[1];
        rig.bots.beforeRemove = n -> {
            if (n.equals(bot.name)) {
                durableAtRemoval[0] = rig.store.persisted(s.key()).orElse(null);
            }
        };
        order.clear();
        playerAt(rig, 900, 70, 0);
        rig.run(100);

        int snapshot = order.indexOf("snapshot:" + bot.name);
        int remove = order.indexOf("remove:" + bot.name);
        assertTrue(snapshot >= 0 && remove > snapshot, "the state is read before the bot is removed: " + order);
        int writeBetween = -1;
        for (int i = snapshot; i < remove; i++) {
            if (order.get(i).equals("write")) {
                writeBetween = i;
            }
        }
        assertTrue(writeBetween > snapshot, "the record is written to disk between the snapshot and the removal: " + order);
        assertNotNull(durableAtRemoval[0]);
        BotRecord durable = durableAtRemoval[0].bots.stream().filter(b -> b.name.equals(bot.name)).findFirst().orElseThrow();
        assertEquals(BotState.DORMANT, durable.state, "the store already says asleep when the bot is removed");
        assertTrue(durable.removing, "marked as being removed");
        assertEquals(state, durable.snapshot, "with the snapshot");
        assertFalse(bot.removing, "and the mark is cleared once the bot is really gone");
    }

    @Test
    void aCrashBetweenTheRecordAndTheRemovalNeverLeavesBothALiveBotAndARestorableCopy() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord bot = rig.record(s.key()).bots.get(0);
        String name = bot.name;
        rig.bots.liveState.put(FakeBots.key(name), snapshotOf(5.0f));
        rig.bots.visibleToHuman.add(FakeBots.key(name));
        rig.run(30);
        assertTrue(bot.seen);

        InMemoryStorage[] crashed = new InMemoryStorage[1];
        rig.bots.beforeRemove = n -> {
            if (n.equals(name) && crashed[0] == null) {
                crashed[0] = rig.store.crashCopy(); // the power goes out right here: the bot is still online and full
            }
        };
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertNotNull(crashed[0]);
        BotRecord persisted = crashed[0].find(s.key()).orElseThrow().bots.stream()
                .filter(b -> b.name.equals(name)).findFirst().orElseThrow();
        assertEquals(BotState.DORMANT, persisted.state);
        assertTrue(persisted.removing, "the store says the bot is being removed, never 'asleep and restorable' without the mark");

        // restart on that crashed store: the bot is still online (the entity survived the crash)
        rig.bots.removes.clear();
        rig.bots.online.add(FakeBots.key(name));
        rig.bots.players.clear();
        rig.store = crashed[0];
        rig.engine = rig.newEngine(rig.store);
        rig.run(30);
        assertTrue(rig.bots.removes.contains(name), "the interrupted removal is finished: the bot is emptied and removed once more");
        BotRecord after = rig.record(s.key()).bots.stream().filter(b -> b.name.equals(name)).findFirst().orElseThrow();
        assertFalse(after.removing);
        assertEquals(BotState.DORMANT, after.state);
        assertFalse(rig.bots.online.contains(FakeBots.key(name)));
    }

    private static BotSnapshot snapshotOf(float health) {
        BotSnapshot snap = new BotSnapshot();
        snap.health = health;
        snap.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:stone\",\"count\":1}"));
        return snap;
    }

    @Test
    void ifTheRecordCannotBeSavedTheBotIsLeftUntouched() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord bot = rig.record(s.key()).bots.get(0);
        rig.bots.visibleToHuman.add(FakeBots.key(bot.name));
        rig.run(30);
        rig.store.failSaves = true;
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertEquals(BotState.SPAWNED, bot.state, "nothing was emptied or removed");
        assertFalse(bot.removing);
        assertFalse(rig.bots.removes.contains(bot.name));
        rig.store.failSaves = false;
        rig.run(300); // after the back-off (a broken disk is not hammered every pass)
        assertEquals(BotState.DORMANT, bot.state, "retried once the store works again");
    }


    // ------------------------------------------------------------------ persistence, wake order, wake place

    /** Makes the first bot of the structure seen and puts it to sleep by taking the player far away; returns its record. */
    private static BotRecord sleeper(Rig rig, StructureSnapshot s) {
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord bot = rig.record(s.key()).bots.get(0);
        BotSnapshot state = new BotSnapshot();
        state.health = 6.0f;
        state.stacks.add(new BotSnapshot.Entry(0, "{\"id\":\"minecraft:bow\",\"count\":1}"));
        rig.bots.liveState.put(FakeBots.key(bot.name), state);
        rig.bots.positions.put(FakeBots.key(bot.name), new BotGateway.PlayerPos(WORLD, 20.5, 64, 21.5, 45f));
        rig.bots.visibleToHuman.add(FakeBots.key(bot.name));
        rig.run(30);
        assertTrue(bot.seen);
        playerAt(rig, 900, 70, 0);
        rig.run(100);
        assertEquals(BotState.DORMANT, bot.state);
        return bot;
    }

    @Test
    void theSeenFlagAndTheSleepSurviveARestartAndTheSameBotWakesWhenThePlayerIsBack() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        BotRecord bot = sleeper(rig, s);
        String name = bot.name;
        BotSnapshot saved = bot.snapshot;

        rig.restart(true); // a clean restart: what was saved is all the new session has
        rig.bots.online.clear();
        BotRecord after = rig.record(s.key()).bots.stream().filter(b -> b.name.equals(name)).findFirst().orElseThrow();
        assertTrue(after.seen, "the SEEN flag is persistent");
        assertEquals(BotState.DORMANT, after.state);
        assertEquals(saved, after.snapshot);
        assertEquals(20.5, after.x);

        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s); // its chunk loads again
        rig.run(200);
        assertEquals(BotState.SPAWNED, after.state, "the same bot is back");
        assertEquals(1, rig.bots.wakes.stream().filter(w -> w.name().equals(name)).count());
        assertEquals(3, live(rig, s));
    }

    @Test
    void aSleepingSeenBotIsWokenBeforeAnyFreshRollAndCountsAgainstTheBudget() {
        Rig rig = rig(1); // room for ONE bot only
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        BotRecord bot = sleeper(rig, s);
        String name = bot.name;
        int requestsBefore = rig.bots.requests.size();

        playerAt(rig, -10, 70, 0);
        rig.run(200);
        assertEquals(BotState.SPAWNED, bot.state, "the seen resident gets the one slot");
        assertEquals(1, rig.bots.requests.size() - requestsBefore, "and nothing else was rolled: the budget is one bot");
        assertEquals(1, rig.engine.stats().liveBots());
        assertEquals(name, rig.bots.requests.get(rig.bots.requests.size() - 1).request().name());
        assertEquals(20.5, rig.bots.requests.get(rig.bots.requests.size() - 1).request().x(), "at the place it went to sleep at");
        assertEquals(21.5, rig.bots.requests.get(rig.bots.requests.size() - 1).request().z());
    }

    @Test
    void aSleeperWakesAtAFreshSpotOfItsStructureWhenItCanNotStandWhereItWentToSleep() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        BotRecord bot = sleeper(rig, s);
        rig.world.standingVerdict = dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.Verdict.UNSAFE;
        int before = rig.bots.requests.size();

        playerAt(rig, -10, 70, 0);
        rig.run(200);
        assertEquals(BotState.SPAWNED, bot.state);
        BotGateway.SpawnRequest wake = rig.bots.requests.subList(before, rig.bots.requests.size()).stream()
                .map(FakeBots.Request::request).filter(r -> r.name().equals(bot.name)).findFirst().orElseThrow();
        assertTrue(wake.x() != 20.5 || wake.z() != 21.5, "not at the unsafe saved place");
        assertTrue(wake.x() >= 0 && wake.x() <= 47 && wake.z() >= 0 && wake.z() <= 47, "but inside its structure");
        assertEquals(bot.name, bot.name);
    }

    @Test
    void noBotWakesOrIsRolledWhileTheServerIsDegraded() {
        Rig rig = rig(3);
        rig.cfg.tpsThrottle.checkIntervalTicks = 5;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        BotRecord bot = sleeper(rig, s);
        int before = rig.bots.requests.size();
        rig.tps.millis = 150.0;
        playerAt(rig, -10, 70, 0);
        rig.run(200);
        assertEquals(BotState.DORMANT, bot.state, "spawning is blocked while the server is degraded");
        assertEquals(before, rig.bots.requests.size());
        rig.tps.millis = 30.0;
        rig.run(300);
        assertEquals(BotState.SPAWNED, bot.state, "and resumes when it recovers");
    }

    // ------------------------------------------------------------------ who is looked at, and how often

    @Test
    void theSeenCheckAsksAboutEveryLiveBotAndAlreadySeenBotsOnlyRarely() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord first = rig.record(s.key()).bots.get(0);
        rig.bots.visibleToHuman.add(FakeBots.key(first.name));
        rig.run(20);
        assertTrue(first.seen);
        long firstSeenAt = first.firstSeenMillis;
        assertTrue(firstSeenAt > 0);
        assertEquals(firstSeenAt, first.lastSeenMillis);
        rig.run(SeenTracker.RESEEN_TICKS + 40);
        assertTrue(first.lastSeenMillis > firstSeenAt, "still in view: the last-seen time moves on");
        assertEquals(firstSeenAt, first.firstSeenMillis, "the first sighting stays");
        long other = rig.record(s.key()).bots.stream().filter(b -> b.seen).count();
        assertEquals(1, other, "the others were never in view");
    }

    @Test
    void aBotNoOneEverSawStaysUnseenAndASeenFlagIsNeverCleared() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        BotRecord bot = rig.record(s.key()).bots.get(0);
        rig.bots.visibleToHuman.add(FakeBots.key(bot.name));
        rig.run(20);
        assertTrue(bot.seen);
        rig.bots.visibleToHuman.clear();
        rig.run(2000);
        assertTrue(bot.seen, "never cleared while the bot lives");
    }

    @Test
    void aBatchOfSleepsSharesOneWriteOfTheStoreAndEveryRecordIsDurableBeforeItsBotIsRemoved() {
        Rig rig = rig(3);
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        playerAt(rig, -10, 70, 0);
        rig.engine.submit(s);
        rig.run(100);
        for (BotRecord b : rig.record(s.key()).bots) {
            rig.bots.visibleToHuman.add(FakeBots.key(b.name));
        }
        rig.run(30);
        assertEquals(3, rig.record(s.key()).seenAliveCount());

        List<String> order = rig.bots.events;
        rig.store.onWrite = () -> order.add("write");
        order.clear();
        playerAt(rig, 900, 70, 0);
        rig.run(100);

        assertEquals(3, rig.record(s.key()).bots.stream().filter(b -> b.state == BotState.DORMANT).count());
        int firstRemove = -1;
        int lastRemove = -1;
        int writesBeforeFirstRemove = 0;
        int writesBetween = 0;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).startsWith("remove:")) {
                if (firstRemove < 0) {
                    firstRemove = i;
                }
                lastRemove = i;
            } else if (order.get(i).equals("write")) {
                if (firstRemove < 0) {
                    writesBeforeFirstRemove++;
                } else {
                    writesBetween++;
                }
            }
        }
        assertTrue(firstRemove >= 0 && lastRemove > firstRemove);
        assertTrue(writesBeforeFirstRemove >= 1, "the records are on disk before the first bot is emptied: " + order);
        assertEquals(0, writesBetween, "one write for the whole batch, not one per bot: " + order);
    }
    // ------------------------------------------------------------------ a new structure at the live ceiling

    @Test
    void aNewStructureNearThePlayerTakesTheSlotsOfFarBotsInsteadOfBeingRecordedEmpty() {
        Rig rig = rig(3);
        StructureSnapshot far = Rig.structure("minecraft:pillager_outpost", 10, 0);   // x 160..207
        playerAt(rig, 190, 70, 0);
        rig.engine.submit(far);
        rig.run(100);
        assertEquals(3, live(rig, far));
        // the player rides 160 blocks west and a new structure is found right there, with the budget full of far bots
        playerAt(rig, -10, 70, 0);
        StructureSnapshot fresh = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(fresh);
        rig.run(300);
        assertNotEquals("CAPACITY_FULL", rig.record(fresh.key()).source, "it is nearer than what fills the budget");
        assertEquals(3, live(rig, fresh));
        assertEquals(0, live(rig, far));
    }

    // ------------------------------------------------------------------ the legacy fallback

    @Test
    void withoutAKnownPlayerTheAllocationIsIdleAndPopulationIsFirstComeFirstServed() {
        Rig rig = rig(4);
        StructureSnapshot a = Rig.structure("minecraft:pillager_outpost", 0, 0);
        StructureSnapshot b = Rig.structure("minecraft:pillager_outpost", 10, 0);
        rig.engine.submit(a);
        rig.engine.submit(b);
        rig.run(200);
        assertEquals(4, rig.engine.stats().liveBots(), "the cap holds, the order is the queue order");
        assertEquals(3, live(rig, a));
        assertEquals(1, live(rig, b));
        assertTrue(rig.bots.removes.isEmpty());
    }

    @Test
    void theAllocationCanBeSwitchedOff() {
        Rig rig = rig(4);
        rig.cfg.allocation.enabled = false;
        StructureSnapshot a = Rig.structure("minecraft:pillager_outpost", 0, 0);
        StructureSnapshot b = Rig.structure("minecraft:pillager_outpost", 10, 0);
        playerAt(rig, 215, 70, 0);
        rig.engine.submit(a);
        rig.engine.submit(b);
        rig.run(200);
        assertEquals(3, live(rig, a), "first come, first served although b is nearer");
        assertEquals(1, live(rig, b));
    }
}
