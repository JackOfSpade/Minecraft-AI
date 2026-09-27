package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.UpstreamContract.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stepan1411.testdouble.Managers;
import org.stepan1411.testdouble.Paths;
import org.stepan1411.testdouble.Recorder;
import org.stepan1411.testdouble.Settings;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reflection contract R1..R13: what is found, and the exact reason when a member is not usable. */
class UpstreamContractTest {

    @BeforeEach
    void fresh() {
        AdapterFixture.resetUpstream();
    }

    private static UpstreamContract probe(ClassLocator locator) {
        return new UpstreamContract(locator);
    }

    @Test
    void aCompleteUpstreamResolvesEveryMember() {
        UpstreamContract c = probe(TestLocators.canonical());
        for (Member m : c.managerMembers()) {
            assertTrue(m.ok(), m.failure());
            assertTrue(Modifier.isStatic(m.method().getModifiers()) && Modifier.isPublic(m.method().getModifiers()));
        }
        assertTrue(c.settingsGet.ok(), c.settingsGet.failure());
        assertEquals(28, c.getters.size(), "23 capability getters + 3 extra booleans + 2 ints");
        assertTrue(c.missingGetterNames().isEmpty(), () -> c.missingGetterNames().toString());
        assertTrue(c.patrolCapable());
        assertTrue(c.isFollowing.ok());
        assertTrue(c.removeState.ok());
        assertNull(c.modIdNote);
    }

    @Test
    void membersCarryTheirContractIds() {
        UpstreamContract c = probe(TestLocators.canonical());
        assertEquals("R1", c.spawn3.id());
        assertEquals("R2", c.spawn4.id());
        assertEquals("R3", c.getAllBots.id());
        assertEquals("R4", c.getBotCount.id());
        assertEquals("R5", c.removeBot.id());
        assertEquals("R6", c.getBot.id());
        assertEquals("R7", c.removeAllBots.id());
        assertEquals("R8", c.saveBots.id());
        assertEquals("R9", c.updateBotData.id());
        assertEquals("R10", c.settingsGet.id());
        assertEquals("R11", c.getters.get("isBotsRelogs").id());
        assertEquals("R12", c.getters.get("getCheckInterval").id());
    }

    @Test
    void probingNeverInvokesOrInitialisesAnything() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.CountsInit.class));
        assertTrue(c.spawn3.ok());
        assertTrue(Recorder.CALLS.isEmpty(), "probing must not call any upstream method: " + Recorder.CALLS);
        assertFalse(Recorder.CALLS.contains("clinit:Managers.CountsInit"),
                "class initialisers of PvP BOT run config I/O; the probe must load classes without initialising them");
        assertTrue(Recorder.FORBIDDEN.isEmpty());
    }

    @Test
    void aBrokenStaticInitialiserIsNeverTriggered() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.BrokenInit.class));
        assertTrue(c.getAllBots.ok(), "getMethod does not initialise the class");
        assertFalse(c.spawn3.ok());
    }

    // ---------------------------------------------------------------- each way to be incompatible

    @Test
    void theMissingPositionOverloadIsNamedPrecisely() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoPos.class));
        assertTrue(c.spawn3.ok());
        assertFalse(c.spawn4.ok());
        assertEquals("R2 BotManager.spawnBot(MinecraftServer, String, ServerCommandSource, Vec3d)", c.spawn4.label());
        assertEquals("not found", c.spawn4.problem());
        assertTrue(c.spawn4.failure().contains("spawnBot") && c.spawn4.failure().contains("Vec3d"));
    }

    @Test
    void aMissingRequiredMethodIsNamed() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoGetAllBots.class));
        assertFalse(c.getAllBots.ok());
        assertEquals("R3 BotManager.getAllBots()", c.getAllBots.label());
        assertEquals("not found", c.getAllBots.problem());
    }

    @Test
    void aWrongReturnTypeIsCalledOut() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.WrongReturn.class));
        assertFalse(c.getAllBots.ok());
        assertEquals("returns List, expected Set", c.getAllBots.problem());
    }

    @Test
    void aWrongPrimitiveReturnTypeIsCalledOut() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.WrongPrimitiveReturn.class));
        assertFalse(c.getBotCount.ok());
        assertEquals("returns long, expected int", c.getBotCount.problem());
    }

    @Test
    void aNonStaticMethodIsCalledOut() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NonStaticRemove.class));
        assertFalse(c.removeBot.ok());
        assertEquals("is not static", c.removeBot.problem());
    }

    @Test
    void aNonPublicMethodIsSaidToExistButNotBePublic() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.PrivateRemove.class));
        assertFalse(c.removeBot.ok());
        assertEquals("exists but is not public", c.removeBot.problem());
    }

    @Test
    void aNonPublicDeclaringClassIsCalledOut() {
        ClassLocator loc = TestLocators.builder()
                .replace(UpstreamNames.CLASS_BOT_MANAGER, "org.stepan1411.testdouble.Managers$HiddenClass").build();
        UpstreamContract c = probe(loc);
        assertFalse(c.getAllBots.ok());
        assertTrue(c.getAllBots.problem().contains("is not public"), c.getAllBots.problem());
    }

    @Test
    void driftedParameterTypesMeanTheMethodIsNotFound() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.WrongParam.class));
        assertFalse(c.spawn3.ok(), "the command source parameter is an Object there");
        assertFalse(c.spawn4.ok());
        assertTrue(c.removeBot.ok(), "other members are unaffected");
    }

    @Test
    void anAbsentClassMakesEveryMemberOfItUnusableWithTheClassNamed() {
        UpstreamContract c = probe(TestLocators.missing(UpstreamNames.CLASS_BOT_MANAGER));
        for (Member m : c.managerMembers()) {
            assertFalse(m.ok());
            assertTrue(m.problem().contains(UpstreamNames.CLASS_BOT_MANAGER), m.problem());
            assertTrue(m.problem().contains("not found"));
        }
        assertTrue(c.settingsGet.ok(), "the other classes are independent");
        assertTrue(c.patrolCapable());
    }

    @Test
    void aClassThatFailsToLoadWithALinkageErrorIsAProblemNotACrash() {
        ClassLocator loc = TestLocators.builder()
                .failWith(UpstreamNames.CLASS_BOT_MANAGER, new NoClassDefFoundError("net/minecraft/Gone")).build();
        UpstreamContract c = probe(loc);
        assertFalse(c.spawn3.ok());
        assertTrue(c.spawn3.problem().contains("NoClassDefFoundError"), c.spawn3.problem());
        assertTrue(c.spawn3.problem().contains("Gone"));
    }

    @Test
    void anArbitraryRuntimeFailureWhileLoadingIsAProblemNotACrash() {
        ClassLocator loc = TestLocators.builder()
                .failWith(UpstreamNames.CLASS_BOT_SETTINGS, new IllegalStateException("boom")).build();
        UpstreamContract c = probe(loc);
        assertFalse(c.settingsGet.ok());
        assertTrue(c.settingsGet.problem().contains("boom"));
        assertNull(c.settingsClass);
    }

    @Test
    void aLocatorThatReturnsNullIsAProblemNotANullPointer() {
        UpstreamContract c = probe(name -> null);
        assertFalse(c.spawn3.ok());
        assertTrue(c.spawn3.problem().contains("not found"));
    }

    // ---------------------------------------------------------------- settings

    @Test
    void missingGettersAreListedByName() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.FewGetters.class));
        assertTrue(c.settingsGet.ok());
        assertTrue(c.getters.get("isMaceEnabled").ok());
        assertFalse(c.getters.get("isRangedEnabled").ok());
        assertTrue(c.missingGetterNames().contains("isRangedEnabled"));
        assertFalse(c.missingGetterNames().contains("isMaceEnabled"));
        assertEquals(28 - 4, c.missingGetterNames().size());
    }

    @Test
    void getterWithTheWrongTypeOrStaticIsUnusable() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.WrongTypes.class));
        assertEquals("returns String, expected boolean", c.getters.get("isMaceEnabled").problem());
        assertEquals("returns long, expected int", c.getters.get("getCheckInterval").problem());
        assertTrue(c.getters.get("isBotsRelogs").problem().contains("static"));
    }

    @Test
    void aMissingStaticGetMakesTheSettingsUnreadable() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.NoGet.class));
        assertFalse(c.settingsGet.ok());
        assertNotNull(c.settingsClass, "the class itself was loaded, which is what setting discovery needs");
    }

    // ---------------------------------------------------------------- paths / navigation / main class

    @Test
    void anIncompletePathApiDisablesPatrolsAndNamesTheMissingMember() {
        UpstreamContract c = probe(TestLocators.replacing(UpstreamNames.CLASS_BOT_PATH, Paths.NoWalkType.class));
        assertFalse(c.patrolCapable());
        assertFalse(c.setWalkType.ok());
        assertEquals("BotPath.setWalkType(String, String)", c.setWalkType.label());
        assertTrue(c.createPath.ok());
    }

    @Test
    void theFollowerCheckAndNavigationCleanupAreOptional() {
        UpstreamContract c = probe(TestLocators.builder()
                .replace(UpstreamNames.CLASS_BOT_PATH, Paths.NoIsFollowing.class)
                .remove(UpstreamNames.CLASS_BOT_NAVIGATION).build());
        assertTrue(c.patrolCapable(), "patrols work without the two optional extras");
        assertFalse(c.isFollowing.ok());
        assertFalse(c.removeState.ok());
    }

    @Test
    void aMissingPathClassDisablesPatrols() {
        UpstreamContract c = probe(TestLocators.missing(UpstreamNames.CLASS_BOT_PATH));
        assertFalse(c.patrolCapable());
    }

    @Test
    void theModIdCrossCheckIsOnlyAnObservation() {
        assertNull(probe(TestLocators.canonical()).modIdNote);
        UpstreamContract absent = probe(TestLocators.missing(UpstreamNames.CLASS_MAIN));
        assertNotNull(absent.modIdNote);
        assertTrue(absent.spawn3.ok(), "an absent main class does not affect anything that is used");
    }
}
