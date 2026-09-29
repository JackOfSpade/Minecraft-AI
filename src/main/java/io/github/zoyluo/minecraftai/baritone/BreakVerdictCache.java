package io.github.zoyluo.minecraftai.baritone;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.world.level.block.Block;

/**
 * The per-block verdict cache of {@link BaritoneBreakPlacePolicy#breakDenialOf}. A verdict is computed from tag membership, and
 * tags are data: they are bound after the mod initialises and are replaced by {@code /reload}, so a verdict filled before that
 * (or before a reload) would be wrong for the rest of the JVM. {@link #invalidate()} therefore drops everything, and is wired to
 * server start and to every tag load ({@code MinecraftAiMod}). It names no Baritone type on purpose: the lifecycle hooks run
 * for every server, and with the legacy navigation engine no Baritone class may be reached from them.
 *
 * <p>Thread safe. A value computed on another thread while an invalidation happens (a search on a worker thread during a
 * reload) is returned to its caller but not kept, so a stale verdict never survives an invalidation.</p>
 */
public final class BreakVerdictCache<K> {
    /** The cache {@link BaritoneBreakPlacePolicy#breakDenialOf} reads. */
    static final BreakVerdictCache<Block> BLOCKS = new BreakVerdictCache<>();

    private final Map<K, String> verdicts = new ConcurrentHashMap<>();
    private final AtomicInteger generation = new AtomicInteger();

    BreakVerdictCache() {
    }

    /** The cached verdict of {@code key}, computed with {@code compute} on a miss. */
    String verdict(K key, Function<K, String> compute) {
        String cached = verdicts.get(key);
        if (cached != null) {
            return cached;
        }
        int started = generation.get();
        String computed = compute.apply(key);
        if (started == generation.get()) {
            verdicts.put(key, computed);
            if (started != generation.get()) {
                verdicts.remove(key, computed); // an invalidation slipped in between: do not keep it
            }
        }
        return computed;
    }

    /** Forgets every verdict of this cache. */
    void clear() {
        generation.incrementAndGet();
        verdicts.clear();
    }

    int size() {
        return verdicts.size();
    }

    /** Forgets every block verdict: called on server start and whenever tags are loaded or reloaded. */
    public static void invalidate() {
        BLOCKS.clear();
    }
}
