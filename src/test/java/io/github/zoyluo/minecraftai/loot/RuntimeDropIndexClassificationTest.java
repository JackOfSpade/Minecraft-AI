package io.github.zoyluo.minecraftai.loot;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DropClassification}'s classify/merge core -- {@link RuntimeDropIndex}'s pure
 * logic, split into its own class precisely so it can be loaded without also loading Minecraft's
 * {@code Blocks}/{@code Items} registries -- with synthetic String stand-ins for Item/Block. Runs
 * in the ordinary JUnit VM; the real per-block loot-table evaluation is covered by a GameTest.
 * {@link DropClassification#classify} and {@link DropClassification#merge} are package-private and
 * called directly here (this test lives in the same package), with no reflection needed.
 */
final class RuntimeDropIndexClassificationTest {

    private static Map<String, Integer> trial(Object... kv) {
        Map<String, Integer> trial = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            trial.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return trial;
    }

    @Test
    void itemPresentInEveryTrialIsDeterministic() {
        // "grass_block" always drops exactly 1 "dirt" -- present, count>0, in all 4 trials.
        List<Map<String, Integer>> trials = List.of(
                trial("dirt", 1), trial("dirt", 1), trial("dirt", 1), trial("dirt", 1));

        Map<String, Boolean> classification = DropClassification.classify(trials);

        assertEquals(Map.of("dirt", Boolean.TRUE), classification);
    }

    @Test
    void itemPresentInOnlySomeTrialsIsProbabilistic() {
        // "gravel" drops "flint" only some of the time (~10% in vanilla); here 1 of 4 trials.
        List<Map<String, Integer>> trials = List.of(
                trial("gravel", 1), trial(), trial(), trial());

        Map<String, Boolean> classification = DropClassification.classify(trials);

        assertEquals(Map.of("gravel", Boolean.FALSE), classification);
    }

    @Test
    void itemAbsentFromEveryTrialIsNotClassifiedAtAll() {
        List<Map<String, Integer>> trials = List.of(trial(), trial(), trial());

        Map<String, Boolean> classification = DropClassification.classify(trials);

        assertTrue(classification.isEmpty(), "a never-dropped item must not appear as any kind of source");
    }

    @Test
    void zeroCountEntriesDoNotCountAsPresent() {
        // A trial map may carry an explicit zero (e.g. from a merge); that must read as "absent",
        // matching the real oneTrial() path which only records stacks with count > 0.
        List<Map<String, Integer>> trials = List.of(trial("dirt", 0), trial("dirt", 1), trial("dirt", 1));

        Map<String, Boolean> classification = DropClassification.classify(trials);

        assertEquals(Boolean.FALSE, classification.get("dirt"), "2 of 3 trials -> probabilistic, not deterministic");
    }

    @Test
    void emptyTrialListClassifiesNothing() {
        assertTrue(DropClassification.classify(List.of()).isEmpty());
    }

    @Test
    void mergeRoutesDeterministicAndProbabilisticItemsToSeparateMaps() {
        Map<String, Set<String>> deterministic = new LinkedHashMap<>();
        Map<String, Set<String>> probabilistic = new LinkedHashMap<>();
        Map<String, Boolean> classification = new LinkedHashMap<>();
        classification.put("dirt", true);
        classification.put("flint", false);

        DropClassification.merge("gravel", classification, deterministic, probabilistic);

        assertEquals(Set.of("gravel"), deterministic.get("dirt"));
        assertFalse(deterministic.containsKey("flint"));
        assertEquals(Set.of("gravel"), probabilistic.get("flint"));
        assertFalse(probabilistic.containsKey("dirt"));
    }

    @Test
    void mergeUnionsMultipleSourcesForTheSameItem() {
        // The whole point of the drop index: "dirt" must accumulate every block that deterministically
        // drops it (dirt, grass_block, podzol, mycelium, ...), not just the first one seen.
        Map<String, Set<String>> deterministic = new LinkedHashMap<>();
        Map<String, Set<String>> probabilistic = new LinkedHashMap<>();

        DropClassification.merge("dirt", Map.of("dirt", true), deterministic, probabilistic);
        DropClassification.merge("grass_block", Map.of("dirt", true), deterministic, probabilistic);
        DropClassification.merge("podzol", Map.of("dirt", true), deterministic, probabilistic);

        assertEquals(new LinkedHashSet<>(Set.of("dirt", "grass_block", "podzol")), deterministic.get("dirt"));
    }

    @Test
    void mergeIsSourceAgnosticSoExclusionIsTheCallersResponsibility() {
        // merge() itself has no notion of "excluded" sources (farmland/dirt_path): the real
        // rebuild() skips those blocks before ever calling classifyBlock/merge, keeping the
        // exclusion list in exactly one place. This documents that boundary at the unit level.
        Map<String, Set<String>> deterministic = new LinkedHashMap<>();
        Map<String, Set<String>> probabilistic = new LinkedHashMap<>();

        DropClassification.merge("farmland", Map.of("dirt", true), deterministic, probabilistic);

        assertTrue(deterministic.get("dirt").contains("farmland"),
                "merge() itself does not filter -- callers must skip excluded sources before calling it");
    }
}
