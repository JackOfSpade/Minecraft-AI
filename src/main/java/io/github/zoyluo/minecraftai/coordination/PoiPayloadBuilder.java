package io.github.zoyluo.minecraftai.coordination;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistState;
import io.github.zoyluo.minecraftai.mining.assist.PoiDetector;
import io.github.zoyluo.minecraftai.mining.assist.PoiEvidenceWindow;
import io.github.zoyluo.minecraftai.mining.assist.PoiPrompt;
import io.github.zoyluo.minecraftai.mining.assist.PoiRegistry;
import io.github.zoyluo.minecraftai.mining.assist.PoiScorer;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Design 6.6's R4 LLM-payload-building cluster, extracted out of {@link PoiCoordinator}: every method here
 * is a pure function of its own parameters plus the static {@code PoiRegistry}/{@code PoiEvidenceWindow}
 * APIs, touching none of {@code PoiCoordinator}'s own instance fields. Package-private: {@link PoiCoordinator}
 * is still this cluster's only caller.
 */
final class PoiPayloadBuilder {
    private PoiPayloadBuilder() {
    }

    /** Design 6.6's user payload input, gathered from {@code result}/{@code state}/{@code PoiRegistry} --
     * see {@code mining.assist.PoiPrompt}'s class javadoc for the one documented adaptation (bucket names in
     * place of raw block ids; a single aggregate entity line; {@code max_free_up} unavailable). */
    static PoiPrompt.PayloadInput buildPayloadInput(AIPlayerEntity bot, MiningAssistState state,
                                                     PoiDetector.Result result, String dim, BlockPos anchor,
                                                     boolean cavernOnly) {
        PoiScorer.PoiScore score = result.score();
        String activity = TaskManager.INSTANCE.getActive(bot).map(Task::name).orElse("unknown");
        String candidateClass = score.habitationLike() ? "habitation_like" : cavernOnly ? "cavern_only" : "structure";
        List<PoiPrompt.EvidenceItem> evidence = evidenceItemsFor(state, PoiCoordinator.MAX_EVIDENCE_ITEMS_IN_PAYLOAD);
        List<PoiPrompt.EntityItem> entities = result.entitiesCounted() > 0
                ? List.of(new PoiPrompt.EntityItem("observed_entity", result.entitiesCounted()))
                : List.of();
        return new PoiPrompt.PayloadInput(
                dim, anchor.getY(), activity,
                MiningAssistRuntime.config().poi().useOwnBiome() && result.biome() != null && !result.biome().isEmpty()
                        ? result.biome() : null,
                score.t(), score.s(), score.c(), candidateClass,
                evidence, entities,
                score.c(), -1.0D, score.c(),
                nearestEvidenceDistance(bot, state),
                priorPoisFor(bot.getUUID(), dim, anchor));
    }

    /** Evidence lines for the payload: {@link PoiEvidenceWindow}'s structural (non-natural) cells grouped by
     * {@link io.github.zoyluo.minecraftai.mining.assist.PoiBucket} name, most-populous first. */
    static List<PoiPrompt.EvidenceItem> evidenceItemsFor(MiningAssistState state, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (PoiEvidenceWindow.Entry entry : state.poiWindow().structuralEntries()) {
            counts.merge(entry.bucket().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<PoiPrompt.EvidenceItem> items = new ArrayList<>();
        counts.forEach((block, n) -> items.add(new PoiPrompt.EvidenceItem(block, n)));
        items.sort((a, b) -> Integer.compare(b.cells(), a.cells()));
        return items.size() <= limit ? items : items.subList(0, limit);
    }

    /** Just the ids of {@link #evidenceItemsFor}, for {@code PoiCache#keyFor}. */
    static List<String> evidenceIdsFor(MiningAssistState state, int limit) {
        List<String> ids = new ArrayList<>();
        for (PoiPrompt.EvidenceItem item : evidenceItemsFor(state, limit)) {
            ids.add(item.block());
        }
        return ids;
    }

    /** Euclidean distance from the bot's eyes to the nearest evidence cell in {@code state}'s POI window,
     * cell-centre to eye-position; 0 when the window is empty. */
    static double nearestEvidenceDistance(AIPlayerEntity bot, MiningAssistState state) {
        Vec3 eye = bot.getEyePosition();
        double best = Double.POSITIVE_INFINITY;
        for (PoiEvidenceWindow.Entry entry : state.poiWindow().structuralEntries()) {
            BlockPos pos = entry.pos();
            double dx = pos.getX() + 0.5D - eye.x;
            double dy = pos.getY() + 0.5D - eye.y;
            double dz = pos.getZ() + 0.5D - eye.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < best) {
                best = d;
            }
        }
        return Double.isInfinite(best) ? 0.0D : best;
    }

    /** Up to {@code PoiCoordinator.MAX_PRIOR_POIS_IN_PAYLOAD} nearest same-dimension prior sites
     * (newest-recorded first, per {@link PoiRegistry#snapshot}), skipping a still-open CONSULTING entry
     * (design's example only shows a resolved decision). */
    static List<PoiPrompt.PriorPoi> priorPoisFor(UUID botId, String dim, BlockPos anchor) {
        List<PoiPrompt.PriorPoi> result = new ArrayList<>();
        for (PoiRegistry.Entry entry : PoiRegistry.snapshot(botId)) {
            if (result.size() >= PoiCoordinator.MAX_PRIOR_POIS_IN_PAYLOAD) {
                break;
            }
            if (entry.state() == PoiRegistry.State.CONSULTING || !entry.dimensionKey().equals(dim)) {
                continue;
            }
            double dist = Math.sqrt(entry.anchor().distSqr(anchor));
            String decision = entry.state() == PoiRegistry.State.STOPPED ? "stop" : "decline";
            result.add(new PoiPrompt.PriorPoi(entry.label(), dist, decision));
        }
        return result;
    }
}
