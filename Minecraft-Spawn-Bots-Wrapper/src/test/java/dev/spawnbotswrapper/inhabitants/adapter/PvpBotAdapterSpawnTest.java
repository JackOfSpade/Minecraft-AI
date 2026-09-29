package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.SpawnState;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.SpawnTicket;
import org.junit.jupiter.api.Test;
import org.stepan1411.testdouble.Managers;
import org.stepan1411.testdouble.Recorder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What can be proven about spawning without a running server: name availability against PvP BOT's list,
 * refusals that must happen before anything upstream is called, ticket bookkeeping, and that nothing ever
 * throws. A real MinecraftServer or ServerLevel cannot be created in a unit test and is deliberately not
 * faked; see the class documentation of the adapter for what only an end-to-end run can prove (building the
 * command source, dispatching through Brigadier, the entity appearing, the re-list of an orphan).
 */
class PvpBotAdapterSpawnTest {

    // ---------------------------------------------------------------- name availability

    @Test
    void aNameNobodyKnowsIsAvailable() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(f.adapter.nameAvailable(null, "Inh_Foo"));
    }

    @Test
    void aNameInPvpBotsListIsNotAvailableWhateverTheCase() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.LISTED.add("Inh_Foo");
        assertFalse(f.adapter.nameAvailable(null, "Inh_Foo"));
        assertFalse(f.adapter.nameAvailable(null, "inh_foo"));
        assertFalse(f.adapter.nameAvailable(null, "INH_FOO"));
        assertTrue(f.adapter.nameAvailable(null, "Inh_Bar"));
    }

    @Test
    void anInvalidNameIsNeverAvailable() {
        AdapterFixture f = AdapterFixture.probed();
        assertFalse(f.adapter.nameAvailable(null, "bad name"));
        assertFalse(f.adapter.nameAvailable(null, "ab"));
        assertFalse(f.adapter.nameAvailable(null, "0123456789abcdefg"));
        assertFalse(f.adapter.nameAvailable(null, ""));
        assertFalse(f.adapter.nameAvailable(null, null));
    }

    @Test
    void nothingIsAvailableWhenTheIntegrationIsNot() {
        assertFalse(AdapterFixture.healthy().adapter.nameAvailable(null, "Inh_Foo"), "not probed yet");
        AdapterFixture f = AdapterFixture.with(TestLocators.missing(UpstreamNames.CLASS_BOT_MANAGER));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertFalse(f.adapter.nameAvailable(null, "Inh_Foo"));
    }

    @Test
    void anUnreadableListMeansNotAvailableRatherThanFree() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.THROW.add("getAllBots");
        assertFalse(assertDoesNotThrow(() -> f.adapter.nameAvailable(null, "Inh_Foo")));
        assertTrue(f.sink.warn.stream().anyMatch(w -> w.contains("bot list")), f.sink.warn.toString());
    }

    // ---------------------------------------------------------------- isManaged / list cache

    @Test
    void isManagedIsCaseInsensitive() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.LISTED.add("Inh_Foo");
        assertTrue(f.adapter.isManaged("Inh_Foo"));
        assertTrue(f.adapter.isManaged("inh_foo"));
        assertFalse(f.adapter.isManaged("Inh_Bar"));
        assertFalse(f.adapter.isManaged(null));
    }

    @Test
    void isManagedIsFalseWhenNotProbedOrUnreadable() {
        assertFalse(AdapterFixture.healthy().adapter.isManaged("Inh_Foo"));
        AdapterFixture f = AdapterFixture.probed();
        Recorder.LISTED.add("Inh_Foo");
        Recorder.THROW.add("getAllBots");
        assertFalse(assertDoesNotThrow(() -> f.adapter.isManaged("Inh_Foo")));
    }

    // ---------------------------------------------------------------- refused requests

    private static SpawnState pollOnce(AdapterFixture f, SpawnTicket t) {
        return f.adapter.pollSpawn(null, t);
    }

    @Test
    void aRequestWithoutAServerOrWorldFailsWithoutCallingUpstream() {
        AdapterFixture f = AdapterFixture.probed();
        SpawnTicket t = f.adapter.requestSpawn(null, null, "Inh_Foo", 1, 64, 2, 0f);
        assertEquals("Inh_Foo", t.name());
        SpawnState s = pollOnce(f, t);
        assertEquals("no server or world given", assertInstanceOf(SpawnState.Failed.class, s).reason());
        assertFalse(Recorder.CALLS.stream().anyMatch(c -> c.startsWith("spawn")), Recorder.CALLS.toString());
    }

    @Test
    void aRequestBeforeTheProbeFailsAndSaysWhy() {
        AdapterFixture f = AdapterFixture.healthy();
        SpawnTicket t = f.adapter.requestSpawn(null, null, "Inh_Foo", 1, 64, 2, 0f);
        String reason = assertInstanceOf(SpawnState.Failed.class, pollOnce(f, t)).reason();
        assertTrue(reason.contains("not available"), reason);
    }

    @Test
    void aRequestWhileUnavailableFails() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoGetAllBots.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        SpawnTicket t = f.adapter.requestSpawn(null, null, "Inh_Foo", 1, 64, 2, 0f);
        String reason = assertInstanceOf(SpawnState.Failed.class, pollOnce(f, t)).reason();
        assertTrue(reason.contains("not available") && reason.contains("R3"), reason);
    }

    @Test
    void ticketsAreUniqueAndCarryTheRequestedName() {
        AdapterFixture f = AdapterFixture.probed();
        SpawnTicket a = f.adapter.requestSpawn(null, null, "Inh_Foo", 0, 0, 0, 0f);
        SpawnTicket b = f.adapter.requestSpawn(null, null, "Inh_Bar", 0, 0, 0, 0f);
        assertNotEquals(a.id(), b.id());
        assertEquals("Inh_Foo", a.name());
        assertEquals("Inh_Bar", b.name());
    }

    @Test
    void aNullNameStillYieldsATicketThatFails() {
        AdapterFixture f = AdapterFixture.probed();
        SpawnTicket t = assertDoesNotThrow(() -> f.adapter.requestSpawn(null, null, null, 0, 0, 0, 0f));
        assertInstanceOf(SpawnState.Failed.class, pollOnce(f, t));
    }

    @Test
    void aRefusedRequestStaysRefusedHoweverOftenItIsPolled() {
        AdapterFixture f = AdapterFixture.probed();
        SpawnTicket t = f.adapter.requestSpawn(null, null, "Inh_Foo", 0, 0, 0, 0f);
        for (int i = 0; i < 5; i++) {
            assertEquals("no server or world given", assertInstanceOf(SpawnState.Failed.class, pollOnce(f, t)).reason());
        }
    }

    // ---------------------------------------------------------------- polling

    @Test
    void pollingWithoutATicketFails() {
        AdapterFixture f = AdapterFixture.probed();
        assertEquals("no spawn ticket given",
                assertInstanceOf(SpawnState.Failed.class, f.adapter.pollSpawn(null, null)).reason());
    }

    @Test
    void aTicketTheAdapterNeverIssuedIsPolledLikeAnyOtherAndIsPendingWhileTheBotIsAbsent() {
        // After a restart the caller rebuilds tickets for spawns that were still open; PvP BOT restores its
        // bots one by one over a long window, so "not there yet" must not become a failure.
        AdapterFixture f = AdapterFixture.probed();
        SpawnState s = f.adapter.pollSpawn(null, new SpawnTicket(9999, "Inh_Foo", 0));
        assertInstanceOf(SpawnState.Pending.class, s);
        assertInstanceOf(SpawnState.Pending.class, f.adapter.pollSpawn(null, new SpawnTicket(9999, "Inh_Foo", 0)));
    }

    @Test
    void aRebuiltTicketWithAnInvalidNameFails() {
        AdapterFixture f = AdapterFixture.probed();
        SpawnState s = f.adapter.pollSpawn(null, new SpawnTicket(3, "bad name", 0));
        assertTrue(assertInstanceOf(SpawnState.Failed.class, s).reason().contains("invalid bot name"));
        assertInstanceOf(SpawnState.Failed.class, f.adapter.pollSpawn(null, new SpawnTicket(4, null, 0)));
    }

    @Test
    void aRebuiltTicketNeverMixesWithALiveOneThatHappensToShareItsId() {
        AdapterFixture f = AdapterFixture.probed();
        SpawnTicket refused = f.adapter.requestSpawn(null, null, "Inh_Foo", 0, 0, 0, 0f);
        assertInstanceOf(SpawnState.Failed.class, pollOnce(f, refused));
        // Same numeric id, different bot: it is a different ticket and must not inherit Inh_Foo's failure.
        SpawnState other = f.adapter.pollSpawn(null, new SpawnTicket(refused.id(), "Inh_Bar", 0));
        assertInstanceOf(SpawnState.Pending.class, other);
        assertInstanceOf(SpawnState.Failed.class, pollOnce(f, refused), "the original stays refused");
    }

    // ---------------------------------------------------------------- lookups and removal never throw

    @Test
    void lookupsWithoutAServerAreEmptyNotExceptions() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(f.adapter.findBotEntity(null, "Inh_Foo").isEmpty());
        assertTrue(f.adapter.findBotEntity(null, null).isEmpty());
        assertFalse(f.adapter.isBotEntity(null));
    }

    @Test
    void removingWithoutAnOnlineBotEntityRefusesAndCallsNothingUpstream() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.LISTED.add("Inh_Foo");
        assertFalse(f.adapter.removeBot(null, "Inh_Foo"));
        assertFalse(f.adapter.removeBot(null, null));
        assertFalse(f.adapter.removeBot(null, "bad name"));
        assertTrue(Recorder.callsStartingWith("removeBot").isEmpty(), "upstream removal must not run: " + Recorder.CALLS);
        assertTrue(Recorder.FORBIDDEN.isEmpty());
        assertTrue(Recorder.LISTED.contains("Inh_Foo"));
    }

    @Test
    void removingWhenTheIntegrationIsUnavailableRefuses() {
        assertFalse(AdapterFixture.healthy().adapter.removeBot(null, "Inh_Foo"));
    }

    // ---------------------------------------------------------------- bot entity class-name check

    @Test
    void theBotEntityCheckRequiresBothTheSimpleNameAndAKnownHeroBotPackage() {
        assertTrue(UpstreamNames.isBotClassName("hero.bane.herobot.bot.BotPlayer"), "HeroBot 1.x");
        assertTrue(UpstreamNames.isBotClassName("hero.bane.herobot.mod.common.bot.BotPlayer"), "HeroBot 2.x");
        assertFalse(UpstreamNames.isBotClassName("net.minecraft.server.level.ServerPlayer"));
        assertFalse(UpstreamNames.isBotClassName("some.pkg.NotABotPlayer"));
        assertFalse(UpstreamNames.isBotClassName("some.pkg.BotPlayerHelper"));
        assertFalse(UpstreamNames.isBotClassName("some.pkg.MyBotPlayer"));
        assertFalse(UpstreamNames.isBotClassName("some.pkg.BotPlayer2"));
        assertFalse(UpstreamNames.isBotClassName(""));
        assertFalse(UpstreamNames.isBotClassName(null));
    }

    @Test
    void aClassNamedBotPlayerInAnUnrelatedModIsNeverMistakenForHeroBots() {
        // The simple name alone is not enough: some other installed mod's own fake-player/NPC class could
        // also be named BotPlayer. Since a false positive here would let removeBot() treat a real player as
        // an addon-owned bot, the package must match one HeroBot has actually shipped under, no matter how
        // it is qualified (a bare name, an unrelated top-level package, or a nested class).
        assertFalse(UpstreamNames.isBotClassName("BotPlayer"), "default package: not HeroBot's");
        assertFalse(UpstreamNames.isBotClassName("some.other.mod.BotPlayer"));
        assertFalse(UpstreamNames.isBotClassName("some.pkg.Holder$BotPlayer"), "nested class in an unrelated package");
        // a lookalike package (a prefix or suffix of the real one) must not pass either
        assertFalse(UpstreamNames.isBotClassName("hero.bane.herobot.BotPlayer"), "one package level short");
        assertFalse(UpstreamNames.isBotClassName("evil.hero.bane.herobot.bot.BotPlayer"), "the real package as a suffix");
    }

    // ---------------------------------------------------------------- commands the fallback would run

    @Test
    void theCommandFallbackOnlyEverBuildsCommandsFromValidatedNames() {
        assertEquals("pvpbot spawn Inh_Foo", UpstreamNames.spawnCommand("Inh_Foo"));
        assertEquals("pvpbot remove Inh_Foo", UpstreamNames.removeCommand("Inh_Foo"));
        // The adapter validates names before building a command; a name that could smuggle a second command
        // is rejected by NameRules and never reaches these builders.
        assertFalse(NameRules.isValid("x; op me"));
        assertFalse(NameRules.isValid("x\nop me"));
    }
}
