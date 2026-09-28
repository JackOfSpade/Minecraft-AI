package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.command.AdminFormatter.Before;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl.ProcessOutcome;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.spawnbotswrapper.inhabitants.command.Fixtures.containsStripped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminFormatterTest {
    private static final StructureKey KEY = Fixtures.key("minecraft:pillager_outpost", 4, -7);

    // ---------------------------------------------------------------- process

    @Test
    void processReportsEveryOutcomeKind() {
        var snapshot = Fixtures.snapshot("minecraft:pillager_outpost", 4, -7, true);

        List<String> queued = AdminFormatter.processed(snapshot, ForceMode.OCCUPIED,
                new ProcessOutcome(ProcessOutcome.Kind.OCCUPIED_QUEUED, "3 bots planned"));
        assertEquals("Processing minecraft:pillager_outpost at chunk 4,-7 (mode occupied)", Markup.strip(queued.get(0)));
        assertTrue(containsStripped(queued, "  rolled OCCUPIED - population queued; bots appear over the next ticks - 3 bots planned"),
                Markup.strip(queued).toString());

        assertTrue(containsStripped(AdminFormatter.processed(snapshot, ForceMode.ABANDONED,
                new ProcessOutcome(ProcessOutcome.Kind.ABANDONED, null)), "rolled ABANDONED (permanent: no bots here)"));
        assertTrue(containsStripped(AdminFormatter.processed(snapshot, ForceMode.ROLL,
                new ProcessOutcome(ProcessOutcome.Kind.ALREADY_PROCESSED, "")), "already processed - nothing changed"));
        assertTrue(containsStripped(AdminFormatter.processed(snapshot, ForceMode.ROLL,
                new ProcessOutcome(ProcessOutcome.Kind.REJECTED, "excluded by config")), "REJECTED - excluded by config"));
    }

    @Test
    void onlyAbandonedAndQueuedCountAsSuccess() {
        assertTrue(AdminFormatter.processSucceeded(new ProcessOutcome(ProcessOutcome.Kind.ABANDONED, "")));
        assertTrue(AdminFormatter.processSucceeded(new ProcessOutcome(ProcessOutcome.Kind.OCCUPIED_QUEUED, "")));
        assertFalse(AdminFormatter.processSucceeded(new ProcessOutcome(ProcessOutcome.Kind.ALREADY_PROCESSED, "")));
        assertFalse(AdminFormatter.processSucceeded(new ProcessOutcome(ProcessOutcome.Kind.REJECTED, "")));
    }

    @Test
    void aMissingOutcomeKindIsNotASuccessAndDoesNotCrash() {
        var snapshot = Fixtures.snapshot("minecraft:x", 0, 0, true);
        ProcessOutcome odd = new ProcessOutcome(null, "??");
        assertFalse(AdminFormatter.processSucceeded(odd));
        assertTrue(containsStripped(AdminFormatter.processed(snapshot, null, odd), "no outcome reported"));
        assertTrue(containsStripped(AdminFormatter.processed(snapshot, null, odd), "(mode roll)"));
    }

    @Test
    void noProcessCandidateDistinguishesNothingFoundFromAllAlreadyProcessed() {
        List<String> none = AdminFormatter.noProcessCandidate(16, 0);
        assertTrue(containsStripped(none, "No structure found within 16 chunks."));
        assertTrue(containsStripped(none, "Only structures in loaded chunks are searched"));

        List<String> one = AdminFormatter.noProcessCandidate(16, 1);
        assertTrue(containsStripped(one, "All 1 structure within 16 chunks already has a record."));

        List<String> many = AdminFormatter.noProcessCandidate(16, 4);
        assertTrue(containsStripped(many, "All 4 structures within 16 chunks already have a record."));
        assertTrue(containsStripped(many, "/inhabitants reset nearest"));
    }

    // ---------------------------------------------------------------- reset

    @Test
    void beforeCapturesOnlySpawnedNamesAndSurvivesLaterMutation() {
        StructureRecord r = Fixtures.populated(Fixtures.bot(0, "Inh_A", BotState.SPAWNED),
                Fixtures.bot(1, "Inh_B", BotState.FAILED), Fixtures.bot(2, "Inh_C", BotState.SPAWNED));
        r.plannedBots = 3;
        Before before = Before.of(r);
        r.bots.clear();

        assertEquals(StructureStatus.POPULATED, before.status());
        assertEquals(List.of("Inh_A", "Inh_C"), before.spawnedNames());
        assertEquals(2, before.spawnedBots());
        assertEquals(3, before.plannedBots());
        assertNull(Before.of(null));
    }

    @Test
    void beforeToleratesMissingBotListAndNullNames() {
        StructureRecord r = Fixtures.populated();
        r.bots = null;
        assertEquals(0, Before.of(r).spawnedBots());

        StructureRecord r2 = Fixtures.populated(Fixtures.bot(0, null, BotState.SPAWNED));
        assertEquals(0, Before.of(r2).spawnedBots());
    }

    @Test
    void resetWithRemoveBotsSaysRemovalWasRequestedAndNamesTheBots() {
        Before before = Before.of(Fixtures.populated(Fixtures.bot(0, "Inh_A", BotState.SPAWNED),
                Fixtures.bot(1, "Inh_B", BotState.SPAWNED)));
        List<String> out = AdminFormatter.resetDone(KEY, before, true, false);

        assertEquals("Reset minecraft:pillager_outpost at chunk 4,-7 in minecraft:overworld", Markup.strip(out.get(0)));
        assertTrue(containsStripped(out, "it was POPULATED, 2/2 bots spawned"), Markup.strip(out).toString());
        assertTrue(containsStripped(out, "removal of 2 inhabitants was requested through PvP BOT: Inh_A, Inh_B"),
                Markup.strip(out).toString());
        assertFalse(containsStripped(out, "stay in the world"), Markup.strip(out).toString());
        assertTrue(containsStripped(out, "the record is gone"), Markup.strip(out).toString());
    }

    @Test
    void resetWithoutRemoveBotsWarnsThatInhabitantsStayAndMayBeDuplicated() {
        Before before = Before.of(Fixtures.populated(Fixtures.bot(0, "Inh_A", BotState.SPAWNED)));
        List<String> out = AdminFormatter.resetDone(KEY, before, false, false);
        assertTrue(containsStripped(out, "1 inhabitant stays in the world untracked: Inh_A"), Markup.strip(out).toString());
        assertTrue(containsStripped(out, "add removeBots to remove them"), Markup.strip(out).toString());
        assertFalse(containsStripped(out, "removal of"), Markup.strip(out).toString());
    }

    @Test
    void resetOfAnAbandonedStructureMentionsNoBots() {
        Before before = Before.of(StructureRecord.abandoned());
        List<String> out = AdminFormatter.resetDone(KEY, before, false, false);
        assertTrue(containsStripped(out, "it was ABANDONED"), Markup.strip(out).toString());
        assertFalse(containsStripped(out, "bots spawned"), Markup.strip(out).toString());
        assertFalse(containsStripped(out, "removal of"), Markup.strip(out).toString());
        assertFalse(containsStripped(out, "in the world untracked"), Markup.strip(out).toString());
    }

    @Test
    void deterministicModeExplainsThatTheSameRollReproduces() {
        List<String> on = AdminFormatter.resetDone(KEY, null, false, true);
        assertTrue(containsStripped(on, "Deterministic mode is ON"), Markup.strip(on).toString());
        assertTrue(containsStripped(on, "the same roll reproduces"), Markup.strip(on).toString());

        List<String> off = AdminFormatter.resetDone(KEY, null, false, false);
        assertTrue(containsStripped(off, "Deterministic mode is off: the next roll is random and may differ."),
                Markup.strip(off).toString());
        assertFalse(containsStripped(off, "reproduces"));
    }

    @Test
    void resetListsAtMostEightNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            names.add("Inh_B" + i);
        }
        Before before = new Before(StructureStatus.POPULATED, 12, 12, names);
        List<String> out = AdminFormatter.resetDone(KEY, before, true, false);
        String joined = String.join("\n", Markup.strip(out));
        assertTrue(joined.contains("Inh_B7") && !joined.contains("Inh_B8"), joined);
        assertTrue(joined.contains("and 4 more"), joined);
    }

    @Test
    void resetNothingExplainsHowToFindTheRightIdentity() {
        List<String> out = AdminFormatter.resetNothing(KEY);
        assertTrue(containsStripped(out, "Nothing to reset: minecraft:pillager_outpost at chunk 4,-7 in minecraft:overworld has no record."));
        assertTrue(containsStripped(out, "/inhabitants nearby"));
    }

    // ---------------------------------------------------------------- reload / help

    @Test
    void reloadedWithoutMessagesIsPlainSuccess() {
        assertEquals(List.of("Config reloaded."), Markup.strip(AdminFormatter.reloaded(List.of())));
        assertEquals(List.of("Config reloaded."), Markup.strip(AdminFormatter.reloaded(null)));
    }

    @Test
    void reloadedListsWarningsAndCapsThem() {
        List<String> few = Markup.strip(AdminFormatter.reloaded(List.of("commandPermissionLevel 9 -> 4", "x")));
        assertEquals("Config reloaded with 2 messages:", few.get(0));
        assertEquals("  - commandPermissionLevel 9 -> 4", few.get(1));

        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            many.add("problem " + i);
        }
        List<String> capped = Markup.strip(AdminFormatter.reloaded(many));
        assertEquals("Config reloaded with 50 messages:", capped.get(0));
        assertEquals(1 + 20 + 1, capped.size());
        assertEquals("... and 30 more", capped.get(21));
    }

    @Test
    void helpListsEverySubcommandUnderTheGivenRoot() {
        List<String> help = Markup.strip(AdminFormatter.help("inhabitants"));
        String all = String.join("\n", help);
        for (String syntax : List.of("/inhabitants info", "/inhabitants adapter", "/inhabitants structure here",
                "/inhabitants nearby [radiusChunks]", "/inhabitants process nearest [roll|occupied|abandoned]",
                "/inhabitants reset here|nearest [removeBots]",
                "/inhabitants reset structure <id> <chunkX> <chunkZ> [removeBots]", "/inhabitants profile <bot>",
                "/inhabitants catalog [category]", "/inhabitants reload")) {
            assertTrue(all.contains(syntax), "help must list " + syntax + " but was:\n" + all);
        }
        assertTrue(all.contains("Also available as /pvpbot_inhabitants"), all);
        assertTrue(String.join("\n", Markup.strip(AdminFormatter.help("pvpbot_inhabitants")))
                .contains("Also available as /inhabitants"));
    }
}
