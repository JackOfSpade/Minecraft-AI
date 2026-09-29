package dev.spawnbotswrapper.inhabitants.command;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestersTest {

    private static Map.Entry<StructureKey, StructureRecord> entry(String id, int cx, int cz, String... botNames) {
        BotRecord[] bots = new BotRecord[botNames.length];
        for (int i = 0; i < bots.length; i++) {
            bots[i] = Fixtures.bot(i, botNames[i], BotState.SPAWNED);
        }
        return Fixtures.entry(Fixtures.key(id, cx, cz), Fixtures.populated(bots));
    }

    private static List<String> complete(SuggestionProvider<String> provider, String input) {
        try {
            SuggestionsBuilder builder = new SuggestionsBuilder(input, 0);
            return provider.getSuggestions(null, builder).get().getList().stream().map(Suggestion::getText).toList();
        } catch (InterruptedException | ExecutionException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- candidate selection

    @Test
    void botNamesAreDistinctInNearestFirstOrder() {
        List<Map.Entry<StructureKey, StructureRecord>> entries = List.of(
                entry("minecraft:a", 0, 0, "Inh_One", "Inh_Two"),
                entry("minecraft:b", 5, 5, "Inh_Two", "Inh_Three"));
        assertEquals(List.of("Inh_One", "Inh_Two", "Inh_Three"), Suggesters.botNames(entries, 10));
    }

    @Test
    void botNamesRespectTheCapAndSkipNamelessBotsAndMissingLists() {
        List<Map.Entry<StructureKey, StructureRecord>> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            entries.add(entry("minecraft:s" + i, i, 0, "Inh_A" + i));
        }
        assertEquals(5, Suggesters.botNames(entries, 5).size());
        assertEquals("Inh_A0", Suggesters.botNames(entries, 5).get(0));

        StructureRecord noList = Fixtures.populated();
        noList.bots = null;
        StructureRecord nameless = Fixtures.populated(Fixtures.bot(0, null, BotState.SPAWNED),
                Fixtures.bot(1, "  ", BotState.SPAWNED), Fixtures.bot(2, "Inh_Real", BotState.SPAWNED));
        assertEquals(List.of("Inh_Real"), Suggesters.botNames(List.of(
                Fixtures.entry(Fixtures.key("minecraft:x", 0, 0), noList),
                Fixtures.entry(Fixtures.key("minecraft:y", 1, 0), nameless)), 10));
        assertEquals(List.of(), Suggesters.botNames(List.of(), 10));
    }

    @Test
    void chunkXsOfferTheMatchingStructuresAndOtherwiseTheSendersChunk() {
        List<Map.Entry<StructureKey, StructureRecord>> entries = List.of(
                entry("minecraft:village_plains", 12, -5),
                entry("minecraft:pillager_outpost", 30, 30),
                entry("minecraft:village_plains", 40, 7),
                entry("minecraft:village_plains", 12, 9));
        assertEquals(List.of("12", "40"), Suggesters.chunkXs(entries, "minecraft:village_plains", 3));
        assertEquals(List.of("3"), Suggesters.chunkXs(entries, "minecraft:mansion", 3));
        assertEquals(List.of("3"), Suggesters.chunkXs(List.of(), "minecraft:mansion", 3));
    }

    @Test
    void chunkZsNarrowByTheChunkXAlreadyTyped() {
        List<Map.Entry<StructureKey, StructureRecord>> entries = List.of(
                entry("minecraft:village_plains", 12, -5),
                entry("minecraft:village_plains", 40, 7),
                entry("minecraft:village_plains", 12, 9),
                entry("minecraft:pillager_outpost", 12, 100));
        assertEquals(List.of("-5", "9"), Suggesters.chunkZs(entries, "minecraft:village_plains", 12, 0));
        assertEquals(List.of("7"), Suggesters.chunkZs(entries, "minecraft:village_plains", 40, 0));
        assertEquals(List.of("4"), Suggesters.chunkZs(entries, "minecraft:village_plains", 99, 4));
    }

    @Test
    void categoryWordsAreTheFourLowerCaseNames() {
        assertEquals(List.of("per_bot_randomizable", "global_only", "admin_operational", "unsupported"),
                Suggesters.categoryWords());
    }

    // ---------------------------------------------------------------- Brigadier wrapper

    @Test
    void theWrapperFiltersLikeVanillaIncludingAfterUnderscores() {
        SuggestionProvider<String> provider = Suggesters.strings(ctx -> List.of("Inh_Bob", "Inh_Alice", "Zed"));
        assertEquals(List.of("Inh_Alice", "Inh_Bob", "Zed"), complete(provider, ""));
        assertEquals(List.of("Inh_Bob"), complete(provider, "Inh_B"));
        assertEquals(List.of("Inh_Bob"), complete(provider, "bo"), "matches after an underscore, case-insensitively");
        assertEquals(List.of("Zed"), complete(provider, "z"));
        assertEquals(List.of(), complete(provider, "nothing"));
    }

    @Test
    void aFailingCandidateSourceMeansNoSuggestionsNotAnError() {
        SuggestionProvider<String> runtime = Suggesters.strings(ctx -> {
            throw new IllegalStateException("no world");
        });
        assertEquals(List.of(), complete(runtime, ""));

        SuggestionProvider<String> linkage = Suggesters.strings(ctx -> {
            throw new NoClassDefFoundError("gone");
        });
        assertEquals(List.of(), complete(linkage, ""));
    }

    @Test
    void theCategoryProviderOffersTheFourNames() {
        SuggestionProvider<net.minecraft.commands.CommandSourceStack> provider = Suggesters.categories();
        try {
            List<String> all = provider.getSuggestions(null, new SuggestionsBuilder("", 0)).get().getList().stream()
                    .map(Suggestion::getText).toList();
            assertEquals(4, all.size());
            assertTrue(all.contains("global_only"));
            List<String> g = provider.getSuggestions(null, new SuggestionsBuilder("gl", 0)).get().getList().stream()
                    .map(Suggestion::getText).toList();
            assertEquals(List.of("global_only"), g);
        } catch (InterruptedException | ExecutionException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError(e);
        }
    }
}
