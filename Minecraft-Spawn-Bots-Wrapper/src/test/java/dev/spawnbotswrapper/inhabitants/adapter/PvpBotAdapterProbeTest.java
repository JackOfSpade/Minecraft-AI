package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Availability;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.stepan1411.pvp_bot.bot.BotSettings;
import org.stepan1411.testdouble.Managers;
import org.stepan1411.testdouble.Recorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.spawnbotswrapper.inhabitants.adapter.AdapterFixture.anyContains;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** probe() end to end against fake upstream classes: classification, the single log report, fail-soft. */
class PvpBotAdapterProbeTest {

    // ---------------------------------------------------------------- before any probe

    @Test
    void beforeTheFirstProbeTheAdapterIsUnavailableAndSaysWhy() {
        AdapterFixture f = AdapterFixture.healthy();
        Status s = f.adapter.status();
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertFalse(f.adapter.available());
        assertTrue(s.summary().contains("has not been probed"));
        assertEquals("0.1.0-test", s.addonVersion());
        assertEquals("NONE", s.spawnTier());
        assertTrue(f.sink.loud().isEmpty(), "nothing is logged until the probe runs");
    }

    // ---------------------------------------------------------------- healthy

    @Test
    void aCompleteUpstreamIsAvailableWithTheFullTierAndOneInfoReport() {
        AdapterFixture f = AdapterFixture.healthy();
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(Availability.AVAILABLE, s.availability());
        assertEquals("CLASS(pos)", s.spawnTier());
        assertEquals("0.0.15", s.pvpBotVersion());
        assertEquals("1.21.11-1.4.3+v260315", s.heroBotVersion());
        assertTrue(f.adapter.available());
        assertSame(s, f.adapter.status(), "status() returns the latest probe result without re-probing");

        assertEquals(1, f.sink.loud().size(), "exactly one report: " + f.sink.loud());
        assertEquals(1, f.sink.info.size(), "a healthy integration reports at INFO");
        String report = f.sink.info.get(0);
        assertTrue(report.contains("\n"), "multi-line");
        assertTrue(report.contains("PvP BOT integration: AVAILABLE"));
        assertTrue(report.contains("Addon:      PvP BOT Inhabitants 0.1.0-test"));
        assertTrue(report.contains("PvP BOT:    0.0.15 (tested: 0.0.15)"));
        assertTrue(report.contains("HeroBot:    1.21.11-1.4.3+v260315"));
        assertTrue(report.contains("API compatibility: COMPATIBLE"));
        assertTrue(report.contains("Spawn tier: CLASS(pos)"));
        assertTrue(report.contains("Warnings"));
    }

    @Test
    void probeNeverCallsAnythingUpstreamBesidesReadingSettings() {
        AdapterFixture f = AdapterFixture.healthy();
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(java.util.List.of("BotSettings.get"), Recorder.CALLS,
                "the only upstream call a probe may make is the read-only settings accessor");
        assertTrue(Recorder.FORBIDDEN.isEmpty(), Recorder.FORBIDDEN.toString());
    }

    @Test
    void theProbeWorksWithAServerlessPublicEntryPointToo() {
        AdapterFixture f = AdapterFixture.healthy();
        Status s = assertDoesNotThrow(() -> f.adapter.probe(null));
        assertEquals(Availability.AVAILABLE, s.availability());
        assertTrue(anyContains(s.warnings(), "command tree could not be inspected"),
                "without a server the command tree cannot be read, which is reported");
    }

    // ---------------------------------------------------------------- classification through the adapter

    @Test
    void aMissingPositionOverloadIsDegradedAndReportedAtWarnLevel() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoPos.class));
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(Availability.DEGRADED, s.availability());
        assertEquals("CLASS", s.spawnTier());
        assertTrue(f.adapter.available());
        assertEquals(1, f.sink.warn.size());
        assertTrue(f.sink.warn.get(0).contains("R2 BotManager.spawnBot"), f.sink.warn.get(0));
    }

    @Test
    void aMissingRequiredMemberIsUnavailableAndReportedAtErrorLevelWithTheMemberNamed() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoGetAllBots.class));
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertFalse(f.adapter.available());
        assertEquals("NONE", s.spawnTier());
        assertEquals(1, f.sink.error.size());
        assertTrue(f.sink.error.get(0).contains("R3 BotManager.getAllBots()"), f.sink.error.get(0));
    }

    @Test
    void anAbsentUpstreamClassIsUnavailable() {
        AdapterFixture f = AdapterFixture.with(TestLocators.missing(UpstreamNames.CLASS_BOT_MANAGER));
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertTrue(s.summary().contains(UpstreamNames.CLASS_BOT_MANAGER), s.summary());
    }

    @Test
    void pvpBotNotInstalledIsUnavailableAndNothingIsReflected() {
        AdapterFixture f = AdapterFixture.with(TestEnvironment.standard().without(UpstreamNames.MOD_PVP_BOT),
                TestLocators.canonical(), SpawnBackend.AUTO);
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertEquals("not installed", s.pvpBotVersion());
        assertEquals("1.21.11-1.4.3+v260315", s.heroBotVersion());
        assertTrue(s.summary().contains("not installed"));
        assertTrue(Recorder.CALLS.isEmpty(), "no upstream class was touched");
        assertEquals(1, f.sink.error.size());
    }

    @Test
    void heroBotNotInstalledIsReportedAsSuch() {
        AdapterFixture f = AdapterFixture.with(TestEnvironment.standard().without(UpstreamNames.MOD_HEROBOT),
                TestLocators.canonical(), SpawnBackend.AUTO);
        assertEquals("not installed", f.adapter.probeWith(AdapterFixture.FULL_TREE).heroBotVersion());
    }

    @Test
    void aNewerPvpBotWhoseProbesAllPassIsAvailableWithTheUntestedWarning() {
        AdapterFixture f = AdapterFixture.with(TestEnvironment.standard().with(UpstreamNames.MOD_PVP_BOT, "0.0.16"),
                TestLocators.canonical(), SpawnBackend.AUTO);
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals(Availability.AVAILABLE, s.availability());
        assertTrue(anyContains(s.warnings(), "untested version 0.0.16, tested: 0.0.15"), s.warnings().toString());
        assertEquals(1, f.sink.warn.size(), "an untested version is worth a WARN report");
        assertTrue(f.sink.warn.get(0).contains("PvP BOT:    0.0.16 (tested: 0.0.15)"));
    }

    @Test
    void carpetBesidePvpBotIsWarnedAbout() {
        AdapterFixture f = AdapterFixture.with(TestEnvironment.standard().with(UpstreamNames.MOD_CARPET, "1.4.0"),
                TestLocators.canonical(), SpawnBackend.AUTO);
        assertTrue(anyContains(f.adapter.probeWith(AdapterFixture.FULL_TREE).warnings(), "Carpet"));
    }

    @Test
    void aMissingPlayerspawnCommandMakesTheIntegrationUnavailable() {
        AdapterFixture f = AdapterFixture.healthy();
        Status s = f.adapter.probeWith(new CommandTree(true, true, true, true, false, true, true));
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertTrue(s.summary().contains("playerspawn"));
    }

    // ---------------------------------------------------------------- read-only settings hygiene

    @Test
    void upstreamSettingsProblemsAreWarnedAboutWithoutEverBeingChanged() {
        AdapterFixture f = AdapterFixture.healthy();
        BotSettings.put("botsRelogs", false);
        BotSettings.put("botLeaveOnDeath", false);
        BotSettings.put("checkInterval", 5);
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertTrue(anyContains(s.warnings(), "botsRelogs is OFF"));
        assertTrue(anyContains(s.warnings(), "botLeaveOnDeath is OFF"));
        assertTrue(anyContains(s.warnings(), "checkInterval is 5"));
        assertTrue(anyContains(s.warnings(), "autoTarget is OFF"), "the default counts too");
        assertEquals(Availability.AVAILABLE, s.availability());
        assertEquals(1, f.sink.warn.size());
        assertTrue(Recorder.FORBIDDEN.isEmpty(), "no setter, load or save may ever be called: " + Recorder.FORBIDDEN);
        assertEquals(Boolean.FALSE, readBotsRelogs(), "the value was left exactly as the operator had it");
    }

    private static Boolean readBotsRelogs() {
        return BotSettings.get().isBotsRelogs();
    }

    @Test
    void telemetryThatIsOnIsWarnedAboutAndTheFileIsNeverTouched(@TempDir Path configDir) throws IOException {
        Path file = configDir.resolve(TelemetryProbe.RELATIVE_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"send_anonymous_statistics\": true}");
        long modified = Files.getLastModifiedTime(file).toMillis();

        TestEnvironment env = TestEnvironment.standard();
        env.configDir = configDir;
        AdapterFixture f = AdapterFixture.with(env, TestLocators.canonical(), SpawnBackend.AUTO);
        Status s = f.adapter.probeWith(AdapterFixture.FULL_TREE);

        assertTrue(anyContains(s.warnings(), "anonymous statistics are ON"), s.warnings().toString());
        assertTrue(anyContains(s.warnings(), "NAME of every bot"));
        assertEquals("{\"send_anonymous_statistics\": true}", Files.readString(file));
        assertEquals(modified, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void telemetryThatIsOffProducesNoStatisticsWarning(@TempDir Path configDir) throws IOException {
        Path file = configDir.resolve(TelemetryProbe.RELATIVE_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"send_anonymous_statistics\": false}");
        TestEnvironment env = TestEnvironment.standard();
        env.configDir = configDir;
        AdapterFixture f = AdapterFixture.with(env, TestLocators.canonical(), SpawnBackend.AUTO);
        assertFalse(anyContains(f.adapter.probeWith(AdapterFixture.FULL_TREE).warnings(), "statistics"));
    }

    // ---------------------------------------------------------------- fail soft

    @Test
    void aBrokenModLoaderIsNotAnException() {
        TestEnvironment env = TestEnvironment.standard();
        env.failure = new IllegalStateException("loader exploded");
        AdapterFixture f = AdapterFixture.with(env, TestLocators.canonical(), SpawnBackend.AUTO);
        Status s = assertDoesNotThrow(() -> f.adapter.probeWith(AdapterFixture.FULL_TREE));
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertTrue(f.sink.anyContains("loader exploded"));
    }

    @Test
    void aConfigDirectoryLookupThatThrowsIsCaughtAndReported() {
        TestEnvironment env = TestEnvironment.standard();
        env.configDirFailure = new IllegalStateException("no config dir");
        AdapterFixture f = AdapterFixture.with(env, TestLocators.canonical(), SpawnBackend.AUTO);
        Status s = assertDoesNotThrow(() -> f.adapter.probeWith(AdapterFixture.FULL_TREE));
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertTrue(s.summary().contains("probe itself failed"), s.summary());
        assertTrue(s.summary().contains("no config dir"));
        assertSame(s, f.adapter.status());
    }

    @Test
    void everyClassFailingToLoadIsUnavailableNotAnException() {
        ClassLocator broken = name -> {
            throw new LinkageError("linkage trouble for " + name);
        };
        AdapterFixture f = AdapterFixture.with(TestEnvironment.standard(), broken, SpawnBackend.AUTO);
        Status s = assertDoesNotThrow(() -> f.adapter.probeWith(AdapterFixture.FULL_TREE));
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertTrue(s.summary().contains("LinkageError"), s.summary());
    }

    @Test
    void settingsThatThrowWhileBeingReadStillLeaveAWorkingIntegration() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS,
                org.stepan1411.testdouble.Settings.ThrowingGet.class));
        Status s = assertDoesNotThrow(() -> f.adapter.probeWith(AdapterFixture.FULL_TREE));
        assertEquals(Availability.AVAILABLE, s.availability(), "settings never gate spawning");
        assertTrue(f.sink.anyContains("settings could not be loaded"));
    }

    @Test
    void reProbingPicksUpAPvpBotThatFinishedLoadingLater() {
        TestEnvironment env = TestEnvironment.standard().without(UpstreamNames.MOD_PVP_BOT);
        AdapterFixture f = AdapterFixture.with(env, TestLocators.canonical(), SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, f.adapter.probeWith(AdapterFixture.FULL_TREE).availability());
        env.with(UpstreamNames.MOD_PVP_BOT, "0.0.15");
        assertEquals(Availability.AVAILABLE, f.adapter.probeWith(AdapterFixture.FULL_TREE).availability());
        assertTrue(f.adapter.available());
    }

    @Test
    void probingNeverInitialisesAnUpstreamClass() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.CountsInit.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertFalse(Recorder.CALLS.contains("clinit:Managers.CountsInit"));
    }

    // ---------------------------------------------------------------- spawning.backend

    @Test
    void theBackendSettingReDerivesTheStatusWithoutReProbing() {
        AdapterFixture f = AdapterFixture.probed();
        assertEquals("CLASS(pos)", f.adapter.status().spawnTier());
        int settingsReads = Recorder.callsStartingWith("BotSettings.get").size();

        f.adapter.setSpawnBackend("COMMAND");
        assertEquals("COMMAND", f.adapter.status().spawnTier());
        assertEquals(SpawnBackend.COMMAND, f.adapter.spawnBackend());
        f.adapter.setSpawnBackend("CLASS");
        assertEquals("CLASS(pos)", f.adapter.status().spawnTier());
        f.adapter.setSpawnBackend("nonsense");
        assertEquals(SpawnBackend.AUTO, f.adapter.spawnBackend());

        assertEquals(settingsReads, Recorder.callsStartingWith("BotSettings.get").size(), "no re-probe happened");
    }

    @Test
    void aBackendThatLeavesNoSpawnPathMakesTheStatusUnavailable() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoSpawn.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertEquals("COMMAND", f.adapter.status().spawnTier());
        f.adapter.setSpawnBackend(SpawnBackend.CLASS);
        assertEquals(Availability.UNAVAILABLE, f.adapter.status().availability());
        assertFalse(f.adapter.available());
        f.adapter.setSpawnBackend(SpawnBackend.AUTO);
        assertEquals(Availability.DEGRADED, f.adapter.status().availability());
    }

    @Test
    void aBackendGivenBeforeTheProbeIsHonoured() {
        AdapterFixture f = AdapterFixture.with(TestEnvironment.standard(), TestLocators.canonical(), SpawnBackend.COMMAND);
        assertEquals("COMMAND", f.adapter.probeWith(AdapterFixture.FULL_TREE).spawnTier());
    }

    @Test
    void theReportNamesTheBackendInUse() {
        AdapterFixture f = AdapterFixture.with(TestEnvironment.standard(), TestLocators.canonical(), SpawnBackend.CLASS);
        assertTrue(f.adapter.probeWith(AdapterFixture.FULL_TREE).details().stream()
                .anyMatch(d -> d.contains("spawning.backend=CLASS")));
    }
}
