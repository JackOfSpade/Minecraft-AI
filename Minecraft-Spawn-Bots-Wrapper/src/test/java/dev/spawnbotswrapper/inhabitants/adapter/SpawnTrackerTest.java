package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.SpawnState;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.SpawnTicket;
import dev.spawnbotswrapper.inhabitants.adapter.SpawnTracker.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The poll protocol against a scripted world: a late entity, an orphaned bot that must be re-listed exactly
 * once, a real player that took the name, a tier that cannot re-list, upstream failures. This is where the
 * risky decisions live, so this is where the tests are dense.
 */
class SpawnTrackerTest {

    /** A scripted world: the test moves time and edits the player list and PvP BOT's list. */
    private static class ScriptedWorld implements SpawnTracker.World {
        long tick;
        final Map<String, Player> players = new HashMap<>();
        final Set<String> listed = new HashSet<>();
        boolean canRelist = true;
        boolean relistAdds = false;
        Throwable relistFailure;
        final List<String> relists = new ArrayList<>();
        int playerLookups;

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public Player player(String name) {
            playerLookups++;
            return players.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean listed(String name) {
            return listed.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean canRelist() {
            return canRelist;
        }

        @Override
        public void relist(String name, Player player) throws Throwable {
            relists.add(name);
            if (relistFailure != null) {
                throw relistFailure;
            }
            if (relistAdds) {
                listed.add(name.toLowerCase(Locale.ROOT));
            }
        }

        void bot(String name, UUID id) {
            players.put(name.toLowerCase(Locale.ROOT), new Player(true, id, name));
        }

        void realPlayer(String name) {
            players.put(name.toLowerCase(Locale.ROOT), new Player(false, UUID.randomUUID(), name));
        }

        void list(String name) {
            listed.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

    private RecordingSink sink;
    private SpawnTracker tracker;
    private ScriptedWorld world;

    @BeforeEach
    void fresh() {
        sink = new RecordingSink();
        tracker = new SpawnTracker(new Diagnostics(sink));
        world = new ScriptedWorld();
    }

    private SpawnState poll(SpawnTicket t) {
        try {
            return tracker.poll(t, world);
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- the happy paths

    @Test
    void anOnlineListedBotIsReadyWithItsUuidAndTheTicketIsReleased() {
        SpawnTicket t = tracker.open("Inh_Foo", 10);
        world.bot("Inh_Foo", ID);
        world.list("Inh_Foo");
        SpawnState s = poll(t);
        assertEquals(ID, assertInstanceOf(SpawnState.Ready.class, s).uuid());
        assertEquals(0, tracker.size());
    }

    @Test
    void thePollingCanRepeatAfterReadyAndStaysReady() {
        SpawnTicket t = tracker.open("Inh_Foo", 10);
        world.bot("Inh_Foo", ID);
        world.list("Inh_Foo");
        poll(t);
        assertEquals(ID, assertInstanceOf(SpawnState.Ready.class, poll(t)).uuid());
    }

    @Test
    void namesAreMatchedCaseInsensitivelyOnBothSides() {
        SpawnTicket t = tracker.open("Inh_Foo", 10);
        world.bot("INH_FOO", ID);
        world.list("inh_foo");
        assertInstanceOf(SpawnState.Ready.class, poll(t));
    }

    @Test
    void noEntityYetIsPendingHoweverLongTheEngineKeepsAsking() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.list("Inh_Foo");
        for (world.tick = 0; world.tick < 2000; world.tick += 7) {
            assertInstanceOf(SpawnState.Pending.class, poll(t), "tick " + world.tick);
        }
        assertTrue(world.relists.isEmpty());
    }

    @Test
    void aLateEntityBecomesReadyTheMomentItAppearsListed() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.list("Inh_Foo");
        world.tick = 150;
        assertInstanceOf(SpawnState.Pending.class, poll(t));
        world.tick = 151;
        world.bot("Inh_Foo", ID);
        assertInstanceOf(SpawnState.Ready.class, poll(t));
        assertTrue(world.relists.isEmpty());
    }

    // ---------------------------------------------------------------- a real player

    @Test
    void aRealPlayerThatTookTheNameFailsTheSpawnAndIsNeverAdopted() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.realPlayer("Inh_Foo");
        String reason = assertInstanceOf(SpawnState.Failed.class, poll(t)).reason();
        assertTrue(reason.contains("real player") && reason.contains("Inh_Foo"), reason);
        assertTrue(world.relists.isEmpty(), "a real player must never be adopted into PvP BOT's list");
        assertEquals(1, tracker.size(), "a failed ticket is kept so that polling it again answers the same");
        assertInstanceOf(SpawnState.Failed.class, poll(t));
    }

    @Test
    void aFailedTicketIsNeverGivenASecondAttemptByPollingAgain() {
        world.bot("Inh_Foo", ID);
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.tick = 100;
        poll(t);
        for (long i = 0; i < 200; i++) {
            world.tick = 100 + SpawnPolicy.RELIST_GRACE_TICKS + i;
            poll(t);
        }
        assertEquals(1, world.relists.size(), "polling a failed ticket for 200 more ticks must not re-list again");
        assertInstanceOf(SpawnState.Failed.class, poll(t));
    }

    @Test
    void aRealPlayerIsNeverAdoptedNoMatterHowLongItStaysUnlisted() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.realPlayer("Inh_Foo");
        for (world.tick = 0; world.tick < 300; world.tick++) {
            poll(t);
        }
        assertTrue(world.relists.isEmpty());
    }

    // ---------------------------------------------------------------- the orphan window

    @Test
    void anUnlistedBotIsGivenTheGraceAndThenRelistedExactlyOnce() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.bot("Inh_Foo", ID);
        for (world.tick = 100; world.tick < 100 + SpawnPolicy.RELIST_GRACE_TICKS; world.tick++) {
            assertInstanceOf(SpawnState.Pending.class, poll(t));
            assertTrue(world.relists.isEmpty(), "still inside the grace at tick " + world.tick);
        }
        world.tick = 100 + SpawnPolicy.RELIST_GRACE_TICKS;
        assertInstanceOf(SpawnState.Pending.class, poll(t));
        assertEquals(List.of("Inh_Foo"), world.relists);

        // Keep polling while PvP BOT still does not list it: no second re-list, ever.
        for (long i = 1; i < SpawnPolicy.RELIST_VERIFY_TICKS; i++) {
            world.tick = 100 + SpawnPolicy.RELIST_GRACE_TICKS + i;
            assertInstanceOf(SpawnState.Pending.class, poll(t), "verifying, +" + i);
        }
        assertEquals(1, world.relists.size());
    }

    @Test
    void aBotThatIsListedAfterTheRelistIsReady() {
        world.relistAdds = true;
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.bot("Inh_Foo", ID);
        world.tick = 100;
        poll(t);
        world.tick = 100 + SpawnPolicy.RELIST_GRACE_TICKS;
        assertInstanceOf(SpawnState.Pending.class, poll(t));
        world.tick++;
        assertEquals(ID, assertInstanceOf(SpawnState.Ready.class, poll(t)).uuid());
        assertEquals(1, world.relists.size());
    }

    @Test
    void aBotThatStaysUnlistedAfterTheRelistFailsAfterTheVerifyWindow() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.bot("Inh_Foo", ID);
        world.tick = 100;
        poll(t);
        long relistAt = 100 + SpawnPolicy.RELIST_GRACE_TICKS;
        world.tick = relistAt;
        poll(t);
        world.tick = relistAt + SpawnPolicy.RELIST_VERIFY_TICKS;
        String reason = assertInstanceOf(SpawnState.Failed.class, poll(t)).reason();
        assertTrue(reason.contains("still does not list"), reason);
        assertEquals(1, world.relists.size(), "one re-list, not a retry loop");
    }

    @Test
    void whenNoAdoptPathExistsTheUnlistedBotFailsWithoutAnyRelist() {
        world.canRelist = false;
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.bot("Inh_Foo", ID);
        world.tick = 100;
        assertInstanceOf(SpawnState.Pending.class, poll(t));
        world.tick = 100 + SpawnPolicy.RELIST_GRACE_TICKS;
        assertInstanceOf(SpawnState.Failed.class, poll(t));
        assertTrue(world.relists.isEmpty());
    }

    @Test
    void aFailingRelistIsLoggedOnceCountsAsTheAttemptAndIsNeverRetried() {
        world.relistFailure = new IllegalStateException("upstream refused");
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.bot("Inh_Foo", ID);
        world.tick = 100;
        poll(t);
        for (long i = 0; i < 40; i++) {
            world.tick = 100 + SpawnPolicy.RELIST_GRACE_TICKS + i;
            poll(t);
        }
        assertEquals(1, world.relists.size());
        assertEquals(1, sink.warn.stream().filter(w -> w.contains("upstream refused")).count());
    }

    @Test
    void anEntityThatVanishesAndReturnsRestartsTheGrace() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        world.bot("Inh_Foo", ID);
        for (world.tick = 100; world.tick < 104; world.tick++) {
            poll(t);
        }
        world.players.clear();
        world.tick = 104;
        assertInstanceOf(SpawnState.Pending.class, poll(t));
        world.bot("Inh_Foo", ID);
        world.tick = 105;
        poll(t);
        world.tick = 105 + SpawnPolicy.RELIST_GRACE_TICKS - 1;
        assertInstanceOf(SpawnState.Pending.class, poll(t));
        assertTrue(world.relists.isEmpty(), "the grace restarted when the entity came back");
        world.tick = 105 + SpawnPolicy.RELIST_GRACE_TICKS;
        poll(t);
        assertEquals(1, world.relists.size());
    }

    @Test
    void onlyThePolledNameIsEverRelisted() {
        SpawnTicket a = tracker.open("Inh_Aaa", 0);
        SpawnTicket b = tracker.open("Inh_Bbb", 0);
        world.bot("Inh_Aaa", ID);
        world.bot("Inh_Bbb", UUID.randomUUID());
        world.list("Inh_Bbb");
        world.bot("Inh_Other", UUID.randomUUID());
        world.tick = 100;
        poll(a);
        poll(b);
        world.tick = 100 + SpawnPolicy.RELIST_GRACE_TICKS;
        poll(a);
        assertInstanceOf(SpawnState.Ready.class, poll(b));
        assertEquals(List.of("Inh_Aaa"), world.relists, "Inh_Other has no ticket, so it is nobody's to adopt");
    }

    // ---------------------------------------------------------------- refused requests and rebuilt tickets

    @Test
    void aRefusedRequestReportsItsReasonOnEveryPollWithoutLookingAtTheWorld() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        tracker.fail(t, "PvP BOT integration is not available: nope");
        world.bot("Inh_Foo", ID);
        world.list("Inh_Foo");
        for (int i = 0; i < 5; i++) {
            assertEquals("PvP BOT integration is not available: nope",
                    assertInstanceOf(SpawnState.Failed.class, poll(t)).reason());
        }
        assertEquals(0, world.playerLookups, "a refused request never queries the player list");
        assertEquals(1, tracker.size(), "and stays until it ages out, so it keeps failing consistently");
    }

    @Test
    void aTicketRebuiltAfterARestartIsPendingWhileTheBotIsNotBackYet() {
        SpawnTicket rebuilt = new SpawnTicket(42, "Inh_Foo", 500);
        world.tick = 500;
        assertInstanceOf(SpawnState.Pending.class, poll(rebuilt));
        assertEquals(1, tracker.size());
    }

    @Test
    void aRebuiltTicketWhoseBotIsBackAndListedIsReady() {
        world.bot("Inh_Foo", ID);
        world.list("Inh_Foo");
        assertEquals(ID, assertInstanceOf(SpawnState.Ready.class, poll(new SpawnTicket(42, "Inh_Foo", 0))).uuid());
    }

    @Test
    void aRebuiltTicketWhoseBotIsBackButOrphanedIsRelistedThisIsTheRestartRepair() {
        // PvP BOT's own restore has the same 2.5-second orphan window; nothing else would ever repair it.
        SpawnTicket rebuilt = new SpawnTicket(42, "Inh_Foo", 0);
        world.bot("Inh_Foo", ID);
        world.tick = 1000;
        poll(rebuilt);
        world.tick = 1000 + SpawnPolicy.RELIST_GRACE_TICKS;
        poll(rebuilt);
        assertEquals(List.of("Inh_Foo"), world.relists);
    }

    @Test
    void aRebuiltTicketOfARealPlayerIsStillNeverAdopted() {
        world.realPlayer("Inh_Foo");
        assertInstanceOf(SpawnState.Failed.class, poll(new SpawnTicket(42, "Inh_Foo", 0)));
        assertTrue(world.relists.isEmpty());
    }

    @Test
    void aRebuiltTicketWithAnInvalidNameFailsWithoutTouchingTheWorld() {
        String reason = assertInstanceOf(SpawnState.Failed.class, poll(new SpawnTicket(1, "bad name", 0))).reason();
        assertTrue(reason.contains("invalid bot name"));
        assertEquals(0, world.playerLookups);
    }

    @Test
    void ticketsSharingAnIdButNotTheNameNeverMix() {
        SpawnTicket live = tracker.open("Inh_Foo", 0);
        tracker.fail(live, "refused");
        SpawnTicket rebuiltFromAnOldRun = new SpawnTicket(live.id(), "Inh_Bar", 0);
        assertInstanceOf(SpawnState.Pending.class, poll(rebuiltFromAnOldRun));
        assertEquals("refused", assertInstanceOf(SpawnState.Failed.class, poll(live)).reason());
    }

    @Test
    void aNullTicketFails() {
        assertEquals("no spawn ticket given", assertInstanceOf(SpawnState.Failed.class, poll(null)).reason());
    }

    // ---------------------------------------------------------------- bookkeeping

    @Test
    void handlesCarryUniqueIdsTheNameAndTheTick() {
        SpawnTicket a = tracker.open("Inh_Foo", 77);
        SpawnTicket b = tracker.open("Inh_Foo", 78);
        assertTrue(a.id() != b.id());
        assertEquals("Inh_Foo", a.name());
        assertEquals(77, a.requestedAtTick());
        assertEquals("", tracker.open(null, 0).name());
        assertEquals(0, tracker.open("Inh_Foo", -1).requestedAtTick(), "an unknown tick is recorded as 0");
    }

    @Test
    void abandonedTicketsAgeOut() {
        tracker.open("Inh_Aaa", 0);
        tracker.open("Inh_Bbb", 100);
        assertEquals(2, tracker.size());
        tracker.open("Inh_Ccc", SpawnTracker.TICKET_TTL_TICKS + 1);
        assertEquals(2, tracker.size(), "the one from tick 0 aged out, the one from tick 100 did not yet");
        tracker.open("Inh_Ddd", SpawnTracker.TICKET_TTL_TICKS + 101);
        assertEquals(2, tracker.size());
    }

    @Test
    void anUnknownTickNeverAgesAnythingOut() {
        tracker.open("Inh_Aaa", 0);
        tracker.open("Inh_Bbb", -1);
        assertEquals(2, tracker.size());
    }

    @Test
    void clearForgetsEverything() {
        tracker.open("Inh_Aaa", 0);
        tracker.clear();
        assertEquals(0, tracker.size());
    }

    @Test
    void failingAnUnknownHandleIsHarmless() {
        tracker.fail(new SpawnTicket(999, "Inh_Foo", 0), "x");
        assertEquals(0, tracker.size());
    }

    @Test
    void exceptionsFromTheWorldPropagateToTheCallerWhoDecidesHowToSurviveThem() {
        SpawnTicket t = tracker.open("Inh_Foo", 0);
        SpawnTracker.World broken = new ScriptedWorld() {
            @Override
            public Player player(String name) {
                throw new IllegalStateException("player list exploded");
            }
        };
        assertThrows(IllegalStateException.class, () -> tracker.poll(t, broken));
    }
}
