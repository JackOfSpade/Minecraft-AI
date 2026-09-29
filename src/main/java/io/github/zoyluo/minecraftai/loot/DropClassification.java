package io.github.zoyluo.minecraftai.loot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, engine-agnostic core of {@link RuntimeDropIndex}: classifies dropped keys across trial
 * runs as deterministic (always dropped) or probabilistic (sometimes dropped), and folds a
 * source's classification into running key -> sources maps. Deliberately has no Minecraft imports
 * (unlike RuntimeDropIndex itself, whose {@code EXCLUDED_SOURCES} field would force-load
 * {@code Blocks} -- and therefore Minecraft's registries -- as soon as the class is touched), so
 * this logic is unit-testable in the ordinary JUnit VM with synthetic stand-ins for Item/Block.
 */
final class DropClassification {
    private DropClassification() {
    }

    /**
     * A dropped key is deterministic when it appears (count > 0) in every trial, probabilistic
     * when it appears in only some trials. A key absent from every trial is not classified at all
     * (never becomes a source for anything).
     */
    static <K> Map<K, Boolean> classify(List<Map<K, Integer>> trials) {
        if (trials.isEmpty()) {
            return Map.of();
        }
        Map<K, Integer> presentCount = new LinkedHashMap<>();
        for (Map<K, Integer> trial : trials) {
            for (Map.Entry<K, Integer> entry : trial.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    presentCount.merge(entry.getKey(), 1, Integer::sum);
                }
            }
        }
        Map<K, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<K, Integer> entry : presentCount.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == trials.size());
        }
        return result;
    }

    /** Folds one source's classification into the running key -> sources maps. */
    static <K, V> void merge(V source, Map<K, Boolean> classification,
                              Map<K, Set<V>> deterministic, Map<K, Set<V>> probabilistic) {
        for (Map.Entry<K, Boolean> entry : classification.entrySet()) {
            Map<K, Set<V>> target = entry.getValue() ? deterministic : probabilistic;
            target.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).add(source);
        }
    }
}
