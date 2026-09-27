package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.SettingsHygiene.Finding;
import dev.spawnbotswrapper.inhabitants.adapter.SettingsHygiene.Severity;
import dev.spawnbotswrapper.inhabitants.adapter.SettingsHygiene.SettingsSnapshot;
import dev.spawnbotswrapper.inhabitants.adapter.TelemetryProbe.Telemetry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsHygieneTest {

    private static final SettingsSnapshot HEALTHY = new SettingsSnapshot(true, true, 20, true);

    private static List<Finding> findings(SettingsSnapshot s, Telemetry t) {
        return SettingsHygiene.findings(s, t);
    }

    @Test
    void healthySettingsAndOptedOutTelemetryNeedNothing() {
        assertTrue(findings(HEALTHY, Telemetry.DISABLED).isEmpty());
    }

    @Test
    void botsRelogsOffWarnsThatInhabitantsVanishOnRestart() {
        List<Finding> f = findings(new SettingsSnapshot(false, true, 20, true), Telemetry.DISABLED);
        assertEquals(1, f.size());
        assertEquals(Severity.WARN, f.get(0).severity());
        assertTrue(f.get(0).text().contains("botsRelogs") && f.get(0).text().contains("vanish on restart"));
    }

    @Test
    void botLeaveOnDeathOffWarnsThatRemovalBreaks() {
        List<Finding> f = findings(new SettingsSnapshot(true, false, 20, true), Telemetry.DISABLED);
        assertEquals(1, f.size());
        assertEquals(Severity.WARN, f.get(0).severity());
        assertTrue(f.get(0).text().contains("botLeaveOnDeath") && f.get(0).text().contains("removal"));
    }

    @Test
    void aCheckIntervalBelowTenWarnsThatCleanupNeverRuns() {
        for (int interval : new int[]{1, 5, 9, 0, -3}) {
            List<Finding> f = findings(new SettingsSnapshot(true, true, interval, true), Telemetry.DISABLED);
            assertEquals(1, f.size(), "interval " + interval);
            assertTrue(f.get(0).text().contains("checkInterval is " + interval));
            assertTrue(f.get(0).text().contains("never runs"));
        }
    }

    @Test
    void aCheckIntervalOfTenOrMoreIsFine() {
        for (int interval : new int[]{10, 11, 20, 100}) {
            assertTrue(findings(new SettingsSnapshot(true, true, interval, true), Telemetry.DISABLED).isEmpty(),
                    "interval " + interval);
        }
    }

    @Test
    void autoTargetOffIsANoteBecauseItIsUpstreamsDefault() {
        List<Finding> f = findings(new SettingsSnapshot(true, true, 20, false), Telemetry.DISABLED);
        assertEquals(1, f.size());
        assertEquals(Severity.NOTE, f.get(0).severity());
        assertTrue(f.get(0).text().contains("passive until they are attacked"));
        assertTrue(f.get(0).text().contains("never changes"), "the addon does not fix it, it says so");
    }

    @Test
    void enabledTelemetryIsANoteThatNamesTheUploadAndTheOptOut() {
        for (Telemetry t : new Telemetry[]{Telemetry.ENABLED, Telemetry.ENABLED_BY_DEFAULT}) {
            List<Finding> f = findings(HEALTHY, t);
            assertEquals(1, f.size(), t.name());
            assertEquals(Severity.NOTE, f.get(0).severity());
            String text = f.get(0).text();
            assertTrue(text.contains("NAME of every bot"), text);
            assertTrue(text.contains("send_anonymous_statistics"), text);
            assertTrue(text.contains("stats_config.json"), text);
            assertTrue(text.contains("never edits"), text);
        }
    }

    @Test
    void unknownOrDisabledTelemetryIsSilent() {
        assertTrue(findings(HEALTHY, Telemetry.UNKNOWN).isEmpty());
        assertTrue(findings(HEALTHY, Telemetry.DISABLED).isEmpty());
        assertTrue(findings(HEALTHY, null).isEmpty());
    }

    @Test
    void unreadableSettingsProduceNoFindingsAtAll() {
        assertTrue(findings(SettingsSnapshot.unreadable(), Telemetry.DISABLED).isEmpty(),
                "a wrong warning about a setting we could not read is worse than none");
        assertTrue(findings(null, Telemetry.DISABLED).isEmpty());
    }

    @Test
    void severalProblemsAreAllReportedInAStableOrder() {
        List<Finding> f = findings(new SettingsSnapshot(false, false, 3, false), Telemetry.ENABLED);
        assertEquals(5, f.size());
        assertTrue(f.get(0).text().contains("botsRelogs"));
        assertTrue(f.get(1).text().contains("botLeaveOnDeath"));
        assertTrue(f.get(2).text().contains("checkInterval"));
        assertTrue(f.get(3).text().contains("autoTarget"));
        assertTrue(f.get(4).text().contains("statistics"));
    }
}
