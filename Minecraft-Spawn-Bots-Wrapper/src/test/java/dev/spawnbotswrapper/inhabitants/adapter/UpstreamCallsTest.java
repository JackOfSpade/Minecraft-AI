package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.UpstreamCalls.RemoveAttempt;
import dev.spawnbotswrapper.inhabitants.adapter.UpstreamCalls.SpawnAttempt;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stepan1411.pvp_bot.bot.BotSettings;
import org.stepan1411.testdouble.Managers;
import org.stepan1411.testdouble.Recorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier orchestration and the typed calls, run against the fake upstream with null stand-ins for the
 * Minecraft objects (reflection passes them through untouched). What needs a real server is only what
 * happens BEFORE these calls: building the command source.
 */
class UpstreamCallsTest {

    private static final Vec3d POS = new Vec3d(1.5, 64, -2.5);
    private static final List<SpawnTier> AUTO = List.of(SpawnTier.CLASS_POS, SpawnTier.CLASS, SpawnTier.COMMAND);

    private final List<String> commands = new ArrayList<>();

    @BeforeEach
    void fresh() {
        AdapterFixture.resetUpstream();
        commands.clear();
    }

    private UpstreamCalls calls() {
        return calls(TestLocators.canonical());
    }

    private UpstreamCalls calls(ClassLocator locator) {
        return new UpstreamCalls(new UpstreamContract(locator));
    }

    private UpstreamCalls.CommandRunner recordingCommand(int result) {
        return command -> {
            commands.add(command);
            return result;
        };
    }

    // ---------------------------------------------------------------- spawn tiers

    @Test
    void theFirstTierThatWorksIsTheOnlyOneCalled() {
        SpawnAttempt a = calls().spawn(AUTO, null, "Inh_Foo", null, POS, recordingCommand(1));
        assertEquals(SpawnTier.CLASS_POS, a.tierUsed());
        assertTrue(a.issued());
        assertEquals(List.of("spawn4:Inh_Foo"), Recorder.CALLS);
        assertTrue(commands.isEmpty());
        assertTrue(a.failures().isEmpty());
    }

    @Test
    void aThrowingPositionOverloadFallsBackToThePlainOne() {
        Recorder.THROW.add("spawn4");
        SpawnAttempt a = calls().spawn(AUTO, null, "Inh_Foo", null, POS, recordingCommand(1));
        assertEquals(SpawnTier.CLASS, a.tierUsed());
        assertEquals(List.of("spawn4:Inh_Foo", "spawn3:Inh_Foo"), Recorder.CALLS);
        assertEquals(1, a.failures().size());
        String failure = a.failures().get(0);
        assertTrue(failure.startsWith("CLASS(pos): IllegalStateException"), failure);
        assertFalse(failure.contains("InvocationTargetException"), "reflection wrappers are unwrapped: " + failure);
    }

    @Test
    void bothClassTiersFailingFallsBackToTheCommandWithThePreparedName() {
        Recorder.THROW.add("spawn4");
        Recorder.THROW.add("spawn3");
        SpawnAttempt a = calls().spawn(AUTO, null, "Inh_Foo", null, POS, recordingCommand(1));
        assertEquals(SpawnTier.COMMAND, a.tierUsed());
        assertEquals(List.of("pvpbot spawn Inh_Foo"), commands);
        assertEquals(2, a.failures().size());
    }

    @Test
    void whenEveryTierFailsNothingWasIssuedAndEveryReasonIsKept() {
        Recorder.THROW.add("spawn4");
        Recorder.THROW.add("spawn3");
        SpawnAttempt a = calls().spawn(AUTO, null, "Inh_Foo", null, POS, name -> {
            throw new IllegalStateException("dispatcher gone");
        });
        assertFalse(a.issued());
        assertNull(a.tierUsed());
        assertEquals(3, a.failures().size());
        assertTrue(a.failures().get(2).startsWith("COMMAND: IllegalStateException: dispatcher gone"));
    }

    @Test
    void aCheckedCommandFailureIsCaughtToo() {
        SpawnAttempt a = calls().spawn(List.of(SpawnTier.COMMAND), null, "Inh_Foo", null, POS, name -> {
            throw new Exception("syntax error at position 3");
        });
        assertFalse(a.issued());
        assertTrue(a.failures().get(0).contains("syntax error"));
    }

    @Test
    void theClassBackendNeverDispatchesACommandEvenWhenEverythingFails() {
        Recorder.THROW.add("spawn4");
        Recorder.THROW.add("spawn3");
        SpawnAttempt a = calls().spawn(SpawnPolicy.tierOrder(SpawnBackend.CLASS, true, true, true), null, "Inh_Foo",
                null, POS, recordingCommand(1));
        assertFalse(a.issued());
        assertTrue(commands.isEmpty());
    }

    @Test
    void theCommandBackendNeverCallsTheClassApi() {
        SpawnAttempt a = calls().spawn(SpawnPolicy.tierOrder(SpawnBackend.COMMAND, true, true, true), null, "Inh_Foo",
                null, POS, recordingCommand(1));
        assertEquals(SpawnTier.COMMAND, a.tierUsed());
        assertTrue(Recorder.CALLS.isEmpty(), Recorder.CALLS.toString());
        assertEquals(List.of("pvpbot spawn Inh_Foo"), commands);
    }

    @Test
    void anUpstreamFalseCountsAsIssuedAndDoesNotTriggerAFallbackSpawn() {
        // Upstream answers false when a live player of that name exists; a second spawn would change nothing.
        Recorder.FAIL.add("spawn4");
        SpawnAttempt a = calls().spawn(AUTO, null, "Inh_Foo", null, POS, recordingCommand(1));
        assertTrue(a.issued());
        assertEquals(SpawnTier.CLASS_POS, a.tierUsed());
        assertEquals(Boolean.FALSE, a.upstreamResult());
        assertEquals(List.of("spawn4:Inh_Foo"), Recorder.CALLS);
    }

    @Test
    void theCommandResultIsRecordedButNeverTrusted() {
        SpawnAttempt ok = calls().spawn(List.of(SpawnTier.COMMAND), null, "Inh_Foo", null, POS, recordingCommand(1));
        SpawnAttempt zero = calls().spawn(List.of(SpawnTier.COMMAND), null, "Inh_Foo", null, POS, recordingCommand(0));
        assertEquals(Boolean.TRUE, ok.upstreamResult());
        assertEquals(Boolean.FALSE, zero.upstreamResult());
        assertTrue(zero.issued(), "the outcome is decided by observing the world, not by this number");
    }

    @Test
    void anEmptyTierListIssuesNothing() {
        SpawnAttempt a = calls().spawn(List.of(), null, "Inh_Foo", null, POS, recordingCommand(1));
        assertFalse(a.issued());
        assertTrue(Recorder.CALLS.isEmpty());
    }

    @Test
    void aMissingCommandRunnerIsAFailureNotANullPointer() {
        SpawnAttempt a = calls().spawn(List.of(SpawnTier.COMMAND), null, "Inh_Foo", null, POS, null);
        assertFalse(a.issued());
        assertTrue(a.failures().get(0).contains("no command dispatcher"));
    }

    @Test
    void onlyThePositionOverloadStillSpawnsThroughThePositionTier() {
        UpstreamCalls c = calls(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.OnlyPos.class));
        SpawnAttempt a = c.spawn(AUTO, null, "Inh_Foo", null, POS, recordingCommand(1));
        assertEquals(SpawnTier.CLASS_POS, a.tierUsed());
        assertEquals(List.of("spawn4:Inh_Foo"), Recorder.CALLS);
    }

    // ---------------------------------------------------------------- adopt

    @Test
    void adoptingPrefersThePlainOverload() throws Throwable {
        calls().adopt(null, "Inh_Foo", null, POS);
        assertEquals(List.of("spawn3:Inh_Foo"), Recorder.CALLS);
    }

    @Test
    void adoptingFallsBackToThePositionOverloadWhenThePlainOneIsGone() throws Throwable {
        calls(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.OnlyPos.class))
                .adopt(null, "Inh_Foo", null, POS);
        assertEquals(List.of("spawn4:Inh_Foo"), Recorder.CALLS);
    }

    // ---------------------------------------------------------------- removal

    @Test
    void removalGoesThroughTheClassApiAndNeverTheCommandWhenItWorks() {
        Recorder.LISTED.add("Inh_Foo");
        RemoveAttempt r = calls().remove(true, null, "Inh_Foo", null, recordingCommand(1));
        assertEquals("class", r.route());
        assertEquals(Boolean.TRUE, r.upstreamResult());
        assertTrue(commands.isEmpty());
        assertEquals(List.of("removeBot:Inh_Foo"), Recorder.CALLS);
        assertFalse(Recorder.LISTED.contains("Inh_Foo"));
    }

    @Test
    void aThrowingClassRemovalFallsBackToTheCommandWhenItIsRegistered() {
        Recorder.THROW.add("removeBot");
        RemoveAttempt r = calls().remove(true, null, "Inh_Foo", null, recordingCommand(1));
        assertEquals("command", r.route());
        assertEquals(List.of("pvpbot remove Inh_Foo"), commands);
        assertEquals(1, r.failures().size());
    }

    @Test
    void aThrowingClassRemovalWithoutACommandIsNotIssued() {
        Recorder.THROW.add("removeBot");
        RemoveAttempt r = calls().remove(false, null, "Inh_Foo", null, recordingCommand(1));
        assertFalse(r.issued());
        assertTrue(commands.isEmpty());
    }

    @Test
    void withoutARemoveMethodTheCommandIsUsed() {
        UpstreamCalls c = calls(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoRemove.class));
        RemoveAttempt r = c.remove(true, null, "Inh_Foo", null, recordingCommand(1));
        assertEquals("command", r.route());
        assertEquals(List.of("pvpbot remove Inh_Foo"), commands);
    }

    @Test
    void withoutARemoveMethodAndWithoutACommandNothingIsIssued() {
        UpstreamCalls c = calls(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoRemove.class));
        assertFalse(c.remove(false, null, "Inh_Foo", null, recordingCommand(1)).issued());
    }

    @Test
    void removalNeverCallsRemoveAll() {
        Recorder.LISTED.add("Inh_Foo");
        calls().remove(true, null, "Inh_Foo", null, recordingCommand(1));
        assertTrue(Recorder.FORBIDDEN.isEmpty(), Recorder.FORBIDDEN.toString());
    }

    // ---------------------------------------------------------------- bot list

    @Test
    void theListedBotsMapKeysByLowerCaseAndKeepsTheExactSpelling() throws Throwable {
        Recorder.LISTED.add("Inh_Foo");
        Recorder.LISTED.add("inh_bar");
        Map<String, String> listed = calls().listedBots();
        assertEquals("Inh_Foo", listed.get("inh_foo"));
        assertEquals("inh_bar", listed.get("inh_bar"));
        assertEquals(2, listed.size());
    }

    // ---------------------------------------------------------------- settings reads

    @Test
    void settingsAreReadThroughGettersOnly() throws Throwable {
        UpstreamCalls c = calls();
        Object settings = c.settingsInstance();
        assertEquals(Boolean.TRUE, c.readBoolean(settings, "isBotsRelogs"));
        assertEquals(Integer.valueOf(20), c.readInt(settings, "getCheckInterval"));
        BotSettings.put("botsRelogs", false);
        assertEquals(Boolean.FALSE, c.readBoolean(c.settingsInstance(), "isBotsRelogs"));
        assertNull(c.readBoolean(settings, "isNoSuchThing"), "an unknown getter is null, not an exception");
        assertNull(c.readBoolean(null, "isBotsRelogs"));
        assertNull(c.readInt(settings, "isBotsRelogs"), "wrong kind of getter is null");
        assertTrue(Recorder.FORBIDDEN.isEmpty(), Recorder.FORBIDDEN.toString());
    }
}
