package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Availability;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl.EngineStats;
import dev.spawnbotswrapper.inhabitants.engine.PopulationView.PopulationCounts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfoFormatterTest {

    private static List<String> raw(InhabitantsConfig cfg) {
        return InfoFormatter.format("0.1.0", Fixtures.availableStatus(), cfg, Fixtures.stats(), Fixtures.counts());
    }

    private static List<String> text(InhabitantsConfig cfg) {
        return Markup.strip(raw(cfg));
    }

    private static boolean has(List<String> lines, String... fragments) {
        return lines.stream().anyMatch(l -> {
            for (String f : fragments) {
                if (!l.contains(f)) {
                    return false;
                }
            }
            return true;
        });
    }

    @Test
    void showsVersionsIntegrationConfigEngineAndPopulation() {
        List<String> t = text(new InhabitantsConfig());

        assertEquals("PvP BOT Inhabitants 0.1.0", t.get(0));
        assertTrue(has(t, "PvP BOT 0.0.15", "HeroBot 1.4.0", "spawn tier CLASS(pos)"), t.toString());
        assertTrue(has(t, "Integration: AVAILABLE - PvP BOT 0.0.15 ok"), t.toString());
        assertTrue(has(t, "Config: enabled", "deterministic off", "default 65% occupied, 1-4 bots",
                "commands level 2 (gamemasters)"), t.toString());
        assertTrue(has(t, "Engine (this session): 120 structures seen, 118 rolled"), t.toString());
        assertTrue(has(t, "40 requested, 38 spawned, 2 failed", "3 queued, 1 in flight, 37 live"), t.toString());
        assertTrue(has(t, "Population (saved): 118 structures - 70 abandoned, 3 pending, 42 populated, 3 gave up"),
                t.toString());
        assertTrue(has(t, "Inhabitants (saved): 38 spawned, 2 failed"), t.toString());
    }

    @Test
    void anUnavailableIntegrationIsRedAndExplainsTheConsequence() {
        List<String> raw = InfoFormatter.format("0.1.0", Fixtures.unavailableStatus(), new InhabitantsConfig(),
                Fixtures.stats(), Fixtures.counts());
        List<String> t = Markup.strip(raw);

        assertTrue(raw.stream().anyMatch(l -> l.contains("§cUNAVAILABLE")), raw.toString());
        assertTrue(has(t, "Integration: UNAVAILABLE - PvP BOT is not installed"), t.toString());
        assertTrue(has(t, "PvP BOT not installed", "HeroBot not installed", "spawn tier NONE"), t.toString());
        assertTrue(has(t, "No structure is rolled and no bot is spawned while PvP BOT is unusable."), t.toString());
    }

    @Test
    void aDegradedIntegrationIsYellowAndDoesNotClaimItIsUnusable() {
        List<String> raw = InfoFormatter.format("0.1.0",
                Fixtures.status(Availability.DEGRADED, List.of(), List.of("using the command fallback")),
                new InhabitantsConfig(), Fixtures.stats(), Fixtures.counts());
        assertTrue(raw.stream().anyMatch(l -> l.contains("§eDEGRADED")), raw.toString());
        assertFalse(has(Markup.strip(raw), "No structure is rolled"));
    }

    @Test
    void warningsAreCappedAndPointToTheAdapterCommand() {
        List<String> warnings = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            warnings.add("warning number " + i);
        }
        List<String> t = Markup.strip(InfoFormatter.format("0.1.0",
                Fixtures.status(Availability.AVAILABLE, List.of(), warnings), new InhabitantsConfig(),
                Fixtures.stats(), Fixtures.counts()));

        assertTrue(has(t, "Warnings (5):"), t.toString());
        assertTrue(has(t, "! warning number 1") && has(t, "! warning number 3"), t.toString());
        assertFalse(has(t, "warning number 4"), t.toString());
        assertTrue(has(t, "... and 2 more (see /inhabitants adapter)"), t.toString());
    }

    @Test
    void noWarningsMeansNoWarningsBlock() {
        assertFalse(has(text(new InhabitantsConfig()), "Warnings"));
    }

    @Test
    void aMissingAdapterStatusDegradesToOneLine() {
        List<String> t = Markup.strip(InfoFormatter.format("0.1.0", null, new InhabitantsConfig(),
                Fixtures.stats(), Fixtures.counts()));
        assertTrue(has(t, "Integration: unknown"), t.toString());
        assertTrue(has(t, "Engine (this session)"), t.toString());
    }

    @Test
    void aDisabledAddonIsSaidLoudly() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.enabled = false;
        List<String> raw = raw(c);
        assertTrue(has(Markup.strip(raw), "DISABLED (nothing is rolled)"));
        assertTrue(raw.stream().anyMatch(l -> l.contains("§cDISABLED")));
    }

    @Test
    void deterministicModeShowsItsSaltWithoutLettingItInjectMarkup() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.deterministic.enabled = true;
        c.deterministic.salt = "ab§cd";
        List<String> raw = raw(c);
        assertTrue(has(Markup.strip(raw), "deterministic on (salt \"abcd\")"), Markup.strip(raw).toString());
        assertFalse(raw.stream().anyMatch(l -> l.contains("§cd")));
    }

    @Test
    void deterministicWithoutSaltAndSingleBotRule() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.deterministic.enabled = true;
        c.defaults = new InhabitantsConfig.Rule(0.5, 1, 1);
        List<String> t = text(c);
        assertTrue(has(t, "deterministic on", "default 50% occupied, 1 bot"), t.toString());
        assertFalse(has(t, "salt"));
    }

    @Test
    void aMissingDefaultRuleDoesNotCrash() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.defaults = null;
        assertTrue(has(text(c), "default rule missing"));
    }

    @Test
    void hugeAndZeroCountsFormatCleanly() {
        List<String> t = Markup.strip(InfoFormatter.format(null, Fixtures.availableStatus(), new InhabitantsConfig(),
                new EngineStats(0, 0, 0, 0, 0, 0, 0, 0),
                new PopulationCounts(1_234_567, 0, 0, 0, 0, 0)));
        assertEquals("PvP BOT Inhabitants -", t.get(0));
        assertTrue(has(t, "0 requested, 0 spawned, 0 failed"), t.toString());
        assertTrue(has(t, "1,234,567 structures - 1,234,567 abandoned, 0 pending"), t.toString());
    }

    @Test
    void failedBotsAreHighlighted() {
        List<String> raw = raw(new InhabitantsConfig());
        assertTrue(raw.stream().anyMatch(l -> l.contains("§c2 failed")), raw.toString());
    }
}
