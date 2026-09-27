package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Availability;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Status;
import dev.spawnbotswrapper.inhabitants.adapter.SettingsHygiene.SettingsSnapshot;
import dev.spawnbotswrapper.inhabitants.adapter.StatusAssembler.Assembly;
import dev.spawnbotswrapper.inhabitants.adapter.StatusAssembler.ProbeInput;
import dev.spawnbotswrapper.inhabitants.adapter.StatusAssembler.ReportLevel;
import dev.spawnbotswrapper.inhabitants.adapter.TelemetryProbe.Telemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stepan1411.testdouble.Managers;
import org.stepan1411.testdouble.Paths;
import org.stepan1411.testdouble.Settings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How probe findings are classified: what is fatal, what merely degrades, what is only a warning. */
class StatusAssemblerTest {

    private static final CommandTree FULL = AdapterFixture.FULL_TREE;
    private static final SettingsSnapshot HEALTHY = new SettingsSnapshot(true, true, 20, true);
    private static final String HERO_1X = "1.21.11-1.4.3+v260315";

    @BeforeEach
    void fresh() {
        AdapterFixture.resetUpstream();
    }

    private static UpstreamContract contract(ClassLocator locator) {
        return new UpstreamContract(locator);
    }

    private static UpstreamContract healthy() {
        return contract(TestLocators.canonical());
    }

    private static ProbeInput input(UpstreamContract c, CommandTree tree) {
        return new ProbeInput("0.1.0", "0.0.15", HERO_1X, false, c, tree, HEALTHY, Telemetry.DISABLED);
    }

    private static Assembly assemble(UpstreamContract c, CommandTree tree, SpawnBackend backend) {
        return StatusAssembler.assemble(input(c, tree), backend);
    }

    private static boolean anyContains(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    // ---------------------------------------------------------------- available

    @Test
    void theCompleteContractIsAvailableWithTheFullTierChain() {
        Assembly a = assemble(healthy(), FULL, SpawnBackend.AUTO);
        Status s = a.status();
        assertEquals(Availability.AVAILABLE, s.availability());
        assertEquals("CLASS(pos)", s.spawnTier());
        assertEquals(List.of(SpawnTier.CLASS_POS, SpawnTier.CLASS, SpawnTier.COMMAND), a.verdict().tierOrder());
        assertTrue(a.verdict().patrolCapable() && a.verdict().canAdopt() && a.verdict().removeByClass());
        assertTrue(s.warnings().isEmpty(), s.warnings().toString());
        assertEquals(ReportLevel.INFO, a.level());
        assertEquals("0.0.15", s.pvpBotVersion());
        assertEquals(HERO_1X, s.heroBotVersion());
        assertEquals("0.1.0", s.addonVersion());
        assertTrue(s.summary().contains("usable") && s.summary().contains("CLASS(pos)"), s.summary());
        assertTrue(s.usable());
    }

    @Test
    void theDetailsStartWithTheApiCompatibilityResultAndCoverEveryProbe() {
        List<String> details = assemble(healthy(), FULL, SpawnBackend.AUTO).status().details();
        assertTrue(details.get(0).startsWith("API compatibility: COMPATIBLE"), details.get(0));
        assertTrue(anyContains(details, "BotManager: resolved R1 R2 R3 R4 R5 R6 R7 R8 R9"), details.toString());
        assertTrue(anyContains(details, "BotSettings: R10 get() and all 28 getters resolved"));
        assertTrue(anyContains(details, "Paths: BotPath statics resolved, patrols supported"));
        assertTrue(anyContains(details, "Commands: all registered"));
        assertTrue(anyContains(details, "Spawn tier: CLASS(pos) [fallback: CLASS, COMMAND] (spawning.backend=AUTO)"));
        assertTrue(anyContains(details, "Removal: BotManager.removeBot"));
        assertTrue(anyContains(details, "Statistics opt-out (read-only): statistics OFF"));
    }

    @Test
    void identicalInputProducesIdenticalOutput() {
        UpstreamContract c = healthy();
        assertEquals(assemble(c, FULL, SpawnBackend.AUTO).status(), assemble(c, FULL, SpawnBackend.AUTO).status());
    }

    // ---------------------------------------------------------------- degraded

    @Test
    void aMissingPositionOverloadDegradesToTheClassTierAndSaysWhy() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoPos.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.DEGRADED, a.status().availability());
        assertEquals("CLASS", a.status().spawnTier());
        assertTrue(a.status().usable());
        assertTrue(anyContains(a.status().warnings(), "R2 BotManager.spawnBot(MinecraftServer, String, "
                + "ServerCommandSource, Vec3d)"), a.status().warnings().toString());
        assertTrue(anyContains(a.status().warnings(), "spawn tier is CLASS"));
        assertEquals(ReportLevel.WARN, a.level());
        assertTrue(a.status().details().get(0).startsWith("API compatibility: DEGRADED"));
    }

    @Test
    void onlyThePositionOverloadIsDegradedButStillSpawnsAndAdopts() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.OnlyPos.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.DEGRADED, a.status().availability());
        assertEquals("CLASS(pos)", a.status().spawnTier());
        assertTrue(a.verdict().canAdopt());
    }

    @Test
    void onlyTheCommandSpawningIsDegradedAndCannotAdopt() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoSpawn.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.DEGRADED, a.status().availability());
        assertEquals("COMMAND", a.status().spawnTier());
        assertEquals(List.of(SpawnTier.COMMAND), a.verdict().tierOrder());
        assertFalse(a.verdict().canAdopt());
        assertTrue(anyContains(a.status().warnings(), "cannot be re-listed"));
    }

    @Test
    void aMissingRemoveMethodWithTheCommandRegisteredIsDegradedNotFatal() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoRemove.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.DEGRADED, a.status().availability());
        assertFalse(a.verdict().removeByClass());
        assertTrue(a.verdict().removeCommandRegistered());
        assertTrue(anyContains(a.status().warnings(), "removal uses the 'pvpbot remove <name>' command"));
        assertTrue(anyContains(a.status().details(), "Removal: command 'pvpbot remove <name>'"));
    }

    @Test
    void anIncompletePathApiDegradesAndDisablesPatrols() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_PATH, Paths.NoWalkType.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.DEGRADED, a.status().availability());
        assertFalse(a.verdict().patrolCapable());
        assertEquals("CLASS(pos)", a.status().spawnTier(), "spawning is unaffected");
        assertTrue(anyContains(a.status().warnings(), "patrols are disabled"));
        assertTrue(anyContains(a.status().warnings(), "BotPath.setWalkType(String, String)"));
    }

    // ---------------------------------------------------------------- unavailable

    @Test
    void pvpBotNotInstalledIsUnavailableAndNothingWasReflected() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", null, null, false, null, FULL, null,
                Telemetry.UNKNOWN), SpawnBackend.AUTO);
        Status s = a.status();
        assertEquals(Availability.UNAVAILABLE, s.availability());
        assertEquals("not installed", s.pvpBotVersion());
        assertEquals("not installed", s.heroBotVersion());
        assertEquals("NONE", s.spawnTier());
        assertFalse(s.usable());
        assertTrue(s.summary().contains("not installed") && s.summary().contains("pvp_bot"), s.summary());
        assertEquals(ReportLevel.ERROR, a.level());
        assertFalse(a.verdict().usable());
    }

    @Test
    void aMissingBotListIsFatalAndNamesTheMember() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoGetAllBots.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertEquals("NONE", a.status().spawnTier());
        assertTrue(a.status().summary().contains("R3 BotManager.getAllBots()"), a.status().summary());
        assertEquals(ReportLevel.ERROR, a.level());
    }

    @Test
    void aMissingBotCountIsFatalAndNamesTheMember() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoGetBotCount.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertTrue(a.status().summary().contains("R4 BotManager.getBotCount()"), a.status().summary());
    }

    @Test
    void aWrongReturnTypeOfARequiredMemberIsFatalWithTheTypes() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.WrongReturn.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertTrue(a.status().summary().contains("returns List, expected Set"), a.status().summary());
    }

    @Test
    void aNonStaticRequiredMemberIsFatalWhenNoCommandCanReplaceIt() {
        CommandTree noRemoveCommand = new CommandTree(true, true, true, false, true, true, true);
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NonStaticRemove.class)),
                noRemoveCommand, SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertTrue(a.status().summary().contains("R5 BotManager.removeBot"), a.status().summary());
        assertTrue(a.status().summary().contains("is not static"), a.status().summary());
    }

    @Test
    void anAbsentBotManagerClassIsFatalAndNamesTheClass() {
        Assembly a = assemble(contract(TestLocators.missing(UpstreamNames.CLASS_BOT_MANAGER)), FULL, SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertTrue(a.status().summary().contains(UpstreamNames.CLASS_BOT_MANAGER + " not found"), a.status().summary());
    }

    @Test
    void noSpawnMethodAndNoSpawnCommandIsFatal() {
        CommandTree noSpawnCommand = new CommandTree(true, true, false, true, true, true, true);
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoSpawn.class)),
                noSpawnCommand, SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        String summary = a.status().summary();
        assertTrue(summary.contains("no usable spawn path"), summary);
        assertTrue(summary.contains("R1 BotManager.spawnBot(MinecraftServer, String, ServerCommandSource)"), summary);
        assertTrue(summary.contains("R2 BotManager.spawnBot"), summary);
        assertTrue(summary.contains("'pvpbot spawn <name>' is not registered"), summary);
    }

    @Test
    void aMissingPlayerspawnCommandIsFatalBecauseNoBotCanBeCreated() {
        CommandTree noPlayerspawn = new CommandTree(true, true, true, true, false, true, true);
        Assembly a = assemble(healthy(), noPlayerspawn, SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertTrue(a.status().summary().contains("playerspawn"), a.status().summary());
        assertTrue(a.status().summary().contains("HeroBot"), a.status().summary());
    }

    // ---------------------------------------------------------------- warnings that never gate

    @Test
    void aNewerVersionWhoseProbesAllPassIsAvailableWithAnUntestedWarning() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.16", HERO_1X, false, healthy(), FULL,
                HEALTHY, Telemetry.DISABLED), SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability(), "never pinned to an exact version");
        assertTrue(anyContains(a.status().warnings(), "untested version 0.0.16, tested: 0.0.15"),
                a.status().warnings().toString());
        assertEquals(ReportLevel.WARN, a.level());
    }

    @Test
    void anOlderVersionWithTheFullContractIsAvailableWithAnUntestedWarning() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.14", HERO_1X, false, healthy(), FULL,
                HEALTHY, Telemetry.DISABLED), SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertTrue(anyContains(a.status().warnings(), "untested version 0.0.14"));
    }

    @Test
    void aNewerVersionThatBreaksTheContractIsStillUnavailable() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.16", HERO_1X, false,
                contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoGetAllBots.class)), FULL,
                HEALTHY, Telemetry.DISABLED), SpawnBackend.AUTO);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
    }

    @Test
    void carpetIsAWarning() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.15", HERO_1X, true, healthy(), FULL,
                HEALTHY, Telemetry.DISABLED), SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertTrue(anyContains(a.status().warnings(), "Carpet"));
        assertEquals(ReportLevel.WARN, a.level());
    }

    @Test
    void heroBotTwoDotXIsAWarningNotAFailure() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.15", "2.7.2", false, healthy(), FULL,
                HEALTHY, Telemetry.DISABLED), SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertTrue(anyContains(a.status().warnings(), "HeroBot 2.7.2 is a 2.x generation"));
        assertEquals("2.7.2", a.status().heroBotVersion());
    }

    @Test
    void anUninspectableCommandTreeIsAWarningAndDisablesOnlyTheCommandTier() {
        Assembly a = assemble(healthy(), CommandTree.UNKNOWN, SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertEquals(List.of(SpawnTier.CLASS_POS, SpawnTier.CLASS), a.verdict().tierOrder());
        assertTrue(anyContains(a.status().warnings(), "command tree could not be inspected"));
        assertTrue(anyContains(a.status().details(), "Commands: not inspected"));
    }

    @Test
    void aMissingPlayerOrHeroBotCommandIsAWarning() {
        Assembly a = assemble(healthy(), new CommandTree(true, true, true, true, true, false, false), SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertTrue(anyContains(a.status().warnings(), "command 'player' is not registered"));
        assertTrue(anyContains(a.status().warnings(), "command 'herobot' is not registered"));
    }

    // ---------------------------------------------------------------- settings hygiene and settings access

    @Test
    void upstreamSettingsProblemsBecomeWarnings() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.15", HERO_1X, false, healthy(), FULL,
                new SettingsSnapshot(false, false, 4, false), Telemetry.ENABLED_BY_DEFAULT), SpawnBackend.AUTO);
        List<String> w = a.status().warnings();
        assertTrue(anyContains(w, "botsRelogs is OFF"));
        assertTrue(anyContains(w, "botLeaveOnDeath is OFF"));
        assertTrue(anyContains(w, "checkInterval is 4"));
        assertTrue(anyContains(w, "autoTarget is OFF"));
        assertTrue(anyContains(w, "anonymous statistics are ON"));
        assertEquals(Availability.AVAILABLE, a.status().availability(), "settings never gate spawning");
        assertEquals(ReportLevel.WARN, a.level());
    }

    @Test
    void theTwoDocumentedDefaultsAloneKeepTheReportAtInfoLevel() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.15", HERO_1X, false, healthy(), FULL,
                new SettingsSnapshot(true, true, 20, false), Telemetry.ENABLED_BY_DEFAULT), SpawnBackend.AUTO);
        assertEquals(2, a.status().warnings().size());
        assertEquals(ReportLevel.INFO, a.level(), "passive-until-attacked and statistics are notes, not alarms");
    }

    @Test
    void unreadableSettingsAreAWarningAndTheDefaultsAreAssumed() {
        Assembly a = StatusAssembler.assemble(new ProbeInput("0.1.0", "0.0.15", HERO_1X, false,
                contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.NoGet.class)), FULL, null,
                Telemetry.DISABLED), SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability(), "unreadable settings never gate spawning");
        assertTrue(anyContains(a.status().warnings(), "settings cannot be read"));
        assertTrue(anyContains(a.status().details(), "BotSettings: unreadable"));
    }

    @Test
    void missingGettersAreListedInTheDetailsByName() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_SETTINGS, Settings.FewGetters.class)),
                FULL, SpawnBackend.AUTO);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertTrue(anyContains(a.status().details(), "getter(s) missing"), a.status().details().toString());
        assertTrue(anyContains(a.status().details(), "isRangedEnabled"));
    }

    // ---------------------------------------------------------------- backend preference

    @Test
    void theClassBackendOnlyUsesClassTiers() {
        Assembly a = assemble(healthy(), FULL, SpawnBackend.CLASS);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertEquals(List.of(SpawnTier.CLASS_POS, SpawnTier.CLASS), a.verdict().tierOrder());
    }

    @Test
    void theClassBackendWithoutClassSpawnMethodsIsUnavailableAndSaysHowToFixIt() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoSpawn.class)),
                FULL, SpawnBackend.CLASS);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertTrue(a.status().summary().contains("spawning.backend=CLASS"), a.status().summary());
        assertTrue(a.status().summary().contains("set spawning.backend to AUTO or COMMAND"), a.status().summary());
    }

    @Test
    void theCommandBackendUsesTheCommandTierEvenWhenTheClassApiIsComplete() {
        Assembly a = assemble(healthy(), FULL, SpawnBackend.COMMAND);
        assertEquals(Availability.AVAILABLE, a.status().availability());
        assertEquals("COMMAND", a.status().spawnTier());
        assertEquals(List.of(SpawnTier.COMMAND), a.verdict().tierOrder());
        assertTrue(a.verdict().canAdopt(), "the class API is still there for re-listing");
    }

    @Test
    void theCommandBackendWithoutTheCommandIsUnavailableAndSaysHowToFixIt() {
        CommandTree noSpawnCommand = new CommandTree(true, true, false, true, true, true, true);
        Assembly a = assemble(healthy(), noSpawnCommand, SpawnBackend.COMMAND);
        assertEquals(Availability.UNAVAILABLE, a.status().availability());
        assertTrue(a.status().summary().contains("set spawning.backend to AUTO or CLASS"), a.status().summary());
    }

    @Test
    void theCommandBackendWithoutAnyClassSpawnMethodWarnsThatOrphansCannotBeRelisted() {
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.NoSpawn.class)),
                FULL, SpawnBackend.COMMAND);
        assertEquals(Availability.DEGRADED, a.status().availability());
        assertTrue(anyContains(a.status().warnings(), "orphaned bots cannot be re-listed"));
    }

    @Test
    void theCommandBackendStillWarnsWhenOnlyTheRequiredThreeArgOverloadIsMissing() {
        // Regression: R1 (the 3-arg spawnBot) is REQUIRED per the adapter's own contract. Under COMMAND
        // backend it is not used for spawning itself, but it still matters for re-listing an orphaned bot,
        // so its absence must be visible even though R2 (the optional 4-arg overload) survived and used to
        // make canAdopt alone look sufficient with no explicit warning about R1.
        Assembly a = assemble(contract(TestLocators.replacing(UpstreamNames.CLASS_BOT_MANAGER, Managers.OnlyPos.class)),
                FULL, SpawnBackend.COMMAND);
        assertEquals(Availability.DEGRADED, a.status().availability(), a.status().summary());
        assertTrue(anyContains(a.status().warnings(), "spawnBot"), a.status().warnings().toString());
        assertTrue(a.verdict().canAdopt(), "R2 still lets orphans be re-listed");
    }

    @Test
    void aNullBackendMeansAuto() {
        assertEquals(assemble(healthy(), FULL, SpawnBackend.AUTO).status(), assemble(healthy(), FULL, null).status());
    }
}
