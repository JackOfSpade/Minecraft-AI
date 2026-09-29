package dev.spawnbotswrapper.inhabitants.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The console commands run at every start: the managed PvP BOT setting first, then the operator's own. */
class StartupCommandsTest {

    @Test
    void theDefaultConfigManagesCritFallTicksAtThreeThroughPvpBotsOwnCommand() {
        assertEquals(List.of("pvpbot settings crit-fall-ticks 3"), StartupCommands.plan(new InhabitantsConfig()));
    }

    @Test
    void operatorCommandsFollowTheManagedOneAndBlanksAreDropped() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.startupCommands = new ArrayList<>(List.of("pvpbot settings auto-target true", "  ", "pvpbot settings view-distance 16"));
        c.startupCommands.add(null);
        assertEquals(List.of("pvpbot settings crit-fall-ticks 3", "pvpbot settings auto-target true",
                "pvpbot settings view-distance 16"), StartupCommands.plan(c));
    }

    @Test
    void anExplicitCritFallTicksCommandOfTheOperatorWins() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.startupCommands = new ArrayList<>(List.of("/PVPBOT settings crit-fall-ticks 5"));
        assertEquals(List.of("/PVPBOT settings crit-fall-ticks 5"), StartupCommands.plan(c));
    }

    @Test
    void zeroSwitchesTheManagedSettingOff() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.criticalFallTicks = 0;
        assertEquals(List.of(), StartupCommands.plan(c));
    }

    @Test
    void theValueIsWhateverTheConfigSays() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.criticalFallTicks = 4;
        assertEquals(List.of("pvpbot settings crit-fall-ticks 4"), StartupCommands.plan(c));
        assertEquals("pvpbot settings crit-fall-ticks 10", StartupCommands.critFallTicksCommand(10));
    }

    @Test
    void aSimilarButDifferentSettingDoesNotCountAsExplicit() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.startupCommands = new ArrayList<>(List.of("pvpbot settings criticals true", "say crit-fall-ticks"));
        assertEquals(List.of("pvpbot settings crit-fall-ticks 3", "pvpbot settings criticals true", "say crit-fall-ticks"),
                StartupCommands.plan(c));
    }
}
