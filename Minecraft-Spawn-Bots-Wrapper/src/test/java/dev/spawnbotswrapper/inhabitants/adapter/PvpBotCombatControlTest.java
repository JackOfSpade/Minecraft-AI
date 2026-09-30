package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.combat.TargetControl;
import org.junit.jupiter.api.Test;
import org.stepan1411.pvp_bot.bot.BotCombat;
import org.stepan1411.pvp_bot.bot.BotSettings;
import org.stepan1411.testdouble.Recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The aggro range's window into PvP BOT: target calls, settings and the walk-back navigation calls. */
class PvpBotCombatControlTest {

    @Test
    void isUnavailableBeforePvpBotWasProbed() {
        AdapterFixture f = AdapterFixture.healthy();
        assertFalse(f.adapter.targetControl().available());
        assertFalse(f.adapter.targetControl().steeringAvailable());
    }

    @Test
    void readsTheSettingsTheDecisionsNeed() {
        AdapterFixture f = AdapterFixture.probed();
        BotSettings.put("targetOtherBots", true);
        BotSettings.put("maxTargetDistance", 48.0);
        TargetControl c = f.adapter.targetControl();
        assertTrue(c.available(), c.unavailableReason());
        TargetControl.Settings s = c.settings();
        assertTrue(s.combatEnabled());
        assertFalse(s.autoTarget());
        assertTrue(s.targetPlayers());
        assertTrue(s.targetOtherBots());
        assertEquals(48.0, s.maxTargetDistance());
        assertTrue(Recorder.FORBIDDEN.isEmpty(), "settings are only ever read: " + Recorder.FORBIDDEN);
    }

    @Test
    void forcesAndClearsTargetsThroughPvpBotsOwnCalls() {
        AdapterFixture f = AdapterFixture.probed();
        TargetControl c = f.adapter.targetControl();
        assertNull(c.forcedTarget("Bot1"));
        c.setTarget("Bot1", "Steve");
        assertEquals("Steve", c.forcedTarget("Bot1"));
        c.clearTarget("Bot1");
        assertNull(c.forcedTarget("Bot1"));
        assertTrue(Recorder.CALLS.contains("setTarget:Bot1->Steve"), Recorder.CALLS.toString());
        assertTrue(Recorder.CALLS.contains("clearTarget:Bot1"), Recorder.CALLS.toString());
    }

    @Test
    void noTargetMeansNull() {
        AdapterFixture f = AdapterFixture.probed();
        assertNull(f.adapter.targetControl().currentTarget("Bot1"));
        assertNull(BotCombat.getState("Bot1").target);
    }

    @Test
    void walkBackIsAvailableWhenBothNavigationCallsResolve() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(f.adapter.targetControl().steeringAvailable(), f.adapter.targetControl().steeringProblem());
        assertNull(f.adapter.targetControl().steeringProblem());
    }

    @Test
    void aFailingUpstreamCallSurfacesAsAnUpstreamFailureNotAnEmptyTarget() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.THROW.add("getTarget");
        assertThrows(TargetControl.UpstreamFailure.class, () -> f.adapter.targetControl().currentTarget("Bot1"));
    }

    @Test
    void steeringRefusesAnythingButAServerPlayer() {
        AdapterFixture f = AdapterFixture.probed();
        assertThrows(TargetControl.UpstreamFailure.class,
                () -> f.adapter.targetControl().steer("not a player", new dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos(0, 0, 0), 1.0));
    }

    @Test
    void lookAndHaltRefuseAnythingButAServerPlayer() {
        AdapterFixture f = AdapterFixture.probed();
        assertThrows(TargetControl.UpstreamFailure.class,
                () -> f.adapter.targetControl().look("not a player", new dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos(0, 0, 0)));
        assertThrows(TargetControl.UpstreamFailure.class, () -> f.adapter.targetControl().halt("not a player"));
    }

    @Test
    void theStatusDetailsMentionTheAggroHunterStates() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(AdapterFixture.anyContains(f.adapter.status().details(), "walking via BotNavigation"),
                f.adapter.status().details().toString());
    }
}
