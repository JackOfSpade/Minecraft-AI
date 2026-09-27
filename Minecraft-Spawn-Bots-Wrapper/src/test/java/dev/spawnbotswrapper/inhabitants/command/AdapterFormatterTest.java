package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Availability;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Status;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterFormatterTest {

    private static List<String> text(Status s, GlobalCapabilities caps) {
        return Markup.strip(AdapterFormatter.format(s, caps));
    }

    private static boolean has(List<String> lines, String fragment) {
        return lines.stream().anyMatch(l -> l.contains(fragment));
    }

    @Test
    void printsVersionsTierSummaryDetailsAndWarnings() {
        Status s = Fixtures.status(Availability.AVAILABLE, List.of("found BotManager", "found BotSettings (68 fields)"),
                List.of("botsRelogs=false"));
        List<String> t = text(s, GlobalCapabilities.upstreamDefaults());

        assertEquals("PvP BOT adapter", t.get(0));
        assertTrue(has(t, "Availability: AVAILABLE"), t.toString());
        assertTrue(has(t, "PvP BOT: 0.0.15   HeroBot: 1.4.0   Addon: 0.1.0"), t.toString());
        assertTrue(has(t, "Spawn tier: CLASS(pos)"), t.toString());
        assertTrue(has(t, "Summary: PvP BOT 0.0.15 ok"), t.toString());
        assertTrue(has(t, "Details:") && has(t, "  found BotManager") && has(t, "  found BotSettings (68 fields)"),
                t.toString());
        assertTrue(has(t, "Warnings (1):") && has(t, "  ! botsRelogs=false"), t.toString());
    }

    @Test
    void multilineDetailsAreSplitIntoChatLines() {
        Status s = Fixtures.status(Availability.AVAILABLE, List.of("probe A\nprobe B\n\nprobe C"), List.of());
        List<String> t = text(s, null);
        assertTrue(has(t, "  probe A") && has(t, "  probe B") && has(t, "  probe C"), t.toString());
        assertFalse(t.stream().anyMatch(l -> l.contains("\n")), "no line may contain a raw newline");
    }

    @Test
    void noWarningsAndNoDetailsAreStatedExplicitly() {
        List<String> t = text(Fixtures.status(Availability.AVAILABLE, List.of(), List.of()), null);
        assertTrue(has(t, "Details: none reported"), t.toString());
        assertTrue(has(t, "Warnings: none"), t.toString());
    }

    @Test
    void anUnavailableAdapterNeverShowsCapabilitiesEvenWhenGiven() {
        List<String> t = text(Fixtures.unavailableStatus(), GlobalCapabilities.upstreamDefaults());
        assertTrue(has(t, "Availability: UNAVAILABLE  (nothing is rolled or spawned)"), t.toString());
        assertTrue(has(t, "Global PvP BOT switches: not read (PvP BOT is unusable)"), t.toString());
        assertFalse(has(t, "switches OFF"), t.toString());
        assertTrue(has(t, "PvP BOT: not installed   HeroBot: not installed"), t.toString());
    }

    @Test
    void offSwitchesAreListedAndAutoTargetGetsTheExplanation() {
        List<String> t = text(Fixtures.availableStatus(), GlobalCapabilities.upstreamDefaults());
        assertTrue(has(t, "switches OFF (read-only, never changed by this addon): autoTarget, spear"), t.toString());
        assertTrue(has(t, "autoTarget is off: inhabitants stay passive until something attacks them"), t.toString());
    }

    @Test
    void allSwitchesOnMeansNoneOffAndNoHint() {
        List<String> t = text(Fixtures.availableStatus(), GlobalCapabilities.allEnabled());
        assertTrue(has(t, "switches OFF (read-only, never changed by this addon): none"), t.toString());
        assertFalse(has(t, "autoTarget is off"), t.toString());
    }

    @Test
    void everySwitchCanBeReportedOff() {
        GlobalCapabilities none = new GlobalCapabilities(false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, false, false, false, false,
                false);
        String line = text(Fixtures.availableStatus(), none).stream()
                .filter(l -> l.contains("switches OFF")).findFirst().orElseThrow();
        assertEquals(20, line.substring(line.indexOf("addon): ") + 8).split(", ").length, line);
    }

    @Test
    void capsAreOptionalForAnAvailableAdapter() {
        List<String> t = text(Fixtures.availableStatus(), null);
        assertFalse(has(t, "switches"), t.toString());
    }

    @Test
    void aRunawayAdapterCannotFloodTheChat() {
        List<String> details = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            details.add("detail " + i);
            warnings.add("warning " + i);
        }
        List<String> t = text(Fixtures.status(Availability.DEGRADED, details, warnings), null);

        assertTrue(has(t, "  detail 40") && !has(t, "  detail 41"), t.toString());
        assertTrue(has(t, "... and 60 more"), t.toString());
        assertTrue(has(t, "Warnings (100):") && has(t, "  ! warning 20") && !has(t, "  ! warning 21"), t.toString());
        assertTrue(has(t, "... and 80 more"), t.toString());
        assertTrue(t.size() < 80, "output must stay bounded, was " + t.size());
    }

    @Test
    void aMissingStatusIsReportedNotThrown() {
        List<String> t = text(null, null);
        assertEquals(2, t.size());
        assertTrue(has(t, "No adapter status is available"), t.toString());
    }

    @Test
    void untrustedUpstreamTextCannotInjectColour() {
        Status s = Fixtures.status(Availability.AVAILABLE, List.of("detail §4red?"), List.of("warn §ax"));
        List<String> raw = AdapterFormatter.format(s, null);
        assertFalse(raw.stream().anyMatch(l -> l.contains("§4red")), raw.toString());
        assertFalse(raw.stream().anyMatch(l -> l.contains("§ax")), raw.toString());
    }
}
