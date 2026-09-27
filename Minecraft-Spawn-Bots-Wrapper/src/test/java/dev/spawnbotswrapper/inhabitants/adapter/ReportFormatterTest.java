package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Availability;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportFormatterTest {

    private static Status status(Availability a, List<String> details, List<String> warnings) {
        return new Status(a, "0.0.15", "2.7.2", "0.1.0", "CLASS(pos)", "the summary", details, warnings);
    }

    @Test
    void theReportContainsVersionsTierResultAndWarnings() {
        List<String> lines = ReportFormatter.render(status(Availability.DEGRADED,
                List.of("API compatibility: DEGRADED - something"), List.of("first warning", "second warning")));
        assertEquals("PvP BOT integration: DEGRADED - the summary", lines.get(0));
        assertTrue(lines.contains("  Addon:      PvP BOT Inhabitants 0.1.0"));
        assertTrue(lines.contains("  PvP BOT:    0.0.15 (tested: 0.0.15)"));
        assertTrue(lines.contains("  HeroBot:    2.7.2"));
        assertTrue(lines.contains("  Spawn tier: CLASS(pos)"));
        assertTrue(lines.contains("  API compatibility: DEGRADED - something"));
        assertTrue(lines.contains("  Warnings (2):"));
        assertTrue(lines.contains("    - first warning"));
        assertTrue(lines.contains("    - second warning"));
    }

    @Test
    void noWarningsSaysSoExplicitly() {
        List<String> lines = ReportFormatter.render(status(Availability.AVAILABLE, List.of(), List.of()));
        assertTrue(lines.contains("  Warnings:   none"));
    }

    @Test
    void theTextIsOneBlockOfLines() {
        String text = ReportFormatter.text(status(Availability.AVAILABLE, List.of("d1", "d2"), List.of("w")));
        assertEquals(ReportFormatter.render(status(Availability.AVAILABLE, List.of("d1", "d2"), List.of("w"))).size(),
                text.split("\n").length);
    }

    @Test
    void anUnavailableReportStillHasEveryField() {
        Status s = new Status(Availability.UNAVAILABLE, "not installed", "not installed", "0.1.0", "NONE",
                "PvP BOT (mod id pvp_bot) is not installed", List.of("API compatibility: NOT CHECKED"), List.of());
        String text = ReportFormatter.text(s);
        assertTrue(text.contains("UNAVAILABLE"));
        assertTrue(text.contains("PvP BOT:    not installed"));
        assertTrue(text.contains("HeroBot:    not installed"));
        assertTrue(text.contains("Spawn tier: NONE"));
    }
}
