package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.Availability;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.AuditReport;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;
import dev.spawnbotswrapper.inhabitants.command.FakeServices.RecordingReply;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl.ProcessOutcome;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every subcommand's behaviour against fake services: no Brigadier, no server. */
class CommandActionsTest {
    /** Chunk (6, -2), block (100, 64, -21). */
    private static final Sender AT = new Sender(null, Fixtures.OVERWORLD, 100.5, 64.0, -20.5);

    private final FakeServices fs = new FakeServices();
    private final FakeServices.FakeCatalog catalog = new FakeServices.FakeCatalog();
    private final RecordingReply reply = new RecordingReply();
    private final List<GlobalCapabilities> renderedWith = new ArrayList<>();
    private final AtomicReference<BotProfile> renderedProfile = new AtomicReference<>();

    private CommandActions actions() {
        Backends.ProfileRenderer renderer = (profile, caps) -> {
            renderedProfile.set(profile);
            renderedWith.add(caps);
            return List.of("rendered profile of " + profile.archetype());
        };
        return new CommandActions(fs.services(), new Backends(catalog, renderer, s -> AT));
    }

    private static StructureSnapshot structure(String id, int cx, int cz) {
        return Fixtures.snapshot(id, cx, cz, true);
    }

    // ---------------------------------------------------------------- info / adapter

    @Test
    void infoAnswersFromTheServices() {
        assertEquals(1, actions().info(reply));
        String t = reply.joined();
        assertTrue(t.startsWith("PvP BOT Inhabitants 1.2.3"), t);
        assertTrue(t.contains("118 structures - 70 abandoned"), t);
        assertTrue(t.contains("Integration: AVAILABLE"), t);
    }

    @Test
    void infoWorksWithAnUnavailableAdapterAndWithoutAConfig() {
        fs.adapter.status = Fixtures.unavailableStatus();
        CommandServices noConfig = new CommandServices(() -> null, fs.population, fs.engine, fs.adapter, fs.locator,
                () -> List.of(), "9.9");
        assertEquals(1, new CommandActions(noConfig, new Backends(catalog, (p, c) -> List.of(), s -> AT)).info(reply));
        assertTrue(reply.joined().contains("Integration: UNAVAILABLE"), reply.joined());
        assertTrue(reply.joined().contains("PvP BOT Inhabitants 9.9"), reply.joined());
        assertTrue(reply.joined().contains("Config: enabled"), "an absent config falls back to the defaults");
    }

    @Test
    void adapterReadsTheGlobalSwitchesOnlyWhenPvpBotIsUsable() {
        assertEquals(1, actions().adapter(reply));
        assertEquals(1, fs.adapter.capabilityReads);
        assertTrue(reply.joined().contains("switches OFF"), reply.joined());

        RecordingReply unavailable = new RecordingReply();
        fs.adapter.status = Fixtures.unavailableStatus();
        assertEquals(1, actions().adapter(unavailable));
        assertEquals(1, fs.adapter.capabilityReads, "an unusable adapter is not asked for its switches");
        assertTrue(unavailable.joined().contains("not read (PvP BOT is unusable)"), unavailable.joined());
    }

    @Test
    void adapterFallsBackToUpstreamDefaultsWhenTheSwitchesCannotBeRead() {
        fs.adapter.throwOnCapabilities = true;
        assertEquals(1, actions().adapter(reply));
        assertTrue(reply.joined().contains("switches OFF (read-only, never changed by this addon): autoTarget, spear"),
                reply.joined());

        RecordingReply nullCaps = new RecordingReply();
        fs.adapter.throwOnCapabilities = false;
        fs.adapter.capabilities = null;
        assertEquals(1, actions().adapter(nullCaps));
        assertTrue(nullCaps.joined().contains("autoTarget, spear"), nullCaps.joined());
    }

    // ---------------------------------------------------------------- structure here / nearby

    @Test
    void structureHereWithNothingReturnsZeroAndSearchesAtTheSendersBlock() {
        assertEquals(0, actions().structureHere(AT, reply));
        assertEquals(new BlockPos(100, 64, -21), fs.locator.lastAtPos);
        assertTrue(reply.joined().startsWith("Structures here: none"), reply.joined());
    }

    @Test
    void structureHereJoinsEachStructureWithItsRecordIfAny() {
        StructureSnapshot processed = structure("minecraft:village_plains", 5, -3);
        StructureSnapshot fresh = structure("minecraft:pillager_outpost", 6, -2);
        fs.locator.at = List.of(processed, fresh);
        fs.population.records.put(processed.key(), Fixtures.populated(Fixtures.bot(0, "Inh_A", BotState.SPAWNED)));

        assertEquals(1, actions().structureHere(AT, reply));
        List<String> t = reply.text();
        String all = reply.joined();
        assertTrue(all.contains("Structures here: 2"), all);
        assertTrue(all.indexOf("minecraft:village_plains") < all.indexOf("minecraft:pillager_outpost"),
                "the locator's order (smallest box first) is kept");
        assertTrue(all.contains("status: POPULATED") && all.contains("Inh_A (spawned)"), all);
        assertTrue(all.contains("status: not processed yet"), all);
        assertFalse(t.isEmpty());
    }

    @Test
    void nearbyClampsTheRadiusAndQueriesTheSendersDimensionAndChunk() {
        fs.population.nearbyResult = List.of();
        assertEquals(0, actions().nearby(AT, 500, reply));
        assertEquals(Fixtures.OVERWORLD, fs.population.lastDimension);
        assertEquals(6, fs.population.lastChunkX);
        assertEquals(-2, fs.population.lastChunkZ);
        assertEquals(64, fs.population.lastRadius);
        assertTrue(reply.joined().startsWith("Processed structures within 64 chunks: none"), reply.joined());

        assertEquals(0, actions().nearby(AT, -3, reply));
        assertEquals(1, fs.population.lastRadius);
    }

    @Test
    void nearbyListsWhatThePopulationReturnsInThatOrder() {
        fs.population.records.put(Fixtures.key("minecraft:b", 1, 1), Fixtures.synthesizedAbandoned());
        fs.population.records.put(Fixtures.key("minecraft:a", 2, 2), Fixtures.populated(Fixtures.bot(0, "X", BotState.SPAWNED)));
        assertEquals(1, actions().nearby(AT, 8, reply));
        String t = reply.joined();
        assertTrue(t.contains("within 8 chunks: 2"), t);
        assertTrue(t.indexOf("minecraft:b") < t.indexOf("minecraft:a"), t);
    }

    // ---------------------------------------------------------------- profile

    @Test
    void profileOfAnUnknownBotIsAnErrorNotAnAnswer() {
        assertEquals(0, actions().profile("Nobody", reply));
        assertEquals(1, reply.errors.size());
        assertTrue(reply.errors.get(0).startsWith("No inhabitant named 'Nobody'."), reply.errors.get(0));
        assertTrue(reply.lines.isEmpty());
    }

    private BotRecord addBotWithProfile(String name) {
        BotRecord b = Fixtures.bot(0, name, BotState.SPAWNED);
        b.profile = Fixtures.profile();
        b.profileApplied = true;
        b.profileVersion = 1;
        fs.population.records.put(Fixtures.key("minecraft:pillager_outpost", 4, 7), Fixtures.populated(b));
        return b;
    }

    @Test
    void profileFindsTheBotCaseInsensitivelyAndRendersItsProfileWithTheGlobalSwitches() {
        BotRecord bot = addBotWithProfile("Inh_Steve");
        GlobalCapabilities caps = GlobalCapabilities.allEnabled();
        fs.adapter.capabilities = caps;

        assertEquals(1, actions().profile("iNh_sTeVe", reply));
        assertSame(bot.profile, renderedProfile.get());
        assertEquals(List.of(caps), renderedWith);
        List<String> t = reply.text();
        assertEquals("Inhabitant Inh_Steve", t.get(0));
        assertTrue(t.contains("rendered profile of guard"), t.toString());
        assertTrue(reply.joined().contains("minecraft:pillager_outpost at chunk 4,7"), reply.joined());
    }

    @Test
    void profileFallsBackToUpstreamDefaultsWhenTheSwitchesCannotBeRead() {
        addBotWithProfile("Inh_Steve");
        fs.adapter.throwOnCapabilities = true;
        assertEquals(1, actions().profile("Inh_Steve", reply));
        assertEquals(List.of(GlobalCapabilities.upstreamDefaults()), renderedWith);
    }

    @Test
    void aBotWithoutAProfileIsReportedWithoutRendering() {
        fs.population.records.put(Fixtures.key("minecraft:x", 0, 0),
                Fixtures.occupied(StructureStatus.OCCUPIED_PENDING, 2,
                        Fixtures.bot(0, "Inh_Wait", BotState.PLANNED)));
        assertEquals(1, actions().profile("Inh_Wait", reply));
        assertTrue(reply.joined().contains("No profile yet"), reply.joined());
        assertNull(renderedProfile.get(), "nothing to render");
    }

    @Test
    void aRendererFailureStillAnswers() {
        addBotWithProfile("Inh_Steve");
        CommandActions failing = new CommandActions(fs.services(),
                new Backends(catalog, (p, c) -> {
                    throw new UnsupportedOperationException("skeleton");
                }, s -> AT));
        assertEquals(1, failing.profile("Inh_Steve", reply));
        assertTrue(reply.joined().contains("The profile could not be rendered"), reply.joined());
    }

    // ---------------------------------------------------------------- catalog

    private static List<SettingSpec> specs() {
        return List.of(
                new SettingSpec("moveSpeed", "movespeed", ValueType.DOUBLE, 0.1, 2.0, "1.0",
                        Category.PER_BOT_RANDOMIZABLE, Mechanism.ATTRIBUTE, "vitals", ""),
                new SettingSpec("combat", "combat", ValueType.BOOLEAN, Double.NaN, Double.NaN, "true",
                        Category.GLOBAL_ONLY, Mechanism.NONE, "", ""));
    }

    @Test
    void catalogSummaryAuditsAgainstTheNamesPvpBotReports() {
        catalog.specs = specs();
        fs.adapter.upstreamNames = Set.of("moveSpeed", "combat");

        assertEquals(1, actions().catalog(null, reply));
        assertEquals(Set.of("moveSpeed", "combat"), catalog.lastAuditInput);
        String t = reply.joined();
        assertTrue(t.contains("Setting catalog: 2 PvP BOT settings, audited against PvP BOT 0.0.15"), t);
        assertTrue(t.contains("clean - every upstream setting is classified (2)"), t);
    }

    @Test
    void catalogSummaryShowsDrift() {
        catalog.specs = specs();
        catalog.report = new AuditReport(List.of("brandNew"), List.of());
        assertEquals(1, actions().catalog(null, reply));
        assertTrue(reply.joined().contains("DRIFT"), reply.joined());
        assertTrue(reply.joined().contains("brandNew"), reply.joined());
    }

    @Test
    void catalogSkipsTheAuditWhenPvpBotIsUnavailableOrSilent() {
        catalog.specs = specs();
        fs.adapter.status = Fixtures.unavailableStatus();
        assertEquals(1, actions().catalog(null, reply));
        assertNull(catalog.lastAuditInput);
        assertTrue(reply.joined().contains("Audit: skipped - PvP BOT is unavailable"), reply.joined());

        RecordingReply empty = new RecordingReply();
        fs.adapter.status = Fixtures.availableStatus();
        fs.adapter.upstreamNames = Set.of();
        assertEquals(1, actions().catalog(null, empty));
        assertNull(catalog.lastAuditInput);
        assertTrue(empty.joined().contains("Audit: skipped - PvP BOT reported no settings"), empty.joined());

        RecordingReply throwing = new RecordingReply();
        fs.adapter.throwOnNames = true;
        assertEquals(1, actions().catalog(null, throwing));
        assertTrue(throwing.joined().contains("Audit: skipped - PvP BOT reported no settings"), throwing.joined());
    }

    @Test
    void catalogWithACategoryListsItsSettings() {
        catalog.specs = specs();
        assertEquals(1, actions().catalog("global", reply));
        assertTrue(reply.joined().startsWith("GLOBAL_ONLY: 1 setting"), reply.joined());
        assertTrue(reply.joined().contains("  combat  boolean, default true"), reply.joined());
    }

    @Test
    void anUnknownCategoryIsRejectedBeforeTheCatalogIsEvenRead() {
        FakeServices.FakeCatalog unreadable = new FakeServices.FakeCatalog() {
            @Override
            public List<SettingSpec> all() {
                throw new IllegalStateException("catalog must not be read for a bad category");
            }
        };
        CommandActions a = new CommandActions(fs.services(), new Backends(unreadable, (p, c) -> List.of(), s -> AT));
        assertEquals(0, a.catalog("bogus", reply));
        assertTrue(reply.errors.get(0).startsWith("Unknown category 'bogus'"), reply.errors.toString());
    }

    @Test
    void catalogWithAnUnknownCategoryNamesTheValidOnes() {
        catalog.specs = specs();
        assertEquals(0, actions().catalog("bogus", reply));
        assertEquals(1, reply.errors.size());
        assertEquals("Unknown category 'bogus'. Categories: per_bot_randomizable global_only admin_operational unsupported",
                reply.errors.get(0));
    }

    // ---------------------------------------------------------------- process nearest

    @Test
    void processNearestTakesTheNearestStructureWithoutARecord() {
        StructureSnapshot done = structure("minecraft:done", 6, -2);
        StructureSnapshot first = structure("minecraft:first", 7, -2);
        StructureSnapshot second = structure("minecraft:second", 8, -2);
        fs.locator.near = List.of(done, first, second);
        fs.population.records.put(done.key(), Fixtures.synthesizedAbandoned());

        assertEquals(1, actions().processNearest(AT, ForceMode.OCCUPIED, reply));

        assertEquals(1, fs.engine.processCalls.size());
        assertSame(first, fs.engine.processCalls.get(0).snapshot());
        assertEquals(ForceMode.OCCUPIED, fs.engine.processCalls.get(0).mode());
        assertEquals(CommandArgs.PROCESS_RADIUS, fs.locator.lastNearRadius);
        assertEquals(new BlockPos(100, 64, -21), fs.locator.lastNearPos);
        assertTrue(reply.joined().contains("Processing minecraft:first at chunk 7,-2 (mode occupied)"), reply.joined());
        assertTrue(reply.joined().contains("rolled OCCUPIED"), reply.joined());
    }

    @Test
    void processNearestPassesEveryModeThrough() {
        StructureSnapshot s = structure("minecraft:x", 0, 0);
        fs.locator.near = List.of(s);
        for (ForceMode mode : ForceMode.values()) {
            actions().processNearest(AT, mode, new RecordingReply());
        }
        assertEquals(List.of(ForceMode.values()), fs.engine.processCalls.stream().map(c -> c.mode()).toList());
    }

    @Test
    void processNearestWithNothingInRangeDoesNotTouchTheEngine() {
        fs.locator.near = List.of();
        assertEquals(0, actions().processNearest(AT, ForceMode.ROLL, reply));
        assertTrue(fs.engine.processCalls.isEmpty());
        assertTrue(reply.joined().contains("No structure found within 16 chunks."), reply.joined());
    }

    @Test
    void processNearestWhenEverythingIsAlreadyProcessedSaysSo() {
        StructureSnapshot a = structure("minecraft:a", 0, 0);
        StructureSnapshot b = structure("minecraft:b", 1, 1);
        fs.locator.near = List.of(a, b);
        fs.population.records.put(a.key(), Fixtures.synthesizedAbandoned());
        fs.population.records.put(b.key(), Fixtures.synthesizedAbandoned());
        assertEquals(0, actions().processNearest(AT, ForceMode.ROLL, reply));
        assertTrue(fs.engine.processCalls.isEmpty());
        assertTrue(reply.joined().contains("All 2 structures within 16 chunks already have a record."), reply.joined());
    }

    @Test
    void processNearestReturnCodeFollowsTheOutcome() {
        fs.locator.near = List.of(structure("minecraft:x", 0, 0));
        assertEquals(0, expectProcess(ProcessOutcome.Kind.REJECTED));
        assertEquals(0, expectProcess(ProcessOutcome.Kind.ALREADY_PROCESSED));
        assertEquals(1, expectProcess(ProcessOutcome.Kind.ABANDONED));
        assertEquals(1, expectProcess(ProcessOutcome.Kind.OCCUPIED_QUEUED));
    }

    private int expectProcess(ProcessOutcome.Kind kind) {
        fs.engine.nextOutcome = new ProcessOutcome(kind, "msg");
        return actions().processNearest(AT, ForceMode.ROLL, new RecordingReply());
    }

    // ---------------------------------------------------------------- reset

    @Test
    void resetHereWithoutAnyStructureIsAnError() {
        assertEquals(0, actions().resetHere(AT, false, reply));
        assertTrue(fs.engine.resetCalls.isEmpty());
        assertTrue(reply.errors.get(0).contains("No registered structure contains your position"), reply.errors.toString());
    }

    @Test
    void resetHereWithoutAnyRecordChangesNothing() {
        fs.locator.at = List.of(structure("minecraft:a", 0, 0), structure("minecraft:b", 0, 0));
        assertEquals(0, actions().resetHere(AT, true, reply));
        assertTrue(fs.engine.resetCalls.isEmpty());
        assertTrue(reply.joined().contains("Nothing to reset: minecraft:a, minecraft:b here have no record yet."), reply.joined());
    }

    @Test
    void resetHereResetsTheSmallestStructureWithARecordAndNotesTheOthers() {
        StructureSnapshot noRecord = structure("minecraft:tiny", 6, -2);
        StructureSnapshot small = structure("minecraft:village_plains", 5, -3);
        StructureSnapshot big = structure("minecraft:mansion", 4, -4);
        fs.locator.at = List.of(noRecord, small, big);
        fs.population.records.put(small.key(), Fixtures.populated(Fixtures.bot(0, "Inh_A", BotState.SPAWNED)));
        fs.population.records.put(big.key(), Fixtures.synthesizedAbandoned());

        assertEquals(1, actions().resetHere(AT, true, reply));

        assertEquals(List.of(new FakeServices.FakeEngine.ResetCall(small.key(), true)), fs.engine.resetCalls);
        String t = reply.joined();
        assertTrue(t.contains("Reset minecraft:village_plains at chunk 5,-3"), t);
        assertTrue(t.contains("1 more overlapping structure(s) here have records too"), t);
    }

    @Test
    void resetDescribesTheRecordAsItWasBeforeTheEngineForgotIt() {
        StructureSnapshot s = structure("minecraft:village_plains", 5, -3);
        fs.locator.at = List.of(s);
        fs.population.records.put(s.key(), Fixtures.populated(Fixtures.bot(0, "Inh_A", BotState.SPAWNED),
                Fixtures.bot(1, "Inh_B", BotState.SPAWNED)));
        fs.engine.onReset = key -> fs.population.records.remove(key);

        assertEquals(1, actions().resetHere(AT, true, reply));

        assertTrue(fs.population.records.isEmpty(), "the fake engine did forget the record");
        String t = reply.joined();
        assertTrue(t.contains("it was POPULATED, 2/2 bots spawned"), t);
        assertTrue(t.contains("removal of 2 inhabitants was requested through PvP BOT: Inh_A, Inh_B"), t);
    }

    @Test
    void resetWhenTheEngineHasNothingToForgetReturnsZero() {
        StructureSnapshot s = structure("minecraft:x", 0, 0);
        fs.locator.at = List.of(s);
        fs.population.records.put(s.key(), Fixtures.synthesizedAbandoned());
        fs.engine.resetResult = false;
        assertEquals(0, actions().resetHere(AT, false, reply));
        assertTrue(reply.joined().contains("Nothing to reset"), reply.joined());
    }

    @Test
    void resetNearestUsesTheFirstProcessedStructureWithinTheResetRadius() {
        StructureKey nearest = Fixtures.key("minecraft:village_plains", 5, 5);
        fs.population.nearbyResult = List.of(Fixtures.entry(nearest, Fixtures.synthesizedAbandoned()),
                Fixtures.entry(Fixtures.key("minecraft:other", 9, 9), Fixtures.synthesizedAbandoned()));

        assertEquals(1, actions().resetNearest(AT, false, reply));

        assertEquals(List.of(new FakeServices.FakeEngine.ResetCall(nearest, false)), fs.engine.resetCalls);
        assertEquals(CommandArgs.RESET_RADIUS, fs.population.lastRadius);
        assertEquals(Fixtures.OVERWORLD, fs.population.lastDimension);
        assertEquals(6, fs.population.lastChunkX);
        assertEquals(-2, fs.population.lastChunkZ);
        assertTrue(reply.joined().contains("Reset minecraft:village_plains at chunk 5,5"), reply.joined());
    }

    @Test
    void resetNearestWithNothingNearbyIsAnErrorAndPassesRemoveBots() {
        fs.population.nearbyResult = List.of();
        assertEquals(0, actions().resetNearest(AT, true, reply));
        assertTrue(fs.engine.resetCalls.isEmpty());
        assertTrue(reply.errors.get(0).contains("No processed structure within 16 chunks"), reply.errors.toString());

        fs.population.nearbyResult = List.of(Fixtures.entry(Fixtures.key("minecraft:x", 0, 0),
                Fixtures.synthesizedAbandoned()));
        assertEquals(1, actions().resetNearest(AT, true, new RecordingReply()));
        assertTrue(fs.engine.resetCalls.get(0).removeBots());
    }

    @Test
    void resetStructureBuildsTheKeyFromTheSendersDimensionAndTheArguments() {
        fs.population.records.put(new StructureKey(Fixtures.OVERWORLD, "somemod:tower", -12, 34), Fixtures.synthesizedAbandoned());

        assertEquals(1, actions().resetStructure(AT, "somemod:tower", -12, 34, false, reply));

        assertEquals(List.of(new FakeServices.FakeEngine.ResetCall(
                new StructureKey(Fixtures.OVERWORLD, "somemod:tower", -12, 34), false)), fs.engine.resetCalls);
        assertTrue(reply.joined().contains("Reset somemod:tower at chunk -12,34 in minecraft:overworld"), reply.joined());
    }

    @Test
    void resetStructureForAnUnknownIdentityExplainsInsteadOfFailing() {
        fs.engine.resetResult = false;
        assertEquals(0, actions().resetStructure(AT, "minecraft:village_plains", 1, 2, false, reply));
        assertTrue(reply.joined().contains("Nothing to reset: minecraft:village_plains at chunk 1,2"), reply.joined());
    }

    @Test
    void resetExplainsDeterministicModeAccordingToTheCurrentConfig() {
        fs.config.deterministic.enabled = true;
        assertEquals(1, actions().resetStructure(AT, "minecraft:x", 0, 0, false, reply));
        assertTrue(reply.joined().contains("Deterministic mode is ON"), reply.joined());
        assertTrue(reply.joined().contains("the same roll reproduces"), reply.joined());

        RecordingReply off = new RecordingReply();
        fs.config.deterministic.enabled = false;
        assertEquals(1, actions().resetStructure(AT, "minecraft:x", 0, 0, false, off));
        assertTrue(off.joined().contains("Deterministic mode is off"), off.joined());
    }

    // ---------------------------------------------------------------- reload

    @Test
    void reloadPrintsTheWarningsOrConfirms() {
        assertEquals(1, actions().reload(reply));
        assertEquals(1, fs.reloads);
        assertEquals(List.of("Config reloaded."), reply.text());

        RecordingReply withWarnings = new RecordingReply();
        fs.reloadMessages = List.of("commandPermissionLevel 9 -> 4", "unknown key 'foo'");
        assertEquals(1, actions().reload(withWarnings));
        assertEquals(2, fs.reloads);
        assertEquals(List.of("Config reloaded with 2 messages:", "  - commandPermissionLevel 9 -> 4",
                "  - unknown key 'foo'"), withWarnings.text());
    }

    @Test
    void reloadToleratesANullMessageList() {
        CommandServices nullMessages = new CommandServices(() -> fs.config, fs.population, fs.engine, fs.adapter,
                fs.locator, () -> null, "1");
        assertEquals(1, new CommandActions(nullMessages, new Backends(catalog, (p, c) -> List.of(), s -> AT)).reload(reply));
        assertEquals(List.of("Config reloaded."), reply.text());
    }

    // ---------------------------------------------------------------- failures propagate to the guard

    @Test
    void actionsDoNotSwallowServiceFailures() {
        fs.population.failure = new IllegalStateException("store closed");
        assertThrows(IllegalStateException.class, () -> actions().info(reply));
        assertThrows(IllegalStateException.class, () -> actions().nearby(AT, 8, reply));
        assertThrows(IllegalStateException.class, () -> actions().profile("x", reply));
    }

    @Test
    void theActionsSeeConfigChangesImmediately() {
        InhabitantsConfig c = fs.config;
        c.enabled = false;
        assertEquals(1, actions().info(reply));
        assertTrue(reply.joined().contains("DISABLED"), reply.joined());
        assertEquals(Availability.AVAILABLE, fs.adapter.status().availability());
    }
}
