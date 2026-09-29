package io.github.zoyluo.minecraftai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Semantic knowledge base (layer 3 of the three-layer memory model): decontextualized
 * persistent knowledge -- resource points / danger zones / lessons.
 * Knowledge isn't recorded out of thin air: it is **rule-distilled** from the episode
 * stream of {@link EpisodeLog} (deterministic, zero LLM cost):
 *  - Deaths clustering in the same area &gt;=2 times -&gt; danger zone (a single death is
 *    just bad luck, not something to be paranoid about; only a repeat earns a marker);
 *  - Resource discoveries are deduplicated and merged -&gt; resource point (used for planning
 *    "don't dive if there's already ore nearby", so roam can head straight there);
 *  - Goal failures are counted -&gt; lesson; when the same-key goal later succeeds -&gt; the
 *    lesson is cleared.
 * Stored as one JSON file per bot on disk (world/minecraftai/knowledge_&lt;uuid&gt;.json),
 * loaded on restart -- gets smarter the more it's used across sessions.
 */
public final class KnowledgeBase {
    public static final KnowledgeBase INSTANCE = new KnowledgeBase();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int RESOURCE_CAP = 64;
    private static final int DANGER_MERGE_DIST = 16;   // death point within this distance of an existing danger zone's center -> merge in (hits++)
    private static final int DANGER_BASE_RADIUS = 12;

    public record ResourcePoint(String blockId, int x, int y, int z, long learnedTick) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public record DangerZone(int x, int y, int z, int radius, String cause, int hits, long lastTick) {
        public BlockPos center() {
            return new BlockPos(x, y, z);
        }
    }

    public record Lesson(String key, String reason, int count, long lastTick) {
    }

    static final class BotKnowledge {
        List<ResourcePoint> resources = new ArrayList<>();
        List<DangerZone> dangers = new ArrayList<>();
        Map<String, Lesson> lessons = new HashMap<>();
    }

    private final Map<UUID, BotKnowledge> knowledge = new ConcurrentHashMap<>();
    private MinecraftServer server;

    private KnowledgeBase() {
    }

    private BotKnowledge of(UUID botId) {
        return knowledge.computeIfAbsent(botId, this::loadOrEmpty);
    }

    // ==================== Distillation (triggered by EpisodeLog.record) ====================

    public void distill(AIPlayerEntity bot, EpisodeLog.EpisodeEvent event, List<EpisodeLog.EpisodeEvent> all) {
        this.server = bot.level().getServer();
        UUID botId = bot.getUUID();
        BotKnowledge k = of(botId);
        boolean dirty = false;
        switch (event.type()) {
            case DEATH -> dirty = distillDeath(k, event, all);
            case RESOURCE_FOUND -> dirty = distillResource(k, event);
            case GOAL_FAILED -> {
                Lesson old = k.lessons.get(event.detail());
                k.lessons.put(event.detail(), new Lesson(event.detail(),
                        event.detail(), old == null ? 1 : old.count() + 1, event.gameTick()));
                dirty = true;
            }
            case GOAL_DONE -> dirty = k.lessons.remove(event.detail()) != null; // later succeeded -> clear the lesson
            case THREAT -> {
            }
        }
        if (dirty) {
            save(botId, k);
        }
    }

    private boolean distillDeath(BotKnowledge k, EpisodeLog.EpisodeEvent event, List<EpisodeLog.EpisodeEvent> all) {
        BlockPos pos = event.pos();
        // Merge into an existing danger zone: hits++ and expand radius (capped at 32)
        for (int i = 0; i < k.dangers.size(); i++) {
            DangerZone z = k.dangers.get(i);
            if (z.center().closerThan(pos, DANGER_MERGE_DIST)) {
                k.dangers.set(i, new DangerZone(z.x(), z.y(), z.z(),
                        Math.min(32, (z.hits() + 1) * 8 + 4), z.cause(), z.hits() + 1, event.gameTick()));
                return true;
            }
        }
        // Condition for establishing a new zone: another death in history also fell within 16 blocks
        // (a marker is only placed after two deaths in the same area -- one is just bad luck)
        long priorNearby = all.stream()
                .filter(e -> e.type() == EpisodeLog.Type.DEATH && e != event)
                .filter(e -> e.pos().closerThan(pos, DANGER_MERGE_DIST))
                .count();
        if (priorNearby >= 1) {
            k.dangers.add(new DangerZone(pos.getX(), pos.getY(), pos.getZ(),
                    DANGER_BASE_RADIUS, event.detail(), 2, event.gameTick()));
            return true;
        }
        return false;
    }

    private boolean distillResource(BotKnowledge k, EpisodeLog.EpisodeEvent event) {
        for (ResourcePoint r : k.resources) {
            if (r.blockId().equals(event.detail()) && r.pos().closerThan(event.pos(), 8)) {
                return false; // already recorded within 8 blocks for this same resource type -> dedupe
            }
        }
        if (k.resources.size() >= RESOURCE_CAP) {
            k.resources.remove(0); // full -> remove the oldest
        }
        k.resources.add(new ResourcePoint(event.detail(),
                event.pos().getX(), event.pos().getY(), event.pos().getZ(), event.gameTick()));
        return true;
    }

    // ==================== Queries (consumer-facing) ====================

    /** Nearest known resource point (by blockId, skipping ones inside danger zones); if you arrive and it's gone, call invalidateResource. */
    public Optional<ResourcePoint> nearestResource(UUID botId, String blockId, BlockPos from, double maxDist) {
        return nearestResource(botId, blockId, from, maxDist, ignored -> true);
    }

    /** Nearest available known resource point; the caller may temporarily exclude hints already confirmed unreachable in this episode. */
    public Optional<ResourcePoint> nearestResource(UUID botId,
                                                   String blockId,
                                                   BlockPos from,
                                                   double maxDist,
                                                   Predicate<BlockPos> allowed) {
        return of(botId).resources.stream()
                .filter(r -> r.blockId().equals(blockId))
                .filter(r -> !isDanger(botId, r.pos()))
                .filter(r -> r.pos().closerThan(from, maxDist))
                .filter(r -> allowed.test(r.pos()))
                .min(java.util.Comparator.comparingDouble(r -> r.pos().distSqr(from)));
    }

    /** Rich ore zone (P1 consumer-facing): &gt;=minPoints resource points with the same blockId
     * clustered within radius -&gt; returns the cluster center.
     * Runtime clustering (zero new schema): used as a prospecting fallback -- when scanning
     * within 64 blocks turns up no ore, head straight for the rich zone "where ore has
     * always been found before". */
    public Optional<BlockPos> richZoneNear(UUID botId, String blockId, BlockPos from, double maxDist, int minPoints, double radius) {
        List<ResourcePoint> mine = of(botId).resources.stream()
                .filter(r -> r.blockId().equals(blockId))
                .filter(r -> r.pos().closerThan(from, maxDist))
                .toList();
        Optional<BlockPos> best = Optional.empty();
        double bestDist = Double.MAX_VALUE;
        for (ResourcePoint center : mine) {
            long n = mine.stream().filter(r -> r.pos().closerThan(center.pos(), radius)).count();
            if (n >= minPoints && !isDanger(botId, center.pos())) {
                double d = center.pos().distSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = Optional.of(center.pos());
                }
            }
        }
        return best;
    }

    public boolean isDanger(UUID botId, BlockPos pos) {
        for (DangerZone z : of(botId).dangers) {
            if (z.center().closerThan(pos, z.radius())) {
                return true;
            }
        }
        return false;
    }

    /** Test isolation: clears all knowledge for this bot. Confirmed cross-test contamination:
     * resource points from the first 9 mining scenarios caused richZoneNear to steer the
     * geo_rich rich-zone lookup toward an already-mined-out zone (suite run FAILs, solo run
     * PASSes). Real usage never calls this path, so knowledge persists as normal. */
    public void resetFor(UUID botId) {
        BotKnowledge k = of(botId);
        k.resources.clear();
        k.dangers.clear();
        k.lessons.clear();
        save(botId, k);
    }

    public void invalidateResource(UUID botId, BlockPos pos) {
        BotKnowledge k = of(botId);
        if (k.resources.removeIf(r -> r.pos().closerThan(pos, 4))) {
            save(botId, k);
        }
    }

    /** Clears knowledge precisely by resource type, avoiding accidentally deleting ore or other knowledge points next to a tree when it's fully harvested. */
    public void invalidateResource(UUID botId, String blockId, BlockPos pos) {
        BotKnowledge k = of(botId);
        if (k.resources.removeIf(r -> r.blockId().equals(blockId)
                && r.pos().closerThan(pos, 4))) {
            save(botId, k);
        }
    }

    public int resourceCount(UUID botId) {
        return of(botId).resources.size();
    }

    public int dangerCount(UUID botId) {
        return of(botId).dangers.size();
    }

    // ==================== Persistence ====================

    public void attachServer(MinecraftServer server) {
        knowledge.clear();
        this.server = server;
    }

    public void detachServer() {
        knowledge.clear();
        server = null;
    }

    public void forget(UUID botId) {
        knowledge.remove(botId);
    }

    private Path fileFor(UUID botId) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("minecraftai");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir.resolve("knowledge_" + botId + ".json");
    }

    private void save(UUID botId, BotKnowledge k) {
        if (server == null) {
            return;
        }
        try (Writer w = Files.newBufferedWriter(fileFor(botId))) {
            GSON.toJson(k, w);
        } catch (IOException e) {
            BotLog.error("knowledge_save_failed", e, "bot", botId);
        }
    }

    private BotKnowledge loadOrEmpty(UUID botId) {
        if (server == null) {
            return new BotKnowledge();
        }
        Path f = fileFor(botId);
        if (!Files.exists(f)) {
            return new BotKnowledge();
        }
        try (Reader r = Files.newBufferedReader(f)) {
            BotKnowledge k = GSON.fromJson(r, new TypeToken<BotKnowledge>() {
            }.getType());
            return k == null ? new BotKnowledge() : k;
        } catch (IOException | RuntimeException e) {
            BotLog.error("knowledge_load_failed", e, "bot", botId);
            return new BotKnowledge();
        }
    }
}
