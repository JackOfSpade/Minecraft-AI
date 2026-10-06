package io.github.zoyluo.minecraftai.baritone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The bounded-memory rule of {@link ObservedNavigationFence}, free of Minecraft types so that it
 * can be exercised without a registry bootstrap.
 */
final class ObservedCellRetention {
    private ObservedCellRetention() {
    }

    /**
     * The {@code limit} most recently seen entries (a larger packed position wins a tie): a
     * saturated snapshot sheds its oldest memory in one pass, however many cells an admission added.
     */
    static <C> List<Map.Entry<Long, C>> freshest(List<Map.Entry<Long, C>> entries,
                                                  ToIntFunction<C> seenTick, int limit) {
        if (entries.size() <= limit) {
            return entries;
        }
        List<Map.Entry<Long, C>> byRecency = new ArrayList<>(entries);
        byRecency.sort(Comparator
                .<Map.Entry<Long, C>>comparingInt(entry -> seenTick.applyAsInt(entry.getValue()))
                .thenComparingLong(Map.Entry::getKey)
                .reversed());
        return new ArrayList<>(byRecency.subList(0, limit));
    }
}
